package com.darkmentor.domain.interactor

import android.content.Context
import com.darkmentor.data.btides.BTIDESRepository
import com.darkmentor.data.database.dao.RssiLocationRow
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Deterministic QA at the exact BTIDALPOOL payload boundary:
 *
 * [UploadToBtidalpoolInteractor] sends only the files returned by
 * [ExportBTIDESInteractor.executeUploadChunks]. This test therefore starts with saved exclusion
 * settings and bulk location rows, runs that production method, and inspects every generated
 * chunk. It also covers the privacy-sensitive case where a device's strongest coordinate is
 * outside the zone but an earlier/weaker observation was inside it.
 */
class ExportBTIDESInteractorGpsExclusionUploadTest {

    @Test
    fun `saved GPS zones remove in-zone devices from exact v4 upload chunks`() = runBlocking {
        val root = Files.createTempDirectory("btidalpool_gps_exclusion").toFile()
        try {
            val context = mockk<Context>().also {
                every { it.cacheDir } returns root
                every { it.filesDir } returns root
            }
            val source = File(root, "source.jsonl").apply {
                writeText(
                    listOf(
                        """{"bdaddr":"AA:AA:AA:AA:AA:AA","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""",
                        """{"bdaddr":"BB:BB:BB:BB:BB:BB","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""",
                        """{"bdaddr":"CC:CC:CC:CC:CC:CC","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""",
                    ).joinToString("\n", postfix = "\n"),
                )
            }
            val zone = ExclusionZone.Square(
                centerLat = 10.0,
                centerLng = 20.0,
                halfSizeMeters = 300.0,
            )
            val settings = mockk<SettingsRepository>()
            every { settings.getExclusionZones() } returns listOf(zone)

            val locations = mockk<LocationRepository>()
            coEvery { locations.getAllRssiLocationsByAddress() } returns mapOf(
                // Strongest coordinate is inside: exclude.
                "AA:AA:AA:AA:AA:AA" to listOf(row(1_000, 10.0, 20.0, -35)),
                // Every coordinate is outside: retain.
                "BB:BB:BB:BB:BB:BB" to listOf(row(2_000, 51.5, -0.12, -45)),
                // Strongest is outside, but a weaker historical fix is inside: exclude.
                "CC:CC:CC:CC:CC:CC" to listOf(
                    row(3_000, 30.0, 40.0, -30),
                    row(2_500, 10.0001, 20.0001, -90),
                ),
            )

            val chunks = ExportBTIDESInteractor(
                btidesRepository = BTIDESRepository(context),
                locationRepository = locations,
                settingsRepository = settings,
            ).executeUploadChunks(
                logFile = source,
                outputDir = File(root, "upload"),
            )

            val devices = chunks.flatMap { chunk ->
                assertEquals(sha256(chunk.file), chunk.sha256)
                Json.parseToJsonElement(chunk.file.readText()).jsonArray.map { element ->
                    element.jsonObject["bdaddr"]!!.jsonPrimitive.content
                }
            }

            assertEquals("only one safe device may enter the uploader", 1, chunks.sumOf { it.deviceCount })
            assertEquals(listOf("BB:BB:BB:BB:BB:BB"), devices)
            assertFalse(devices.contains("AA:AA:AA:AA:AA:AA"))
            assertFalse(devices.contains("CC:CC:CC:CC:CC:CC"))
            assertTrue(chunks.all { it.file.extension == "btides" })
            coVerify(exactly = 1) { locations.getAllRssiLocationsByAddress() }
            coVerify(exactly = 0) { locations.getAllStrongestRssiLocations() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an entirely excluded log produces no upload payload`() = runBlocking {
        val root = Files.createTempDirectory("btidalpool_gps_all_excluded").toFile()
        try {
            val context = mockk<Context>().also {
                every { it.cacheDir } returns root
                every { it.filesDir } returns root
            }
            val address = "AA:AA:AA:AA:AA:AA"
            val source = File(root, "source.jsonl").apply {
                writeText(
                    """{"bdaddr":"$address","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" +
                        "\n",
                )
            }
            val settings = mockk<SettingsRepository>()
            every { settings.getExclusionZones() } returns listOf(
                ExclusionZone.Square(10.0, 20.0, 300.0),
            )
            val locations = mockk<LocationRepository>()
            coEvery { locations.getAllRssiLocationsByAddress() } returns mapOf(
                address to listOf(row(1_000, 10.0, 20.0, -35)),
            )
            val output = File(root, "upload")

            val chunks = ExportBTIDESInteractor(
                btidesRepository = BTIDESRepository(context),
                locationRepository = locations,
                settingsRepository = settings,
            ).executeUploadChunks(source, output)

            assertTrue("no request body should be queued when every device is excluded", chunks.isEmpty())
            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `location-history failure aborts upload preparation instead of failing open`() =
        runBlocking {
            val root = Files.createTempDirectory("btidalpool_gps_lookup_failure").toFile()
            try {
                val context = mockk<Context>().also {
                    every { it.cacheDir } returns root
                    every { it.filesDir } returns root
                }
                val source = File(root, "source.jsonl").apply {
                    writeText(
                        """{"bdaddr":"AA:AA:AA:AA:AA:AA","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" +
                            "\n",
                    )
                }
                val settings = mockk<SettingsRepository>()
                every { settings.getExclusionZones() } returns listOf(
                    ExclusionZone.Square(10.0, 20.0, 300.0),
                )
                val locations = mockk<LocationRepository>()
                coEvery { locations.getAllRssiLocationsByAddress() } throws
                    IllegalStateException("database unavailable")
                val output = File(root, "upload")

                val failure = runCatching {
                    ExportBTIDESInteractor(
                        btidesRepository = BTIDESRepository(context),
                        locationRepository = locations,
                        settingsRepository = settings,
                    ).executeUploadChunks(source, output)
                }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertTrue("no payload may exist after privacy lookup failure", output.listFiles().orEmpty().isEmpty())
            } finally {
                root.deleteRecursively()
            }
        }

    private fun row(time: Long, lat: Double, lng: Double, rssi: Int) =
        RssiLocationRow(time = time, lat = lat, lng = lng, rssi = rssi)

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
