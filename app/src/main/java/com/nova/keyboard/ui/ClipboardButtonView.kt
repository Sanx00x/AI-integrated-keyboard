package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

enum class ClipboardButtonGesture {
    START, MOVE, END, CANCEL
}

/**
 * Compact clipboard control for the prediction strip.
 * A tap pastes the newest item. Holding opens history; the original pointer
 * stream stays on this view while raw coordinates drive popup selection.
 */
internal class ClipboardButtonView(
    context: Context,
    private val onTap: () -> Unit,
    private val onHistoryGesture: (ClipboardButtonGesture, Float, Float) -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
    private fun dp(value: Float) = value * density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circleGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.55f)
    }
    private val circleHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.65f)
        strokeCap = Paint.Cap.ROUND
    }
    private val circleHighlightBounds = RectF()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconRect = RectF()

    private var hasItems = false
    private var tracking = false
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private var latestRawX = 0f
    private var latestRawY = 0f

    private val longPressRunnable = Runnable {
        if (tracking && isEnabled) {
            longPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onHistoryGesture(ClipboardButtonGesture.START, latestRawX, latestRawY)
            invalidate()
        }
    }

    init {
        circleGlassPaint.shader = LinearGradient(
            0f, 0f, 0f, dp(32f),
            intArrayOf(
                Color.argb(34, 250, 252, 255),
                Color.argb(16, 220, 235, 255),
                Color.argb(5, 180, 207, 244)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Clipboard"
        minimumWidth = dp(46f).toInt()
        minimumHeight = dp(48f).toInt()
    }

    fun setHasItems(value: Boolean) {
        if (hasItems == value) return
        hasItems = value
        contentDescription = if (value) "Clipboard, recent items available" else "Clipboard, no recent items yet"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = dp(16f)

        val glassOpacity = when {
            !isEnabled -> 55
            tracking -> 255
            hasItems -> 205
            else -> 150
        }
        circleGlassPaint.alpha = glassOpacity
        canvas.drawCircle(cx, cy, radius, circleGlassPaint)

        circleRimPaint.color = Color.argb(
            when {
                !isEnabled -> 8
                tracking -> 72
                hasItems -> 46
                else -> 27
            },
            244, 249, 255
        )
        canvas.drawCircle(cx, cy, radius - dp(0.3f), circleRimPaint)

        circleHighlightBounds.set(
            cx - radius + dp(3.2f),
            cy - radius + dp(3.2f),
            cx + radius - dp(3.2f),
            cy + radius - dp(3.2f)
        )
        circleHighlightPaint.color = Color.argb(
            when {
                !isEnabled -> 0
                tracking -> 88
                hasItems -> 51
                else -> 32
            },
            255, 255, 255
        )
        canvas.drawArc(circleHighlightBounds, 205f, 130f, false, circleHighlightPaint)

        iconPaint.color = if (hasItems || longPressTriggered) {
            Color.argb(232, 247, 250, 255)
        } else {
            Color.argb(174, 216, 225, 239)
        }
        val left = cx - dp(6.4f)
        val top = cy - dp(7.1f)
        val right = cx + dp(6.4f)
        val bottom = cy + dp(7.7f)
        iconRect.set(left, top, right, bottom)
        canvas.drawRoundRect(iconRect, dp(2f), dp(2f), iconPaint)

        // Clipboard tab and two short content lines.
        iconRect.set(cx - dp(3.4f), cy - dp(9.2f), cx + dp(3.4f), cy - dp(5.9f))
        circlePaint.color = Color.argb(220, 206, 225, 249)
        canvas.drawRoundRect(iconRect, dp(1.5f), dp(1.5f), circlePaint)
        canvas.drawLine(cx - dp(3.2f), cy - dp(1.5f), cx + dp(3.3f), cy - dp(1.5f), iconPaint)
        canvas.drawLine(cx - dp(3.2f), cy + dp(2.2f), cx + dp(2.0f), cy + dp(2.2f), iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                longPressTriggered = false
                downX = event.x
                downY = event.y
                latestRawX = event.rawX
                latestRawY = event.rawY
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                latestRawX = event.rawX
                latestRawY = event.rawY
                if (!longPressTriggered && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                }
                if (longPressTriggered) {
                    onHistoryGesture(ClipboardButtonGesture.MOVE, latestRawX, latestRawY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                latestRawX = event.rawX
                latestRawY = event.rawY
                handler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    onHistoryGesture(ClipboardButtonGesture.END, latestRawX, latestRawY)
                } else if (hypot(event.x - downX, event.y - downY) <= touchSlop) {
                    performClick()
                }
                tracking = false
                longPressTriggered = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    onHistoryGesture(ClipboardButtonGesture.CANCEL, event.rawX, event.rawY)
                }
                tracking = false
                longPressTriggered = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPressRunnable)
        if (longPressTriggered) onHistoryGesture(ClipboardButtonGesture.CANCEL, latestRawX, latestRawY)
        tracking = false
        longPressTriggered = false
        super.onDetachedFromWindow()
    }
}

