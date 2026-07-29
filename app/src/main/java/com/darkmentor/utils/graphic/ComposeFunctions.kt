package com.darkmentor.utils.graphic

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vanpra.composematerialdialogs.MaterialDialog
import com.vanpra.composematerialdialogs.MaterialDialogButtons
import com.vanpra.composematerialdialogs.MaterialDialogScope
import com.vanpra.composematerialdialogs.MaterialDialogState
import com.vanpra.composematerialdialogs.datetime.date.DatePickerColors
import com.vanpra.composematerialdialogs.datetime.date.DatePickerDefaults
import com.vanpra.composematerialdialogs.datetime.date.datepicker
import com.vanpra.composematerialdialogs.datetime.time.TimePickerColors
import com.vanpra.composematerialdialogs.datetime.time.TimePickerDefaults
import com.vanpra.composematerialdialogs.datetime.time.timepicker
import com.vanpra.composematerialdialogs.rememberMaterialDialogState
import com.darkmentor.R
import com.darkmentor.domain.model.DeviceClass
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.ExtendedAddressInfo
import com.darkmentor.domain.model.Transport
import com.darkmentor.dpToPx
import com.darkmentor.pxToDp
import com.darkmentor.toHexString
import com.darkmentor.ui.GlobalUiState
import com.darkmentor.ui.devicelist.DeviceListScreen
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.random.Random

/**
 * Shared "what minute is it" pulse used by row composables to invalidate cached time-since
 * strings ("5 min ago"). Updates once a minute, NOT once a frame — combined with a
 * remember(...) keyed on this state's value, downstream composables re-format their human-
 * readable durations only when the clock actually ticks past a minute boundary instead of
 * on every recomposition. One ticker per process; cheap.
 *
 * Implementation note: rememberSaveable + LaunchedEffect both honour Compose lifecycle, so
 * the ticker auto-stops when no composable observes the state.
 */
@Composable
fun minuteBucketState(): androidx.compose.runtime.State<Long> {
    val state = remember { mutableStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            val msToNextMinute = 60_000L - (now % 60_000L)
            delay(msToNextMinute)
            state.value = System.currentTimeMillis() / 60_000L
        }
    }
    return state
}

@Composable
fun rememberDateDialog(
    initialDate: LocalDate = LocalDate.now(),
    datePickerColors: DatePickerColors = DatePickerDefaults.colors(
        headerBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
        headerTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        calendarHeaderTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        dateActiveBackgroundColor = MaterialTheme.colorScheme.primary,
        dateActiveTextColor = MaterialTheme.colorScheme.onPrimary,
        dateInactiveBackgroundColor = Color.Transparent,
        dateInactiveTextColor = MaterialTheme.colorScheme.onSurface,
    ),
    dateResult: (date: LocalDate) -> Unit,
): MaterialDialogState {
    val dialogState = rememberMaterialDialogState()
    ThemedDialog(
        dialogState = dialogState,
        buttons = {
            positiveButton(
                stringResource(R.string.ok),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ) { dialogState.hide() }
            negativeButton(
                stringResource(R.string.cancel),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ) { dialogState.hide() }
        },
    ) {
        datepicker(initialDate = initialDate, colors = datePickerColors) { localDate ->
            dateResult.invoke(localDate)
        }
    }
    return dialogState
}

@Composable
fun rememberTimeDialog(
    initialTime: LocalTime = LocalTime.now(),
    timePickerColors: TimePickerColors = TimePickerDefaults.colors(
        activeBackgroundColor = MaterialTheme.colorScheme.primary,
        activeTextColor = MaterialTheme.colorScheme.onPrimary,
        inactiveBackgroundColor = MaterialTheme.colorScheme.primaryContainer,
        inactiveTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        inactivePeriodBackground = Color.Transparent,
        selectorColor = MaterialTheme.colorScheme.primary,
        selectorTextColor = MaterialTheme.colorScheme.onPrimary,
        headerTextColor = MaterialTheme.colorScheme.onSurface,
        borderColor = Color.Transparent,
    ),
    dateResult: (date: LocalTime) -> Unit,
): MaterialDialogState {
    val dialogState = rememberMaterialDialogState()
    ThemedDialog(
        dialogState = dialogState,
        buttons = {
            positiveButton(
                stringResource(R.string.ok),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ) { dialogState.hide() }
            negativeButton(
                stringResource(R.string.cancel),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ) { dialogState.hide() }
        },
    ) {
        timepicker(is24HourClock = true, initialTime = initialTime, colors = timePickerColors) { localDate ->
            dateResult.invoke(localDate)
        }
    }
    return dialogState
}

@Composable
fun rememberProgressDialog(
    text: String,
): MaterialDialogState {
    val dialogState = rememberMaterialDialogState()
    ThemedDialog(
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        dialogState = dialogState,
        autoDismiss = false,
        buttons = {},
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }
    }
    return dialogState
}

@Composable
fun infoDialog(
    title: String,
    content: String?,
    buttons: ((state: MaterialDialogState) -> (@Composable MaterialDialogButtons.() -> Unit))? = null,
): MaterialDialogState {
    val dialogState = rememberMaterialDialogState()
    ThemedDialog(
        dialogState = dialogState,
        buttons = buttons?.invoke(dialogState) ?: {
            positiveButton(
                stringResource(R.string.ok),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface)
            ) { dialogState.hide() }
        },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold)
            if (!content.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = content)
            }
        }
    }
    return dialogState
}

@Composable
fun ThemedDialog(
    dialogState: MaterialDialogState = rememberMaterialDialogState(),
    properties: DialogProperties = DialogProperties(),
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    shape: Shape = MaterialTheme.shapes.medium,
    border: BorderStroke? = null,
    elevation: Dp = 24.dp,
    autoDismiss: Boolean = true,
    onCloseRequest: (MaterialDialogState) -> Unit = { it.hide() },
    buttons: @Composable MaterialDialogButtons.() -> Unit = {},
    content: @Composable MaterialDialogScope.() -> Unit
) {
    MaterialDialog(
        dialogState = dialogState,
        properties = properties,
        backgroundColor = backgroundColor,
        shape = shape,
        border = border,
        elevation = elevation,
        autoDismiss = autoDismiss,
        onCloseRequest = onCloseRequest,
        buttons = buttons,
        content = content
    )
}

@Composable
fun ClickableField(
    modifier: Modifier = Modifier,
    text: String?,
    placeholder: String?,
    label: String?,
    onClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val unfocuse = remember { mutableStateOf(false) }
    if (unfocuse.value) {
        focusManager.clearFocus(true)
        unfocuse.value = false
    }
    TextField(
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused) {
                    unfocuse.value = true
                    onClick.invoke()
                }
            },
        value = text ?: "",
        onValueChange = {},
        readOnly = true,
        label = label?.let { { Text(text = it) } },
        placeholder = placeholder?.let { { Text(text = it) } },
    )
}

@Composable
fun DeviceListItem(
    modifier: Modifier = Modifier,
    device: DeviceData,
    showSignalData: Boolean = false,
    showLastUpdate: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick.invoke() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // CoD circle-icon was removed (Devices view + Connect All) — the "AudioVideo from
            // 0x180F advertised UUID" heuristic mis-classified BLE peripherals like UVP01,
            // and the user found the icons distracting + low-information. The transport badge
            // + manufacturer line carry the actually-useful identity bits.
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    if (device.isPaired) {
                        DevicePairedIcon(true)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        modifier = Modifier.weight(1f),
                        text = device.resolvedName ?: stringResource(R.string.not_applicable),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TransportBadge(transport = device.transport)
                    if (showSignalData) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SignalData(rssi = device.rssi, distance = device.distance())
                    }
                }
                device.resolvedManufacturerName?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it)
                }
                device.manufacturerInfo?.airdrop?.let { airdrop ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = airdrop.contacts.joinToString { "0x${it.sha256.toHexString().uppercase()}" })
                }
                Spacer(modifier = Modifier.height(4.dp))
                ExtendedAddressView(device.extendedAddressInfo(), transport = device.transport)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (device.isConnectable) R.string.device_connectable else R.string.device_non_connectable
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = if (device.isConnectable) {
                        colorResource(id = R.color.green_600)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Cache the formatted "first/last detection period" string per visible row
                // and only invalidate when (a) the underlying timestamp changes or (b) the
                // current minute-bucket flips. Without this, every recomposition (filter
                // chip toggles, scroll state writes, sibling row updates) re-allocated two
                // fresh getTimePeriodStr() strings per row — at ~1000 rows on screen during
                // a fast filter change, that was several MB/s of throwaway String + Locale
                // formatter allocations on the main thread.
                val context = LocalContext.current
                val str_lifetime = R.string.lifetime_data
                val str_lifetime_with_update = R.string.lifetime_data_last_update
                val updateStr = remember(
                    device.firstDetectTimeMs,
                    device.lastDetectTimeMs,
                    showLastUpdate,
                    minuteBucketState().value,
                ) {
                    if (showLastUpdate) {
                        context.getString(
                            str_lifetime_with_update,
                            device.firstDetectionPeriod(context),
                            device.lastDetectionPeriod(context),
                        )
                    } else {
                        context.getString(
                            str_lifetime,
                            device.firstDetectionPeriod(context),
                        )
                    }
                }
                Text(
                    text = updateStr,
                    fontWeight = FontWeight.Light,
                )
            }
        }
    }
}

@Composable
fun DevicePairedIcon(isPaired: Boolean) {
    if (isPaired) {
        val color = colorResource(R.color.blue_600)
        val infoDialog = infoDialog(
            title = stringResource(id = R.string.bluetooth_status_paired),
            content = stringResource(id = R.string.bluetooth_status_paired_description),
        )
        Row(
            modifier = Modifier
                .background(color.copy(0.2f), RoundedCornerShape(20.dp))
                .clickable { infoDialog.show() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.bluetooth_status_paired),
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun DeviceTypeIcon(
    modifier: Modifier = Modifier.size(64.dp),
    device: DeviceData,
    paddingDp: Dp = 16.dp
) {
    val icon = remember(device) { GetIconForDeviceClass.getIcon(device) }
    val color = colorByHash(device.address.hashCode())
    Icon(
        modifier = modifier
            .background(color.copy(0.2f), CircleShape)
            .padding(paddingDp),
        painter = painterResource(icon),
        contentDescription = stringResource(R.string.device_type),
        tint = color
    )
}

@Composable
fun ExtendedAddressView(
    extendedAddressInfo: ExtendedAddressInfo,
    transport: Transport = Transport.LE,
) {

    Row {
        Text(
            text = extendedAddressInfo.address,
            fontWeight = FontWeight.Light,
        )
        // BR/EDR addresses are always public per BT Core Spec, but the BLE STP semantics
        // (and warning text) don't quite fit — surface a Classic-specific BTC chip with its
        // own description so the user understands the trackability implication for the
        // actual radio they're looking at.
        val chip = if (transport == Transport.BREDR || transport == Transport.DUAL) {
            ExtendedAddressInfoChip.BTC
        } else {
            extendedAddressInfo.type.toChip()
        }
        if (chip != null) {

            val dialog = infoDialog(
                title = stringResource(id = chip.descriptionRes),
                content = stringResource(id = chip.bodyRes),
            )

            Spacer(modifier = Modifier.width(8.dp))
            val color = chip.color.invoke()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.2f))
                    .clickable { dialog.show() }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(id = chip.titleRes),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    modifier = Modifier
                        .size(12.dp),
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.app_info_title),
                    tint = color
                )
            }
        }
    }
}

private fun ExtendedAddressInfo.BleAddressType.toChip(): ExtendedAddressInfoChip? {
    return when (this) {
        ExtendedAddressInfo.BleAddressType.PUBLIC -> ExtendedAddressInfoChip.PUBLIC
        ExtendedAddressInfo.BleAddressType.STATIC_RANDOM -> ExtendedAddressInfoChip.RANDOM
        ExtendedAddressInfo.BleAddressType.NON_RESOLVABLE_PRIVATE -> ExtendedAddressInfoChip.NON_RESOLVABLE
        ExtendedAddressInfo.BleAddressType.RESOLVABLE_PRIVATE -> ExtendedAddressInfoChip.RESOLVABLE
        ExtendedAddressInfo.BleAddressType.INVALID -> null
    }
}

private enum class ExtendedAddressInfoChip(
    val titleRes: Int,
    val descriptionRes: Int,
    val bodyRes: Int,
    val color: @Composable () -> Color,
) {
    PUBLIC(
        titleRes = R.string.address_type_public_tag,
        descriptionRes = R.string.address_type_public_description,
        // Public is permanent — surface the "trackable" disclaimer + Android <15 caveat
        // rather than the privacy-rotation blurb that applies to the random variants.
        bodyRes = R.string.address_trackable_disclaimer,
        color = { colorResource(R.color.address_tag_stp) },
    ),
    BTC(
        titleRes = R.string.address_type_btc_tag,
        descriptionRes = R.string.address_type_btc_tag,
        bodyRes = R.string.address_type_btc_description,
        // Classic-public addresses share the same trackability concern as STP, so reuse the
        // STP colour to preserve at-a-glance consistency.
        color = { colorResource(R.color.address_tag_stp) },
    ),
    RANDOM(
        titleRes = R.string.address_type_random_static_tag,
        descriptionRes = R.string.address_type_random_static_description,
        // Random Static, like Public, is set once and never rotates — share the trackable
        // disclaimer with the PUBLIC chip so the user gets the same warning text.
        bodyRes = R.string.address_trackable_disclaimer,
        color = { colorResource(R.color.address_tag_rst) },
    ),
    NON_RESOLVABLE(
        titleRes = R.string.address_type_non_resolvable_tag,
        descriptionRes = R.string.address_type_non_resolvable_description,
        bodyRes = R.string.address_private_disclamer,
        color = { colorResource(R.color.address_tag_nrp) },
    ),
    RESOLVABLE(
        titleRes = R.string.address_type_resolvable_private_tag,
        descriptionRes = R.string.address_type_resolvable_private_description,
        bodyRes = R.string.address_private_disclamer,
        color = { colorResource(R.color.address_tag_rpa) },
    ),
}

@Composable
fun SignalData(rssi: Int?, distance: Float?) {
    Column(horizontalAlignment = Alignment.End) {
        distance?.let { distance ->
            val distanceStr = if (distance < 2) "%.1f".format(distance) else distance.toInt().toString()
            val infoDialog = infoDialog(
                title = stringResource(id = R.string.disclaimer),
                content = stringResource(id = R.string.device_distance_disclaimer)
            )
            Row(modifier = Modifier.clickable { infoDialog.show() }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.distance_to_device, distanceStr),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    // Decorative icon next to the RSSI/distance label — the surrounding text
                    // already conveys meaning to a screen reader, so the icon doesn't need its own
                    // contentDescription. (Previously misleadingly used `R.string.is_favorite`.)
                    modifier = Modifier
                        .size(16.dp)
                        .alpha(0.5f),
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        rssi?.let { rssi ->
            Text(text = stringResource(id = R.string.rssi_value, rssi), fontSize = 14.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(horizontal = 16.dp)) {
        Box(
            modifier = Modifier
                .height(1.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
        )
    }
}

@Composable
fun ContentPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter = painterResource(R.drawable.ic_wifi),
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                modifier = Modifier.size(100.dp),
                painter = icon,
                contentDescription = text,
                tint = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = text, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun RoundedBox(
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    internalPaddings: Dp = 16.dp,
    boxContent: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = modifier) {
        val shape = RoundedCornerShape(corner = CornerSize(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape)
                .clip(shape = shape)
                .padding(internalPaddings)
        ) { boxContent(this) }
    }
}

private val colorsLight = listOf(
    Color(0xFFE57373),
    Color(0xFFF06292),
    Color(0xFFBA68C8),
    Color(0xFF9575CD),
    Color(0xFF7986CB),
    Color(0xFF64B5F6),
    Color(0xFF4FC3F7),
    Color(0xFF4DD0E1),
    Color(0xFF4DB6AC),
    Color(0xFF81C784),
    Color(0xFFAED581),
    Color(0xFFFF8A65),
    Color(0xFFD4E157),
    Color(0xFFFFD54F),
    Color(0xFFFFB74D),
    Color(0xFFA1887F),
    Color(0xFF90A4AE),
)

private val colorsDark = listOf(
    Color(0xFF813535),
    Color(0xFF742A43),
    Color(0xFF5E2F66),
    Color(0xFF443066),
    Color(0xFF363E69),
    Color(0xFF2F5574),
    Color(0xFF275A70),
    Color(0xFF2D6A72),
    Color(0xFF235E58),
    Color(0xFF457047),
    Color(0xFF546D37),
    Color(0xFF885241),
    Color(0xFF6A7030),
    Color(0xFF776426),
    Color(0xFF7C643F),
    Color(0xFF7A5446),
    Color(0xFF3D545F),
)

@Composable
fun colorByHash(hash: Int): Color {
    val colors = if (isSystemInDarkTheme()) colorsDark else colorsLight
    return colors[abs(Random(hash).nextInt() % colors.size)]
}

/**
 * Small inline label showing the radio transport a device was observed on (LE / BR / Dual).
 */
@Composable
fun TransportBadge(transport: Transport) {
    val (label, container) = when (transport) {
        Transport.LE -> stringResource(R.string.transport_badge_le) to MaterialTheme.colorScheme.secondaryContainer
        Transport.BREDR -> stringResource(R.string.transport_badge_brEdr) to MaterialTheme.colorScheme.tertiaryContainer
        Transport.DUAL -> stringResource(R.string.transport_badge_dual) to MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when (transport) {
        Transport.LE -> MaterialTheme.colorScheme.onSecondaryContainer
        Transport.BREDR -> MaterialTheme.colorScheme.onTertiaryContainer
        Transport.DUAL -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .background(container, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = onContainer,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun TagChip(
    tagName: String,
    tagIcon: ImageVector? = null,
    onClick: () -> Unit = {},
) {
    AssistChip(
        colors = AssistChipDefaults.assistChipColors(
            containerColor = colorByHash(tagName.hashCode()),
            labelColor = Color.Black,
            leadingIconContentColor = Color.Black,
        ),
        border = null,
        onClick = onClick,
        leadingIcon = { tagIcon?.let { Icon(imageVector = it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) } },
        label = {
            Text(text = tagName, color = MaterialTheme.colorScheme.onSurface)
        }
    )
}

@Composable
fun dpToPx(dp: Float): Float {
    return LocalContext.current.dpToPx(dp).toFloat()
}

@Composable
fun pxToDp(px: Float): Float {
    return LocalContext.current.pxToDp(px)
}

@Composable
fun FABSpacer() {
    val bottomOffset = remember { GlobalUiState.totalOffset }
    Column {
        Spacer(modifier = Modifier.height(pxToDp(bottomOffset.value).dp))
        SystemNavbarSpacer()
    }
}

@Composable
fun SystemNavbarSpacer() {
    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
}

fun ColorScheme.surfaceEvaluated(evaluation: Dp = 3.dp): Color {
    return this.surfaceColorAtElevation(evaluation)
}

@Composable
fun Switcher(
    modifier: Modifier = Modifier,
    value: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick.invoke() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(text = title)
                subtitle?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, fontWeight = FontWeight.Light, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
                checked = value,
                onCheckedChange = { onClick.invoke() }
            )
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun RadarIcon() {
    var atEnd by remember { mutableStateOf(false) }
    val image = AnimatedImageVector.animatedVectorResource(id = R.drawable.radar_animation)
    val animatedPainter = DeviceListScreen.rememberAnimatedVectorPainterCompat(image, atEnd)
    LaunchedEffect(Unit) {
        while (true) {
            delay(image.totalDuration.toLong())
            atEnd = !atEnd
        }
    }
    Image(
        painter = animatedPainter,
        contentDescription = null,
    )
}

@Composable
fun ListItem(icon: Painter, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.Bold)
            Text(text = subtitle)
        }
    }
}
