package com.darkmentor.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.BuildConfig
import com.darkmentor.R
import com.darkmentor.collectAsState
import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.BackupDatabaseInteractor
import com.darkmentor.domain.interactor.ClearAllDevicesInteractor
import com.darkmentor.domain.interactor.ClearBTIDESLogInteractor
import com.darkmentor.domain.interactor.ClearGarbageInteractor
import com.darkmentor.domain.interactor.CreateBTIDESFileInteractor
import com.darkmentor.domain.interactor.CreateBackupFileInteractor
import com.darkmentor.domain.interactor.ExportBTIDESInteractor
import com.darkmentor.domain.interactor.GetDatabaseInfoInteractor
import com.darkmentor.domain.interactor.RestoreDatabaseInteractor
import com.darkmentor.domain.interactor.SaveReportInteractor
import com.darkmentor.domain.interactor.SelectBackupFileInteractor
import com.darkmentor.domain.interactor.UploadToBtidalpoolInteractor
import com.darkmentor.domain.model.JournalEntry
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.utils.navigation.Router
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
    private val btidesRepository: com.darkmentor.data.btides.BTIDESRepository,
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

    /**
     * In-app toggle for the [AutoPairAccessibilityService][com.darkmentor.service.AutoPairAccessibilityService].
     * Independent from [autoPairServiceEnabledOs]: even with the OS-level Accessibility
     * permission granted, this gate decides whether the service actually performs clicks.
     */
    var autoPairToggleEnabled: Boolean by mutableStateOf(settingsRepository.getAutoPairEnabled())

    /**
     * Live read of Android's [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] for our service.
     * Refreshed by [refreshAutoPairOsState] on every entry into the Settings screen — Android
     * doesn't fire a broadcast when the user enables/disables an accessibility service, so
     * polling on screen entry is the practical way to keep the status text accurate.
     */
    var autoPairServiceEnabledOs: Boolean by mutableStateOf(false)

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
    /**
     * Non-null while the post-sign-in "Credentials valid"/"Credentials invalid" dialog should
     * be shown. Separate from [btidalpoolStatusDialogMessage] so the two can carry different
     * titles; the user dismisses it with OK. A valid result has already persisted the token (so
     * the upload UI shows underneath); an invalid result persisted nothing (so the Sign-in
     * button shows underneath).
     */
    var btidalpoolCredentialsDialogMessage: String? by mutableStateOf(null)
    /** True while a "Cancel current BTIDALPOOL upload?" confirmation dialog is showing. */
    var btidalpoolCancelDialogVisible: Boolean by mutableStateOf(false)
    /** Job handle for the in-flight upload pass, so the cancel dialog can interrupt it. */
    private var btidalpoolUploadJob: Job? = null

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
        // Guard against a double-tap on "Use token" kicking off a second validation while the
        // first network round-trip is still in flight.
        if (btidalpoolSignInInProgress) return
        viewModelScope.launch {
            btidalpoolSignInInProgress = true
            try {
                // Validates against Google AND probes the BTIDALPOOL upload server. Persists
                // (flipping btidalpoolAuth non-null) on any Valid outcome — including when the
                // upload server is unreachable, since Google already vouched for the token.
                val outcome = btidalpoolAuthRepository.signInWithPastedJson(
                    json,
                    settingsRepository.getBtidalpoolUseTestDb(),
                )
                // Close the paste dialog and report the verdict in a follow-up dialog. A Valid
                // outcome persisted the token, so the upload UI renders underneath; an Invalid
                // one persisted nothing, so the screen falls back to "Sign in with Google".
                btidalpoolPasteDialogVisible = false
                btidalpoolCredentialsDialogMessage = messageFor(outcome)
            } finally {
                btidalpoolSignInInProgress = false
            }
        }
    }

    /**
     * Native "Sign in with Google" entry point. The Settings screen owns the Google Identity
     * Services interaction and the consent ActivityResult launcher (those need an Activity +
     * Compose launcher); it hands us the one-time serverAuthCode here. From this point on the
     * flow is identical to the paste flow's tail: exchange → validate → probe → persist.
     */
    fun onGoogleServerAuthCode(authCode: String) {
        if (btidalpoolSignInInProgress) return
        viewModelScope.launch {
            btidalpoolSignInInProgress = true
            try {
                val outcome = btidalpoolAuthRepository.signInWithServerAuthCode(
                    authCode,
                    settingsRepository.getBtidalpoolUseTestDb(),
                )
                btidalpoolCredentialsDialogMessage = messageFor(outcome)
            } finally {
                btidalpoolSignInInProgress = false
            }
        }
    }

    /**
     * The native Google sign-in failed before any code was issued (e.g. Google Play services
     * unavailable, or the app's Android OAuth client isn't registered → DEVELOPER_ERROR). This
     * is distinct from "credentials invalid" — no credential was ever produced — so we say so.
     */
    fun onGoogleSignInFailed(detail: String?) {
        Timber.d("Google sign-in failed: %s", detail ?: "unknown")
        btidalpoolCredentialsDialogMessage = context.getString(R.string.btidalpool_google_signin_failed)
    }

    /** User dismissed the Google account picker / consent screen — silent no-op. */
    fun onGoogleSignInCancelled() {
        btidalpoolSignInInProgress = false
    }

    /** Maps a sign-in outcome to the dialog text shared by the native and paste flows. */
    private fun messageFor(outcome: BtidalpoolAuthRepository.SignInOutcome): String = when (outcome) {
        is BtidalpoolAuthRepository.SignInOutcome.Valid ->
            if (outcome.serverReachable) {
                context.getString(R.string.btidalpool_credentials_valid)
            } else {
                // Token is good (Google confirmed it) but the upload server didn't answer.
                // Tell the truth instead of falsely claiming the token is invalid.
                context.getString(R.string.btidalpool_credentials_valid_server_unreachable)
            }
        is BtidalpoolAuthRepository.SignInOutcome.Invalid -> {
            Timber.d("BTIDALPOOL credentials invalid: %s", outcome.reason)
            context.getString(R.string.btidalpool_credentials_invalid)
        }
    }

    fun onBtidalpoolSignOutClick() {
        btidalpoolAuthRepository.signOut()
        toast(context.getString(R.string.btidalpool_signed_out))
    }

    fun onBtidalpoolStatusDialogDismiss() {
        btidalpoolStatusDialogMessage = null
    }

    fun onBtidalpoolCredentialsDialogDismiss() {
        btidalpoolCredentialsDialogMessage = null
    }

    fun onToggleBtidalpoolUseTestDb() {
        val newValue = !settingsRepository.getBtidalpoolUseTestDb()
        settingsRepository.setBtidalpoolUseTestDb(newValue)
        btidalpoolUseTestDb = newValue
    }

    fun onUploadCurrentBtidalpoolClick() {
        // Tap-while-uploading raises a cancel-confirmation dialog instead of starting a
        // second upload (matches the BTIDES ADB-export button's behaviour).
        if (btidalpoolUploadInProgress) {
            btidalpoolCancelDialogVisible = true
            return
        }
        runUpload { useTestDb, onProgress ->
            uploadToBtidalpoolInteractor.executeCurrent(useTestDb, onProgress)
        }
    }

    fun onUploadAllBtidalpoolClick() {
        if (btidalpoolUploadInProgress) {
            btidalpoolCancelDialogVisible = true
            return
        }
        runUpload { useTestDb, onProgress ->
            uploadToBtidalpoolInteractor.executeAll(useTestDb, onProgress)
        }
    }

    fun onConfirmCancelBtidalpoolUpload() {
        btidalpoolCancelDialogVisible = false
        btidalpoolUploadJob?.cancel()
    }

    fun onDismissCancelBtidalpoolUpload() {
        btidalpoolCancelDialogVisible = false
    }

    private fun runUpload(
        block: suspend (
            useTestDb: Boolean,
            onProgress: suspend (bytesProcessed: Long, totalBytes: Long) -> Unit,
        ) -> UploadToBtidalpoolInteractor.Outcome,
    ) {
        if (btidalpoolUploadInProgress) return
        btidalpoolUploadJob = viewModelScope.launch {
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
            } catch (ce: CancellationException) {
                btidalpoolStatusDialogMessage = context.getString(R.string.btidalpool_upload_cancelled)
                throw ce
            } catch (e: Throwable) {
                reportError(e)
                btidalpoolStatusDialogMessage = context.getString(
                    R.string.btidalpool_upload_failed_with_reason,
                    e.message ?: e::class.java.simpleName,
                )
            } finally {
                btidalpoolUploadInProgress = false
                btidalpoolUploadProgress = 0f
                btidalpoolUploadJob = null
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

    /**
     * In-app gate flip. Independent from the OS Accessibility permission — toggling this off
     * leaves the service bound but makes it short-circuit; toggling on without the OS-level
     * permission is a no-op (the service never gets bound until the user grants it via the
     * Accessibility settings page reachable from [openAccessibilitySettings]).
     */
    fun toggleAutoPair() {
        val newValue = !settingsRepository.getAutoPairEnabled()
        settingsRepository.setAutoPairEnabled(newValue)
        autoPairToggleEnabled = newValue
    }

    /**
     * Refresh [autoPairServiceEnabledOs] from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
     * Android doesn't broadcast accessibility-service enable/disable changes, so callers
     * (typically the Settings screen on entry/return-from-Accessibility-settings) invoke this
     * to keep the displayed status text in sync.
     */
    fun refreshAutoPairOsState() {
        autoPairServiceEnabledOs = isAutoPairAccessibilityServiceEnabled()
    }

    private fun isAutoPairAccessibilityServiceEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        // Format is "pkg/cls:pkg/cls:..." — match by ComponentName flat string. We construct
        // ours rather than hard-coding so a future package rename doesn't silently break this.
        val component = android.content.ComponentName(
            context,
            com.darkmentor.service.AutoPairAccessibilityService::class.java,
        ).flattenToString()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    fun openAccessibilitySettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag("SettingsVM").w(it, "Failed to open Accessibility settings") }
    }

    /**
     * Deep-link into Android's App Info page for our package — that's where the user can tap
     * the ⋮ menu to flip "Allow restricted settings", which Android 13+ requires for
     * sideloaded apps' accessibility services to actually bind. Android exposes no direct
     * intent for the restricted-settings flip itself; App Info is the closest the OS allows
     * a third-party app to deep-link.
     */
    fun openAppInfoForRestrictedSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag("SettingsVM").w(it, "Failed to open App Info") }
    }

    /**
     * Deep-link to Android's Battery Saver settings page. Battery Saver downgrades the LE
     * scan duty cycle to ~12% and stretches BR/EDR inquiry to every 15 min — which
     * dramatically slows new-candidate discovery during long Connect All sessions. Surface
     * the toggle next to "Keep screen on while scanning" so the user can switch both
     * power-related controls in one place.
     */
    fun openBatterySaverSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag("SettingsVM").w(it, "Failed to open Battery Saver settings") }
    }

    /**
     * Per-app battery optimization deep-link. On TCL devices the vendor's `AppBootManager`
     * kills our process on every screen-on transition (logged as Reason[screen_on]), creating
     * a 1-3s window where the AccessibilityService isn't bound and pairing prompts get
     * missed. Setting this app's battery state to Unrestricted is the standard Android
     * opt-out from those kills. We use [Settings.ACTION_APPLICATION_DETAILS_SETTINGS] —
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` would technically work too but pops up a
     * confirmation dialog, while landing on App Info → Battery lets the user pick the right
     * state directly and is also where TCL/Motorola/Samsung surface their vendor-specific
     * "Allow background activity" toggles.
     */
    fun openAppBatterySettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", context.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.tag("SettingsVM").w(it, "Failed to open App Info for battery optimization") }
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

    fun onOpenExclusionZonesClick() {
        router.navigate(ScreenNavigationCommands.OpenExclusionZonesScreen)
    }

    fun onOpenDiscoveryTransportsClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.DISCOVERY))
    }

    fun onOpenAppBehaviorClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.APP_BEHAVIOR))
    }

    fun onOpenLocationClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.LOCATION))
    }

    fun onOpenDatabaseClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.DATABASE))
    }

    fun onOpenBtidalpoolClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.BTIDALPOOL))
    }

    fun onOpenBtidesClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.BTIDES))
    }

    fun onOpenAboutClick() {
        router.navigate(ScreenNavigationCommands.OpenSettingsDetailScreen(SettingsSection.ABOUT))
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