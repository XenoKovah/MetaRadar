package com.darkmentor.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable upload outbox row for one bounded BTIDES chunk.
 *
 * The server destination and account key are part of the identity on purpose: successfully
 * sending bytes to the test database, or under another Google account, must never suppress a
 * production upload for the current account.
 */
@Entity(
    tableName = "btidalpool_upload_outbox",
    indices = [
        Index(
            name = "index_btidalpool_outbox_scope_state",
            value = ["destination", "account_key", "state", "next_attempt_at_ms"],
        ),
        Index(
            name = "index_btidalpool_outbox_source_scope",
            value = ["source_sha256", "destination", "account_key"],
        ),
        Index(
            name = "index_btidalpool_outbox_chunk_scope",
            value = ["chunk_sha256", "destination", "account_key"],
        ),
        Index(
            name = "index_btidalpool_outbox_batch_chunk",
            value = ["batch_id", "chunk_index"],
            unique = true,
        ),
    ],
)
data class BtidalpoolUploadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "batch_id")
    val batchId: String,
    @ColumnInfo(name = "source_log_name")
    val sourceLogName: String,
    @ColumnInfo(name = "source_sha256")
    val sourceSha256: String,
    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,
    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int,
    @ColumnInfo(name = "chunk_sha256")
    val chunkSha256: String,
    @ColumnInfo(name = "destination")
    val destination: String,
    @ColumnInfo(name = "account_key")
    val accountKey: String,
    @ColumnInfo(name = "payload_path")
    val payloadPath: String,
    @ColumnInfo(name = "payload_bytes")
    val payloadBytes: Long,
    @ColumnInfo(name = "device_count")
    val deviceCount: Int,
    @ColumnInfo(name = "state")
    val state: String = State.PENDING,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at_ms")
    val nextAttemptAtMs: Long = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "uploaded_at_ms")
    val uploadedAtMs: Long? = null,
) {
    object State {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val RETRYABLE = "retryable"
        const val PERMANENT_FAILURE = "permanent_failure"
        const val SUCCEEDED = "succeeded"
    }

    object Destination {
        const val PRODUCTION = "production"
        const val TEST = "test"

        fun fromUseTestDb(useTestDb: Boolean): String = if (useTestDb) TEST else PRODUCTION
    }
}
