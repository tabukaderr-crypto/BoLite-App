package com.example.data

import kotlinx.coroutines.flow.Flow

class SavedCardRepository(private val dao: SavedCardDao) {
    val allCards: Flow<List<SavedCard>> = dao.getAllCards()
    val cardsCount: Flow<Int> = dao.getCardsCount()

    suspend fun insertCard(text: String, category: String): Long {
        return dao.insertCard(
            SavedCard(
                text = text,
                category = category,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteCard(id: Long) {
        dao.deleteCardById(id)
    }
}
