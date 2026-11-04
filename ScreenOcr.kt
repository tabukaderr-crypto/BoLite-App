package com.example.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/** One recognized line of text and where it sits on the screen (pixels). */
data class OcrLine(val text: String, val box: Rect, val confidence: Float)

/**
 * Reads text from a screenshot (Arabic + English) fully on the phone, no internet.
 * Google ML Kit cannot read Arabic, so Tesseract is used instead.
 */
object ScreenOcr {
    private val lock = Any()
    private var engine: TessBaseAPI? = null

    /**
     * Initializes Tesseract OCR engine by extracting Arabic ('ara') and English ('eng')
     * traineddata files from the assets directory to local filesDir/ocr/tessdata before usage.
     * Can be invoked proactively on background thread or lazily before recognition.
     */
    fun initialize(context: Context): Boolean {
        synchronized(lock) {
            return ensureEngine(context) != null
        }
    }

    /** Copies the language files out of the APK once, then starts the engine. */
    private fun ensureEngine(context: Context): TessBaseAPI? {
        engine?.let { return it }
        val base = File(context.filesDir, "ocr")
        val dir = File(base, "tessdata")
        if (!dir.exists()) {
            dir.mkdirs()
        }

        // Copy language traineddata files from APK assets
        for (lang in listOf("ara", "eng")) {
            val out = File(dir, "$lang.traineddata")
            if (!out.exists() || out.length() == 0L) {
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                    android.util.Log.i("ScreenOcr", "Extracted traineddata for $lang (${out.length()} bytes)")
                } catch (e: Exception) {
                    android.util.Log.w("ScreenOcr", "Could not load assets tessdata/$lang.traineddata: ${e.message}")
                }
            }
        }

        val hasAra = File(dir, "ara.traineddata").let { it.exists() && it.length() > 0 }
        val hasEng = File(dir, "eng.traineddata").let { it.exists() && it.length() > 0 }

        val langCode = when {
            hasAra && hasEng -> "ara+eng"
            hasAra -> "ara"
            hasEng -> "eng"
            else -> "ara+eng"
        }

        val api = TessBaseAPI()
        val success = try {
            api.init(base.absolutePath, langCode)
        } catch (t: Throwable) {
            android.util.Log.e("ScreenOcr", "TessBaseAPI init exception with $langCode", t)
            false
        }

        if (!success) {
            // Try fallback to whatever is available
            val fallbackSuccess = if (langCode == "ara+eng" && hasEng) {
                try { api.init(base.absolutePath, "eng") } catch (e: Exception) { false }
            } else false

            if (!fallbackSuccess) {
                try { api.recycle() } catch (e: Exception) {}
                android.util.Log.w("ScreenOcr", "Failed to initialize Tesseract with languages ($langCode).")
                return null
            }
        }

        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        engine = api
        android.util.Log.i("ScreenOcr", "Tesseract initialized successfully with language mode: $langCode")
        return api
    }

    /**
     * Makes the picture easier to read: grayscale, more contrast, and white-on-black
     * (dark mode) is flipped to black-on-white because the engine reads that best.
     */
    private fun preprocess(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(src, 32, 64, true)
        var sum = 0L
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val px = small.getPixel(x, y)
                sum += (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
            }
        }
        val mean = sum / (small.width * small.height)
        small.recycle()
        val matrix = ColorMatrix()
        matrix.setSaturation(0f)
        val contrast = 1.6f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift,
                    0f, 0f, contrast, 0f, shift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        if (mean < 110) {
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Reads every line of text in the screenshot, with its position on the screen. */
    fun recognize(context: Context, screenshot: Bitmap): List<OcrLine> {
        val top = (screenshot.height * 0.09f).toInt()
        val bottom = (screenshot.height * 0.07f).toInt()
        val cropped = Bitmap.createBitmap(
            screenshot, 0, top, screenshot.width, (screenshot.height - top - bottom).coerceAtLeast(1)
        )

        val tesseractLines = synchronized(lock) {
            val api = ensureEngine(context)
            if (api != null) {
                val processed = preprocess(cropped)
                val lines = mutableListOf<OcrLine>()
                try {
                    api.setImage(processed)
                    api.getUTF8Text() // runs the recognition
                    val iterator = api.getResultIterator()
                    if (iterator != null) {
                        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                        iterator.begin()
                        do {
                            val text = iterator.getUTF8Text(level)?.trim()
                            val confidence = iterator.confidence(level)
                            val r = iterator.getBoundingRect(level)
                            if (!text.isNullOrBlank() && confidence >= 35f) {
                                lines.add(
                                    OcrLine(text, Rect(r.left, r.top + top, r.right, r.bottom + top), confidence)
                                )
                            }
                        } while (iterator.next(level))
                        iterator.delete()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ScreenOcr", "Tesseract recognition error", e)
                } finally {
                    api.clear()
                    processed.recycle()
                }
                lines
            } else {
                emptyList()
            }
        }

        if (tesseractLines.isNotEmpty()) {
            cropped.recycle()
            return tesseractLines
        }

        // Secondary Fallback: ML Kit Text Recognition
        val mlKitLines = mutableListOf<OcrLine>()
        try {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
            )
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(cropped, 0)
            val task = recognizer.process(image)
            val visionText = com.google.android.gms.tasks.Tasks.await(task, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: Rect(0, 0, cropped.width, 30)
                    val shiftedBox = Rect(box.left, box.top + top, box.right, box.bottom + top)
                    mlKitLines.add(OcrLine(line.text.trim(), shiftedBox, 85f))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ScreenOcr", "ML Kit secondary fallback error: ${e.message}")
        } finally {
            cropped.recycle()
        }

        return mlKitLines
    }

    /**
     * Groups nearby lines into posts: lines that are close together vertically belong
     * to the same post; a big empty gap (picture, buttons, separator) starts a new one.
     */
    fun groupLinesIntoPosts(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.box.top }
        val heights = sorted.map { it.box.height() }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(10)
        val blocks = mutableListOf<MutableList<OcrLine>>()
        var current = mutableListOf<OcrLine>()
        var lastBottom = Int.MIN_VALUE
        for (line in sorted) {
            if (current.isNotEmpty() && line.box.top - lastBottom > medianHeight * 1.8) {
                blocks.add(current)
                current = mutableListOf()
            }
            current.add(line)
            lastBottom = maxOf(lastBottom, line.box.bottom)
        }
        if (current.isNotEmpty()) blocks.add(current)
        return blocks
            .map { block -> block.joinToString("\n") { it.text } }
            .filter { isUsefulText(it) }
    }

    private fun isUsefulText(text: String): Boolean {
        val clean = text.trim()
        val letters = clean.count { it.isLetter() }
        if (clean.length < 18 || letters < 10) return false
        return com.example.util.PostSanitizer.isValidPostContent(clean)
    }
}
