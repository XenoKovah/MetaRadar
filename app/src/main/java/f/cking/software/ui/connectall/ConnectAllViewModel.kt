package f.cking.software.ui.connectall

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.BulkEnumerateGattInteractor
import f.cking.software.domain.interactor.VendorIdentifier
import f.cking.software.domain.model.DeviceData
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.CancellationException
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
) : ViewModel() {

    var bulkSkipApple: Boolean by mutableStateOf(settingsRepository.getBulkSkipApple())
    var bulkSkipSamsung: Boolean by mutableStateOf(settingsRepository.getBulkSkipSamsung())
    var retryForever: Boolean by mutableStateOf(settingsRepository.getBulkRetryForever())
    var inProgress: Boolean by mutableStateOf(false)
    /** Live one-line status of the in-flight pass — overwritten between events. */
    var statusLine: String by mutableStateOf("")
    /**
     * "Done: X connected, Y skipped, Z errors" from the most recently completed pass. Held
     * across passes so the user can still inspect prior errors after a "Retry forever" loop has
     * already moved on to the next pass.
     */
    var lastDoneSummary: String by mutableStateOf("")
    /** Devices that have successfully completed GATT enumeration during the latest run. */
    var connectedDevices: List<DeviceData> by mutableStateOf(emptyList())
    /** Devices whose latest attempt ended in ERROR or TIMEOUT, with the captured failure reason. */
    var errorDetails: List<ErrorEntry> by mutableStateOf(emptyList())
    /** Whether the persistent "Done: …" summary is expanded to reveal the per-device errors. */
    var errorsExpanded: Boolean by mutableStateOf(false)
    private var bulkJob: Job? = null
    /** Addresses already enumerated this session — never re-attempted under "Retry forever". */
    private val successfulAddresses: MutableSet<String> = mutableSetOf()

    /**
     * Live preview of devices the next "Connect to all" pass would attempt — visible + connectable
     * + not vendor-filtered. Updated as the BLE scanner's last-batch StateFlow re-emits and as the
     * user toggles vendor filters. [candidateVendorFiltered] tracks the count we'd skip.
     */
    var candidateDevices: List<DeviceData> by mutableStateOf(emptyList())
    var candidateVendorFiltered: Int by mutableStateOf(0)

    init {
        observeCandidates()
        startCandidatePolling()
    }

    private fun observeCandidates() {
        viewModelScope.launch {
            devicesRepository.observeLastBatch().collect { batch ->
                recomputeCandidates(batch)
            }
        }
    }

    /**
     * Re-poll the candidate count on a fixed cadence so the status line stays fresh even when
     * the BLE scanner's batch StateFlow hasn't re-emitted (e.g., the same set of devices is
     * still visible — value-equal lists don't trigger StateFlow). The visible-cadence is what
     * the user sees as "dynamically updating every 10 seconds".
     */
    private fun startCandidatePolling() {
        viewModelScope.launch {
            while (true) {
                delay(CANDIDATE_POLL_INTERVAL_MS)
                recomputeCandidates(devicesRepository.observeLastBatch().value)
            }
        }
    }

    private fun recomputeCandidates(batch: List<DeviceData>) {
        val connectable = batch.filter { it.isConnectable }
        var filtered = 0
        val keep = mutableListOf<DeviceData>()
        for (d in connectable) {
            if (vendorIdentifier.shouldSkip(d, bulkSkipApple, bulkSkipSamsung)) filtered++ else keep += d
        }
        candidateDevices = keep.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
        candidateVendorFiltered = filtered
    }

    data class ErrorEntry(val device: DeviceData, val outcome: BulkEnumerateGattInteractor.Outcome, val message: String?)

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

    fun onToggleErrorsExpanded() {
        errorsExpanded = !errorsExpanded
    }

    fun onConnectAllClick() {
        if (bulkJob?.isActive == true) {
            bulkJob?.cancel()
            return
        }
        // A fresh session — reset everything we accumulated for the previous one.
        connectedDevices = emptyList()
        errorDetails = emptyList()
        errorsExpanded = false
        lastDoneSummary = ""
        successfulAddresses.clear()
        bulkJob = viewModelScope.launch {
            inProgress = true
            statusLine = ""
            try {
                runEnumerationLoop()
            } catch (ce: CancellationException) {
                statusLine = "Cancelled"
                throw ce
            } catch (e: Throwable) {
                Timber.tag("ConnectAll").e(e)
                statusLine = "Failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                inProgress = false
            }
        }
    }

    /**
     * Runs the enumeration once. If [retryForever] is enabled, immediately starts another pass
     * after each finishes — but always with [successfulAddresses] excluded so previously-captured
     * peers are not re-attempted. The loop exits when the user cancels the surrounding job.
     */
    private suspend fun runEnumerationLoop() {
        var pass = 0
        while (true) {
            pass++
            // Per-pass error list — each round shows only the failures from that round.
            val passErrors = mutableListOf<ErrorEntry>()
            bulkEnumerateGattInteractor.execute(skipAddresses = successfulAddresses.toSet()).collect { progress ->
                when (progress) {
                    is BulkEnumerateGattInteractor.Progress.Started -> {
                        statusLine = if (progress.total == 0 && progress.skippedAdvFilter == 0) {
                            if (retryForever) "Pass $pass: nothing to attempt — waiting for new visible devices"
                            else "No connectable devices visible"
                        } else {
                            val passLabel = if (retryForever) "Pass $pass — " else ""
                            "${passLabel}Starting on ${progress.total} device${if (progress.total == 1) "" else "s"} " +
                                    "(${progress.skippedAdvFilter} pre-skipped)"
                        }
                    }
                    is BulkEnumerateGattInteractor.Progress.DeviceStarted -> {
                        statusLine = "Connecting ${progress.index + 1}/${progress.total}: " +
                                progress.device.buildDisplayName()
                    }
                    is BulkEnumerateGattInteractor.Progress.DeviceFinished -> {
                        statusLine = "${progress.index + 1}/${progress.total} ${progress.device.buildDisplayName()} → ${progress.outcome}"
                        when (progress.outcome) {
                            BulkEnumerateGattInteractor.Outcome.SUCCESS -> {
                                connectedDevices = connectedDevices + progress.device
                                successfulAddresses += progress.device.address.uppercase()
                            }
                            BulkEnumerateGattInteractor.Outcome.ERROR,
                            BulkEnumerateGattInteractor.Outcome.TIMEOUT,
                            -> {
                                passErrors += ErrorEntry(progress.device, progress.outcome, progress.errorMessage)
                            }
                            BulkEnumerateGattInteractor.Outcome.SKIPPED_VENDOR -> Unit
                        }
                    }
                    is BulkEnumerateGattInteractor.Progress.Done -> {
                        errorDetails = passErrors.toList()
                        lastDoneSummary = "Done: ${progress.succeeded} connected, " +
                                "${progress.advSkipped + progress.skippedVendor} skipped, " +
                                "${progress.errors} errors"
                        statusLine = lastDoneSummary
                    }
                }
            }
            if (!retryForever) return
            // Brief pause between passes so the BLE stack can settle and advertisements arrive
            // before we re-snapshot the visible-device list.
            delay(NEXT_PASS_DELAY_MS)
        }
    }

    fun onDeviceClick(device: DeviceData) {
        router.navigate(ScreenNavigationCommands.OpenDeviceDetailsScreen(device.address))
    }

    companion object {
        private const val NEXT_PASS_DELAY_MS = 1000L
        private const val CANDIDATE_POLL_INTERVAL_MS = 10_000L
    }
}
