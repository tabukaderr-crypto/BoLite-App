package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedCardDao {
    @Query("SELECT * FROM saved_cards ORDER BY savedAt DESC")
    fun getAllCards(): Flow<List<SavedCard>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: SavedCard): Long

    @Query("DELETE FROM saved_cards WHERE id = :id")
    suspend fun deleteCardById(id: Long)

    @Query("SELECT COUNT(*) FROM saved_cards")
    fun getCardsCount(): Flow<Int>
}
