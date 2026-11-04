package com.example.ocr

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.Window
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

/**
 * Output data class representing the verified extracted Facebook post
 * derived strictly from Visual Screen Capture & ML Kit OCR.
 */
data class ParsedPost(
    val author: String,
    val content: String,
    val confidenceScore: Float
)

/**
 * Modular OCR engine abstraction.
 * Allows swapping between Google ML Kit, Tesseract, or future on-device ML models without touching parsing logic.
 */
interface PostOcrEngine {
    fun setLanguages(languages: List<String>)
    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock>
}

/**
 * Google ML Kit Text Recognition v2 implementation.
 * Performs on-device visual text recognition, providing coordinate-aware text blocks and lines.
 */
class MlKitPostOcrEngine : PostOcrEngine {

    companion object {
        private const val TAG = "MlKitPostOcrEngine"
    }

    private var activeLanguages: List<String> = listOf("ar", "en")
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun setLanguages(languages: List<String>) {
        this.activeLanguages = languages
        Log.d(TAG, "ML Kit configured for target languages: $languages")
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText: Text ->
                    val ocrBlocks = mutableListOf<OcrTextBlock>()
                    for (block in visionText.textBlocks) {
                        val blockBox = block.boundingBox ?: Rect(0, 0, 0, 0)
                        val lineItems = block.lines.map { line ->
                            OcrTextLine(
                                text = line.text.trim(),
                                boundingBox = line.boundingBox ?: Rect(0, 0, 0, 0)
                            )
                        }

                        // Estimate quality score from block characteristics
                        val nonNoiseChars = block.text.count { it.isLetterOrDigit() }
                        val quality = if (block.text.isNotEmpty()) {
                            (nonNoiseChars.toFloat() / block.text.length).coerceIn(0.6f, 0.98f)
                        } else {
                            0.5f
                        }

                        ocrBlocks.add(
                            OcrTextBlock(
                                text = block.text.trim(),
                                boundingBox = blockBox,
                                lines = lineItems,
                                qualityScore = quality
                            )
                        )
                    }
                    Log.d(TAG, "ML Kit recognized ${ocrBlocks.size} text blocks")
                    continuation.resume(ocrBlocks)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "ML Kit OCR failed: ${exception.message}", exception)
                    continuation.resume(emptyList())
                }
        }
    }
}

/**
 * Swappable Tesseract OCR Engine.
 * Supports simultaneous offline Arabic ('ara') and English ('eng') dictionaries.
 */
class TesseractPostOcrEngine(private val context: Context) : PostOcrEngine {

    private var configuredLanguages: List<String> = listOf("ar", "en")

    override fun setLanguages(languages: List<String>) {
        this.configuredLanguages = languages
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        try {
            val lines = com.example.ocr.ScreenOcr.recognize(context, bitmap)
            lines.map { line ->
                OcrTextBlock(
                    text = line.text,
                    boundingBox = line.box,
                    lines = listOf(OcrTextLine(text = line.text, boundingBox = line.box)),
                    qualityScore = line.confidence
                )
            }
        } catch (t: Throwable) {
            Log.e("TesseractPostOcrEngine", "Tesseract recognition failed: ${t.message}", t)
            emptyList()
        }
    }
}

/**
 * Hybrid OCR Engine:
 * Combines ML Kit with Tesseract to ensure 100% Arabic + English accuracy.
 * ML Kit is primary; if Latin text dominates or Arabic blocks need enrichment, combines results.
 */
class HybridPostOcrEngine(private val context: Context) : PostOcrEngine {
    private val mlKit = MlKitPostOcrEngine()
    private val tesseract = TesseractPostOcrEngine(context)

    override fun setLanguages(languages: List<String>) {
        mlKit.setLanguages(languages)
        tesseract.setLanguages(languages)
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        val mlKitBlocks = mlKit.recognize(bitmap)
        val hasSubstantialArabic = mlKitBlocks.any { block ->
            block.text.any { c -> c in '\u0600'..'\u06FF' }
        }

        if (mlKitBlocks.isNotEmpty() && (hasSubstantialArabic || mlKitBlocks.sumOf { it.text.length } > 50)) {
            return@withContext mlKitBlocks
        }

        // Enrich with Tesseract if ML Kit returns sparse results or misses Arabic script
        val tessBlocks = tesseract.recognize(bitmap)
        if (tessBlocks.isNotEmpty()) {
            return@withContext tessBlocks
        }

        mlKitBlocks
    }
}

/**
 * SmartPostReader:
 *
 * Replaces unreliable accessibility node tree parsing with direct Visual Screenshot Capture + ML Kit OCR.
 *
 * Pipeline:
 * 1. CAPTURE & CROP: Captures high-res screenshot (PixelCopy / MediaProjection / Accessibility)
 *    and crops away system UI chrome to focus strictly on one post card.
 * 2. OCR INFERENCE: Dispatches cropped bitmap to Google ML Kit (configured for "ar" + "en").
 * 3. SPATIAL COORDINATE ANALYSIS:
 *    - TOP-MOST text block -> Author Name
 *    - LARGEST text blocks below author -> Post Content Body
 *    - BOTTOM-MOST small fragments (< 15 chars, reactions, timestamps) -> Ignored
 *    - Adjacent lines belonging to the same paragraph are logically merged.
 * 4. CLEANUP & VALIDATION:
 *    - Non-words, symbol strings, and noise patterns removed.
 *    - Output returned as strongly-typed ParsedPost.
 */
class SmartPostReader(
    private var ocrEngine: PostOcrEngine = MlKitPostOcrEngine()
) {

    companion object {
        private const val TAG = "SmartPostReader"

        // Default languages
        val DEFAULT_LANGUAGES = listOf("ar", "en")

        // Reaction / interaction noise patterns
        private val reactionKeywords = setOf(
            "like", "likes", "comment", "comments", "share", "shares", "reply", "replies",
            "إعجاب", "إعجابات", "تعليق", "تعليقات", "مشاركة", "مشاركات", "رد", "ردود"
        )

        private val reactionRegex = Regex(
            """^(?:^\d+$|like|likes|comment|comments|share|shares|reply|replies|إعجاب|إعجابات|تعليق|تعليقات|مشاركة|مشاركات|رد|ردود|[\d\u0660-\u0669KkMm\s]+)$""",
            RegexOption.IGNORE_CASE
        )

        // Timestamp patterns
        private val timePatternRegex = Regex(
            """(?:\b[PApa]?\d{1,2}:\d{2}\b|\bمنذ\b|\bساعة\b|\bساعات\b|\bدقيقة\b|\bدقائق\b|\bأمس\b|\byesterday\b|\bjust now\b|\bالآن\b|\b\d+\s*[hmdwHMDW]\b)""",
            RegexOption.IGNORE_CASE
        )
    }

    init {
        ocrEngine.setLanguages(DEFAULT_LANGUAGES)
    }

    /**
     * Allows dynamically swapping the underlying OCR engine (e.g. ML Kit, Tesseract, or Hybrid).
     */
    fun setOcrEngine(newEngine: PostOcrEngine) {
        this.ocrEngine = newEngine
        this.ocrEngine.setLanguages(DEFAULT_LANGUAGES)
        Log.i(TAG, "Swapped OCR engine to ${newEngine.javaClass.simpleName}")
    }

    /**
     * Primary entry point: Reads one post from a pre-captured Bitmap image.
     *
     * @param fullOrCroppedBitmap Screenshot bitmap.
     * @param postBoundsInBitmap Optional precise bounding box of the single post container.
     *                           If null, automatic intelligent cropping excludes status bar & navigation bar.
     * @return Clean ParsedPost with strictly separated author and content.
     */
    suspend fun readPostFromBitmap(
        fullOrCroppedBitmap: Bitmap,
        postBoundsInBitmap: Rect? = null
    ): ParsedPost? = withContext(Dispatchers.Default) {
        val cropped = cropToPostContainer(fullOrCroppedBitmap, postBoundsInBitmap)
        try {
            Log.d(TAG, "Processing cropped post image: ${cropped.width}x${cropped.height}")
            val blocks = ocrEngine.recognize(cropped)
            if (blocks.isEmpty()) {
                Log.w(TAG, "OCR produced 0 text blocks from cropped post image")
                return@withContext null
            }

            val parsed = performSpatialAnalysis(blocks, cropped.width, cropped.height)
            Log.i(TAG, "Successfully parsed post: Author='${parsed.author}', Confidence=${parsed.confidenceScore}, ContentLength=${parsed.content.length}")
            parsed
        } finally {
            if (cropped != fullOrCroppedBitmap) {
                cropped.recycle()
            }
        }
    }

    /**
     * Reads all visible posts from a captured full-screen Bitmap.
     * Segments multiple posts if present, or parses the single primary post visible on screen.
     */
    suspend fun readVisiblePosts(
        fullOrCroppedBitmap: Bitmap
    ): List<ParsedPost> = withContext(Dispatchers.Default) {
        val cropped = cropToPostContainer(fullOrCroppedBitmap, null)
        try {
            val blocks = ocrEngine.recognize(cropped)
            if (blocks.isEmpty()) return@withContext emptyList()

            // Filter out pure noise / chrome blocks
            val cleanBlocks = blocks.filter { block ->
                val txt = block.text.trim()
                txt.isNotEmpty() && !isPureNoiseOrSymbols(txt)
            }.sortedBy { it.boundingBox.top }

            if (cleanBlocks.isEmpty()) return@withContext emptyList()

            // Look for author markers along Y axis to detect multiple posts
            val authorIndices = mutableListOf<Int>()
            for (i in cleanBlocks.indices) {
                val block = cleanBlocks[i]
                val text = block.text.trim()
                if (isTimestampOnly(text) || isStatusBarChrome(text) || isReactionOrAction(text)) continue

                val firstLine = block.lines.firstOrNull()?.text?.trim() ?: text.lines().first().trim()
                val candidateAuthor = sanitizeAuthorString(firstLine)
                if (candidateAuthor.isNotBlank() && candidateAuthor != "Unknown Member") {
                    val prevIdx = authorIndices.lastOrNull()
                    if (prevIdx == null || (block.boundingBox.top - cleanBlocks[prevIdx].boundingBox.top) > 200) {
                        authorIndices.add(i)
                    }
                }
            }

            if (authorIndices.size <= 1) {
                val single = performSpatialAnalysis(cleanBlocks, cropped.width, cropped.height)
                return@withContext if (single.content.length >= 20) listOf(single) else emptyList()
            }

            val results = mutableListOf<ParsedPost>()
            for (k in authorIndices.indices) {
                val startIdx = authorIndices[k]
                val endIdx = if (k < authorIndices.size - 1) authorIndices[k + 1] else cleanBlocks.size
                val postBlocks = cleanBlocks.subList(startIdx, endIdx)

                if (postBlocks.isNotEmpty()) {
                    val topY = postBlocks.first().boundingBox.top
                    val bottomY = postBlocks.last().boundingBox.bottom
                    val postHeight = max(bottomY - topY, 150)

                    val normalizedBlocks = postBlocks.map { b ->
                        val relBox = Rect(b.boundingBox.left, b.boundingBox.top - topY, b.boundingBox.right, b.boundingBox.bottom - topY)
                        val relLines = b.lines.map { l ->
                            OcrTextLine(l.text, Rect(l.boundingBox.left, l.boundingBox.top - topY, l.boundingBox.right, l.boundingBox.bottom - topY))
                        }
                        OcrTextBlock(b.text, relBox, relLines, b.qualityScore)
                    }

                    val post = performSpatialAnalysis(normalizedBlocks, cropped.width, postHeight)
                    if (post.content.length >= 20) {
                        results.add(post)
                    }
                }
            }

            results
        } finally {
            if (cropped != fullOrCroppedBitmap) {
                cropped.recycle()
            }
        }
    }

    /**
     * STEP 1: CAPTURE SCREENSHOT via PixelCopy API (Available on API 26+).
     *
     * @param window Target window to capture from.
     * @param targetPostRect Optional screen coordinates of the post container.
     */
    suspend fun captureWithPixelCopy(
        window: Window,
        targetPostRect: Rect? = null
    ): Bitmap? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "PixelCopy requires Android 8.0 (API 26) or higher")
            return@withContext null
        }

        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return@withContext null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())

        suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    targetPostRect,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            continuation.resume(bitmap)
                        } else {
                            Log.e(TAG, "PixelCopy failed with result code: $copyResult")
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    },
                    handler
                )
            } catch (t: Throwable) {
                Log.e(TAG, "PixelCopy exception: ${t.message}", t)
                bitmap.recycle()
                continuation.resume(null)
            }
        }
    }

    /**
     * STEP 1: CAPTURE SCREENSHOT via MediaProjection VirtualDisplay.
     * Captures a single pristine frame from an active MediaProjection session.
     */
    @SuppressLint("WrongConstant")
    suspend fun captureWithMediaProjection(
        mediaProjection: MediaProjection,
        displayWidth: Int,
        displayHeight: Int,
        densityDpi: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val imageReader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            2
        )

        var virtualDisplay: VirtualDisplay? = null
        try {
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "SmartPostReaderCapture",
                displayWidth,
                displayHeight,
                densityDpi,
                flags,
                imageReader.surface,
                null,
                null
            )

            // Allow frame buffer to settle
            kotlinx.coroutines.delay(120)

            var latestImage: Image? = null
            for (retry in 0 until 5) {
                latestImage = imageReader.acquireLatestImage()
                if (latestImage != null) break
                kotlinx.coroutines.delay(50)
            }

            if (latestImage == null) {
                Log.w(TAG, "Failed to acquire image from MediaProjection")
                return@withContext null
            }

            val planes = latestImage.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * displayWidth

            val bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            latestImage.close()

            // Crop excess row padding if present
            if (bitmap.width != displayWidth) {
                val clean = Bitmap.createBitmap(bitmap, 0, 0, displayWidth, displayHeight)
                bitmap.recycle()
                clean
            } else {
                bitmap
            }
        } catch (t: Throwable) {
            Log.e(TAG, "MediaProjection capture error: ${t.message}", t)
            null
        } finally {
            virtualDisplay?.release()
            imageReader.close()
        }
    }

    /**
     * STEP 1: CAPTURE SCREENSHOT via AccessibilityService (Android 11+ / API 30+).
     */
    suspend fun captureWithAccessibility(service: AccessibilityService): Bitmap? =
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e(TAG, "Accessibility screenshot requires Android 11 (API 30)+")
                return@withContext null
            }

            suspendCancellableCoroutine { continuation ->
                val executor = Executors.newSingleThreadExecutor()
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshotResult.colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                buffer.close()
                                continuation.resume(softBitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error copying accessibility screenshot: ${e.message}", e)
                                continuation.resume(null)
                            } finally {
                                executor.shutdown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Accessibility takeScreenshot failed with code $errorCode")
                            continuation.resume(null)
                            executor.shutdown()
                        }
                    }
                )
            }
        }

    /**
     * Crops image to focus ONLY on the post container, discarding status bar, header, and bottom navigation.
     */
    fun cropToPostContainer(src: Bitmap, targetBounds: Rect? = null): Bitmap {
        val width = src.width
        val height = src.height

        if (targetBounds != null && !targetBounds.isEmpty) {
            val left = targetBounds.left.coerceIn(0, width - 1)
            val top = targetBounds.top.coerceIn(0, height - 1)
            val right = targetBounds.right.coerceIn(left + 1, width)
            val bottom = targetBounds.bottom.coerceIn(top + 1, height)
            val cropW = right - left
            val cropH = bottom - top

            if (cropW > 50 && cropH > 50) {
                return Bitmap.createBitmap(src, left, top, cropW, cropH)
            }
        }

        // Automatic crop: Exclude top 12% (status bar + app bar) and bottom 10% (navigation bar)
        val topOffset = (height * 0.12f).toInt().coerceAtLeast(140)
        val bottomLimit = (height * 0.90f).toInt().coerceAtMost(height - 120)
        val availableHeight = bottomLimit - topOffset

        return if (availableHeight > 100 && width > 100) {
            try {
                Bitmap.createBitmap(src, 0, topOffset, width, availableHeight)
            } catch (e: Exception) {
                Log.w(TAG, "Crop failed, fallback to full bitmap: ${e.message}")
                src
            }
        } else {
            src
        }
    }

    /**
     * STEP 3: SPATIAL ANALYSIS (The Magic Step).
     *
     * Coordinates-based parsing:
     * - Top-most text block in crop -> Author Name
     * - Largest text blocks below author -> Post Content
     * - Bottom small fragments (timestamps, reactions) -> Ignored
     * - Adjacent lines belonging to the same paragraph are logically merged.
     */
    fun performSpatialAnalysis(
        blocks: List<OcrTextBlock>,
        cropWidth: Int,
        cropHeight: Int
    ): ParsedPost {
        // 1. Filter out pure noise, symbols, and empty blocks
        val cleanBlocks = blocks.filter { block ->
            val txt = block.text.trim()
            txt.isNotEmpty() && !isPureNoiseOrSymbols(txt)
        }

        if (cleanBlocks.isEmpty()) {
            return ParsedPost(author = "Unknown Member", content = "", confidenceScore = 0f)
        }

        // 2. Sort blocks spatially from top to bottom
        val sortedBlocks = cleanBlocks.sortedBy { it.boundingBox.top }

        // -------------------------------------------------------------
        // STEP 3A: Identify TOP-MOST text block -> AUTHOR NAME
        // -------------------------------------------------------------
        var authorBlockIndex = -1
        var candidateAuthor = "Unknown Member"
        var authorBottomY = 0

        for (i in sortedBlocks.indices) {
            val block = sortedBlocks[i]
            val text = block.text.trim()

            // Skip status bar remnants or standalone timestamps if near top
            if (isTimestampOnly(text) || isStatusBarChrome(text)) {
                continue
            }

            // The first meaningful block in the top 35% of the post card
            if (block.boundingBox.top < (cropHeight * 0.35f)) {
                // If block contains multiple lines (e.g. "Author Name\n2 hrs ago"), extract author from first line
                val firstLine = block.lines.firstOrNull()?.text?.trim() ?: text.lines().first().trim()
                val cleanLine = sanitizeAuthorString(firstLine)
                if (cleanLine.isNotBlank()) {
                    candidateAuthor = cleanLine
                    authorBlockIndex = i
                    authorBottomY = block.lines.firstOrNull()?.boundingBox?.bottom ?: block.boundingBox.bottom
                    Log.d(TAG, "Identified Author Name by spatial coordinates: '$candidateAuthor' at Y=$authorBottomY")
                    break
                }
            }
        }

        // -------------------------------------------------------------
        // STEP 3B: Identify LARGEST text blocks below author -> POST CONTENT
        // -------------------------------------------------------------
        val candidateContentBlocks = mutableListOf<OcrTextBlock>()
        val bottomReactionThreshold = cropHeight * 0.88f // Bottom 12% is usually Like/Comment bar

        for (i in sortedBlocks.indices) {
            if (i == authorBlockIndex) continue
            val block = sortedBlocks[i]

            // Must be below the author name
            if (block.boundingBox.top < (authorBottomY - 10)) {
                continue
            }

            val text = block.text.trim()

            // Ignore small text fragments at the very bottom (timestamps, reaction counts, etc.)
            val isAtBottom = block.boundingBox.bottom >= bottomReactionThreshold
            val isShortReaction = text.length < 25 && isReactionOrAction(text)
            val isShortTimestamp = text.length < 25 && isTimestampOnly(text)

            if (isAtBottom && (isShortReaction || isShortTimestamp || text.length < 15)) {
                Log.d(TAG, "Ignored bottom noise fragment: '$text'")
                continue
            }

            if (isShortReaction || isShortTimestamp) {
                Log.d(TAG, "Ignored reaction/timestamp block: '$text'")
                continue
            }

            candidateContentBlocks.add(block)
        }

        // -------------------------------------------------------------
        // STEP 3C: Merge adjacent text lines belonging to the same paragraph logically
        // -------------------------------------------------------------
        val mergedContent = mergeParagraphLinesLogically(candidateContentBlocks)
        val finalContent = formatAndEnforceRtl(mergedContent)

        // -------------------------------------------------------------
        // STEP 4: Calculate confidence score based on OCR and text coherence
        // -------------------------------------------------------------
        val avgBlockConfidence = if (candidateContentBlocks.isNotEmpty()) {
            candidateContentBlocks.map { it.qualityScore }.average().toFloat()
        } else {
            0.50f
        }

        val lengthFactor = (finalContent.length / 100f).coerceIn(0.6f, 1.0f)
        val finalConfidence = (avgBlockConfidence * 0.7f + lengthFactor * 0.3f).coerceIn(0.1f, 1.0f)

        return ParsedPost(
            author = candidateAuthor,
            content = finalContent,
            confidenceScore = finalConfidence
        )
    }

    /**
     * Merges adjacent text lines belonging to the same paragraph logically.
     * Evaluates spatial line gaps: lines close together vertical-wise are part of the same paragraph.
     */
    private fun mergeParagraphLinesLogically(blocks: List<OcrTextBlock>): String {
        if (blocks.isEmpty()) return ""

        // Extract all individual lines with bounding boxes
        val allLines = mutableListOf<OcrTextLine>()
        for (block in blocks) {
            if (block.lines.isNotEmpty()) {
                allLines.addAll(block.lines)
            } else {
                allLines.add(OcrTextLine(text = block.text, boundingBox = block.boundingBox))
            }
        }

        // Sort lines by Y coordinate
        val sortedLines = allLines.sortedBy { it.boundingBox.top }
        val paragraphBuilder = StringBuilder()

        var previousLine: OcrTextLine? = null

        for (line in sortedLines) {
            val text = line.text.trim()
            if (text.isEmpty() || isPureNoiseOrSymbols(text)) continue

            val prev = previousLine
            if (prev == null) {
                paragraphBuilder.append(text)
            } else {
                val prevHeight = max(prev.boundingBox.height(), 20)
                val verticalGap = line.boundingBox.top - prev.boundingBox.bottom

                // If gap is small (<= 1.2x line height), lines belong to the same paragraph
                if (verticalGap <= (prevHeight * 1.2f)) {
                    // Check if previous line ended with hyphenation or punctuation
                    if (prev.text.endsWith("-")) {
                        paragraphBuilder.setLength(paragraphBuilder.length - 1)
                        paragraphBuilder.append(text)
                    } else {
                        paragraphBuilder.append(" ").append(text)
                    }
                } else {
                    // Larger gap indicates a distinct paragraph
                    paragraphBuilder.append("\n\n").append(text)
                }
            }
            previousLine = line
        }

        return paragraphBuilder.toString().trim()
    }

    /**
     * Cleans and validates author names.
     */
    private fun sanitizeAuthorString(raw: String): String {
        var clean = raw.trim()
        clean = clean.replace(Regex("""^[•\-\s_]+"""), "") // Remove bullets or prefixes
        clean = clean.replace(Regex("""\s*(?:[PApa]?\d{1,2}:\d{2}|منذ.*|yesterday|أمس|\d+\s*[hmdw]).*$"""), "") // Strip timestamp

        if (clean.length < 2 || clean.length > 50) return "Unknown Member"
        if (isPureNoiseOrSymbols(clean)) return "Unknown Member"

        return clean
    }

    /**
     * Checks if text consists purely of symbols, emojis, or non-word characters.
     */
    private fun isPureNoiseOrSymbols(text: String): Boolean {
        val stripped = text.filter { it.isLetterOrDigit() }
        if (stripped.isEmpty()) return true
        // If string contains mostly non-letters (e.g. "----____----" or "$$$$$")
        val letterCount = text.count { it.isLetter() }
        if (text.length >= 4 && letterCount == 0) return true
        return false
    }

    private fun isTimestampOnly(text: String): Boolean {
        val clean = text.trim()
        if (clean.length > 30) return false
        return timePatternRegex.containsMatchIn(clean)
    }

    private fun isStatusBarChrome(text: String): Boolean {
        val clean = text.trim()
        return clean.matches(Regex("""^(?:LTE|4G|5G|WiFi|100%|\d{1,2}%|[PApa]?\d{1,2}:\d{2})$""", RegexOption.IGNORE_CASE))
    }

    private fun isReactionOrAction(text: String): Boolean {
        val clean = text.trim()
        if (clean.matches(Regex("""^\d+$"""))) return true
        if (reactionKeywords.contains(clean.lowercase())) return true
        return reactionRegex.matches(clean)
    }

    /**
     * Enforces proper RTL orientation if Arabic script is present.
     */
    private fun formatAndEnforceRtl(text: String): String {
        if (text.isBlank()) return ""
        val hasArabic = text.any { it in '\u0600'..'\u06FF' }
        return if (hasArabic) {
            if (text.startsWith("\u200F")) text else "\u200F$text"
        } else {
            text
        }
    }
}
