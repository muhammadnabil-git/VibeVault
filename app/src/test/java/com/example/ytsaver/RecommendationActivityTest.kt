package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationActivityTest {
    @Test
    fun regionalVideoRecommendationsLeadAndOutnumberPersonalizedResults() {
        val regional = (1..9).map { recommendation("regional-$it", "regional") }
        val personalized = (1..3).map { recommendation("personalized-$it", "personalized") }

        val ranked = prioritizeRegionalRecommendations(regional, personalized)

        assertEquals(
            listOf(
                "regional-1", "regional-2", "regional-3", "personalized-1",
                "regional-4", "regional-5", "regional-6", "personalized-2",
                "regional-7", "regional-8", "regional-9", "personalized-3",
            ),
            ranked.map { it.title },
        )
    }

    @Test
    fun regionalVideoRecommendationsDeduplicateByVideo() {
        val regional = listOf(recommendation("abcdefghijk", "regional"))
        val personalized = listOf(
            FeedItem("https://youtu.be/abcdefghijk?feature=shared", "duplicate", "", 0L, null),
            recommendation("personalized-1", "personalized"),
        )

        val ranked = prioritizeRegionalRecommendations(regional, personalized)

        assertEquals(listOf("abcdefghijk", "personalized-1"), ranked.map { it.title })
    }

    @Test
    fun combinesSearchesPlayedFilesAndSavedFiles() {
        val savedAudio = audio("Downloaded Artist - Downloaded Song.mp3", "saved-audio")
        val playedAudio = audio("Played Artist - Played Song.mp3", "played-audio")
        val savedVideo = video("Downloaded Channel - Video.mp4", "saved-video")
        val playedVideo = video("Played Channel - Clip.mp4", "played-video")

        val audioTopics = RecommendationActivity.topics(
            searches = listOf("Searched Artist"),
            history = listOf(savedAudio, playedAudio, savedVideo, playedVideo),
            playedUris = listOf(playedAudio.uri, playedVideo.uri),
            audio = true,
        )
        val videoTopics = RecommendationActivity.topics(
            searches = listOf("Searched Topic"),
            history = listOf(savedAudio, playedAudio, savedVideo, playedVideo),
            playedUris = listOf(playedAudio.uri, playedVideo.uri),
            audio = false,
        )

        assertTrue(audioTopics.contains("Searched Artist"))
        assertTrue(audioTopics.contains("Played Artist Played Song"))
        assertTrue(audioTopics.contains("Downloaded Artist Downloaded Song"))
        assertTrue(videoTopics.contains("Searched Topic"))
        assertTrue(videoTopics.contains("Played Channel - Clip"))
        assertTrue(videoTopics.contains("Downloaded Channel - Video"))
    }

    private fun audio(name: String, uri: String) = HistoryItem(name, "MP3", 0, 0, 1, "", uri, "")

    private fun video(name: String, uri: String) = HistoryItem(name, "MP4", 0, 0, 1, "", uri, "")

    private fun recommendation(id: String, site: String) =
        FeedItem("https://www.youtube.com/watch?v=$id", id, "", 0L, null, site = site)
}
