package com.nova.keyboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Lightweight, offline emoji picker rendered with native TextViews so each
 * category and emoji remains individually accessible to TalkBack.
 * Only recently selected emoji are persisted; message text is never stored.
 */
class EmojiPickerView(
    context: Context,
    private val onEmojiSelected: (String) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {

    private data class Category(
        val id: String,
        val icon: String,
        val description: String,
        val emojis: List<String> = emptyList()
    )

    companion object {
        private const val PREFERENCES = "nova_emoji_preferences"
        private const val RECENTS_KEY = "recent_emojis"
        private const val MAX_RECENTS = 24
        private const val COLUMN_COUNT = 8

        private val CATEGORIES = listOf(
            Category("smileys", "😀", "Smileys and emotions", listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
                "🥲", "😊", "😇", "🙂", "🙃", "😉", "😍", "🥰",
                "😘", "😋", "😎", "🤔", "🤨", "😐", "😴", "🥱",
                "😭", "🥺", "😤", "😡", "🤗", "🤭", "🫡", "🙏",
                "❤️", "🩷", "💙", "💚", "💔", "🔥", "✨", "🎉"
            )),
            Category("people", "👋", "People and gestures", listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
                "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉",
                "👆", "👇", "☝️", "👍", "👎", "✊", "👊", "👏",
                "🙌", "👐", "🤝", "🙏", "💪", "🫶", "💅", "🙋",
                "🤦", "🤷", "💃", "🕺", "🧑‍💻", "👨‍👩‍👧‍👦"
            )),
            Category("animals", "🐻", "Animals and nature", listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
                "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈",
                "🐔", "🐧", "🐦", "🦄", "🐝", "🦋", "🐢", "🐍",
                "🐬", "🐳", "🦖", "🌸", "🌹", "🌻", "🌈", "☀️",
                "🌙", "⭐", "🌍", "🍀", "🌴", "🌧️"
            )),
            Category("food", "🍕", "Food and drink", listOf(
                "🍎", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🥭",
                "🍍", "🥥", "🥑", "🍅", "🥕", "🌽", "🍞", "🥐",
                "🧀", "🍕", "🍔", "🍟", "🌭", "🥪", "🌮", "🍜",
                "🍚", "🍛", "🍱", "🍰", "🎂", "🍦", "🍩", "🍪",
                "☕", "🧋", "🥤", "🧃", "🍵", "🥛"
            )),
            Category("travel", "✈️", "Travel and places", listOf(
                "🚗", "🚕", "🚌", "🚎", "🏍️", "🚲", "🚆", "🚄",
                "✈️", "🛫", "🚀", "🚁", "🚢", "⛵", "🏠", "🏡",
                "🏢", "🏫", "🏥", "🏖️", "🏝️", "⛰️", "🌋", "⛺",
                "🌆", "🌃", "🌉", "🎡", "🎢", "🗺️", "🧭", "🛣️"
            )),
            Category("objects", "💡", "Objects and activities", listOf(
                "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "📷", "🎥",
                "🎧", "🎤", "🔋", "💡", "🔦", "📚", "📖", "✏️",
                "📝", "📌", "📎", "🔒", "🔑", "⚙️", "🧰", "🛠️",
                "⚽", "🏏", "🏆", "🎮", "🎸", "🎹", "🎬", "🎁"
            )),
            Category("symbols", "♡", "Symbols", listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
                "💯", "✅", "☑️", "❌", "⭕", "❗", "❓", "‼️",
                "⚠️", "🚫", "♻️", "♾️", "🔰", "⭐", "🌟", "✨",
                "💬", "💭", "🗨️", "🔔", "🔕", "➕", "➖", "➗"
            ))
        )
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val recents = loadRecents()
    private var selectedCategoryId = "recent"

    private val categoryButtons = linkedMapOf<String, TextView>()
    private val gridRows = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(8))
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(Color.rgb(26, 28, 32))
        contentDescription = "NOVA emoji picker"
        isFocusable = true

        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.rgb(35, 38, 43))
        }
        val categoryRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        addCategoryButton(categoryRow, "close", "⌨", "Return to keyboard")
        addCategoryButton(categoryRow, "recent", "◷", "Recently used emoji")
        CATEGORIES.forEach { category ->
            addCategoryButton(categoryRow, category.id, category.icon, category.description)
        }
        categoryScroll.addView(
            categoryRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        )
        addView(categoryScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        val gridScroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                gridRows,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rebuildGrid()
        refreshCategoryStates()
    }

    private fun addCategoryButton(
        parent: LinearLayout,
        id: String,
        label: String,
        description: String
    ) {
        val button = TextView(context).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            background = roundedBackground(Color.TRANSPARENT)
            setOnClickListener {
                if (id == "close") {
                    onClose()
                } else {
                    selectedCategoryId = id
                    refreshCategoryStates()
                    rebuildGrid()
                }
            }
        }
        categoryButtons[id] = button
        parent.addView(button, LayoutParams(dp(44), dp(40)))
    }

    private fun refreshCategoryStates() {
        categoryButtons.forEach { (id, button) ->
            button.background = roundedBackground(
                if (id == selectedCategoryId) Color.rgb(75, 82, 94) else Color.TRANSPARENT
            )
        }
    }

    private fun rebuildGrid() {
        gridRows.removeAllViews()
        val emojis = when (selectedCategoryId) {
            "recent" -> if (recents.isNotEmpty()) recents.toList() else CATEGORIES.first().emojis
            else -> CATEGORIES.firstOrNull { it.id == selectedCategoryId }?.emojis.orEmpty()
        }

        emojis.chunked(COLUMN_COUNT).forEach { rowEmojis ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowEmojis.forEach { emoji ->
                val cell = TextView(context).apply {
                    text = emoji
                    contentDescription = "Emoji $emoji"
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                    setTextColor(Color.WHITE)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        addRecent(emoji)
                        onEmojiSelected(emoji)
                    }
                }
                row.addView(cell, LayoutParams(0, dp(48), 1f))
            }
            repeat(COLUMN_COUNT - rowEmojis.size) {
                row.addView(View(context), LayoutParams(0, dp(48), 1f))
            }
            gridRows.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        }
    }

    private fun addRecent(emoji: String) {
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        preferences.edit().putString(RECENTS_KEY, recents.joinToString("|")).apply()
        if (selectedCategoryId == "recent") rebuildGrid()
    }

    private fun loadRecents(): MutableList<String> = preferences
        .getString(RECENTS_KEY, "")
        .orEmpty()
        .split('|')
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_RECENTS)
        .toMutableList()

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
