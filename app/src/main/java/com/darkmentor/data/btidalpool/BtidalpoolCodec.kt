@file:OptIn(ExperimentalSerializationApi::class)

package com.darkmentor.data.btidalpool

import com.github.luben.zstd.Zstd
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.ByteArrayOutputStream

/**
 * Wire codec for the BTIDALPOOL **Rust** server (port 3568). The transport is a single "BTPL"
 * frame per request and per response:
 *
 * ```
 * "BTPL"            4 bytes  ASCII magic
 * 0x01              1 byte   frame version
 * uncompressed_len  4 bytes  big-endian uint32 = byte length of the *uncompressed* CBOR
 * <payload>         N bytes  zstd-compressed CBOR
 * ```
 *
 * Request CBOR is the envelope `{ "auth": {...}, "payload": { "cmd": ..., ... } }`. The server
 * deserialises `payload` as a serde *internally-tagged* enum keyed on `"cmd"`, i.e. a flat map
 * `{cmd, <fields>}` — so each command is modelled as a concrete class with a constant `cmd`
 * field rather than kotlinx polymorphism (which would emit a `"type"` discriminator key).
 *
 * `btides_json` MUST be a CBOR **byte string** (major type 2) holding the raw UTF-8 JSON bytes,
 * never a nested object or text string — hence [ByteString].
 *
 * Response CBOR is one of `{"result":"ok", "message"}`, `{"result":"err", "kind", "message"}`,
 * or `{"result":"query_result", "records", "btides_json"}`. [WireResponse] is a flat superset of
 * all three (every field but `result` optional) decoded with `ignoreUnknownKeys`.
 */
object BtidalpoolCodec {

    const val CONTENT_TYPE = "application/x-btidalpool-cbor-zstd"

    private val MAGIC = byteArrayOf(
        'B'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(),
    )
    private const val VERSION: Byte = 0x01
    private const val HEADER_LEN = 9
    private const val ZSTD_LEVEL = 3

    private val cbor = Cbor {
        // The server may add response fields we don't model; tolerate them. And always emit our
        // request fields — the constant `cmd` discriminator is a default value, and without
        // encodeDefaults the server would see a payload with no `cmd` tag and reject it.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Auth(
        val token: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("use_test_db") val useTestDb: Boolean,
    )

    @Serializable
    data class UploadPayload(
        @ByteString @SerialName("btides_json") val btidesJson: ByteArray,
        val cmd: String = "upload",
    )

    @Serializable
    data class UploadEnvelope(
        val auth: Auth,
        val payload: UploadPayload,
    )

    /** Flat view over all three server response shapes (`ok` / `err` / `query_result`). */
    @Serializable
    data class WireResponse(
        val result: String,
        val message: String? = null,
        val kind: String? = null,
        val records: Long? = null,
        @ByteString @SerialName("btides_json") val btidesJson: ByteArray? = null,
    )

    /** Build the BTPL upload frame for [btidesJson] (raw UTF-8 JSON bytes). */
    fun encodeUploadFrame(
        token: String,
        refreshToken: String,
        useTestDb: Boolean,
        btidesJson: ByteArray,
    ): ByteArray {
        val envelope = UploadEnvelope(
            auth = Auth(token = token, refreshToken = refreshToken, useTestDb = useTestDb),
            payload = UploadPayload(btidesJson = btidesJson),
        )
        return frame(cbor.encodeToByteArray(envelope))
    }

    /** Wrap raw CBOR [cborBytes] in a zstd-compressed BTPL frame. */
    fun frame(cborBytes: ByteArray): ByteArray {
        val compressed = Zstd.compress(cborBytes, ZSTD_LEVEL)
        val out = ByteArrayOutputStream(HEADER_LEN + compressed.size)
        out.write(MAGIC)
        out.write(VERSION.toInt())
        val len = cborBytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(compressed)
        return out.toByteArray()
    }

    /**
     * Decode a BTPL response [frame] to its [WireResponse]. Throws [IllegalArgumentException] on a
     * malformed frame (bad magic/version, length mismatch) so the caller can surface it as a
     * transport failure rather than a protocol-level error.
     */
    fun decodeResponse(frame: ByteArray): WireResponse {
        require(frame.size >= HEADER_LEN) { "frame shorter than $HEADER_LEN-byte header (${frame.size})" }
        require(
            frame[0] == MAGIC[0] && frame[1] == MAGIC[1] &&
                frame[2] == MAGIC[2] && frame[3] == MAGIC[3],
        ) { "bad BTPL magic" }
        require(frame[4] == VERSION) { "unsupported BTPL version ${frame[4].toInt()}" }
        val declaredLen =
            ((frame[5].toInt() and 0xFF) shl 24) or
                ((frame[6].toInt() and 0xFF) shl 16) or
                ((frame[7].toInt() and 0xFF) shl 8) or
                (frame[8].toInt() and 0xFF)
        require(declaredLen >= 0) { "negative declared length" }
        val compressed = frame.copyOfRange(HEADER_LEN, frame.size)
        val cborBytes = Zstd.decompress(compressed, declaredLen)
        require(cborBytes.size == declaredLen) {
            "declared length $declaredLen != decompressed ${cborBytes.size}"
        }
        return cbor.decodeFromByteArray(cborBytes)
    }
}
