package f.cking.software.domain.interactor

import f.cking.software.data.btides.BTIDESRepository

class ClearBTIDESLogInteractor(
    private val btidesRepository: BTIDESRepository,
) {
    enum class Mode { CURRENT, ALL }

    suspend fun execute(mode: Mode = Mode.CURRENT) = when (mode) {
        Mode.CURRENT -> btidesRepository.clearActive()
        Mode.ALL -> btidesRepository.clearAll()
    }
}
