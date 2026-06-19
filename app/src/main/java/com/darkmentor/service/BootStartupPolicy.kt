package com.darkmentor.service

import com.darkmentor.data.repo.SettingsRepository

/**
 * Pure decision logic for how the app should respond to a [android.content.Intent.ACTION_BOOT_COMPLETED]
 * broadcast. Extracted from [BootBroadcastReceiver] so the across-reboot behaviour can be unit-
 * tested without standing up a real Android service or Koin container.
 *
 * Inputs are values read from [SettingsRepository] + the runtime permission gate; outputs are
 * the side-effect a caller should perform. The receiver still holds responsibility for actually
 * starting the foreground service / [com.darkmentor.ui.connectall.ConnectAllSession].
 */
sealed interface BootStartupAction {
    /** Neither auto-start toggle is enabled. The receiver should no-op. */
    object Idle : BootStartupAction

    /** An auto-start toggle is enabled but BLE permissions are missing — log to Journal. */
    data class PermissionError(val label: String) : BootStartupAction

    /**
     * Start the foreground BgScanService with [scanStartMode] = USER_EXPLICIT so the scan
     * survives the next app open and isn't torn down by Connect All's mode tracking when the
     * user happens to visit that pane.
     */
    object StartDeviceScan : BootStartupAction

    /**
     * Start BgScanService with mode = CONNECT_ALL_AUTO and resume the bulk Connect-All loop
     * with [retryForever] from saved settings.
     */
    data class StartConnectAll(val retryForever: Boolean) : BootStartupAction
}

/** Human-readable label used in the Journal entry for a permission-error report. */
const val BOOT_STARTUP_LABEL_DEVICE_SCAN = "Launch device scan at system startup"
const val BOOT_STARTUP_LABEL_CONNECT_ALL = "Launch Connect All at system startup"

/**
 * Decide what the boot receiver should do. The receiver invokes the side effects; this
 * function is pure so tests can sweep the input space.
 *
 * - Both toggles off → [BootStartupAction.Idle].
 * - Permissions missing → [BootStartupAction.PermissionError] with the appropriate label
 *   ("Connect All" wins the label if its toggle is on; otherwise "device scan").
 * - Otherwise → [BootStartupAction.StartDeviceScan] when the device-scan toggle is on
 *   (takes precedence, matching the receiver's pre-extraction behaviour), else
 *   [BootStartupAction.StartConnectAll] with [retryForever] from settings.
 *
 * Settings UI enforces mutual exclusion so the device-scan-wins precedence shouldn't fire in
 * practice, but it's the deterministic fallback if the prefs ever land in that state.
 */
fun decideBootStartup(
    runDeviceScanOnStartup: Boolean,
    runConnectAllOnStartup: Boolean,
    blePermissionsAllowed: Boolean,
    bulkRetryForever: Boolean,
): BootStartupAction {
    if (!runDeviceScanOnStartup && !runConnectAllOnStartup) return BootStartupAction.Idle
    if (!blePermissionsAllowed) {
        val label = if (runConnectAllOnStartup) BOOT_STARTUP_LABEL_CONNECT_ALL else BOOT_STARTUP_LABEL_DEVICE_SCAN
        return BootStartupAction.PermissionError(label)
    }
    return if (runDeviceScanOnStartup) {
        BootStartupAction.StartDeviceScan
    } else {
        BootStartupAction.StartConnectAll(retryForever = bulkRetryForever)
    }
}
