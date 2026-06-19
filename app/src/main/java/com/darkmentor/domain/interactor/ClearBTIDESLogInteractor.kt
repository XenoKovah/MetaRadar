package com.darkmentor.domain.interactor

import com.darkmentor.data.btides.BTIDESRepository

class ClearBTIDESLogInteractor(
    private val btidesRepository: BTIDESRepository,
) {
    enum class Mode { CURRENT, ALL }

    suspend fun execute(mode: Mode = Mode.CURRENT) = when (mode) {
        Mode.CURRENT -> btidesRepository.clearActive()
        Mode.ALL -> btidesRepository.clearAll()
    }
}
