package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.util.CategoryMatcher
import java.util.UUID

data class PageSearchResult(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val category: String,
    val sourceName: String,
    val sourceUrl: String? = null
)

data class SearchScreenResult(
    val sourceName: String,
    val items: List<PageSearchResult>,
    val totalRawTextsCount: Int,
    val errorMessage: String? = null
)

open class EnhancedSearchWithinPageService : AccessibilityService() {

    companion object {
        private const val TAG = "BoLiteAccessibility"
        var activeInstance: EnhancedSearchWithinPageService? = null

        fun isServiceEnabled(context: Context): Boolean {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            val pkg = context.packageName
            while (colonSplitter.hasNext()) {
                val component = colonSplitter.next()
                if (component.startsWith(pkg, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        Log.i(TAG, "EnhancedSearchWithinPageService connected successfully.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event listening
    }

    override fun onInterrupt() {
        Log.w(TAG, "EnhancedSearchWithinPageService interrupted.")
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        Log.i(TAG, "EnhancedSearchWithinPageService destroyed.")
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance == this) {
            activeInstance = null
        }
        Log.i(TAG, "EnhancedSearchWithinPageService unbound.")
        return super.onUnbind(intent)
    }

    /**
     * Dumps all accessible node texts found during a scan, recursively traverses child nodes,
     * inspects application windows, and filters text by the typed keyword.
     */
    fun searchVisibleScreen(keyword: String): SearchScreenResult {
        Log.d(TAG, "=== Starting Screen Scan (keyword: '$keyword') ===")

        val targetRoots = mutableListOf<AccessibilityNodeInfo>()
        var detectedPkg = ""

        // 1. Query all interactive windows (Android 5.0+) to locate the target application under our overlay
        try {
            val windowList = windows
            Log.d(TAG, "Detected window count: ${windowList?.size ?: 0}")

            if (!windowList.isNullOrEmpty()) {
                for (win in windowList) {
                    val root = win.root
                    if (root != null) {
                        val pkg = root.packageName?.toString() ?: ""
                        Log.d(TAG, "Window type=${win.type}, id=${win.id}, pkg=$pkg")

                        // Collect application windows (Facebook, WhatsApp, Chrome, etc.)
                        if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            targetRoots.add(root)
                            if (detectedPkg.isEmpty() && pkg.isNotEmpty()) {
                                detectedPkg = pkg
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying windows", e)
        }

        // 2. Fallback to rootInActiveWindow if no application windows found in windows list
        if (targetRoots.isEmpty()) {
            rootInActiveWindow?.let { root ->
                Log.d(TAG, "Using rootInActiveWindow as fallback: pkg=${root.packageName}")
                targetRoots.add(root)
                if (detectedPkg.isEmpty()) {
                    detectedPkg = root.packageName?.toString() ?: ""
                }
            }
        }

        // Diagnostic Check: No window available
        if (targetRoots.isEmpty()) {
            Log.e(TAG, "FAILURE: No accessible window found.")
            return SearchScreenResult(
                sourceName = "غير معروف",
                items = emptyList(),
                totalRawTextsCount = 0,
                errorMessage = "تعذر الوصول لنافذة التطبيق في الخلفية (تأكد من فتح فيسبوك أو واتساب)"
            )
        }

        val sourceName = detectSourceName(targetRoots.first(), detectedPkg)
        val rawTexts = mutableListOf<String>()
        val visitedNodesCount = IntArray(1) { 0 }

        // 3. Deep recursive traversal across all target root hierarchies (handles nested Facebook ViewGroups)
        for (root in targetRoots) {
            traverseNodeRecursively(root, rawTexts, visitedNodesCount, maxNodes = 1500)
        }

        Log.d(TAG, "=== Scan Finished: Visited ${visitedNodesCount[0]} nodes, Extracted ${rawTexts.size} raw texts ===")

        // Diagnostic Check: No text nodes detected in tree
        if (rawTexts.isEmpty()) {
            Log.w(TAG, "FAILURE: Zero text nodes extracted from screen hierarchy.")
            return SearchScreenResult(
                sourceName = sourceName,
                items = emptyList(),
                totalRawTextsCount = 0,
                errorMessage = "لم يتم التقاط أي نصوص من الشاشة (قد تكون الصفحة قيد التحميل أو تمنع القراءة)"
            )
        }

        // 4. Split raw texts into candidate chunks (paragraphs / lines / comments)
        val candidates = mutableListOf<String>()
        for (raw in rawTexts) {
            val lines = raw.split("\n", "\r\n").map { it.trim() }.filter { it.length >= 4 }
            if (lines.isEmpty()) {
                if (raw.length >= 4) candidates.add(raw)
            } else {
                candidates.addAll(lines)
            }
        }

        val cleanKeyword = keyword.trim().lowercase()
        val seen = mutableSetOf<String>()
        val matchedResults = mutableListOf<PageSearchResult>()

        for (candidate in candidates) {
            val clean = candidate.trim()
            if (clean.length < 4 || seen.contains(clean)) continue

            val isMatch = if (cleanKeyword.isEmpty()) {
                // If search query is empty, prioritize student inquiries or general page posts
                CategoryMatcher.isStudentRelevant(clean) || clean.length >= 20
            } else {
                clean.lowercase().contains(cleanKeyword)
            }

            if (isMatch) {
                seen.add(clean)
                val category = CategoryMatcher.match(clean)
                val sourceUrl = buildSourceUrl(clean)
                matchedResults.add(
                    PageSearchResult(
                        text = clean,
                        category = category,
                        sourceName = sourceName,
                        sourceUrl = sourceUrl
                    )
                )
            }
        }

        Log.d(TAG, "Matching candidate cards count: ${matchedResults.size} (for keyword '$cleanKeyword')")

        // Diagnostic Check: Texts found, but none matched keyword
        if (matchedResults.isEmpty() && cleanKeyword.isNotEmpty()) {
            return SearchScreenResult(
                sourceName = sourceName,
                items = emptyList(),
                totalRawTextsCount = rawTexts.size,
                errorMessage = "تم فحص الشاشة (${rawTexts.size} نصاً)، ولم يتم العثور على '$cleanKeyword'"
            )
        }

        return SearchScreenResult(
            sourceName = sourceName,
            items = matchedResults,
            totalRawTextsCount = rawTexts.size,
            errorMessage = null
        )
    }

    /**
     * Recursively traverses ALL child nodes. Does not stop when container is invisible,
     * ensuring deeply nested ViewGroups in Facebook/WhatsApp are fully traversed.
     */
    private fun traverseNodeRecursively(
        node: AccessibilityNodeInfo?,
        output: MutableList<String>,
        visitedCount: IntArray,
        maxNodes: Int
    ) {
        if (node == null || visitedCount[0] >= maxNodes) return
        visitedCount[0]++

        // Extract text and content description from current node
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank() && text.length >= 3) {
            if (!output.contains(text)) {
                output.add(text)
                Log.d(TAG, "DUMP [Text] (#${output.size}): $text")
            }
        }

        if (!desc.isNullOrBlank() && desc.length >= 3 && desc != text) {
            if (!output.contains(desc)) {
                output.add(desc)
                Log.d(TAG, "DUMP [Desc] (#${output.size}): $desc")
            }
        }

        // Recursively visit ALL children (crucial for Litho / React Native nested structures in Facebook)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (visitedCount[0] >= maxNodes) break
            try {
                val child = node.getChild(i)
                if (child != null) {
                    traverseNodeRecursively(child, output, visitedCount, maxNodes)
                }
            } catch (e: Exception) {
                // Ignore transient accessibility node recycling exceptions
            }
        }
    }

    private fun detectSourceName(rootNode: AccessibilityNodeInfo, packageName: String): String {
        val topTexts = mutableListOf<String>()

        fun findTopHeaders(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 5 || topTexts.size >= 6) return
            val txt = node.text?.toString()?.trim()
            if (!txt.isNullOrBlank() && txt.length in 4..50) {
                topTexts.add(txt)
            }
            for (i in 0 until node.childCount) {
                try {
                    findTopHeaders(node.getChild(i), depth + 1)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        findTopHeaders(rootNode, 0)

        val pageCandidate = topTexts.firstOrNull { candidate ->
            !candidate.contains("بحث") &&
            !candidate.contains("Search") &&
            !candidate.contains("Notifications") &&
            !candidate.contains("إشعارات")
        }

        return when {
            pageCandidate != null -> pageCandidate
            packageName.contains("facebook") -> "مجموعة فيسبوك"
            packageName.contains("whatsapp") -> "محادثة واتساب"
            else -> "فيسبوك / واتساب"
        }
    }

    private fun buildSourceUrl(text: String): String {
        val snippet = text.take(60).replace("\n", " ").trim()
        val encodedQuery = Uri.encode(snippet)
        return "https://www.facebook.com/search/posts/?q=$encodedQuery"
    }
}
