package f.cking.software.data.btidalpool

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
 * HTTP client for the BTIDALPOOL upload server (port 3567, self-signed cert pinned via the
 * bundled `btidalpool_server.crt` asset) and the BTIDALPOOL OAuth helper server (port 7653,
 * LetsEncrypt cert validated by the standard Android trust store).
 *
 * Two TLS configurations because the two endpoints use different certificate authorities and
 * the upload server's self-signed cert isn't in the system trust store.
 */
class BtidalpoolClient(
    private val context: Context,
) {

    /** Result of a check-hash query against the upload server. */
    sealed class CheckHashResult {
        object NotPresent : CheckHashResult()
        object AlreadyPresent : CheckHashResult()
        object AuthFailed : CheckHashResult()
        data class Failed(val httpCode: Int, val body: String) : CheckHashResult()
    }

    sealed class UploadResult {
        object Success : UploadResult()
        object AlreadyPresent : UploadResult()
        object AuthFailed : UploadResult()
        data class Failed(val httpCode: Int, val body: String) : UploadResult()
    }

    /** Opaque pair returned from [refreshToken]. */
    data class RefreshedTokens(val token: String, val refreshToken: String)

    /**
     * POST `{"command":"check_hash", "hash": <sha1>, "token":..., "refresh_token":..., "use_test_db":...}`.
     */
    suspend fun checkHash(
        sha1: String,
        token: String,
        refreshToken: String,
        useTestDb: Boolean,
    ): CheckHashResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("command", "check_hash")
            put("hash", sha1)
            put("token", token)
            put("refresh_token", refreshToken)
            put("use_test_db", useTestDb)
        }.toString()
        val (code, respBody) = postPinned(UPLOAD_URL, body)
        when {
            code == 200 -> CheckHashResult.NotPresent
            // Server returns 400 + a specific message both when content already exists AND when
            // the OAuth token is invalid — disambiguate on the body text. Auth failures send the
            // user back through SSO; "already exists" is just a no-op success.
            code == 400 && respBody.contains("already exists", ignoreCase = true) -> CheckHashResult.AlreadyPresent
            code == 400 && respBody.contains("Invalid OAuth", ignoreCase = true) -> CheckHashResult.AuthFailed
            else -> CheckHashResult.Failed(code, respBody)
        }
    }

    /**
     * POST `{"command":"upload", "btides_content":<json>, "token":..., "refresh_token":..., "use_test_db":...}`.
     * `btidesJson` is the literal canonical JSON of the file content; this method splices it in
     * as a JSON value (no double-encoding).
     */
    suspend fun upload(
        btidesJson: String,
        token: String,
        refreshToken: String,
        useTestDb: Boolean,
    ): UploadResult = withContext(Dispatchers.IO) {
        // Build the outer envelope by string concatenation rather than parse+re-serialize so we
        // don't pay to load the (potentially multi-MB) BTIDES content into a JsonElement tree.
        val sb = StringBuilder(btidesJson.length + 256)
        sb.append("{\"command\":\"upload\",\"btides_content\":")
        sb.append(btidesJson)
        sb.append(",\"token\":")
        sb.append(JSONObject.quote(token))
        sb.append(",\"refresh_token\":")
        sb.append(JSONObject.quote(refreshToken))
        sb.append(",\"use_test_db\":")
        sb.append(if (useTestDb) "true" else "false")
        sb.append("}")
        val (code, respBody) = postPinned(UPLOAD_URL, sb.toString())
        when {
            code == 200 -> UploadResult.Success
            code == 400 && respBody.contains("already exists", ignoreCase = true) -> UploadResult.AlreadyPresent
            code == 400 && respBody.contains("Invalid OAuth", ignoreCase = true) -> UploadResult.AuthFailed
            else -> UploadResult.Failed(code, respBody)
        }
    }

    /**
     * Verify the access token by fetching the authenticated user's email from Google's
     * userinfo endpoint. Uses the standard system trust store (Google's cert is publicly
     * trusted). Returns null on any non-200 response.
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
     * Exchange a refresh token for a fresh access token via the BTIDALPOOL refresh proxy
     * (which holds the OAuth client_secret server-side). Uses the LetsEncrypt-validated trust
     * store. Returns null on any error.
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
     * POST a JSON body to the upload server, validating the server's TLS cert against the
     * pinned `btidalpool_server.crt` shipped in assets. Returns (status code, response body).
     */
    @Throws(IOException::class)
    private fun postPinned(urlStr: String, body: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = pinnedSocketFactory()
            // Self-signed cert has CN=btidalpool.ddns.net but the issuer chain is also
            // self-rooted; bypass hostname verification because the pinning by-cert above
            // already proves we're talking to the intended endpoint.
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to resp
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
        private const val UPLOAD_URL = "https://btidalpool.ddns.net:3567/"
        private const val REFRESH_URL = "https://btidalpool.ddns.net:7653/refresh"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
        private const val PINNED_CERT_ASSET = "btidalpool_server.crt"
    }
}
