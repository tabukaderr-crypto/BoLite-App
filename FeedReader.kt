package com.example.reader

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.util.PostSanitizer
import java.util.UUID

/**
 * Data structure representing an extracted post from the structural feed hierarchy.
 */
data class FeedPost(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val author: String = "Unknown",
    val timestamp: String = ""
)

/**
 * FeedReader: Core structural reading engine for Bo Lite v15.
 * Traverses accessibility node trees with precision post isolation,
 * author detection, noise exclusion, and fallback deep collection.
 */
object FeedReader {

    private val TIMESTAMP_REGEX = Regex(
        """(\d{1,2}:\d{2}|\d+\s*(m|h|d|min|hrs?|days?|ساعة|ساعات|دقيقة|دقائق|يوم|أيام|ثانية|منذ))""",
        RegexOption.IGNORE_CASE
    )

    private val HANDLE_REGEX = Regex("""@[\w\.]+""")

    /**
     * Finds the feed container in the tree.
     */
    fun findFeedContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val rect = Rect()

        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8) return
            try {
                if (node.isScrollable) {
                    node.getBoundsInScreen(rect)
                    val area = rect.width().toLong() * rect.height().toLong()
                    if (area > bestArea) {
                        bestArea = area
                        best = node
                    }
                }
                val count = node.childCount
                for (i in 0 until count) {
                    search(node.getChild(i), depth + 1)
                }
            } catch (ignored: Exception) {}
        }

        search(root, 0)
        return best ?: root
    }

    /**
     * Reads posts from the given accessibility node hierarchy.
     */
    fun readPosts(rootNode: AccessibilityNodeInfo?): List<FeedPost> {
        if (rootNode == null) return emptyList()

        val container = findFeedContainer(rootNode) ?: rootNode
        val posts = mutableListOf<FeedPost>()
        val seenTexts = HashSet<String>()

        val childCount = container.childCount
        if (childCount > 0) {
            for (i in 0 until childCount) {
                val postItemNode = try { container.getChild(i) } catch (e: Exception) { null } ?: continue
                val post = extractSinglePost(postItemNode)
                if (post != null && post.text.length >= 10 && seenTexts.add(post.text.take(100))) {
                    posts.add(post)
                }
            }
        }

        // Fallback: If 0 posts were extracted from direct children, do a deeper traversal for text blocks
        if (posts.isEmpty()) {
            val textBlocks = mutableListOf<String>()
            collectTextNodes(container, textBlocks, 0)
            for (raw in textBlocks) {
                val clean = raw.trim()
                if (PostSanitizer.isValidPostContent(clean) && clean.length >= 15 && seenTexts.add(clean.take(100))) {
                    posts.add(FeedPost(text = clean))
                }
            }
        }

        return posts
    }

    private fun extractSinglePost(containerNode: AccessibilityNodeInfo): FeedPost? {
        var author = "Unknown"
        var timestamp = ""
        val contentCandidates = mutableListOf<String>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 6) return
            try {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val candidate = when {
                    !text.isNullOrBlank() -> text
                    !desc.isNullOrBlank() -> desc
                    else -> null
                }

                if (!candidate.isNullOrBlank()) {
                    if (TIMESTAMP_REGEX.containsMatchIn(candidate) && timestamp.isEmpty()) {
                        timestamp = candidate
                    } else if (author == "Unknown" && isValidAuthorName(candidate)) {
                        author = candidate
                    } else if (candidate.length >= 15 && PostSanitizer.isValidPostContent(candidate)) {
                        contentCandidates.add(candidate)
                    }
                }

                val count = node.childCount
                for (i in 0 until count) {
                    traverse(node.getChild(i), depth + 1)
                }
            } catch (ignored: Exception) {}
        }

        traverse(containerNode, 0)

        val bestContent = contentCandidates.maxByOrNull { it.length } ?: return null
        return FeedPost(
            text = bestContent,
            author = author,
            timestamp = timestamp
        )
    }

    private fun isValidAuthorName(text: String): Boolean {
        if (text.length < 3 || text.length > 50) return false
        if (TIMESTAMP_REGEX.containsMatchIn(text)) return false
        if (HANDLE_REGEX.containsMatchIn(text)) return false
        val noiseWords = listOf("Like", "Comment", "Share", "إعجاب", "تعليق", "مشاركة", "Group", "مجموعة", "Facebook", "Sponsored")
        return noiseWords.none { text.contains(it, ignoreCase = true) }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 8) return
        try {
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrBlank() && t.length >= 10) {
                out.add(t)
            }
            val count = node.childCount
            for (i in 0 until count) {
                collectTextNodes(node.getChild(i), out, depth + 1)
            }
        } catch (ignored: Exception) {}
    }
}
