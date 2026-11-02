package com.nova.keyboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.nova.keyboard.prediction.SpatialTouchRanking

@RunWith(AndroidJUnit4::class)
class KeyboardViewInstrumentedTest {

    // Keep onKey last so existing trailing-lambda calls bind to the key callback.
    private fun createAndDrawKeyboard(
        onPreview: (String?, RectF?) -> Unit = { _, _ -> },
        onSpatial: (SpatialTouchRanking, String?) -> Unit = { _, _ -> },
        onKey: (String) -> Unit
    ): KeyboardView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val width = (360f * density).toInt()
        val height = (KeyboardLayoutPolicy.PORTRAIT_BODY_HEIGHT_DP * density).toInt()
        val view = KeyboardView(context, onKey, onPreview, onSpatial)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
        return view
    }

    private fun sendTouch(view: KeyboardView, action: Int, xDp: Float, yDp: Float) {
        val density = view.resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now - 10L,
            now,
            action,
            xDp * density,
            yDp * density,
            0
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun sendTwoPointerTouch(
        view: KeyboardView,
        action: Int,
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float
    ) {
        val density = view.resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        val properties = Array(2) { MotionEvent.PointerProperties() }
        val coordinates = Array(2) { MotionEvent.PointerCoords() }
        for (index in 0..1) {
            properties[index].id = index
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        coordinates[0].x = firstX * density
        coordinates[0].y = firstY * density
        coordinates[0].pressure = 1f
        coordinates[0].size = 1f
        coordinates[1].x = secondX * density
        coordinates[1].y = secondY * density
        coordinates[1].pressure = 1f
        coordinates[1].size = 1f
        val event = MotionEvent.obtain(
            now - 10L, now, action, 2, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun overlappingTwoFingerLetterTapsCommitBothKeys() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                now - 10L, now, MotionEvent.ACTION_DOWN,
                22.5f * density, 29.5f * density, 0
            )
            try { view.onTouchEvent(down) } finally { down.recycle() }

            val pointerDownAction = MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            sendTwoPointerTouch(
                view, pointerDownAction,
                22.5f, 29.5f, 58.5f, 29.5f
            )
            val pointerUpSecondAction = MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            sendTwoPointerTouch(
                view, pointerUpSecondAction,
                22.5f, 29.5f, 58.5f, 29.5f
            )
            sendTouch(view, MotionEvent.ACTION_UP, 22.5f, 29.5f)
        }
        assertEquals("The second key must commit without cancelling the first", listOf("w", "q"), typed)
    }


    @Test
    fun overlappingTwoFingerLetterTapsAlsoWorkWhenFirstFingerLiftsFirst() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                now - 10L, now, MotionEvent.ACTION_DOWN,
                22.5f * density, 29.5f * density, 0
            )
            try { view.onTouchEvent(down) } finally { down.recycle() }

            val pointerDownAction = MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            sendTwoPointerTouch(
                view, pointerDownAction,
                22.5f, 29.5f, 58.5f, 29.5f
            )
            // Lift pointer ID 0 first. ID 1 then receives the final ACTION_UP.
            sendTwoPointerTouch(
                view, MotionEvent.ACTION_POINTER_UP,
                22.5f, 29.5f, 58.5f, 29.5f
            )
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                x = 58.5f * density
                y = 29.5f * density
                pressure = 1f
                size = 1f
            })
            val up = MotionEvent.obtain(
                now - 10L, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try { view.onTouchEvent(up) } finally { up.recycle() }
        }
        assertEquals("Both keys must commit when the first pointer lifts first", listOf("q", "w"), typed)
    }

    @Test
    fun rapidSequentialLetterTapsCommitEveryKey() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            // Two separate taps in a single main-thread turn model a fast
            // alternating-finger typing burst without inserting artificial delays.
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 31f)
            sendTouch(view, MotionEvent.ACTION_UP, 22.5f, 31f)
            sendTouch(view, MotionEvent.ACTION_DOWN, 57.5f, 31f)
            sendTouch(view, MotionEvent.ACTION_UP, 57.5f, 31f)
        }
        assertEquals("Fast sequential taps must not be debounced or dropped", listOf("q", "w"), typed)
    }

    @Test
    fun fastTopRowBurstCommitsAllTenLettersInOrder() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val topRow = KeyboardLayoutPolicy.createKeyFrames(
                widthPx = view.width,
                heightPx = view.height,
                density = density,
                symbolPage = -1
            ).filter { it.rowIndex == 0 }.sortedBy { it.centerX }

            // Send ten distinct taps in one UI-thread turn, with no artificial
            // delay between presses. Every down/up pair must commit exactly once.
            for (frame in topRow) {
                val x = frame.centerX / density
                val y = frame.centerY / density
                sendTouch(view, MotionEvent.ACTION_DOWN, x, y)
                sendTouch(view, MotionEvent.ACTION_UP, x, y)
            }
        }
        assertEquals("Rapid top-row typing must preserve every key and its order", "qwertyuiop", typed.joinToString(""))
    }

    @Test
    fun letterBoundaryChoosesTheNearestPhysicalKeyCenter() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            // The calibrated grid has 3.5dp side gutters. At this 360dp
            // fixture width the Q/W centres are 21.15dp and 56.45dp,
            // so the physical midpoint is 38.8dp (not the old 40dp).
            // 41dp is safely on the W side of the actual midpoint.
            sendTouch(view, MotionEvent.ACTION_DOWN, 41f, 31f)
            sendTouch(view, MotionEvent.ACTION_UP, 41f, 31f)
        }
        assertEquals("A touch just across the Q/W boundary must select W", listOf("w"), typed)
    }

    @Test
    fun punctuationTargetsRemainSelectableWithNarrowerPeriodKey() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            // Comma retains a full pitch; the narrower period cell remains tappable.
            sendTouch(view, MotionEvent.ACTION_DOWN, 66.25f, 199f)
            sendTouch(view, MotionEvent.ACTION_UP, 66.25f, 199f)
            sendTouch(view, MotionEvent.ACTION_DOWN, 271f, 199f)
            sendTouch(view, MotionEvent.ACTION_UP, 271f, 199f)
        }
        assertEquals(listOf(",", "."), typed)
    }


    @Test
    fun spacebarEdgeTapDoesNotBecomePeriodDuringFastTyping() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val bottom = KeyboardLayoutPolicy.createKeyFrames(
                widthPx = view.width,
                heightPx = view.height,
                density = density,
                symbolPage = -1
            ).filter { it.rowIndex == 3 }
            val space = bottom.first { it.value == "SPACE" }
            // This is still inside SPACE, very near its right edge where the
            // narrow period key's centre used to win nearest-centre selection.
            val xDp = space.cellRightPx / density - 1f
            val yDp = (space.visualTopPx + space.visualBottomPx) / (2f * density)
            sendTouch(view, MotionEvent.ACTION_DOWN, xDp, yDp)
            sendTouch(view, MotionEvent.ACTION_UP, xDp, yDp)
        }
        assertEquals("A touch inside the SPACE cell must commit a space", listOf("<SPACE>"), typed)
    }

    @Test
    fun shiftCyclesOneShotToCapsLockAndBackOff() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            fun tap(x: Float, y: Float) {
                sendTouch(view, MotionEvent.ACTION_DOWN, x, y)
                sendTouch(view, MotionEvent.ACTION_UP, x, y)
            }
            // First tap: capitalize one letter only.
            tap(27f, 140.5f)
            tap(22.5f, 29.5f) // Q
            tap(22.5f, 29.5f) // q

            // Second Shift tap turns Caps Lock on; third tap turns it off.
            tap(27f, 140.5f)
            tap(27f, 140.5f)
            tap(22.5f, 29.5f) // Q
            tap(57.5f, 29.5f) // W
            tap(27f, 140.5f)
            tap(22.5f, 29.5f) // q
        }
        assertEquals(listOf("Q", "q", "Q", "W", "q"), typed)
    }

    @Test
    fun oneShotShiftIsNotConsumedBySpace() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val bottom = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            ).filter { it.rowIndex == 3 }
            val space = bottom.first { it.value == "SPACE" }
            fun tap(x: Float, y: Float) {
                sendTouch(view, MotionEvent.ACTION_DOWN, x, y)
                sendTouch(view, MotionEvent.ACTION_UP, x, y)
            }
            tap(27f, 140.5f)
            tap(space.centerX / density, space.centerY / density)
            tap(22.5f, 29.5f)
        }
        assertEquals(listOf("<SPACE>", "Q"), typed)
    }


    @Test
    fun accidentalEdgeTapOnLayoutSwitchDoesNotLeaveAlphabeticLayout() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val switchKey = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            ).first { it.value == "123" }
            // The point is still inside the visible ?123 cell, but outside its
            // central intent zone. A fast thumb graze must not change pages.
            val edgeX = (switchKey.cellLeftPx / density) + 1.5f
            val centerY = switchKey.centerY / density
            sendTouch(view, MotionEvent.ACTION_DOWN, edgeX, centerY)
            sendTouch(view, MotionEvent.ACTION_UP, edgeX, centerY)

            val q = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            ).first { it.value == "q" }
            sendTouch(view, MotionEvent.ACTION_DOWN, q.centerX / density, q.centerY / density)
            sendTouch(view, MotionEvent.ACTION_UP, q.centerX / density, q.centerY / density)
        }
        assertEquals("An edge graze must not switch to symbols", listOf("q"), typed)
    }

    @Test
    fun modeSwitchTapMustReleaseInsideItsSafeAreaAndWithinShortDrift() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            val switchKey = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            ).first { it.value == "123" }
            val centerX = switchKey.centerX / density
            val centerY = switchKey.centerY / density

            // 15dp movement is below the general 36dp letter-key limit, but
            // exceeds the stricter 14dp layout-control tap limit.
            sendTouch(view, MotionEvent.ACTION_DOWN, centerX, centerY)
            sendTouch(view, MotionEvent.ACTION_MOVE, centerX + 15f, centerY)
            sendTouch(view, MotionEvent.ACTION_UP, centerX + 15f, centerY)

            val q = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            ).first { it.value == "q" }
            sendTouch(view, MotionEvent.ACTION_DOWN, q.centerX / density, q.centerY / density)
            sendTouch(view, MotionEvent.ACTION_UP, q.centerX / density, q.centerY / density)
        }
        assertEquals("A drifting mode-switch gesture must be cancelled", listOf("q"), typed)
    }

    @Test
    fun accidentalSymbolNextEdgeTapDoesNotCycleTheSymbolPage() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            fun tap(frame: KeyboardLayoutPolicy.KeyFrame) {
                sendTouch(view, MotionEvent.ACTION_DOWN, frame.centerX / density, frame.centerY / density)
                sendTouch(view, MotionEvent.ACTION_UP, frame.centerX / density, frame.centerY / density)
            }
            val alphaFrames = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = -1
            )
            tap(alphaFrames.first { it.value == "123" })

            val page0 = KeyboardLayoutPolicy.createKeyFrames(
                view.width, view.height, density, symbolPage = 0
            )
            val next = page0.first { it.value == "SYM_NEXT" }
            val edgeX = next.cellLeftPx / density + 1.5f
            val y = next.centerY / density
            sendTouch(view, MotionEvent.ACTION_DOWN, edgeX, y)
            sendTouch(view, MotionEvent.ACTION_UP, edgeX, y)

            // If SYM_NEXT had fired, the @ key would no longer exist at this
            // position. The current page-0 @ key must remain selectable.
            tap(page0.first { it.value == "@" })
        }
        assertEquals(listOf("@"), typed)
    }

    @Test
    fun firstTapWorksEvenBeforeTheFirstDrawPass() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = context.resources.displayMetrics.density
            val width = (360f * density).toInt()
            val height = (KeyboardLayoutPolicy.PORTRAIT_BODY_HEIGHT_DP * density).toInt()
            view = KeyboardView(context, { typed.add(it) })
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            // Deliberately do not call draw(); the first touch builds cached geometry.
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 31f)
            sendTouch(view, MotionEvent.ACTION_UP, 22.5f, 31f)
        }
        assertEquals(listOf("q"), typed)
    }

    @Test
    fun shortBackspaceTapDeletesExactlyOnce() {
        val count = AtomicInteger(0)
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { key ->
                if (key == "<BACKSPACE>") count.incrementAndGet()
            }
            sendTouch(view, MotionEvent.ACTION_DOWN, 326f, 140.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 326f, 140.5f)
        }
        assertEquals(1, count.get())
    }

    @Test
    fun longPressBackspaceRepeatsAndStopsOnRelease() {
        val count = AtomicInteger(0)
        val twoDeletes = CountDownLatch(2)
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { key ->
                if (key == "<BACKSPACE>") {
                    count.incrementAndGet()
                    twoDeletes.countDown()
                }
            }
            sendTouch(view, MotionEvent.ACTION_DOWN, 326f, 140.5f)
        }
        assertTrue("Holding Backspace should trigger repeat deletion", twoDeletes.await(2, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sendTouch(view, MotionEvent.ACTION_UP, 326f, 140.5f)
        }
        val countAtRelease = count.get()
        Thread.sleep(180)
        assertEquals("Backspace repeat must stop immediately on release", countAtRelease, count.get())
    }

    @Test
    fun emojiKeyOpensEmojiPanelCallback() {
        val tapped = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { key -> tapped.add(key) }
            sendTouch(view, MotionEvent.ACTION_DOWN, 87.25f, 195.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 87.25f, 195.5f)
        }
        assertEquals(listOf("<EMOJI>"), tapped)
    }

    @Test
    fun keyRemainsLatchedToInitialTouchDuringFingerDrift() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { key -> typed.add(key) }
            // Start on Q, drift across the Q/W boundary, and release over W.
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_MOVE, 48f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 48f, 29.5f)
        }
        assertEquals("A small/medium drift must not change the initially selected key", listOf("q"), typed)
    }

    @Test
    fun letterPopupAppearsOnDownAndDismissesOnUp() {
        val previewEvents = mutableListOf<String?>()
        val previewBounds = mutableListOf<RectF?>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard(
                onKey = {},
                onPreview = { label, bounds ->
                    previewEvents.add(label)
                    previewBounds.add(bounds?.let(::RectF))
                }
            )
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 22.5f, 29.5f)
        }
        assertEquals("q", previewEvents.first())
        assertTrue("Popup must receive key geometry", previewBounds.first() != null)
        assertEquals(null, previewEvents.last())
        assertTrue(previewBounds.first()!!.width() > 0f)
    }

    @Test
    fun shiftedLetterPopupUsesUppercasePreviewAndOutput() {
        val typed = mutableListOf<String>()
        val previews = mutableListOf<String?>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard(
                onKey = { typed.add(it) },
                onPreview = { label, _ -> if (label != null) previews.add(label) }
            )
            // Shift is on the left side of the third row.
            sendTouch(view, MotionEvent.ACTION_DOWN, 27f, 140.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 27f, 140.5f)
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 22.5f, 29.5f)
        }
        assertEquals(listOf("Q"), typed)
        assertEquals(listOf("Q"), previews)
    }

    @Test
    fun farDragCancelsTapInsteadOfTypingUnexpectedLetter() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            sendTouch(view, MotionEvent.ACTION_DOWN, 22.5f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_MOVE, 120f, 29.5f)
            sendTouch(view, MotionEvent.ACTION_UP, 120f, 29.5f)
        }
        assertTrue("A large drag should cancel a tap, not type an accidental key", typed.isEmpty())
    }
    @Test
    fun spatialShadowKeepsAlternativesWithoutChangingCommittedTap() {
        val typed = mutableListOf<String>()
        var ranking: SpatialTouchRanking? = null
        var legacyWinner: String? = null
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard(
                onKey = { typed.add(it) },
                onSpatial = { result, hardWinner ->
                    ranking = result
                    legacyWinner = hardWinner
                }
            )
            // Near the Q/W edge: Phase A still commits its hard winner, while
            // the shadow model should retain both plausible alternatives.
            // Derive the true midpoint from the same geometry policy as the
            // keyboard. The old fixed 39dp sample was 0.2dp onto W's side for
            // this 360dp test fixture, so W was correctly selected.
            val density = view.resources.displayMetrics.density
            val firstRow = KeyboardLayoutPolicy.createKeyFrames(
                widthPx = view.width,
                heightPx = view.height,
                density = density,
                isSymbols = false
            ).filter { it.rowIndex == 0 }.sortedBy { it.centerX }
            val qCenterDp = firstRow.first { it.value == "q" }.centerX / density
            val wCenterDp = firstRow.first { it.value == "w" }.centerX / density
            val qSideOfBoundaryDp = (qCenterDp + wCenterDp) / 2f - 0.5f
            sendTouch(view, MotionEvent.ACTION_DOWN, qSideOfBoundaryDp, 29.5f)
            sendTouch(view, MotionEvent.ACTION_UP, qSideOfBoundaryDp, 29.5f)
        }
        assertEquals(listOf("q"), typed)
        assertEquals("q", legacyWinner)
        assertTrue(ranking != null)
        val candidates = ranking!!.candidates.map { it.key }
        assertTrue("Spatial model should retain the initially selected key", candidates.contains("q"))
        assertTrue("Spatial model should retain a nearby alternative", candidates.contains("w"))
    }


    @Test
    fun threeSymbolPagesCommitSymbolsAndReturnToAlphabeticLayout() {
        val typed = mutableListOf<String>()
        lateinit var view: KeyboardView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = createAndDrawKeyboard { typed.add(it) }
            val density = view.resources.displayMetrics.density
            fun tapAt(xDp: Float, yDp: Float) {
                sendTouch(view, MotionEvent.ACTION_DOWN, xDp, yDp)
                sendTouch(view, MotionEvent.ACTION_UP, xDp, yDp)
            }
            fun tapKey(value: String, page: Int) {
                val frames = KeyboardLayoutPolicy.createKeyFrames(
                    view.width,
                    view.height,
                    density,
                    symbolPage = page
                )
                val frame = frames.first { it.value == value }
                tapAt(frame.centerX / density, frame.centerY / density)
            }

            // Enter page 0, emit a common symbol, then exercise each extended page.
            tapKey("123", -1)
            tapKey("@", 0)
            tapKey("SYM_NEXT", 0)
            tapKey("₹", 1)
            tapKey("SYM_NEXT", 1)
            tapKey("π", 2)
            tapKey("SYM_NEXT", 2)
            tapKey("ABC", 0)

            // Confirm the alphabetic grid was restored.
            tapKey("q", -1)
        }
        assertEquals(listOf("@", "₹", "π", "q"), typed)
    }
}
