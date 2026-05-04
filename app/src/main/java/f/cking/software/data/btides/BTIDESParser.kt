package f.cking.software.data.btides

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts a raw BLE advertising payload (sequence of {length, type, data...} AD structures)
 * into a JSON array of AdvData entries that conform to the BTIDES BTIDES_AdvData schema.
 *
 * https://darkmentor.com/BTIDES_Schema/BTIDES_AdvData.json
 *
 * Unknown / unparseable AD types are skipped because the schema does not define a fallback
 * union member.
 */
object BTIDESParser {

    private const val AD_FLAGS = 0x01
    private const val AD_UUID16_INCOMPLETE = 0x02
    private const val AD_UUID16_COMPLETE = 0x03
    private const val AD_UUID32_INCOMPLETE = 0x04
    private const val AD_UUID32_COMPLETE = 0x05
    private const val AD_UUID128_INCOMPLETE = 0x06
    private const val AD_UUID128_COMPLETE = 0x07
    private const val AD_NAME_SHORT = 0x08
    private const val AD_NAME_COMPLETE = 0x09
    private const val AD_TX_POWER = 0x0A
    private const val AD_CLASS_OF_DEVICE = 0x0D
    private const val AD_DEVICE_ID = 0x10
    private const val AD_PERIPHERAL_CONN_INTERVAL = 0x12
    private const val AD_UUID16_SOLICITATION = 0x14
    private const val AD_UUID128_SOLICITATION = 0x15
    private const val AD_UUID16_SERVICE_DATA = 0x16
    private const val AD_PUBLIC_TARGET_ADDRESS = 0x17
    private const val AD_RANDOM_TARGET_ADDRESS = 0x18
    private const val AD_APPEARANCE = 0x19
    private const val AD_ADVERTISING_INTERVAL = 0x1A
    private const val AD_LE_BDADDR = 0x1B
    private const val AD_LE_ROLE = 0x1C
    private const val AD_UUID32_SOLICITATION = 0x1F
    private const val AD_UUID32_SERVICE_DATA = 0x20
    private const val AD_UUID128_SERVICE_DATA = 0x21
    private const val AD_URI = 0x24
    private const val AD_LE_SUPPORTED_FEATURES = 0x27
    private const val AD_BROADCAST_NAME = 0x30
    private const val AD_ENCRYPTED_ADV_DATA = 0x31
    private const val AD_3D_INFO_DATA = 0x3D
    private const val AD_MANUFACTURER_SPECIFIC = 0xFF

    fun parseAdvDataArray(raw: ByteArray?): JsonArray {
        if (raw == null || raw.isEmpty()) return JsonArray(emptyList())

        return buildJsonArray {
            var i = 0
            while (i < raw.size) {
                val length = raw[i].toInt() and 0xFF
                if (length == 0) break // padding / end of data
                if (i + length >= raw.size) break // malformed length
                val type = raw[i + 1].toInt() and 0xFF
                val dataStart = i + 2
                val dataEnd = i + 1 + length // inclusive end index
                val data = raw.copyOfRange(dataStart, dataEnd + 1)

                buildAdvData(type, length, data)?.let { add(it) }
                i += 1 + length
            }
        }
    }

    private fun buildAdvData(type: Int, length: Int, data: ByteArray): JsonObject? {
        return when (type) {
            AD_FLAGS -> {
                if (data.isEmpty()) return null
                buildJsonObject {
                    put("type_str", "Flags")
                    put("type", AD_FLAGS)
                    put("length", length)
                    put("flags_hex_str", data.toHexLower(maxLen = 1))
                }
            }
            AD_UUID16_INCOMPLETE -> uuidListEntry("UUID16ListIncomplete", AD_UUID16_INCOMPLETE, length, data, uuidByteSize = 2, listKey = "UUID16List", reverseToBigEndian = true)
            AD_UUID16_COMPLETE -> uuidListEntry("UUID16ListComplete", AD_UUID16_COMPLETE, length, data, uuidByteSize = 2, listKey = "UUID16List", reverseToBigEndian = true)
            AD_UUID32_INCOMPLETE -> uuidListEntry("UUID32ListIncomplete", AD_UUID32_INCOMPLETE, length, data, uuidByteSize = 4, listKey = "UUID32List", reverseToBigEndian = true)
            AD_UUID32_COMPLETE -> uuidListEntry("UUID32ListComplete", AD_UUID32_COMPLETE, length, data, uuidByteSize = 4, listKey = "UUID32List", reverseToBigEndian = true)
            AD_UUID128_INCOMPLETE -> uuidListEntry("UUID128ListIncomplete", AD_UUID128_INCOMPLETE, length, data, uuidByteSize = 16, listKey = "UUID128List", reverseToBigEndian = true)
            AD_UUID128_COMPLETE -> uuidListEntry("UUID128ListComplete", AD_UUID128_COMPLETE, length, data, uuidByteSize = 16, listKey = "UUID128List", reverseToBigEndian = true)
            AD_NAME_SHORT -> nameEntry("IncompleteName", AD_NAME_SHORT, length, data)
            AD_NAME_COMPLETE -> nameEntry("CompleteName", AD_NAME_COMPLETE, length, data)
            AD_TX_POWER -> {
                if (data.isEmpty()) return null
                buildJsonObject {
                    put("type_str", "TxPower")
                    put("type", AD_TX_POWER)
                    put("length", length)
                    put("tx_power", data[0].toInt())
                }
            }
            AD_CLASS_OF_DEVICE -> {
                if (data.size < 3) return null
                buildJsonObject {
                    put("type_str", "ClassOfDevice")
                    put("type", AD_CLASS_OF_DEVICE)
                    put("length", length)
                    // CoD is 3 bytes little-endian on the wire; encode big-endian hex (MSB first)
                    val bigEndian = byteArrayOf(data[2], data[1], data[0])
                    put("CoD_hex_str", bigEndian.toHexLower())
                }
            }
            AD_DEVICE_ID -> {
                if (data.size < 8) return null
                buildJsonObject {
                    put("type_str", "DeviceID")
                    put("type", AD_DEVICE_ID)
                    put("length", length)
                    put("vendor_id_source", le16(data, 0))
                    put("vendor_id", le16(data, 2))
                    put("product_id", le16(data, 4))
                    put("version", le16(data, 6))
                }
            }
            AD_PERIPHERAL_CONN_INTERVAL -> {
                if (data.size < 4) return null
                buildJsonObject {
                    put("type_str", "PeripheralConnectionIntervalRange")
                    put("type", AD_PERIPHERAL_CONN_INTERVAL)
                    put("length", length)
                    put("conn_interval_min", le16(data, 0))
                    put("conn_interval_max", le16(data, 2))
                }
            }
            AD_UUID16_SOLICITATION -> uuidListEntry("UUID16ListServiceSolicitation", AD_UUID16_SOLICITATION, length, data, uuidByteSize = 2, listKey = "UUID16List", reverseToBigEndian = true)
            AD_UUID32_SOLICITATION -> uuidListEntry("UUID32ListServiceSolicitation", AD_UUID32_SOLICITATION, length, data, uuidByteSize = 4, listKey = "UUID32List", reverseToBigEndian = true)
            AD_UUID128_SOLICITATION -> uuidListEntry("UUID128ListServiceSolicitation", AD_UUID128_SOLICITATION, length, data, uuidByteSize = 16, listKey = "UUID128List", reverseToBigEndian = true)
            AD_UUID16_SERVICE_DATA -> {
                if (data.size < 2) return null
                buildJsonObject {
                    put("type_str", "UUID16ServiceData")
                    put("type", AD_UUID16_SERVICE_DATA)
                    put("length", length)
                    put("UUID16", reverseHex(data.copyOfRange(0, 2)))
                    put("service_data_hex_str", data.copyOfRange(2, data.size).toHexLower())
                }
            }
            AD_UUID32_SERVICE_DATA -> {
                if (data.size < 4) return null
                buildJsonObject {
                    put("type_str", "UUID32ServiceData")
                    put("type", AD_UUID32_SERVICE_DATA)
                    put("length", length)
                    put("UUID32", reverseHex(data.copyOfRange(0, 4)))
                    put("service_data_hex_str", data.copyOfRange(4, data.size).toHexLower())
                }
            }
            AD_UUID128_SERVICE_DATA -> {
                if (data.size < 16) return null
                buildJsonObject {
                    put("type_str", "UUID128ServiceData")
                    put("type", AD_UUID128_SERVICE_DATA)
                    put("length", length)
                    put("UUID128", reverseHex(data.copyOfRange(0, 16)))
                    put("service_data_hex_str", data.copyOfRange(16, data.size).toHexLower())
                }
            }
            AD_PUBLIC_TARGET_ADDRESS -> {
                if (data.size < 6) return null
                buildJsonObject {
                    put("type_str", "PublicTargetAddress")
                    put("type", AD_PUBLIC_TARGET_ADDRESS)
                    put("length", length)
                    put("public_bdaddr", bdaddrLEtoString(data, 0))
                }
            }
            AD_RANDOM_TARGET_ADDRESS -> {
                if (data.size < 6) return null
                buildJsonObject {
                    put("type_str", "RandomTargetAddress")
                    put("type", AD_RANDOM_TARGET_ADDRESS)
                    put("length", length)
                    put("random_bdaddr", bdaddrLEtoString(data, 0))
                }
            }
            AD_APPEARANCE -> {
                if (data.size < 2) return null
                buildJsonObject {
                    put("type_str", "Appearance")
                    put("type", AD_APPEARANCE)
                    put("length", length)
                    // Appearance is 2 bytes little-endian. Encode as big-endian hex (4 chars).
                    put("appearance_hex_str", "%02x%02x".format(data[1].toInt() and 0xFF, data[0].toInt() and 0xFF))
                }
            }
            AD_ADVERTISING_INTERVAL -> {
                if (data.isEmpty()) return null
                val interval = when (data.size) {
                    2 -> le16(data, 0)
                    3 -> (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16)
                    4 -> (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)
                    else -> return null
                }
                buildJsonObject {
                    put("type_str", "AdvertisingInterval")
                    put("type", AD_ADVERTISING_INTERVAL)
                    put("length", length)
                    put("advertising_interval", interval)
                }
            }
            AD_LE_BDADDR -> {
                if (data.size < 7) return null
                buildJsonObject {
                    put("type_str", "LE_BDADDR")
                    put("type", AD_LE_BDADDR)
                    put("length", length)
                    put("bdaddr_type", (data[0].toInt() and 0x01))
                    put("le_bdaddr", bdaddrLEtoString(data, 1))
                }
            }
            AD_LE_ROLE -> {
                if (data.isEmpty()) return null
                val role = data[0].toInt() and 0xFF
                if (role !in 0..3) return null
                buildJsonObject {
                    put("type_str", "LE_Role")
                    put("type", AD_LE_ROLE)
                    put("length", length)
                    put("role", role)
                }
            }
            AD_URI -> {
                buildJsonObject {
                    put("type_str", "URI")
                    put("type", AD_URI)
                    put("length", length)
                    put("uri_hex_str", data.toHexLower())
                }
            }
            AD_LE_SUPPORTED_FEATURES -> {
                buildJsonObject {
                    put("type_str", "LESupportedFeatures")
                    put("type", AD_LE_SUPPORTED_FEATURES)
                    put("length", length)
                    put("le_features_hex_str", data.toHexLower())
                }
            }
            AD_BROADCAST_NAME -> nameEntry("BroadcastName", AD_BROADCAST_NAME, length, data)
            AD_ENCRYPTED_ADV_DATA -> buildJsonObject {
                put("type_str", "EncryptedAdvertisingData")
                put("type", AD_ENCRYPTED_ADV_DATA)
                put("length", length)
                put("enc_adv_data_hex_str", data.toHexLower())
            }
            AD_3D_INFO_DATA -> {
                if (data.size < 2) return null
                buildJsonObject {
                    put("type_str", "3DInfoData")
                    put("type", AD_3D_INFO_DATA)
                    put("length", length)
                    put("byte1", data[0].toInt() and 0xFF)
                    put("path_loss", data[1].toInt() and 0xFF)
                }
            }
            AD_MANUFACTURER_SPECIFIC -> {
                if (data.size < 2) return null
                buildJsonObject {
                    put("type_str", "ManufacturerSpecificData")
                    put("type", AD_MANUFACTURER_SPECIFIC)
                    put("length", length)
                    // Company ID is little-endian on the wire; render in spec-style "AABB" (LSB-first hex)
                    put("company_id_hex_str", "%02x%02x".format(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF))
                    put("msd_hex_str", data.copyOfRange(2, data.size).toHexLower())
                }
            }
            else -> null
        }
    }

    private fun nameEntry(typeStr: String, type: Int, length: Int, data: ByteArray): JsonObject {
        return buildJsonObject {
            put("type_str", typeStr)
            put("type", type)
            put("length", length)
            put("name_hex_str", data.toHexLower())
            try {
                val str = String(data, Charsets.UTF_8).trimEnd(' ')
                if (str.all { it.isPrintableUtf8() }) {
                    put("utf8_name", str)
                }
            } catch (_: Throwable) {
                // skip utf8_name
            }
        }
    }

    private fun uuidListEntry(
        typeStr: String,
        type: Int,
        length: Int,
        data: ByteArray,
        uuidByteSize: Int,
        listKey: String,
        reverseToBigEndian: Boolean,
    ): JsonObject {
        return buildJsonObject {
            put("type_str", typeStr)
            put("type", type)
            put("length", length)
            val uuids = buildJsonArray {
                var p = 0
                while (p + uuidByteSize <= data.size) {
                    val slice = data.copyOfRange(p, p + uuidByteSize)
                    val hex = if (reverseToBigEndian) reverseHex(slice) else slice.toHexLower()
                    add(JsonPrimitive(hex))
                    p += uuidByteSize
                }
            }
            put(listKey, uuids)
        }
    }

    private fun le16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun reverseHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (i in data.indices.reversed()) {
            sb.append("%02x".format(data[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun bdaddrLEtoString(data: ByteArray, offset: Int): String {
        // BDADDR on the wire is little-endian (LSB first). BTIDES wants big-endian colon string.
        return (5 downTo 0).joinToString(":") { "%02X".format(data[offset + it].toInt() and 0xFF) }
    }

    private fun ByteArray.toHexLower(maxLen: Int = this.size): String {
        val n = minOf(maxLen, this.size)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append("%02x".format(this[i].toInt() and 0xFF))
        return sb.toString()
    }

    private fun Char.isPrintableUtf8(): Boolean {
        return this == '\t' || this == '\n' || this == '\r' || (this.code in 0x20..0x7E) || this.code >= 0xA0
    }
}
