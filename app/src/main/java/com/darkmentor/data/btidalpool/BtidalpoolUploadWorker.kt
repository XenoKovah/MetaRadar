package com.darkmentor.data.btidalpool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.darkmentor.R
import com.darkmentor.domain.interactor.UploadToBtidalpoolInteractor
import com.darkmentor.ui.MainActivity
import org.koin.java.KoinJavaComponent

class BtidalpoolUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val interactor: UploadToBtidalpoolInteractor by lazy {
        KoinJavaComponent.getKoin().get()
    }
    private val scheduler: BtidalpoolUploadScheduler by lazy {
        KoinJavaComponent.getKoin().get()
    }

    override suspend fun doWork(): Result {
        val mode = runCatching {
            UploadToBtidalpoolInteractor.Mode.valueOf(
                inputData.getString(INPUT_MODE) ?: UploadToBtidalpoolInteractor.Mode.ALL.name,
            )
        }.getOrDefault(UploadToBtidalpoolInteractor.Mode.ALL)
        val useTestDb = inputData.getBoolean(INPUT_USE_TEST_DB, false)
        val allowReupload = inputData.getBoolean(INPUT_ALLOW_REUPLOAD, false)
        val resumeOnly = inputData.getBoolean(INPUT_RESUME_ONLY, false)
        val expectedAccountKey = inputData.getString(INPUT_EXPECTED_ACCOUNT_KEY)

        setForeground(foregroundInfo(progress = null))
        var lastProgressAt = 0L
        var lastPercent = 0
        val execution = interactor.execute(
            mode = mode,
            useTestDb = useTestDb,
            allowReupload = allowReupload,
            resumeOnly = resumeOnly,
            expectedAccountKey = expectedAccountKey,
            onProgress = { processed, total ->
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= PROGRESS_THROTTLE_MS || processed >= total) {
                    lastProgressAt = now
                    lastPercent = if (total > 0) {
                        ((processed.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    setProgress(
                        workDataOf(
                            OUTPUT_PROGRESS_PERCENT to lastPercent,
                            OUTPUT_SERVER_BUSY_MESSAGE to "",
                        ),
                    )
                    setForeground(foregroundInfo(lastPercent))
                }
            },
            onBusyRetry = { busy ->
                val message = busy?.let {
                    applicationContext.getString(
                        R.string.btidalpool_server_busy_retrying,
                        (it.delayMillis + 999L) / 1_000L,
                    )
                }.orEmpty()
                setProgress(
                    workDataOf(
                        OUTPUT_PROGRESS_PERCENT to lastPercent,
                        OUTPUT_SERVER_BUSY_MESSAGE to message,
                    ),
                )
                setForeground(foregroundInfo(lastPercent, message.takeIf { it.isNotEmpty() }))
            },
        )

        return when (execution) {
            UploadToBtidalpoolInteractor.Execution.NotSignedIn -> Result.failure(
                output(
                    message = applicationContext.getString(R.string.btidalpool_not_signed_in),
                    terminal = true,
                ),
            )
            is UploadToBtidalpoolInteractor.Execution.AccountChanged -> Result.failure(
                output(execution.message, terminal = true),
            )
            is UploadToBtidalpoolInteractor.Execution.AuthRequired -> Result.failure(
                output(execution.message, terminal = true),
            )
            is UploadToBtidalpoolInteractor.Execution.Finished -> Result.success(
                output(summaryMessage(execution.summary), terminal = true),
            )
            is UploadToBtidalpoolInteractor.Execution.RetryRequired -> {
                scheduler.enqueueRetry(
                    mode = mode,
                    useTestDb = useTestDb,
                    allowReupload = allowReupload,
                    expectedAccountKey = execution.scope.accountKey,
                    delayMillis = execution.delayMillis,
                )
                Result.success(
                    output(
                        message = "Upload paused; retry scheduled. ${execution.reason}",
                        terminal = false,
                        retryScheduled = true,
                    ),
                )
            }
        }
    }

    private fun summaryMessage(summary: UploadToBtidalpoolInteractor.RunSummary): String {
        val delivered = summary.uploadedChunks + summary.alreadyPresentChunks
        val lines = mutableListOf<String>()
        if (delivered > 0) {
            lines += "$delivered chunk(s) confirmed by BTIDALPOOL " +
                "(${summary.uploadedDevices} device record(s))."
        }
        if (summary.preparedLogs > 0) lines += "${summary.preparedLogs} log(s) prepared."
        if (summary.skippedUploadedLogs > 0) {
            lines += "${summary.skippedUploadedLogs} log(s) already complete for this destination/account."
        }
        if (summary.emptyLogs > 0) lines += "${summary.emptyLogs} log(s) contained no uploadable devices."
        if (summary.preparationFailures.isNotEmpty()) {
            lines += "Preparation failures:\n" + summary.preparationFailures.joinToString("\n")
        }
        if (summary.permanentFailures.isNotEmpty()) {
            lines += "Permanent upload failures:\n" + summary.permanentFailures.joinToString("\n")
        }
        if (lines.isEmpty()) lines += "Nothing new to upload."
        return lines.joinToString("\n")
    }

    private fun output(
        message: String,
        terminal: Boolean,
        retryScheduled: Boolean = false,
    ) = workDataOf(
        // WorkManager Data is capped at 10 KiB. Preserve room for keys/metadata even if many
        // archives fail with long server messages.
        OUTPUT_MESSAGE to message.take(MAX_OUTPUT_MESSAGE_CHARS),
        OUTPUT_TERMINAL to terminal,
        OUTPUT_RETRY_SCHEDULED to retryScheduled,
        OUTPUT_COMPLETED_AT_MS to System.currentTimeMillis(),
    )

    private fun foregroundInfo(progress: Int?, serverBusyMessage: String? = null): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.btidalpool_upload_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_ble)
            .setContentTitle(applicationContext.getString(R.string.btidalpool_upload_notification_title))
            .setContentText(
                if (!serverBusyMessage.isNullOrBlank()) {
                    serverBusyMessage
                } else if (progress == null) {
                    applicationContext.getString(R.string.btidalpool_upload_notification_preparing)
                } else {
                    applicationContext.getString(R.string.btidalpool_upload_notification_progress, progress)
                },
            )
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_cancel, applicationContext.getString(R.string.cancel), cancelIntent)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val INPUT_MODE = "mode"
        const val INPUT_USE_TEST_DB = "use_test_db"
        const val INPUT_ALLOW_REUPLOAD = "allow_reupload"
        const val INPUT_RESUME_ONLY = "resume_only"
        const val INPUT_EXPECTED_ACCOUNT_KEY = "expected_account_key"

        const val OUTPUT_MESSAGE = "message"
        const val OUTPUT_TERMINAL = "terminal"
        const val OUTPUT_RETRY_SCHEDULED = "retry_scheduled"
        const val OUTPUT_PROGRESS_PERCENT = "progress_percent"
        const val OUTPUT_SERVER_BUSY_MESSAGE = "server_busy_message"
        const val OUTPUT_COMPLETED_AT_MS = "completed_at_ms"

        private const val NOTIFICATION_CHANNEL = "btidalpool_upload"
        private const val NOTIFICATION_ID = 43
        private const val PROGRESS_THROTTLE_MS = 250L
        private const val MAX_OUTPUT_MESSAGE_CHARS = 7_000
    }
}
