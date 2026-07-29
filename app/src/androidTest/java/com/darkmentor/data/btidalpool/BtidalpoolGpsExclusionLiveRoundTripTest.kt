package com.darkmentor.data.btidalpool

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.btides.StrongestRssiLocation
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import com.darkmentor.domain.model.ExclusionZone
import junit.framework.AssertionFailedError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Live, test-database-only round trip for GPS exclusion.
 *
 * Synthetic devices are filtered through the real upload chunk exporter, sent with the real v4
 * resumable client, then queried back from BTIDALPOOL's test database. Random addresses prevent
 * pre-existing data from satisfying the assertion. No production database or captured phone data
 * is modified.
 */
@RunWith(AndroidJUnit4::class)
class BtidalpoolGpsExclusionLiveRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun only_out_of_zone_data_arrives_in_btidalpool_test_database() = runBlocking {
        val client = KoinJavaComponent.getKoin().get<BtidalpoolClient>()
        val auth = KoinJavaComponent.getKoin().get<BtidalpoolAuthRepository>()
        assumeNotNull("Live GPS exclusion QA requires BTIDALPOOL sign-in.", auth.current())

        val runId = UUID.randomUUID().toString().replace("-", "").uppercase()
        val prefix = "02:${runId.substring(0, 2)}:${runId.substring(2, 4)}:" +
            "${runId.substring(4, 6)}:${runId.substring(6, 8)}"
        val circleAddress = "$prefix:01"
        val squareAddress = "$prefix:02"
        val historicalAddress = "$prefix:03"
        val safeAddress = "$prefix:04"
        val addresses = listOf(circleAddress, squareAddress, historicalAddress, safeAddress)

        val circle = ExclusionZone.Circle(10.0, 20.0, 200.0)
        val square = ExclusionZone.Square(30.0, 40.0, 200.0)
        val zones = listOf(circle, square)
        val strongest: suspend (String) -> StrongestRssiLocation? = { address ->
            when (address) {
                circleAddress -> StrongestRssiLocation(10.0, 20.0, -30, 1_000)
                squareAddress -> StrongestRssiLocation(30.0, 40.0, -35, 2_000)
                historicalAddress -> StrongestRssiLocation(45.0, -70.0, -20, 3_000)
                safeAddress -> StrongestRssiLocation(51.5, -0.12, -25, 4_000)
                else -> null
            }
        }
        val allCoordinates: suspend (String) -> List<Pair<Double, Double>> = { address ->
            when (address) {
                circleAddress -> listOf(10.0 to 20.0)
                squareAddress -> listOf(30.0 to 40.0)
                historicalAddress -> listOf(45.0 to -70.0, 10.0001 to 20.0001)
                safeAddress -> listOf(51.5 to -0.12)
                else -> emptyList()
            }
        }

        val root = File(context.cacheDir, "gps_live_qa_$runId").also { it.mkdirs() }
        try {
            val source = File(root, "source.jsonl").apply {
                writeText(
                    addresses.joinToString("\n", postfix = "\n") { address ->
                        """{"bdaddr":"$address","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}"""
                    },
                )
            }
            val exported = KoinJavaComponent.getKoin().get<BTIDESRepository>()
                .exportUploadChunks(
                    outputDir = File(root, "payload"),
                    strongestRssiLookup = strongest,
                    sourceFile = source,
                    exclusionZones = zones,
                    exclusionCoordsLookup = allCoordinates,
                )
            assertEquals(1, exported.size)
            assertEquals(1, exported.single().deviceCount)
            val payload = exported.single().file
            val payloadAddresses = json.parseToJsonElement(payload.readText()).jsonArray.map {
                it.jsonObject["bdaddr"]!!.jsonPrimitive.content
            }
            assertEquals(listOf(safeAddress), payloadAddresses)
            assertFalse(payload.readText().contains(circleAddress))
            assertFalse(payload.readText().contains(squareAddress))
            assertFalse(payload.readText().contains(historicalAddress))

            val digest = sha256(payload)
            val outboxId = "gps-live-$runId"
            val uploader = BtidalpoolResumableUploader(
                client,
                auth,
                BtidalpoolResumableStateStore(File(root, "state"), testOnly = true),
            )
            val result = uploader.upload(
                row = BtidalpoolUploadEntity(
                    id = outboxId,
                    batchId = outboxId,
                    sourceLogName = source.name,
                    sourceSha256 = sha256(source),
                    chunkIndex = 0,
                    chunkCount = 1,
                    chunkSha256 = digest,
                    destination = BtidalpoolUploadEntity.Destination.TEST,
                    accountKey = "gps-live-qa",
                    payloadPath = payload.absolutePath,
                    payloadBytes = payload.length(),
                    deviceCount = 1,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                ),
                payload = payload,
                useTestDb = true,
                onProgress = { _, _ -> },
                onBusyRetry = {},
            )
            assertTrue(
                "live resumable upload failed: $result",
                result is BtidalpoolClient.UploadResult.Success ||
                    result is BtidalpoolClient.UploadResult.AlreadyPresent,
            )
            val receipt = BtidalpoolResumableStateStore(
                File(root, "state"),
                testOnly = true,
            ).load(outboxId)?.receipt ?: throw AssertionFailedError("No live upload receipt")
            assertEquals(digest, receipt.contentSha256)
            assertTrue(receipt.useTestDb)

            val sessionToken = createSession(client, auth)
            val query = client.v4NativeQuery(
                sessionToken,
                BtidalpoolCodec.QueryParams(bdaddrRegex = listOf("^$prefix:")),
                useTestDb = true,
            ) as? BtidalpoolClient.V4Result.NativeQuery
                ?: throw AssertionFailedError("Could not query live test DB")
            assertEquals(
                listOf(safeAddress.lowercase()),
                query.query.devices.map { it.bdaddr.lowercase() },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun createSession(
        client: BtidalpoolClient,
        auth: BtidalpoolAuthRepository,
    ): String {
        var state = checkNotNull(auth.current())
        var session = client.createV4Session(state.token)
        if (session is BtidalpoolClient.V4Result.Error && session.httpCode == 401) {
            state = when (val refreshed = auth.refresh()) {
                is BtidalpoolAuthRepository.RefreshOutcome.Success -> refreshed.state
                else -> throw AssertionFailedError("Could not refresh live QA credentials: $refreshed")
            }
            session = client.createV4Session(state.token)
        }
        return (session as? BtidalpoolClient.V4Result.Session)?.token
            ?: throw AssertionFailedError("Could not create live QA session: $session")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
