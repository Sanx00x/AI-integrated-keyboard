package com.nova.keyboard.ui


/**
 * Tracks independent, tap-stable key presses by Android pointer ID.
 *
 * A second finger never cancels the first finger's pending key. Movement beyond
 * [maxDriftPx] cancels only the affected pointer, which keeps accidental drags
 * from committing while allowing fast overlapping two-finger taps.
 */
internal class MultiTouchPressTracker<K>(
    private val maxDriftPx: Float
) {
    internal data class Press<K>(
        val key: K?,
        val downX: Float,
        val downY: Float,
        var cancelled: Boolean = false
    )

    private val presses = LinkedHashMap<Int, Press<K>>()

    val activeCount: Int
        get() = presses.size

    fun down(pointerId: Int, key: K?, x: Float, y: Float) {
        presses[pointerId] = Press(key, x, y)
    }

    fun move(pointerId: Int, x: Float, y: Float): Boolean {
        val press = presses[pointerId] ?: return false
        if (press.cancelled) return false
        val dx = x - press.downX
        val dy = y - press.downY
        if (dx * dx + dy * dy > maxDriftPx * maxDriftPx) {
            press.cancelled = true
            return true
        }
        return false
    }

    fun cancel(pointerId: Int): Press<K>? {
        val press = presses[pointerId] ?: return null
        press.cancelled = true
        return press
    }

    fun release(pointerId: Int, x: Float, y: Float): Press<K>? {
        val press = presses.remove(pointerId) ?: return null
        val dx = x - press.downX
        val dy = y - press.downY
        if (dx * dx + dy * dy > maxDriftPx * maxDriftPx) {
            press.cancelled = true
        }
        return press
    }

    fun get(pointerId: Int): Press<K>? = presses[pointerId]

    /** Latest pointer ID without creating a Pair for the common one/two-pointer case. */
    fun latestPointerId(): Int? = presses.keys.lastOrNull()

    /** Latest press without allocating a Pair. */
    fun latestPress(): Press<K>? = presses.values.lastOrNull()

    /** Allocation-light iteration. Callers must not structurally mutate this tracker. */
    fun forEachActive(action: (Int, Press<K>) -> Unit) {
        for ((pointerId, press) in presses) action(pointerId, press)
    }

    /** Retained for tests and diagnostics; avoid this snapshot in touch hot paths. */
    fun active(): List<Pair<Int, Press<K>>> = presses.map { it.key to it.value }

    fun cancelAll() {
        presses.values.forEach { it.cancelled = true }
        presses.clear()
    }
}
