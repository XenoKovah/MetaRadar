package f.cking.software.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import f.cking.software.data.database.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Query("SELECT * FROM device")
    fun getAll(): List<DeviceEntity>

    @Query("SELECT * FROM device")
    fun observeAll(): Flow<List<DeviceEntity>>

    /**
     * Run an arbitrary SELECT against the `device` table. Used by the repository to plug in
     * `WHERE` clauses built by [DeviceFilterSqlBuilder] (T3.16) and a `LIMIT` (T3.15) so the
     * Devices tab returns a bounded result set straight from SQL instead of materialising the
     * full M-row table and filtering in Kotlin.
     */
    @RawQuery
    fun queryFiltered(query: SupportSQLiteQuery): List<DeviceEntity>

    @Query("SELECT * FROM device ORDER BY last_detect_time_ms DESC LIMIT :limit OFFSET :offset")
    fun getPaginated(offset: Int, limit: Int): List<DeviceEntity>

    @Query("SELECT * FROM device WHERE last_detect_time_ms >= :lastDetectTime ORDER BY last_detect_time_ms DESC")
    fun getByLastDetectTime(lastDetectTime: Long): List<DeviceEntity>

    @Query("SELECT * FROM device WHERE address LIKE :address")
    fun findByAddress(address: String): DeviceEntity?

    @Query("SELECT * FROM device WHERE address IN (:addresses)")
    fun findAllByAddresses(addresses: List<String>): List<DeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(deviceEntity: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(devices: List<DeviceEntity>)

    @Query("DELETE FROM device WHERE address LIKE :address")
    fun delete(address: String)

    @Query("DELETE FROM device WHERE address IN (:addresses)")
    fun deleteAllByAddress(addresses: List<String>)

    @Query("DELETE FROM device")
    fun deleteAll()
}