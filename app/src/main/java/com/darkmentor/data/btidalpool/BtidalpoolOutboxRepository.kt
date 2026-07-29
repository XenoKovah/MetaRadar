package com.darkmentor.data.btidalpool

import android.content.Context
import com.darkmentor.data.database.dao.BtidalpoolUploadDao
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class BtidalpoolOutboxRepository(
    private val context: Context,
    private val dao: BtidalpoolUploadDao,
    private val resumableStateStore: BtidalpoolResumableStateStore? = null,
) {
    data class Scope(val destination: String, val accountKey: String)

    val rootDir: File
        get() = File(context.filesDir, OUTBOX_DIR).also { it.mkdirs() }

    fun scope(auth: BtidalpoolAuthRepository.AuthState, useTestDb: Boolean): Scope {
        val stableIdentity = auth.email?.trim()?.lowercase()
            ?: "legacy-refresh:${sha256(auth.refreshToken.toByteArray(Charsets.UTF_8))}"
        return Scope(
            destination = BtidalpoolUploadEntity.Destination.fromUseTestDb(useTestDb),
            accountKey = sha256(stableIdentity.toByteArray(Charsets.UTF_8)),
        )
    }

    fun newBatchDirectory(batchId: String = UUID.randomUUID().toString()): Pair<String, File> {
        val dir = batchDirectory(batchId)
        check(dir.mkdirs()) { "Could not create BTIDALPOOL outbox directory ${dir.path}" }
        return batchId to dir
    }

    suspend fun insertBatch(
        batchId: String,
        sourceLog: File,
        sourceSha256: String,
        scope: Scope,
        chunks: List<com.darkmentor.domain.interactor.ExportBTIDESInteractor.UploadChunk>,
    ) = withContext(Dispatchers.IO) {
        require(chunks.isNotEmpty())
        val fingerprints = chunks.map { it.privacyPolicyFingerprint }.distinct()
        require(fingerprints.size == 1) { "One outbox batch cannot mix GPS exclusion policies" }
        val batchDir = chunks.first().file.parentFile
            ?: error("BTIDALPOOL chunk has no batch directory")
        require(chunks.all { it.file.parentFile == batchDir }) {
            "One outbox batch cannot span directories"
        }
        require(batchDir.canonicalFile == batchDirectory(batchId).canonicalFile) {
            "BTIDALPOOL chunks must be inside their generated outbox batch"
        }
        writePrivacyPolicyFingerprint(batchDir, fingerprints.single())
        val now = System.currentTimeMillis()
        val rows = chunks.map { chunk ->
            BtidalpoolUploadEntity(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                sourceLogName = sourceLog.name,
                sourceSha256 = sourceSha256,
                chunkIndex = chunk.index,
                chunkCount = chunks.size,
                chunkSha256 = chunk.sha256,
                destination = scope.destination,
                accountKey = scope.accountKey,
                payloadPath = chunk.file.absolutePath,
                payloadBytes = chunk.file.length(),
                deviceCount = chunk.deviceCount,
                createdAtMs = now,
                updatedAtMs = now,
            )
        }
        dao.insertAll(rows)
    }

    fun privacyPolicyFingerprint(batchId: String): String? =
        runCatching {
            batchDirectory(batchId)
                .resolve(PRIVACY_POLICY_FILE)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.matches(SHA256) }
        }.getOrNull()

    suspend fun invalidatePrivacyPolicy(batchId: String): Unit = withContext(Dispatchers.IO) {
        batchDirectory(batchId).resolve(PRIVACY_POLICY_FILE).delete()
        Unit
    }

    /**
     * Remove only a generated outbox batch and its resumable metadata. The immutable source
     * BTIDES log is outside [rootDir] and is never touched.
     */
    suspend fun discardBatch(batchId: String): Unit = withContext(Dispatchers.IO) {
        val rows = dao.rowsForBatch(batchId)
        dao.deleteBatch(batchId)
        rows.forEach { row ->
            runCatching { File(row.payloadPath).delete() }
            resumableStateStore?.delete(row.id)
        }
        val batchDir = batchDirectory(batchId)
        runCatching { batchDir.deleteRecursively() }
        Unit
    }

    suspend fun succeededChunkId(chunkSha256: String, scope: Scope): String? =
        dao.succeededChunkId(chunkSha256, scope.destination, scope.accountKey)

    suspend fun incompleteBatchId(sourceSha256: String, scope: Scope): String? =
        dao.incompleteBatchId(sourceSha256, scope.destination, scope.accountKey)

    suspend fun resetBatchForManualRetry(batchId: String) =
        dao.resetBatchForManualRetry(batchId, System.currentTimeMillis())

    suspend fun recoverInterrupted(scope: Scope) =
        dao.recoverInterrupted(scope.destination, scope.accountKey, System.currentTimeMillis())

    suspend fun readyChunks(scope: Scope): List<BtidalpoolUploadEntity> =
        dao.readyChunks(scope.destination, scope.accountKey, System.currentTimeMillis())

    suspend fun earliestRetryAt(scope: Scope): Long? =
        dao.earliestRetryAt(scope.destination, scope.accountKey)

    suspend fun actionableCount(scope: Scope): Int =
        dao.actionableCount(scope.destination, scope.accountKey)

    suspend fun markInProgress(row: BtidalpoolUploadEntity) {
        dao.updateState(
            id = row.id,
            state = State.IN_PROGRESS,
            attemptCount = row.attemptCount,
            nextAttemptAtMs = 0,
            lastError = null,
            updatedAtMs = System.currentTimeMillis(),
            uploadedAtMs = null,
        )
    }

    suspend fun markSucceeded(row: BtidalpoolUploadEntity) {
        val now = System.currentTimeMillis()
        dao.updateState(
            id = row.id,
            state = State.SUCCEEDED,
            attemptCount = row.attemptCount + 1,
            nextAttemptAtMs = 0,
            lastError = null,
            updatedAtMs = now,
            uploadedAtMs = now,
        )
        withContext(Dispatchers.IO) { runCatching { File(row.payloadPath).delete() } }
    }

    suspend fun markRetryable(
        row: BtidalpoolUploadEntity,
        message: String,
        nextAttemptAtMs: Long,
    ) {
        dao.updateState(
            id = row.id,
            state = State.RETRYABLE,
            attemptCount = row.attemptCount + 1,
            nextAttemptAtMs = nextAttemptAtMs,
            lastError = message,
            updatedAtMs = System.currentTimeMillis(),
            uploadedAtMs = null,
        )
    }

    suspend fun markPermanentFailure(row: BtidalpoolUploadEntity, message: String) {
        dao.updateState(
            id = row.id,
            state = State.PERMANENT_FAILURE,
            attemptCount = row.attemptCount + 1,
            nextAttemptAtMs = 0,
            lastError = message,
            updatedAtMs = System.currentTimeMillis(),
            uploadedAtMs = null,
        )
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.allPayloadPaths().forEach { path -> runCatching { File(path).delete() } }
        dao.deleteAll()
        resumableStateStore?.deleteAll()
        runCatching { rootDir.deleteRecursively() }
    }

    suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private fun batchDirectory(batchId: String): File {
        require(batchId.matches(SAFE_ID)) { "Unsafe BTIDALPOOL batch id" }
        return File(rootDir, batchId)
    }

    private fun writePrivacyPolicyFingerprint(batchDir: File, fingerprint: String) {
        require(fingerprint.matches(SHA256))
        val target = batchDir.resolve(PRIVACY_POLICY_FILE)
        val temporary = batchDir.resolve("$PRIVACY_POLICY_FILE.tmp")
        temporary.writeText(fingerprint)
        check(temporary.renameTo(target)) {
            temporary.delete()
            "Could not atomically persist BTIDALPOOL GPS exclusion policy"
        }
    }

    companion object {
        private const val OUTBOX_DIR = "btidalpool_outbox"
        private const val PRIVACY_POLICY_FILE = ".gps-exclusion-policy.sha256"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
