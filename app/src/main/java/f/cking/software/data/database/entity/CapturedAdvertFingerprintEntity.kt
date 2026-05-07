package f.cking.software.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per advertisement-fingerprint that has been "fully captured" — i.e. a Connect All
 * attempt completed with `allCharsRead = true` on a device whose AD bytes hash to this
 * fingerprint. Subsequent devices that advertise the same AD bytes (typically the same
 * physical peripheral after an RPA rotation produced a fresh BDADDR) get filtered out of
 * the candidate pool by `BulkEnumerateCandidateSelection` so we don't re-attempt a
 * connection just because the address rotated.
 *
 * Persists across app restarts and across distinct Connect All sessions — RPA rotation is
 * frequent enough (typically every 15 min) that an in-memory dedup set would miss most of
 * the win. Wiping the database (Settings → Clear database) resets the dedup, which is the
 * intended escape hatch when the user wants to force a fresh re-capture.
 *
 * The fingerprint itself is computed by [f.cking.software.domain.interactor.AdvertisementFingerprint];
 * see that file for the hash strategy and the cases where it deliberately returns null
 * (e.g. Apple devices whose MSD includes a rolling counter that defeats stable hashing).
 */
@Entity(tableName = "captured_advert_fingerprint")
data class CapturedAdvertFingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "fingerprint") val fingerprint: String,
    /** First BDADDR that produced this fingerprint with allCharsRead=true. Diagnostic only. */
    @ColumnInfo(name = "first_address") val firstAddress: String,
    @ColumnInfo(name = "captured_time_ms") val capturedTimeMs: Long,
)
