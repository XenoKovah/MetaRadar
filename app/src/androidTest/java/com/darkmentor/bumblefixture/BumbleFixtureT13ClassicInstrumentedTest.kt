package com.darkmentor.bumblefixture

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.darkmentor.domain.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BR/EDR-side counterpart to [BumbleFixtureT01InstrumentedTest]. Pins the contract that a
 * BR/EDR-discoverable device the bumble fixture has placed on the air shows up in DM BT's
 * inquiry pipeline with the expected `local name`, `Class of Device`, and `device.type`.
 *
 * Pairs with `tests/bumble-fixtures/fixtures.py:T13`. **Run the bumble fixture on the host
 * before this test:**
 *   `python tests/bumble-fixtures/run_fixture.py T13`
 *
 * What this guards:
 *   - The full BroadcastReceiver pipeline that
 *     [com.darkmentor.data.helpers.BrEdrDiscoveryHelper] depends on:
 *     `BluetoothAdapter.startDiscovery()` actually fires `ACTION_FOUND`,
 *     `EXTRA_DEVICE` carries a usable [BluetoothDevice], `EXTRA_CLASS` (or
 *     `device.bluetoothClass`) returns a populated [BluetoothClass], and
 *     `EXTRA_NAME`/`device.name` resolves to the local name we wrote via
 *     `HCI_Write_Local_Name_Command`.
 *   - The `device.type` mapping that
 *     [com.darkmentor.domain.model.Transport.fromAndroidDeviceType] uses to derive the
 *     persisted [Transport]. A Realtek dual-mode dongle in BR/EDR-discoverable mode shows up
 *     as either `DEVICE_TYPE_CLASSIC` (1, BR/EDR-only response) or `DEVICE_TYPE_DUAL` (3,
 *     when the controller advertises dual-mode capabilities in the inquiry response).
 *     Either is correct; the only outcome that would indicate a regression is
 *     `DEVICE_TYPE_LE` (2), which would mean the BLU View 5's leaked-LE-scan path
 *     misclassified the controller (or the receiver's `EXTRA_DEVICE` wasn't really the same
 *     device that responded to the BR/EDR inquiry).
 *   - `BluetoothClass.getMajorDeviceClass()` returning the major class (PHONE = 0x0200)
 *     that the fixture set via `HCI_Write_Class_Of_Device_Command(0x000540)`. Catches a
 *     regression where the framework drops the CoD field on its way through the broadcast
 *     extras.
 *
 * Note on flakiness: a BR/EDR inquiry takes ~10.24s by spec, and on the BLU View 5 the
 * known leaked-LE-scan recovery cycle can absorb one full window before the next inquiry
 * goes through. The timeout in [BrEdrFixtureScanner] is set generously (25s) for that
 * reason — if a CI run does flake here, pin the cause via logcat
 * (`adb -s 7040016024296523 logcat -d | grep BrEdrDiscoveryHelper`) before increasing the
 * timeout further.
 */
@RunWith(AndroidJUnit4::class)
class BumbleFixtureT13ClassicInstrumentedTest {

    // BR/EDR inquiry needs BLUETOOTH_SCAN (Android 12+), BLUETOOTH_CONNECT (for
    // device.bondState side reads), and ACCESS_FINE_LOCATION (without `neverForLocation` on
    // the BLUETOOTH_SCAN flag in the manifest, which DM BT does NOT use because it relies on
    // location for the GPS pin metadata path).
    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Mirrors `fixtures.py:T13['local_name']`. Used as the BR/EDR scan filter. */
    private val expectedName = "DMBT-T13"

    /** Mirrors `fixtures.py:T13['expected']['major_device_class']`. PHONE = 0x0200. */
    private val expectedMajorDeviceClass = BluetoothClass.Device.Major.PHONE

    @Test
    fun T13_classic_discoverable_device_is_captured_with_correct_name_type_and_major_class() {
        val hit = BrEdrFixtureScanner.awaitNamedDevice(expectedName)

        // ---- Identity: name + (some) BD_ADDR. We don't pin the BD_ADDR because BR/EDR uses
        // the controller's hardware-assigned public address; the dongle may be replaced and
        // the test should keep passing.
        assertEquals(expectedName, hit.name)
        assertNotNull("BR/EDR inquiry hit must carry a non-null BD_ADDR", hit.address)
        assertTrue(
            "BD_ADDR must be a colon-separated 6-byte form (MAC); got '${hit.address}'",
            Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(hit.address),
        )

        // ---- Transport classification: must be CLASSIC or DUAL — never LE. The latter
        // would indicate either a leaked-LE-scan misclassification on the BLU View 5's
        // Qualcomm stack, or a logic regression in fromAndroidDeviceType, both of which
        // would silently break Connect All for BR/EDR peers.
        assertTrue(
            "device.type for a BR/EDR-discoverable bumble fixture must be CLASSIC (1) or " +
                    "DUAL (3); got ${hit.deviceType}. " +
                    "DEVICE_TYPE_LE here would mean the inquiry-response classification path is broken.",
            hit.deviceType == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                    hit.deviceType == BluetoothDevice.DEVICE_TYPE_DUAL,
        )
        // And the domain-model mapping must produce a transport that wears the BR/EDR badge.
        val derivedTransport = Transport.fromAndroidDeviceType(hit.deviceType)
        assertTrue(
            "Transport.fromAndroidDeviceType($derivedTransport) must be BR/EDR-flavoured; got $derivedTransport",
            derivedTransport == Transport.BREDR || derivedTransport == Transport.DUAL,
        )

        // ---- Class of Device: HCI_Write_Class_Of_Device_Command(0x000540) was issued by
        // the fixture. Android's BluetoothClass exposes the major class as PHONE (0x0200).
        // We don't pin the full deviceClass int because some Android versions filter the
        // service-class bits out of the value reported on the broadcast — the major class
        // is the contract that's stable across SDK levels.
        assertEquals(
            "Major device class must be PHONE (0x0200) per the fixture's CoD = 0x000540; " +
                    "got 0x${hit.majorDeviceClass?.toString(16)?.padStart(4, '0')}",
            expectedMajorDeviceClass,
            hit.majorDeviceClass,
        )
    }
}
