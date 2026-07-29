package com.darkmentor.data.btidalpool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BtidalpoolClientOverloadTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `429 delta seconds waits for Retry-After plus positive jitter and replays v4 request`() =
        runBlocking {
            val runtime = FakeRuntime(jitter = 0.0)
            val busyStates = mutableListOf<BtidalpoolClient.BusyRetryState>()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
            server.enqueue(sessionResponse())

            val result = client(runtime).createV4Session("google-token") {
                state -> state?.let(busyStates::add)
            }

            assertTrue(result is BtidalpoolClient.V4Result.Session)
            assertEquals(listOf(2_001L), runtime.delays)
            assertTrue(busyStates.single().message.startsWith("Server busy; retrying in"))
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/v4", first.path)
            assertEquals(BtidalpoolCodec.V4_CONTENT_TYPE, first.getHeader("Content-Type"))
            assertTrue(first.body.readByteArray().contentEquals(second.body.readByteArray()))
        }

    @Test
    fun `503 v4 server_busy body waits then replays exact operation`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        server.enqueue(
            v4Response(
                BtidalpoolCodec.V4WireResponse(
                    result = "err",
                    kind = "server_busy",
                    message = "capacity",
                ),
                code = 503,
                retryAfter = "3",
            ),
        )
        server.enqueue(sessionResponse())

        val result = client(runtime).createV4Session("google-token")

        assertTrue(result is BtidalpoolClient.V4Result.Session)
        assertEquals(listOf(3_001L), runtime.delays)
        val first = server.takeRequest().body.readByteArray()
        val second = server.takeRequest().body.readByteArray()
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `HTTP-date Retry-After is supported`() = runBlocking {
        val now = Instant.parse("2026-07-28T12:00:00Z")
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            now.plusSeconds(17).atZone(ZoneOffset.UTC),
        )
        val runtime = FakeRuntime(jitter = 0.0, now = now.toEpochMilli())
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", header))
        server.enqueue(sessionResponse())

        val result = client(runtime).createV4Session("google-token")

        assertTrue(result is BtidalpoolClient.V4Result.Session)
        assertEquals(listOf(17_001L), runtime.delays)
        assertEquals(
            17_000L,
            BtidalpoolOverloadRetry.parseRetryAfterMillis(header, now.toEpochMilli()),
        )
    }

    @Test
    fun `missing and malformed Retry-After use bounded fallback`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "tomorrow"))
        server.enqueue(sessionResponse())

        val result = client(runtime).createV4Session("google-token")

        assertTrue(result is BtidalpoolClient.V4Result.Session)
        assertEquals(listOf(1_001L, 2_001L), runtime.delays)
        assertEquals(null, BtidalpoolOverloadRetry.parseRetryAfterMillis(null, 0))
        assertEquals(null, BtidalpoolOverloadRetry.parseRetryAfterMillis("tomorrow", 0))
        assertEquals(null, BtidalpoolOverloadRetry.parseRetryAfterMillis("0", 0))
        val bases = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        assertEquals(
            bases.map { it + 1 },
            bases.indices.map { index ->
                BtidalpoolOverloadRetry.decision(index + 1, 0, null, 0.0)!!.delayMillis
            },
        )
    }

    @Test
    fun `jitter is positive and within one-second or quarter-delay bound`() {
        val minimum = BtidalpoolOverloadRetry.decision(1, 0, 20_000, 0.0)!!.delayMillis
        val maximum = BtidalpoolOverloadRetry.decision(1, 0, 20_000, 1.0)!!.delayMillis
        assertEquals(20_001, minimum)
        assertEquals(21_000, maximum)
        assertTrue(minimum > 20_000)
    }

    @Test
    fun `retry budget is bounded by attempts and elapsed time`() {
        assertEquals(
            null,
            BtidalpoolOverloadRetry.decision(
                BtidalpoolOverloadRetry.MAX_ATTEMPTS,
                0,
                1_000,
                0.0,
            ),
        )
        assertEquals(
            null,
            BtidalpoolOverloadRetry.decision(
                1,
                BtidalpoolOverloadRetry.MAX_ELAPSED_MILLIS - 500,
                1_000,
                0.0,
            ),
        )
    }

    @Test
    fun `cancellation during overload backoff stops before replay`() = runBlocking {
        val runtime = BlockingRuntime()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "30"))
        val job = launch { client(runtime).createV4Session("google-token") }
        runtime.sleepStarted.await()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `session expiry returned after overload backoff remains an auth response`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        server.enqueue(
            v4Response(
                BtidalpoolCodec.V4WireResponse("err", kind = "server_busy", message = "busy"),
                code = 503,
                retryAfter = "1",
            ),
        )
        server.enqueue(
            v4Response(
                BtidalpoolCodec.V4WireResponse(
                    "err",
                    kind = "session_expired",
                    message = "expired",
                ),
                code = 401,
            ),
        )

        val result = client(runtime).v4Status("old-session", "upload")

        assertTrue(result is BtidalpoolClient.V4Result.Error)
        result as BtidalpoolClient.V4Result.Error
        assertEquals(401, result.httpCode)
        assertEquals("session_expired", result.kind)
        assertFalse(result.retryExhausted)
        assertEquals(listOf(1_001L), runtime.delays)
    }

    @Test
    fun `permanent v4 4xx is not replayed unchanged`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        server.enqueue(
            v4Response(
                BtidalpoolCodec.V4WireResponse(
                    "err",
                    kind = "hash_mismatch",
                    message = "bad chunk",
                ),
                code = 422,
            ),
        )

        val result = client(runtime).v4PutChunk("session", "upload", 0, byteArrayOf(1))

        assertTrue(result is BtidalpoolClient.V4Result.Error)
        assertEquals(1, server.requestCount)
        assertTrue(runtime.delays.isEmpty())
    }

    @Test
    fun `v4 native query decodes field-stable lossless values`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        server.enqueue(
            v4Response(
                BtidalpoolCodec.V4WireResponse(
                    result = "native_query_result",
                    query = BtidalpoolCodec.V4NativeQueryResult(
                        devices = listOf(
                            BtidalpoolCodec.V4NativeDevice(
                                bdaddr = "AA:BB:CC:DD:EE:FF",
                                tables = mapOf(
                                    "LE_bdaddr_to_name" to BtidalpoolCodec.V4NativeTable(
                                        columns = listOf("payload", "large_unsigned"),
                                        rows = listOf(
                                            listOf(
                                                BtidalpoolCodec.V4DbValue(
                                                    kind = "bytes",
                                                    bytes = byteArrayOf(0, 0xFF.toByte()),
                                                ),
                                                BtidalpoolCodec.V4DbValue(
                                                    kind = "unsigned",
                                                    unsigned = "18446744073709551615",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        totalRows = 1,
                        rowLimit = 1_000,
                        truncated = false,
                    ),
                ),
            ),
        )

        val result = client(runtime).v4NativeQuery(
            "session",
            BtidalpoolCodec.QueryParams(bdaddr = "AA:BB:CC:DD:EE:FF"),
            useTestDb = true,
        )

        assertTrue(result is BtidalpoolClient.V4Result.NativeQuery)
        result as BtidalpoolClient.V4Result.NativeQuery
        val row = result.query.devices.single().tables.getValue("LE_bdaddr_to_name").rows.single()
        assertTrue(row[0].bytes!!.contentEquals(byteArrayOf(0, 0xFF.toByte())))
        assertEquals("18446744073709551615", row[1].unsigned)
    }

    @Test
    fun `retry exhaustion returns final v4 server-busy error`() = runBlocking {
        val runtime = FakeRuntime(jitter = 0.0)
        repeat(BtidalpoolOverloadRetry.MAX_ATTEMPTS) {
            server.enqueue(
                v4Response(
                    BtidalpoolCodec.V4WireResponse(
                        "err",
                        kind = "server_busy",
                        message = "busy",
                    ),
                    code = 503,
                ),
            )
        }

        val result = client(runtime).createV4Session("google")

        assertTrue(result is BtidalpoolClient.V4Result.Error)
        result as BtidalpoolClient.V4Result.Error
        assertTrue(result.retryExhausted)
        assertTrue(result.message.contains("retry limit reached"))
        assertEquals(BtidalpoolOverloadRetry.MAX_ATTEMPTS, server.requestCount)
        assertEquals(
            listOf(1_001L, 2_001L, 4_001L, 8_001L, 16_001L, 30_001L),
            runtime.delays,
        )
    }

    private fun client(runtime: BtidalpoolRetryRuntime): BtidalpoolClient =
        BtidalpoolClient(
            uploadClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            v4Url = server.url("/v4").toString(),
            retryRuntime = runtime,
        )

    private fun sessionResponse(): MockResponse = v4Response(
        BtidalpoolCodec.V4WireResponse(
            result = "session",
            token = "session-token",
            expiresAtUnix = 4_000_000_000,
        ),
    )

    private fun v4Response(
        response: BtidalpoolCodec.V4WireResponse,
        code: Int = 200,
        retryAfter: String? = null,
    ): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", BtidalpoolCodec.V4_CONTENT_TYPE)
        .apply { if (retryAfter != null) setHeader("Retry-After", retryAfter) }
        .setBody(Buffer().write(BtidalpoolCodec.encodeV4ResponseForTest(response)))

    private class FakeRuntime(
        private val jitter: Double,
        private var now: Long = 1_000_000,
    ) : BtidalpoolRetryRuntime {
        val delays = mutableListOf<Long>()
        override fun wallClockMillis(): Long = now
        override fun monotonicMillis(): Long = now
        override fun jitterUnit(): Double = jitter
        override suspend fun sleep(delayMillis: Long) {
            delays += delayMillis
            now += delayMillis
        }
    }

    private class BlockingRuntime : BtidalpoolRetryRuntime {
        val sleepStarted = CompletableDeferred<Unit>()
        override fun wallClockMillis(): Long = 1_000_000
        override fun monotonicMillis(): Long = 1_000_000
        override fun jitterUnit(): Double = 0.0
        override suspend fun sleep(delayMillis: Long) {
            sleepStarted.complete(Unit)
            awaitCancellation()
        }
    }
}
