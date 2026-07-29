package com.darkmentor.data.btidalpool

import android.content.Context
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.database.dao.RssiLocationRow
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.ExportBTIDESInteractor
import com.darkmentor.domain.model.ExclusionZone
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * End-to-end privacy QA from saved GPS exclusion settings through the bytes observed by an HTTP
 * server. This deliberately uses the production exporter, v4 resumable uploader, wire codec,
 * zstd framing, and OkHttp transport. The MockWebServer plays only the remote BTIDALPOOL role.
 */
class BtidalpoolGpsExclusionWireTest {
    private val server = MockWebServer()
    private val root = Files.createTempDirectory("btidalpool_gps_wire").toFile()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    @Test
    fun `no excluded address or coordinate crosses the resumable v4 wire`() = runBlocking {
        val zone = ExclusionZone.Square(
            centerLat = 10.0,
            centerLng = 20.0,
            halfSizeMeters = 300.0,
        )
        val payload = exportUploadPayload(zone)
        val payloadBytes = payload.readBytes()
        val contentSha256 = sha256(payloadBytes)
        val transportChunks = payloadBytes.asList()
            .chunked(BtidalpoolResumableUploader.CHUNK_SIZE)
            .map { it.toByteArray() }

        enqueueSession()
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "manifest",
                    uploadId = UPLOAD_ID,
                    missingChunks = transportChunks.indices.toList(),
                ),
            ),
        )
        transportChunks.indices.forEach { index ->
            server.enqueue(
                response(
                    BtidalpoolCodec.V4WireResponse(
                        result = "chunk",
                        uploadId = UPLOAD_ID,
                        index = index,
                        alreadyPresent = false,
                    ),
                ),
            )
        }
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "status",
                    uploadId = UPLOAD_ID,
                    missingChunks = emptyList(),
                ),
            ),
        )
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "finalized",
                    receipt = BtidalpoolCodec.UploadReceipt(
                        receiptId = "receipt",
                        uploadId = UPLOAD_ID,
                        contentSha256 = contentSha256,
                        canonicalSha1 = "a".repeat(40),
                        totalSize = payload.length(),
                        completedAtUnix = 1,
                        useTestDb = true,
                        deduplicated = false,
                    ),
                ),
            ),
        )

        val result = uploader().upload(
            row = row(payload, contentSha256),
            payload = payload,
            useTestDb = true,
            onProgress = { _, _ -> },
            onBusyRetry = {},
        )
        assertTrue(result is BtidalpoolClient.UploadResult.Success)

        val requests = (0 until server.requestCount).map {
            server.takeRequest().also { request ->
                assertEquals("/v4", request.path)
                assertEquals(BtidalpoolCodec.V4_CONTENT_TYPE, request.getHeader("Content-Type"))
            }
        }
        val commands = requests.map { request ->
            BtidalpoolCodec.decodeV4RequestForTest(request.body.readByteArray()).payload
        }
        assertEquals(
            listOf("create_session", "manifest") +
                List(transportChunks.size) { "put_chunk" } +
                listOf("status", "finalize"),
            commands.map { it.cmd },
        )

        val manifest = commands.single { it.cmd == "manifest" }
        assertEquals(contentSha256, manifest.contentSha256)
        assertEquals(payload.length(), manifest.totalSize)
        assertEquals(true, manifest.useTestDb)
        assertEquals(transportChunks.map(::sha256), manifest.chunkSha256)

        val received = ByteArrayOutputStream().apply {
            commands.filter { it.cmd == "put_chunk" }
                .sortedBy { it.index }
                .forEach { write(it.data) }
        }.toByteArray()
        assertArrayEquals("server must receive exactly the filtered upload file", payloadBytes, received)
        assertEquals(contentSha256, sha256(received))

        val devices = Json.parseToJsonElement(received.toString(Charsets.UTF_8)).jsonArray
        val addresses = devices.map { it.jsonObject["bdaddr"]!!.jsonPrimitive.content }
        assertEquals(listOf(SAFE_ADDRESS, NO_GPS_ADDRESS), addresses)
        assertFalse(received.toString(Charsets.UTF_8).contains(STRONGEST_INSIDE_ADDRESS))
        assertFalse(received.toString(Charsets.UTF_8).contains(HISTORICAL_INSIDE_ADDRESS))

        val sentCoordinates = devices.flatMap { device ->
            device.jsonObject["GPSArray"]?.jsonArray.orEmpty().map { gps ->
                val objectValue = gps.jsonObject
                objectValue["lat"]!!.jsonPrimitive.double to
                    objectValue["lon"]!!.jsonPrimitive.double
            }
        }
        assertTrue(
            "no coordinate received by the server may be inside the zone",
            sentCoordinates.none { (lat, lng) -> zone.contains(lat, lng) },
        )
        assertTrue(
            "fixture must cross more than one transport chunk",
            transportChunks.size > 1,
        )
    }

    private suspend fun exportUploadPayload(zone: ExclusionZone): File {
        val context = mockk<Context>().also {
            every { it.cacheDir } returns root
            every { it.filesDir } returns root
        }
        val source = File(root, "source.jsonl").apply {
            writeText(
                listOf(
                    record(STRONGEST_INSIDE_ADDRESS),
                    record(SAFE_ADDRESS, "x".repeat(BtidalpoolResumableUploader.CHUNK_SIZE + 4_096)),
                    record(HISTORICAL_INSIDE_ADDRESS),
                    record(NO_GPS_ADDRESS),
                ).joinToString("\n", postfix = "\n"),
            )
        }
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        coEvery { locations.getAllRssiLocationsByAddress() } returns mapOf(
            STRONGEST_INSIDE_ADDRESS to listOf(row(1_000, 10.0, 20.0, -30)),
            SAFE_ADDRESS to listOf(row(2_000, 51.5, -0.12, -40)),
            HISTORICAL_INSIDE_ADDRESS to listOf(
                row(3_000, 30.0, 40.0, -25),
                row(2_500, 10.0001, 20.0001, -90),
            ),
        )
        val chunks = ExportBTIDESInteractor(
            btidesRepository = BTIDESRepository(context),
            locationRepository = locations,
            settingsRepository = settings,
        ).executeUploadChunks(source, File(root, "export"))
        assertEquals("fixture must produce one upload file", 1, chunks.size)
        assertEquals(2, chunks.single().deviceCount)
        return chunks.single().file
    }

    private fun uploader(): BtidalpoolResumableUploader {
        val auth = mockk<BtidalpoolAuthRepository>()
        every { auth.current() } returns BtidalpoolAuthRepository.AuthState(
            token = "google-access",
            refreshToken = "google-refresh",
            email = "qa@example.com",
        )
        val runtime = object : BtidalpoolRetryRuntime {
            override fun wallClockMillis(): Long = 1_000_000
            override fun monotonicMillis(): Long = 1_000_000
            override fun jitterUnit(): Double = 0.0
            override suspend fun sleep(delayMillis: Long) = Unit
        }
        val client = BtidalpoolClient(
            uploadClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
            v4Url = server.url("/v4").toString(),
            retryRuntime = runtime,
        )
        return BtidalpoolResumableUploader(
            client = client,
            authRepository = auth,
            stateStore = BtidalpoolResumableStateStore(File(root, "state"), testOnly = true),
        )
    }

    private fun enqueueSession() {
        server.enqueue(
            response(
                BtidalpoolCodec.V4WireResponse(
                    result = "session",
                    token = "session",
                    expiresAtUnix = 4_000_000_000,
                ),
            ),
        )
    }

    private fun response(value: BtidalpoolCodec.V4WireResponse) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", BtidalpoolCodec.V4_CONTENT_TYPE)
        .setBody(Buffer().write(BtidalpoolCodec.encodeV4ResponseForTest(value)))

    private fun row(payload: File, sha256: String) = BtidalpoolUploadEntity(
        id = "gps-wire",
        batchId = "qa",
        sourceLogName = "source.jsonl",
        sourceSha256 = "source",
        chunkIndex = 0,
        chunkCount = 1,
        chunkSha256 = sha256,
        destination = BtidalpoolUploadEntity.Destination.TEST,
        accountKey = "qa",
        payloadPath = payload.absolutePath,
        payloadBytes = payload.length(),
        deviceCount = 2,
        createdAtMs = 1,
        updatedAtMs = 1,
    )

    private fun record(address: String, data: String = "") =
        """{"bdaddr":"$address","bdaddr_rand":1,"AdvChanArray":[{"type":0,"data":"$data"}]}"""

    private fun row(time: Long, lat: Double, lng: Double, rssi: Int) =
        RssiLocationRow(time = time, lat = lat, lng = lng, rssi = rssi)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val UPLOAD_ID = "gps-upload"
        private const val STRONGEST_INSIDE_ADDRESS = "AA:AA:AA:AA:AA:AA"
        private const val SAFE_ADDRESS = "BB:BB:BB:BB:BB:BB"
        private const val HISTORICAL_INSIDE_ADDRESS = "CC:CC:CC:CC:CC:CC"
        private const val NO_GPS_ADDRESS = "DD:DD:DD:DD:DD:DD"
    }
}
