package com.darkmentor.data.btidalpool

import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

/**
 * V4 resumable upload state machine.
 *
 * The manifest and acknowledgements are written after every server acknowledgement. A fresh
 * manifest/status response is authoritative for missing chunks, so an acknowledged chunk is never
 * sent merely because local state is stale. Finalize is safe to replay after a lost response.
 */
class BtidalpoolResumableUploader(
    private val client: BtidalpoolClient,
    private val authRepository: BtidalpoolAuthRepository,
    private val stateStore: BtidalpoolResumableStateStore,
) {
    private data class MemorySession(val token: String, val expiresAtUnix: Long)

    private val sessionMutex = Mutex()
    private var memorySession: MemorySession? = null

    suspend fun upload(
        row: BtidalpoolUploadEntity,
        payload: File,
        useTestDb: Boolean,
        onProgress: suspend (Long, Long) -> Unit,
        onBusyRetry: suspend (BtidalpoolClient.BusyRetryState?) -> Unit,
    ): BtidalpoolClient.UploadResult = withContext(Dispatchers.IO) {
        try {
            val state = loadOrCreateState(row, payload)
            if (state.contentSha256 != row.chunkSha256) {
                return@withContext BtidalpoolClient.UploadResult.PermanentFailure(
                    422,
                    "Local payload hash no longer matches its durable outbox manifest",
                )
            }
            var current = state
            repeat(MAX_MANIFEST_REPLAYS) {
                val manifestResult = sessionOperation(onBusyRetry) { session ->
                    client.v4Manifest(
                        session,
                        current.contentSha256,
                        current.totalSize,
                        current.chunkSha256,
                        useTestDb,
                        onBusyRetry,
                    )
                }
                when (manifestResult) {
                    is BtidalpoolClient.V4Result.Manifest -> {
                        if (current.uploadId != null && current.uploadId != manifestResult.uploadId) {
                            return@withContext BtidalpoolClient.UploadResult.PermanentFailure(
                                409,
                                "Server returned a different upload ID for the persisted manifest",
                            )
                        }
                        current = current.copy(
                            uploadId = manifestResult.uploadId,
                            acknowledgedChunks = acknowledgedFromMissing(
                                current.chunkSha256.size,
                                manifestResult.missingChunks,
                            ),
                            receipt = manifestResult.receipt,
                        )
                        stateStore.save(current)
                        manifestResult.receipt?.let {
                            return@withContext receiptResult(it)
                        }

                        val completed = uploadMissingAndFinalize(
                            state = current,
                            initialMissing = manifestResult.missingChunks,
                            payload = payload,
                            onProgress = onProgress,
                            onBusyRetry = onBusyRetry,
                        )
                        if (completed is RestartManifest) {
                            current = completed.state
                        } else {
                            return@withContext (completed as Completed).result
                        }
                    }
                    else -> return@withContext mapFailure(manifestResult)
                }
            }
            BtidalpoolClient.UploadResult.RetryableFailure(
                404,
                "BTIDALPOOL upload state changed repeatedly; retrying from the manifest",
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            BtidalpoolClient.UploadResult.RetryableFailure(
                -1,
                t.message ?: t::class.java.simpleName,
            )
        }
    }

    private sealed interface UploadCycle
    private data class RestartManifest(val state: BtidalpoolResumableState) : UploadCycle
    private data class Completed(val result: BtidalpoolClient.UploadResult) : UploadCycle

    private suspend fun uploadMissingAndFinalize(
        state: BtidalpoolResumableState,
        initialMissing: List<Int>,
        payload: File,
        onProgress: suspend (Long, Long) -> Unit,
        onBusyRetry: suspend (BtidalpoolClient.BusyRetryState?) -> Unit,
    ): UploadCycle {
        var current = state
        var missing = validateMissing(initialMissing, current.chunkSha256.size)
            ?: return Completed(
                BtidalpoolClient.UploadResult.PermanentFailure(
                    400,
                    "Server returned an invalid missing-chunk index",
                ),
            )
        repeat(MAX_STATUS_CYCLES) {
            for (index in missing) {
                val bytes = readChunk(payload, index, current.chunkSize)
                val result = sessionOperation(onBusyRetry) { session ->
                    client.v4PutChunk(
                        session,
                        checkNotNull(current.uploadId),
                        index,
                        bytes,
                        onBusyRetry,
                    )
                }
                when (result) {
                    is BtidalpoolClient.V4Result.Chunk -> {
                        if (result.index != index || result.uploadId != current.uploadId) {
                            return Completed(
                                BtidalpoolClient.UploadResult.PermanentFailure(
                                    409,
                                    "Server acknowledged a different v4 chunk",
                                ),
                            )
                        }
                        current = current.copy(
                            acknowledgedChunks = current.acknowledgedChunks + index,
                        )
                        stateStore.save(current)
                        onProgress(acknowledgedBytes(current), current.totalSize)
                    }
                    is BtidalpoolClient.V4Result.Error
                        if result.httpCode == 404 || result.kind == "not_found" ->
                        return RestartManifest(current)
                    else -> return Completed(mapFailure(result))
                }
            }

            val status = sessionOperation(onBusyRetry) { session ->
                client.v4Status(session, checkNotNull(current.uploadId), onBusyRetry)
            }
            when (status) {
                is BtidalpoolClient.V4Result.Status -> {
                    status.receipt?.let { receipt ->
                        current = current.copy(receipt = receipt)
                        stateStore.save(current)
                        return Completed(receiptResult(receipt))
                    }
                    missing = validateMissing(status.missingChunks, current.chunkSha256.size)
                        ?: return Completed(
                            BtidalpoolClient.UploadResult.PermanentFailure(
                                400,
                                "Server returned an invalid missing-chunk index",
                            ),
                        )
                    current = current.copy(
                        acknowledgedChunks = acknowledgedFromMissing(
                            current.chunkSha256.size,
                            missing,
                        ),
                    )
                    stateStore.save(current)
                    if (missing.isNotEmpty()) return@repeat
                }
                is BtidalpoolClient.V4Result.Error
                    if status.httpCode == 404 || status.kind == "not_found" ->
                    return RestartManifest(current)
                else -> return Completed(mapFailure(status))
            }

            val finalized = sessionOperation(onBusyRetry) { session ->
                client.v4Finalize(session, checkNotNull(current.uploadId), onBusyRetry)
            }
            when (finalized) {
                is BtidalpoolClient.V4Result.Finalized -> {
                    current = current.copy(receipt = finalized.receipt)
                    stateStore.save(current)
                    return Completed(receiptResult(finalized.receipt))
                }
                is BtidalpoolClient.V4Result.Error
                    if finalized.httpCode == 404 || finalized.kind == "not_found" ->
                    return RestartManifest(current)
                is BtidalpoolClient.V4Result.Error
                    if finalized.httpCode == 409 && finalized.missingChunks.isNotEmpty() -> {
                    missing = validateMissing(finalized.missingChunks, current.chunkSha256.size)
                        ?: return Completed(
                            BtidalpoolClient.UploadResult.PermanentFailure(
                                409,
                                "Finalize returned an invalid missing-chunk index",
                            ),
                        )
                }
                else -> return Completed(mapFailure(finalized))
            }
        }
        return Completed(
            BtidalpoolClient.UploadResult.RetryableFailure(
                409,
                "BTIDALPOOL repeatedly reported missing chunks",
            ),
        )
    }

    /**
     * Reacquire a short-lived v4 session and replay an idempotent operation once after 401.
     * This is deliberately outside overload backoff so session expiry is never shown as busy.
     */
    private suspend fun sessionOperation(
        onBusyRetry: suspend (BtidalpoolClient.BusyRetryState?) -> Unit,
        operation: suspend (sessionToken: String) -> BtidalpoolClient.V4Result,
    ): BtidalpoolClient.V4Result {
        val firstSession = when (val session = session(force = false, onBusyRetry)) {
            is BtidalpoolClient.V4Result.Session -> session
            else -> return session
        }
        val first = operation(firstSession.token)
        if (first !is BtidalpoolClient.V4Result.Error ||
            (first.httpCode != 401 &&
                first.kind != "session_expired" &&
                first.kind != "unauthorized")
        ) {
            return first
        }
        invalidateSession(firstSession.token)
        val refreshed = when (val session = session(force = true, onBusyRetry)) {
            is BtidalpoolClient.V4Result.Session -> session
            else -> return session
        }
        return operation(refreshed.token)
    }

    private suspend fun session(
        force: Boolean,
        onBusyRetry: suspend (BtidalpoolClient.BusyRetryState?) -> Unit,
    ): BtidalpoolClient.V4Result = sessionMutex.withLock {
        val nowUnix = System.currentTimeMillis() / 1_000L
        if (!force) {
            memorySession?.takeIf { it.expiresAtUnix > nowUnix + SESSION_EXPIRY_SKEW_SECONDS }
                ?.let { return@withLock BtidalpoolClient.V4Result.Session(it.token, it.expiresAtUnix) }
        }
        var auth = authRepository.current()
            ?: return@withLock BtidalpoolClient.V4Result.Error(401, "unauthorized", "Not signed in")
        var created = client.createV4Session(auth.token, onBusyRetry)
        if (created is BtidalpoolClient.V4Result.Error &&
            created.httpCode == 401 &&
            (created.kind == "unauthorized" || created.kind == "session_expired")
        ) {
            created = when (val refresh = authRepository.refresh()) {
                is BtidalpoolAuthRepository.RefreshOutcome.Success -> {
                    auth = refresh.state
                    client.createV4Session(auth.token, onBusyRetry)
                }
                is BtidalpoolAuthRepository.RefreshOutcome.InvalidGrant ->
                    BtidalpoolClient.V4Result.Error(401, "unauthorized", refresh.message)
                is BtidalpoolAuthRepository.RefreshOutcome.TransientFailure ->
                    BtidalpoolClient.V4Result.TransportFailure(refresh.message)
            }
        }
        if (created is BtidalpoolClient.V4Result.Session) {
            memorySession = MemorySession(created.token, created.expiresAtUnix)
        }
        created
    }

    private suspend fun invalidateSession(token: String) = sessionMutex.withLock {
        if (memorySession?.token == token) memorySession = null
    }

    private suspend fun loadOrCreateState(
        row: BtidalpoolUploadEntity,
        payload: File,
    ): BtidalpoolResumableState {
        stateStore.load(row.id)?.let { existing ->
            if (existing.totalSize == payload.length() &&
                existing.contentSha256 == row.chunkSha256
            ) {
                return existing
            }
        }
        val hashes = mutableListOf<String>()
        val whole = MessageDigest.getInstance("SHA-256")
        payload.inputStream().buffered().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count == 0) break
                whole.update(buffer, 0, count)
                hashes += MessageDigest.getInstance("SHA-256")
                    .digest(buffer.copyOf(count))
                    .toHex()
            }
        }
        require(hashes.isNotEmpty()) { "BTIDALPOOL v4 cannot upload an empty payload" }
        val state = BtidalpoolResumableState(
            outboxId = row.id,
            contentSha256 = whole.digest().toHex(),
            totalSize = payload.length(),
            chunkSize = CHUNK_SIZE,
            chunkSha256 = hashes,
        )
        stateStore.save(state)
        return state
    }

    private fun readChunk(payload: File, index: Int, chunkSize: Int): ByteArray {
        val offset = index.toLong() * chunkSize
        val length = min(chunkSize.toLong(), payload.length() - offset).toInt()
        require(length > 0) { "Invalid local v4 chunk index $index" }
        return RandomAccessFile(payload, "r").use { file ->
            file.seek(offset)
            ByteArray(length).also(file::readFully)
        }
    }

    private fun acknowledgedBytes(state: BtidalpoolResumableState): Long =
        state.acknowledgedChunks.sumOf { index ->
            min(state.chunkSize.toLong(), state.totalSize - index.toLong() * state.chunkSize)
                .coerceAtLeast(0L)
        }

    private fun acknowledgedFromMissing(chunkCount: Int, missing: List<Int>): Set<Int> {
        val missingSet = missing.toSet()
        return (0 until chunkCount).filterNotTo(mutableSetOf()) { it in missingSet }
    }

    private fun validateMissing(missing: List<Int>, chunkCount: Int): List<Int>? =
        missing.distinct().sorted().takeIf { indexes -> indexes.all { it in 0 until chunkCount } }

    private fun receiptResult(receipt: BtidalpoolCodec.UploadReceipt): BtidalpoolClient.UploadResult =
        if (receipt.deduplicated) BtidalpoolClient.UploadResult.AlreadyPresent
        else BtidalpoolClient.UploadResult.Success

    private fun mapFailure(result: BtidalpoolClient.V4Result): BtidalpoolClient.UploadResult =
        when (result) {
            is BtidalpoolClient.V4Result.Error -> when {
                result.retryExhausted -> BtidalpoolClient.UploadResult.RetryExhausted(
                    result.httpCode,
                    result.message,
                )
                result.httpCode == 401 ||
                    result.kind == "unauthorized" ||
                    result.kind == "session_expired" -> BtidalpoolClient.UploadResult.AuthFailed
                result.httpCode == 408 || result.httpCode >= 500 ->
                    BtidalpoolClient.UploadResult.RetryableFailure(
                        result.httpCode,
                        result.message,
                    )
                else -> BtidalpoolClient.UploadResult.PermanentFailure(
                    result.httpCode,
                    "${result.kind ?: "error"}: ${result.message}",
                )
            }
            is BtidalpoolClient.V4Result.TransportFailure ->
                BtidalpoolClient.UploadResult.RetryableFailure(-1, result.message)
            else -> BtidalpoolClient.UploadResult.PermanentFailure(
                -1,
                "Unexpected BTIDALPOOL v4 response",
            )
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        internal const val CHUNK_SIZE = 1024 * 1024
        private const val SESSION_EXPIRY_SKEW_SECONDS = 30L
        private const val MAX_MANIFEST_REPLAYS = 3
        private const val MAX_STATUS_CYCLES = 3
    }
}
