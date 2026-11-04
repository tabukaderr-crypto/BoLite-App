package com.example.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.accessibility.BoLiteAccessibilityService
import com.example.data.AppDatabase
import com.example.data.ArchivedPost
import com.example.ui.theme.BgDark
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CardDark
import com.example.ui.theme.OrangePrimary
import com.example.ui.theme.TextHint
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextWhite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * BoLiteOverlayService:
 * Complete Live Mini-Dashboard Floating Overlay with Focused Reading Mode.
 *
 * 1. DATA SOURCE:
 *    Directly connects to AppDatabase.getDatabase(context).postDao().getPosts()
 *    via a dedicated Service CoroutineScope collecting the Room Flow continuously into local StateFlow.
 * 2. EXPANDED VIEW UI (State Machine):
 *    - Mode B1 (List Dashboard):
 *      - Top 10 most recent posts displayed in a scrollable LazyColumn.
 *      - Real-time search filter TextField at the top.
 *      - Each item shows truncated Content, Timestamp, and Category badge.
 *      - Quick 1-tap scan trigger button.
 *    - Mode B2 (Focused Reading Detail View):
 *      - Tapping any item switches immediately to distraction-free reading without opening MainActivity.
 *      - Scrollable full text content + prominently displayed author & timestamp.
 *      - Formatted threaded comments and discussions.
 *      - Top-left "Back to List" navigation button + 1-tap Copy text action.
 * 3. GESTURE & WINDOW MANAGEMENT:
 *    - Mode A: Small Draggable Circle.
 *    - Trigger: Double-Tap or Swipe-Up expands into a Rectangular Card (~82% screen width, max 60% height).
 *    - Collapse: Close button ('X') or tapping outside (FLAG_WATCH_OUTSIDE_TOUCH) collapses back to circle.
 *    - Focusable when expanded for keyboard input.
 *    - Lifecycle: Scope cancelled in onDestroy() to prevent memory leaks.
 */
class BoLiteOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlayComposeView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // Real-time live data collected continuously from Room DB via CoroutineScope
    private val recentPostsStateFlow = MutableStateFlow<List<ArchivedPost>>(emptyList())

    // State holding whether the overlay is currently in expanded card mode or circular bubble mode
    private val isExpandedModeFlow = MutableStateFlow(false)

    companion object {
        private const val TAG = "BoLiteOverlayService"
        private const val CHANNEL_ID = "bo_lite_overlay_channel"
        private const val NOTIFICATION_ID = 4002

        val isOverlayActiveFlow = MutableStateFlow(false)

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, BoLiteOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BoLiteOverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        startForeground(NOTIFICATION_ID, createNotification())
        isOverlayActiveFlow.value = true

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // Continuous local Flow collection from Room Database
        startDatabaseObservation()

        setupOverlayView()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * Rule 1: Directly collects Room Flow continuously in Service CoroutineScope.
     */
    private fun startDatabaseObservation() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.postDao().getPosts().collectLatest { postList ->
                    Log.d(TAG, "Room Flow received update: ${postList.size} posts")
                    recentPostsStateFlow.value = postList
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting posts Flow from AppDatabase", e)
            }
        }
    }

    private fun getBubbleSizePx(): Int = (58 * resources.displayMetrics.density).toInt()

    private fun getExpandedWidthPx(): Int {
        val displayWidth = resources.displayMetrics.widthPixels
        val calculated = (displayWidth * 0.85f).toInt()
        val minPx = (300 * resources.displayMetrics.density).toInt()
        val maxPx = (420 * resources.displayMetrics.density).toInt()
        return calculated.coerceIn(minPx, maxPx)
    }

    private fun getExpandedHeightPx(): Int {
        val displayHeight = resources.displayMetrics.heightPixels
        val calculated = (displayHeight * 0.62f).toInt()
        val minPx = (380 * resources.displayMetrics.density).toInt()
        val maxPx = (560 * resources.displayMetrics.density).toInt()
        return calculated.coerceIn(minPx, maxPx)
    }

    private fun setupOverlayView() {
        val wm = windowManager ?: return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bubbleSize = getBubbleSizePx()

        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        windowParams = params

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BoLiteOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BoLiteOverlayService)
            setContent {
                val isScanning by BoLiteAccessibilityService.isScanningFlow.collectAsState()
                val isExpanded by isExpandedModeFlow.collectAsState()
                val livePosts by recentPostsStateFlow.collectAsState()

                if (isExpanded) {
                    ExpandedMiniCardContent(
                        posts = livePosts,
                        onClose = { collapseToBubble() },
                        onToggleScan = { BoLiteAccessibilityService.toggleScan(applicationContext) },
                        isScanning = isScanning
                    )
                } else {
                    FloatingBubbleContent(
                        isScanning = isScanning,
                        onExpandRequest = { expandToCard() }
                    )
                }
            }
        }

        // Dedicated GestureDetector for Double-Tap, Swipe-Up (Fling), Single-Tap, and Long-Press
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isExpandedModeFlow.value) {
                    Log.d(TAG, "onSingleTapConfirmed: Toggling scan via broadcast")
                    BoLiteAccessibilityService.toggleScan(applicationContext)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isExpandedModeFlow.value) {
                    Log.d(TAG, "onDoubleTap: Expanding to Mini-Dashboard")
                    expandToCard()
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (!isExpandedModeFlow.value && e1 != null) {
                    val deltaY = e2.rawY - e1.rawY
                    val deltaX = e2.rawX - e1.rawX
                    if (deltaY < -35 && abs(deltaY) > abs(deltaX) && velocityY < -120) {
                        Log.d(TAG, "onSwipeUp: Expanding to Mini-Dashboard")
                        expandToCard()
                        return true
                    }
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isExpandedModeFlow.value) {
                    Log.d(TAG, "onLongPress: Closing overlay")
                    Toast.makeText(applicationContext, "تم إغلاق الزر العائم | Overlay Hidden", Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            }
        })

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        composeView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // Collapse when tapping outside in expanded card mode
                if (isExpandedModeFlow.value) {
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        Log.d(TAG, "Outside touch detected: Collapsing to bubble")
                        collapseToBubble()
                        return true
                    }
                    return false
                }

                // Bubble Mode: First give GestureDetector priority
                val gestureHandled = gestureDetector.onTouchEvent(event)
                val currentParams = windowParams ?: return gestureHandled

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = currentParams.x
                        initialY = currentParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            currentParams.x = initialX + dx
                            currentParams.y = initialY + dy
                            try {
                                wm.updateViewLayout(v, currentParams)
                            } catch (ignored: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!gestureHandled) {
                            val totalDeltaY = event.rawY - initialTouchY
                            val totalDeltaX = event.rawX - initialTouchX
                            if (totalDeltaY < -50 && abs(totalDeltaY) > abs(totalDeltaX) * 1.3) {
                                Log.d(TAG, "onSwipeUp (displacement fallback): Expanding to Mini-Dashboard")
                                expandToCard()
                                return true
                            }
                        }
                        isDragging = false
                        return true
                    }
                }
                return gestureHandled
            }
        })

        overlayComposeView = composeView
        try {
            wm.addView(composeView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view to WindowManager", e)
            stopSelf()
        }
    }

    /**
     * Expands the overlay into the interactive Mini-Card dashboard.
     * Uses FLAG_WATCH_OUTSIDE_TOUCH and allows focus for real-time keyboard search input.
     */
    fun expandToCard() {
        Log.d(TAG, "expandToCard: Transitioning to Expanded Mini-Dashboard")
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = windowParams ?: return

        isExpandedModeFlow.value = true

        params.width = getExpandedWidthPx()
        params.height = getExpandedHeightPx()
        // Focusable for keyboard text input + watch outside touches to auto-collapse
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            wm.updateViewLayout(view, params)
            Log.d(TAG, "expandToCard: Window layout updated to ${params.width}x${params.height}")
        } catch (e: Exception) {
            Log.e(TAG, "expandToCard: Error updating view layout", e)
        }
    }

    /**
     * Collapses back to the small circular bubble.
     */
    fun collapseToBubble() {
        Log.d(TAG, "collapseToBubble: Transitioning to Compact Bubble Mode")
        val wm = windowManager ?: return
        val view = overlayComposeView ?: return
        val params = windowParams ?: return

        isExpandedModeFlow.value = false

        val bubbleSize = getBubbleSizePx()
        params.width = bubbleSize
        params.height = bubbleSize
        // Remove focusable flags so touches pass through around the circular bubble
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        try {
            wm.updateViewLayout(view, params)
            Log.d(TAG, "collapseToBubble: Window layout updated to ${params.width}x${params.height}")
        } catch (e: Exception) {
            Log.e(TAG, "collapseToBubble: Error updating view layout", e)
        }
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Bo Lite Quick Scan Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating shortcut for educational post reading"
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("بو لايت | Bo Lite Live Dashboard")
            .setContentText("الزر العائم نشط. انقر للفحص أو انقر مرتين لفتح لوحة البيانات.")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isOverlayActiveFlow.value = false

        // Cancel CoroutineScope to prevent background memory leaks
        serviceScope.cancel()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        overlayComposeView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (ignored: Exception) {}
        }
        overlayComposeView = null
        windowManager = null
    }
}

/**
 * Compact circular bubble view content with active/idle visual indicator.
 */
@Composable
fun FloatingBubbleContent(
    isScanning: Boolean,
    onExpandRequest: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isScanning) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isScanning) 0.8f else 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )

    val bgColor by animateColorAsState(
        targetValue = if (isScanning) OrangePrimary else Color(0xFF1E212B),
        label = "bgColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(58.dp)
    ) {
        // Glowing halo in active state
        if (isScanning) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale)
                    .alpha(haloAlpha)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(OrangePrimary, Color.Transparent)
                        )
                    )
            )
        }

        // Core Bubble
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(bgColor)
                .alpha(if (isScanning) 1.0f else 0.85f)
                .border(
                    width = if (isScanning) 2.dp else 1.dp,
                    color = if (isScanning) Color.White else Color(0x66FFFFFF),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = if (isScanning) Icons.Default.DocumentScanner else Icons.Default.School,
                contentDescription = if (isScanning) "Scanning..." else "Bo Lite Bubble (Double-tap to open dashboard)",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Expanded Mini-Card Dashboard:
 * Manages dual UI sub-states:
 * 1. List View: Top 10 recent posts with search filter and scan trigger.
 * 2. Focused Reading View: Tapping a post transitions into full reading view with comments and copy action.
 */
@Composable
fun ExpandedMiniCardContent(
    posts: List<ArchivedPost>,
    onClose: () -> Unit,
    onToggleScan: () -> Unit,
    isScanning: Boolean
) {
    var selectedPostId by remember { mutableStateOf<String?>(null) }
    val selectedPost = remember(posts, selectedPostId) {
        posts.find { it.id == selectedPostId }
    }

    // If a post is selected, render the Focused Reading Detail View
    if (selectedPost != null) {
        FocusedReadingDetailView(
            post = selectedPost,
            onBack = { selectedPostId = null },
            onClose = onClose
        )
        return
    }

    // Otherwise, render the Searchable List Mode (Top 10 Posts)
    val top10Posts = remember(posts) { posts.take(10) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredPosts = remember(top10Posts, searchQuery) {
        if (searchQuery.isBlank()) {
            top10Posts
        } else {
            top10Posts.filter {
                it.postContent.contains(searchQuery, ignoreCase = true) ||
                        it.authorName.contains(searchQuery, ignoreCase = true) ||
                        (it.categoryTag?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header: Title, Live Scan indicator, and Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = OrangePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "بو لايت | لوحة البيانات الحية",
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quick Scan Action Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isScanning) OrangePrimary else Color(0xFF2A2D3A),
                        modifier = Modifier
                            .clickable { onToggleScan() }
                            .padding(end = 4.dp)
                    ) {
                        Text(
                            text = if (isScanning) "جار الفحص..." else "فحص الآن",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Collapse to Bubble",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search TextField (Touchable & Keyboard-Ready)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "بحث فوري في آخر المنشورات...",
                        color = TextHint,
                        fontSize = 12.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextHint,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OrangePrimary,
                    unfocusedBorderColor = BorderStroke,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    cursorColor = OrangePrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Scrollable List (Top 10 Posts)
            if (filteredPosts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (top10Posts.isEmpty()) "لا توجد ملخصات محفوظة بعد" else "لا توجد نتائج مطابقة للبحث",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredPosts, key = { it.id }) { post ->
                        MiniPostCardItem(
                            post = post,
                            onClick = { selectedPostId = post.id }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Focused Reading Detail View:
 * Full, distraction-free reading experience rendered inside the floating card.
 * Displays complete scrollable text, author, timestamp, comments, and copy action.
 */
@Composable
fun FocusedReadingDetailView(
    post: ArchivedPost,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val dateStr = remember(post.scanDate) {
        val sdf = SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale.getDefault())
        sdf.format(Date(post.scanDate))
    }

    val contentText = post.fullText.ifBlank { post.text }
    val commentSeparator = "--- 💬 الردود والتعليقات"
    val hasCommentsSection = contentText.contains(commentSeparator)

    val (mainBodyText, commentsText) = remember(contentText) {
        if (hasCommentsSection) {
            val parts = contentText.split(commentSeparator, limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        } else {
            contentText to ""
        }
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .shadow(16.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderStroke)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header: Back arrow, Title, Copy action, and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "العودة للقائمة | Back to List",
                            tint = OrangePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "وضع القراءة المركزة",
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quick Copy Full Text Button
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Bo Lite Note", contentText)
                            cm?.setPrimaryClip(clip)
                            Toast.makeText(context, "تم نسخ المنشور بالكامل ✓", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Full Post",
                            tint = TextMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Overlay",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Post Metadata Bar: Author, Date, and Category
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BgDark,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderStroke),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(OrangePrimary.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = OrangePrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = post.authorName,
                                color = OrangePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = dateStr,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    val category = post.categoryTag ?: "تعليمي"
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF232732),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderStroke)
                    ) {
                        Text(
                            text = category,
                            color = Color(0xFFFFB366),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Scrollable Reading Content Body
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(vertical = 4.dp)
            ) {
                // Primary Post Body Text
                SelectionContainer {
                    Text(
                        text = mainBodyText,
                        color = TextWhite,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                    )
                }

                // Associated Comments & Discussion Section (if extracted)
                if (hasCommentsSection && commentsText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = OrangePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "الردود والتعليقات المرتبطة:",
                            color = OrangePrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderStroke),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Text(
                                text = commentsText,
                                color = Color(0xFFE2E4EB),
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Bottom Action Bar: Return to List Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangePrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OrangePrimary.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "العودة للقائمة", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "بو لايت • قراءة سريعة",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * Compact post card displayed inside the Expanded Mini-Dashboard.
 * Shows: Post Content (truncated), Timestamp, Category Badge, and Tap-to-Read indicator.
 */
@Composable
fun MiniPostCardItem(
    post: ArchivedPost,
    onClick: () -> Unit
) {
    val dateStr = remember(post.scanDate) {
        val sdf = SimpleDateFormat("MM/dd - hh:mm a", Locale.getDefault())
        sdf.format(Date(post.scanDate))
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = BgDark,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderStroke),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = post.authorName,
                    color = OrangePrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = dateStr,
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Truncated Post Content
            Text(
                text = post.postContent,
                color = TextWhite,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Category Badge & Tap to Read Hint
            val category = post.categoryTag ?: "تعليمي"
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF232732),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderStroke)
                ) {
                    Text(
                        text = category,
                        color = Color(0xFFFFB366),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = "انقر للقراءة الكاملة ←",
                    color = OrangePrimary.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
