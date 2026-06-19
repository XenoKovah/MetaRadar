package com.darkmentor.ui.devicelist

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkmentor.R
import com.darkmentor.checkRegexSafe
import com.darkmentor.collectAsState
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.DevicesRepository
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.interactor.ClearAllDevicesInteractor
import com.darkmentor.domain.interactor.filterchecker.FilterCheckerImpl
import com.darkmentor.domain.model.DeviceClass
import com.darkmentor.domain.model.DeviceData
import com.darkmentor.domain.model.DeviceFilter
import com.darkmentor.domain.model.ManufacturerInfo
import com.darkmentor.mapParallel
import com.darkmentor.service.BgScanService
import com.darkmentor.splitToBatches
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.utils.navigation.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.ext.getFullName
import java.util.concurrent.TimeUnit

class DeviceListViewModel(
    private val context: Application,
    private val devicesRepository: DevicesRepository,
    private val filterCheckerImpl: FilterCheckerImpl,
    permissionHelper: PermissionHelper,
    val router: Router,
    private val settingsRepository: SettingsRepository,
    private val intentHelper: IntentHelper,
    private val clearAllDevicesInteractor: ClearAllDevicesInteractor,
) : ViewModel() {

    var currentBatchSortingStrategy by mutableStateOf(getDefaultSortStrategy())
    var devicesViewState by mutableStateOf(emptyList<DeviceData>())
    var activeScannerExpandedState by mutableStateOf(ActiveScannerExpandedState.COLLAPSED)
    var currentBatchViewState by mutableStateOf<List<DeviceData>?>(null)
    var appliedFilter: MutableStateFlow<List<FilterHolder>> = MutableStateFlow(emptyList())
    var searchQuery: MutableStateFlow<String?> = MutableStateFlow(null)
    var isSearchMode: Boolean by mutableStateOf(false)
    var isLoading: Boolean by mutableStateOf(false)
    /**
     * Number of "Show next N" pages currently loaded into the visible list. Drives the SQL
     * `LIMIT` on every refetch, and the visibility of the footer button. Read by Compose
     * (so the button shows "Show next 50" with the configured page size); written from
     * [loadMoreDevices], the filter / search / sort handlers (reset to one page), and
     * the "limit reached" coercion.
     */
    var displayedCount: Int by mutableStateOf(PAGE_SIZE)
    /**
     * True when the most recent SQL fetch returned more rows than [displayedCount] —
     * tells the LazyColumn to render a "Show next 50" footer item. False at the
     * [DEVICE_LIST_LIMIT] cap or when the filter/search has fewer matching rows than
     * the current page.
     */
    var hasMoreRows: Boolean by mutableStateOf(false)
    var quickFilters: List<FilterHolder> by mutableStateOf(
        listOf(
            DefaultFilters.btc(context),
            DefaultFilters.dual(context),
            DefaultFilters.gatt(context),
            DefaultFilters.connectable(context),
            DefaultFilters.notApple(context),
            DefaultFilters.notSamsung(context),
        )
    )
    val showBackgroundPermissionWarning: Boolean by combine(
        permissionHelper.observeBackgroundLocationPermission(),
        settingsRepository.observeHideBackgroundLocationWarning(),
    ) { permissionGranted, hideWarningTime -> !permissionGranted && checkBackgroundWarningIsExpired(hideWarningTime) }
        .collectAsState(viewModelScope, false)
    val areFiltersApplied by combine(appliedFilter, searchQuery) { filters, query -> filters.isNotEmpty() || !query.isNullOrBlank() }
        .collectAsState(viewModelScope, false)

    private var scannerObservingJob: Job? = null
    private var lastBatchJob: Job? = null

    /**
     * Backing Flow for [displayedCount] — the SQL-side page cap. Used as a combine input
     * in [observeAllDevices] so loading more pages re-fires the query (with a wider LIMIT)
     * without resetting the existing snapshot or unsubscribing from the Room invalidation
     * tick. Filter/search changes reset this back to one page via [resetPageDepth] before
     * emitting the new filter/query, so the user always lands on page 1 when they change
     * the result set.
     */
    private val displayedCountFlow = MutableStateFlow(PAGE_SIZE)

    private fun resetPageDepth() {
        displayedCountFlow.value = PAGE_SIZE
        displayedCount = PAGE_SIZE
    }

    /**
     * Footer-button handler. Bumps [displayedCount] by [PAGE_SIZE], capped at
     * [DEVICE_LIST_LIMIT_HARD_CAP] so the Room/Compose working set stays bounded even if
     * the user keeps clicking. The combine() in [observeAllDevices] picks up the new
     * value and re-issues the SQL query at the wider LIMIT.
     */
    fun loadMoreDevices() {
        val next = (displayedCountFlow.value + PAGE_SIZE).coerceAtMost(DEVICE_LIST_LIMIT_HARD_CAP)
        displayedCountFlow.value = next
        displayedCount = next
    }

    /**
     * Latches true while the user is actively scrolling the LazyColumn — driven by the
     * Compose `LazyListState.isScrollInProgress` snapshotFlow in [DeviceListScreen]. Used by
     * [observeAllDevices] to defer expensive snapshot rebuilds until the scroll settles, so
     * Room invalidations + AppleContact joins + DeviceData allocation pressure don't
     * compete with the LazyColumn for main-thread budget mid-scroll. A 1.88s Davey + 167-
     * frame Choreographer skip on the Motorola during a 60-fling test traced back to a major
     * GC triggered by snapshot churn during scroll; gating the rebuild eliminates that.
     */
    private val isScrollingFlow = MutableStateFlow(false)
    fun setScrolling(scrolling: Boolean) {
        isScrollingFlow.value = scrolling
    }

    init {
        observeIsScannerEnabled()
    }

    fun onFilterClick(filter: FilterHolder) {
        val newFilters = appliedFilter.value.toMutableList()
        if (newFilters.contains(filter)) {
            newFilters.remove(filter)
        } else {
            newFilters.add(filter)
        }
        resetPageDepth()
        viewModelScope.launch { appliedFilter.emit(newFilters) }
    }

    /**
     * True for filter chips the user built via the "+ Add filter" affordance. Quick filters
     * (Not Apple, BTC) are baked-in and toggled by tapping the chip; custom filters open the
     * editor on tap (where the user can also delete them via the trash icon in the top bar).
     */
    fun isCustomFilter(filter: FilterHolder): Boolean = filter !in quickFilters

    /** Replace [old] with [new] in the active filter list, preserving its position. */
    fun replaceFilter(old: FilterHolder, new: FilterHolder) {
        val updated = appliedFilter.value.toMutableList()
        val idx = updated.indexOf(old)
        if (idx >= 0) updated[idx] = new else updated.add(new)
        resetPageDepth()
        viewModelScope.launch { appliedFilter.emit(updated) }
    }

    /** Remove [filter] from the active filter list. Used by the editor's top-bar trash icon. */
    fun removeFilter(filter: FilterHolder) {
        val updated = appliedFilter.value.toMutableList().also { it.remove(filter) }
        resetPageDepth()
        viewModelScope.launch { appliedFilter.emit(updated) }
    }

    fun onOpenSearchClick() {
        isSearchMode = !isSearchMode
        if (!isSearchMode) {
            resetPageDepth()
            viewModelScope.launch { searchQuery.emit(null) }
        }
    }

    fun onClearAllDevicesConfirmed() {
        viewModelScope.launch {
            clearAllDevicesInteractor.execute()
        }
    }

    fun onSearchInput(str: String) {
        resetPageDepth()
        viewModelScope.launch { searchQuery.emit(str) }
    }

    fun onDeviceClick(device: DeviceData) {
        router.navigate(ScreenNavigationCommands.OpenDeviceDetailsScreen(device.address))
    }

    private fun observeIsScannerEnabled() {
        viewModelScope.launch {
            BgScanService.observeIsActive()
                .collect { checkScreenMode(true) }
        }
    }

    private fun checkScreenMode(invalidateCurrentBatch: Boolean) {
        val isScannerEnabled = BgScanService.isActive

        scannerObservingJob?.cancel()
        scannerObservingJob = observeAllDevices()

        if (invalidateCurrentBatch) {
            lastBatchJob?.cancel()
            if (isScannerEnabled) {
                currentBatchViewState = emptyList()
                lastBatchJob = observeCurrentBatch()
            } else {
                currentBatchViewState = null
                lastBatchJob = null
            }
        }
    }

    fun onBackgraundLocationWarningClick() {
        router.navigate(ScreenNavigationCommands.OpenBackgroundLocationScreen)
    }

    fun onHideBackgroundLocationWarningClick() {
        settingsRepository.setHideBackgroundLocationWarning(System.currentTimeMillis())
    }

    private fun checkBackgroundWarningIsExpired(hideMessageTime: Long): Boolean {
        return System.currentTimeMillis() - hideMessageTime > TimeUnit.DAYS.toMillis(3)
    }

    fun applyCurrentBatchSortingStrategy(strategy: CurrentBatchSortingStrategy) {
        currentBatchSortingStrategy = strategy
        settingsRepository.setCurrentBatchSortingStrategyId(strategy.ordinal)
        currentBatchViewState = currentBatchViewState?.sortedWith(strategy.comparator)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeCurrentBatch(): Job {
        return viewModelScope.launch {
            // Sample BEFORE the expensive filter+sort, not after — combine emits on every
            // upstream change (and observeLastBatch fires every Room invalidation during
            // active scanning, several times per second), so applying the throttle to the
            // raw triple lets us collapse multiple batch arrivals into one rebuild.
            // Without this, scrolling-with-Connect-All in a dense (250+ device) environment
            // sustained ~120 MB/s of LOS allocation pressure from this one Flow alone, and
            // the heap would peg at the 192 MB cap within ~30-90 minutes → OOM crash
            // (logcat 12:17:44 on 2026-05-08, PID 4687, fixedPeriodTicker as incidental
            // allocator on a heap with <1% free).
            combine(
                appliedFilter,
                searchQuery,
                devicesRepository.observeLastBatch()
                    .onStart {
                        currentBatchViewState = emptyList()
                        devicesRepository.clearLastBatch()
                    }
            ) { filters, query, devices -> Triple(filters, query, devices) }
                .sample(VIEW_STATE_THROTTLE_MS)
                .map { (filters, query, devices) ->
                    devices
                        .withFilters(filters, query)
                        .sortedWith(currentBatchSortingStrategy.comparator)
                }
                .collect { devices ->
                    currentBatchViewState = devices
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeAllDevices(): Job {
        isLoading = true
        return viewModelScope.launch {
            // Combine filter / search / page-depth / Room-tick. Page-depth changes (user
            // tapped "Show next 50") fire just like filter changes — they re-run the SQL
            // at the wider LIMIT and emit the larger snapshot. Room-tick is observed
            // purely as a "table changed" signal; we don't consume the rows it carries
            // because the snapshot below re-queries with the current filters anyway.
            combine(
                appliedFilter,
                searchQuery,
                displayedCountFlow,
                devicesRepository.observeAllDevices(),
            ) { filters, query, count, _ -> Triple(filters, query, count) }
                .flatMapLatest { (filterHolders, query, count) ->
                    flow {
                        // Scroll gate: defer the snapshot rebuild until the user stops
                        // scrolling. Combined with flatMapLatest above, multiple Room
                        // invalidations during a long scroll are conflated to "rebuild once
                        // when the scroll stops" — which eliminates the major-GC stall
                        // (1.88s Davey + 167-frame Choreographer skip on the Motorola)
                        // caused by snapshot churn racing the LazyColumn for main-thread /
                        // heap budget mid-scroll.
                        if (isScrollingFlow.value) {
                            isScrollingFlow.first { !it }
                        }
                        isLoading = true
                        val filters = filterHolders.map { it.filter }
                        // Over-fetch by 1 to detect whether more rows exist beyond [count].
                        // Cheaper than a separate COUNT(*) and good enough for the footer
                        // gate — we only need the boolean, not the exact remaining count.
                        val limit = (count + 1).coerceAtMost(DEVICE_LIST_LIMIT_HARD_CAP + 1)
                        val rows = withContext(Dispatchers.Default) {
                            devicesRepository.snapshotFilteredDevices(
                                filters = filters,
                                searchQuery = query,
                                limit = limit,
                            )
                        }
                        if (rows != null) {
                            hasMoreRows = rows.size > count
                            emit(rows.take(count))
                        } else {
                            // Non-pushable filter (Apple Manufacturer / location-based) —
                            // single-shot fallback through the legacy in-Kotlin path. The
                            // whole filtered set is materialised in memory; we slice for
                            // pagination locally rather than re-running the filter.
                            val devices = withContext(Dispatchers.Default) {
                                devicesRepository.getDevices(withAirdropInfo = true)
                                    .withFilters(filterHolders, query)
                                    .sortedWith(GENERAL_COMPARATOR)
                            }
                            hasMoreRows = devices.size > count
                            emit(devices.take(count))
                        }
                    }
                }
                .onStart {
                    isLoading = true
                }
                // Throttle: rapid Room invalidations during active scanning + bulk inserts
                // can fire several times per second. The UI doesn't need that resolution —
                // sample at ~3 Hz so scroll/recompose work doesn't pile up on the main
                // thread. distinctUntilChanged on top: when the resulting list is reference-
                // or content-equal to what's already on screen, suppress the state write
                // entirely (avoids re-keying every visible LazyColumn item for a no-op).
                .sample(VIEW_STATE_THROTTLE_MS)
                .distinctUntilChanged()
                .collect { devices ->
                    isLoading = false
                    devicesViewState = devices
                }
        }
    }

    private suspend inline fun List<DeviceData>.withFilters(
        appliedFilters: List<FilterHolder>,
        searchQuery: String?,
    ): List<DeviceData> {
        val filter = when {
            appliedFilters.isEmpty() -> null
            appliedFilters.size == 1 -> appliedFilters.first().filter
            else -> DeviceFilter.All(appliedFilters.map { it.filter })
        }
        val query = searchQuery

        return if (filter == null && query == null) {
            this
        } else {
            this.splitToBatches(100)
                .mapParallel { batch ->
                    batch.filter { checkFilter(it, filter) && filterQuery(it, query) }
                }
                .flatMap { it }
        }
    }

    private fun filterQuery(device: DeviceData, query: String?): Boolean {
        return query?.takeIf { it.isNotBlank() }?.let { searchStr ->
            (device.resolvedName?.contains(searchStr, true) == true)
                    || (device.customName?.contains(searchStr, true) == true)
                    || (device.manufacturerInfo?.name?.contains(searchStr, true) == true)
                    || device.address.contains(searchStr, true)
                    || device.address.checkRegexSafe(query)
                    || (device.resolvedName?.checkRegexSafe(query) == true)
        } != false
    }

    private suspend fun checkFilter(device: DeviceData, filter: DeviceFilter?): Boolean {
        return if (filter != null) {
            filterCheckerImpl.check(device, filter)
        } else {
            true
        }
    }

    private fun getDefaultSortStrategy(): CurrentBatchSortingStrategy {
        val id = settingsRepository.getCurrentBatchSortingStrategyId()
        return CurrentBatchSortingStrategy.entries.getOrElse(id) { CurrentBatchSortingStrategy.GENERAL }
    }

    data class FilterHolder(
        val displayName: String,
        val filter: DeviceFilter,
    )

    object DefaultFilters {

        fun notApple(context: Context) = FilterHolder(
            displayName = context.getString(R.string.not_apple),
            filter = DeviceFilter.Not(
                filter = DeviceFilter.Manufacturer(ManufacturerInfo.APPLE_ID)
            )
        )

        /**
         * "Not Samsung" quick-filter: parallel to "Not Apple". Inverts the broadened Samsung
         * classification (MSD company id + OUI + advertised UUIDs via VendorIdentifier),
         * matching the same set Connect All's "Skip Samsung" toggle excludes.
         */
        fun notSamsung(context: Context) = FilterHolder(
            displayName = context.getString(R.string.not_samsung),
            filter = DeviceFilter.Not(
                filter = DeviceFilter.Manufacturer(ManufacturerInfo.SAMSUNG_ID)
            )
        )

        /**
         * "BTC" quick-filter: any device seen on the BR/EDR radio. Includes DUAL because a
         * dual-mode device that responded to inquiry IS a BR/EDR-discoverable device, even
         * if its LE side also surfaced.
         */
        fun btc(context: Context) = FilterHolder(
            displayName = context.getString(R.string.filter_btc),
            filter = DeviceFilter.TransportFilter(
                transportOrdinal = com.darkmentor.domain.model.Transport.BREDR.ordinal,
                includeDual = true,
            ),
        )

        /**
         * "Dual" quick-filter: only DUAL-classified devices (peers we've seen on BOTH the
         * LE and BR/EDR radios). Useful to spot-check whether the BR/EDR ACTION_FOUND path
         * is correctly upgrading LE-only entries to DUAL when they respond to inquiry.
         * Implementation note: passing transportOrdinal=DUAL with includeDual=false matches
         * exactly Transport.DUAL (the includeDual short-circuit only activates when the
         * primary transport is BREDR — see the filter checker's `if` branch).
         */
        fun dual(context: Context) = FilterHolder(
            displayName = context.getString(R.string.filter_dual),
            filter = DeviceFilter.TransportFilter(
                transportOrdinal = com.darkmentor.domain.model.Transport.DUAL.ordinal,
                includeDual = false,
            ),
        )

        /**
         * "GATT" quick-filter: devices that have at least one captured GATT enumeration in
         * the BTIDES log. Useful to narrow the list to devices Connect All has actually
         * succeeded against. Backed by [DeviceFilter.HasGatt].
         */
        fun gatt(context: Context) = FilterHolder(
            displayName = context.getString(R.string.filter_gatt),
            filter = DeviceFilter.HasGatt(hasGatt = true),
        )

        /**
         * "Connectable" quick-filter: any device whose latest scan observation marked it as
         * connectable (LE connectable-advertisement bit set, or BR/EDR inquiry-respondent
         * which we always treat as connectable). Pushes to SQL via the `is_connectable`
         * column so the Devices tab gets a fast narrowed result set.
         */
        fun connectable(context: Context) = FilterHolder(
            displayName = context.getString(R.string.filter_connectable),
            filter = DeviceFilter.IsConnectable(isConnectable = true),
        )

    }

    /**
     * Sort options for the "Devices around you" header. Single-select via the radio-button
     * dialog; default is [BY_DISTANCE] so the user immediately sees the closest peers at the
     * top. The legacy [GENERAL] option (lastDetectTimeMs desc + name + rssi + manufacturer
     * tiebreakers) is retained because it's what the rest of the device list (paginated) uses
     * as its underlying ordering, and some users prefer "most-recently-seen first".
     *
     * The previous BY_TYPE option was removed (low signal — DeviceClass is largely "Unknown"
     * for the bulk of LE peers), and BY_MANUFACTURER takes its slot since that's a more
     * useful axis once a Connect All pass has populated the GATT-derived manufacturer
     * fallback.
     */
    enum class CurrentBatchSortingStrategy(
        val comparator: Comparator<DeviceData>,
        @StringRes val displayNameRes: Int,
    ) {
        BY_DISTANCE(Comparator { second, first ->
            when {
                first.distance() != second.distance() -> second.distance()?.compareTo(first.distance() ?: return@Comparator 1) ?: -1
                else -> GENERAL_COMPARATOR.compare(first, second)
            }
        }, R.string.sort_type_by_distance),
        GENERAL(GENERAL_COMPARATOR, R.string.sort_type_standart),
        BY_MANUFACTURER(Comparator { first, second ->
            // Sort by resolvedManufacturerName (MSD → GATT 0x2A29 → IEEE OUI fallback chain),
            // null-last so unidentified devices sink to the bottom. Within a manufacturer
            // group, fall back to the GENERAL ordering so the user gets a stable secondary
            // sort by recency + name.
            val firstName = first.resolvedManufacturerName
            val secondName = second.resolvedManufacturerName
            when {
                firstName == null && secondName != null -> 1
                firstName != null && secondName == null -> -1
                firstName != null && secondName != null && firstName != secondName ->
                    firstName.compareTo(secondName, ignoreCase = true)
                else -> GENERAL_COMPARATOR.compare(first, second)
            }
        }, R.string.sort_type_by_manufacturer),
    }

    enum class ActiveScannerExpandedState {
        EXPANDED, COLLAPSED;

        fun next(): ActiveScannerExpandedState {
            return entries.elementAt((ordinal + 1) % entries.size)
        }

        companion object {
            val MAX_DEVICES_COUNT = 3
            // EXPANDED mode cap. Without it, a 2000-device current batch would compose 2000
            // DeviceListItem rows inside a single parent LazyColumn item — Compose can't
            // virtualise inside an item's content. At N=50 the panel is large but bounded;
            // the user is steered to search/filter for more via the footer hint. The proper
            // fix (emit each row as its own LazyColumn item) is queued for Tier 3 with paging.
            val MAX_DEVICES_COUNT_EXPANDED = 50
        }
    }

    companion object {
        // Page size for the user-driven "Show next 50" footer. Drives both the initial
        // SQL `LIMIT` (one page) and the increment per button-tap. 50 is large enough
        // to fill the visible viewport on a portrait phone with over-fetch, small
        // enough to keep the LazyColumn working set tight when the user only wants the
        // most recent few devices.
        const val PAGE_SIZE = 50
        // Hard ceiling on [displayedCount] so the per-refetch SQL + Compose working
        // set stays bounded even after many "Show next 50" taps. Matches the existing
        // DEVICE_LIST_LIMIT in DevicesRepository (the SQL also clamps there) — kept
        // duplicated so the VM can render the cap-reached footer without a repository
        // dependency just for the constant.
        const val DEVICE_LIST_LIMIT_HARD_CAP = 1000
        // Sample-rate cap on devicesViewState writes. Active scanning + bulk inserts can
        // fire several Room invalidations per second; the user can't perceptibly benefit
        // from a refresh faster than ~3 Hz on a long list, and the per-write LazyColumn
        // re-key cost is real. Combined with distinctUntilChanged, this means the screen
        // only repaints when the list actually changed AND at most once every 333 ms.
        private const val VIEW_STATE_THROTTLE_MS: Long = 333L

        private val GENERAL_COMPARATOR = Comparator<DeviceData> { second, first ->

            when {
                first.lastDetectTimeMs != second.lastDetectTimeMs -> first.lastDetectTimeMs.compareTo(second.lastDetectTimeMs)

                first.resolvedName != second.resolvedName -> first.resolvedName?.compareTo(second.resolvedName ?: return@Comparator 1) ?: -1

                first.rssi != second.rssi -> first.rssi?.compareTo(second.rssi ?: return@Comparator 1) ?: -1

                first.manufacturerInfo?.name != second.manufacturerInfo?.name ->
                    first.manufacturerInfo?.name?.compareTo(second.manufacturerInfo?.name ?: return@Comparator 1) ?: -1

                else -> first.address.compareTo(second.address)
            }
        }
    }
}