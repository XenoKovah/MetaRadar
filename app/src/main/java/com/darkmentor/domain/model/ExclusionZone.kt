package com.darkmentor.domain.model

import android.location.Location
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos

/**
 * A user-defined GPS region used to keep detections collected in sensitive places (home, work, a
 * friend's house) out of the public BTIDALPOOL upload. Up to [MAX_ZONES] are stored (see
 * [com.darkmentor.data.repo.SettingsRepository]).
 *
 * During a BTIDALPOOL upload, any device whose strongest-RSSI GPS sample — the only coordinate the
 * exporter emits per device (one `GPSArray` entry, see [com.darkmentor.data.btides.BTIDESRepository])
 * — falls inside ANY zone is omitted from the export ENTIRELY (whole-device drop, for privacy).
 * Manual on-device exports are unaffected; exclusion is upload-only.
 *
 * Serialised polymorphically exactly like [DeviceFilter]: each subclass carries a @SerialName
 * discriminator, so kotlinx.serialization round-trips a `List<ExclusionZone>` as a JSON array of
 * tagged objects with no custom serializer.
 *
 * Scope note: containment is evaluated against the single strongest-RSSI coordinate the exporter
 * emits, not the device's full location history.
 */
@Serializable
sealed class ExclusionZone {

    abstract val centerLat: Double
    abstract val centerLng: Double

    /** True if the point (lat, lng) lies inside this zone. */
    abstract fun contains(lat: Double, lng: Double): Boolean

    @Serializable
    @SerialName("circle")
    data class Circle(
        override val centerLat: Double,
        override val centerLng: Double,
        val radiusMeters: Double,
    ) : ExclusionZone() {
        override fun contains(lat: Double, lng: Double): Boolean {
            val out = FloatArray(1)
            // Geodetic distance in meters — same Android API as LocationModel.distanceTo.
            Location.distanceBetween(centerLat, centerLng, lat, lng, out)
            return out[0] <= radiusMeters
        }
    }

    /**
     * Axis-aligned square centered on (centerLat, centerLng) with side length `2 * halfSizeMeters`.
     * Containment uses the SAME meters→degrees conversion the map-draw code uses so the drawn box
     * and the hit-test agree:
     *  - latitude:  1 deg ≈ [METERS_PER_DEG_LAT] meters (constant).
     *  - longitude: 1 deg ≈ [METERS_PER_DEG_LAT] * cos(centerLat) meters (shrinks toward the poles).
     */
    @Serializable
    @SerialName("square")
    data class Square(
        override val centerLat: Double,
        override val centerLng: Double,
        val halfSizeMeters: Double,
    ) : ExclusionZone() {
        override fun contains(lat: Double, lng: Double): Boolean {
            val dLat = halfSizeMeters / METERS_PER_DEG_LAT
            val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(MIN_COS_LAT)
            val dLng = halfSizeMeters / (METERS_PER_DEG_LAT * cosLat)
            return abs(lat - centerLat) <= dLat && abs(lng - centerLng) <= dLng
        }
    }

    companion object {
        const val MAX_ZONES = 3

        /** Mean meters per degree of latitude. Shared with the map overlay so draw == hit-test. */
        const val METERS_PER_DEG_LAT = 111_320.0

        /** Floor on cos(lat) so a zone defined at/near a pole can't divide by ~0. */
        private const val MIN_COS_LAT = 1e-6
    }
}
