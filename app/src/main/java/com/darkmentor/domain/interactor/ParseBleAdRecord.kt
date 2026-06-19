package com.darkmentor.domain.interactor

import com.darkmentor.data.helpers.BluetoothSIG
import com.darkmentor.toHexString
import java.util.UUID

/**
 * Parses the data portion of a BLE Advertising / EIR record into a list of
 * human-readable (label, value) pairs, per the Bluetooth Core Specification
 * Supplement (CSS), Part A, Section 1: Common Data Types.
 *
 * The first entry of the returned list is conventionally the data of primary
 * interest for that AD type (e.g. company ID for MSD, name for Local Name).
 */
object ParseBleAdRecord {

    data class Field(val label: String, val value: String)

    fun execute(type: Byte, data: ByteArray): List<Field> {
        val unsigned = type.toInt() and 0xFF
        return try {
            when (unsigned) {
                0x01 -> parseFlags(data)
                0x02, 0x03, 0x14 -> parseUuids16(data)
                0x04, 0x05, 0x1F -> parseUuids32(data)
                0x06, 0x07, 0x15 -> parseUuids128(data)
                0x08, 0x09 -> parseLocalName(data)
                0x0A -> parseTxPower(data)
                0x16 -> parseServiceData16(data)
                0x19 -> parseAppearance(data)
                0x20 -> parseServiceData32(data)
                0x21 -> parseServiceData128(data)
                0x24 -> parseUri(data)
                0xFF -> parseManufacturerSpecific(data)
                else -> emptyList()
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun parseFlags(data: ByteArray): List<Field> {
        if (data.isEmpty()) return emptyList()
        val flags = data[0].toInt() and 0xFF
        val names = listOf(
            0x01 to "LE Limited Discoverable Mode",
            0x02 to "LE General Discoverable Mode",
            0x04 to "BR/EDR Not Supported",
            0x08 to "Simultaneous LE and BR/EDR (Controller)",
            0x10 to "Simultaneous LE and BR/EDR (Host)",
        )
        val active = names.filter { (bit, _) -> flags and bit != 0 }.map { it.second }
        return listOf(
            Field("Flags", "0x${"%02X".format(flags)}"),
            Field("Bits set", active.joinToString(", ").ifEmpty { "(none)" }),
        )
    }

    private fun parseUuids16(data: ByteArray): List<Field> {
        return data.toList()
            .chunked(2)
            .filter { it.size == 2 }
            .map { bytes -> "%04X".format(((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)) }
            .map { Field("Service UUID (16-bit)", "0x$it") }
    }

    private fun parseUuids32(data: ByteArray): List<Field> {
        return data.toList()
            .chunked(4)
            .filter { it.size == 4 }
            .map { bytes ->
                "%08X".format(
                    ((bytes[3].toInt() and 0xFF).toLong() shl 24)
                            or ((bytes[2].toInt() and 0xFF).toLong() shl 16)
                            or ((bytes[1].toInt() and 0xFF).toLong() shl 8)
                            or (bytes[0].toInt() and 0xFF).toLong()
                )
            }
            .map { Field("Service UUID (32-bit)", "0x$it") }
    }

    private fun parseUuids128(data: ByteArray): List<Field> {
        return data.toList()
            .chunked(16)
            .filter { it.size == 16 }
            .map { bytes -> bytesToUuid128(bytes.toByteArray()) }
            .map { Field("Service UUID (128-bit)", it) }
    }

    private fun parseLocalName(data: ByteArray): List<Field> {
        val name = String(data, Charsets.UTF_8)
        return listOf(Field("Name", name))
    }

    private fun parseTxPower(data: ByteArray): List<Field> {
        if (data.isEmpty()) return emptyList()
        val dbm = data[0].toInt().toByte().toInt()
        return listOf(Field("TX Power", "$dbm dBm"))
    }

    private fun parseAppearance(data: ByteArray): List<Field> {
        if (data.size < 2) return emptyList()
        val value = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        return listOf(Field("Appearance", "0x${"%04X".format(value)}"))
    }

    private fun parseServiceData16(data: ByteArray): List<Field> {
        if (data.size < 2) return emptyList()
        val uuid = "%04X".format(((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF))
        val payload = data.copyOfRange(2, data.size)
        return listOf(
            Field("Service UUID (16-bit)", "0x$uuid"),
            Field("Service data", "0x${payload.toHexString().uppercase()}"),
            Field("String interpretation", interpretAsString(payload)),
        )
    }

    private fun parseServiceData32(data: ByteArray): List<Field> {
        if (data.size < 4) return emptyList()
        val uuid = "%08X".format(
            ((data[3].toInt() and 0xFF).toLong() shl 24)
                    or ((data[2].toInt() and 0xFF).toLong() shl 16)
                    or ((data[1].toInt() and 0xFF).toLong() shl 8)
                    or (data[0].toInt() and 0xFF).toLong()
        )
        val payload = data.copyOfRange(4, data.size)
        return listOf(
            Field("Service UUID (32-bit)", "0x$uuid"),
            Field("Service data", "0x${payload.toHexString().uppercase()}"),
            Field("String interpretation", interpretAsString(payload)),
        )
    }

    private fun parseServiceData128(data: ByteArray): List<Field> {
        if (data.size < 16) return emptyList()
        val uuid = bytesToUuid128(data.copyOfRange(0, 16))
        val payload = data.copyOfRange(16, data.size)
        return listOf(
            Field("Service UUID (128-bit)", uuid),
            Field("Service data", "0x${payload.toHexString().uppercase()}"),
            Field("String interpretation", interpretAsString(payload)),
        )
    }

    private fun parseUri(data: ByteArray): List<Field> {
        if (data.isEmpty()) return emptyList()
        // BT Core Spec § 1.10.27: AD type 0x24 (URI) is `<UTF-8 scheme code><UTF-8 URI body>`.
        // The scheme code is the SIG-assigned single-byte index into URI_SCHEMES (the older
        // implementation read it as a little-endian uint16, which both consumed an extra body
        // byte and produced garbage scheme labels — e.g. a UVP-style "https://DarkMentor.com"
        // payload was rendering with a bogus scheme + a body shifted by one byte).
        val schemeCode = data[0].toInt() and 0xFF
        val schemeName = URI_SCHEMES[schemeCode]
        val body = String(data.copyOfRange(1, data.size), Charsets.UTF_8)
        val schemeLabel = schemeName ?: "(unknown 0x${"%02X".format(schemeCode)})"
        val fullUri = if (schemeName != null) schemeName + body else body
        return listOf(
            Field("URI scheme", "0x${"%02X".format(schemeCode)} ($schemeLabel)"),
            Field("URI", fullUri),
        )
    }

    private fun parseManufacturerSpecific(data: ByteArray): List<Field> {
        if (data.size < 2) return listOf(Field("Company ID", "(missing)"))
        val companyId = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val companyName = BluetoothSIG.bluetoothSIG[companyId] ?: "Unknown"
        val payload = data.copyOfRange(2, data.size)

        // iBeacon detection per Apple's spec — Apple company id (0x004C) followed by subtype
        // 0x02, length 0x15 (21 bytes of UUID + Major + Minor + TX Power). The format is
        // multi-vendor: Tesla, Estimote, etc. broadcast under Apple's company id, so always
        // decode it as iBeacon when the shape matches rather than just dumping raw bytes.
        // https://developer.apple.com/ibeacon/
        if (companyId == APPLE_COMPANY_ID && payload.size >= IBEACON_PAYLOAD_LEN
            && (payload[0].toInt() and 0xFF) == IBEACON_SUBTYPE
            && (payload[1].toInt() and 0xFF) == IBEACON_LENGTH
        ) {
            val uuidBytes = payload.copyOfRange(2, 18)
            val major = ((payload[18].toInt() and 0xFF) shl 8) or (payload[19].toInt() and 0xFF)
            val minor = ((payload[20].toInt() and 0xFF) shl 8) or (payload[21].toInt() and 0xFF)
            val txPower = payload[22].toInt() // signed int8 — RSSI at 1m calibration
            return listOf(
                Field("Company ID", "0x${"%04X".format(companyId)} ($companyName)"),
                Field("Format", "iBeacon"),
                Field("UUID", formatBigEndianUuid(uuidBytes)),
                Field("Major", "$major (0x${"%04X".format(major)})"),
                Field("Minor", "$minor (0x${"%04X".format(minor)})"),
                Field("TX Power (1m)", "$txPower dBm"),
            )
        }

        return listOf(
            Field("Company ID", "0x${"%04X".format(companyId)} ($companyName)"),
            Field("String interpretation", interpretAsString(payload)),
        )
    }

    /**
     * Formats 16 already-big-endian bytes as a canonical UUID string (8-4-4-4-12 hex). iBeacon
     * UUIDs are big-endian on the wire (per Apple's spec), unlike SIG service UUIDs which use
     * little-endian — don't share `bytesToUuid128` because it reverses for LE.
     */
    private fun formatBigEndianUuid(bytes: ByteArray): String {
        val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xFF) }
        val lsb = (8..15).fold(0L) { acc, i -> (acc shl 8) or (bytes[i].toLong() and 0xFF) }
        return UUID(msb, lsb).toString().uppercase()
    }

    /**
     * Attempts to interpret the bytes as a UTF-8 string. Returns the decoded
     * string only if every code point falls in a printable range (no C0/C1
     * controls, no surrogates, no replacement char, no DEL). Returns "None"
     * otherwise — including when the input is empty or invalid UTF-8.
     */
    private fun interpretAsString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "None"
        val decoder = Charsets.UTF_8.newDecoder()
        val decoded = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            return "None"
        }
        if (decoded.isEmpty()) return "None"
        var i = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            if (!isPrintableCodePoint(cp)) return "None"
            i += Character.charCount(cp)
        }
        return decoded
    }

    private fun isPrintableCodePoint(cp: Int): Boolean {
        // Reject C0 controls (0x00–0x1F) and DEL (0x7F).
        if (cp < 0x20) return false
        if (cp == 0x7F) return false
        // Reject C1 controls (0x80–0x9F).
        if (cp in 0x80..0x9F) return false
        // Reject the Unicode replacement character — indicates decode failure.
        if (cp == 0xFFFD) return false
        // Reject unpaired surrogates and non-characters.
        if (cp in 0xD800..0xDFFF) return false
        if (cp in 0xFDD0..0xFDEF) return false
        if ((cp and 0xFFFE) == 0xFFFE) return false
        return Character.isDefined(cp)
    }

    private fun bytesToUuid128(bytes: ByteArray): String {
        // CSS little-endian byte order — reverse to standard big-endian for UUID display.
        val be = bytes.reversedArray()
        val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (be[i].toLong() and 0xFF) }
        val lsb = (8..15).fold(0L) { acc, i -> (acc shl 8) or (be[i].toLong() and 0xFF) }
        return UUID(msb, lsb).toString()
    }

    private const val APPLE_COMPANY_ID = 0x004C
    private const val IBEACON_SUBTYPE = 0x02
    private const val IBEACON_LENGTH = 0x15
    // 2 bytes (subtype + length) + 16-byte UUID + 2-byte Major + 2-byte Minor + 1-byte TX power.
    private const val IBEACON_PAYLOAD_LEN = 23

    /**
     * Bluetooth SIG Assigned Numbers — URI scheme codes used by AD type 0x24 (URI). The full
     * table from
     * https://bitbucket.org/bluetooth-SIG/public/src/main/assigned_numbers/uri_schemes/uri_scheme_name.yaml
     * — most BLE devices in the wild advertise `https:` (0x16) with a body of `//hostname/…`
     * which reconstitutes to `https://hostname/…`.
     */
    private val URI_SCHEMES: Map<Int, String> = mapOf(
        0x01 to "aaa:",
        0x02 to "aaas:",
        0x03 to "about:",
        0x04 to "acap:",
        0x05 to "acct:",
        0x06 to "cap:",
        0x07 to "cid:",
        0x08 to "coap:",
        0x09 to "coaps:",
        0x0A to "crid:",
        0x0B to "data:",
        0x0C to "dav:",
        0x0D to "dict:",
        0x0E to "dns:",
        0x0F to "file:",
        0x10 to "ftp:",
        0x11 to "geo:",
        0x12 to "go:",
        0x13 to "gopher:",
        0x14 to "h323:",
        0x15 to "http:",
        0x16 to "https:",
        0x17 to "iax:",
        0x18 to "icap:",
        0x19 to "im:",
        0x1A to "imap:",
        0x1B to "info:",
        0x1C to "ipp:",
        0x1D to "ipps:",
        0x1E to "iris:",
        0x1F to "iris.beep:",
        0x20 to "iris.xpc:",
        0x21 to "iris.xpcs:",
        0x22 to "iris.lwz:",
        0x23 to "jabber:",
        0x24 to "ldap:",
        0x25 to "mailto:",
        0x26 to "mid:",
        0x27 to "msrp:",
        0x28 to "msrps:",
        0x29 to "mtqp:",
        0x2A to "mupdate:",
        0x2B to "news:",
        0x2C to "nfs:",
        0x2D to "ni:",
        0x2E to "nih:",
        0x2F to "nntp:",
        0x30 to "opaquelocktoken:",
        0x31 to "pop:",
        0x32 to "pres:",
        0x33 to "reload:",
        0x34 to "rtsp:",
        0x35 to "rtsps:",
        0x36 to "rtspu:",
        0x37 to "service:",
        0x38 to "session:",
        0x39 to "shttp:",
        0x3A to "sieve:",
        0x3B to "sip:",
        0x3C to "sips:",
        0x3D to "sms:",
        0x3E to "snmp:",
        0x3F to "soap.beep:",
        0x40 to "soap.beeps:",
        0x41 to "stun:",
        0x42 to "stuns:",
        0x43 to "tag:",
        0x44 to "tel:",
        0x45 to "telnet:",
        0x46 to "tftp:",
        0x47 to "thismessage:",
        0x48 to "tn3270:",
        0x49 to "tip:",
        0x4A to "turn:",
        0x4B to "turns:",
        0x4C to "tv:",
        0x4D to "urn:",
        0x4E to "vemmi:",
        0x4F to "ws:",
        0x50 to "wss:",
        0x51 to "xcon:",
        0x52 to "xcon-userid:",
        0x53 to "xmlrpc.beep:",
        0x54 to "xmlrpc.beeps:",
        0x55 to "xmpp:",
        0x56 to "z39.50r:",
        0x57 to "z39.50s:",
    )
}
