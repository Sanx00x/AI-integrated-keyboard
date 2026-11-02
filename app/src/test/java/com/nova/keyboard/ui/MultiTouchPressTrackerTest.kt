package com.nova.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTouchPressTrackerTest {
    @Test
    fun overlappingTwoFingerTapsReleaseBothKeysIndependently() {
        val tracker = MultiTouchPressTracker<String>(maxDriftPx = 30f)

        tracker.down(pointerId = 4, key = "h", x = 10f, y = 10f)
        tracker.down(pointerId = 9, key = "i", x = 40f, y = 10f)

        assertEquals(2, tracker.activeCount)
        assertEquals("i", tracker.release(9, 40f, 10f)?.key)
        assertEquals(1, tracker.activeCount)
        assertEquals("h", tracker.release(4, 10f, 10f)?.key)
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun firstPointerMayReleaseBeforeSecondPointer() {
        val tracker = MultiTouchPressTracker<String>(maxDriftPx = 30f)

        tracker.down(pointerId = 2, key = "h", x = 12f, y = 10f)
        tracker.down(pointerId = 7, key = "i", x = 48f, y = 10f)

        assertEquals("h", tracker.release(2, 12f, 10f)?.key)
        assertEquals(1, tracker.activeCount)
        assertEquals("i", tracker.release(7, 48f, 10f)?.key)
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun movementCancelsOnlyThePointerThatMoved() {
        val tracker = MultiTouchPressTracker<String>(maxDriftPx = 30f)

        tracker.down(pointerId = 1, key = "a", x = 10f, y = 10f)
        tracker.down(pointerId = 2, key = "s", x = 50f, y = 10f)

        assertTrue(tracker.move(1, 50f, 10f))
        assertFalse(tracker.move(2, 52f, 11f))

        val cancelled = tracker.release(1, 50f, 10f)
        val accepted = tracker.release(2, 52f, 11f)

        assertNotNull(cancelled)
        assertTrue(cancelled!!.cancelled)
        assertNull(accepted?.takeIf { it.cancelled })
        assertEquals("s", accepted?.key)
    }

    @Test
    fun cancelAllPreventsAnyPendingPointerFromCommitting() {
        val tracker = MultiTouchPressTracker<String>(maxDriftPx = 30f)
        tracker.down(pointerId = 1, key = "x", x = 0f, y = 0f)
        tracker.down(pointerId = 2, key = "c", x = 20f, y = 0f)

        tracker.cancelAll()

        assertEquals(0, tracker.activeCount)
        assertNull(tracker.release(1, 0f, 0f))
        assertNull(tracker.release(2, 20f, 0f))
    }
    @Test
    fun latestPointerAndPressDoNotNeedAnAllocatedPair() {
        val tracker = MultiTouchPressTracker<String>(maxDriftPx = 30f)
        tracker.down(pointerId = 3, key = "a", x = 0f, y = 0f)
        tracker.down(pointerId = 8, key = "s", x = 20f, y = 0f)

        assertEquals(8, tracker.latestPointerId())
        assertEquals("s", tracker.latestPress()?.key)

        var visited = 0
        tracker.forEachActive { _, _ -> visited++ }
        assertEquals(2, visited)
    }

}
