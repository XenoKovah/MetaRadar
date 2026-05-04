package f.cking.software.ui.connectall

import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.BulkEnumerateGattInteractor
import f.cking.software.domain.model.DeviceData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * App-singleton "Connect All session" — owns the bulk-enumeration loop and its accumulated
 * state outside any ViewModel so the loop can be started by either the UI (the Connect All
 * pane's "Connect to all" button) or by [f.cking.software.service.BootBroadcastReceiver] at
 * device boot when "Launch Connect All at system startup" is enabled.
 *
 * Two key decisions live here:
 *
 *  1. The session-wide [successfulAddresses] and [attemptCounts] survive across passes within
 *     one call to [start]: a device that's been enumerated once is never re-attempted, and a
 *     device that's failed [BulkEnumerateGattInteractor.MAX_ATTEMPTS_PER_DEVICE] times is
 *     skipped for the rest of the session.
 *
 *  2. [isActive] is consulted by [ConnectAllViewModel.onPaneHidden] so the foreground
 *     scan service doesn't get torn down when the user navigates away from the Connect All
 *     pane while a boot-started session is still running. Without that check, leaving the
 *     pane would kill the boot-started session's underlying scan.
 */
class ConnectAllSession(
    private val applicationScope: CoroutineScope,
    private val bulkEnumerateGattInteractor: BulkEnumerateGattInteractor,
    private val settingsRepository: SettingsRepository,
) {

    data class ErrorEntry(
        val device: DeviceData,
        val outcome: BulkEnumerateGattInteractor.Outcome,
        val message: String?,
    )

    /** Snapshot of everything the Connect All pane renders — observed by the ViewModel. */
    data class State(
        val inProgress: Boolean = false,
        val statusLine: String = "",
        val lastDoneSummary: String = "",
        val connectedDevices: List<DeviceData> = emptyList(),
        val errorDetails: List<ErrorEntry> = emptyList(),
        val errorsExpanded: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val successfulAddresses: MutableSet<String> = mutableSetOf()
    private val attemptCounts: MutableMap<String, Int> = mutableMapOf()
    private var bulkJob: Job? = null

    val isActive: Boolean get() = bulkJob?.isActive == true

    /**
     * Begin a new pass. Idempotent if already running. [retryForever] is captured at start;
     * later toggle changes don't affect an in-flight session (matches the prior VM behaviour).
     */
    fun start(retryForever: Boolean) {
        if (isActive) return
        // Fresh session: reset accumulated per-session bookkeeping.
        successfulAddresses.clear()
        attemptCounts.clear()
        _state.value = State(inProgress = true, statusLine = "")
        bulkJob = applicationScope.launch {
            try {
                runEnumerationLoop(retryForever)
            } catch (ce: CancellationException) {
                _state.update { it.copy(statusLine = "Cancelled") }
                throw ce
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "Connect All session failed")
                _state.update { it.copy(statusLine = "Failed: ${e.message ?: e::class.java.simpleName}") }
            } finally {
                _state.update { it.copy(inProgress = false) }
            }
        }
    }

    fun stop() {
        bulkJob?.cancel()
    }

    fun toggleErrorsExpanded() {
        _state.update { it.copy(errorsExpanded = !it.errorsExpanded) }
    }

    private suspend fun runEnumerationLoop(retryForever: Boolean) {
        var pass = 0
        while (true) {
            pass++
            val passErrors = mutableListOf<ErrorEntry>()
            bulkEnumerateGattInteractor.execute(
                skipAddresses = successfulAddresses.toSet(),
                attemptCounts = attemptCounts,
            ).collect { progress ->
                when (progress) {
                    is BulkEnumerateGattInteractor.Progress.Started -> {
                        val text = if (progress.total == 0 && progress.skippedAdvFilter == 0) {
                            if (retryForever) "Pass $pass: nothing to attempt — waiting for new visible devices"
                            else "No connectable devices visible"
                        } else {
                            val passLabel = if (retryForever) "Pass $pass — " else ""
                            "${passLabel}Starting on ${progress.total} device${if (progress.total == 1) "" else "s"} " +
                                    "(${progress.skippedAdvFilter} pre-skipped)"
                        }
                        _state.update { it.copy(statusLine = text) }
                    }
                    is BulkEnumerateGattInteractor.Progress.DeviceStarted -> {
                        _state.update {
                            it.copy(
                                statusLine = "Connecting ${progress.index + 1}/${progress.total}: " +
                                        progress.device.buildDisplayName()
                            )
                        }
                    }
                    is BulkEnumerateGattInteractor.Progress.DeviceFinished -> {
                        val text = "${progress.index + 1}/${progress.total} " +
                                "${progress.device.buildDisplayName()} → ${progress.outcome}"
                        when (progress.outcome) {
                            // SDP_SUCCESS is BR/EDR-only — same UI treatment as SUCCESS (the
                            // device captured something useful), but distinct in the outcome
                            // enum so consumers can split full-GATT from SDP-only summaries.
                            BulkEnumerateGattInteractor.Outcome.SUCCESS,
                            BulkEnumerateGattInteractor.Outcome.SDP_SUCCESS,
                            -> {
                                successfulAddresses += progress.device.address.uppercase()
                                _state.update {
                                    it.copy(
                                        statusLine = text,
                                        connectedDevices = it.connectedDevices + progress.device,
                                    )
                                }
                            }
                            BulkEnumerateGattInteractor.Outcome.ERROR,
                            BulkEnumerateGattInteractor.Outcome.TIMEOUT,
                            BulkEnumerateGattInteractor.Outcome.SDP_TIMEOUT,
                            -> {
                                passErrors += ErrorEntry(progress.device, progress.outcome, progress.errorMessage)
                                _state.update { it.copy(statusLine = text) }
                            }
                            BulkEnumerateGattInteractor.Outcome.SKIPPED_VENDOR -> {
                                _state.update { it.copy(statusLine = text) }
                            }
                        }
                    }
                    is BulkEnumerateGattInteractor.Progress.Done -> {
                        val summary = "Done: ${progress.succeeded} connected, " +
                                "${progress.advSkipped + progress.skippedVendor} skipped, " +
                                "${progress.errors} errors"
                        _state.update {
                            it.copy(
                                lastDoneSummary = summary,
                                statusLine = summary,
                                errorDetails = passErrors.toList(),
                            )
                        }
                    }
                }
            }
            if (!retryForever) return
            delay(NEXT_PASS_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "ConnectAllSession"
        private const val NEXT_PASS_DELAY_MS = 1000L
    }
}
