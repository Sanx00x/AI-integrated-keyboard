package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

internal data class ClipboardPopupItem(
    val id: Long,
    val title: String,
    val detail: String,
    val kind: String
)

/**
 * Draw-only four-item clipboard chooser. The IME button retains the active
 * touch stream and forwards raw screen coordinates to this view.
 */
internal class ClipboardHistoryPopupView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
    private fun dp(value: Float) = value * density

    private val panel = RectF()
    private val row = RectF()
    private val iconBox = RectF()
    private val reflection = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val popupTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // This overlay darkens only this popup, not the shared keyboard theme.
        color = Color.argb(140, 8, 15, 28)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.7f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 249, 255)
        textSize = dp(14.2f)
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 207, 219, 237)
        textSize = dp(10.8f)
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.NORMAL
        )
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 225, 238, 255)
        textSize = dp(12.5f)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
    }

    private var items: List<ClipboardPopupItem> = emptyList()
    private var selectedIndex = -1
    private var screenOriginX = 0
    private var screenOriginY = 0
    private val screenLocation = IntArray(2)
    private val rowHeight = dp(45f)
    private val verticalPadding = dp(8f)

    init {
        setWillNotDraw(false)
        background = LiquidGlassDrawable(
            density = density,
            cornerRadiusPx = dp(24f),
            insetPx = 0f,
            tintStrength = 1.0f
        )
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun show(entries: List<ClipboardPopupItem>) {
        items = entries.take(4)
        selectedIndex = -1
        visibility = VISIBLE
        refreshScreenOrigin()
        updatePanelGeometry()
        invalidate()
    }

    fun desiredWidthPx(): Int {
        val available = resources.displayMetrics.widthPixels - dp(32f).toInt()
        return min(dp(286f).toInt(), max(dp(180f).toInt(), available))
    }

    fun desiredHeightPx(): Int =
        (verticalPadding * 2f + rowHeight * max(1, items.size)).toInt()

    fun refreshScreenOrigin() {
        getLocationOnScreen(screenLocation)
        screenOriginX = screenLocation[0]
        screenOriginY = screenLocation[1]
    }

    fun replaceEntries(entries: List<ClipboardPopupItem>) {
        items = entries.take(4)
        if (selectedIndex !in items.indices) selectedIndex = -1
        updatePanelGeometry()
        invalidate()
    }

    private fun updatePanelGeometry() {
        if (width <= 0 || height <= 0) return
        val inset = dp(1f)
        panel.set(
            inset,
            inset,
            width.toFloat() - inset,
            height.toFloat() - inset
        )
    }

    /** Returns the selected history item ID, or null when the finger is elsewhere. */
    fun updateSelectionFromScreen(rawX: Float, rawY: Float): Long? {
        // PopupWindow may be repositioned by the window manager after layout.
        // Refresh its actual screen origin for every drag sample.
        refreshScreenOrigin()
        if (width > 0 && height > 0) updatePanelGeometry()

        if (visibility != VISIBLE || items.isEmpty()) {
            selectedIndex = -1
            invalidate()
            return null
        }

        val x = rawX - screenOriginX
        val y = rawY - screenOriginY
        var next = -1

        if (panel.contains(x, y)) {
            val rowTop = panel.top + verticalPadding
            val index = ((y - rowTop) / rowHeight).toInt()
            if (index in items.indices) next = index
        }

        if (next != selectedIndex) {
            selectedIndex = next
            invalidate()
        }

        return items.getOrNull(selectedIndex)?.id
    }

    fun selectedItemId(): Long? = items.getOrNull(selectedIndex)?.id

    fun hide() {
        selectedIndex = -1
        visibility = INVISIBLE
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (visibility == VISIBLE) {
            refreshScreenOrigin()
            updatePanelGeometry()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || panel.isEmpty) return

        val radius = dp(24f)

        // Draw a dark translucent layer over this popup's glass background.
        // The reflection and rim are drawn afterwards to retain the glass look.
        canvas.drawRoundRect(panel, radius, radius, popupTintPaint)

        reflection.set(
            panel.left + dp(2f),
            panel.top + dp(1f),
            panel.right - dp(2f),
            panel.top + dp(26f)
        )
        fillPaint.color = Color.argb(18, 255, 255, 255)
        canvas.drawRoundRect(reflection, radius, radius, fillPaint)

        rimPaint.color = Color.argb(30, 238, 246, 255)
        canvas.drawRoundRect(panel, radius, radius, rimPaint)

        if (items.isEmpty()) {
            textPaint.textSize = dp(13.5f)
            canvas.drawText(
                "Nothing copied yet",
                panel.left + dp(17f),
                panel.centerY() + dp(4f),
                textPaint
            )
            detailPaint.textSize = dp(10.5f)
            canvas.drawText(
                "Copy text or media to build history",
                panel.left + dp(17f),
                panel.centerY() + dp(21f),
                detailPaint
            )
            return
        }

        items.forEachIndexed { index, item ->
            val rowTop = panel.top + verticalPadding + index * rowHeight
            row.set(
                panel.left + dp(6f),
                rowTop,
                panel.right - dp(6f),
                rowTop + rowHeight
            )

            if (index == selectedIndex) {
                fillPaint.color = Color.argb(68, 235, 245, 255)
                canvas.drawRoundRect(row, dp(12f), dp(12f), fillPaint)
            }

            val iconSize = dp(28f)
            val iconCx = row.left + dp(22f)
            val iconCy = row.centerY()
            iconBox.set(
                iconCx - iconSize / 2f,
                iconCy - iconSize / 2f,
                iconCx + iconSize / 2f,
                iconCy + iconSize / 2f
            )

            fillPaint.color = when (item.kind) {
                "image" -> Color.argb(58, 126, 194, 255)
                "file" -> Color.argb(48, 184, 164, 255)
                "link" -> Color.argb(45, 119, 210, 195)
                else -> Color.argb(42, 232, 241, 255)
            }
            canvas.drawRoundRect(iconBox, dp(9f), dp(9f), fillPaint)

            val glyph = when (item.kind) {
                "image" -> "\u25A7"
                "file" -> "F"
                "link" -> "\u2197"
                else -> "T"
            }
            val iconBaseline = iconCy - (iconPaint.ascent() + iconPaint.descent()) / 2f
            canvas.drawText(glyph, iconCx, iconBaseline, iconPaint)

            val textX = row.left + dp(44f)
            val maxTextWidth = row.right - textX - dp(8f)

            textPaint.textSize = dp(13.5f)
            textPaint.color = Color.rgb(246, 249, 255)
            canvas.drawText(
                ellipsize(item.title, textPaint, maxTextWidth),
                textX,
                rowTop + dp(19f),
                textPaint
            )

            detailPaint.textSize = dp(10.5f)
            canvas.drawText(
                ellipsize(item.detail, detailPaint, maxTextWidth),
                textX,
                rowTop + dp(34f),
                detailPaint
            )
        }
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        val suffix = "\u2026"
        if (paint.measureText(value) <= maxWidth) return value

        var end = value.length
        while (end > 0 &&
            paint.measureText(value, 0, end) + paint.measureText(suffix) > maxWidth
        ) {
            end--
        }

        return if (end <= 0) suffix else value.substring(0, end) + suffix
    }
}
