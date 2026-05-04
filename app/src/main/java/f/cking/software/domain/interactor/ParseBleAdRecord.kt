package f.cking.software.domain.interactor

import f.cking.software.data.helpers.BluetoothSIG
import f.cking.software.toHexString
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
        if (data.size < 2) return emptyList()
        val scheme = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val uriBody = String(data.copyOfRange(2, data.size), Charsets.UTF_8)
        return listOf(
            Field("URI scheme", "0x${"%04X".format(scheme)}"),
            Field("URI", uriBody),
        )
    }

    private fun parseManufacturerSpecific(data: ByteArray): List<Field> {
        if (data.size < 2) return listOf(Field("Company ID", "(missing)"))
        val companyId = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val companyName = BluetoothSIG.bluetoothSIG[companyId] ?: "Unknown"
        val payload = data.copyOfRange(2, data.size)
        return listOf(
            Field("Company ID", "0x${"%04X".format(companyId)} ($companyName)"),
            Field("String interpretation", interpretAsString(payload)),
        )
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
}
