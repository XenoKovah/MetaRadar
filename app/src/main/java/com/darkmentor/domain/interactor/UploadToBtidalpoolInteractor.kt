package com.darkmentor.domain.interactor

import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.btidalpool.BtidalpoolClient
import com.darkmentor.data.btidalpool.BtidalpoolOutboxRepository
import com.darkmentor.data.btidalpool.BtidalpoolResumableUploader
import com.darkmentor.data.btidalpool.BtidalpoolUploadPrivacyValidator
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.math.min
import kotlin.random.Random

/**
 * Prepares bounded upload chunks into a durable Room-backed outbox, then drains that outbox.
 *
 * Source archives are never deleted. A successful server acknowledgement deletes only the
 * derived chunk payload; the outbox receipt remains and is scoped to content + destination +
 * account. A process death leaves pending rows and payload files intact for WorkManager to resume.
 */
class UploadToBtidalpoolInteractor(
    private val btidesRepository: BTIDESRepository,
    private val exportBTIDESInteractor: ExportBTIDESInteractor,
    private val authRepository: BtidalpoolAuthRepository,
    private val outboxRepository: BtidalpoolOutboxRepository,
    private val resumableUploader: BtidalpoolResumableUploader,
    private val privacyValidator: BtidalpoolUploadPrivacyValidator,
) {
    enum class Mode { CURRENT, ALL }

    data class RunSummary(
        val preparedLogs: Int = 0,
        val skippedUploadedLogs: Int = 0,
        val emptyLogs: Int = 0,
        val preparationFailures: List<String> = emptyList(),
        val uploadedChunks: Int = 0,
        val alreadyPresentChunks: Int = 0,
        val permanentFailures: List<String> = emptyList(),
        val uploadedDevices: Int = 0,
    ) {
        operator fun plus(other: RunSummary): RunSummary = RunSummary(
            preparedLogs = preparedLogs + other.preparedLogs,
            skippedUploadedLogs = skippedUploadedLogs + other.skippedUploadedLogs,
            emptyLogs = emptyLogs + other.emptyLogs,
            preparationFailures = preparationFailures + other.preparationFailures,
            uploadedChunks = uploadedChunks + other.uploadedChunks,
            alreadyPresentChunks = alreadyPresentChunks + other.alreadyPresentChunks,
            permanentFailures = permanentFailures + other.permanentFailures,
            uploadedDevices = uploadedDevices + other.uploadedDevices,
        )
    }

    sealed class Execution {
        object NotSignedIn : Execution()
        data class AccountChanged(val message: String) : Execution()
        data class AuthRequired(val message: String) : Execution()
        data class Finished(val summary: RunSummary, val email: String?) : Execution()
        data class RetryRequired(
            val delayMillis: Long,
            val reason: String,
            val scope: BtidalpoolOutboxRepository.Scope,
            val summary: RunSummary,
        ) : Execution()
    }

    suspend fun execute(
        mode: Mode,
        useTestDb: Boolean,
        allowReupload: Boolean,
        resumeOnly: Boolean = false,
        expectedAccountKey: String? = null,
        onProgress: (suspend (bytesProcessed: Long, totalBytes: Long) -> Unit)? = null,
        onBusyRetry: (suspend (BtidalpoolClient.BusyRetryState?) -> Unit)? = null,
    ): Execution = withContext(Dispatchers.IO) {
        val auth = authRepository.current() ?: return@withContext Execution.NotSignedIn
        val scope = outboxRepository.scope(auth, useTestDb)
        if (expectedAccountKey != null && expectedAccountKey != scope.accountKey) {
            return@withContext Execution.AccountChanged(
                "The queued upload belongs to a different signed-in Google account.",
            )
        }

        outboxRepository.recoverInterrupted(scope)
        if (resumeOnly) {
            return@withContext drainToExecution(
                drainOutbox(scope, useTestDb, onProgress, onBusyRetry),
                scope,
                RunSummary(),
            )
        }
        prepareAndDrainPipelined(
            mode,
            scope,
            useTestDb,
            allowReupload,
            onProgress,
            onBusyRetry,
        )
    }

    /**
     * Export one archive ahead while the previously-prepared chunks upload. The outbox insert
     * happens before networking starts, so process death never loses ownership of a payload.
     */
    private suspend fun prepareAndDrainPipelined(
        mode: Mode,
        scope: BtidalpoolOutboxRepository.Scope,
        useTestDb: Boolean,
        allowReupload: Boolean,
        onProgress: (suspend (Long, Long) -> Unit)?,
        onBusyRetry: (suspend (BtidalpoolClient.BusyRetryState?) -> Unit)?,
    ): Execution = coroutineScope {
        val logs = when (mode) {
            Mode.CURRENT -> listOfNotNull(btidesRepository.rotateActive())
                .map { BTIDESRepository.LogFile(it, isActive = false) }
            Mode.ALL -> {
                btidesRepository.rotateActive()
                btidesRepository.listLogs()
            }
        }
        if (logs.isEmpty()) {
            return@coroutineScope drainToExecution(
                drainOutbox(scope, useTestDb, onProgress, onBusyRetry),
                scope,
                RunSummary(),
            )
        }

        var summary = RunSummary()
        var terminalDrain: DrainResult? = null
        var nextPreparation = async {
            prepareOneLog(logs.first(), scope, allowReupload, onProgress)
        }
        for (index in logs.indices) {
            summary += nextPreparation.await()
            nextPreparation = if (index + 1 < logs.size) {
                async {
                    prepareOneLog(logs[index + 1], scope, allowReupload, onProgress)
                }
            } else {
                async { RunSummary() }
            }
            if (terminalDrain == null) {
                val drained = drainOutbox(scope, useTestDb, onProgress, onBusyRetry)
                if (drained is DrainResult.Finished) {
                    summary += drained.summary
                } else {
                    terminalDrain = drained
                }
            }
        }
        nextPreparation.await()

        val terminal = terminalDrain
        if (terminal != null) {
            return@coroutineScope drainToExecution(terminal, scope, summary)
        }
        val finalDrain = drainOutbox(scope, useTestDb, onProgress, onBusyRetry)
        drainToExecution(finalDrain, scope, summary)
    }

    private suspend fun prepareOneLog(
        log: BTIDESRepository.LogFile,
        scope: BtidalpoolOutboxRepository.Scope,
        allowReupload: Boolean,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): RunSummary {
        return try {
            val sourceSha = outboxRepository.sha256(log.file)
            val existing = outboxRepository.incompleteBatchId(sourceSha, scope)
            if (existing != null) {
                val queuedPolicy = outboxRepository.privacyPolicyFingerprint(existing)
                val currentPolicy = privacyValidator.currentPolicyFingerprint()
                if (queuedPolicy == currentPolicy) {
                    outboxRepository.resetBatchForManualRetry(existing)
                    return RunSummary()
                }
                // The generated payload predates the current zones. Remove only this outbox
                // batch; the immutable source log is then re-exported immediately below.
                outboxRepository.discardBatch(existing)
            }

            val (batchId, batchDir) = outboxRepository.newBatchDirectory()
            val exportedChunks = try {
                exportBTIDESInteractor.executeUploadChunks(log.file, batchDir, onProgress)
            } catch (t: Throwable) {
                runCatching { batchDir.deleteRecursively() }
                throw t
            }
            if (exportedChunks.isEmpty()) {
                runCatching { batchDir.deleteRecursively() }
                return RunSummary(emptyLogs = 1)
            }
            val chunks = if (allowReupload) {
                exportedChunks
            } else {
                exportedChunks.filter { chunk ->
                    val alreadySucceeded =
                        outboxRepository.succeededChunkId(chunk.sha256, scope) != null
                    if (alreadySucceeded) runCatching { chunk.file.delete() }
                    !alreadySucceeded
                }
            }
            if (chunks.isEmpty()) {
                runCatching { batchDir.deleteRecursively() }
                return RunSummary(skippedUploadedLogs = 1)
            }
            try {
                outboxRepository.insertBatch(batchId, log.file, sourceSha, scope, chunks)
            } catch (t: Throwable) {
                runCatching { batchDir.deleteRecursively() }
                throw t
            }
            RunSummary(preparedLogs = 1)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "Could not prepare BTIDALPOOL chunks for %s", log.file.name)
            RunSummary(
                preparationFailures = listOf(
                    "${log.file.name}: ${t.message ?: t::class.java.simpleName}",
                ),
            )
        }
    }

    private fun drainToExecution(
        drain: DrainResult,
        scope: BtidalpoolOutboxRepository.Scope,
        summary: RunSummary,
    ): Execution = when (drain) {
        is DrainResult.Finished ->
            Execution.Finished(summary + drain.summary, authRepository.current()?.email)
        is DrainResult.AuthRequired -> Execution.AuthRequired(drain.message)
        is DrainResult.RetryRequired -> Execution.RetryRequired(
            delayMillis = drain.delayMillis,
            reason = drain.reason,
            scope = scope,
            summary = summary + drain.summary,
        )
    }

    private sealed class DrainResult {
        abstract val summary: RunSummary

        data class Finished(override val summary: RunSummary) : DrainResult()
        data class AuthRequired(
            val message: String,
            override val summary: RunSummary,
        ) : DrainResult()
        data class RetryRequired(
            val delayMillis: Long,
            val reason: String,
            override val summary: RunSummary,
        ) : DrainResult()
    }

    private sealed class RowDrainResult {
        abstract val summary: RunSummary

        data class Completed(override val summary: RunSummary) : RowDrainResult()
        data class AuthRequired(
            val message: String,
            override val summary: RunSummary = RunSummary(),
        ) : RowDrainResult()
        data class RetryRequired(
            val delayMillis: Long,
            val reason: String,
            override val summary: RunSummary = RunSummary(),
        ) : RowDrainResult()
    }

    private suspend fun drainOutbox(
        scope: BtidalpoolOutboxRepository.Scope,
        useTestDb: Boolean,
        onProgress: (suspend (Long, Long) -> Unit)?,
        onBusyRetry: (suspend (BtidalpoolClient.BusyRetryState?) -> Unit)?,
    ): DrainResult {
        val ready = outboxRepository.readyChunks(scope)
        if (ready.isEmpty()) {
            val retryAt = outboxRepository.earliestRetryAt(scope)
            if (retryAt != null && outboxRepository.actionableCount(scope) > 0) {
                return DrainResult.RetryRequired(
                    delayMillis = (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L),
                    reason = "Waiting for the next scheduled retry.",
                    summary = RunSummary(),
                )
            }
            return DrainResult.Finished(RunSummary())
        }

        val totalBytes = ready.sumOf { it.payloadBytes }.coerceAtLeast(1L)
        var completedBytes = 0L
        var summary = RunSummary()
        for (batch in ready.chunked(MAX_PARALLEL_UPLOADS)) {
            val progressLock = Mutex()
            val inFlightProgress = mutableMapOf<String, Long>()
            val baseCompletedBytes = completedBytes
            val outcomes = coroutineScope {
                batch.map { row ->
                    async {
                        processRow(row, useTestDb, onBusyRetry) { sent, frameTotal ->
                            val local = if (frameTotal > 0) {
                                (sent.toDouble() / frameTotal * row.payloadBytes).toLong()
                            } else {
                                0L
                            }.coerceIn(0L, row.payloadBytes)
                            progressLock.withLock {
                                inFlightProgress[row.id] = local
                                onProgress?.invoke(
                                    (baseCompletedBytes + inFlightProgress.values.sum())
                                        .coerceAtMost(totalBytes),
                                    totalBytes,
                                )
                            }
                        }
                    }
                }
            }.awaitAll()

            outcomes.forEach { summary += it.summary }
            outcomes.filterIsInstance<RowDrainResult.AuthRequired>().firstOrNull()?.let {
                return DrainResult.AuthRequired(it.message, summary)
            }
            outcomes.filterIsInstance<RowDrainResult.RetryRequired>()
                .minByOrNull { it.delayMillis }
                ?.let {
                    return DrainResult.RetryRequired(it.delayMillis, it.reason, summary)
                }

            completedBytes += batch.sumOf { it.payloadBytes }
            onProgress?.invoke(completedBytes.coerceAtMost(totalBytes), totalBytes)
        }

        val retryAt = outboxRepository.earliestRetryAt(scope)
        return if (retryAt != null && outboxRepository.actionableCount(scope) > 0) {
            DrainResult.RetryRequired(
                delayMillis = (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L),
                reason = "One or more chunks are waiting for retry.",
                summary = summary,
            )
        } else {
            DrainResult.Finished(summary)
        }
    }

    private suspend fun processRow(
        row: BtidalpoolUploadEntity,
        useTestDb: Boolean,
        onBusyRetry: (suspend (BtidalpoolClient.BusyRetryState?) -> Unit)?,
        onProgress: suspend (Long, Long) -> Unit,
    ): RowDrainResult {
        val payload = File(row.payloadPath)
        if (!payload.isFile) {
            val message = "${row.sourceLogName} chunk ${row.chunkIndex + 1}: payload is missing"
            outboxRepository.markPermanentFailure(row, message)
            return RowDrainResult.Completed(
                RunSummary(permanentFailures = listOf(message)),
            )
        }
        when (
            val privacy = privacyValidator.validate(
                payload,
                outboxRepository.privacyPolicyFingerprint(row.batchId),
            )
        ) {
            BtidalpoolUploadPrivacyValidator.Validation.Safe -> Unit
            is BtidalpoolUploadPrivacyValidator.Validation.Blocked -> {
                // Make the whole batch stale. A later non-resume/manual run discards only these
                // generated files and rebuilds them from the original BTIDES log.
                outboxRepository.invalidatePrivacyPolicy(row.batchId)
                val message =
                    "${row.sourceLogName} chunk ${row.chunkIndex + 1}: ${privacy.reason}. " +
                        "Run the upload again to rebuild it safely."
                outboxRepository.markPermanentFailure(row, message)
                return RowDrainResult.Completed(
                    RunSummary(permanentFailures = listOf(message)),
                )
            }
        }
        outboxRepository.markInProgress(row)
        val uploadResult = resumableUploader.upload(
            row = row,
            payload = payload,
            useTestDb = useTestDb,
            onProgress = onProgress,
            onBusyRetry = onBusyRetry ?: {},
        )
        return when (uploadResult) {
            BtidalpoolClient.UploadResult.Success -> {
                outboxRepository.markSucceeded(row)
                RowDrainResult.Completed(
                    RunSummary(uploadedChunks = 1, uploadedDevices = row.deviceCount),
                )
            }
            BtidalpoolClient.UploadResult.AlreadyPresent -> {
                outboxRepository.markSucceeded(row)
                RowDrainResult.Completed(
                    RunSummary(alreadyPresentChunks = 1, uploadedDevices = row.deviceCount),
                )
            }
            BtidalpoolClient.UploadResult.AuthFailed -> {
                // Keep the chunk retryable. A same-account sign-in and another tap resumes it.
                val message = "BTIDALPOOL credentials were rejected"
                outboxRepository.markRetryable(row, message, System.currentTimeMillis())
                RowDrainResult.AuthRequired("$message. Sign out and sign in again.")
            }
            is BtidalpoolClient.UploadResult.PermanentFailure -> {
                val message =
                    "${row.sourceLogName} chunk ${row.chunkIndex + 1}: ${uploadResult.body}"
                outboxRepository.markPermanentFailure(row, message)
                RowDrainResult.Completed(
                    RunSummary(permanentFailures = listOf(message)),
                )
            }
            is BtidalpoolClient.UploadResult.RetryExhausted -> {
                val message =
                    "${row.sourceLogName} chunk ${row.chunkIndex + 1}: ${uploadResult.body}"
                outboxRepository.markPermanentFailure(row, message)
                RowDrainResult.Completed(
                    RunSummary(permanentFailures = listOf(message)),
                )
            }
            is BtidalpoolClient.UploadResult.RetryableFailure -> {
                val attempt = row.attemptCount + 1
                val delay = BtidalpoolRetryPolicy.nextDelayMillis(
                    attempt = attempt,
                    retryAfterMillis = uploadResult.retryAfterMillis,
                )
                val message =
                    "${row.sourceLogName} chunk ${row.chunkIndex + 1}: ${uploadResult.body}"
                outboxRepository.markRetryable(
                    row,
                    message,
                    System.currentTimeMillis() + delay,
                )
                RowDrainResult.RetryRequired(delay, message)
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL_UPLOADS = 2
    }
}

/** Exponential backoff with bounded positive jitter, while honoring a longer Retry-After. */
object BtidalpoolRetryPolicy {
    private const val BASE_DELAY_MS = 15_000L
    private const val MAX_DELAY_MS = 6L * 60 * 60 * 1_000

    fun nextDelayMillis(
        attempt: Int,
        retryAfterMillis: Long?,
        jitterMillis: Long? = null,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        val exponential = min(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl exponent))
        val jitterCap = (exponential / 4).coerceAtLeast(1L)
        val jitter = jitterMillis ?: Random.nextLong(0, jitterCap + 1)
        val calculated = (exponential + jitter.coerceIn(0, jitterCap)).coerceAtMost(MAX_DELAY_MS)
        return maxOf(calculated, retryAfterMillis?.coerceAtLeast(0L) ?: 0L)
    }
}
