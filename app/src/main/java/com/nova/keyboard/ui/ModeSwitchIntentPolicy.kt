package com.nova.keyboard.ui

import kotlin.math.min

/**
 * Protects layout-changing controls from incidental edge taps during fast typing.
 *
 * The visible key still occupies its normal key cell. Only a deliberate press
 * inside the inset activation core can switch keyboard mode. The guard uses the
 * touchscreen contact ellipse reported by MotionEvent as a small adaptive margin;
 * devices or events without contact-size data simply use the fixed safe core.
 */
internal object ModeSwitchIntentPolicy {
    const val HORIZONTAL_INSET_DP = 2f
    const val VERTICAL_INSET_DP = 3f
    const val MAX_CONTACT_INSET_DP = 1f
    const val CONTACT_INSET_FACTOR = 0.08f
    const val MAX_RELEASE_DRIFT_DP = 14f

    data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun requiresGuard(key: String): Boolean =
        key == "123" || key == "ABC" || key == "SYM_NEXT"

    fun createActivationBounds(
        cellLeftPx: Float,
        cellTopPx: Float,
        cellRightPx: Float,
        cellBottomPx: Float,
        density: Float
    ): Bounds {
        require(density > 0f)
        val horizontalInsetPx = HORIZONTAL_INSET_DP * density
        val verticalInsetPx = VERTICAL_INSET_DP * density
        val result = Bounds(
            left = cellLeftPx + horizontalInsetPx,
            top = cellTopPx + verticalInsetPx,
            right = cellRightPx - horizontalInsetPx,
            bottom = cellBottomPx - verticalInsetPx
        )
        require(result.width > 0f && result.height > 0f) {
            "Mode-switch activation bounds are too small"
        }
        return result
    }

    /**
     * Contact-size-aware centre test. Android reports touchMajor/minor as the
     * display-pixel axes of the finger-contact ellipse on touch screens.
     * A small fraction of its narrower axis is held back from each edge, capped
     * at 2dp, so a broad thumb landing across a boundary is treated as ambiguous.
     */
    fun contains(
        bounds: Bounds,
        x: Float,
        y: Float,
        density: Float,
        touchMajorPx: Float = 0f,
        touchMinorPx: Float = 0f
    ): Boolean {
        if (density <= 0f) return false
        val minorAxis = when {
            touchMajorPx.isFinite() && touchMinorPx.isFinite() &&
                touchMajorPx > 0f && touchMinorPx > 0f -> min(touchMajorPx, touchMinorPx)
            else -> 0f
        }
        val maxContactInsetPx = MAX_CONTACT_INSET_DP * density
        val contactInsetPx = (minorAxis * CONTACT_INSET_FACTOR)
            .coerceIn(0f, maxContactInsetPx)
        return x >= bounds.left + contactInsetPx &&
            x <= bounds.right - contactInsetPx &&
            y >= bounds.top + contactInsetPx &&
            y <= bounds.bottom - contactInsetPx
    }

    /**
     * Mode changes must start and finish in the activation core and remain a
     * stable short tap. This is stricter than the 36dp letter-key drift policy,
     * without adding any dwell delay to a valid tap.
     */
    fun acceptsRelease(
        bounds: Bounds,
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        density: Float,
        touchMajorPx: Float = 0f,
        touchMinorPx: Float = 0f
    ): Boolean {
        if (!contains(bounds, upX, upY, density, touchMajorPx, touchMinorPx)) return false
        val dx = upX - downX
        val dy = upY - downY
        val maxDriftPx = MAX_RELEASE_DRIFT_DP * density
        return dx * dx + dy * dy <= maxDriftPx * maxDriftPx
    }

    /** Useful to test the geometry reduction without Android View dependencies. */
    fun activationAreaRatio(bounds: Bounds, originalWidthPx: Float, originalHeightPx: Float): Float {
        val originalArea = originalWidthPx * originalHeightPx
        if (originalArea <= 0f) return 0f
        return (bounds.width * bounds.height / originalArea).coerceIn(0f, 1f)
    }
}
