package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-interactive key popup overlay.
 *
 * The view spans the complete bounded IME surface so the first keyboard row can
 * display its popup inside the suggestion strip. Its Z elevation must remain
 * above SuggestionView; FrameLayout child insertion order alone is not enough
 * because SuggestionView has a non-zero elevation of its own.
 */
class KeyPreviewView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * scaledDensity

    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val popupSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val popupIridescentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val popupInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.45f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(176, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.85f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(19, 31, 49)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sp(27f)
    }

    private val bubbleBounds = RectF()
    private val shadowBounds = RectF()
    private val bubbleSheenBounds = RectF()
    private val pointerPath = Path()
    private var previewLabel: String? = null
    private val anchorBounds = RectF()
    private var hasAnchor = false

    // A single Choreographer timeline drives every popup transition. Allocating
    // a ValueAnimator and listeners on every keystroke caused avoidable churn.
    private val choreographer = Choreographer.getInstance()
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback(::onAnimationFrame)
    private var animationRunning = false
    private var animationStartNanos = 0L
    private var animationDurationNanos = 0L
    private var startAlpha = 0f
    private var targetAlpha = 0f
    private var startScale = 0.92f
    private var targetScale = 1f
    private var startLiftPx = dp(2.5f)
    private var targetLiftPx = 0f
    private var hideAtEnd = false
    private var useEntranceOvershoot = false

    // These properties animate only popup drawing, not the overlay View. The
    // entrance is a short ease-out, with no spring overshoot that can feel like
    // a second, delayed response during fast typing.
    private var previewAlpha = 0f
    private var previewScale = 0.86f
    private var previewLiftPx = dp(5f)
    private var hideRequested = false

    init {
        isClickable = false
        isFocusable = false
        // Keep the full-surface overlay visible but empty while idle. Toggling
        // visibility on every character adds needless view-tree and invalidation churn.
        visibility = VISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)

        // SuggestionView currently uses 2dp elevation. The popup must draw above
        // it, particularly for QWERTY's first row where the popup rises into the
        // suggestion strip. This is a real Z-order requirement, not a draw-order
        // assumption.
        elevation = dp(12f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        popupPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.argb(246, 255, 255, 255),
                Color.argb(226, 237, 247, 255),
                Color.argb(232, 207, 229, 250)
            ), null, Shader.TileMode.CLAMP
        )
        popupIridescentPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.argb(132, 255, 255, 255),
                Color.argb(104, 116, 224, 255),
                Color.argb(90, 189, 163, 255),
                Color.argb(124, 255, 255, 255)
            ), null, Shader.TileMode.CLAMP
        )
    }

    /** Shows a letter/digit popup, or fades the active popup out for non-letters. */
    fun showKey(label: String?, keyBounds: RectF?) {
        val safeLabel = label?.takeIf { it.isNotBlank() }
        if (safeLabel == null || keyBounds == null || keyBounds.isEmpty) {
            hideKey()
            return
        }

        val sameAnchor = hasAnchor && previewLabel == safeLabel &&
            kotlin.math.abs(anchorBounds.centerX() - keyBounds.centerX()) < dp(0.5f) &&
            kotlin.math.abs(anchorBounds.centerY() - keyBounds.centerY()) < dp(0.5f)
        if (sameAnchor && !hideRequested) return

        // Popup activity is determined by its content, not View.visibility.
        // The overlay stays in place to avoid per-key visibility churn.
        val wasActive = hasAnchor && previewLabel != null && !hideRequested
        stopAnimation()
        hideRequested = false
        previewLabel = safeLabel
        anchorBounds.set(keyBounds)
        hasAnchor = true

        if (wasActive) {
            // A new active pointer moves the popup without replaying the full
            // entrance or making the bubble flash between rapid taps.
            previewAlpha = max(previewAlpha, 0.96f)
            previewScale = max(previewScale, 0.98f)
            previewLiftPx = min(previewLiftPx, dp(1.5f))
            animateTo(1f, 1f, 0f, durationMs = 42L, hideAtEnd = false)
        } else {
            previewAlpha = 0f
            previewScale = 0.86f
            previewLiftPx = dp(5f)
            animateTo(
                1f, 1f, 0f,
                durationMs = 84L,
                hideAtEnd = false,
                entranceOvershoot = true
            )
        }
        invalidate()
    }

    private fun hideKey() {
        if (previewLabel == null || hideRequested) return
        hideRequested = true
        animateTo(
            targetAlpha = 0f,
            targetScale = 0.96f,
            targetLiftPx = dp(1.5f),
            durationMs = 54L,
            hideAtEnd = true
        )
    }

    private fun stopAnimation() {
        animationRunning = false
        if (frameScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
    }

    private fun animateTo(
        targetAlpha: Float,
        targetScale: Float,
        targetLiftPx: Float,
        durationMs: Long,
        hideAtEnd: Boolean,
        entranceOvershoot: Boolean = false
    ) {
        startAlpha = previewAlpha
        startScale = previewScale
        startLiftPx = previewLiftPx
        this.targetAlpha = targetAlpha
        this.targetScale = targetScale
        this.targetLiftPx = targetLiftPx
        animationStartNanos = System.nanoTime()
        animationDurationNanos = durationMs * 1_000_000L
        this.hideAtEnd = hideAtEnd
        useEntranceOvershoot = entranceOvershoot
        animationRunning = true
        scheduleAnimationFrame()
    }

    private fun scheduleAnimationFrame() {
        if (frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun onAnimationFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!animationRunning) return

        val fraction = ((frameTimeNanos - animationStartNanos).toFloat() /
            animationDurationNanos.toFloat()).coerceIn(0f, 1f)
        // Alpha and lift use cubic ease-out. The entrance scale uses a very
        // small (about 1.3%) overshoot, then settles, giving the popup a crisp
        // tactile response without a springy bounce during rapid typing.
        val inverse = 1f - fraction
        val eased = 1f - inverse * inverse * inverse
        val t = fraction - 1f
        val scaleEased = if (useEntranceOvershoot) {
            (1f + 1.65f * t * t * t + 0.65f * t * t).coerceIn(0f, 1.02f)
        } else eased
        previewAlpha = startAlpha + (targetAlpha - startAlpha) * eased
        previewScale = startScale + (targetScale - startScale) * scaleEased
        previewLiftPx = startLiftPx + (targetLiftPx - startLiftPx) * eased
        postInvalidateOnAnimation()

        if (fraction >= 1f) {
            animationRunning = false
            useEntranceOvershoot = false
            previewAlpha = targetAlpha
            previewScale = targetScale
            previewLiftPx = targetLiftPx
            if (hideAtEnd && hideRequested) {
                previewLabel = null
                hasAnchor = false
                anchorBounds.setEmpty()
                hideRequested = false
            }
        } else {
            scheduleAnimationFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = previewLabel ?: return
        if (!hasAnchor) return
        val anchor = anchorBounds
        if (previewAlpha <= 0.001f) return

        // Keep the pointer aligned with the actual key centre. The rounded body
        // is clamped inside the overlay at Q/P; the label remains centred in the
        // body while the pointer preserves the true key anchor.
        val bubbleWidth = min(
            max(dp(44f), anchor.width() + dp(10f)),
            max(dp(40f), width.toFloat() - dp(4f))
        )
        val bubbleHeight = dp(46f)
        val pointerHeight = dp(6f)
        val pointerX = anchor.centerX()
        val halfBubbleWidth = bubbleWidth / 2f
        val bubbleCenterX = pointerX.coerceIn(
            halfBubbleWidth + dp(2f),
            max(halfBubbleWidth + dp(2f), width.toFloat() - halfBubbleWidth - dp(2f))
        )

        // The popup rises into the 48dp suggestion strip. The 46dp body and
        // 6dp pointer fit above the first row on the Nord 5's calibrated grid.
        val bubbleTop = max(dp(1.5f), anchor.top - bubbleHeight - pointerHeight)
        bubbleBounds.set(
            bubbleCenterX - halfBubbleWidth,
            bubbleTop,
            bubbleCenterX + halfBubbleWidth,
            bubbleTop + bubbleHeight
        )

        pointerPath.reset()
        pointerPath.moveTo(pointerX - dp(6f), bubbleBounds.bottom - dp(1f))
        pointerPath.lineTo(pointerX + dp(6f), bubbleBounds.bottom - dp(1f))
        pointerPath.lineTo(pointerX, anchor.top + dp(1f))
        pointerPath.close()

        val saveCount = canvas.save()
        canvas.translate(0f, previewLiftPx)
        canvas.scale(previewScale, previewScale, bubbleCenterX, bubbleBounds.centerY())

        shadowBounds.set(bubbleBounds)
        shadowPaint.alpha = (58f * previewAlpha).roundToInt().coerceIn(0, 58)
        val shadowSave = canvas.save()
        canvas.translate(0f, dp(1.5f))
        canvas.drawRoundRect(shadowBounds, dp(8f), dp(8f), shadowPaint)
        canvas.drawPath(pointerPath, shadowPaint)
        canvas.restoreToCount(shadowSave)

        val alpha = (255f * previewAlpha).roundToInt().coerceIn(0, 255)
        popupPaint.alpha = alpha
        popupSheenPaint.alpha = alpha
        outlinePaint.alpha = (176f * previewAlpha).roundToInt().coerceIn(0, 176)
        popupIridescentPaint.alpha = (188f * previewAlpha).roundToInt().coerceIn(0, 188)
        popupInnerRimPaint.alpha = (72f * previewAlpha).roundToInt().coerceIn(0, 72)
        textPaint.alpha = alpha

        val radius = dp(11f)
        canvas.drawRoundRect(bubbleBounds, radius, radius, popupPaint)
        canvas.drawPath(pointerPath, popupPaint)
        bubbleSheenBounds.set(
            bubbleBounds.left + dp(1.2f), bubbleBounds.top + dp(1f),
            bubbleBounds.right - dp(1.2f), bubbleBounds.top + dp(19f)
        )
        canvas.drawRoundRect(bubbleSheenBounds, dp(9f), dp(9f), popupSheenPaint)
        canvas.drawRoundRect(bubbleBounds, radius, radius, popupInnerRimPaint)
        canvas.drawRoundRect(bubbleBounds, radius, radius, popupIridescentPaint)
        canvas.drawRoundRect(bubbleBounds, radius, radius, outlinePaint)
        canvas.drawPath(pointerPath, popupIridescentPaint)
        canvas.drawPath(pointerPath, outlinePaint)

        val baseline = bubbleBounds.centerY() -
            (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, bubbleCenterX, baseline, textPaint)

        canvas.restoreToCount(saveCount)

        // Paints are reused for every frame. Restore their canonical alpha so
        // a cancelled animation cannot leave a later popup partially transparent.
        popupPaint.alpha = 255
        popupSheenPaint.alpha = 255
        outlinePaint.alpha = 176
        popupIridescentPaint.alpha = 188
        popupInnerRimPaint.alpha = 72
        textPaint.alpha = 255
        shadowPaint.alpha = 58
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        previewLabel = null
        hasAnchor = false
        anchorBounds.setEmpty()
        hideRequested = false
        previewAlpha = 0f
        previewScale = 0.86f
        previewLiftPx = dp(5f)
        useEntranceOvershoot = false
        super.onDetachedFromWindow()
    }
}
