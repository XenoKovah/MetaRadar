package f.cking.software.ui.devicedetails

import android.bluetooth.BluetoothGattCharacteristic
import android.graphics.Paint
import android.view.MotionEvent
import f.cking.software.dateTimeStringFormatLocalized
import f.cking.software.domain.model.Transport
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.flowlayout.FlowRow
import com.vanpra.composematerialdialogs.rememberMaterialDialogState
import f.cking.software.R
import f.cking.software.bottomRight
import f.cking.software.dateTimeStringFormat
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.LocationModel
import f.cking.software.domain.model.toGeoPoint
import f.cking.software.domain.model.toLocation
import f.cking.software.dpToPx
import f.cking.software.extract16BitUuid
import f.cking.software.frameRate
import f.cking.software.mapParallel
import f.cking.software.pxToDp
import f.cking.software.splitToBatchesEqual
import f.cking.software.toLocation
import f.cking.software.topLeft
import f.cking.software.ui.AsyncBatchProcessor
import f.cking.software.ui.map.MapView
import f.cking.software.ui.tagdialog.TagDialog
import f.cking.software.utils.ScreenSizeLocal
import f.cking.software.utils.graphic.DevicePairedIcon
import f.cking.software.utils.graphic.DeviceTypeIcon
import f.cking.software.utils.graphic.ExtendedAddressView
import f.cking.software.utils.graphic.HeatMapBitmapFactory
import f.cking.software.utils.graphic.HeatMapBitmapFactory.Tile
import f.cking.software.utils.graphic.ListItem
import f.cking.software.utils.graphic.RadarIcon
import f.cking.software.utils.graphic.RoundedBox
import f.cking.software.utils.graphic.SignalData
import f.cking.software.utils.graphic.Switcher
import f.cking.software.utils.graphic.SystemNavbarSpacer
import f.cking.software.utils.graphic.TagChip
import f.cking.software.utils.graphic.ThemedDialog
import f.cking.software.utils.graphic.infoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import timber.log.Timber
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
object DeviceDetailsScreen {

    private const val TAG = "DeviceDetailsScreen"

    @Composable
    fun Screen(
        address: String,
        viewModel: DeviceDetailsViewModel = koinViewModel(key = address) { parametersOf(address) }
    ) {

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize(),
            topBar = {
                AppBar(viewModel = viewModel, scrollBehavior)
            },
            content = { padding ->
                Content(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    viewModel = viewModel,
                )
            }
        )
    }

    @Composable
    private fun AppBar(viewModel: DeviceDetailsViewModel, scrollBehavior: TopAppBarScrollBehavior) {
        val deviceData = viewModel.deviceState
        TopAppBar(
            scrollBehavior = scrollBehavior,
            title = {
                Text(text = stringResource(R.string.device_details_title))
            },
            actions = {},
            navigationIcon = {
                IconButton(onClick = { viewModel.back() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )
    }

    @Composable
    private fun Content(
        modifier: Modifier,
        viewModel: DeviceDetailsViewModel,
    ) {
        val deviceData = viewModel.deviceState
        if (deviceData == null) {
            Progress(modifier)
        } else {
            DeviceDetails(modifier, viewModel, deviceData)
        }
    }

    @Composable
    private fun Progress(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }

    @Composable
    private fun DeviceDetails(
        modifier: Modifier,
        viewModel: DeviceDetailsViewModel,
        deviceData: DeviceData,
    ) {
        var scrollEnabled by remember { mutableStateOf(true) }
        val isMoving = remember { mutableStateOf(false) }

        val screenHeight = ScreenSizeLocal.current.height
        val expandedHeight = screenHeight * 0.9f
        val collapsedHeight = screenHeight * 0.4f

        LaunchedEffect(isMoving.value) {
            scrollEnabled = !isMoving.value
        }

        Column(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState(), scrollEnabled)
                .fillMaxSize(),
        ) {

            val mapToolkitOffsetDp = 100
            val mapBlockSizePx = LocalContext.current.pxToDp(if (viewModel.mapExpanded) expandedHeight else collapsedHeight) + mapToolkitOffsetDp
            LocationHistory(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .height(mapBlockSizePx.dp),
                deviceData = deviceData,
                viewModel = viewModel,
                isMoving = isMoving,
            )
            OnlineStatus(viewModel = viewModel, deviceData.isConnectable)
            Spacer(modifier = Modifier.height(16.dp))
            Tags(deviceData = deviceData, viewModel = viewModel)
            Spacer(modifier = Modifier.height(16.dp))
            DeviceContent(modifier = Modifier, deviceData = deviceData, viewModel = viewModel)
            Spacer(modifier = Modifier.height(16.dp))
            SystemNavbarSpacer()
        }
    }

    @Composable
    private fun OnlineStatus(
        viewModel: DeviceDetailsViewModel,
        isConnectable: Boolean,
    ) {
        viewModel.onlineStatusData?.let { onlineStatus ->
            Spacer(modifier = Modifier.height(16.dp))
            RoundedBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadarIcon()
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(modifier = Modifier, text = stringResource(id = R.string.device_is_online), fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = stringResource(viewModel.connectionStatus.statusRes))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    SignalData(rssi = onlineStatus.signalStrength, distance = onlineStatus.distance)
                }
            }
        }
        if (isConnectable) {
            Spacer(modifier = Modifier.height(8.dp))
            ConnectControl(viewModel)
        }
    }

    @Composable
    private fun ConnectControl(viewModel: DeviceDetailsViewModel) {
        RoundedBox(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(viewModel.connectionStatus.statusRes),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                when (val status = viewModel.connectionStatus) {
                    is DeviceDetailsViewModel.ConnectionStatus.DISCONNECTED -> {
                        Button(onClick = { viewModel.establishConnection() }) {
                            Text(text = stringResource(R.string.device_details_connect), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    is DeviceDetailsViewModel.ConnectionStatus.CONNECTED -> {
                        Button(onClick = { viewModel.disconnect(status.gatt) }) {
                            Text(text = stringResource(R.string.device_details_disconnect), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }

                    is DeviceDetailsViewModel.ConnectionStatus.CONNECTING, is DeviceDetailsViewModel.ConnectionStatus.DISCONNECTING -> {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    @Composable
    private fun DeviceContent(
        modifier: Modifier = Modifier,
        deviceData: DeviceData,
        viewModel: DeviceDetailsViewModel,
    ) {
        RoundedBox(
            modifier = modifier
                .fillMaxWidth(),
            internalPaddings = 0.dp,
        ) {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceTypeIcon(modifier = Modifier.size(42.dp), device = deviceData, paddingDp = 4.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = deviceData.buildDisplayName(),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        DevicePairedIcon(deviceData.isPaired, extended = true)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = stringResource(R.string.device_details_name), fontWeight = FontWeight.Bold)
                    Text(text = deviceData.resolvedName ?: stringResource(R.string.not_applicable))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = stringResource(R.string.device_details_address), fontWeight = FontWeight.Bold)
                    ExtendedAddressView(deviceData.extendedAddressInfo())
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = stringResource(R.string.device_details_manufacturer), fontWeight = FontWeight.Bold)
                    Text(text = deviceData.resolvedManufacturerName ?: stringResource(R.string.not_applicable))
                    Spacer(modifier = Modifier.height(8.dp))

                    SdpServices(viewModel)
                    Services(viewModel.services, viewModel)
                    Spacer(modifier = Modifier.height(8.dp))

                    RawData(viewModel.rawData)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row {
                        Text(
                            text = stringResource(R.string.device_details_detect_count),
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(text = deviceData.detectCount.toString())
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.device_details_first_detection), fontWeight = FontWeight.Bold)
                    Text(text = deviceData.firstDetectionExactTime(LocalContext.current, formatStyle = FormatStyle.MEDIUM))

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.device_details_last_detection), fontWeight = FontWeight.Bold)
                    Text(text = deviceData.lastDetectionExactTime(LocalContext.current, formatStyle = FormatStyle.MEDIUM))
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    /**
     * Renders the SDP service-class UUIDs returned by `BluetoothDevice.fetchUuidsWithSdp()`.
     * Hidden entirely for LE-only devices (GATT services tell the same story over there). For
     * BR/EDR / dual-mode devices, surfaces the cached list synchronously and re-fetches when
     * the user taps the action button — running fetch state is reflected in the section title.
     */
    @Composable
    private fun SdpServices(viewModel: DeviceDetailsViewModel) {
        val transport = viewModel.deviceState?.transport ?: return
        if (transport != Transport.BREDR && transport != Transport.DUAL) return

        val sdpServices = viewModel.sdpServices
        val inProgress = viewModel.sdpFetchInProgress
        val lastFetch = viewModel.sdpLastFetchTimeMs
        val context = LocalContext.current

        val title = when {
            inProgress -> stringResource(R.string.sdp_services_status_in_progress)
            sdpServices.isEmpty() -> stringResource(R.string.sdp_services_status_empty)
            lastFetch != null -> stringResource(
                R.string.sdp_services_status_count_template,
                sdpServices.size,
                lastFetch.dateTimeStringFormatLocalized(FormatStyle.SHORT),
            )
            else -> "${sdpServices.size} service classes"
        }

        Text(
            text = stringResource(R.string.sdp_services_section_title),
            fontWeight = FontWeight.Bold,
        )
        Text(text = title, fontWeight = FontWeight.Light)
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { viewModel.fetchSdpServices() },
            enabled = !inProgress,
        ) {
            Text(
                text = stringResource(R.string.sdp_services_refetch),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        if (sdpServices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            sdpServices.forEach { svc ->
                val uuidLabel = displayUuid(svc.uuid)
                val name = svc.name
                val clues = svc.clues
                val hasPurpose = clues?.purpose != null
                ExpandableLine(
                    title = { UuidTitle(uuid = uuidLabel, name = name, clues = clues) },
                    isExpandable = hasPurpose,
                ) {
                    if (hasPurpose) CluesPurpose(clues!!.purpose!!)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    @Composable
    private fun Services(servicesUuids: Set<DeviceDetailsViewModel.ServiceData>, viewModel: DeviceDetailsViewModel) {
        val characteristicCount = servicesUuids.sumOf { it.characteristics.size }
        val title = stringResource(
            R.string.services_and_characteristics_discovered,
            servicesUuids.size,
            characteristicCount,
        )
        ExpandableLine(title) {
            servicesUuids.forEach { service ->
                ServiceDetails(service, viewModel)
            }
        }
    }

    private fun displayUuid(uuid: String): String {
        // BTIDES stores SIG short UUIDs as 4-char hex ("1801"); live BluetoothGattService objects
        // expose them in expanded base form. Render both as "0xXXXX" so the cached and live
        // entries look consistent.
        val short = extract16BitUuid(uuid)
            ?: uuid.takeIf { it.length == 4 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
        return if (short != null) "0x${short.uppercase()}" else uuid
    }

    @Composable
    private fun ServiceDetails(service: DeviceDetailsViewModel.ServiceData, viewModel: DeviceDetailsViewModel) {
        val serviceUuid = displayUuid(service.uuid)
        val name = service.name
        val clues = service.clues
        val hasPurpose = clues?.purpose != null
        ExpandableLine(
            title = { UuidTitle(uuid = serviceUuid, name = name, clues = clues) },
            isExpandable = service.characteristics.isNotEmpty() || hasPurpose,
            initiallyExpanded = service.characteristics.isNotEmpty(),
        ) {
            Column {
                if (hasPurpose) {
                    CluesPurpose(clues!!.purpose!!)
                    Spacer(Modifier.height(8.dp))
                }
                if (service.characteristics.isNotEmpty()) {
                    Column(
                        Modifier
                            .border(width = 1.dp, color = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        service.characteristics.forEach { characteristic ->
                            CharacteristicDetails(service.uuid, characteristic, viewModel)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CharacteristicDetails(parentServiceUuid: String, characteristic: DeviceDetailsViewModel.CharacteristicData, viewModel: DeviceDetailsViewModel) {
        val characteristicUuid = displayUuid(characteristic.uuid)
        val isReadable = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        // The Read/Re-read button gates on the device-level connection state, not on whether
        // *this specific entry* still has a live BluetoothGattCharacteristic. Cached entries
        // can be re-read against the live gatt by looking the char up by UUID — the connection
        // is the source of truth for "can we issue a read right now?".
        val isConnected = viewModel.connectionStatus is DeviceDetailsViewModel.ConnectionStatus.CONNECTED
        val clues = characteristic.clues
        val hasPurpose = clues?.purpose != null
        val name = characteristic.name
        val value = characteristic.value
        val valueHex = characteristic.valueHex
        val hasCachedValue = value != null && valueHex != null

        ExpandableLine(
            title = { UuidTitle(uuid = characteristicUuid, name = name, clues = clues) },
            isExpandable = isReadable || hasPurpose || hasCachedValue,
        ) {
            Column {
                if (hasPurpose) {
                    CluesPurpose(clues!!.purpose!!)
                    if (isReadable || hasCachedValue) Spacer(Modifier.height(8.dp))
                }
                if (isReadable || hasCachedValue) {
                    if (hasCachedValue) {
                        Text(value!!)
                        Text(valueHex!!)
                        if (isConnected && isReadable) Spacer(Modifier.height(4.dp))
                    }
                    if (isConnected && isReadable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val onClick: () -> Unit = {
                                if (characteristic.gatt != null) {
                                    viewModel.readCharacteristic(characteristic.gatt)
                                } else {
                                    viewModel.readCharacteristicByUuid(parentServiceUuid, characteristic.uuid)
                                }
                            }
                            if (hasCachedValue) {
                                ReReadChip(onClick)
                            } else {
                                TagChip(stringResource(R.string.read), onClick = onClick)
                            }
                            if (viewModel.recentReadFailures.contains(characteristic.uuid)) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.read_failed),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun UuidTitle(uuid: String, name: String?, clues: DeviceDetailsViewModel.CluesInfo?) {
        // Resolved name precedence:
        //   1. Bluetooth-SIG Assigned Numbers — set on `name` for 16-bit UUIDs that match.
        //   2. CLUES community attribution — used for 128-bit (and otherwise unknown 16-bit) UUIDs.
        //   3. "Unknown" — neither source had a hit.
        // The UUID and the resolved name share one bold line ("0x180F - Battery Service"). CLUES
        // company stays as a secondary line when present.
        val resolvedName = name ?: clues?.name ?: stringResource(R.string.uuid_name_unknown)
        Column {
            Text(text = "$uuid - $resolvedName", fontWeight = FontWeight.Bold)
            clues?.company?.let { Text(text = it, fontWeight = FontWeight.Light) }
        }
    }

    @Composable
    private fun ReReadChip(onClick: () -> Unit) {
        AssistChip(
            onClick = onClick,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = colorResource(R.color.green_600),
                labelColor = androidx.compose.ui.graphics.Color.White,
            ),
            border = null,
            label = { Text(text = stringResource(R.string.re_read)) },
        )
    }

    @Composable
    private fun CluesPurpose(purpose: String) {
        Column(modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(text = stringResource(R.string.clues_purpose_label), fontWeight = FontWeight.SemiBold)
            Text(text = purpose)
        }
    }

    @Composable
    private fun RawData(rawData: List<DeviceDetailsViewModel.AdRecord>) {
        ExpandableLine(pluralStringResource(R.plurals.device_details_raw_data, rawData.size, rawData.size)) {
            rawData.forEach { record ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    val typeLabel = record.typeName
                        ?.let { "0x${record.typeHex} — $it" }
                        ?: stringResource(R.string.device_details_raw_data_unknown_type, record.typeHex)
                    Text(
                        text = typeLabel,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = "0x${record.dataHex}")
                    record.fields.forEach { field ->
                        Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                            Text(
                                text = "${field.label}: ",
                                fontWeight = FontWeight.Light,
                            )
                            Text(text = field.value)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ExpandableLine(
        title: String,
        isExpandable: Boolean = true,
        initiallyExpanded: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        ExpandableLine(
            title = {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                )
            },
            isExpandable = isExpandable,
            initiallyExpanded = initiallyExpanded,
            content = content
        )
    }

    @Composable
    private fun ExpandableLine(
        title: @Composable () -> Unit,
        isExpandable: Boolean = true,
        initiallyExpanded: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            var expanded by remember { mutableStateOf(initiallyExpanded && isExpandable) }
            // Icon is a right-pointing triangle; rotate 90° to point down when expanded.
            val rotation by animateFloatAsState(90f * if (expanded) 1 else 0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isExpandable) {
                            expanded = !expanded
                        }
                    }
                    .padding(vertical = 8.dp)
            ) {
                title.invoke()
                if (isExpandable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        modifier = Modifier.rotate(rotation),
                        painter = painterResource(R.drawable.ic_drop_up),
                        contentDescription = null,
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column {
                    content.invoke()
                }
            }
        }
    }

    @Composable
    private fun Tags(
        deviceData: DeviceData,
        viewModel: DeviceDetailsViewModel,
    ) {
        RoundedBox(
            modifier = Modifier.fillMaxWidth(),
            internalPaddings = 0.dp
        ) {
            FlowRow(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
            ) {
                AddTag(viewModel = viewModel, deviceData = deviceData)
                deviceData.tags.forEach { tag ->
                    Tag(name = tag, viewModel = viewModel, deviceData = deviceData)
                }
            }
        }
    }

    @Composable
    private fun Tag(
        deviceData: DeviceData,
        name: String,
        viewModel: DeviceDetailsViewModel,
    ) {
        val dialogState = rememberMaterialDialogState()

        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialogState.hide() }
                positiveButton(text = stringResource(R.string.confirm), textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    dialogState.hide()
                    viewModel.onRemoveTagClick(deviceData, name)
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(text = stringResource(R.string.delete_tag_title, name), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
        TagChip(tagName = name, tagIcon = Icons.Filled.Delete) { dialogState.show() }
    }

    @Composable
    private fun AddTag(
        deviceData: DeviceData,
        viewModel: DeviceDetailsViewModel,
    ) {
        val addTagDialog = TagDialog.rememberDialog {
            viewModel.onNewTagSelected(deviceData, it)
        }
        SuggestionChip(
            onClick = { addTagDialog.show() },
            icon = {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            },
            label = { Text(text = stringResource(R.string.add_tag)) }
        )
    }

    @Composable
    private fun PointsStyle(
        viewModel: DeviceDetailsViewModel,
    ) {
        val dialog = rememberMaterialDialogState()
        ThemedDialog(
            dialogState = dialog,
            buttons = {
                negativeButton(
                    stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialog.hide() }
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(R.string.device_history_pint_style), fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                DeviceDetailsViewModel.PointsStyle.entries.forEach { pointStyle ->
                    val isSelected = viewModel.pointsStyle == pointStyle

                    val onClick = {
                        viewModel.pointsStyle = pointStyle
                        dialog.hide()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = onClick)
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(pointStyle.displayNameRes), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        ListItem(
            icon = painterResource(R.drawable.ic_style),
            title = stringResource(R.string.device_history_pint_style),
            subtitle = stringResource(viewModel.pointsStyle.displayNameRes),
            onClick = { dialog.show() }
        )
    }

    @Composable
    private fun HeatMapSettings(viewModel: DeviceDetailsViewModel) {
        Switcher(
            modifier = Modifier.fillMaxWidth(),
            value = viewModel.useHeatmap,
            title = stringResource(R.string.device_history_pint_style_heatmap),
            subtitle = null,
            onClick = {
                viewModel.useHeatmap = !viewModel.useHeatmap
            }
        )
    }

    @Composable
    private fun HistoryPeriod(
        deviceData: DeviceData,
        viewModel: DeviceDetailsViewModel,
    ) {
        val dialog = rememberMaterialDialogState()
        ThemedDialog(
            dialogState = dialog,
            buttons = {
                negativeButton(
                    stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
                ) { dialog.hide() }
            },
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(R.string.change_history_period_dialog), fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                DeviceDetailsViewModel.HistoryPeriod.entries.forEach { period ->
                    val isSelected = viewModel.historyPeriod == period

                    val onClick = {
                        viewModel.selectHistoryPeriodSelected(period, deviceData.address, autotunePeriod = false)
                        dialog.hide()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = onClick)
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(period.displayNameRes), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        ListItem(
            icon = painterResource(R.drawable.ic_time),
            title = stringResource(R.string.device_details_history_period, stringResource(viewModel.historyPeriod.displayNameRes)),
            subtitle = stringResource(R.string.device_details_history_period_subtitle),
            onClick = { dialog.show() }
        )
    }

    @Composable
    private fun LocationHistory(
        modifier: Modifier = Modifier,
        deviceData: DeviceData, viewModel: DeviceDetailsViewModel,
        isMoving: MutableState<Boolean>,
    ) {
        RoundedBox(modifier = modifier, internalPaddings = 0.dp) {
            var mapIsReady by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .weight(1f)
            ) {
                Map(
                    Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    isLoading = { viewModel.markersInLoadingState = it },
                    mapIsReadyToUse = {
                        mapIsReady = true
                    },
                    isMoving = isMoving,
                )
                if (mapIsReady) {
                    MapOverlay(viewModel = viewModel)
                }
            }
            if (mapIsReady) {
                HeatMapSettings(viewModel)
                PointsStyle(viewModel)
                HistoryPeriod(deviceData = deviceData, viewModel = viewModel)
            }
        }
    }

    @Composable
    private fun MapOverlay(
        viewModel: DeviceDetailsViewModel
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            if (viewModel.pointsState.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.background(color = colorResource(id = R.color.black_30), shape = RoundedCornerShape(8.dp))) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = stringResource(R.string.device_details_no_location_history_for_such_period),
                            color = Color.White,
                        )
                    }
                }
            }

            if (viewModel.markersInLoadingState || viewModel.loadingHeatmap) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            val dialog = infoDialog(
                title = stringResource(R.string.device_map_disclaimer_title),
                content = stringResource(R.string.device_map_disclaimer_content)
            )

            IconButton(
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = {
                    dialog.show()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.device_map_disclaimer_title),
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.1f), shape = CircleShape),
                    tint = Color.DarkGray,
                )
            }

            IconButton(
                modifier = Modifier.align(Alignment.TopEnd),
                onClick = {
                    viewModel.mapExpanded = !viewModel.mapExpanded
                },
            ) {
                Icon(
                    imageVector = if (viewModel.mapExpanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    contentDescription = stringResource(R.string.device_map_expand_title),
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.1f), shape = CircleShape),
                    tint = Color.DarkGray,
                )
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Map(
        modifier: Modifier,
        viewModel: DeviceDetailsViewModel,
        isLoading: (isLoading: Boolean) -> Unit,
        mapIsReadyToUse: () -> Unit,
        isMoving: MutableState<Boolean>,
    ) {

        val scope = rememberCoroutineScope()
        val frameRate = LocalContext.current.frameRate()

        val batchProcessor = remember {
            AsyncBatchProcessor<LocationModel, MapView>(
                frameRate = frameRate,
                provideIsCancelled = { !scope.isActive },
                onBatchCompleted = { batchId, map ->
                    // Re-assert best-fit z-order every batch: the inserts above pushed regular
                    // markers onto the end of the overlays list, displacing the best-fit pin.
                    map.overlays.bringBestFitToTop()
                    if (batchId % 10 == 0) {
                        map.invalidate()
                    }
                },
                processItem = { location, map ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(location.lat, location.lng)
                        title = location.time.dateTimeStringFormat("dd.MM.yy HH:mm")
                    }
                    map.overlays.add(marker)
                },
                onStart = { map ->
                    isLoading.invoke(true)
                    map.overlays.clearPoints()
                    map.invalidate()
                },
                onComplete = { map ->
                    isLoading.invoke(false)
                    map.overlays.bringBestFitToTop()
                    map.invalidate()
                },
                onCancelled = { map ->
                    isLoading.invoke(false)
                    map?.invalidate()
                }
            )
        }

        var mapView: MapView? by remember { mutableStateOf(null) }
        val colorScheme = MaterialTheme.colorScheme


        fun getViewport(): Tile? {
            val mapView = mapView ?: return null
            return Tile(topLeft = mapView.projection.topLeft().toLocation(), mapView.projection.bottomRight().toLocation())
        }

        var pendingViewport: Tile? by remember { mutableStateOf(getViewport()) }
        var committedViewport: Tile? by remember { mutableStateOf(pendingViewport) }

        fun updateViewport() {
            pendingViewport = getViewport()
        }

        fun commitViewPort() {
            committedViewport = pendingViewport
        }

        MapView(
            modifier = modifier.pointerInteropFilter { event ->
                if (mapView != null) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isMoving.value = true
                            false
                        }

                        MotionEvent.ACTION_UP -> {
                            isMoving.value = false
                            false
                        }

                        MotionEvent.ACTION_OUTSIDE -> {
                            isMoving.value = false
                            false
                        }

                        else -> true
                    }
                } else {
                    false
                }
            },
            onLoad = { map ->
                initMapState(map, colorScheme)
                mapIsReadyToUse.invoke()
                map.addMapListener(object : MapListener {

                    override fun onScroll(event: ScrollEvent?): Boolean {
                        isMoving.value = false
                        updateViewport()
                        return true
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        updateViewport()
                        return true
                    }
                })
                updateViewport()
            },
            onUpdate = { map -> mapView = map }
        )
        val mapColorScheme = remember { MapColorScheme(colorScheme.scrim.copy(alpha = 0.6f), Color.Red) }

        if (mapView != null) {
            val mapView = mapView!!

            val mapUpdate = MapUpdate(viewModel.pointsState, viewModel.cameraState, mapView)

            LaunchedEffect(pendingViewport) {
                delay(50L)
                yield()
                commitViewPort()
            }

            LaunchedEffect(mapView, viewModel.pointsState, viewModel.pointsStyle) {
                refreshMap(mapUpdate, batchProcessor, mapColorScheme, viewModel.pointsStyle)
            }

            LaunchedEffect(mapView, viewModel.pointsState) {
                updateMapCamera(mapUpdate)
            }

            val tilesState = rememberTilesState()
            LaunchedEffect(mapView, viewModel.pointsState, viewModel.useHeatmap, committedViewport) {
                val committedViewport = committedViewport ?: return@LaunchedEffect
                renderHeatmap(mapUpdate, committedViewport, viewModel, tilesState)
            }

            // Best-fit black marker. Re-emitted whenever the underlying RSSI samples or the
            // PointsStyle change — including pointsStyle in the keys ensures the marker is
            // re-asserted (i.e. moved to the end of the overlays list, so it draws last and
            // stays visible) after a style switch into PATH or HIDE_NON_BEST_FIT_MARKERS, both
            // of which mutate `mapView.overlays` and could otherwise cover the marker.
            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(mapView, viewModel.bestFitLocation, viewModel.pointsState, viewModel.pointsStyle) {
                val best = viewModel.bestFitLocation
                mapView.overlays.removeAll { it is BestFitMarker }
                if (best != null) {
                    val marker = BestFitMarker(mapView).apply {
                        position = GeoPoint(best.lat, best.lng)
                        title = BEST_FIT_MARKER_TITLE
                        // Tint the default osmdroid pin black to mark this as a fitted estimate
                        // rather than an actual detected sample.
                        val pin = androidx.core.content.ContextCompat
                            .getDrawable(context, org.osmdroid.library.R.drawable.marker_default)
                            ?.mutate()
                        pin?.colorFilter = android.graphics.PorterDuffColorFilter(
                            android.graphics.Color.BLACK,
                            android.graphics.PorterDuff.Mode.SRC_IN,
                        )
                        if (pin != null) icon = pin
                    }
                    // Append → drawn last → on top.
                    mapView.overlays.add(marker)
                    mapView.invalidate()
                }
            }
        }
    }

    /**
     * Move the best-fit marker (if any) to the end of [this] so osmdroid draws it last and it
     * never gets covered by a per-detection marker / polyline / fast-point overlay added after
     * the best-fit was first inserted. Cheap O(N) — overlays list rarely has more than a few
     * thousand entries on the Device Details screen.
     */
    private fun MutableList<Overlay>.bringBestFitToTop() {
        val best = firstOrNull { it is BestFitMarker } ?: return
        remove(best)
        add(best)
    }

    /**
     * Marker subclass used solely as a tag so the best-fit pin can be selectively removed
     * without disturbing the per-detection markers — both rely on osmdroid's [Marker] type so
     * a generic [removeAll] would clear them indiscriminately.
     */
    private class BestFitMarker(map: MapView) : Marker(map)

    private val BEST_FIT_MARKER_TITLE = "Best-fit (RSSI-weighted)"

    private fun initMapState(map: MapView, colorScheme: ColorScheme) {
        map.setMultiTouchControls(true)
        map.setBackgroundColor(colorScheme.surface.toArgb())
        map.minZoomLevel = MapConfig.MIN_MAP_ZOOM
        map.maxZoomLevel = MapConfig.MAX_MAP_ZOOM
        map.controller.setZoom(MapConfig.MIN_MAP_ZOOM)
    }

    private data class MapUpdate(
        val points: List<LocationModel>,
        val cameraState: DeviceDetailsViewModel.MapCameraState,
        val map: MapView,
    )

    private data class MapColorScheme(
        val lineColor: Color,
        val pointColor: Color,
    )


    private const val PADDING_METERS = 50.0
    private const val TILE_SIZE_METERS = 300.0

    @Composable
    private fun rememberTilesState() = remember { TilesState() }
    private class TilesState {
        var tiles = HashMap<Tile, TilesData>()
        var lastLocationsState: List<LocationModel> = emptyList()
    }

    private data class TilesData(
        val tile: Tile,
        val overlay: GroundOverlay,
        val locations: List<HeatMapBitmapFactory.Position>,
    )

    private suspend fun renderHeatmap(mapUpdate: MapUpdate, viewport: Tile, viewModel: DeviceDetailsViewModel, tilesState: TilesState) {

        if (viewModel.useHeatmap) {
            withContext(Dispatchers.Default) {
                val locations = mapUpdate.points.map { it.toLocation() }
                Timber.tag(TAG).d("Heatmap points: ${locations.size}")
                val pointsChanged = tilesState.lastLocationsState.size != locations.size
                val tiles = HeatMapBitmapFactory.buildTilesWithRenderPaddingStable(locations, TILE_SIZE_METERS, PADDING_METERS)
                    .asSequence()
                    .filter {
                        val inViewport = viewport.intersects(it)

                        if (!pointsChanged) {
                            val existedTile = tilesState.tiles[it]
                            val isAdded = mapUpdate.map.overlays.contains(existedTile?.overlay)
                            inViewport && (!tilesState.tiles.containsKey(it) || !isAdded)
                        } else {
                            inViewport
                        }
                    }
                    .sortedBy { tilesState.tiles.containsKey(it) }
                    .toList()

                Timber.tag(TAG).d("Heatmap tiles: ${tiles.size}")

                if (pointsChanged) {
                    val removedTiles = tilesState.tiles.values.filter { !tiles.contains(it.tile) }
                    removedTiles.forEach { tileData ->
                        Timber.tag(TAG).d("Tile exists but should be removed")
                        tilesState.tiles.remove(tileData.tile)
                        mapUpdate.map.overlays.remove(tileData.overlay)
                    }
                }

                tilesState.lastLocationsState = mapUpdate.points

                val loadingJob = launch(Dispatchers.Main) {
                    delay(30)
                    yield()
                    viewModel.loadingHeatmap = true
                }

                tiles.splitToBatchesEqual(10).mapParallel { batch ->
                    batch.map { tile ->
                        withContext(Dispatchers.Default) {

                            val positionsForTile = locations
                                .mapNotNull { loc ->
                                    if (!tile.contains(loc, PADDING_METERS)) return@mapNotNull null
                                    // Pull the per-detection RSSI from the VM sidecar (keyed by
                                    // location time). Missing entries carry null, which the
                                    // factory treats as "no RSSI info" — those pixels stay
                                    // green-by-default so old data doesn't disappear.
                                    val rssi = viewModel.rssiByTime[loc.time]
                                    HeatMapBitmapFactory.Position(loc, PADDING_METERS.toFloat(), rssi)
                                }
                            val existedTile = tilesState.tiles[tile]

                            if (existedTile != null && positionsForTile == existedTile.locations) {
                                // tile is already added and didn't change
                                Timber.tag(TAG).d("Tile already rendered")
                                withContext(Dispatchers.IO) {
                                    if (!mapUpdate.map.overlays.contains(existedTile.overlay)) {
                                        mapUpdate.map.overlays.add(0, existedTile.overlay)
                                        mapUpdate.map.invalidate()
                                    }
                                }
                                return@withContext
                            } else if (existedTile != null) {
                                // tile is rendered but changed (need to re-render)
                                Timber.tag(TAG).d("Tile exists but changed")
                                mapUpdate.map.overlays.remove(existedTile.overlay)
                                tilesState.tiles.remove(tile)
                            }
                            Timber.tag(TAG).d("Rendering tile with ${positionsForTile.size} points")
                            val bitmap = HeatMapBitmapFactory.generateTileGradientBitmapFastSeamless(
                                positionsAll = positionsForTile,
                                coreTile = tile,
                                widthPxCore = 300,
                                renderPaddingMeters = PADDING_METERS,
                                debugBorderPx = 0,
                                colorMode = HeatMapBitmapFactory.ColorMode.RSSI,
                            )

                            yield()
                            val heatmapOverlay = GroundOverlay()
                            heatmapOverlay.setImage(bitmap)
                            heatmapOverlay.transparency = 0.3f
                            heatmapOverlay.setPosition(tile.topLeft.toGeoPoint(), tile.bottomRight.toGeoPoint())
                            withContext(Dispatchers.Main) {
                                mapUpdate.map.overlays.add(0, heatmapOverlay)
                                mapUpdate.map.invalidate()
                            }
                            tilesState.tiles[tile] = TilesData(tile, heatmapOverlay, positionsForTile)
                        }
                    }
                }

                Timber.tag(TAG).d("All tiles rendered")
                loadingJob.cancel()
            }
        } else {
            mapUpdate.map.overlays.removeAll { it is GroundOverlay }
        }
        viewModel.loadingHeatmap = false
        mapUpdate.map.invalidate()
    }

    private fun updateMapCamera(mapUpdate: MapUpdate) {
        when (val cameraConfig = mapUpdate.cameraState) {
            is DeviceDetailsViewModel.MapCameraState.SinglePoint -> {
                Timber.d(cameraConfig.toString())
                val point = GeoPoint(cameraConfig.location.lat, cameraConfig.location.lng)
                mapUpdate.map.controller.animateTo(
                    point,
                    cameraConfig.zoom,
                    if (cameraConfig.withAnimation) MapConfig.MAP_ANIMATION else MapConfig.MAP_NO_ANIMATION
                )
                mapUpdate.map.invalidate()
            }

            is DeviceDetailsViewModel.MapCameraState.MultiplePoints -> {
                Timber.d(cameraConfig.toString())
                mapUpdate.map.post {
                    mapUpdate.map.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(cameraConfig.points.map { GeoPoint(it.lat, it.lng) }),
                        cameraConfig.withAnimation,
                        mapUpdate.map.context.dpToPx(16f),
                        MapConfig.MAX_MAP_ZOOM,
                        MapConfig.MAP_ANIMATION,
                    )
                }
                mapUpdate.map.invalidate()
            }
        }
    }

    private fun refreshMap(
        mapUpdate: MapUpdate,
        batchProcessor: AsyncBatchProcessor<LocationModel, MapView>,
        mapColorScheme: MapColorScheme,
        pointsStyle: DeviceDetailsViewModel.PointsStyle,
    ) {
        when (pointsStyle) {
            DeviceDetailsViewModel.PointsStyle.MARKERS -> {
                batchProcessor.process(mapUpdate.points, mapUpdate.map)
            }

            DeviceDetailsViewModel.PointsStyle.PATH -> {
                batchProcessor.cancel()
                mapUpdate.map.overlays.clearPoints()
                val points = mapUpdate.points.map { it.toGeoPoint() }
                val polyline = Polyline(mapUpdate.map).apply {
                    this.setPoints(points)
                    this.outlinePaint.apply {
                        color = mapColorScheme.lineColor.toArgb()
                    }
                }

                mapUpdate.map.overlays.add(polyline)

                val pt = SimplePointTheme(points)

                val paint = Paint().apply {
                    style = Paint.Style.FILL
                    setColor(mapColorScheme.pointColor.toArgb())
                }

                val fastPointOverlayOptions = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setPointStyle(paint)
                    .setRadius(5f)

                val fastPointOverlay = SimpleFastPointOverlay(pt, fastPointOverlayOptions)
                mapUpdate.map.overlays.add(fastPointOverlay)
                mapUpdate.map.overlays.bringBestFitToTop()
                mapUpdate.map.invalidate()
            }

            DeviceDetailsViewModel.PointsStyle.HIDE_MARKERS -> {
                batchProcessor.cancel()
                // Strip per-detection markers but leave the best-fit pin (clearPoints already
                // spares BestFitMarker + the heatmap GroundOverlay). Re-assert z-order so the
                // best-fit ends up on top of the heatmap rather than under it.
                mapUpdate.map.overlays.clearPoints()
                mapUpdate.map.overlays.bringBestFitToTop()
                mapUpdate.map.invalidate()
            }
        }
    }

    private fun MutableList<Overlay>.clearPoints() {
        // Keep the heatmap (GroundOverlay) and the best-fit estimate marker (BestFitMarker).
        // Both have their own LaunchedEffect-driven update paths and shouldn't be torn down by
        // the per-detection-marker refresh.
        this.removeAll { it !is GroundOverlay && it !is BestFitMarker }
    }
}