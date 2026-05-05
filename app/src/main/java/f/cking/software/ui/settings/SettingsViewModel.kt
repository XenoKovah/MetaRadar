package f.cking.software.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import f.cking.software.BuildConfig
import f.cking.software.R
import f.cking.software.collectAsState
import f.cking.software.data.btidalpool.BtidalpoolAuthRepository
import f.cking.software.data.helpers.IntentHelper
import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.repo.LocationRepository
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.BackupDatabaseInteractor
import f.cking.software.domain.interactor.ClearAllDevicesInteractor
import f.cking.software.domain.interactor.ClearBTIDESLogInteractor
import f.cking.software.domain.interactor.ClearGarbageInteractor
import f.cking.software.domain.interactor.CreateBTIDESFileInteractor
import f.cking.software.domain.interactor.CreateBackupFileInteractor
import f.cking.software.domain.interactor.ExportBTIDESInteractor
import f.cking.software.domain.interactor.GetDatabaseInfoInteractor
import f.cking.software.domain.interactor.RestoreDatabaseInteractor
import f.cking.software.domain.interactor.SaveReportInteractor
import f.cking.software.domain.interactor.SelectBackupFileInteractor
import f.cking.software.domain.interactor.UploadToBtidalpoolInteractor
import f.cking.software.domain.model.JournalEntry
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val clearGarbageInteractor: ClearGarbageInteractor,
    private val locationRepository: LocationRepository,
    private val locationProvider: LocationProvider,
    private val context: Application,
    private val backupDatabaseInteractor: BackupDatabaseInteractor,
    private val saveReportInteractor: SaveReportInteractor,
    private val createBackupFileInteractor: CreateBackupFileInteractor,
    private val selectBackupFileInteractor: SelectBackupFileInteractor,
    private val restoreDatabaseInteractor: RestoreDatabaseInteractor,
    private val intentHelper: IntentHelper,
    private val permissionHelper: PermissionHelper,
    private val router: Router,
    private val getDatabaseInfoInteractor: GetDatabaseInfoInteractor,
    private val createBTIDESFileInteractor: CreateBTIDESFileInteractor,
    private val exportBTIDESInteractor: ExportBTIDESInteractor,
    private val clearBTIDESLogInteractor: ClearBTIDESLogInteractor,
    private val btidesRepository: f.cking.software.data.btides.BTIDESRepository,
    private val clearAllDevicesInteractor: ClearAllDevicesInteractor,
    private val btidalpoolAuthRepository: BtidalpoolAuthRepository,
    private val uploadToBtidalpoolInteractor: UploadToBtidalpoolInteractor,
) : ViewModel() {

    var garbageRemovingInProgress: Boolean by mutableStateOf(false)
    var locationRemovingInProgress: Boolean by mutableStateOf(false)
    var backupDbInProgress: Boolean by mutableStateOf(false)
    var clearDatabaseInProgress: Boolean by mutableStateOf(false)
    var btidesInProgress: Boolean by mutableStateOf(false)
    /** 0f..1f progress fraction of the in-flight BTIDES export, or 0f when idle. */
    var btidesProgress: Float by mutableStateOf(0f)
    /** True while a cancel-confirmation dialog should be shown over the export button. */
    var btidesCancelDialogVisible: Boolean by mutableStateOf(false)
    private var btidesExportJob: Job? = null
    var btidesLogSizeBytes: Long by mutableStateOf(0L)
    var useGpsLocationOnly: Boolean by mutableStateOf(settingsRepository.getUseGpsLocationOnly())
    var locationData: LocationProvider.LocationHandle? by mutableStateOf(null)
    var runOnStartup: Boolean by mutableStateOf(settingsRepository.getRunOnStartup())
    var runConnectAllOnStartup: Boolean by mutableStateOf(settingsRepository.getRunConnectAllOnStartup())
    /**
     * True while the "disable battery optimisations?" dialog should be shown. Triggered when
     * either auto-start toggle is flipped on (and the app isn't already on the OS's
     * ignore-battery-optimisations list). Single-shot per toggle action; dismissed by either
     * "Open settings" (opens the system page) or "Skip".
     */
    var batteryOptimizationDialogVisible: Boolean by mutableStateOf(false)
    var wakeUpWhileScanning: Boolean by mutableStateOf(settingsRepository.getWakeUpScreenWhileScanning())
    var silentModeEnabled: Boolean by mutableStateOf(settingsRepository.getSilentMode())
    var discoverLeEnabled: Boolean by mutableStateOf(settingsRepository.getDiscoverLeEnabled())
    var discoverBrEdrEnabled: Boolean by mutableStateOf(settingsRepository.getDiscoverBrEdrEnabled())

    /** Cached SSO state. Null when no token is stored. */
    var btidalpoolAuth: BtidalpoolAuthRepository.AuthState? by mutableStateOf(btidalpoolAuthRepository.current())
    /** True while the paste-token dialog is showing. */
    var btidalpoolPasteDialogVisible: Boolean by mutableStateOf(false)
    /** True while sign-in is parsing/validating the pasted token. */
    var btidalpoolSignInInProgress: Boolean by mutableStateOf(false)
    /** True while an upload is in flight. */
    var btidalpoolUploadInProgress: Boolean by mutableStateOf(false)
    /** Reuses the BTIDES export progress signal — upload runs an export internally. */
    var btidalpoolUploadProgress: Float by mutableStateOf(0f)
    /** Mirrors the `--use-test-db` CLI flag; routes uploads to the server's alternate `bttest` DB. */
    var btidalpoolUseTestDb: Boolean by mutableStateOf(settingsRepository.getBtidalpoolUseTestDb())
    /**
     * Non-null while a post-upload status dialog should be shown. The user must tap OK to
     * dismiss — this is louder than a toast because uploads are infrequent and the user needs
     * to know whether their data actually landed on the server.
     */
    var btidalpoolStatusDialogMessage: String? by mutableStateOf(null)

    val databaseInfo by getDatabaseInfoInteractor.execute().collectAsState(viewModelScope, null)

    init {
        observeLocationData()
        observeSilentMode()
        observeBtidalpoolAuth()
        refreshBTIDESLogSize()
    }

    private fun observeBtidalpoolAuth() {
        viewModelScope.launch {
            btidalpoolAuthRepository.observe().collect { btidalpoolAuth = it }
        }
    }

    fun onBtidalpoolSignInClick() {
        // Open Google's OAuth consent page in the browser. The redirect lands on the BTIDALPOOL
        // OAuth helper, which displays the resulting `{"token":...,"refresh_token":...}` JSON
        // for the user to copy back here.
        intentHelper.openUrl(btidalpoolAuthRepository.authorizationUrl())
        btidalpoolPasteDialogVisible = true
    }

    fun onBtidalpoolPasteDismiss() {
        btidalpoolPasteDialogVisible = false
    }

    fun onBtidalpoolPasteSubmit(json: String) {
        viewModelScope.launch {
            btidalpoolSignInInProgress = true
            try {
                val result = btidalpoolAuthRepository.signInWithPastedJson(json)
                result.fold(
                    onSuccess = { state ->
                        btidalpoolPasteDialogVisible = false
                        toast(context.getString(R.string.btidalpool_signed_in_as, state.email ?: "?"))
                    },
                    onFailure = { e ->
                        toast(e.message ?: context.getString(R.string.btidalpool_signin_failed))
                    },
                )
            } finally {
                btidalpoolSignInInProgress = false
            }
        }
    }

    fun onBtidalpoolSignOutClick() {
        btidalpoolAuthRepository.signOut()
        toast(context.getString(R.string.btidalpool_signed_out))
    }

    fun onBtidalpoolStatusDialogDismiss() {
        btidalpoolStatusDialogMessage = null
    }

    fun onToggleBtidalpoolUseTestDb() {
        val newValue = !settingsRepository.getBtidalpoolUseTestDb()
        settingsRepository.setBtidalpoolUseTestDb(newValue)
        btidalpoolUseTestDb = newValue
    }

    fun onUploadCurrentBtidalpoolClick() {
        runUpload { useTestDb, onProgress ->
            uploadToBtidalpoolInteractor.executeCurrent(useTestDb, onProgress)
        }
    }

    fun onUploadAllBtidalpoolClick() {
        runUpload { useTestDb, onProgress ->
            uploadToBtidalpoolInteractor.executeAll(useTestDb, onProgress)
        }
    }

    private fun runUpload(
        block: suspend (
            useTestDb: Boolean,
            onProgress: suspend (bytesProcessed: Long, totalBytes: Long) -> Unit,
        ) -> UploadToBtidalpoolInteractor.Outcome,
    ) {
        if (btidalpoolUploadInProgress) return
        viewModelScope.launch {
            btidalpoolUploadInProgress = true
            btidalpoolUploadProgress = 0f
            try {
                val outcome = block(settingsRepository.getBtidalpoolUseTestDb()) { processed, total ->
                    btidalpoolUploadProgress =
                        if (total > 0L) (processed.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        else 0f
                }
                btidalpoolStatusDialogMessage = formatUploadOutcome(outcome)
                refreshBTIDESLogSize()
            } catch (e: Throwable) {
                reportError(e)
                btidalpoolStatusDialogMessage = context.getString(
                    R.string.btidalpool_upload_failed_with_reason,
                    e.message ?: e::class.java.simpleName,
                )
            } finally {
                btidalpoolUploadInProgress = false
                btidalpoolUploadProgress = 0f
            }
        }
    }

    /**
     * Build a multi-line summary of an upload outcome. For single-log flows the message is
     * one line; for multi-log flows we list each log's status on its own line so the user can
     * tell at a glance which (if any) failed and would need a retry.
     */
    private fun formatUploadOutcome(outcome: UploadToBtidalpoolInteractor.Outcome): String {
        if (outcome is UploadToBtidalpoolInteractor.Outcome.NotSignedIn) {
            return context.getString(R.string.btidalpool_not_signed_in)
        }
        outcome as UploadToBtidalpoolInteractor.Outcome.WithResults
        val results = outcome.results
        // Side-effect: an auth failure on any log means the cached token is invalid; clear it
        // so the next launch shows the Sign-in button.
        if (results.any { it is UploadToBtidalpoolInteractor.LogResult.AuthFailed }) {
            btidalpoolAuthRepository.signOut()
        }
        return results.joinToString(separator = "\n") { logResult ->
            when (logResult) {
                is UploadToBtidalpoolInteractor.LogResult.Success ->
                    context.getString(
                        R.string.btidalpool_upload_log_succeeded,
                        logResult.logName, logResult.deviceCount,
                    )
                is UploadToBtidalpoolInteractor.LogResult.AlreadyOnServer ->
                    context.getString(
                        R.string.btidalpool_upload_log_already_present,
                        logResult.logName,
                    )
                is UploadToBtidalpoolInteractor.LogResult.AuthFailed ->
                    context.getString(R.string.btidalpool_upload_log_auth_failed, logResult.logName)
                is UploadToBtidalpoolInteractor.LogResult.EmptyLog ->
                    context.getString(R.string.btidalpool_upload_log_empty, logResult.logName)
                is UploadToBtidalpoolInteractor.LogResult.Failed -> {
                    Timber.w("BTIDALPOOL upload failed for %s: %s", logResult.logName, logResult.message)
                    context.getString(
                        R.string.btidalpool_upload_log_failed,
                        logResult.logName, logResult.message,
                    )
                }
            }
        }
    }

    fun onRemoveGarbageClick() {
        viewModelScope.launch {
            garbageRemovingInProgress = true
            try {
                val garbageCount = clearGarbageInteractor.execute()
                toast(context.getString(R.string.garbage_has_cleared, garbageCount.toString()))
            } catch (e: Exception) {
                reportError(e)
            }
            garbageRemovingInProgress = false
        }
    }

    fun onClearLocationsClick() {
        viewModelScope.launch {
            locationRemovingInProgress = true
            locationRepository.removeAllLocations()
            toast(context.getString(R.string.settings_location_history_was_removed))
            locationRemovingInProgress = false
        }
    }

    fun onClearDatabaseClick() {
        viewModelScope.launch {
            clearDatabaseInProgress = true
            try {
                clearAllDevicesInteractor.execute()
                toast(context.getString(R.string.clear_all_devices_done))
            } catch (e: Throwable) {
                reportError(e)
            }
            clearDatabaseInProgress = false
        }
    }

    fun onUseGpsLocationOnlyClick() {
        viewModelScope.launch {
            val currentValue = settingsRepository.getUseGpsLocationOnly()
            settingsRepository.setUseGpsLocationOnly(!currentValue)
            useGpsLocationOnly = !currentValue

            // restart location provider
            if (locationProvider.isActive()) {
                locationProvider.stopLocationListening()
                locationProvider.startLocationFetching()
            } else if (permissionHelper.locationAllowed()) {
                locationProvider.fetchOnce()
            }
        }
    }

    fun onExportBTIDESClick() {
        viewModelScope.launch {
            createBTIDESFileInteractor.execute()
                .catch {
                    toast(context.getString(R.string.btides_export_failed))
                    reportError(it)
                }
                .collect { uri ->
                    if (uri != null) {
                        exportBTIDESToUri(uri)
                    } else {
                        toast(context.getString(R.string.file_was_not_selected))
                    }
                }
        }
    }

    /**
     * Tap handler for the ADB export button. While idle, kicks off an export. While an export
     * is in flight, surfaces a cancel-confirmation dialog rather than starting a second one.
     */
    fun onExportBTIDESForAdbClick() {
        if (btidesInProgress) {
            btidesCancelDialogVisible = true
            return
        }
        btidesExportJob = viewModelScope.launch {
            btidesInProgress = true
            btidesProgress = 0f
            try {
                val results = exportBTIDESInteractor.execute { processed, total ->
                    btidesProgress = if (total > 0L) (processed.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                }
                if (results.isEmpty()) {
                    toast(context.getString(R.string.btides_export_for_adb_empty))
                } else {
                    val totalDevices = results.sumOf { it.deviceCount }
                    val parentDir = results.first().file.parent ?: ""
                    toast(
                        context.getString(
                            R.string.btides_export_for_adb_succeeded,
                            results.size,
                            parentDir,
                            totalDevices,
                        )
                    )
                }
                refreshBTIDESLogSize()
            } catch (e: CancellationException) {
                toast(context.getString(R.string.btides_export_cancelled))
                throw e
            } catch (e: Throwable) {
                toast(context.getString(R.string.btides_export_failed))
                reportError(e)
            } finally {
                btidesInProgress = false
                btidesProgress = 0f
                btidesExportJob = null
            }
        }
    }

    fun onConfirmCancelBTIDESExport() {
        btidesCancelDialogVisible = false
        btidesExportJob?.cancel()
    }

    fun onDismissCancelBTIDESExport() {
        btidesCancelDialogVisible = false
    }

    fun onClearCurrentBTIDESLogClick() {
        clearBtidesLogs(ClearBTIDESLogInteractor.Mode.CURRENT)
    }

    fun onClearAllBTIDESLogsClick() {
        clearBtidesLogs(ClearBTIDESLogInteractor.Mode.ALL)
    }

    private fun clearBtidesLogs(mode: ClearBTIDESLogInteractor.Mode) {
        viewModelScope.launch {
            btidesInProgress = true
            try {
                clearBTIDESLogInteractor.execute(mode)
                val msg = when (mode) {
                    ClearBTIDESLogInteractor.Mode.CURRENT -> R.string.btides_current_log_was_cleared
                    ClearBTIDESLogInteractor.Mode.ALL -> R.string.btides_all_logs_were_cleared
                }
                toast(context.getString(msg))
                refreshBTIDESLogSize()
            } catch (e: Throwable) {
                reportError(e)
            }
            btidesInProgress = false
        }
    }

    /** Public so the Settings screen can re-poll the size each time it re-enters composition. */
    fun refreshBTIDESLogSize() {
        viewModelScope.launch {
            // Total across active + every rotated archive — the on-screen size is now a
            // multi-log aggregate, not just the active log.
            btidesLogSizeBytes = btidesRepository.totalLogSizeBytes()
        }
    }

    private fun exportBTIDESToUri(uri: Uri) {
        btidesExportJob = viewModelScope.launch {
            btidesInProgress = true
            btidesProgress = 0f
            try {
                val deviceCount = exportBTIDESInteractor.execute(uri) { processed, total ->
                    btidesProgress = if (total > 0L) (processed.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                }
                toast(context.getString(R.string.btides_export_succeeded, deviceCount))
                refreshBTIDESLogSize()
            } catch (e: CancellationException) {
                toast(context.getString(R.string.btides_export_cancelled))
                throw e
            } catch (e: Throwable) {
                toast(context.getString(R.string.btides_export_failed))
                reportError(e)
            } finally {
                btidesInProgress = false
                btidesProgress = 0f
                btidesExportJob = null
            }
        }
    }

    fun onBackupDBClick() {
        viewModelScope.launch {
            createBackupFileInteractor.execute()
                .catch {
                    toast(context.getString(R.string.backup_has_failed))
                    reportError(it)
                }
                .collect { uri ->
                    if (uri != null) {
                        backupFileTo(uri)
                    } else {
                        toast(context.getString(R.string.directory_was_not_selected))
                    }
                }
        }
    }

    fun onRestoreDBClick() {
        viewModelScope.launch {
            selectBackupFileInteractor.execute()
                .catch {
                    toast(context.getString(R.string.cannot_restore_database))
                    reportError(it)
                }
                .collect { uri ->
                    if (uri != null) {
                        restoreFrom(uri)
                    } else {
                        toast(context.getString(R.string.file_was_not_selected))
                    }
                }
        }
    }

    fun setRunOnStartup() {
        val newValue = !settingsRepository.getRunOnStartup()
        settingsRepository.setRunOnStartup(newValue)
        runOnStartup = newValue
        if (newValue) {
            // Mutually exclusive with the Connect All variant.
            settingsRepository.setRunConnectAllOnStartup(false)
            runConnectAllOnStartup = false
            maybePromptBatteryOptimization()
        }
    }

    /**
     * Toggle the Connect All boot-start. Mutually exclusive with [setRunOnStartup]: enabling
     * one disables the other so we never have two boot-time scan owners contending for the
     * foreground service. When turning ON, also prompts the user to opt the app out of
     * battery optimisation (Android otherwise kills foreground scan services after a while).
     */
    fun setRunConnectAllOnStartup() {
        val newValue = !settingsRepository.getRunConnectAllOnStartup()
        settingsRepository.setRunConnectAllOnStartup(newValue)
        runConnectAllOnStartup = newValue
        if (newValue) {
            settingsRepository.setRunOnStartup(false)
            runOnStartup = false
            maybePromptBatteryOptimization()
        }
    }

    private fun maybePromptBatteryOptimization() {
        // Skip the dialog if the app is already exempt — nothing to ask the user for.
        val pm = context.getSystemService(Application.POWER_SERVICE) as? android.os.PowerManager
        val alreadyIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        if (!alreadyIgnored) batteryOptimizationDialogVisible = true
    }

    fun onBatteryOptimizationDialogDismiss() {
        batteryOptimizationDialogVisible = false
    }

    fun onBatteryOptimizationDialogOpenSettings() {
        batteryOptimizationDialogVisible = false
        intentHelper.openIgnoreBatteryOptimizationSettings()
    }

    fun toggleWakeUpOnScreen() {
        val newValue = !settingsRepository.getWakeUpScreenWhileScanning()
        settingsRepository.setWakeUpScreenWhileScanning(newValue)
        wakeUpWhileScanning = newValue
        if (newValue && !Settings.System.canWrite(context)) {
            permissionHelper.requestWriteSettingsPermission()
        }
    }

    fun changeSilentMode() {
        settingsRepository.setSilentMode(!settingsRepository.getSilentMode())
    }

    fun toggleDiscoverLe() {
        val newValue = !settingsRepository.getDiscoverLeEnabled()
        settingsRepository.setDiscoverLeEnabled(newValue)
        discoverLeEnabled = newValue
    }

    fun toggleDiscoverBrEdr() {
        val newValue = !settingsRepository.getDiscoverBrEdrEnabled()
        settingsRepository.setDiscoverBrEdrEnabled(newValue)
        discoverBrEdrEnabled = newValue
    }

    fun onReportIssueClick() {
        intentHelper.openUrl(BuildConfig.REPORT_ISSUE_URL)
    }

    fun onGithubClick() {
        intentHelper.openUrl(BuildConfig.GITHUB_URL)
    }

    fun onOpenJournalClick() {
        router.navigate(ScreenNavigationCommands.OpenJournalScreen)
    }

    private fun observeLocationData() {
        viewModelScope.launch {
            locationProvider.observeLocation()
                .collect { locationHandle ->
                    locationData = locationHandle
                }
        }
    }

    private fun observeSilentMode() {
        viewModelScope.launch {
            settingsRepository.observeSilentMode()
                .collect { silentModeEnabled = it }
        }
    }

    private fun restoreFrom(uri: Uri) {
        viewModelScope.launch {
            backupDbInProgress = true
            try {
                restoreDatabaseInteractor.execute(uri)
            } catch (e: Throwable) {
                toast(context.getString(R.string.cannot_restore_database))
                reportError(e)
            }
            backupDbInProgress = false
            toast(context.getString(R.string.database_was_restored))
        }
    }

    private fun backupFileTo(uri: Uri) {
        viewModelScope.launch {
            backupDbInProgress = true
            try {
                backupDatabaseInteractor.execute(uri)
            } catch (e: Throwable) {
                toast(context.getString(R.string.backup_has_failed))
                reportError(e)
            }
            backupDbInProgress = false
            toast(context.getString(R.string.backup_has_succeeded))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    private fun reportError(error: Throwable) {
        // Don't pollute the journal with user-initiated cancellations — they're not errors.
        if (error is CancellationException) {
            Timber.d(error, "Operation cancelled")
            return
        }
        Timber.e(error)
        viewModelScope.launch {
            val report = JournalEntry.Report.Error(
                title = "[Settings]: ${error.message ?: error::class.java}",
                stackTrace = error.stackTraceToString(),
            )
            saveReportInteractor.execute(report)
        }
    }
}