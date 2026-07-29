package com.darkmentor.data.btidalpool

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.concurrent.futures.await
import com.darkmentor.domain.interactor.UploadToBtidalpoolInteractor
import java.util.concurrent.TimeUnit

class BtidalpoolUploadScheduler(
    context: Context,
    private val outboxRepository: BtidalpoolOutboxRepository,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(
        mode: UploadToBtidalpoolInteractor.Mode,
        useTestDb: Boolean,
        allowReupload: Boolean,
    ): OneTimeWorkRequest {
        val request = request(
            mode = mode,
            useTestDb = useTestDb,
            allowReupload = allowReupload,
            resumeOnly = false,
            expectedAccountKey = null,
            delayMillis = 0,
        )
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request
    }

    fun enqueueRetry(
        mode: UploadToBtidalpoolInteractor.Mode,
        useTestDb: Boolean,
        allowReupload: Boolean,
        expectedAccountKey: String,
        delayMillis: Long,
    ): OneTimeWorkRequest {
        val request = request(
            mode = mode,
            useTestDb = useTestDb,
            allowReupload = allowReupload,
            resumeOnly = true,
            expectedAccountKey = expectedAccountKey,
            delayMillis = delayMillis,
        )
        // The running worker returns success after appending this delayed continuation, so a
        // failed parent can never poison the rest of the unique chain.
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND, request)
        return request
    }

    fun workInfos(): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_NAME)

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    suspend fun cancelAndClearOutbox() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.await()
        outboxRepository.clearAll()
    }

    private fun request(
        mode: UploadToBtidalpoolInteractor.Mode,
        useTestDb: Boolean,
        allowReupload: Boolean,
        resumeOnly: Boolean,
        expectedAccountKey: String?,
        delayMillis: Long,
    ): OneTimeWorkRequest {
        val input = Data.Builder()
            .putString(BtidalpoolUploadWorker.INPUT_MODE, mode.name)
            .putBoolean(BtidalpoolUploadWorker.INPUT_USE_TEST_DB, useTestDb)
            .putBoolean(BtidalpoolUploadWorker.INPUT_ALLOW_REUPLOAD, allowReupload)
            .putBoolean(BtidalpoolUploadWorker.INPUT_RESUME_ONLY, resumeOnly)
            .apply {
                if (expectedAccountKey != null) {
                    putString(BtidalpoolUploadWorker.INPUT_EXPECTED_ACCOUNT_KEY, expectedAccountKey)
                }
            }
            .build()
        return OneTimeWorkRequestBuilder<BtidalpoolUploadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG)
            .build()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "btidalpool-upload"
        const val WORK_TAG = "btidalpool-upload-worker"
    }
}
