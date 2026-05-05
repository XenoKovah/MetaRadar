package f.cking.software.domain.interactor

import f.cking.software.data.helpers.BluetoothSIG
import java.util.UUID

/**
 * Per-UUID human-readable formatter for GATT characteristic values. Returns a multi-line
 * string for known structured-binary characteristics (e.g. PnP ID = 0x2A50), or null when
 * the UUID isn't one we know how to decode — letting the caller fall back to the generic
 * UTF-8 / hex display path.
 *
 * Add new entries by extending [format]'s when-branch + a private formatter; keep the
 * formatters pure (no Android types) so they're trivially JVM-unit-testable.
 */
object GattValueFormatter {

    private val PNP_ID_UUID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    // 0x2B3A "Server Supported Features" — 1-byte bitfield, bit 0 = EATT Supported.
    private val SERVER_SUPPORTED_FEATURES_UUID: UUID = UUID.fromString("00002b3a-0000-1000-8000-00805f9b34fb")
    // 0x2B29 "Client Supported Features" — variable-length bitfield (≥ 1 byte). Defined bits
    // per BT Core Spec v5.4 Vol 3 Part G § 7.2:
    //   bit 0 = Robust Caching, bit 1 = EATT Bearer, bit 2 = Multiple Handle Value Notifications.
    // The peer can return a longer value with reserved-future bits set to 0.
    private val CLIENT_SUPPORTED_FEATURES_UUID: UUID = UUID.fromString("00002b29-0000-1000-8000-00805f9b34fb")

    fun format(uuid: UUID, bytes: ByteArray): String? = when (uuid) {
        PNP_ID_UUID -> formatPnpId(bytes)
        SERVER_SUPPORTED_FEATURES_UUID -> formatServerSupportedFeatures(bytes)
        CLIENT_SUPPORTED_FEATURES_UUID -> formatClientSupportedFeatures(bytes)
        else -> null
    }

    /**
     * GATT 0x2A50 PnP ID — fixed 7-byte struct (BT GATT spec, Generic Access Profile):
     *   octet 0     : Vendor ID Source (0x01 = Bluetooth SIG, 0x02 = USB IF)
     *   octets 1..2 : Vendor ID (uint16, little-endian)
     *   octets 3..4 : Product ID (uint16, little-endian)
     *   octets 5..6 : Product Version (uint16) — formatted as J.M.N where the high byte is
     *                 the major number, the upper nibble of the low byte is the minor, and
     *                 the lower nibble of the low byte is the sub-minor.
     *
     * For Bluetooth-SIG-sourced vendor IDs we look up the assigned company name via the
     * existing [BluetoothSIG.bluetoothSIG] table — the same one that resolves manufacturer
     * IDs from MSD advertisement bytes.
     */
    private fun formatPnpId(bytes: ByteArray): String? {
        if (bytes.size != PNP_ID_LEN) return null
        val source = bytes[0].toInt() and 0xFF
        val vendorId = leUint16(bytes, 1)
        val productId = leUint16(bytes, 3)
        val versionRaw = leUint16(bytes, 5)

        val major = (versionRaw shr 8) and 0xFF
        val minor = (versionRaw shr 4) and 0x0F
        val subMinor = versionRaw and 0x0F

        val sourceLabel = when (source) {
            VENDOR_SOURCE_BLUETOOTH_SIG -> "Bluetooth SIG"
            VENDOR_SOURCE_USB_IF -> "USB Implementer's Forum"
            else -> "0x%02X".format(source)
        }
        val vendorName = if (source == VENDOR_SOURCE_BLUETOOTH_SIG) BluetoothSIG.bluetoothSIG[vendorId] else null
        val vendorLabel = "0x%04X".format(vendorId) + (vendorName?.let { " ($it)" } ?: "")

        return "Vendor source: $sourceLabel\n" +
                "Vendor ID: $vendorLabel\n" +
                "Product ID: 0x%04X\n".format(productId) +
                "Product Version: $major.$minor.$subMinor"
    }

    private fun leUint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /**
     * 0x2B3A Server Supported Features — single-byte bitfield. Only one bit is currently
     * defined in the BT Core Spec (5.4 Vol 3 Part G § 7.4): bit 0 = EATT Supported. Anything
     * else is reserved-future-use; we still surface the raw byte so non-spec bits don't get
     * silently dropped.
     */
    private fun formatServerSupportedFeatures(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val raw = bytes[0].toInt() and 0xFF
        val supported = mutableListOf<String>()
        if (raw and 0x01 != 0) supported += "EATT Supported"
        val reserved = raw and 0xFE
        if (reserved != 0) supported += "Reserved bits: 0b" + Integer.toBinaryString(reserved).padStart(8, '0')
        val flags = if (supported.isEmpty()) "(no features set)" else supported.joinToString(", ")
        return "Server Supported Features bitfield: 0x%02X\n%s".format(raw, flags)
    }

    /**
     * 0x2B29 Client Supported Features — variable-length bitfield (≥ 1 byte). Only the first
     * byte carries currently-defined bits per BT Core Spec v5.4 Vol 3 Part G § 7.2:
     *   bit 0 = Robust Caching, bit 1 = EATT Bearer, bit 2 = Multiple Handle Value Notifications.
     * Trailing bytes are reserved for future use; we still hex-print the full value so an
     * extended peer feature isn't lost from view.
     */
    private fun formatClientSupportedFeatures(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val raw0 = bytes[0].toInt() and 0xFF
        val supported = mutableListOf<String>()
        if (raw0 and 0x01 != 0) supported += "Robust Caching"
        if (raw0 and 0x02 != 0) supported += "EATT Bearer"
        if (raw0 and 0x04 != 0) supported += "Multiple Handle Value Notifications"
        val reserved0 = raw0 and 0xF8
        if (reserved0 != 0) supported += "Reserved bits in byte 0: 0b" + Integer.toBinaryString(reserved0).padStart(8, '0')
        val flags = if (supported.isEmpty()) "(no features set)" else supported.joinToString(", ")
        val hex = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return "Client Supported Features bitfield: 0x$hex\n$flags"
    }

    private const val PNP_ID_LEN = 7
    private const val VENDOR_SOURCE_BLUETOOTH_SIG = 0x01
    private const val VENDOR_SOURCE_USB_IF = 0x02
}
