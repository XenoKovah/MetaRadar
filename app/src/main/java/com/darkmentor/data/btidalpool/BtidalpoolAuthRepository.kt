package com.darkmentor.data.btidalpool

import android.content.SharedPreferences
import androidx.core.content.edit
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
     * Parse the JSON the user pasted, validate it against Google's userinfo endpoint (with one
     * refresh attempt on failure), persist on success.
     */
    suspend fun signInWithPastedJson(pasted: String): Result<AuthState> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = parseTokenBlob(pasted)
                ?: throw IllegalArgumentException("Pasted text is not a valid token JSON. Expected fields: \"token\", \"refresh_token\".")
            val verified = verifyAndRefreshIfNeeded(parsed)
            persist(verified)
            verified
        }
    }

    /**
     * Force-refresh the cached token. Returns the new state on success, or null and clears the
     * cache on failure (e.g., refresh token revoked by the user on Google's side).
     */
    suspend fun refresh(): AuthState? = withContext(Dispatchers.IO) {
        val cur = state.value ?: return@withContext null
        val refreshed = client.refreshToken(cur.refreshToken)
        if (refreshed == null) {
            Timber.w("BTIDALPOOL token refresh failed; clearing cached credentials")
            signOut()
            return@withContext null
        }
        // The refresh endpoint returns the same email account; reuse the cached email rather
        // than re-querying Google's userinfo (saves a round-trip).
        val newState = AuthState(refreshed.token, refreshed.refreshToken, cur.email)
        persist(newState)
        newState
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
        val refreshed = client.refreshToken(initial.refreshToken)
            ?: throw IllegalStateException("Token validation failed and refresh was rejected. Sign in again.")
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
        // BTIDALPOOL's Google OAuth web client (mirrors oauth_helper.AuthClient.client_id).
        const val CLIENT_ID = "934838710114-hrn5hafisthr3eqh7gnr1jka5c5hmjli.apps.googleusercontent.com"
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
        const val REDIRECT_URI = "https://btidalpool.ddns.net:7653/oauth2callback"
        const val SCOPES =
            "https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"

        private const val KEY_TOKEN = "btidalpool_token"
        private const val KEY_REFRESH_TOKEN = "btidalpool_refresh_token"
        private const val KEY_EMAIL = "btidalpool_email"
    }
}
