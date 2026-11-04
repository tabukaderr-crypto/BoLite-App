package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.AppDatabase
import com.example.data.SavedCardRepository
import com.example.overlay.BubbleViewBuilder
import com.example.overlay.MiniPanel
import com.example.overlay.OverlayPermissionHelper
import com.example.overlay.PanelLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow

class FloatingBubbleService : Service() {

    companion object {
        const val CHANNEL_ID = "bo_lite_floating_bubble_channel"
        const val NOTIFICATION_ID = 3001

        val isRunning = MutableStateFlow(false)
        private var userDisabled = false

        /** The open panel window, so a scan can hide it while taking screenshots. */
        @Volatile var panelViewRef: View? = null

        /** Hides/shows the panel (and lets touches pass through while hidden). */
        fun setPanelVisible(visible: Boolean) {
            val v = panelViewRef ?: return
            v.post {
                try {
                    v.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                    val lp = v.layoutParams as? WindowManager.LayoutParams
                    if (lp != null) {
                        lp.flags = if (visible) {
                            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        } else {
                            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        }
                        v.context.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun start(context: Context) {
            userDisabled = false
            if (!OverlayPermissionHelper.hasPermission(context)) {
                OverlayPermissionHelper.requestPermission(context)
                return
            }

            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            userDisabled = true
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: SavedCardRepository

    private var bubbleView: View? = null
    private var bubbleParams = WindowManager.LayoutParams()

    private var panelComposeView: ComposeView? = null
    private var panelLifecycleOwner: PanelLifecycleOwner? = null
    private var isPanelShowing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val db = AppDatabase.getDatabase(applicationContext)
        repository = SavedCardRepository(db.savedCardDao())

        startForegroundNotification()
        setupLightweightBubbleView()
        isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (userDisabled) {
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "الفقاعة العائمة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "خدمة الفقاعة العائمة للبحث السريع وتصفية الشاشة"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("بو لايت — الفقاعة نشطة")
            .setContentText("انقر على الفقاعة لتصفية الصفحة الحالية أو البحث")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupLightweightBubbleView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = (200 * density).toInt()
        }

        bubbleView = BubbleViewBuilder.createBubbleView(
            context = this,
            onTap = {
                toggleMiniPanelOverlay()
            },
            onLongPress = {
                Toast.makeText(this, "تم إخفاء الفقاعة", Toast.LENGTH_SHORT).show()
                userDisabled = true
                stopSelf()
            },
            onDrag = { dx, dy ->
                bubbleParams.x += dx.toInt()
                bubbleParams.y += dy.toInt()
                bubbleView?.let { view ->
                    try {
                        windowManager.updateViewLayout(view, bubbleParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun toggleMiniPanelOverlay() {
        if (isPanelShowing) {
            hideMiniPanelOverlay()
        } else {
            showMiniPanelOverlay()
        }
    }

    private fun showMiniPanelOverlay() {
        if (isPanelShowing) return

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val owner = PanelLifecycleOwner()
            panelLifecycleOwner = owner
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setContent {
                    val app = applicationContext as android.app.Application
                    val vm = androidx.compose.runtime.remember { MainViewModel(app) }
                    com.example.overlay.UpdatedMiniPanelComposable(
                        viewModel = vm,
                        onClose = { hideMiniPanelOverlay() }
                    )
                }
            }
            panelComposeView = composeView
            panelViewRef = composeView

            windowManager.addView(composeView, panelParams)
            isPanelShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
            hideMiniPanelOverlay()
        }
    }

    private fun hideMiniPanelOverlay() {
        if (!isPanelShowing) return

        panelComposeView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        panelComposeView = null
        panelViewRef = null
        panelLifecycleOwner?.destroy()
        panelLifecycleOwner = null
        isPanelShowing = false
    }

    override fun onDestroy() {
        isRunning.value = false
        hideMiniPanelOverlay()
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubbleView = null
        super.onDestroy()
    }
}
