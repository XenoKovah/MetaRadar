package f.cking.software.domain.interactor

import android.content.Context
import f.cking.software.data.btidalpool.BtidalpoolAuthRepository
import f.cking.software.data.btidalpool.BtidalpoolClient
import f.cking.software.data.btidalpool.PythonCanonicalJson
import f.cking.software.data.btides.BTIDESRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Orchestrates uploads of merged BTIDES files to the BTIDALPOOL pool server. Mirrors the
 * three-step flow in `BTIDES_to_BTIDALPOOL.send_btides_to_btidalpool` but is structured around
 * the per-day rotated log layout: every successful upload deletes the underlying log archive,
 * so the user never re-uploads the same data twice.
 *
 * Two flows:
 *   - [executeCurrent] — rotate the active log, upload the resulting archive, delete on
 *     success. Mid-upload captures continue accumulating in a fresh active log without being
 *     mixed into either side of the upload boundary.
 *   - [executeAll] — rotate first (so current data joins the pending set), then iterate every
 *     archive sequentially. Failures don't abort: each archive's success/failure is reported
 *     independently so the user can decide whether to retry.
 */
class UploadToBtidalpoolInteractor(
    private val btidesRepository: BTIDESRepository,
    private val exportBTIDESInteractor: ExportBTIDESInteractor,
    private val client: BtidalpoolClient,
    private val authRepository: BtidalpoolAuthRepository,
    private val context: Context,
) {

    /** Result for a single log. Multi-log uploads return a list of these. */
    sealed class LogResult {
        abstract val logName: String

        data class Success(override val logName: String, val deviceCount: Int) : LogResult()
        data class AlreadyOnServer(override val logName: String, val deviceCount: Int) : LogResult()
        data class Failed(override val logName: String, val message: String) : LogResult()
        data class AuthFailed(override val logName: String) : LogResult()
        data class EmptyLog(override val logName: String) : LogResult()
    }

    /** Wrapper signaling that no credentials were cached when the call ran. */
    sealed class Outcome {
        object NotSignedIn : Outcome()
        data class WithResults(val results: List<LogResult>, val email: String?) : Outcome()
    }

    /**
     * Upload only the currently-active log. Rotates first, so any data captured *during* the
     * upload accumulates in a fresh active log untouched. Returns either NotSignedIn or a
     * single-element WithResults.
     */
    suspend fun executeCurrent(
        useTestDb: Boolean,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (authRepository.current() == null) return@withContext Outcome.NotSignedIn
        val rotated = btidesRepository.rotateActive()
            ?: return@withContext Outcome.WithResults(
                results = listOf(LogResult.EmptyLog(activeDisplayName())),
                email = authRepository.current()?.email,
            )
        val result = uploadOneLog(rotated, useTestDb, onProgress)
        Outcome.WithResults(results = listOf(result), email = authRepository.current()?.email)
    }

    /**
     * Upload the active log AND every rotated archive in chronological order, returning one
     * [LogResult] per log. Each successful upload removes that log's archive from disk.
     * Failures are collected and reported but do not stop the loop.
     *
     * The aggregate progress callback is rebased per-log: while uploading log N of M, the
     * fraction reflects "this log only". The UI rebinds it as the overall progress for the
     * single visible inline progress bar.
     */
    suspend fun executeAll(
        useTestDb: Boolean,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (authRepository.current() == null) return@withContext Outcome.NotSignedIn
        // Rotate first so the active log joins the candidate set as a normal archive — keeps
        // the upload loop uniform (no special case for the active file) and means any
        // mid-upload captures land in a fresh, unsent log rather than getting mixed into a
        // batch that's already in flight.
        btidesRepository.rotateActive()
        val logs = btidesRepository.listLogs()
        if (logs.isEmpty()) {
            return@withContext Outcome.WithResults(
                results = listOf(LogResult.EmptyLog(activeDisplayName())),
                email = authRepository.current()?.email,
            )
        }
        val results = mutableListOf<LogResult>()
        for (log in logs) {
            results.add(uploadOneLog(log.file, useTestDb, onProgress))
        }
        Outcome.WithResults(results = results, email = authRepository.current()?.email)
    }

    /**
     * Export [logFile] to a temp `.btides` file, hash + check + upload, delete the source
     * archive on success. Single-log workhorse for both [executeCurrent] and [executeAll].
     */
    private suspend fun uploadOneLog(
        logFile: File,
        useTestDb: Boolean,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)?,
    ): LogResult {
        val displayName = logFile.name
        val tempExport = File.createTempFile("btidalpool_upload_", ".btides", context.cacheDir)
        try {
            val deviceCount = try {
                exportBTIDESInteractor.executeForLog(logFile, tempExport, onProgress)
            } catch (t: Throwable) {
                Timber.w(t, "BTIDES export failed for %s", displayName)
                return LogResult.Failed(displayName, t.message ?: t::class.java.simpleName)
            }
            if (deviceCount == 0 || tempExport.length() == 0L) {
                return LogResult.EmptyLog(displayName)
            }

            val rawJson = tempExport.readText(Charsets.UTF_8)
            val parsed = try {
                Json.parseToJsonElement(rawJson)
            } catch (t: Throwable) {
                Timber.e(t, "Exported BTIDES for %s failed to parse — refusing upload", displayName)
                return LogResult.Failed(displayName, "exported file was not valid JSON")
            }
            val canonical = PythonCanonicalJson.encode(parsed)
            val sha1 = sha1Hex(canonical.toByteArray(Charsets.UTF_8))

            // Two-step: hash check first, then upload if the server doesn't have it. Both share
            // the same auth-refresh-and-retry wrapper.
            when (val hashResult = withTokenRefresh { (t, r) -> client.checkHash(sha1, t, r, useTestDb) }) {
                is BtidalpoolClient.CheckHashResult.AlreadyPresent -> {
                    // Server already has this content — drop the archive on the floor so we
                    // don't keep retrying the same upload on every "Upload all".
                    deleteIfArchive(logFile)
                    return LogResult.AlreadyOnServer(displayName, deviceCount)
                }
                is BtidalpoolClient.CheckHashResult.NotPresent -> Unit
                is BtidalpoolClient.CheckHashResult.AuthFailed -> return LogResult.AuthFailed(displayName)
                is BtidalpoolClient.CheckHashResult.Failed ->
                    return LogResult.Failed(displayName, "hash check HTTP ${hashResult.httpCode}: ${hashResult.body}")
            }

            return when (val up = withTokenRefresh { (t, r) -> client.upload(rawJson, t, r, useTestDb) }) {
                is BtidalpoolClient.UploadResult.Success -> {
                    deleteIfArchive(logFile)
                    LogResult.Success(displayName, deviceCount)
                }
                is BtidalpoolClient.UploadResult.AlreadyPresent -> {
                    deleteIfArchive(logFile)
                    LogResult.AlreadyOnServer(displayName, deviceCount)
                }
                is BtidalpoolClient.UploadResult.AuthFailed -> LogResult.AuthFailed(displayName)
                is BtidalpoolClient.UploadResult.Failed ->
                    LogResult.Failed(displayName, "upload HTTP ${up.httpCode}: ${up.body}")
            }
        } finally {
            runCatching { tempExport.delete() }
        }
    }

    /**
     * Drop a successfully-uploaded archive from disk. The active log file is never deleted by
     * this method (only its rotated copies) — execution paths that need to clear the active
     * log do so via [BTIDESRepository.rotateActive] before calling here.
     */
    private suspend fun deleteIfArchive(logFile: File) {
        // [rotateActive] always renames the active log into an archive before this method
        // sees it, so every file we get here is safe to delete.
        btidesRepository.deleteArchive(logFile)
    }

    private fun activeDisplayName(): String = "btides_log.jsonl"

    private suspend fun <R : Any> withTokenRefresh(block: suspend (Pair<String, String>) -> R): R {
        val first = authRepository.current() ?: error("No cached credentials")
        val firstAttempt = block(first.token to first.refreshToken)
        if (!isAuthFailure(firstAttempt)) return firstAttempt
        val refreshed = authRepository.refresh() ?: return firstAttempt
        return block(refreshed.token to refreshed.refreshToken)
    }

    private fun isAuthFailure(r: Any): Boolean = when (r) {
        is BtidalpoolClient.CheckHashResult.AuthFailed,
        is BtidalpoolClient.UploadResult.AuthFailed,
        -> true
        else -> false
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
