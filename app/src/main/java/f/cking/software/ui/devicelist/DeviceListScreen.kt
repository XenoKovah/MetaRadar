package f.cking.software.ui.devicelist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import com.google.accompanist.flowlayout.FlowRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanpra.composematerialdialogs.rememberMaterialDialogState
import f.cking.software.R
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.ui.filter.FilterUiMapper
import f.cking.software.ui.filter.SelectFilterTypeScreen
import f.cking.software.utils.graphic.ContentPlaceholder
import f.cking.software.utils.graphic.DeviceListItem
import f.cking.software.utils.graphic.Divider
import f.cking.software.utils.graphic.FABSpacer
import f.cking.software.utils.graphic.RadarIcon
import f.cking.software.utils.graphic.RoundedBox
import f.cking.software.utils.graphic.ThemedDialog
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
object DeviceListScreen {

    @Composable
    fun Screen() {
        val modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxSize()
        val viewModel: DeviceListViewModel = koinViewModel()

        val appliedFilter by viewModel.appliedFilter.collectAsState()
        if (viewModel.devicesViewState.isEmpty() && !viewModel.isSearchMode && appliedFilter.isEmpty() && viewModel.currentBatchViewState == null) {
            ContentPlaceholder(stringResource(R.string.device_list_placeholder), modifier)
            if (viewModel.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            DevicesListContent(modifier, viewModel)
        }
    }

    @Composable
    fun DevicesListContent(modifier: Modifier, viewModel: DeviceListViewModel) {
        val focusManager = LocalFocusManager.current
        val state = rememberLazyListState()
        val nestedScroll = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    focusManager.clearFocus(true)
                    return super.onPreScroll(available, source)
                }
            }
        }
        LazyColumn(
            modifier = modifier.nestedScroll(nestedScroll),
            state = state,
        ) {
            stickyHeader {
                Box() {
                    Filters(viewModel)
                    if (viewModel.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item(contentType = ListContentType.BACKGROUND_PERMISSION_WARNING, key = "background_permission_warning") {
                Spacer(modifier = Modifier.height(8.dp))
                AnimatedVisibility(
                    modifier = Modifier.animateItem(),
                    visible = viewModel.showBackgroundPermissionWarning,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    BackgroundLocationWarning(viewModel)
                }
            }

            item(contentType = ListContentType.CURRENT_BATCH, key = "current_batch") {
                AnimatedVisibility(
                    modifier = Modifier.animateItem(),
                    visible = viewModel.currentBatchViewState != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        CurrentBatch(viewModel)
                    }
                }
            }

            val devices = viewModel.devicesViewState

            // Key on the BLE address (stable per device across batches) instead of `"device_${devices[it]}"`,
            // which interpolated DeviceData.toString() — every batch produced fresh data-class instances,
            // so Compose saw new keys and re-laid-out every visible item. With a stable key, only changed
            // rows re-render. Critical at N>1000.
            items(devices.size, key = { devices[it].address }, contentType = { ListContentType.DEVICE}) { index ->
                val deviceData = devices[index]
                    DeviceListItem(
                        modifier = Modifier.animateItem(),
                        device = deviceData,
                        onClick = { viewModel.onDeviceClick(deviceData) },
                    )

                val showDivider = devices.getOrNull(index + 1)?.lastDetectTimeMs != deviceData.lastDetectTimeMs
                if (showDivider) {
                    Divider(Modifier.animateItem())
                }
            }

            if (viewModel.isPaginationEnabled) {
                item(contentType = ListContentType.PAGINATION_PROGRESS) {
                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(), contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            item(contentType = ListContentType.BOTTOM_SPACER) {
                FABSpacer()
            }
        }
    }

    enum class ListContentType {
        CURRENT_BATCH, DEVICE, PAGINATION_PROGRESS, BOTTOM_SPACER, BACKGROUND_PERMISSION_WARNING
    }

    @Composable
    fun CurrentBatch(
        viewModel: DeviceListViewModel,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        RoundedBox(internalPaddings = 0.dp) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(16.dp))
                RadarIcon()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.current_batch_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!viewModel.currentBatchViewState.isNullOrEmpty()) {
                    ExpandIcon(viewModel)
                    SortByIcon(viewModel)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                CurrentBatchList(viewModel)
            }
        }
    }

    @Composable
    private fun ExpandIcon(viewModel: DeviceListViewModel) {
        val state = viewModel.activeScannerExpandedState
        val icon = when (state) {
            DeviceListViewModel.ActiveScannerExpandedState.COLLAPSED -> painterResource(id = R.drawable.ic_show_more)
            DeviceListViewModel.ActiveScannerExpandedState.EXPANDED -> painterResource(id = R.drawable.ic_show_less)
        }
        IconButton(onClick = { viewModel.activeScannerExpandedState = state.next() }) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    @Composable
    private fun SortByIcon(viewModel: DeviceListViewModel) {
        val sortByDialog = rememberMaterialDialogState()
        ThemedDialog(sortByDialog) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.sort_by_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp
                )
                DeviceListViewModel.CurrentBatchSortingStrategy.entries.forEach { strategy ->
                    fun selectStrategy() {
                        viewModel.applyCurrentBatchSortingStrategy(strategy)
                        sortByDialog.hide()
                    }
                    Box(modifier = Modifier.clickable { selectStrategy() }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = viewModel.currentBatchSortingStrategy == strategy, onCheckedChange = { selectStrategy() })
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(id = strategy.displayNameRes), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }


        IconButton(onClick = { sortByDialog.show() }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_sort),
                contentDescription = stringResource(R.string.sort_by_title),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    @OptIn(ExperimentalAnimationGraphicsApi::class)
    @Composable
    fun rememberAnimatedVectorPainterCompat(image: AnimatedImageVector, atEnd: Boolean): Painter {
        val animatedPainter = rememberAnimatedVectorPainter(image, atEnd)
        val animatedPainter2 = rememberAnimatedVectorPainter(image, !atEnd)
        return if (atEnd) {
            animatedPainter
        } else {
            animatedPainter2
        }
    }

    @Composable
    private fun CurrentBatchList(viewModel: DeviceListViewModel) {
        val currentBatch = viewModel.currentBatchViewState.orEmpty()
        val mode = viewModel.activeScannerExpandedState
        val visibleDevices = when (mode) {
            DeviceListViewModel.ActiveScannerExpandedState.COLLAPSED -> currentBatch.take(DeviceListViewModel.ActiveScannerExpandedState.MAX_DEVICES_COUNT)
            // Cap EXPANDED so a 2000-device batch doesn't try to compose 2000 rows inside a
            // single LazyColumn item (Compose can't virtualise within an item). Cap is also
            // surfaced to the user via the footer when it kicks in.
            DeviceListViewModel.ActiveScannerExpandedState.EXPANDED -> currentBatch.take(DeviceListViewModel.ActiveScannerExpandedState.MAX_DEVICES_COUNT_EXPANDED)
        }
        if (currentBatch.isNotEmpty()) {
            visibleDevices.forEachIndexed { index, deviceData ->
                DeviceListItem(
                    device = deviceData,
                    showSignalData = true,
                    showLastUpdate = false,
                    onClick = { viewModel.onDeviceClick(deviceData) },
                )
                if (index < visibleDevices.lastIndex) {
                    Divider()
                }
            }
            if (mode == DeviceListViewModel.ActiveScannerExpandedState.COLLAPSED
                && visibleDevices.size < currentBatch.size
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.activeScannerExpandedState = DeviceListViewModel.ActiveScannerExpandedState.EXPANDED
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.active_mode_show_all, currentBatch.size.toString()),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(painter = painterResource(id = R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                }
            } else if (mode == DeviceListViewModel.ActiveScannerExpandedState.EXPANDED
                && visibleDevices.size < currentBatch.size
            ) {
                // Hit the EXPANDED cap. Tell the user the panel is showing a slice and steer
                // them at the search/filter chips to narrow down further.
                Text(
                    text = stringResource(R.string.active_mode_capped, visibleDevices.size, currentBatch.size),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Light,
                )
            }
        } else {
            val text = if (viewModel.areFiltersApplied) {
                stringResource(R.string.current_batch_empty_filtered)
            } else {
                stringResource(R.string.current_batch_empty)
            }
            Text(
                modifier = Modifier.padding(16.dp),
                text = text,
                fontWeight = FontWeight.Light,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    @Composable
    private fun BackgroundLocationWarning(viewModel: DeviceListViewModel) {
        RoundedBox(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.background_location_restricted_content),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(modifier = Modifier.weight(1f), onClick = viewModel::onBackgraundLocationWarningClick) {
                    Text(text = stringResource(R.string.background_location_restricted_button), color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    modifier = Modifier.weight(1f), onClick = viewModel::onHideBackgroundLocationWarningClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text(text = stringResource(R.string.background_location_hide_button), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    @Composable
    private fun Filters(viewModel: DeviceListViewModel) {
        Surface(shadowElevation = 4.dp) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .fillMaxWidth()
            ) {
                val appliedFilter by viewModel.appliedFilter.collectAsState()

                // FlowRow lets the chip set wrap onto multiple lines as it grows past one
                // screen width, instead of scrolling horizontally off the side. With the four
                // default quick filters (BTC / GATT / Not Apple / Not Samsung) plus Clear,
                // Search, and the trailing add-custom-chip, this spans 2 rows out of the box;
                // adding custom filters extends to 3+ as needed.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    mainAxisSpacing = 8.dp,
                    crossAxisSpacing = 1.dp,
                ) {
                    val allFilters = (viewModel.quickFilters + appliedFilter).toSet()

                    ClearAllChip(viewModel)
                    SearchChip(viewModel)

                    allFilters.forEach { holder ->
                        val isSelected = appliedFilter.contains(holder)
                        val isCustom = viewModel.isCustomFilter(holder)
                        val customFilterName = stringResource(R.string.custom_filter)

                        // Pin chip height below Material 3's 32.dp min so the row-to-row pitch
                        // matches the user's "25% of current vertical distance" ask. The
                        // crossAxisSpacing knob alone moved the inter-row gap by only a few
                        // dp; the bigger contributor is the chip's own internal vertical
                        // padding around its 32.dp min-height.
                        FilterChip(
                            modifier = Modifier.height(24.dp),
                            // Quick filters (BTC / GATT / Not Apple / Not Samsung): tap toggles
                            // selection, showing a trash icon in the leading slot when active
                            // so the user knows another tap will remove it.
                            // Custom filters: tap opens the editor with the existing
                            // filter state, preserving the chip's position via
                            // [replaceFilter]. Deletion lives in the editor's top-bar
                            // trash icon ([removeFilter]) so click-to-delete and
                            // click-to-edit don't compete for the same gesture.
                            onClick = {
                                if (isCustom) {
                                    viewModel.router.navigate(
                                        ScreenNavigationCommands.OpenCreateFilterScreen(
                                            initialFilterState = FilterUiMapper.mapToUi(holder.filter),
                                            onConfirm = { edited ->
                                                viewModel.replaceFilter(
                                                    old = holder,
                                                    new = DeviceListViewModel.FilterHolder(
                                                        displayName = customFilterName,
                                                        filter = edited,
                                                    ),
                                                )
                                            },
                                            onDelete = { viewModel.removeFilter(holder) },
                                        )
                                    )
                                } else {
                                    viewModel.onFilterClick(holder)
                                }
                            },
                            // Pass null when the chip has no icon to draw, so FilterChip skips
                            // the leading-icon slot entirely instead of reserving icon-slot
                            // padding around an empty composable. Drops a few more dp from the
                            // chip's footprint without changing visual semantics.
                            leadingIcon = when {
                                isCustom -> ({
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.edit),
                                        modifier = Modifier.size(16.dp),
                                    )
                                })
                                isSelected -> ({
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        modifier = Modifier.size(16.dp),
                                    )
                                })
                                else -> null
                            },
                            selected = isSelected,
                            label = {
                                Text(text = holder.displayName)
                            }
                        )
                    }

                    AddFilterChip(viewModel)
                }

                if (viewModel.isSearchMode) {
                    SearchStr(viewModel)
                }
            }
        }
    }

    @Composable
    private fun AddFilterChip(viewModel: DeviceListViewModel) {

        val filterName = stringResource(R.string.custom_filter)

        val selectFilterDialog = rememberMaterialDialogState()
        SelectFilterTypeScreen.Dialog(selectFilterDialog) { initialFilter ->
            viewModel.router.navigate(
                ScreenNavigationCommands.OpenCreateFilterScreen(
                    initialFilterState = initialFilter,
                    onConfirm = { filter ->
                        val filterHolder = DeviceListViewModel.FilterHolder(
                            displayName = filterName,
                            filter = filter,
                        )
                        viewModel.onFilterClick(filterHolder)
                    },
                )
            )
        }

        SuggestionChip(
            modifier = Modifier.height(24.dp),
            icon = {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp))
            },
            onClick = { selectFilterDialog.show() },
            label = {
                Text(text = stringResource(R.string.add_filter))
            }
        )
    }

    @Composable
    private fun SearchChip(viewModel: DeviceListViewModel) {
        val searchQuery by viewModel.searchQuery.collectAsState()
        FilterChip(
            modifier = Modifier.height(24.dp),
            leadingIcon = {
                val icon = if (viewModel.isSearchMode) Icons.Filled.Delete else Icons.Filled.Search
                Icon(icon, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp))
            },
            onClick = { viewModel.onOpenSearchClick() },
            selected = viewModel.isSearchMode,
            label = {
                Text(text = searchQuery?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search))
            }
        )
    }

    @Composable
    private fun ClearAllChip(viewModel: DeviceListViewModel) {
        // Red destructive chip — wipes every device row from the database. Behind a confirm
        // dialog because it can't be undone.
        val dialogState = rememberMaterialDialogState()
        ThemedDialog(
            dialogState = dialogState,
            buttons = {
                negativeButton(
                    text = stringResource(R.string.cancel),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                ) { dialogState.hide() }
                positiveButton(
                    text = stringResource(R.string.confirm),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.error),
                ) {
                    dialogState.hide()
                    viewModel.onClearAllDevicesConfirmed()
                }
            },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.clear_all_devices_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(R.string.clear_all_devices_subtitle))
            }
        }
        SuggestionChip(
            modifier = Modifier.height(24.dp),
            onClick = { dialogState.show() },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            border = null,
            icon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.clear),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
            label = { Text(text = stringResource(R.string.clear)) },
        )
    }

    @Composable
    private fun SearchStr(viewModel: DeviceListViewModel) {
        val searchQuery by viewModel.searchQuery.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val focusRequest = remember { FocusRequester() }
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusTarget()
                    .focusRequester(focusRequest),
                value = searchQuery.orEmpty(),
                onValueChange = { viewModel.onSearchInput(it) },
                placeholder = { Text(text = stringResource(R.string.search_query), fontWeight = FontWeight.Light) },
                trailingIcon = {
                    if (searchQuery.isNullOrBlank()) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.close_search),
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { viewModel.onOpenSearchClick() }
                        )
                    } else {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.clear_search),
                            modifier = Modifier
                                .size(24.dp)
                                .clickable { viewModel.onSearchInput("") }
                        )
                    }
                }
            )
            LaunchedEffect(Unit) {
                focusRequest.requestFocus()
            }
        }
    }
}