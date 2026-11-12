package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPlaylistsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test fun addedThisWeekOnlyHoldsRecentFiles() {
        val fresh = item("new.mp3", time = now - 2 * day)
        val old = item("old.mp3", time = now - 30 * day)
        val week = SmartPlaylists.build(listOf(fresh, old), now).first { it.id == "new" }
        assertEquals(listOf(fresh), week.items)
    }

    @Test fun musicAndVideoAreSplitByFormat() {
        val m = item("a.mp3", "MP3"); val v = item("b.mp4", "MP4")
        val lists = SmartPlaylists.build(listOf(m, v), now).associateBy { it.id }
        assertEquals(listOf(m), lists["music"]!!.items)
        assertEquals(listOf(v), lists["video"]!!.items)
    }

    @Test fun emptyListsAreLeftOut() {
        val lists = SmartPlaylists.build(listOf(item("a.mp3", "MP3", time = now)), now)
        assertFalse(lists.any { it.id == "video" })
        assertTrue(SmartPlaylists.build(emptyList(), now).isEmpty())
    }

    @Test fun biggestFilesAreSortedBySize() {
        val items = (1..30).map { item("f$it.mp3", size = it * 10L, time = now) }
        val big = SmartPlaylists.build(items, now).first { it.id == "big" }
        assertEquals(25, big.items.size)
        assertEquals(300L, big.items.first().sizeBytes)
    }
}
