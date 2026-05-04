package f.cking.software.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import f.cking.software.R
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.helpers.NotificationsHelper
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.data.helpers.PowerModeHelper
import f.cking.software.domain.interactor.SaveOrMergeBatchInteractor
import f.cking.software.domain.interactor.SaveReportInteractor
import f.cking.software.domain.model.BleScanDevice
import f.cking.software.domain.model.JournalEntry
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

    private val bleListener = object : BleScannerHelper.ScanListener {
        override fun onFailure(exception: Exception) {
            handleError(exception)
        }

        override fun onSuccess(batch: List<BleScanDevice>) {
            handleScanResult(batch)
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
        locationProvider.stopLocationListening()
        handler.removeCallbacks(nextScanRunnable)
        notificationsHelper.cancel(NotificationsHelper.FOREGROUND_NOTIFICATION_ID)
        // Reset the persisted scan-start mode so on next app open the cleanup check sees NONE
        // instead of carrying over a stale CONNECT_ALL_AUTO/USER_EXPLICIT label.
        settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.NONE)
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun scan() {
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