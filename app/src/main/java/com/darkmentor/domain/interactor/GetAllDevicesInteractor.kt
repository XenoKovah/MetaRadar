package com.darkmentor.domain.interactor

import com.darkmentor.data.repo.DevicesRepository
import com.darkmentor.domain.model.DeviceData

class GetAllDevicesInteractor(
    private val devicesRepository: DevicesRepository,
) {

    suspend fun execute(withAirdropInfo: Boolean = false): List<DeviceData> {
        return devicesRepository.getDevices(withAirdropInfo)
    }
}