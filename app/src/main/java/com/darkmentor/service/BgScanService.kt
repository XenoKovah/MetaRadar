package com.darkmentor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.darkmentor.R
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.helpers.BleScannerHelper
import com.darkmentor.data.helpers.BrEdrDiscoveryHelper
import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.data.helpers.SdpEnumerationHelper
import com.darkmentor.data.helpers.NotificationsHelper
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.data.helpers.PowerModeHelper
import com.darkmentor.domain.interactor.SaveOrMergeBatchInteractor
import com.darkmentor.domain.interactor.SaveReportInteractor
import com.darkmentor.domain.model.BleScanDevice
import com.darkmentor.domain.model.JournalEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger


class BgScanService : Service() {

    private val permissionHelper: PermissionHelper by inject()
    private val bleScannerHelper: BleScannerHelper by inject()
    private val brEdrDiscoveryHelper: BrEdrDiscoveryHelper by inject()
    private val sdpEnumerationHelper: SdpEnumerationHelper by inject()
    private val btidesRepository: BTIDESRepository by inject()
    private val locationProvider: LocationProvider by inject()
    private val notificationsHelper: NotificationsHelper by inject()
    private val powerModeHelper: PowerModeHelper by inject()

    private val saveOrMergeBatchInteractor: SaveOrMergeBatchInteractor by inject()
    private val saveReportInteractor: SaveReportInteractor by inject()
    private val settingsRepository: SettingsRepository by inject()

    private val handler = Handler(Looper.getMainLooper())
    private var failureScanCounter: AtomicInteger = AtomicInteger(0)
    private var locationDisabledWasReported: Boolean = false
    private var bluetoothDisabledWasReported: Boolean = false
    private var backgroundLocationRestrictedWasReported: Boolean = false
    private var observeScreenBrightnessJob: Job? = null
    private val nextScanRunnable = Runnable {
        scan()
    }

    /**
     * BR/EDR inquiry runs on its own slower cadence — driven by [scheduleNextBrEdrInquiry]
     * with [BR_EDR_INTERVAL_NORMAL_MS] / [BR_EDR_INTERVAL_LOW_POWER_MS]. Decoupled from the
     * LE rhythm so toggling either transport doesn't disturb the other.
     */
    private val nextBrEdrInquiryRunnable = Runnable {
        runBrEdrInquiry()
    }

    private val bleListener = object : BleScannerHelper.ScanListener {
        override fun onFailure(exception: Exception) {
            handleError(exception)
        }

        override fun onSuccess(batch: List<BleScanDevice>) {
            handleScanResult(batch)
        }
    }

    private val brEdrListener = object : BleScannerHelper.ScanListener {
        override fun onFailure(exception: Exception) {
            // BR/EDR errors should not tear down the service — the LE side may still be working
            // fine, and inquiry frequently fails benignly (system busy, BT off-then-on, etc.).
            // Log and reschedule the next inquiry; do not call handleError(), which would
            // increment the LE failure counter.
            Timber.w(exception, "BR/EDR inquiry failed")
            // When startDiscovery silently no-ops (the watchdog-detected case), nudge the
            // system BLE scanner state to drop any LE-scan registrations from this UID that
            // the OS didn't reclaim — that's the typical block on Qualcomm stacks.
            val msg = exception.message.orEmpty()
            if (msg.contains("silently no-op'd")) {
                bleScannerHelper.flushLeakedScans()
            }
            scheduleNextBrEdrInquiry()
        }

        override fun onSuccess(batch: List<BleScanDevice>) {
            handleBrEdrInquiryResult(batch)
        }

        override fun onIncrementalDevice(device: BleScanDevice) {
            // Push each ACTION_FOUND through the persist + BTIDES path immediately so the
            // Devices list shows BTC peers as the inquiry sees them, instead of waiting for
            // ACTION_DISCOVERY_FINISHED ~13s later. The closing onSuccess(batch) still rolls
            // up the full inquiry; DB merge handles the dedup.
            scope.launch { persistBrEdrDevice(device) }
        }
    }

    // Dispatcher addition isn't composition — `Main + IO + Default` was a no-op pattern that
    // silently pinned all coroutines to whichever dispatcher came last. The work this scope runs
    // (batch persistence, notification rebuilds) is CPU-bound, so Default is correct; suspending
    // calls inside use their own withContext(IO) to switch when needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        observeScreenBrightnessJob = powerModeHelper.observeScreenBrightnessMode()
        // ACTION_UUID broadcasts arrive on the system's schedule, not ours — register the
        // receiver once for the service's lifetime so any concurrent SDP fetch can land
        // its result without racing the screen lifecycle.
        sdpEnumerationHelper.ensureReceiverRegistered()
        updateState(ScannerState.IDLING)
    }

    private fun handleError(exception: Throwable) {
        reportError(exception)

        if (failureScanCounter.incrementAndGet() >= MAX_FAILURE_SCANS_TO_CLOSE) {
            reportError(RuntimeException("Ble Scan service has been stopped after $MAX_FAILURE_SCANS_TO_CLOSE errors"))
            stopSelf()
        } else {
            scheduleNextScan()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent != null && intent.action == ACTION_STOP_SERVICE) {
            Timber.d("Background service close action handled")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (intent != null && intent.action == ACTION_SCAN_NOW) {
            Timber.d("Background service scan now command")
            scan()
            // The user explicitly asked for a scan — don't make them wait up to 60s for the
            // next BR/EDR inquiry to fire on its own cadence. Cancel any pending runnable and
            // re-post at +0 so the inquiry kicks off right now. The runnable's own gates
            // (toggle / Bluetooth-on / open-GATT-connection) still apply.
            handler.removeCallbacks(nextBrEdrInquiryRunnable)
            handler.post(nextBrEdrInquiryRunnable)
        } else {
            Timber.d("Background service launched")
            try {
                startForeground(
                    NotificationsHelper.FOREGROUND_NOTIFICATION_ID,
                    notificationsHelper.buildForegroundNotification(
                        NotificationsHelper.ServiceNotificationContent.NoDataYet,
                        createCloseServiceIntent(this)
                    ),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } catch (e: Exception) {
                reportError(e)
                Toast.makeText(this, R.string.unable_to_run_service_erro_toast, Toast.LENGTH_LONG).show()
                stopSelf()
            }

            permissionHelper.checkOrRequestPermission(
                onRequestPermissions = { _, _, _ ->
                    reportError(IllegalStateException("BLE Service is started but permissins are not granted"))
                    stopSelf()
                },
                onPermissionGranted = {
                    locationProvider.startLocationFetching()
                    scan()
                }
            )
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("Background service destroyed")
        scope.cancel()
        observeScreenBrightnessJob?.cancel()
        updateState(ScannerState.DISABLED)
        bleScannerHelper.stopScanning()
        brEdrDiscoveryHelper.cancel()
        sdpEnumerationHelper.release()
        locationProvider.stopLocationListening()
        handler.removeCallbacks(nextScanRunnable)
        handler.removeCallbacks(nextBrEdrInquiryRunnable)
        brEdrInquiryLoopStarted = false
        notificationsHelper.cancel(NotificationsHelper.FOREGROUND_NOTIFICATION_ID)
        // Reset the persisted scan-start mode so on next app open the cleanup check sees NONE
        // instead of carrying over a stale CONNECT_ALL_AUTO/USER_EXPLICIT label.
        settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.NONE)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun scan() {
        // First call after onCreate — kick the BR/EDR inquiry loop too. Idempotent: subsequent
        // calls re-enter the LE-scan path normally; the inquiry handler reschedules itself.
        // The user-tap ("Scan now") path additionally kicks an immediate BR/EDR inquiry — see
        // [scanNow] / the ACTION_SCAN_NOW branch in [onStartCommand].
        ensureBrEdrInquiryLoopStarted()

        // Honor the user's "Discover BLE" toggle. Skipping with scheduleNextScan() keeps the
        // LE cadence intact so re-enabling just rejoins the rhythm without restarting the
        // service.
        if (!settingsRepository.getDiscoverLeEnabled()) {
            updateState(ScannerState.IDLING)
            scheduleNextScan()
            return
        }
        scope.launch {
            try {
                updateState(ScannerState.SCANNING)
                bleScannerHelper.scan(scanListener = bleListener)
            } catch (e: BleScannerHelper.BluetoothIsNotInitialized) {
                handleBleIsTurnedOffError()
                notificationsHelper.updateNotification(
                    NotificationsHelper.ServiceNotificationContent.BluetoothIsTurnedOff,
                    createCloseServiceIntent(this@BgScanService)
                )
                scheduleNextScan()
            } catch (e: Throwable) {
                reportError(e)
                stopSelf()
            }
        }
    }

    /**
     * Run a single BR/EDR inquiry if the user's toggle is on. Inquiry takes ~12.8s and runs
     * concurrently with LE scanning (the radio multiplexes them at the controller level).
     * Skips the cycle when a GATT connection is currently open: cancelDiscovery / inquiry
     * during an active link can degrade throughput and induce link supervision timeouts.
     */
    private fun runBrEdrInquiry() {
        Timber.i("runBrEdrInquiry: enabled=${settingsRepository.getDiscoverBrEdrEnabled()} btOn=${brEdrDiscoveryHelper.isBluetoothEnabled()} hasGatt=${bleScannerHelper.hasOpenGattConnections()}")
        if (!settingsRepository.getDiscoverBrEdrEnabled()) {
            scheduleNextBrEdrInquiry()
            return
        }
        if (bleScannerHelper.hasOpenGattConnections()) {
            // Defer one cycle so we don't disturb an in-flight enumeration.
            Timber.i("Deferring BR/EDR inquiry — open GATT connection in progress")
            scheduleNextBrEdrInquiry()
            return
        }
        if (!brEdrDiscoveryHelper.isBluetoothEnabled()) {
            scheduleNextBrEdrInquiry()
            return
        }
        try {
            brEdrDiscoveryHelper.discover(brEdrListener)
        } catch (e: Throwable) {
            Timber.w(e, "Failed to start BR/EDR inquiry")
            scheduleNextBrEdrInquiry()
        }
    }

    private fun handleBrEdrInquiryResult(batch: List<BleScanDevice>) {
        scope.launch {
            if (batch.isNotEmpty()) {
                try {
                    saveOrMergeBatchInteractor.execute(batch)
                } catch (e: Throwable) {
                    Timber.w(e, "Failed to persist BR/EDR inquiry batch (${batch.size} devices)")
                }
                for (device in batch) {
                    appendBrEdrBtidesRecords(device)
                }
            }
            scheduleNextBrEdrInquiry()
        }
    }

    /**
     * Persist a single BR/EDR device discovered mid-inquiry. Same code path as
     * [handleBrEdrInquiryResult] but for one device, so the UI can show it the moment
     * ACTION_FOUND arrives. Idempotent against the closing batch — the DB merge dedups.
     */
    private suspend fun persistBrEdrDevice(device: BleScanDevice) {
        try {
            saveOrMergeBatchInteractor.execute(listOf(device))
        } catch (e: Throwable) {
            Timber.w(e, "Failed to persist incremental BR/EDR device ${device.address}")
        }
        appendBrEdrBtidesRecords(device)
    }

    /**
     * Capture the Class-of-Device byte and (when available) the Remote Name as BTIDES sidecar
     * records. EIR ClassOfDevice is the only BR/EDR-specific schema record we can populate
     * from the high-level Android API today (PageScanRepetitionMode is not exposed). One
     * record per device per inquiry — DeviceAccumulator dedups by `type` on export. The
     * Remote Name lands in HCIArray as a Remote_Name_Request_Complete record because the
     * EIRArray doesn't define a local-name variant and the AdvData CompleteLocalName types
     * are LE-flavoured.
     */
    private suspend fun appendBrEdrBtidesRecords(device: BleScanDevice) {
        val cod = device.deviceClass
        if (cod != null) {
            try {
                btidesRepository.appendEirClassOfDevice(
                    bdaddr = device.address,
                    cod = cod,
                    timestampMs = device.scanTimeMs,
                )
            } catch (e: Throwable) {
                Timber.w(e, "Failed to write BTIDES EIR record for ${device.address}")
            }
        }
        val remoteName = device.name
        if (!remoteName.isNullOrEmpty()) {
            try {
                btidesRepository.appendHciRemoteName(
                    bdaddr = device.address,
                    name = remoteName,
                    timestampMs = device.scanTimeMs,
                )
            } catch (e: Throwable) {
                Timber.w(e, "Failed to write BTIDES HCI record for ${device.address}")
            }
        }
    }

    private var brEdrInquiryLoopStarted: Boolean = false
    private fun ensureBrEdrInquiryLoopStarted() {
        if (brEdrInquiryLoopStarted) return
        brEdrInquiryLoopStarted = true
        // Fire the first inquiry shortly after the service starts, then it self-reschedules.
        handler.postDelayed(nextBrEdrInquiryRunnable, BR_EDR_FIRST_INQUIRY_DELAY_MS)
    }

    private fun scheduleNextBrEdrInquiry() {
        val interval = if (powerModeHelper.powerMode().useRestrictedBleConfig) {
            BR_EDR_INTERVAL_LOW_POWER_MS
        } else {
            BR_EDR_INTERVAL_NORMAL_MS
        }
        handler.removeCallbacks(nextBrEdrInquiryRunnable)
        handler.postDelayed(nextBrEdrInquiryRunnable, interval)
    }

    private fun handleScanResult(batch: List<BleScanDevice>) {
        scope.launch {
            val notificationContent: NotificationsHelper.ServiceNotificationContent = if (batch.isNotEmpty()) {
                handleNonEmptyBatch(batch)
            } else {
                handleEmptyBatch()
            }

            notificationsHelper.updateNotification(notificationContent, createCloseServiceIntent(this@BgScanService))

            scheduleNextScan()
        }
    }

    private fun handleEmptyBatch(): NotificationsHelper.ServiceNotificationContent {
        return when {
            !locationProvider.isLocationAvailable() -> handleLocationDisabled()
            !bleScannerHelper.isBluetoothEnabled() -> handleBleIsTurnedOffError()
            !permissionHelper.backgroundLocationAllowed() -> handleBackgroundLocationRestricted()
            else -> NotificationsHelper.ServiceNotificationContent.NoDataYet
        }
    }

    private fun handleBackgroundLocationRestricted(): NotificationsHelper.ServiceNotificationContent {
        if (!backgroundLocationRestrictedWasReported) {
            notificationsHelper.notifyBackgroundLocationIsRestricted()
            reportError(IllegalStateException("Can't scan BLE without background location permission due to Android restrictions."))
            backgroundLocationRestrictedWasReported = true
        }
        return NotificationsHelper.ServiceNotificationContent.BackgroundLocationIsRestricted
    }

    private fun handleLocationDisabled(): NotificationsHelper.ServiceNotificationContent {
        if (!locationDisabledWasReported) {
            notificationsHelper.notifyLocationIsTurnedOff()
            reportError(IllegalStateException("The BLE scanner did not return anything. This may happen if geolocation is turned off at the system level. Location access is required to work with BLE on Android."))
            locationDisabledWasReported = true
        }
        return NotificationsHelper.ServiceNotificationContent.LocationIsTurnedOff
    }

    private fun handleBleIsTurnedOffError(): NotificationsHelper.ServiceNotificationContent {
        if (!bluetoothDisabledWasReported) {
            notificationsHelper.notifyBluetoothIsTurnedOff()
            reportError(BleScannerHelper.BluetoothIsNotInitialized())
            bluetoothDisabledWasReported = true
        }
        return NotificationsHelper.ServiceNotificationContent.BluetoothIsTurnedOff
    }

    private suspend fun handleNonEmptyBatch(batch: List<BleScanDevice>): NotificationsHelper.ServiceNotificationContent {
        locationDisabledWasReported = false
        bluetoothDisabledWasReported = false

        return try {
            updateState(ScannerState.ANALYZING)
            val savingResult = withContext(Dispatchers.Default) {
                saveOrMergeBatchInteractor.execute(batch)
            }

            Timber.d("Background scan result: known_devices_count=${savingResult.knownDevicesCount}")

            failureScanCounter.set(0)

            if (savingResult.knownDevicesCount > 0) {
                NotificationsHelper.ServiceNotificationContent.KnownDevicesAround(savingResult.knownDevicesCount)
            } else {
                NotificationsHelper.ServiceNotificationContent.TotalDevicesAround(batch.size)
            }
        } catch (exception: Throwable) {
            handleError(exception)
            NotificationsHelper.ServiceNotificationContent.NoDataYet
        }
    }

    private fun scheduleNextScan() {
        updateState(ScannerState.IDLING)
        val interval = powerModeHelper.powerMode().scanInterval
        handler.postDelayed(nextScanRunnable, interval)
    }

    private fun reportError(error: Throwable) {
        Timber.e(error)
        scope.launch {
            val report = JournalEntry.Report.Error(
                title = "[BLE Service Error]: ${error.message ?: error::class.java}",
                stackTrace = error.stackTraceToString(),
            )
            saveReportInteractor.execute(report)
        }
    }

    enum class ScannerState {
        DISABLED, SCANNING, ANALYZING, IDLING;

        fun isActive(): Boolean {
            return this != DISABLED
        }

        fun isProcessing(): Boolean {
            return this == SCANNING || this == ANALYZING
        }
    }

    companion object {
        private const val MAX_FAILURE_SCANS_TO_CLOSE = 10

        private const val ACTION_STOP_SERVICE = "stop_ble_scan_service"
        private const val ACTION_SCAN_NOW = "ble_scan_now"

        // BR/EDR inquiry takes ~12.8s of radio time per pass. 60s baseline keeps total radio
        // utilization well below LE's percentage while still surfacing pairing-mode peers
        // promptly. Low-power mode stretches to 15min.
        private const val BR_EDR_FIRST_INQUIRY_DELAY_MS = 5_000L
        private const val BR_EDR_INTERVAL_NORMAL_MS = 60_000L
        private const val BR_EDR_INTERVAL_LOW_POWER_MS = 15 * 60_000L

        var state = MutableStateFlow(ScannerState.DISABLED)
            private set
        val isActive: Boolean get() = state.value.isActive()

        private fun updateState(newState: ScannerState) {
            Timber.i("Scanner state: $newState")
            state.tryEmit(newState)
        }

        fun observeIsActive(): Flow<Boolean> {
            return state.map { it.isActive() }
                .distinctUntilChanged()
        }

        private fun createCloseServiceIntent(context: Context): Intent {
            return Intent(context, BgScanService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, BgScanService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (isActive) {
                context.startService(createCloseServiceIntent(context))
            }
        }

        fun scan(context: Context) {
            if (isActive) {
                val intent = Intent(context, BgScanService::class.java).apply {
                    action = ACTION_SCAN_NOW
                }
                context.startService(intent)
            } else {
                start(context)
            }
        }
    }
}