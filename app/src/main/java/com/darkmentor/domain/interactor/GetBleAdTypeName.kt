package com.darkmentor.domain.interactor

/**
 * Maps Bluetooth Advertising / EIR data type codes to their spec-defined names.
 * See Bluetooth Core Specification Supplement, Part A, Section 1: Common Data Types.
 */
object GetBleAdTypeName {

    fun execute(type: Byte): String? {
        val unsigned = type.toInt() and 0xFF
        return AD_TYPE_NAMES[unsigned]
    }

    private val AD_TYPE_NAMES: Map<Int, String> = mapOf(
        0x01 to "Flags",
        0x02 to "Incomplete List of 16-bit Service Class UUIDs",
        0x03 to "Complete List of 16-bit Service Class UUIDs",
        0x04 to "Incomplete List of 32-bit Service Class UUIDs",
        0x05 to "Complete List of 32-bit Service Class UUIDs",
        0x06 to "Incomplete List of 128-bit Service Class UUIDs",
        0x07 to "Complete List of 128-bit Service Class UUIDs",
        0x08 to "Shortened Local Name",
        0x09 to "Complete Local Name",
        0x0A to "Tx Power Level",
        0x0D to "Class of Device",
        0x0E to "Simple Pairing Hash C-192",
        0x0F to "Simple Pairing Randomizer R-192",
        0x10 to "Device ID / Security Manager TK Value",
        0x11 to "Security Manager Out of Band Flags",
        0x12 to "Peripheral Connection Interval Range",
        0x14 to "List of 16-bit Service Solicitation UUIDs",
        0x15 to "List of 128-bit Service Solicitation UUIDs",
        0x16 to "Service Data - 16-bit UUID",
        0x17 to "Public Target Address",
        0x18 to "Random Target Address",
        0x19 to "Appearance",
        0x1A to "Advertising Interval",
        0x1B to "LE Bluetooth Device Address",
        0x1C to "LE Role",
        0x1D to "Simple Pairing Hash C-256",
        0x1E to "Simple Pairing Randomizer R-256",
        0x1F to "List of 32-bit Service Solicitation UUIDs",
        0x20 to "Service Data - 32-bit UUID",
        0x21 to "Service Data - 128-bit UUID",
        0x22 to "LE Secure Connections Confirmation Value",
        0x23 to "LE Secure Connections Random Value",
        0x24 to "URI",
        0x25 to "Indoor Positioning",
        0x26 to "Transport Discovery Data",
        0x27 to "LE Supported Features",
        0x28 to "Channel Map Update Indication",
        0x29 to "PB-ADV",
        0x2A to "Mesh Message",
        0x2B to "Mesh Beacon",
        0x2C to "BIGInfo",
        0x2D to "Broadcast Code",
        0x2E to "Resolvable Set Identifier",
        0x2F to "Advertising Interval - Long",
        0x30 to "Broadcast Name",
        0x31 to "Encrypted Advertising Data",
        0x32 to "Periodic Advertising Response Timing Information",
        0x34 to "Electronic Shelf Label",
        0x3D to "3D Information Data",
        0xFF to "Manufacturer Specific Data",
    )
}
