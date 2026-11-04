package com.example.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import com.example.accessibility.EnhancedAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * HybridCaptureManager:
 * Coordinates the dual-engine screen capture:
 * 1. Primary Engine: MediaProjection API for high-resolution visual capture (OCR of images, styled text).
 * 2. Secondary Engine: AccessibilityService for instant metadata extraction (author, time, URL).
 * Enforces async execution, 2-second debounce cooldown, and strict bitmap memory management.
 */
object HybridCaptureManager {

    private const val TAG = "HybridCaptureManager"
    private const val DEBOUNCE_COOLDOWN_MS = 2000L

    // MediaProjection permission payload obtained from MainActivity
    var mediaProjectionResultCode: Int = 0
    var mediaProjectionData: Intent? = null

    private var activeMediaProjection: MediaProjection? = null
    private var lastTriggerTime: Long = 0L

    private val _isCaptureActive = MutableStateFlow(false)
    val isCaptureActive: StateFlow<Boolean> = _isCaptureActive.asStateFlow()

    private val _hasMediaProjectionPermission = MutableStateFlow(false)
    val hasMediaProjectionPermission: StateFlow<Boolean> = _hasMediaProjectionPermission.asStateFlow()

    fun updateMediaProjectionPermission(resultCode: Int, data: Intent?) {
        mediaProjectionResultCode = resultCode
        mediaProjectionData = data
        _hasMediaProjectionPermission.value = (resultCode != 0 && data != null)
        Log.i(TAG, "MediaProjection permission updated: granted=${_hasMediaProjectionPermission.value}")
    }

    /**
     * Checks if a scan trigger is debounced (prevents rapid double-tapping).
     */
    fun canTriggerScan(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEBOUNCE_COOLDOWN_MS) {
            Log.w(TAG, "Debounce rejected: Please wait ${DEBOUNCE_COOLDOWN_MS - (now - lastTriggerTime)}ms")
            return false
        }
        lastTriggerTime = now
        return true
    }

    /**
     * Primary Capture: Takes a high-resolution screenshot using MediaProjection on background thread.
     * Returns null if permission is not yet granted or on timeout, triggering the accessibility fallback.
     */
    suspend fun captureActiveScreen(
        context: Context,
        metrics: DisplayMetrics
    ): Bitmap? = withContext(Dispatchers.Default) {
        val resultCode = mediaProjectionResultCode
        val data = mediaProjectionData

        if (resultCode == 0 || data == null) {
            Log.w(TAG, "MediaProjection permission not ready. Fallback to Accessibility.")
            return@withContext null
        }

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return@withContext null

        _isCaptureActive.value = true

        try {
            val width = (metrics.widthPixels).coerceAtLeast(480)
            val height = (metrics.heightPixels).coerceAtLeast(800)
            val density = metrics.densityDpi

            // Create image reader for RGBA_8888 frames
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            val projection = try {
                projectionManager.getMediaProjection(resultCode, data.clone() as Intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MediaProjection", e)
                null
            } ?: return@withContext null

            activeMediaProjection = projection

            var virtualDisplay: VirtualDisplay? = null
            val handler = Handler(Looper.getMainLooper())

            val bitmap: Bitmap? = withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine { continuation ->
                    var captured = false

                    imageReader.setOnImageAvailableListener({ reader ->
                        if (captured) return@setOnImageAvailableListener
                        var image: Image? = null
                        try {
                            image = reader.acquireLatestImage()
                            if (image != null) {
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                val pixelStride = planes[0].pixelStride
                                val rowStride = planes[0].rowStride
                                val rowPadding = rowStride - pixelStride * width

                                val bmp = Bitmap.createBitmap(
                                    width + rowPadding / pixelStride,
                                    height,
                                    Bitmap.Config.ARGB_8888
                                )
                                bmp.copyPixelsFromBuffer(buffer)

                                // Crop out row padding if any
                                val finalBmp = if (rowPadding > 0) {
                                    val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                                    bmp.recycle()
                                    cropped
                                } else {
                                    bmp
                                }

                                captured = true
                                if (continuation.isActive) {
                                    continuation.resume(finalBmp)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error acquiring screenshot image", e)
                            if (continuation.isActive) continuation.resume(null)
                        } finally {
                            image?.close()
                        }
                    }, handler)

                    virtualDisplay = projection.createVirtualDisplay(
                        "BoLiteScreenCapture",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface,
                        null,
                        handler
                    )

                    continuation.invokeOnCancellation {
                        try {
                            virtualDisplay?.release()
                            projection.stop()
                            imageReader.close()
                        } catch (e: Exception) {
                            // ignore cleanup exceptions
                        }
                    }
                }
            }

            // Clean up projection pipeline
            try {
                virtualDisplay?.release()
                projection.stop()
                imageReader.close()
            } catch (e: Exception) {
                // ignore
            }

            return@withContext bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Exception during screen capture", e)
            null
        } finally {
            _isCaptureActive.value = false
        }
    }

    /**
     * Safely recycles a Bitmap immediately after OCR preprocessing to prevent memory leaks.
     */
    fun recycleBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recycling bitmap", e)
        }
    }

    /**
     * Fallback inspection via AccessibilityService when screenshot cannot be captured.
     */
    fun getAccessibilityActiveScreenSnapshot(): List<com.example.util.ParsedPostBlock> {
        val service = EnhancedAccessibilityService.activeInstance ?: return emptyList()
        val (_, blocks) = service.parseLiveScreen()
        return blocks
    }
}
