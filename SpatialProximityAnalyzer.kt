package com.example.spatial

import android.graphics.Rect
import android.graphics.RectF
import com.example.nlp.LocalContextClassifier
import com.example.ocr.OcrTextBlock
import com.example.util.ParsedPostBlock
import com.example.util.TimeFilterUtility
import java.util.UUID

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
}

data class HybridRecognizedPost(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fullText: String = text,
    val author: String? = null,
    val timestamp: String? = null,
    val category: String = "نقاش",
    val ocrConfidence: Float = 0.95f,
    val normalizedBox: NormalizedBox? = null,
    val isOcrCaptured: Boolean = true,
    val isWithin7Days: Boolean = true
)

/**
 * SpatialProximityAnalyzer:
 * Analyzes OCR bounding boxes and spatial topology to segment on-screen content into
 * self-contained post blocks. Normalizes coordinates to guarantee identical behavior
 * on all screen sizes/densities.
 */
object SpatialProximityAnalyzer {

    // Normalized margin for associating author & timestamp with a post body (approx 60-80dp)
    private const val VERTICAL_PROXIMITY_THRESHOLD = 0.085f

    private val timestampKeywords = listOf(
        "منذ", "ساعة", "ساعات", "دقيقة", "دقائق", "أمس", "yesterday",
        "now", "الآن", "يوم", "أيام", "hrs", "mins", "1h", "2h", "3h", "1d", "2d"
    )

    private val actionWords = setOf(
        "إعجاب", "أعجبني", "like", "likes", "تعليق", "comment", "comments",
        "مشاركة", "share", "shares", "إرسال", "send", "رد", "reply",
        "بحث", "search", "انضمام", "join", "دعوة", "invite", "متابعة", "follow"
    )

    /**
     * Segments OCR text blocks and correlates them with accessibility metadata.
     */
    fun analyzeAndSegmentPosts(
        ocrBlocks: List<OcrTextBlock>,
        screenWidth: Int,
        screenHeight: Int,
        accessibilityFallback: List<ParsedPostBlock> = emptyList()
    ): List<HybridRecognizedPost> {
        val widthF = screenWidth.coerceAtLeast(1).toFloat()
        val heightF = screenHeight.coerceAtLeast(1).toFloat()

        // 1. Convert all blocks to normalized coordinate space (0.0 to 1.0)
        val normalizedItems = ocrBlocks.map { block ->
            val box = block.boundingBox
            val norm = NormalizedBox(
                left = (box.left / widthF).coerceIn(0f, 1f),
                top = (box.top / heightF).coerceIn(0f, 1f),
                right = (box.right / widthF).coerceIn(0f, 1f),
                bottom = (box.bottom / heightF).coerceIn(0f, 1f)
            )
            SpatialCandidate(block.text, norm, block.qualityScore)
        }.sortedBy { it.box.top }

        // 2. Identify candidate Post Bodies (> 50 chars or multi-line discussion)
        val candidateBodies = mutableListOf<SpatialCandidate>()
        val candidateHeaders = mutableListOf<SpatialCandidate>()

        for (item in normalizedItems) {
            val clean = item.text.trim()
            if (isActionOrChrome(clean)) continue

            if (clean.length > 45 || (clean.length in 30..45 && clean.contains("\n"))) {
                candidateBodies.add(item)
            } else if (clean.length in 3..40 && !isActionOrChrome(clean)) {
                candidateHeaders.add(item)
            }
        }

        val recognizedPosts = mutableListOf<HybridRecognizedPost>()
        val seenTexts = mutableSetOf<String>()

        // 3. For each Post Body, inspect neighboring regions (within vertical threshold)
        for (body in candidateBodies) {
            val normalizedKey = body.text.trim().replace("\\s+".toRegex(), " ")
            if (seenTexts.contains(normalizedKey)) continue
            seenTexts.add(normalizedKey)

            // Find nearest author header above the body within threshold
            val headerAbove = candidateHeaders
                .filter { it.box.bottom <= body.box.top + 0.02f && (body.box.top - it.box.bottom) < VERTICAL_PROXIMITY_THRESHOLD }
                .maxByOrNull { it.box.bottom }

            var author: String? = null
            var timestamp: String? = null

            if (headerAbove != null) {
                val headerText = headerAbove.text.trim()
                if (isTimestamp(headerText)) {
                    timestamp = headerText
                } else {
                    author = headerText
                }
            }

            // Cross-reference with Accessibility metadata if header was missing
            if (author == null || timestamp == null) {
                val match = accessibilityFallback.firstOrNull { fb ->
                    fb.text.contains(body.text.take(30)) || body.text.contains(fb.text.take(30))
                }
                if (match != null) {
                    if (author == null) author = match.author
                    if (timestamp == null) timestamp = match.timestamp
                }
            }

            // Classify context using NLP classifier
            val classification = LocalContextClassifier.classifyContent(body.text)

            val isRecent = TimeFilterUtility.isWithinLast7Days(timestamp)

            if (isRecent) {
                recognizedPosts.add(
                    HybridRecognizedPost(
                        text = body.text,
                        fullText = body.text,
                        author = author ?: "عضو في المجموعة",
                        timestamp = timestamp ?: "حديث",
                        category = classification.categoryName,
                        ocrConfidence = body.quality,
                        normalizedBox = body.box,
                        isOcrCaptured = true,
                        isWithin7Days = true
                    )
                )
            }
        }

        // 4. If OCR bodies were empty (e.g. low screen resolution or pure vector UI),
        // seamlessly fall back to Accessibility blocks!
        if (recognizedPosts.isEmpty() && accessibilityFallback.isNotEmpty()) {
            for (fb in accessibilityFallback) {
                val normalizedKey = fb.text.trim().replace("\\s+".toRegex(), " ")
                if (!seenTexts.contains(normalizedKey)) {
                    seenTexts.add(normalizedKey)
                    val classification = LocalContextClassifier.classifyContent(fb.text)
                    recognizedPosts.add(
                        HybridRecognizedPost(
                            id = fb.id,
                            text = fb.text,
                            fullText = fb.fullText,
                            author = fb.author ?: "عضو في المجموعة",
                            timestamp = fb.timestamp ?: "حديث",
                            category = classification.categoryName,
                            ocrConfidence = 0.96f,
                            normalizedBox = null,
                            isOcrCaptured = false,
                            isWithin7Days = fb.isWithin7Days
                        )
                    )
                }
            }
        }

        return recognizedPosts
    }

    private data class SpatialCandidate(
        val text: String,
        val box: NormalizedBox,
        val quality: Float
    )

    private fun isTimestamp(text: String): Boolean {
        val lower = text.lowercase()
        return timestampKeywords.any { lower.contains(it) } || text.matches(".*\\d{1,2}:\\d{2}.*".toRegex())
    }

    private fun isActionOrChrome(text: String): Boolean {
        val clean = text.trim()
        val lower = clean.lowercase()
        if (clean.length > 25) return false
        if (actionWords.contains(lower)) return true
        if (clean.matches("^[0-9+.,kKmM]+$".toRegex()) && clean.length <= 6) return true
        return false
    }
}
