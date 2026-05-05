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
 * Service-UUID capture test — exercises the AD type 0x03 (Complete List
 * of 16-bit Service Class UUIDs) parser and the framework-level
 * `scanRecord.serviceUuids` projection.
 *
 * Pairs with `tests/bumble-fixtures/fixtures.py:T02`. **Run the bumble
 * fixture on the host before this test:**
 *   `python tests/bumble-fixtures/run_fixture.py T02`
 *
 * What this guards:
 *   - The 16-bit UUID encoding is little-endian on the wire; if the
 *     parser regresses to big-endian the fixture's HRS (0x180D) shows
 *     up as 0x0D18 (= "Pulse Oximeter Service"), a meaningful but
 *     wrong service. We pin the LE order explicitly.
 *   - Android's [ScanRecord.getServiceUuids] expands 16-bit values to
 *     the canonical 128-bit form using the BLUETOOTH_BASE_UUID prefix.
 *     Verifying the expansion catches platform-API-version regressions.
 *   - DM BT's `previouslyNoticedServicesUUIDs` cache (in
 *     [f.cking.software.data.helpers.BleFiltersProvider]) is fed from
 *     the same projection; a regression here propagates into the
 *     background-scan filter rebuild path too.
 */
@RunWith(AndroidJUnit4::class)
class BumbleFixtureT02InstrumentedTest {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /** Mirrors `fixtures.py:T02['address']`. */
    private val expectedAddress = "F0:F1:F2:F3:F4:02"

    /** Mirrors `fixtures.py:T02['expected']['name']`. */
    private val expectedName = "DMBT-T02"

    /** Mirrors `fixtures.py:T02['adv_data']`. Flags + Name + Complete-16-bit list. */
    private val expectedAdvBytes: ByteArray = byteArrayOf(
        0x02, 0x01, 0x06,                                                  // Flags: 0x06
        0x09, 0x09, 0x44, 0x4D, 0x42, 0x54, 0x2D, 0x54, 0x30, 0x32,        // Complete Local Name "DMBT-T02"
        0x07, 0x03, 0x0D, 0x18, 0x0F, 0x18, 0x09, 0x18,                    // Complete 16-bit UUIDs: 180D, 180F, 1809
    )

    /** SIG-allocated 16-bit UUIDs the fixture advertises. */
    private val expectedUuids16: List<Int> = listOf(0x180D, 0x180F, 0x1809)

    /** Canonical 128-bit form Android's [ScanRecord] yields after BLUETOOTH_BASE_UUID expansion. */
    private val expectedUuids128: List<String> = expectedUuids16.map { sigUuid16ToCanonical128(it) }

    @Test
    fun T02_complete_16bit_uuid_list_is_captured_in_LE_order_and_expanded_to_canonical_128() {
        val result = BleFixtureScanner.awaitFixture(expectedAddress)
        val scanRecord: ScanRecord = assertNotNull("ScanResult must include a ScanRecord", result.scanRecord).let { result.scanRecord!! }

        // --- Framework-level: address, name, raw bytes, serviceUuids projection. ---
        assertEquals(expectedAddress, result.device.address.uppercase())
        assertEquals(expectedName, scanRecord.deviceName)

        val rawBytes = scanRecord.bytes
        assertNotNull("scanRecord.bytes must be non-null", rawBytes)
        assertTrue("scanRecord.bytes shorter than fixture payload", rawBytes!!.size >= expectedAdvBytes.size)
        assertArrayEquals(
            "Adv-payload prefix mismatch — UUID-list record didn't reach the scan callback verbatim",
            expectedAdvBytes, rawBytes.copyOfRange(0, expectedAdvBytes.size),
        )

        val seen = scanRecord.serviceUuids?.map { it.uuid.toString().lowercase() }?.toSet().orEmpty()
        for (expected in expectedUuids128) {
            assertTrue(
                "scanRecord.serviceUuids must contain canonical 128-bit form $expected; got $seen. " +
                        "If only one UUID is missing, the parser might be stopping at the first record. " +
                        "If all are missing, getServiceUuids() may not be expanding 16-bit lists at all.",
                seen.contains(expected),
            )
        }

        // --- Parser-level: DM BT's own ParseBleAdRecord must surface every UUID, in LE order. ---
        val rawUuidListPayload = byteArrayOf(0x0D, 0x18, 0x0F, 0x18, 0x09, 0x18)
        val parsed = ParseBleAdRecord.execute(0x03, rawUuidListPayload)
        val parsedHex = parsed
            .filter { it.label == "Service UUID (16-bit)" }
            .map { it.value }
        assertEquals(
            "ParseBleAdRecord must emit one Service-UUID-16 field per UUID, in on-air order",
            listOf("0x180D", "0x180F", "0x1809"),
            parsedHex,
        )
    }

    /**
     * Build the canonical 128-bit form of a SIG 16-bit UUID, the way
     * `ScanRecord.getServiceUuids` returns it. Equivalent to
     *   `0000XXXX-0000-1000-8000-00805F9B34FB`
     * for `XXXX = uuid16` — the well-known Bluetooth Base UUID.
     */
    private fun sigUuid16ToCanonical128(uuid16: Int): String =
        "0000%04x-0000-1000-8000-00805f9b34fb".format(uuid16)
}
