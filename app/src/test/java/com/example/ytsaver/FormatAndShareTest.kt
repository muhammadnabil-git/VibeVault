package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAndShareTest {
    @Test fun countdownFormatting() {
        assertEquals("0:05", formatCountdown(5_000))
        assertEquals("15:00", formatCountdown(15 * 60_000L))
        assertEquals("1:30:00", formatCountdown(90 * 60_000L))
        assertEquals("0:00", formatCountdown(-5))
    }

    @Test fun sourceIdIgnoresTrackingParameters() {
        assertEquals("abcdefghijk", sourceId("https://youtu.be/abcdefghijk?si=xyz"))
        assertEquals("abcdefghijk", sourceId("https://www.youtube.com/watch?v=abcdefghijk&list=1"))
    }

    @Test fun youtubeMusicDownloadsUseTheEquivalentYoutubeVideo() {
        assertEquals(
            "https://www.youtube.com/watch?v=abcdefghijk",
            LinkResolver.youtubeVideoUrlFromMusic("https://music.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk"),
        )
        assertNull(LinkResolver.youtubeVideoUrlFromMusic("https://www.youtube.com/watch?v=abcdefghijk"))
        assertNull(LinkResolver.youtubeVideoUrlFromMusic("https://music.youtube.com/channel/UC123"))
    }

    @Test fun shareTextHasTitlesAndLinksButNeverLocalPaths() {
        val p = Playlist(
            "id", "Road trip", KIND_MIXED,
            listOf(
                PlEntry("Song A", "Artist", 100, null, "https://youtu.be/aaaaaaaaaaa", "content://media/external/1", true, "YouTube"),
                PlEntry("Song B", "", 100, null, "", "content://media/external/2", true, ""),
            ),
            0L,
        )
        val text = playlistShareText(p)
        assertTrue(text.contains("Road trip"))
        assertTrue(text.contains("1. Song A — Artist"))
        assertTrue(text.contains("https://youtu.be/aaaaaaaaaaa"))
        assertTrue(text.contains("2. Song B"))
        assertFalse(text.contains("content://"))
    }
}
