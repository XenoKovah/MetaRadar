package com.darkmentor.data.btidalpool

import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class BtidalpoolResumableUploaderTest {
    private val server = MockWebServer()
    private val tempDir = Files.createTempDirectory("btidalpool_v2_test").toFile()
    private val stateDir = File(tempDir, "state")

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `resume follows server missing list and never replays acknowledged chunk`() = runBlocking {
        val payload = File(tempDir, "two-chunks.json").apply {
            writeBytes(ByteArray(BtidalpoolResumableUploader.CHUNK_SIZE + 17) { (it % 251).toByte() })
        }
        val chunks = listOf(
            payload.readBytes().copyOfRange(0, BtidalpoolResumableUploader.CHUNK_SIZE),
            payload.readBytes().copyOfRange(
                BtidalpoolResumableUploader.CHUNK_SIZE,
                payload.length().toInt(),
            ),
        )
        val store = BtidalpoolResumableStateStore(stateDir, testOnly = true)
        store.save(
            BtidalpoolResumableState(
                outboxId = "row-resume",
                contentSha256 = sha256(payload.readBytes()),
                totalSize = payload.length(),
                chunkSize = BtidalpoolResumableUploader.CHUNK_SIZE,
                chunkSha256 = chunks.map(::sha256),
                uploadId = "upload-id",
                acknowledgedChunks = setOf(0),
            ),
        )
        enqueueSession("session-secret")
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "manifest",
                    uploadId = "upload-id",
                    missingChunks = listOf(1),
                ),
            ),
        )
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "chunk",
                    uploadId = "upload-id",
                    index = 1,
                    alreadyPresent = false,
                ),
            ),
        )
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "status",
                    uploadId = "upload-id",
                    missingChunks = emptyList(),
                ),
            ),
        )
        server.enqueue(response(BtidalpoolCodec.V4WireResponse("finalized", receipt = receipt())))

        val result = uploader(store).upload(
            row(payload, "row-resume"),
            payload,
            useTestDb = false,
            onProgress = { _, _ -> },
            onBusyRetry = {},
        )

        assertTrue(result is BtidalpoolClient.UploadResult.Success)
        val commands = (0 until server.requestCount).map {
            BtidalpoolCodec.decodeV4RequestForTest(server.takeRequest().body.readByteArray()).payload
        }
        assertEquals(
            listOf("create_session", "manifest", "put_chunk", "status", "finalize"),
            commands.map { it.cmd },
        )
        assertEquals(listOf(1), commands.filter { it.cmd == "put_chunk" }.map { it.index })
        val persisted = store.load("row-resume")
        assertNotNull(persisted?.receipt)
        assertEquals(setOf(0, 1), persisted?.acknowledgedChunks)
        val serialized = File(stateDir, "row-resume.json").readText()
        assertFalse(serialized.contains("google-access"))
        assertFalse(serialized.contains("session-secret"))
    }

    @Test
    fun `session expiry after overload backoff reacquires session and replays manifest`() =
        runBlocking {
            val payload = File(tempDir, "one.json").apply { writeText("[{}]") }
            val store = BtidalpoolResumableStateStore(stateDir, testOnly = true)
            enqueueSession("old-session")
            server.enqueue(
                response(
                    BtidalpoolCodec.V4WireResponse(
                        "err",
                        kind = "server_busy",
                        message = "busy",
                    ),
                    code = 503,
                    retryAfter = "1",
                ),
            )
            server.enqueue(
                response(
                    BtidalpoolCodec.V4WireResponse(
                        "err",
                        kind = "session_expired",
                        message = "expired",
                    ),
                    code = 401,
                ),
            )
            enqueueSession("new-session")
            server.enqueue(
                response(
                    BtidalpoolCodec.V4WireResponse(
                        "manifest",
                        uploadId = "upload-id",
                        receipt = receipt(),
                    ),
                ),
            )

            val result = uploader(store).upload(
                row(payload, "row-expiry"),
                payload,
                false,
                { _, _ -> },
                {},
            )

            assertTrue(result is BtidalpoolClient.UploadResult.Success)
            val commands = (0 until server.requestCount).map {
                BtidalpoolCodec.decodeV4RequestForTest(server.takeRequest().body.readByteArray())
                    .payload.cmd
            }
            assertEquals(
                listOf("create_session", "manifest", "manifest", "create_session", "manifest"),
                commands,
            )
        }

    @Test
    fun `legacy v2 state without protocol field loads as resumable v4 state`() = runBlocking {
        check(stateDir.mkdirs())
        File(stateDir, "legacy-state.json").writeText(
            """
            {
              "outboxId": "legacy-state",
              "contentSha256": "${"a".repeat(64)}",
              "totalSize": 4,
              "chunkSize": 1048576,
              "chunkSha256": ["${"b".repeat(64)}"],
              "uploadId": "existing-upload",
              "acknowledgedChunks": [0],
              "receipt": null
            }
            """.trimIndent(),
        )

        val loaded = BtidalpoolResumableStateStore(stateDir, testOnly = true)
            .load("legacy-state")

        assertNotNull(loaded)
        assertEquals(4, loaded?.protocolVersion)
        assertEquals("existing-upload", loaded?.uploadId)
        assertEquals(setOf(0), loaded?.acknowledgedChunks)
    }

    private fun uploader(store: BtidalpoolResumableStateStore): BtidalpoolResumableUploader {
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns BtidalpoolAuthRepository.AuthState(
            token = "google-access",
            refreshToken = "google-refresh",
            email = "student@example.com",
        )
        val runtime = object : BtidalpoolRetryRuntime {
            private var now = 1_000_000L
            override fun wallClockMillis(): Long = now
            override fun monotonicMillis(): Long = now
            override fun jitterUnit(): Double = 0.0
            override suspend fun sleep(delayMillis: Long) {
                now += delayMillis
            }
        }
        val client = BtidalpoolClient(
            uploadClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            v4Url = server.url("/v4").toString(),
            retryRuntime = runtime,
        )
        return BtidalpoolResumableUploader(client, auth, store)
    }

    private fun enqueueSession(token: String) {
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    "session",
                    token = token,
                    expiresAtUnix = 4_000_000_000,
                ),
            ),
        )
    }

    private fun response(
        value: BtidalpoolCodec.V4WireResponse,
        code: Int = 200,
        retryAfter: String? = null,
    ): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", BtidalpoolCodec.V4_CONTENT_TYPE)
        .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }
        .setBody(Buffer().write(BtidalpoolCodec.encodeV4ResponseForTest(value)))

    private fun receipt() = BtidalpoolCodec.UploadReceipt(
        receiptId = "receipt-id",
        uploadId = "upload-id",
        contentSha256 = "content",
        canonicalSha1 = "canonical",
        totalSize = 4,
        completedAtUnix = 1,
        useTestDb = false,
        deduplicated = false,
    )

    private fun row(file: File, id: String) = BtidalpoolUploadEntity(
        id = id,
        batchId = "batch",
        sourceLogName = file.name,
        sourceSha256 = "source",
        chunkIndex = 0,
        chunkCount = 1,
        chunkSha256 = sha256(file.readBytes()),
        destination = "production",
        accountKey = "account",
        payloadPath = file.absolutePath,
        payloadBytes = file.length(),
        deviceCount = 1,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
