package f.cking.software.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.SaveReportInteractor
import f.cking.software.domain.model.JournalEntry
import f.cking.software.ui.connectall.ConnectAllSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

class BootBroadcastReceiver : BroadcastReceiver() {

    private val permissionHelper: PermissionHelper by inject(PermissionHelper::class.java)
    private val settingsRepository: SettingsRepository by inject(SettingsRepository::class.java)
    private val saveReportInteractor: SaveReportInteractor by inject(SaveReportInteractor::class.java)
    private val connectAllSession: ConnectAllSession by inject(ConnectAllSession::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            tryToRunService(context)
        }
    }

    private fun tryToRunService(context: Context) {
        // Two mutually-exclusive boot-start modes (the SettingsViewModel enforces that they
        // can't both be on at the same time):
        //   - runOnStartup: just scan the airwaves, no GATT connections
        //   - runConnectAllOnStartup: scan AND drive a Connect All retry-forever pass
        // Both depend on BLE permissions still being granted; if not, log a Journal entry so
        // the user can see why the auto-start did nothing.
        val runDeviceScan = settingsRepository.getRunOnStartup()
        val runConnectAll = settingsRepository.getRunConnectAllOnStartup()
        if (!runDeviceScan && !runConnectAll) return

        if (!permissionHelper.blePermissionsAllowed()) {
            val label = if (runConnectAll) "Launch Connect All at system startup" else "Launch device scan at system startup"
            report(
                JournalEntry.Report.Error(
                    title = "[$label error]: Not all permissions granted",
                    stackTrace = IllegalStateException("Not all permissions granted").stackTraceToString()
                )
            )
            return
        }

        try {
            if (runDeviceScan) {
                // User opted into device-scan auto-start → treat this as USER_EXPLICIT so the
                // scan survives the next app open, and isn't torn down by Connect All's mode
                // tracking when the user happens to visit that pane.
                settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.USER_EXPLICIT)
                BgScanService.start(context)
            } else {
                // Connect All auto-start: mode = CONNECT_ALL_AUTO so the BTIDES Apple/Samsung
                // skip filter applies (matches what a user-driven Connect All session does).
                // ConnectAllSession.isActive will block onPaneHidden's tear-down, so the scan
                // keeps running even when the user visits and leaves the pane. Skip Apple /
                // Skip Samsung / Retry Forever come from SettingsRepository — the user's last
                // configured values are reused.
                settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO)
                BgScanService.start(context)
                connectAllSession.start(retryForever = settingsRepository.getBulkRetryForever())
            }
        } catch (error: Exception) {
            Timber.e(error, "Failed to start auto-launched service from boot receiver")
            val label = if (runConnectAll) "Launch Connect All at system startup" else "Launch device scan at system startup"
            val report = JournalEntry.Report.Error(
                title = "[$label error]: ${error.message ?: error::class.java}",
                stackTrace = error.stackTraceToString(),
            )
            report(report)
        }
    }

    private fun report(report: JournalEntry.Report.Error) {
        Timber.e(report.stackTrace)
        scope.launch {
            saveReportInteractor.execute(report)
        }
    }
}