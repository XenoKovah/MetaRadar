package com.darkmentor.data.repo

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Verifies the two boot-startup toggles persist their state across (mock-)reboots.
 *
 * The boot receiver reads these via [SettingsRepository.getRunOnStartup] /
 * [SettingsRepository.getRunConnectAllOnStartup] when [android.content.Intent.ACTION_BOOT_COMPLETED]
 * fires, so persistence is the precondition that makes [BootStartupPolicyTest]'s decision
 * inputs meaningful in production. A simulated reboot is modelled by tearing down the in-
 * memory SharedPreferences fake and rebuilding the [SettingsRepository] over the same backing
 * map — exactly what happens at OS level when the process is killed and reborn from disk.
 */
class SettingsRepositoryBootTogglesTest {

    /**
     * Minimal in-memory SharedPreferences fake. Returns a (SharedPreferences, backing-map) pair
     * — callers can hand the same backing map to a freshly-constructed repository to simulate
     * the process being killed and the prefs read from disk on the next boot.
     */
    private fun fakePrefs(backing: MutableMap<String, Any> = mutableMapOf()): Pair<SharedPreferences, MutableMap<String, Any>> {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getBoolean(any<String>(), any<Boolean>()) } answers {
            (backing[firstArg<String>()] as? Boolean) ?: secondArg<Boolean>()
        }
        every { prefs.getString(any<String>(), any()) } answers { backing[firstArg<String>()] as? String ?: secondArg() }
        every { prefs.getInt(any<String>(), any<Int>()) } answers { backing[firstArg<String>()] as? Int ?: secondArg() }
        every { prefs.getLong(any<String>(), any<Long>()) } answers { backing[firstArg<String>()] as? Long ?: secondArg() }
        every { prefs.edit() } returns editor
        val keySlot = slot<String>()
        val valueSlot = slot<Boolean>()
        every { editor.putBoolean(capture(keySlot), capture(valueSlot)) } answers {
            backing[keySlot.captured] = valueSlot.captured
            editor
        }
        every { editor.apply() } answers {}
        every { editor.commit() } returns true
        return prefs to backing
    }

    /** Simulates a reboot: a fresh SettingsRepository over the same backing map. */
    private fun reboot(backing: MutableMap<String, Any>): SettingsRepository {
        val (prefs, _) = fakePrefs(backing)
        return SettingsRepository(prefs)
    }

    @Test
    fun `default boot toggles are both off (no surprise auto-start after first install)`() {
        val (prefs, _) = fakePrefs()
        val repo = SettingsRepository(prefs)
        assertFalse("device-scan auto-start should default off", repo.getRunOnStartup())
        assertFalse("Connect All auto-start should default off", repo.getRunConnectAllOnStartup())
    }

    @Test
    fun `enabling device-scan auto-start persists across reboot`() {
        val (prefs, backing) = fakePrefs()
        SettingsRepository(prefs).setRunOnStartup(true)
        // Reboot: fresh repo over the same backing map.
        val rebooted = reboot(backing)
        assertTrue(rebooted.getRunOnStartup())
        assertFalse("Connect-All toggle must remain at its default", rebooted.getRunConnectAllOnStartup())
    }

    @Test
    fun `enabling Connect All auto-start persists across reboot`() {
        val (prefs, backing) = fakePrefs()
        SettingsRepository(prefs).setRunConnectAllOnStartup(true)
        val rebooted = reboot(backing)
        assertTrue(rebooted.getRunConnectAllOnStartup())
        assertFalse("device-scan toggle must remain at its default", rebooted.getRunOnStartup())
    }

    @Test
    fun `disabling a previously-enabled toggle persists across reboot`() {
        val (prefs, backing) = fakePrefs()
        val firstBoot = SettingsRepository(prefs)
        firstBoot.setRunOnStartup(true)
        // Simulate a reboot, then user turns the toggle off, then another reboot.
        val secondBoot = reboot(backing)
        assertTrue(secondBoot.getRunOnStartup()) // confirm it survived
        secondBoot.setRunOnStartup(false)
        val thirdBoot = reboot(backing)
        assertFalse(thirdBoot.getRunOnStartup())
    }

    @Test
    fun `bulkRetryForever default is true and persists across reboot`() {
        // The Connect-All boot path reads bulkRetryForever to decide whether to keep the bulk
        // loop spinning. Default behaviour must match what a user-driven Connect All session
        // does, and the user's choice must survive a reboot.
        val (prefs, backing) = fakePrefs()
        val firstBoot = SettingsRepository(prefs)
        assertEquals(true, firstBoot.getBulkRetryForever()) // factory default

        firstBoot.setBulkRetryForever(false)
        val rebooted = reboot(backing)
        assertEquals(false, rebooted.getBulkRetryForever())
    }

    @Test
    fun `independent persistence - flipping one boot toggle does not reset the other`() {
        val (prefs, backing) = fakePrefs()
        val r = SettingsRepository(prefs)
        r.setRunOnStartup(true)
        r.setRunConnectAllOnStartup(true) // (Settings UI normally prevents this; defensive)
        val after = reboot(backing)
        assertTrue(after.getRunOnStartup())
        assertTrue(after.getRunConnectAllOnStartup())

        // Now disable just one — the other survives the next reboot.
        after.setRunOnStartup(false)
        val finalBoot = reboot(backing)
        assertFalse(finalBoot.getRunOnStartup())
        assertTrue(finalBoot.getRunConnectAllOnStartup())
    }

    @Test
    fun `boot-toggle keys are independent of LE-or-BTC discovery toggles`() {
        // Smoke-test: setting a discovery transport toggle must not flip the boot-startup
        // toggles, and vice versa. Catches accidental key collisions in future renames.
        val (prefs, backing) = fakePrefs()
        val r = SettingsRepository(prefs)
        r.setRunOnStartup(true)
        r.setDiscoverBrEdrEnabled(false)
        val rebooted = reboot(backing)
        assertTrue(rebooted.getRunOnStartup())
        assertFalse(rebooted.getDiscoverBrEdrEnabled())
        assertTrue("LE discovery toggle stays at its (true) default", rebooted.getDiscoverLeEnabled())
    }
}
