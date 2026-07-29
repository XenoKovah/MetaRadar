package com.darkmentor.data.btidalpool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable, non-secret v4 resume state. Authentication credentials deliberately have no fields in
 * this schema and session tokens remain process-memory-only in [BtidalpoolResumableUploader].
 * The existing directory name is retained so in-progress v2 uploads resume seamlessly after the
 * client upgrade.
 */
@Serializable
data class BtidalpoolResumableState(
    val protocolVersion: Int = 4,
    val outboxId: String,
    val contentSha256: String,
    val totalSize: Long,
    val chunkSize: Int,
    val chunkSha256: List<String>,
    val uploadId: String? = null,
    val acknowledgedChunks: Set<Int> = emptySet(),
    val receipt: BtidalpoolCodec.UploadReceipt? = null,
)

class BtidalpoolResumableStateStore private constructor(
    private val rootDirectory: File,
) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    internal constructor(rootDirectory: File, testOnly: Boolean) : this(rootDirectory) {
        check(testOnly)
    }

    private val lock = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun load(outboxId: String): BtidalpoolResumableState? = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = stateFile(outboxId)
            if (!file.isFile) return@withLock null
            runCatching { json.decodeFromString<BtidalpoolResumableState>(file.readText()) }
                .getOrNull()
        }
    }

    suspend fun save(state: BtidalpoolResumableState) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(rootDirectory.exists() || rootDirectory.mkdirs()) {
                "Could not create BTIDALPOOL resumable state directory"
            }
            val target = stateFile(state.outboxId)
            val temporary = File(rootDirectory, "${target.name}.tmp")
            temporary.writeText(json.encodeToString(state))
            check(temporary.renameTo(target)) {
                temporary.delete()
                "Could not atomically persist BTIDALPOOL v4 resume state"
            }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        lock.withLock { rootDirectory.deleteRecursively() }
    }

    suspend fun delete(outboxId: String) = withContext(Dispatchers.IO) {
        lock.withLock { stateFile(outboxId).delete() }
    }

    private fun stateFile(outboxId: String): File {
        require(outboxId.matches(SAFE_ID)) { "Unsafe outbox id" }
        return File(rootDirectory, "$outboxId.json")
    }

    companion object {
        private const val DIRECTORY = "btidalpool_v2_state"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
    }
}
