package f.cking.software.data.helpers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives SDP service-class discovery for BR/EDR (and dual-mode) peers via Android's
 * `BluetoothDevice.fetchUuidsWithSdp()`. Android only exposes the parsed `ParcelUuid[]`
 * via the [BluetoothDevice.ACTION_UUID] broadcast — never raw L2CAP bytes — which is why
 * BTIDES records are *synthesized* downstream by `BTIDESSdpBuilder`.
 *
 * One process-wide receiver demultiplexes broadcasts to the per-address pending fetches.
 * A bounded [Semaphore] caps concurrent fetches at [MAX_CONCURRENT_FETCHES] because Android's
 * bond-state machine and SDP socket pool can serialize aggressive fan-out.
 *
 * Caveat: real-world SDP discovery typically returns useful UUIDs only for already-bonded
 * devices or peers actively in pairing/discoverable mode. Drive-by inquiries against an
 * unbonded peripheral often return cached state (or nothing) — surface this in the UI.
 */
class SdpEnumerationHelper(
    private val appContext: Context,
) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<List<ParcelUuid>>>()
    private val semaphore = Semaphore(MAX_CONCURRENT_FETCHES)
    private val receiverRegistered = AtomicBoolean(false)

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val device: BluetoothDevice = intent
                .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            val rawUuids: List<ParcelUuid> = intent
                .getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                ?.toList()
                .orEmpty()
            val key = device.address.uppercase()
            val deferred = pending.remove(key) ?: return
            // Some Android builds re-deliver this action when the system caches change; if a
            // duplicate broadcast arrives after we've completed, the second remove() returns
            // null and we ignore it.
            deferred.complete(rawUuids)
        }
    }

    /** Register the broadcast receiver. Call once from BgScanService.onCreate(). */
    fun ensureReceiverRegistered() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        // BluetoothDevice.ACTION_UUID is a system-protected broadcast. RECEIVER_EXPORTED is
        // correct because the system (not another app) is the sender — see same reasoning in
        // BrEdrDiscoveryHelper.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Timber.tag(TAG).d("ACTION_UUID receiver registered")
    }

    /** Symmetric to [ensureReceiverRegistered]; call from BgScanService.onDestroy(). */
    fun release() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        runCatching { appContext.unregisterReceiver(receiver) }
            .onFailure { Timber.tag(TAG).w(it, "unregisterReceiver failed (already gone?)") }
        // Fail any callers still waiting on a fetch we'll never complete.
        for ((_, deferred) in pending) deferred.complete(emptyList())
        pending.clear()
    }

    /**
     * Run an SDP service-class enumeration against [address]. Returns the union of (a) UUIDs
     * Android already has cached on the device (synchronous read of `device.uuids`) and
     * (b) UUIDs delivered via [BluetoothDevice.ACTION_UUID] within [TIMEOUT_MS]. Throws
     * [BleScannerHelper.BluetoothIsNotInitialized] if the adapter isn't available.
     * On timeout, returns whatever was cached (often empty for unbonded peers).
     *
     * Returns [UUID] objects so callers can pass directly to BTIDESSdpBuilder; map to
     * `.toString().lowercase()` when persisting in the `service_uuids`/`sdp_uuids` text format.
     */
    @SuppressLint("MissingPermission")
    suspend fun enumerate(address: String): List<UUID> {
        ensureReceiverRegistered()
        val key = address.uppercase()
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BleScannerHelper.BluetoothIsNotInitialized()

        return semaphore.withPermit {
            val device = adapter.getRemoteDevice(address)
            // Seed with the cached SDP UUIDs Android already has (from prior pairing or a
            // previous fetch). If the broadcast never fires, this is what the caller sees.
            val cached: List<ParcelUuid> = device.uuids?.toList().orEmpty()

            val deferred = CompletableDeferred<List<ParcelUuid>>()
            // If a prior fetch for this same address is still in flight, replace it: ours
            // becomes the active one, theirs gets a synthetic empty completion so it doesn't
            // hang forever.
            pending.put(key, deferred)?.complete(emptyList())

            val started = try {
                device.fetchUuidsWithSdp()
            } catch (e: SecurityException) {
                Timber.tag(TAG).e(e, "BLUETOOTH_CONNECT denied; cannot fetch SDP UUIDs")
                pending.remove(key)
                return@withPermit canonicalize(cached)
            }
            if (!started) {
                pending.remove(key)
                return@withPermit canonicalize(cached)
            }

            val fresh = try {
                withTimeout(TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pending.remove(key)
                Timber.tag(TAG).d("SDP fetch for %s timed out at %dms", address, TIMEOUT_MS)
                emptyList()
            }
            canonicalize((cached + fresh).distinct())
        }
    }

    private fun canonicalize(uuids: List<ParcelUuid>): List<UUID> {
        // Dedup while preserving insertion order so the cached entries are listed before fresh
        // ones (UI tends to show the first few).
        val seen = LinkedHashSet<UUID>()
        for (u in uuids) seen.add(u.uuid)
        return seen.toList()
    }

    companion object {
        private const val TAG = "SdpEnumerationHelper"
        private const val TIMEOUT_MS = 12_000L
        private const val MAX_CONCURRENT_FETCHES = 4
    }
}
