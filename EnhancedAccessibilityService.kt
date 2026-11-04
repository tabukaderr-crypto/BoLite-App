package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.example.data.PostComment
import com.example.ocr.ScreenOcr
import com.example.spatial.SpatialProximityAnalyzer
import com.example.util.CategoryMatcher
import com.example.util.FuzzySearchUtility
import com.example.util.TimeFilterUtility
import com.example.data.AppDatabase
import com.example.data.ArchivedPost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.notify.WatchNotifier
import com.example.util.PostInsights
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import androidx.annotation.RequiresApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.PixelCopy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import com.example.capture.HybridCaptureManager

data class DeepScanPostResult(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fullText: String = text,
    val author: String? = null,
    val timestamp: String? = null,
    val groupName: String,
    val category: String,
    val fuzzyScore: Double = 1.0,
    val matchedRanges: List<IntRange> = emptyList(),
    val sourceUrl: String? = null,
    val comments: List<PostComment> = emptyList(),
    val isWithin7Days: Boolean = true
)

data class DeepScanScreenResult(
    val groupName: String,
    val isMonitored: Boolean = true,
    val items: List<DeepScanPostResult>,
    val totalTraversedNodes: Int,
    val errorMessage: String? = null
)

open class EnhancedAccessibilityService : EnhancedSearchWithinPageService() {

    private val VERBOSE_LOGGING = true

    private fun diagLog(message: String, showToast: Boolean = false, shortToast: String? = null) {
        if (VERBOSE_LOGGING) {
            Log.d(TAG, message)
            if (showToast) {
                val text = shortToast ?: message
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BoLiteRadarService"
        const val MAX_SNAPSHOTS = 12
        const val TARGET_BATCH_SIZE = 5
        const val HARD_LIMIT_POSTS = 50
        var activeInstance: EnhancedAccessibilityService? = null

        // Global state flows for Group & Content Map
        val detectedGroupNameFlow = MutableStateFlow("مجموعة فيسبوك")
        val detectedPackageFlow = MutableStateFlow("")
        val isCurrentGroupMonitoredFlow = MutableStateFlow(true)
        val monitoredGroupsFlow = MutableStateFlow<Set<String>>(emptySet())
        val radarBannerFlow = MutableStateFlow<String?>(null)
        val latestContentMapFlow = MutableStateFlow<List<DeepScanPostResult>>(emptyList())

        // Indexing Progress State for Room DB persistence
        val indexingProgressFlow = MutableStateFlow(0)
        val isIndexingActiveFlow = MutableStateFlow(false)
        val lastIndexedCountFlow = MutableStateFlow(0)
        val indexingStatusTextFlow = MutableStateFlow("")
        val indexingRoundFlow = MutableStateFlow(0)

        // Exclusive Facebook Group Detection States
        val isCurrentScreenFacebookGroupFlow = MutableStateFlow(false)
        val groupScreenMessageFlow = MutableStateFlow<String?>("يرجى فتح مجموعة فيسبوك أولاً")

        // Diagnostic Overlay StateFlow for runtime manual inspection
        val diagnosticReportFlow = MutableStateFlow<String?>(null)

        /**
         * Builds and returns the active target app root node (e.g. Facebook feed).
         */
        fun buildScreenTree(): AccessibilityNodeInfo? {
            val service = activeInstance ?: return null
            return service.findTargetAppRoot() ?: service.rootInActiveWindow
        }

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

        /**
         * Hard reset: clears all cached posts in the service memory to prevent data repetition.
         */
        fun clearAllCachedPosts() {
            activeInstance?.clearAllCachedPosts()
        }

        /**
         * UI Entry point for checkAndFixCapturePermission:
         * Checks if MediaProjection permission is granted.
         * If NOT granted, returns "PERMISSION_DENIED".
         * If GRANTED, takes ONE screenshot using PixelCopy, runs ML Kit OCR on it immediately,
         * logs Log.d("RESULT", text), and returns the raw extracted text directly to UI without saving to DB.
         */
        suspend fun checkAndFixCapturePermission(): String {
            val service = activeInstance
            return service?.checkAndFixCapturePermission() ?: run {
                val isGranted = HybridCaptureManager.mediaProjectionResultCode != 0 && HybridCaptureManager.mediaProjectionData != null
                if (!isGranted) "PERMISSION_DENIED" else "SERVICE_NOT_CONNECTED"
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ocrNote: String = ""
    private val shotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val smartPostReader by lazy {
        com.example.ocr.SmartPostReader(ocrEngine = com.example.ocr.HybridPostOcrEngine(applicationContext))
    }

    /**
     * Checks if MediaProjection permission is granted.
     * If NOT granted, returns "PERMISSION_DENIED".
     * If GRANTED, takes ONE screenshot of the current screen using PixelCopy,
     * runs ML Kit OCR on it immediately, and Log.d("RESULT", text).
     * Returns the extracted text string directly to the UI without saving to DB yet.
     */
    suspend fun checkAndFixCapturePermission(): String = withContext(Dispatchers.Default) {
        // 1. Check if MediaProjection permission is granted
        val resultCode = HybridCaptureManager.mediaProjectionResultCode
        val data = HybridCaptureManager.mediaProjectionData

        if (resultCode == 0 || data == null) {
            Log.w("RESULT", "MediaProjection permission check failed: PERMISSION_DENIED")
            return@withContext "PERMISSION_DENIED"
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            Log.e("RESULT", "MediaProjectionManager service not available")
            return@withContext "PERMISSION_DENIED"
        }

        val projection: MediaProjection = try {
            projectionManager.getMediaProjection(resultCode, data.clone() as Intent)
        } catch (e: Exception) {
            Log.e("RESULT", "Failed to obtain MediaProjection: ${e.message}", e)
            return@withContext "PERMISSION_DENIED"
        } ?: return@withContext "PERMISSION_DENIED"

        val metrics = resources.displayMetrics
        val width = maxOf(metrics.widthPixels, 480)
        val height = maxOf(metrics.heightPixels, 800)
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val virtualDisplay = projection.createVirtualDisplay(
            "PixelCopyCaptureDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        // Wait briefly for the frame to be drawn onto the virtual surface
        delay(150)

        // 3. Take ONE screenshot of the current screen using PixelCopy
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val mainThreadHandler = Handler(Looper.getMainLooper())

        val pixelCopySuccess: Boolean = suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    PixelCopy.request(
                        imageReader.surface,
                        bitmap,
                        { copyResult ->
                            continuation.resume(copyResult == PixelCopy.SUCCESS)
                        },
                        mainThreadHandler
                    )
                } catch (t: Throwable) {
                    Log.e("RESULT", "PixelCopy request error: ${t.message}", t)
                    continuation.resume(false)
                }
            } else {
                continuation.resume(false)
            }
        }

        val finalBitmap: Bitmap = if (pixelCopySuccess) {
            bitmap
        } else {
            // Buffer fallback if PixelCopy on ImageReader Surface is unsupported on host
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    if (rowPadding > 0) {
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                        bmp.recycle()
                        cropped
                    } else {
                        bmp
                    }
                } finally {
                    image.close()
                }
            } else {
                bitmap
            }
        }

        virtualDisplay?.release()
        imageReader.close()
        projection.stop()

        // Run ML Kit OCR on it immediately
        val inputImage = InputImage.fromBitmap(finalBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val rawText: String = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    // Log.d("RESULT", text)
                    Log.d("RESULT", text)
                    continuation.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e("RESULT", "ML Kit OCR failed: ${e.message}", e)
                    continuation.resume("OCR_FAILED: ${e.message}")
                }
        }

        try { finalBitmap.recycle() } catch (e: Exception) {}

        // 4. Return extracted text string directly to the UI without saving to DB yet
        rawText
    }

    /**
     * ZERO MEMORY RESET: Wipes all cached post state before starting a new scan.
     */
    fun clearAllCachedPosts() {
        latestContentMapFlow.value = emptyList()
        Log.i(TAG, "[ZERO MEMORY RESET] Cleared all memory for fresh snapshot scan.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        EnhancedSearchWithinPageService.activeInstance = this
        Log.i(TAG, "EnhancedAccessibilityService connected for Auto-Scan Content Map.")
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance == this) {
            activeInstance = null
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        super.onAccessibilityEvent(event)
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""
        if (isSocialOrMessagingApp(pkg)) {
            inspectScreenForGroupRadar(pkg)
        }
    }

    private fun isSocialOrMessagingApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("facebook") ||
            lower.contains("katana") ||
            lower.contains("whatsapp") ||
            lower.contains("telegram") ||
            lower.contains("orca")
    }

    /**
     * Inspects active window hierarchy to automatically detect Facebook groups.
     * EXCLUSIVE GROUP MODE: Validates if the current screen is genuinely a Facebook GROUP.
     * Pages, Feeds, and Marketplace are ignored.
     */
    private fun inspectScreenForGroupRadar(pkg: String) {
        val root = rootInActiveWindow ?: return
        val isGroup = checkIfFacebookGroup(root, pkg)
        isCurrentScreenFacebookGroupFlow.value = isGroup

        if (!isGroup) {
            groupScreenMessageFlow.value = "يرجى فتح مجموعة فيسبوك أولاً"
            return
        }

        groupScreenMessageFlow.value = null
        val detectedGroup = extractGroupOrPageName(root, pkg)

        if (detectedGroup.isNotBlank() && detectedGroup != "فيسبوك / واتساب") {
            if (detectedGroupNameFlow.value != detectedGroup) {
                // User switched groups! Clear old cache immediately
                clearAllCachedPosts()
                Log.i(TAG, "[GROUP SWITCH] Switched to new group: '$detectedGroup'. Cleared stale memory.")
            }
            detectedGroupNameFlow.value = detectedGroup
            detectedPackageFlow.value = pkg
            isCurrentGroupMonitoredFlow.value = true
        }
    }

    /**
     * EXCLUSIVE GROUP MODE CHECK:
     * Detects if the current screen hierarchy is a Facebook GROUP.
     * Searches for group indicators: URL containing /groups/, "عضو", "انضمام", "مجموعة", "جروب",
     * "Member", "Join Group", "Joined".
     * Rejects Pages, Feeds, and Marketplace entirely.
     */
    fun checkIfFacebookGroup(rootNode: AccessibilityNodeInfo?, pkg: String): Boolean {
        if (rootNode == null) return false
        val pkgLower = pkg.lowercase()
        val isFacebook = pkgLower.contains("facebook") || pkgLower.contains("katana") || pkgLower.contains("lite")

        var hasGroupKeyword = false
        var hasNonGroupDisqualifier = false

        fun scanForGroupMarkers(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 10 || (hasGroupKeyword && hasNonGroupDisqualifier)) return

            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase() ?: ""
            if (text.isNotBlank()) {
                if (text.contains("/groups/") ||
                    text.contains("join group") || text.contains("joined") ||
                    text.contains("member") || text.contains("members") ||
                    text.contains("public group") || text.contains("private group") ||
                    text.contains("community") || text.contains("انضمام") ||
                    text.contains("انضممت") || text.contains("عضو") ||
                    text.contains("أعضاء") || text.contains("مجموعة عامة") ||
                    text.contains("مجموعة خاصة") || text.contains("مجموعة") ||
                    text.contains("جروب") || text.contains("المجموعة")
                ) {
                    hasGroupKeyword = true
                }

                // Disqualifiers: Explicit Marketplace, Video Reels, or Page likes without group context
                if (text.contains("marketplace") || text.contains("سوق فيسبوك") ||
                    text.contains("عناصر للبيع") || text.contains("watch video") ||
                    text.contains("reels") || text.contains("ريلز")
                ) {
                    hasNonGroupDisqualifier = true
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    scanForGroupMarkers(node.getChild(i), depth + 1)
                } catch (e: Exception) {
                    // Ignore transient exceptions
                }
            }
        }

        scanForGroupMarkers(rootNode, 0)
        return hasGroupKeyword && !hasNonGroupDisqualifier
    }

    fun startMonitoringGroup(groupName: String) {
        // Kept for backward compatibility; auto-monitoring is active by default
        isCurrentGroupMonitoredFlow.value = true
    }

    fun stopMonitoringGroup(groupName: String) {
        // Kept for backward compatibility
    }

    fun isGroupMonitored(groupName: String): Boolean = true

    /**
     * Extracts group or page title accurately from Facebook or WhatsApp screen headers
     */
    private fun extractGroupOrPageName(rootNode: AccessibilityNodeInfo, packageName: String): String {
        val headerCandidates = mutableListOf<String>()

        fun findTopHeaderNodes(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 8 || headerCandidates.size >= 12) return

            val text = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!text.isNullOrBlank() && text.length in 3..70) {
                val lower = text.lowercase()
                val isNavigational = lower.contains("search") || lower.contains("بحث") ||
                    lower.contains("notifications") || lower.contains("إشعارات") ||
                    lower.contains("home") || lower.contains("الرئيسية") ||
                    lower.contains("menu") || lower.contains("قائمة") ||
                    lower.contains("chat") || lower.contains("محادثة") ||
                    lower.contains("type a message") || lower.contains("اكتب رسالة") ||
                    lower.contains("create post") || lower.contains("إنشاء منشور") ||
                    lower.contains("what's on your mind") || lower.contains("بم تفكر")

                if (!isNavigational) {
                    headerCandidates.add(text)
                }
            }

            for (i in 0 until node.childCount) {
                try {
                    findTopHeaderNodes(node.getChild(i), depth + 1)
                } catch (e: Exception) {
                    // Ignore transient exceptions
                }
            }
        }

        findTopHeaderNodes(rootNode, 0)

        // Select the most prominent header candidate
        val bestCandidate = headerCandidates.firstOrNull { candidate ->
            candidate.length in 3..60 && !candidate.contains("\n") && !candidate.all { it.isDigit() }
        }

        return when {
            bestCandidate != null -> bestCandidate
            packageName.contains("facebook") -> "جروب فيسبوك"
            packageName.contains("whatsapp") -> "مجموعة واتساب"
            else -> "صفحة غير معروفة"
        }
    }

    /**
     * Deep Scan: Performs full recursive text traversal of all visible posts in the group feed,
     * builds the complete "Content Map", and optionally matches against query.
     * Enforces:
     * - Hard Reset: Clears cached memory completely before starting.
     * - Strict Scope: Targets EXCLUSIVELY the active window.
     * - Precision Scanning: Filters out short navigation labels, ad banners, and extracts high-value posts.
     */
    fun performDeepScan(rawQuery: String = "", forcedGroup: String? = null): DeepScanScreenResult {
        val query = rawQuery.trim()

        // FIX 1: HARD RESET BEFORE EVERY SCAN
        clearAllCachedPosts()

        var detectedPkg = detectedPackageFlow.value

        // FIX 3: STRICT SCOPE ENFORCEMENT - Active window only
        val activeRoot: AccessibilityNodeInfo? = rootInActiveWindow
            ?: windows?.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root

        if (activeRoot == null) {
            Log.w(TAG, "[STRICT SCOPE] Active window root is null.")
            return DeepScanScreenResult(
                groupName = forcedGroup ?: detectedGroupNameFlow.value,
                isMonitored = true,
                items = emptyList(),
                totalTraversedNodes = 0,
                errorMessage = "تعذر الوصول لشاشة التطبيق (تأكد من فتح تطبيق فيسبوك أو واتساب)"
            )
        }

        val rootPkg = activeRoot.packageName?.toString() ?: ""
        if (rootPkg.isNotEmpty()) {
            detectedPkg = rootPkg
            detectedPackageFlow.value = rootPkg
        }

        val detectedGroupName = forcedGroup ?: extractGroupOrPageName(activeRoot, detectedPkg)
        detectedGroupNameFlow.value = detectedGroupName
        isCurrentGroupMonitoredFlow.value = true

        // STEP 1: Strict Hierarchical Accessibility Tree Extraction
        val treePosts = readVisiblePostsFromTree(detectedGroupName)
        var parsedBlocks = treePosts.map { post ->
            com.example.util.ParsedPostBlock(
                id = post.id,
                text = post.postContent,
                fullText = post.postContent,
                author = post.authorName,
                timestamp = post.timestampRaw,
                category = post.categoryTag ?: "عام",
                isWithin7Days = true
            )
        }

        // Posts are extracted strictly via SmartPostReader Visual OCR (no AccessibilityNodeInfo.text)

        val mapResults = mutableListOf<DeepScanPostResult>()
        val seenNormalizedTexts = mutableSetOf<String>()

        for (block in parsedBlocks) {
            val clean = block.text.trim()
            if (clean.length < 20 || !com.example.util.PostSanitizer.isValidPostContent(clean)) continue

            // Requirement 3: DUPLICATE PREVENTION (Unique Keys)
            val normalizedKey = clean.replace("\\s+".toRegex(), " ")
            if (seenNormalizedTexts.contains(normalizedKey)) {
                // If duplicate found -> Skip it silently. No repeated posts allowed.
                continue
            }

            val fuzzyMatch = if (query.isEmpty()) {
                com.example.util.FuzzyMatchResult(isMatch = true, score = 1.0)
            } else {
                val bodyMatch = FuzzySearchUtility.matchFuzzy(clean, query)
                val authorMatch = block.author?.let { FuzzySearchUtility.matchFuzzy(it, query) }
                if (bodyMatch.isMatch || authorMatch?.isMatch == true) {
                    val score = maxOf(bodyMatch.score, authorMatch?.score ?: 0.0)
                    com.example.util.FuzzyMatchResult(isMatch = true, score = score, matchedRanges = bodyMatch.matchedRanges)
                } else {
                    com.example.util.FuzzyMatchResult(isMatch = false, score = 0.0)
                }
            }

            if (fuzzyMatch.isMatch) {
                seenNormalizedTexts.add(normalizedKey)
                mapResults.add(
                    DeepScanPostResult(
                        id = block.id,
                        text = block.text,
                        fullText = block.fullText,
                        author = block.author,
                        timestamp = block.timestamp,
                        groupName = detectedGroupName,
                        category = block.category,
                        fuzzyScore = fuzzyMatch.score,
                        matchedRanges = fuzzyMatch.matchedRanges,
                        sourceUrl = block.sourceUrl,
                        comments = block.comments,
                        isWithin7Days = block.isWithin7Days
                    )
                )
            }
        }

        val sortedResults = if (query.isNotEmpty()) {
            mapResults.sortedByDescending { it.fuzzyScore }
        } else {
            mapResults
        }

        latestContentMapFlow.value = sortedResults
        Log.i(TAG, "[PRECISION SCAN COMPLETE] Found ${sortedResults.size} fresh posts for group: $detectedGroupName")

        val errorMsg = if (sortedResults.isEmpty() && query.isNotEmpty()) {
            "لم يتم العثور على منشورات مطابقة لـ '$query' في هذه المجموعة"
        } else null

        return DeepScanScreenResult(
            groupName = detectedGroupName,
            isMonitored = true,
            items = sortedResults,
            totalTraversedNodes = parsedBlocks.size,
            errorMessage = errorMsg
        )
    }

    /**
     * Fallback step: Takes a screenshot via AccessibilityService API (Android 11+)
     * when the accessibility node tree returns empty content.
     */
    fun takeScreenshotSync(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hwBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            resultBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot hardware buffer", e)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Accessibility takeScreenshot failed with error code: $errorCode")
                        latch.countDown()
                    }
                }
            )
            latch.await(2000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in takeScreenshotSync", e)
        }
        return resultBitmap
    }

    /**
     * Reads visible text nodes of the active window using DOM-like traversal,
     * splits into logical blocks (posts/comments/captions), scores relevance, and returns categorized posts.
     */
    fun parseLiveScreen(): Pair<String, List<com.example.util.ParsedPostBlock>> {
        val root = rootInActiveWindow ?: return Pair("صفحة عامة", emptyList())
        return try {
            com.example.util.PageParserUtility.parseScreen(root)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair("صفحة عامة", emptyList())
        }
    }

    private fun extractAuthorAndTimestamp(text: String): Pair<String?, String?> {
        val lines = text.split("\n", "•", "·", "|").map { it.trim() }.filter { it.isNotBlank() }
        var author: String? = null
        var timestamp: String? = null

        val timeKeywords = listOf(
            "منذ", "ساعة", "ساعات", "دقيقة", "دقائق", "ثانية", "ثواني",
            "أمس", "yesterday", "now", "الآن", "يوم", "أيام", "اسبوع", "أسبوع", "شهر",
            "hrs", "mins", "just now", "1h", "2h", "3h", "1d", "2d", "1w"
        )
        for (line in lines.take(4)) {
            val lower = line.lowercase()
            if (timestamp == null && (timeKeywords.any { lower.contains(it) } || line.matches(".*\\d{1,2}:\\d{2}.*".toRegex()))) {
                timestamp = line
            } else if (author == null && line.length in 3..40 && !line.startsWith("#") && !line.startsWith("http")) {
                // Ignore general UI labels
                if (!lower.contains("like") && !lower.contains("comment") && !lower.contains("share") &&
                    !lower.contains("إعجاب") && !lower.contains("تعليق") && !lower.contains("مشاركة")) {
                    author = line
                }
            }
        }
        return Pair(author, timestamp)
    }

    private fun extractChildComments(text: String): List<PostComment> {
        val comments = mutableListOf<PostComment>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        for (line in lines) {
            val lower = line.lowercase()
            if (lower.startsWith("تعليق:") || lower.startsWith("رد:") || lower.startsWith("comment:")) {
                val body = line.substringAfter(":").trim()
                if (body.length >= 3) {
                    comments.add(PostComment(author = "عضو في المجموعة", text = body))
                }
            } else if (line.contains(":") && !line.startsWith("http") && line.length in 8..150) {
                val author = line.substringBefore(":").trim()
                val body = line.substringAfter(":").trim()
                if (author.length in 3..25 && body.length >= 3 && !author.contains(" ")) {
                    comments.add(PostComment(author = author, text = body))
                }
            }
        }
        return comments
    }

    /** Package names of every window the service can currently see (for diagnostics). */
    private fun windowsSummary(): String = try {
        val list = windows.orEmpty().map { w ->
            (w.root?.packageName?.toString() ?: "؟").substringAfterLast('.')
        }
        if (list.isEmpty()) "لا توجد نوافذ (الخدمة لا ترى أي نافذة)" else list.joinToString(" , ")
    } catch (e: Exception) { "تعذّر قراءة النوافذ" }

    /** Short report of what the service can actually see (shown only when nothing was found). */
    private fun diagnose(root: AccessibilityNodeInfo): String {
        var nodes = 0
        var texts = 0
        val samples = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || nodes > 4000) return
            nodes++
            val t = (n.text?.toString() ?: n.contentDescription?.toString())?.trim()
            if (!t.isNullOrBlank()) {
                texts++
                if (samples.size < 8 && t.length >= 8) samples.add(t.take(35).replace("\n", " "))
            }
            for (i in 0 until n.childCount) {
                try { walk(n.getChild(i), depth + 1) } catch (e: Exception) { /* ignore */ }
            }
        }
        walk(root, 0)
        val winCount = try { windows.orEmpty().size } catch (e: Exception) { -1 }
        return "التطبيق=${root.packageName} | نوافذ=$winCount [${windowsSummary()}] | عقد=$nodes | عقد فيها نص=$texts" +
            if (samples.isNotEmpty()) " | أمثلة: " + samples.joinToString(" / ") else ""
    }

    /**
     * Finds the Facebook/WhatsApp window to read.
     * IMPORTANT: when the Bo Lite panel is open it becomes the "active window",
     * so rootInActiveWindow would return OUR OWN panel instead of Facebook.
     * We therefore look through all windows and skip our own app.
     */
    internal fun findTargetAppRoot(): AccessibilityNodeInfo? {
        val ownPkg = packageName
        try {
            for (w in windows.orEmpty()) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p != ownPkg && isSocialOrMessagingApp(p)) return r
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate windows: ${e.message}")
        }
        // General mode: any other app on screen (not us, not the system bar / launcher)
        try {
            for (w in windows.orEmpty()) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p != ownPkg && p != "com.android.systemui" && !p.contains("launcher")) return r
            }
        } catch (e: Exception) {
            Log.w(TAG, "General window search failed: ${e.message}")
        }
        val active = rootInActiveWindow
        return if (active != null && active.packageName?.toString() != ownPkg) active else null
    }

    internal fun captureScreenshotSync(): android.graphics.Bitmap? = captureScreenshot()

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun findLargestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val rect = android.graphics.Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (n.isScrollable) {
                n.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) {
                try { walk(n.getChild(i), depth + 1) } catch (e: Exception) { /* ignore */ }
            }
        }
        walk(root, 0)
        return best
    }

    /** Scrolls the feed one page down. Returns false if nothing could be scrolled. */
    private fun scrollFeedForward(): Boolean {
        val root = findTargetAppRoot() ?: return false
        val target = findLargestScrollable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** Takes a screenshot through the accessibility service (Android 11+). Blocking. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(): android.graphics.Bitmap? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: android.graphics.Bitmap? = null
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                shotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val hw = android.graphics.Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            result = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            buffer.close()
                        } catch (e: Exception) {
                            ocrNote = "تعذّر تحويل لقطة الشاشة: ${e.javaClass.simpleName}"
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        ocrNote = "فشل التقاط الشاشة (رمز $errorCode)"
                        latch.countDown()
                    }
                }
            )
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            ocrNote = "تعذّر التقاط الشاشة: ${e.javaClass.simpleName}"
        }
        return result
    }

    /** Swipes up with a finger gesture (used when the feed does not expose a scroll action). */
    private fun swipeUpGesture(): Boolean {
        return try {
            val dm = resources.displayMetrics
            val path = android.graphics.Path().apply {
                moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.75f)
                lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.30f)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads the screen as a PICTURE: hides our panel, takes a screenshot, runs Arabic/English OCR,
     * groups the lines into posts, scrolls and repeats. Works even when the app hides its text
     * from accessibility. Blocking and slow (several seconds per screen): run off the main thread.
     */
    /**
     * Extracts a fast, lightweight signature representing the visible text nodes on screen
     * to detect whether the Facebook feed has lazy-loaded new posts after a scroll.
     */
    private fun extractScreenContentSignature(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = java.lang.StringBuilder()
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12) return
            val txt = node.text?.toString()?.trim()
            if (!txt.isNullOrBlank() && txt.length > 8) {
                sb.append(txt.take(30)).append('|')
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }
        }
        try {
            traverse(root, 0)
        } catch (e: Exception) {
            // ignore traversal exceptions
        }
        return sb.toString()
    }

    /**
     * Dynamic wait check: Polls the accessibility tree every 500ms for up to [maxWaitMs] (default 3000ms)
     * until new text nodes appear or content changes significantly compared to [previousSignature].
     * If no new content loads within [maxWaitMs], returns false so the caller can break gracefully.
     */
    private fun waitForContentChange(
        previousSignature: String,
        maxWaitMs: Long = 3000L,
        pollIntervalMs: Long = 500L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                return false
            }
            val currentSig = extractScreenContentSignature()
            if (currentSig.isNotEmpty() && currentSig != previousSignature) {
                // Stabilize newly rendered DOM elements
                try { Thread.sleep(300) } catch (e: InterruptedException) {}
                return true
            }
        }
        return false
    }

    /**
     * Reads the screen as a PICTURE: hides our panel, takes a screenshot, runs Arabic/English OCR,
     * groups the lines into posts, scrolls and repeats. Works even when the app hides its text
     * from accessibility. Blocking and slow (several seconds per screen): run off the main thread.
     * All recognized posts are saved immediately to Room DB.
     */
    private fun performOcrScan(
        groupName: String,
        maxRounds: Int = 10,
        retryMultiplier: Long = 1L
    ): List<DeepScanPostResult> {
        ocrNote = ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ocrNote = "التقاط الشاشة يحتاج أندرويد 11 أو أحدث"
            return emptyList()
        }
        toast("لا يوجد نص مباشر، سأقرأ الشاشة بالصور (قد يستغرق دقيقة)")
        val results = LinkedHashMap<String, DeepScanPostResult>()
        val postDao = try { AppDatabase.getDatabase(applicationContext).postDao() } catch (e: Exception) { null }
        com.example.FloatingBubbleService.setPanelVisible(false)
        try {
            // Mandatory initial delay for feed rendering
            Thread.sleep(2000L * retryMultiplier)
            var round = 0
            var emptyRounds = 0
            while (round < maxRounds) {
                val shot = captureScreenshot()
                if (shot == null) {
                    if (ocrNote.isEmpty()) ocrNote = "لم يتم الحصول على صورة للشاشة"
                    break
                }
                val lines = try {
                    com.example.ocr.ScreenOcr.recognize(applicationContext, shot)
                } catch (t: Throwable) {
                    ocrNote = "خطأ في محرك القراءة: ${t.javaClass.simpleName}"
                    emptyList()
                } finally {
                    shot.recycle()
                }
                var added = 0
                val newlyFoundArchived = mutableListOf<ArchivedPost>()
                for (text in com.example.ocr.ScreenOcr.groupLinesIntoPosts(lines)) {
                    val clean = text.trim()
                    if (!com.example.util.PostSanitizer.isValidPostContent(clean)) continue
                    val key = clean.replace(Regex("\\s+"), " ").take(150)
                    if (!results.containsKey(key)) {
                        val postRes = DeepScanPostResult(
                            text = clean,
                            groupName = groupName,
                            category = CategoryMatcher.match(clean)
                        )
                        results[key] = postRes
                        newlyFoundArchived.add(
                            ArchivedPost(
                                id = postRes.id,
                                text = clean,
                                fullText = clean,
                                author = com.example.util.PostSanitizer.sanitizeAuthorName(postRes.author),
                                timestamp = postRes.timestamp,
                                groupName = groupName,
                                category = postRes.category,
                                scanDate = System.currentTimeMillis(),
                                normalizedKey = key,
                                ocrConfidence = 0.90f,
                                isOcrCaptured = true
                            )
                        )
                        added++
                    }
                }

                // Immediately persist OCR discoveries into Room Database
                if (newlyFoundArchived.isNotEmpty() && postDao != null) {
                    runBlocking {
                        try {
                            postDao.insertAll(newlyFoundArchived)
                            indexingProgressFlow.value += newlyFoundArchived.size
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving OCR posts to Room DB", e)
                        }
                    }
                }

                if (lines.isEmpty() && ocrNote.isEmpty()) ocrNote = "قرأ الصورة لكن لم يجد نصاً فيها"
                if (added == 0) emptyRounds++ else emptyRounds = 0
                if (emptyRounds >= 2) break
                if (!(scrollFeedForward() || swipeUpGesture())) break
                round++
                try { Thread.sleep(1500L * retryMultiplier) } catch (e: InterruptedException) { break }
            }
        } catch (e: InterruptedException) {
            // stopped
        } finally {
            com.example.FloatingBubbleService.setPanelVisible(true)
        }
        return results.values.toList()
    }

    /**
     * Reads visible posts directly via SCREENSHOT CAPTURE + ML KIT OCR (SmartPostReader).
     * Strictly avoids AccessibilityNodeInfo.text parsing to guarantee zero garbage characters.
     */
    fun readVisiblePostsFromTree(targetGroupName: String): List<ArchivedPost> {
        val activeRoot: AccessibilityNodeInfo? = rootInActiveWindow
            ?: windows?.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: findTargetAppRoot()

        val pkg = activeRoot?.packageName?.toString() ?: ""
        if (pkg.isNotEmpty()) detectedPackageFlow.value = pkg

        val detectedGroup = if (activeRoot != null) extractGroupOrPageName(activeRoot, pkg) else ""
        if (detectedGroup.isNotBlank() && detectedGroup != "مجموعة فيسبوك") {
            detectedGroupNameFlow.value = detectedGroup
        }

        // VISUAL OCR ONLY: Zero reliance on AccessibilityNodeInfo.text for content!
        val shot = captureScreenshot() ?: return emptyList()
        val parsedPosts = try {
            runBlocking(Dispatchers.Default) {
                smartPostReader.readVisiblePosts(shot)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing smartPostReader OCR in readVisiblePostsFromTree", t)
            emptyList()
        } finally {
            try { shot.recycle() } catch (e: Exception) {}
        }

        val results = mutableListOf<ArchivedPost>()
        for (item in parsedPosts) {
            val clean = item.content.trim()
            val conf = item.confidenceScore
            if (clean.length >= 20 && conf >= 0.50f && com.example.util.PostSanitizer.isValidPostContent(clean)) {
                val normalized = clean.replace("\\s+".toRegex(), " ").take(150)
                val cleanAuthor = com.example.util.PostSanitizer.sanitizeAuthorName(item.author)
                val insight = try { PostInsights.analyze(clean) } catch (t: Throwable) { null }
                val categoryOrSummary = insight?.summary()?.ifBlank { null }
                    ?: try { CategoryMatcher.match(clean) } catch (t: Throwable) { "عام" }

                results.add(
                    ArchivedPost(
                        id = "ocr_${System.currentTimeMillis()}_${Math.abs(clean.hashCode())}",
                        text = clean,
                        fullText = clean,
                        author = cleanAuthor,
                        authorAvatarUrl = null,
                        timestamp = null,
                        groupName = targetGroupName.ifBlank { detectedGroupNameFlow.value },
                        category = categoryOrSummary,
                        cleanSnippet = clean.replace(Regex("\\s+"), " ").take(100),
                        isMediaRich = false,
                        scanDate = System.currentTimeMillis(),
                        normalizedKey = normalized,
                        ocrConfidence = conf,
                        isOcrCaptured = true
                    )
                )
            }
        }
        return results
    }

    /**
     * Captures a single OCR frame via SmartPostReader.
     */
    private fun takeSingleOcrSnapshot(groupName: String): List<ArchivedPost> {
        return readVisiblePostsFromTree(groupName)
    }

    /**
     * SMART BATCH SCROLL ENGINE ("FAST-SCAN PRO MODE"):
     * 1. Scrolls continuously until TARGET_BATCH_SIZE (5) distinct posts are detected.
     * 2. Uses PostParser view hierarchy dumping with UI chrome exclusion (Like, Comment, Share).
     * 3. Non-blocking Room DB batch writes.
     * 4. Hard limit: Automatically terminates after collecting 50 valid posts OR reaching feed bottom.
     */
    fun performFastSnapshotScan(
        maxSnapshots: Int = MAX_SNAPSHOTS,
        onProgress: ((count: Int, snapshot: Int, maxSnapshots: Int) -> Unit)? = null
    ): Int {
        isIndexingActiveFlow.value = true
        com.example.FloatingBubbleService.setPanelVisible(false)
        val targetGroupName = detectedGroupNameFlow.value
        val db = try { AppDatabase.getDatabase(applicationContext) } catch (e: Exception) { null }
        val postDao = db?.postDao()

        var totalSaved = 0
        var currentDbCount = 0

        val seenKeys = mutableSetOf<String>()
        if (postDao != null) {
            runBlocking(Dispatchers.IO) {
                try {
                    seenKeys.addAll(postDao.getExistingKeysForGroup(targetGroupName))
                    currentDbCount = postDao.getPostsSnapshotByGroup(targetGroupName).size
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching existing keys from Room DB", e)
                }
            }
        }

        var consecutiveEmptyBatches = 0

        try {
            for (batchIndex in 1..maxSnapshots) {
                indexingRoundFlow.value = batchIndex

                // Smart Batch Accumulation: Scroll continuously until 5 distinct posts are detected
                val batchPostsToInsert = mutableListOf<ArchivedPost>()
                var subScrollAttempts = 0

                while (batchPostsToInsert.size < TARGET_BATCH_SIZE && subScrollAttempts < 3 && (totalSaved + batchPostsToInsert.size) < HARD_LIMIT_POSTS) {
                    val candidatePosts = readVisiblePostsFromTree(targetGroupName)

                    for (item in candidatePosts) {
                        val key = item.normalizedKey
                        if (key.length >= 15 && seenKeys.add(key)) {
                            batchPostsToInsert.add(item)
                            if (totalSaved + batchPostsToInsert.size >= HARD_LIMIT_POSTS) break
                        }
                    }

                    if (batchPostsToInsert.size >= TARGET_BATCH_SIZE || totalSaved + batchPostsToInsert.size >= HARD_LIMIT_POSTS) {
                        break
                    }

                    // Micro-swipe to accumulate more posts into the 5-post batch
                    val swiped = swipeUpGesture() || scrollFeedForward()
                    subScrollAttempts++
                    if (!swiped) break
                    try { Thread.sleep(250L) } catch (e: InterruptedException) { break }
                }

                // Batch persist posts into Room Database
                var newlyAddedInBatch = 0
                if (batchPostsToInsert.isNotEmpty() && postDao != null) {
                    runBlocking(Dispatchers.IO) {
                        try {
                            val insertResults = postDao.insertIgnore(batchPostsToInsert)
                            newlyAddedInBatch = insertResults.count { it != -1L }
                            totalSaved += newlyAddedInBatch
                            currentDbCount = postDao.getPostsSnapshotByGroup(targetGroupName).size
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting batch posts to Room DB", e)
                        }
                    }

                    // Notify WatchList matches
                    try {
                        WatchNotifier.notifyMatches(
                            applicationContext,
                            batchPostsToInsert.map { it.text },
                            detectedGroupNameFlow.value
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking watch notifications", e)
                    }

                    try { Thread.sleep(80L) } catch (e: InterruptedException) { break }
                }

                val statusText = "فحص برو: دفعة $batchIndex | تم حفظ $totalSaved/${HARD_LIMIT_POSTS} منشور"
                toast(statusText)
                indexingStatusTextFlow.value = statusText
                indexingProgressFlow.value = currentDbCount
                lastIndexedCountFlow.value = currentDbCount
                onProgress?.invoke(totalSaved, batchIndex, maxSnapshots)

                // Hard Limit Check: Stop automatically when reaching 50 posts
                if (totalSaved >= HARD_LIMIT_POSTS) {
                    diagLog("[FAST-SCAN PRO] Reached hard limit of $HARD_LIMIT_POSTS posts. Stopping scan.")
                    break
                }

                // Check for reaching bottom of feed
                if (newlyAddedInBatch == 0) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= 2) {
                        diagLog("[FAST-SCAN PRO] Reached bottom of feed. Stopping early.")
                        break
                    }
                } else {
                    consecutiveEmptyBatches = 0
                }

                if (batchIndex == maxSnapshots) break

                // Advance viewport for next batch
                val swiped = swipeUpGesture() || scrollFeedForward()
                if (!swiped) break
                try { Thread.sleep(350L) } catch (e: InterruptedException) { break }
            }
        } finally {
            com.example.FloatingBubbleService.setPanelVisible(true)
            isIndexingActiveFlow.value = false
        }

        val finalCount = if (postDao != null) {
            runBlocking(Dispatchers.IO) {
                try { postDao.getPostsSnapshotByGroup(targetGroupName).size } catch (e: Exception) { totalSaved }
            }
        } else totalSaved

        val summary = "اكتمل الفحص السريع! الأرشيف: $finalCount منشور ($totalSaved جديد)"
        toast(summary)
        indexingStatusTextFlow.value = summary
        return totalSaved
    }

    /**
     * Backward-compatible delegation wrapper for indexGroupContent -> performFastSnapshotScan
     */
    fun indexGroupContent(
        maxScrolls: Int = MAX_SNAPSHOTS,
        retryMultiplier: Long = 1L,
        onProgress: ((Int) -> Unit)? = null
    ): DeepScanScreenResult {
        performFastSnapshotScan(maxSnapshots = maxScrolls) { count, _, _ ->
            onProgress?.invoke(count)
        }
        val targetGroup = detectedGroupNameFlow.value
        val db = try { AppDatabase.getDatabase(applicationContext) } catch (e: Exception) { null }
        val snapshot = db?.let {
            runBlocking(Dispatchers.IO) { it.postDao().getPostsSnapshotByGroup(targetGroup) }
        } ?: emptyList()

        // RUNTIME DIAGNOSTIC OVERLAY TRIGGER
        // If scan returns 0 posts or fails, generate a consolidated diagnostic report
        val diagnosticReport = if (snapshot.isEmpty()) {
            val report = generateDiagnosticReport(
                ocrExecuted = true,
                ocrBitmapWidth = 1080,
                ocrBitmapHeight = 2400,
                ocrRawLineCount = 0,
                ocrErrorMsg = ocrNote.ifEmpty { "0 posts returned from scan" }
            )
            toast("⚠️ تقرير تشخيصي: فشل قراءة المنشورات (راجع السجل أو الشاشة)")
            Log.w("BO_LITE_DIAGNOSTIC", "Diagnostic report generated on empty scan:\n$report")
            report
        } else {
            null
        }

        return DeepScanScreenResult(
            groupName = targetGroup,
            isMonitored = true,
            items = snapshot.map { it.toDeepScanResult() },
            totalTraversedNodes = snapshot.size,
            errorMessage = if (snapshot.isEmpty()) "No new content found. Diagnostic trace captured." else null
        )
    }

    /**
     * Diagnostic report generator called when a scan returns 0 posts or fails.
     * Evaluates live accessibility root state, container hierarchy, scrollable vs static nodes,
     * samples raw text from the first container child, and logs comprehensive failure causes.
     */
    private fun generateDiagnosticReport(
        ocrExecuted: Boolean,
        ocrBitmapWidth: Int,
        ocrBitmapHeight: Int,
        ocrRawLineCount: Int,
        ocrErrorMsg: String
    ): String {
        return try {
            val report = StringBuilder()
            report.appendLine("=== [BO LITE RUNTIME DIAGNOSTIC REPORT] ===")
            report.appendLine("Timestamp: ${System.currentTimeMillis()}")

            // 1. PERMISSION & WINDOW STATE
            val expectedPkgs = listOf("com.facebook.katana", "com.facebook.lite", "com.facebook.orca")
            val targetRoot = try { findTargetAppRoot() } catch (e: Exception) { null }
            val activeRoot = try { rootInActiveWindow } catch (e: Exception) { null }
            val rootNode = targetRoot ?: activeRoot

            val targetPkg = targetRoot?.packageName?.toString() ?: "<null>"
            val activePkg = activeRoot?.packageName?.toString() ?: "<null>"
            val rootPkg = rootNode?.packageName?.toString() ?: "<null>"
            val isExpectedPkg = expectedPkgs.any { rootPkg.contains(it, ignoreCase = true) }

            report.appendLine("\n--- [1. WINDOW & PACKAGE STATE] ---")
            report.appendLine("Target Root Package : $targetPkg")
            report.appendLine("Active Window Package: $activePkg")
            report.appendLine("Selected Root        : $rootPkg (Facebook Match: $isExpectedPkg)")

            // 2. ROOT NODE NULL CHECK
            if (rootNode == null) {
                report.appendLine("\n❌ PRIMARY DIAGNOSIS: Root Null")
                report.appendLine("Explanation: AccessibilityService could not obtain rootInActiveWindow or findTargetAppRoot().")
                report.appendLine("Probable Cause: Accessibility permission revoked, window not focused, or secure overlay active.")
                report.appendLine("\n--- [OCR FALLBACK DATA] ---")
                report.appendLine("Executed: $ocrExecuted | Dims: ${ocrBitmapWidth}x$ocrBitmapHeight | Raw Lines: $ocrRawLineCount | Error: $ocrErrorMsg")
                val result = report.toString()
                diagnosticReportFlow.value = result
                Log.w("BO_LITE_DIAGNOSTIC", result)
                return result
            }

            // 3. TREE STRUCTURE & NODE COUNTS (Scrollable vs Static TextViews)
            var scrollableCount = 0
            var textViewCount = 0
            var totalTraversed = 0

            fun countNodeTypes(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 8 || totalTraversed > 250) return
                totalTraversed++
                try {
                    if (node.isScrollable) scrollableCount++
                    val className = node.className?.toString() ?: ""
                    if (className.contains("TextView", ignoreCase = true)) {
                        textViewCount++
                    }
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i) ?: continue
                        countNodeTypes(child, depth + 1)
                    }
                } catch (ignored: Exception) {}
            }

            try {
                countNodeTypes(rootNode, 0)
            } catch (e: Exception) {
                report.appendLine("Tree counting interrupted: ${e.message}")
            }

            report.appendLine("\n--- [2. NODE DISTRIBUTION] ---")
            report.appendLine("Total Nodes Traversed   : $totalTraversed")
            report.appendLine("Scrollable Containers   : $scrollableCount")
            report.appendLine("Static TextViews Found  : $textViewCount")

            // 4. LOCATE LARGEST SCROLLABLE CONTAINER & SAMPLE FIRST CHILD
            val largestScrollable = try { findLargestScrollable(rootNode) } catch (e: Exception) { null }
            val container = largestScrollable ?: rootNode
            val isActualScrollable = container.isScrollable

            report.appendLine("\n--- [3. CONTAINER & FIRST CHILD SAMPLE] ---")
            report.appendLine("Container Class Name    : ${container.className}")
            report.appendLine("Is Container Scrollable : $isActualScrollable")
            report.appendLine("Container Child Count   : ${container.childCount}")

            var firstChildTextSample = "<null>"
            var firstChildDescSample = "<null>"
            var firstChildHasText = false

            if (container.childCount > 0) {
                val firstChild = try { container.getChild(0) } catch (e: Exception) { null }
                if (firstChild != null) {
                    val rawText = firstChild.text?.toString()?.trim()
                    val rawDesc = firstChild.contentDescription?.toString()?.trim()
                    firstChildTextSample = rawText ?: "<null>"
                    firstChildDescSample = rawDesc ?: "<null>"

                    if (!rawText.isNullOrBlank() || !rawDesc.isNullOrBlank()) {
                        firstChildHasText = true
                    } else {
                        // Check first child's sub-children (shallow BFS)
                        for (subIdx in 0 until minOf(5, firstChild.childCount)) {
                            val subChild = try { firstChild.getChild(subIdx) } catch (e: Exception) { null }
                            val subText = subChild?.text?.toString()?.trim()
                            val subDesc = subChild?.contentDescription?.toString()?.trim()
                            if (!subText.isNullOrBlank() || !subDesc.isNullOrBlank()) {
                                firstChildTextSample = subText ?: "<null>"
                                firstChildDescSample = subDesc ?: "<null>"
                                firstChildHasText = true
                                break
                            }
                        }
                    }
                    report.appendLine("First Child Class       : ${firstChild.className}")
                    report.appendLine("First Child Sub-children: ${firstChild.childCount}")
                    report.appendLine("First Child raw .text   : \"$firstChildTextSample\"")
                    report.appendLine("First Child .contentDesc: \"$firstChildDescSample\"")
                } else {
                    report.appendLine("First Child: NULL (getChild(0) returned null)")
                }
            } else {
                report.appendLine("Container has 0 children.")
            }

            // 5. OCR FALLBACK TRACE
            report.appendLine("\n--- [4. OCR FALLBACK TRACE] ---")
            report.appendLine("OCR Executed            : $ocrExecuted")
            report.appendLine("Bitmap Resolution       : ${ocrBitmapWidth}x$ocrBitmapHeight")
            report.appendLine("Raw Lines Detected      : $ocrRawLineCount")
            report.appendLine("OCR Error Message       : ${ocrErrorMsg.ifEmpty { "None" }}")

            // 6. PRIMARY DIAGNOSIS SYNTHESIS
            val diagnosis = when {
                !isExpectedPkg -> "Not Facebook Window (Current: $rootPkg)"
                largestScrollable == null && scrollableCount == 0 -> "No Scrollable Found (Feed not loaded or flat hierarchy)"
                container.childCount == 0 -> "Empty Container (0 Child Nodes)"
                !firstChildHasText && textViewCount == 0 -> "Nodes Visible but Empty Text (Custom/Canvas UI or Facebook Lite virtualized layout)"
                ocrExecuted && ocrRawLineCount == 0 -> "Nodes Visible but OCR Failed to Extract Text"
                else -> "Unfiltered Content Mismatch (Nodes present but rejected by filters)"
            }

            report.appendLine("\n🎯 PRIMARY DIAGNOSIS: $diagnosis")
            report.appendLine("===========================================")

            val finalReport = report.toString()
            diagnosticReportFlow.value = finalReport
            Log.w("BO_LITE_DIAGNOSTIC", finalReport)
            finalReport
        } catch (e: Exception) {
            val fallback = "Diagnostic generation failed with exception: ${e.message}\nOCR Error: $ocrErrorMsg"
            Log.e("BO_LITE_DIAGNOSTIC", fallback, e)
            diagnosticReportFlow.value = fallback
            fallback
        }
    }

    /**
     * Backward-compatible delegation wrapper for performScrollingScan -> performFastSnapshotScan
     */
    fun performScrollingScan(maxScrolls: Int = MAX_SNAPSHOTS, retryMultiplier: Long = 1L): DeepScanScreenResult {
        return indexGroupContent(maxScrolls = maxScrolls, retryMultiplier = retryMultiplier)
    }

    /**
     * Backward-compatible alias for cumulativeIndexGroupContent -> performFastSnapshotScan
     */
    fun cumulativeIndexGroupContent(
        maxRounds: Int = MAX_SNAPSHOTS,
        onProgress: ((count: Int, round: Int, maxRounds: Int) -> Unit)? = null
    ): Int {
        return performFastSnapshotScan(maxSnapshots = maxRounds, onProgress = onProgress)
    }

    /**
     * Single-step visible archive for fast manual capture
     */
    fun archiveVisibleContent(): Boolean {
        val targetGroupName = detectedGroupNameFlow.value
        val db = try { AppDatabase.getDatabase(applicationContext) } catch (e: Exception) { null }
        val postDao = db?.postDao() ?: return false
        val visible = readVisiblePostsFromTree(targetGroupName)
        if (visible.isEmpty()) return false
        runBlocking(Dispatchers.IO) {
            postDao.insertIgnore(visible)
            val count = postDao.getPostsSnapshotByGroup(targetGroupName).size
            indexingProgressFlow.value = count
            lastIndexedCountFlow.value = count
            indexingStatusTextFlow.value = "Done! Indexed $count posts total."
            try {
                WatchNotifier.notifyMatches(
                    applicationContext,
                    visible.map { it.text },
                    targetGroupName
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error checking watch notifications in archiveVisibleContent", e)
            }
        }
        return true
    }
}

/**
 * Top-level convenience wrapper to retrieve the current active screen tree
 * for FeedReader and structural extraction.
 */
fun buildScreenTree(): android.view.accessibility.AccessibilityNodeInfo? =
    EnhancedAccessibilityService.buildScreenTree()

/**
 * Fallback OCR routine to capture screen and recognize text when structural accessibility parsing fails.
 */
suspend fun performOcrScanFallback(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val service = EnhancedAccessibilityService.activeInstance ?: return@withContext ""
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return@withContext ""
    val shot = service.captureScreenshotSync() ?: return@withContext ""
    try {
        val lines = com.example.ocr.ScreenOcr.recognize(service.applicationContext, shot)
        val posts = com.example.ocr.ScreenOcr.groupLinesIntoPosts(lines)
        if (posts.isNotEmpty()) {
            posts.first().take(200)
        } else if (lines.isNotEmpty()) {
            lines.take(3).joinToString("\n") { it.text }
        } else {
            ""
        }
    } catch (e: Exception) {
        ""
    } finally {
        shot.recycle()
    }
}
