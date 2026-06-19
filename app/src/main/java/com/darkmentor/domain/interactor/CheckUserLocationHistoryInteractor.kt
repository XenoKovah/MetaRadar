package com.darkmentor.domain.interactor

import com.darkmentor.data.helpers.LocationProvider
import com.darkmentor.domain.model.LocationModel
import com.darkmentor.domain.toDomain

class CheckUserLocationHistoryInteractor(
    private val locationProvider: LocationProvider,
) {

    /**
     * @param targetLocation location to check
     * @param radius radius in meters
     * @param ifNoLocationDefaultValue default value if no location found
     *
     * @return true if device was detected in allowed radius from current location
     */
    suspend fun execute(
        targetLocation: LocationModel,
        radius: Float,
        ifNoLocationDefaultValue: Boolean,
    ): Boolean {

        val location = locationProvider.getFreshLocation()?.toDomain(System.currentTimeMillis()) ?: return ifNoLocationDefaultValue

        return targetLocation.distanceTo(location) <= radius
    }
}