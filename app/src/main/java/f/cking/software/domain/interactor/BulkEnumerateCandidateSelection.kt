package f.cking.software.domain.interactor

import f.cking.software.domain.model.DeviceData

/**
 * Pure candidate-selection logic for [BulkEnumerateGattInteractor]. Extracted so it can be
 * exercised under JVM unit tests without spinning up the full interactor's dependency tree
 * (DevicesRepository / Flow plumbing / settings / vendor identifier / Bluetooth helpers).
 *
 * Centralising this logic also makes the per-pass invariants explicit at the type level:
 *
 *   - Devices already enumerated in this session ([normalizedSkipAddresses]) never get
 *     re-attempted, even if they re-appear at the top of the RSSI list.
 *   - The session-wide [attemptCounts] map enforces a per-device retry cap so a stubbornly
 *     unconnectable peer can't monopolise pass after pass.
 *   - Vendor skip ([shouldSkipVendor]) is applied last so the cap and skip-set are always
 *     considered first; flipping their order would let a device the user wants vendor-skipped
 *     still count against the per-device retry budget.
 *   - Sorting is by RSSI desc, with `null` RSSI sinking to the bottom — Connect-All prefers
 *     the strongest-signal devices first because they're likeliest to enumerate without timing
 *     out.
 *
 * Both the frozen-candidate queue and the two pre-pass counters consumed by the Started
 * progress event are derived from the same input list, so a single source of truth for the
 * normalisation rules avoids subtle drift between "what the UI says is queued" and "what
 * the workers actually attempt".
 */
internal object BulkEnumerateCandidateSelection {

    /**
     * Build the per-pass frozen queue. The result is the exact list (in pop order) that the
     * worker pool will iterate; an [ArrayDeque] over this list is what production wraps.
     */
    fun selectFrozenCandidates(
        connectable: List<DeviceData>,
        normalizedSkipAddresses: Set<String>,
        attemptCounts: Map<String, Int>,
        maxAttemptsPerDevice: Int,
        shouldSkipVendor: (DeviceData) -> Boolean,
    ): List<DeviceData> = connectable
        .filter { it.address.uppercase() !in normalizedSkipAddresses }
        .filter { (attemptCounts[it.address.uppercase()] ?: 0) < maxAttemptsPerDevice }
        .filterNot(shouldSkipVendor)
        .sortedByDescending { it.rssi ?: Int.MIN_VALUE }

    /**
     * How many of the connectable devices are vendor-skipped (Apple/Samsung when their
     * respective toggles are on). Reported up-front via [Progress.Started.skippedAdvFilter] so
     * the user can see "(N pre-skipped)" before any worker fires. Excludes anything already
     * in the session's enumerated-skip set, which gets its own count.
     */
    fun countAdvSkipped(
        connectable: List<DeviceData>,
        normalizedSkipAddresses: Set<String>,
        shouldSkipVendor: (DeviceData) -> Boolean,
    ): Int = connectable.count { d ->
        d.address.uppercase() !in normalizedSkipAddresses && shouldSkipVendor(d)
    }

    /**
     * Total candidates *before* the per-device retry cap is applied. Different from
     * [selectFrozenCandidates] in that it ignores [attemptCounts] — used as the lower-bound
     * "potential" count for the multi-worker progress display so a value driven by
     * `attemptIndex` can still climb above this floor when devices appear mid-pass.
     */
    fun countCandidatesBeforeAttemptCap(
        connectable: List<DeviceData>,
        normalizedSkipAddresses: Set<String>,
        shouldSkipVendor: (DeviceData) -> Boolean,
    ): Int = connectable.count { d ->
        d.address.uppercase() !in normalizedSkipAddresses && !shouldSkipVendor(d)
    }
}
