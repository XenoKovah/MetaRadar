package com.darkmentor.domain.interactor

import com.darkmentor.data.repo.DevicesRepository

/**
 * Wipe every device row (and the related Apple-contact rows). Triggered from the explicit
 * "Clear" actions in the Devices tab and the Settings → Database actions block. Returns nothing
 * — callers re-render via the DevicesRepository's batch observer once the delete completes.
 */
class ClearAllDevicesInteractor(
    private val devicesRepository: DevicesRepository,
) {

    suspend fun execute() {
        devicesRepository.deleteAllDevices()
    }
}
