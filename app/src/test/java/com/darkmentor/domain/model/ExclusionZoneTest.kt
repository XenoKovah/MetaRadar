package com.darkmentor.domain.model

import android.location.Location
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for [ExclusionZone.contains]. The Circle uses Android's [Location.distanceBetween]
 * (geodetic), mocked here with an equirectangular approximation that's exact enough for the
 * ~100 m assertions. The Square is pure cos-corrected box math — no Android API.
 */
class ExclusionZoneTest {

    @Before
    fun setUp() {
        mockkStatic(Location::class)
        val out = slot<FloatArray>()
        every { Location.distanceBetween(any(), any(), any(), any(), capture(out)) } answers {
            val lat1 = arg<Double>(0)
            val lon1 = arg<Double>(1)
            val lat2 = arg<Double>(2)
            val lon2 = arg<Double>(3)
            val mPerDegLat = 111_320.0
            val dy = (lat2 - lat1) * mPerDegLat
            val dx = (lon2 - lon1) * mPerDegLat * Math.cos(Math.toRadians(lat1))
            out.captured[0] = Math.hypot(dx, dy).toFloat()
        }
    }

    @Test
    fun `circle contains point inside radius and excludes point outside`() {
        val zone = ExclusionZone.Circle(centerLat = 40.0, centerLng = -75.0, radiusMeters = 100.0)
        assertTrue(zone.contains(40.0005, -75.0)) // ~55 m north → inside
        assertFalse(zone.contains(40.01, -75.0))  // ~1.1 km north → outside
    }

    @Test
    fun `square contains point inside half-size and excludes point outside`() {
        val zone = ExclusionZone.Square(centerLat = 0.0, centerLng = 0.0, halfSizeMeters = 100.0)
        assertTrue(zone.contains(0.0005, 0.0005)) // ~55 m → inside
        assertFalse(zone.contains(0.0, 0.01))     // ~1.1 km east → outside
    }

    @Test
    fun `square longitude extent widens with latitude via cos correction`() {
        // At lat 60, cos≈0.5 so a degree of longitude is ~half as wide → the lng half-extent in
        // degrees is ~2x the equator case. A point at 0.9x that bound is inside (cos-corrected).
        val zone = ExclusionZone.Square(centerLat = 60.0, centerLng = 0.0, halfSizeMeters = 100.0)
        val dLngDegAt60 = 100.0 / (111_320.0 * Math.cos(Math.toRadians(60.0)))
        assertTrue(zone.contains(60.0, dLngDegAt60 * 0.9))
    }
}
