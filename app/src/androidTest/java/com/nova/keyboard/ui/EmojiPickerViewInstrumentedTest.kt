package com.nova.keyboard.ui

import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmojiPickerViewInstrumentedTest {

    @Test
    fun tappingEmojiEmitsWholeUnicodeSequence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("nova_emoji_preferences", 0)
            .edit().clear().commit()

        var selected: String? = null
        lateinit var picker: EmojiPickerView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            picker = EmojiPickerView(context, { selected = it }, {})
            val matches = ArrayList<View>()
            picker.findViewsWithText(matches, "😀", View.FIND_VIEWS_WITH_TEXT)
            val emojiCell = matches.filterIsInstance<TextView>()
                .firstOrNull { it.contentDescription == "Emoji 😀" }
            assertNotNull("Smileys category should be available in the initial recents view", emojiCell)
            emojiCell!!.performClick()
        }
        assertEquals("😀", selected)
    }

    @Test
    fun pickerExposesCategoryAndEmojiControlsToAccessibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var picker: EmojiPickerView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            picker = EmojiPickerView(context, {}, {})
            val recentButtons = ArrayList<View>()
            picker.findViewsWithText(recentButtons, "◷", View.FIND_VIEWS_WITH_TEXT)
            assertNotNull(recentButtons.firstOrNull { it.contentDescription == "Recently used emoji" })
        }
    }
}
