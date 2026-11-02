package com.nova.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * A restrained optical overlay drawn over the IME's native, bounded background blur.
 * It deliberately contains no opaque panel fill and no full perimeter stroke: the
 * underlying application remains visible through the shared glass surface.
 * All geometry and shaders are cached; draw() performs no object allocation.
 */
class LiquidGlassDrawable(
    density: Float,
    private val cornerRadiusPx: Float,
    private val insetPx: Float = 0f,
    private val tintStrength: Float = 1f
) : Drawable() {

    private val density = density.coerceAtLeast(0.1f)
    private val surface = RectF()
    private val topReflection = RectF()
    private val lowerReflection = RectF()
    private val topGlint = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lowerReflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var fillShader: Shader? = null
    private var reflectionShader: Shader? = null
    private var lowerReflectionShader: Shader? = null
    private var glintShader: Shader? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        surface.set(
            bounds.left + insetPx,
            bounds.top + insetPx,
            bounds.right - insetPx,
            bounds.bottom - insetPx
        )
        if (surface.width() <= 0f || surface.height() <= 0f) return

        fun alpha(value: Int): Int =
            (value * tintStrength.coerceIn(0f, 1.15f)).roundToInt().coerceIn(0, 255)

        // Only a trace of cool tint. The substantial frosted appearance comes from
        // Window.setBackgroundBlurRadius(), not from painting an opaque gradient.
        fillShader = LinearGradient(
            0f, surface.top, 0f, surface.bottom,
            intArrayOf(
                Color.argb(alpha(18), 246, 250, 255),
                Color.argb(alpha(7), 194, 216, 246),
                Color.argb(alpha(11), 8, 15, 28)
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )

        topReflection.set(
            surface.left + density,
            surface.top + density,
            surface.right - density,
            surface.top + 42f * density
        )
        reflectionShader = LinearGradient(
            0f, topReflection.top, 0f, topReflection.bottom,
            intArrayOf(
                Color.argb(alpha(28), 255, 255, 255),
                Color.argb(alpha(12), 222, 237, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )

        lowerReflection.set(
            surface.left + 2f * density,
            surface.bottom - 8f * density,
            surface.right - 2f * density,
            surface.bottom - density
        )
        lowerReflectionShader = LinearGradient(
            surface.left, 0f, surface.right, 0f,
            intArrayOf(
                Color.argb(alpha(6), 112, 216, 255),
                Color.argb(alpha(13), 160, 180, 255),
                Color.argb(alpha(9), 222, 190, 255),
                Color.argb(alpha(4), 255, 255, 255)
            ),
            floatArrayOf(0f, 0.42f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )

        topGlint.set(
            surface.left + 22f * density,
            surface.top + 1.0f * density,
            surface.right - 22f * density,
            surface.top + 1.55f * density
        )
        glintShader = LinearGradient(
            topGlint.left, 0f, topGlint.right, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(alpha(58), 255, 255, 255),
                Color.argb(alpha(25), 196, 227, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.42f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        fillPaint.shader = fillShader
        reflectionPaint.shader = reflectionShader
        lowerReflectionPaint.shader = lowerReflectionShader
        glintPaint.shader = glintShader
    }

    override fun draw(canvas: Canvas) {
        if (surface.isEmpty) return
        val radius = cornerRadiusPx
        canvas.drawRoundRect(surface, radius, radius, fillPaint)

        val save = canvas.save()
        canvas.clipRect(surface.left, surface.top, surface.right, topReflection.bottom)
        canvas.drawRoundRect(topReflection, radius, radius, reflectionPaint)
        canvas.restoreToCount(save)

        canvas.drawRoundRect(lowerReflection, 3f * density, 3f * density, lowerReflectionPaint)
        canvas.drawRoundRect(topGlint, density, density, glintPaint)
    }

    override fun setAlpha(alpha: Int) {
        val safe = alpha.coerceIn(0, 255)
        fillPaint.alpha = safe
        reflectionPaint.alpha = safe
        lowerReflectionPaint.alpha = safe
        glintPaint.alpha = safe
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        reflectionPaint.colorFilter = colorFilter
        lowerReflectionPaint.colorFilter = colorFilter
        glintPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}