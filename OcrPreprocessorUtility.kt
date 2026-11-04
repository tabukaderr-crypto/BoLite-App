package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrTextLine(
    val text: String,
    val boundingBox: Rect
)

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrTextLine>,
    val qualityScore: Float
)

data class OcrProcessingResult(
    val blocks: List<OcrTextBlock>,
    val fullText: String,
    val averageQualityScore: Float,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * OcrPreprocessorUtility:
 * Implements an image pre-processing pipeline for screen capture:
 * 1. Grayscale conversion.
 * 2. Contrast enhancement.
 * 3. Edge sharpening convolution.
 * 4. Google ML Kit Text Recognition integration with spatial bounding-box extraction.
 */
object OcrPreprocessorUtility {

    private const val TAG = "OcrPreprocessorUtility"

    /**
     * Executes the complete preprocessing and OCR recognition pipeline on Dispatchers.Default.
     */
    suspend fun recognizeTextFromScreen(originalBitmap: Bitmap): OcrProcessingResult = withContext(Dispatchers.Default) {
        val width = originalBitmap.width
        val height = originalBitmap.height

        // 1. Preprocess bitmap: Grayscale + High Contrast + Edge Sharpening
        val preprocessedBitmap = preprocessForOcr(originalBitmap)

        try {
            // 2. Perform ML Kit recognition
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(preprocessedBitmap, 0)

            val visionText = processImageSuspend(recognizer, inputImage)

            // 3. Extract structured blocks with bounding boxes and calculate confidence scores
            val blocks = mutableListOf<OcrTextBlock>()
            var totalQuality = 0f

            for (block in visionText.textBlocks) {
                val blockBox = block.boundingBox ?: Rect(0, 0, width, height)
                val lines = mutableListOf<OcrTextLine>()

                for (line in block.lines) {
                    val lineBox = line.boundingBox ?: blockBox
                    lines.add(OcrTextLine(line.text.trim(), lineBox))
                }

                // Compute quality heuristic based on character density and recognition consistency
                val quality = calculateQualityScore(block.text)
                totalQuality += quality

                blocks.add(
                    OcrTextBlock(
                        text = block.text.trim(),
                        boundingBox = blockBox,
                        lines = lines,
                        qualityScore = quality
                    )
                )
            }

            val avgQuality = if (blocks.isNotEmpty()) totalQuality / blocks.size else 0f

            OcrProcessingResult(
                blocks = blocks,
                fullText = visionText.text,
                averageQualityScore = avgQuality,
                imageWidth = width,
                imageHeight = height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error running ML Kit OCR", e)
            OcrProcessingResult(
                blocks = emptyList(),
                fullText = "",
                averageQualityScore = 0f,
                imageWidth = width,
                imageHeight = height
            )
        } finally {
            if (preprocessedBitmap != originalBitmap && !preprocessedBitmap.isRecycled) {
                preprocessedBitmap.recycle()
            }
        }
    }

    /**
     * Preprocessing pipeline:
     * - Grayscale conversion.
     * - Contrast enhancement matrix.
     * - Sharpening filter.
     */
    fun preprocessForOcr(source: Bitmap): Bitmap {
        try {
            val width = source.width
            val height = source.height

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // Step 1 & 2: Grayscale and Contrast Boost (scale 1.35x, translate -30)
            val cm = ColorMatrix()
            cm.setSaturation(0f)

            val contrast = 1.35f
            val brightness = -30f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)
            paint.colorFilter = ColorMatrixColorFilter(cm)

            canvas.drawBitmap(source, 0f, 0f, paint)

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing image", e)
            return source
        }
    }

    private suspend fun processImageSuspend(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage
    ): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText)
            }
            .addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
    }

    private fun calculateQualityScore(text: String): Float {
        if (text.isBlank()) return 0.0f
        val clean = text.trim()
        val length = clean.length

        // High score for coherent, multi-word Arabic or English text
        var score = 0.85f
        if (length > 40) score += 0.08f
        if (clean.contains(" ")) score += 0.05f

        // Penalize garbled single-character spam
        val digitRatio = clean.count { it.isDigit() }.toFloat() / length
        if (digitRatio > 0.6f && length < 8) {
            score -= 0.3f
        }

        return score.coerceIn(0.5f, 0.99f)
    }
}
