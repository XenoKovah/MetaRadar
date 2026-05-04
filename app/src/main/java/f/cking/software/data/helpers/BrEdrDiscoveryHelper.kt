package f.cking.software.data.helpers

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
import f.cking.software.domain.model.BleScanDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * BR/EDR (Bluetooth Classic) discovery: a thin wrapper around [BluetoothAdapter.startDiscovery]
 * that surfaces results through the same [BleScannerHelper.ScanListener] callback shape used by
 * the LE scanner, so [f.cking.software.service.BgScanService] can drive both with one mental
 * model.
 *
 * Single-flight: a second [discover] call while one is in progress refuses with a no-op log
 * (matches LE-scanner behaviour). The system enforces an inquiry cadence of ~12.8s; the
 * `BluetoothAdapter.ACTION_DISCOVERY_FINISHED` broadcast signals completion.
 *
 * BR/EDR addresses are always public (Bluetooth Core Spec); each emitted [BleScanDevice] uses
 * `addressType = 0` and `scanRecordRaw = null` (no advertisement bytes; SDP runs separately).
 * `deviceType` carries Android's raw `BluetoothDevice.DEVICE_TYPE_*` constant so the upstream
 * builder can derive [f.cking.software.domain.model.Transport] without importing Android types.
 */
class BrEdrDiscoveryHelper(
    private val appContext: Context,
) {

    private val inProgress = AtomicBoolean(false)
    private val batch: MutableMap<String, BleScanDevice> = ConcurrentHashMap()
    private var scanListener: BleScannerHelper.ScanListener? = null
    private var registeredReceiver: BroadcastReceiver? = null
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable: Runnable = Runnable {
        Timber.tag(TAG).w("BR/EDR inquiry timed out after %dms — synthesizing finish event", INQUIRY_TIMEOUT_MS)
        handleFinished()
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

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> handleFound(intent)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> handleFinished()
                }
            }
        }
        registeredReceiver = receiver

        val filter = IntentFilter().apply {
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
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
        val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, MIN_RSSI).toInt()
        val name: String? = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
        val cls: BluetoothClass? =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                ?: device.bluetoothClass

        val now = System.currentTimeMillis()
        val scanDevice = BleScanDevice(
            address = device.address,
            name = name,
            scanTimeMs = now,
            // BR/EDR inquiries do not carry advertisement payloads. SDP populates the UUID list
            // separately; until then we leave it empty.
            scanRecordRaw = null,
            rssi = if (rssi == MIN_RSSI.toInt()) null else rssi,
            // BR/EDR addresses are always public per BT Core Spec; bdaddr_rand will be 0 in BTIDES.
            addressType = ADDRESS_TYPE_PUBLIC,
            deviceClass = cls?.deviceClass,
            isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
            serviceUuids = device.uuids?.map { it.uuid.toString() }.orEmpty(),
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
        // the system can fire several as RSSI updates arrive.
        batch[device.address] = scanDevice
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
        registeredReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
                .onFailure { Timber.tag(TAG).w(it, "unregisterReceiver failed (already unregistered?)") }
        }
        registeredReceiver = null
        scanListener = null
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
    }
}
