package com.darkmentor.data.btides

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.domain.model.ExclusionZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * On-device, end-to-end check that the BTIDES file generated for a BTIDALPOOL upload EXCLUDES
 * detections that fall inside the user's GPS exclusion zones — run against the device's REAL
 * captured data and the REAL export pipeline, and WITHOUT uploading anything.
 *
 * Strategy (two circles + one square):
 *  1. Pull the real repositories out of the running app's Koin graph (real Room DB + real logs).
 *  2. Baseline-export a real log with NO zones to discover which devices actually have a
 *     strongest-RSSI GPS sample (these are the only ones a zone could ever exclude).
 *  3. Wrap two Circle zones and one Square zone (60 m) around three well-separated such devices,
 *     and pick a fourth device that lies outside all three zones as the control.
 *  4. Re-export the SAME log WITH those zones — this is byte-for-byte the file the upload path
 *     (ExportBTIDESInteractor.executeForLog -> BTIDESRepository.exportTo) would produce.
 *  5. Assert the three in-zone devices are gone and the control survives.
 *
 * Safety: zones are passed straight into exportTo, never persisted — the user's saved zones are
 * untouched. Nothing is uploaded. Production data is read only. The test self-skips (assumeTrue)
 * if the device hasn't captured enough GPS-tagged devices to make the assertion meaningful.
 */
@RunWith(AndroidJUnit4::class)
class ExclusionZoneRealDataExportTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class GpsDev(val bdaddr: String, val lat: Double, val lon: Double)

    private fun parseGpsDevices(exportJson: String): List<GpsDev> =
        json.parseToJsonElement(exportJson).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val bdaddr = obj["bdaddr"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val gps = obj["GPSArray"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val lat = gps["lat"]?.jsonPrimitive?.double ?: return@mapNotNull null
            val lon = gps["lon"]?.jsonPrimitive?.double ?: return@mapNotNull null
            GpsDev(bdaddr, lat, lon)
        }.distinctBy { it.bdaddr }

    /** True when [q] is more than [meters] away from [p] (geodetic, via Location.distanceBetween). */
    private fun far(p: GpsDev, q: GpsDev, meters: Double): Boolean =
        !ExclusionZone.Circle(p.lat, p.lon, meters).contains(q.lat, q.lon)

    @Test
    fun upload_btides_file_excludes_devices_inside_two_circles_and_a_square() = runBlocking {
        val btides = KoinJavaComponent.getKoin().get<BTIDESRepository>()
        val locationRepo = KoinJavaComponent.getKoin().get<LocationRepository>()
        val lookup: suspend (String) -> StrongestRssiLocation? = { addr ->
            locationRepo.getStrongestRssiLocation(addr)?.let {
                StrongestRssiLocation(lat = it.lat, lng = it.lng, rssi = it.rssi, timeMs = it.time)
            }
        }
        // ALL recorded coordinates per device — exclusion now drops a device if ANY of these (not
        // just the strongest) is inside a zone.
        val coordsLookup: suspend (String) -> List<Pair<Double, Double>> = { addr ->
            locationRepo.getAllLocationsByAddress(addr).map { it.lat to it.lng }
        }

        // Find a real log with >= 4 GPS-tagged devices.
        var sourceLog: File? = null
        var gpsDevices: List<GpsDev> = emptyList()
        for (log in btides.listLogs()) {
            val baseline = ByteArrayOutputStream()
            btides.exportTo(baseline, lookup, null, sourceFile = log.file, exclusionZones = emptyList())
            val devs = parseGpsDevices(baseline.toString(Charsets.UTF_8.name()))
            if (devs.size >= 4) {
                sourceLog = log.file
                gpsDevices = devs
                break
            }
        }
        assumeTrue(
            "Need a BTIDES log with >= 4 GPS-tagged devices for this real-data check; capture some first.",
            sourceLog != null,
        )

        // Two circles + one square (60 m) around three well-separated devices.
        val radius = 60.0
        val a = gpsDevices[0]
        val b = gpsDevices.firstOrNull { far(a, it, 250.0) } ?: gpsDevices[1]
        val c = gpsDevices.firstOrNull { far(a, it, 250.0) && far(b, it, 250.0) } ?: gpsDevices[2]
        val zones = listOf(
            ExclusionZone.Circle(a.lat, a.lon, radius),
            ExclusionZone.Circle(b.lat, b.lon, radius),
            ExclusionZone.Square(c.lat, c.lon, radius),
        )
        // Control: a device EVERY recorded coordinate of which is outside all zones, so the
        // any-coordinate rule must keep it.
        var control: GpsDev? = null
        for (d in gpsDevices) {
            if (coordsLookup(d.bdaddr).none { (la, ln) -> zones.any { it.contains(la, ln) } }) {
                control = d; break
            }
        }
        assumeTrue("Need a GPS device with no coordinate in any zone as a control.", control != null)

        // Re-export the SAME log through the exact chunking method consumed by the v4 uploader.
        val qaRoot = File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.cacheDir,
            "btidalpool_gps_shape_qa_${System.nanoTime()}",
        ).also { it.mkdirs() }
        val out = try {
            val chunks = btides.exportUploadChunks(
                outputDir = qaRoot,
                strongestRssiLookup = lookup,
                sourceFile = sourceLog!!,
                exclusionZones = zones,
                exclusionCoordsLookup = coordsLookup,
            )
            JsonArray(
                chunks.flatMap { chunk ->
                    json.parseToJsonElement(chunk.file.readText()).jsonArray
                },
            ).toString()
        } finally {
            qaRoot.deleteRecursively()
        }

        assertFalse("circle-zone device A (${a.bdaddr}) must be excluded from the upload file", out.contains(a.bdaddr))
        assertFalse("circle-zone device B (${b.bdaddr}) must be excluded from the upload file", out.contains(b.bdaddr))
        assertFalse("square-zone device C (${c.bdaddr}) must be excluded from the upload file", out.contains(c.bdaddr))
        assertTrue("out-of-zone control (${control!!.bdaddr}) must remain in the upload file", out.contains(control.bdaddr))
    }
}
