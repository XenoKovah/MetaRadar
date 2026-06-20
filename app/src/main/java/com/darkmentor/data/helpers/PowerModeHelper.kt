package com.darkmentor.data.helpers

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.getValue
import com.darkmentor.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class PowerModeHelper(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val globalScope: CoroutineScope,
) {

    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private var cachedPowerMode: PowerMode = PowerMode.DEFAULT

    private var originalBrightness by AtomicInteger(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))

    fun observeScreenBrightnessMode(): Job? {

        if (!Settings.System.canWrite(context) || !settingsRepository.getWakeUpScreenWhileScanning()) {
            Timber.tag(TAG).d("Screen brightness mode is not supported or disabled")
            return null
        }

        val job = globalScope.launch {
            powerManager.userInteractionFlow(context)
                .filter { Settings.System.canWrite(context) && settingsRepository.getWakeUpScreenWhileScanning() }
                .distinctUntilChanged()
                .collect { isActive ->
                    if (isActive) {
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, originalBrightness)
                    } else {
                        originalBrightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
                    }
                    Timber.tag(TAG).d("User interaction: $isActive, originalBrightness: $originalBrightness")
                }
        }

        job.invokeOnCompletion {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, originalBrightness)
            }
        }

        return job
    }

    fun powerMode(useCached: Boolean = false): PowerMode {
        if (!useCached) {
            cachedPowerMode = when {
                powerManager.isPowerSaveMode -> PowerMode.POWER_SAVING
                !powerManager.isInteractive -> PowerMode.DEFAULT_RESTRICTED
                else -> PowerMode.DEFAULT
            }
        }

        return cachedPowerMode
    }

    fun wakeScreenTemporarily(durationMillis: Long) {

        // Check if screen is already on
        if (powerManager.isInteractive) return

        // Acquire a wake lock to turn on screen at minimum brightness
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "BLERadar:TemporaryWakeLock"
        )

        wakeLock.acquire(durationMillis)

        // Launch coroutine to release wake lock after duration
        globalScope.launch {
            delay(durationMillis)
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    enum class PowerMode(
        val scanDuration: Long,
        val scanInterval: Long,
        val useLocation: Boolean,
        val locationUpdateInterval: Long,
        val useRestrictedBleConfig: Boolean,
        val filterCacheExpirationTime: Long,
        val tryToTurnOnScreen: Boolean,
    ) {
        DEFAULT(
            scanDuration = 5_000L,
            scanInterval = 5_000L,
            useLocation = true,
            locationUpdateInterval = 1_000L, // 1 s: track per-device GPS closely (intentional power trade-off)
            useRestrictedBleConfig = false,
            filterCacheExpirationTime = 3 * 60 * 1000L, // 3 minutes
            tryToTurnOnScreen = false,
        ),
        DEFAULT_RESTRICTED(
            scanDuration = 5_000L,
            scanInterval = 10_000L,
            useLocation = true,
            locationUpdateInterval = 1_000L, // 1 s: track per-device GPS closely (intentional power trade-off)
            useRestrictedBleConfig = true,
            filterCacheExpirationTime = 5 * 60 * 1000L, // 5 minutes
            tryToTurnOnScreen = true,
        ),
        POWER_SAVING(
            scanDuration = 2_000L,
            scanInterval = 15_000L,
            useLocation = false,
            locationUpdateInterval = 60_000L,
            useRestrictedBleConfig = true,
            filterCacheExpirationTime = 10 * 60 * 1000L, // 10 minutes
            tryToTurnOnScreen = false,
        ),
    }

    companion object {
        private const val TAG = "PowerModeHelper"
    }
}