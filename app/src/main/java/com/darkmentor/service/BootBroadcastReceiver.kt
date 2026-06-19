package com.darkmentor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.SaveReportInteractor
import com.darkmentor.domain.model.JournalEntry
import com.darkmentor.ui.connectall.ConnectAllSession
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
        // The decision logic lives in [decideBootStartup] so it's testable without a Context
        // / Koin container; this method just performs the side effect implied by the result.
        val action = decideBootStartup(
            runDeviceScanOnStartup = settingsRepository.getRunOnStartup(),
            runConnectAllOnStartup = settingsRepository.getRunConnectAllOnStartup(),
            blePermissionsAllowed = permissionHelper.blePermissionsAllowed(),
            bulkRetryForever = settingsRepository.getBulkRetryForever(),
        )

        try {
            when (action) {
                is BootStartupAction.Idle -> Unit
                is BootStartupAction.PermissionError -> {
                    report(
                        JournalEntry.Report.Error(
                            title = "[${action.label} error]: Not all permissions granted",
                            stackTrace = IllegalStateException("Not all permissions granted").stackTraceToString()
                        )
                    )
                }
                is BootStartupAction.StartDeviceScan -> {
                    // USER_EXPLICIT so the scan survives the next app open and isn't torn down
                    // by Connect All's mode tracking when the user happens to visit that pane.
                    settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.USER_EXPLICIT)
                    BgScanService.start(context)
                }
                is BootStartupAction.StartConnectAll -> {
                    // CONNECT_ALL_AUTO so the BTIDES Apple/Samsung skip filter applies
                    // (matches what a user-driven Connect All session does). ConnectAllSession
                    // .isActive will block onPaneHidden's tear-down, so the scan keeps running
                    // even when the user visits and leaves the pane.
                    settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO)
                    BgScanService.start(context)
                    connectAllSession.start(retryForever = action.retryForever)
                }
            }
        } catch (error: Exception) {
            Timber.e(error, "Failed to start auto-launched service from boot receiver")
            val label = when (action) {
                is BootStartupAction.StartConnectAll, is BootStartupAction.PermissionError -> {
                    if (settingsRepository.getRunConnectAllOnStartup()) BOOT_STARTUP_LABEL_CONNECT_ALL
                    else BOOT_STARTUP_LABEL_DEVICE_SCAN
                }
                else -> BOOT_STARTUP_LABEL_DEVICE_SCAN
            }
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