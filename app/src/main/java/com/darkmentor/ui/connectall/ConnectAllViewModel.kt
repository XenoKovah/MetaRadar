package com.darkmentor.ui.connectall

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.DevicesRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.BulkEnumerateGattInteractor
import com.darkmentor.domain.interactor.VendorIdentifier
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.service.BgScanService
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.utils.navigation.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Drives the bulk-GATT-enumeration flow and exposes the list of devices that successfully
 * enumerated so the UI can render them in the same style as the main Devices tab.
 */
class ConnectAllViewModel(
    private val context: Application,
    private val bulkEnumerateGattInteractor: BulkEnumerateGattInteractor,
    private val settingsRepository: SettingsRepository,
    private val router: Router,
    private val devicesRepository: DevicesRepository,
    private val vendorIdentifier: VendorIdentifier,
    private val permissionHelper: PermissionHelper,
    private val connectAllSession: ConnectAllSession,
) : ViewModel() {

    var bulkSkipApple: Boolean by mutableStateOf(settingsRepository.getBulkSkipApple())
    var bulkSkipSamsung: Boolean by mutableStateOf(settingsRepository.getBulkSkipSamsung())
    var retryForever: Boolean by mutableStateOf(settingsRepository.getBulkRetryForever())
    var inProgress: Boolean by mutableStateOf(false)
    /** Live one-line headline ("Pass N — Starting on M devices…", "Done…", etc.). */
    var statusLine: String by mutableStateOf("")
    /**
     * Per-worker-slot in-flight status. With 4 LE + 1 BR/EDR concurrent workers there can be up
     * to 5 simultaneous "Connecting BDADDR Name…" lines. The screen renders these in slot-id
     * order so the same slot's progress stays in the same visual row across attempts.
     */
    var inFlightLines: List<String> by mutableStateOf(emptyList())
    /** Devices that have successfully completed GATT enumeration during the latest run. */
    var connectedDevices: List<DeviceData> by mutableStateOf(emptyList())

    /**
     * Three running-total categories from the live session, surfaced for the screen's
     * collapsible category boxes. Each list is most-recent-first; success on a previously-
     * errored address removes it from [errorEntries]/[tooManyAttemptsEntries] and lifts it
     * into [connectedEntries].
     */
    var connectedEntries: List<ConnectAllSession.ConnectedEntry> by mutableStateOf(emptyList())
    var errorEntries: List<ConnectAllSession.ErrorEntry> by mutableStateOf(emptyList())
    var tooManyAttemptsEntries: List<ConnectAllSession.TooManyAttemptsEntry> by mutableStateOf(emptyList())

    var connectedExpanded: Boolean by mutableStateOf(false)
    var errorsExpanded: Boolean by mutableStateOf(false)
    var tooManyAttemptsExpanded: Boolean by mutableStateOf(false)

    /**
     * Live preview of devices the next "Connect to all" pass would attempt — visible + connectable
     * + not vendor-filtered. Updated as the BLE scanner's last-batch StateFlow re-emits and as the
     * user toggles vendor filters. [candidateVendorFiltered] tracks the count we'd skip.
     */
    var candidateDevices: List<DeviceData> by mutableStateOf(emptyList())
    var candidateVendorFiltered: Int by mutableStateOf(0)

    /** Tracks whether the pane is currently in composition — gates the scan-poll loop. */
    private var paneVisible: Boolean = false
    private var pollJob: Job? = null

    init {
        observeCandidates()
        observeSession()
    }

    private fun observeCandidates() {
        viewModelScope.launch {
            devicesRepository.observeLastBatch().collect { batch ->
                recomputeCandidates(batch)
            }
        }
    }

    /**
     * Mirror the singleton [ConnectAllSession] state into the local Compose-observable fields
     * so screen code keeps reading from the same `viewModel.connectedDevices`,
     * `viewModel.statusLine`, etc. surface as before. This is what makes a boot-started
     * Connect All session visible in the UI when the user later opens the app — the session
     * has been accumulating connectedDevices, and the first VM observation pulls them in.
     */
    private fun observeSession() {
        viewModelScope.launch {
            connectAllSession.state.collect { s ->
                inProgress = s.inProgress
                statusLine = s.statusLine
                inFlightLines = s.inFlightBySlot.toSortedMap().values.toList()
                connectedDevices = s.connectedDevices
                connectedEntries = s.connected
                errorEntries = s.errors
                tooManyAttemptsEntries = s.tooManyAttempts
                connectedExpanded = s.connectedExpanded
                errorsExpanded = s.errorsExpanded
                tooManyAttemptsExpanded = s.tooManyAttemptsExpanded
            }
        }
    }

    /**
     * Re-poll the candidate count on a fixed cadence so the status line stays fresh even when
     * the BLE scanner's batch StateFlow hasn't re-emitted (e.g., the same set of devices is
     * still visible — value-equal lists don't trigger StateFlow). The visible-cadence is what
     * the user sees as "dynamically updating every 10 seconds".
     *
     * Each tick also pokes the scanner so the list keeps refreshing — without this, the user
     * has to start a scan from the Devices tab before any candidates show up. The loop only
     * runs while the pane is visible; otherwise it'd resurrect a scan we'd just torn down on
     * pane exit, which the user noticed as "I left Connect All but the scan kept restarting".
     */
    private fun startCandidatePolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (paneVisible) {
                delay(CANDIDATE_POLL_INTERVAL_MS)
                if (!paneVisible) break
                ensureScanRunning()
                recomputeCandidates(devicesRepository.observeLastBatch().value)
            }
        }
    }

    /**
     * Called from the screen on every entry into composition AND on every candidate-poll tick.
     * Triggers a one-shot scan via the BgScanService so the candidate list starts populating
     * immediately even if the user came straight here from a fresh DB clear (where lastBatch
     * is empty).
     *
     * `BgScanService.scan(context)` is the same call the Devices-tab Scan FAB uses — if the
     * service is already running it just kicks off an extra scan window; if it's not, it starts
     * the foreground service. The `onRequestPermissions` arg is a no-op so this never
     * re-prompts the system permission dialog: scanning either works because the user granted
     * BLE perms during initial setup, or it silently no-ops here.
     *
     * Mode tracking: only mark the scan as CONNECT_ALL_AUTO when no other mode is active. If
     * the user already started the service explicitly via the Devices-tab FAB, we leave the
     * mode as USER_EXPLICIT so [onPaneHidden] doesn't tear down their manual scan.
     */
    fun ensureScanRunning() {
        // First time the pane reports visible, start the polling loop.
        val firstVisible = !paneVisible
        paneVisible = true
        if (firstVisible) startCandidatePolling()
        permissionHelper.checkOrRequestPermission(
            onRequestPermissions = { _, _, _ -> /* no-op: skip silently if not yet granted */ },
            onPermissionGranted = {
                if (settingsRepository.getScanStartMode() == SettingsRepository.ScanStartMode.NONE) {
                    settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO)
                }
                BgScanService.scan(context)
            },
        )
    }

    /**
     * Called when the Connect All pane leaves composition (tab switch, navigate back). If we
     * were the ones who started the foreground service (mode == CONNECT_ALL_AUTO), tear it
     * down so Devices doesn't show ghost scan results. If the mode is USER_EXPLICIT (started
     * via the Devices FAB), leave it alone — the user wants that to keep running. Also
     * cancels the candidate-poll loop so it can't resurrect the scan after we just stopped it.
     */
    fun onPaneHidden() {
        paneVisible = false
        pollJob?.cancel()
        pollJob = null
        // Don't tear down the foreground scan service if a Connect All session is still
        // running — including a session that was started by the boot receiver. The user can
        // navigate away from the pane and back; the underlying capture must survive both
        // transitions or the boot-started behaviour would silently die on first pane exit.
        if (connectAllSession.isActive) return
        if (settingsRepository.getScanStartMode() == SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO) {
            BgScanService.stop(context)
            settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.NONE)
        }
    }

    /**
     * This runs on Dispatchers.Main.immediate (the StateFlow collector that observes
     * `devicesRepository.observeLastBatch()`). A throw here is fatal — it kills the UI thread
     * and crashes the whole app. Per-device errors should never abort the candidate sweep, so
     * each device is evaluated under its own try/catch and logged to the journal on failure.
     */
    private fun recomputeCandidates(batch: List<DeviceData>) {
        val connectable = batch.filter { it.isConnectable }
        var filtered = 0
        val keep = mutableListOf<DeviceData>()
        for (d in connectable) {
            val skip = try {
                vendorIdentifier.shouldSkip(d, bulkSkipApple, bulkSkipSamsung)
            } catch (e: Throwable) {
                Timber.tag("ConnectAll").e(e, "vendor classification failed for ${d.address}; including in candidates")
                false
            }
            if (skip) filtered++ else keep += d
        }
        candidateDevices = keep.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        candidateVendorFiltered = filtered
    }

    fun onSkipAppleToggled() {
        bulkSkipApple = !bulkSkipApple
        settingsRepository.setBulkSkipApple(bulkSkipApple)
        viewModelScope.launch { recomputeCandidates(devicesRepository.observeLastBatch().value) }
    }

    fun onSkipSamsungToggled() {
        bulkSkipSamsung = !bulkSkipSamsung
        settingsRepository.setBulkSkipSamsung(bulkSkipSamsung)
        viewModelScope.launch { recomputeCandidates(devicesRepository.observeLastBatch().value) }
    }

    fun onRetryForeverToggled() {
        retryForever = !retryForever
        settingsRepository.setBulkRetryForever(retryForever)
    }

    fun onToggleConnectedExpanded() {
        connectAllSession.toggleConnectedExpanded()
    }

    fun onToggleErrorsExpanded() {
        connectAllSession.toggleErrorsExpanded()
    }

    fun onToggleTooManyAttemptsExpanded() {
        connectAllSession.toggleTooManyAttemptsExpanded()
    }

    fun onConnectAllClick() {
        if (connectAllSession.isActive) {
            connectAllSession.stop()
            return
        }
        connectAllSession.start(retryForever = retryForever)
    }

    fun onDeviceClick(device: DeviceData) {
        router.navigate(ScreenNavigationCommands.OpenDeviceDetailsScreen(device.address))
    }

    companion object {
        private const val CANDIDATE_POLL_INTERVAL_MS = 10_000L
    }
}
