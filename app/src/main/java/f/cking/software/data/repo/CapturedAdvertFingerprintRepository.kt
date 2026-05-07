package f.cking.software.data.repo

import f.cking.software.data.database.AppDatabase
import f.cking.software.data.database.entity.CapturedAdvertFingerprintEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin repo around [CapturedAdvertFingerprintDao] — backs the Connect All "skip
 * same-AD-different-BDADDR" dedup. The bulk interactor calls [register] when an attempt
 * completes with `allCharsRead = true`; the candidate selector calls [allFingerprints]
 * once at pass start and uses the snapshot to filter the candidate pool.
 *
 * INSERT-OR-IGNORE semantics on register so the diagnostic first_address /
 * captured_time_ms columns reflect the FIRST capture, not the most recent — useful when
 * post-mortem-debugging "why did Connect All skip this device" questions.
 */
class CapturedAdvertFingerprintRepository(
    appDatabase: AppDatabase,
) {
    private val dao = appDatabase.capturedAdvertFingerprintDao()

    /**
     * Snapshot of every fingerprint that's been fully captured. Returned as a Set for
     * O(1) membership checks in the per-device candidate filter. Read on Dispatchers.IO.
     */
    suspend fun allFingerprints(): Set<String> = withContext(Dispatchers.IO) {
        dao.getAllFingerprints().toHashSet()
    }

    /**
     * Record [fingerprint] as fully captured. No-op when an entry already exists for the
     * same fingerprint (preserves the original first_address + captured_time_ms).
     */
    suspend fun register(fingerprint: String, address: String, capturedTimeMs: Long) {
        withContext(Dispatchers.IO) {
            dao.insert(
                CapturedAdvertFingerprintEntity(
                    fingerprint = fingerprint,
                    firstAddress = address,
                    capturedTimeMs = capturedTimeMs,
                )
            )
        }
    }

    /**
     * Wipe the dedup table. Exposed so future "force re-capture every device" controls have
     * a clean entry point (none in the UI today; "Clear database" already wipes everything
     * via the DB-restore pathway).
     */
    suspend fun clear() {
        withContext(Dispatchers.IO) { dao.deleteAll() }
    }
}
