package com.darkmentor.domain.interactor

import android.content.Context
import android.net.Uri
import com.darkmentor.data.helpers.IntentHelper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class SelectBackupFileInteractor(
    private val intentHelper: IntentHelper,
    private val context: Context,
) {

    fun execute(): Flow<Uri?> {

        return callbackFlow {
            intentHelper.selectFile { uri ->
                trySend(uri)
            }
            awaitClose()
        }
    }
}