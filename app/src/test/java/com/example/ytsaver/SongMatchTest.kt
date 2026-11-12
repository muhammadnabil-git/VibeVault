package com.example.ytsaver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the Anghami / Apple Music matching (title and artist only; extra words are ignored). */
class SongMatchTest {
    @Test fun sameTitleAndArtistMatches() =
        assertTrue(LinkResolver.matchesSong("Gada3", "Tamer Ashour", "Gada3", "Tamer Ashour"))

    @Test fun extraWordsInTheTitleAreIgnored() =
        assertTrue(LinkResolver.matchesSong("Shape of You", "Ed Sheeran", "Shape of You (Remastered 2020)", "Ed Sheeran"))

    @Test fun firstArtistIsEnoughWhenManyAreListed() =
        assertTrue(LinkResolver.matchesSong("Song", "Artist A, Artist B", "Song", "Artist A & Artist B"))

    @Test fun differentSongDoesNotMatch() =
        assertFalse(LinkResolver.matchesSong("Gada3", "Tamer Ashour", "Another Song", "Tamer Ashour"))

    @Test fun differentArtistDoesNotMatch() =
        assertFalse(LinkResolver.matchesSong("Gada3", "Tamer Ashour", "Gada3", "Someone Else"))
}
