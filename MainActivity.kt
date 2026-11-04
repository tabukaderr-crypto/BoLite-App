package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import com.example.util.FuzzySearchUtility
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SavedCard
import com.example.ui.theme.BgDark
import com.example.ui.theme.BoLiteTheme
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CardDark
import com.example.ui.theme.GrayButton
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.TextHint
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite

import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.accessibility.EnhancedAccessibilityService
import com.example.accessibility.buildScreenTree
import com.example.accessibility.performOcrScanFallback
import com.example.reader.FeedReader
import com.example.overlay.OverlayPermissionHelper

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var hasOverlayPermissionState by mutableStateOf(false)
    private var hasAccessibilityPermissionState by mutableStateOf(false)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            viewModel.updateMediaProjectionPermission(result.resultCode, result.data)
            Toast.makeText(this, "تم تفعيل محرك الالتقاط البصري الهجين (MediaProjection) بنجاح ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "تم إلغاء الإذن - سيعمل التطبيق عبر محرك إمكانية الوصول البديل", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mediaProjectionManager != null) {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(this, "خدمة التقاط الشاشة غير متوفرة على هذا الجهاز", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        checkPermissions()

        setContent {
            BoLiteTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BgDark)
                    ) {
                        // 2. وضع الزر في واجهة التطبيق الرئيسية مؤقتًا لرؤية النتيجة
                        TestBoLiteReaderButton()

                        Box(modifier = Modifier.weight(1f)) {
                            BoLiteScreen(
                                viewModel = viewModel,
                                hasOverlayPermission = hasOverlayPermissionState,
                                hasAccessibilityPermission = hasAccessibilityPermissionState,
                                onRequestOverlayPermission = { OverlayPermissionHelper.requestPermission(this@MainActivity) },
                                onRequestMediaProjectionPermission = { requestMediaProjection() },
                                onRequestAccessibilityPermission = { EnhancedAccessibilityService.openAccessibilitySettings(this@MainActivity) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun checkPermissions() {
        hasOverlayPermissionState = OverlayPermissionHelper.hasPermission(this)
        hasAccessibilityPermissionState = EnhancedAccessibilityService.isServiceEnabled(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.updateInputText(sharedText)
            }
        }
    }
}

/**
 * زر اختبار سريع لمحرك قراءة منشورات Bo Lite v15
 * يقوم بفحص الهيكل الشجري ثم التحويل لـ OCR في حال عدم العثور على منشورات هيكلية
 */
@Composable
fun TestBoLiteReaderButton() {
    var resultText by remember { mutableStateOf("اضغط للاختبار") }
    val scope = rememberCoroutineScope()

    Surface(
        color = CardDark,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    // استدعاء محرك القراءة النقي من ملف BoLite-AI-Code.md
                    val rootNode = buildScreenTree() // تأكد من وجود هذه الدالة في الخدمة

                    if (rootNode != null) {
                        // استخدام FeedReader لقراءة المنشورات بدقة هيكلية
                        val posts = com.example.reader.FeedReader.readPosts(rootNode)

                        if (posts.isNotEmpty()) {
                            resultText = "✅ نجح! تم قراءة ${posts.size} منشور.\n\n" +
                                posts.first().text.take(200) // عرض أول 200 حرف للتأكد
                        } else {
                            resultText = "❌ فشل القراءة الهيكلية. جاري تجربة OCR..."

                            // إذا فشلت القراءة الهيكلية، ننتقل لـ OCR كخطة بديلة فورية
                            scope.launch(Dispatchers.IO) {
                                val ocrResult = performOcrScanFallback() // دالة وهمية هنا لتوضيح الفكرة
                                withContext(Dispatchers.Main) {
                                    resultText = if (ocrResult.isNotEmpty()) " OCR نجح:\n$ocrResult" else " كل الطرق فشلت."
                                }
                            }
                        }
                    } else {
                        resultText = "⚠️ لم يتم العثور على شاشة فيسبوك صالحة."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary),
                modifier = Modifier.testTag("test_bo_lite_reader_button")
            ) {
                Text("اختبار محرك Bo Lite v15", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                resultText,
                modifier = Modifier.padding(8.dp),
                color = TextWhite,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoLiteScreen(
    viewModel: MainViewModel,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onRequestMediaProjectionPermission: () -> Unit,
    onRequestAccessibilityPermission: () -> Unit
) {
    val context = LocalContext.current
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filteredPosts by viewModel.filteredPosts.collectAsStateWithLifecycle()
    val rawPosts by viewModel.rawPosts.collectAsStateWithLifecycle()
    val detectedGroupName by viewModel.detectedGroupName.collectAsStateWithLifecycle()
    val selectedPostForDetail by viewModel.selectedPostForDetail.collectAsStateWithLifecycle()
    val isFacebookGroupActive by viewModel.isFacebookGroupActive.collectAsStateWithLifecycle()
    val isSimulatedFacebookGroup by viewModel.isSimulatedFacebookGroup.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val scanProgressText by viewModel.scanProgressText.collectAsStateWithLifecycle()
    val scanSessionId by viewModel.scanSessionId.collectAsStateWithLifecycle()
    val hasMediaProjectionPermission by viewModel.hasMediaProjectionPermission.collectAsStateWithLifecycle()

    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val currentCard by viewModel.generatedCard.collectAsStateWithLifecycle()
    val savedCount by viewModel.savedCount.collectAsStateWithLifecycle()
    val savedCards by viewModel.savedCards.collectAsStateWithLifecycle()
    val isSavedListVisible by viewModel.isSavedListVisible.collectAsStateWithLifecycle()
    val watchKeywords by viewModel.watchKeywords.collectAsStateWithLifecycle()
    val isWatchListSheetOpen by viewModel.isWatchListSheetOpen.collectAsStateWithLifecycle()
    val feedbackMessage by viewModel.feedbackMessage.collectAsStateWithLifecycle()

    val isBubbleRunning by FloatingBubbleService.isRunning.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearFeedbackMessage()
        }
    }

    if (isSavedListVisible) {
        BackHandler {
            viewModel.hideSavedList()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("bo_lite_main_scaffold"),
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar: Saved count & Bubble toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "المحفوظات: $savedCount",
                        color = OrangePrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .testTag("saved_count_button")
                            .clickable { viewModel.showSavedList() }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    )

                    Text(
                        text = "🔔 المراقبة (${watchKeywords.size})",
                        color = if (watchKeywords.isNotEmpty()) OrangePrimary else TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .testTag("watchlist_button")
                            .clickable { viewModel.openWatchListSheet() }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    )
                }

                if (hasOverlayPermission) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("bubble_toggle_container")
                    ) {
                        Text(
                            text = "الفقاعة",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Switch(
                            checked = isBubbleRunning,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    OverlayPermissionHelper.startServiceIfPermitted(context)
                                } else {
                                    FloatingBubbleService.stop(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = OrangePrimary,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = GrayButton
                            ),
                            modifier = Modifier.testTag("bubble_toggle_switch")
                        )
                    }
                }
            }

            // ONBOARDING & PERMISSION HUB: Explaining Hybrid Capture Engine clearly in Arabic
            if (!hasMediaProjectionPermission || !hasAccessibilityPermission || !hasOverlayPermission) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .testTag("onboarding_permissions_hub"),
                    shape = RoundedCornerShape(14.dp),
                    color = CardDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "🛡️ إعدادات محرك الفحص الذكي الهجين",
                                color = TextWhite,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 1. MediaProjection OCR Engine
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "1. التقاط الشاشة البصري (ML Kit OCR)",
                                    color = if (hasMediaProjectionPermission) Color(0xFF81C784) else OrangePrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "لقراءة المنشورات بدقة فائقة من الصور وتصاميم فيسبوك أوفلاين.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            if (hasMediaProjectionPermission) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("مفعّل ✓", color = Color(0xFF81C784), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { context.startActivity(Intent(context, TestScanActivity::class.java)) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C3C)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("تجربة الفحص ⚡", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            } else {
                                Button(
                                    onClick = onRequestMediaProjectionPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("تفعيل", fontSize = 14.sp)
                                }
                            }
                        }

                        HorizontalDivider(color = BorderStroke.copy(alpha = 0.5f))

                        // 2. Accessibility Metadata Engine
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "2. خدمة الوصول (الناشر والوقت)",
                                    color = if (hasAccessibilityPermission) Color(0xFF81C784) else OrangePrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "لربط أسماء الناشرين وأوقات النشر الدقيقة بالمنشورات المكتشفة.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                            if (hasAccessibilityPermission) {
                                Text("مفعّل ✓", color = Color(0xFF81C784), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Button(
                                    onClick = onRequestAccessibilityPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("تفعيل", fontSize = 14.sp)
                                }
                            }
                        }

                        if (!hasOverlayPermission) {
                            HorizontalDivider(color = BorderStroke.copy(alpha = 0.5f))

                            // 3. Floating Overlay
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "3. الفقاعة العائمة (56dp)",
                                        color = OrangePrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "لفتح الفحص بلمسة واحدة فوق تطبيق فيسبوك مباشرة.",
                                        color = TextMuted,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    )
                                }
                                Button(
                                    onClick = onRequestOverlayPermission,
                                    colors = ButtonDefaults.buttonColors(containerColor = OrangePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("تفعيل", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // App Title
            Text(
                text = "بو لايت",
                color = TextWhite,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_title")
                    .padding(bottom = 16.dp)
            )

            // ========================================================
            // THE NEW SINGLE FLOW
            // ========================================================
            when (scanState) {
                // STEP 1: THE TRIGGER BUTTON
                // ONE prominent button labeled: "ابدأ البحث في هذه الصفحة"
                // No other visible controls initially. Clean interface.
                is PageScanState.Idle -> {
                    if (!isFacebookGroupActive) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = OrangePrimary.copy(alpha = 0.15f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Groups,
                                        contentDescription = null,
                                        tint = OrangePrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "يرجى فتح مجموعة فيسبوك أولاً",
                                color = TextWhite,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "الميزة مخصصة لمجموعات فيسبوك فقط (Facebook Groups). يتم تجاهل الصفحات والريلز وموجز الأخبار والماركت بليس.",
                                color = TextMuted,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { /* Disabled */ },
                                enabled = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .testTag("start_page_search_button_disabled"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = Color(0xFF282836),
                                    disabledContentColor = TextMuted
                                )
                            ) {
                                Text(
                                    text = "ابدأ البحث في هذه الصفحة",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.toggleFacebookGroupSimulation() },
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                            ) {
                                Text(
                                    text = "تجربة محاكاة مجموعة فيسبوك",
                                    color = OrangePrimary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1E1E2C),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Text(
                                    text = "📍 $detectedGroupName • آخر 7 أيام",
                                    color = OrangePrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }

                            Text(
                                text = "جاهز لقراءة منشورات المجموعة خلال آخر 7 أيام فوراً والبحث المباشر بها",
                                color = TextMuted,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )

                            Button(
                                onClick = { viewModel.startPageSearch() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .testTag("start_page_search_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OrangePrimary,
                                    contentColor = TextWhite
                                )
                            ) {
                                Text(
                                    text = "ابدأ البحث في هذه الصفحة",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // STEP 2: THE SCAN & LOAD
                // Real cumulative indexing progress: "Indexed: X posts | Round Y/Z"
                is PageScanState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(54.dp),
                            color = OrangePrimary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = scanProgressText.ifBlank { "Snapshot 1/8 | Saved: 0" },
                            color = TextWhite,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "فحص سريع متعدد اللقطات وحفظ فوري في قاعدة البيانات",
                            color = TextMuted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // STEP 3 & STEP 4: DISPLAY MAP + INSTANT CLIENT-SIDE FILTERING
                is PageScanState.Loaded -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        // Fixed Group Name Header at the very top of the result list view with 7-day badge
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .testTag("group_name_fixed_header"),
                            shape = RoundedCornerShape(10.dp),
                            color = CardDark,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "📍 ${if (detectedGroupName.isNotBlank()) detectedGroupName else "مجموعة فيسبوك"} • أرشيف محلي",
                                    color = OrangePrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${filteredPosts.size} منشور محفوظ",
                                    color = TextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // STEP 4: Search Input Field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchQueryChange(it) },
                            placeholder = {
                                Text(
                                    text = "ابحث في أرشيف المجموعة الكامل...",
                                    color = TextHint,
                                    fontSize = 17.sp
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("page_search_input_field"),
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                color = TextWhite,
                                textAlign = TextAlign.Start
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = CardDark,
                                unfocusedContainerColor = CardDark,
                                focusedBorderColor = OrangePrimary,
                                unfocusedBorderColor = BorderStroke,
                                cursorColor = OrangePrimary
                            ),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    "النتائج: ${filteredPosts.size} من ${rawPosts.size}"
                                } else {
                                    "خريطة المنشورات (${filteredPosts.size})"
                                },
                                color = OrangePrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Text(
                                text = "مسح جديد ↻",
                                color = TextMuted,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { viewModel.startPageSearch() }
                                    .padding(4.dp)
                            )
                        }

                        // STEP 3: Scrollable list showing EVERY post found
                        if (filteredPosts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Text(
                                        text = statusMessage ?: "No new content found. Try scrolling manually first.",
                                        color = TextWhite,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "لم يتم العثور على محتوى جديد. حاول التمرير يدوياً أولاً",
                                        color = OrangePrimary,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("content_map_posts_list"),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(items = filteredPosts, key = { "${it.id}_$scanSessionId" }) { post ->
                                    var isExpanded by remember { mutableStateOf(false) }

                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        val confidenceBorder = when {
                                            post.ocrConfidence >= 0.90f -> Color(0xFF4CAF50).copy(alpha = 0.50f)
                                            post.ocrConfidence >= 0.70f -> OrangePrimary.copy(alpha = 0.50f)
                                            else -> BorderStroke
                                        }

                                        val insight = com.example.util.PostInsights.analyze(post.text)
                                        val tagLabel = when {
                                            insight.kind != com.example.util.PostKind.OTHER -> "#${insight.kind.label}"
                                            !insight.course.isNullOrBlank() -> "#مادة_${insight.course.replace(' ', '_')}"
                                            !insight.doctor.isNullOrBlank() -> "#د_${insight.doctor.replace(' ', '_')}"
                                            post.category.isNotBlank() && post.category != "عام" -> "#${post.category}"
                                            else -> "#عام"
                                        }

                                        AnimatedVisibility(
                                            visible = true,
                                            enter = fadeIn(animationSpec = tween(350)) +
                                                    slideInVertically(
                                                        animationSpec = tween(350),
                                                        initialOffsetY = { 30 }
                                                    )
                                        ) {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("main_post_card_${post.id}")
                                                    .clickable { viewModel.selectPostForDetail(post) },
                                                shape = RoundedCornerShape(16.dp),
                                                color = CardDark,
                                                border = androidx.compose.foundation.BorderStroke(1.dp, confidenceBorder)
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    // ==========================================
                                                    // 1. HEADER ROW: Distinct Visual Separation
                                                    // ==========================================
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            // Circular Avatar Placeholder (size = 40.dp)
                                                            Surface(
                                                                shape = CircleShape,
                                                                color = OrangePrimary.copy(alpha = 0.22f),
                                                                border = androidx.compose.foundation.BorderStroke(1.dp, OrangePrimary.copy(alpha = 0.45f)),
                                                                modifier = Modifier.size(40.dp)
                                                            ) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    val initial = post.authorName.trim().firstOrNull()?.toString() ?: "👤"
                                                                    Text(
                                                                        text = initial,
                                                                        color = OrangePrimary,
                                                                        fontSize = 18.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }

                                                            Spacer(modifier = Modifier.width(10.dp))

                                                            Column {
                                                                Text(
                                                                    text = post.authorName,
                                                                    color = Color.White,
                                                                    fontSize = 16.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )

                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                ) {
                                                                val resolvedTag = com.example.util.PostSanitizer.resolveCleanTag(post.postContent, post.categoryTag)
                                                                if (resolvedTag != null) {
                                                                    Text(
                                                                        text = resolvedTag,
                                                                        color = Color.Gray,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = FontWeight.Medium
                                                                    )
                                                                }

                                                                    if (!post.timestampRaw.isNullOrBlank()) {
                                                                        if (resolvedTag != null) {
                                                                            Text(text = "•", color = Color.Gray, fontSize = 10.sp)
                                                                        }
                                                                        Text(
                                                                            text = post.timestampRaw!!,
                                                                            color = Color.Gray,
                                                                            fontSize = 12.sp,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        if (post.isMediaRich) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF2C2C3C)
                                                            ) {
                                                                Text(
                                                                    text = "📸 صورة",
                                                                    color = OrangePrimary,
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    HorizontalDivider(
                                                        color = BorderStroke.copy(alpha = 0.5f),
                                                        thickness = 0.8.dp,
                                                        modifier = Modifier.padding(vertical = 4.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))

                                                    // ==========================================
                                                    // 2. BODY SECTION: Readable content ONLY (24sp line spacing)
                                                    // ==========================================
                                                    val isContentValid = com.example.util.PostSanitizer.isValidPostContent(post.postContent)

                                                    if (!isContentValid) {
                                                        Text(
                                                            text = "Content could not be read clearly",
                                                            color = Color(0xFFB0B0B0),
                                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                            fontSize = 14.sp,
                                                            lineHeight = 22.sp,
                                                            textAlign = TextAlign.Start,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 4.dp)
                                                        )
                                                    } else {
                                                        val cleanContent = post.postContent
                                                        val isLongText = cleanContent.length > 300 || cleanContent.lines().size > 4
                                                        val previewText = if (isExpanded) cleanContent else cleanContent.take(300)

                                                        val annotatedText = FuzzySearchUtility.buildHighlightedAnnotatedString(
                                                            originalText = previewText,
                                                            matchedRanges = post.matchedRanges,
                                                            highlightColor = OrangePrimary
                                                        )

                                                        Text(
                                                            text = annotatedText,
                                                            color = TextWhite,
                                                            fontSize = 15.sp,
                                                            lineHeight = 24.sp,
                                                            maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                                                            overflow = TextOverflow.Ellipsis,
                                                            textAlign = TextAlign.Start,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(max = if (isExpanded) 1000.dp else 95.dp)
                                                                .padding(vertical = 4.dp)
                                                        )

                                                        if (isLongText) {
                                                            Text(
                                                                text = if (isExpanded) "عرض أقل ▲" else "...عرض المزيد ▼",
                                                                color = OrangePrimary,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                modifier = Modifier
                                                                    .clickable { isExpanded = !isExpanded }
                                                                    .padding(vertical = 2.dp)
                                                            )
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(6.dp))

                                                    // ==========================================
                                                    // 3. FOOTER ACTIONS: Minimalist icons
                                                    // ==========================================
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // Right / Start: Copy action icon
                                                        IconButton(
                                                            onClick = {
                                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(ClipData.newPlainText("BoLite Post", post.postContent))
                                                                Toast.makeText(context, "تم النسخ ✓", Toast.LENGTH_SHORT).show()
                                                            },
                                                            modifier = Modifier
                                                                .size(34.dp)
                                                                .testTag("copy_post_icon")
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.ContentCopy,
                                                                contentDescription = "نسخ النص",
                                                                tint = Color.LightGray,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }

                                                        // Left / End: Bookmark button
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.savePostToNotebook(post) {
                                                                    Toast.makeText(context, "تم الحفظ للدفتر ✓", Toast.LENGTH_SHORT).show()
                                                                }
                                                            },
                                                            modifier = Modifier
                                                                .size(34.dp)
                                                                .testTag("save_to_notebook_icon")
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.BookmarkBorder,
                                                                contentDescription = "احفظ للدفتر",
                                                                tint = Color.LightGray,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Share text / input fallback if shared text was received
            if (inputText.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.convertToCard() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("convert_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GrayButton,
                        contentColor = TextWhite
                    )
                ) {
                    Text("تحويل النص المشترك إلى بطاقة", fontSize = 17.sp)
                }

                currentCard?.let { card ->
                    Spacer(modifier = Modifier.height(12.dp))
                    GeneratedCardView(
                        card = card,
                        onSave = { viewModel.saveCurrentCard() },
                        onClear = { viewModel.clearCurrentCard() }
                    )
                }
            }
        }
    }

    // DETAILED POST VIEW ON TAP: Modal bottom sheet titled "تفاصيل المنشور"
    if (selectedPostForDetail != null) {
        val post = selectedPostForDetail!!
        ModalBottomSheet(
            onDismissRequest = { viewModel.closePostDetail() },
            containerColor = Color(0xFF14141E),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            modifier = Modifier.testTag("main_post_detail_bottom_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Title and close button "✕"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = OrangePrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = OrangePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "تفاصيل المنشور",
                            color = TextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { viewModel.closePostDetail() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "✕ إغلاق",
                            tint = TextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Post Author & Exact Timestamp Bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E1E2C),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "👤 ${post.author ?: "عضو في المجموعة"}",
                                color = OrangePrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = OrangePrimary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = post.category,
                                    color = OrangePrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🕒 ${post.timestamp ?: "حديث"} • آخر 7 أيام",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            if (post.groupName.isNotBlank()) {
                                Text(
                                    text = "  |  📍 ${post.groupName}",
                                    color = TextHint,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Full Post Text
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF191924),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "نص المنشور الكامل:",
                            color = TextHint,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = post.fullText,
                            color = TextWhite,
                            fontSize = 16.sp,
                            lineHeight = 25.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Comments Section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = OrangePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "التعليقات والردود (${post.comments.size})",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (post.comments.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1A1A26),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "لا توجد ردود إضافية مرئية لهذا المنشور في الشاشة",
                            color = TextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (comment in post.comments) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1E1E2C),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, BorderStroke),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "👤 ${comment.author}",
                                            color = OrangePrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (!comment.timestamp.isNullOrBlank()) {
                                            Text(
                                                text = comment.timestamp,
                                                color = TextHint,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        comment.text,
                                        color = TextWhite,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons: Save & Copy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.savePostToNotebook(post) {
                                Toast.makeText(context, "تم الحفظ للدفتر ✓", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OrangePrimary,
                            contentColor = TextWhite
                        ),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("احفظ للدفتر", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("BoLite Post Detail", post.fullText))
                            Toast.makeText(context, "تم نسخ النص الكامل ✓", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, tint = TextWhite, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("نسخ النص", color = TextWhite, fontSize = 15.sp)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Modal Sheet showing plain list of saved items
    if (isSavedListVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideSavedList() },
            sheetState = sheetState,
            containerColor = CardDark,
            dragHandle = null,
            modifier = Modifier.testTag("saved_list_sheet")
        ) {
            SavedItemsListView(
                savedCards = savedCards,
                onClose = { viewModel.hideSavedList() },
                onDelete = { id -> viewModel.deleteSavedCard(id) }
            )
        }
    }

    // Watchlist Management Dialog (for notifications on keywords)
    if (isWatchListSheetOpen) {
        com.example.ui.WatchListDialog(
            keywords = watchKeywords,
            onAddKeyword = { viewModel.addWatchKeyword(it) },
            onRemoveKeyword = { viewModel.removeWatchKeyword(it) },
            onDismiss = { viewModel.closeWatchListSheet() }
        )
    }
}

@Composable
fun GeneratedCardView(
    card: GeneratedCard,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_container"),
        shape = RoundedCornerShape(20.dp),
        color = CardDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Category Chip
            CategoryChip(
                category = card.category,
                modifier = Modifier.testTag("card_category_chip")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // First 60 characters of the text as the card title
            Text(
                text = card.title,
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_title")
            )

            // Optional full text preview if longer than title
            if (card.fullText.length > 60) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = card.fullText,
                    color = TextMuted,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Two full-width buttons: "حفظ" (orange) and "مسح" (gray)
            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("card_save_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OrangePrimary,
                    contentColor = TextWhite
                )
            ) {
                Text(
                    text = "حفظ",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("card_clear_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GrayButton,
                    contentColor = TextWhite
                )
            ) {
                Text(
                    text = "مسح",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun CategoryChip(
    category: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(OrangePrimary.copy(alpha = 0.16f))
            .border(1.dp, OrangePrimary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = category,
            color = OrangePrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SavedItemsListView(
    savedCards: List<SavedCard>,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "المحفوظات (${savedCards.size})",
                color = TextWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("close_list_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "إغلاق",
                    tint = TextWhite,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = BorderStroke, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        if (savedCards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "لا توجد عناصر محفوظة حتى الآن",
                    color = TextMuted,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(savedCards, key = { it.id }) { item ->
                    SavedCardRow(
                        item = item,
                        onDelete = { onDelete(item.id) },
                        onCopy = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Card Text", item.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "تم نسخ النص", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SavedCardRow(
    item: SavedCard,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val title = if (item.text.length > 60) item.text.substring(0, 60) else item.text

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BgDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved_item_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CategoryChip(category = item.category)

                Row {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ",
                            tint = TextMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف",
                            tint = Color(0xFFFF4D4F),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 26.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            if (item.text.length > 60) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.text,
                    color = TextMuted,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
