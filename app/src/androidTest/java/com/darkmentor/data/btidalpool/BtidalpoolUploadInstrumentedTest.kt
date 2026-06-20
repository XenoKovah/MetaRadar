package com.darkmentor.data.btidalpool

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkmentor.TheApp
import junit.framework.AssertionFailedError
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end smoke test for the BTIDALPOOL upload pipeline against the live **Rust**
 * server.
 *
 * Why on-device, not a unit test: the test exercises the *real* TLS pinning, the live
 * `https://btidalpool.ddns.net:3568/` endpoint, the BTPL CBOR+zstd codec (including the zstd-jni
 * native library, which only loads on a real device/emulator), and the production BTIDES schema
 * validator on the server. The bundled `btidalpool_test_fixture.btides` (two minimal SingleBDADDR
 * entries) is small enough that the test always completes within a few seconds.
 *
 * Auth flow: this test reuses *cached* credentials written to the production app's
 * `btidalpool-auth.xml` by [BtidalpoolAuthRepository] — the user must have signed in via the
 * Settings screen at least once before running this. (Tokens used to live in `app-prefs.xml`; the
 * repository's init-block migration moves them to the dedicated, backup-excluded file the first
 * time the new build runs.) When no cached token is found, the test is skipped via [assumeNotNull]
 * rather than failing, so CI pipelines without bundled creds stay green.
 *
 * Test-DB only: every request sets `use_test_db = true` so we never write to the public pool. The
 * server's test database accepts the same schema and applies the same content dedup, which is
 * exactly the contract we're verifying here.
 *
 * Expected outcomes (both pass): the first upload after a server-side test-DB reset returns
 * Success; subsequent runs return AlreadyPresent — the server dedups the content and replies with a
 * `duplicate_upload` error the client maps to AlreadyPresent. Anything else (auth failure, network
 * error, schema validation rejection) is a real failure.
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
        // Mirror the production wiring: tokens live in `btidalpool-auth.xml` and the repo runs a
        // one-shot migration from the legacy `app-prefs.xml` on construction.
        val authPrefs = targetContext.getSharedPreferences(TheApp.BTIDALPOOL_AUTH_PREF_NAME, Context.MODE_PRIVATE)
        val legacyPrefs = targetContext.getSharedPreferences(TheApp.SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val authRepo = BtidalpoolAuthRepository(authPrefs, BtidalpoolClient(targetContext), legacyPrefs)
        val cached = authRepo.current()
        assumeNotNull("BTIDALPOOL test skipped — sign in via Settings first", cached)
        val auth = cached!!

        // Load the bundled two-entry fixture (from the *test* APK's assets, not the prod app's)
        // and materialise it to a temp file — [BtidalpoolClient.uploadFile] takes a File handle,
        // mirroring the prod path where the BTIDES export always lands on disk first. No
        // client-side hashing: the Rust server dedups the uploaded content on its own end.
        val rawJson = testContext.assets.open(FIXTURE_ASSET).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        val tempFile = java.io.File.createTempFile(
            "btidalpool_test_", ".btides", targetContext.cacheDir,
        ).apply { writeText(rawJson, Charsets.UTF_8) }

        val client = BtidalpoolClient(targetContext)
        val uploadResult = try {
            client.uploadFile(tempFile, auth.token, auth.refreshToken, useTestDb = true)
        } finally {
            tempFile.delete()
        }
        when (uploadResult) {
            is BtidalpoolClient.UploadResult.Success,
            is BtidalpoolClient.UploadResult.AlreadyPresent,
            -> Unit // both are a successful round-trip — the server dedups re-uploads
            is BtidalpoolClient.UploadResult.AuthFailed ->
                throw AssertionFailedError(
                    "Cached BTIDALPOOL credentials rejected. Re-sign-in via Settings."
                )
            is BtidalpoolClient.UploadResult.Failed ->
                throw AssertionFailedError(
                    "upload failed HTTP ${uploadResult.httpCode}: ${uploadResult.body}"
                )
        }
    }

    companion object {
        private const val FIXTURE_ASSET = "btidalpool_test_fixture.btides"
    }
}
