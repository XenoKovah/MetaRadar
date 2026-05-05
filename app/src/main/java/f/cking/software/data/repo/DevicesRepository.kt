package f.cking.software.data.repo

import androidx.sqlite.db.SimpleSQLiteQuery
import f.cking.software.data.database.AppDatabase
import f.cking.software.data.database.DatabaseUtils
import f.cking.software.data.database.dao.DeviceDao
import f.cking.software.data.database.entity.DeviceEntity
import f.cking.software.domain.model.AppleAirDrop
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.toData
import f.cking.software.domain.toDomain
import f.cking.software.splitToBatches
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
        val sql = "SELECT * FROM device$where ORDER BY last_detect_time_ms DESC LIMIT $DEVICE_LIST_LIMIT"
        val query = SimpleSQLiteQuery(sql, args.toTypedArray())
        deviceDao.queryFiltered(query).map { it.toDomain() }
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
            // devices × 5 contacts each = 1M rows on every batch tick). DAO already chunks by
            // splitToBatches under the hood for the IN-clause variable limit.
            val addressesInPage = mapTo(mutableSetOf()) { it.address }
            val allRelatedContacts = if (addressesInPage.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    appleContactsDao.getByAddresses(addressesInPage.toList()).groupBy { it.associatedAddress }
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