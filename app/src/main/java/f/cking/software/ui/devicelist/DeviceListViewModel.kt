package f.cking.software.ui.devicelist

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import f.cking.software.R
import f.cking.software.checkRegexSafe
import f.cking.software.collectAsState
import f.cking.software.data.helpers.IntentHelper
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.repo.DevicesRepository
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.domain.interactor.ClearAllDevicesInteractor
import f.cking.software.domain.interactor.filterchecker.FilterCheckerImpl
import f.cking.software.domain.model.DeviceClass
import f.cking.software.domain.model.DeviceData
import f.cking.software.domain.model.DeviceFilter
import f.cking.software.domain.model.ManufacturerInfo
import f.cking.software.mapParallel
import f.cking.software.service.BgScanService
import f.cking.software.splitToBatches
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
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
    var isPaginationEnabled: Boolean by mutableStateOf(false)
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
    private var currentPage: Int by mutableStateOf(INITIAL_PAGE)

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
        viewModelScope.launch { appliedFilter.emit(updated) }
    }

    /** Remove [filter] from the active filter list. Used by the editor's top-bar trash icon. */
    fun removeFilter(filter: FilterHolder) {
        val updated = appliedFilter.value.toMutableList().also { it.remove(filter) }
        viewModelScope.launch { appliedFilter.emit(updated) }
    }

    fun onOpenSearchClick() {
        isSearchMode = !isSearchMode
        if (!isSearchMode) {
            viewModelScope.launch { searchQuery.emit(null) }
        }
    }

    fun onClearAllDevicesConfirmed() {
        viewModelScope.launch {
            clearAllDevicesInteractor.execute()
        }
    }

    fun onSearchInput(str: String) {
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
        val anyFilterApplyed = isSearchMode || appliedFilter.value.isNotEmpty()

        scannerObservingJob?.cancel()
        disablePagination()

        // TODO fix realtime items observing before enabling pagination
//        if (isScannerEnabled || anyFilterApplyed) {
//            disablePagination()
//        } else {
//            enablePagination()
//        }

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

    private fun disablePagination() {
        isPaginationEnabled = false
        scannerObservingJob = observeAllDevices()
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

    @ExperimentalCoroutinesApi
    private fun observeCurrentBatch(): Job {
        return viewModelScope.launch {
            combine(
                appliedFilter,
                searchQuery,
                devicesRepository.observeLastBatch()
                    .onStart {
                        currentBatchViewState = emptyList()
                        devicesRepository.clearLastBatch()
                    }
            ) { filters, query, devices ->
                val devices = devices
                    .withFilters(filters, query)
                    .sortedWith(currentBatchSortingStrategy.comparator)
                devices
            }
                .collect { devices ->
                    currentBatchViewState = devices
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAllDevices(): Job {
        isLoading = true
        return viewModelScope.launch {
            // Step 1: collapse filter / search / DB-tick into a single trigger. We don't
            // collect devices here — we just use Room's flow as a "table changed" signal so
            // the snapshot below re-runs.
            combine(
                appliedFilter,
                searchQuery,
                devicesRepository.observeAllDevices(),
            ) { filters, query, _ -> filters to query }
                .flatMapLatest { (filterHolders, query) ->
                    flow {
                        isLoading = true
                        val result = withContext(Dispatchers.Default) {
                            val filters = filterHolders.map { it.filter }
                            // Try the SQL push-down path first. If every applied filter
                            // translates to a WHERE clause (e.g., user only has the empty
                            // filter set, or only IsPaired / Tag / Address / interval / Name
                            // / non-Apple Manufacturer), the DB returns at most LIMIT rows
                            // already filtered + sorted — orders of magnitude faster at
                            // M=200k devices than the legacy materialise-then-filter path.
                            val sqlSnapshot = devicesRepository.snapshotFilteredDevices(filters, query)
                            val devices = sqlSnapshot ?: devicesRepository.getDevices(withAirdropInfo = true)
                            devices
                                // Re-apply filters in Kotlin only on the fallback path
                                // (sqlSnapshot==null): the SQL snapshot is already filtered
                                // + sorted. The fallback case happens for non-pushable
                                // filters (Apple Manufacturer / Device or User location).
                                .let { list -> if (sqlSnapshot != null) list else list.withFilters(filterHolders, query) }
                                .let { list -> if (sqlSnapshot != null) list else list.sortedWith(GENERAL_COMPARATOR) }
                        }
                        emit(result)
                    }
                }
                .onStart {
                    isLoading = true
                }
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
                transportOrdinal = f.cking.software.domain.model.Transport.BREDR.ordinal,
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
                transportOrdinal = f.cking.software.domain.model.Transport.DUAL.ordinal,
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
        private const val PAGE_SIZE = 40
        private const val INITIAL_PAGE = 0

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