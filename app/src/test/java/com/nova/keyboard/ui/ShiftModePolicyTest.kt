package com.nova.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftModePolicyTest {
    @Test
    fun successiveShiftTapsCycleOneShotCapsLockAndOff() {
        val oneShot = ShiftModePolicy.nextAfterTap(ShiftMode.OFF)
        assertEquals(ShiftMode.ONE_SHOT, oneShot)
        val locked = ShiftModePolicy.nextAfterTap(oneShot)
        assertEquals(ShiftMode.CAPS_LOCK, locked)
        assertEquals(ShiftMode.OFF, ShiftModePolicy.nextAfterTap(locked))
    }

    @Test
    fun oneShotUppercasesOneLetterThenTurnsOff() {
        assertEquals("A", ShiftModePolicy.outputForKey("a", ShiftMode.ONE_SHOT))
        assertEquals(ShiftMode.OFF, ShiftModePolicy.afterKeyCommitted(ShiftMode.ONE_SHOT, "a"))
    }

    @Test
    fun oneShotSurvivesSpaceAndPunctuationUntilALetterIsCommitted() {
        assertEquals(" ", ShiftModePolicy.outputForKey(" ", ShiftMode.ONE_SHOT))
        assertEquals(ShiftMode.ONE_SHOT, ShiftModePolicy.afterKeyCommitted(ShiftMode.ONE_SHOT, " "))
        assertEquals(".", ShiftModePolicy.outputForKey(".", ShiftMode.ONE_SHOT))
        assertEquals(ShiftMode.ONE_SHOT, ShiftModePolicy.afterKeyCommitted(ShiftMode.ONE_SHOT, "."))
    }

    @Test
    fun capsLockUppercasesEveryLetterUntilShiftIsTappedAgain() {
        assertEquals("A", ShiftModePolicy.outputForKey("a", ShiftMode.CAPS_LOCK))
        assertEquals("B", ShiftModePolicy.outputForKey("b", ShiftMode.CAPS_LOCK))
        assertEquals(ShiftMode.CAPS_LOCK, ShiftModePolicy.afterKeyCommitted(ShiftMode.CAPS_LOCK, "a"))
        assertEquals(ShiftMode.CAPS_LOCK, ShiftModePolicy.afterKeyCommitted(ShiftMode.CAPS_LOCK, "b"))
        assertEquals(ShiftMode.OFF, ShiftModePolicy.nextAfterTap(ShiftMode.CAPS_LOCK))
        assertEquals("c", ShiftModePolicy.outputForKey("c", ShiftMode.OFF))
    }
}
