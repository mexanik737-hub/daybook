package ru.daybook.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DaybookDao {
    @Query("SELECT * FROM entries ORDER BY updated DESC")
    fun observeAll(): Flow<List<DaybookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DaybookEntity)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM entries")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DaybookEntity>)

    @Transaction
    suspend fun replaceAll(entries: List<DaybookEntity>) {
        clear()
        upsertAll(entries)
    }
}
