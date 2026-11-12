package com.example.ytsaver

import android.content.Context
import org.json.JSONArray

object RecommendationActivity {
    private const val SEARCH_KEY = "recommendation_searches"
    private const val MAX_SEARCHES = 24

    fun searches(ctx: Context): List<String> = runCatching {
        val values = JSONArray(Prefs.str(ctx.applicationContext, SEARCH_KEY) ?: "[]")
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun recordSearch(ctx: Context, query: String) {
        val value = query.trim().take(160)
        if (value.isBlank() || parseLinks(value).isNotEmpty()) return
        val normalized = normalize(value)
        val updated = listOf(value) + searches(ctx).filterNot { normalize(it) == normalized }
        Prefs.putStr(ctx.applicationContext, SEARCH_KEY, JSONArray(updated.take(MAX_SEARCHES)).toString())
    }

    fun hasArabicMetadata(item: FeedItem): Boolean =
        (item.title + item.uploader).any {
            it.isLetter() && (it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF)
        }

    fun topics(
        searches: List<String>,
        history: List<HistoryItem>,
        playedUris: List<String>,
        audio: Boolean,
        limit: Int = 9,
    ): List<String> {
        val format = if (audio) "MP3" else "MP4"
        val byUri = history.associateBy { it.uri }
        fun topic(item: HistoryItem): String {
            if (!audio) return prettyName(item.name)
            val artist = TrackArt.artistFromName(item.name)
            val title = TrackArt.titleFromName(item.name)
            return listOfNotNull(artist, title).joinToString(" ").ifBlank { prettyName(item.name) }
        }

        val searchTopics = searches.map(String::trim).filter(String::isNotBlank)
        val playedTopics = playedUris.mapNotNull(byUri::get).filter { it.format == format }.map(::topic)
        val savedTopics = history.asSequence().filter { it.format == format }
            .sortedByDescending { it.timeMillis }.map(::topic).toList()
        val groups = if (audio) {
            listOf(searchTopics, playedTopics, savedTopics)
        } else {
            listOf(playedTopics, savedTopics, searchTopics)
        }
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var index = 0
        while (groups.any { index < it.size } && results.size < limit) {
            groups.forEach { group ->
                group.getOrNull(index)?.let { value ->
                    if (value.isNotBlank() && seen.add(normalize(value))) results += value
                }
            }
            index++
        }
        return results.take(limit)
    }

    private fun normalize(value: String) =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
