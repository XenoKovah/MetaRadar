package com.darkmentor.domain.interactor

import com.darkmentor.data.helpers.CluesRepository
import com.darkmentor.domain.model.DeviceData
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Test

/**
 * Coverage for the Samsung "Galaxy" advertised-name rule (VendorIdentifier.identify step 5).
 *
 * In captured scan data the bulk of Galaxy devices advertise a rotating RPA address with no
 * Samsung MSD company id and no usable OUI, so the advertised name is the only reliable Samsung
 * signal. These tests build a [DeviceData] with manufacturerInfo=null, a random address type,
 * no service UUIDs, and rowDataEncoded=null — so the name is the ONLY thing identify() can key
 * on, and no android.util.Base64 decode is triggered in this JVM unit test.
 */
class VendorIdentifierSamsungNameTest {

    private val clues: CluesRepository = mockk<CluesRepository>(relaxed = true)
    private val identifier = VendorIdentifier(clues)

    private fun device(name: String?, addressType: Int? = 1): DeviceData = DeviceData(
        address = "12:34:56:78:9A:BC",
        name = name,
        lastDetectTimeMs = 0,
        firstDetectTimeMs = 0,
        manufacturerInfo = null,
        detectCount = 1,
        customName = null,
        rssi = null,
        systemAddressType = addressType,
        deviceClass = null,
        isPaired = false,
        servicesUuids = emptyList(),
        rowDataEncoded = null,
        isConnectable = true,
    )

    @Test
    fun `Galaxy-named device with no other signal is classified Samsung`() {
        assertEquals(VendorIdentifier.Vendor.SAMSUNG, identifier.identifyVendor(device("Galaxy S24 Ultra")))
        assertTrue(identifier.isSamsung(device("Galaxy Buds3 Pro (B711) LE")))
    }

    @Test
    fun `Galaxy name match is case-insensitive and tolerates leading whitespace`() {
        assertTrue(identifier.isSamsung(device("  galaxy watch7")))
        assertTrue(identifier.isSamsung(device("GALAXY Z Fold7")))
    }

    @Test
    fun `non-Galaxy names are not Samsung`() {
        assertNull(identifier.identifyVendor(device("Pixel Buds Pro")))
        assertFalse(identifier.isSamsung(device("Pixel Buds Pro")))
        assertFalse(identifier.isSamsung(device(null)))
    }

    @Test
    fun `Galaxy must be a name prefix, not a substring`() {
        // Avoids matching e.g. a third-party "Cool Galaxy Speaker".
        assertNull(identifier.identifyVendor(device("Cool Galaxy Speaker")))
    }

    @Test
    fun `shouldSkip honours the skipSamsung flag for a Galaxy device`() {
        val galaxy = device("Galaxy A16 5G")
        assertTrue(identifier.shouldSkip(galaxy, skipApple = false, skipSamsung = true))
        assertFalse(identifier.shouldSkip(galaxy, skipApple = false, skipSamsung = false))
        assertFalse(
            "skipApple must not skip a Samsung device",
            identifier.shouldSkip(galaxy, skipApple = true, skipSamsung = false),
        )
    }

    @Test
    fun `shouldSkipByName skips a Galaxy GATT-read name only when skipSamsung is on`() {
        // The post-connect GATT 0x2A00 path: a Galaxy device that didn't advertise its name is
        // only recognisable as Samsung once the GAP Device Name characteristic is read.
        assertTrue(identifier.shouldSkipByName("Galaxy S24", skipApple = false, skipSamsung = true))
        assertFalse(identifier.shouldSkipByName("Galaxy S24", skipApple = false, skipSamsung = false))
        // skipApple has no name rule; a non-Galaxy or null name is never name-skipped.
        assertFalse(identifier.shouldSkipByName("Galaxy S24", skipApple = true, skipSamsung = false))
        assertFalse(identifier.shouldSkipByName("Pixel 9 Pro", skipApple = true, skipSamsung = true))
        assertFalse(identifier.shouldSkipByName(null, skipApple = true, skipSamsung = true))
    }

    @Test
    fun `shouldSkipByScanRecord detects Galaxy from a Complete Local Name frame`() {
        // [02 01 06] Flags, then [len, 0x09, <utf8>] Complete Local Name = "Galaxy Watch".
        val name = "Galaxy Watch".toByteArray(Charsets.UTF_8)
        val raw = byteArrayOf(0x02, 0x01, 0x06) +
            byteArrayOf((name.size + 1).toByte(), 0x09.toByte()) + name
        assertTrue(
            identifier.shouldSkipByScanRecord(
                rawScanRecord = raw,
                address = "12:34:56:78:9A:BC",
                addressType = 1,
                skipApple = false,
                skipSamsung = true,
            )
        )
        // A non-Galaxy Local Name in the same shape is not skipped.
        val other = "Pixel Watch".toByteArray(Charsets.UTF_8)
        val otherRaw = byteArrayOf(0x02, 0x01, 0x06) +
            byteArrayOf((other.size + 1).toByte(), 0x09.toByte()) + other
        assertFalse(
            identifier.shouldSkipByScanRecord(otherRaw, "12:34:56:78:9A:BC", 1, false, true)
        )
    }
}
