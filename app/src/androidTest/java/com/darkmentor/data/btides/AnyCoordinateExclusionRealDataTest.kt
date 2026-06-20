package com.darkmentor.data.btides

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.ByteArrayOutputStream

/**
 * On-device, end-to-end confirmation that the BTIDALPOOL upload export drops a device if ANY
 * coordinate it was ever seen at falls inside the user's CURRENT exclusion zones — not just its
 * strongest/trilaterated sample. Runs against the real Koin repositories (the user's saved zones,
 * the real Room DB, the real logs) and the real export path; reads only, uploads nothing.
 *
 * For each log it: (1) enumerates devices, (2) computes which have ANY recorded coordinate in a
 * zone (and how many of those have their *strongest* outside every zone — i.e. would have leaked
 * under the old strongest-only rule), (3) runs the real upload-bound export, and asserts that none
 * of those devices, and no uploaded coordinate, remain inside a zone. Self-skips if no zones are
 * set or nothing was captured inside one.
 */
@RunWith(AndroidJUnit4::class)
class AnyCoordinateExclusionRealDataTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun bdaddrsIn(exportJson: String): List<String> =
        json.parseToJsonElement(exportJson).jsonArray.mapNotNull {
            it.jsonObject["bdaddr"]?.jsonPrimitive?.contentOrNull
        }

    private fun uploadedCoordsIn(exportJson: String): List<Pair<Double, Double>> =
        json.parseToJsonElement(exportJson).jsonArray.flatMap { el ->
            (el.jsonObject["GPSArray"]?.jsonArray ?: return@flatMap emptyList()).mapNotNull { g ->
                val o = g.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.double ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.double ?: return@mapNotNull null
                lat to lon
            }
        }

    @Test
    fun no_device_with_an_in_zone_coordinate_reaches_the_upload() = runBlocking {
        val btides = KoinJavaComponent.getKoin().get<BTIDESRepository>()
        val locationRepo = KoinJavaComponent.getKoin().get<LocationRepository>()
        val settings = KoinJavaComponent.getKoin().get<SettingsRepository>()

        val zones: List<ExclusionZone> = settings.getExclusionZones()
        assumeTrue("No exclusion zones set in the app; set some and walk through one first.", zones.isNotEmpty())
        println("ANYCOORD zones=${zones.size}")

        val strongest: suspend (String) -> StrongestRssiLocation? = { addr ->
            locationRepo.getStrongestRssiLocation(addr)?.let {
                StrongestRssiLocation(lat = it.lat, lng = it.lng, rssi = it.rssi, timeMs = it.time)
            }
        }
        val coordsLookup: suspend (String) -> List<Pair<Double, Double>> = { addr ->
            locationRepo.getAllLocationsByAddress(addr).map { it.lat to it.lng }
        }
        fun inAnyZone(lat: Double, lng: Double) = zones.any { it.contains(lat, lng) }

        var totDevices = 0; var totShouldExclude = 0; var totLeaked = 0
        var totWeakOnly = 0; var totUploadedInZone = 0; var anyChecked = false

        for (log in btides.listLogs()) {
            // Enumerate this log's devices via an unfiltered export.
            val baseline = ByteArrayOutputStream()
            btides.exportTo(baseline, strongest, null, sourceFile = log.file)
            val devices = bdaddrsIn(baseline.toString(Charsets.UTF_8.name()))
            if (devices.isEmpty()) continue

            // Which devices have ANY coordinate in a zone — and of those, how many have their
            // strongest OUTSIDE every zone (the ones the old strongest-only rule would have leaked)?
            val shouldExclude = ArrayList<String>()
            var weakOnly = 0
            for (addr in devices) {
                val coords = coordsLookup(addr)
                if (coords.any { (la, ln) -> inAnyZone(la, ln) }) {
                    shouldExclude.add(addr)
                    val s = strongest(addr)
                    if (s == null || !inAnyZone(s.lat, s.lng)) weakOnly++
                }
            }

            // The real upload-bound export (zones + all-coordinate exclusion).
            val filtered = ByteArrayOutputStream()
            btides.exportTo(
                filtered, strongest, null,
                sourceFile = log.file,
                exclusionZones = zones,
                exclusionCoordsLookup = coordsLookup,
            )
            val out = filtered.toString(Charsets.UTF_8.name())

            val leaked = shouldExclude.filter { out.contains(it) }
            val uploadedInZone = uploadedCoordsIn(out).filter { (la, ln) -> inAnyZone(la, ln) }
            println("ANYCOORD log=${log.file.name} devices=${devices.size} should_exclude=${shouldExclude.size} weak_only=$weakOnly leaked=${leaked.size} uploaded_in_zone=${uploadedInZone.size}")

            assertEquals("device(s) with an in-zone coordinate leaked into the upload: ${leaked.take(5)}", 0, leaked.size)
            assertEquals("uploaded coordinate(s) fall inside a zone: ${uploadedInZone.take(3)}", 0, uploadedInZone.size)

            totDevices += devices.size; totShouldExclude += shouldExclude.size; totLeaked += leaked.size
            totWeakOnly += weakOnly; totUploadedInZone += uploadedInZone.size
            if (shouldExclude.isNotEmpty()) anyChecked = true
        }
        println("ANYCOORD_SUMMARY devices=$totDevices should_exclude=$totShouldExclude weak_only=$totWeakOnly leaked=$totLeaked uploaded_in_zone=$totUploadedInZone")
        assumeTrue("No captured device had a coordinate inside any zone — walk through a zone to exercise this.", anyChecked)
        assertEquals("no in-zone-coordinate device may survive the upload", 0, totLeaked)
        assertEquals("no uploaded coordinate may fall in a zone", 0, totUploadedInZone)
    }
}
