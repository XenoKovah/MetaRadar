package com.darkmentor.data.btidalpool

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Persists Google OAuth credentials obtained via the BTIDALPOOL SSO redirect server. The user
 * pastes the JSON `{"token":"...","refresh_token":"..."}` blob the redirect page prints; we
 * verify it by calling Google's userinfo endpoint, cache it, and refresh on demand via the
 * BTIDALPOOL `/refresh` endpoint (which holds the OAuth client secret server-side).
 *
 * Token refresh goes through BTIDALPOOL rather than Google directly because the BTIDALPOOL
 * Google OAuth client is a "web" type and requires the client secret — we don't ship that to
 * the device. Mirrors the Python `oauth_helper.AuthClient` flow.
 */
class BtidalpoolAuthRepository(
    private val sharedPreferences: SharedPreferences,
    private val client: BtidalpoolClient,
    legacyPrefs: SharedPreferences,
) {

    /**
     * Cached SSO state. `email` is non-null after a successful userinfo lookup; null while
     * we hold a token whose validity hasn't been confirmed yet.
     */
    data class AuthState(
        val token: String,
        val refreshToken: String,
        val email: String?,
    )

    init {
        // One-shot migration: tokens used to live in the default `app-prefs` file, which is
        // covered by Android's cloud auto-backup. They now live in their own prefs file that
        // backup_rules.xml / data_extraction_rules.xml explicitly <exclude>. On the first run
        // after upgrade, copy any existing tokens out of the legacy file and delete them
        // there. Runs before [state] is initialized so [load] sees the migrated values.
        migrateFromLegacyPrefs(legacyPrefs)
    }

    private val state = MutableStateFlow(load())

    fun observe(): StateFlow<AuthState?> = state.asStateFlow()

    fun current(): AuthState? = state.value

    /**
     * URL the user opens in a browser to start the Google SSO flow. The redirect lands on the
     * BTIDALPOOL OAuth callback, which displays the resulting JSON token blob for the user to
     * copy back into the app. `prompt=consent` + `access_type=offline` ensures we always get a
     * refresh token (Google withholds it on subsequent grants without consent re-prompt).
     */
    fun authorizationUrl(): String {
        val params = mapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPES,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return AUTH_URI + "?" + params.entries.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
    }

    /**
     * Outcome of a paste-token sign-in attempt.
     *
     * The distinction that matters: [Invalid] means the token was *definitively* rejected —
     * malformed, failed Google validation, or the upload server answered "Invalid OAuth".
     * A [Valid] with `serverReachable == false` means the token passed Google validation but we
     * couldn't reach the upload server to confirm it end-to-end. These must NOT collapse into a
     * single "invalid" verdict: a down/unreachable upload server is not a bad credential, and
     * reporting it as one sends the user back through SSO for nothing.
     */
    sealed class SignInOutcome {
        data class Valid(val state: AuthState, val serverReachable: Boolean) : SignInOutcome()
        data class Invalid(val reason: String) : SignInOutcome()
    }

    sealed class RefreshOutcome {
        data class Success(val state: AuthState) : RefreshOutcome()
        data class InvalidGrant(val message: String) : RefreshOutcome()
        data class TransientFailure(
            val message: String,
            val retryAfterMillis: Long? = null,
        ) : RefreshOutcome()
    }

    /**
     * Parse the JSON the user pasted, validate it against Google's userinfo endpoint (with one
     * refresh attempt on failure), then probe the BTIDALPOOL upload server, and persist on
     * success.
     *
     * The Google userinfo check only proves the token is a valid Google token; the probe — a
     * check-hash query for an all-zeros SHA1 (a digest no real BTIDES export produces) — adds an
     * end-to-end confirmation against the upload server. Three cases:
     *  - server says "not present"/"already exists" -> [SignInOutcome.Valid] (serverReachable = true)
     *  - server says "Invalid OAuth"                -> [SignInOutcome.Invalid] (definitive reject)
     *  - server unreachable / non-auth HTTP error   -> [SignInOutcome.Valid] (serverReachable = false)
     *
     * The last case still counts as valid because Google already vouched for the token; we just
     * couldn't reach BTIDALPOOL to double-check. We persist on both Valid branches so the user
     * ends up signed in (and can upload once the server is reachable again).
     */
    suspend fun signInWithPastedJson(pasted: String): SignInOutcome = withContext(Dispatchers.IO) {
        val parsed = parseTokenBlob(pasted)
            ?: return@withContext SignInOutcome.Invalid("Pasted text is not a valid token JSON.")
        val verified = try {
            verifyAndRefreshIfNeeded(parsed)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.d(e, "BTIDALPOOL token failed Google validation")
            return@withContext SignInOutcome.Invalid(e.message ?: "Token validation failed.")
        }
        when (probeUploadServer(verified)) {
            ProbeResult.REJECTED -> SignInOutcome.Invalid("The BTIDALPOOL server rejected these credentials.")
            ProbeResult.ACCEPTED -> {
                persist(verified)
                SignInOutcome.Valid(verified, serverReachable = true)
            }
            ProbeResult.UNREACHABLE -> {
                persist(verified)
                SignInOutcome.Valid(verified, serverReachable = false)
            }
        }
    }

    /**
     * Sign in from a native Google serverAuthCode (the seamless phone flow — no browser, no
     * paste). Exchanges the one-time code for tokens via the BTIDALPOOL helper's `/exchange_app`
     * endpoint, then runs the exact same Google-userinfo validation and upload-server probe as
     * [signInWithPastedJson], persisting on success. The exchanged tokens are web-client tokens,
     * so the existing [refresh] proxy keeps working unchanged.
     */
    suspend fun signInWithServerAuthCode(authCode: String): SignInOutcome = withContext(Dispatchers.IO) {
        val exchanged = try {
            client.exchangeServerAuthCode(authCode)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.d(e, "BTIDALPOOL serverAuthCode exchange failed")
            return@withContext SignInOutcome.Invalid(e.message ?: "Sign-in failed.")
        } ?: return@withContext SignInOutcome.Invalid("The sign-in server did not return tokens.")

        val verified = try {
            verifyAndRefreshIfNeeded(AuthState(exchanged.token, exchanged.refreshToken, email = null))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.d(e, "BTIDALPOOL token failed Google validation")
            return@withContext SignInOutcome.Invalid(e.message ?: "Token validation failed.")
        }
        when (probeUploadServer(verified)) {
            ProbeResult.REJECTED -> SignInOutcome.Invalid("The BTIDALPOOL server rejected these credentials.")
            ProbeResult.ACCEPTED -> {
                persist(verified)
                SignInOutcome.Valid(verified, serverReachable = true)
            }
            ProbeResult.UNREACHABLE -> {
                persist(verified)
                SignInOutcome.Valid(verified, serverReachable = false)
            }
        }
    }

    private enum class ProbeResult { ACCEPTED, REJECTED, UNREACHABLE }

    /**
     * End-to-end credential probe against the BTIDALPOOL upload server (a check-hash query for
     * an all-zeros digest, which a working token always gets "not present" back for):
     *  - [ProbeResult.ACCEPTED]    the server authenticated us ("not present"/"already exists").
     *  - [ProbeResult.REJECTED]    an explicit "Invalid OAuth" — the credentials are bad.
     *  - [ProbeResult.UNREACHABLE] a connect/read failure or any other HTTP error. These are
     *                              server-availability problems, NOT bad credentials, so they
     *                              must not be reported as a rejection.
     */
    private suspend fun probeUploadServer(state: AuthState): ProbeResult {
        return try {
            when (client.checkHash(ALL_ZERO_SHA1, state.token)) {
                BtidalpoolClient.CheckHashResult.NotPresent,
                BtidalpoolClient.CheckHashResult.AlreadyPresent -> ProbeResult.ACCEPTED
                BtidalpoolClient.CheckHashResult.AuthFailed -> ProbeResult.REJECTED
                is BtidalpoolClient.CheckHashResult.Failed -> ProbeResult.UNREACHABLE
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "BTIDALPOOL check-hash probe could not reach the upload server")
            ProbeResult.UNREACHABLE
        }
    }

    /**
     * Force-refresh the cached token. Only a definitive invalid-grant response clears cached
     * credentials; timeouts, rate limits, and helper-server failures preserve them for retry.
     */
    suspend fun refresh(): RefreshOutcome = withContext(Dispatchers.IO) {
        val cur = state.value
            ?: return@withContext RefreshOutcome.InvalidGrant("No cached BTIDALPOOL credentials")
        when (val refreshed = client.refreshToken(cur.refreshToken)) {
            is BtidalpoolClient.TokenRefreshResult.Success -> {
                // The refresh endpoint returns the same email account; reuse the cached email
                // rather than re-querying Google's userinfo (saves a round-trip).
                val newState = AuthState(
                    refreshed.tokens.token,
                    refreshed.tokens.refreshToken,
                    cur.email,
                )
                persist(newState)
                RefreshOutcome.Success(newState)
            }
            is BtidalpoolClient.TokenRefreshResult.InvalidGrant -> {
                Timber.w("BTIDALPOOL refresh credential was rejected; clearing cached credentials")
                signOut()
                RefreshOutcome.InvalidGrant(refreshed.message)
            }
            is BtidalpoolClient.TokenRefreshResult.TransientFailure -> {
                // Critical distinction: a timeout, 429, or helper-server 5xx does not revoke the
                // user's refresh token. Keep credentials so WorkManager can retry later.
                Timber.w("BTIDALPOOL token refresh temporarily unavailable: %s", refreshed.message)
                RefreshOutcome.TransientFailure(refreshed.message, refreshed.retryAfterMillis)
            }
        }
    }

    fun signOut() {
        sharedPreferences.edit {
            remove(KEY_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EMAIL)
        }
        state.value = null
    }

    /**
     * Validate the token by calling Google's userinfo endpoint. On 401 we attempt a single
     * refresh and retry. Throws if the refresh-and-retry path also fails.
     */
    private suspend fun verifyAndRefreshIfNeeded(initial: AuthState): AuthState {
        client.fetchUserEmail(initial.token)?.let { email ->
            return initial.copy(email = email)
        }
        Timber.d("BTIDALPOOL userinfo lookup failed; attempting refresh")
        val refreshed = when (val result = client.refreshToken(initial.refreshToken)) {
            is BtidalpoolClient.TokenRefreshResult.Success -> result.tokens
            is BtidalpoolClient.TokenRefreshResult.InvalidGrant ->
                throw IllegalStateException("Token refresh was rejected. Sign in again.")
            is BtidalpoolClient.TokenRefreshResult.TransientFailure ->
                throw IllegalStateException("Token validation service is temporarily unavailable.")
        }
        val email = client.fetchUserEmail(refreshed.token)
            ?: throw IllegalStateException("Token validation failed even after refresh. Sign in again.")
        return AuthState(refreshed.token, refreshed.refreshToken, email)
    }

    private fun parseTokenBlob(raw: String): AuthState? {
        // The BTIDALPOOL redirect page sometimes wraps the JSON in surrounding HTML/whitespace
        // when the user copies the wrong span; pull the first {...} substring as a forgiveness.
        val trimmed = raw.trim()
        val candidate = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            trimmed.substring(start, end + 1)
        }
        return try {
            val obj: JsonObject = Json.parseToJsonElement(candidate).jsonObject
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
            val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
            if (token.isNullOrBlank() || refresh.isNullOrBlank()) null
            else AuthState(token, refresh, email = null)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to parse pasted token blob")
            null
        }
    }

    private fun persist(s: AuthState) {
        sharedPreferences.edit {
            putString(KEY_TOKEN, s.token)
            putString(KEY_REFRESH_TOKEN, s.refreshToken)
            if (s.email != null) putString(KEY_EMAIL, s.email) else remove(KEY_EMAIL)
        }
        state.value = s
    }

    private fun load(): AuthState? {
        val token = sharedPreferences.getString(KEY_TOKEN, null) ?: return null
        val refresh = sharedPreferences.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val email = sharedPreferences.getString(KEY_EMAIL, null)
        return AuthState(token, refresh, email)
    }

    private fun migrateFromLegacyPrefs(legacyPrefs: SharedPreferences) {
        // Already migrated (or never had legacy entries) — nothing to do.
        if (sharedPreferences.contains(KEY_TOKEN)) return
        val token = legacyPrefs.getString(KEY_TOKEN, null) ?: return
        val refresh = legacyPrefs.getString(KEY_REFRESH_TOKEN, null)
        val email = legacyPrefs.getString(KEY_EMAIL, null)
        sharedPreferences.edit {
            putString(KEY_TOKEN, token)
            if (refresh != null) putString(KEY_REFRESH_TOKEN, refresh)
            if (email != null) putString(KEY_EMAIL, email)
        }
        legacyPrefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EMAIL)
        }
    }

    companion object {
        // BTIDALPOOL's Google OAuth **web** client. Must equal the CLIENT_ID the 7653 server
        // exchanges with (google-SSO-redirect-and-token-print-server.py) — it's the serverClientId
        // for the native serverAuthCode flow AND the client_id for the browser/paste + /refresh
        // flows. (The separate *Android* OAuth client is matched by Google via package + signing
        // SHA-1, not referenced here.) Project moved 934838710114 → 6849068466 on 2026-06-20.
        const val CLIENT_ID = "6849068466-1sone95u0ihio99646tn60s234d88hge.apps.googleusercontent.com"
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
        const val REDIRECT_URI = "https://btidalpool.ddns.net:7653/oauth2callback"
        const val SCOPES =
            "https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

        private const val KEY_TOKEN = "btidalpool_token"
        private const val KEY_REFRESH_TOKEN = "btidalpool_refresh_token"
        private const val KEY_EMAIL = "btidalpool_email"

        // A 40-hex-char (160-bit) all-zeros SHA1 — the digest used by the post-sign-in
        // credential probe. No real BTIDES export hashes to this, so the server reliably
        // answers "not present" for any valid token.
        private const val ALL_ZERO_SHA1 = "0000000000000000000000000000000000000000"
    }
}
