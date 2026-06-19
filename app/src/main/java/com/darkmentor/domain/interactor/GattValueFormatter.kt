package com.darkmentor.domain.interactor

import com.darkmentor.data.helpers.BluetoothSIG
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
    // 0x2A01 "Appearance" — 16-bit packed Category (10 bits) + Sub-category (6 bits).
    private val APPEARANCE_UUID: UUID = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb")

    fun format(uuid: UUID, bytes: ByteArray): String? = when (uuid) {
        PNP_ID_UUID -> formatPnpId(bytes)
        SERVER_SUPPORTED_FEATURES_UUID -> formatServerSupportedFeatures(bytes)
        CLIENT_SUPPORTED_FEATURES_UUID -> formatClientSupportedFeatures(bytes)
        APPEARANCE_UUID -> formatAppearance(bytes)
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
        // Some peers (observed: an HP OfficeJet printer reading back ~19 bytes here) put
        // non-conformant data on the 0x2A50 characteristic. Returning null lets the caller
        // fall through to decodeToString which renders binary noise; instead surface an
        // explicit length-mismatch line so the user understands the byte count problem.
        if (bytes.size != PNP_ID_LEN) {
            return "PnP ID expected ${PNP_ID_LEN} bytes, got ${bytes.size} — peer not spec-compliant. Raw bytes shown below."
        }
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
     * GATT 0x2A01 Appearance — uint16 LE, packed as:
     *   bits 15..6 (10 bits) = Category
     *   bits 5..0  (6 bits)  = Sub-category
     *
     * Category names from the Bluetooth Assigned Numbers (Generic Access Profile, "Appearance
     * Values"). We surface every category present in the spec at the time of writing; sub-
     * category labels only resolve for the categories the user is most likely to encounter
     * (Watch, Phone, Computer, HID). Anything else falls through with a "subcategory 0xNN"
     * suffix so the raw value isn't lost.
     */
    private fun formatAppearance(bytes: ByteArray): String? {
        if (bytes.size != APPEARANCE_LEN) {
            return "Appearance expected ${APPEARANCE_LEN} bytes, got ${bytes.size} — peer not spec-compliant. Raw bytes shown below."
        }
        val raw = leUint16(bytes, 0)
        val category = (raw shr 6) and 0x3FF
        val subCategory = raw and 0x3F
        val categoryName = APPEARANCE_CATEGORY_NAMES[category] ?: "Unknown category"
        val subLabel = appearanceSubCategoryName(category, subCategory)
            ?: if (subCategory == 0) "Generic" else "subcategory 0x%02X".format(subCategory)
        return "Appearance: 0x%04X\n".format(raw) +
                "Category: $categoryName (0x%03X)\n".format(category) +
                "Sub-category: $subLabel"
    }

    /**
     * Sub-category labels for the highest-traffic categories. For everything else we return
     * null so the caller can fall back to "subcategory 0xNN". Keeping the table tight on
     * purpose — the BT GAP Appearance enum has hundreds of entries and most are exotic.
     */
    private fun appearanceSubCategoryName(category: Int, sub: Int): String? = when (category) {
        APPEARANCE_CATEGORY_WATCH -> when (sub) {
            0 -> "Generic Watch"
            1 -> "Sports Watch"
            2 -> "Smartwatch"
            else -> null
        }
        APPEARANCE_CATEGORY_PHONE -> when (sub) {
            0 -> "Generic Phone"
            else -> null
        }
        APPEARANCE_CATEGORY_COMPUTER -> when (sub) {
            0 -> "Generic Computer"
            1 -> "Desktop Workstation"
            2 -> "Server-class Computer"
            3 -> "Laptop"
            4 -> "Handheld PC/PDA (clamshell)"
            5 -> "Palm-size PC/PDA"
            6 -> "Wearable computer (Watch size)"
            7 -> "Tablet"
            8 -> "Docking Station"
            9 -> "All-in-One"
            10 -> "Blade Server"
            11 -> "Convertible"
            12 -> "Detachable"
            13 -> "IoT Gateway"
            14 -> "Mini PC"
            15 -> "Stick PC"
            else -> null
        }
        APPEARANCE_CATEGORY_HID -> when (sub) {
            0 -> "Generic HID"
            1 -> "Keyboard"
            2 -> "Mouse"
            3 -> "Joystick"
            4 -> "Gamepad"
            5 -> "Digitizer Tablet"
            6 -> "Card Reader"
            7 -> "Digital Pen"
            8 -> "Barcode Scanner"
            9 -> "Touchpad"
            10 -> "Presentation Remote"
            else -> null
        }
        else -> null
    }

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

    private const val APPEARANCE_LEN = 2
    private const val APPEARANCE_CATEGORY_PHONE = 0x001
    private const val APPEARANCE_CATEGORY_COMPUTER = 0x002
    private const val APPEARANCE_CATEGORY_WATCH = 0x003
    private const val APPEARANCE_CATEGORY_HID = 0x00F

    /**
     * Bluetooth Assigned Numbers — Generic Access Profile "Appearance" categories.
     * IDs are the 10-bit category values (already shifted out of the packed uint16).
     * Source: https://www.bluetooth.com/specifications/assigned-numbers/ (Appearance Values)
     */
    private val APPEARANCE_CATEGORY_NAMES: Map<Int, String> = mapOf(
        0x000 to "Unknown",
        0x001 to "Phone",
        0x002 to "Computer",
        0x003 to "Watch",
        0x004 to "Clock",
        0x005 to "Display",
        0x006 to "Remote Control",
        0x007 to "Eye-glasses",
        0x008 to "Tag",
        0x009 to "Keyring",
        0x00A to "Media Player",
        0x00B to "Barcode Scanner",
        0x00C to "Thermometer",
        0x00D to "Heart Rate Sensor",
        0x00E to "Blood Pressure",
        0x00F to "Human Interface Device",
        0x010 to "Glucose Meter",
        0x011 to "Running Walking Sensor",
        0x012 to "Cycling",
        0x013 to "Control Device",
        0x014 to "Network Device",
        0x015 to "Sensor",
        0x016 to "Light Fixtures",
        0x017 to "Fan",
        0x018 to "HVAC",
        0x019 to "Air Conditioning",
        0x01A to "Humidifier",
        0x01B to "Heating",
        0x01C to "Access Control",
        0x01D to "Motorized Device",
        0x01E to "Power Device",
        0x01F to "Light Source",
        0x020 to "Window Covering",
        0x021 to "Audio Sink",
        0x022 to "Audio Source",
        0x023 to "Motorized Vehicle",
        0x024 to "Domestic Appliance",
        0x025 to "Wearable Audio Device",
        0x026 to "Aircraft",
        0x027 to "AV Equipment",
        0x028 to "Display Equipment",
        0x029 to "Hearing aid",
        0x02A to "Gaming",
        0x02B to "Signage",
        0x031 to "Pulse Oximeter",
        0x032 to "Weight Scale",
        0x033 to "Personal Mobility Device",
        0x034 to "Continuous Glucose Monitor",
        0x035 to "Insulin Pump",
        0x036 to "Medication Delivery",
        0x037 to "Spirometer",
        0x051 to "Outdoor Sports Activity",
    )
}
