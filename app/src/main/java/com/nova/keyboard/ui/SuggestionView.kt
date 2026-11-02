package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.keyboard.prediction.WordSuggestion

class SuggestionView(
    context: Context,
    private val onClipboardTap: () -> Unit = {},
    private val onClipboardGesture: (ClipboardButtonGesture, Float, Float) -> Unit = { _, _, _ -> },
    private val onSuggestionSelected: (String) -> Unit
) : LinearLayout(context) {

    private val suggestionLabels = mutableListOf<TextView>()
    private lateinit var clipboardButton: ClipboardButtonView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        weightSum = 3f
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)

        repeat(3) { index ->

            val label = TextView(context).apply {

                layoutParams = LayoutParams(
                    0,
                    LayoutParams.MATCH_PARENT,
                    1f
                )

                gravity = Gravity.CENTER

                setTextColor(Color.rgb(247, 250, 255))
                visibility = View.VISIBLE

                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    15f
                )

                typeface = Typeface.create(
                    "sans-serif-medium",
                    Typeface.NORMAL
                )

                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false

                setPadding(dp(5), 0, dp(5), 0)

                setOnClickListener {

                    val word = it.tag as? String
                        ?: return@setOnClickListener

                    onSuggestionSelected(word)
                }
            }

            suggestionLabels.add(label)
            addView(label)

            if (index < 2) {

                val divider = View(context).apply {
                    setBackgroundColor(Color.argb(24, 220, 236, 255))
                }

                val dividerParams = LayoutParams(
                    dp(1),
                    dp(22)
                )

                addView(divider, dividerParams)
            }
        }

        // Three full-width suggestion hit areas sit inside a 48dp-high row.
        // On the Nord 5 each column is >120dp wide, so row slots comfortably
        // exceed the Android 48dp touch-target recommendation in both axes.
        val clipboardDivider = View(context).apply {
            setBackgroundColor(Color.argb(30, 220, 236, 255))
        }
        addView(clipboardDivider, LayoutParams(dp(1), dp(22)))

        clipboardButton = ClipboardButtonView(context, onClipboardTap, onClipboardGesture)
        addView(clipboardButton, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))

        minimumHeight = dp(48)
        elevation = dp(2).toFloat()
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setClipboardHasItems(hasItems: Boolean) {
        clipboardButton.setHasItems(hasItems)
    }

    fun setClipboardEnabled(enabled: Boolean) {
        clipboardButton.isEnabled = enabled
        clipboardButton.alpha = if (enabled) 1f else 0.38f
    }

    internal fun clipboardAnchorView(): View = clipboardButton

    fun showSuggestions(
        suggestions: List<WordSuggestion>
    ) {

        var changed = false
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            changed = true
        }
        if (alpha != 1f) {
            alpha = 1f
            changed = true
        }
        val scaledDensity = resources.displayMetrics.scaledDensity
        suggestionLabels.forEachIndexed { index, label ->
            val suggestion = suggestions.getOrNull(index)
            val isIdleHint = suggestions.isEmpty() && index == 1
            val displayText = suggestion?.word ?: if (isIdleHint) "Type to predict" else ""
            val displayColor = if (suggestion != null) {
                Color.rgb(247, 250, 255)
            } else {
                Color.rgb(173, 191, 215)
            }
            val description = suggestion?.let { "Insert ${it.word}" }
                ?: if (isIdleHint) "Type to see predictions" else "No suggestion"
            val displaySizeSp = if (isIdleHint) 12f else 15f

            // Avoid text/layout work when a repeated prediction result leaves
            // this slot unchanged. Only invalidate when something actually changes.
            if (!android.text.TextUtils.equals(label.text, displayText)) {
                label.text = displayText
                changed = true
            }
            if (label.currentTextColor != displayColor) {
                label.setTextColor(displayColor)
                changed = true
            }
            if (label.tag != suggestion?.word) {
                label.tag = suggestion?.word
                changed = true
            }
            val enabled = suggestion != null
            if (label.isEnabled != enabled) {
                label.isEnabled = enabled
                changed = true
            }
            if (label.isClickable != enabled) {
                label.isClickable = enabled
                changed = true
            }
            if (!android.text.TextUtils.equals(label.contentDescription, description)) {
                label.contentDescription = description
                changed = true
            }
            val displaySizePx = displaySizeSp * scaledDensity
            if (kotlin.math.abs(label.textSize - displaySizePx) > 0.5f) {
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, displaySizeSp)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun dp(value: Int): Int {

        return (
                value * resources.displayMetrics.density + 0.5f
                ).toInt()
    }
}