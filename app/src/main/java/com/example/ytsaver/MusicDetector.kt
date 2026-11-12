package com.example.ytsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A recording of MusicBrainz that goes with a recognised song. */
data class SongCandidate(val recordingId: String, val title: String, val artist: String, val score: Double)

/** Everything MusicBrainz knows about the recording, ready to show. */
data class SongInfo(
    val recordingId: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val genres: String,
    val durationSec: Int,
    val score: Double,
    val cover: Bitmap?,
    val releaseId: String,
    val via: String = "",           // which service recognised it
    val summary: String = "",       // short background from Last.fm
)

class DetectorException(message: String) : Exception(message)

/**
 * Music detector helpers: MusicBrainz details + Cover Art Archive picture, and the final enrichment of a recognised song.
 * Everything here is blocking: call it from a background thread.
 */
object MusicDetector {
    private const val UA = "VibeVault/7.7 (Android music detector)"
    private fun open(url: String, post: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        return c
    }

    private fun readText(c: HttpURLConnection): String {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw DetectorException("HTTP $code" + if (body.isNotBlank()) ": " + body.take(160) else "")
        return body
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** MusicBrainz details (+ cover picture) of one recording. */
    fun details(c: SongCandidate): SongInfo {
        var title = c.title
        var artist = c.artist
        var album = ""
        var year = ""
        var genres = ""
        var length = 0
        var releaseId = ""
        var groupId = ""
        runCatching {
            val j = JSONObject(readText(open("https://musicbrainz.org/ws/2/recording/${c.recordingId}?inc=artists+releases+release-groups+genres&fmt=json")))
            title = j.optString("title", title)
            length = j.optInt("length", 0) / 1000
            j.optJSONArray("artist-credit")?.let { ac ->
                artist = (0 until ac.length()).joinToString("") {
                    val o = ac.getJSONObject(it)
                    o.optString("name") + o.optString("joinphrase")
                }.ifBlank { artist }
            }
            j.optJSONArray("genres")?.let { g ->
                genres = (0 until g.length()).map { g.getJSONObject(it) }
                    .sortedByDescending { it.optInt("count") }.take(3)
                    .joinToString(", ") { it.optString("name").replaceFirstChar { ch -> ch.uppercase() } }
            }
            // the original album: an official album release with the earliest date wins over singles and compilations
            val rel = j.optJSONArray("releases")
            if (rel != null) {
                val list = (0 until rel.length()).map { rel.getJSONObject(it) }
                fun rank(o: JSONObject): Int {
                    val rg = o.optJSONObject("release-group")
                    val primary = rg?.optString("primary-type").orEmpty()
                    val secondary = rg?.optJSONArray("secondary-types")?.length() ?: 0
                    var r = 0
                    if (o.optString("status") != "Official") r += 4
                    if (primary != "Album") r += 2
                    if (secondary > 0) r += 1
                    return r
                }
                val best = list.sortedWith(compareBy<JSONObject>({ rank(it) }, { it.optString("date").ifBlank { "9999" } })).firstOrNull()
                if (best != null) {
                    album = best.optString("title")
                    year = best.optString("date").take(4)
                    releaseId = best.optString("id")
                    groupId = best.optJSONObject("release-group")?.optString("id").orEmpty()
                }
            }
        }
        val cover = coverArt(releaseId, groupId)
        return SongInfo(c.recordingId, title, artist, album, year, genres, length, c.score, cover, releaseId)
    }

    /** MusicBrainz: the recording that goes with what was recognised, or null when it can't be matched confidently. */
    private fun searchRecording(h: TrackMatch): SongCandidate? {
        val q = "recording:\"${LastFm.cleanTitle(h.title).replace("\"", "")}\" AND artist:\"${LastFm.cleanArtist(h.artist).replace("\"", "")}\""
        val j = JSONObject(readText(open("https://musicbrainz.org/ws/2/recording?query=${enc(q)}&limit=3&fmt=json")))
        val recs = j.optJSONArray("recordings") ?: return null
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            if (r.optInt("score") < 80) continue
            val id = r.optString("id")
            if (id.isNotBlank()) return SongCandidate(id, r.optString("title", h.title), h.artist, 1.0)
        }
        return null
    }

    private fun fetchBitmap(url: String): Bitmap? = runCatching {
        val c = open(url)
        c.setRequestProperty("Accept", "image/*")
        if (c.responseCode in 200..299) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
    }.getOrNull()

    /**
     * Phase 3: the recognised song, completed from Last.fm (cover, genres, background). Whatever Last.fm doesn't have
     * (cover, album, year) is filled from MusicBrainz / the Cover Art Archive, then from the recognising service itself.
     * Never throws: the title and artist of the match always come back.
     */
    fun enrich(m: TrackMatch): SongInfo {
        val lf = runCatching { LastFm.trackInfo(m.artist, m.title) }.getOrNull()
        var cover: Bitmap? = lf?.imageUrl?.let { fetchBitmap(it) }
        var album = lf?.album.orEmpty().ifBlank { m.album }
        var year = m.year
        var genres = lf?.tags.orEmpty().ifBlank { m.genres }
        var length = lf?.durationSec?.takeIf { it > 0 } ?: m.durationSec
        var recordingId = ""
        var releaseId = ""
        if (cover == null || album.isBlank() || year.isBlank()) {
            val mb = runCatching { searchRecording(m) }.getOrNull()?.let { runCatching { details(it) }.getOrNull() }
            if (mb != null) {
                cover = cover ?: mb.cover
                album = album.ifBlank { mb.album }
                year = year.ifBlank { mb.year }
                genres = genres.ifBlank { mb.genres }
                if (length <= 0) length = mb.durationSec
                recordingId = mb.recordingId
                releaseId = mb.releaseId
            }
        }
        if (cover == null) cover = m.coverUrl?.let { fetchBitmap(it) }
        return SongInfo(recordingId, m.title, m.artist, album, year, genres, length, -1.0, cover, releaseId, m.via, lf?.summary.orEmpty())
    }

    /** Cover Art Archive: the release's front picture, else the release group's. */
    private fun coverArt(releaseId: String, groupId: String): Bitmap? {
        val urls = buildList {
            if (releaseId.isNotBlank()) add("https://coverartarchive.org/release/$releaseId/front-500")
            if (groupId.isNotBlank()) add("https://coverartarchive.org/release-group/$groupId/front-500")
        }
        for (u in urls) {
            val bmp = runCatching {
                val c = open(u)
                c.setRequestProperty("Accept", "image/*")
                if (c.responseCode in 200..299) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
            }.getOrNull()
            if (bmp != null) return bmp
        }
        return null
    }
}
