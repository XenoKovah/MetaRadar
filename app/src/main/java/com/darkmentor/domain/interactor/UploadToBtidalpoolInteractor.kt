package com.darkmentor.domain.interactor

import android.content.Context
import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.btidalpool.BtidalpoolClient
import com.darkmentor.data.btides.BTIDESRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Orchestrates uploads of merged BTIDES files to the BTIDALPOOL pool server. Mirrors the
 * three-step flow in `BTIDES_to_BTIDALPOOL.send_btides_to_btidalpool` but is structured around
 * the per-day rotated log layout. A successful upload MARKS the underlying archive as uploaded
 * (via [BTIDESRepository.markUploaded]) and KEEPS it on the device — the data is never deleted —
 * and future uploads skip already-uploaded archives so the same data isn't re-sent. Pass
 * [executeAll]'s `allowReupload = true` (the hidden debug override) to re-send them anyway.
 *
 * Two flows:
 *   - [executeCurrent] — rotate the active log and upload the resulting (always-fresh) archive,
 *     marking it on success. Mid-upload captures accumulate in a fresh active log without being
 *     mixed into the boundary.
 *   - [executeAll] — rotate first, then iterate every not-yet-uploaded archive sequentially (or
 *     every archive when `allowReupload`). Failures don't abort: each archive's success/failure
 *     is reported independently so the user can decide whether to retry.
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
        allowReupload: Boolean = false,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        if (authRepository.current() == null) return@withContext Outcome.NotSignedIn
        // Rotate first so the active log joins the candidate set as a normal archive — keeps
        // the upload loop uniform (no special case for the active file) and means any
        // mid-upload captures land in a fresh, unsent log rather than getting mixed into a
        // batch that's already in flight.
        btidesRepository.rotateActive()
        // Skip archives already uploaded (kept on device, just marked) so we don't re-send the
        // same data — unless the hidden debug [allowReupload] override is on.
        val uploaded = if (allowReupload) emptySet() else btidesRepository.uploadedLogNames()
        val logs = btidesRepository.listLogs().filter { it.file.name !in uploaded }
        if (logs.isEmpty()) {
            return@withContext Outcome.WithResults(
                results = listOf(LogResult.EmptyLog(activeDisplayName())),
                email = authRepository.current()?.email,
            )
        }
        // Aggregate progress across the whole multi-log loop so the single visible progress
        // bar advances 0→1 across N archives. Per-log byte counts are summed up-front; each
        // archive's local (processed, total) callback is rebased into the global span.
        // Doubling the per-log size accounts for the export pass (file → temp .btides) AND
        // the upload pass (temp .btides → server) that BTIDES exports run through —
        // [exportTo] reports `total = 2 * sourceSize` for the same reason internally.
        val perLogTotal = logs.map { it.file.length() * 2L }
        val grandTotal = perLogTotal.sum().coerceAtLeast(1L)
        val results = mutableListOf<LogResult>()
        var completedBytes = 0L
        for ((index, log) in logs.withIndex()) {
            val logBudget = perLogTotal[index]
            val baseBefore = completedBytes
            val rebased: suspend (Long, Long) -> Unit = { processed, total ->
                val localFraction = if (total > 0L) processed.toDouble() / total else 0.0
                onProgress?.invoke(
                    (baseBefore + (localFraction * logBudget).toLong()).coerceAtMost(grandTotal),
                    grandTotal,
                )
            }
            val r = try {
                uploadOneLog(log.file, useTestDb, rebased)
            } catch (t: Throwable) {
                Timber.w(t, "Per-log upload threw for ${log.file.name}")
                LogResult.Failed(log.file.name, t.message ?: t::class.java.simpleName)
            }
            results.add(r)
            completedBytes += logBudget
            onProgress?.invoke(completedBytes.coerceAtMost(grandTotal), grandTotal)
        }
        Outcome.WithResults(results = results, email = authRepository.current()?.email)
    }

    /**
     * Export [logFile] to a temp `.btides` file, upload it, and delete the source archive on
     * success. Single-log workhorse for both [executeCurrent] and [executeAll].
     *
     * There is no client-side hash pre-flight: the Rust pool server dedups uploaded content
     * itself and reports a re-upload as [BtidalpoolClient.UploadResult.AlreadyPresent], which we
     * treat exactly like a fresh success (archive deleted, counted as already-on-server). This
     * also sidesteps the old parse → canonical re-emit → SHA1 step, which expanded the
     * JsonElement tree ~4-5x and OOM'd a 4 GB heap on a 125 MB log.
     */
    private suspend fun uploadOneLog(
        logFile: File,
        useTestDb: Boolean,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)?,
    ): LogResult {
        val displayName = logFile.name
        val tempExport = File.createTempFile("btidalpool_upload_", ".btides", context.cacheDir)
        try {
            // Split this log's progress budget into [export-half, upload-half]. The export
            // pass already reports `total = 2 * sourceSize` internally so we map it into the
            // first half of our budget; the upload pass reports actual byte counts that we
            // map into the second half. Caller's [onProgress] gets a smooth 0→1 across both.
            val sourceSize = logFile.length().coerceAtLeast(1L)
            val phaseTotal = sourceSize * 2L
            val exportProgress: suspend (Long, Long) -> Unit = { processed, total ->
                val frac = if (total > 0L) processed.toDouble() / total else 0.0
                onProgress?.invoke((frac * sourceSize).toLong().coerceAtMost(sourceSize), phaseTotal)
            }
            val uploadProgress: suspend (Long, Long) -> Unit = { sent, total ->
                val frac = if (total > 0L) sent.toDouble() / total else 0.0
                onProgress?.invoke((sourceSize + frac * sourceSize).toLong().coerceAtMost(phaseTotal), phaseTotal)
            }
            val deviceCount = try {
                exportBTIDESInteractor.executeForLog(logFile, tempExport, exportProgress)
            } catch (t: Throwable) {
                Timber.w(t, "BTIDES export failed for %s", displayName)
                return LogResult.Failed(displayName, t.message ?: t::class.java.simpleName)
            }
            if (deviceCount == 0 || tempExport.length() == 0L) {
                return LogResult.EmptyLog(displayName)
            }

            // No client-side hash pre-flight: the Rust server dedups uploaded content and reports
            // a re-upload as AlreadyPresent (handled below), so we just upload and let it decide.
            return when (val up = withTokenRefresh { (t, r) -> client.uploadFile(tempExport, t, r, useTestDb, uploadProgress) }) {
                is BtidalpoolClient.UploadResult.Success -> {
                    btidesRepository.markUploaded(logFile)
                    LogResult.Success(displayName, deviceCount)
                }
                is BtidalpoolClient.UploadResult.AlreadyPresent -> {
                    btidesRepository.markUploaded(logFile)
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

    private fun activeDisplayName(): String = "btides_log.jsonl"

    private suspend fun <R : Any> withTokenRefresh(block: suspend (Pair<String, String>) -> R): R {
        val first = authRepository.current() ?: error("No cached credentials")
        val firstAttempt = block(first.token to first.refreshToken)
        if (!isAuthFailure(firstAttempt)) return firstAttempt
        val refreshed = authRepository.refresh() ?: return firstAttempt
        return block(refreshed.token to refreshed.refreshToken)
    }

    private fun isAuthFailure(r: Any): Boolean = r is BtidalpoolClient.UploadResult.AuthFailed
}
