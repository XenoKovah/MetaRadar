package f.cking.software.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import f.cking.software.data.database.entity.CapturedAdvertFingerprintEntity

/**
 * DAO for the [CapturedAdvertFingerprintEntity] dedup table consumed by the Connect All
 * candidate selector. INSERT-OR-IGNORE on write so a re-capture of the same fingerprint
 * keeps the original first_address / captured_time_ms (the dedup is what matters; the
 * "who first" data is purely diagnostic).
 */
@Dao
interface CapturedAdvertFingerprintDao {

    @Query("SELECT * FROM captured_advert_fingerprint")
    fun getAll(): List<CapturedAdvertFingerprintEntity>

    @Query("SELECT fingerprint FROM captured_advert_fingerprint")
    fun getAllFingerprints(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entry: CapturedAdvertFingerprintEntity)

    @Query("DELETE FROM captured_advert_fingerprint")
    fun deleteAll()
}
