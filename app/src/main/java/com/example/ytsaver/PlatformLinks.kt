package com.example.ytsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The music services a detected song can be opened in. */
enum class Platform(val title: String, val pkg: String, val icon: Int) {
    // the logos are real project drawables (res/drawable/ic_platform_*.xml), referenced exactly like the Search tab's symbols
    YouTube("YouTube", "com.google.android.youtube", R.drawable.ic_platform_youtube),
    Spotify("Spotify", "com.spotify.music", R.drawable.ic_platform_spotify),
    Anghami("Anghami", "com.anghami", R.drawable.ic_platform_anghami),
    AppleMusic("Apple Music", "com.apple.android.music", R.drawable.ic_platform_applemusic),
    SoundCloud("SoundCloud", "com.soundcloud.android", R.drawable.ic_platform_soundcloud),
    YouTubeMusic("YouTube Music", "com.google.android.apps.youtube.music", R.drawable.ic_platform_youtubemusic),
}

/** The services that get a button, in order (Apple Music is used behind the scenes only). */
val BUTTON_PLATFORMS: List<Platform> = listOf(Platform.YouTube, Platform.Spotify, Platform.Anghami, Platform.SoundCloud, Platform.YouTubeMusic)

/**
 * The song's own page on each service that has it. A service that doesn't have it is simply missing.
 * [unofficial] = the services whose link is not the official release but another upload of the very same recording.
 */
class PlatformLinks(val urls: Map<Platform, String>, val unofficial: Set<Platform> = emptySet(), val notes: String = "") {
    companion object { val NONE = PlatformLinks(emptyMap()) }
}

/**
 * Finds where a recognised song lives on each music service: the links the recognition service itself gave are used
 * first, then the song is looked up on Apple Music (iTunes search) and Odesli (song.link) turns any one link into the
 * links of all the others. A service that still has no official link is then searched for any other upload of the
 * very same recording (same title and artist, same version, same length): that link is marked unofficial.
 * Blocking: call it from a background thread.
 */
object LinkResolver {
    private const val UA = "VibeVault/7.7 (Android music detector)"
    private const val WEB_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private class Cached(val links: PlatformLinks, val at: Long, val complete: Boolean)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val PARTIAL_TTL_MS = 10 * 60_000L
    private val ODESLI_LOCK = Any()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** HTTP status of the last [getJson] call that got an answer, per thread (429 = rate limited). */
    private val lastCode = ThreadLocal<Int>()

    private fun getJson(url: String, timeoutMs: Int = 12_000, ua: String = UA, referer: String? = null): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (referer != null) { c.setRequestProperty("Referer", referer); c.setRequestProperty("Origin", referer.trimEnd('/')) }
            val code = c.responseCode
            lastCode.set(code)
            if (code !in 200..299) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            lastCode.set(-1)
            null
        } finally {
            c.disconnect()
        }
    }

    /** [getJson] that tries again (growing time limit, waiting out a rate limit) before it gives up on one address. */
    private fun getJsonRetry(url: String, attempts: Int = 3, baseTimeoutMs: Int = 7_000, stop: () -> Boolean = { false }, ua: String = UA, referer: String? = null): JSONObject? {
        for (i in 0 until attempts) {
            if (stop()) return null
            val j = getJson(url, baseTimeoutMs + i * 4_000, ua, referer)
            if (j != null) return j
            val code = lastCode.get() ?: -1
            if (code in 400..499 && code != 429 && code != 408) return null      // the address is simply refused: asking again won't help
            try { Thread.sleep(if (code == 429) 2_000L * (i + 1) else 500L * (i + 1)) } catch (_: InterruptedException) { return null }
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The same song, judging by title and artist ONLY (extra words like "(Remastered)" or "feat." are ignored). */
    private fun sameSong(title: String, artist: String, candTitle: String, candArtist: String): Boolean {
        val t1 = norm(LastFm.cleanTitle(title)); val t2 = norm(LastFm.cleanTitle(candTitle))
        if (t1.isEmpty() || t2.isEmpty() || !(t1 == t2 || t1.contains(t2) || t2.contains(t1))) return false
        val a1 = norm(LastFm.cleanArtist(artist)); val a2 = norm(candArtist)
        if (a1.isEmpty() || a2.isEmpty()) return true
        if (a1.contains(a2) || a2.contains(a1)) return true
        // "Artist A, Artist B" against "Artist A & Artist B": the first artist is enough
        val first = norm(LastFm.cleanArtist(artist).split(',', '&', ';').first())
        return first.isNotEmpty() && a2.contains(first)
    }

    /** Exposed for the unit tests. */
    internal fun matchesSong(title: String, artist: String, candTitle: String, candArtist: String) =
        sameSong(title, artist, candTitle, candArtist)

    // ---------------------------------------------------------------- Apple Music (iTunes search, no key)

    /** Storefronts tried in turn: a song missing from the US one is often there in a regional one. */
    private val STORES = listOf("US", "EG", "SA", "AE", "LB", "GB", "FR", "DE", "ES", "TR", "IN", "BR")

    private fun appleQueries(m: TrackMatch): List<String> {
        val t = LastFm.cleanTitle(m.title); val a = LastFm.cleanArtist(m.artist)
        val fa = firstArtist(m.artist)
        return listOf("$a $t", "$t $a", "$fa $t", t, "${m.artist} ${m.title}").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** Apple Music's page of the song, or null when it can't be matched. Several storefronts are searched at the same time. */
    private fun appleMusicUrl(m: TrackMatch, stop: () -> Boolean): String? {
        val queries = appleQueries(m)
        fun one(country: String): String? {
            for ((qi, q) in queries.withIndex()) {
                if (stop()) return null
                // the other wordings are only worth a call in the first two storefronts
                if (qi >= 3 && country != "US" && country != "EG") continue
                val j = getJsonRetry(
                    "https://itunes.apple.com/search?term=${enc(q)}&media=music&entity=song&limit=25&country=$country",
                    attempts = 2, stop = stop,
                ) ?: continue
                val res = j.optJSONArray("results") ?: continue
                for (i in 0 until res.length()) {
                    val r = res.optJSONObject(i) ?: continue
                    if (sameSong(m.title, m.artist, r.optString("trackName"), r.optString("artistName"))) {
                        val u = r.optString("trackViewUrl")
                        if (u.startsWith("http")) return u.substringBefore("&uo=").substringBefore("?uo=")
                    }
                }
            }
            return null
        }
        val pool = Executors.newFixedThreadPool(3)
        try {
            val cs = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            STORES.forEach { c -> cs.submit(Callable<String?> { try { one(c) } catch (_: Exception) { null } }) }
            for (n in STORES.indices) {
                val r = try { cs.take().get() } catch (_: Exception) { null }
                if (r != null) return r
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- Odesli (song.link)

    /** The YouTube Music page of a YouTube video link (YouTube Music plays the same video ids). */
    private fun musicFromYoutube(u: String): String? {
        if (u.contains("music.youtube.com")) return u
        val id = Regex("(?:[?&]v=|youtu\\.be/)([A-Za-z0-9_-]{11})").find(u)?.groupValues?.get(1) ?: return null
        return "https://music.youtube.com/watch?v=$id"
    }

    private fun youtubeFromMusic(u: String) = u.replace("://music.youtube.com/", "://www.youtube.com/")

    /** Why the last Odesli attempt failed (HTTP code, -1 = network/parse error, 0 = nothing came back), shown in the note. */
    private val odesliWhy = ThreadLocal<String>()

    private fun odesli(link: String, stop: () -> Boolean): Map<Platform, String>? {
        val j = getJsonRetry("https://api.song.link/v1-alpha.1/links?url=${enc(link)}&userCountry=US&songIfSingle=true", 3, 8_000, stop)
        if (j == null) {
            odesliWhy.set("api${lastCode.get() ?: 0}")
            // the keyless API is limited to ~10 calls a minute per IP (mobile networks share IPs, so 429 is common):
            // the public song.link page carries the same links and is not limited the same way
            return odesliPage(link, stop)
        }
        val by = j.optJSONObject("linksByPlatform") ?: return emptyMap()
        fun url(key: String) = by.optJSONObject(key)?.optString("url")?.takeIf { it.startsWith("http") }
        val out = LinkedHashMap<Platform, String>()
        (url("youtube") ?: url("youtubeMusic")?.let(::youtubeFromMusic))?.let { out[Platform.YouTube] = it }
        url("spotify")?.let { out[Platform.Spotify] = it }
        url("anghami")?.let { out[Platform.Anghami] = it }
        url("appleMusic")?.let { out[Platform.AppleMusic] = it }
        url("soundcloud")?.let { out[Platform.SoundCloud] = it }
        url("youtubeMusic")?.let { out[Platform.YouTubeMusic] = it }
        return out
    }

    private fun getText(url: String, timeoutMs: Int = 15_000): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", WEB_UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            val code = c.responseCode
            lastCode.set(code)
            if (code !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            lastCode.set(-1)
            null
        } finally {
            c.disconnect()
        }
    }

    /** Fallback: read the service links out of the public song.link page of [link]. */
    private fun odesliPage(link: String, stop: () -> Boolean): Map<Platform, String>? {
        if (stop()) return null
        val html = getText("https://song.link/$link")?.replace("\\/", "/")?.replace("\\u002F", "/")
        if (html == null) { odesliWhy.set(odesliWhy.get() + "/page${lastCode.get() ?: 0}"); return null }
        fun first(re: String) = Regex(re).find(html)?.value
        val out = LinkedHashMap<Platform, String>()
        first("https://open\\.spotify\\.com/track/[A-Za-z0-9]{22}")?.let { out[Platform.Spotify] = it }
        first("https://play\\.anghami\\.com/song/\\d+")?.let { out[Platform.Anghami] = it }
        first("https://music\\.apple\\.com/[A-Za-z]{2}/(?:album|song)/[^\"'\\s<>\\\\]+")?.let { out[Platform.AppleMusic] = it }
        first("https://(?:www\\.)?youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}")?.let { out[Platform.YouTube] = it }
        if (out.isEmpty()) { odesliWhy.set(odesliWhy.get() + "/page-empty"); return null }
        return out
    }

    /** What a link we already hold says about its own service. */
    private fun platformOf(u: String): Platform? = when {
        u.contains("open.spotify.com") -> Platform.Spotify
        u.contains("music.apple.com") || u.contains("itunes.apple.com") -> Platform.AppleMusic
        u.contains("music.youtube.com") -> Platform.YouTubeMusic
        u.contains("youtube.com") || u.contains("youtu.be") -> Platform.YouTube
        u.contains("soundcloud.com") -> Platform.SoundCloud
        u.contains("anghami.com") -> Platform.Anghami
        else -> null
    }

    // ---------------------------------------------------------------- Anghami

    private class ASong(val id: String, val title: String, val artist: String, val durationSec: Int)

    private fun durationOf(o: JSONObject): Int {
        for (k in listOf("duration", "durationSec", "length", "seconds")) {
            val v = o.opt(k)?.toString().orEmpty().trim()
            if (v.isEmpty()) continue
            if (v.contains(":")) { val p = v.split(":").mapNotNull { it.toIntOrNull() }; if (p.size >= 2) return p[p.size - 2] * 60 + p.last() }
            v.toDoubleOrNull()?.let { d -> return if (d > 10_000) (d / 1000).toInt() else d.toInt() }
        }
        return 0
    }

    /** Pulls song entries (id + title + artist + length) out of any JSON shape Anghami's search answers with. */
    private fun anghamiSongs(node: Any?, out: MutableList<ASong>) {
        when (node) {
            is JSONObject -> {
                val id = node.opt("id")?.toString().orEmpty()
                val title = node.optString("title").ifBlank { node.optString("name") }
                val artist = node.optString("artist").ifBlank { node.optString("artistName") }
                    .ifBlank { node.optJSONObject("artist")?.optString("name").orEmpty() }
                val type = node.optString("type")
                if (id.isNotEmpty() && id.all { it.isDigit() } && title.isNotBlank() && artist.isNotBlank() &&
                    (type.isEmpty() || type.equals("song", true))
                ) out += ASong(id, title, artist, durationOf(node))
                node.keys().forEach { k -> anghamiSongs(node.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) anghamiSongs(node.opt(i), out)
        }
    }

    private fun hasArabic(s: String) = s.any { it.code in 0x0600..0x06FF }
    private fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    /**
     * The song in Anghami's list: by title and artist when they are written the same way; when Anghami writes them in
     * Arabic and the detection is in Latin letters, text can't be compared, so the song LENGTH decides (the closest
     * one within 3 seconds, and only when it is clearly alone).
     */
    private fun pickAnghami(m: TrackMatch, songs: List<ASong>): ASong? {
        songs.firstOrNull { sameSong(m.title, m.artist, it.title, it.artist) }?.let { return it }
        if (m.durationSec <= 0) return null
        val latin = hasLatin(m.title) && !hasArabic(m.title)
        val close = songs.filter {
            it.durationSec > 0 && abs(it.durationSec - m.durationSec) <= 3 &&
                (!latin || hasArabic(it.title + it.artist)) &&
                // if the artist is written in Latin letters it must agree; an Arabic artist can't be compared
                (hasArabic(it.artist) || sameSong(m.title, m.artist, m.title, it.artist))
        }.distinctBy { it.id }
        if (close.isEmpty()) return null
        val best = close.minByOrNull { abs(it.durationSec - m.durationSec) } ?: return null
        // several different songs of the same length: the one whose artist matches the first result's artist family is safest
        val sameArtist = close.filter { it.artist == best.artist }
        return if (close.size == 1 || sameArtist.size == close.size) best else null
    }

    /** Anghami's own search, through every address of it known to answer, with a browser-like identity. */
    private fun anghamiUrl(m: TrackMatch, stop: () -> Boolean, note: (String) -> Unit): String? {
        val t = LastFm.cleanTitle(m.title); val a = LastFm.cleanArtist(m.artist)
        val tpl = "https://coussa.anghami.com/rest/v1/GETtabsearch.view?query=%Q&lang=%L&output=jsonhp"
        val all = ArrayList<ASong>()
        for (q in listOf("$a $t", t, a).map { it.trim() }.distinct()) {
            for (lang in listOf("en", "ar")) {
                if (stop()) return null
                val j = getJsonRetry(tpl.replace("%Q", enc(q)).replace("%L", lang), attempts = 2, stop = stop, ua = WEB_UA)
                if (j == null) { note("coussa:${lastCode.get() ?: -1}"); continue }
                val songs = ArrayList<ASong>()
                anghamiSongs(j, songs)
                all += songs
                pickAnghami(m, songs)?.let { return "https://play.anghami.com/song/${it.id}" }
            }
        }
        pickAnghami(m, all)?.let { return "https://play.anghami.com/song/${it.id}" }
        val e = all.firstOrNull()
        note("coussa:ok/${all.size}" + if (e != null) " e=${e.title.take(12)}|${e.artist.take(10)}|${e.durationSec}s want=${m.durationSec}s" else "")
        return null
    }

    // ---------------------------------------------------------------- web search of the service's own site

    private fun isSongPage(p: Platform, u: String): Boolean {
        val l = u.lowercase()
        return when (p) {
            Platform.Anghami -> l.contains("/song/")
            Platform.AppleMusic -> l.contains("/song/") || l.contains("?i=") || l.contains("&i=")
            Platform.Spotify -> l.contains("/track/")
            Platform.SoundCloud -> Regex("^https?://(www\\.)?soundcloud\\.com/[^/?#]+/[^/?#]+/?$").matches(l) &&
                listOf("/sets", "/likes", "/tracks", "/albums", "/reposts", "/popular-tracks", "/following", "/followers", "/discover", "/search", "/you/").none { l.contains(it) }
            else -> false
        }
    }

    /**
     * A search-engine page title looks like "Song - Artist | Anghami" or "Song by Artist on Apple Music": a song page
     * counts when the title AND the artist appear in it as whole words. Nothing else (length, version words) is required.
     */
    private fun webSongUrl(p: Platform, domain: String, m: TrackMatch, stop: () -> Boolean, note: (String) -> Unit = {}): String? {
        val t = LastFm.cleanTitle(m.title); val a = firstArtist(m.artist)
        val titleWords = words(t); val artistWords = words(a)
        if (titleWords.isEmpty()) return null
        for (q in listOf("$a $t", "$t $a song", t).map { it.trim() }.distinct()) {
            if (stop()) return null
            val hits = try { FeedLoader.siteLinks(domain, q) } catch (_: Exception) { note("web:blocked"); continue }
            note("web:${hits.count { isSongPage(p, it.first) }}/${hits.size}")
            for ((u, title) in hits) {
                if (!isSongPage(p, u)) continue
                val all = words(title + " " + u.substringAfterLast("/").replace('-', ' '))
                if (!hasWords(all, titleWords)) continue
                if (artistWords.isNotEmpty() && !hasWords(all, artistWords)) continue
                return u.substringBefore("#")
            }
        }
        return null
    }

    // ---------------------------------------------------------------- resolve

    /**
     * Finds the song on every service, all services at the same time so a slow one never holds up the others:
     * track 1 = Odesli + the other uploads (YouTube, Spotify, SoundCloud: quick, as before), track 2 = Apple Music in depth,
     * track 3 = Anghami in depth (repeated rounds). [onUpdate] is called each time a link is found so the buttons light up
     * one by one; the call returns only when every strategy is spent. [stop] cancels it.
     */
    fun resolve(m: TrackMatch, stop: () -> Boolean = { false }, onUpdate: (PlatformLinks) -> Unit = {}): PlatformLinks {
        val key = norm(m.artist) + "|" + norm(m.title)
        cache[key]?.let { c ->
            if (c.complete || System.currentTimeMillis() - c.at < PARTIAL_TTL_MS) { onUpdate(c.links); return c.links }
        }
        val lock = Any()
        val found = LinkedHashMap<Platform, String>()
        val unofficial = LinkedHashSet<Platform>()
        val notes = ArrayList<String>()
        fun note(s: String) { synchronized(lock) { if (s !in notes) notes += s } }
        fun has(p: Platform) = synchronized(lock) { p in found }
        fun snapshot() = synchronized(lock) { PlatformLinks(LinkedHashMap(found), LinkedHashSet(unofficial), notes.joinToString("  ")) }
        fun add(p: Platform, u: String, other: Boolean = false) {
            // a later OFFICIAL link replaces an earlier unofficial one (the quick searches no longer wait for Odesli)
            val isNew = synchronized(lock) {
                val cur = found[p]
                when {
                    cur == null -> { found[p] = u; if (other) unofficial += p; true }
                    !other && p in unofficial -> { found[p] = u; unofficial -= p; true }
                    else -> false
                }
            }
            if (isNew) onUpdate(snapshot())
            // YouTube Music plays the same video: its link comes straight from the YouTube one
            if (isNew && p == Platform.YouTube && !has(Platform.YouTubeMusic)) {
                musicFromYoutube(u)?.let { mu -> add(Platform.YouTubeMusic, mu, other = other) }
            } else if (isNew && p == Platform.YouTube) {
                // YouTube just became official: so does the YouTube Music link that was built from the unofficial one
                if (!other && synchronized(lock) { Platform.YouTubeMusic in unofficial }) musicFromYoutube(u)?.let { mu -> add(Platform.YouTubeMusic, mu) }
            }
        }

        // the links the recognition service gave are exact: keep them whatever else happens
        for (s in m.seeds) platformOf(s)?.let { add(it, s) }
        val askedOdesli = ConcurrentHashMap.newKeySet<String>()
        val odesliDone = java.util.concurrent.atomic.AtomicBoolean(false)
        // Odesli allows only a few calls a minute: one at a time, and once it has answered it knows every service, so no more calls
        fun odesliFromAll() {
            synchronized(ODESLI_LOCK) {
                // Odesli understands Spotify / Apple Music links best, YouTube ones least: ask in that order
                fun rank(u: String) = when (platformOf(u)) { Platform.Spotify -> 0; Platform.AppleMusic -> 1; Platform.Anghami -> 2; Platform.SoundCloud -> 3; else -> 4 }
                for (u in synchronized(lock) { found.values.toList() }.sortedBy(::rank)) {
                    if (stop() || odesliDone.get()) return
                    if (!askedOdesli.add(u)) continue
                    try {
                        val r = odesli(u, stop)
                        if (r != null) { odesliDone.set(true); note("odesli:ok/${r.size}"); r.forEach { (p, l) -> add(p, l) } }
                        else {
                            val why = odesliWhy.get() ?: "?"
                            note("odesli:fail/$why")
                            // a rate limit or network error says nothing about this link: let a later round ask again
                            if (why.startsWith("api429") || why.startsWith("api-1") || why.startsWith("api5")) askedOdesli.remove(u)
                        }
                    } catch (e: Exception) { note("odesli:err/${e.javaClass.simpleName}") }
                }
            }
        }

        val alts = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()   // the song's names on other services
        val pool = Executors.newFixedThreadPool(5)
        try {
            val tasks = listOf(
                // track 1: what worked instantly in the old version, unchanged in speed
                Callable<Unit> {
                    if (synchronized(lock) { found.isEmpty() }) {
                        try {
                            val q = LastFm.cleanArtist(m.artist) + " " + LastFm.cleanTitle(m.title)
                            val r = getJsonRetry("https://itunes.apple.com/search?term=${enc(q)}&media=music&entity=song&limit=25", 2, 8_000, stop)
                                ?.optJSONArray("results")
                            if (r != null) for (i in 0 until r.length()) {
                                val o = r.optJSONObject(i) ?: continue
                                if (sameSong(m.title, m.artist, o.optString("trackName"), o.optString("artistName"))) {
                                    val u = o.optString("trackViewUrl")
                                    if (u.startsWith("http")) { add(Platform.AppleMusic, u.substringBefore("&uo=").substringBefore("?uo=")); break }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                    odesliFromAll()
                    if (!has(Platform.Spotify) && !stop()) {
                        try { unofficialLinks(m, listOf(Platform.Spotify)).forEach { (p, u) -> add(p, u, other = true) } } catch (_: Exception) { }
                    }
                    if (!has(Platform.Spotify) && !stop()) {
                        try { webSongUrl(Platform.Spotify, "open.spotify.com", m, stop) { note("Spotify $it") }?.let { add(Platform.Spotify, it) } } catch (_: Exception) { }
                    }
                    // Odesli may have been rate limited a moment ago: one more try before giving up on Spotify
                    if (!has(Platform.Spotify) && !stop()) {
                        try { Thread.sleep(2_500L) } catch (_: InterruptedException) { }
                        odesliFromAll()
                    }
                },
                // track 2: Apple Music through every storefront and wording
                Callable<Unit> {
                    if (!has(Platform.AppleMusic)) {
                        try { appleMusicUrl(m, stop)?.let { add(Platform.AppleMusic, it) } } catch (_: Exception) { }
                    }
                    if (!has(Platform.AppleMusic) && !stop()) {
                        try { webSongUrl(Platform.AppleMusic, "music.apple.com", m, stop)?.let { add(Platform.AppleMusic, it) } } catch (_: Exception) { }
                    }
                },
                // track 1b: other uploads of the same recording on YouTube / SoundCloud, started at once (an official link
                // from Odesli replaces them when it arrives)
                Callable<Unit> {
                    // YouTube Music's own search first: one quick call that gives the official upload for YouTube AND YouTube Music
                    if ((!has(Platform.YouTube) || !has(Platform.YouTubeMusic) || Platform.YouTubeMusic in synchronized(lock) { unofficial.toSet() }) && !stop()) {
                        try {
                            ytMusicId(m, stop) { tt, aa -> alts += tt to aa }?.let { id ->
                                add(Platform.YouTubeMusic, "https://music.youtube.com/watch?v=$id")
                                add(Platform.YouTube, "https://www.youtube.com/watch?v=$id")
                                note("ytm:ok")
                            }
                        } catch (_: Exception) { }
                    }
                    val missing = listOf(Platform.YouTube).filter { !has(it) }
                    if (missing.isNotEmpty() && !stop()) {
                        try { unofficialLinks(m, missing) { p, u -> add(p, u, other = true) } } catch (_: Exception) { }
                    }
                },
                // track 5: SoundCloud, with its backups, started at once
                Callable<Unit> {
                    if (!has(Platform.SoundCloud) && !stop()) {
                        try { soundcloudRace(m, stop, { alts.toList() }) { note(it) }?.let { add(Platform.SoundCloud, it, other = true) } } catch (_: Exception) { }
                    }
                },
                // track 3: Anghami, round after round (each round also asks Odesli about any link found meanwhile)
                Callable<Unit> {
                    for (round in 0 until 3) {
                        if (stop() || has(Platform.Anghami)) break
                        val tag = if (round == 0) "" else "r${round + 1} "
                        try { anghamiUrl(m, stop) { note("Anghami $tag$it") }?.let { add(Platform.Anghami, it) } } catch (_: Exception) { }
                        if (has(Platform.Anghami) || stop()) break
                        try { webSongUrl(Platform.Anghami, "anghami.com", m, stop) { note("Anghami $tag$it") }?.let { add(Platform.Anghami, it) } } catch (_: Exception) { }
                        if (has(Platform.Anghami) || stop()) break
                        odesliFromAll()
                        if (has(Platform.Anghami)) break
                        try { Thread.sleep(1_200L) } catch (_: InterruptedException) { break }
                    }
                },
            )
            pool.invokeAll(tasks)
        } finally {
            pool.shutdownNow()
        }
        val links = snapshot()
        val complete = has(Platform.YouTubeMusic) && has(Platform.Anghami)
        if (!stop() && links.urls.isNotEmpty()) cache[key] = Cached(links, System.currentTimeMillis(), complete)
        return links
    }

    // ---------------------------------------------------------------- unofficial links (the same recording, another upload)

    private class Cand(
        val url: String,
        val title: String,
        val uploader: String,
        val durationSec: Long,
        val thumbUrl: String? = null,
    )

    /** Words that make a different recording of a song (live, cover, remix...). Both sides must have the same ones. */
    private val VARIANT = Regex(
        "\\b(live|cover|remix|karaoke|instrumental|acoustic|slowed|reverb|sped ?up|nightcore|8d|tribute|remake|reprise|mashup|demo|piano|lo-?fi)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun variants(t: String): Set<String> =
        VARIANT.findAll(t).map { it.value.lowercase().replace(" ", "") }.toSet()

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** [part] appears in [all] as whole words in the same order ("up" does not match "upside"). */
    private fun hasWords(all: List<String>, part: List<String>): Boolean {
        if (part.isEmpty()) return false
        if (part.size > all.size) return false
        for (i in 0..all.size - part.size) if (all.subList(i, i + part.size) == part) return true
        return false
    }

    private fun firstArtist(artist: String) = LastFm.cleanArtist(artist).split(',', '&', ';').first().trim()

    /** True only when the candidate is this very recording: same title, same artist, same version, same length. */
    private fun exactFile(m: TrackMatch, c: Cand): Boolean {
        val titleWords = words(LastFm.cleanTitle(m.title))
        if (!hasWords(words(c.title), titleWords)) return false
        val artistWords = words(firstArtist(m.artist))
        if (artistWords.isNotEmpty() && !hasWords(words(c.title + " " + c.uploader), artistWords)) return false
        if (variants(c.title) != variants(m.title)) return false
        // the length is compared whenever both sides know it
        if (m.durationSec > 0 && c.durationSec > 0 && abs(m.durationSec - c.durationSec) > 4) return false
        return true
    }

    // ---- SoundCloud, directly: one quick web call instead of starting the download engine (which takes seconds)

    @Volatile private var scClientId: String? = null
    @Volatile private var scCtx: Context? = null
    private val scWarming = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun saveScId(id: String?) { scClientId = id; scCtx?.let { runCatching { Prefs.putStr(it, "sc_client_id", id) } } }

    /** Reads SoundCloud's public client id from its website (the script files are fetched at the same time). */
    private fun fetchSoundcloudClientId(): String? {
        val html = getText("https://soundcloud.com/", 6_000) ?: return null
        val scripts = Regex("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"").findAll(html).map { it.groupValues[1] }.toList().asReversed().take(6)
        if (scripts.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(scripts.size)
        try {
            val cs = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            scripts.forEach { u ->
                cs.submit(Callable<String?> {
                    val js = getText(u, 8_000)
                    if (js == null) null else Regex("client_id[:=]\"([A-Za-z0-9]{32})\"").find(js)?.groupValues?.get(1)
                })
            }
            for (n in scripts.indices) {
                val id = try { cs.take().get() } catch (_: Exception) { null }
                if (id != null) return id
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** The id is remembered on the phone, so only the very first detection ever has to read it. */
    private val scLock = Any()

    private fun soundcloudClientId(): String? {
        scClientId?.let { return it }
        synchronized(scLock) {          // several searches at once must not each read the website
            scClientId?.let { return it }
            scCtx?.let { c -> Prefs.str(c, "sc_client_id")?.takeIf { it.length == 32 }?.let { scClientId = it; return it } }
            val id = fetchSoundcloudClientId() ?: return null
            saveScId(id)
            return id
        }
    }

    /** Call when listening starts: SoundCloud's id is read in the background while the song is still being recognised. */
    fun warmUp(ctx: Context) {
        scCtx = ctx.applicationContext
        if (scClientId != null || !scWarming.compareAndSet(false, true)) return
        Thread { try { soundcloudClientId() } catch (_: Exception) { } finally { scWarming.set(false) } }.start()
    }

    /** SoundCloud's track search, or null when it could not be asked (the caller then uses the slow way). */
    private fun soundcloudFast(query: String, limit: Int): List<Cand>? {
        for (attempt in 0 until 2) {
            val id = soundcloudClientId() ?: return null
            val j = getJson("https://api-v2.soundcloud.com/search/tracks?q=${enc(query)}&client_id=$id&limit=$limit&offset=0", 8_000, WEB_UA, "https://soundcloud.com/")
            if (j == null) {
                if (lastCode.get() in listOf(401, 403)) { saveScId(null); continue }   // the id expired: read a new one
                return null
            }
            val arr = j.optJSONArray("collection") ?: return emptyList()
            val out = ArrayList<Cand>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("permalink_url")
                if (!u.startsWith("http")) continue
                out += Cand(u, o.optString("title"), o.optJSONObject("user")?.optString("username").orEmpty(), (o.optLong("full_duration", o.optLong("duration", 0L))) / 1000)
            }
            return out
        }
        return null
    }

    // ---- YouTube Music's own search (a backup for Odesli): its song results are the official uploads, with a length

    private fun postJson(url: String, body: String, timeoutMs: Int = 8_000): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", WEB_UA)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Origin", "https://music.youtube.com")
            c.setRequestProperty("Referer", "https://music.youtube.com/")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            lastCode.set(code)
            if (code !in 200..299) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            lastCode.set(-1)
            null
        } finally {
            c.disconnect()
        }
    }

    /** Song / video rows of a YouTube Music search answer, wherever they sit in it. */
    private fun ytmItems(node: Any?, out: MutableList<Cand>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                    val vid = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                    val cols = r.optJSONArray("flexColumns")?.let { a ->
                        (0 until a.length()).map { i ->
                            val runs = a.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")?.optJSONArray("runs")
                            if (runs == null) "" else (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
                        }
                    }.orEmpty()
                    if (vid.length == 11 && cols.size >= 2 && cols[0].isNotBlank()) {
                        val d = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})\\s*$").find(cols[1].trim())
                        val sec = if (d == null) 0L else (d.groupValues[1].toLongOrNull() ?: 0L) * 3600 + d.groupValues[2].toLong() * 60 + d.groupValues[3].toLong()
                        val thumbs = r.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
                            ?.optString("url")?.takeIf { it.startsWith("http") }
                        out += Cand("https://music.youtube.com/watch?v=$vid", cols[0], cols[1], sec, thumb)
                    }
                }
                node.keys().forEach { k -> ytmItems(node.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) ytmItems(node.opt(i), out)
        }
    }

    /** The title and artist as YouTube Music writes them ("Song • Artist • Album • 3:29"). */
    private fun ytmNames(c: Cand): Pair<String, String>? {
        val parts = c.uploader.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.firstOrNull()?.lowercase() in listOf("song", "video")) parts.removeAt(0)
        val artist = parts.firstOrNull() ?: return null
        return c.title to artist
    }

    fun youtubeMusicSearch(query: String, limit: Int, region: String): List<FeedItem>? {
        val locale = Locale.getDefault()
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20241023.01.00")
                        .put("hl", locale.language.ifBlank { "en" })
                        .put("gl", region.uppercase(Locale.ROOT)),
                ),
            )
            .put("query", query)
            .toString()
        val response = postJson(
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false&key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
            body,
        ) ?: return null
        val candidates = ArrayList<Cand>()
        ytmItems(response, candidates)
        return candidates.distinctBy { it.url }.take(limit.coerceAtLeast(1)).mapNotNull { item ->
            val id = Regex("v=([A-Za-z0-9_-]{11})").find(item.url)?.groupValues?.get(1) ?: return@mapNotNull null
            FeedItem(
                url = item.url,
                title = item.title,
                uploader = ytmNames(item)?.second ?: item.uploader.substringBefore(" • "),
                durationSec = item.durationSec,
                thumbUrl = item.thumbUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                site = "YouTube Music",
                isAudio = true,
            )
        }
    }

    /** The video id of this very recording on YouTube Music, or null (not found, or the service could not be asked). */
    private fun ytMusicId(m: TrackMatch, stop: () -> Boolean, onNames: (String, String) -> Unit = { _, _ -> }): String? {
        val t = LastFm.cleanTitle(m.title); val a = LastFm.cleanArtist(m.artist)
        for (q in listOf("$a $t", "${firstArtist(m.artist)} $t").map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            if (stop()) return null
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20241023.01.00").put("hl", "en").put("gl", "US")))
                .put("query", q).toString()
            val j = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false&key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30", body) ?: continue
            val list = ArrayList<Cand>()
            ytmItems(j, list)
            val hit = bestOf(m, list)?.also { u -> list.firstOrNull { it.url == u }?.let { c -> ytmNames(c)?.let { (tt, aa) -> onNames(tt, aa) } } } ?: run {
                // the artist may be written in other letters there: the same title and the same length (within 2 s) is enough
                val tw = words(t)
                list.firstOrNull { c ->
                    m.durationSec > 0 && c.durationSec > 0 && abs(m.durationSec - c.durationSec) <= 2 &&
                        hasWords(words(c.title), tw) && variants(c.title) == variants(m.title)
                }?.also { c -> ytmNames(c)?.let { (tt, aa) -> onNames(tt, aa) } }?.url
            }
            if (hit != null) return Regex("v=([A-Za-z0-9_-]{11})").find(hit)?.groupValues?.get(1)
        }
        return null
    }

    private fun candidates(p: Platform, query: String, limit: Int = 15): List<Cand> {
        val spec = when (p) {
            Platform.YouTube -> SiteSpec("YouTube", "ytsearch", null, false)
            Platform.SoundCloud -> SiteSpec("SoundCloud", "scsearch", null, true)
            Platform.Spotify -> SiteSpec("Spotify", null, "open.spotify.com", true)
            Platform.Anghami -> SiteSpec("Anghami", null, "anghami.com", true)
            Platform.AppleMusic -> SiteSpec("Apple Music", null, "music.apple.com", true)
            Platform.YouTubeMusic -> SiteSpec("YouTube", "ytsearch", null, false)
        }
        return FeedLoader.searchSite(spec, query, limit).filter {
            val u = it.url.lowercase()
            when (p) {
                Platform.YouTube -> u.contains("watch?v=") || u.contains("youtu.be/")
                Platform.SoundCloud -> u.contains("soundcloud.com/") && !u.contains("/sets/")
                Platform.Spotify -> u.contains("/track/")
                Platform.Anghami -> u.contains("/song/")
                Platform.AppleMusic -> u.contains("/song/") || u.contains("?i=") || u.contains("&i=")
                Platform.YouTubeMusic -> u.contains("watch?v=")
            }
        }.map { Cand(it.url, it.title, it.uploader, it.durationSec) }
    }

    /** The best candidate that is the same recording: closest in length, and preferably from the artist's own or a topic channel. */
    private fun bestOf(m: TrackMatch, list: List<Cand>): String? {
        val artist = norm(firstArtist(m.artist))
        fun cost(c: Cand): Int {
            val gap = if (m.durationSec > 0 && c.durationSec > 0) abs(m.durationSec - c.durationSec).toInt() else 5
            val up = norm(c.uploader)
            val own = artist.isNotEmpty() && up.contains(artist) || up.endsWith("topic") || up.contains("vevo")
            return gap + if (own) 0 else 3
        }
        return list.filter { exactFile(m, it) }.minByOrNull { cost(it) }?.url
    }

    /** The first result whose title has [title] and whose title or uploader has the artist's name. */
    private fun firstSameTitleArtist(title: String, artist: String, list: List<Cand>): String? {
        val tw = words(LastFm.cleanTitle(title))
        if (tw.isEmpty()) return null
        val aw = words(firstArtist(artist))
        return list.firstOrNull { c ->
            hasWords(words(c.title), tw) && (aw.isEmpty() || hasWords(words(c.title + " " + c.uploader), aw))
        }?.url
    }

    /** The first non-null answer of [tasks], all run at the same time; the rest are cancelled. */
    private fun raceFirst(tasks: List<Callable<String?>>, stop: () -> Boolean): String? {
        if (tasks.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val cs = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            tasks.forEach { cs.submit(it) }
            for (n in tasks.indices) {
                if (stop()) return null
                val r = try { cs.take().get() } catch (_: Exception) { null }
                if (r != null) return r
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * SoundCloud, with backups. Wave 1: SoundCloud's own search with three wordings at once (first answer that has the
     * title and the artist wins). Wave 2, only when wave 1 found nothing, all at once: the same search with the names
     * YouTube Music / Anghami use for the song (often in Arabic letters), a web search of soundcloud.com, and, when the
     * quick search could not be asked at all, the slow download-engine search.
     */
    private fun soundcloudRace(m: TrackMatch, stop: () -> Boolean, alts: () -> List<Pair<String, String>>, note: (String) -> Unit): String? {
        val t = LastFm.cleanTitle(m.title); val a = LastFm.cleanArtist(m.artist)
        fun matchIn(list: List<Cand>): String? =
            (listOf(m.title to m.artist) + alts()).firstNotNullOfOrNull { (tt, aa) -> firstSameTitleArtist(tt, aa, list) }
        val directFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun direct(q: String) = Callable<String?> {
            val l = soundcloudFast(q, 10)
            if (l == null) { directFailed.set(true); null } else matchIn(l)
        }
        val q1 = listOf("$a $t", t, "${firstArtist(m.artist)} $t").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        raceFirst(q1.map { direct(it) }, stop)?.let { return it }
        if (stop()) return null
        val tasks = ArrayList<Callable<String?>>()
        alts().map { (tt, aa) -> "$aa $tt".trim() }.filter { it.isNotBlank() && it !in q1 }.distinct().forEach { tasks += direct(it) }
        tasks += Callable<String?> { webSongUrl(Platform.SoundCloud, "soundcloud.com", m, stop) }
        if (directFailed.get()) { note("sc:slow"); tasks += Callable<String?> { matchIn(candidates(Platform.SoundCloud, "$a $t".trim(), 8)) } }
        return raceFirst(tasks, stop)
    }

    /** One search per missing service, all at the same time and never longer than 60 seconds. */
    private fun unofficialLinks(m: TrackMatch, missing: List<Platform>, onFound: (Platform, String) -> Unit = { _, _ -> }): Map<Platform, String> {
        val query = (LastFm.cleanArtist(m.artist) + " " + LastFm.cleanTitle(m.title)).trim()
        if (query.isBlank()) return emptyMap()
        val pool = Executors.newFixedThreadPool(missing.size)
        try {
            val tasks = missing.map { p ->
                Callable<Pair<Platform, String?>> {
                    // SoundCloud's search is slow and its first few results are almost always the song: a short list first,
                    // a longer one only when that had no match. Each link is reported the moment it is found.
                    val r = try {
                        bestOf(m, candidates(p, query))
                    } catch (_: Exception) { null }
                    if (r != null) onFound(p, r)
                    p to r
                }
            }
            val out = LinkedHashMap<Platform, String>()
            for (f in pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                if (f.isCancelled) continue
                try { f.get().let { (p, u) -> if (u != null) out[p] = u } } catch (_: Exception) { }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------------------------------------------------------------- opening

    private fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun view(ctx: Context, url: String, pkg: String?): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        ctx.startActivity(i)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    /**
     * Opens the song in the service's own app. When the app isn't installed the Google Play page of the app opens
     * instead (the Play Store app; its web page only if there is no Play Store).
     */
    fun open(ctx: Context, p: Platform, url: String) {
        // try the service's own app first: it works even when Android hides the package from the installed-apps check
        if (view(ctx, url, p.pkg)) return
        if (installed(ctx, p.pkg)) {
            // installed but it doesn't take this address form: let Android pick any app that does
            if (view(ctx, url, null)) return
            toast(ctx, L.t("Couldn't open ${p.title}.", "تعذّر فتح ${p.title}."))
            return
        }
        toast(ctx, L.t("${p.title} isn't installed. Opening Google Play…", "${p.title} غير مثبّت. جارٍ فتح Google Play…"))
        if (!view(ctx, "market://details?id=${p.pkg}", "com.android.vending") &&
            !view(ctx, "market://details?id=${p.pkg}", null)
        ) view(ctx, "https://play.google.com/store/apps/details?id=${p.pkg}", null)
    }
}

/**
 * One round button per music service. A button is live only when the song's page on that service was found;
 * the others stay dimmed and can't be tapped. While the links are still being found everything is dimmed with a small note.
 */
@Composable
fun PlatformButtons(links: PlatformLinks?, loading: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BUTTON_PLATFORMS.forEach { p ->
                val url = links?.urls?.get(p)
                val on = url != null
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // the service's logo: a project drawable referenced directly, the same way the Search tab's symbols are
                    Image(
                        painterResource(p.icon), p.title,
                        modifier = Modifier.size(52.dp).clip(CircleShape).alpha(if (on) 1f else 0.3f)
                            .then(if (on && url != null) Modifier.clickable { LinkResolver.open(ctx, p, url) } else Modifier),
                        colorFilter = if (on) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    )
                    Text(p.title, fontSize = 10.sp, color = if (on) MaterialTheme.colorScheme.onSurface else Muted, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Text(tr("Finding the song on music apps…", "جارٍ البحث عن الأغنية في تطبيقات الموسيقى…"), color = Muted, fontSize = 11.sp)
            }
        }
        if (!loading && links != null && links.urls.isNotEmpty() && BUTTON_PLATFORMS.any { it !in links.urls } && links.notes.isNotBlank()) {
            Text(links.notes.take(160), color = Muted, fontSize = 9.sp, textAlign = TextAlign.Center)
        }
        if (!loading && links != null && links.urls.isEmpty()) {
            Text(tr("This song wasn't found on the music apps.", "لم يتم العثور على الأغنية في تطبيقات الموسيقى."), color = Muted, fontSize = 11.sp)
        }
    }
}
