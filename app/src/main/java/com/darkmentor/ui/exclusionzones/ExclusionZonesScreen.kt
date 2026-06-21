package com.darkmentor.ui.exclusionzones

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.darkmentor.R
import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.LocationRepository
import com.darkmentor.ui.devicedetails.MapConfig
import com.darkmentor.ui.exclusionzones.ExclusionZonesViewModel.Mode
import com.darkmentor.ui.exclusionzones.ExclusionZonesViewModel.ShapeKind
import com.darkmentor.ui.map.MapView
import com.darkmentor.utils.navigation.Router
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.getKoin
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
object ExclusionZonesScreen {

    @Composable
    fun Screen(router: Router) {
        val viewModel: ExclusionZonesViewModel = koinViewModel()
        Scaffold(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize(),
            topBar = { AppBar(onCloseClick = { viewModel.onCloseClick() }) },
            content = { paddings ->
                Content(
                    modifier = Modifier.padding(top = paddings.calculateTopPadding()),
                    viewModel = viewModel,
                )
            },
        )
    }

    @Composable
    private fun AppBar(onCloseClick: () -> Unit) {
        TopAppBar(
            title = { Text(text = stringResource(R.string.exclusion_zones_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onCloseClick) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }

    @Composable
    private fun Content(modifier: Modifier, viewModel: ExclusionZonesViewModel) {
        val map = remember { mutableStateOf<MapView?>(null) }
        var selectedZoneIndex by remember { mutableStateOf<Int?>(null) }

        val savedStroke = MaterialTheme.colorScheme.primary.toArgb()
        val savedFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f).toArgb()
        val inProgressStroke = MaterialTheme.colorScheme.tertiary.toArgb()
        val inProgressFill = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f).toArgb()

        Box(modifier = modifier.fillMaxSize()) {

            // The map. During ADJUST, the pointer filter consumes touches and turns drags into a
            // resize; during BROWSE it passes through so osmdroid pans/zooms.
            ExclusionMap(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter { _ ->
                        // ADJUST: the map is frozen (size comes from the Size slider, center is fixed
                        // at the crosshair), so consume touches to block pan/zoom. BROWSE: let
                        // osmdroid handle pan/zoom.
                        viewModel.mode is Mode.Adjust
                    },
                onMapReady = { map.value = it },
            )

            // Center crosshair marks the zone center — where a NEW zone is dropped (BROWSE) and the
            // fixed center the in-progress shape is sized around (ADJUST). Always at screen center.
            if (map.value != null) {
                CenterCrosshair()
            }

            // Top: address search (BROWSE) or the "Adjust exclusion zone" label (ADJUST).
            if (map.value != null && viewModel.mode is Mode.Browse) {
                AddressSearchBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    enabled = !viewModel.addressSearchInProgress,
                    onSearch = { viewModel.onAddressSearch(it) },
                )
            }
            if (viewModel.mode is Mode.Adjust) {
                RoundedLabel(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    text = stringResource(R.string.exclusion_zones_adjust_label),
                )
            }

            // Middle-right: Circle / Square rail (BROWSE only). Greyed at the cap; tapping a greyed
            // one toasts (handled in onAddShape).
            if (map.value != null && viewModel.mode is Mode.Browse) {
                ShapeRail(
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                    greyed = !viewModel.canAddZone,
                    onAdd = { shape ->
                        val c = map.value?.mapCenter
                        viewModel.onAddShape(shape, GeoPoint(c?.latitude ?: 0.0, c?.longitude ?: 0.0))
                    },
                )
            }

            // Bottom: Save / Cancel (ADJUST only).
            if (viewModel.mode is Mode.Adjust) {
                AdjustBottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    sizeMeters = (viewModel.mode as Mode.Adjust).sizeMeters,
                    onSizeChange = { viewModel.onSizeChanged(it) },
                    onSave = { viewModel.onSaveZone() },
                    onCancel = { viewModel.onCancelAdjust() },
                )
            }
        }

        // Toggle multi-touch with the mode (off while adjusting).
        LaunchedEffect(map.value, viewModel.mode) {
            map.value?.setMultiTouchControls(viewModel.mode is Mode.Browse)
        }

        // Consume one-shot recenter requests (address search / edit).
        LaunchedEffect(map.value, viewModel.recenterTarget) {
            val m = map.value
            val t = viewModel.recenterTarget
            if (m != null && t != null) {
                m.controller.setZoom(MapConfig.DEFAULT_MAP_ZOOM)
                m.controller.setCenter(t)
                viewModel.onRecenterConsumed()
            }
        }

        // Saved zones → tappable overlays (open the Delete/Edit dialog).
        LaunchedEffect(map.value, viewModel.zones) {
            val m = map.value ?: return@LaunchedEffect
            m.overlays.removeAll { it is SavedZonePolygon }
            viewModel.zones.forEachIndexed { index, zone ->
                m.overlays.add(
                    SavedZonePolygon(m, index).apply {
                        setPoints(zone.toPoints())
                        outlinePaint.color = savedStroke
                        outlinePaint.strokeWidth = 5f
                        fillPaint.color = savedFill
                        setOnClickListener { _, _, _ -> selectedZoneIndex = index; true }
                    }
                )
            }
            m.invalidate()
        }

        // In-progress zone overlay, rebuilt as the size changes during ADJUST.
        LaunchedEffect(map.value, viewModel.mode) {
            val m = map.value ?: return@LaunchedEffect
            m.overlays.removeAll { it is InProgressZonePolygon }
            (viewModel.mode as? Mode.Adjust)?.let { adj ->
                m.overlays.add(
                    InProgressZonePolygon(m).apply {
                        setPoints(zonePoints(adj.shape, adj.center, adj.sizeMeters))
                        outlinePaint.color = inProgressStroke
                        outlinePaint.strokeWidth = 6f
                        fillPaint.color = inProgressFill
                    }
                )
            }
            m.invalidate()
        }

        // Delete / Edit chooser for a tapped saved zone.
        selectedZoneIndex?.let { index ->
            AlertDialog(
                onDismissRequest = { selectedZoneIndex = null },
                title = { Text(text = stringResource(R.string.exclusion_zones_zone_options_title)) },
                text = { Text(text = stringResource(R.string.exclusion_zones_zone_options_message)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.onEditZone(index); selectedZoneIndex = null }) {
                        Text(text = stringResource(R.string.edit))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDeleteZone(index); selectedZoneIndex = null }) {
                        Text(text = stringResource(R.string.delete))
                    }
                },
            )
        }
    }

    @Composable
    private fun ExclusionMap(modifier: Modifier, onMapReady: (MapView) -> Unit) {
        val locationProvider = getKoin().get<LocationProvider>()
        val permissionHelper = getKoin().get<PermissionHelper>()
        val locationRepository = getKoin().get<LocationRepository>()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        MapView(
            modifier = modifier,
            onLoad = { mapView ->
                onMapReady(mapView)
                mapView.setMultiTouchControls(true)
                mapView.minZoomLevel = MapConfig.MIN_MAP_ZOOM
                mapView.maxZoomLevel = MapConfig.MAX_MAP_ZOOM
                // Seed the camera immediately from a cached last-known fix at a usable zoom, so the
                // map is never stuck at whole-world min zoom while waiting for a live fix — which
                // indoors can be slow or filtered out entirely (accuracy/freshness limits).
                val allowed = permissionHelper.locationAllowed()
                // Instant synchronous seed from the system's cached fix so the map never flashes
                // whole-world while the recorded-location DB query runs.
                val sysSeed = if (allowed) locationProvider.lastKnownLocation() else null
                mapView.controller.setZoom(if (sysSeed != null) MapConfig.DEFAULT_MAP_ZOOM else MapConfig.MIN_MAP_ZOOM)
                sysSeed?.let { mapView.controller.setCenter(GeoPoint(it)) }
                scope.launch {
                    // Default the camera to the user's LAST RECORDED GPS location — where they
                    // actually collected data, which is where they'll want to draw exclusion zones.
                    val recorded = locationRepository.getLastRecordedLocation()
                    if (recorded != null) {
                        mapView.controller.setZoom(MapConfig.DEFAULT_MAP_ZOOM)
                        mapView.controller.setCenter(GeoPoint(recorded.lat, recorded.lng))
                    } else if (allowed) {
                        // Nothing recorded yet — request a live fix and center on the first one.
                        // fetchOnce() must run BEFORE collect(): observeLocation() is a
                        // MutableStateFlow(null), so collect() suspends until a non-null fix arrives.
                        locationProvider.fetchOnce()
                        locationProvider.observeLocation()
                            .filterNotNull()
                            .take(1)
                            .collect { location ->
                                mapView.controller.setZoom(MapConfig.DEFAULT_MAP_ZOOM)
                                mapView.controller.setCenter(GeoPoint(location.location))
                            }
                    }
                }
            },
            onUpdate = { mapView -> onMapReady(mapView) },
        )
    }

    /** Targeting reticle centered on the map: a ring + center dot + four ticks, so it's clear the
     *  zone is built around the EXACT center point (not the bottom of a pin). */
    @Composable
    private fun CenterCrosshair() {
        val color = MaterialTheme.colorScheme.primary
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val stroke = 2.5.dp.toPx()
                val ring = size.minDimension * 0.28f
                val gap = ring + 3.dp.toPx()
                val end = size.minDimension / 2f
                drawCircle(color = color, radius = ring, center = c, style = Stroke(width = stroke))
                drawCircle(color = color, radius = 2.dp.toPx(), center = c)
                drawLine(color, Offset(c.x, c.y - gap), Offset(c.x, c.y - end), strokeWidth = stroke)
                drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, c.y + end), strokeWidth = stroke)
                drawLine(color, Offset(c.x - gap, c.y), Offset(c.x - end, c.y), strokeWidth = stroke)
                drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + end, c.y), strokeWidth = stroke)
            }
        }
    }

    @Composable
    private fun AddressSearchBar(modifier: Modifier, enabled: Boolean, onSearch: (String) -> Unit) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            value = query,
            onValueChange = { query = it },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.exclusion_zones_address_hint)) },
            trailingIcon = {
                IconButton(onClick = { onSearch(query) }) {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        )
    }

    @Composable
    private fun RoundedLabel(modifier: Modifier, text: String) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = text, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ShapeRail(modifier: Modifier, greyed: Boolean, onAdd: (ShapeKind) -> Unit) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            ShapeButton(shape = CircleShape, greyed = greyed, onClick = { onAdd(ShapeKind.CIRCLE) })
            Spacer(modifier = Modifier.height(12.dp))
            ShapeButton(shape = RectangleShape, greyed = greyed, onClick = { onAdd(ShapeKind.SQUARE) })
        }
    }

    @Composable
    private fun ShapeButton(shape: Shape, greyed: Boolean, onClick: () -> Unit) {
        val outline = if (greyed) Color.Gray else MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp))
                .clickable { onClick() }, // always clickable so a greyed tap can toast
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(26.dp).border(width = 3.dp, color = outline, shape = shape))
        }
    }

    @Composable
    private fun AdjustBottomBar(
        modifier: Modifier,
        sizeMeters: Double,
        onSizeChange: (Double) -> Unit,
        onSave: () -> Unit,
        onCancel: () -> Unit,
    ) {
        val minM = ExclusionZonesViewModel.MIN_ZONE_METERS
        val maxM = ExclusionZonesViewModel.MAX_ZONE_METERS
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.exclusion_zones_size_label, sizeMeters.toInt()),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            // Size slider: left = smallest (MIN_ZONE_METERS), right = largest (MAX). Exponential
            // mapping so small house-sized zones get usable travel across the 10 m – 5 km range, and
            // it lives at the bottom so a finger never covers the shape being sized.
            Slider(
                value = sizeFraction(sizeMeters, minM, maxM),
                onValueChange = { onSizeChange(fractionToMeters(it, minM, maxM)) },
                valueRange = 0f..1f,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = onCancel) {
                    Text(text = stringResource(R.string.exclusion_zones_cancel))
                }
                Button(modifier = Modifier.weight(1f), onClick = onSave) {
                    Text(text = stringResource(R.string.exclusion_zones_save))
                }
            }
        }
    }

    /** Slider fraction [0,1] for [meters], exponential across [minM]..[maxM] (more travel for small sizes). */
    private fun sizeFraction(meters: Double, minM: Double, maxM: Double): Float =
        (ln(meters / minM) / ln(maxM / minM)).toFloat().coerceIn(0f, 1f)

    /** Inverse of [sizeFraction]: slider fraction [f] → meters, exponential across [minM]..[maxM]. */
    private fun fractionToMeters(f: Float, minM: Double, maxM: Double): Double =
        (minM * (maxM / minM).pow(f.toDouble())).coerceIn(minM, maxM)

    // ---- osmdroid overlay helpers -------------------------------------------------------------

    private class SavedZonePolygon(map: MapView, val zoneIndex: Int) : Polygon(map)
    private class InProgressZonePolygon(map: MapView) : Polygon(map)

    private fun zonePoints(shape: ShapeKind, center: GeoPoint, sizeMeters: Double): List<GeoPoint> =
        when (shape) {
            ShapeKind.CIRCLE -> Polygon.pointsAsCircle(center, sizeMeters)
            // pointsAsRect takes the full side lengths and returns IGeoPoint — map to GeoPoint.
            ShapeKind.SQUARE -> Polygon.pointsAsRect(center, sizeMeters * 2, sizeMeters * 2)
                .map { GeoPoint(it.latitude, it.longitude) }
        }

    private fun com.darkmentor.domain.model.ExclusionZone.toPoints(): List<GeoPoint> = when (this) {
        is com.darkmentor.domain.model.ExclusionZone.Circle ->
            zonePoints(ShapeKind.CIRCLE, GeoPoint(centerLat, centerLng), radiusMeters)
        is com.darkmentor.domain.model.ExclusionZone.Square ->
            zonePoints(ShapeKind.SQUARE, GeoPoint(centerLat, centerLng), halfSizeMeters)
    }
}
