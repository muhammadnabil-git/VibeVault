package com.example.ytsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The picture of a saved file.
 *  - video: a frame of the video;
 *  - audio: the embedded cover; then a matching Deezer album cover and artist photo; finally the branded tile.
 * The online lookups can be switched off in Settings -> Behavior ("Artist pictures").
 */
object TrackArt {
    private val mem = LruCache<String, Bitmap>(80)
    private val noArt = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val quranTerms = Regex("""(?i)(?<![a-z0-9])(qur['’]?an|surah?|recitation|tilawah|tilawat|qari|ayat|ayah)(?![a-z0-9])""")
    private val arabicQuranTerms = Regex("القرآن|قرآن|تلاوة|ترتيل|سورة|سوره|آيات|آية|المصحف")
    @Volatile private var standardQuranCover: Bitmap? = null

    fun enabled(ctx: Context) = Prefs.bool(ctx, "artist_art", true)

    /** Blocking: call from a background thread. */
    fun load(ctx: Context, item: HistoryItem): Bitmap? {
        mem.get(item.uri)?.let { return it }
        if (item.uri in noArt) return null
        val bmp = when {
            isQuranRecitation(item.name, item.sourceUrl) -> quranCover(ctx)
            item.format == "MP3" -> audioArt(ctx, item)
            else -> VideoThumbs.load(ctx, item.uri)
        }
        if (bmp != null) mem.put(item.uri, bmp) else if (item.format == "MP3") noArt.add(item.uri)
        return bmp
    }

    private fun audioArt(ctx: Context, item: HistoryItem): Bitmap? {
        var tagArtist: String? = null
        var tagTitle: String? = null
        var tagAlbum: String? = null
        var embedded: ByteArray? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(item.uri))
            embedded = r.embeddedPicture
            tagArtist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))?.takeIf { it.isNotBlank() }
            tagTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            tagAlbum = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // an unreadable file simply has no cover
        } finally {
            runCatching { r.release() }
        }
        if (isQuranRecitation(item.name, item.sourceUrl, tagTitle, tagAlbum, tagArtist)) return quranCover(ctx)
        embedded?.let { decode(it)?.let { cover -> return cover } }
        if (!enabled(ctx)) return null
        val artist = (tagArtist ?: artistFromName(item.name)) ?: return null
        val title = tagTitle ?: item.name.substringAfter(" - ", "").takeIf { it.isNotBlank() }?.let(::prettyName)
        if (title != null) trackPicture(ctx, artist, title)?.let { return it }
        return artistPicture(ctx, artist)
    }

    private fun isQuranRecitation(vararg values: String?): Boolean =
        values.filterNotNull().any { quranTerms.containsMatchIn(it) || arabicQuranTerms.containsMatchIn(it) }

    private fun quranCover(ctx: Context): Bitmap? {
        standardQuranCover?.let { return it }
        return synchronized(this) {
            standardQuranCover ?: BitmapFactory.decodeResource(
                ctx.resources,
                R.drawable.quran_cover,
                BitmapFactory.Options().apply { inSampleSize = 4 },
            )?.also { standardQuranCover = it }
        }
    }

    /** "Artist - Title.mp3" -> "Artist" (first artist only); null when the name has no such pattern. */
    fun artistFromName(fileName: String): String? {
        val base = prettyName(fileName)
        val i = base.indexOf(" - ")
        if (i <= 0) return null
        val first = base.substring(0, i).split(',', '&', ';').first()
        return LastFm.cleanArtist(first).trim().takeIf { it.length in 2..60 }
    }

    private fun decode(bytes: ByteArray, target: Int = 400): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun key(artist: String): String =
        MessageDigest.getInstance("SHA-1").digest(artist.lowercase().trim().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun artistPicture(ctx: Context, artist: String): Bitmap? {
        val dir = File(ctx.cacheDir, "artist_art").apply { mkdirs() }
        val k = key(artist)
        val img = File(dir, "$k.jpg")
        val none = File(dir, "$k.none")
        if (img.isFile) return runCatching { BitmapFactory.decodeFile(img.path) }.getOrNull()
        // a failed lookup is not repeated for a week (offline, unknown artist)
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < 7 * 24 * 3600_000L) return null
        return try {
            val url = fetchArtistUrl(artist)
            if (url == null) { none.writeText("x"); return null }
            val bytes = download(url) ?: return null
            img.writeBytes(bytes)
            decode(bytes)
        } catch (_: Exception) {
            null       // offline: tried again next time
        }
    }

    /** A track result is used only when Deezer reports the exact same artist. */
    private fun trackPicture(ctx: Context, artist: String, title: String): Bitmap? {
        val dir = File(ctx.cacheDir, "track_art").apply { mkdirs() }
        val img = File(dir, "${key("$artist $title")}.jpg")
        if (img.isFile) {
            runCatching { BitmapFactory.decodeFile(img.path) }.getOrNull()?.let { return it }
            img.delete()
        }
        return try {
            val q = URLEncoder.encode("artist:\"$artist\" track:\"$title\"", "UTF-8")
            val bytes = get("https://api.deezer.com/search/track?q=$q&limit=10", 300_000) ?: return null
            val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("data") ?: return null
            val want = norm(artist)
            val wantTitle = norm(title)
            for (i in 0 until arr.length()) {
                val track = arr.optJSONObject(i) ?: continue
                if (norm(track.optJSONObject("artist")?.optString("name").orEmpty()) != want) continue
                val foundTitle = norm(track.optString("title"))
                if (!foundTitle.contains(wantTitle) && !wantTitle.contains(foundTitle)) continue
                val album = track.optJSONObject("album") ?: continue
                val cover = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
                if (!cover.startsWith("https://")) continue
                val art = download(cover) ?: continue
                img.writeBytes(art)
                return decode(art)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun get(url: String, maxBytes: Int): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "VibeVault/7.7")
            if (c.responseCode !in 200..299) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) return null
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): ByteArray? = get(url, 1_500_000)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Deezer's public artist search (no key). The result must have the same name, so a wrong person is never shown. */
    private fun fetchArtistUrl(artist: String): String? {
        val bytes = get("https://api.deezer.com/search/artist?q=" + URLEncoder.encode(artist, "UTF-8") + "&limit=5", 200_000) ?: return null
        val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("data") ?: return null
        val want = norm(artist)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (norm(o.optString("name")) != want) continue
            val pic = o.optString("picture_medium").ifBlank { o.optString("picture") }
            // Deezer's "no picture" placeholder has an empty hash in its address
            if (pic.startsWith("https://") && !pic.contains("/artist//")) return pic
        }
        return null
    }
}

/** The picture of a file (see [TrackArt]); a branded tile with the app's colours while loading or when there is none. */
@Composable
fun TrackArtImage(item: HistoryItem, modifier: Modifier = Modifier, iconSize: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { TrackArt.load(ctx, item) }
    }
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.background(Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.55f), cs.secondary.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Sym(if (item.format == "MP3") R.drawable.ic_queue_music else R.drawable.ic_play, iconSize, tint = cs.onPrimary)
        }
    }
}
