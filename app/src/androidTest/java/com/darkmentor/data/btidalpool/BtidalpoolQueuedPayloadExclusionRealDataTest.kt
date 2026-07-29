package com.darkmentor.data.btidalpool

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.File

/**
 * Read-only QA over the exact durable payload files that WorkManager can resume and send.
 *
 * This closes the time-of-check gap between export and a later retry: every currently queued
 * `.btides` file is checked against the user's current exclusion zones and current location
 * history. Nothing is modified or uploaded.
 */
@RunWith(AndroidJUnit4::class)
class BtidalpoolQueuedPayloadExclusionRealDataTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun send_time_guard_blocks_real_device_with_in_zone_history() = runBlocking {
        val settings = KoinJavaComponent.getKoin().get<SettingsRepository>()
        val locations = KoinJavaComponent.getKoin().get<LocationRepository>()
        val validator =
            KoinJavaComponent.getKoin().get<BtidalpoolUploadPrivacyValidator>()
        val zones = settings.getExclusionZones()
        assumeTrue("No GPS exclusion zones are currently configured.", zones.isNotEmpty())
        val excludedAddress = locations.getAllRssiLocationsByAddress()
            .entries
            .firstOrNull { (_, rows) ->
                rows.any { row -> zones.any { it.contains(row.lat, row.lng) } }
            }
            ?.key
        assumeTrue(
            "No real device has location history inside the configured zones.",
            excludedAddress != null,
        )

        val payload = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "queued_privacy_guard_${System.nanoTime()}.btides",
        )
        try {
            // Deliberately omit GPSArray: the full Room history must still block this address.
            payload.writeText("""[{"bdaddr":"$excludedAddress","bdaddr_rand":1}]""")
            val result = validator.validate(
                payload,
                validator.currentPolicyFingerprint(),
            )

            assertTrue(
                "send-time guard did not block real in-zone history: $result",
                result is BtidalpoolUploadPrivacyValidator.Validation.Blocked,
            )
        } finally {
            payload.delete()
        }
    }

    @Test
    fun queued_payloads_contain_no_currently_excluded_data() = runBlocking {
        val outbox = KoinJavaComponent.getKoin().get<BtidalpoolOutboxRepository>()
        val settings = KoinJavaComponent.getKoin().get<SettingsRepository>()
        val locations = KoinJavaComponent.getKoin().get<LocationRepository>()
        val zones = settings.getExclusionZones()
        assumeTrue("No GPS exclusion zones are currently configured.", zones.isNotEmpty())

        val payloads = outbox.rootDir.walkTopDown()
            .filter { it.isFile && it.extension == "btides" }
            .toList()
        assumeTrue("No durable BTIDALPOOL payloads are currently queued.", payloads.isNotEmpty())

        val locationHistory = locations.getAllRssiLocationsByAddress()
        var devices = 0
        val addressesWithInZoneHistory = mutableListOf<String>()
        val coordinatesInsideZones = mutableListOf<Pair<Double, Double>>()

        payloads.forEach { payload ->
            json.parseToJsonElement(payload.readText()).jsonArray.forEach deviceLoop@{ element ->
                val device = element.jsonObject
                val address = device["bdaddr"]?.jsonPrimitive?.content ?: return@deviceLoop
                devices++

                val history = locationHistory[address.uppercase()].orEmpty()
                if (history.any { row -> zones.any { it.contains(row.lat, row.lng) } }) {
                    addressesWithInZoneHistory += address
                }
                device["GPSArray"]?.jsonArray.orEmpty().forEach gpsLoop@{ gps ->
                    val objectValue = gps.jsonObject
                    val lat = objectValue["lat"]?.jsonPrimitive?.double ?: return@gpsLoop
                    val lng = objectValue["lon"]?.jsonPrimitive?.double ?: return@gpsLoop
                    if (zones.any { it.contains(lat, lng) }) {
                        coordinatesInsideZones += lat to lng
                    }
                }
            }
        }

        println(
            "QUEUED_GPS_QA payloads=${payloads.size} devices=$devices " +
                "history_leaks=${addressesWithInZoneHistory.size} " +
                "coordinate_leaks=${coordinatesInsideZones.size}",
        )
        assertEquals(
            "queued device(s) now fall inside an exclusion zone: " +
                addressesWithInZoneHistory.distinct().take(5),
            0,
            addressesWithInZoneHistory.size,
        )
        assertEquals(
            "queued GPS coordinate(s) fall inside an exclusion zone: " +
                coordinatesInsideZones.take(3),
            0,
            coordinatesInsideZones.size,
        )
    }
}
