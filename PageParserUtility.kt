package com.example.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.PostComment
import java.util.UUID

enum class RelevanceTier {
    HIGH,    // Bright orange #FF6B35
    MEDIUM,  // Teal #00A896
    LOW      // Gray #757582
}

data class ParsedPostBlock(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fullText: String = text,
    val author: String? = null,
    val timestamp: String? = null,
    val category: String,
    val score: Int = 10,
    val tier: RelevanceTier = RelevanceTier.HIGH,
    val sourceName: String = "فيسبوك",
    val sourceUrl: String? = null,
    val comments: List<PostComment> = emptyList(),
    val isWithin7Days: Boolean = true
)

/**
 * PageParserUtility: Strictly parses "Current Screen Snapshot Only".
 * Zero history, zero caching, strictly visual on-screen hierarchy of the active feed container.
 */
object PageParserUtility {

    private val highPriorityKeywords = listOf(
        "دكتور", "دكتورة", "د.", "استاذ", "أستاذ", "بروفيسور", "معيد",
        "مادة", "ماده", "كورس", "منهج", "مقرر", "امتحان", "اختبار", "فاينل", "ميد",
        "كويز", "quiz", "exam", "تسجيل", "ريكورد", "فويس", "pdf", "درايف",
        "drive", "سلايدات", "slides", "ملزمة", "ملخص", "تجميعات", "وظيفة", "تدريب"
    )

    private val mediumPriorityKeywords = listOf(
        "كافتيريا", "سكن", "مكتبة", "جدول", "درجات", "علامات", "واجب",
        "اسايمنت", "assignment", "حضور", "غياب", "محاضرة", "محاضره", "شرح",
        "استفسار", "سؤال", "جامعة", "كلية", "تخصص", "شعبة", "شعب", "قاعة"
    )

    private val timestampPatterns = listOf(
        "منذ", "ساعة", "ساعات", "دقيقة", "دقائق", "أمس", "yesterday",
        "just now", "الآن", "hrs", "mins", "days", "يوم", "يومان", "أيام", "اسبوع", "أسبوع"
    )

    // Ad & sponsored patterns to unconditionally reject
    private val adPatterns = listOf(
        "ممول", "مُموَّل", "إعلان", "اعلان", "sponsored", "advertisement",
        "suggested for you", "مقترح لك", "قد يعجبك", "popular across facebook"
    )

    // UI chrome, actions, buttons, and navigation labels to discard
    private val navigationChromeLabels = setOf(
        "بحث", "search", "الرئيسية", "home", "إشعارات", "notifications", "القائمة", "menu",
        "watch", "فيديو", "reels", "ريلز", "marketplace", "ماركت بليس", "gaming", "ألعاب",
        "انضمام", "join", "انضم للمجموعة", "join group", "joined", "تم الانضمام",
        "دعوة", "invite", "مغادرة", "leave", "تعديل", "edit", "حذف", "delete", "إبلاغ", "report",
        "نسخ الرابط", "copy link", "عرض المزيد", "see more", "المزيد", "more", "less", "عرض أقل",
        "رجوع", "back", "إغلاق", "close", "أعضاء", "members", "حول", "about",
        "الفعاليات", "events", "ملفات", "files", "وسائط", "media", "ألبومات", "albums",
        "صورة", "photo", "منشور", "post", "منشورات", "posts",
        "إعجاب", "like", "أعجبني", "likes", "تعليق", "comment", "تعليقات", "comments",
        "مشاركة", "share", "مشاركات", "shares", "إرسال", "send", "رد", "reply", "ردود", "replies",
        "اكتب تعليقاً", "اكتب تعليق", "write a comment",
        "فيس بوك", "facebook", "meta", "تطبيق", "app", "follow", "متابعة", "متابع"
    )

    /**
     * Parses the current visible active window snapshot into clean, unique post blocks.
     * Core principles:
     * 1. Strict visual scope: evaluates visible nodes inside the active feed container.
     * 2. Relevance filtering: substantial text (>50 chars), discards UI chrome and ads.
     * 3. Duplicate prevention: eliminates repeats silently via normalized content checking.
     */
    fun parseScreen(rootNode: AccessibilityNodeInfo?): Pair<String, List<ParsedPostBlock>> {
        if (rootNode == null) return Pair("صفحة عامة", emptyList())

        val sourceName = detectSourceName(rootNode)

        // 1. Locate the main feed container (e.g. RecyclerView/ListView/ScrollView)
        val feedContainer = findMainFeedContainer(rootNode)

        val collectedNodes = mutableListOf<NodeSnapshot>()
        val bounds = Rect()

        // 2. Traverse only visible nodes currently rendered on screen
        traverseVisibleNodes(
            node = feedContainer,
            output = collectedNodes,
            inScrollContainer = true,
            boundsHolder = bounds,
            maxNodes = 400
        )

        // 3. Assemble unique posts and enforce duplicate prevention
        val postBlocks = chunkNodesIntoPosts(collectedNodes, sourceName)
        return Pair(sourceName, postBlocks.take(60))
    }

    /**
     * Identifies the primary scrollable feed container within the active window.
     */
    private fun findMainFeedContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var bestContainer: AccessibilityNodeInfo? = null
        var maxArea = 0
        val rect = Rect()

        fun searchContainers(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8) return
            if (isScrollableContainer(node)) {
                try {
                    node.getBoundsInScreen(rect)
                    val area = rect.width() * rect.height()
                    if (rect.width() > 180 && rect.height() > 250 && area > maxArea) {
                        maxArea = area
                        bestContainer = node
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            for (i in 0 until node.childCount) {
                try {
                    searchContainers(node.getChild(i), depth + 1)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        searchContainers(root, 0)
        return bestContainer ?: root
    }

    data class NodeSnapshot(
        val text: String,
        val isPostBody: Boolean,
        val isLikelyAuthor: Boolean,
        val isLikelyTimestamp: Boolean
    )

    private fun traverseVisibleNodes(
        node: AccessibilityNodeInfo?,
        output: MutableList<NodeSnapshot>,
        inScrollContainer: Boolean,
        boundsHolder: Rect,
        maxNodes: Int
    ) {
        if (node == null || output.size >= maxNodes) return

        // STRICT NODE TARGETING: Only extract text from nodes where className == TextView AND isVisibleToUser
        try {
            if (!node.isVisibleToUser) {
                return
            }
        } catch (e: Exception) {
            return
        }

        val className = node.className?.toString() ?: ""
        if (!className.endsWith("TextView") || className.contains("Button") || className.contains("Image") || className.contains("Edit")) {
            for (i in 0 until node.childCount) {
                try {
                    traverseVisibleNodes(node.getChild(i), output, inScrollContainer, boundsHolder, maxNodes)
                } catch (e: Exception) {}
            }
            return
        }

        // STRICT: Never use contentDescription for post body; only actual TextView text
        val rawText = node.text?.toString()?.trim()

        if (!rawText.isNullOrBlank() && rawText.length >= 3) {
            try {
                node.getBoundsInScreen(boundsHolder)
            } catch (e: Exception) {
                boundsHolder.setEmpty()
            }

            val isAd = isAdOrSponsored(rawText)
            val isChrome = isNavigationOrChrome(rawText, boundsHolder)

            if (!isAd && !isChrome) {
                val isTimestamp = isTimestampText(rawText)

                // Requirement 4: Extract only substantial text blocks (>35 chars) representing actual posts
                val isBody = !isTimestamp && (
                    rawText.length > 35 ||
                    (rawText.length in 20..35 && rawText.contains(" ") && hasDiscussionMarkers(rawText))
                )

                // Author name: short clean line (3..35 chars), single line, no punctuation/links
                val isAuthor = !isTimestamp && !isBody && rawText.length in 3..35 &&
                    !rawText.contains("\n") && !rawText.startsWith("http") && !rawText.startsWith("#") &&
                    !rawText.contains(":")

                if (isBody || isAuthor || isTimestamp) {
                    output.add(
                        NodeSnapshot(
                            text = rawText,
                            isPostBody = isBody,
                            isLikelyAuthor = isAuthor,
                            isLikelyTimestamp = isTimestamp
                        )
                    )
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (output.size >= maxNodes) break
            try {
                val child = node.getChild(i)
                if (child != null) {
                    traverseVisibleNodes(child, output, inScrollContainer, boundsHolder, maxNodes)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun isScrollableContainer(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        val className = node.className?.toString() ?: return false
        return className.contains("Scroll", ignoreCase = true) ||
            className.contains("Recycler", ignoreCase = true) ||
            className.contains("List", ignoreCase = true) ||
            className.contains("ViewPager", ignoreCase = true)
    }

    private fun isAdOrSponsored(text: String): Boolean {
        val lower = text.lowercase()
        return adPatterns.any { lower.contains(it) }
    }

    private fun isNavigationOrChrome(text: String, bounds: Rect): Boolean {
        val clean = text.trim()
        val lower = clean.lowercase()

        // Pure digits or counters
        if (clean.matches("^[0-9+.,kKmM]+$".toRegex()) && clean.length <= 6) {
            return true
        }

        // Tiny corner icons or side buttons
        if (!bounds.isEmpty && bounds.width() in 1..80 && bounds.height() in 1..80 && clean.length < 20) {
            return true
        }

        // UI action & navigation words
        if (clean.length < 25) {
            if (navigationChromeLabels.contains(lower)) return true
            if (clean.contains("تعليق") && clean.length < 15 && clean.any { it.isDigit() }) return true
            if (clean.contains("إعجاب") && clean.length < 15 && clean.any { it.isDigit() }) return true
            if (clean.contains("مشاركة") && clean.length < 15 && clean.any { it.isDigit() }) return true
        }

        return false
    }

    private fun hasDiscussionMarkers(text: String): Boolean {
        val lower = text.lowercase()
        return highPriorityKeywords.any { lower.contains(it) } ||
            mediumPriorityKeywords.any { lower.contains(it) } ||
            text.contains("؟") || text.contains("?") || text.contains(":") || text.contains("،")
    }

    private fun isTimestampText(text: String): Boolean {
        val lower = text.lowercase()
        return timestampPatterns.any { lower.contains(it) } ||
            text.matches(".*\\d{1,2}:\\d{2}.*".toRegex()) ||
            text.matches("^\\d+[mhdwsMHDWS]$".toRegex())
    }

    private fun detectSourceName(rootNode: AccessibilityNodeInfo): String {
        val topTexts = mutableListOf<String>()

        fun findTopHeaders(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 6 || topTexts.size >= 8) return
            val txt = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!txt.isNullOrBlank() && txt.length in 3..60 && !isTimestampText(txt) && !isAdOrSponsored(txt)) {
                topTexts.add(txt)
            }
            for (i in 0 until node.childCount) {
                try {
                    findTopHeaders(node.getChild(i), depth + 1)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        findTopHeaders(rootNode, 0)

        val pageCandidate = topTexts.firstOrNull { candidate ->
            val lower = candidate.lowercase()
            !navigationChromeLabels.contains(lower) &&
            !lower.contains("بحث") &&
            !lower.contains("search") &&
            !lower.contains("notifications") &&
            !lower.contains("إشعارات") &&
            !lower.contains("home") &&
            !lower.contains("الرئيسية")
        }

        return pageCandidate ?: "مجموعة فيسبوك"
    }

    private fun chunkNodesIntoPosts(
        nodes: List<NodeSnapshot>,
        sourceName: String
    ): List<ParsedPostBlock> {
        val blocks = mutableListOf<ParsedPostBlock>()
        // Requirement 3: DUPLICATE PREVENTION (Unique normalized keys)
        val seenPostKeys = mutableSetOf<String>()

        var pendingAuthor: String? = null
        var pendingTimestamp: String? = null

        for (snapshot in nodes) {
            when {
                snapshot.isLikelyTimestamp -> {
                    pendingTimestamp = snapshot.text
                }
                snapshot.isLikelyAuthor -> {
                    pendingAuthor = snapshot.text
                }
                snapshot.isPostBody -> {
                    val bodyText = snapshot.text
                    // Normalize text to detect identical content regardless of spacing
                    val normalizedKey = bodyText.trim().replace("\\s+".toRegex(), " ")

                    // STEP 1: Aggressive Real-Word & Phonetic Sanitization
                    if (!PostSanitizer.isValidPostContent(bodyText)) {
                        pendingAuthor = null
                        pendingTimestamp = null
                        continue
                    }

                    // If duplicate found -> Skip it silently. No repeated posts allowed.
                    if (!seenPostKeys.contains(normalizedKey)) {
                        seenPostKeys.add(normalizedKey)

                        val score = calculateRelevanceScore(bodyText)
                        val tier = when {
                            score >= 4 -> RelevanceTier.HIGH
                            score in 2..3 -> RelevanceTier.MEDIUM
                            else -> RelevanceTier.LOW
                        }
                        val category = CategoryMatcher.match(bodyText)
                        val isRecent = TimeFilterUtility.isWithinLast7Days(pendingTimestamp)
                        val cleanAuthor = PostSanitizer.sanitizeAuthorName(pendingAuthor)

                        if (isRecent) {
                            blocks.add(
                                ParsedPostBlock(
                                    text = bodyText,
                                    fullText = bodyText,
                                    author = cleanAuthor,
                                    timestamp = pendingTimestamp,
                                    category = category,
                                    score = score,
                                    tier = tier,
                                    sourceName = sourceName,
                                    sourceUrl = buildSourceUrl(sourceName, bodyText),
                                    comments = emptyList(),
                                    isWithin7Days = true
                                )
                            )
                        }
                    }

                    // Reset context for next post block
                    pendingAuthor = null
                    pendingTimestamp = null
                }
            }
        }

        return blocks
    }

    private fun calculateRelevanceScore(text: String): Int {
        val lower = text.lowercase()
        var score = 1

        for (kw in highPriorityKeywords) {
            if (lower.contains(kw)) {
                score += 3
            }
        }

        for (kw in mediumPriorityKeywords) {
            if (lower.contains(kw)) {
                score += 1
            }
        }

        return score
    }

    private fun buildSourceUrl(sourceName: String, text: String): String {
        val querySnippet = text.take(60).replace("\n", " ").trim()
        val encodedQuery = android.net.Uri.encode(querySnippet)
        return "https://www.facebook.com/search/posts/?q=$encodedQuery"
    }

    /**
     * Fallback used when the strict parser finds nothing: take every visible, reasonably long
     * text on the Facebook screen (still skipping ads and buttons) and show it as a post.
     */
    fun parseScreenRelaxed(rootNode: AccessibilityNodeInfo?): List<ParsedPostBlock> {
        if (rootNode == null) return emptyList()
        val sourceName = detectSourceName(rootNode)
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ParsedPostBlock>()
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60 || out.size >= 80) return
            val visible = try { node.isVisibleToUser } catch (e: Exception) { false }
            val className = node.className?.toString() ?: ""
            val isTextView = className.endsWith("TextView") && !className.contains("Button") && !className.contains("Image") && !className.contains("Edit")
            val raw = node.text?.toString()?.trim()
            if (visible && isTextView && !raw.isNullOrBlank() && raw.length >= 20 && PostSanitizer.isValidPostContent(raw)) {
                try { node.getBoundsInScreen(rect) } catch (e: Exception) { rect.setEmpty() }
                val key = raw.replace(Regex("\\s+"), " ")
                if (!isAdOrSponsored(raw) && !isNavigationOrChrome(raw, rect) &&
                    !isTimestampText(raw) && seen.add(key)
                ) {
                    out.add(
                        ParsedPostBlock(
                            text = raw,
                            fullText = raw,
                            author = "Unknown Member",
                            category = CategoryMatcher.match(raw),
                            score = calculateRelevanceScore(raw),
                            tier = RelevanceTier.LOW,
                            sourceName = sourceName,
                            sourceUrl = buildSourceUrl(sourceName, raw)
                        )
                    )
                }
            }
            for (i in 0 until node.childCount) {
                try { walk(node.getChild(i), depth + 1) } catch (e: Exception) { /* ignore */ }
            }
        }
        walk(rootNode, 0)
        return out
    }
}
