package f.cking.software.ui.main

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanpra.composematerialdialogs.MaterialDialogState
import f.cking.software.R
import f.cking.software.data.helpers.BleScannerHelper
import f.cking.software.data.helpers.IntentHelper
import f.cking.software.data.helpers.LocationProvider
import f.cking.software.data.helpers.PermissionHelper
import f.cking.software.data.repo.SettingsRepository
import f.cking.software.service.BgScanService
import f.cking.software.ui.ScreenNavigationCommands
import f.cking.software.ui.connectall.ConnectAllScreen
import f.cking.software.ui.devicelist.DeviceListScreen
import f.cking.software.ui.settings.SettingsScreen
import f.cking.software.utils.navigation.Router
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

class MainViewModel(
    private val permissionHelper: PermissionHelper,
    private val context: Application,
    private val bluetoothHelper: BleScannerHelper,
    private val settingsRepository: SettingsRepository,
    private val locationProvider: LocationProvider,
    private val intentHelper: IntentHelper,
    private val router: Router,
) : ViewModel() {

    var scanStarted: Boolean by mutableStateOf(BgScanService.state.value.isProcessing())
    var bgServiceIsActive: Boolean by mutableStateOf(BgScanService.isActive)
    var showLocationDisabledDialog: MaterialDialogState = MaterialDialogState()
    var showBluetoothDisabledDialog: MaterialDialogState = MaterialDialogState()
    /**
     * True when LocationProvider has emitted a fix in the last [GPS_FRESH_WINDOW_MS]. Drives the
     * 🛰️/🚫 GPS chip in the top app bar so the user can tell at a glance whether geo-tags are
     * being attached to scan records.
     */
    var gpsHasRecentFix: Boolean by mutableStateOf(false)

    var tabs by mutableStateOf(
        listOf(
            Tab(
                key = TabKey.DEVICES,
                iconRes = R.drawable.ic_home_outline,
                selectedIconRes = R.drawable.ic_home,
                text = context.getString(R.string.menu_device_list),
                selected = true,
            ) { DeviceListScreen.Screen() },
            Tab(
                key = TabKey.CONNECT_ALL,
                iconRes = R.drawable.ic_alert_outline,
                selectedIconRes = R.drawable.ic_alert,
                text = context.getString(R.string.menu_connect_all),
                selected = false,
            ) { ConnectAllScreen.Screen() },
            Tab(
                key = TabKey.SETTINGS,
                iconRes = R.drawable.ic_settings_outline,
                selectedIconRes = R.drawable.ic_settings,
                text = context.getString(R.string.menu_settings),
                selected = false,
            ) { SettingsScreen.Screen() },
        )
    )

    val selectedTabKey: TabKey
        get() = tabs.firstOrNull { it.selected }?.key ?: TabKey.DEVICES

    init {
        observeScanInProgress()
        observeServiceIsLaunched()
        observeGpsFreshness()
        cleanupOrphanedAutoScan()
    }

    /**
     * If the BgScanService is still running from a prior session and the persisted scan-start
     * mode is CONNECT_ALL_AUTO, that's a leftover from a Connect-All auto-scan whose
     * DisposableEffect didn't get to run (process was killed mid-pane). Stop it now so a fresh
     * Devices-tab visit doesn't see ghost scan results — the user only opted in to scanning
     * "while looking at Connect All".
     */
    private fun cleanupOrphanedAutoScan() {
        if (BgScanService.isActive
            && settingsRepository.getScanStartMode() == SettingsRepository.ScanStartMode.CONNECT_ALL_AUTO
        ) {
            BgScanService.stop(context)
            settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.NONE)
        }
    }

    private var lastLocationHandle: LocationProvider.LocationHandle? = null

    private fun observeGpsFreshness() {
        // Two cooperating jobs feed [gpsHasRecentFix]:
        // 1) The location observer caches every new fix and recomputes immediately so a fresh
        //    fix flips the chip to 🛰️ within one frame.
        // 2) The poll loop recomputes every [GPS_FRESHNESS_POLL_MS] so the chip flips back to
        //    🚫 once the cached fix passes [GPS_FRESH_WINDOW_MS] without depending on the
        //    upstream flow re-emitting.
        viewModelScope.launch {
            locationProvider.observeLocation().collect { handle ->
                lastLocationHandle = handle
                recomputeGpsFreshness()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(GPS_FRESHNESS_POLL_MS)
                recomputeGpsFreshness()
            }
        }
    }

    private fun recomputeGpsFreshness() {
        val handle = lastLocationHandle
        val fresh = handle != null && (System.currentTimeMillis() - handle.emitTime) < GPS_FRESH_WINDOW_MS
        if (fresh != gpsHasRecentFix) {
            Timber.tag("GpsChip").d("gpsHasRecentFix %s -> %s (handle=%s, age=%s)",
                gpsHasRecentFix, fresh, handle != null,
                handle?.let { System.currentTimeMillis() - it.emitTime })
            gpsHasRecentFix = fresh
        }
    }

    fun onScanButtonClick() {
        checkPermissions {
            // Manual scan from the Devices-tab FAB → user-explicit mode. Survives app restarts
            // (the foreground service keeps running) until the user stops it themselves.
            settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.USER_EXPLICIT)
            BgScanService.scan(context)
        }
    }

    fun onTabClick(tab: Tab) {
        val list = tabs.map { it.copy(selected = it == tab) }
        tabs = list
    }

    fun runBackgroundScanning() {
        checkPermissions {
            if (BgScanService.isActive) {
                BgScanService.stop(context)
                settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.NONE)
            } else if (!locationProvider.isLocationAvailable()) {
                showLocationDisabledDialog.show()
            } else if (!bluetoothHelper.isBluetoothEnabled()) {
                showBluetoothDisabledDialog.show()
            } else {
                // User-initiated start → user-explicit mode. Persists across restarts.
                settingsRepository.setScanStartMode(SettingsRepository.ScanStartMode.USER_EXPLICIT)
                BgScanService.start(context)
            }
        }
    }

    fun onTurnOnLocationClick() {
        intentHelper.openLocationSettings()
    }

    fun onTurnOnBluetoothClick() {
        intentHelper.openBluetoothSettings()
    }

    fun needToShowPermissionsIntro(): Boolean {
        return !settingsRepository.getPermissionsIntroWasShown()
    }

    fun userHasPassedPermissionsIntro() {
        settingsRepository.setPermissionsIntroWasShown(true)
    }

    fun checkAndShowAboutApp() {
        if (!settingsRepository.getWhatIsThisAppForWasShown()) {
            router.navigate(ScreenNavigationCommands.OpenAboutScreen)
            settingsRepository.setWhatIsThisAppForWasShown(true)
        }
    }

    private fun observeScanInProgress() {
        viewModelScope.launch {
            BgScanService.state
                .map { it.isProcessing() }
                .distinctUntilChanged()
                .collect { scanStarted = it }
        }
    }

    private fun observeServiceIsLaunched() {
        viewModelScope.launch {
            BgScanService.observeIsActive()
                .collect { bgServiceIsActive = it }
        }
    }

    private fun checkPermissions(granted: () -> Unit) {
        permissionHelper.checkOrRequestPermission {
            permissionHelper.checkDozeModePermission()
            granted.invoke()
        }
    }

    enum class TabKey { DEVICES, CONNECT_ALL, SETTINGS }

    data class Tab(
        val key: TabKey,
        @DrawableRes val iconRes: Int,
        @DrawableRes val selectedIconRes: Int,
        val text: String,
        val selected: Boolean,
        val screen: @Composable () -> Unit,
    )

    companion object {
        // Two minutes mirrors LocationProvider.ALLOWED_LOCATION_LIVETIME_MS — same window the
        // location pipeline considers a fix "fresh enough" to attach to scan records.
        private const val GPS_FRESH_WINDOW_MS = 2L * 60L * 1000L
        // Re-poll cadence so the chip flips from 🛰️ to 🚫 within ~30s of the fix going stale.
        private const val GPS_FRESHNESS_POLL_MS = 30L * 1000L
    }
}