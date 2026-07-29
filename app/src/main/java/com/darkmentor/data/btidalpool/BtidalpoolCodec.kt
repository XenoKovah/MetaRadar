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
 * 0x04              1 byte   frame version
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
 * [V4WireResponse] is a flat superset of every v4 success and error shape (every field but
 * `result` optional) decoded with `ignoreUnknownKeys`.
 */
object BtidalpoolCodec {

    const val CONTENT_TYPE = "application/x-btidalpool-cbor-zstd"
    const val V4_CONTENT_TYPE = "$CONTENT_TYPE; version=4"

    private val MAGIC = byteArrayOf(
        'B'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(),
    )
    private const val V4_VERSION: Byte = 0x04
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
    data class QueryParams(
        val bdaddr: String? = null,
        @SerialName("NOT_bdaddr") val notBdaddr: List<String>? = null,
        @SerialName("bdaddr_regex") val bdaddrRegex: List<String>? = null,
        @SerialName("NOT_bdaddr_regex") val notBdaddrRegex: List<String>? = null,
        @SerialName("name_regex") val nameRegex: List<String>? = null,
        @SerialName("NOT_name_regex") val notNameRegex: List<String>? = null,
        @SerialName("company_regex") val companyRegex: List<String>? = null,
        @SerialName("NOT_company_regex") val notCompanyRegex: List<String>? = null,
        @SerialName("UUID_regex") val uuidRegex: List<String>? = null,
        @SerialName("NOT_UUID_regex") val notUuidRegex: List<String>? = null,
        @SerialName("MSD_regex") val msdRegex: List<String>? = null,
        @SerialName("LL_VERSION_IND") val llVersionInd: String? = null,
        @SerialName("LMP_VERSION_RES") val lmpVersionRes: String? = null,
        @SerialName("GPS_exclude_upper_left") val gpsExcludeUpperLeft: String? = null,
        @SerialName("GPS_exclude_lower_right") val gpsExcludeLowerRight: String? = null,
        @SerialName("require_GPS") val requireGps: Boolean = false,
        @SerialName("require_GATT_any") val requireGattAny: Boolean = false,
        @SerialName("require_GATT_values") val requireGattValues: Boolean = false,
        @SerialName("require_SMP") val requireSmp: Boolean = false,
        @SerialName("require_SMP_legacy_pairing") val requireSmpLegacyPairing: Boolean = false,
        @SerialName("require_SDP") val requireSdp: Boolean = false,
        @SerialName("require_LL_VERSION_IND") val requireLlVersionInd: Boolean = false,
        @SerialName("require_LMP_VERSION_RES") val requireLmpVersionRes: Boolean = false,
    )

    @Serializable
    data class V4GoogleAuth(
        val scheme: String = "google",
        @SerialName("access_token") val accessToken: String,
    )

    @Serializable
    data class V4SessionAuth(
        val scheme: String = "session",
        val token: String,
    )

    @Serializable
    data class V4CreateSessionPayload(val cmd: String = "create_session")

    @Serializable
    data class V4ManifestPayload(
        @SerialName("content_sha256") val contentSha256: String,
        @SerialName("total_size") val totalSize: Long,
        @SerialName("chunk_sha256") val chunkSha256: List<String>,
        @SerialName("use_test_db") val useTestDb: Boolean,
        val cmd: String = "manifest",
    )

    @Serializable
    data class V4PutChunkPayload(
        @SerialName("upload_id") val uploadId: String,
        val index: Int,
        @ByteString val data: ByteArray,
        val cmd: String = "put_chunk",
    )

    @Serializable
    data class V4UploadIdPayload(
        @SerialName("upload_id") val uploadId: String,
        val cmd: String,
    )

    @Serializable
    data class UploadReceipt(
        @SerialName("receipt_id") val receiptId: String,
        @SerialName("upload_id") val uploadId: String,
        @SerialName("content_sha256") val contentSha256: String,
        @SerialName("canonical_sha1") val canonicalSha1: String,
        @SerialName("total_size") val totalSize: Long,
        @SerialName("completed_at_unix") val completedAtUnix: Long,
        @SerialName("use_test_db") val useTestDb: Boolean,
        val deduplicated: Boolean,
    )

    @Serializable
    data class V4CreateSessionEnvelope(
        val auth: V4GoogleAuth,
        val payload: V4CreateSessionPayload,
    )

    @Serializable
    data class V4UploadPayload(
        @ByteString @SerialName("btides_json") val btidesJson: ByteArray,
        @SerialName("use_test_db") val useTestDb: Boolean,
        val cmd: String = "upload",
    )

    @Serializable
    data class V4CheckHashPayload(
        val hash: String,
        val cmd: String = "check_hash",
    )

    @Serializable
    data class V4QueryPayload(
        val params: QueryParams,
        @SerialName("use_test_db") val useTestDb: Boolean,
        val cmd: String,
    )

    @Serializable
    data class V4UploadEnvelope(
        val auth: V4SessionAuth,
        val payload: V4UploadPayload,
    )

    @Serializable
    data class V4CheckHashEnvelope(
        val auth: V4SessionAuth,
        val payload: V4CheckHashPayload,
    )

    @Serializable
    data class V4QueryEnvelope(
        val auth: V4SessionAuth,
        val payload: V4QueryPayload,
    )

    @Serializable
    data class V4ManifestEnvelope(
        val auth: V4SessionAuth,
        val payload: V4ManifestPayload,
    )

    @Serializable
    data class V4PutChunkEnvelope(
        val auth: V4SessionAuth,
        val payload: V4PutChunkPayload,
    )

    @Serializable
    data class V4UploadIdEnvelope(
        val auth: V4SessionAuth,
        val payload: V4UploadIdPayload,
    )

    @Serializable
    data class V4Date(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val micros: Long,
    )

    @Serializable
    data class V4Time(
        val negative: Boolean,
        val days: Long,
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
        val micros: Long,
    )

    /** Lossless field-stable v4 representation of a native MySQL value. */
    @Serializable
    data class V4DbValue(
        val kind: String,
        @ByteString val bytes: ByteArray? = null,
        val signed: Long? = null,
        /** Decimal text so values above [Long.MAX_VALUE] remain lossless on the JVM. */
        val unsigned: String? = null,
        val float: Double? = null,
        val date: V4Date? = null,
        val time: V4Time? = null,
    )

    @Serializable
    data class V4NativeTable(
        val columns: List<String>,
        val rows: List<List<V4DbValue>>,
        val truncated: Boolean = false,
    )

    @Serializable
    data class V4NativeDevice(
        val bdaddr: String,
        val tables: Map<String, V4NativeTable>,
    )

    @Serializable
    data class V4NativeQueryResult(
        val devices: List<V4NativeDevice>,
        @SerialName("total_rows") val totalRows: Long,
        @SerialName("row_limit") val rowLimit: Long,
        val truncated: Boolean,
    )

    /** Flat view of every v4 success and error shape. */
    @Serializable
    data class V4WireResponse(
        val result: String,
        val token: String? = null,
        @SerialName("expires_at_unix") val expiresAtUnix: Long? = null,
        @SerialName("upload_id") val uploadId: String? = null,
        @SerialName("missing_chunks") val missingChunks: List<Int> = emptyList(),
        val receipt: UploadReceipt? = null,
        val index: Int? = null,
        @SerialName("already_present") val alreadyPresent: Boolean? = null,
        val records: Long? = null,
        @ByteString @SerialName("btides_json") val btidesJson: ByteArray? = null,
        val query: V4NativeQueryResult? = null,
        val kind: String? = null,
        val message: String? = null,
    )

    @Serializable
    internal data class V4RequestProbe(
        val payload: V4PayloadProbe,
    )

    @Serializable
    internal data class V4PayloadProbe(
        val cmd: String,
        @SerialName("upload_id") val uploadId: String? = null,
        val index: Int? = null,
        @SerialName("content_sha256") val contentSha256: String? = null,
        @SerialName("total_size") val totalSize: Long? = null,
        @SerialName("chunk_sha256") val chunkSha256: List<String> = emptyList(),
        @SerialName("use_test_db") val useTestDb: Boolean? = null,
        @ByteString val data: ByteArray = byteArrayOf(),
    )

    fun encodeV4CreateSessionFrame(accessToken: String): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4CreateSessionEnvelope(
                auth = V4GoogleAuth(accessToken = accessToken),
                payload = V4CreateSessionPayload(),
            ),
        ),
    )

    fun encodeV4UploadFrame(
        sessionToken: String,
        btidesJson: ByteArray,
        useTestDb: Boolean,
    ): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4UploadEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4UploadPayload(btidesJson, useTestDb),
            ),
        ),
    )

    fun encodeV4CheckHashFrame(sessionToken: String, hash: String): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4CheckHashEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4CheckHashPayload(hash),
            ),
        ),
    )

    fun encodeV4LegacyQueryFrame(
        sessionToken: String,
        params: QueryParams,
        useTestDb: Boolean,
    ): ByteArray = encodeV4QueryFrame(sessionToken, params, useTestDb, "legacy_query")

    fun encodeV4NativeQueryFrame(
        sessionToken: String,
        params: QueryParams,
        useTestDb: Boolean,
    ): ByteArray = encodeV4QueryFrame(sessionToken, params, useTestDb, "native_query")

    private fun encodeV4QueryFrame(
        sessionToken: String,
        params: QueryParams,
        useTestDb: Boolean,
        command: String,
    ): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4QueryEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4QueryPayload(params, useTestDb, command),
            ),
        ),
    )

    fun encodeV4ManifestFrame(
        sessionToken: String,
        contentSha256: String,
        totalSize: Long,
        chunkSha256: List<String>,
        useTestDb: Boolean,
    ): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4ManifestEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4ManifestPayload(contentSha256, totalSize, chunkSha256, useTestDb),
            ),
        ),
    )

    fun encodeV4PutChunkFrame(
        sessionToken: String,
        uploadId: String,
        index: Int,
        data: ByteArray,
    ): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4PutChunkEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4PutChunkPayload(uploadId, index, data),
            ),
        ),
    )

    fun encodeV4StatusFrame(sessionToken: String, uploadId: String): ByteArray =
        encodeV4UploadIdFrame(sessionToken, uploadId, "status")

    fun encodeV4FinalizeFrame(sessionToken: String, uploadId: String): ByteArray =
        encodeV4UploadIdFrame(sessionToken, uploadId, "finalize")

    private fun encodeV4UploadIdFrame(
        sessionToken: String,
        uploadId: String,
        command: String,
    ): ByteArray = frameV4(
        cbor.encodeToByteArray(
            V4UploadIdEnvelope(
                auth = V4SessionAuth(token = sessionToken),
                payload = V4UploadIdPayload(uploadId, command),
            ),
        ),
    )

    internal fun encodeV4ResponseForTest(response: V4WireResponse): ByteArray =
        frame(cbor.encodeToByteArray(response), V4_VERSION)

    internal fun decodeV4RequestForTest(frame: ByteArray): V4RequestProbe =
        cbor.decodeFromByteArray(unframe(frame, V4_VERSION))

    private fun frameV4(cborBytes: ByteArray): ByteArray = frame(cborBytes, V4_VERSION)

    private fun frame(cborBytes: ByteArray, version: Byte): ByteArray {
        val compressed = Zstd.compress(cborBytes, ZSTD_LEVEL)
        val out = ByteArrayOutputStream(HEADER_LEN + compressed.size)
        writeFrameHeader(out, cborBytes.size, version)
        out.write(compressed)
        return out.toByteArray()
    }

    fun decodeV4Response(frame: ByteArray): V4WireResponse =
        cbor.decodeFromByteArray(unframe(frame, V4_VERSION))

    private fun unframe(frame: ByteArray, expectedVersion: Byte): ByteArray {
        require(frame.size >= HEADER_LEN) { "frame shorter than $HEADER_LEN-byte header (${frame.size})" }
        require(
            frame[0] == MAGIC[0] && frame[1] == MAGIC[1] &&
                frame[2] == MAGIC[2] && frame[3] == MAGIC[3],
        ) { "bad BTPL magic" }
        require(frame[4] == expectedVersion) {
            "unsupported BTPL version ${frame[4].toInt()}; expected ${expectedVersion.toInt()}"
        }
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
        return cborBytes
    }

    private fun writeFrameHeader(
        out: ByteArrayOutputStream,
        uncompressedLength: Int,
        version: Byte,
    ) {
        out.write(MAGIC)
        out.write(version.toInt())
        out.write((uncompressedLength ushr 24) and 0xFF)
        out.write((uncompressedLength ushr 16) and 0xFF)
        out.write((uncompressedLength ushr 8) and 0xFF)
        out.write(uncompressedLength and 0xFF)
    }

}
