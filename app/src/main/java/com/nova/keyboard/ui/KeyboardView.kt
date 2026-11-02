package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.nova.keyboard.prediction.SpatialKeyGeometry
import com.nova.keyboard.prediction.SpatialTouchGate
import com.nova.keyboard.prediction.SpatialTouchModel
import com.nova.keyboard.prediction.SpatialTouchRanking
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight NOVA keyboard surface.
 *
 * Each pointer gets its own tap-stable key press. A second finger may press a
 * different key before the first finger is lifted; each key commits on that
 * pointer's release. Movement cancels only the pointer that drifted. Swipe
 * typing is not implemented, so a moving finger never changes its selected key.
 */
class KeyboardView(
    context: Context,
    private val onKeyPressed: (String) -> Unit,
    private val onKeyPreviewChanged: (String?, RectF?) -> Unit = { _, _ -> },
    /** Shadow-only callback; does not change the character committed by Phase A. */
    private val onSpatialShadowSample: (SpatialTouchRanking, String?) -> Unit = { _, _ -> },
    /** Called only when the spatial model reports an uncertain letter tap. */
    private val onResolveAmbiguousKey: (String, SpatialTouchRanking) -> String = { raw, _ -> raw },
    /** Main-thread release-to-commit duration, used only for aggregate diagnostics. */
    private val onTouchReleaseLatency: (Long) -> Unit = {}
) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = value * scaledDensity

    private var shiftMode = ShiftMode.OFF
    /** -1 = letters; 0..2 = symbol pages. */
    private var symbolPage = -1
    private val activePresses = MultiTouchPressTracker<KeyRegion>(dp(MAX_TAP_DRIFT_DP))
    private val repeatedBackspacePointers = mutableSetOf<Int>()
    private val spatialRankingsByPointer = HashMap<Int, SpatialTouchRanking>(4)
    private var backspacePointerId: Int? = null

    private val touchHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var pressFrameScheduled = false
    private val pressFrameCallback = Choreographer.FrameCallback(::onPressAnimationFrame)
    private var backspaceRepeatRunnable: Runnable? = null
    private val keyRegions = mutableListOf<KeyRegion>()
    private var geometryDirty = true
    private var geometryWidth = 0
    private var geometryHeight = 0
    private var geometrySymbolPage = -2
    // Geometry is cached and rebuilt only when size or keyboard mode changes.
    private var spatialKeyGeometries: List<SpatialKeyGeometry> = emptyList()
    private val spatialTouchModel = SpatialTouchModel(minSigmaPx = dp(4.5f).toDouble())

    private class KeyRegion(
        val value: String,
        val rowIndex: Int,
        val centerX: Float,
        val cellLeftPx: Float,
        val cellRightPx: Float,
        val touchBounds: RectF,
        val visualBounds: RectF,
        val displayLabel: String,
        val isSpecial: Boolean,
        val textSizePx: Float,
        val modeSwitchActivationBounds: ModeSwitchIntentPolicy.Bounds?
    ) {
        var pressProgress = 0f
        var animationFrom = 0f
        var animationTo = 0f
        var animationStartNanos = 0L
        var animationDurationNanos = 0L
        var isAnimating = false
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, 238, 247, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.35f)
    }
    private val keyTopSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Keep the paint fully opaque so the cached shader controls the actual
        // reflection strength instead of multiplying it by a second low alpha.
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(0.48f)
        strokeCap = Paint.Cap.ROUND
    }
    private val keyPressHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val keyPressHighlightRect = RectF()
    private val keyDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 2, 8, 18)
        style = Paint.Style.FILL
    }
    private val shiftOnceColor = Color.rgb(83, 174, 235)
    private val shiftCapsColor = Color.rgb(77, 197, 174)
    private val shiftIndicatorColor = Color.rgb(235, 255, 252)

    private companion object {
        const val BACKSPACE_LONG_PRESS_TIMEOUT_MS = 360L
        const val BACKSPACE_REPEAT_INTERVAL_MS = 55L
        // Approximately half the Nord 5 letter-key pitch: ordinary tap jitter
        // is tolerated, but crossing into a neighbouring key cancels the tap.
        const val MAX_TAP_DRIFT_DP = 36f
        const val BACKSPACE_EXIT_SLOP_DP = 10f
        const val DEFAULT_KEYBOARD_HEIGHT_DP = KeyboardLayoutPolicy.PORTRAIT_BODY_HEIGHT_DP
        // A short, single-vsync-driven keycap response. No ValueAnimator is
        // allocated for each letter press/release.
        const val KEY_PRESS_IN_DURATION_MS = 38L
        const val KEY_PRESS_OUT_DURATION_MS = 72L
        const val KEY_PRESS_SCALE_AMOUNT = 0.020f
        const val SPATIAL_BOUNDARY_MARGIN_DP = 10f
    }

    init {
        isClickable = true
        isFocusable = true
        textPaint.apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        textPaint.color = Color.rgb(248, 251, 255)
        keyGlassPaint.shader = LinearGradient(
            0f, 0f, 0f, dp(DEFAULT_KEYBOARD_HEIGHT_DP.toFloat()),
            intArrayOf(
                Color.argb(51, 246, 250, 255),
                Color.argb(27, 198, 224, 250),
                Color.argb(12, 118, 158, 207)
            ), null, Shader.TileMode.CLAMP
        )
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(DEFAULT_KEYBOARD_HEIGHT_DP).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> availableHeight
            MeasureSpec.AT_MOST -> min(desiredHeight, availableHeight)
            else -> desiredHeight
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyGlassPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat().coerceAtLeast(1f),
            intArrayOf(
                Color.argb(51, 246, 250, 255),
                Color.argb(27, 198, 224, 250),
                Color.argb(12, 118, 158, 207)
            ), floatArrayOf(0f, 0.46f, 1f), Shader.TileMode.CLAMP
        )
        keyTopSheenPaint.shader = LinearGradient(
            0f, 0f, w.toFloat().coerceAtLeast(1f), 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(31, 240, 249, 255),
                Color.argb(14, 190, 225, 255),
                Color.TRANSPARENT
            ), floatArrayOf(0f, 0.40f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        geometryDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ensureKeyGeometry()
        keyRegions.forEach { drawKey(canvas, it) }
    }

    /**
     * Build the exact same geometry for drawing and hit-testing. This also
     * protects the first tap after the IME appears: hit regions no longer depend
     * on whether the first frame happened to draw before the first touch event.
     */
    private fun ensureKeyGeometry() {
        if (!geometryDirty && geometryWidth == width && geometryHeight == height &&
            geometrySymbolPage == symbolPage
        ) return

        // A size/mode change invalidates the old hit geometry and its visual
        // timelines. Pointer tracking is separate and will be cancelled by the
        // IME/window lifecycle if a configuration change occurs mid-gesture.
        keyRegions.forEach { it.isAnimating = false }
        keyRegions.clear()
        val frames = KeyboardLayoutPolicy.createKeyFrames(
            widthPx = width,
            heightPx = height,
            density = density,
            symbolPage = symbolPage
        )
        frames.forEach { frame ->
            keyRegions.add(
                KeyRegion(
                    value = frame.value,
                    rowIndex = frame.rowIndex,
                    centerX = frame.centerX,
                    cellLeftPx = frame.cellLeftPx,
                    cellRightPx = frame.cellRightPx,
                    touchBounds = RectF(
                        frame.touchLeftPx,
                        frame.touchTopPx,
                        frame.touchRightPx,
                        frame.touchBottomPx
                    ),
                    visualBounds = RectF(
                        frame.visualLeftPx,
                        frame.visualTopPx,
                        frame.visualRightPx,
                        frame.visualBottomPx
                    ),
                    displayLabel = displayLabelFor(frame.value),
                    isSpecial = isSpecialKey(frame.value),
                    textSizePx = keyTextSizePx(frame.value),
                    modeSwitchActivationBounds = if (
                        ModeSwitchIntentPolicy.requiresGuard(frame.value)
                    ) {
                        ModeSwitchIntentPolicy.createActivationBounds(
                            cellLeftPx = frame.cellLeftPx,
                            cellTopPx = frame.visualTopPx,
                            cellRightPx = frame.cellRightPx,
                            cellBottomPx = frame.visualBottomPx,
                            density = density
                        )
                    } else null
                )
            )
        }

        spatialKeyGeometries = if (symbolPage >= 0) emptyList() else keyRegions.asSequence()
            .filter { it.value.length == 1 && it.value[0] in 'a'..'z' }
            .map { region ->
                SpatialKeyGeometry(
                    label = if (shiftMode != ShiftMode.OFF) region.value.uppercase() else region.value,
                    left = region.visualBounds.left,
                    top = region.visualBounds.top,
                    right = region.visualBounds.right,
                    bottom = region.visualBounds.bottom,
                    rowIndex = region.rowIndex
                )
            }
            .toList()

        geometryWidth = width
        geometryHeight = height
        geometrySymbolPage = symbolPage
        geometryDirty = false
    }

    private fun isSpecialKey(value: String): Boolean = when (value) {
        "SHIFT", "BACKSPACE", "123", "ABC", "SYM_NEXT", "ENTER", "EMOJI" -> true
        else -> false
    }

    private fun displayLabelFor(value: String): String = when (value) {
        "SHIFT" -> "⇧"
        "BACKSPACE" -> "⌫"
        "SPACE" -> "English · Tanglish"
        "ENTER" -> "↵"
        "123" -> "?123"
        "ABC" -> "ABC"
        "SYM_NEXT" -> "${symbolPage.coerceAtLeast(0) + 1}/3"
        "EMOJI" -> "☺"
        else -> if (shiftMode != ShiftMode.OFF && value.length == 1 && value[0].isLetter()) {
            value.uppercase()
        } else value
    }

    private fun keyTextSizePx(value: String): Float = when (value) {
        "SPACE" -> sp(11.5f)
        "SHIFT", "BACKSPACE", "ENTER" -> sp(19f)
        "EMOJI" -> sp(20f)
        "123", "ABC", "SYM_NEXT" -> sp(14f)
        else -> sp(20f)
    }

    private fun drawKey(canvas: Canvas, key: KeyRegion) {
        val rect = key.visualBounds
        val pressProgress = key.pressProgress.coerceIn(0f, 1f)
        val shiftAccent = when {
            key.value == "SHIFT" && shiftMode == ShiftMode.ONE_SHOT -> shiftOnceColor
            key.value == "SHIFT" && shiftMode == ShiftMode.CAPS_LOCK -> shiftCapsColor
            else -> 0
        }

        val canvasSave = canvas.save()
        val keyScale = 1f - KEY_PRESS_SCALE_AMOUNT * pressProgress
        canvas.scale(keyScale, keyScale, rect.centerX(), rect.centerY())
        val cornerRadius = dp(KeyboardLayoutPolicy.KEY_CORNER_RADIUS_DP)
        val depthOffset = dp(1.35f) * (1f - pressProgress)

        // Soft dark under-edge creates the floating glass-cap depth.
        canvas.drawRoundRect(
            rect.left, rect.top + depthOffset, rect.right, rect.bottom + depthOffset,
            cornerRadius, cornerRadius, keyDepthPaint
        )

        // Frosted translucent key face. A cool tint separates special controls
        // without turning every key into a bright, visually noisy button.
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyGlassPaint)
        val specialTintAlpha = when {
            shiftAccent != 0 -> 66
            key.isSpecial -> 30
            else -> 0
        }
        val pressedTintAlpha = (pressProgress * 88f).roundToInt()
        if (specialTintAlpha > 0 || pressedTintAlpha > 0) {
            val tintColor = when {
                shiftAccent != 0 -> shiftAccent
                key.isSpecial -> Color.rgb(77, 145, 214)
                else -> Color.rgb(153, 211, 255)
            }
            val tintAlpha = (specialTintAlpha + pressedTintAlpha).coerceAtMost(158)
            keyTintPaint.color = Color.argb(
                tintAlpha,
                Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor)
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyTintPaint)
        } else if (pressedTintAlpha > 0) {
            keyTintPaint.color = Color.argb(pressedTintAlpha, 172, 222, 255)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyTintPaint)
        }

        // Fine specular rim and a narrow upper reflection make the surface read
        // as glass, while staying within the same single Canvas draw pass.
        val topY = rect.top + dp(1.9f)
        canvas.drawLine(
            rect.left + dp(8f), topY,
            rect.right - dp(8f), topY,
            keyTopSheenPaint
        )

        // A pressed key catches a little more light at its edge. The rim is
        // absent at rest, so the keyboard does not become a grid of outlined caps.
        val activeRimAlpha = (pressProgress * 48f).roundToInt()
        if (activeRimAlpha > 0) {
            keyRimPaint.color = Color.argb(activeRimAlpha, 248, 252, 255)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyRimPaint)

            // A small internal reflection appears only during contact. It reuses
            // one RectF and one Paint; there are no per-frame allocations.
            val highlightHeight = min(dp(11f), rect.height() * 0.25f)
            keyPressHighlightRect.set(
                rect.left + dp(1.5f),
                rect.top + dp(1f),
                rect.right - dp(1.5f),
                rect.top + highlightHeight
            )
            keyPressHighlightPaint.color = Color.argb(
                (pressProgress * 27f).roundToInt(), 255, 255, 255
            )
            canvas.drawRoundRect(
                keyPressHighlightRect,
                dp(5f),
                dp(5f),
                keyPressHighlightPaint
            )
        }

        if (key.value == "SHIFT" && shiftMode == ShiftMode.CAPS_LOCK) {
            keyTintPaint.color = shiftIndicatorColor
            canvas.drawCircle(
                rect.right - dp(8f), rect.top + dp(7f), dp(2.4f), keyTintPaint
            )
        }

        if (textPaint.textSize != key.textSizePx) textPaint.textSize = key.textSizePx
        val baseline = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(key.displayLabel, rect.centerX(), baseline, textPaint)
        canvas.restoreToCount(canvasSave)
    }

    /**
     * Resolve the nearest key in the touched row. Adjacent letter hit regions
     * overlap deliberately; choosing the nearest cell centre avoids dead seams
     * and removes the old left-key bias at expanded hit-box boundaries.
     */
    private fun findKey(
        x: Float,
        y: Float,
        touchMajorPx: Float = 0f,
        touchMinorPx: Float = 0f
    ): KeyRegion? {
        ensureKeyGeometry()
        var selectedRow = -1
        var rowDistance = Float.POSITIVE_INFINITY
        for (region in keyRegions) {
            val hit = region.touchBounds
            if (y < hit.top || y > hit.bottom) continue
            val distance = abs(y - region.visualBounds.centerY())
            if (distance < rowDistance) {
                rowDistance = distance
                selectedRow = region.rowIndex
            }
        }
        if (selectedRow < 0) return null

        // Mode-changing controls are deliberately handled before normal nearest-
        // centre selection. A touch in their visible cell is not sufficient:
        // its centroid must land in the inset activation core. This stops a
        // thumb grazing ?123/ABC/SYM_NEXT from changing pages during fast typing.
        for (region in keyRegions) {
            if (region.rowIndex != selectedRow || region.modeSwitchActivationBounds == null) continue
            if (x >= region.cellLeftPx && x < region.cellRightPx) {
                return if (ModeSwitchIntentPolicy.contains(
                        bounds = region.modeSwitchActivationBounds,
                        x = x,
                        y = y,
                        density = density,
                        touchMajorPx = touchMajorPx,
                        touchMinorPx = touchMinorPx
                    )) region else null
            }
        }

        // On the bottom utility row, unequal key widths made nearest-centre
        // selection steal touches from the wide SPACE key: the small period
        // key's centre could be closer even when the finger was still inside
        // SPACE's own cell. Respect the actual cell there, while retaining the
        // overlap/nearest-centre correction used by letter rows.
        if (selectedRow == 3) {
            for (region in keyRegions) {
                if (region.rowIndex == selectedRow &&
                    x >= region.cellLeftPx && x < region.cellRightPx
                ) return region
            }
        }

        var best: KeyRegion? = null
        var bestDistanceSquared = Float.POSITIVE_INFINITY
        for (region in keyRegions) {
            if (region.rowIndex != selectedRow) continue
            val hit = region.touchBounds
            if (x < hit.left || x > hit.right) continue
            val modeBounds = region.modeSwitchActivationBounds
            if (modeBounds != null && !ModeSwitchIntentPolicy.contains(
                    bounds = modeBounds,
                    x = x,
                    y = y,
                    density = density,
                    touchMajorPx = touchMajorPx,
                    touchMinorPx = touchMinorPx
                )) continue

            // Compare physical distance, not distance divided by key width.
            // Normalizing by width made narrow punctuation keys lose too much
            // of their boundary area to wider neighbours.
            val dx = x - region.centerX
            val distanceSquared = dx * dx
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared
                best = region
            }
        }
        return best
    }

    private fun previewLabelFor(key: KeyRegion): String? {
        val value = key.value
        if (value.length != 1 || !value[0].isLetterOrDigit()) return null
        return if (shiftMode != ShiftMode.OFF) value.uppercase() else value
    }

    private fun updatePreview(key: KeyRegion?) {
        val label = key?.let(::previewLabelFor)
        // The receiver copies these bounds synchronously. Reuse the geometry's
        // immutable-for-this-frame RectF instead of allocating on every tap.
        onKeyPreviewChanged(label, key?.takeIf { label != null }?.visualBounds)
    }

    /**
     * Capture the spatial distribution at pointer-down. Aggregate shadow
     * diagnostics are retained, and the same ranking may resolve a genuinely
     * ambiguous key only when the editor's current word prefix supports it.
     */
    private fun recordSpatialShadow(
        event: MotionEvent,
        pointerIndex: Int,
        pressedKey: KeyRegion?
    ): SpatialTouchRanking? {
        val legacyKey = pressedKey?.value ?: return null
        if (legacyKey.length != 1 || !legacyKey[0].isLetterOrDigit()) return null

        val geometries = spatialKeyGeometries
        if (geometries.isEmpty()) return null
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        // Confident centre hits use the physical path. Run the Gaussian model
        // only in the final ~10dp around a same-row boundary. At the Nord 5's
        // ~35.6dp letter pitch this limits the expensive decoder to roughly the
        // outer 28% of a key column instead of running it on ordinary centre taps.
        if (!SpatialTouchGate.shouldRank(
                x = x,
                y = y,
                rawKey = legacyKey,
                rowIndex = pressedKey.rowIndex,
                keys = geometries,
                boundaryMarginPx = dp(SPATIAL_BOUNDARY_MARGIN_DP)
            )
        ) return null

        val ranking = spatialTouchModel.rank(
            x = x,
            y = y,
            keys = geometries,
            touchMajorPx = event.getTouchMajor(pointerIndex),
            touchMinorPx = event.getTouchMinor(pointerIndex),
            topK = 3
        )
        onSpatialShadowSample(
            ranking,
            if (shiftMode != ShiftMode.OFF) legacyKey.uppercase() else legacyKey
        )
        return ranking
    }

    private fun backspacePointerLeftKey(key: KeyRegion?, x: Float, y: Float): Boolean {
        val bounds = key?.touchBounds ?: return true
        val slop = dp(BACKSPACE_EXIT_SLOP_DP)
        return x < bounds.left - slop || x > bounds.right + slop ||
            y < bounds.top - slop || y > bounds.bottom + slop
    }

    private var pressedKeyValues: MutableSet<String> = HashSet(4)
    private var nextPressedKeyValues: MutableSet<String> = HashSet(4)

    private fun refreshPressedVisualsAndPreview() {
        val next = nextPressedKeyValues
        next.clear()
        activePresses.forEachActive { _, press ->
            if (!press.cancelled) press.key?.value?.let(next::add)
        }

        pressedKeyValues.forEach { value ->
            if (value !in next) animateKeyPress(value, pressed = false)
        }
        next.forEach { value ->
            if (value !in pressedKeyValues) animateKeyPress(value, pressed = true)
        }

        val previous = pressedKeyValues
        pressedKeyValues = nextPressedKeyValues
        nextPressedKeyValues = previous

        val latest = activePresses.latestPress()
        updatePreview(latest?.takeUnless { it.cancelled }?.key)
        invalidate()
    }

    /**
     * One Choreographer callback advances all active keycap timelines. This
     * avoids constructing/cancelling a ValueAnimator and two listener objects
     * for every press and release during rapid typing.
     */
    private fun animateKeyPress(value: String, pressed: Boolean) {
        val key = keyRegions.firstOrNull { it.value == value } ?: return
        val now = System.nanoTime()
        val current = currentPressProgress(key, now)
        val target = if (pressed) 1f else 0f
        if (abs(current - target) < 0.001f) {
            key.pressProgress = target
            key.isAnimating = false
            invalidate()
            return
        }
        key.animationFrom = current
        key.animationTo = target
        key.animationStartNanos = now
        key.animationDurationNanos = (
            if (pressed) KEY_PRESS_IN_DURATION_MS else KEY_PRESS_OUT_DURATION_MS
        ) * 1_000_000L
        key.pressProgress = current
        key.isAnimating = true
        schedulePressAnimationFrame()
    }

    private fun currentPressProgress(key: KeyRegion, nowNanos: Long): Float {
        if (!key.isAnimating || key.animationDurationNanos <= 0L) return key.pressProgress
        val fraction = ((nowNanos - key.animationStartNanos).toFloat() /
            key.animationDurationNanos.toFloat()).coerceIn(0f, 1f)
        val eased = fraction * fraction * (3f - 2f * fraction)
        return key.animationFrom + (key.animationTo - key.animationFrom) * eased
    }

    private fun schedulePressAnimationFrame() {
        if (pressFrameScheduled || !isAttachedToWindow) return
        pressFrameScheduled = true
        choreographer.postFrameCallback(pressFrameCallback)
    }

    private fun onPressAnimationFrame(frameTimeNanos: Long) {
        pressFrameScheduled = false
        var active = false
        for (key in keyRegions) {
            if (!key.isAnimating) continue
            val fraction = ((frameTimeNanos - key.animationStartNanos).toFloat() /
                key.animationDurationNanos.toFloat()).coerceIn(0f, 1f)
            val eased = fraction * fraction * (3f - 2f * fraction)
            key.pressProgress = key.animationFrom +
                (key.animationTo - key.animationFrom) * eased
            if (fraction >= 1f) {
                key.pressProgress = key.animationTo
                key.isAnimating = false
            } else {
                active = true
            }
        }
        postInvalidateOnAnimation()
        if (active) schedulePressAnimationFrame()
    }

    private fun cancelPressAnimations() {
        if (pressFrameScheduled) {
            choreographer.removeFrameCallback(pressFrameCallback)
            pressFrameScheduled = false
        }
        keyRegions.forEach {
            it.isAnimating = false
            it.pressProgress = 0f
        }
        pressedKeyValues.clear()
        nextPressedKeyValues.clear()
    }

    private fun addPointerPress(event: MotionEvent, pointerIndex: Int) {
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val key = findKey(
            x = x,
            y = y,
            touchMajorPx = event.getTouchMajor(pointerIndex),
            touchMinorPx = event.getTouchMinor(pointerIndex)
        )
        activePresses.down(pointerId, key, x, y)
        // Trigger tactile feedback on contact, not after finger release. The
        // visual press and popup begin on this same input event.
        if (key != null) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // Backspace repeat is intentionally single-pointer only. A second finger
        // cancels repeat timing but does not cancel either key tap.
        if (activePresses.activeCount == 1 && key?.value == "BACKSPACE") {
            backspacePointerId = pointerId
            scheduleBackspaceRepeat(pointerId)
        } else {
            cancelBackspaceRepeat()
            backspacePointerId = null
        }

        recordSpatialShadow(event, pointerIndex, key)?.let { ranking ->
            spatialRankingsByPointer[pointerId] = ranking
        }
        refreshPressedVisualsAndPreview()
    }

    private fun movePointers(event: MotionEvent) {
        var visualChanged = false
        // Iterate the tracker in place. The previous active() call created a
        // List plus one Pair per pointer for every ACTION_MOVE event.
        activePresses.forEachActive { pointerId, press ->
            val pointerIndex = event.findPointerIndex(pointerId)
            if (pointerIndex < 0) {
                activePresses.cancel(pointerId)
                spatialRankingsByPointer.remove(pointerId)
                if (backspacePointerId == pointerId) {
                    cancelBackspaceRepeat()
                    backspacePointerId = null
                }
                visualChanged = true
            } else {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val movedOutside = activePresses.move(pointerId, x, y)
                val leftBackspace = press.key?.value == "BACKSPACE" &&
                    backspacePointerLeftKey(press.key, x, y)
                if (movedOutside || leftBackspace) {
                    activePresses.cancel(pointerId)
                    spatialRankingsByPointer.remove(pointerId)
                    if (backspacePointerId == pointerId) {
                        cancelBackspaceRepeat()
                        backspacePointerId = null
                    }
                    visualChanged = true
                }
            }
        }
        if (visualChanged) refreshPressedVisualsAndPreview()
    }

    private fun releasePointer(event: MotionEvent, pointerIndex: Int) {
        val releaseStartedAt = System.nanoTime()
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val tracked = activePresses.get(pointerId)
        val spatialRanking = spatialRankingsByPointer.remove(pointerId)
        if (tracked?.key?.value == "BACKSPACE" && backspacePointerLeftKey(tracked.key, x, y)) {
            activePresses.cancel(pointerId)
        }
        val released = activePresses.release(pointerId, x, y)
        released?.let { press ->
            val region = press.key
            val guardBounds = region?.modeSwitchActivationBounds
            if (guardBounds != null && !ModeSwitchIntentPolicy.acceptsRelease(
                    bounds = guardBounds,
                    downX = press.downX,
                    downY = press.downY,
                    upX = x,
                    upY = y,
                    density = density,
                    touchMajorPx = event.getTouchMajor(pointerIndex),
                    touchMinorPx = event.getTouchMinor(pointerIndex)
                )) {
                press.cancelled = true
            }
        }

        if (backspacePointerId == pointerId) {
            cancelBackspaceRepeat()
            backspacePointerId = null
        }

        refreshPressedVisualsAndPreview()

        if (released != null && !released.cancelled) {
            val rawKey = released.key?.value
            val key = if (
                rawKey != null && rawKey.length == 1 && rawKey[0] in 'a'..'z' &&
                spatialRanking != null && symbolPage < 0
            ) onResolveAmbiguousKey(rawKey, spatialRanking) else rawKey
            val wasRepeatedBackspace = repeatedBackspacePointers.remove(pointerId)
            if (key != null && !(key == "BACKSPACE" && wasRepeatedBackspace)) {
                handleKey(key)
            }
            performClick()
        } else {
            repeatedBackspacePointers.remove(pointerId)
        }

        // If the remaining pointer is backspace after a concurrent finger was
        // lifted, restart the hold timer from this point rather than deleting
        // unexpectedly while another key was being tapped.
        if (activePresses.activeCount == 1) {
            val remainingPointerId = activePresses.latestPointerId()
            val remainingPress = activePresses.latestPress()
            if (remainingPointerId != null && remainingPress?.key?.value == "BACKSPACE" &&
                !remainingPress.cancelled
            ) {
                backspacePointerId = remainingPointerId
                scheduleBackspaceRepeat(remainingPointerId)
            }
        }
        if (activePresses.activeCount == 0) {
            repeatedBackspacePointers.clear()
        }
        onTouchReleaseLatency(System.nanoTime() - releaseStartedAt)
    }

    private fun cancelAllPointers() {
        cancelBackspaceRepeat()
        backspacePointerId = null
        activePresses.cancelAll()
        repeatedBackspacePointers.clear()
        spatialRankingsByPointer.clear()
        pressedKeyValues.forEach { animateKeyPress(it, pressed = false) }
        pressedKeyValues.clear()
        nextPressedKeyValues.clear()
        updatePreview(null)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelBackspaceRepeat()
                backspacePointerId = null
                activePresses.cancelAll()
                repeatedBackspacePointers.clear()
                addPointerPress(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                addPointerPress(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                movePointers(event)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_UP -> {
                releasePointer(event, event.actionIndex)
                if (activePresses.activeCount > 0) cancelAllPointers()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelAllPointers()
                return true
            }
        }
        return true
    }

    private fun scheduleBackspaceRepeat(pointerId: Int) {
        cancelBackspaceRepeat()
        val repeat = object : Runnable {
            override fun run() {
                val press = activePresses.get(pointerId)
                if (activePresses.activeCount != 1 ||
                    backspacePointerId != pointerId ||
                    press == null || press.cancelled ||
                    press.key?.value != "BACKSPACE"
                ) return

                repeatedBackspacePointers.add(pointerId)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKeyPressed("<BACKSPACE>")
                touchHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
            }
        }
        backspaceRepeatRunnable = repeat
        touchHandler.postDelayed(repeat, BACKSPACE_LONG_PRESS_TIMEOUT_MS)
    }

    private fun cancelBackspaceRepeat() {
        backspaceRepeatRunnable?.let(touchHandler::removeCallbacks)
        backspaceRepeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelBackspaceRepeat()
        cancelAllPointers()
        cancelPressAnimations()
        super.onDetachedFromWindow()
    }

    private fun handleKey(key: String) {
        when (key) {
            "SHIFT" -> {
                shiftMode = ShiftModePolicy.nextAfterTap(shiftMode)
                geometryDirty = true
                invalidate()
            }
            "BACKSPACE" -> onKeyPressed("<BACKSPACE>")
            "SPACE" -> onKeyPressed("<SPACE>")
            "EMOJI" -> onKeyPressed("<EMOJI>")
            "ENTER" -> onKeyPressed("<ENTER>")
            "123" -> {
                symbolPage = 0
                geometryDirty = true
                invalidate()
            }
            "SYM_NEXT" -> {
                if (symbolPage >= 0) {
                    symbolPage = (symbolPage + 1) % 3
                    geometryDirty = true
                    invalidate()
                }
            }
            "ABC" -> {
                symbolPage = -1
                geometryDirty = true
                invalidate()
            }
            else -> {
                val previousShiftMode = shiftMode
                val output = ShiftModePolicy.outputForKey(key, previousShiftMode)
                onKeyPressed(output)
                shiftMode = ShiftModePolicy.afterKeyCommitted(previousShiftMode, key)
                if (shiftMode != previousShiftMode) {
                    geometryDirty = true
                    invalidate()
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
