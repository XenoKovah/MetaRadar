package com.darkmentor.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.darkmentor.data.database.entity.JournalEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(journalEntryEntity: JournalEntryEntity)

    @Query("SELECT * FROM journal")
    fun observe(): Flow<List<JournalEntryEntity>>

    @Query("DELETE FROM journal")
    fun deleteAll()
}
