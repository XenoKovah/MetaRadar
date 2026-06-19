package com.darkmentor.domain.interactor

import android.net.Uri
import com.jakewharton.processphoenix.ProcessPhoenix
import com.darkmentor.TheApp
import com.darkmentor.data.database.AppDatabase
import com.darkmentor.service.BgScanService

class RestoreDatabaseInteractor(
    private val appDatabase: AppDatabase,
    private val application: TheApp,
) {

    suspend fun execute(uri: Uri) {
        BgScanService.stop(application)
        appDatabase.restoreDatabase(uri, application)
        application.restartKoin()
        ProcessPhoenix.triggerRebirth(application)
    }
}