package com.darkmentor.data.repo

import com.darkmentor.domain.model.DeviceFilter
import com.darkmentor.domain.model.ManufacturerInfo
import com.darkmentor.domain.model.Transport

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

        is DeviceFilter.IsConnectable ->
            Result.Pushable("is_connectable = ?", listOf(if (filter.isConnectable) 1 else 0))

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
            // Delegates the "which ordinals satisfy this filter" decision to
            // [Transport.matchingOrdinalsForFilter]; FilterCheckerImpl uses the same call so
            // the SQL and in-memory paths can't drift on the BTC-chip-includes-DUAL contract.
            val ordinals = Transport.matchingOrdinalsForFilter(filter.transportOrdinal, filter.includeDual)
            val whereClause = if (ordinals.size == 1) {
                "transport = ?"
            } else {
                "transport IN (${ordinals.joinToString(", ") { "?" }})"
            }
            Result.Pushable(whereClause, ordinals)
        }

        is DeviceFilter.Manufacturer -> {
            if (filter.manufacturerId == ManufacturerInfo.APPLE_ID ||
                filter.manufacturerId == ManufacturerInfo.SAMSUNG_ID
            ) {
                // Apple + Samsung route through VendorIdentifier so the broadened classification
                // (iBeacon-shaped MSDs / OUI / advertised UUIDs) matches Connect All's
                // Skip-Apple / Skip-Samsung toggles. That decision needs raw scan bytes, which
                // aren't usefully queryable from SQL.
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
        // GATT presence lives in the BTIDES sidecar index, not a Room column. The Kotlin
        // filter path consults BTIDESRepository.addressesWithGatt() (5s-cached set lookup).
        is DeviceFilter.HasGatt,
            -> Result.NotPushable
    }

    /**
     * The SQL-pushable subset of a flat, AND-combined filter list, for PARTIAL pushdown. When a
     * caller hits a non-pushable filter (Apple/Samsung vendor, location, address-type, has-GATT)
     * it can still narrow the candidate rows by the filters that DO translate, then apply the
     * rest in memory over the much smaller set. Top-level semantics are AND (the Devices VM
     * composes the active chips with [DeviceFilter.All]), so pushing a subset is sound — SQL
     * returns a superset and the in-memory residual narrows it further. [whereClause] is null
     * when nothing is pushable (e.g. a lone "Not Samsung").
     */
    data class PushableWhere(val whereClause: String?, val args: List<Any?>)

    fun pushableWhere(filters: List<DeviceFilter>): PushableWhere {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        for (filter in filters) {
            val pushed = toSql(filter)
            if (pushed is Result.Pushable) {
                clauses.add(pushed.whereClause)
                args.addAll(pushed.args)
            }
            // Result.NotPushable filters are left for the caller's in-Kotlin filter chain.
        }
        return PushableWhere(
            whereClause = if (clauses.isEmpty()) null else clauses.joinToString(" AND "),
            args = args,
        )
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
