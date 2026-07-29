package com.darkmentor.data.repo

import com.darkmentor.data.database.AppDatabase
import com.darkmentor.domain.model.JournalEntry
import com.darkmentor.domain.toData
import com.darkmentor.domain.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class JournalRepository(database: AppDatabase) {

    private val journalDao = database.journalDao()
    private val journal = journalDao.observe()
        .map {
            withContext(Dispatchers.Default) {
                it.map { it.toDomain() }
            }
        }

    fun observe(): Flow<List<JournalEntry>> {
        return journal
    }

    suspend fun newEntry(journalEntry: JournalEntry) {
        withContext(Dispatchers.IO) {
            journalDao.insert(journalEntry.toData())
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            journalDao.deleteAll()
        }
    }
}
