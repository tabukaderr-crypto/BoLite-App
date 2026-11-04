package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AppDatabase
import com.example.data.ArchivedPost
import com.example.util.PostSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * BoLiteAccessibilityService
 *
 * Privacy-compliant, non-intrusive AccessibilityService for "Bo Lite".
 * Reads visible educational content locally, concatenates split post paragraphs,
 * and extracts threaded comments/replies into student study notes.
 *
 * PRIVACY & COMPLIANCE GUARANTEE:
 * 1. 100% On-Device Processing: No network calls or cloud connections are made.
 * 2. Zero Data Exfiltration: The application declares NO internet permission (android.permission.INTERNET is omitted).
 * 3. Battery Conservation: Enforces minimum 500ms event throttle and background coroutine delays.
 * 4. Memory Safety: No static Context, Service, or View references are maintained to prevent memory leaks.
 */
class BoLiteAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "BoLiteAccessibility"
        private const val TARGET_FACEBOOK_PACKAGE = "com.facebook.katana"
        private const val TARGET_FACEBOOK_LITE_PACKAGE = "com.facebook.lite"

        const val ACTION_TRIGGER_SCAN = "com.example.accessibility.ACTION_TRIGGER_SCAN"
        const val ACTION_STOP_SCAN = "com.example.accessibility.ACTION_STOP_SCAN"

        // Observable scanning state flow for UI and overlay bubbles (Safe primitives only, no context/view leaks)
        val isScanningFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun toggleScan(context: android.content.Context) {
            val nextState = !isScanningFlow.value
            isScanningFlow.value = nextState
            val intent = android.content.Intent(if (nextState) ACTION_TRIGGER_SCAN else ACTION_STOP_SCAN).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    private val scanReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                ACTION_TRIGGER_SCAN -> {
                    isScanningFlow.value = true
                    triggerScanNow()
                }
                ACTION_STOP_SCAN -> {
                    isScanningFlow.value = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_TRIGGER_SCAN)
            addAction(ACTION_STOP_SCAN)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "BoLiteAccessibilityService connected. Configuring minimal event filters.")

        // Configure minimal event listening compliant with Google Play accessibility policies
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100L
        }
        serviceInfo = info
    }

    private var lastEventScanTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: ""
        // Validate target package to process only relevant educational posts
        if (pkgName != TARGET_FACEBOOK_PACKAGE && pkgName != TARGET_FACEBOOK_LITE_PACKAGE) {
            return
        }

        // Battery conservation throttle: ignore high-frequency event bursts within 500ms
        val now = System.currentTimeMillis()
        if (now - lastEventScanTime < 500L) {
            return
        }
        lastEventScanTime = now

        val rootNode: AccessibilityNodeInfo = try {
            event.source ?: rootInActiveWindow ?: return
        } catch (e: Exception) {
            Log.w(TAG, "Failed to obtain root accessibility node: ${e.message}")
            return
        }

        // Offload node traversal and local database persistence to background coroutines
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Short delay to let UI render completely and prevent CPU spikes
                kotlinx.coroutines.delay(250L)
                processNodeHierarchy(rootNode, pkgName)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing accessibility event hierarchy", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "BoLiteAccessibilityService interrupted by system.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BoLiteAccessibilityService destroyed. Cancelling coroutine scope.")
        try {
            unregisterReceiver(scanReceiver)
        } catch (ignored: Exception) {}
        serviceScope.cancel()
    }

    /**
     * Triggered directly via broadcast from the overlay bubble or notification.
     * Captures current active window content immediately.
     */
    fun triggerScanNow() {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val pkg = root.packageName?.toString() ?: ""
                processNodeHierarchy(root, pkg)
            } finally {
                // Auto-reset state after a brief reading burst
                kotlinx.coroutines.delay(1200L)
                isScanningFlow.value = false
            }
        }
    }

    private data class ExtractedTextNode(
        val text: String,
        val viewId: String?,
        val isComment: Boolean
    )

    private data class EnrichedPostBlock(
        var author: String = "عضو في المجموعة",
        var timestamp: String? = null,
        val paragraphs: MutableList<String> = mutableListOf(),
        val comments: MutableList<String> = mutableListOf()
    ) {
        val primaryBody: String
            get() = paragraphs.joinToString("\n\n").trim()

        val enrichedFullText: String
            get() {
                val main = primaryBody
                if (comments.isEmpty()) return main
                return buildString {
                    append(main)
                    append("\n\n--- 💬 الردود والتعليقات (${comments.size}) ---\n")
                    comments.forEachIndexed { idx, comment ->
                        val cleanC = comment.removePrefix("💬").trim()
                        append("${idx + 1}. ").append(cleanC).append("\n\n")
                    }
                }.trim()
            }
    }

    /**
     * Traverses the node tree safely, extracts visible text nodes with structural context,
     * concatenates split paragraphs, captures threaded replies, and persists enriched posts
     * into Room SQLite database.
     */
    private suspend fun processNodeHierarchy(rootNode: AccessibilityNodeInfo, packageName: String) {
        val rawNodes = mutableListOf<ExtractedTextNode>()
        extractVisibleTextNodes(rootNode, rawNodes, depth = 0)

        if (rawNodes.isEmpty()) return

        val database = try {
            AppDatabase.getDatabase(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Database access error in accessibility service", e)
            return
        }

        val postDao = database.postDao()

        // Cluster consecutive text nodes into unified post blocks
        val postBlocks = clusterNodesIntoPostBlocks(rawNodes)

        for (block in postBlocks) {
            val primaryText = block.primaryBody
            if (primaryText.length >= 18 && PostSanitizer.isValidPostContent(primaryText)) {
                val fullText = block.enrichedFullText
                val normalizedKey = primaryText.replace(Regex("\\s+"), " ").take(150)
                val cleanSnippet = primaryText.replace(Regex("\\s+"), " ").trim().take(100)
                val resolvedCategory = PostSanitizer.resolveCleanTag(primaryText, null)?.removePrefix("#") ?: "تعليمي"

                val post = ArchivedPost(
                    id = UUID.randomUUID().toString(),
                    text = primaryText,
                    fullText = fullText,
                    author = block.author,
                    authorAvatarUrl = null,
                    timestamp = block.timestamp,
                    groupName = "مجموعة دراسية",
                    category = resolvedCategory,
                    cleanSnippet = cleanSnippet,
                    isMediaRich = false,
                    scanDate = System.currentTimeMillis(),
                    normalizedKey = normalizedKey,
                    ocrConfidence = 1.0f,
                    isOcrCaptured = false
                )

                try {
                    postDao.insertPost(post)
                    Log.d(TAG, "Successfully archived post from '${block.author}' with ${block.comments.size} comments")
                } catch (e: Exception) {
                    Log.w(TAG, "Duplicate or constraint ignored on post insertion: ${e.message}")
                }
            }
        }
    }

    /**
     * Clusters consecutive text nodes into unified post blocks.
     * - Combines paragraphs split across multiple child TextViews.
     * - Attaches child/sibling comment nodes to the corresponding parent post.
     * - Discards interaction chrome (Like, Comment, Share buttons).
     */
    private fun clusterNodesIntoPostBlocks(nodes: List<ExtractedTextNode>): List<EnrichedPostBlock> {
        val blocks = mutableListOf<EnrichedPostBlock>()
        var currentBlock: EnrichedPostBlock? = null
        var lastAuthorCandidate: String? = null

        val uiChromeKeywords = setOf(
            "like", "comment", "share", "send", "reply", "see more", "write a comment",
            "إعجاب", "تعليق", "مشاركة", "إرسال", "رد", "عرض المزيد", "أكتب تعليقاً", "اكتب تعليقا"
        )

        for (node in nodes) {
            val clean = node.text.trim()
            val lower = clean.lowercase()

            // Skip interaction button chrome
            if (uiChromeKeywords.any { lower == it || lower.startsWith("$it ") }) {
                continue
            }

            // Check if this is a comment/reply
            if (node.isComment || clean.startsWith("💬")) {
                val commentText = clean.removePrefix("💬").trim()
                if (commentText.length >= 6) {
                    if (currentBlock != null) {
                        currentBlock.comments.add(commentText)
                    } else if (blocks.isNotEmpty()) {
                        blocks.last().comments.add(commentText)
                    }
                }
                continue
            }

            // Check if this node is an author candidate (short header preceding text)
            if (clean.length in 3..40 && !clean.contains("\n") && !PostSanitizer.isValidPostContent(clean)) {
                val sanitized = PostSanitizer.sanitizeAuthorName(clean)
                if (sanitized != "Unknown Member") {
                    lastAuthorCandidate = sanitized
                    continue
                }
            }

            // Check if this node is substantive post text
            if (clean.length >= 15) {
                if (currentBlock == null) {
                    currentBlock = EnrichedPostBlock(
                        author = lastAuthorCandidate ?: "عضو في المجموعة"
                    ).apply {
                        paragraphs.add(clean)
                    }
                    lastAuthorCandidate = null
                } else {
                    // Check if this is a continuation paragraph or a new post
                    if (lastAuthorCandidate != null) {
                        // A new author appeared -> finalize previous block and start new one
                        blocks.add(currentBlock)
                        currentBlock = EnrichedPostBlock(author = lastAuthorCandidate).apply {
                            paragraphs.add(clean)
                        }
                        lastAuthorCandidate = null
                    } else {
                        // Concatenate split TextView paragraph into the current post block
                        currentBlock.paragraphs.add(clean)
                    }
                }
            }
        }

        currentBlock?.let { blocks.add(it) }
        return blocks
    }

    /**
     * Recursively and safely extracts text and contentDescription from visible nodes.
     * Enforces depth bounds to prevent stack overflows, skips null/hidden views,
     * and tags comment/reply nodes based on view ID hierarchy and content markers.
     */
    private fun extractVisibleTextNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<ExtractedTextNode>,
        depth: Int
    ) {
        if (node == null || depth > 14 || out.size > 80) return

        try {
            val isVisible = try { node.isVisibleToUser } catch (e: Exception) { true }
            if (isVisible) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val viewId = try { node.viewIdResourceName?.lowercase() } catch (e: Exception) { null }

                val isCommentNode = viewId != null && (
                        viewId.contains("comment") ||
                                viewId.contains("reply") ||
                                viewId.contains("feedback") ||
                                viewId.contains("threaded")
                        )

                if (!text.isNullOrBlank()) {
                    if (text.length >= 15) {
                        out.add(ExtractedTextNode(text = text, viewId = viewId, isComment = isCommentNode))
                    } else if (isCommentNode && text.length >= 6) {
                        out.add(ExtractedTextNode(text = "💬 $text", viewId = viewId, isComment = true))
                    }
                } else if (!desc.isNullOrBlank() && desc.length >= 15) {
                    out.add(ExtractedTextNode(text = desc, viewId = viewId, isComment = isCommentNode))
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    extractVisibleTextNodes(child, out, depth + 1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Traversal exception at depth $depth: ${e.message}")
        }
    }
}
