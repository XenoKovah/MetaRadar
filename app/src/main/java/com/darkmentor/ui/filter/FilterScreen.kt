package com.darkmentor.ui.filter

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import com.google.accompanist.flowlayout.FlowRow
import com.darkmentor.domain.model.ExtendedAddressInfo
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanpra.composematerialdialogs.rememberMaterialDialogState
import com.darkmentor.R
import com.darkmentor.dateTimeFormat
import com.darkmentor.dateTimeStringFormat
import com.darkmentor.toLocalTime
import com.darkmentor.toMilliseconds
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.utils.graphic.ClickableField
import com.darkmentor.utils.graphic.RoundedBox
import com.darkmentor.utils.graphic.TagChip
import com.darkmentor.utils.graphic.rememberDateDialog
import com.darkmentor.utils.graphic.rememberTimeDialog
import com.darkmentor.utils.navigation.Router
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object FilterScreen {

    @Composable
    fun Filter(
        filterState: FilterUiState,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        when (filterState) {
            is FilterUiState.All -> FilterAll(filterState, router, onDeleteClick)
            is FilterUiState.Any -> FilterAny(filterState, router, onDeleteClick)
            is FilterUiState.Not -> FilterNot(filterState, router, onDeleteClick)
            is FilterUiState.Name -> FilterName(filterState, onDeleteClick)
            is FilterUiState.Address -> FilterAddress(router, filterState, onDeleteClick)
            is FilterUiState.IsPaired -> FilterIsPaired(filterState, onDeleteClick)
            is FilterUiState.AddressType -> FilterAddressType(filterState, onDeleteClick)
            is FilterUiState.Manufacturer -> FilterManufacturer(router, filterState, onDeleteClick)
            is FilterUiState.LastDetectionInterval -> FilterLastDetectionInterval(filterState, onDeleteClick)
            is FilterUiState.FirstDetectionInterval -> FilterFirstDetectionInterval(filterState, onDeleteClick)
            is FilterUiState.DeviceLocation -> FilterDeviceLocation(filterState, router, onDeleteClick)
            is FilterUiState.UserLocation -> FilterUserLocation(filterState, router, onDeleteClick)
            is FilterUiState.Unknown, is FilterUiState.Interval -> FilterUnknown(filterState, onDeleteClick)
        }
    }

    @Composable
    private fun FilterUnknown(filter: FilterUiState, onDeleteClick: (child: FilterUiState) -> Unit) {
        FilterBase(
            title = stringResource(R.string.filter_unknown),
            color = colorResource(R.color.filter_unknown),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Text(text = stringResource(R.string.filter_unknown_title))
        }
    }

    @Composable
    private fun FilterName(
        filter: FilterUiState.Name,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_name),
            color = colorResource(R.color.filter_name),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Column {
                TextField(
                    value = filter.name,
                    singleLine = true,
                    onValueChange = { filter.name = it },
                    placeholder = { Text(text = stringResource(R.string.placeholder_device_name)) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(R.string.ignore_case))
                    Checkbox(checked = filter.ignoreCase, onCheckedChange = {
                        filter.ignoreCase = it
                    })
                }
            }
        }
    }

    @Composable
    private fun FilterAddress(
        router: Router,
        filter: FilterUiState.Address,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_address),
            color = colorResource(R.color.filter_address),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                TextField(
                    modifier = Modifier.weight(1f),
                    value = filter.address,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.filter_by_address_disclaimer)) },
                    onValueChange = { filter.address = it },
                    placeholder = { Text(text = "00:00:00:00:00:00") },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = {
                    router.navigate(ScreenNavigationCommands.OpenSelectDeviceScreen { device ->
                        filter.address = device.address
                    })
                }) {
                    Icon(imageVector = Icons.Filled.List, contentDescription = stringResource(R.string.select_device), tint = Color.Black)
                }
            }
        }
    }

    @Composable
    private fun FilterManufacturer(
        router: Router,
        filter: FilterUiState.Manufacturer,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_manufacturer),
            color = colorResource(R.color.filter_manufacturer),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }) {
            val name: String? = filter.manufacturer?.name
            val label = if (name == null) stringResource(R.string.tap_to_select) else null

            ClickableField(
                text = name,
                placeholder = stringResource(R.string.select),
                label = label,
            ) {
                router.navigate(ScreenNavigationCommands.OpenSelectManufacturerScreen { manufacturer ->
                    filter.manufacturer = manufacturer
                })
            }
        }
    }

    @Composable
    private fun FilterFirstDetectionInterval(
        filter: FilterUiState.FirstDetectionInterval,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_first_detection_period),
            color = colorResource(R.color.filter_first_seen),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            TimeInterval(filter)
        }
    }

    @Composable
    private fun FilterLastDetectionInterval(
        filter: FilterUiState.LastDetectionInterval,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_last_detection_period),
            color = colorResource(R.color.filter_last_seen),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            TimeInterval(filter)
        }
    }

    @Composable
    private fun TimeInterval(filter: FilterUiState.Interval) {

        val dateFormat = "dd MMM yyyy"
        val timeFormat = "HH:mm"

        val fromDateStr: String? = filter.fromDate?.dateTimeFormat(dateFormat)
        val fromTimeStr: String? = filter.fromTime?.dateTimeFormat(timeFormat)
        val toDateStr: String? = filter.toDate?.dateTimeFormat(dateFormat)
        val toTimeStr: String? = filter.toTime?.dateTimeFormat(timeFormat)

        val fromDateDialog = rememberDateDialog(filter.fromDate ?: LocalDate.now()) { date ->
            filter.fromDate = date
        }
        val fromTimeDialog = rememberTimeDialog(filter.fromTime ?: LocalTime.now()) { date ->
            filter.fromTime = date
        }
        val toDateDialog = rememberDateDialog(filter.toDate ?: LocalDate.now()) { date ->
            filter.toDate = date
        }
        val toTimeDialog = rememberTimeDialog(filter.toTime ?: LocalTime.now()) { date ->
            filter.toTime = date
        }

        Column {
            Row {
                val fromDatePlaceholder = stringResource(R.string.from_date)
                val fromTimePlaceholder = stringResource(R.string.from_time)
                ClickableField(
                    modifier = Modifier.weight(1f),
                    text = fromDateStr,
                    placeholder = fromDatePlaceholder,
                    label = if (fromDateStr != null) fromDatePlaceholder else null
                ) { fromDateDialog.show() }
                Spacer(modifier = Modifier.width(2.dp))
                ClickableField(
                    modifier = Modifier.weight(1f),
                    text = fromTimeStr,
                    placeholder = fromTimePlaceholder,
                    label = if (fromTimeStr != null) fromTimePlaceholder else null
                ) { fromTimeDialog.show() }
                Spacer(modifier = Modifier.width(2.dp))
                ClearIcon {
                    filter.fromDate = null
                    filter.fromTime = null
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                val toDatePlaceholder = stringResource(R.string.to_date)
                val toTimePlaceholder = stringResource(R.string.to_time)
                ClickableField(
                    modifier = Modifier.weight(1f),
                    text = toDateStr,
                    placeholder = toDatePlaceholder,
                    label = if (toDateStr != null) toDatePlaceholder else null,
                ) { toDateDialog.show() }
                Spacer(modifier = Modifier.width(2.dp))
                ClickableField(
                    modifier = Modifier.weight(1f),
                    text = toTimeStr,
                    placeholder = toTimePlaceholder,
                    label = if (toTimeStr != null) toTimePlaceholder else null,
                ) { toTimeDialog.show() }
                Spacer(modifier = Modifier.width(2.dp))
                ClearIcon {
                    filter.toDate = null
                    filter.toTime = null
                }
            }
        }
    }

    @Composable
    private fun ClearIcon(modifier: Modifier = Modifier, action: () -> Unit) {
        IconButton(modifier = modifier, onClick = action) {
            Icon(imageVector = Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
        }
    }

    @Composable
    private fun FilterIsPaired(
        filter: FilterUiState.IsPaired,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_is_paired),
            color = colorResource(R.color.filter_lost_time),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.is_paired))
                Checkbox(checked = filter.isPaired, onCheckedChange = {
                    filter.isPaired = it
                })
            }
        }
    }

    @Composable
    private fun FilterAddressType(
        filter: FilterUiState.AddressType,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_by_address_type),
            color = colorResource(R.color.filter_address),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            // Pair each enum value with the same short label that appears as the chip on a
            // device row, so the filter UI reads as "match anything tagged Static / NRPA / …".
            // INVALID is intentionally omitted — devices we couldn't classify shouldn't be
            // selectable as a target type.
            val choices = listOf(
                ExtendedAddressInfo.BleAddressType.PUBLIC to R.string.address_type_public_tag,
                ExtendedAddressInfo.BleAddressType.STATIC_RANDOM to R.string.address_type_random_static_tag,
                ExtendedAddressInfo.BleAddressType.RESOLVABLE_PRIVATE to R.string.address_type_resolvable_private_tag,
                ExtendedAddressInfo.BleAddressType.NON_RESOLVABLE_PRIVATE to R.string.address_type_non_resolvable_tag,
            )
            FlowRow(modifier = Modifier.fillMaxWidth(), mainAxisSpacing = 8.dp, crossAxisSpacing = 4.dp) {
                choices.forEach { (type, labelRes) ->
                    val name = type.name
                    val selected = name in filter.selectedTypeNames
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = {
                            filter.selectedTypeNames =
                                if (selected) filter.selectedTypeNames - name
                                else filter.selectedTypeNames + name
                        },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }
        }
    }

    @Composable
    private fun FilterAll(
        filter: FilterUiState.All,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        val selectFilterDialog = rememberMaterialDialogState()
        SelectFilterTypeScreen.Dialog(selectFilterDialog) { newFilter ->
            filter.filters = filter.filters + listOf(newFilter)
        }

        FilterGroup(
            title = stringResource(R.string.filter_all_of),
            color = colorResource(R.color.filter_all),
            addText = stringResource(R.string.add_filter),
            addClick = { selectFilterDialog.show() },
            onDeleteClick = { onDeleteClick.invoke(filter) }
        ) {
            filter.filters.forEach {
                Filter(filterState = it, router = router, onDeleteClick = filter::delete)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    @Composable
    private fun FilterDeviceLocation(
        filter: FilterUiState.DeviceLocation,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_device_location),
            color = colorResource(R.color.filter_device_location),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Column {
                RoundedBox(modifier = Modifier.fillMaxWidth(), internalPaddings = 0.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                router.navigate(ScreenNavigationCommands.OpenSelectLocationScreen(
                                    initialLocationModel = filter.targetLocation,
                                    initialRadius = filter.radius,
                                ) { location, radiusMeters ->
                                    filter.targetLocation = location
                                    filter.radius = radiusMeters
                                })
                            }
                    ) {
                        val locationText = if (filter.targetLocation != null) {
                            stringResource(R.string.filter_location_has_data, filter.radius)
                        } else {
                            stringResource(R.string.filter_location_no_data)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Text(modifier = Modifier.weight(1f), text = locationText)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = locationText,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                TimeInterval(filter = filter)
            }
        }
    }

    @Composable
    private fun FilterUserLocation(
        filter: FilterUiState.UserLocation,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        FilterBase(
            title = stringResource(R.string.filter_user_location),
            color = colorResource(R.color.filter_user_location),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) }
        ) {
            Column {
                RoundedBox(modifier = Modifier.fillMaxWidth(), internalPaddings = 0.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                router.navigate(ScreenNavigationCommands.OpenSelectLocationScreen(
                                    initialLocationModel = filter.targetLocation,
                                    initialRadius = filter.radius,
                                ) { location, radiusMeters ->
                                    filter.targetLocation = location
                                    filter.radius = radiusMeters
                                })
                            }
                    ) {
                        val locationText = if (filter.targetLocation != null) {
                            stringResource(R.string.filter_location_has_data, filter.radius)
                        } else {
                            stringResource(R.string.filter_location_no_data)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Text(modifier = Modifier.weight(1f), text = locationText)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_location),
                                contentDescription = locationText,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { filter.defaultValueIfNoLocation = !filter.defaultValueIfNoLocation },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(modifier = Modifier.weight(1f), text = stringResource(R.string.filter_user_location_if_no_location))
                    Spacer(modifier = Modifier.width(4.dp))
                    Checkbox(
                        checked = filter.defaultValueIfNoLocation,
                        onCheckedChange = {
                            filter.defaultValueIfNoLocation = it
                        })
                    Spacer(modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun FilterAny(
        filter: FilterUiState.Any,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        val selectFilterDialog = rememberMaterialDialogState()
        SelectFilterTypeScreen.Dialog(selectFilterDialog) { newFilter ->
            filter.filters = filter.filters + listOf(newFilter)
        }

        FilterGroup(
            title = stringResource(R.string.filter_any_of),
            color = colorResource(R.color.filter_any),
            addText = stringResource(R.string.add_filter),
            addClick = { selectFilterDialog.show() },
            onDeleteClick = { onDeleteClick.invoke(filter) }
        ) {
            filter.filters.forEach {
                Filter(filterState = it, router = router, onDeleteClick = filter::delete)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    @Composable
    private fun FilterNot(
        filter: FilterUiState.Not,
        router: Router,
        onDeleteClick: (child: FilterUiState) -> Unit,
    ) {
        val selectFilterDialog = rememberMaterialDialogState()
        SelectFilterTypeScreen.Dialog(selectFilterDialog) { newFilter ->
            filter.filter = newFilter
        }

        FilterBase(
            title = stringResource(R.string.filter_not),
            color = colorResource(R.color.filter_not),
            onDeleteButtonClick = { onDeleteClick.invoke(filter) },
        ) {
            if (filter.filter != null) {
                Filter(filter.filter!!, router = router, onDeleteClick = filter::delete)
            } else {
                SuggestionChip(
                    onClick = { selectFilterDialog.show() },
                    icon = {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.add_filter))
                    },
                    label = { Text(text = stringResource(R.string.select)) },
                )
            }
        }
    }

    @Composable
    private fun FilterBase(
        title: String,
        color: Color,
        onDeleteButtonClick: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        val minimize = remember { mutableStateOf(false) }
        val backgroundShape = RoundedCornerShape(8.dp)
        Column(
            Modifier
                .background(color, shape = backgroundShape)
                .border(width = 1.dp, color = Color.Black, backgroundShape)
                .clip(backgroundShape)
        ) {
            Box(Modifier.clickable { minimize.value = !minimize.value }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    val icon = if (!minimize.value) painterResource(R.drawable.ic_drop_down) else painterResource(R.drawable.ic_drop_up)
                    Icon(
                        painter = icon,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDeleteButtonClick) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (!minimize.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    content.invoke()
                }
            }
        }
    }

    @Composable
    private fun FilterGroup(
        title: String,
        color: Color,
        addText: String,
        addClick: () -> Unit,
        onDeleteClick: () -> Unit,
        content: @Composable () -> Unit
    ) {
        FilterBase(title = title, color = color, onDeleteClick) {
            Column {
                content.invoke()
                Spacer(modifier = Modifier.height(4.dp))
                SuggestionChip(
                    onClick = addClick,
                    icon = {
                        Icon(imageVector = Icons.Default.Add, contentDescription = addText)
                    },
                    label = { Text(text = addText) },
                )
            }
        }
    }
}