package com.darkmentor.domain.interactor

import com.darkmentor.domain.model.DeviceData
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Pins the per-pass candidate-selection invariants that drive Connect All. Lives off the
 * extracted [BulkEnumerateCandidateSelection] object — exercising it through the full
 * [BulkEnumerateGattInteractor] would need a DevicesRepository / Flow / Bluetooth-helper
 * harness for one piece of pure logic.
 *
 * Each test pins one decision a future refactor could trip over:
 *   - already-enumerated devices skipped before vendor filtering (so the per-device retry
 *     budget isn't burned on a session-skip);
 *   - per-device retry cap honoured, even when the device has the strongest RSSI;
 *   - vendor skip applied last (Apple/Samsung filter from settings);
 *   - sort stable on RSSI desc with null sinking to the bottom (Connect All prefers strong
 *     signals because they're likeliest to enumerate quickly).
 */
class BulkEnumerateCandidateSelectionTest {

    private fun device(
        address: String,
        rssi: Int? = -50,
        isConnectable: Boolean = true,
    ): DeviceData = DeviceData(
        address = address,
        name = null,
        lastDetectTimeMs = 0,
        firstDetectTimeMs = 0,
        manufacturerInfo = null,
        detectCount = 1,
        customName = null,
        rssi = rssi,
        systemAddressType = null,
        deviceClass = null,
        isPaired = false,
        servicesUuids = emptyList(),
        rowDataEncoded = null,
        isConnectable = isConnectable,
    )

    /** Vendor-skip stub matching the listed addresses (uppercase). */
    private fun skipVendorFor(addresses: Set<String>): (DeviceData) -> Boolean = { d ->
        d.address.uppercase() in addresses.map { it.uppercase() }.toSet()
    }

    private val noVendorSkip: (DeviceData) -> Boolean = { false }

    // ---- selectFrozenCandidates: the actual queue the workers iterate.

    @Test
    fun `addresses already in normalizedSkipAddresses are filtered out`() {
        val a = device("AA:AA:AA:AA:AA:AA")
        val b = device("BB:BB:BB:BB:BB:BB")
        val c = device("CC:CC:CC:CC:CC:CC")
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(a, b, c),
            normalizedSkipAddresses = setOf("BB:BB:BB:BB:BB:BB"),
            attemptCounts = emptyMap(),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        // b is in the session skip set → excluded. a and c stay.
        assertEquals(listOf(a, c).map { it.address }, out.map { it.address })
    }

    @Test
    fun `skip-set lookup is case-insensitive on the queue side`() {
        // The interactor's caller normalises the skip set to uppercase before passing it in
        // ([BulkEnumerateGattInteractor.execute] does `skipAddresses.map { it.uppercase() }`).
        // The queue then uppercases each device's address before checking. Pin both halves —
        // a regression that drops the uppercase on either side would silently miss skips.
        val a = device("aa:bb:cc:dd:ee:ff")
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(a),
            normalizedSkipAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            attemptCounts = emptyMap(),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        assertTrue("Mixed-case device address must match the uppercase skip set", out.isEmpty())
    }

    @Test
    fun `device whose attempt count has reached the cap is dropped`() {
        val a = device("AA:AA:AA:AA:AA:AA")
        val b = device("BB:BB:BB:BB:BB:BB")
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(a, b),
            normalizedSkipAddresses = emptySet(),
            attemptCounts = mapOf("BB:BB:BB:BB:BB:BB" to 5),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        // b hit the cap (5 of 5). Cap is "less than", not "less than or equal".
        assertEquals(listOf(a), out)
    }

    @Test
    fun `device just under the cap is still included`() {
        val a = device("AA:AA:AA:AA:AA:AA")
        val b = device("BB:BB:BB:BB:BB:BB")
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(a, b),
            normalizedSkipAddresses = emptySet(),
            attemptCounts = mapOf("AA:AA:AA:AA:AA:AA" to 4, "BB:BB:BB:BB:BB:BB" to 0),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        assertEquals(setOf(a.address, b.address), out.map { it.address }.toSet())
    }

    @Test
    fun `vendor-skip predicate filters last so it cannot poison cap accounting`() {
        val a = device("AA:AA:AA:AA:AA:AA")
        val b = device("BB:BB:BB:BB:BB:BB")
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(a, b),
            normalizedSkipAddresses = emptySet(),
            attemptCounts = emptyMap(),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = skipVendorFor(setOf("BB:BB:BB:BB:BB:BB")),
        )
        // b is vendor-skipped → not in the queue.
        assertEquals(listOf(a), out)
    }

    @Test
    fun `sort puts strongest RSSI first and null RSSI at the bottom`() {
        val strong = device("AA:AA:AA:AA:AA:AA", rssi = -40)
        val weak = device("BB:BB:BB:BB:BB:BB", rssi = -90)
        val mid = device("CC:CC:CC:CC:CC:CC", rssi = -60)
        val unknown = device("DD:DD:DD:DD:DD:DD", rssi = null)
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = listOf(weak, unknown, strong, mid),
            normalizedSkipAddresses = emptySet(),
            attemptCounts = emptyMap(),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        // Strongest RSSI first (least-negative dBm); null sinks to the bottom because
        // `null ?: Int.MIN_VALUE` is the smallest possible "score" in the sort.
        assertEquals(
            listOf(strong, mid, weak, unknown).map { it.address },
            out.map { it.address },
        )
    }

    @Test
    fun `empty connectable list yields empty queue`() {
        // Smoke test: the empty-input path must not throw and must produce an empty list, not
        // null. Connect All initialises an ArrayDeque from this output every pass — a null
        // would NPE the worker pool in production.
        val out = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = emptyList(),
            normalizedSkipAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            attemptCounts = mapOf("FF:FF:FF:FF:FF:FF" to 100),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = noVendorSkip,
        )
        assertTrue(out.isEmpty())
    }

    // ---- countAdvSkipped: the "(N pre-skipped)" up-front number.

    @Test
    fun `countAdvSkipped excludes session-skipped devices so they aren't double-counted`() {
        // A device that's already enumerated this session AND vendor-skipped should count
        // exactly once (in the session skip — which the UI tracks separately). Otherwise the
        // up-front "(N pre-skipped)" would inflate as the session progresses.
        val sessionSkipped = device("AA:AA:AA:AA:AA:AA")  // also vendor-skipped below
        val vendorSkipped = device("BB:BB:BB:BB:BB:BB")
        val notSkipped = device("CC:CC:CC:CC:CC:CC")
        val n = BulkEnumerateCandidateSelection.countAdvSkipped(
            connectable = listOf(sessionSkipped, vendorSkipped, notSkipped),
            normalizedSkipAddresses = setOf("AA:AA:AA:AA:AA:AA"),
            shouldSkipVendor = skipVendorFor(setOf("AA:AA:AA:AA:AA:AA", "BB:BB:BB:BB:BB:BB")),
        )
        // Only `vendorSkipped` should count: `sessionSkipped` is excluded by the
        // session-skip clause first.
        assertEquals(1, n)
    }

    @Test
    fun `countAdvSkipped returns zero when no vendor toggle is active`() {
        val n = BulkEnumerateCandidateSelection.countAdvSkipped(
            connectable = listOf(device("AA:AA:AA:AA:AA:AA"), device("BB:BB:BB:BB:BB:BB")),
            normalizedSkipAddresses = emptySet(),
            shouldSkipVendor = noVendorSkip,
        )
        assertEquals(0, n)
    }

    // ---- countCandidatesBeforeAttemptCap: the floor for the live "X / Y" progress display.

    @Test
    fun `countCandidatesBeforeAttemptCap ignores attemptCounts unlike selectFrozenCandidates`() {
        // The "candidates before cap" counter exists specifically to provide a lower bound for
        // the multi-worker progress display: after a few passes, devices that hit their retry
        // cap drop out of [selectFrozenCandidates] but are still part of the conceptual
        // candidate pool. Ignoring [attemptCounts] here is deliberate.
        val a = device("AA:AA:AA:AA:AA:AA")
        val b = device("BB:BB:BB:BB:BB:BB")
        val n = BulkEnumerateCandidateSelection.countCandidatesBeforeAttemptCap(
            connectable = listOf(a, b),
            normalizedSkipAddresses = emptySet(),
            shouldSkipVendor = noVendorSkip,
        )
        // (We don't even pass attemptCounts here, by design — proves it isn't part of the
        // shape this counter cares about.)
        assertEquals(2, n)
    }

    @Test
    fun `countCandidatesBeforeAttemptCap excludes session skips and vendor skips`() {
        val sessionSkipped = device("AA:AA:AA:AA:AA:AA")
        val vendorSkipped = device("BB:BB:BB:BB:BB:BB")
        val candidate = device("CC:CC:CC:CC:CC:CC")
        val n = BulkEnumerateCandidateSelection.countCandidatesBeforeAttemptCap(
            connectable = listOf(sessionSkipped, vendorSkipped, candidate),
            normalizedSkipAddresses = setOf("AA:AA:AA:AA:AA:AA"),
            shouldSkipVendor = skipVendorFor(setOf("BB:BB:BB:BB:BB:BB")),
        )
        assertEquals(1, n)
    }

    // ---- Cross-counter consistency

    @Test
    fun `the three derivations partition the connectable list cleanly`() {
        // Invariant the production code relies on (without spelling it out): for any input
        // list, every device is in exactly one of: session-skipped, vendor-skipped (counted by
        // countAdvSkipped), candidate (counted by countCandidatesBeforeAttemptCap). The
        // frozen-candidates queue is a subset of the third bucket. Pin the partition so a
        // future refactor that introduces a 4th bucket has to update this test consciously.
        val sessionSkipped = device("AA:AA:AA:AA:AA:AA", rssi = -10)
        val vendorSkipped = device("BB:BB:BB:BB:BB:BB", rssi = -20)
        val candidate1 = device("CC:CC:CC:CC:CC:CC", rssi = -30)
        val candidate2OverCap = device("DD:DD:DD:DD:DD:DD", rssi = -40)
        val all = listOf(sessionSkipped, vendorSkipped, candidate1, candidate2OverCap)
        val sessionSkip = setOf("AA:AA:AA:AA:AA:AA")
        val skipVendor = skipVendorFor(setOf("BB:BB:BB:BB:BB:BB"))

        val frozen = BulkEnumerateCandidateSelection.selectFrozenCandidates(
            connectable = all,
            normalizedSkipAddresses = sessionSkip,
            attemptCounts = mapOf("DD:DD:DD:DD:DD:DD" to 5),
            maxAttemptsPerDevice = 5,
            shouldSkipVendor = skipVendor,
        )
        val advSkipped = BulkEnumerateCandidateSelection.countAdvSkipped(
            connectable = all,
            normalizedSkipAddresses = sessionSkip,
            shouldSkipVendor = skipVendor,
        )
        val beforeCap = BulkEnumerateCandidateSelection.countCandidatesBeforeAttemptCap(
            connectable = all,
            normalizedSkipAddresses = sessionSkip,
            shouldSkipVendor = skipVendor,
        )
        // Frozen queue: only candidate1 (candidate2OverCap is at the cap).
        assertEquals(listOf(candidate1.address), frozen.map { it.address })
        // Vendor-skipped (excluding session-skipped): just vendorSkipped.
        assertEquals(1, advSkipped)
        // Candidates before cap: candidate1 + candidate2OverCap = 2.
        assertEquals(2, beforeCap)
        // Sanity check: session(1) + vendor(1) + before-cap(2) = 4 = all.size.
        assertEquals(all.size, sessionSkip.size + advSkipped + beforeCap)
    }
}
