package com.darkmentor.data.btidalpool

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
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
 * The app uses only the unified `/v4` interface. Google credentials are exchanged once per
 * short-lived BTIDALPOOL session; upload/query requests carry only that session token.
 *
 * Two TLS configurations because the two endpoints use different certificate authorities and the
 * upload server's self-signed cert isn't in the system trust store. The 7653 refresh proxy and the
 * Google userinfo lookup use the standard system trust store.
 */
class BtidalpoolClient private constructor(
    private val context: Context?,
    private val injectedUploadClient: OkHttpClient?,
    private val v4Url: String,
    private val retryRuntime: BtidalpoolRetryRuntime,
) {
    constructor(context: Context) : this(
        context = context,
        injectedUploadClient = null,
        v4Url = V4_URL,
        retryRuntime = SystemBtidalpoolRetryRuntime,
    )

    internal constructor(
        uploadClient: OkHttpClient,
        v4Url: String,
        retryRuntime: BtidalpoolRetryRuntime,
    ) : this(
        context = null,
        injectedUploadClient = uploadClient,
        v4Url = v4Url,
        retryRuntime = retryRuntime,
    )

    private data class PinnedTls(
        val socketFactory: javax.net.ssl.SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    private val v4MediaType: MediaType = BtidalpoolCodec.V4_CONTENT_TYPE.toMediaType()
    private val pinnedTls: PinnedTls by lazy { buildPinnedTls() }
    private val uploadHttpClient: OkHttpClient by lazy {
        injectedUploadClient ?: OkHttpClient.Builder()
            .sslSocketFactory(pinnedTls.socketFactory, pinnedTls.trustManager)
            // The server certificate is pinned byte-for-byte below. Preserve the existing
            // deployment's hostname behavior until its self-signed certificate carries SANs.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            // Upload retry policy lives in the durable outbox. Avoid an invisible second POST.
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Result of a check-hash query. The upload path no longer uses this (the server dedups on
     *  upload); it survives only as the sign-in connectivity/auth probe in [BtidalpoolAuthRepository]. */
    sealed class CheckHashResult {
        object NotPresent : CheckHashResult()
        object AlreadyPresent : CheckHashResult()
        object AuthFailed : CheckHashResult()
        data class Failed(val httpCode: Int, val body: String) : CheckHashResult()
    }

    data class BusyRetryState(
        val httpStatus: Int,
        val completedAttempts: Int,
        val delayMillis: Long,
    ) {
        val message: String
            get() = "Server busy; retrying in ${((delayMillis + 999L) / 1_000L)} s…"
    }

    sealed class UploadResult {
        object Success : UploadResult()
        object AlreadyPresent : UploadResult()
        object AuthFailed : UploadResult()
        data class RetryableFailure(
            val httpCode: Int,
            val body: String,
            val retryAfterMillis: Long? = null,
        ) : UploadResult()
        data class RetryExhausted(
            val httpCode: Int,
            val body: String,
        ) : UploadResult()
        data class PermanentFailure(val httpCode: Int, val body: String) : UploadResult()
    }

    /** Unified result surface for every BTIDALPOOL capability exposed by `/v4`. */
    sealed class V4Result {
        data class Session(val token: String, val expiresAtUnix: Long) : V4Result()
        data class Ok(val message: String) : V4Result()
        data class Manifest(
            val uploadId: String,
            val missingChunks: List<Int>,
            val receipt: BtidalpoolCodec.UploadReceipt?,
        ) : V4Result()
        data class Chunk(val uploadId: String, val index: Int, val alreadyPresent: Boolean) : V4Result()
        data class Status(
            val uploadId: String,
            val missingChunks: List<Int>,
            val receipt: BtidalpoolCodec.UploadReceipt?,
        ) : V4Result()
        data class Finalized(val receipt: BtidalpoolCodec.UploadReceipt) : V4Result()
        data class LegacyQuery(val records: Long, val btidesJson: ByteArray) : V4Result()
        data class NativeQuery(val query: BtidalpoolCodec.V4NativeQueryResult) : V4Result()
        data class Error(
            val httpCode: Int,
            val kind: String?,
            val message: String,
            val missingChunks: List<Int> = emptyList(),
            val retryExhausted: Boolean = false,
        ) : V4Result()
        data class TransportFailure(val message: String) : V4Result()
    }

    /** Opaque pair returned from [refreshToken]. */
    data class RefreshedTokens(val token: String, val refreshToken: String)

    sealed class TokenRefreshResult {
        data class Success(val tokens: RefreshedTokens) : TokenRefreshResult()
        data class InvalidGrant(val httpCode: Int, val message: String) : TokenRefreshResult()
        data class TransientFailure(
            val httpCode: Int,
            val message: String,
            val retryAfterMillis: Long? = null,
        ) : TokenRefreshResult()
    }

    /** Transport-level result of a single framed POST: HTTP code, response Content-Type, raw body. */
    private data class RawResponse(
        val code: Int,
        val contentType: String,
        val body: ByteArray,
        val retryAfterMillis: Long?,
        val overloadExhausted: Boolean = false,
    )

    /** Authenticate through v4, then use `check_hash` as the sign-in connectivity probe. */
    suspend fun checkHash(
        sha1: String,
        googleAccessToken: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): CheckHashResult = withContext(Dispatchers.IO) {
        repeat(2) { sessionAttempt ->
            when (val session = createV4Session(googleAccessToken, onBusyRetry)) {
                is V4Result.Session -> when (
                    val checked = v4CheckHash(session.token, sha1, onBusyRetry)
                ) {
                    is V4Result.Ok -> return@withContext CheckHashResult.NotPresent
                    is V4Result.Error -> when (checked.kind) {
                        "duplicate_upload" ->
                            return@withContext CheckHashResult.AlreadyPresent
                        "session_expired" -> if (sessionAttempt == 0) {
                            return@repeat
                        } else {
                            return@withContext CheckHashResult.Failed(
                                checked.httpCode,
                                checked.message,
                            )
                        }
                        "unauthorized" ->
                            return@withContext CheckHashResult.AuthFailed
                        else ->
                            return@withContext CheckHashResult.Failed(
                                checked.httpCode,
                                checked.message,
                            )
                    }
                    is V4Result.TransportFailure ->
                        return@withContext CheckHashResult.Failed(-1, checked.message)
                    else ->
                        return@withContext CheckHashResult.Failed(
                            -1,
                            "unexpected v4 check_hash response",
                        )
                }
                is V4Result.Error -> {
                    if (session.kind == "unauthorized") {
                        return@withContext CheckHashResult.AuthFailed
                    }
                    return@withContext CheckHashResult.Failed(
                        session.httpCode,
                        session.message,
                    )
                }
                is V4Result.TransportFailure ->
                    return@withContext CheckHashResult.Failed(-1, session.message)
                else ->
                    return@withContext CheckHashResult.Failed(
                        -1,
                        "unexpected v4 create_session response",
                    )
            }
        }
        CheckHashResult.Failed(-1, "v4 session expired twice during check_hash")
    }

    suspend fun createV4Session(
        googleAccessToken: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4CreateSessionFrame(googleAccessToken),
        onBusyRetry,
    )

    /** Whole-file upload through v4. Resumable uploads should use [v4Manifest]. */
    suspend fun v4Upload(
        sessionToken: String,
        btidesJson: ByteArray,
        useTestDb: Boolean,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4UploadFrame(sessionToken, btidesJson, useTestDb),
        onBusyRetry,
    )

    suspend fun v4CheckHash(
        sessionToken: String,
        canonicalSha1: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4CheckHashFrame(sessionToken, canonicalSha1),
        onBusyRetry,
    )

    suspend fun v4LegacyQuery(
        sessionToken: String,
        params: BtidalpoolCodec.QueryParams,
        useTestDb: Boolean,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4LegacyQueryFrame(sessionToken, params, useTestDb),
        onBusyRetry,
    )

    suspend fun v4NativeQuery(
        sessionToken: String,
        params: BtidalpoolCodec.QueryParams,
        useTestDb: Boolean,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4NativeQueryFrame(sessionToken, params, useTestDb),
        onBusyRetry,
    )

    suspend fun v4Manifest(
        sessionToken: String,
        contentSha256: String,
        totalSize: Long,
        chunkSha256: List<String>,
        useTestDb: Boolean,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4ManifestFrame(
            sessionToken,
            contentSha256,
            totalSize,
            chunkSha256,
            useTestDb,
        ),
        onBusyRetry,
    )

    suspend fun v4PutChunk(
        sessionToken: String,
        uploadId: String,
        index: Int,
        data: ByteArray,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4PutChunkFrame(sessionToken, uploadId, index, data),
        onBusyRetry,
    )

    suspend fun v4Status(
        sessionToken: String,
        uploadId: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4StatusFrame(sessionToken, uploadId),
        onBusyRetry,
    )

    suspend fun v4Finalize(
        sessionToken: String,
        uploadId: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)? = null,
    ): V4Result = postV4(
        BtidalpoolCodec.encodeV4FinalizeFrame(sessionToken, uploadId),
        onBusyRetry,
    )

    private suspend fun postV4(
        frame: ByteArray,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)?,
    ): V4Result = withContext(Dispatchers.IO) {
        val raw = try {
            postFrame(
                frame.toRequestBody(v4MediaType),
                v4Url,
                BtidalpoolCodec.V4_CONTENT_TYPE,
                onBusyRetry,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            return@withContext V4Result.TransportFailure(e.message ?: e::class.java.simpleName)
        }
        val wire = try {
            if (!raw.contentType.startsWith(BtidalpoolCodec.CONTENT_TYPE)) null
            else BtidalpoolCodec.decodeV4Response(raw.body)
        } catch (t: Throwable) {
            return@withContext V4Result.Error(
                raw.code,
                null,
                "malformed response frame: ${t.message}",
                retryExhausted = raw.overloadExhausted,
            )
        }
        if (wire == null) {
            return@withContext V4Result.Error(
                raw.code,
                null,
                raw.textOrHttpError(),
                retryExhausted = raw.overloadExhausted,
            )
        }
        if (raw.overloadExhausted) {
            return@withContext V4Result.Error(
                raw.code,
                wire.kind,
                "Server busy; retry limit reached (${wire.message.orEmpty()})",
                wire.missingChunks,
                retryExhausted = true,
            )
        }
        when (wire.result) {
            "session" -> {
                val token = wire.token
                val expires = wire.expiresAtUnix
                if (token.isNullOrBlank() || expires == null) {
                    V4Result.Error(raw.code, null, "session response omitted credentials")
                } else {
                    V4Result.Session(token, expires)
                }
            }
            "ok" -> V4Result.Ok(wire.message.orEmpty())
            "manifest" -> {
                val id = wire.uploadId
                if (id.isNullOrBlank()) V4Result.Error(raw.code, null, "manifest omitted upload_id")
                else V4Result.Manifest(id, wire.missingChunks, wire.receipt)
            }
            "chunk" -> {
                val id = wire.uploadId
                val index = wire.index
                if (id.isNullOrBlank() || index == null) {
                    V4Result.Error(raw.code, null, "chunk acknowledgement was incomplete")
                } else {
                    V4Result.Chunk(id, index, wire.alreadyPresent ?: false)
                }
            }
            "status" -> {
                val id = wire.uploadId
                if (id.isNullOrBlank()) V4Result.Error(raw.code, null, "status omitted upload_id")
                else V4Result.Status(id, wire.missingChunks, wire.receipt)
            }
            "finalized" -> wire.receipt?.let(V4Result::Finalized)
                ?: V4Result.Error(raw.code, null, "finalize omitted receipt")
            "query_result" -> V4Result.LegacyQuery(
                wire.records ?: 0L,
                wire.btidesJson ?: byteArrayOf(),
            )
            "native_query_result" -> wire.query?.let(V4Result::NativeQuery)
                ?: V4Result.Error(raw.code, null, "native query omitted result data")
            "err" -> V4Result.Error(
                raw.code,
                wire.kind,
                wire.message ?: wire.kind ?: "BTIDALPOOL error",
                wire.missingChunks,
            )
            else -> V4Result.Error(raw.code, null, "unexpected result '${wire.result}'")
        }
    }

    private fun RawResponse.textOrHttpError(): String =
        String(body, Charsets.UTF_8).trim().ifEmpty { "HTTP $code" }

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
     * Returns a typed result so invalid grants are not confused with transient server/network
     * failures.
     */
    suspend fun refreshToken(refreshToken: String): TokenRefreshResult = withContext(Dispatchers.IO) {
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
                    val message = err.ifBlank { "refresh HTTP $code" }
                    return@withContext when {
                        code == 400 || code == 401 || code == 403 ->
                            TokenRefreshResult.InvalidGrant(code, message)
                        code == 408 || code == 429 || code in 500..599 ->
                            TokenRefreshResult.TransientFailure(
                                code,
                                message,
                                BtidalpoolOverloadRetry.parseRetryAfterMillis(
                                    conn.getHeaderField("Retry-After"),
                                    retryRuntime.wallClockMillis(),
                                ),
                            )
                        else -> TokenRefreshResult.TransientFailure(code, message)
                    }
                }
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val obj: JsonObject = Json.parseToJsonElement(resp).jsonObject
                val newToken = obj["token"]?.jsonPrimitive?.contentOrNull
                val newRefresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
                if (newToken.isNullOrBlank() || newRefresh.isNullOrBlank()) {
                    TokenRefreshResult.TransientFailure(code, "refresh response omitted credentials")
                } else {
                    TokenRefreshResult.Success(RefreshedTokens(newToken, newRefresh))
                }
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "refresh request failed")
            TokenRefreshResult.TransientFailure(-1, t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Exchange a one-time Google serverAuthCode — obtained natively by the app via Google
     * Identity Services (`AuthorizationClient.requestOfflineAccess`) — for the access + refresh
     * tokens, through the BTIDALPOOL OAuth helper's `/exchange_app` endpoint. The helper holds
     * the web client_secret and performs the Google token exchange server-side, then returns
     * `{"token","refresh_token"}` directly over TLS (no token ever transits a browser URL).
     * Uses the LetsEncrypt-validated trust store, like [refreshToken]. Returns null on any error.
     */
    suspend fun exchangeServerAuthCode(authCode: String): RefreshedTokens? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("auth_code", authCode)
        }.toString()
        try {
            val url = URL(EXCHANGE_URL)
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
                    Timber.w("exchange_app returned HTTP %d: %s", code, err)
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
            Timber.w(t, "exchange_app request failed")
            null
        }
    }

    /**
     * POST one BTPL request through the reusable OkHttp connection pool. Cancelling the coroutine
     * cancels the in-flight socket call. Reads the small response frame (or text transport error)
     * for the caller to classify.
     */
    @Throws(IOException::class)
    private suspend fun postFrame(
        body: RequestBody,
        endpoint: String,
        mediaType: String,
        onBusyRetry: (suspend (BusyRetryState?) -> Unit)?,
    ): RawResponse {
        val startedAt = retryRuntime.monotonicMillis()
        var completedAttempts = 0
        var busyReported = false
        while (true) {
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", mediaType)
                .header("Accept", mediaType)
                .post(body)
                .build()
            val raw = uploadHttpClient.newCall(request).awaitResponse().use { response ->
                RawResponse(
                    code = response.code,
                    contentType = response.header("Content-Type").orEmpty(),
                    body = response.body.bytes(),
                    retryAfterMillis = BtidalpoolOverloadRetry.parseRetryAfterMillis(
                        response.header("Retry-After"),
                        retryRuntime.wallClockMillis(),
                    ),
                )
            }
            completedAttempts += 1
            if (!BtidalpoolOverloadRetry.isOverload(raw.code)) {
                if (busyReported) onBusyRetry?.invoke(null)
                return raw
            }

            val decision = BtidalpoolOverloadRetry.decision(
                completedAttempts = completedAttempts,
                elapsedMillis = (retryRuntime.monotonicMillis() - startedAt).coerceAtLeast(0L),
                retryAfterMillis = raw.retryAfterMillis,
                jitterUnit = retryRuntime.jitterUnit(),
            ) ?: run {
                if (busyReported) onBusyRetry?.invoke(null)
                return raw.copy(overloadExhausted = true)
            }
            busyReported = true
            onBusyRetry?.invoke(
                BusyRetryState(
                    httpStatus = raw.code,
                    completedAttempts = completedAttempts,
                    delayMillis = decision.delayMillis,
                ),
            )
            // kotlinx.coroutines delay is cancellation-aware. Tests inject the same contract.
            retryRuntime.sleep(decision.delayMillis)
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(response))
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    private fun buildPinnedTls(): PinnedTls {
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
        return PinnedTls(ctx.socketFactory, tm)
    }

    private fun loadPinnedCertificate(): X509Certificate {
        return checkNotNull(context).assets.open(PINNED_CERT_ASSET).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    companion object {
        private const val V4_URL = "https://btidalpool.ddns.net:3568/v4"
        private const val REFRESH_URL = "https://btidalpool.ddns.net:7653/refresh"
        // Phone-app SSO: exchanges a native Google serverAuthCode for tokens (see [exchangeServerAuthCode]).
        private const val EXCHANGE_URL = "https://btidalpool.ddns.net:7653/exchange_app"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val PINNED_CERT_ASSET = "btidalpool_server.crt"
    }
}
