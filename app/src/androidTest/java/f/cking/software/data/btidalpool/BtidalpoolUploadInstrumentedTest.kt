package f.cking.software.data.btidalpool

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import f.cking.software.TheApp
import junit.framework.AssertionFailedError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * On-device end-to-end smoke test for the BTIDALPOOL upload pipeline against the live server.
 *
 * Why on-device, not a unit test: the test exercises the *real* TLS pinning, the live
 * `https://btidalpool.ddns.net:3567/` endpoint, and the production BTIDES schema validator on
 * the server. The bundled `btidalpool_test_fixture.btides` (two minimal SingleBDADDR entries)
 * is small enough that the test always completes within a few seconds.
 *
 * Auth flow: this test reuses *cached* credentials written to the production app's
 * `btidalpool-auth.xml` by [BtidalpoolAuthRepository] — the user must have signed in via the
 * Settings screen at least once before running this. (Tokens used to live in `app-prefs.xml`;
 * the repository's init-block migration moves them to the dedicated, backup-excluded file the
 * first time the new build runs.) When no cached token is found, the test is skipped via
 * [assumeNotNull] rather than failing, so CI pipelines without bundled creds stay green.
 *
 * Test-DB only: every request sets `use_test_db = true` so we never write to the public
 * pool. The server's bttest database accepts the same schema and applies the same hash dedup,
 * which is exactly the contract we're verifying here.
 *
 * Expected outcomes (both pass):
 *   - First run after a server-side bttest reset: server returns 200 NotPresent on hash check
 *     → 200 Success on upload.
 *   - Subsequent runs: server returns 400 AlreadyPresent on hash check (or, if the hash check
 *     somehow misses, on the upload itself). The test treats both as a successful round-trip.
 *
 * Anything else (auth failure, network error, schema validation rejection) is a real failure.
 */
@RunWith(AndroidJUnit4::class)
class BtidalpoolUploadInstrumentedTest {

    /** Use the production app context so the prod-app SharedPreferences (and any cached
     *  Google OAuth tokens written by the Settings UI) are visible to the test. */
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    /** Test APK's own context — that's where our bundled `btidalpool_test_fixture.btides`
     *  lives, not on the app's asset path. */
    private val testContext: Context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun upload_test_fixture_to_test_db_succeeds_or_dedups() = runBlocking {
        // Reuse the auth state the user established via the Settings screen. If empty we skip
        // (no creds → no test). Don't try to fall back to a hard-coded fixture: tokens expire.
        // Mirror the production wiring: tokens live in `btidalpool-auth.xml` and the repo runs
        // a one-shot migration from the legacy `app-prefs.xml` on construction.
        val authPrefs = targetContext.getSharedPreferences(TheApp.BTIDALPOOL_AUTH_PREF_NAME, Context.MODE_PRIVATE)
        val legacyPrefs = targetContext.getSharedPreferences(TheApp.SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val authRepo = BtidalpoolAuthRepository(authPrefs, BtidalpoolClient(targetContext), legacyPrefs)
        val cached = authRepo.current()
        assumeNotNull("BTIDALPOOL test skipped — sign in via Settings first", cached)
        val auth = cached!!

        // Load the bundled two-entry fixture. The asset comes from the *test* APK's assets,
        // not the prod app's, so this path doesn't pollute the prod APK's resource table.
        val rawJson = testContext.assets.open(FIXTURE_ASSET).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        val parsed = Json.parseToJsonElement(rawJson)
        val canonical = PythonCanonicalJson.encode(parsed)
        val sha1 = sha1Hex(canonical.toByteArray(Charsets.UTF_8))

        val client = BtidalpoolClient(targetContext)

        // Step 1: hash check. AlreadyPresent (server has it from a prior run) is a valid pass
        // — the upload would just dedup anyway. NotPresent means we proceed to step 2.
        val hashResult = client.checkHash(sha1, auth.token, auth.refreshToken, useTestDb = true)
        when (hashResult) {
            is BtidalpoolClient.CheckHashResult.AlreadyPresent -> return@runBlocking
            is BtidalpoolClient.CheckHashResult.NotPresent -> Unit
            is BtidalpoolClient.CheckHashResult.AuthFailed ->
                throw AssertionFailedError(
                    "Cached BTIDALPOOL credentials rejected. Re-sign-in via Settings."
                )
            is BtidalpoolClient.CheckHashResult.Failed ->
                throw AssertionFailedError(
                    "check_hash failed HTTP ${hashResult.httpCode}: ${hashResult.body}"
                )
        }

        // Step 2: actually upload via the streaming path. Server recomputes SHA1 on its side
        // and confirms dedup if applicable. We materialise the fixture into a temp file
        // because [BtidalpoolClient.uploadFile] takes a File handle (and the prod path always
        // does — the BTIDES export goes to disk first), but the underlying transport never
        // holds the full body in memory.
        val tempFile = java.io.File.createTempFile(
            "btidalpool_test_", ".btides", targetContext.cacheDir,
        ).apply { writeText(rawJson, Charsets.UTF_8) }
        val uploadResult = try {
            client.uploadFile(tempFile, auth.token, auth.refreshToken, useTestDb = true)
        } finally {
            tempFile.delete()
        }
        when (uploadResult) {
            is BtidalpoolClient.UploadResult.Success,
            is BtidalpoolClient.UploadResult.AlreadyPresent,
            -> Unit // both treat as a successful round-trip
            is BtidalpoolClient.UploadResult.AuthFailed ->
                throw AssertionFailedError(
                    "Upload rejected for auth reasons after a successful hash check — token race?"
                )
            is BtidalpoolClient.UploadResult.Failed ->
                throw AssertionFailedError(
                    "upload failed HTTP ${uploadResult.httpCode}: ${uploadResult.body}"
                )
        }
    }

    /**
     * Verifies the fixture is in the canonical-JSON-stable shape we ship — guards against a
     * future merge accidentally adding/removing a field, which would change the SHA1 and
     * invalidate the dedup behaviour the upload test relies on.
     */
    @Test
    fun fixture_canonical_hash_is_stable() {
        val rawJson = testContext.assets.open(FIXTURE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = Json.parseToJsonElement(rawJson)
        val canonical = PythonCanonicalJson.encode(parsed)
        val sha1 = sha1Hex(canonical.toByteArray(Charsets.UTF_8))

        // SHA1 of the canonical form of btidalpool_test_fixture.btides as committed. Update
        // this string only when the fixture itself is intentionally changed.
        val expected = EXPECTED_FIXTURE_SHA1
        if (sha1 != expected) {
            // Build a useful error: print the actual canonical so it's easy to copy a new
            // expected hash (or eyeball the diff) without re-running the test locally.
            throw AssertionFailedError(
                "Fixture canonical SHA1 changed.\n" +
                    "  expected: $expected\n" +
                    "  actual:   $sha1\n" +
                    "  canonical:\n${canonical.take(400)}"
            )
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        private const val FIXTURE_ASSET = "btidalpool_test_fixture.btides"
        // SHA1 of `python3 -c 'json.dumps(content, sort_keys=True)'` over the fixture's
        // current bytes. Recompute and update this string whenever the fixture changes —
        // [fixture_canonical_hash_is_stable] checks for accidental drift.
        private const val EXPECTED_FIXTURE_SHA1 = "bd91fd53d2fffebc717cb140704fc3b0c335c7d8"
    }
}
