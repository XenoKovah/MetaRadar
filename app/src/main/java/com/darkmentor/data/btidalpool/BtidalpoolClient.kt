package com.darkmentor.data.btidalpool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP client for the BTIDALPOOL **Rust** upload server (port 3568, self-signed cert pinned via
 * the bundled `btidalpool_server.crt` asset) and the BTIDALPOOL OAuth helper server (port 7653,
 * LetsEncrypt cert validated by the standard Android trust store).
 *
 * The upload server speaks the BTPL codec — a zstd-compressed CBOR envelope wrapped in a 9-byte
 * "BTPL" frame (see [BtidalpoolCodec]) — over `Content-Type: application/x-btidalpool-cbor-zstd`.
 * Requests carry `{ auth: {token, refresh_token, use_test_db}, payload: {cmd: "upload",
 * btides_json: <raw JSON bytes>} }`; the server dedups content itself (returning a
 * `duplicate_upload` error we treat as success), so there is no separate client-side hash
 * pre-flight.
 *
 * Two TLS configurations because the two endpoints use different certificate authorities and the
 * upload server's self-signed cert isn't in the system trust store. The 7653 refresh proxy and the
 * Google userinfo lookup use the standard system trust store.
 */
class BtidalpoolClient(
    private val context: Context,
) {

    sealed class UploadResult {
        object Success : UploadResult()
        object AlreadyPresent : UploadResult()
        object AuthFailed : UploadResult()
        data class Failed(val httpCode: Int, val body: String) : UploadResult()
    }

    /** Opaque pair returned from [refreshToken]. */
    data class RefreshedTokens(val token: String, val refreshToken: String)

    /** Transport-level result of a single framed POST: HTTP code, response Content-Type, raw body. */
    private data class RawResponse(val code: Int, val contentType: String, val body: ByteArray)

    /**
     * Upload [btidesFile] to the Rust pool server as a BTPL frame. The whole file is read into
     * memory and CBOR+zstd-framed (the old :3567 path streamed; the codec can't), so files above
     * [MAX_UPLOAD_BYTES] are refused up-front — both to honour the server's cap and to avoid an
     * OOM building an oversize frame. The server dedups, so a re-upload comes back as
     * [UploadResult.AlreadyPresent] rather than an error.
     *
     * [onProgress] reports `(bytesSent, totalBytes)` over the compressed frame as it streams to the
     * socket, so the UI bar advances 0 → 1 across the network phase.
     */
    suspend fun uploadFile(
        btidesFile: File,
        token: String,
        refreshToken: String,
        useTestDb: Boolean,
        onProgress: (suspend (bytesSent: Long, totalBytes: Long) -> Unit)? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        val fileLen = btidesFile.length()
        if (fileLen > MAX_UPLOAD_BYTES) {
            Timber.w(
                "BTIDES file %s is %d bytes (> %d cap); refusing :3568 upload",
                btidesFile.name, fileLen, MAX_UPLOAD_BYTES,
            )
            return@withContext UploadResult.Failed(
                413,
                "File is $fileLen bytes; BTIDALPOOL caps a single upload at $MAX_UPLOAD_BYTES " +
                    "bytes (~10 MiB). Upload more often so each log stays small.",
            )
        }
        val jsonBytes = btidesFile.readBytes()
        val frame = try {
            BtidalpoolCodec.encodeUploadFrame(token, refreshToken, useTestDb, jsonBytes)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to build BTPL upload frame for %s", btidesFile.name)
            return@withContext UploadResult.Failed(-1, "could not encode upload frame: ${t.message}")
        }
        mapUploadResponse(postFrame(UPLOAD_URL, frame, onProgress))
    }

    /** Translate a framed POST result into an [UploadResult]. */
    private fun mapUploadResponse(raw: RawResponse): UploadResult {
        // Per the protocol a genuine codec reply — success OR a structured error — carries the
        // codec Content-Type even on 4xx/5xx. Anything else (text/plain: 405/415/413/429/malformed
        // body) is a transport-layer error we surface verbatim.
        if (!raw.contentType.startsWith(BtidalpoolCodec.CONTENT_TYPE)) {
            val text = String(raw.body, Charsets.UTF_8).trim()
            return UploadResult.Failed(raw.code, text.ifEmpty { "HTTP ${raw.code} (non-codec response)" })
        }
        val wire = try {
            BtidalpoolCodec.decodeResponse(raw.body)
        } catch (t: Throwable) {
            Timber.e(t, "Malformed BTPL response frame (HTTP %d)", raw.code)
            return UploadResult.Failed(raw.code, "malformed response frame: ${t.message}")
        }
        return when (wire.result) {
            "ok" -> UploadResult.Success
            "err" -> when (wire.kind) {
                // Server already holds this content — same terminal "don't re-send" outcome.
                "duplicate_upload" -> UploadResult.AlreadyPresent
                // Token rejected — the interactor refreshes and retries once.
                "unauthorized" -> UploadResult.AuthFailed
                else -> UploadResult.Failed(raw.code, "${wire.kind ?: "err"}: ${wire.message ?: ""}".trim())
            }
            else -> UploadResult.Failed(raw.code, "unexpected result '${wire.result}': ${wire.message ?: ""}".trim())
        }
    }

    /**
     * Verify the access token by fetching the authenticated user's email from Google's userinfo
     * endpoint. Uses the standard system trust store (Google's cert is publicly trusted). Returns
     * null on any non-200 response.
     */
    suspend fun fetchUserEmail(accessToken: String): String? = withContext(Dispatchers.IO) {
        val url = URL(USERINFO_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                Timber.d("userinfo lookup returned HTTP %d", code)
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj: JsonObject = Json.parseToJsonElement(body).jsonObject
            obj["email"]?.jsonPrimitive?.contentOrNull
        } catch (t: Throwable) {
            Timber.w(t, "userinfo lookup failed")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Exchange a refresh token for a fresh access token via the BTIDALPOOL refresh proxy (which
     * holds the OAuth client_secret server-side). Uses the LetsEncrypt-validated trust store.
     * Returns null on any error.
     */
    suspend fun refreshToken(refreshToken: String): RefreshedTokens? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("refresh_token", refreshToken)
            put("client_id", BtidalpoolAuthRepository.CLIENT_ID)
        }.toString()
        try {
            val url = URL(REFRESH_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Timber.w("refresh returned HTTP %d: %s", code, err)
                    return@withContext null
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val obj: JsonObject = Json.parseToJsonElement(resp).jsonObject
                val newToken = obj["token"]?.jsonPrimitive?.contentOrNull
                val newRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
                if (newToken.isNullOrBlank() || newRefresh.isNullOrBlank()) null
                else RefreshedTokens(newToken, newRefresh)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            Timber.w(t, "refresh request failed")
            null
        }
    }

    /**
     * POST a BTPL [frame] to the upload server, validating the server's TLS cert against the pinned
     * `btidalpool_server.crt`. With FixedLengthStreamingMode set, the frame flows straight to the
     * socket in 64KB chunks (so [onProgress] can advance the UI) without HttpsURLConnection
     * buffering it. Reads the full response body — codec frame or text/plain transport error — for
     * the caller to classify.
     */
    @Throws(IOException::class)
    private suspend fun postFrame(
        urlStr: String,
        frame: ByteArray,
        onProgress: (suspend (bytesSent: Long, totalBytes: Long) -> Unit)?,
    ): RawResponse {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedSocketFactory()
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = "POST"
            connectTimeout = 30_000
            // Long enough for a slow-Wi-Fi upload to finish without the connection dropping.
            readTimeout = 5 * 60_000
            doOutput = true
            setRequestProperty("Content-Type", BtidalpoolCodec.CONTENT_TYPE)
            setRequestProperty("Accept", BtidalpoolCodec.CONTENT_TYPE)
            setFixedLengthStreamingMode(frame.size.toLong())
        }
        return try {
            val total = frame.size.toLong()
            conn.outputStream.use { out ->
                var sent = 0
                while (sent < frame.size) {
                    val n = minOf(64 * 1024, frame.size - sent)
                    out.write(frame, sent, n)
                    sent += n
                    onProgress?.invoke(sent.toLong(), total)
                }
            }
            val code = conn.responseCode
            val ctype = conn.contentType ?: ""
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            RawResponse(code, ctype, respBody)
        } finally {
            conn.disconnect()
        }
    }

    private fun pinnedSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val pinned = loadPinnedCertificate()
        // Custom trust manager that accepts only the pinned cert (by equality on the encoded
        // form). Standard X509TrustManager checks chain, hostname, etc. — we deliberately bypass
        // those because the cert is self-signed.
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val first = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("Server presented no certificate")
                if (!first.encoded.contentEquals(pinned.encoded)) {
                    throw java.security.cert.CertificateException(
                        "Server certificate did not match pinned BTIDALPOOL cert"
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        return ctx.socketFactory
    }

    private fun loadPinnedCertificate(): X509Certificate {
        return context.assets.open(PINNED_CERT_ASSET).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    companion object {
        private const val UPLOAD_URL = "https://btidalpool.ddns.net:3568/"
        private const val REFRESH_URL = "https://btidalpool.ddns.net:7653/refresh"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val PINNED_CERT_ASSET = "btidalpool_server.crt"

        /**
         * Hard cap on a single upload's raw BTIDES JSON. The Rust server rejects larger bodies, and
         * — unlike the streaming :3567 path — the CBOR+zstd frame is built fully in memory, so we
         * refuse oversize files up-front rather than risk an OOM. 10 MiB matches the server limit.
         */
        private const val MAX_UPLOAD_BYTES = 10L * 1024 * 1024
    }
}
