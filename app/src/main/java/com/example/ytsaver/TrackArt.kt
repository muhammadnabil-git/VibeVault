package com.example.ytsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture of a saved file.
 *  - video: a frame of the video;
 *  - audio: the embedded cover; then matching online album art and artist imagery; finally the branded tile.
 * The online lookups can be switched off in Settings -> Behavior ("Artist pictures").
 */
object TrackArt {
    private val mem = LruCache<String, Bitmap>(80)
    private val noArt = ConcurrentHashMap<String, Long>()
    private val trackCoverUrls = ConcurrentHashMap<String, List<String>>()
    private val artistCoverUrls = ConcurrentHashMap<String, String>()
    private const val MISS_RETRY_MS = 30 * 60_000L
    private val quranTerms = Regex("""(?i)(?<![a-z0-9])(qur['’]?an|surah?|recitation|tilawah|tilawat|qari|ayat|ayah)(?![a-z0-9])""")
    private val arabicQuranTerms = Regex("القرآن|قرآن|تلاوة|ترتيل|سورة|سوره|آيات|آية|المصحف")
    @Volatile private var standardQuranCover: Bitmap? = null

    fun enabled(ctx: Context) = Prefs.bool(ctx, "artist_art", true)

    /** Blocking: call from a background thread. */
    fun load(ctx: Context, item: HistoryItem): Bitmap? {
        mem.get(item.uri)?.let { return it }
        val now = System.currentTimeMillis()
        if ((noArt[item.uri] ?: 0L) > now) return null
        val bmp = when {
            isQuranRecitation(item.name, item.sourceUrl) -> quranCover(ctx)
            item.format == "MP3" -> audioArt(ctx, item)
            else -> videoArt(ctx, item)
        }
        if (bmp != null) {
            mem.put(item.uri, bmp)
            noArt.remove(item.uri)
        } else {
            noArt[item.uri] = now + MISS_RETRY_MS
        }
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
        val artists = listOfNotNull(tagArtist, artistFromName(item.name))
            .map(LastFm::cleanArtist)
            .filter(String::isNotBlank)
            .distinct()
        val titles = listOfNotNull(tagTitle, titleFromName(item.name))
            .map(::cleanTrackTitle)
            .filter(String::isNotBlank)
            .distinct()
        for (title in titles) {
            artists.forEach { artist -> trackPicture(ctx, artist, title)?.let { return it } }
            trackPicture(ctx, null, title)?.let { return it }
        }
        artists.forEach { artist -> artistPicture(ctx, artist)?.let { return it } }
        return null
    }

    private fun videoArt(ctx: Context, item: HistoryItem): Bitmap? {
        VideoThumbs.load(ctx, item.uri)?.let { return it }
        val thumbUrl = youtubeThumbUrl(item.sourceUrl) ?: return null
        return download(thumbUrl)?.let(::decode)
    }

    fun youtubeThumbUrl(url: String): String? {
        val id = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")
            .find(url)?.groupValues?.get(1) ?: return null
        return "https://i.ytimg.com/vi/$id/hqdefault.jpg"
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

    fun findArtistCoverUrl(artist: String): String? {
        val k = key(artist)
        artistCoverUrls[k]?.let { return it }
        val url = fetchArtistUrl(artist) ?: youtubeMusicArtistCoverUrl(artist)
        if (url != null) artistCoverUrls[k] = url
        return url
    }

    /** "Artist - Title.mp3" -> "Artist" (first artist only); null when the name has no such pattern. */
    fun artistFromName(fileName: String): String? {
        val base = prettyName(fileName)
        val i = base.indexOf(" - ")
        if (i <= 0) return null
        val first = base.substring(0, i).split(',', '&', ';').first()
        return LastFm.cleanArtist(first).trim().takeIf { it.length in 2..60 }
    }

    fun titleFromName(fileName: String): String {
        val base = prettyName(fileName)
        return cleanTrackTitle(base.substringAfter(" - ", base)).ifBlank { base }
    }

    fun findTrackCoverUrl(title: String, artist: String?, region: String = Locale.getDefault().country): String? {
        val cleanedTitle = cleanTrackTitle(title)
        if (cleanedTitle.isBlank()) return null
        val cleanedArtist = artist?.let(LastFm::cleanArtist)?.takeIf { it.isNotBlank() }
        val urls = findTrackCoverUrls(cleanedTitle, cleanedArtist, region)
        return urls.firstOrNull() ?: cleanedArtist?.let(::findArtistCoverUrl)
    }

    fun findCoverUrl(title: String, artist: String?, region: String = Locale.getDefault().country): String? =
        findTrackCoverUrl(title, artist, region) ?: artist?.let(::findArtistCoverUrl)

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

    private fun artFile(ctx: Context, kind: String, name: String): File =
        File(File(ctx.filesDir, "artwork/$kind").apply { mkdirs() }, name)

    private fun persistArtwork(file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }

    private fun artistPicture(ctx: Context, artist: String): Bitmap? {
        val k = key(artist)
        val img = artFile(ctx, "artist_art", "$k.jpg")
        val none = artFile(ctx, "artist_art", "$k.none")
        if (img.isFile) return runCatching { BitmapFactory.decodeFile(img.path) }.getOrNull()
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < MISS_RETRY_MS) return null
        return try {
            val url = findArtistCoverUrl(artist)
            if (url == null) { none.writeText("x"); return null }
            val bytes = download(url) ?: return null
            img.writeBytes(bytes)
            none.delete()
            decode(bytes)
        } catch (_: Exception) {
            null       // offline: tried again next time
        }
    }

    /** Looks up public catalogues in order and persists the first matching cover that can be downloaded. */
    private fun trackPicture(ctx: Context, artist: String?, title: String): Bitmap? {
        val img = artFile(ctx, "track_art", "${key("${artist.orEmpty()} $title")}.jpg")
        if (img.isFile) {
            runCatching { BitmapFactory.decodeFile(img.path) }.getOrNull()?.let { return it }
            img.delete()
        }
        for (cover in findTrackCoverUrls(title, artist, Locale.getDefault().country)) {
            val art = download(cover) ?: continue
            val bitmap = decode(art) ?: continue
            img.writeBytes(art)
            return bitmap
        }
        val catalogArt = artist?.takeIf(String::isNotBlank)?.let { name ->
            runCatching { MusicDetector.enrich(TrackMatch(title, name, "artwork lookup")).cover }.getOrNull()
        }
        if (catalogArt != null) {
            persistArtwork(img, catalogArt)
            return catalogArt
        }
        return null
    }

    private fun findTrackCoverUrls(title: String, artist: String?, region: String): List<String> {
        val cleanedTitle = cleanTrackTitle(title)
        val cleanedArtist = artist?.let(LastFm::cleanArtist)?.takeIf { it.isNotBlank() }
        if (cleanedTitle.isBlank()) return emptyList()
        val cacheKey = "${region.uppercase(Locale.ROOT)}:${norm(cleanedArtist.orEmpty())}:${norm(cleanedTitle)}"
        trackCoverUrls[cacheKey]?.let { return it }
        val found = listOfNotNull(
            deezerTrackCoverUrl(cleanedArtist, cleanedTitle)
                ?: appleTrackCoverUrl(cleanedArtist, cleanedTitle, region)
                ?: cleanedArtist?.let { lastFmTrackCoverUrl(it, cleanedTitle) }
                ?: youtubeMusicTrackCoverUrl(cleanedArtist, cleanedTitle, region),
        )
        if (found.isNotEmpty()) trackCoverUrls.putIfAbsent(cacheKey, found)
        return found
    }

    private fun lastFmTrackCoverUrl(artist: String, title: String): String? =
        runCatching { LastFm.trackInfo(artist, title)?.imageUrl }.getOrNull()

    private fun youtubeMusicTrackCoverUrl(artist: String?, title: String, region: String): String? {
        val items = runCatching {
            LinkResolver.youtubeMusicSearch(listOfNotNull(artist, title).joinToString(" "), 10, region)
        }.getOrNull().orEmpty()
        val wantedTitle = norm(cleanTrackTitle(title))
        val wantedArtist = norm(LastFm.cleanArtist(artist.orEmpty()))
        return items.firstOrNull { item ->
            titleMatches(wantedTitle, norm(cleanTrackTitle(item.title))) &&
                (wantedArtist.isBlank() || artistMatches(wantedArtist, norm(LastFm.cleanArtist(item.uploader))))
        }?.thumbUrl
    }

    private fun youtubeMusicArtistCoverUrl(artist: String): String? {
        val items = runCatching {
            LinkResolver.youtubeMusicSearch(artist, 10, Locale.getDefault().country)
        }.getOrNull().orEmpty()
        val wantedArtist = norm(LastFm.cleanArtist(artist))
        return items.firstOrNull {
            artistMatches(wantedArtist, norm(LastFm.cleanArtist(it.uploader)))
        }?.thumbUrl
    }

    private fun deezerTrackCoverUrl(artist: String?, title: String): String? {
        return try {
            val query = listOfNotNull(
                artist?.takeIf { it.isNotBlank() }?.let { "artist:\"$it\"" },
                "track:\"$title\"",
            ).joinToString(" ")
            val q = URLEncoder.encode(query, "UTF-8")
            val bytes = get("https://api.deezer.com/search/track?q=$q&limit=10", 300_000) ?: return null
            val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("data") ?: return null
            val wantedArtist = norm(artist.orEmpty())
            val wantedTitle = norm(cleanTrackTitle(title))
            for (i in 0 until arr.length()) {
                val track = arr.optJSONObject(i) ?: continue
                if (!titleMatches(wantedTitle, norm(cleanTrackTitle(track.optString("title"))))) continue
                val foundArtist = norm(track.optJSONObject("artist")?.optString("name").orEmpty())
                if (wantedArtist.isNotBlank() && !artistMatches(wantedArtist, foundArtist)) continue
                val album = track.optJSONObject("album") ?: continue
                val cover = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
                if (cover.startsWith("https://")) return cover
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun appleTrackCoverUrl(artist: String?, title: String, region: String): String? {
        return try {
            val country = region.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(Locale.ROOT) ?: "US"
            val term = URLEncoder.encode(listOfNotNull(artist, title).joinToString(" "), "UTF-8")
            val bytes = get("https://itunes.apple.com/search?term=$term&entity=song&limit=15&country=$country", 300_000)
                ?: return null
            val arr = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("results") ?: return null
            val wantedArtist = norm(artist.orEmpty())
            val wantedTitle = norm(cleanTrackTitle(title))
            for (i in 0 until arr.length()) {
                val track = arr.optJSONObject(i) ?: continue
                if (!titleMatches(wantedTitle, norm(cleanTrackTitle(track.optString("trackName"))))) continue
                val foundArtist = norm(LastFm.cleanArtist(track.optString("artistName")))
                if (wantedArtist.isNotBlank() && !artistMatches(wantedArtist, foundArtist)) continue
                val cover = track.optString("artworkUrl100")
                if (cover.startsWith("https://")) return cover.replace(Regex("\\d+x\\d+bb"), "600x600bb")
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanTrackTitle(value: String): String = LastFm.cleanTitle(value)
        .replace(Regex("""(?i)\s*[-–—]\s*(official\s*)?(audio|video|lyrics?|visualizer|visualiser)(\s+.*)?$"""), "")
        .trim()

    private fun titleMatches(wanted: String, found: String): Boolean =
        wanted.isNotBlank() && found.isNotBlank() &&
            (wanted == found || (minOf(wanted.length, found.length) >= 5 && (wanted.startsWith(found) || found.startsWith(wanted))))

    private fun artistMatches(wanted: String, found: String): Boolean =
        wanted.isNotBlank() && found.isNotBlank() &&
            (wanted == found || (minOf(wanted.length, found.length) >= 4 && (wanted.startsWith(found) || found.startsWith(wanted))))

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
            CoverPlaceholder(item.name, item.format == "MP3", iconSize)
        }
    }
}

@Composable
fun RemoteCover(url: String?, title: String, isAudio: Boolean, modifier: Modifier, iconSize: Dp) {
    val bmp by produceState<Bitmap?>(null, url) {
        value = url?.let { ThumbCache.get(it) }
    }
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            CoverPlaceholder(title, isAudio, iconSize)
        }
    }
}

@Composable
fun FavoriteCover(entry: PlEntry, modifier: Modifier, iconSize: Dp) {
    val ctx = LocalContext.current
    val localUri = entry.localUri
    if (localUri != null) {
        TrackArtImage(
            HistoryItem(
                name = entry.title,
                format = if (entry.isAudio) "MP3" else "MP4",
                quality = 0,
                sizeBytes = 0,
                timeMillis = 0,
                location = "",
                uri = localUri,
                sourceUrl = entry.pageUrl,
            ),
            modifier,
            iconSize,
        )
    } else {
        val coverUrl by produceState<String?>(entry.thumbUrl, entry.title, entry.uploader, entry.pageUrl, entry.isAudio) {
            val external = if (entry.isAudio && TrackArt.enabled(ctx)) {
                withContext(Dispatchers.IO) {
                    val artist = entry.uploader.takeIf { it.isNotBlank() } ?: TrackArt.artistFromName(entry.title)
                    TrackArt.findCoverUrl(TrackArt.titleFromName(entry.title), artist, Locale.getDefault().country)
                }
            } else {
                null
            }
            value = external ?: entry.thumbUrl ?: TrackArt.youtubeThumbUrl(entry.pageUrl)
        }
        RemoteCover(coverUrl, entry.title, entry.isAudio, modifier, iconSize)
    }
}

@Composable
private fun CoverPlaceholder(title: String, isAudio: Boolean, iconSize: Dp) {
    val cs = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val mark = prettyName(title).trim().firstOrNull()?.uppercaseChar()?.toString()
            ?: if (isAudio) "♪" else "▶"
        Text(mark, color = cs.onPrimary, fontSize = (iconSize.value + 8).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Sym(if (isAudio) R.drawable.ic_queue_music else R.drawable.ic_play, iconSize * 0.6f, tint = cs.onPrimary)
    }
}
