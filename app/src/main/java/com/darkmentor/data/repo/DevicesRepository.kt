package com.darkmentor.data.repo

import androidx.sqlite.db.SimpleSQLiteQuery
import com.darkmentor.data.database.AppDatabase
import com.darkmentor.data.database.DatabaseUtils
import com.darkmentor.data.database.dao.DeviceDao
import com.darkmentor.data.database.entity.DeviceEntity
import com.darkmentor.domain.model.AppleAirDrop
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.DeviceFilter
import com.darkmentor.domain.toData
import com.darkmentor.domain.toDomain
import com.darkmentor.splitToBatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevicesRepository(
    appDatabase: AppDatabase,
) {

    private val deviceDao: DeviceDao = appDatabase.deviceDao()
    private val appleContactsDao = appDatabase.appleContactDao()
    private val lastBatch = MutableStateFlow(emptyList<DeviceData>())
    private val allDevices = deviceDao.observeAll()
        .map { it.toDomain(withAirdropInfo = true) }

    /**
     * Cache of (entity, domain) pairs keyed by address from the most recent
     * [snapshotFilteredDevices] call. Used to AVOID rebuilding [DeviceData] for entities
     * that haven't changed across snapshots — for steady-state Connect All sessions where
     * 999 of 1000 rows are unchanged from the prior emit, this drops per-emit allocations
     * by ~3 orders of magnitude. Replaced wholesale on each snapshot so cache size stays
     * bounded by the snapshot size (no leak).
     *
     * Equality check is data-class structural equality on [DeviceEntity] — covers all
     * persisted fields (last_detect_time_ms / RSSI / scan-record bytes / etc.) so any
     * meaningful row change forces a fresh [DeviceData] allocation; only true no-op
     * Room invalidations reuse.
     */
    @Volatile
    private var snapshotReuseCache: Map<String, Pair<com.darkmentor.data.database.entity.DeviceEntity, DeviceData>> = emptyMap()

    suspend fun getDevices(withAirdropInfo: Boolean = false): List<DeviceData> {
        return withContext(Dispatchers.IO) {
            deviceDao.getAll().toDomain(withAirdropInfo)
        }
    }

    suspend fun getPaginated(offset: Int, limit: Int): List<DeviceData> {
        return withContext(Dispatchers.IO) {
            deviceDao.getPaginated(offset, limit).toDomain(withAirdropInfo = true)
        }
    }

    suspend fun getLastBatch(): List<DeviceData> {
        return withContext(Dispatchers.IO) {
            val lastDevice = deviceDao.getPaginated(0, 1).firstOrNull()

            if (lastDevice == null) {
                emptyList()
            } else {
                val scanTime = lastDevice.lastDetectTimeMs
                deviceDao.getByLastDetectTime(scanTime).toDomain(withAirdropInfo = true)
            }
        }
    }

    fun clearLastBatch() {
        lastBatch.value = emptyList()
    }

    fun observeAllDevices(): Flow<List<DeviceData>> {
        return allDevices
    }

    /**
     * SQL-narrowed snapshot of the `device` table for the Devices tab. Pushes whatever
     * [filters] can be translated by [DeviceFilterSqlBuilder] into a `WHERE` clause; pushes
     * [searchQuery] as a name+address LIKE; orders by `last_detect_time_ms DESC` (matches the
     * primary key of GENERAL_COMPARATOR in the VM); caps the result at [DEVICE_LIST_LIMIT]
     * rows.
     *
     * Returns `null` when any of the [filters] can't be expressed in SQL (Apple manufacturer
     * with iBeacon exemption, location filters). Caller falls
     * back to the in-Kotlin [observeAllDevices] path in that case — slower but correct for
     * filters that need raw BLE bytes / cross-table data.
     *
     * The LIMIT means a fully-unfiltered view at M=1M devices renders the most-recent 1000
     * rather than trying to materialise the entire table. Users who want to find a specific
     * older device should narrow with the search/filter chips — those translate to SQL and
     * the LIMIT lifts effectively (the WHERE is the actual selectivity).
     */
    suspend fun snapshotFilteredDevices(
        filters: List<DeviceFilter>,
        searchQuery: String?,
        // Caller-supplied limit lets the VM stage a fast first paint at a small N (typical
        // ~200 rows for the visible viewport + a small over-fetch buffer) and follow up with
        // the full DEVICE_LIST_LIMIT in a second emission. Defaults to the full cap so prior
        // call sites stay unchanged. Hard-clamped at DEVICE_LIST_LIMIT — the SQL ORDER BY +
        // LIMIT bound stays the actual ceiling regardless of what the caller asks for.
        limit: Int = DEVICE_LIST_LIMIT,
    ): List<DeviceData>? = withContext(Dispatchers.IO) {
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        for (filter in filters) {
            when (val sql = DeviceFilterSqlBuilder.toSql(filter)) {
                is DeviceFilterSqlBuilder.Result.Pushable -> {
                    whereClauses.add(sql.whereClause)
                    args.addAll(sql.args)
                }
                DeviceFilterSqlBuilder.Result.NotPushable -> return@withContext null
            }
        }
        if (!searchQuery.isNullOrBlank()) {
            whereClauses.add("(name LIKE ? ESCAPE '\\' COLLATE NOCASE OR address LIKE ? ESCAPE '\\' COLLATE NOCASE)")
            val pattern = "%${searchQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
            args.add(pattern)
            args.add(pattern)
        }
        val where = if (whereClauses.isEmpty()) "" else " WHERE " + whereClauses.joinToString(" AND ")
        val effectiveLimit = limit.coerceIn(1, DEVICE_LIST_LIMIT)
        val sql = "SELECT * FROM device$where ORDER BY last_detect_time_ms DESC LIMIT $effectiveLimit"
        val query = SimpleSQLiteQuery(sql, args.toTypedArray())
        val entities = deviceDao.queryFiltered(query)
        // Per-snapshot diff against the prior cache: reuse the cached DeviceData instance
        // when this entity row is byte-equal to last time we saw it. The data-class equals
        // on DeviceEntity covers every persisted column, so any change (lastDetectTimeMs
        // bumping, RSSI shifting, fresh scan-record, name promotion) drops to the else
        // branch and allocates a fresh DeviceData. Cache is replaced wholesale below so it
        // stays bounded by the current snapshot size — no unbounded growth.
        val priorCache = snapshotReuseCache
        val newCache = HashMap<String, Pair<com.darkmentor.data.database.entity.DeviceEntity, DeviceData>>(entities.size)
        val result = entities.map { entity ->
            val cached = priorCache[entity.address]
            val domain = if (cached != null && cached.first == entity) {
                cached.second
            } else {
                entity.toDomain()
            }
            newCache[entity.address] = entity to domain
            domain
        }
        snapshotReuseCache = newCache
        result
    }

    suspend fun observeLastBatch(): StateFlow<List<DeviceData>> {
        return lastBatch.apply {
            if (lastBatch.value.isEmpty()) {
                notifyLastBatchListener()
            }
        }
    }

    suspend fun saveScanBatch(devices: List<DeviceData>) {
        withContext(Dispatchers.IO) {
            saveDevices(devices)
            saveContacts(devices)
            notifyLastBatchListener()
        }
    }

    suspend fun saveDevice(data: DeviceData) {
        withContext(Dispatchers.IO) {
            deviceDao.insert(data.toData())
            notifyLastBatchListener()
        }
    }

    /**
     * Persist a fresh SDP service-class UUID list for [address]. No-op if the row doesn't
     * exist (e.g. user wiped the DB between SDP fetch and result delivery). Treats UUIDs as
     * canonical lower-case strings — same shape as `service_uuids`.
     */
    suspend fun updateSdpUuids(address: String, uuids: List<String>) {
        withContext(Dispatchers.IO) {
            val existing = deviceDao.findByAddress(address) ?: return@withContext
            deviceDao.insert(existing.copy(sdpUuids = uuids))
            notifyLastBatchListener()
        }
    }

    /**
     * Promote a GATT-Device-Name (0x2A00 char read) into the row's [DeviceEntity.name] column
     * if it carries strictly more information than what's already there. "More information"
     * means a strictly longer string — many peers advertise a truncated Local Name (limited
     * by the 31-byte AD payload budget) but expose the full long name on 0x2A00, e.g.
     * "HP" advertised vs "HP OfficeJet Pro 8020 series" on the GATT char.
     *
     * Equal-length writes are no-ops to avoid churn when the same value comes back twice.
     * customName edits live on a separate column and aren't touched here.
     *
     * Skipping blank/empty inputs guards against the not-uncommon case where a peer ACKs the
     * read but returns a zero-length value.
     */
    suspend fun setNameIfBetter(address: String, name: String) {
        if (name.isBlank()) return
        withContext(Dispatchers.IO) {
            val existing = deviceDao.findByAddress(address) ?: return@withContext
            val current = existing.name
            if (current != null && current.length >= name.length) return@withContext
            deviceDao.insert(existing.copy(name = name))
            notifyLastBatchListener()
        }
    }

    /**
     * Promote a GATT-Manufacturer-Name (0x2A29 char read) into the row's
     * [DeviceEntity.gattManufacturerName] column IFF nothing is there yet. Surfaces under the
     * "Manufacturer" line on Device Details as a fallback for peers that don't advertise an
     * MSD company id but do expose Generic Access → Manufacturer Name String. Refuses to
     * overwrite a prior capture so a single transient bad read doesn't corrupt the row.
     */
    suspend fun setGattManufacturerNameIfMissing(address: String, name: String) {
        if (name.isBlank()) return
        withContext(Dispatchers.IO) {
            val existing = deviceDao.findByAddress(address) ?: return@withContext
            if (!existing.gattManufacturerName.isNullOrBlank()) return@withContext
            deviceDao.insert(existing.copy(gattManufacturerName = name))
            notifyLastBatchListener()
        }
    }

    suspend fun deleteAllByAddress(addresses: List<String>) {
        withContext(Dispatchers.IO) {
            addresses.splitToBatches(DatabaseUtils.getMaxSQLVariablesNumber()).forEach { addressesBatch ->
                deviceDao.deleteAllByAddress(addressesBatch)
            }
            notifyLastBatchListener()
        }
    }

    /**
     * Wipe every row from `device` and `apple_contacts`. Used by the explicit "Clear" buttons
     * in the Devices tab and the Settings → Database actions block.
     */
    suspend fun deleteAllDevices() {
        withContext(Dispatchers.IO) {
            deviceDao.deleteAll()
            appleContactsDao.deleteAll()
            lastBatch.value = emptyList()
            notifyLastBatchListener()
        }
    }

    suspend fun clearUnAssociatedAirdrops() {
        withContext(Dispatchers.IO) {
            val allDevices = deviceDao.getAll().mapTo(mutableSetOf()) { it.address }
            val allAidrops = appleContactsDao.getAll().map { it.associatedAddress }

            val unassotiatedAirdrops = allAidrops.filter { !allDevices.contains(it) }

            appleContactsDao.deleteAllByAddresses(unassotiatedAirdrops)
        }
    }

    suspend fun getAllByAddresses(addresses: List<String>): List<DeviceData> {
        return withContext(Dispatchers.IO) {
            addresses.splitToBatches(DatabaseUtils.getMaxSQLVariablesNumber()).flatMap {
                deviceDao.findAllByAddresses(addresses).toDomain(withAirdropInfo = true)
            }
        }
    }

    suspend fun getDeviceByAddress(address: String): DeviceData? {
        return withContext(Dispatchers.IO) {
            deviceDao.findByAddress(address)?.toDomainWithAirDrop()
        }
    }

    suspend fun getAirdropByKnownAddress(address: String): AppleAirDrop? {
        return withContext(Dispatchers.IO) {
            appleContactsDao.getByAddress(address)
                .map { it.toDomain() }
                .takeIf { it.isNotEmpty() }
                ?.let { AppleAirDrop(it) }
        }
    }

    suspend fun getAllBySHA(sha: List<Int>): List<AppleAirDrop.AppleContact> {
        return withContext(Dispatchers.IO) {
            appleContactsDao.getBySHA(sha).map { it.toDomain() }
        }
    }

    private suspend fun saveDevices(devices: List<DeviceData>) {
        withContext(Dispatchers.IO) {
            deviceDao.insertAll(devices.map { it.toData() })
        }
    }

    private suspend fun saveContacts(devices: List<DeviceData>) {
        withContext(Dispatchers.IO) {
            val contacts = devices.flatMap { device ->
                device.manufacturerInfo?.airdrop?.contacts?.map { it.toData(device.address) } ?: emptyList()
            }

            appleContactsDao.insertAll(contacts)
        }
    }

    private suspend fun notifyLastBatchListener() {
        coroutineScope {
            launch(Dispatchers.Default) {
                val data = getLastBatch()
                lastBatch.emit(data)
            }
        }
    }

    private suspend fun DeviceEntity.toDomainWithAirDrop(): DeviceData {
        return withContext(Dispatchers.IO) {
            val contacts = appleContactsDao.getByAddress(address)
            toDomain(AppleAirDrop(contacts.map { it.toDomain() }))
        }
    }

    private suspend fun List<DeviceEntity>.toDomain(withAirdropInfo: Boolean): List<DeviceData> {
        return withContext(Dispatchers.Default) {
            if (withAirdropInfo) {
                toDomainWithAirDrop()
            } else {
                map { it.toDomain() }
            }
        }
    }

    private suspend fun List<DeviceEntity>.toDomainWithAirDrop(): List<DeviceData> {
        return withContext(Dispatchers.Default) {
            // Only fetch the contacts whose associatedAddress is actually in this list, instead
            // of `appleContactsDao.getAll()` (which materialised the entire table — at M=200k
            // devices × 5 contacts each = 1M rows on every batch tick).
            //
            // appleContactsDao.getByAddresses is a plain Room @Query that pastes the address
            // list straight into an `IN (?, ?, ...)` clause — no internal chunking. SQLite
            // caps the bound-variable count at 999 (pre-Android-12) or 32766 (Android 12+),
            // so passing the whole page as one call FATAL-crashes the UI thread the moment
            // the table grows past the cap. Chunk via DatabaseUtils.getMaxSQLVariablesNumber()
            // and flatMap the per-batch results, matching the pattern at lines 189 and 222.
            val addressesInPage = mapTo(mutableSetOf()) { it.address }
            val allRelatedContacts = if (addressesInPage.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    addressesInPage.toList()
                        .splitToBatches(DatabaseUtils.getMaxSQLVariablesNumber())
                        .flatMap { batch -> appleContactsDao.getByAddresses(batch) }
                        .groupBy { it.associatedAddress }
                }
            }

            map { device ->
                val airdrop = allRelatedContacts[device.address]?.let {
                    AppleAirDrop(it.map { it.toDomain() })
                }

                device.toDomain(airdrop)
            }
        }
    }

    companion object {
        // Hard cap for [snapshotFilteredDevices]. SQL ORDER BY last_detect_time_ms DESC LIMIT
        // <this> means an unfiltered view at M=1M devices materialises the most-recent 1000
        // rather than the whole table. Users find older devices by search/filter chips, where
        // selectivity comes from the WHERE clause and the LIMIT is rarely the binding factor.
        private const val DEVICE_LIST_LIMIT = 1000
    }
}