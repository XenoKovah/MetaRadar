package com.darkmentor.ui.exclusionzones

import android.view.MotionEvent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
                    .pointerInteropFilter { event ->
                        val m = map.value
                        val adj = viewModel.mode as? Mode.Adjust
                        if (m != null && adj != null) {
                            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                                val p = m.projection.fromPixels(event.x.toInt(), event.y.toInt())
                                viewModel.onResizeDrag(adj.center.distanceToAsDouble(GeoPoint(p.latitude, p.longitude)))
                            }
                            true // consume — no pan/zoom while adjusting
                        } else {
                            false // browse — let osmdroid handle the gesture
                        }
                    },
                onMapReady = { map.value = it },
            )

            // Center crosshair marks where a NEW zone will be placed (BROWSE only).
            if (map.value != null && viewModel.mode is Mode.Browse) {
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

    @Composable
    private fun CenterCrosshair() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.size(width = 20.dp, height = 10.dp).blur(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 10.dp, height = 5.dp)
                        .background(color = Color.DarkGray, shape = RoundedCornerShape(10.dp)),
                )
            }
            Image(
                modifier = Modifier.height(60.dp).width(60.dp),
                contentScale = ContentScale.FillWidth,
                painter = painterResource(R.drawable.ic_location),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
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
    private fun AdjustBottomBar(modifier: Modifier, sizeMeters: Double, onSave: () -> Unit, onCancel: () -> Unit) {
        Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.exclusion_zones_size_label, sizeMeters.toInt()),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally),
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
