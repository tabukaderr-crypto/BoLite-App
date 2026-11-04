package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * TestScanActivity:
 *
 * Standalone verification Activity for "Bo Lite v10".
 * 1. Checks and requests MediaProjection screen capture permission.
 * 2. Takes a pristine visual screenshot.
 * 3. Runs Google ML Kit Text Recognition (Arabic & English supported).
 * 4. Logs output to Logcat under tag "THAER_FINAL_TEST".
 * 5. Displays raw extracted text directly on screen in real time.
 */
class TestScanActivity : ComponentActivity() {

    companion object {
        const val LOG_TAG = "THAER_FINAL_TEST"
    }

    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    // UI state observable by Compose
    private val rawOcrResultState = mutableStateOf("")
    private val statusMessageState = mutableStateOf("Ready to scan. Tap the button below.")
    private val isScanningState = mutableStateOf(false)
    private val isErrorState = mutableStateOf(false)

    // MediaProjection permission launcher
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            // Sync with global capture manager for background services
            com.example.capture.HybridCaptureManager.updateMediaProjectionPermission(
                result.resultCode,
                result.data
            )
            Log.d(LOG_TAG, "MediaProjection permission GRANTED by user.")
            statusMessageState.value = "Permission granted! Starting capture..."
            executeCaptureAndOcr()
        } else {
            isScanningState.value = false
            isErrorState.value = true
            val errorMsg = "ERROR: MediaProjection permission was DENIED by user."
            statusMessageState.value = errorMsg
            rawOcrResultState.value = errorMsg
            Log.e(LOG_TAG, errorMsg)
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore permission if already granted earlier in MainActivity
        if (com.example.capture.HybridCaptureManager.mediaProjectionResultCode != 0 &&
            com.example.capture.HybridCaptureManager.mediaProjectionData != null
        ) {
            mediaProjectionResultCode = com.example.capture.HybridCaptureManager.mediaProjectionResultCode
            mediaProjectionData = com.example.capture.HybridCaptureManager.mediaProjectionData
        }

        setContent {
            MaterialTheme {
                TestScanScreen(
                    rawText = rawOcrResultState.value,
                    statusText = statusMessageState.value,
                    isScanning = isScanningState.value,
                    isError = isErrorState.value,
                    onTestScanClicked = { startTestScanFlow() },
                    onClearClicked = {
                        rawOcrResultState.value = ""
                        statusMessageState.value = "Ready to scan."
                        isErrorState.value = false
                    }
                )
            }
        }
    }

    /**
     * Step 1: Initiates the scan flow by ensuring MediaProjection permission is granted.
     */
    private fun startTestScanFlow() {
        if (isScanningState.value) return

        isScanningState.value = true
        isErrorState.value = false
        rawOcrResultState.value = ""
        statusMessageState.value = "Checking screen capture permissions..."

        if (mediaProjectionResultCode != Activity.RESULT_OK || mediaProjectionData == null) {
            Log.d(LOG_TAG, "Requesting MediaProjection permission...")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                val err = "ERROR: MediaProjectionManager not available on this device"
                Log.e(LOG_TAG, err)
                showError(err)
                return
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            Log.d(LOG_TAG, "MediaProjection already granted. Proceeding directly to capture.")
            executeCaptureAndOcr()
        }
    }

    /**
     * Steps 2 & 3: Captures screen bitmap and runs ML Kit OCR.
     */
    @SuppressLint("WrongConstant")
    private fun executeCaptureAndOcr() {
        lifecycleScope.launch {
            try {
                statusMessageState.value = "Capturing screen frame..."
                val bitmap = captureScreenFrame()
                if (bitmap == null) {
                    showError("ERROR: Screen capture returned null bitmap. Please try again.")
                    return@launch
                }

                statusMessageState.value = "Running Google ML Kit OCR (Arabic & English)..."
                val extractedText = runMlKitOcr(bitmap)
                bitmap.recycle()

                // Step 4 & 5: Display RAW recognized text and log under THAER_FINAL_TEST
                Log.d(LOG_TAG, "================ [THAER_FINAL_TEST OCR RESULT] ================")
                Log.d(LOG_TAG, extractedText)
                Log.d(LOG_TAG, "===============================================================")

                isScanningState.value = false
                if (extractedText.isBlank()) {
                    showError("ERROR: ML Kit OCR finished but detected 0 characters. Make sure readable text is visible on screen.")
                } else {
                    isErrorState.value = false
                    statusMessageState.value = "SUCCESS! Extracted ${extractedText.length} characters."
                    rawOcrResultState.value = extractedText
                }
            } catch (t: Throwable) {
                val err = "ERROR: ${t.javaClass.simpleName} - ${t.message}"
                Log.e(LOG_TAG, err, t)
                showError(err)
            }
        }
    }

    /**
     * Takes ONE pristine screenshot of current screen using MediaProjection & ImageReader.
     */
    private suspend fun captureScreenFrame(): Bitmap? = withContext(Dispatchers.IO) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return@withContext null

        val projection: MediaProjection = try {
            projectionManager.getMediaProjection(
                mediaProjectionResultCode,
                mediaProjectionData!!.clone() as Intent
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to instantiate MediaProjection: ${e.message}", e)
            return@withContext null
        } ?: return@withContext null

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(480)
        val height = metrics.heightPixels.coerceAtLeast(800)
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ThaerTestScanDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )

            // Allow frame buffer to render
            delay(200)

            var latestImage: Image? = null
            for (retry in 0 until 6) {
                latestImage = imageReader.acquireLatestImage()
                if (latestImage != null) break
                delay(60)
            }

            if (latestImage == null) {
                Log.e(LOG_TAG, "ImageReader failed to acquire latest image frame")
                return@withContext null
            }

            val planes = latestImage.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val rawBitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            latestImage.close()

            // Crop excess row padding if hardware stride padded the row
            val cleanBitmap = if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }

            cleanBitmap
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Screen capture exception: ${e.message}", e)
            null
        } finally {
            virtualDisplay?.release()
            imageReader.close()
            projection.stop()
        }
    }

    /**
     * Executes Google ML Kit Text Recognition with Tesseract fallback for Arabic.
     */
    private suspend fun runMlKitOcr(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val mlKitText: String = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.w(LOG_TAG, "ML Kit default model failure: ${e.message}")
                    continuation.resume("")
                }
        }

        // Check if ML Kit captured substantial text including Arabic
        val hasArabic = mlKitText.any { it in '\u0600'..'\u06FF' }
        if (mlKitText.isNotBlank() && (hasArabic || mlKitText.length > 50)) {
            return@withContext mlKitText
        }

        // Dual-Engine Fallback: Tesseract for dual Arabic + English dictionary support
        val tesseractText = try {
            val lines = com.example.ocr.ScreenOcr.recognize(applicationContext, bitmap)
            lines.joinToString("\n") { it.text }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Tesseract fallback note: ${t.message}")
            ""
        }

        if (tesseractText.length > mlKitText.length) {
            tesseractText
        } else {
            mlKitText
        }
    }

    private fun showError(message: String) {
        isScanningState.value = false
        isErrorState.value = true
        statusMessageState.value = message
        rawOcrResultState.value = message
        Log.e(LOG_TAG, message)
    }
}

/**
 * Visual Compose layout for TestScanActivity.
 */
@Composable
fun TestScanScreen(
    rawText: String,
    statusText: String,
    isScanning: Boolean,
    isError: Boolean,
    onTestScanClicked: () -> Unit,
    onClearClicked: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Scaffold(
            containerColor = Color(0xFF121218)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Text(
                    text = "Bo Lite v10 - Visual OCR Test",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Single-frame MediaProjection + ML Kit OCR",
                    color = Color(0xFFB0B0C0),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // BIG BUTTON: TEST SCAN NOW
                Button(
                    onClick = onTestScanClicked,
                    enabled = !isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6F00),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("test_scan_now_button")
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "SCANNING SCREEN...",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "⚡ TEST SCAN NOW",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status Banner
                Surface(
                    color = if (isError) Color(0xFF3E1D1D) else Color(0xFF1E1E2C),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isError) Color(0xFFE53935) else Color(0xFF333348)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusText,
                            color = if (isError) Color(0xFFFF8A80) else Color(0xFF81C784),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Output Result Container
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RAW RECOGNIZED TEXT:",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row {
                        if (rawText.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("OCR_RAW", rawText)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Copy", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        OutlinedButton(
                            onClick = onClearClicked,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A10)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF282838))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        if (rawText.isBlank()) {
                            Text(
                                text = "Extracted text will appear here immediately after tapping 'TEST SCAN NOW'...\n\n(No node traversal. 100% direct screen capture + ML Kit OCR)",
                                color = Color(0xFF555566),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    text = rawText,
                                    color = if (isError) Color(0xFFFF5252) else Color(0xFFE0E0E0),
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scrollState)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
