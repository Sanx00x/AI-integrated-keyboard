package com.nova.keyboard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSwitchIntentPolicyTest {
    private val density = 3.5f
    private val bounds = ModeSwitchIntentPolicy.createActivationBounds(
        cellLeftPx = 12.25f * density,
        cellTopPx = 188.75f * density,
        cellRightPx = (12.25f + 44.55f) * density,
        cellBottomPx = (188.75f + 49.75f) * density,
        density = density
    )

    @Test
    fun layoutChangingControlsAreExplicitlyGuarded() {
        assertTrue(ModeSwitchIntentPolicy.requiresGuard("123"))
        assertTrue(ModeSwitchIntentPolicy.requiresGuard("ABC"))
        assertTrue(ModeSwitchIntentPolicy.requiresGuard("SYM_NEXT"))
        assertFalse(ModeSwitchIntentPolicy.requiresGuard("SPACE"))
        assertFalse(ModeSwitchIntentPolicy.requiresGuard("q"))
    }

    @Test
    fun centreTapIsAcceptedButVisibleCellEdgeIsNot() {
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f
        assertTrue(ModeSwitchIntentPolicy.contains(bounds, centerX, centerY, density))
        assertFalse(ModeSwitchIntentPolicy.contains(
            bounds,
            bounds.left - 1f,
            centerY,
            density
        ))
    }

    @Test
    fun contactEllipseMakesEdgeGateSlightlyMoreConservative() {
        val centerY = (bounds.top + bounds.bottom) / 2f
        val point = bounds.left + 1.0f // 1px inside the static core edge
        assertTrue(ModeSwitchIntentPolicy.contains(bounds, point, centerY, density))
        assertFalse(ModeSwitchIntentPolicy.contains(
            bounds,
            point,
            centerY,
            density,
            touchMajorPx = 48f,
            touchMinorPx = 30f
        ))
        // A missing or invalid contact ellipse falls back to the static gate.
        assertTrue(ModeSwitchIntentPolicy.contains(
            bounds,
            point,
            centerY,
            density,
            touchMajorPx = 0f,
            touchMinorPx = 0f
        ))
    }

    @Test
    fun modeSwitchRequiresStableReleaseInsideTheCore() {
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f
        assertTrue(ModeSwitchIntentPolicy.acceptsRelease(
            bounds, centerX, centerY, centerX + 10f, centerY, density
        ))
        assertFalse(ModeSwitchIntentPolicy.acceptsRelease(
            bounds, centerX, centerY, centerX + 15f * density, centerY, density
        ))
        assertFalse(ModeSwitchIntentPolicy.acceptsRelease(
            bounds, centerX, centerY, bounds.left - 1f, centerY, density
        ))
    }

    @Test
    fun activationCoreRetainsMostOfTheAlreadySmallModeKey() {
        val ratio = ModeSwitchIntentPolicy.activationAreaRatio(
            bounds,
            originalWidthPx = 44.55f * density,
            originalHeightPx = 49.75f * density
        )
        // Fixed 2dp horizontal and 3dp vertical guard leaves about 80% of
        // the original cell active, before optional contact inset.
        assertEquals(0.800f, ratio, 0.015f)
    }
}
