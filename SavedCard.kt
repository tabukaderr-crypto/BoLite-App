package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_cards")
data class SavedCard(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val category: String,
    val savedAt: Long = System.currentTimeMillis()
)
