package com.darkmentor.data.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkmentor.TheApp
import com.darkmentor.data.database.AppDatabase
import com.darkmentor.service.BgScanService
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the delayed BLE batch-flush teardown window.
 *
 * Android keeps the ScanCallback registered until stopScan runs. Releasing the app-side
 * in-progress flag before that point allowed overlapping "scan now" requests to call startScan
 * with the same callback and produce SCAN_FAILED_ALREADY_STARTED (error 1).
 */
@RunWith(AndroidJUnit4::class)
class BleScanLifecycleInstrumentedTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun overlappingScanNowRequestsDoNotRecordAlreadyStarted() {
        assumeTrue(
            "BLE lifecycle test requires the app's runtime scan permissions",
            PermissionHelper.BLE_PERMISSIONS.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            },
        )
        val database = AppDatabase.build(context, TheApp.DATABASE_NAME)
        val wasActive = BgScanService.isActive
        val before = alreadyStartedCount(database)

        try {
            if (!wasActive) BgScanService.start(context)
            waitUntil(SERVICE_START_TIMEOUT_MS) { BgScanService.isActive }
            assertTrue("Background scanner did not start", BgScanService.isActive)

            // Cover a complete 5 s normal scan plus its delayed flush/stop interval. Requests
            // arriving while the callback is owned must be ignored, never passed to startScan.
            repeat(OVERLAPPING_REQUEST_COUNT) {
                BgScanService.scan(context)
                SystemClock.sleep(OVERLAPPING_REQUEST_INTERVAL_MS)
            }
            SystemClock.sleep(FINAL_CALLBACK_GRACE_MS)

            assertEquals(
                "Overlapping scan requests recorded Android BLE error 1",
                before,
                alreadyStartedCount(database),
            )
        } finally {
            if (!wasActive) {
                BgScanService.stop(context)
                SystemClock.sleep(SERVICE_STOP_GRACE_MS)
            }
            database.close()
        }
    }

    private fun alreadyStartedCount(database: AppDatabase): Int =
        database.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM journal
            WHERE reportJson LIKE '%Scan already started%' COLLATE NOCASE
            """.trimIndent(),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val SERVICE_START_TIMEOUT_MS = 5_000L
        private const val OVERLAPPING_REQUEST_COUNT = 70
        private const val OVERLAPPING_REQUEST_INTERVAL_MS = 100L
        private const val FINAL_CALLBACK_GRACE_MS = 1_000L
        private const val SERVICE_STOP_GRACE_MS = 500L
        private const val POLL_INTERVAL_MS = 50L
    }
}
