package com.darkmentor.data.btides

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Synthesizes BTIDES `0x07_SDP_SERVICE_SEARCH_ATTR_RSP` records from the parsed UUID list
 * Android exposes via [android.bluetooth.BluetoothDevice.fetchUuidsWithSdp]. Android does not
 * surface the raw SDP wire bytes, so we build a schema-valid response containing the discovered
 * UUIDs as a single ServiceClassIDList (SDP attribute 0x0001) and tag it with
 * `std_optional_fields.src_file = "android-fetchUuidsWithSdp"` so wire-captured records remain
 * distinguishable downstream.
 *
 * Wire format (per BT Core Spec Vol 3 Part B §4.7 / §3.3):
 *   AttributeListsByteCount        u16 BE — byte count of the AttributeLists DataElementSequence
 *   AttributeLists                 Sequence of per-record AttributeList sequences
 *     AttributeList                Sequence of (AttributeID, AttributeValue) pairs
 *       AttributeID                UnsignedInt16 — 0x0001 ServiceClassIDList
 *       AttributeValue             Sequence of UUID elements
 *         UUID16  : 0x19 BB BB
 *         UUID32  : 0x1A BB BB BB BB
 *         UUID128 : 0x1C BB...(16 BE)
 *   ContinuationState              0x00 (no continuation)
 *
 * Sequence headers use 0x35 (1-byte length) when content < 256 bytes, else 0x36 (2-byte length).
 */
object BTIDESSdpBuilder {

    private const val PDU_ID_SEARCH_ATTR_RSP = 7
    private const val DIRECTION_PERIPHERAL_TO_CENTRAL = 1
    private const val L2CAP_CID_SDP = 0x0040
    private const val SDP_PDU_HEADER_BYTES = 5 // pdu_id(1) + transaction_id(2) + param_len(2)
    private const val SIG_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private const val DESC_UINT16 = 0x09.toByte()
    private const val DESC_UUID16 = 0x19.toByte()
    private const val DESC_UUID32 = 0x1A.toByte()
    private const val DESC_UUID128 = 0x1C.toByte()
    private const val DESC_SEQ_LEN1 = 0x35.toByte()
    private const val DESC_SEQ_LEN2 = 0x36.toByte()

    private val ATTRIBUTE_ID_SERVICE_CLASS_ID_LIST: ByteArray =
        byteArrayOf(DESC_UINT16, 0x00, 0x01)

    /**
     * Build the raw `param` bytes for a 0x07_SDP_SERVICE_SEARCH_ATTR_RSP carrying the given
     * UUIDs as a single ServiceClassIDList AttributeList. Empty input produces a minimal-but-
     * valid 5-byte response (empty outer sequence + zero ContinuationState).
     */
    fun buildSearchAttrRsp(uuids: List<UUID>): ByteArray {
        val outerSeq = if (uuids.isEmpty()) {
            // Empty list-of-AttributeLists: outer sequence with no inner content.
            encodeSequence(ByteArray(0))
        } else {
            val uuidElements = encodeUuidElements(uuids)
            val attributeValueSeq = encodeSequence(uuidElements)
            val attributeListContent = ATTRIBUTE_ID_SERVICE_CLASS_ID_LIST + attributeValueSeq
            val attributeListSeq = encodeSequence(attributeListContent)
            encodeSequence(attributeListSeq)
        }

        val byteCount = outerSeq.size
        val out = ByteArray(2 + outerSeq.size + 1)
        out[0] = ((byteCount ushr 8) and 0xFF).toByte()
        out[1] = (byteCount and 0xFF).toByte()
        outerSeq.copyInto(out, destinationOffset = 2)
        out[out.size - 1] = 0x00 // ContinuationState
        return out
    }

    /**
     * Build a complete `SDPArray` entry (JsonObject) for one synthesized 0x07 response.
     *
     * The `transaction_id` is derived from [timestampMs] and forced non-zero — some downstream
     * tooling treats `0` as "uninitialized" even though the schema permits it.
     */
    fun synthesizeSearchAttrRspRecord(uuids: List<UUID>, timestampMs: Long): JsonObject {
        val rawData = buildSearchAttrRsp(uuids)
        val rawHex = rawData.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val tid = nonZeroTransactionId(timestampMs)
        val l2capLen = SDP_PDU_HEADER_BYTES + rawData.size

        return buildJsonObject {
            putJsonObject("std_optional_fields") {
                putJsonObject("time") {
                    put("unix_time_milli", timestampMs)
                    put("unix_time", timestampMs / 1000L)
                }
                put("src_file", "android-fetchUuidsWithSdp")
            }
            put("pdu_id", PDU_ID_SEARCH_ATTR_RSP)
            put("pdu_id_str", "SDP_SERVICE_SEARCH_ATTR_RSP")
            put("direction", DIRECTION_PERIPHERAL_TO_CENTRAL)
            put("l2cap_cid", L2CAP_CID_SDP)
            put("l2cap_len", l2capLen)
            put("transaction_id", tid)
            put("param_len", rawData.size)
            put("raw_data_hex_str", rawHex)
        }
    }

    private fun nonZeroTransactionId(timestampMs: Long): Int {
        val masked = (timestampMs and 0xFFFFL).toInt()
        return if (masked != 0) masked else 1
    }

    private fun encodeSequence(content: ByteArray): ByteArray {
        return if (content.size < 256) {
            val out = ByteArray(2 + content.size)
            out[0] = DESC_SEQ_LEN1
            out[1] = content.size.toByte()
            content.copyInto(out, destinationOffset = 2)
            out
        } else {
            val out = ByteArray(3 + content.size)
            out[0] = DESC_SEQ_LEN2
            out[1] = ((content.size ushr 8) and 0xFF).toByte()
            out[2] = (content.size and 0xFF).toByte()
            content.copyInto(out, destinationOffset = 3)
            out
        }
    }

    private fun encodeUuidElements(uuids: List<UUID>): ByteArray {
        val out = java.io.ByteArrayOutputStream(uuids.size * 17)
        for (uuid in uuids) {
            val short = sigShortValue(uuid)
            when {
                short != null && short and 0xFFFF.inv() == 0 -> {
                    out.write(DESC_UUID16.toInt() and 0xFF)
                    out.write((short ushr 8) and 0xFF)
                    out.write(short and 0xFF)
                }
                short != null -> {
                    out.write(DESC_UUID32.toInt() and 0xFF)
                    out.write((short ushr 24) and 0xFF)
                    out.write((short ushr 16) and 0xFF)
                    out.write((short ushr 8) and 0xFF)
                    out.write(short and 0xFF)
                }
                else -> {
                    out.write(DESC_UUID128.toInt() and 0xFF)
                    val msb = uuid.mostSignificantBits
                    val lsb = uuid.leastSignificantBits
                    for (i in 7 downTo 0) out.write(((msb ushr (i * 8)) and 0xFFL).toInt())
                    for (i in 7 downTo 0) out.write(((lsb ushr (i * 8)) and 0xFFL).toInt())
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * If [uuid] matches the SIG base (`xxxxxxxx-0000-1000-8000-00805f9b34fb`), returns the high
     * 32 bits as an Int suitable for UUID16/UUID32 encoding. Otherwise returns null and the
     * caller emits a full UUID128.
     */
    private fun sigShortValue(uuid: UUID): Int? {
        val s = uuid.toString().lowercase()
        if (!s.endsWith(SIG_BASE_SUFFIX)) return null
        return s.substring(0, 8).toLongOrNull(16)?.toInt()
    }
}
