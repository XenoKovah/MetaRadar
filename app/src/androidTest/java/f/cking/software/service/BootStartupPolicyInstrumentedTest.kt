package f.cking.software.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import f.cking.software.data.repo.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * On-device boot-time decision tests.
 *
 * Pairs the unit-test [BootStartupPolicyTest] (decision logic, in-memory inputs) with this
 * device-run variant that wires real Android SharedPreferences through [SettingsRepository]
 * into [decideBootStartup]. Catches regressions that depend on real Android persistence
 * semantics — e.g. SharedPreferences write barriers, default-value resolution, or process-
 * restart edge cases that the JVM mock can't model.
 *
 * **Data safety:** every test runs against a UUID-suffixed test-only prefs file. Production
 * data is never touched. [cleanup] wipes the test prefs after each case; Android removes the
 * empty file when the app is uninstalled.
 *
 * "Reboot" is simulated by dropping the [SettingsRepository] reference and re-constructing
 * over the same prefs file — the same persistence path the OS exercises when the process is
 * killed and respawned. The decision call models what BootBroadcastReceiver does on
 * `Intent.ACTION_BOOT_COMPLETED`.
 */
@RunWith(AndroidJUnit4::class)
class BootStartupPolicyInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testPrefsName = "test_boot_policy_prefs_${UUID.randomUUID()}"

    @After
    fun cleanup() {
        context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun freshRepo(): SettingsRepository =
        SettingsRepository(context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE))

    /**
     * Models exactly what `BootBroadcastReceiver.tryToRunService` does after an
     * `Intent.ACTION_BOOT_COMPLETED` broadcast: read fresh values from prefs, hand them to
     * [decideBootStartup], get a [BootStartupAction] back. Permissions are passed in by the
     * caller because they aren't a SharedPreferences value.
     */
    private fun decisionAfterReboot(blePermissionsAllowed: Boolean): BootStartupAction {
        val booted = freshRepo()
        return decideBootStartup(
            runDeviceScanOnStartup = booted.getRunOnStartup(),
            runConnectAllOnStartup = booted.getRunConnectAllOnStartup(),
            blePermissionsAllowed = blePermissionsAllowed,
            bulkRetryForever = booted.getBulkRetryForever(),
        )
    }

    @Test
    fun fresh_install_with_no_toggles_set_yields_idle_on_boot() {
        // Default state: nothing set. Boot does nothing.
        assertSame(BootStartupAction.Idle, decisionAfterReboot(blePermissionsAllowed = true))
    }

    @Test
    fun device_scan_toggle_set_then_reboot_yields_StartDeviceScan() {
        freshRepo().setRunOnStartup(true)
        assertSame(BootStartupAction.StartDeviceScan, decisionAfterReboot(blePermissionsAllowed = true))
    }

    @Test
    fun connect_all_toggle_set_then_reboot_yields_StartConnectAll_with_persisted_retryForever() {
        val firstBoot = freshRepo()
        firstBoot.setRunConnectAllOnStartup(true)
        firstBoot.setBulkRetryForever(false)
        assertEquals(
            BootStartupAction.StartConnectAll(retryForever = false),
            decisionAfterReboot(blePermissionsAllowed = true),
        )
    }

    @Test
    fun connect_all_with_default_retryForever_uses_factory_default() {
        // setBulkRetryForever never called — boot must read the factory default (true).
        freshRepo().setRunConnectAllOnStartup(true)
        assertEquals(
            BootStartupAction.StartConnectAll(retryForever = true),
            decisionAfterReboot(blePermissionsAllowed = true),
        )
    }

    @Test
    fun device_scan_toggle_with_revoked_permissions_yields_PermissionError_with_correct_label() {
        freshRepo().setRunOnStartup(true)
        val action = decisionAfterReboot(blePermissionsAllowed = false)
        assertTrue("expected PermissionError, got $action", action is BootStartupAction.PermissionError)
        assertEquals(BOOT_STARTUP_LABEL_DEVICE_SCAN, (action as BootStartupAction.PermissionError).label)
    }

    @Test
    fun connect_all_toggle_with_revoked_permissions_yields_PermissionError_with_correct_label() {
        freshRepo().setRunConnectAllOnStartup(true)
        val action = decisionAfterReboot(blePermissionsAllowed = false)
        assertTrue("expected PermissionError, got $action", action is BootStartupAction.PermissionError)
        assertEquals(BOOT_STARTUP_LABEL_CONNECT_ALL, (action as BootStartupAction.PermissionError).label)
    }

    @Test
    fun a_long_chain_of_simulated_reboots_does_not_drift() {
        // Hammer the persistence path — set, reboot, read, set again, reboot, read — to catch
        // any weird write-barrier or caching issue that only surfaces with many cycles. If the
        // value drifts at any cycle we'll see it in the assertion that fires.
        val ITERATIONS = 8
        for (i in 0 until ITERATIONS) {
            val expected = (i % 2 == 0)
            freshRepo().setRunOnStartup(expected)
            val readBack = freshRepo().getRunOnStartup()
            assertEquals("iteration $i drifted (expected=$expected, got=$readBack)", expected, readBack)
        }
    }

    @Test
    fun toggling_one_setting_does_not_corrupt_the_other_across_reboot() {
        val firstBoot = freshRepo()
        firstBoot.setRunOnStartup(true)
        firstBoot.setRunConnectAllOnStartup(true) // (Settings UI normally prevents this; defensive)
        firstBoot.setBulkRetryForever(false)

        val rebooted = freshRepo()
        // After reboot, the device-scan path takes precedence per [decideBootStartup]'s
        // documented "device scan wins when both are on" rule.
        val action = decideBootStartup(
            runDeviceScanOnStartup = rebooted.getRunOnStartup(),
            runConnectAllOnStartup = rebooted.getRunConnectAllOnStartup(),
            blePermissionsAllowed = true,
            bulkRetryForever = rebooted.getBulkRetryForever(),
        )
        assertSame(BootStartupAction.StartDeviceScan, action)

        // Now flip device-scan off; Connect-All should win on the next reboot, with retryForever
        // still propagated from the persisted false.
        rebooted.setRunOnStartup(false)
        val nextBoot = freshRepo()
        assertEquals(
            BootStartupAction.StartConnectAll(retryForever = false),
            decideBootStartup(
                runDeviceScanOnStartup = nextBoot.getRunOnStartup(),
                runConnectAllOnStartup = nextBoot.getRunConnectAllOnStartup(),
                blePermissionsAllowed = true,
                bulkRetryForever = nextBoot.getBulkRetryForever(),
            ),
        )
    }
}
