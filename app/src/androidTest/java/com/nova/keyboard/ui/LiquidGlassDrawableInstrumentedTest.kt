package com.nova.keyboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidGlassDrawableInstrumentedTest {
    @Test
    fun drawableRendersTranslucentTintAndVisibleRim() {
        val bitmap = Bitmap.createBitmap(240, 72, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(70, 110, 150))
        val drawable = LiquidGlassDrawable(
            density = 1f,
            cornerRadiusPx = 14f,
            insetPx = 1f
        )
        drawable.bounds = Rect(0, 0, 240, 72)
        drawable.draw(canvas)

        val center = bitmap.getPixel(120, 36)
        val topRim = bitmap.getPixel(120, 1)
        assertTrue("glass tint must composite over its backdrop", center != Color.rgb(70, 110, 150))
        assertTrue("surface must remain visible and not fully transparent", Color.alpha(center) > 0)
        assertTrue("specular rim must alter backdrop pixels", topRim != Color.rgb(70, 110, 150))
        bitmap.recycle()
    }
}
