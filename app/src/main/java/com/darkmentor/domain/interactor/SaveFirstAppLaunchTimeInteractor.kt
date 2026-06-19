package com.darkmentor.domain.interactor

import com.darkmentor.data.repo.SettingsRepository

class SaveFirstAppLaunchTimeInteractor(
    private val settingsRepository: SettingsRepository
) {

    fun execute() {
        if (settingsRepository.getFirstAppLaunchTime() == SettingsRepository.NO_APP_LAUNCH_TIME) {
            settingsRepository.setFirstAppLaunchTime(System.currentTimeMillis())
        }
    }
}
