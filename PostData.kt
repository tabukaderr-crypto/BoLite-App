package com.example.data

import java.util.UUID

data class PostComment(
    val id: String = UUID.randomUUID().toString(),
    val author: String,
    val text: String,
    val timestamp: String? = null
)

data class PostData(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fullText: String = text,
    val author: String? = null,
    val timestamp: String? = null,
    val groupName: String = "",
    val category: String = "عام",
    val subtitle: String = "",
    val cleanSnippet: String = text.replace(Regex("\\s+"), " ").trim().take(100),
    val isMediaRich: Boolean = false,
    val authorAvatarUrl: String? = null,
    val matchedRanges: List<IntRange> = emptyList(),
    val fuzzyScore: Double = 1.0,
    val comments: List<PostComment> = emptyList(),
    val isWithin7Days: Boolean = true,
    val ocrConfidence: Float = 0.95f,
    val isOcrCaptured: Boolean = true
) {
    val authorName: String
        get() = author?.trim()?.takeIf { it.isNotBlank() } ?: "عضو في المجموعة"

    val postContent: String
        get() = fullText.ifBlank { text }.trim()

    val timestampRaw: String?
        get() = timestamp

    val categoryTag: String?
        get() = category
}
