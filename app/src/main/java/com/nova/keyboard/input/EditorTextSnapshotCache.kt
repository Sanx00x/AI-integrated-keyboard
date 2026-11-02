package com.nova.keyboard.input

/**
 * Main-thread-owned mirror of the text immediately before the editor cursor.
 *
 * NOVA updates the snapshot after its own edits. Android selection callbacks
 * validate the expected cursor movement; unexpected cursor/selection changes
 * invalidate the mirror. Age and edit-count limits force periodic resyncs with
 * InputConnection so editor-side transformations cannot leave it stale forever.
 *
 * This class never calls InputConnection and is deliberately independent of
 * Android UI classes so its state transitions can be tested directly.
 */
internal class EditorTextSnapshotCache(
    private val maxCharacters: Int = 160,
    private val maxEditsBeforeResync: Int = 24,
    private val maxAgeNanos: Long = 2_000_000_000L
) {
    private var textBeforeCursor: String? = null
    private var seededAtNanos: Long = 0L
    private var editsSinceSeed: Int = 0

    private var hasPendingSelectionDelta: Boolean = false
    private var pendingSelectionDelta: Int = 0
    private var lastObservedCursor: Int? = null

    init {
        require(maxCharacters > 0)
        require(maxEditsBeforeResync > 0)
        require(maxAgeNanos > 0L)
    }

    /** Returns a reusable snapshot, or null when a fresh editor read is required. */
    fun getIfFresh(nowNanos: Long): String? {
        val snapshot = textBeforeCursor ?: return null
        val age = nowNanos - seededAtNanos
        if (age < 0L || age >= maxAgeNanos || editsSinceSeed >= maxEditsBeforeResync) {
            invalidate()
            return null
        }
        return snapshot
    }

    /** Replace the cache with an authoritative InputConnection snapshot. */
    fun seed(text: String, nowNanos: Long) {
        textBeforeCursor = text.takeLast(maxCharacters)
        seededAtNanos = nowNanos
        editsSinceSeed = 0
        hasPendingSelectionDelta = false
        pendingSelectionDelta = 0
        lastObservedCursor = null
    }

    /**
     * Apply a local edit made at the cursor. [deletedBeforeCursor] is measured
     * in UTF-16 code units, matching InputConnection.deleteSurroundingText().
     * Returns the new snapshot, or null when the operation cannot be mirrored.
     */
    fun applyLocalEdit(
        deletedBeforeCursor: Int,
        insertedText: String
    ): String? {
        val current = textBeforeCursor ?: return null
        if (deletedBeforeCursor < 0 || deletedBeforeCursor > current.length) {
            invalidate()
            return null
        }

        val updated = (current.dropLast(deletedBeforeCursor) + insertedText)
            .takeLast(maxCharacters)
        textBeforeCursor = updated
        editsSinceSeed++

        val delta = insertedText.length - deletedBeforeCursor
        pendingSelectionDelta = if (hasPendingSelectionDelta) {
            pendingSelectionDelta + delta
        } else {
            delta
        }
        hasPendingSelectionDelta = true

        // Do not extend seededAtNanos here. The age limit is measured from the
        // last authoritative editor read, not from the last local keystroke.
        return updated
    }

    /**
     * Validate Android's cursor callback. A callback matching NOVA's pending
     * edit delta keeps the cache; any unexpected movement invalidates it.
     */
    fun onSelectionUpdate(
        oldSelectionStart: Int,
        oldSelectionEnd: Int,
        newSelectionStart: Int,
        newSelectionEnd: Int
    ): Boolean {
        if (textBeforeCursor == null) return false

        val allKnown = oldSelectionStart >= 0 && oldSelectionEnd >= 0 &&
            newSelectionStart >= 0 && newSelectionEnd >= 0
        val bothCollapsed = oldSelectionStart == oldSelectionEnd &&
            newSelectionStart == newSelectionEnd
        if (!allKnown || !bothCollapsed) {
            invalidate()
            return false
        }

        if (hasPendingSelectionDelta) {
            val actualDelta = newSelectionStart - oldSelectionStart
            val previousCursorMatches = lastObservedCursor == null ||
                lastObservedCursor == oldSelectionStart
            if (actualDelta == pendingSelectionDelta && previousCursorMatches) {
                hasPendingSelectionDelta = false
                pendingSelectionDelta = 0
                lastObservedCursor = newSelectionStart
                return true
            } else {
                invalidate()
                return false
            }
        } else {
            // With no local edit pending, a changed cursor or an old position
            // inconsistent with our last callback means the editor moved the
            // insertion point independently of NOVA.
            val previousCursorMatches = lastObservedCursor == null ||
                lastObservedCursor == oldSelectionStart
            if (newSelectionStart != oldSelectionStart || !previousCursorMatches) {
                invalidate()
                return false
            } else {
                lastObservedCursor = newSelectionStart
                return true
            }
        }
    }

    fun invalidate() {
        textBeforeCursor = null
        seededAtNanos = 0L
        editsSinceSeed = 0
        hasPendingSelectionDelta = false
        pendingSelectionDelta = 0
        lastObservedCursor = null
    }
}
