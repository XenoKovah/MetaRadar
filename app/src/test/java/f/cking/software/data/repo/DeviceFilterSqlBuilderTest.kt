package f.cking.software.data.repo

import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.model.ManufacturerInfo
import f.cking.software.domain.model.Transport
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Pins the SQL pushdown emitted by [DeviceFilterSqlBuilder] for each [DeviceFilter] sub-class
 * that the production query path can push into Room. The builder is on the hot path of the
 * Devices tab — at M=200k devices, an unpushable filter forces a 200k-row scan in Kotlin, so a
 * silent regression that turns a previously-pushable filter into [Result.NotPushable] would be
 * a major performance cliff with no obvious test signal short of a profiler.
 *
 * The transport-filter assertions in particular guard the BTC-includes-DUAL contract documented
 * on [Transport.matchingOrdinalsForFilter]: the BTC quick-filter chip must surface dual-mode
 * devices alongside BR/EDR-only ones, and the SQL clause's args must align with the placeholder
 * count (one regression in the past hardcoded `3` as the DUAL ordinal — pre-migration value —
 * which silently filtered out every dual-mode device in the corpus).
 */
class DeviceFilterSqlBuilderTest {

    // ---- TransportFilter

    @Test
    fun `BTC chip with includeDual emits IN clause with BREDR and DUAL ordinals in order`() {
        // The BTC filter chip on the Devices tab uses TransportFilter(BREDR, includeDual=true).
        // Must broaden to include DUAL — anything seen on the Classic radio matches, dual-mode
        // or BR/EDR-only.
        val filter = DeviceFilter.TransportFilter(
            transportOrdinal = Transport.BREDR.ordinal,
            includeDual = true,
        )
        val result = DeviceFilterSqlBuilder.toSql(filter)
        assertTrue("transport filter must be SQL-pushable", result is DeviceFilterSqlBuilder.Result.Pushable)
        result as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("transport IN (?, ?)", result.whereClause)
        assertEquals(
            "Args must list BREDR ordinal first then DUAL — order has to match placeholder positions",
            listOf<Any?>(Transport.BREDR.ordinal, Transport.DUAL.ordinal),
            result.args,
        )
    }

    @Test
    fun `BTC chip without includeDual emits strict equality clause`() {
        val filter = DeviceFilter.TransportFilter(
            transportOrdinal = Transport.BREDR.ordinal,
            includeDual = false,
        )
        val result = DeviceFilterSqlBuilder.toSql(filter) as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("transport = ?", result.whereClause)
        assertEquals(listOf<Any?>(Transport.BREDR.ordinal), result.args)
    }

    @Test
    fun `LE filter with includeDual broadens to LE plus DUAL`() {
        val filter = DeviceFilter.TransportFilter(Transport.LE.ordinal, includeDual = true)
        val result = DeviceFilterSqlBuilder.toSql(filter) as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("transport IN (?, ?)", result.whereClause)
        assertEquals(listOf<Any?>(Transport.LE.ordinal, Transport.DUAL.ordinal), result.args)
    }

    @Test
    fun `DUAL-targeted filter never broadens regardless of includeDual flag`() {
        // No category broader than DUAL exists, so the strict equality form must be emitted in
        // both branches — the helper guarantees this; the SQL builder must reflect it. Catches
        // a hypothetical regression where someone naively wraps every includeDual=true case in
        // an IN clause and ends up emitting `transport IN (?, ?)` with `[2, 2]`.
        val withDual = DeviceFilter.TransportFilter(Transport.DUAL.ordinal, includeDual = true)
        val withoutDual = DeviceFilter.TransportFilter(Transport.DUAL.ordinal, includeDual = false)
        for (f in listOf(withDual, withoutDual)) {
            val r = DeviceFilterSqlBuilder.toSql(f) as DeviceFilterSqlBuilder.Result.Pushable
            assertEquals("transport = ?", r.whereClause)
            assertEquals(listOf<Any?>(Transport.DUAL.ordinal), r.args)
        }
    }

    // ---- IsPaired / Address / Manufacturer / detection-interval baseline pushdowns

    @Test
    fun `is_paired filter pushes a single int arg`() {
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.IsPaired(isPaired = true))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("is_paired = ?", r.whereClause)
        assertEquals(listOf<Any?>(1), r.args)

        val rFalse = DeviceFilterSqlBuilder.toSql(DeviceFilter.IsPaired(isPaired = false))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals(listOf<Any?>(0), rFalse.args)
    }

    @Test
    fun `address filter pushes the raw address string`() {
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.Address("AA:BB:CC:DD:EE:FF"))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("address = ?", r.whereClause)
        assertEquals(listOf<Any?>("AA:BB:CC:DD:EE:FF"), r.args)
    }

    @Test
    fun `interval filters use BETWEEN with explicit bounds`() {
        val last = DeviceFilterSqlBuilder.toSql(DeviceFilter.LastDetectionInterval(from = 100, to = 200))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("last_detect_time_ms BETWEEN ? AND ?", last.whereClause)
        assertEquals(listOf<Any?>(100L, 200L), last.args)

        val first = DeviceFilterSqlBuilder.toSql(DeviceFilter.FirstDetectionInterval(from = 5, to = 10))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("first_detect_time_ms BETWEEN ? AND ?", first.whereClause)
        assertEquals(listOf<Any?>(5L, 10L), first.args)
    }

    @Test
    fun `non-Apple non-Samsung manufacturer pushes equality on manufacturer_id`() {
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.Manufacturer(manufacturerId = 0x0059))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("manufacturer_id = ?", r.whereClause)
        assertEquals(listOf<Any?>(0x0059), r.args)
    }

    @Test
    fun `Apple and Samsung manufacturer filters are not pushable - vendor identifier handles them`() {
        // Both broadened-vendor cases delegate to VendorIdentifier (iBeacon shape, OUI etc.),
        // which needs raw scan bytes that aren't queryable from SQL. Caught here so a refactor
        // that flattens the special case can't silently drop iBeacon-as-Apple matching.
        assertSame(
            DeviceFilterSqlBuilder.Result.NotPushable,
            DeviceFilterSqlBuilder.toSql(DeviceFilter.Manufacturer(ManufacturerInfo.APPLE_ID)),
        )
        assertSame(
            DeviceFilterSqlBuilder.Result.NotPushable,
            DeviceFilterSqlBuilder.toSql(DeviceFilter.Manufacturer(ManufacturerInfo.SAMSUNG_ID)),
        )
    }

    // ---- Name (LIKE + escape)

    @Test
    fun `Name filter wraps the term in percent-percent and uses ESCAPE clause`() {
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.Name("Pixel", ignoreCase = true))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("name LIKE ? ESCAPE '\\' COLLATE NOCASE", r.whereClause)
        assertEquals(listOf<Any?>("%Pixel%"), r.args)
    }

    @Test
    fun `Name filter escapes user-typed wildcard chars so they do not become SQL wildcards`() {
        // User searches for the literal substring "100% off"; SQL LIKE treats `%` as wildcard,
        // so without escaping it would match everything. The escape char is `\\` (backslash),
        // declared in the ESCAPE clause. Underscore gets the same treatment.
        val pct = DeviceFilterSqlBuilder.toSql(DeviceFilter.Name("100%", ignoreCase = true))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals(listOf<Any?>("%100\\%%"), pct.args)

        val underscore = DeviceFilterSqlBuilder.toSql(DeviceFilter.Name("foo_bar", ignoreCase = true))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals(listOf<Any?>("%foo\\_bar%"), underscore.args)

        // Backslash also needs escaping so a user typing `\%` doesn't get treated as a literal
        // percent (which would itself need escaping). Doubling-the-backslash-first ordering in
        // [DeviceFilterSqlBuilder.escapeLike] is what makes this round-trip.
        val backslash = DeviceFilterSqlBuilder.toSql(DeviceFilter.Name("a\\b", ignoreCase = true))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals(listOf<Any?>("%a\\\\b%"), backslash.args)
    }

    // ---- Composite (Any / All / Not) handling

    @Test
    fun `All combines pushable children with AND and concatenates args`() {
        val filter = DeviceFilter.All(
            listOf(
                DeviceFilter.IsPaired(true),
                DeviceFilter.Address("11:22:33:44:55:66"),
            ),
        )
        val r = DeviceFilterSqlBuilder.toSql(filter) as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("(is_paired = ? AND address = ?)", r.whereClause)
        assertEquals(listOf<Any?>(1, "11:22:33:44:55:66"), r.args)
    }

    @Test
    fun `Any combines pushable children with OR and concatenates args`() {
        val filter = DeviceFilter.Any(
            listOf(
                DeviceFilter.Address("AA:AA:AA:AA:AA:AA"),
                DeviceFilter.Address("BB:BB:BB:BB:BB:BB"),
            ),
        )
        val r = DeviceFilterSqlBuilder.toSql(filter) as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("(address = ? OR address = ?)", r.whereClause)
        assertEquals(listOf<Any?>("AA:AA:AA:AA:AA:AA", "BB:BB:BB:BB:BB:BB"), r.args)
    }

    @Test
    fun `Not wraps pushable child in NOT parens and preserves args`() {
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.Not(DeviceFilter.IsPaired(true)))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("NOT (is_paired = ?)", r.whereClause)
        assertEquals(listOf<Any?>(1), r.args)
    }

    @Test
    fun `composite atomicity - any unpushable child poisons the whole composite`() {
        // The All / Any composites must propagate NotPushable when even one child can't be
        // pushed. Partial pushdown would change result-set boundaries and break PagingSource
        // paging — page N would have a different count than page N+1 if half the predicate
        // ran in SQL and half in Kotlin.
        val filter = DeviceFilter.All(
            listOf(
                DeviceFilter.IsPaired(true),
                // Apple manufacturer is unpushable.
                DeviceFilter.Manufacturer(ManufacturerInfo.APPLE_ID),
            ),
        )
        assertSame(DeviceFilterSqlBuilder.Result.NotPushable, DeviceFilterSqlBuilder.toSql(filter))

        // Same poisoning rule for Any.
        val any = DeviceFilter.Any(
            listOf(
                DeviceFilter.Address("AA:AA:AA:AA:AA:AA"),
                DeviceFilter.Manufacturer(ManufacturerInfo.SAMSUNG_ID),
            ),
        )
        assertSame(DeviceFilterSqlBuilder.Result.NotPushable, DeviceFilterSqlBuilder.toSql(any))

        // And for Not — a NOT around an unpushable inner is itself unpushable.
        val not = DeviceFilter.Not(DeviceFilter.Manufacturer(ManufacturerInfo.APPLE_ID))
        assertSame(DeviceFilterSqlBuilder.Result.NotPushable, DeviceFilterSqlBuilder.toSql(not))
    }

    @Test
    fun `empty composite collapses to constant-true so it is a no-op WHERE clause`() {
        // An empty filter list is the user's "no filter applied" state; emitting `1=1` lets
        // the caller compose it into an AND chain without special-casing. Catches a
        // regression where the empty-list branch silently flips to NotPushable and forces
        // every list view to fall back to the in-Kotlin slow path.
        val r = DeviceFilterSqlBuilder.toSql(DeviceFilter.All(emptyList()))
                as DeviceFilterSqlBuilder.Result.Pushable
        assertEquals("1=1", r.whereClause)
        assertTrue(r.args.isEmpty())
    }

    // ---- Filters that intentionally fall through to in-Kotlin

    @Test
    fun `address-type filter is not pushable (computed at read time)`() {
        // BleAddressType is computed by BuildExtendedAddressInfoInteractor from address bytes
        // + observed lifetime + MSD vendor signal — not stored as its own column. Pinning the
        // NotPushable verdict here documents that.
        assertSame(
            DeviceFilterSqlBuilder.Result.NotPushable,
            DeviceFilterSqlBuilder.toSql(DeviceFilter.AddressType(listOf("STATIC"))),
        )
    }

    @Test
    fun `has_gatt filter is not pushable (lives in BTIDES sidecar index)`() {
        assertSame(
            DeviceFilterSqlBuilder.Result.NotPushable,
            DeviceFilterSqlBuilder.toSql(DeviceFilter.HasGatt(hasGatt = true)),
        )
    }
}
