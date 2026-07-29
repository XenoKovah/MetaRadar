package com.darkmentor.domain.interactor

import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.btidalpool.BtidalpoolClient
import com.darkmentor.data.btidalpool.BtidalpoolOutboxRepository
import com.darkmentor.data.btidalpool.BtidalpoolResumableUploader
import com.darkmentor.data.btidalpool.BtidalpoolUploadPrivacyValidator
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class UploadToBtidalpoolInteractorExecuteAllTest {
    private val tempDir = Files.createTempDirectory("btidalpool_outbox_test").toFile()

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `not signed in short-circuits before logs or v4 upload`() = runBlocking {
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns null
        val btides = mockk<BTIDESRepository>(relaxed = true)
        val uploader = mockk<BtidalpoolResumableUploader>(relaxed = true)
        val interactor = interactor(btides = btides, auth = auth, uploader = uploader)

        val result = interactor.execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = false,
            allowReupload = false,
        )

        assertSame(UploadToBtidalpoolInteractor.Execution.NotSignedIn, result)
        coVerify(exactly = 0) { btides.listLogs() }
        coVerify(exactly = 0) {
            uploader.upload(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `exact chunk completion lookup is scoped to account and test destination`() = runBlocking {
        val authState = BtidalpoolAuthRepository.AuthState("token", "refresh", "one@example.com")
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns authState
        val scope = BtidalpoolOutboxRepository.Scope("test", "account-one")
        val outbox = mockk<BtidalpoolOutboxRepository>()
        every { outbox.scope(authState, true) } returns scope
        coEvery { outbox.recoverInterrupted(scope) } returns Unit

        val source = File(tempDir, "archive.jsonl").apply { writeText("""{"bdaddr":"A"}""") }
        val btides = mockk<BTIDESRepository>()
        coEvery { btides.rotateActive() } returns null
        coEvery { btides.listLogs() } returns listOf(BTIDESRepository.LogFile(source, false))
        coEvery { outbox.sha256(source) } returns "source-hash"
        coEvery { outbox.incompleteBatchId("source-hash", scope) } returns null
        val batchDir = File(tempDir, "batch").apply { mkdirs() }
        every { outbox.newBatchDirectory(any()) } returns ("batch" to batchDir)
        val chunkFile = File(batchDir, "chunk.btides").apply { writeText("[]") }
        val exporter = mockk<ExportBTIDESInteractor>()
        coEvery { exporter.executeUploadChunks(source, batchDir, null) } returns listOf(
            ExportBTIDESInteractor.UploadChunk(
                chunkFile,
                0,
                1,
                "exact-chunk-hash",
                TEST_POLICY,
            ),
        )
        coEvery { outbox.succeededChunkId("exact-chunk-hash", scope) } returns "done-row"
        coEvery { outbox.readyChunks(scope) } returns emptyList()
        coEvery { outbox.earliestRetryAt(scope) } returns null

        val result = interactor(
            btides = btides,
            auth = auth,
            outbox = outbox,
            exporter = exporter,
        ).execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = true,
            allowReupload = false,
        )

        assertTrue(result is UploadToBtidalpoolInteractor.Execution.Finished)
        result as UploadToBtidalpoolInteractor.Execution.Finished
        assertEquals(1, result.summary.skippedUploadedLogs)
        coVerify(exactly = 1) { outbox.succeededChunkId("exact-chunk-hash", scope) }
    }

    @Test
    fun `retryable v4 upload records next attempt and asks worker to continue later`() =
        runBlocking {
            val fixture = readyFixture("retry.btides")
            val uploader = mockk<BtidalpoolResumableUploader>()
            coEvery {
                uploader.upload(fixture.row, fixture.payload, false, any(), any())
            } returns BtidalpoolClient.UploadResult.RetryableFailure(
                httpCode = 503,
                body = "temporarily unavailable",
                retryAfterMillis = 60_000,
            )

            val result = interactor(
                auth = fixture.auth,
                outbox = fixture.outbox,
                uploader = uploader,
            ).execute(
                mode = UploadToBtidalpoolInteractor.Mode.ALL,
                useTestDb = false,
                allowReupload = false,
                resumeOnly = true,
                expectedAccountKey = fixture.scope.accountKey,
            )

            assertTrue(result is UploadToBtidalpoolInteractor.Execution.RetryRequired)
            result as UploadToBtidalpoolInteractor.Execution.RetryRequired
            assertTrue(result.delayMillis >= 60_000)
            coVerify(exactly = 1) {
                fixture.outbox.markRetryable(
                    fixture.row,
                    match { it.contains("temporarily") },
                    any(),
                )
            }
        }

    @Test
    fun `v4 auth rejection keeps payload retryable and requires sign-in`() = runBlocking {
        val fixture = readyFixture("auth.btides")
        val uploader = mockk<BtidalpoolResumableUploader>()
        coEvery {
            uploader.upload(fixture.row, fixture.payload, false, any(), any())
        } returns BtidalpoolClient.UploadResult.AuthFailed

        val result = interactor(
            auth = fixture.auth,
            outbox = fixture.outbox,
            uploader = uploader,
        ).execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = false,
            allowReupload = false,
            resumeOnly = true,
            expectedAccountKey = fixture.scope.accountKey,
        )

        assertTrue(result is UploadToBtidalpoolInteractor.Execution.AuthRequired)
        coVerify(exactly = 1) {
            fixture.outbox.markRetryable(fixture.row, any(), any())
        }
    }

    @Test
    fun `overload retry exhaustion is a final user-facing failure`() = runBlocking {
        val fixture = readyFixture("exhausted.btides", expectsPermanentFailure = true)
        val uploader = mockk<BtidalpoolResumableUploader>()
        coEvery {
            uploader.upload(fixture.row, fixture.payload, false, any(), any())
        } returns BtidalpoolClient.UploadResult.RetryExhausted(
            503,
            "Server busy; retry limit reached",
        )

        val result = interactor(
            auth = fixture.auth,
            outbox = fixture.outbox,
            uploader = uploader,
        ).execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = false,
            allowReupload = false,
            resumeOnly = true,
            expectedAccountKey = fixture.scope.accountKey,
        )

        assertTrue(result is UploadToBtidalpoolInteractor.Execution.Finished)
        result as UploadToBtidalpoolInteractor.Execution.Finished
        assertTrue(result.summary.permanentFailures.single().contains("retry limit reached"))
    }

    @Test
    fun `privacy rejection invalidates batch and never enters network uploader`() = runBlocking {
        val fixture = readyFixture("privacy-blocked.btides", expectsPermanentFailure = true)
        val uploader = mockk<BtidalpoolResumableUploader>(relaxed = true)
        val privacy = mockk<BtidalpoolUploadPrivacyValidator>()
        coEvery {
            privacy.validate(fixture.payload, TEST_POLICY)
        } returns BtidalpoolUploadPrivacyValidator.Validation.Blocked(
            "A queued device now has location history inside an exclusion zone",
        )
        coEvery { fixture.outbox.invalidatePrivacyPolicy(fixture.row.batchId) } returns Unit

        val result = interactor(
            auth = fixture.auth,
            outbox = fixture.outbox,
            uploader = uploader,
            privacy = privacy,
        ).execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = false,
            allowReupload = false,
            resumeOnly = true,
            expectedAccountKey = fixture.scope.accountKey,
        )

        assertTrue(result is UploadToBtidalpoolInteractor.Execution.Finished)
        result as UploadToBtidalpoolInteractor.Execution.Finished
        assertTrue(result.summary.permanentFailures.single().contains("rebuild it safely"))
        coVerify(exactly = 1) {
            fixture.outbox.invalidatePrivacyPolicy(fixture.row.batchId)
        }
        coVerify(exactly = 0) {
            uploader.upload(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `legacy batch without privacy marker is discarded and re-exported from source`() =
        runBlocking {
            val authState =
                BtidalpoolAuthRepository.AuthState("token", "refresh", "one@example.com")
            val auth = mockk<BtidalpoolAuthRepository>()
            every { auth.current() } returns authState
            val scope = BtidalpoolOutboxRepository.Scope("production", "account-one")
            val outbox = mockk<BtidalpoolOutboxRepository>()
            every { outbox.scope(authState, false) } returns scope
            coEvery { outbox.recoverInterrupted(scope) } returns Unit

            val source = File(tempDir, "legacy.jsonl").apply {
                writeText("""{"bdaddr":"AA:BB:CC:DD:EE:FF"}""")
            }
            val btides = mockk<BTIDESRepository>()
            coEvery { btides.rotateActive() } returns null
            coEvery { btides.listLogs() } returns listOf(BTIDESRepository.LogFile(source, false))
            coEvery { outbox.sha256(source) } returns "source-hash"
            coEvery { outbox.incompleteBatchId("source-hash", scope) } returns "legacy-batch"
            every { outbox.privacyPolicyFingerprint("legacy-batch") } returns null
            coEvery { outbox.discardBatch("legacy-batch") } returns Unit

            val batchDir = File(tempDir, "replacement-batch").apply { mkdirs() }
            every { outbox.newBatchDirectory(any()) } returns ("replacement-batch" to batchDir)
            val chunkFile = File(batchDir, "chunk.btides").apply { writeText("[]") }
            val replacement = ExportBTIDESInteractor.UploadChunk(
                chunkFile,
                0,
                1,
                "replacement-hash",
                TEST_POLICY,
            )
            val exporter = mockk<ExportBTIDESInteractor>()
            coEvery {
                exporter.executeUploadChunks(source, batchDir, null)
            } returns listOf(replacement)
            coEvery { outbox.succeededChunkId("replacement-hash", scope) } returns null
            coEvery {
                outbox.insertBatch(
                    "replacement-batch",
                    source,
                    "source-hash",
                    scope,
                    listOf(replacement),
                )
            } returns Unit
            coEvery { outbox.readyChunks(scope) } returns emptyList()
            coEvery { outbox.earliestRetryAt(scope) } returns null

            val privacy = mockk<BtidalpoolUploadPrivacyValidator>()
            every { privacy.currentPolicyFingerprint() } returns TEST_POLICY

            val result = interactor(
                btides = btides,
                exporter = exporter,
                auth = auth,
                outbox = outbox,
                privacy = privacy,
            ).execute(
                mode = UploadToBtidalpoolInteractor.Mode.ALL,
                useTestDb = false,
                allowReupload = false,
            )

            assertTrue(result is UploadToBtidalpoolInteractor.Execution.Finished)
            result as UploadToBtidalpoolInteractor.Execution.Finished
            assertEquals(1, result.summary.preparedLogs)
            coVerify(exactly = 1) { outbox.discardBatch("legacy-batch") }
            coVerify(exactly = 1) { exporter.executeUploadChunks(source, batchDir, null) }
            coVerify(exactly = 0) { outbox.resetBatchForManualRetry(any()) }
        }

    @Test
    fun `ready chunks upload through v4 with bounded parallelism of two`() = runBlocking {
        val authState = BtidalpoolAuthRepository.AuthState("token", "refresh", "one@example.com")
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns authState
        val scope = BtidalpoolOutboxRepository.Scope("production", "account-one")
        val outbox = mockk<BtidalpoolOutboxRepository>()
        every { outbox.scope(authState, false) } returns scope
        coEvery { outbox.recoverInterrupted(scope) } returns Unit

        val firstPayload = File(tempDir, "parallel_one.btides").apply { writeText("[]") }
        val secondPayload = File(tempDir, "parallel_two.btides").apply { writeText("[]") }
        val rows = listOf(
            row(firstPayload, id = "row-one"),
            row(secondPayload, id = "row-two"),
        )
        coEvery { outbox.readyChunks(scope) } returns rows
        every { outbox.privacyPolicyFingerprint(any()) } returns TEST_POLICY
        coEvery { outbox.markInProgress(any()) } returns Unit
        coEvery { outbox.markSucceeded(any()) } returns Unit
        coEvery { outbox.earliestRetryAt(scope) } returns null

        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val uploader = mockk<BtidalpoolResumableUploader>()
        coEvery { uploader.upload(any(), any(), false, any(), any()) } coAnswers {
            val now = active.incrementAndGet()
            maximum.updateAndGet { current -> maxOf(current, now) }
            delay(75)
            active.decrementAndGet()
            BtidalpoolClient.UploadResult.Success
        }

        val result = interactor(
            auth = auth,
            outbox = outbox,
            uploader = uploader,
        ).execute(
            mode = UploadToBtidalpoolInteractor.Mode.ALL,
            useTestDb = false,
            allowReupload = false,
            resumeOnly = true,
            expectedAccountKey = scope.accountKey,
        )

        assertTrue(result is UploadToBtidalpoolInteractor.Execution.Finished)
        assertEquals(2, maximum.get())
        coVerify(exactly = 2) { outbox.markSucceeded(any()) }
    }

    @Test
    fun `retry policy honors Retry-After and grows exponentially`() {
        assertEquals(60_000, BtidalpoolRetryPolicy.nextDelayMillis(1, 60_000, jitterMillis = 0))
        assertEquals(30_000, BtidalpoolRetryPolicy.nextDelayMillis(2, null, jitterMillis = 0))
        assertEquals(60_000, BtidalpoolRetryPolicy.nextDelayMillis(3, null, jitterMillis = 0))
    }

    private data class ReadyFixture(
        val auth: BtidalpoolAuthRepository,
        val scope: BtidalpoolOutboxRepository.Scope,
        val outbox: BtidalpoolOutboxRepository,
        val payload: File,
        val row: BtidalpoolUploadEntity,
    )

    private fun readyFixture(
        filename: String,
        expectsPermanentFailure: Boolean = false,
    ): ReadyFixture {
        val authState = BtidalpoolAuthRepository.AuthState("token", "refresh", "one@example.com")
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns authState
        val scope = BtidalpoolOutboxRepository.Scope("production", "account-one")
        val outbox = mockk<BtidalpoolOutboxRepository>()
        every { outbox.scope(authState, false) } returns scope
        coEvery { outbox.recoverInterrupted(scope) } returns Unit
        val payload = File(tempDir, filename).apply { writeText("[]") }
        val row = row(payload)
        coEvery { outbox.readyChunks(scope) } returns listOf(row)
        every { outbox.privacyPolicyFingerprint(row.batchId) } returns TEST_POLICY
        coEvery { outbox.markInProgress(row) } returns Unit
        if (expectsPermanentFailure) {
            coEvery { outbox.markPermanentFailure(row, any()) } returns Unit
            coEvery { outbox.earliestRetryAt(scope) } returns null
        } else {
            coEvery { outbox.markRetryable(row, any(), any()) } returns Unit
        }
        return ReadyFixture(auth, scope, outbox, payload, row)
    }

    private fun row(payload: File, id: String = "row") = BtidalpoolUploadEntity(
        id = id,
        batchId = "batch",
        sourceLogName = "source.jsonl",
        sourceSha256 = "source",
        chunkIndex = 0,
        chunkCount = 1,
        chunkSha256 = "chunk",
        destination = "production",
        accountKey = "account-one",
        payloadPath = payload.absolutePath,
        payloadBytes = payload.length(),
        deviceCount = 2,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun interactor(
        btides: BTIDESRepository = mockk(relaxed = true),
        exporter: ExportBTIDESInteractor = mockk(relaxed = true),
        auth: BtidalpoolAuthRepository = mockk(relaxed = true),
        outbox: BtidalpoolOutboxRepository = mockk(relaxed = true),
        uploader: BtidalpoolResumableUploader = mockk(relaxed = true),
        privacy: BtidalpoolUploadPrivacyValidator = allowingPrivacyValidator(),
    ) = UploadToBtidalpoolInteractor(
        btidesRepository = btides,
        exportBTIDESInteractor = exporter,
        authRepository = auth,
        outboxRepository = outbox,
        resumableUploader = uploader,
        privacyValidator = privacy,
    )

    private fun allowingPrivacyValidator() = mockk<BtidalpoolUploadPrivacyValidator>().also {
        every { it.currentPolicyFingerprint() } returns TEST_POLICY
        coEvery { it.validate(any(), any()) } returns
            BtidalpoolUploadPrivacyValidator.Validation.Safe
    }

    private companion object {
        const val TEST_POLICY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
