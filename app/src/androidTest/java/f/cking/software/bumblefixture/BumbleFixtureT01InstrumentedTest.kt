package f.cking.software.bumblefixture

import android.Manifest
import android.bluetooth.le.ScanRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import f.cking.software.domain.interactor.ParseBleAdRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test: minimal bumble-advertised packet — just `Flags` + `Complete
 * Local Name` — must round-trip through the BLE framework into the same
 * raw bytes and parsed fields the DM BT app uses downstream.
 *
 * Pairs with `tests/bumble-fixtures/fixtures.py:T01`. **Run the bumble
 * fixture on the host before this test:**
 *   `python tests/bumble-fixtures/run_fixture.py T01`
 *
 * What this guards: every regression in BLE scan-record delivery,
 * `device.name`/`scanRecord.deviceName` resolution, address-string
 * canonicalisation, connectable/legacy flag plumbing, and
 * [ParseBleAdRecord]'s Flags + Local Name branches. Future fixtures
 * cover other AD types incrementally; this is the foundation.
 *
 * Why a separate class per fixture rather than parameterised tests:
 * each regression a fixture guards has a different triage path (HCI
 * stack vs parser vs UI display), and a focused class with a focused
 * KDoc makes the failure message tell the on-call which slice broke.
 */
@RunWith(AndroidJUnit4::class)
class BumbleFixtureT01InstrumentedTest {

    // BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime perms on Android 12+;
    // ACCESS_FINE_LOCATION is needed for any BLE scan that doesn't use
    // neverForLocation. Granting them here so the test runs cleanly on a
    // freshly-installed APK without manual UI tapping.
    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Mirrors `fixtures.py:T01['address']`. */
    private val expectedAddress = "F0:F1:F2:F3:F4:01"

    /** Mirrors `fixtures.py:T01['expected']['name']`. */
    private val expectedName = "DMBT-T01"

    /** Mirrors `fixtures.py:T01['adv_data']`: Flags(0x06) + CompleteLocalName("DMBT-T01"). */
    private val expectedAdvBytes: ByteArray = byteArrayOf(
        0x02, 0x01, 0x06,                                                  // Flags: 0x06
        0x09, 0x09, 0x44, 0x4D, 0x42, 0x54, 0x2D, 0x54, 0x30, 0x31,        // Complete Local Name "DMBT-T01"
    )

    @Test
    fun T01_minimal_named_advertiser_is_captured_with_correct_address_name_and_flags() {
        val result = BleFixtureScanner.awaitFixture(expectedAddress)

        // --- Framework-level assertions: what the Android BLE stack delivered. ---
        assertEquals(
            "BD_ADDR delivered to onScanResult must match the fixture's address",
            expectedAddress, result.device.address.uppercase(),
        )
        assertTrue(
            "T01 fixture is undirected-connectable-scannable; ScanResult.isConnectable must be true",
            result.isConnectable,
        )
        assertTrue(
            "T01 uses legacy advertising; ScanResult.isLegacy must be true",
            result.isLegacy,
        )

        val scanRecord: ScanRecord = assertNotNull("ScanResult must include a ScanRecord", result.scanRecord).let { result.scanRecord!! }
        assertEquals(
            "scanRecord.deviceName must equal the bumble-advertised Complete Local Name",
            expectedName, scanRecord.deviceName,
        )
        // Raw bytes can include controller-appended trailing zeros; comparing the prefix
        // verifies our records are present without coupling to controller-specific padding.
        val rawBytes = scanRecord.bytes
        assertNotNull("scanRecord.bytes must be non-null on a successful legacy scan", rawBytes)
        assertTrue(
            "scanRecord.bytes (${rawBytes!!.toHex()}) must begin with the fixture's adv payload " +
                    "(${expectedAdvBytes.toHex()}); a mismatch here means the scan callback is dropping bytes " +
                    "or copying the wrong array.",
            rawBytes.size >= expectedAdvBytes.size,
        )
        assertArrayEquals(
            "Adv-payload prefix mismatch — bytes-on-the-wire diverged from fixture",
            expectedAdvBytes, rawBytes.copyOfRange(0, expectedAdvBytes.size),
        )

        // --- Parser-level assertions: same code path DM BT's UI uses. ---
        val flagsFields = ParseBleAdRecord.execute(0x01, byteArrayOf(0x06))
        assertEquals(
            "Flags parser must map 0x06 to a single bits-set string with the two LE-discoverable bits",
            "LE General Discoverable Mode, BR/EDR Not Supported",
            flagsFields.firstOrNull { it.label == "Bits set" }?.value,
        )

        val nameFields = ParseBleAdRecord.execute(0x09, expectedName.toByteArray())
        assertEquals(
            "Complete Local Name parser must round-trip UTF-8 bytes back to the original ASCII name",
            expectedName,
            nameFields.firstOrNull { it.label == "Name" }?.value,
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
