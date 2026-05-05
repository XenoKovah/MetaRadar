package f.cking.software.data.repo

import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.model.ManufacturerInfo

/**
 * Translates a [DeviceFilter] tree into a SQL `WHERE` fragment that can run inside the device-
 * table query, dramatically narrowing the row count returned to Kotlin. The reduction is the
 * point of T3.16: at M=200k devices, the difference between filtering in SQL (DB walks an index
 * and returns 50 rows) vs. filtering in Kotlin (DB returns 200k rows, Kotlin loops them) is
 * orders of magnitude.
 *
 * Filters that can't be expressed in SQL — chiefly [DeviceFilter.DeviceLocation],
 * [DeviceFilter.UserLocation], and the Apple-specific [DeviceFilter.Manufacturer] case (which
 * delegates to VendorIdentifier for iBeacon exemption) — return [Result.NotPushable] so the
 * caller knows to fall back to the existing in-Kotlin filter chain. Composite filters
 * propagate "not pushable" upward.
 */
object DeviceFilterSqlBuilder {

    sealed interface Result {
        data class Pushable(val whereClause: String, val args: List<Any?>) : Result
        data object NotPushable : Result
    }

    fun toSql(filter: DeviceFilter): Result = when (filter) {
        is DeviceFilter.IsPaired ->
            Result.Pushable("is_paired = ?", listOf(if (filter.isPaired) 1 else 0))

        is DeviceFilter.Address ->
            Result.Pushable("address = ?", listOf(filter.address))

        is DeviceFilter.LastDetectionInterval ->
            Result.Pushable("last_detect_time_ms BETWEEN ? AND ?", listOf(filter.from, filter.to))

        is DeviceFilter.FirstDetectionInterval ->
            Result.Pushable("first_detect_time_ms BETWEEN ? AND ?", listOf(filter.from, filter.to))

        is DeviceFilter.Name ->
            // The in-Kotlin variant supports regex; SQL only does LIKE substrings. We push the
            // LIKE form and let the FilterCheckerImpl accept the result as-is. The escape char
            // is `\` so user-typed `%` or `_` doesn't accidentally turn into wildcards.
            Result.Pushable(
                "name LIKE ? ESCAPE '\\' COLLATE NOCASE",
                listOf("%${filter.name.escapeLike()}%"),
            )

        is DeviceFilter.TransportFilter -> {
            // BREDR + DUAL when includeDual=true (the BTC chip's contract: anything seen on
            // the Classic radio); strict equality otherwise. Transport.DUAL.ordinal = 3.
            val dualOrdinal = 3
            if (filter.includeDual && filter.transportOrdinal != dualOrdinal) {
                Result.Pushable(
                    "transport IN (?, ?)",
                    listOf(filter.transportOrdinal, dualOrdinal),
                )
            } else {
                Result.Pushable("transport = ?", listOf(filter.transportOrdinal))
            }
        }

        is DeviceFilter.Manufacturer -> {
            if (filter.manufacturerId == ManufacturerInfo.APPLE_ID) {
                // The Apple case routes through VendorIdentifier so iBeacon-shaped MSDs from
                // Tesla/Estimote etc. don't get classified as Apple. That decision needs the
                // raw scan record bytes, which aren't usefully queryable from SQL.
                Result.NotPushable
            } else {
                Result.Pushable("manufacturer_id = ?", listOf(filter.manufacturerId))
            }
        }

        is DeviceFilter.Not -> {
            val inner = toSql(filter.filter)
            if (inner is Result.Pushable) {
                Result.Pushable("NOT (${inner.whereClause})", inner.args)
            } else {
                Result.NotPushable
            }
        }

        is DeviceFilter.All -> combineComposite(filter.filters, " AND ")
        is DeviceFilter.Any -> combineComposite(filter.filters, " OR ")

        is DeviceFilter.DeviceLocation,
        is DeviceFilter.UserLocation,
        // BleAddressType is computed at read time from address bytes + lifetime + MSD vendor
        // hints (see BuildExtendedAddressInfoInteractor), not stored as its own column. The
        // in-Kotlin filter chain runs on the cached extendedAddressInfo() per device.
        is DeviceFilter.AddressType,
            -> Result.NotPushable
    }

    private fun combineComposite(filters: List<DeviceFilter>, joiner: String): Result {
        if (filters.isEmpty()) return Result.Pushable("1=1", emptyList())
        val parts = filters.map(::toSql)
        // Whole-filter atomicity: if any sub-filter can't be pushed, we can't push the
        // composite either — partial pushdown would change result-set boundaries and break
        // PagingSource paging (page N would have a different row count than page N+1).
        if (parts.any { it is Result.NotPushable }) return Result.NotPushable
        val pushable = parts.filterIsInstance<Result.Pushable>()
        return Result.Pushable(
            pushable.joinToString(joiner, "(", ")") { it.whereClause },
            pushable.flatMap { it.args },
        )
    }

    /**
     * Escape `%` and `_` in a LIKE pattern so user-typed search terms don't accidentally turn
     * into wildcards. Backslash is the escape char (matches the no-COLLATE default; SQLite
     * handles `ESCAPE '\\'` implicitly for our LIKE expressions).
     */
    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
