package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtNameTest {
    @Test fun artistIsTheTextBeforeTheDash() =
        assertEquals("Tamer Ashour", TrackArt.artistFromName("Tamer Ashour - Gada3.mp3"))

    @Test fun onlyTheFirstArtistIsUsed() =
        assertEquals("Artist A", TrackArt.artistFromName("Artist A, Artist B - Song.mp3"))

    @Test fun featuredArtistIsDropped() =
        assertEquals("Artist", TrackArt.artistFromName("Artist feat. Guest - Song.mp3"))

    @Test fun noDashMeansNoArtist() =
        assertNull(TrackArt.artistFromName("Just a title.mp3"))
}
