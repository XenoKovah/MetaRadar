package com.darkmentor.data.btidalpool

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkmentor.TheApp
import com.darkmentor.data.database.entity.BtidalpoolUploadEntity
import junit.framework.AssertionFailedError
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * On-device end-to-end smoke test for the BTIDALPOOL upload pipeline against the live **Rust**
 * server.
 *
 * Why on-device, not a unit test: the test exercises the *real* TLS pinning, the live
 * `https://btidalpool.ddns.net:3568/v4` endpoint, the BTPL CBOR+zstd codec (including the zstd-jni
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

        val rawJson = testContext.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        val client = BtidalpoolClient(targetContext)
        val session = client.createV4Session(auth.token)
        val sessionToken = (session as? BtidalpoolClient.V4Result.Session)?.token
            ?: throw AssertionFailedError("Could not create v4 upload session: $session")
        val uploadResult = client.v4Upload(sessionToken, rawJson, useTestDb = true)
        when (uploadResult) {
            is BtidalpoolClient.V4Result.Ok -> Unit
            is BtidalpoolClient.V4Result.Error ->
                if (uploadResult.kind != "duplicate_upload") {
                    throw AssertionFailedError(
                        "v4 upload failed HTTP ${uploadResult.httpCode}: ${uploadResult.message}",
                    )
                }
            else -> throw AssertionFailedError("Unexpected v4 upload result: $uploadResult")
        }
    }

    @Test
    fun resumable_v4_test_fixture_persists_receipt_without_credentials() = runBlocking {
        val client = BtidalpoolClient(targetContext)
        val authPrefs = targetContext.getSharedPreferences(
            TheApp.BTIDALPOOL_AUTH_PREF_NAME,
            Context.MODE_PRIVATE,
        )
        val legacyPrefs = targetContext.getSharedPreferences(
            TheApp.SHARED_PREF_NAME,
            Context.MODE_PRIVATE,
        )
        val authRepo = BtidalpoolAuthRepository(authPrefs, client, legacyPrefs)
        assumeNotNull(
            "BTIDALPOOL v4 test skipped — sign in via Settings first",
            authRepo.current(),
        )

        val rawJson = testContext.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        val id = "instrumented-${UUID.randomUUID()}"
        val payload = java.io.File(targetContext.cacheDir, "$id.btides")
            .apply { writeBytes(rawJson) }
        val stateDirectory = java.io.File(targetContext.cacheDir, "$id-state")
        val stateStore = BtidalpoolResumableStateStore(stateDirectory, testOnly = true)
        val digest = MessageDigest.getInstance("SHA-256").digest(rawJson)
            .joinToString("") { "%02x".format(it) }
        val row = BtidalpoolUploadEntity(
            id = id,
            batchId = id,
            sourceLogName = payload.name,
            sourceSha256 = digest,
            chunkIndex = 0,
            chunkCount = 1,
            chunkSha256 = digest,
            destination = BtidalpoolUploadEntity.Destination.TEST,
            accountKey = "instrumented",
            payloadPath = payload.absolutePath,
            payloadBytes = payload.length(),
            deviceCount = 2,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
        )
        try {
            val result = BtidalpoolResumableUploader(client, authRepo, stateStore).upload(
                row = row,
                payload = payload,
                useTestDb = true,
                onProgress = { _, _ -> },
                onBusyRetry = {},
            )
            when (result) {
                BtidalpoolClient.UploadResult.Success,
                BtidalpoolClient.UploadResult.AlreadyPresent,
                -> Unit
                else -> throw AssertionFailedError("v4 upload failed: $result")
            }
            val persisted = stateStore.load(id)
                ?: throw AssertionFailedError("v4 resume state was not persisted")
            if (persisted.receipt == null) {
                throw AssertionFailedError("v4 finalized without persisting its receipt")
            }
            val stateText = java.io.File(stateDirectory, "$id.json").readText()
            val auth = authRepo.current()!!
            if (stateText.contains(auth.token) || stateText.contains(auth.refreshToken)) {
                throw AssertionFailedError("v4 resume state persisted an authentication secret")
            }
        } finally {
            payload.delete()
            stateDirectory.deleteRecursively()
        }
    }

    @Test
    fun v4_native_query_read_only_smoke() = runBlocking {
        val client = BtidalpoolClient(targetContext)
        val authRepo = BtidalpoolAuthRepository(
            targetContext.getSharedPreferences(
                TheApp.BTIDALPOOL_AUTH_PREF_NAME,
                Context.MODE_PRIVATE,
            ),
            client,
            targetContext.getSharedPreferences(TheApp.SHARED_PREF_NAME, Context.MODE_PRIVATE),
        )
        val auth = authRepo.current()
        assumeNotNull("BTIDALPOOL v4 native query skipped — sign in via Settings first", auth)
        val session = client.createV4Session(auth!!.token)
        val sessionToken = (session as? BtidalpoolClient.V4Result.Session)?.token
            ?: throw AssertionFailedError("Could not create v4 query session: $session")
        val result = client.v4NativeQuery(
            sessionToken,
            BtidalpoolCodec.QueryParams(bdaddrRegex = listOf("^00:")),
            useTestDb = false,
        )
        val query = result as? BtidalpoolClient.V4Result.NativeQuery
            ?: throw AssertionFailedError("v4 native read-only query failed: $result")
        if (query.query.devices.isEmpty() || query.query.totalRows <= 0) {
            throw AssertionFailedError("v4 native query returned no production rows")
        }
    }

    /**
     * Opt-in destructive-volume (but data-preserving) stress test. The harness creates 48 MiB of
     * synthetic, schema-valid BTIDES files under the app's own files directory, uploads all six
     * through resumable v4, verifies each persisted receipt and a native v4 query, then removes
     * only its UUID-namespaced synthetic directory. Run only after an external full app-data
     * backup with `-e runV4Stress true`.
     */
    @Test
    fun v4_large_resumable_upload_stress_is_receipted_and_queryable() = runBlocking {
        assumeTrue(
            "Large v4 stress test is opt-in and requires a pre-test phone backup",
            InstrumentationRegistry.getArguments().getString(STRESS_ARGUMENT) == "true",
        )
        val client = BtidalpoolClient(targetContext)
        val authRepo = BtidalpoolAuthRepository(
            targetContext.getSharedPreferences(
                TheApp.BTIDALPOOL_AUTH_PREF_NAME,
                Context.MODE_PRIVATE,
            ),
            client,
            targetContext.getSharedPreferences(TheApp.SHARED_PREF_NAME, Context.MODE_PRIVATE),
        )
        val auth = authRepo.current()
        assumeNotNull("BTIDALPOOL v4 stress skipped — sign in via Settings first", auth)

        val runId = UUID.randomUUID().toString().replace("-", "")
        val stressDirectory = File(targetContext.filesDir, "v4_stress/$runId")
        val stateDirectory = File(targetContext.cacheDir, "v4_stress_state_$runId")
        check(stressDirectory.mkdirs()) { "Could not create v4 stress directory" }
        val stateStore = BtidalpoolResumableStateStore(stateDirectory, testOnly = true)
        val uploader = BtidalpoolResumableUploader(client, authRepo, stateStore)
        val fixture = testContext.assets.open(FIXTURE_ASSET).use { it.readBytes() }
        var firstCanonicalSha1: String? = null
        var uploadedBytes = 0L
        try {
            repeat(STRESS_FILE_COUNT) { index ->
                val prefix = "DA:7A:${runId.substring(0, 2)}:${runId.substring(2, 4)}:" +
                    "%02X".format(index)
                val addressOne = "$prefix:01"
                val addressTwo = "$prefix:02"
                val uniqueFixture = fixture.toString(Charsets.UTF_8)
                    .replace("CA:FE:13:37:00:01", addressOne)
                    .replace("CA:FE:13:37:00:02", addressTwo)
                    .toByteArray(Charsets.UTF_8)
                val payload = File(stressDirectory, "v4-stress-$index.btides")
                writeInflatedJson(payload, uniqueFixture, STRESS_FILE_BYTES, index + 1)
                if (payload.length() != STRESS_FILE_BYTES) {
                    throw AssertionFailedError(
                        "Synthetic file ${payload.name} was ${payload.length()} bytes",
                    )
                }
                uploadedBytes += payload.length()
                val digest = sha256(payload)
                val id = "v4-stress-$runId-$index"
                val result = uploader.upload(
                    row = BtidalpoolUploadEntity(
                        id = id,
                        batchId = "v4-stress-$runId",
                        sourceLogName = payload.name,
                        sourceSha256 = digest,
                        chunkIndex = index,
                        chunkCount = STRESS_FILE_COUNT,
                        chunkSha256 = digest,
                        destination = BtidalpoolUploadEntity.Destination.TEST,
                        accountKey = "v4-stress",
                        payloadPath = payload.absolutePath,
                        payloadBytes = payload.length(),
                        deviceCount = 2,
                        createdAtMs = System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                    payload = payload,
                    useTestDb = true,
                    onProgress = { _, _ -> },
                    onBusyRetry = {},
                )
                if (result !is BtidalpoolClient.UploadResult.Success &&
                    result !is BtidalpoolClient.UploadResult.AlreadyPresent
                ) {
                    throw AssertionFailedError("v4 stress file $index failed: $result")
                }
                val receipt = stateStore.load(id)?.receipt
                    ?: throw AssertionFailedError("v4 stress file $index has no durable receipt")
                if (receipt.totalSize != STRESS_FILE_BYTES ||
                    !receipt.contentSha256.equals(digest, ignoreCase = true)
                ) {
                    throw AssertionFailedError("v4 stress file $index receipt does not match")
                }
                if (firstCanonicalSha1 == null) firstCanonicalSha1 = receipt.canonicalSha1
            }
            if (uploadedBytes != STRESS_TOTAL_BYTES) {
                throw AssertionFailedError("Expected $STRESS_TOTAL_BYTES bytes, uploaded $uploadedBytes")
            }

            val session = client.createV4Session(auth!!.token)
            val sessionToken = (session as? BtidalpoolClient.V4Result.Session)?.token
                ?: throw AssertionFailedError("Could not create v4 verification session: $session")
            val check = client.v4CheckHash(
                sessionToken,
                checkNotNull(firstCanonicalSha1),
            )
            if (check !is BtidalpoolClient.V4Result.Error ||
                check.kind != "duplicate_upload"
            ) {
                throw AssertionFailedError("v4 hash verification did not find upload: $check")
            }
        } finally {
            // Delete only this run's UUID-scoped synthetic files and temporary resume state.
            stressDirectory.deleteRecursively()
            stressDirectory.parentFile?.delete()
            stateDirectory.deleteRecursively()
        }
    }

    private fun writeInflatedJson(target: File, json: ByteArray, targetBytes: Long, seed: Int) {
        require(json.size < targetBytes)
        FileOutputStream(target, false).buffered().use { output ->
            output.write(json)
            var remaining = targetBytes - json.size
            var state = seed
            val whitespace = byteArrayOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte())
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                repeat(count) { offset ->
                    state = state xor (state shl 13)
                    state = state xor (state ushr 17)
                    state = state xor (state shl 5)
                    buffer[offset] = whitespace[state and 3]
                }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val FIXTURE_ASSET = "btidalpool_test_fixture.btides"
        private const val STRESS_ARGUMENT = "runV4Stress"
        private const val STRESS_FILE_COUNT = 6
        private const val STRESS_FILE_BYTES = 8L * 1024 * 1024
        private const val STRESS_TOTAL_BYTES = STRESS_FILE_COUNT * STRESS_FILE_BYTES
    }
}
