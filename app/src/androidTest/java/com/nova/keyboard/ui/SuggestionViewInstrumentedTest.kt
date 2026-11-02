package com.nova.keyboard.ui

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.keyboard.prediction.SuggestionSource
import com.nova.keyboard.prediction.WordSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuggestionViewInstrumentedTest {
    @Test
    fun suggestionsAreVisibleAndSelectableInTheTopStrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = mutableListOf<String>()
        val row = SuggestionView(context) { selected.add(it) }
        val density = context.resources.displayMetrics.density
        val width = (360f * density).toInt()
        val height = (48f * density).toInt()

        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        row.layout(0, 0, width, height)
        row.showSuggestions(
            listOf(
                WordSuggestion("college", 1f, SuggestionSource.CONTEXT),
                WordSuggestion("gym", 0.9f, SuggestionSource.CONTEXT),
                WordSuggestion("veetuku", 0.8f, SuggestionSource.CONTEXT)
            )
        )

        assertEquals(View.VISIBLE, row.visibility)
        assertTrue("Suggestion row must retain the calibrated minimum height", row.minimumHeight >= height)
        assertTrue((row.getChildAt(0) as TextView).isEnabled)
        assertEquals("college", (row.getChildAt(0) as TextView).text.toString())
        assertEquals("gym", (row.getChildAt(2) as TextView).text.toString())
        assertEquals("veetuku", (row.getChildAt(4) as TextView).text.toString())

        row.getChildAt(0).performClick()
        assertEquals(listOf("college"), selected)
    }

    @Test
    fun emptyPredictionStateKeepsTheStripVisiblyIdentifiable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val row = SuggestionView(context) { }
        row.showSuggestions(emptyList())
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Type to predict", (row.getChildAt(2) as TextView).text.toString())
    }
}
