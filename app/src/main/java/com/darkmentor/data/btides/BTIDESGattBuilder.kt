package com.darkmentor.data.btides

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Builds BTIDES GATT JSON fragments from Android's BluetoothGatt* objects.
 *
 * The Android API does not expose ATT handles as first-class fields, but
 * `getInstanceId()` is the underlying handle for services and descriptors,
 * and the value handle for characteristics. The Characteristic Declaration
 * handle is by spec convention `value_handle - 1`.
 *
 * BTIDES GATT schema: https://darkmentor.com/BTIDES_Schema/BTIDES_GATT.json
 */
object BTIDESGattBuilder {

    private const val IO_TYPE_ERROR = 1
    private const val IO_TYPE_READ_RSP = 11 // ATT_READ_RSP
    private const val SERVICE_TYPE_PRIMARY = BluetoothGattService.SERVICE_TYPE_PRIMARY
    private const val SERVICE_TYPE_SECONDARY = BluetoothGattService.SERVICE_TYPE_SECONDARY

    /** Build a full GATTArray for a list of services with no observed reads yet. */
    fun buildServicesArray(services: List<BluetoothGattService>): JsonArray {
        return buildJsonArray {
            for (service in services) add(buildService(service))
        }
    }

    /** Build a single-service GATTArray with one read on one characteristic. */
    fun buildCharacteristicReadArray(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ): JsonArray {
        val service = characteristic.service ?: return JsonArray(emptyList())
        return buildJsonArray { addJsonObject { fillService(this, service, includeAllChars = false, focusChar = characteristic, focusCharIo = ioForCharacteristicRead(value, status)) } }
    }

    /** Build a single-service GATTArray containing a characteristic with one descriptor entry. */
    fun buildDescriptorReadArray(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
        status: Int,
    ): JsonArray {
        val characteristic = descriptor.characteristic ?: return JsonArray(emptyList())
        val service = characteristic.service ?: return JsonArray(emptyList())
        return buildJsonArray {
            addJsonObject {
                fillService(
                    this,
                    service,
                    includeAllChars = false,
                    focusChar = characteristic,
                    focusDescriptor = descriptor,
                    focusDescriptorValue = if (status == 0) value else null,
                    focusDescriptorErrorStatus = if (status != 0) status else null,
                )
            }
        }
    }

    private fun buildService(service: BluetoothGattService): JsonObject {
        return buildJsonObject { fillService(this, service, includeAllChars = true) }
    }

    private fun fillService(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        service: BluetoothGattService,
        includeAllChars: Boolean,
        focusChar: BluetoothGattCharacteristic? = null,
        focusCharIo: JsonObject? = null,
        focusDescriptor: BluetoothGattDescriptor? = null,
        focusDescriptorValue: ByteArray? = null,
        focusDescriptorErrorStatus: Int? = null,
    ) {
        val isPrimary = service.type == SERVICE_TYPE_PRIMARY
        val beginHandle = service.instanceId.coerceAtLeast(1)
        val endHandle = computeEndHandle(service, beginHandle)

        builder.put("type_str", if (isPrimary) "Primary Service" else "Secondary Service")
        builder.put("utype", if (isPrimary) "2800" else "2801")
        builder.put("UUID", uuidToBtidesString(service.uuid))
        builder.put("begin_handle", beginHandle)
        builder.put("end_handle", endHandle)

        val charsToInclude = if (includeAllChars) service.characteristics.orEmpty() else listOfNotNull(focusChar)
        if (charsToInclude.isNotEmpty()) {
            builder.putJsonArray("characteristics") {
                for (c in charsToInclude) {
                    addJsonObject {
                        fillCharacteristic(
                            this,
                            c,
                            includeAllDescriptors = includeAllChars,
                            focusIo = if (c === focusChar) focusCharIo else null,
                            focusDescriptor = if (c === focusChar) focusDescriptor else null,
                            focusDescriptorValue = focusDescriptorValue,
                            focusDescriptorErrorStatus = focusDescriptorErrorStatus,
                        )
                    }
                }
            }
        }
    }

    private fun fillCharacteristic(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        characteristic: BluetoothGattCharacteristic,
        includeAllDescriptors: Boolean,
        focusIo: JsonObject?,
        focusDescriptor: BluetoothGattDescriptor?,
        focusDescriptorValue: ByteArray?,
        focusDescriptorErrorStatus: Int?,
    ) {
        val valueHandle = characteristic.instanceId.coerceAtLeast(1)
        val declarationHandle = (valueHandle - 1).coerceAtLeast(1)
        builder.put("type_str", "Characteristic")
        builder.put("utype", "2803")
        builder.put("handle", declarationHandle)
        builder.put("properties", characteristic.properties and 0xFF)
        builder.put("value_handle", valueHandle)
        builder.put("value_uuid", uuidToBtidesString(characteristic.uuid))

        if (focusIo != null) {
            builder.putJsonObject("char_value") {
                put("handle", valueHandle)
                put("value_uuid", uuidToBtidesString(characteristic.uuid))
                putJsonArray("io_array") { add(focusIo) }
            }
        }

        val descriptorsToInclude = when {
            focusDescriptor != null -> listOf(focusDescriptor)
            includeAllDescriptors -> characteristic.descriptors.orEmpty()
            else -> emptyList()
        }
        if (descriptorsToInclude.isNotEmpty()) {
            builder.putJsonArray("descriptors") {
                for (d in descriptorsToInclude) {
                    val obj = buildDescriptor(
                        d,
                        readValue = if (d === focusDescriptor) focusDescriptorValue else null,
                        readErrorStatus = if (d === focusDescriptor) focusDescriptorErrorStatus else null,
                    )
                    if (obj != null) add(obj)
                }
            }
        }
    }

    /**
     * Build one descriptor JSON object. When the descriptor's UUID is one of the schema-recognised
     * 0x2900-0x2905 forms with a parsable value, fill in the corresponding parsed field.
     * Returns null for unknown descriptor types because the BTIDES schema's descriptors union
     * does not define a generic fallback.
     */
    private fun buildDescriptor(
        descriptor: BluetoothGattDescriptor,
        readValue: ByteArray?,
        readErrorStatus: Int?,
    ): JsonObject? {
        val handle = descriptorHandle(descriptor)
        val short = sigUuid16(descriptor.uuid)
        return when (short) {
            0x2900 -> buildJsonObject {
                put("type_str", "Characteristic Descriptor: Characteristic Extended Properties")
                put("UUID", "2900")
                put("handle", handle)
                put("extended_properties", readValue?.let { le16(it) } ?: 0)
            }
            0x2901 -> buildJsonObject {
                put("type_str", "Characteristic Descriptor: Characteristic User Description")
                put("UUID", "2901")
                put("handle", handle)
                val bytes = readValue ?: ByteArray(0)
                put("user_description_hex_str", bytes.toLowerHex())
                tryDecodeUtf8(bytes)?.let { put("utf8_user_description", it) }
            }
            0x2902 -> buildJsonObject {
                put("type_str", "Characteristic Descriptor: Client Characteristic Configuration")
                put("UUID", "2902")
                put("handle", handle)
                put("config_bits", readValue?.let { le16(it) } ?: 0)
            }
            0x2903 -> buildJsonObject {
                put("type_str", "Characteristic Descriptor: Server Characteristic Configuration")
                put("UUID", "2903")
                put("handle", handle)
                put("config_bits", readValue?.let { le16(it) } ?: 0)
            }
            0x2904 -> {
                val v = readValue
                buildJsonObject {
                    put("type_str", "Characteristic Descriptor: Characteristic Presentation Format")
                    put("UUID", "2904")
                    put("handle", handle)
                    if (v != null && v.size >= 7) {
                        put("format", v[0].toInt() and 0xFF)
                        put("exponent", v[1].toInt() and 0xFF)
                        put("unit", ((v[2].toInt() and 0xFF) or ((v[3].toInt() and 0xFF) shl 8)))
                        put("name_space", v[4].toInt() and 0xFF)
                        put("description", ((v[5].toInt() and 0xFF) or ((v[6].toInt() and 0xFF) shl 8)))
                    } else {
                        // Schema requires these fields; emit zero placeholders so the
                        // descriptor's existence is captured even before any read.
                        put("format", 0)
                        put("exponent", 0)
                        put("unit", 0)
                        put("name_space", 0)
                        put("description", 0)
                    }
                }
            }
            0x2905 -> {
                val v = readValue
                buildJsonObject {
                    put("type_str", "Characteristic Descriptor: Characteristic Aggregate Format")
                    put("UUID", "2905")
                    put("handle", handle)
                    putJsonArray("attribute_handles_list") {
                        var i = 0
                        if (v != null && v.size % 2 == 0) {
                            while (i < v.size) {
                                add(le16FromOffset(v, i))
                                i += 2
                            }
                        }
                    }
                }
            }
            else -> null
        }
    }

    private fun ioForCharacteristicRead(value: ByteArray, status: Int): JsonObject {
        return if (status == 0) {
            buildJsonObject {
                put("io_type_str", "Read - ATT_READ_RSP")
                put("io_type", IO_TYPE_READ_RSP)
                put("value_hex_str", value.toLowerHex())
            }
        } else {
            buildJsonObject {
                put("io_type_str", "Error - ATT_ERROR_RSP")
                put("io_type", IO_TYPE_ERROR)
                put("value_hex_str", "")
            }
        }
    }

    private fun computeEndHandle(service: BluetoothGattService, beginHandle: Int): Int {
        var end = beginHandle
        for (c in service.characteristics.orEmpty()) {
            val v = c.instanceId
            if (v > end) end = v
            for (d in c.descriptors.orEmpty()) {
                val h = descriptorHandle(d)
                if (h > end) end = h
            }
        }
        return end.coerceIn(1, 65535)
    }

    /**
     * BluetoothGattDescriptor.getInstanceId() is not in the public Android API. Try reflection
     * first, then fall back to a synthesised handle (characteristic value handle + descriptor
     * position) so we always emit a non-zero, stable, monotonically-increasing handle.
     */
    private fun descriptorHandle(descriptor: BluetoothGattDescriptor): Int {
        runCatching {
            val m = descriptor.javaClass.getMethod("getInstanceId")
            m.isAccessible = true
            val v = m.invoke(descriptor) as? Int
            if (v != null && v > 0) return v.coerceAtMost(65535)
        }
        val char = descriptor.characteristic ?: return 1
        val idx = char.descriptors.orEmpty().indexOf(descriptor).coerceAtLeast(0)
        return (char.instanceId + 1 + idx).coerceIn(1, 65535)
    }

    private fun le16(data: ByteArray): Int = if (data.size >= 2) le16FromOffset(data, 0) else 0
    private fun le16FromOffset(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.toLowerHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun tryDecodeUtf8(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return try {
            val s = String(bytes, Charsets.UTF_8).trimEnd(' ', ' ')
            if (s.all { it.code in 0x20..0x7E || it.code >= 0xA0 || it == '\t' || it == '\n' || it == '\r' }) s else null
        } catch (_: Throwable) {
            null
        }
    }

    private val SIG_UUID_BASE_SUFFIX = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")

    /** Returns the 16-bit form (e.g. 0x2902) if this is a Bluetooth-SIG short UUID, else -1. */
    private fun sigUuid16(uuid: UUID): Int {
        if (uuid.leastSignificantBits != SIG_UUID_BASE_SUFFIX.leastSignificantBits) return -1
        val msb = uuid.mostSignificantBits
        // SIG short UUID has MSB layout 0x0000_XXXX_0000_1000. The mask zeroes XXXX (bits 47..32)
        // and the result must match 0x0000_0000_0000_1000.
        val mask = (0xFFFFL shl 48) or 0xFFFFFFFFL
        if ((msb and mask) != 0x0000000000001000L) return -1
        return ((msb ushr 32) and 0xFFFFL).toInt()
    }

    /**
     * Convert a Java UUID into the BTIDES UUID form.
     * - Bluetooth-SIG short UUIDs render as a 4-char UUID16 hex string (e.g. "180A").
     * - Other UUIDs render as a 36-char dashed UUID128.
     */
    fun uuidToBtidesString(uuid: UUID): String {
        val short = sigUuid16(uuid)
        return if (short in 0..0xFFFF) "%04X".format(short) else uuid.toString()
    }
}
