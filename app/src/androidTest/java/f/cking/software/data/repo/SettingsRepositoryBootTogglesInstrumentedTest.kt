package f.cking.software.data.repo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * On-device boot-toggle persistence tests.
 *
 * These run via `./gradlew :app:connectedGithubDebugAndroidTest` and use REAL Android
 * [android.content.SharedPreferences] backed by a real on-disk prefs file — exercising the same
 * persistence path that survives an actual reboot. The unit-test variant in
 * [SettingsRepositoryBootTogglesTest] uses an in-memory mockk fake; this variant catches
 * regressions that would only surface on real Android (commit/apply semantics, default-value
 * resolution, etc).
 *
 * **Data safety:** every test routes its prefs through a UUID-suffixed test-only file
 * (`test_boot_toggles_prefs_*`). Production prefs and DB are never touched. [cleanup] wipes
 * the test prefs after each test so successive runs start from a clean slate, and Android
 * removes the empty file when the app is uninstalled.
 *
 * "Reboot" is simulated by dropping the [SettingsRepository] reference and constructing a
 * fresh one over the same prefs file — exactly what happens when the OS kills the process and
 * `BootBroadcastReceiver` fires on next boot.
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryBootTogglesInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPrefsName = "test_boot_toggles_prefs_${UUID.randomUUID()}"

    @After
    fun cleanup() {
        // Wipe ONLY our test-only prefs file. Production prefs are addressed by a different
        // name (the `sharedPreferencesName` passed to DataModule), so this can't touch them.
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun freshRepo(): SettingsRepository =
        SettingsRepository(context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE))

    @Test
    fun default_boot_toggles_are_both_off_on_a_fresh_install() {
        val repo = freshRepo()
        assertFalse("device-scan auto-start defaults to off", repo.getRunOnStartup())
        assertFalse("Connect-All auto-start defaults to off", repo.getRunConnectAllOnStartup())
    }

    @Test
    fun device_scan_auto_start_persists_across_simulated_reboot() {
        freshRepo().setRunOnStartup(true)
        // Drop the reference; construct a fresh repo over the same on-disk prefs file.
        val nextBoot = freshRepo()
        assertTrue("device-scan toggle survives the reboot", nextBoot.getRunOnStartup())
        assertFalse("Connect-All toggle stays at its default", nextBoot.getRunConnectAllOnStartup())
    }

    @Test
    fun connect_all_auto_start_persists_across_simulated_reboot() {
        freshRepo().setRunConnectAllOnStartup(true)
        val nextBoot = freshRepo()
        assertTrue("Connect-All toggle survives the reboot", nextBoot.getRunConnectAllOnStartup())
        assertFalse("device-scan toggle stays at its default", nextBoot.getRunOnStartup())
    }

    @Test
    fun disabling_a_previously_enabled_toggle_persists_across_reboot() {
        // Boot 1: enable.
        freshRepo().setRunOnStartup(true)
        // Boot 2: confirm survival, then disable.
        val secondBoot = freshRepo()
        assertTrue(secondBoot.getRunOnStartup())
        secondBoot.setRunOnStartup(false)
        // Boot 3: the disable persists.
        val thirdBoot = freshRepo()
        assertFalse(thirdBoot.getRunOnStartup())
    }

    @Test
    fun bulk_retry_forever_survives_reboot_and_drives_connect_all_action() {
        // Connect-All boot path reads bulkRetryForever to decide whether to keep the bulk loop
        // spinning. The user's last choice must survive a reboot.
        val firstBoot = freshRepo()
        assertEquals("factory default is true", true, firstBoot.getBulkRetryForever())
        firstBoot.setBulkRetryForever(false)

        val nextBoot = freshRepo()
        assertEquals("flipped value survives", false, nextBoot.getBulkRetryForever())
    }

    @Test
    fun toggles_are_independent_across_reboot() {
        val repo = freshRepo()
        repo.setRunOnStartup(true)
        repo.setRunConnectAllOnStartup(true) // Settings UI normally prevents this; defensive.
        val nextBoot = freshRepo()
        assertTrue(nextBoot.getRunOnStartup())
        assertTrue(nextBoot.getRunConnectAllOnStartup())

        // Flip just one — the other survives the next reboot.
        nextBoot.setRunOnStartup(false)
        val thirdBoot = freshRepo()
        assertFalse(thirdBoot.getRunOnStartup())
        assertTrue("Connect-All survived independently", thirdBoot.getRunConnectAllOnStartup())
    }

    @Test
    fun discovery_transport_toggles_default_to_true_and_persist_independently() {
        // BLE/BTC defaults are true (out-of-box behaviour: scan both transports). Verify on a
        // real-prefs round trip — the JVM mock test covers the same logic but at this layer
        // we want to catch any platform-specific default-resolution surprise.
        val firstBoot = freshRepo()
        assertTrue("LE discovery defaults on", firstBoot.getDiscoverLeEnabled())
        assertTrue("BTC discovery defaults on", firstBoot.getDiscoverBrEdrEnabled())

        firstBoot.setDiscoverLeEnabled(false)
        val nextBoot = freshRepo()
        assertFalse("LE flip survives", nextBoot.getDiscoverLeEnabled())
        assertTrue("BTC stays on (independent key)", nextBoot.getDiscoverBrEdrEnabled())
    }

    @Test
    fun keep_screen_on_default_is_true_for_real_prefs() {
        // The default-value flip from false to true (committed in ac1a5fb) needs to be visible
        // through real SharedPreferences.getBoolean(...) calls, not just the in-memory fake.
        val repo = freshRepo()
        assertTrue("Keep Screen On defaults to true", repo.getWakeUpScreenWhileScanning())

        repo.setWakeUpScreenWhileScanning(false)
        val nextBoot = freshRepo()
        assertFalse("user's off-choice persists across reboot", nextBoot.getWakeUpScreenWhileScanning())
    }
}
