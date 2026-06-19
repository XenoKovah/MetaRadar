package com.darkmentor.domain.interactor

import com.darkmentor.TheApp
import com.darkmentor.data.database.AppDatabase
import com.darkmentor.domain.model.DatabaseInformation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class GetDatabaseInfoInteractor(
    private val database: AppDatabase,
    private val application: TheApp,
) {
    fun execute(): Flow<DatabaseInformation> {
        return combine(
            database.deviceDao().observeAll().map { it.size },
            database.locationDao().observeAllLocations().map { it.size }
        ) { deviceCount, locationCount ->
            DatabaseInformation(
                sizeBytes = database.getDatabaseSize(application),
                totalDevices = deviceCount,
                totalGeotags = locationCount
            )
        }
    }
}