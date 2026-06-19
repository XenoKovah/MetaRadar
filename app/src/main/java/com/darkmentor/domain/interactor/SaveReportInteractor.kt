package com.darkmentor.domain.interactor

import com.darkmentor.data.repo.JournalRepository
import com.darkmentor.domain.model.JournalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SaveReportInteractor(
    private val journalRepository: JournalRepository,
) {

    suspend fun execute(report: JournalEntry.Report) {
        withContext(Dispatchers.Default) {
            val journalEntry = JournalEntry(
                id = null,
                timestamp = System.currentTimeMillis(),
                report = report,
            )

            journalRepository.newEntry(journalEntry)
        }
    }
}