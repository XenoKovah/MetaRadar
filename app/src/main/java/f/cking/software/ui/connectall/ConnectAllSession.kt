package f.cking.software.ui.connectall

import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.BulkEnumerateGattInteractor
import f.cking.software.domain.model.DeviceData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Continuous-mode invariants:
 *
 *  1. Per-session [successfulAddresses] and [attemptCounts] survive for the entire run: a
 *     device that's been enumerated once is never re-attempted, and a device that's failed
 *     [BulkEnumerateGattInteractor.MAX_ATTEMPTS_PER_DEVICE] times is moved into the "too many
 *     attempts" category and skipped for the rest of the session.
 *
 *  2. [isActive] is consulted by [ConnectAllViewModel.onPaneHidden] so the foreground scan
 *     service doesn't get torn down when the user navigates away from the Connect All pane
 *     while a boot-started session is still running.
 *
 *  3. The three result categories ([State.connected] / [State.errors] / [State.tooManyAttempts])
 *     are running totals, not per-pass tallies. Success moves a device into [connected] AND
 *     removes any prior error/too-many entry for the same address — a peer that finally
 *     enumerates after a few retries should appear in exactly one bucket.
 */
class ConnectAllSession(
    private val applicationScope: CoroutineScope,
    private val bulkEnumerateGattInteractor: BulkEnumerateGattInteractor,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Most-recent-first list invariant: each list is mutated by removing any prior entry for
     * the device's address and inserting the new entry at the head. So index 0 is always the
     * latest event for that bucket.
     */
    data class ConnectedEntry(
        val device: DeviceData,
        val outcome: BulkEnumerateGattInteractor.Outcome,
    )

    data class ErrorEntry(
        val device: DeviceData,
        val outcome: BulkEnumerateGattInteractor.Outcome,
        val message: String?,
        val attempts: Int,
    )

    data class TooManyAttemptsEntry(
        val device: DeviceData,
        val attempts: Int,
        val lastError: String?,
    )

    /** Snapshot of everything the Connect All pane renders — observed by the ViewModel. */
    data class State(
        val inProgress: Boolean = false,
        /** Top-line headline ("Running on N devices…", or the latest finish line). */
        val statusLine: String = "",
        /**
         * Per-worker-slot in-flight status. Key = slot id (0..3 = LE workers, 4 = BR/EDR
         * worker per [BulkEnumerateGattInteractor.BLE_PARALLELISM] / [BREDR_PARALLELISM]).
         * Value = the "Connecting BDADDR Name…" line for that slot. Empty when the worker
         * is between attempts.
         */
        val inFlightBySlot: Map<Int, String> = emptyMap(),

        // Three running-total categories — most-recent-first within each list.
        val connected: List<ConnectedEntry> = emptyList(),
        val errors: List<ErrorEntry> = emptyList(),
        val tooManyAttempts: List<TooManyAttemptsEntry> = emptyList(),

        val connectedExpanded: Boolean = false,
        val errorsExpanded: Boolean = false,
        val tooManyAttemptsExpanded: Boolean = false,
    ) {
        /**
         * Backwards-compat surface for the rest of the app (Devices tab "GATT" filter, etc.)
         * which previously read [connectedDevices] directly off the session state. Implemented
         * as a derived view so the session has a single source of truth — [connected].
         */
        val connectedDevices: List<DeviceData> get() = connected.map { it.device }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val successfulAddresses: MutableSet<String> = mutableSetOf()
    private val attemptCounts: MutableMap<String, Int> = mutableMapOf()
    private var bulkJob: Job? = null

    val isActive: Boolean get() = bulkJob?.isActive == true

    /**
     * Begin a new run. Idempotent if already running. [retryForever] is captured at start;
     * later toggle changes don't affect an in-flight session (matches the prior VM behaviour).
     *
     * When [retryForever] is true, the underlying interactor runs in continuous mode and
     * never voluntarily exits — this session lives until [stop] is called. When false, one
     * pass runs, the workers drain the initial pool, and the session ends naturally.
     */
    fun start(retryForever: Boolean) {
        if (isActive) return
        successfulAddresses.clear()
        attemptCounts.clear()
        _state.value = State(inProgress = true, statusLine = "")
        bulkJob = applicationScope.launch {
            try {
                runEnumeration(retryForever)
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

    fun toggleConnectedExpanded() {
        _state.update { it.copy(connectedExpanded = !it.connectedExpanded) }
    }

    fun toggleErrorsExpanded() {
        _state.update { it.copy(errorsExpanded = !it.errorsExpanded) }
    }

    fun toggleTooManyAttemptsExpanded() {
        _state.update { it.copy(tooManyAttemptsExpanded = !it.tooManyAttemptsExpanded) }
    }

    private suspend fun runEnumeration(retryForever: Boolean) {
        bulkEnumerateGattInteractor.execute(
            skipAddresses = successfulAddresses.toSet(),
            attemptCounts = attemptCounts,
            continuous = retryForever,
        ).collect { progress ->
            when (progress) {
                is BulkEnumerateGattInteractor.Progress.Started -> handleStarted(progress, retryForever)
                is BulkEnumerateGattInteractor.Progress.DeviceStarted -> handleDeviceStarted(progress)
                is BulkEnumerateGattInteractor.Progress.DeviceFinished -> handleDeviceFinished(progress)
                is BulkEnumerateGattInteractor.Progress.Done -> handleDone(progress)
            }
        }
    }

    private fun handleStarted(progress: BulkEnumerateGattInteractor.Progress.Started, retryForever: Boolean) {
        val text = if (progress.total == 0 && progress.skippedAdvFilter == 0) {
            if (retryForever) "Waiting for connectable devices to appear" else "No connectable devices visible"
        } else {
            val noun = if (progress.total == 1) "device" else "devices"
            val skipNote = if (progress.skippedAdvFilter > 0) " (${progress.skippedAdvFilter} pre-skipped)" else ""
            if (retryForever) "Running continuously — ${progress.total} $noun queued$skipNote"
            else "Starting on ${progress.total} $noun$skipNote"
        }
        _state.update { it.copy(statusLine = text) }
    }

    private fun handleDeviceStarted(progress: BulkEnumerateGattInteractor.Progress.DeviceStarted) {
        val slotLabel = "Connecting ${progress.index + 1}/${progress.total}: ${progress.device.buildDisplayName()}"
        _state.update {
            it.copy(inFlightBySlot = it.inFlightBySlot + (progress.slotId to slotLabel))
        }
    }

    private fun handleDeviceFinished(progress: BulkEnumerateGattInteractor.Progress.DeviceFinished) {
        val text = "${progress.index + 1}/${progress.total} ${progress.device.buildDisplayName()} → ${progress.outcome}"
        val key = progress.device.address.uppercase()
        when (progress.outcome) {
            BulkEnumerateGattInteractor.Outcome.SUCCESS,
            BulkEnumerateGattInteractor.Outcome.SDP_SUCCESS -> {
                successfulAddresses += key
                _state.update { s ->
                    s.copy(
                        statusLine = text,
                        inFlightBySlot = s.inFlightBySlot - progress.slotId,
                        // Move into [connected]; remove any prior error / too-many entry for the
                        // same address so the device appears in exactly one bucket.
                        connected = prepend(s.connected, ConnectedEntry(progress.device, progress.outcome)) { it.device.address.uppercase() },
                        errors = s.errors.filterNot { it.device.address.uppercase() == key },
                        tooManyAttempts = s.tooManyAttempts.filterNot { it.device.address.uppercase() == key },
                    )
                }
            }
            BulkEnumerateGattInteractor.Outcome.ERROR,
            BulkEnumerateGattInteractor.Outcome.TIMEOUT,
            BulkEnumerateGattInteractor.Outcome.SDP_TIMEOUT -> {
                val attempts = attemptCounts[key] ?: 0
                val capped = attempts >= BulkEnumerateGattInteractor.MAX_ATTEMPTS_PER_DEVICE
                _state.update { s ->
                    if (capped) {
                        s.copy(
                            statusLine = text,
                            inFlightBySlot = s.inFlightBySlot - progress.slotId,
                            // Promote: remove from errors (if there) and add to too-many.
                            errors = s.errors.filterNot { it.device.address.uppercase() == key },
                            tooManyAttempts = prepend(
                                s.tooManyAttempts,
                                TooManyAttemptsEntry(progress.device, attempts, progress.errorMessage),
                            ) { it.device.address.uppercase() },
                        )
                    } else {
                        s.copy(
                            statusLine = text,
                            inFlightBySlot = s.inFlightBySlot - progress.slotId,
                            errors = prepend(
                                s.errors,
                                ErrorEntry(progress.device, progress.outcome, progress.errorMessage, attempts),
                            ) { it.device.address.uppercase() },
                        )
                    }
                }
            }
            BulkEnumerateGattInteractor.Outcome.SKIPPED_VENDOR -> {
                // Vendor skip is a user-configured filter, not a connection outcome — keep
                // it out of all three buckets. Just clear the slot.
                _state.update { it.copy(statusLine = text, inFlightBySlot = it.inFlightBySlot - progress.slotId) }
            }
        }
    }

    private fun handleDone(progress: BulkEnumerateGattInteractor.Progress.Done) {
        // Only emitted in one-shot (non-continuous) mode. Just clear straggler in-flight rows
        // and update the headline to a finished-on-N summary; the three category counts are
        // already correct from the per-device events above.
        val noun = if (progress.total == 1) "device" else "devices"
        _state.update {
            it.copy(
                statusLine = "Finished — ${progress.total} $noun attempted",
                inFlightBySlot = emptyMap(),
            )
        }
    }

    /**
     * Prepend [entry] to [list] after removing any prior entry whose [keyOf] matches. Used by
     * each category bucket to maintain the most-recent-first invariant while keeping at most
     * one entry per device address.
     */
    private inline fun <T> prepend(list: List<T>, entry: T, keyOf: (T) -> String): List<T> {
        val key = keyOf(entry)
        return listOf(entry) + list.filterNot { keyOf(it) == key }
    }

    companion object {
        private const val TAG = "ConnectAllSession"
    }
}
