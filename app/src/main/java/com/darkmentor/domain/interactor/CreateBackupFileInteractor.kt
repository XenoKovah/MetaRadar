package com.darkmentor.domain.interactor

import android.content.Context
import android.net.Uri
import com.darkmentor.R
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.dateTimeStringFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class CreateBackupFileInteractor(
    private val intentHelper: IntentHelper,
    private val context: Context,
) {

    fun execute(): Flow<Uri?> {
        val appName = context.getString(R.string.app_name)
        val time = System.currentTimeMillis().dateTimeStringFormat(TIME_FORMAT)
        val name = "${appName}_${BACKUP_FILE_PREFIX}_(${time}).sqlite"

        return callbackFlow {
            intentHelper.createFile(name) { uri ->
                trySend(uri)
            }
            awaitClose()
        }
    }

    companion object {
        private const val BACKUP_FILE_PREFIX = "database_backup"
        private const val TIME_FORMAT = "dd MMM yyyy HH:mm"
    }
}