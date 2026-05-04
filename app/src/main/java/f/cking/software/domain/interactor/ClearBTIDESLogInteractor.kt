package f.cking.software.domain.interactor

import f.cking.software.data.btides.BTIDESRepository

class ClearBTIDESLogInteractor(
    private val btidesRepository: BTIDESRepository,
) {
    suspend fun execute() = btidesRepository.clearLog()
}
