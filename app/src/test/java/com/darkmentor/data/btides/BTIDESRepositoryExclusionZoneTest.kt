package com.darkmentor.data.btides

import android.content.Context
import com.darkmentor.domain.model.ExclusionZone
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Exercises the real [BTIDESRepository.exportTo] GPS upload-exclusion loop. A device is dropped
 * from the export (whole record) if ANY coordinate it was ever seen at — not just its
 * strongest/uploaded sample — falls inside an exclusion zone. Uses a Square zone so containment is
 * pure math (no Android Location static needed).
 */
class BTIDESRepositoryExclusionZoneTest {

    private fun context(tempDir: File): Context = mockk<Context>().also {
        every { it.cacheDir } returns tempDir
        every { it.filesDir } returns tempDir
    }

    // Strongest (the single uploaded) sample per device:
    //   AA — inside the zone at (10,20)
    //   BB — far away in London (outside)
    //   CC — at (30,40), OUTSIDE the zone
    private val strongest: suspend (String) -> StrongestRssiLocation? = { addr ->
        when (addr) {
            "AA:AA:AA:AA:AA:AA" -> StrongestRssiLocation(lat = 10.0, lng = 20.0, rssi = -40, timeMs = 1_000L)
            "BB:BB:BB:BB:BB:BB" -> StrongestRssiLocation(lat = 51.5, lng = -0.12, rssi = -50, timeMs = 2_000L)
            "CC:CC:CC:CC:CC:CC" -> StrongestRssiLocation(lat = 30.0, lng = 40.0, rssi = -45, timeMs = 3_000L)
            else -> null
        }
    }

    // EVERY recorded coordinate per device. CC's strongest is OUTSIDE the zone, but it was also
    // seen once at (10.0001, 20.0001) — ~16 m from the zone center, i.e. INSIDE the zone. That weak
    // fix must still get CC excluded.
    private val allCoords: suspend (String) -> List<Pair<Double, Double>> = { addr ->
        when (addr) {
            "AA:AA:AA:AA:AA:AA" -> listOf(10.0 to 20.0)
            "BB:BB:BB:BB:BB:BB" -> listOf(51.5 to -0.12)
            "CC:CC:CC:CC:CC:CC" -> listOf(30.0 to 40.0, 10.0001 to 20.0001)
            else -> emptyList()
        }
    }

    private fun threeDeviceLog(dir: File): File = File(dir, "src.jsonl").apply {
        writeText(
            """{"bdaddr":"AA:AA:AA:AA:AA:AA","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" + "\n" +
                """{"bdaddr":"BB:BB:BB:BB:BB:BB","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" + "\n" +
                """{"bdaddr":"CC:CC:CC:CC:CC:CC","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" + "\n",
        )
    }

    @Test
    fun `a device with ANY coordinate inside a zone is omitted, even if its strongest is outside`() = runBlocking {
        val dir = Files.createTempDirectory("btides_excl").toFile()
        try {
            val repo = BTIDESRepository(context(dir))
            val log = threeDeviceLog(dir)
            val zone = ExclusionZone.Square(centerLat = 10.0, centerLng = 20.0, halfSizeMeters = 300.0)
            val sink = ByteArrayOutputStream()
            val count = repo.exportTo(
                sink, strongest, null,
                sourceFile = log,
                exclusionZones = listOf(zone),
                exclusionCoordsLookup = allCoords,
            )
            val out = sink.toString(Charsets.UTF_8.name())

            assertFalse("AA (strongest in zone) must be excluded", out.contains("AA:AA:AA:AA:AA:AA"))
            assertFalse(
                "CC (strongest OUTSIDE, but a weaker fix INSIDE) must be excluded — any coordinate counts",
                out.contains("CC:CC:CC:CC:CC:CC"),
            )
            assertTrue("BB (no coordinate in any zone) must be kept", out.contains("BB:BB:BB:BB:BB:BB"))
            assertEquals("only the device with no in-zone coordinate survives", 1, count)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty exclusion list keeps every device`() = runBlocking {
        val dir = Files.createTempDirectory("btides_excl_empty").toFile()
        try {
            val repo = BTIDESRepository(context(dir))
            val log = threeDeviceLog(dir)
            val sink = ByteArrayOutputStream()
            val count = repo.exportTo(sink, strongest, null, sourceFile = log, exclusionZones = emptyList())
            val out = sink.toString(Charsets.UTF_8.name())

            assertEquals(3, count)
            assertTrue(out.contains("AA:AA:AA:AA:AA:AA"))
            assertTrue(out.contains("BB:BB:BB:BB:BB:BB"))
            assertTrue(out.contains("CC:CC:CC:CC:CC:CC"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
