package com.darkmentor.domain.interactor

import android.content.Context
import com.darkmentor.data.btidalpool.BtidalpoolAuthRepository
import com.darkmentor.data.btidalpool.BtidalpoolClient
import com.darkmentor.data.btides.BTIDESRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the per-log try/catch contract of [UploadToBtidalpoolInteractor.executeAll]: when one
 * log's upload throws, the loop must capture the failure as a [UploadToBtidalpoolInteractor.LogResult.Failed]
 * entry and continue with the remaining logs. Without this guarantee, a transient network
 * blip on archive #2 would silently abandon archives #3..N and the user would have to
 * re-trigger the upload — easily missed because the UI shows "upload finished" with the
 * results it does have.
 *
 * The test fakes the BTIDES side (real temp files, mockk-driven exporter that writes a few
 * bytes into the temp target) and the network side (mockk-driven [BtidalpoolClient]). The
 * interactor itself runs unmodified, so the test exercises the actual loop body — not a
 * paraphrased copy.
 *
 * Note on test runner: uses plain `runBlocking` rather than `kotlinx-coroutines-test`'s
 * `runTest`. Adding `coroutines-test` to the JVM-test classpath would be a new dep for one
 * test; the existing JVM tests in this repo all use `runBlocking` or non-suspending helpers
 * (matches the project's stated "don't add test deps unless you need them" rule).
 */
class UploadToBtidalpoolInteractorExecuteAllTest {

    private val cacheTempDir: File = Files.createTempDirectory("upload_executeAll_test").toFile()

    @After
    fun cleanup() {
        cacheTempDir.deleteRecursively()
    }

    @Test
    fun `one log throwing during uploadFile does not abort the rest of the loop`() = runBlocking {
        // GIVEN: signed-in, three rotated archives, and a BtidalpoolClient whose uploadFile
        // succeeds, throws, and succeeds in turn.
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheTempDir

        val authRepo = mockk<BtidalpoolAuthRepository>()
        every { authRepo.current() } returns BtidalpoolAuthRepository.AuthState(
            token = "tok",
            refreshToken = "ref",
            email = "tester@example.com",
        )

        val btidesRepo = mockk<BTIDESRepository>()
        coEvery { btidesRepo.rotateActive() } returns null
        val log1 = createLog("log1.jsonl", """{"sentinel":1}""")
        val log2 = createLog("log2.jsonl", """{"sentinel":2}""")
        val log3 = createLog("log3.jsonl", """{"sentinel":3}""")
        coEvery { btidesRepo.listLogs() } returns listOf(
            BTIDESRepository.LogFile(log1, isActive = false),
            BTIDESRepository.LogFile(log2, isActive = false),
            BTIDESRepository.LogFile(log3, isActive = false),
        )
        // The interactor records a successful upload via markUploaded() (it no longer deletes the
        // data). Stub the upload-tracking calls so the non-relaxed mock doesn't throw; the
        // success/failure split itself is what matters here.
        coEvery { btidesRepo.uploadedLogNames() } returns emptySet()
        coEvery { btidesRepo.markUploaded(any()) } returns Unit

        val exporter = mockk<ExportBTIDESInteractor>()
        // Side effect: write a small JSON blob into the tempExport target so [uploadOneLog]
        // sees length > 0 and proceeds past the EmptyLog short-circuit. Returns deviceCount=5.
        coEvery { exporter.executeForLog(any(), any(), any()) } coAnswers {
            val target = secondArg<File>()
            target.writeText("""{"AdvData":[]}""")
            5
        }

        val client = mockk<BtidalpoolClient>()
        // Sequential answers: success → throw → success. Counter inside coAnswers because the
        // sequence depends on call order, not arg matching.
        var uploadCallCount = 0
        coEvery { client.uploadFile(any(), any(), any(), any(), any()) } coAnswers {
            uploadCallCount += 1
            when (uploadCallCount) {
                1 -> BtidalpoolClient.UploadResult.Success
                2 -> throw RuntimeException("simulated transient network failure on archive 2")
                3 -> BtidalpoolClient.UploadResult.Success
                else -> error("uploadFile called more times than the test expected (got $uploadCallCount)")
            }
        }

        val interactor = UploadToBtidalpoolInteractor(
            btidesRepository = btidesRepo,
            exportBTIDESInteractor = exporter,
            client = client,
            authRepository = authRepo,
            context = context,
        )

        // WHEN
        val outcome = interactor.executeAll(useTestDb = true)

        // THEN: three results, second is Failed, third still ran.
        assertTrue(
            "executeAll must return WithResults when signed in (got $outcome)",
            outcome is UploadToBtidalpoolInteractor.Outcome.WithResults,
        )
        outcome as UploadToBtidalpoolInteractor.Outcome.WithResults
        assertEquals("Three input logs must yield three result entries", 3, outcome.results.size)
        assertTrue(
            "Log 1 should be Success — got ${outcome.results[0]}",
            outcome.results[0] is UploadToBtidalpoolInteractor.LogResult.Success,
        )
        assertTrue(
            "Log 2 should be Failed — the loop must catch the throw, not propagate it (got ${outcome.results[1]})",
            outcome.results[1] is UploadToBtidalpoolInteractor.LogResult.Failed,
        )
        assertTrue(
            "Log 3 should be Success — proves the loop did not break after log 2 threw " +
                    "(got ${outcome.results[2]})",
            outcome.results[2] is UploadToBtidalpoolInteractor.LogResult.Success,
        )
        // Failed log's message should at least include the simulated cause text or its class
        // name, so a real-world triage path can find the root cause without re-running.
        val failedMsg = (outcome.results[1] as UploadToBtidalpoolInteractor.LogResult.Failed).message
        assertTrue(
            "Failed.message should contain the exception message (got: $failedMsg)",
            failedMsg.contains("simulated transient network failure"),
        )
        // The biggest behavioural assertion: uploadFile was called exactly 3 times. If the
        // loop had aborted after the throw on call 2, we'd see only 2 calls.
        coVerify(exactly = 3) { client.uploadFile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `not signed in short-circuits before touching listLogs or the network`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val authRepo = mockk<BtidalpoolAuthRepository>()
        every { authRepo.current() } returns null
        val btidesRepo = mockk<BTIDESRepository>(relaxed = true)
        val exporter = mockk<ExportBTIDESInteractor>(relaxed = true)
        val client = mockk<BtidalpoolClient>(relaxed = true)

        val interactor = UploadToBtidalpoolInteractor(btidesRepo, exporter, client, authRepo, context)
        val outcome = interactor.executeAll(useTestDb = true)

        assertSame(UploadToBtidalpoolInteractor.Outcome.NotSignedIn, outcome)
        // No log enumeration and no network calls — both expensive on a slow link, and the
        // OAuth-prompt UI is the contract for the NotSignedIn path.
        coVerify(exactly = 0) { btidesRepo.listLogs() }
        coVerify(exactly = 0) { client.uploadFile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `empty log list returns a single EmptyLog result`() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheTempDir

        val authRepo = mockk<BtidalpoolAuthRepository>()
        every { authRepo.current() } returns BtidalpoolAuthRepository.AuthState("t", "r", null)

        val btidesRepo = mockk<BTIDESRepository>()
        coEvery { btidesRepo.rotateActive() } returns null
        coEvery { btidesRepo.uploadedLogNames() } returns emptySet()
        coEvery { btidesRepo.listLogs() } returns emptyList()

        val exporter = mockk<ExportBTIDESInteractor>(relaxed = true)
        val client = mockk<BtidalpoolClient>(relaxed = true)

        val interactor = UploadToBtidalpoolInteractor(btidesRepo, exporter, client, authRepo, context)
        val outcome = interactor.executeAll(useTestDb = true)

        assertTrue(outcome is UploadToBtidalpoolInteractor.Outcome.WithResults)
        outcome as UploadToBtidalpoolInteractor.Outcome.WithResults
        assertEquals(1, outcome.results.size)
        assertTrue(
            "Empty corpus returns exactly one EmptyLog result so the UI can render the " +
                    "'nothing to upload' state without special-casing on size==0",
            outcome.results[0] is UploadToBtidalpoolInteractor.LogResult.EmptyLog,
        )
        // No network at all on an empty corpus.
        coVerify(exactly = 0) { client.uploadFile(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `already-uploaded archives are skipped and kept, unless allowReupload`() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheTempDir
        val authRepo = mockk<BtidalpoolAuthRepository>()
        every { authRepo.current() } returns BtidalpoolAuthRepository.AuthState("t", "r", null)

        val btidesRepo = mockk<BTIDESRepository>()
        coEvery { btidesRepo.rotateActive() } returns null
        val log1 = createLog("log1.jsonl", """{"s":1}""")
        val log2 = createLog("log2.jsonl", """{"s":2}""")
        val log3 = createLog("log3.jsonl", """{"s":3}""")
        coEvery { btidesRepo.listLogs() } returns listOf(
            BTIDESRepository.LogFile(log1, isActive = false),
            BTIDESRepository.LogFile(log2, isActive = false),
            BTIDESRepository.LogFile(log3, isActive = false),
        )
        coEvery { btidesRepo.uploadedLogNames() } returns setOf("log2.jsonl") // log2 already sent
        coEvery { btidesRepo.markUploaded(any()) } returns Unit

        val exporter = mockk<ExportBTIDESInteractor>()
        coEvery { exporter.executeForLog(any(), any(), any()) } coAnswers {
            secondArg<File>().writeText("""{"AdvData":[]}"""); 5
        }
        val client = mockk<BtidalpoolClient>()
        coEvery { client.uploadFile(any(), any(), any(), any(), any()) } returns BtidalpoolClient.UploadResult.Success

        val interactor = UploadToBtidalpoolInteractor(btidesRepo, exporter, client, authRepo, context)

        // Default: log2 is skipped -> only 2 uploads, and NO file is deleted (data is kept).
        val outcome = interactor.executeAll(useTestDb = true)
        outcome as UploadToBtidalpoolInteractor.Outcome.WithResults
        assertEquals(2, outcome.results.size)
        coVerify(exactly = 2) { client.uploadFile(any(), any(), any(), any(), any()) }
        assertTrue("uploaded archives must NOT be deleted", log1.exists() && log2.exists() && log3.exists())

        // Debug override: allowReupload re-sends all three (including the already-uploaded log2).
        interactor.executeAll(useTestDb = true, allowReupload = true)
        coVerify(exactly = 5) { client.uploadFile(any(), any(), any(), any(), any()) } // 2 + 3
    }

    private fun createLog(name: String, content: String): File {
        val f = File(cacheTempDir, name)
        f.writeText(content)
        return f
    }
}
