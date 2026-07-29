package com.darkmentor.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity

@Dao
interface BtidalpoolUploadDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<BtidalpoolUploadEntity>)

    @Query(
        """
        SELECT id FROM btidalpool_upload_outbox
        WHERE chunk_sha256 = :chunkSha256
          AND destination = :destination
          AND account_key = :accountKey
          AND state = 'succeeded'
        LIMIT 1
        """,
    )
    suspend fun succeededChunkId(
        chunkSha256: String,
        destination: String,
        accountKey: String,
    ): String?

    @Query(
        """
        SELECT batch_id FROM btidalpool_upload_outbox
        WHERE source_sha256 = :sourceSha256
          AND destination = :destination
          AND account_key = :accountKey
          AND state != 'succeeded'
        ORDER BY created_at_ms DESC
        LIMIT 1
        """,
    )
    suspend fun incompleteBatchId(
        sourceSha256: String,
        destination: String,
        accountKey: String,
    ): String?

    @Query(
        """
        UPDATE btidalpool_upload_outbox
        SET state = 'pending',
            next_attempt_at_ms = 0,
            last_error = NULL,
            updated_at_ms = :nowMs
        WHERE batch_id = :batchId
          AND state != 'succeeded'
        """,
    )
    suspend fun resetBatchForManualRetry(batchId: String, nowMs: Long)

    @Query(
        """
        UPDATE btidalpool_upload_outbox
        SET state = 'pending', updated_at_ms = :nowMs
        WHERE state = 'in_progress'
          AND destination = :destination
          AND account_key = :accountKey
        """,
    )
    suspend fun recoverInterrupted(
        destination: String,
        accountKey: String,
        nowMs: Long,
    )

    @Query(
        """
        SELECT * FROM btidalpool_upload_outbox
        WHERE destination = :destination
          AND account_key = :accountKey
          AND state IN ('pending', 'retryable')
          AND next_attempt_at_ms <= :nowMs
        ORDER BY created_at_ms, batch_id, chunk_index
        """,
    )
    suspend fun readyChunks(
        destination: String,
        accountKey: String,
        nowMs: Long,
    ): List<BtidalpoolUploadEntity>

    @Query(
        """
        SELECT MIN(next_attempt_at_ms) FROM btidalpool_upload_outbox
        WHERE destination = :destination
          AND account_key = :accountKey
          AND state = 'retryable'
        """,
    )
    suspend fun earliestRetryAt(
        destination: String,
        accountKey: String,
    ): Long?

    @Query(
        """
        SELECT COUNT(*) FROM btidalpool_upload_outbox
        WHERE destination = :destination
          AND account_key = :accountKey
          AND state != 'succeeded'
          AND state != 'permanent_failure'
        """,
    )
    suspend fun actionableCount(destination: String, accountKey: String): Int

    @Query(
        """
        UPDATE btidalpool_upload_outbox
        SET state = :state,
            attempt_count = :attemptCount,
            next_attempt_at_ms = :nextAttemptAtMs,
            last_error = :lastError,
            updated_at_ms = :updatedAtMs,
            uploaded_at_ms = :uploadedAtMs
        WHERE id = :id
        """,
    )
    suspend fun updateState(
        id: String,
        state: String,
        attemptCount: Int,
        nextAttemptAtMs: Long,
        lastError: String?,
        updatedAtMs: Long,
        uploadedAtMs: Long?,
    )

    @Query("SELECT payload_path FROM btidalpool_upload_outbox")
    suspend fun allPayloadPaths(): List<String>

    @Query("SELECT * FROM btidalpool_upload_outbox WHERE batch_id = :batchId")
    suspend fun rowsForBatch(batchId: String): List<BtidalpoolUploadEntity>

    @Query("DELETE FROM btidalpool_upload_outbox WHERE batch_id = :batchId")
    suspend fun deleteBatch(batchId: String)

    @Query("DELETE FROM btidalpool_upload_outbox")
    suspend fun deleteAll()
}
