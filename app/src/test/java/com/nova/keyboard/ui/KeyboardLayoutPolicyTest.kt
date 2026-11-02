package com.nova.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutPolicyTest {
    @Test
    fun nordFivePortraitUsesMeasuredComfortProfile() {
        val profile = KeyboardLayoutPolicy.profileForDisplay(
            screenHeightDp = 800,
            isLandscape = false
        )

        assertEquals("NORD5_PORTRAIT", profile.name)
        assertEquals(248f, profile.keyboardBodyHeightDp, 0.001f)
        assertEquals(48f, profile.suggestionHeightDp, 0.001f)
        assertEquals(296f, profile.totalHeightDp, 0.001f)
    }

    @Test
    fun shortPortraitProfileDoesNotReuseLandscapeMetrics() {
        val profile = KeyboardLayoutPolicy.profileForDisplay(
            screenHeightDp = 480,
            isLandscape = false
        )

        assertEquals("SMALL_PORTRAIT", profile.name)
        assertEquals(192f, profile.keyboardBodyHeightDp, 0.001f)
        assertEquals(44f, profile.suggestionHeightDp, 0.001f)
        assertEquals(236f, profile.totalHeightDp, 0.001f)
    }

    @Test
    fun landscapeProfileLeavesMoreOfTheAppVisible() {
        val profile = KeyboardLayoutPolicy.profileForDisplay(
            screenHeightDp = 363,
            isLandscape = true
        )

        assertEquals("COMPACT_LANDSCAPE", profile.name)
        assertEquals(184f, profile.keyboardBodyHeightDp, 0.001f)
        assertEquals(40f, profile.suggestionHeightDp, 0.001f)
        assertEquals(224f, profile.totalHeightDp, 0.001f)
    }

    @Test
    fun nordFiveGeometryKeepsTenColumnPitchAndFourBalancedRows() {
        val density = 3.5f
        val geometry = KeyboardLayoutPolicy.createKeyFrames(
            widthPx = 1272,
            heightPx = (248f * density).toInt(),
            density = density,
            isSymbols = false
        )

        assertEquals(10, geometry.count { it.rowIndex == 0 })
        assertEquals(9, geometry.count { it.rowIndex == 1 })
        assertEquals(9, geometry.count { it.rowIndex == 2 })
        assertEquals(6, geometry.count { it.rowIndex == 3 })

        val firstRow = geometry.filter { it.rowIndex == 0 }
        val pitchDp = firstRow.first().cellWidthPx / density
        assertEquals(35.6429f, pitchDp, 0.02f)
        assertEquals(54.75f, firstRow.first().visualHeightPx / density +
            2f * KeyboardLayoutPolicy.KEY_VISUAL_INSET_Y_DP, 0.02f)
        assertEquals(49.75f, firstRow.first().visualHeightPx / density, 0.02f)
        assertEquals(59.75f,
            (firstRow.first().touchBottomPx - firstRow.first().touchTopPx) / density,
            0.02f
        )
        assertEquals(33.6429f, firstRow.first().visualRightPx / density -
            firstRow.first().visualLeftPx / density, 0.02f)
        val secondFirst = firstRow[1]
        val visibleGapDp = (secondFirst.visualLeftPx - firstRow.first().visualRightPx) / density
        assertEquals(2f, visibleGapDp, 0.02f)

        val homeRow = geometry.filter { it.rowIndex == 1 }
        assertTrue(homeRow.first().cellLeftPx > firstRow.first().cellLeftPx)
        assertEquals(
            firstRow.first().cellWidthPx / 2f,
            homeRow.first().cellLeftPx - firstRow.first().cellLeftPx,
            0.1f
        )

        // The two punctuation helpers keep usable targets; space is deliberately
        // widened and the period key narrowed to reduce rapid-typing mistakes.
        val third = geometry.filter { it.rowIndex == 2 }
        assertEquals(1.5f, third.first { it.value == "SHIFT" }.cellWidthPx /
            (density * pitchDp), 0.001f)
        assertEquals(1.5f, third.first { it.value == "BACKSPACE" }.cellWidthPx /
            (density * pitchDp), 0.001f)

        val bottom = geometry.filter { it.rowIndex == 3 }
        val comma = bottom.first { it.value == "," }
        val emoji = bottom.first { it.value == "EMOJI" }
        val period = bottom.first { it.value == "." }
        val space = bottom.first { it.value == "SPACE" }
        val enter = bottom.first { it.value == "ENTER" }
        assertEquals(pitchDp, comma.cellWidthPx / density, 0.02f)
        assertEquals(pitchDp, emoji.cellWidthPx / density, 0.02f)
        assertEquals(0.70f * pitchDp, period.cellWidthPx / density, 0.03f)
        assertEquals(4.15f * pitchDp, space.cellWidthPx / density, 0.03f)
        assertEquals(1.9f * pitchDp, enter.cellWidthPx / density, 0.03f)
    }

    @Test
    fun geometryStaysInsideTheKeyboardForNarrowAndWideViews() {
        for (widthDp in listOf(320f, 360f, 393f, 430f, 600f)) {
            val density = 2f
            val frames = KeyboardLayoutPolicy.createKeyFrames(
                widthPx = (widthDp * density).toInt(),
                heightPx = (248f * density).toInt(),
                density = density,
                isSymbols = false
            )
            assertEquals(34, frames.size)
            assertTrue(frames.all { it.visualLeftPx >= 0f })
            assertTrue(frames.all { it.visualRightPx <= widthDp * density + 0.1f })
            assertTrue(frames.all { it.visualBottomPx <= 248f * density + 0.1f })
            assertTrue(frames.all { it.touchLeftPx >= 0f })
            assertTrue(frames.all { it.touchRightPx <= widthDp * density + 0.1f })
            val firstRow = frames.filter { it.rowIndex == 0 }.sortedBy { it.visualLeftPx }
            assertTrue(firstRow.zipWithNext().all { (a, b) -> a.visualRightPx < b.visualLeftPx })
            assertTrue(firstRow.sumOf { it.cellWidthPx.toDouble() } / density <= 420.1)
        }
    }

    @Test
    fun allThreeSymbolPagesHaveFourRowsAndStayWithinTenGridUnits() {
        for (page in 0..2) {
            val rows = KeyboardLayoutPolicy.rows(page)
            assertEquals(4, rows.size)
            assertEquals(10, rows[0].size)
            assertEquals(9, rows[1].size)
            assertTrue(rows[2].any { it.value == "SYM_NEXT" })
            assertTrue(rows[2].any { it.value == "BACKSPACE" })
            assertTrue(rows[3].any { it.value == "SPACE" })
            rows.forEach { row ->
                val units = row.sumOf { it.units.toDouble() }
                assertTrue("Page $page row uses $units units", units <= 10.0001)
            }
        }
        assertTrue(KeyboardLayoutPolicy.rows(0)[1].any { it.value == "@" })
        assertTrue(KeyboardLayoutPolicy.rows(1)[1].any { it.value == "₹" })
        assertTrue(KeyboardLayoutPolicy.rows(1)[1].any { it.value == "_" })
        assertTrue(KeyboardLayoutPolicy.rows(1)[2].any { it.value == "\"" })
        assertTrue(KeyboardLayoutPolicy.rows(1)[2].any { it.value == "'" })
        assertTrue(KeyboardLayoutPolicy.rows(2)[0].any { it.value == "π" })
    }

    @Test
    fun symbolPageGeometryFitsTheSameKeyboardViewport() {
        for (page in 0..2) {
            val frames = KeyboardLayoutPolicy.createKeyFrames(
                widthPx = 1272,
                heightPx = (248f * 3.5f).toInt(),
                density = 3.5f,
                symbolPage = page
            )
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.all { it.visualLeftPx >= 0f && it.visualRightPx <= 1272f })
            assertTrue(frames.all { it.visualTopPx >= 0f && it.visualBottomPx <= 248f * 3.5f })
            assertTrue(frames.any { it.value == "SYM_NEXT" })
        }
    }
}
