package com.darkmentor.data.btides

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Test
import java.util.UUID

class BTIDESSdpBuilderTest {

    @Test
    fun `encodes uuid16 with descriptor 0x19 and two bytes BE`() {
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(listOf(sigUuid16(0x1101)))
        // SerialPort = 0x1101 → expect 0x19 0x11 0x01 somewhere in the encoded blob.
        assertTrue(
            "expected 191101 in ${bytes.toHex()}",
            bytes.toHex().contains("191101"),
        )
    }

    @Test
    fun `encodes uuid128 with descriptor 0x1C and sixteen bytes BE`() {
        val u = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(listOf(u))
        // 0x1C followed by the big-endian 16-byte UUID value.
        assertTrue(
            "expected 1c11111111222233334444555555555555 in ${bytes.toHex()}",
            bytes.toHex().contains("1c11111111222233334444555555555555"),
        )
    }

    @Test
    fun `picks 0x35 header when payload under 256 bytes`() {
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(listOf(sigUuid16(0x1101)))
        // After the 2-byte AttributeListsByteCount, the outer sequence header is at index 2.
        assertEquals(0x35.toByte(), bytes[2])
    }

    @Test
    fun `picks 0x36 header when payload at or over 256 bytes`() {
        // 16 UUID128 entries × 17 bytes = 272 bytes of element payload, which forces every
        // wrapping sequence (UUID list, AttributeList, outer list-of-lists) over 255 bytes
        // and therefore into the 2-byte-length 0x36 header form.
        val many = (0 until 16).map {
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-${"%012x".format(it.toLong())}")
        }
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(many)
        // The outer sequence header is at index 2.
        assertEquals(0x36.toByte(), bytes[2])
    }

    @Test
    fun `attribute lists byte count matches outer sequence size`() {
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(listOf(sigUuid16(0x1101)))
        val byteCount = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        // Outer sequence header at byte 2 is 0x35 (1-byte len), so outer total = 2 + len.
        assertEquals(0x35.toByte(), bytes[2])
        val outerLen = bytes[3].toInt() and 0xFF
        val outerTotal = outerLen + 2
        assertEquals(outerTotal, byteCount)
        // Total bytes = 2 (count) + outerTotal + 1 (continuation state).
        assertEquals(2 + outerTotal + 1, bytes.size)
    }

    @Test
    fun `param_len matches raw_data length`() {
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = listOf(sigUuid16(0x1101)),
            timestampMs = 1_700_000_000_000L,
        )
        val paramLen = record["param_len"]!!.jsonPrimitive.intOrNull!!
        val rawHex = record["raw_data_hex_str"]!!.jsonPrimitive.contentOrNull!!
        assertEquals(rawHex.length / 2, paramLen)
    }

    @Test
    fun `transaction_id is non-zero`() {
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = listOf(sigUuid16(0x1101)),
            timestampMs = 1_700_000_000_000L,
        )
        val tid = record["transaction_id"]!!.jsonPrimitive.intOrNull!!
        assertTrue("transaction_id should be non-zero, got $tid", tid != 0)
    }

    @Test
    fun `transaction_id stays non-zero even when timestamp masks to zero`() {
        // 0x1_0000 has its low 16 bits all-zero. Naive masking would yield 0; the builder must
        // pick a different non-zero value for downstream tooling that treats 0 as uninitialized.
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = listOf(sigUuid16(0x1101)),
            timestampMs = 0x10000L,
        )
        val tid = record["transaction_id"]!!.jsonPrimitive.intOrNull!!
        assertTrue("transaction_id should be non-zero even at boundary, got $tid", tid != 0)
    }

    @Test
    fun `roundtrip decode recovers uuids`() {
        val input = listOf(
            sigUuid16(0x1101), // SerialPort
            sigUuid16(0x110A), // AudioSource
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
        )
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(input)
        val decoded = decodeSearchAttrRspUuids(bytes)
        assertEquals(input.toSet(), decoded.toSet())
    }

    @Test
    fun `empty uuid list emits minimal valid pdu`() {
        val bytes = BTIDESSdpBuilder.buildSearchAttrRsp(emptyList())
        // 2 bytes AttributeListsByteCount + 2 bytes empty sequence (0x35 0x00) + 1 byte
        // ContinuationState = 5 bytes total, the smallest possible 0x07 PDU body.
        assertEquals(5, bytes.size)
        val byteCount = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        assertEquals(2, byteCount)
        assertEquals(0x35.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
        assertEquals(0x00.toByte(), bytes[4])
    }

    @Test
    fun `std_optional_fields src_file is set`() {
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = listOf(sigUuid16(0x1101)),
            timestampMs = 1_700_000_000_000L,
        )
        val srcFile = record["std_optional_fields"]!!
            .jsonObject["src_file"]!!.jsonPrimitive.contentOrNull
        assertEquals("android-fetchUuidsWithSdp", srcFile)
    }

    @Test
    fun `synthesized record carries pdu_id 7 and direction 1 and l2cap_cid 0x40`() {
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = listOf(sigUuid16(0x1101)),
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(7, record["pdu_id"]!!.jsonPrimitive.intOrNull)
        assertEquals(
            "SDP_SERVICE_SEARCH_ATTR_RSP",
            record["pdu_id_str"]!!.jsonPrimitive.contentOrNull,
        )
        assertEquals(1, record["direction"]!!.jsonPrimitive.intOrNull)
        assertEquals(0x0040, record["l2cap_cid"]!!.jsonPrimitive.intOrNull)
        // l2cap_len = 5 (PDU header) + param_len.
        val paramLen = record["param_len"]!!.jsonPrimitive.intOrNull!!
        val l2capLen = record["l2cap_len"]!!.jsonPrimitive.intOrNull!!
        assertEquals(5 + paramLen, l2capLen)
    }

    @Test
    fun `synthesized record carries unix timestamps under std_optional_fields_time`() {
        val ts = 1_700_000_000_123L
        val record = BTIDESSdpBuilder.synthesizeSearchAttrRspRecord(
            uuids = emptyList(),
            timestampMs = ts,
        )
        val timeObj = record["std_optional_fields"]!!.jsonObject["time"]!!.jsonObject
        assertEquals(ts, timeObj["unix_time_milli"]!!.jsonPrimitive.longOrNull)
        assertEquals(ts / 1000L, timeObj["unix_time"]!!.jsonPrimitive.longOrNull)
    }

    // ---------- helpers ----------

    private fun sigUuid16(value: Int): UUID =
        UUID.fromString("0000${"%04x".format(value)}-0000-1000-8000-00805f9b34fb")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Minimal SDP element walker that decodes a 0x07_SDP_SERVICE_SEARCH_ATTR_RSP raw payload
     * and extracts the UUIDs from each AttributeList's ServiceClassIDList (attribute 0x0001).
     * Used by `roundtrip decode recovers uuids` to confirm the encoder produced parseable bytes.
     */
    private fun decodeSearchAttrRspUuids(raw: ByteArray): List<UUID> {
        val out = mutableListOf<UUID>()
        if (raw.size < 5) return out
        val byteCount = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val outerEnd = 2 + byteCount
        if (outerEnd > raw.size) return out
        val outerRange = decodeSequence(raw, 2, outerEnd) ?: return out
        var p = outerRange.contentStart
        while (p < outerRange.contentEnd) {
            val inner = decodeSequence(raw, p, outerRange.contentEnd) ?: break
            var q = inner.contentStart
            while (q < inner.contentEnd) {
                val attrId = decodeUInt16(raw, q) ?: break
                q = attrId.afterIndex
                if (attrId.value == 0x0001) {
                    val uuidSeq = decodeSequence(raw, q, inner.contentEnd) ?: break
                    q = uuidSeq.afterIndex
                    var r = uuidSeq.contentStart
                    while (r < uuidSeq.contentEnd) {
                        val u = decodeUuid(raw, r) ?: break
                        out += u.value
                        r = u.afterIndex
                    }
                } else {
                    break
                }
            }
            p = inner.afterIndex
        }
        return out
    }

    private data class SeqRange(val contentStart: Int, val contentEnd: Int, val afterIndex: Int)
    private data class IntDecoded(val value: Int, val afterIndex: Int)
    private data class UuidDecoded(val value: UUID, val afterIndex: Int)

    private fun decodeSequence(raw: ByteArray, off: Int, limit: Int): SeqRange? {
        if (off >= limit) return null
        return when (val desc = raw[off].toInt() and 0xFF) {
            0x35 -> {
                if (off + 2 > limit) return null
                val len = raw[off + 1].toInt() and 0xFF
                val start = off + 2
                val end = start + len
                if (end > limit) return null
                SeqRange(start, end, end)
            }
            0x36 -> {
                if (off + 3 > limit) return null
                val len = ((raw[off + 1].toInt() and 0xFF) shl 8) or (raw[off + 2].toInt() and 0xFF)
                val start = off + 3
                val end = start + len
                if (end > limit) return null
                SeqRange(start, end, end)
            }
            else -> error("unexpected sequence descriptor 0x${"%02x".format(desc)} at offset $off")
        }
    }

    private fun decodeUInt16(raw: ByteArray, off: Int): IntDecoded? {
        if (off + 3 > raw.size) return null
        if ((raw[off].toInt() and 0xFF) != 0x09) return null
        val v = ((raw[off + 1].toInt() and 0xFF) shl 8) or (raw[off + 2].toInt() and 0xFF)
        return IntDecoded(v, off + 3)
    }

    private fun decodeUuid(raw: ByteArray, off: Int): UuidDecoded? {
        if (off >= raw.size) return null
        return when (raw[off].toInt() and 0xFF) {
            0x19 -> {
                if (off + 3 > raw.size) return null
                val v = ((raw[off + 1].toInt() and 0xFF) shl 8) or (raw[off + 2].toInt() and 0xFF)
                UuidDecoded(sigUuidFromShort(v.toLong()), off + 3)
            }
            0x1A -> {
                if (off + 5 > raw.size) return null
                val v = ((raw[off + 1].toLong() and 0xFFL) shl 24) or
                    ((raw[off + 2].toLong() and 0xFFL) shl 16) or
                    ((raw[off + 3].toLong() and 0xFFL) shl 8) or
                    (raw[off + 4].toLong() and 0xFFL)
                UuidDecoded(sigUuidFromShort(v), off + 5)
            }
            0x1C -> {
                if (off + 17 > raw.size) return null
                var msb = 0L
                for (i in 0 until 8) msb = (msb shl 8) or (raw[off + 1 + i].toLong() and 0xFFL)
                var lsb = 0L
                for (i in 8 until 16) lsb = (lsb shl 8) or (raw[off + 1 + i].toLong() and 0xFFL)
                UuidDecoded(UUID(msb, lsb), off + 17)
            }
            else -> null
        }
    }

    private fun sigUuidFromShort(short: Long): UUID =
        UUID.fromString("${"%08x".format(short)}-0000-1000-8000-00805f9b34fb")

    @Suppress("unused")
    private fun JsonObject.dump(): String = toString()
}
