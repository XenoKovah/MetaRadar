package com.darkmentor.domain.interactor

import com.darkmentor.TheAppConfig
import com.darkmentor.domain.model.DeviceData

class IsKnownDeviceInteractor {

    fun execute(device: DeviceData): Boolean {
        return device.lastDetectTimeMs - device.firstDetectTimeMs > TheAppConfig.DEFAULT_KNOWN_DEVICE_PERIOD_MS
    }
}