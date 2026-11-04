package com.example.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.hypot

object BubbleViewBuilder {

    /**
     * Builds a plain lightweight 56dp circular FrameLayout containing a TextView "بو"
     * with drag via raw coordinates, quick tap, and long press.
     */
    fun createBubbleView(
        context: Context,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
        onDrag: (dx: Float, dy: Float) -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val frameLayout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF6B35"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            elevation = 8f * density
        }

        val textView = TextView(context).apply {
            text = "بو"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frameLayout.addView(textView)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var downTime = 0L

        val handler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            onLongPress()
        }

        frameLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    downTime = System.currentTimeMillis()
                    handler.postDelayed(longPressRunnable, 750)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        onDrag(dx, dy)
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    val duration = System.currentTimeMillis() - downTime
                    if (!isDragging && duration < 400) {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        return frameLayout
    }
}
