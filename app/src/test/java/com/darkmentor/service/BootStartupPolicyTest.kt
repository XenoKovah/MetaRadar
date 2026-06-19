package com.darkmentor.service

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertSame
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Unit tests for the boot-time decision logic that BootBroadcastReceiver delegates to.
 *
 * These cover the "across reboots" behaviour the user can configure via Settings:
 *   - Background DEVICE SCAN auto-resume on boot (the "Launch device scan at system startup"
 *     toggle, persisted in SettingsRepository.getRunOnStartup).
 *   - Background CONNECT ALL auto-resume on boot ("Launch Connect All at system startup",
 *     persisted in SettingsRepository.getRunConnectAllOnStartup). Connect All always runs in
 *     continuous ("retry forever") mode — there is no longer a per-session toggle.
 *
 * The decision is pure (input → action), so we sweep the input space without standing up a
 * real Context / Koin container / BroadcastReceiver lifecycle.
 */
class BootStartupPolicyTest {

    // ---- Idle: neither auto-start toggle is enabled ------------------------------------------

    @Test
    fun `both toggles off yields Idle (default factory state)`() {
        val action = decideBootStartup(
            runDeviceScanOnStartup = false,
            runConnectAllOnStartup = false,
            blePermissionsAllowed = true,
        )
        assertSame(BootStartupAction.Idle, action)
    }

    @Test
    fun `both toggles off and permissions denied still yields Idle`() {
        // Permissions only matter when at least one toggle is on; otherwise the receiver does
        // nothing and we never need to journal a permission error.
        val action = decideBootStartup(
            runDeviceScanOnStartup = false,
            runConnectAllOnStartup = false,
            blePermissionsAllowed = false,
        )
        assertSame(BootStartupAction.Idle, action)
    }

    // ---- Background device-scan auto-resume on boot ------------------------------------------

    @Test
    fun `device scan toggle plus granted permissions starts the device scan`() {
        val action = decideBootStartup(
            runDeviceScanOnStartup = true,
            runConnectAllOnStartup = false,
            blePermissionsAllowed = true,
        )
        assertSame(BootStartupAction.StartDeviceScan, action)
    }

    @Test
    fun `device scan toggle but missing permissions journals a permission error`() {
        val action = decideBootStartup(
            runDeviceScanOnStartup = true,
            runConnectAllOnStartup = false,
            blePermissionsAllowed = false,
        )
        assertTrue("expected PermissionError, got $action", action is BootStartupAction.PermissionError)
        assertEquals(BOOT_STARTUP_LABEL_DEVICE_SCAN, (action as BootStartupAction.PermissionError).label)
    }

    // ---- Background Connect-All auto-resume on boot ------------------------------------------

    @Test
    fun `connect all toggle plus granted permissions resumes Connect All`() {
        val action = decideBootStartup(
            runDeviceScanOnStartup = false,
            runConnectAllOnStartup = true,
            blePermissionsAllowed = true,
        )
        assertSame(BootStartupAction.StartConnectAll, action)
    }

    @Test
    fun `connect all toggle but missing permissions journals a connect-all permission error`() {
        // Distinct label from device-scan so the Journal entry is informative.
        val action = decideBootStartup(
            runDeviceScanOnStartup = false,
            runConnectAllOnStartup = true,
            blePermissionsAllowed = false,
        )
        assertTrue("expected PermissionError, got $action", action is BootStartupAction.PermissionError)
        assertEquals(BOOT_STARTUP_LABEL_CONNECT_ALL, (action as BootStartupAction.PermissionError).label)
    }

    // ---- Mutual-exclusivity precedence (Settings UI prevents both being on, but we still want
    // a deterministic fallback if prefs ever land in that state across an upgrade or import).

    @Test
    fun `if both toggles are somehow on then device scan wins`() {
        // Documents the existing receiver behaviour pre-extraction: device scan wins when both
        // are enabled. The Settings UI enforces mutual exclusion, so this is purely defensive.
        val action = decideBootStartup(
            runDeviceScanOnStartup = true,
            runConnectAllOnStartup = true,
            blePermissionsAllowed = true,
        )
        assertSame(BootStartupAction.StartDeviceScan, action)
    }

    @Test
    fun `if both toggles are on but permissions denied then connect-all label wins`() {
        // Connect-All produces a more user-actionable error message ("you opted into Connect
        // All but it can't run") so when both toggles claim the boot path, we prefer the more
        // informative label for the journal entry.
        val action = decideBootStartup(
            runDeviceScanOnStartup = true,
            runConnectAllOnStartup = true,
            blePermissionsAllowed = false,
        )
        assertTrue(action is BootStartupAction.PermissionError)
        assertEquals(BOOT_STARTUP_LABEL_CONNECT_ALL, (action as BootStartupAction.PermissionError).label)
    }
}
