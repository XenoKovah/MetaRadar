package com.darkmentor.data.btidalpool

import com.darkmentor.data.database.dao.RssiLocationRow
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BtidalpoolUploadPrivacyValidatorTest {
    private val tempDir = Files.createTempDirectory("btidalpool_privacy_test").toFile()
    private val zone = ExclusionZone.Square(
        centerLat = 40.0,
        centerLng = -75.0,
        halfSizeMeters = 100.0,
    )

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `policy fingerprint is order independent and changes with zone geometry`() {
        val second = ExclusionZone.Circle(41.0, -76.0, 250.0)

        assertEquals(
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone, second)),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(second, zone)),
        )
        assertNotEquals(
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
            BtidalpoolGpsExclusionPolicy.fingerprint(
                listOf(zone.copy(halfSizeMeters = zone.halfSizeMeters + 1.0)),
            ),
        )
    }

    @Test
    fun `changed policy blocks a queued payload before location lookup`() = runBlocking {
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(payload("""[{"bdaddr":"AA:BB:CC:DD:EE:FF"}]"""), null)

        assertBlocked(result, "changed")
    }

    @Test
    fun `embedded coordinate inside a zone is blocked`() = runBlocking {
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(
            payload(
                """
                [{
                  "bdaddr":"AA:BB:CC:DD:EE:FF",
                  "GPSArray":[{"lat":40.0001,"lon":-75.0001}]
                }]
                """.trimIndent(),
            ),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
        )

        assertBlocked(result, "contains a GPS coordinate")
    }

    @Test
    fun `weaker in-zone history blocks device even when strongest sample is outside`() =
        runBlocking {
            val settings = mockk<SettingsRepository>()
            every { settings.getExclusionZones() } returns listOf(zone)
            val locations = mockk<LocationRepository>()
            coEvery { locations.getAllRssiLocationsByAddress() } returns mapOf(
                "AA:BB:CC:DD:EE:FF" to listOf(
                    RssiLocationRow(time = 1, lat = 40.02, lng = -75.02, rssi = -20),
                    RssiLocationRow(time = 2, lat = 40.0001, lng = -75.0001, rssi = -90),
                ),
            )
            val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

            val result = validator.validate(
                payload(
                    """
                    [{
                      "bdaddr":"aa:bb:cc:dd:ee:ff",
                      "GPSArray":[{"lat":40.02,"lon":-75.02}]
                    }]
                    """.trimIndent(),
                ),
                BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
            )

            assertBlocked(result, "location history")
        }

    @Test
    fun `location lookup failure blocks upload closed`() = runBlocking {
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        coEvery { locations.getAllRssiLocationsByAddress() } throws
            IllegalStateException("database unavailable")
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(
            payload("""[{"bdaddr":"AA:BB:CC:DD:EE:FF"}]"""),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
        )

        assertBlocked(result, "upload blocked")
    }

    @Test
    fun `malformed queued payload blocks upload closed`() = runBlocking {
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(
            payload("""{"not":"a BTIDES array"}"""),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
        )

        assertBlocked(result, "upload blocked")
    }

    @Test
    fun `policy change during validation blocks upload`() = runBlocking {
        val changedZone = zone.copy(halfSizeMeters = 200.0)
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returnsMany
            listOf(listOf(zone), listOf(changedZone))
        val locations = mockk<LocationRepository>()
        coEvery { locations.getAllRssiLocationsByAddress() } returns emptyMap()
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(
            payload("""[{"bdaddr":"AA:BB:CC:DD:EE:FF"}]"""),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
        )

        assertBlocked(result, "changed while validating")
    }

    @Test
    fun `payload outside zones with no in-zone history is safe`() = runBlocking {
        val settings = mockk<SettingsRepository>()
        every { settings.getExclusionZones() } returns listOf(zone)
        val locations = mockk<LocationRepository>()
        coEvery { locations.getAllRssiLocationsByAddress() } returns mapOf(
            "AA:BB:CC:DD:EE:FF" to listOf(
                RssiLocationRow(time = 1, lat = 40.02, lng = -75.02, rssi = -20),
            ),
        )
        val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

        val result = validator.validate(
            payload(
                """
                [{
                  "bdaddr":"AA:BB:CC:DD:EE:FF",
                  "GPSArray":[{"lat":40.02,"lon":-75.02}]
                }]
                """.trimIndent(),
            ),
            BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
        )

        assertEquals(BtidalpoolUploadPrivacyValidator.Validation.Safe, result)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never converted into a privacy result`() {
        runBlocking {
            val settings = mockk<SettingsRepository>()
            every { settings.getExclusionZones() } returns listOf(zone)
            val locations = mockk<LocationRepository>()
            coEvery { locations.getAllRssiLocationsByAddress() } throws
                CancellationException("app stopped")
            val validator = BtidalpoolUploadPrivacyValidator(settings, locations)

            validator.validate(
                payload("""[{"bdaddr":"AA:BB:CC:DD:EE:FF"}]"""),
                BtidalpoolGpsExclusionPolicy.fingerprint(listOf(zone)),
            )
        }
    }

    private fun payload(json: String): File =
        File(tempDir, "payload-${System.nanoTime()}.btides").apply { writeText(json) }

    private fun assertBlocked(
        result: BtidalpoolUploadPrivacyValidator.Validation,
        expectedReason: String,
    ) {
        assertTrue(result is BtidalpoolUploadPrivacyValidator.Validation.Blocked)
        result as BtidalpoolUploadPrivacyValidator.Validation.Blocked
        assertTrue(
            "Expected '${result.reason}' to contain '$expectedReason'",
            result.reason.contains(expectedReason, ignoreCase = true),
        )
    }
}
