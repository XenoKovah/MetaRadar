package com.darkmentor.data.helpers

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
}
