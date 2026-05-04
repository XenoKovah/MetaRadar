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
    var wakeUpWhileScanning: Boolean by mutableStateOf(settingsRepository.getWakeUpScreenWhileScanning())
    var silentModeEnabled: Boolean by mutableStateOf(settingsRepository.getSilentMode())
    var discoverLeEnabled: Boolean by mutableStateOf(settingsRepository.getDiscoverLeEnabled())
    var discoverBrEdrEnabled: Boolean by mutableStateOf(settingsRepository.getDiscoverBrEdrEnabled())

    val databaseInfo by getDatabaseInfoInteractor.execute().collectAsState(viewModelScope, null)

    init {
        observeLocationData()
        observeSilentMode()
        refreshBTIDESLogSize()
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
                val result = exportBTIDESInteractor.execute { processed, total ->
                    btidesProgress = if (total > 0L) (processed.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
                }
                toast(
                    context.getString(
                        R.string.btides_export_for_adb_succeeded,
                        result.file.absolutePath,
                        result.deviceCount,
                    )
                )
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

    fun onClearBTIDESLogClick() {
        viewModelScope.launch {
            btidesInProgress = true
            try {
                clearBTIDESLogInteractor.execute()
                toast(context.getString(R.string.btides_log_was_cleared))
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
            btidesLogSizeBytes = btidesRepository.logFileSizeBytes()
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