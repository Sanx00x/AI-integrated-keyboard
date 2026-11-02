package com.nova.keyboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyPreviewViewInstrumentedTest {

    @Test
    fun firstRowPopupIsDrawnAboveTheSuggestionStrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val width = (360f * density).toInt()
        val height = (296f * density).toInt()
        val stripHeight = (48f * density).toInt()
        val sampleX = (24f * density).toInt()
        val sampleY = (15.2f * density).toInt()

        lateinit var root: FrameLayout
        lateinit var suggestions: SuggestionView
        lateinit var preview: KeyPreviewView
        var backgroundPixel = Color.TRANSPARENT

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
            suggestions = SuggestionView(context) { }
            preview = KeyPreviewView(context)

            root.addView(
                suggestions,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    stripHeight,
                    android.view.Gravity.TOP
                )
            )
            root.addView(
                preview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    height
                )
            )

            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)

            assertTrue(
                "Key popup must have higher Z than the suggestion strip",
                preview.elevation > suggestions.elevation
            )

            // Capture the actual Liquid Glass suggestion-strip sample before
            // the popup is shown. Its color is intentionally not hard-coded:
            // translucent material colors can change with the system compositor.
            val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(background))
            backgroundPixel = background.getPixel(sampleX, sampleY)
            background.recycle()

            // With the calibrated 248dp Nord 5 body, the keyboard is bottom
            // anchored below the 48dp suggestion strip. The first-row keycap
            // begins at 57.5dp in overlay coordinates. The popup must rise into
            // the strip without being clipped or drawn underneath it.
            preview.showKey(
                "Q",
                RectF(
                    4.5f * density,
                    57.5f * density,
                    37.8f * density,
                    107.25f * density
                )
            )
            assertEquals(View.VISIBLE, preview.visibility)
        }

        // Let the Choreographer-driven popup timeline finish while the UI thread remains free.
        Thread.sleep(180L)

        var popupPixel = Color.TRANSPARENT
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            popupPixel = bitmap.getPixel(sampleX, sampleY)
            bitmap.recycle()
        }

        fun brightness(color: Int): Int =
            (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

        assertEquals("Popup sample must be composited over an opaque strip", 255, Color.alpha(popupPixel))
        assertTrue(
            "The top-row popup must paint above the darker suggestion-strip glass: " +
                "popup=$popupPixel background=$backgroundPixel",
            brightness(popupPixel) >= brightness(backgroundPixel) + 80
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            preview.showKey(null, null)
            assertEquals("Overlay stays in the hierarchy during its short fade-out", View.VISIBLE, preview.visibility)
        }
        Thread.sleep(130L)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals("Overlay remains visible but empty after its fade-out", View.VISIBLE, preview.visibility)
            val afterHide = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(afterHide))
            val afterHidePixel = afterHide.getPixel(sampleX, sampleY)
            afterHide.recycle()
            assertEquals(
                "The popup must leave no stale pixels after the fade-out",
                backgroundPixel,
                afterHidePixel
            )
        }
    }
}

