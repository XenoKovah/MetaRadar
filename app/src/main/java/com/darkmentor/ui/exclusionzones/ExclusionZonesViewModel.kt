package com.darkmentor.ui.exclusionzones

import android.app.Application
import android.location.Geocoder
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.R
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import com.darkmentor.utils.navigation.BackCommand
import com.darkmentor.utils.navigation.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint

/**
 * Drives the "Create Upload Exclusion Zone" screen. Two modes: BROWSE (pan/zoom the map, search an
 * address, drop a new circle/square at the crosshair) and ADJUST (multi-touch disabled; drag to
 * resize the in-progress shape, then Save or Cancel). Up to [ExclusionZone.MAX_ZONES] zones, mirrored
 * in memory for instant overlay updates and persisted to [SettingsRepository] on every change.
 */
class ExclusionZonesViewModel(
    private val context: Application,
    private val settingsRepository: SettingsRepository,
    private val router: Router,
) : ViewModel() {

    enum class ShapeKind { CIRCLE, SQUARE }

    sealed interface Mode {
        data object Browse : Mode

        /** [center] is the geo-anchor (map crosshair for a new zone, the zone's own center on edit).
         *  [editIndex] null = creating a new zone; non-null = editing the existing zone at that index. */
        data class Adjust(
            val shape: ShapeKind,
            val sizeMeters: Double,
            val center: GeoPoint,
            val editIndex: Int?,
        ) : Mode
    }

    var zones: List<ExclusionZone> by mutableStateOf(settingsRepository.getExclusionZones())
        private set
    var mode: Mode by mutableStateOf(Mode.Browse)
        private set

    /** One-shot recenter request consumed by the screen (address search result / edit target). */
    var recenterTarget: GeoPoint? by mutableStateOf(null)
        private set
    var addressSearchInProgress: Boolean by mutableStateOf(false)
        private set

    val canAddZone: Boolean get() = zones.size < ExclusionZone.MAX_ZONES

    /** Tapped a Circle/Square button. Enter ADJUST to place a new zone centered on [center]
     *  (the map crosshair). At the cap, toast instead of entering adjust mode. */
    fun onAddShape(shape: ShapeKind, center: GeoPoint) {
        if (!canAddZone) {
            Toast.makeText(context, R.string.exclusion_zones_max_reached, Toast.LENGTH_LONG).show()
            return
        }
        mode = Mode.Adjust(shape, DEFAULT_ZONE_METERS, center, editIndex = null)
    }

    /** Re-enter ADJUST to resize an existing zone; recenters the map to it. */
    fun onEditZone(index: Int) {
        val zone = zones.getOrNull(index) ?: return
        val center = GeoPoint(zone.centerLat, zone.centerLng)
        val (shape, size) = when (zone) {
            is ExclusionZone.Circle -> ShapeKind.CIRCLE to zone.radiusMeters
            is ExclusionZone.Square -> ShapeKind.SQUARE to zone.halfSizeMeters
        }
        recenterTarget = center
        mode = Mode.Adjust(shape, size, center, editIndex = index)
    }

    fun onDeleteZone(index: Int) {
        val updated = zones.toMutableList().also { if (index in it.indices) it.removeAt(index) }
        zones = updated
        settingsRepository.setExclusionZones(updated)
    }

    fun onResizeDrag(distanceMeters: Double) {
        val adj = mode as? Mode.Adjust ?: return
        mode = adj.copy(sizeMeters = distanceMeters.coerceIn(MIN_ZONE_METERS, MAX_ZONE_METERS))
    }

    fun onSaveZone() {
        val adj = mode as? Mode.Adjust ?: return
        val zone: ExclusionZone = when (adj.shape) {
            ShapeKind.CIRCLE -> ExclusionZone.Circle(adj.center.latitude, adj.center.longitude, adj.sizeMeters)
            ShapeKind.SQUARE -> ExclusionZone.Square(adj.center.latitude, adj.center.longitude, adj.sizeMeters)
        }
        val updated = zones.toMutableList().apply {
            if (adj.editIndex != null && adj.editIndex in indices) set(adj.editIndex, zone) else add(zone)
        }.take(ExclusionZone.MAX_ZONES)
        zones = updated
        mode = Mode.Browse
        settingsRepository.setExclusionZones(updated)
    }

    /** "Cancel exclusion zone": discard the in-progress shape / revert the edit, back to BROWSE. */
    fun onCancelAdjust() {
        mode = Mode.Browse
    }

    fun onAddressSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            addressSearchInProgress = true
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context).getFromLocationName(query, 1)?.firstOrNull()
                }.getOrNull()
            }
            addressSearchInProgress = false
            if (target != null) {
                recenterTarget = GeoPoint(target.latitude, target.longitude)
            } else {
                // Covers no-match AND the offline/silent-mode / no-backend (F-Droid) IOException path.
                Toast.makeText(context, R.string.exclusion_zones_address_not_found, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onRecenterConsumed() {
        recenterTarget = null
    }

    fun onCloseClick() {
        router.navigate(BackCommand)
    }

    companion object {
        const val DEFAULT_ZONE_METERS = 100.0
        const val MIN_ZONE_METERS = 10.0
        const val MAX_ZONE_METERS = 5000.0
    }
}
