package com.darkmentor.data.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.darkmentor.data.helpers.IntentHelper.ScreenNavigation.Companion.toNavigationCommand
import com.darkmentor.openUrl
import com.darkmentor.ui.MainActivity
import com.darkmentor.ui.ScreenNavigationCommands
import com.darkmentor.utils.navigation.NavigationCommand
import com.darkmentor.utils.navigation.Router

class IntentHelper(
    private val activityProvider: ActivityProvider,
    private val router: Router,
    private val context: Context,
) {

    /**
     * TODO: this code us unsafe
     */
    private val pendingConsumers = mutableMapOf<Int, (result: Uri?) -> Unit>()

    fun selectFile(onResult: (filePath: Uri?) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activityProvider.requireActivity().startActivityForResult(intent, ACTIVITY_RESULT_SELECT_FILE)
        pendingConsumers[ACTIVITY_RESULT_SELECT_FILE] = onResult
    }

    fun createFile(fileName: String, onResult: (directoryPath: Uri?) -> Unit) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, fileName)
            type = "application/sqlite"
        }
        activityProvider.requireActivity().startActivityForResult(intent, ACTIVITY_RESULT_CREATE_FILE)
        pendingConsumers[ACTIVITY_RESULT_CREATE_FILE] = onResult
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", context.packageName, null)
        intent.data = uri
        activityProvider.requireActivity().startActivity(intent)
    }

    /**
     * Open the system battery-optimisation page so the user can add this app to the
     * "ignore battery optimisation" list. Required for the boot-launched scan / Connect All
     * to keep running long-term — Android otherwise kills foreground services after a few
     * minutes on a battery-saving doze cycle.
     *
     * Falls back to general battery-saver settings if the request-ignore intent isn't
     * resolvable on this device (some OEMs hide it).
     */
    fun openIgnoreBatteryOptimizationSettings() {
        val activity = activityProvider.requireActivity()
        // Show the app already pre-selected on the system list so the user doesn't have to
        // hunt for it. ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS opens directly into the
        // confirmation dialog when the URI is the package name.
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        activityProvider.requireActivity().startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    fun openBluetoothSettings() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        activityProvider.requireActivity().startActivity(intent)
    }

    fun openUrl(url: String) {
        val activity = activityProvider.requireActivity()
        activity.openUrl(url)
    }

    fun openScreenIntent(screenName: ScreenNavigation): Intent {
        val intent = Intent(context, MainActivity::class.java)
        intent.setAction(ACTION_OPEN_SCREEN)
        intent.putExtra(SCREEN_NAME, screenName.name)
        return intent
    }

    fun tryHandleIntent(intent: Intent): Boolean {
        if (intent.isScreenNavigation()) {
            val screenNavigation = ScreenNavigation.fromIntent(intent) ?: return false
            router.navigate(screenNavigation.toNavigationCommand())
            return true
        }
        return false
    }

    private fun Intent.isScreenNavigation(): Boolean {
        return action == ACTION_OPEN_SCREEN
    }

    enum class ScreenNavigation {
        MAIN,
        BACKGROUND_LOCATION_DESCRIPTION;

        companion object {

            fun fromIntent(intent: Intent): ScreenNavigation? {
                val name = intent.getStringExtra(SCREEN_NAME) ?: return null
                return entries.firstOrNull { it.name == name }
            }

            fun ScreenNavigation.toNavigationCommand(): NavigationCommand {
                return when (this) {
                    MAIN -> ScreenNavigationCommands.OpenMainScreen
                    BACKGROUND_LOCATION_DESCRIPTION -> ScreenNavigationCommands.OpenBackgroundLocationScreen
                }
            }
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val consumer = pendingConsumers[requestCode]
        if (resultCode == Activity.RESULT_OK) {
            consumer?.invoke(data?.data)
        } else {
            consumer?.invoke(null)
        }
    }

    companion object {
        private const val ACTIVITY_RESULT_SELECT_FILE = 2
        private const val ACTIVITY_RESULT_CREATE_FILE = 3

        private const val ACTION_OPEN_SCREEN = "action_open_screen"
        private const val SCREEN_NAME = "screen_name"
    }
}
