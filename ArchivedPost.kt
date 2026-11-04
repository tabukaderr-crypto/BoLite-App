package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Room Database entity representing an indexed Facebook group post stored permanently on device.
 * Enables offline searching, historical archive browsing, and instant loading without re-scanning.
 */
@Entity(
    tableName = "archived_posts",
    indices = [
        Index(value = ["groupName"]),
        Index(value = ["scanDate"]),
        Index(value = ["normalizedKey"], unique = true)
    ]
)
data class ArchivedPost(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val text: String, // Pure postContent, no metadata mixed in
    val fullText: String = text, // Pure postContent
    val author: String? = null, // Cleaned authorName
    val authorAvatarUrl: String? = null,
    val timestamp: String? = null, // Optional timestampRaw
    val groupName: String = "",
    val category: String = "عام", // categoryTag
    val cleanSnippet: String = text.replace(Regex("\\s+"), " ").trim().take(100),
    val isMediaRich: Boolean = false,
    val imageUrl: String? = null,
    val scanDate: Long = System.currentTimeMillis(),
    val normalizedKey: String = text.trim().replace("\\s+".toRegex(), " ").take(150),
    val ocrConfidence: Float = 0.95f,
    val isOcrCaptured: Boolean = false
) {
    // Explicit accessors matching requested schema:
    val content: String
        get() = text

    val authorName: String
        get() = author?.trim()?.takeIf { it.isNotBlank() } ?: "عضو في المجموعة"

    val postContent: String
        get() = fullText.ifBlank { text }.trim()

    val timestampRaw: String?
        get() = timestamp

    val categoryTag: String?
        get() = category

    /**
     * Converts to PostData used by Jetpack Compose UI cards and detail dialogs.
     */
    fun toPostData(): PostData = PostData(
        id = id,
        text = postContent,
        fullText = postContent,
        author = authorName,
        authorAvatarUrl = authorAvatarUrl,
        timestamp = timestampRaw,
        groupName = groupName,
        category = categoryTag ?: "عام",
        subtitle = com.example.util.PostInsights.analyze(postContent).summary(),
        cleanSnippet = if (cleanSnippet.isNotBlank()) cleanSnippet else postContent.replace(Regex("\\s+"), " ").trim().take(100),
        isMediaRich = isMediaRich || !imageUrl.isNullOrBlank(),
        isWithin7Days = true,
        ocrConfidence = ocrConfidence,
        isOcrCaptured = isOcrCaptured
    )

    fun toDeepScanResult(): com.example.accessibility.DeepScanPostResult = com.example.accessibility.DeepScanPostResult(
        id = id,
        text = postContent,
        fullText = postContent,
        author = authorName,
        timestamp = timestampRaw,
        groupName = groupName,
        category = categoryTag ?: "عام",
        isWithin7Days = true,
        fuzzyScore = 1.0
    )

    companion object {
        fun fromPostData(post: PostData, imageUrl: String? = null): ArchivedPost {
            val cleanContent = post.postContent.ifBlank { post.text }.trim()
            val cleanAuthor = post.authorName.ifBlank { post.author?.trim() ?: "عضو في المجموعة" }
            val normalized = cleanContent.replace("\\s+".toRegex(), " ").take(150)
            return ArchivedPost(
                id = post.id,
                text = cleanContent,
                fullText = cleanContent,
                author = cleanAuthor,
                authorAvatarUrl = post.authorAvatarUrl,
                timestamp = post.timestampRaw ?: post.timestamp,
                groupName = post.groupName,
                category = post.categoryTag ?: post.category,
                cleanSnippet = post.cleanSnippet.ifBlank { cleanContent.replace(Regex("\\s+"), " ").trim().take(100) },
                isMediaRich = post.isMediaRich || !imageUrl.isNullOrBlank(),
                imageUrl = imageUrl ?: post.authorAvatarUrl,
                scanDate = System.currentTimeMillis(),
                normalizedKey = normalized,
                ocrConfidence = post.ocrConfidence,
                isOcrCaptured = post.isOcrCaptured
            )
        }
    }
}
