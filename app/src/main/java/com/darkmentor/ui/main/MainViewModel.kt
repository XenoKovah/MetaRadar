package com.darkmentor.ui.main

import android.app.Application
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanpra.composematerialdialogs.MaterialDialogState
import com.darkmentor.R
import com.darkmentor.data.helpers.BleScannerHelper
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.data.helpers.PermissionHelper
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.service.BgScanService
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.ui.connectall.ConnectAllScreen
import com.darkmentor.ui.devicelist.DeviceListScreen
import com.darkmentor.ui.settings.SettingsScreen
import com.darkmentor.utils.navigation.Router
import kotlinx.coroutines.Job
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
    /** True while a user-tapped GPS refresh is in flight. Drives the spinner overlay on the
     *  🚫GPS chip. Cleared either when a fresh fix arrives or after a max-wait timeout, so
     *  the spinner doesn't spin forever when the system genuinely can't get a fix. */
    var gpsRefreshInProgress: Boolean by mutableStateOf(false)
    private var gpsRefreshTimeoutJob: Job? = null

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
        // A fresh fix arriving cancels any in-flight refresh spinner immediately.
        if (fresh && gpsRefreshInProgress) {
            gpsRefreshInProgress = false
            gpsRefreshTimeoutJob?.cancel()
            gpsRefreshTimeoutJob = null
        }
    }

    /**
     * User tapped the 🚫GPS chip — kick a one-shot location refresh through the existing
     * [LocationProvider.fetchOnce] path. Shows a cycling spinner over the chip while the
     * fetch is in flight. The system may still fail to get a fix (no satellite lock, no
     * cell-tower assist) — in that case the spinner times out after [GPS_REFRESH_TIMEOUT_MS]
     * and we fall back to the static 🚫 icon so the user knows the attempt completed.
     *
     * Permission gating mirrors [onScanButtonClick]: re-checks via PermissionHelper so a user
     * who denied location-when-asked at install time gets prompted again on tap.
     */
    fun onGpsChipClick() {
        // Toggle behavior: if a refresh is already showing, the second tap cancels the
        // spinner so the user has clear feedback that the refresh is *not* in progress.
        // The underlying location request the system queued may still complete and emit a
        // fix (we don't have a clean per-request cancel via LocationManager, only a coarse
        // stopLocationListening that would also tear down the periodic loop) — but the UI
        // returns to its idle state, which is what the user asked for.
        if (gpsRefreshInProgress) {
            Timber.tag("GpsChip").i("User cancelled in-flight GPS refresh")
            gpsRefreshInProgress = false
            gpsRefreshTimeoutJob?.cancel()
            gpsRefreshTimeoutJob = null
            return
        }
        checkPermissions {
            gpsRefreshInProgress = true
            gpsRefreshTimeoutJob?.cancel()
            gpsRefreshTimeoutJob = viewModelScope.launch {
                delay(GPS_REFRESH_TIMEOUT_MS)
                gpsRefreshInProgress = false
            }
            try {
                locationProvider.fetchOnce()
            } catch (e: Throwable) {
                Timber.tag("GpsChip").w(e, "fetchOnce threw; clearing spinner")
                gpsRefreshInProgress = false
                gpsRefreshTimeoutJob?.cancel()
                gpsRefreshTimeoutJob = null
            }
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
        // Max time we leave the spinner up before giving up on a user-tapped GPS refresh. Keep
        // this a hair longer than LocationProvider.LOCATION_REQUEST_MAX_DURATION_MILLS (30s)
        // so the underlying getCurrentLocation has a chance to complete or fail before the UI
        // gives up.
        private const val GPS_REFRESH_TIMEOUT_MS = 35L * 1000L
    }
}
