package com.example.util

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Output data class representing a cleanly extracted and verified single Facebook post.
 */
data class CleanPost(
    val author: String,
    val body: String,      // Guaranteed clean, readable text
    val timestamp: String?,
    val isValid: Boolean   // True only if body length > 15 chars AND author != null/gibberish
)

/**
 * Single Post Precision Extraction Utility ("Bo Lite v10").
 *
 * Implements strict hierarchical parsing on a SINGLE root AccessibilityNodeInfo
 * representing one discrete post container card.
 *
 * - Step A: Finds avatar ImageView and its sibling TextView for Author Name.
 * - Step B: Discards noise/reactions/timestamps and selects largest content TextView as Post Body.
 * - Step C: Extracts metadata/timestamp safely without mixing with content.
 */
object PostExtractor {

    private const val TAG = "PostExtractor"

    // Reaction and interaction noise patterns to strictly discard
    private val reactionNoiseRegex = Regex(
        """^(?:^\d+$|like|likes|comment|comments|share|shares|reply|replies|إعجاب|إعجابات|تعليق|تعليقات|مشاركة|مشاركات|رد|ردود|أنت و.+|[\d\u0660-\u0669KkMm\s]+)$""",
        RegexOption.IGNORE_CASE
    )

    // Timestamp regex patterns (e.g. 11:49, 2 hrs, منذ ساعة, أمس, etc.)
    private val timeFormatRegex = Regex("""(?:\b|[PApa])?\d{1,2}:\d{2}(?:\s*(?:AM|PM|am|pm|ص|م))?(?:\b|$)""")
    private val relativeTimeRegex = Regex(
        """(?:\bمنذ\b|\bساعة\b|\bساعات\b|\bدقيقة\b|\bدقائق\b|\bأمس\b|\byesterday\b|\bjust now\b|\bالآن\b|\b\d+\s*[hmdwHMDW]\b|\b\d+\s*(?:hrs?|mins?|days?|hours?|weeks?)\b|\bيوم\b|\bيومان\b|\bأيام\b)""",
        RegexOption.IGNORE_CASE
    )

    // Status bar and chrome noise patterns (e.g. 999gâS, P11:49, LTE, battery, WiFi)
    private val chromeNoiseRegex = Regex(
        """^.*(?:\d+[gâsG]+|LTE|4G|5G|WiFi|battery|\b\d{1,3}%\b|[PApa]\d{1,2}:\d{2}|@\w+).*$""",
        RegexOption.IGNORE_CASE
    )

    // Common Latin vowels for gibberish detection
    private val latinVowels = setOf('a', 'e', 'i', 'o', 'u', 'y', 'A', 'E', 'I', 'O', 'U', 'Y')

    /**
     * Primary entry point: Extracts a single CleanPost from a single post container card.
     *
     * @param rootNode AccessibilityNodeInfo representing ONE complete post container card.
     * @return CleanPost instance, or null if the node is null or completely invalid.
     */
    fun processOnePost(rootNode: AccessibilityNodeInfo?): CleanPost? {
        if (rootNode == null) {
            Log.w(TAG, "processOnePost called with null rootNode")
            return null
        }

        val containerBounds = Rect()
        try { rootNode.getBoundsInScreen(containerBounds) } catch (e: Exception) {}
        Log.d(TAG, "--- [START] Processing Single Post Container bounds=$containerBounds class=${rootNode.className} ---")

        // -------------------------------------------------------------
        // STEP A: Identify Author Name
        // Find avatar ImageView and inspect its sibling TextViews
        // -------------------------------------------------------------
        val (rawAuthor, detectedAvatarBounds) = extractAuthorFromHierarchy(rootNode)
        val validatedAuthor = validateAuthor(rawAuthor)
        Log.d(TAG, "Step A Result: rawAuthor='$rawAuthor' -> validatedAuthor='$validatedAuthor'")

        // -------------------------------------------------------------
        // STEP C: Extract Metadata / Timestamp Safely
        // Detect timestamp node by resource ID or timestamp patterns
        // -------------------------------------------------------------
        val detectedTimestamp = extractTimestampFromHierarchy(rootNode, validatedAuthor)
        Log.d(TAG, "Step C Result: extracted timestamp='$detectedTimestamp'")

        // -------------------------------------------------------------
        // STEP B: Identify Content Body
        // Find the LARGEST TextView that is not the author and not a timestamp
        // -------------------------------------------------------------
        val rawBody = extractContentBody(rootNode, validatedAuthor, detectedTimestamp)
        val cleanBody = formatAndEnforceRtl(rawBody)
        Log.d(TAG, "Step B Result: extracted body length=${cleanBody.length}, preview='${cleanBody.take(60)}'")

        // -------------------------------------------------------------
        // FINAL VALIDATION:
        // True only if body length > 15 chars AND author != null/gibberish
        // -------------------------------------------------------------
        val isAuthorValid = validatedAuthor != "Unknown" && validatedAuthor.isNotBlank()
        val isBodyValid = cleanBody.length > 15 && !isNoise(cleanBody) && PostSanitizer.isValidPostContent(cleanBody)
        val isValidPost = isAuthorValid && isBodyValid

        val result = CleanPost(
            author = validatedAuthor,
            body = cleanBody,
            timestamp = detectedTimestamp,
            isValid = isValidPost
        )

        Log.d(TAG, "--- [FINISH] Extracted Post: author='${result.author}', valid=${result.isValid}, bodyLength=${result.body.length} ---")
        return result
    }

    /**
     * STEP A: Hierarchical Avatar & Author Identification.
     * Searches for an ImageView representing the avatar, then locates the sibling TextView.
     */
    private fun extractAuthorFromHierarchy(root: AccessibilityNodeInfo): Pair<String?, Rect?> {
        val avatarRect = Rect()
        var candidateAuthor: String? = null

        // 1. Search for avatar ImageView via structural traversal
        val avatarNode = findAvatarImageView(root)
        if (avatarNode != null) {
            try { avatarNode.getBoundsInScreen(avatarRect) } catch (e: Exception) {}
            Log.d(TAG, "Step A: Found Avatar ImageView bounds=$avatarRect desc='${avatarNode.contentDescription}' id='${avatarNode.viewIdResourceName}'")

            // Look in avatar's parent for sibling TextView
            val parent = avatarNode.parent
            if (parent != null) {
                candidateAuthor = findSiblingTextView(parent, avatarNode)
                if (candidateAuthor == null && parent.parent != null) {
                    // Try grandparent if avatar was wrapped in a layout container
                    candidateAuthor = findSiblingTextView(parent.parent, parent)
                }
            }
        }

        // 2. Fallback: If no avatar found, check top header TextView with author-like resource IDs or position
        if (candidateAuthor.isNullOrBlank()) {
            Log.d(TAG, "Step A Fallback: Locating header TextView candidate structurally")
            candidateAuthor = findTopHeaderAuthorTextView(root)
        }

        return Pair(candidateAuthor?.trim(), if (avatarRect.isEmpty) null else avatarRect)
    }

    /**
     * Finds an ImageView that qualifies as an author profile avatar.
     */
    private fun findAvatarImageView(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 6) return null

        val className = node.className?.toString() ?: ""
        if (className.contains("ImageView", ignoreCase = true)) {
            val bounds = Rect()
            try { node.getBoundsInScreen(bounds) } catch (e: Exception) {}
            val width = bounds.width()
            val height = bounds.height()

            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""

            val isAvatarSize = width in 24..180 && height in 24..180
            val isAvatarSignature = desc.contains("profile") || desc.contains("avatar") || desc.contains("الملف الشخصي") ||
                    desc.contains("صورة") || resId.contains("avatar") || resId.contains("profile") || resId.contains("actor_pic")

            if (isAvatarSize || isAvatarSignature) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findAvatarImageView(child, depth + 1)
                if (found != null) return found
            } catch (e: Exception) {}
        }
        return null
    }

    /**
     * Locates a sibling TextView near the target node within a parent container.
     */
    private fun findSiblingTextView(parent: AccessibilityNodeInfo, excludeNode: AccessibilityNodeInfo): String? {
        for (i in 0 until parent.childCount) {
            try {
                val child = parent.getChild(i) ?: continue
                if (child == excludeNode) continue

                val className = child.className?.toString() ?: ""
                if (isStrictTextView(child)) {
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrBlank() && !isNoise(text) && !isTimestampText(text)) {
                        Log.d(TAG, "Step A: Found sibling TextView text='$text' id='${child.viewIdResourceName}'")
                        return text
                    }
                } else if (child.childCount > 0) {
                    // Descend into text container layouts adjacent to avatar (e.g. LinearLayout holding name and time)
                    for (j in 0 until child.childCount) {
                        val subChild = child.getChild(j) ?: continue
                        if (isStrictTextView(subChild)) {
                            val subText = subChild.text?.toString()?.trim()
                            if (!subText.isNullOrBlank() && !isNoise(subText) && !isTimestampText(subText)) {
                                Log.d(TAG, "Step A: Found sub-sibling TextView text='$subText' id='${subChild.viewIdResourceName}'")
                                return subText
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun findTopHeaderAuthorTextView(node: AccessibilityNodeInfo): String? {
        val collected = mutableListOf<Pair<String, Rect>>()
        collectAllVisibleTextViews(node, collected, depth = 0)

        // Sort by Y position (top-most first)
        val sorted = collected.sortedBy { it.second.top }
        for (item in sorted) {
            val text = item.first
            if (text.length in 3..40 && !isTimestampText(text) && !isNoise(text)) {
                return text
            }
        }
        return null
    }

    /**
     * Validates Author Name:
     * If authorName contains numbers < 3 digits OR looks like gibberish (no vowels/spaces), set to "Unknown".
     */
    fun validateAuthor(author: String?): String {
        if (author.isNullOrBlank()) return "Unknown"
        val clean = author.trim()

        // Length constraints
        if (clean.length !in 3..45) return "Unknown"

        // Reject noise, URLs, or status bar items
        if (isNoise(clean) || clean.contains("http") || clean.contains("\n")) return "Unknown"

        // Check digits: If it contains numbers < 3 digits (e.g. "John1", "User23")
        val digitCount = clean.count { it.isDigit() }
        if (digitCount in 1..2) {
            Log.d(TAG, "Step A Validation: Rejected author '$clean' (contains numbers < 3 digits)")
            return "Unknown"
        }

        // Arabic validation
        val isArabic = clean.any { it in '\u0600'..'\u06FF' }
        if (isArabic) {
            val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
            val hasHonorific = clean.startsWith("د.") || clean.startsWith("م.") || clean.startsWith("أ.") ||
                    clean.startsWith("دكتور") || clean.startsWith("مهندس") || clean.startsWith("أستاذ")
            if (words.size >= 2 || hasHonorific || (clean.length in 3..18 && words.size == 1)) {
                return clean
            }
            return "Unknown"
        }

        // Latin validation: Check for vowels and spaces
        val isLatin = clean.all { it.isLetter() || it.isWhitespace() }
        if (isLatin) {
            val hasSpaces = clean.contains(" ")
            val vowelCount = clean.count { it in latinVowels }

            // If no vowels or no spaces in single lowercase token -> GIBBERISH
            if (vowelCount == 0) {
                Log.d(TAG, "Step A Validation: Rejected author '$clean' (no vowels)")
                return "Unknown"
            }

            val isSingleLowercase = !hasSpaces && clean.all { it.isLowerCase() }
            if (isSingleLowercase) {
                Log.d(TAG, "Step A Validation: Rejected author '$clean' (single lowercase token without spaces)")
                return "Unknown"
            }

            // Must contain spaces (First + Last name) OR a capital letter
            if (hasSpaces || clean.any { it.isUpperCase() }) {
                return clean
            }
        }

        return "Unknown"
    }

    /**
     * STEP B: Identify Content Body.
     * Finds the LARGEST TextView within the post container that is NOT the author name and NOT a timestamp.
     * Filters noise, reactions, and text < 10 characters.
     */
    private fun extractContentBody(
        root: AccessibilityNodeInfo,
        authorName: String,
        timestampText: String?
    ): String {
        val candidates = mutableListOf<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8) return

            try {
                if (!node.isVisibleToUser) return
            } catch (e: Exception) {
                return
            }

            if (isStrictTextView(node)) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    val isAuthor = authorName != "Unknown" && text.equals(authorName, ignoreCase = true)
                    val isTime = (timestampText != null && text.equals(timestampText, ignoreCase = true)) || isTimestampText(text)
                    val isActionNoise = isNoise(text)

                    // CRITICAL FILTER: Ignore text < 10 chars unless it's clearly a headline
                    val isHeadline = text.length in 8..9 && (text.endsWith(":") || text.endsWith("!"))
                    val isLengthAcceptable = text.length >= 10 || isHeadline

                    if (!isAuthor && !isTime && !isActionNoise && isLengthAcceptable) {
                        Log.d(TAG, "Step B Candidate: length=${text.length}, text='${text.take(40)}...' id='${node.viewIdResourceName}'")
                        candidates.add(text)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    walk(node.getChild(i), depth + 1)
                } catch (e: Exception) {}
            }
        }

        walk(root, 0)

        if (candidates.isEmpty()) {
            return ""
        }

        // Pick the LARGEST candidate TextView as the primary post content body
        val largest = candidates.maxByOrNull { it.length } ?: ""
        Log.d(TAG, "Step B: Selected largest candidate of ${candidates.size} items (length=${largest.length})")

        // If the post has multiple paragraph blocks that were split into siblings, check if we should concatenate
        val substantialBlocks = candidates.filter { it.length >= 25 }
        return if (substantialBlocks.size > 1 && substantialBlocks.contains(largest)) {
            // Join substantial paragraphs in original visual sequence
            substantialBlocks.joinToString("\n\n").trim()
        } else {
            largest.trim()
        }
    }

    /**
     * STEP C: Safely extract timestamp without mixing into content body.
     */
    private fun extractTimestampFromHierarchy(root: AccessibilityNodeInfo, authorName: String): String? {
        val collected = mutableListOf<Pair<String, String>>() // (text, resourceId)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8) return
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            if (isStrictTextView(node)) {
                val text = node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    collected.add(Pair(text, resId))
                }
            }
            for (i in 0 until node.childCount) {
                try { walk(node.getChild(i), depth + 1) } catch (e: Exception) {}
            }
        }

        walk(root, 0)

        // 1. Check for specific resource IDs used by Facebook for timestamps
        for ((text, resId) in collected) {
            val isTimestampResId = resId.contains("time") || resId.contains("date") ||
                    resId.contains("timestamp") || resId.contains("subtitle") || resId.contains("header_subtext")
            if (isTimestampResId && isTimestampText(text) && !text.equals(authorName, ignoreCase = true)) {
                Log.d(TAG, "Step C: Found timestamp by resource ID '$resId': '$text'")
                return text
            }
        }

        // 2. Check for text matching relative timestamp regex or time format
        for ((text, _) in collected) {
            if (isTimestampText(text) && !text.equals(authorName, ignoreCase = true)) {
                Log.d(TAG, "Step C: Found timestamp by regex pattern: '$text'")
                return text
            }
        }

        return null
    }

    /**
     * Helper to verify if node is strictly an android.widget.TextView and visible.
     */
    private fun isStrictTextView(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val className = node.className?.toString() ?: return false
        if (className != "android.widget.TextView" && !className.endsWith("TextView")) {
            return false
        }
        if (className.contains("Button", ignoreCase = true) ||
            className.contains("Image", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("CheckBox", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    /**
     * Checks if text represents a timestamp.
     */
    private fun isTimestampText(text: String): Boolean {
        val clean = text.trim()
        if (clean.length > 35) return false
        return relativeTimeRegex.containsMatchIn(clean) || timeFormatRegex.containsMatchIn(clean)
    }

    /**
     * Noise and reaction filter:
     * Discards numbers alone, reactions ("Like", "Comment"), chrome ("999gâS", "P11:49", handles).
     */
    private fun isNoise(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 3) return true
        if (clean.matches(Regex("""^\d+$"""))) return true
        if (reactionNoiseRegex.matches(clean)) return true
        if (chromeNoiseRegex.matches(clean)) return true
        return false
    }

    private fun collectAllVisibleTextViews(
        node: AccessibilityNodeInfo?,
        output: MutableList<Pair<String, Rect>>,
        depth: Int
    ) {
        if (node == null || depth > 8) return
        if (isStrictTextView(node)) {
            val txt = node.text?.toString()?.trim()
            if (!txt.isNullOrBlank()) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (e: Exception) {}
                output.add(Pair(txt, rect))
            }
        }
        for (i in 0 until node.childCount) {
            try { collectAllVisibleTextViews(node.getChild(i), output, depth + 1) } catch (e: Exception) {}
        }
    }

    /**
     * Enforces correct RTL orientation if Arabic content is detected.
     */
    private fun formatAndEnforceRtl(text: String): String {
        if (text.isBlank()) return ""
        val hasArabic = text.any { it in '\u0600'..'\u06FF' }
        return if (hasArabic) {
            // Prepend Right-to-Left Mark (RLM \u200F) if not present to guarantee proper rendering in all UI contexts
            if (text.startsWith("\u200F")) text else "\u200F$text"
        } else {
            text
        }
    }
}
