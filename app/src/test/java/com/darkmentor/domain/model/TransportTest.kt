package com.darkmentor.domain.model

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class TransportTest {

    // ---- fromAndroidDeviceType: maps the raw android.bluetooth.BluetoothDevice.DEVICE_TYPE_*
    // constants without leaking those imports into the domain layer. Stable per the platform.
    // After the v24→v25 schema migration there is no UNKNOWN member — anything Android can't
    // classify falls back to LE since the only callers of this builder are LE scans (rawData
    // present) and BR/EDR inquiries (deviceType == 1 explicitly).

    @Test
    fun `device_type_classic maps to BREDR`() {
        // BluetoothDevice.DEVICE_TYPE_CLASSIC == 1
        assertEquals(Transport.BREDR, Transport.fromAndroidDeviceType(1))
    }

    @Test
    fun `device_type_le maps to LE`() {
        // BluetoothDevice.DEVICE_TYPE_LE == 2
        assertEquals(Transport.LE, Transport.fromAndroidDeviceType(2))
    }

    @Test
    fun `device_type_dual maps to DUAL`() {
        // BluetoothDevice.DEVICE_TYPE_DUAL == 3
        assertEquals(Transport.DUAL, Transport.fromAndroidDeviceType(3))
    }

    @Test
    fun `device_type_unknown and null fall back to LE`() {
        // BluetoothDevice.DEVICE_TYPE_UNKNOWN == 0
        assertEquals(Transport.LE, Transport.fromAndroidDeviceType(0))
        assertEquals(Transport.LE, Transport.fromAndroidDeviceType(null))
        // Unrecognized future value also defaults to LE rather than throwing.
        assertEquals(Transport.LE, Transport.fromAndroidDeviceType(99))
    }

    // ---- merge: combines a stored Transport with a freshly-observed one when the same address
    // is re-detected.

    @Test
    fun `merge identical transports returns same`() {
        for (t in Transport.entries) {
            assertEquals(t, Transport.merge(t, t))
        }
    }

    @Test
    fun `merge LE with BREDR yields DUAL`() {
        assertEquals(Transport.DUAL, Transport.merge(Transport.LE, Transport.BREDR))
        assertEquals(Transport.DUAL, Transport.merge(Transport.BREDR, Transport.LE))
    }

    @Test
    fun `merge with DUAL stays DUAL`() {
        assertEquals(Transport.DUAL, Transport.merge(Transport.DUAL, Transport.LE))
        assertEquals(Transport.DUAL, Transport.merge(Transport.LE, Transport.DUAL))
        assertEquals(Transport.DUAL, Transport.merge(Transport.DUAL, Transport.BREDR))
        assertEquals(Transport.DUAL, Transport.merge(Transport.BREDR, Transport.DUAL))
    }

    // ---- Capability predicates

    @Test
    fun `supportsGattOverLe is true for LE and DUAL only`() {
        assertEquals(true, Transport.LE.supportsGattOverLe())
        assertEquals(true, Transport.DUAL.supportsGattOverLe())
        assertEquals(false, Transport.BREDR.supportsGattOverLe())
    }

    @Test
    fun `isBrEdrOnly is true only for BREDR`() {
        assertEquals(true, Transport.BREDR.isBrEdrOnly())
        assertEquals(false, Transport.LE.isBrEdrOnly())
        assertEquals(false, Transport.DUAL.isBrEdrOnly())
    }

    // ---- Ordinal stability — these ordinals back the persisted DeviceEntity.transport column;
    // reordering would corrupt the database. Pin them with explicit asserts.

    @Test
    fun `ordinals are stable for persistence`() {
        assertEquals(0, Transport.LE.ordinal)
        assertEquals(1, Transport.BREDR.ordinal)
        assertEquals(2, Transport.DUAL.ordinal)
    }

    @Test
    fun `short labels match expected UI badge text`() {
        assertEquals("LE", Transport.LE.shortLabel())
        assertEquals("BR", Transport.BREDR.shortLabel())
        assertEquals("Dual", Transport.DUAL.shortLabel())
    }

    // ---- matchingOrdinalsForFilter: the single source of truth for the BTC-chip
    // BREDR-includes-DUAL contract. Pinning these explicitly catches any future regression
    // where someone hardcodes `2` (or `3`) on either of the two delegating call sites
    // (DeviceFilterSqlBuilder + FilterCheckerImpl).

    @Test
    fun `BREDR filter with includeDual broadens to BREDR plus DUAL ordinals`() {
        val ordinals = Transport.matchingOrdinalsForFilter(
            filterOrdinal = Transport.BREDR.ordinal,
            includeDual = true,
        )
        // Order matters for SQL `IN (?, ?)` arg binding — the explicit transport first,
        // DUAL appended. Pinning the order keeps the SQL placeholders aligned with the args.
        assertEquals(listOf(Transport.BREDR.ordinal, Transport.DUAL.ordinal), ordinals)
    }

    @Test
    fun `LE filter with includeDual broadens to LE plus DUAL ordinals`() {
        val ordinals = Transport.matchingOrdinalsForFilter(
            filterOrdinal = Transport.LE.ordinal,
            includeDual = true,
        )
        assertEquals(listOf(Transport.LE.ordinal, Transport.DUAL.ordinal), ordinals)
    }

    @Test
    fun `BREDR filter without includeDual is strict equality`() {
        val ordinals = Transport.matchingOrdinalsForFilter(
            filterOrdinal = Transport.BREDR.ordinal,
            includeDual = false,
        )
        assertEquals(listOf(Transport.BREDR.ordinal), ordinals)
    }

    @Test
    fun `LE filter without includeDual is strict equality`() {
        val ordinals = Transport.matchingOrdinalsForFilter(
            filterOrdinal = Transport.LE.ordinal,
            includeDual = false,
        )
        assertEquals(listOf(Transport.LE.ordinal), ordinals)
    }

    @Test
    fun `DUAL filter never broadens regardless of includeDual flag`() {
        // includeDual is a no-op when the filter already targets DUAL itself — no broader
        // category exists. Both branches must collapse to the single-ordinal form so the
        // SQL clause becomes `transport = ?` (matching the in-memory checker's
        // single-element `in` test).
        assertEquals(
            listOf(Transport.DUAL.ordinal),
            Transport.matchingOrdinalsForFilter(Transport.DUAL.ordinal, includeDual = true),
        )
        assertEquals(
            listOf(Transport.DUAL.ordinal),
            Transport.matchingOrdinalsForFilter(Transport.DUAL.ordinal, includeDual = false),
        )
    }

    @Test
    fun `DUAL ordinal is read from enum not hardcoded - regression guard for stale-3 bug`() {
        // The pre-extraction code hardcoded `3` on both call sites — a remnant of the
        // pre-migration enum that included UNKNOWN=0. Migration 24→25 collapsed UNKNOWN, so
        // DUAL became ordinal 2. The extraction makes the enum-derivation unmissable; this
        // test pins it once more so even a hypothetical future "let me just inline this"
        // regression breaks visibly.
        assertEquals(2, Transport.DUAL.ordinal)
        // And: the helper's broadened path includes `2`, not `3`.
        val broadened = Transport.matchingOrdinalsForFilter(Transport.BREDR.ordinal, includeDual = true)
        assertTrue("DUAL ordinal must be present in broadened set", 2 in broadened)
        assertFalse("Stale-3 must not appear", 3 in broadened)
    }
}
