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
 * Exercises the real [BTIDESRepository.exportTo] device loop: a device whose strongest-RSSI GPS
 * sample is inside an exclusion zone must be dropped from the export (whole record), and the
 * returned count must reflect what was actually written. Uses a Square zone so the containment
 * test is pure math (no Android Location static needed).
 */
class BTIDESRepositoryExclusionZoneTest {

    private fun context(tempDir: File): Context = mockk<Context>().also {
        every { it.cacheDir } returns tempDir
        every { it.filesDir } returns tempDir
    }

    // AA's strongest sample sits at (10, 20); BB is far away in London.
    private val lookup: suspend (String) -> StrongestRssiLocation? = { addr ->
        when (addr) {
            "AA:AA:AA:AA:AA:AA" -> StrongestRssiLocation(lat = 10.0, lng = 20.0, rssi = -40, timeMs = 1_000L)
            "BB:BB:BB:BB:BB:BB" -> StrongestRssiLocation(lat = 51.5, lng = -0.12, rssi = -50, timeMs = 2_000L)
            else -> null
        }
    }

    private fun twoDeviceLog(dir: File): File = File(dir, "src.jsonl").apply {
        writeText(
            """{"bdaddr":"AA:AA:AA:AA:AA:AA","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" + "\n" +
                """{"bdaddr":"BB:BB:BB:BB:BB:BB","bdaddr_rand":1,"AdvChanArray":[{"type":0}]}""" + "\n",
        )
    }

    @Test
    fun `device whose strongest location is inside a zone is omitted, outside is kept`() = runBlocking {
        val dir = Files.createTempDirectory("btides_excl").toFile()
        try {
            val repo = BTIDESRepository(context(dir))
            val log = twoDeviceLog(dir)
            val zone = ExclusionZone.Square(centerLat = 10.0, centerLng = 20.0, halfSizeMeters = 300.0)
            val sink = ByteArrayOutputStream()
            val count = repo.exportTo(sink, lookup, null, sourceFile = log, exclusionZones = listOf(zone))
            val out = sink.toString(Charsets.UTF_8.name())

            assertEquals("only the out-of-zone device should be written", 1, count)
            assertFalse("in-zone device must be absent", out.contains("AA:AA:AA:AA:AA:AA"))
            assertTrue("out-of-zone device must be present", out.contains("BB:BB:BB:BB:BB:BB"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty exclusion list keeps both devices`() = runBlocking {
        val dir = Files.createTempDirectory("btides_excl_empty").toFile()
        try {
            val repo = BTIDESRepository(context(dir))
            val log = twoDeviceLog(dir)
            val sink = ByteArrayOutputStream()
            val count = repo.exportTo(sink, lookup, null, sourceFile = log, exclusionZones = emptyList())
            val out = sink.toString(Charsets.UTF_8.name())

            assertEquals(2, count)
            assertTrue(out.contains("AA:AA:AA:AA:AA:AA"))
            assertTrue(out.contains("BB:BB:BB:BB:BB:BB"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
