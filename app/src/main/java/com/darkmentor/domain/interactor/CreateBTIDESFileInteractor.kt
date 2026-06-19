package com.darkmentor.domain.interactor

import android.content.Context
import android.net.Uri
import com.darkmentor.R
import com.darkmentor.data.helpers.IntentHelper
import com.darkmentor.dateTimeStringFormat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class CreateBTIDESFileInteractor(
    private val intentHelper: IntentHelper,
    private val context: Context,
) {

    fun execute(): Flow<Uri?> {
        val appName = context.getString(R.string.app_name)
        val time = System.currentTimeMillis().dateTimeStringFormat(TIME_FORMAT)
        val name = "${appName}_btides_${time}.btides"

        return callbackFlow {
            intentHelper.createFile(name) { uri -> trySend(uri) }
            awaitClose()
        }
    }

    companion object {
        private const val TIME_FORMAT = "yyyy-MM-dd_HH-mm-ss"
    }
}
