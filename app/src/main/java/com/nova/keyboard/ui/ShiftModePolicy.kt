package com.nova.keyboard.ui

/**
 * Classic mobile-keyboard Shift behavior:
 * OFF -> one-shot uppercase -> Caps Lock -> OFF.
 * One-shot Shift is consumed only by the next alphabetic character.
 */
internal enum class ShiftMode {
    OFF,
    ONE_SHOT,
    CAPS_LOCK
}

internal object ShiftModePolicy {
    fun nextAfterTap(current: ShiftMode): ShiftMode = when (current) {
        ShiftMode.OFF -> ShiftMode.ONE_SHOT
        ShiftMode.ONE_SHOT -> ShiftMode.CAPS_LOCK
        ShiftMode.CAPS_LOCK -> ShiftMode.OFF
    }

    fun outputForKey(key: String, mode: ShiftMode): String =
        if (mode != ShiftMode.OFF && isSingleLetter(key)) key.uppercase() else key

    fun afterKeyCommitted(mode: ShiftMode, key: String): ShiftMode =
        if (mode == ShiftMode.ONE_SHOT && isSingleLetter(key)) ShiftMode.OFF else mode

    private fun isSingleLetter(key: String): Boolean =
        key.length == 1 && key[0].isLetter()
}
