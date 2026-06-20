package com.darkmentor.data.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the lat/lng comparison [LocationProvider] uses to decide whether a new fix
 * is a real move. A copy-paste bug once compared the new latitude against the old *longitude*,
 * which silently treated genuine moves (and equal-but-swapped coordinates) as "no change".
 */
class LocationProviderPositionTest {

    @Test
    fun `identical coordinates do not differ`() {
        assertFalse(locationPositionsDiffer(40.0, -75.0, 40.0, -75.0))
    }

    @Test
    fun `a latitude-only change is detected`() {
        // new(20,20) vs old(10,20): only latitude moved, longitudes match. The old buggy code
        // compared latitude to the old LONGITUDE (20 != 20 -> false) and missed this move.
        assertTrue(locationPositionsDiffer(20.0, 20.0, 10.0, 20.0))
    }

    @Test
    fun `a longitude-only change is detected`() {
        assertTrue(locationPositionsDiffer(10.0, 99.0, 10.0, 20.0))
    }

    @Test
    fun `swapped lat-lng counts as a move, not a match`() {
        // old(10,20) vs new(20,10): same numbers, different point — must be "different".
        assertTrue(locationPositionsDiffer(20.0, 10.0, 10.0, 20.0))
    }

    @Test
    fun `nearestWithinWindow returns the closest in-window index`() {
        // times 1000/2000/5000, target 2200 -> index 1 (2000) is closest, delta 200 <= 1000.
        assertEquals(1, nearestWithinWindow(listOf(1000L, 2000L, 5000L), 2200L, 1000L))
    }

    @Test
    fun `nearestWithinWindow returns -1 when the closest is outside the window`() {
        // target 9000, closest is 5000 (delta 4000) > window 1000 -> no usable fix.
        assertEquals(-1, nearestWithinWindow(listOf(1000L, 2000L, 5000L), 9000L, 1000L))
    }

    @Test
    fun `nearestWithinWindow returns -1 for an empty history`() {
        assertEquals(-1, nearestWithinWindow(emptyList(), 1234L, 120_000L))
    }

    @Test
    fun `nearestWithinWindow honors an exact match and picks the nearer side`() {
        assertEquals(2, nearestWithinWindow(listOf(0L, 100L, 200L), 200L, 60_000L)) // exact hit
        assertEquals(0, nearestWithinWindow(listOf(100L, 400L), 200L, 60_000L))     // 100 (d=100) < 400 (d=200)
    }
}
