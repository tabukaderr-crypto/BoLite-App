package com.example.util

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

/**
 * Data structure representing an extracted post item cleanly separated into metadata and content.
 */
data class ParsedPostItem(
    val id: String = UUID.randomUUID().toString(),
    val author: String? = null,
    val timestamp: String? = null,
    val contentBody: String,
    val fullText: String = contentBody,
    val cleanSnippet: String = contentBody.take(100),
    val hasImage: Boolean = false,
    val authorAvatarUrl: String? = null,
    val category: String = "عام",
    val confidence: Float = 1.0f,
    val bounds: Rect = Rect()
) {
    val authorName: String
        get() = author?.trim()?.takeIf { it.isNotBlank() } ?: "عضو في المجموعة"

    val postContent: String
        get() = contentBody.trim()

    val timestampRaw: String?
        get() = timestamp

    val categoryTag: String?
        get() = category
}

/**
 * High-performance, noise-immune PostParser for Bo Lite v10.
 *
 * 1. Strict Node Type Validation:
 *    - Only accepts android.widget.TextView nodes for text.
 *    - Rejects Buttons, ImageViews, status bar chrome, and hidden labels.
 * 2. Strict Exclusion Rules:
 *    - Time formats (\d{1,2}:\d{2}, P11:49, 11:49 AM, 11:49 ص).
 *    - Relative timestamps ("Just now", "2h ago", "منذ ساعة", "أمس").
 *    - Interaction counts ("Like", "Comment", "Share", numbers alone < 3 chars).
 *    - Garbage strings (999gâS, battery %, LTE, 5G, Wi-Fi, random symbols).
 *    - Minimum 70% linguistic letter ratio threshold (discard low confidence text).
 * 3. Clean Author vs Content Separation:
 *    - Author: FIRST meaningful TextView near profile avatar/top.
 *    - Timestamp: Isolated and DISCARDED from content body.
 *    - Content: SUBSEQUENT larger TextViews, cleanly concatenated without author mixing.
 *    - Discards low quality text (<70% confidence) silently.
 */
object PostParser {

    // Action buttons & interaction keywords to strictly reject from content
    private val actionKeywords = setOf(
        "أعجبني", "إعجاب", "لايك", "like", "likes", "أعجبك",
        "تعليق", "تعليقات", "اكتب تعليقاً", "اكتب تعليق", "write a comment", "comment", "comments",
        "مشاركة", "مشاركات", "share", "shares", "إرسال", "send", "إرسال رسالة",
        "رد", "ردود", "reply", "replies", "نسخ الرابط", "copy link",
        "تفاعل", "تفاعلات", "reactions", "مشاهدة", "views",
        "عرض المزيد", "see more", "المزيد", "more", "عرض أقل", "see less"
    )

    private val navigationChrome = setOf(
        "بحث", "search", "الرئيسية", "home", "إشعارات", "notifications", "القائمة", "menu",
        "watch", "فيديو", "reels", "ريلز", "marketplace", "ماركت بليس", "gaming", "ألعاب",
        "انضمام", "join", "انضم للمجموعة", "join group", "joined", "تم الانضمام",
        "دعوة", "invite", "مغادرة", "leave", "تعديل", "edit", "حذف", "delete", "إبلاغ", "report",
        "رجوع", "back", "إغلاق", "close", "أعضاء", "members", "حول", "about",
        "الفعاليات", "events", "ملفات", "files", "وسائط", "media", "ألبومات", "albums"
    )

    private val adPatterns = listOf(
        "ممول", "مُموَّل", "إعلان", "اعلان", "sponsored", "advertisement",
        "suggested for you", "مقترح لك", "قد يعجبك", "popular across facebook"
    )

    // Regex for time formats like 11:49, P11:49, p11:49, 11:49 AM, 09:30, \d{1,2}:\d{2}
    private val timeFormatRegex = Regex(
        """(?:\b|[PApa])?\d{1,2}:\d{2}(?:\s*(?:AM|PM|am|pm|ص|م))?(?:\b|$)"""
    )
    private val timeSimpleRegex = Regex("""\d{1,2}:\d{2}""")

    // Regex for username handles like @username
    private val usernameHandleRegex = Regex("""@\w+""")

    // Relative post timestamp words and relative dates
    private val relativeTimestampRegex = Regex(
        """(?:\bمنذ\b|\bساعة\b|\bساعات\b|\bدقيقة\b|\bدقائق\b|\bأمس\b|\byesterday\b|\bjust now\b|\bالآن\b|\b\d+\s*[hmdwHMDW]\b|\b\d+\s*(?:hrs?|mins?|days?|hours?|weeks?)\b|\bيوم\b|\bيومان\b|\bأيام\b|\bاسبوع\b|\bأسبوع\b)""",
        RegexOption.IGNORE_CASE
    )

    // Standalone metadata / audience indicators
    private val metaAudienceWords = setOf(
        "public", "عامة", "الأصدقاء", "friends", "group", "مجموعة", "مجموعة عامة", "مجموعة خاصة"
    )

    // Garbage patterns (e.g. 999gâS, P11:49, battery, signal, WiFi, percentage)
    private val garbagePatternRegex = Regex(
        """^.*(?:\d+[gâsG]+|LTE|4G|5G|WiFi|battery|\b\d{1,3}%\b|[PApa]\d{1,2}:\d{2}).*$""",
        RegexOption.IGNORE_CASE
    )

    // Reaction counts (e.g. 120 تفاعلاً, 45 comments, 3 shares, digits alone)
    private val reactionCountRegex = Regex(
        """^(?:[\d\u0660-\u0669KkMm\s]+(?:تفاعل|تعليق|مشاركة|like|comment|share)s?|أنت و.+|[\d\u0660-\u0669]+\s*)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks if a string is garbage, hidden UI label, status bar indicator or chrome.
     */
    fun isUiNoiseOrChrome(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 3) return true
        if (clean.matches(Regex("""^\d+$"""))) return true // numbers alone < 3 or pure digits
        if (clean in actionKeywords || clean in navigationChrome) return true
        if (clean.lowercase() in metaAudienceWords) return true
        if (reactionCountRegex.matches(clean)) return true
        if (usernameHandleRegex.containsMatchIn(clean)) return true
        if (timeSimpleRegex.containsMatchIn(clean) || timeFormatRegex.containsMatchIn(clean)) return true
        if (garbagePatternRegex.containsMatchIn(clean)) return true
        val lower = clean.lowercase()
        if (adPatterns.any { lower.contains(it) }) return true
        return false
    }

    /**
     * Checks if text represents a post timestamp or metadata.
     */
    fun isTimestamp(text: String): Boolean {
        val clean = text.trim()
        if (clean.length > 40) return false
        val lower = clean.lowercase()
        if (lower in metaAudienceWords) return true
        return relativeTimestampRegex.containsMatchIn(clean) || timeSimpleRegex.containsMatchIn(clean) || timeFormatRegex.containsMatchIn(clean)
    }

    /**
     * Calculates linguistic confidence score (0.0 to 1.0).
     * If score < 0.60, the text is considered corrupt or garbage and must be discarded.
     */
    fun calculateConfidence(text: String): Float {
        val clean = text.trim()
        if (clean.length < 15) return 0.0f
        if (!PostSanitizer.isValidPostContent(clean)) return 0.0f

        val nonSpace = clean.count { !it.isWhitespace() }
        if (nonSpace == 0) return 0.0f

        val letterCount = clean.count { it in '\u0600'..'\u06FF' || it in 'a'..'z' || it in 'A'..'Z' }
        val letterRatio = letterCount.toFloat() / nonSpace

        if (letterRatio < 0.60f) return 0.0f
        if (isUiNoiseOrChrome(clean)) return 0.0f
        if (garbagePatternRegex.containsMatchIn(clean)) return 0.0f

        return letterRatio
    }

    /**
     * Strict validation of TextView nodes:
     * - Class name must strictly be/end with TextView.
     * - Must NOT be Button, ImageView, EditText, CheckBox, Switch, etc.
     * - Node bounds must be within usable screen area (below status bar > 100px).
     */
    fun isStrictTextView(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        // STRICT NODE TARGETING: Must be visible on screen
        try {
            if (!node.isVisibleToUser) return false
        } catch (e: Exception) {}

        val className = node.className?.toString() ?: return false
        if (className.contains("Button", ignoreCase = true) ||
            className.contains("Image", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("Progress", ignoreCase = true) ||
            className.contains("SeekBar", ignoreCase = true)
        ) {
            return false
        }
        return className == "android.widget.TextView" || className.endsWith("TextView")
    }

    /**
     * Extracts distinct, clean posts from an AccessibilityNodeInfo hierarchy.
     */
    fun extractPostsFromHierarchy(root: AccessibilityNodeInfo?): List<ParsedPostItem> {
        if (root == null) return emptyList()

        val results = mutableListOf<ParsedPostItem>()
        val postContainers = mutableListOf<AccessibilityNodeInfo>()

        // 1. Identify discrete post container cards in feed (preventing screen-level lumping)
        findPostContainers(root, postContainers, depth = 0)

        // 2. Parse each container strictly
        for (container in postContainers) {
            val parsed = parseSinglePostContainer(container)
            // Prioritize clean data over volume: confidence >= 0.60f and substantial pure content
            if (parsed != null && parsed.confidence >= 0.60f && parsed.contentBody.length >= 20) {
                results.add(parsed)
            }
        }

        return results
    }

    /**
     * Identifies candidate post container viewgroups in the feed.
     * Distinguishes between scrollable feed/screen containers and individual post cards.
     */
    private fun findPostContainers(
        node: AccessibilityNodeInfo?,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 8 || output.size >= 30) return

        val className = node.className?.toString() ?: ""
        val bounds = Rect()
        try { node.getBoundsInScreen(bounds) } catch (e: Exception) {}

        // Skip status bar area (top < 100px)
        if (bounds.top < 100) {
            for (i in 0 until node.childCount) {
                try { findPostContainers(node.getChild(i), output, depth + 1) } catch (e: Exception) {}
            }
            return
        }

        // If this node is a scrollable feed container (RecyclerView / ListView) or full screen (> 1600px tall),
        // it is a parent container holding multiple posts. WE MUST DESCEND into its children!
        val isFeedParent = node.isScrollable ||
                className.contains("Recycler", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ||
                className.contains("ViewPager", ignoreCase = true) ||
                bounds.height() > 1600

        if (isFeedParent) {
            for (i in 0 until node.childCount) {
                try { findPostContainers(node.getChild(i), output, depth + 1) } catch (e: Exception) {}
            }
            return
        }

        // Check if this node qualifies as an individual post card container
        val isLikelyCard = (bounds.width() > 220 && bounds.height() in 140..1550) &&
                (className.contains("ViewGroup") || className.contains("Layout") || className.contains("Card") || className.contains("FrameLayout"))

        if (isLikelyCard && hasPostSignature(node)) {
            output.add(node)
            return // Found discrete post card; do not descend into internal sub-containers
        }

        for (i in 0 until node.childCount) {
            try {
                findPostContainers(node.getChild(i), output, depth + 1)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun hasPostSignature(node: AccessibilityNodeInfo): Boolean {
        var hasTextViewWithSubstantialText = false
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                if (isStrictTextView(child)) {
                    val text = child.text?.toString() ?: ""
                    if (text.length >= 20 && !isUiNoiseOrChrome(text) && !isTimestamp(text)) {
                        hasTextViewWithSubstantialText = true
                        break
                    }
                }
            } catch (e: Exception) {}
        }
        return hasTextViewWithSubstantialText
    }

    /**
     * Traverses a single post container card, separating author, timestamp, and pure content body.
     */
    fun parseSinglePostContainer(container: AccessibilityNodeInfo): ParsedPostItem? {
        val containerBounds = Rect()
        try { container.getBoundsInScreen(containerBounds) } catch (e: Exception) {}

        val collectedTextViews = mutableListOf<NodeTextItem>()
        var hasMedia = false
        var avatarBounds: Rect? = null
        var detectedAuthorAvatarUrl: String? = null

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 6) return
            try {
                if (!node.isVisibleToUser) return
            } catch (e: Exception) {}

            val className = node.className?.toString() ?: ""
            val nodeBounds = Rect()
            try { node.getBoundsInScreen(nodeBounds) } catch (e: Exception) {}

            // Skip status bar nodes
            if (nodeBounds.top < 100) return

            // Check for avatar or post media
            if (className.contains("ImageView")) {
                val desc = node.contentDescription?.toString() ?: ""
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val isAvatarSize = nodeBounds.width() in 20..150 && nodeBounds.height() in 20..150
                val isProfileIndicated = desc.contains("الملف الشخصي", ignoreCase = true) ||
                        desc.contains("profile", ignoreCase = true) ||
                        desc.contains("avatar", ignoreCase = true) ||
                        resId.contains("avatar") ||
                        resId.contains("profile") ||
                        resId.contains("actor_pic")

                if (isProfileIndicated || isAvatarSize) {
                    avatarBounds = nodeBounds
                    detectedAuthorAvatarUrl = "avatar_detected"
                } else if (nodeBounds.width() > 180 && nodeBounds.height() > 120) {
                    hasMedia = true
                }
            }

            // STRICT NODE VALIDATION: Only accept android.widget.TextView
            if (isStrictTextView(node)) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank() && !isUiNoiseOrChrome(text)) {
                    val resId = node.viewIdResourceName ?: ""
                    collectedTextViews.add(NodeTextItem(text, nodeBounds, resId))
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    traverse(node.getChild(i), depth + 1)
                } catch (e: Exception) {}
            }
        }

        traverse(container, 0)
        if (collectedTextViews.isEmpty()) return null

        // Sort nodes spatially from top to bottom
        val sortedNodes = collectedTextViews.sortedBy { it.bounds.top }

        var authorName: String? = null
        var timestampText: String? = null
        val bodyLines = mutableListOf<String>()

        for (item in sortedNodes) {
            val text = item.text

            // 1. Check for timestamp or metadata: DISCARD from content body!
            if (isTimestamp(text)) {
                if (timestampText == null) {
                    timestampText = text
                }
                continue // Never add timestamp to bodyLines
            }

            // 2. Identify Author Metadata:
            // Locate the first meaningful TextView adjacent to avatar OR with specific user resource IDs
            val isAuthorResId = item.resourceId.contains("name", ignoreCase = true) ||
                    item.resourceId.contains("actor", ignoreCase = true) ||
                    item.resourceId.contains("author", ignoreCase = true) ||
                    item.resourceId.contains("title", ignoreCase = true)

            val isNearAvatar = avatarBounds != null &&
                    Math.abs(item.bounds.top - avatarBounds!!.top) < 140

            if (authorName == null && (isAuthorResId || isNearAvatar || isCandidateAuthor(text))) {
                authorName = text.trim()
                continue // Stored separately, never added to content body
            }

            // Prevent duplicating author name in content
            if (authorName != null && text.equals(authorName, ignoreCase = true)) {
                continue
            }

            // 3. Extract Pure Content: Subsequent substantial TextViews
            if (!isTimestamp(text) && !isUiNoiseOrChrome(text)) {
                bodyLines.add(text)
            }
        }

        var postContent = bodyLines.joinToString("\n").trim()
        if (postContent.length < 20) return null

        // Ensure Author Name is never mixed into the start of post body
        if (authorName != null && postContent.startsWith(authorName)) {
            postContent = postContent.removePrefix(authorName)
                .trim()
                .removePrefix(":")
                .removePrefix("-")
                .trim()
        }

        // Clean up any stray timestamp pattern from start or end of postContent
        postContent = postContent.replace(timeFormatRegex, "").trim()

        // STEP 1: Aggressive Real-Word & Phonetic Sanitization
        if (!PostSanitizer.isValidPostContent(postContent)) {
            return null // Discard immediately: contains random consonant mashing or lacks real words
        }

        // Clean Data Constraint: Check confidence score (>= 0.60f)
        val confidence = calculateConfidence(postContent)
        if (confidence < 0.60f || postContent.length < 20) {
            return null // Discard silently
        }

        val sanitizedAuthor = PostSanitizer.sanitizeAuthorName(authorName)
        val cleanSnippet = postContent.replace(Regex("\\s+"), " ").trim().take(100)
        val insight = PostInsights.analyze(postContent)
        val category = insight.summary().ifBlank { "عام" }

        return ParsedPostItem(
            author = sanitizedAuthor,
            timestamp = timestampText?.trim(),
            contentBody = postContent,
            fullText = postContent, // Strictly content only
            cleanSnippet = cleanSnippet,
            hasImage = hasMedia,
            authorAvatarUrl = detectedAuthorAvatarUrl,
            category = category,
            confidence = confidence,
            bounds = containerBounds
        )
    }

    private fun isCandidateAuthor(text: String): Boolean {
        val clean = text.trim()
        if (clean.length !in 3..45) return false
        if (clean.contains("\n") || clean.contains("?") || clean.contains("؟")) return false
        if (clean.startsWith("http") || clean.startsWith("#") || clean.startsWith("@")) return false
        if (isUiNoiseOrChrome(clean) || isTimestamp(clean)) return false
        val words = clean.split(' ').filter { it.isNotBlank() }
        return words.size in 1..5
    }

    private data class NodeTextItem(
        val text: String,
        val bounds: Rect,
        val resourceId: String = ""
    )
}
