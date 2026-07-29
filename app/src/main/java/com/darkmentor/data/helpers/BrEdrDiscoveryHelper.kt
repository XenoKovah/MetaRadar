package com.darkmentor.data.helpers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.darkmentor.domain.model.BleScanDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * BR/EDR (Bluetooth Classic) discovery: a thin wrapper around [BluetoothAdapter.startDiscovery]
 * that surfaces results through the same [BleScannerHelper.ScanListener] callback shape used by
 * the LE scanner, so [com.darkmentor.service.BgScanService] can drive both with one mental
 * model.
 *
 * Single-flight: a second [discover] call while one is in progress refuses with a no-op log
 * (matches LE-scanner behaviour). The system enforces an inquiry cadence of ~12.8s; the
 * `BluetoothAdapter.ACTION_DISCOVERY_FINISHED` broadcast signals completion.
 *
 * BR/EDR addresses are always public (Bluetooth Core Spec); each emitted [BleScanDevice] uses
 * `addressType = 0` and `scanRecordRaw = null` (no advertisement bytes; SDP runs separately).
 * `deviceType` carries Android's raw `BluetoothDevice.DEVICE_TYPE_*` constant so the upstream
 * builder can derive [com.darkmentor.domain.model.Transport] without importing Android types.
 */
class BrEdrDiscoveryHelper(
    private val appContext: Context,
) {

    private val inProgress = AtomicBoolean(false)
    private val batch: MutableMap<String, BleScanDevice> = ConcurrentHashMap()
    // One timestamp per inquiry window, applied to every device found in it. Mirrors
    // BleScannerHelper.currentScanTimeMs so DevicesRepository.getLastBatch (which selects rows
    // tied at max(last_detect_time_ms)) returns the whole inquiry batch instead of just the
    // last-arriving ACTION_FOUND. Without this, BR/EDR devices fall out of `lastBatch` and
    // Connect All never sees them.
    @Volatile private var inquiryStartMs: Long = 0L
    // Set true when ACTION_DISCOVERY_STARTED reaches the receiver after our startDiscovery().
    // We only treat ACTION_DISCOVERY_FINISHED as ours once this flag is set; any FINISHED that
    // arrives earlier is the system finishing the *previous* discovery (e.g. the one we just
    // cancelDiscovery()'d) and would otherwise tear our session down within milliseconds with
    // 0 devices found. Reset to false on cleanup().
    @Volatile private var discoveryActuallyStarted: Boolean = false
    // Counts consecutive inquiries where startDiscovery() returned true but ACTION_DISCOVERY_
    // STARTED never arrived. On Qualcomm stacks (observed on moto g play) this happens when
    // LE scan registrations from a prior, force-killed process instance leak at the system
    // level — they aren't GC'd on linkToDeath as they should be, and they then block our
    // fresh BR/EDR inquiry. Reset to 0 on the next successful STARTED broadcast.
    //
    // No JVM unit-test coverage for the streak/recovery path: it's woven into the
    // BroadcastReceiver lifecycle and a Handler-posted watchdog — both Android-native, and
    // the streak field plus its reset live behind a private receiver instance, so
    // exercising them without spinning up the system would require a sizeable extraction.
    // Track regressions via an instrumented test on a real device (Connect All happy path
    // already covers the recovery side; leak repro needs killing + relaunching the app).
    @Volatile private var consecutiveSilentFailures: Int = 0
    private var scanListener: BleScannerHelper.ScanListener? = null
    private var registeredReceiver: BroadcastReceiver? = null
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable: Runnable = Runnable {
        Timber.tag(TAG).w("BR/EDR inquiry timed out after %dms — synthesizing finish event", INQUIRY_TIMEOUT_MS)
        handleFinished()
    }
    // Watchdog: ACTION_DISCOVERY_STARTED should arrive within ~milliseconds of
    // startDiscovery() returning true. If it doesn't within DISCOVERY_STARTED_DEADLINE_MS,
    // the controller silently dropped our request — surface the failure quickly so the
    // periodic loop retries on a tight cadence instead of stalling for the full 18s
    // INQUIRY_TIMEOUT_MS.
    private val startedWatchdogRunnable: Runnable = Runnable {
        if (discoveryActuallyStarted) return@Runnable
        if (!inProgress.get()) return@Runnable
        consecutiveSilentFailures += 1
        Timber.tag(TAG).w(
            "BR/EDR startDiscovery() silently no-op'd: no ACTION_DISCOVERY_STARTED within %dms (streak=%d). " +
                "Likely OS-side leaked LE scans from a prior process — toggle Bluetooth to recover.",
            DISCOVERY_STARTED_DEADLINE_MS,
            consecutiveSilentFailures,
        )
        val listener = scanListener
        cleanup()
        listener?.onFailure(
            DiscoveryFailure(
                "startDiscovery silently no-op'd (no ACTION_DISCOVERY_STARTED in ${DISCOVERY_STARTED_DEADLINE_MS}ms; streak=$consecutiveSilentFailures)"
            )
        )
    }

    private fun adapter(): BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun isBluetoothEnabled(): Boolean = adapter()?.isEnabled == true

    /**
     * Run a single BR/EDR inquiry. Calls [listener] once with the accumulated devices on
     * normal completion, or with [BleScannerHelper.BluetoothIsNotInitialized] /
     * [DiscoveryFailure] on error. Returns immediately after starting; results arrive on the
     * main thread via the broadcast receiver.
     */
    @SuppressLint("MissingPermission")
    fun discover(listener: BleScannerHelper.ScanListener) {
        if (!isBluetoothEnabled()) {
            listener.onFailure(BleScannerHelper.BluetoothIsNotInitialized())
            return
        }
        if (!inProgress.compareAndSet(false, true)) {
            Timber.tag(TAG).d("BR/EDR discovery already in progress; ignoring concurrent request")
            return
        }
        scanListener = listener
        batch.clear()
        inquiryStartMs = System.currentTimeMillis()
        discoveryActuallyStarted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        discoveryActuallyStarted = true
                        mainHandler.removeCallbacks(startedWatchdogRunnable)
                        if (consecutiveSilentFailures > 0) {
                            Timber.tag(TAG).i(
                                "BR/EDR inquiry recovered after %d silent failures",
                                consecutiveSilentFailures,
                            )
                            consecutiveSilentFailures = 0
                        }
                    }
                    BluetoothDevice.ACTION_FOUND -> handleFound(intent)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Suppress the stray FINISHED that the system fires when our prior
                        // cancelDiscovery() takes effect — it predates our STARTED. The 18s
                        // timeoutRunnable still rescues us if STARTED genuinely never arrives.
                        if (discoveryActuallyStarted) handleFinished()
                        else Timber.tag(TAG).d("Ignoring DISCOVERY_FINISHED before STARTED (stray cancelDiscovery broadcast)")
                    }
                }
            }
        }
        registeredReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // BluetoothDevice.ACTION_FOUND / BluetoothAdapter.ACTION_DISCOVERY_FINISHED are
        // system-protected broadcasts (only system can send them). Empirically, registering
        // with RECEIVER_NOT_EXPORTED on some OEM Android builds causes the system to
        // silently drop these — onReceive never fires, despite startDiscovery() returning
        // true. RECEIVER_EXPORTED is correct for system broadcasts since they originate
        // outside the app's UID.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        val bluetoothAdapter = adapter()
        if (bluetoothAdapter == null) {
            cleanup()
            listener.onFailure(BleScannerHelper.BluetoothIsNotInitialized())
            return
        }
        // The Android docs require cancelDiscovery() before starting another inquiry, even when
        // we don't think one is running — protects against leftover system-side state.
        bluetoothAdapter.cancelDiscovery()
        val started = try {
            bluetoothAdapter.startDiscovery()
        } catch (e: SecurityException) {
            Timber.tag(TAG).e(e, "BLUETOOTH_SCAN denied; cannot run BR/EDR inquiry")
            false
        }
        if (!started) {
            cleanup()
            listener.onFailure(DiscoveryFailure("startDiscovery() returned false"))
        } else {
            Timber.tag(TAG).i("BR/EDR inquiry started")
            // Belt-and-suspenders: in environments where ACTION_DISCOVERY_FINISHED never reaches
            // our receiver (e.g. some OEM Bluetooth stacks, or transient system-state issues),
            // synthesise a finish event after the spec'd inquiry duration plus a small grace
            // window. Without this the periodic-inquiry loop would lock up after the first
            // call because scheduleNextBrEdrInquiry is only invoked from the listener's
            // onSuccess/onFailure handlers.
            mainHandler.postDelayed(timeoutRunnable, INQUIRY_TIMEOUT_MS)
            // STARTED watchdog: catches the silent-no-op case (controller blocked by leaked
            // LE scans) in 3s instead of waiting the full 18s for the synthetic finish.
            mainHandler.postDelayed(startedWatchdogRunnable, DISCOVERY_STARTED_DEADLINE_MS)
        }
    }

    /**
     * Cancel any in-flight inquiry. Safe to call when nothing is running. Does NOT fire the
     * listener — the caller is opting out, not finishing.
     */
    @SuppressLint("MissingPermission")
    fun cancel() {
        if (!inProgress.get()) return
        adapter()?.takeIf { it.state == BluetoothAdapter.STATE_ON }?.cancelDiscovery()
        cleanup()
    }

    @SuppressLint("MissingPermission")
    private fun handleFound(intent: Intent) {
        val device: BluetoothDevice =
            IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            )
                ?: return
        val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, MIN_RSSI).toInt()
        val name: String? = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
        val cls: BluetoothClass? =
            IntentCompat.getParcelableExtra(
                intent,
                BluetoothDevice.EXTRA_CLASS,
                BluetoothClass::class.java,
            )
                ?: device.bluetoothClass

        // Some Android Bluetooth stacks (observed on a B160V / BLU View 5) leak LE-only
        // advertisers into the ACTION_FOUND stream even though startDiscovery() is the
        // BR/EDR-only API. Those leaked entries arrive with device.type == DEVICE_TYPE_LE,
        // i.e. the system has explicitly classified them as LE-only — that is the only
        // signal we can trust to suppress them.
        //
        // We deliberately do NOT filter on UNCATEGORIZED majorDeviceClass: legitimate BR/EDR-
        // discoverable peers on the Motorola moto g (and other Qualcomm-based phones) routinely
        // arrive with majorClass=0x1F00 in the initial inquiry response — Apple iPhone/iPad
        // peers in particular do not carry CoD until SDP returns. Filtering on UNCATEGORIZED
        // dropped every BR/EDR device on this hardware.
        val majorClass = cls?.majorDeviceClass
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            Timber.tag(TAG).d(
                "Skipping leaked LE-only ACTION_FOUND %s type=%d majorClass=0x%04X",
                device.address,
                device.type,
                majorClass ?: 0,
            )
            return
        }

        val scanDevice = BleScanDevice(
            address = device.address,
            name = name,
            // Use the inquiry-start timestamp (not the per-broadcast arrival time) so all devices
            // in this inquiry share a single `last_detect_time_ms`. See [inquiryStartMs] above.
            scanTimeMs = inquiryStartMs,
            // BR/EDR inquiries do not carry advertisement payloads. SDP populates the UUID list
            // separately; until then we leave it empty.
            scanRecordRaw = null,
            rssi = if (rssi == MIN_RSSI.toInt()) null else rssi,
            // BR/EDR addresses are always public per BT Core Spec; bdaddr_rand will be 0 in BTIDES.
            addressType = ADDRESS_TYPE_PUBLIC,
            deviceClass = cls?.deviceClass,
            isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
            // Intentionally left empty. `device.uuids` here returns the system's cached SDP
            // *service-class* UUIDs from a prior bond — but feeding them into BleScanDevice's
            // `serviceUuids` field drops them into `DeviceData.servicesUuids`, which is the
            // LE GATT advertised-services bucket. The DeviceDetails screen then displays
            // them under the "GATT" section, where they very much do not belong (they're
            // BR/EDR Audio Source / AVRCP / MAP / MFi iAP, not LE GATT services). The
            // explicit SDP enumeration path persists these correctly into `sdp_uuids`.
            serviceUuids = emptyList(),
            // BR/EDR devices that responded to inquiry ARE connectable in the only sense
            // Connect-All cares about — we can issue an SDP fetch (and possibly a GATT-over-
            // BR/EDR connect on dual-mode peers). The LE-flavoured "connectable advertisement"
            // bit doesn't translate one-to-one for BR/EDR, but mapping inquiry-respondents to
            // true keeps them in BulkEnumerateGattInteractor's candidate pool, which is the
            // user-visible contract behind Connect All.
            isConnectable = true,
            deviceType = device.type,
        )
        // Coalesce duplicate ACTION_FOUND broadcasts for the same address within one inquiry —
        // the system can fire several as RSSI updates arrive. We fire the incremental listener
        // callback only on the *first* sighting in this window so the UI/DB path runs once per
        // device per inquiry, then the final onSuccess at handleFinished still rolls everything
        // up into a single batch (which DB merge dedups anyway).
        val firstSighting = batch.put(device.address, scanDevice) == null
        if (firstSighting) {
            try {
                scanListener?.onIncrementalDevice(scanDevice)
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "onIncrementalDevice listener threw for ${device.address}")
            }
        }
    }

    private fun handleFinished() {
        if (!inProgress.get()) return // already torn down — ignore late duplicate fire
        mainHandler.removeCallbacks(timeoutRunnable)
        val results = batch.values.toList()
        val listener = scanListener
        cleanup()
        Timber.tag(TAG).i("BR/EDR inquiry finished; ${results.size} devices found")
        listener?.onSuccess(results)
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(startedWatchdogRunnable)
        registeredReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
                .onFailure { Timber.tag(TAG).w(it, "unregisterReceiver failed (already unregistered?)") }
        }
        registeredReceiver = null
        scanListener = null
        discoveryActuallyStarted = false
        inProgress.set(false)
    }

    class DiscoveryFailure(message: String) : RuntimeException(message)

    companion object {
        private const val TAG = "BrEdrDiscoveryHelper"
        private const val MIN_RSSI: Short = Short.MIN_VALUE
        private const val ADDRESS_TYPE_PUBLIC = 0
        // Spec: ~12.8s for the inquiry window itself; allow a 5s grace so the late
        // ACTION_DISCOVERY_FINISHED still wins over our synthesized finish.
        private const val INQUIRY_TIMEOUT_MS: Long = 18_000L
        // ACTION_DISCOVERY_STARTED arrives within milliseconds in the healthy case; 3s is a
        // generous deadline that comfortably tolerates a busy main looper. After that window
        // we treat the silence as the controller having silently dropped startDiscovery().
        private const val DISCOVERY_STARTED_DEADLINE_MS: Long = 3_000L
    }
}
