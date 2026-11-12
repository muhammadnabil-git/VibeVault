package com.example.ytsaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URL

data class Preview(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumb: Bitmap?,
    val thumbUrl: String? = null,
)

data class PlaylistEntry(val url: String, val title: String)

data class PlaylistInfo(val title: String, val entries: List<PlaylistEntry>)

data class PreviewState(
    val loading: Boolean = false,
    val preview: Preview? = null,
    val playlist: PlaylistInfo? = null,
    val failed: Boolean = false,
)

fun formatDuration(sec: Long): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

object PreviewLoader {
    /**
     * Blocking and QUICK (about 0.3 s): YouTube's own oEmbed gives title, channel and picture at once, so the card can
     * show them while [load] is still reading the duration. Null for other websites or when it fails.
     */
    fun quick(url: String): Preview? {
        if (!(url.contains("youtube.com") || url.contains("youtu.be"))) return null
        return runCatching {
            val c = URL("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8")).openConnection()
            c.connectTimeout = 3_000
            c.readTimeout = 3_000
            val o = org.json.JSONObject(c.getInputStream().bufferedReader().use { it.readText() })
            val thumb = o.optString("thumbnail_url").ifBlank { null }
            val bmp = thumb?.let { u ->
                runCatching {
                    val t = URL(u).openConnection()
                    t.connectTimeout = 3_000
                    t.readTimeout = 3_000
                    t.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            Preview(o.optString("title").ifBlank { return null }, o.optString("author_name"), 0L, bmp, thumb)
        }.getOrNull()
    }

    /** Blocking: run on a background thread. Throws if the link can't be read. */
    fun load(url: String): Preview {
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", "10")
            addOption("--no-warnings")
            addOption("--ignore-no-formats-error")     // only the title, duration and picture are needed, not a format
            addOption("--no-check-formats")
        }
        val info = YoutubeDL.getInstance().getInfo(req)

        val title: String = info.title ?: "Untitled"
        val uploader: String = info.uploader ?: ""
        val duration: Long = (info.duration as Number?)?.toLong() ?: 0L
        val thumbUrl: String? = info.thumbnail

        val bmp: Bitmap? = thumbUrl?.let { u ->
            runCatching {
                val c = URL(u).openConnection()
                c.connectTimeout = 5_000
                c.readTimeout = 5_000
                c.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        return Preview(title, uploader, duration, bmp, thumbUrl)
    }
}

object PlaylistLoader {
    const val MAX_ITEMS = 200

    /** Blocking: lists the videos of a playlist without downloading anything. */
    fun load(url: String, processId: String? = null): PlaylistInfo {
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--yes-playlist")
            addOption("--playlist-end", MAX_ITEMS.toString())
            addOption("--socket-timeout", "15")
            addOption("--print", "%(playlist_title)s|||%(url)s|||%(title)s|||%(id)s")
        }
        val out: String = YoutubeDL.getInstance().execute(req, processId, null).out ?: ""

        var playlistTitle = ""
        val entries = mutableListOf<PlaylistEntry>()
        for (line in out.lines()) {
            val parts = line.split("|||")
            if (parts.size < 4) continue
            val pt = parts[0]
            val u = parts[1]
            val id = parts.last()
            val t = parts.subList(2, parts.size - 1).joinToString("|||")

            if (playlistTitle.isEmpty() && pt != "NA") playlistTitle = pt
            if (t == "[Private video]" || t == "[Deleted video]") continue

            val entryUrl = when {
                u.startsWith("http") -> u
                id.isNotBlank() && id != "NA" -> "https://www.youtube.com/watch?v=$id"
                else -> continue
            }
            entries += PlaylistEntry(entryUrl, if (t == "NA") "" else t)
        }
        return PlaylistInfo(playlistTitle.ifBlank { "Playlist" }, entries)
    }
}
