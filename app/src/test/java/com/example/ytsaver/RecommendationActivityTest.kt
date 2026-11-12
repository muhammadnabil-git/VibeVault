package com.example.ytsaver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationActivityTest {
    @Test
    fun regionalVideoCandidatesRequireArabicMetadata() {
        assertTrue(RecommendationActivity.hasArabicMetadata(recommendation("abcdefghijk", "أخبار مصر")))
        assertFalse(RecommendationActivity.hasArabicMetadata(recommendation("lmnopqrstuv", "Global News")))
    }

    @Test
    fun personalizedVideoRecommendationsLeadAndRegionalResultsFillRemainingSlots() {
        val regional = (1..9).map { recommendation("regional-$it", "regional") }
        val personalized = (1..3).map { recommendation("personalized-$it", "personalized") }

        val ranked = prioritizePersonalizedRecommendations(personalized, regional)

        assertEquals(
            listOf(
                "personalized-1", "personalized-2", "personalized-3",
                "regional-1", "regional-2", "regional-3", "regional-4",
                "regional-5", "regional-6", "regional-7", "regional-8", "regional-9",
            ),
            ranked.map { it.title },
        )
    }

    @Test
    fun recommendationSectionsReturnAtMostSeventeenVideos() {
        val personalized = (1..20).map { recommendation("personalized-$it", "personalized") }

        assertEquals(17, prioritizePersonalizedRecommendations(personalized, emptyList()).size)
    }

    @Test
    fun videoRecommendationsDeduplicateByVideo() {
        val regional = listOf(recommendation("abcdefghijk", "regional"))
        val personalized = listOf(
            FeedItem("https://youtu.be/abcdefghijk?feature=shared", "duplicate", "", 0L, null),
            recommendation("personalized-1", "personalized"),
        )

        val ranked = prioritizePersonalizedRecommendations(personalized, regional)

        assertEquals(listOf("duplicate", "personalized-1"), ranked.map { it.title })
    }

    @Test
    fun musicRecommendationsCanBeInterleavedByDistinctArtist() {
        val items = listOf(
            recommendation("music-one", "Artist One").copy(uploader = "Artist One"),
            recommendation("music-two", "Artist One").copy(uploader = "Artist One"),
            recommendation("music-three", "Artist Two").copy(uploader = "Artist Two"),
        )

        val distinct = interleaveRecommendationResults(
            results = listOf(items),
            limit = 17,
            distinctKey = RecommendationActivity::artistKey,
        )

        assertEquals(listOf("music-one", "music-three"), distinct.map { it.title })
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
        assertEquals(
            listOf("Searched Topic", "Played Channel - Clip", "Downloaded Channel - Video"),
            videoTopics.take(3),
        )
    }

    @Test
    fun recentVideoSearchesTakePriorityOverPlaybackAndDownloads() {
        val savedVideo = video("Downloaded Channel - Video.mp4", "saved-video")
        val playedVideo = video("Played Channel - Clip.mp4", "played-video")
        val recentSearches = listOf("Search One", "Search Two", "Search Three", "Search Four", "Search Five")

        val topics = RecommendationActivity.topics(
            searches = recentSearches,
            history = listOf(savedVideo, playedVideo),
            playedUris = listOf(playedVideo.uri),
            audio = false,
            limit = 5,
        )

        assertEquals(recentSearches, topics)
    }

    private fun audio(name: String, uri: String) = HistoryItem(name, "MP3", 0, 0, 1, "", uri, "")

    private fun video(name: String, uri: String) = HistoryItem(name, "MP4", 0, 0, 1, "", uri, "")

    private fun recommendation(id: String, site: String) =
        FeedItem("https://www.youtube.com/watch?v=$id", id, "", 0L, null, site = site)
}
