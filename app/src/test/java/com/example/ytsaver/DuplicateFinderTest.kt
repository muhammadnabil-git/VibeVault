package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {
    @Test fun sameSourceAndFormatAreDuplicates_newestIsKept() {
        val a = item("song.mp3", time = 100, source = "https://youtu.be/abcdefghijk")
        val b = item("song (1).mp3", time = 300, source = "https://www.youtube.com/watch?v=abcdefghijk&t=5")
        val g = DuplicateFinder.groups(listOf(a, b))
        assertEquals(1, g.size)
        assertEquals(b, g[0][0])                              // newest first
        assertEquals(listOf(a), DuplicateFinder.removable(listOf(a, b)))
    }

    @Test fun sameSourceDifferentFormatIsNotADuplicate() {
        val mp3 = item("x.mp3", "MP3", source = "https://youtu.be/abcdefghijk")
        val mp4 = item("x.mp4", "MP4", source = "https://youtu.be/abcdefghijk")
        assertTrue(DuplicateFinder.groups(listOf(mp3, mp4)).isEmpty())
    }

    @Test fun withoutSourceNameFormatAndSizeMustAllMatch() {
        val a = item("Song.mp3", size = 5, time = 1)
        val b = item("song.mp3", size = 5, time = 2)
        val c = item("song.mp3", size = 6, time = 3)
        assertEquals(1, DuplicateFinder.groups(listOf(a, b, c)).size)
        assertEquals(5L, DuplicateFinder.reclaimableBytes(listOf(a, b, c)))
    }

    @Test fun importedDeviceFilesAreNeverTouched() {
        val a = item("a.mp3", location = LOCATION_DEVICE + "/Music", source = "https://youtu.be/abcdefghijk")
        val b = item("b.mp3", location = LOCATION_DEVICE + "/Music", source = "https://youtu.be/abcdefghijk")
        assertTrue(DuplicateFinder.removable(listOf(a, b)).isEmpty())
    }

    @Test fun nothingToRemoveWhenAllDifferent() {
        assertTrue(DuplicateFinder.removable(listOf(item("a.mp3"), item("b.mp3", size = 9))).isEmpty())
    }
}
