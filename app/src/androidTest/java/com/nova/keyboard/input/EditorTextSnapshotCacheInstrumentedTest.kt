package com.nova.keyboard.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class EditorTextSnapshotCacheInstrumentedTest {

    @Test
    fun localInsertIsRetainedWhenAndroidConfirmsExpectedCursorMovement() {
        val cache = EditorTextSnapshotCache()
        cache.seed("na ini", 1_000_000_000L)

        assertEquals("na iniku", cache.applyLocalEdit(0, "ku"))
        assertEquals(true, cache.onSelectionUpdate(40, 40, 42, 42))

        assertEquals("na iniku", cache.getIfFresh(1_020_000_000L))
    }

    @Test
    fun backspaceAndSuggestionReplacementMirrorUtf16Changes() {
        val cache = EditorTextSnapshotCache()
        cache.seed("hello worl", 1_000_000_000L)

        assertEquals("hello wor", cache.applyLocalEdit(1, ""))
        assertEquals(true, cache.onSelectionUpdate(10, 10, 9, 9))

        assertEquals("hello world ", cache.applyLocalEdit(3, "world "))
        assertEquals(true, cache.onSelectionUpdate(9, 9, 12, 12))

        assertEquals("hello world ", cache.getIfFresh(1_030_000_000L))
    }

    @Test
    fun unexpectedCursorMovementInvalidatesSnapshot() {
        val cache = EditorTextSnapshotCache()
        cache.seed("hello", 1_000_000_000L)
        assertEquals(false, cache.onSelectionUpdate(5, 5, 2, 2))

        assertNull(cache.getIfFresh(1_010_000_000L))
    }

    @Test
    fun nonCollapsedSelectionInvalidatesSnapshot() {
        val cache = EditorTextSnapshotCache()
        cache.seed("hello", 1_000_000_000L)
        assertEquals(false, cache.onSelectionUpdate(5, 5, 2, 4))

        assertNull(cache.getIfFresh(1_010_000_000L))
    }

    @Test
    fun cacheExpiresByAgeAndEditCount() {
        val ageCache = EditorTextSnapshotCache(maxAgeNanos = 100L)
        ageCache.seed("hello", 1_000L)
        assertNull(ageCache.getIfFresh(1_100L))

        val editCache = EditorTextSnapshotCache(maxEditsBeforeResync = 2)
        editCache.seed("a", 1_000L)
        assertNotNull(editCache.applyLocalEdit(0, "b"))
        assertNotNull(editCache.applyLocalEdit(0, "c"))
        assertNull(editCache.getIfFresh(1_030L))
    }
}
