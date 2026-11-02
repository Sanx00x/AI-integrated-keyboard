package com.nova.keyboard.ui

import kotlin.math.max
import kotlin.math.min

/**
 * One source of truth for NOVA's keyboard dimensions and QWERTY key geometry.
 *
 * Values are expressed in dp at the policy boundary and converted to pixels
 * only when geometry is generated. This keeps the view, IME window and tests
 * aligned instead of maintaining separate magic numbers in each class.
 */
internal object KeyboardLayoutPolicy {
    const val PORTRAIT_BODY_HEIGHT_DP = 248f
    const val MAX_KEY_GRID_WIDTH_DP = 420f
    const val SIDE_INSET_DP = 3.5f
    // 1dp inset per side gives a 2dp inter-key gap while using more of the
    // constrained ten-column width for the actual letter caps.
    const val KEY_VISUAL_INSET_X_DP = 1f
    // Preserve nearly 48dp caps while using the larger row pitch for clear gaps.
    const val KEY_VISUAL_INSET_Y_DP = 1.0f
    const val TOUCH_EXPANSION_X_DP = 6f
    const val KEY_CORNER_RADIUS_DP = 10f
    const val SURFACE_INSET_DP = 2.5f
    const val SURFACE_CORNER_RADIUS_DP = 13f

    data class Profile(
        val name: String,
        val keyboardBodyHeightDp: Float,
        val suggestionHeightDp: Float,
        val rowGapDp: Float,
        val topPaddingDp: Float,
        val bottomPaddingDp: Float
    ) {
        val totalHeightDp: Float
            get() = keyboardBodyHeightDp + suggestionHeightDp
    }

    data class KeySpec(val value: String, val units: Float)

    data class KeyFrame(
        val value: String,
        val rowIndex: Int,
        val cellLeftPx: Float,
        val cellRightPx: Float,
        val visualLeftPx: Float,
        val visualTopPx: Float,
        val visualRightPx: Float,
        val visualBottomPx: Float,
        val touchLeftPx: Float,
        val touchTopPx: Float,
        val touchRightPx: Float,
        val touchBottomPx: Float
    ) {
        val centerX: Float
            get() = (cellLeftPx + cellRightPx) / 2f

        val centerY: Float
            get() = (visualTopPx + visualBottomPx) / 2f

        val cellWidthPx: Float
            get() = cellRightPx - cellLeftPx

        val visualHeightPx: Float
            get() = visualBottomPx - visualTopPx
    }

    /**
     * Nord 5 portrait calibration uses the measured 800dp logical screen
     * height. Short/landscape windows get a shorter keyboard so they do not
     * consume nearly the entire usable display.
     */
    fun profileForDisplay(screenHeightDp: Int, isLandscape: Boolean): Profile {
        return when {
            isLandscape -> compactLandscapeProfile()
            screenHeightDp in 1..500 -> smallPortraitProfile()
            screenHeightDp in 501..719 -> compactPortraitProfile()
            else -> nord5PortraitProfile()
        }
    }

    private fun compactLandscapeProfile() = Profile(
        name = "COMPACT_LANDSCAPE",
        keyboardBodyHeightDp = 184f,
        suggestionHeightDp = 40f,
        rowGapDp = 3f,
        topPaddingDp = 3f,
        bottomPaddingDp = 4f
    )

    private fun smallPortraitProfile() = Profile(
        name = "SMALL_PORTRAIT",
        keyboardBodyHeightDp = 192f,
        suggestionHeightDp = 44f,
        rowGapDp = 3f,
        topPaddingDp = 4f,
        bottomPaddingDp = 5f
    )

    private fun compactPortraitProfile() = Profile(
        name = "COMPACT_PORTRAIT",
        keyboardBodyHeightDp = 216f,
        suggestionHeightDp = 48f,
        rowGapDp = 4f,
        topPaddingDp = 5f,
        bottomPaddingDp = 7f
    )

    private fun nord5PortraitProfile() = Profile(
        name = "NORD5_PORTRAIT",
        keyboardBodyHeightDp = PORTRAIT_BODY_HEIGHT_DP,
        suggestionHeightDp = 48f,
        rowGapDp = 5f,
        topPaddingDp = 0f,
        bottomPaddingDp = 7f
    )

    private fun profileForBodyHeight(bodyHeightDp: Float): Profile = when {
        bodyHeightDp < 190f -> compactLandscapeProfile()
        bodyHeightDp < 208f -> smallPortraitProfile()
        bodyHeightDp < 224f -> compactPortraitProfile()
        else -> nord5PortraitProfile()
    }

    /**
     * Symbol pages: 0 = common punctuation, 1 = extended currency/brackets,
     * 2 = mathematics/arrows. -1 selects the alphabetic QWERTY page.
     * Every row is weighted to a ten-unit grid, so the touch geometry remains
     * deterministic across all pages.
     */
    fun rows(symbolPage: Int): List<List<KeySpec>> {
        if (symbolPage >= 0) {
            val page = symbolPage.coerceIn(0, 2)
            val secondRow = when (page) {
                0 -> listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
                1 -> listOf("~", "_", "\\", "|", "[", "]", "{", "}", "₹")
                else -> listOf("∑", "∏", "∫", "∂", "Δ", "Ω", "μ", "°", "≡")
            }
            val thirdSymbols = when (page) {
                0 -> listOf("=", "/", ";", ":", "!", "?")
                1 -> listOf("€", "£", "¥", "₩", "\"", "'")
                else -> listOf("←", "→", "↑", "↓", "✓", "★")
            }
            val firstRow = if (page == 2) {
                listOf("√", "π", "÷", "×", "±", "≠", "≤", "≥", "∞", "≈")
            } else {
                "1234567890".map { it.toString() }
            }
            return listOf(
                firstRow.map { KeySpec(it, 1f) },
                secondRow.map { KeySpec(it, 1f) },
                listOf(KeySpec("SYM_NEXT", 1.4f)) +
                    thirdSymbols.map { KeySpec(it, 1f) } +
                    listOf(KeySpec("BACKSPACE", 1.6f)),
                bottomRow(isSymbols = true)
            )
        }

        return listOf(
            "qwertyuiop".map { KeySpec(it.toString(), 1f) },
            "asdfghjkl".map { KeySpec(it.toString(), 1f) },
            listOf(KeySpec("SHIFT", 1.5f)) +
                "zxcvbnm".map { KeySpec(it.toString(), 1f) } +
                listOf(KeySpec("BACKSPACE", 1.5f)),
            bottomRow(isSymbols = false)
        )
    }

    /** Backwards-compatible entry point for existing geometry callers/tests. */
    fun rows(isSymbols: Boolean): List<List<KeySpec>> = rows(if (isSymbols) 0 else -1)

    private fun bottomRow(isSymbols: Boolean): List<KeySpec> = listOf(
        KeySpec(if (isSymbols) "ABC" else "123", 1.25f),
        KeySpec(",", 1f),
        KeySpec("EMOJI", 1f),
        // Give SPACE an extra 0.30 pitch and make period narrower. Combined
        // with bottom-row cell-priority hit testing, this reduces accidental
        // full-stop commits during rapid two-thumb spacebar taps.
        KeySpec("SPACE", 4.15f),
        KeySpec(".", 0.70f),
        KeySpec("ENTER", 1.9f)
    )

    /**
     * Builds immutable hit/paint geometry for the current measured view.
     * Alphabetic rows use ten equal key pitches; the home row is centered by
     * half a pitch; special keys use explicit width units that always add to 10.
     */
    fun createKeyFrames(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        isSymbols: Boolean
    ): List<KeyFrame> = createKeyFrames(
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        symbolPage = if (isSymbols) 0 else -1
    )

    fun createKeyFrames(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        symbolPage: Int
    ): List<KeyFrame> {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return emptyList()

        val widthDp = widthPx / density
        val heightDp = heightPx / density
        val viewportProfile = profileForBodyHeight(heightDp)
        val rowGapDp = viewportProfile.rowGapDp
        val topPaddingDp = viewportProfile.topPaddingDp
        val bottomPaddingDp = viewportProfile.bottomPaddingDp

        val gridWidthDp = min(
            max(0f, widthDp - SIDE_INSET_DP * 2f),
            MAX_KEY_GRID_WIDTH_DP
        )
        if (gridWidthDp <= 0f) return emptyList()

        val gridLeftDp = (widthDp - gridWidthDp) / 2f
        val unitWidthDp = gridWidthDp / 10f
        val rowGapPx = rowGapDp * density
        val topPaddingPx = topPaddingDp * density
        val bottomPaddingPx = bottomPaddingDp * density
        val availableRowsPx = heightPx - topPaddingPx - bottomPaddingPx - rowGapPx * 3f
        if (availableRowsPx <= 0f) return emptyList()

        val rowHeightPx = availableRowsPx / 4f
        val visualInsetXPx = KEY_VISUAL_INSET_X_DP * density
        val visualInsetYPx = KEY_VISUAL_INSET_Y_DP * density
        val touchExpansionPx = TOUCH_EXPANSION_X_DP * density
        val rows = rows(symbolPage)
        val result = ArrayList<KeyFrame>(rows.sumOf { it.size })

        rows.forEachIndexed { rowIndex, row ->
            val totalUnits = row.sumOf { it.units.toDouble() }.toFloat()
            var currentX = gridLeftDp + (10f - totalUnits) * unitWidthDp / 2f
            val rowTopPx = topPaddingPx + rowIndex * (rowHeightPx + rowGapPx)

            row.forEach { spec ->
                val cellWidthPx = spec.units * unitWidthDp * density
                val leftPx = currentX * density
                val rightPx = leftPx + cellWidthPx
                val visualTopPx = rowTopPx + visualInsetYPx
                val visualBottomPx = rowTopPx + rowHeightPx - visualInsetYPx

                result.add(
                    KeyFrame(
                        value = spec.value,
                        rowIndex = rowIndex,
                        cellLeftPx = leftPx,
                        cellRightPx = rightPx,
                        visualLeftPx = leftPx + visualInsetXPx,
                        visualTopPx = visualTopPx,
                        visualRightPx = rightPx - visualInsetXPx,
                        visualBottomPx = visualBottomPx,
                        touchLeftPx = max(0f, leftPx - touchExpansionPx),
                        touchTopPx = max(0f, rowTopPx - rowGapPx / 2f),
                        touchRightPx = min(widthPx.toFloat(), rightPx + touchExpansionPx),
                        touchBottomPx = min(
                            heightPx.toFloat(),
                            rowTopPx + rowHeightPx + rowGapPx / 2f
                        )
                    )
                )
                currentX += spec.units * unitWidthDp
            }
        }
        return result
    }
}
