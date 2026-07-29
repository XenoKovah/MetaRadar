package com.darkmentor.data.btidalpool

import android.content.Context
import com.darkmentor.data.database.dao.BtidalpoolUploadDao
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import com.darkmentor.domain.interactor.ExportBTIDESInteractor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BtidalpoolOutboxRepositoryPrivacyTest {
    private val tempDir = Files.createTempDirectory("btidalpool_outbox_privacy").toFile()
    private val filesDir = File(tempDir, "app-files").apply { mkdirs() }
    private val context = mockk<Context>().also {
        every { it.filesDir } returns filesDir
    }
    private val dao = mockk<BtidalpoolUploadDao>()
    private val stateStore = BtidalpoolResumableStateStore(
        File(filesDir, "resume-state"),
        testOnly = true,
    )
    private val repository = BtidalpoolOutboxRepository(context, dao, stateStore)

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `insert persists policy marker alongside exact generated payload`() = runBlocking {
        val (batchId, batchDir) = repository.newBatchDirectory("batch-one")
        val payload = File(batchDir, "chunk.btides").apply { writeText("[]") }
        val rows = slot<List<BtidalpoolUploadEntity>>()
        coEvery { dao.insertAll(capture(rows)) } returns Unit

        repository.insertBatch(
            batchId = batchId,
            sourceLog = File(tempDir, "source.jsonl").apply { writeText("{}") },
            sourceSha256 = "source-sha",
            scope = BtidalpoolOutboxRepository.Scope("test", "account"),
            chunks = listOf(
                ExportBTIDESInteractor.UploadChunk(
                    file = payload,
                    index = 0,
                    deviceCount = 1,
                    sha256 = "payload-sha",
                    privacyPolicyFingerprint = POLICY,
                ),
            ),
        )

        assertEquals(POLICY, repository.privacyPolicyFingerprint(batchId))
        assertEquals(payload.absolutePath, rows.captured.single().payloadPath)
    }

    @Test
    fun `discard deletes generated payload and resume state but preserves source log`() =
        runBlocking {
            val (batchId, batchDir) = repository.newBatchDirectory("batch-two")
            val source = File(tempDir, "immutable-source.jsonl").apply { writeText("{}") }
            val payload = File(batchDir, "chunk.btides").apply { writeText("[]") }
            val row = row(batchId, payload)
            coEvery { dao.rowsForBatch(batchId) } returns listOf(row)
            coEvery { dao.deleteBatch(batchId) } returns Unit
            stateStore.save(
                BtidalpoolResumableState(
                    outboxId = row.id,
                    contentSha256 = "content",
                    totalSize = payload.length(),
                    chunkSize = 1,
                    chunkSha256 = listOf("chunk"),
                ),
            )
            assertNotNull(stateStore.load(row.id))

            repository.discardBatch(batchId)

            assertTrue(source.isFile)
            assertFalse(payload.exists())
            assertFalse(batchDir.exists())
            assertEquals(null, stateStore.load(row.id))
            coVerify(exactly = 1) { dao.deleteBatch(batchId) }
        }

    @Test(expected = IllegalArgumentException::class)
    fun `batch directory rejects path traversal`() {
        repository.newBatchDirectory("../outside")
    }

    private fun row(batchId: String, payload: File) = BtidalpoolUploadEntity(
        id = "row-one",
        batchId = batchId,
        sourceLogName = "immutable-source.jsonl",
        sourceSha256 = "source",
        chunkIndex = 0,
        chunkCount = 1,
        chunkSha256 = "chunk",
        destination = "test",
        accountKey = "account",
        payloadPath = payload.absolutePath,
        payloadBytes = payload.length(),
        deviceCount = 1,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private companion object {
        const val POLICY =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
