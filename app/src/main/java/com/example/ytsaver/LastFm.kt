package com.example.ytsaver

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What Last.fm adds to a recognised song. */
class LastFmInfo(val album: String, val imageUrl: String?, val tags: String, val summary: String, val durationSec: Int)

/** Phase 3: cleans the recognised text, then asks Last.fm's track.getInfo for the cover, genres and a short background. Blocking. */
object LastFm {
    private val NOISE = Regex(
        "(remaster(ed)?|re-?master|version|edit|mix|mono|stereo|live|deluxe|bonus|explicit|radio|single|feat\\.?|ft\\.?|featuring|from\\b)",
        RegexOption.IGNORE_CASE,
    )

    /** "Song (Remastered 2011) [feat. X] - 2011 Remaster" -> "Song". */
    fun cleanTitle(t: String): String {
        var s = t
        s = Regex("\\s*[(\\[]([^)\\]]*)[)\\]]").replace(s) { if (NOISE.containsMatchIn(it.groupValues[1])) "" else it.value }
        s = Regex("\\s+[-–—]\\s+.*(remaster|version|edit|mix|live|mono|stereo).*$", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE).replace(s, "")
        return s.trim().ifBlank { t.trim() }
    }

    /** "Artist feat. Guest" -> "Artist". */
    fun cleanArtist(a: String): String =
        Regex("\\s+(feat\\.?|ft\\.?|featuring|with)\\s+.*$", RegexOption.IGNORE_CASE).replace(a, "").trim().ifBlank { a.trim() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(artist: String, track: String): JSONObject? {
        val url = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=${DetectorKeys.LASTFM}" +
            "&artist=${enc(artist)}&track=${enc(track)}&autocorrect=1&format=json"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "VibeVault/7.7 (Android music detector)")
            if (c.responseCode !in 200..299) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return j.optJSONObject("track")           // an unknown song comes back as {"error":6,...}
        } finally {
            c.disconnect()
        }
    }

    fun trackInfo(artist: String, title: String): LastFmInfo? {
        val t = call(cleanArtist(artist), cleanTitle(title)) ?: call(artist, title) ?: return null

        val album = t.optJSONObject("album")
        // the biggest picture; Last.fm's grey star placeholder doesn't count
        val images: JSONArray = album?.optJSONArray("image") ?: JSONArray()
        var image: String? = null
        for (size in listOf("extralarge", "large", "medium")) {
            for (i in 0 until images.length()) {
                val o = images.optJSONObject(i) ?: continue
                val u = o.optString("#text")
                if (o.optString("size") == size && u.startsWith("http") && "2a96cbd8b46e442fc41c2b86b821562f" !in u) { image = u; break }
            }
            if (image != null) break
        }

        val tagNode = t.optJSONObject("toptags")?.opt("tag")
        val tagObjs: List<JSONObject> = when (tagNode) {
            is JSONArray -> (0 until tagNode.length()).mapNotNull { tagNode.optJSONObject(it) }
            is JSONObject -> listOf(tagNode)
            else -> emptyList()
        }
        val tags = tagObjs.map { it.optString("name") }
            .filter { it.isNotBlank() && !it.equals("seen live", true) }
            .take(3).joinToString(", ") { n -> n.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } }

        val summary = t.optJSONObject("wiki")?.optString("summary").orEmpty()
            .replace(Regex("<a href.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()

        return LastFmInfo(album?.optString("title").orEmpty(), image, tags, summary, t.optString("duration").toIntOrNull()?.div(1000) ?: 0)
    }
}
