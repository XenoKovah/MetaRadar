package f.cking.software.data.repo

import android.content.SharedPreferences
import androidx.core.content.edit
import f.cking.software.BuildConfig
import f.cking.software.TheAppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class SettingsRepository(
    private val sharedPreferences: SharedPreferences,
) {

    private val silentModeState = MutableStateFlow(getSilentMode())
    private val hideBackgroundLocationWarning = MutableStateFlow(getHideBackgroundLocationWarning())

    fun setGarbagingTime(time: Long) {
        sharedPreferences.edit().putLong(KEY_GARBAGING_TIME, time).apply()
    }

    fun getGarbagingTime(): Long {
        return sharedPreferences.getLong(KEY_GARBAGING_TIME, TheAppConfig.DEVICE_GARBAGING_TIME)
    }

    fun setUseGpsLocationOnly(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_USE_GPS_ONLY, value).apply()
    }

    fun getUseGpsLocationOnly(): Boolean {
        return sharedPreferences.getBoolean(KEY_USE_GPS_ONLY, TheAppConfig.USE_GPS_LOCATION_ONLY)
    }

    fun getPermissionsIntroWasShown(): Boolean {
        return sharedPreferences.getBoolean(KEY_PERMISSIONS_INTRO_WAS_SHOWN, false)
    }

    fun setPermissionsIntroWasShown(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_PERMISSIONS_INTRO_WAS_SHOWN, value).apply()
    }

    fun getRunOnStartup(): Boolean {
        return sharedPreferences.getBoolean(KEY_RUN_ON_STARTUP, false)
    }

    fun setRunOnStartup(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RUN_ON_STARTUP, value).apply()
    }

    /**
     * "Launch Connect All at system startup". Mutually exclusive with [getRunOnStartup] —
     * the SettingsViewModel enforces that, this layer just stores the flag. When true and
     * BOOT_COMPLETED arrives, [BootBroadcastReceiver] starts the BLE service AND kicks off a
     * Connect All retry-forever pass via [ConnectAllSession]. The Skip Apple / Skip Samsung
     * toggles are respected via the existing settings (read each pass).
     */
    fun getRunConnectAllOnStartup(): Boolean {
        return sharedPreferences.getBoolean(KEY_RUN_CONNECT_ALL_ON_STARTUP, false)
    }

    fun setRunConnectAllOnStartup(value: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_RUN_CONNECT_ALL_ON_STARTUP, value).apply()
    }

    fun getFirstAppLaunchTime(): Long {
        return sharedPreferences.getLong(KEY_FIRST_APP_LAUNCH_TIME, NO_APP_LAUNCH_TIME)
    }

    fun setFirstAppLaunchTime(value: Long) {
        sharedPreferences.edit().putLong(KEY_FIRST_APP_LAUNCH_TIME, value).apply()
    }

    fun setHideBackgroundLocationWarning(value: Long) {
        sharedPreferences.edit { putLong(KEY_HIDE_BACKGROUND_LOCATION_WARNING, value) }
        hideBackgroundLocationWarning.tryEmit(value)
    }

    fun getHideBackgroundLocationWarning(): Long {
        return sharedPreferences.getLong(KEY_HIDE_BACKGROUND_LOCATION_WARNING, 0L)
    }

    fun observeHideBackgroundLocationWarning(): Flow<Long> {
        return hideBackgroundLocationWarning
    }

    fun setSilentMode(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_SILENT_NETWORK_MODE, enabled) }
        silentModeState.tryEmit(getSilentMode())
    }

    fun getSilentMode(): Boolean {
        return sharedPreferences.getBoolean(KEY_SILENT_NETWORK_MODE, BuildConfig.OFFLINE_MODE_DEFAULT_STATE)
    }

    fun observeSilentMode(): Flow<Boolean> {
        return silentModeState
    }

    fun getCurrentBatchSortingStrategyId(): Int {
        return sharedPreferences.getInt(KEY_CURRENT_BATCH_SORTING_STRATEGY_ID, 0)
    }

    fun setCurrentBatchSortingStrategyId(value: Int) {
        sharedPreferences.edit { putInt(KEY_CURRENT_BATCH_SORTING_STRATEGY_ID, value) }
    }

    fun setDisclaimerWasAccepted(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_DISCLAIMER_WAS_ACCEPTED, value) }
    }

    fun getDisclaimerWasAccepted(): Boolean {
        return sharedPreferences.getBoolean(KEY_DISCLAIMER_WAS_ACCEPTED, false)
    }

    fun getWhatIsThisAppForWasShown(): Boolean {
        return sharedPreferences.getBoolean(KEY_WHAT_IS_THIS_APP_FOR_WAS_SHOWN, false)
    }

    fun setWhatIsThisAppForWasShown(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_WHAT_IS_THIS_APP_FOR_WAS_SHOWN, value) }
    }

    fun getWakeUpScreenWhileScanning(): Boolean {
        // Default true: Android pauses BLE scans aggressively when the screen is off, and the
        // user-visible cost (the wakelock and the ~10s screen-on burst) is low compared to
        // missing scan windows. Pre-existing installs with the false-default value persisted
        // in prefs keep their choice.
        return sharedPreferences.getBoolean(KEY_WAKE_UP_SCREEN_WHILE_SCANNING, true)
    }

    fun setWakeUpScreenWhileScanning(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_WAKE_UP_SCREEN_WHILE_SCANNING, value) }
    }

    fun getBulkSkipApple(): Boolean = sharedPreferences.getBoolean(KEY_BULK_SKIP_APPLE, true)
    fun setBulkSkipApple(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BULK_SKIP_APPLE, value) }
    }

    fun getBulkSkipSamsung(): Boolean = sharedPreferences.getBoolean(KEY_BULK_SKIP_SAMSUNG, true)
    fun setBulkSkipSamsung(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BULK_SKIP_SAMSUNG, value) }
    }

    fun getBulkRetryForever(): Boolean = sharedPreferences.getBoolean(KEY_BULK_RETRY_FOREVER, true)
    fun setBulkRetryForever(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BULK_RETRY_FOREVER, value) }
    }

    /**
     * Independent toggles for the two discovery transports — both default to true so out-of-the-
     * box behaviour matches today's app (LE-only) plus opt-in BR/EDR. Each can be turned off
     * independently to test one transport in isolation.
     */
    fun getDiscoverLeEnabled(): Boolean = sharedPreferences.getBoolean(KEY_DISCOVER_LE_ENABLED, true)
    fun setDiscoverLeEnabled(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_DISCOVER_LE_ENABLED, value) }
    }

    fun getDiscoverBrEdrEnabled(): Boolean = sharedPreferences.getBoolean(KEY_DISCOVER_BR_EDR_ENABLED, true)
    fun setDiscoverBrEdrEnabled(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_DISCOVER_BR_EDR_ENABLED, value) }
    }

    /**
     * Mirrors the BTIDES_to_BTIDALPOOL.py `--use-test-db` flag. When true, uploads route to
     * the BTIDALPOOL server's alternate `bttest` database instead of the production pool —
     * useful while iterating without polluting the public dataset.
     */
    fun getBtidalpoolUseTestDb(): Boolean = sharedPreferences.getBoolean(KEY_BTIDALPOOL_USE_TEST_DB, false)
    fun setBtidalpoolUseTestDb(value: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BTIDALPOOL_USE_TEST_DB, value) }
    }

    /**
     * Tracks who started the currently-running BgScanService:
     *   - NONE: service is not running, or was started before this concept existed.
     *   - USER_EXPLICIT: user tapped Scan FAB / kept-on toggle / boot-on. Survives app restarts.
     *   - CONNECT_ALL_AUTO: auto-started by Connect All entry. Killed when leaving Connect All
     *     and on next MainActivity create if leftover from a process kill.
     * Persistent so MainActivity can clean up CONNECT_ALL_AUTO leftovers across process death.
     */
    fun getScanStartMode(): ScanStartMode {
        val raw = sharedPreferences.getString(KEY_SCAN_START_MODE, null) ?: return ScanStartMode.NONE
        return runCatching { ScanStartMode.valueOf(raw) }.getOrDefault(ScanStartMode.NONE)
    }

    fun setScanStartMode(mode: ScanStartMode) {
        sharedPreferences.edit { putString(KEY_SCAN_START_MODE, mode.name) }
    }

    enum class ScanStartMode { NONE, USER_EXPLICIT, CONNECT_ALL_AUTO }

    companion object {
        private const val KEY_GARBAGING_TIME = "key_garbaging_time"
        private const val KEY_USE_GPS_ONLY = "key_use_gps_location_only"
        private const val KEY_PERMISSIONS_INTRO_WAS_SHOWN = "key_permissions_intro_was_shown"
        private const val KEY_RUN_ON_STARTUP = "key_run_on_startup"
        private const val KEY_RUN_CONNECT_ALL_ON_STARTUP = "key_run_connect_all_on_startup"
        private const val KEY_FIRST_APP_LAUNCH_TIME = "key_first_app_launch_time"
        private const val KEY_SILENT_NETWORK_MODE = "silent_network_mode"
        private const val KEY_CURRENT_BATCH_SORTING_STRATEGY_ID = "key_current_batch_sorting_strategy_id"
        private const val KEY_HIDE_BACKGROUND_LOCATION_WARNING = "key_hide_background_location_warning"
        private const val KEY_DISCLAIMER_WAS_ACCEPTED = "key_disclaimer_was_accepted"
        private const val KEY_WHAT_IS_THIS_APP_FOR_WAS_SHOWN = "what_is_this_app_for_was_shown"
        private const val KEY_WAKE_UP_SCREEN_WHILE_SCANNING = "key_wake_up_screen_while_scanning"
        private const val KEY_BULK_SKIP_APPLE = "key_bulk_skip_apple"
        private const val KEY_BULK_SKIP_SAMSUNG = "key_bulk_skip_samsung"
        private const val KEY_BULK_RETRY_FOREVER = "key_bulk_retry_forever"
        private const val KEY_SCAN_START_MODE = "key_scan_start_mode"
        private const val KEY_DISCOVER_LE_ENABLED = "key_discover_le_enabled"
        private const val KEY_DISCOVER_BR_EDR_ENABLED = "key_discover_br_edr_enabled"
        private const val KEY_BTIDALPOOL_USE_TEST_DB = "key_btidalpool_use_test_db"

        const val NO_APP_LAUNCH_TIME = -1L
    }
}