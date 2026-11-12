package com.example.ytsaver

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** One search result in the Search tab. */
data class FeedItem(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbUrl: String?,
    val thumb: Bitmap? = null,
    val site: String = "",          // the website this result comes from
    val isAudio: Boolean = false,   // music (SoundCloud…) rather than video
)

/** Direct stream address(es) of a video plus the HTTP headers the server expects. */
data class StreamInfo(val urls: List<String>, val headers: Map<String, String>)

data class FeedState(
    val query: String = "",
    val loading: Boolean = false,
    val items: List<FeedItem> = emptyList(),
    val message: String = "",
    val searched: Boolean = false,
    // results of every website, searched at the same time
    val siteResults: Map<String, List<FeedItem>> = emptyMap(),
    val siteState: Map<String, Int> = emptyMap(),     // 0 = searching, 1 = done, 2 = failed
    val siteFilter: String? = null,                   // null = all websites together
    val searchedQuery: String = "",                   // what the results on screen were searched for
    val siteLimit: Map<String, Int> = emptyMap(),     // how many results each website was asked for so far
    val siteMore: Map<String, Boolean> = emptyMap(),  // true = this website probably has more ("Show more")
    val loadingMore: Boolean = false,
    // inline preview of one result
    val previewUrl: String? = null,        // result that is being previewed
    val previewStream: StreamInfo? = null,     // direct stream address once resolved
    val previewLoading: Boolean = false,
    val previewFailed: Boolean = false,
)

/** A website the Search tab can search. [prefix] = searched by the download engine; otherwise by a web search limited to [webDomain]. */
class SiteSpec(val label: String, val prefix: String?, val webDomain: String?, val audio: Boolean)

const val LINK_SITE = "Link"

/** All websites' results in one list: one from each website in turn, so no single site floods the top. */
fun interleaveResults(results: Map<String, List<FeedItem>>): List<FeedItem> {
    val lists = SEARCH_SITES.map { results[it.label].orEmpty() } + listOf(results[LINK_SITE].orEmpty())
    val out = mutableListOf<FeedItem>()
    var i = 0
    while (true) {
        var any = false
        for (l in lists) if (i < l.size) { out += l[i]; any = true }
        if (!any) break
        i++
    }
    return out.distinctBy { it.url }
}

object FeedLoader {
    private const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // searches run in their own scope: a slow website is given up on without waiting for its blocking call to return
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun timeoutMs(spec: SiteSpec): Long = when {
        spec.prefix != null -> 35_000L          // the download engine (YouTube, SoundCloud)
        spec.label == "Dailymotion" || spec.label == "Mixcloud" || spec.label == "Internet Archive" -> 15_000L  // their own search APIs: fast
        else -> 25_000L                         // web search (Instagram, Facebook, Bandcamp and the websites the user added)
    }

    /**
     * One website's results, never waiting longer than that website's time limit.
     * Null = the website failed or timed out (the other websites are not affected).
     */
    suspend fun searchSiteTimed(spec: SiteSpec, query: String, limit: Int): List<FeedItem>? {
        val job = searchScope.async {
            try {
                searchSite(spec, query, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val res = withTimeoutOrNull(timeoutMs(spec)) { job.await() }
        if (res == null) job.cancel()
        return res
    }

    /** Blocking: one website's results for a video name. */
    fun searchSite(spec: SiteSpec, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<FeedItem> = when {
        spec.prefix != null -> engineSearch(spec, query, limit)
        spec.label == "Dailymotion" ->
            try { dailymotionSearch(query, limit) } catch (_: Exception) { webSearch(spec, query, limit) }
        spec.label == "Mixcloud" ->
            try { mixcloudSearch(query, limit) } catch (_: Exception) { webSearch(spec, query, limit) }
        spec.label == "Internet Archive" ->
            try { archiveSearch(query, limit) } catch (_: Exception) { webSearch(spec, query, limit) }
        else -> webSearch(spec, query, limit)
    }

    private fun httpText(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", WEB_UA)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Dailymotion has a public search API: quick and reliable, with titles, channels, durations and pictures. */
    private fun dailymotionSearch(query: String, limit: Int): List<FeedItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val fields = URLEncoder.encode("id,title,url,duration,thumbnail_360_url,owner.screenname", "UTF-8")
        val text = httpText("https://api.dailymotion.com/videos?search=$q&fields=$fields&limit=${limit.coerceIn(1, 100)}&sort=relevance", 10_000)
        val list = JSONObject(text).optJSONArray("list") ?: return emptyList()
        val items = mutableListOf<FeedItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { "https://www.dailymotion.com/video/" + o.optString("id") }
            if (o.optString("id").isBlank()) continue
            items += FeedItem(
                url = url,
                title = o.optString("title").ifBlank { url },
                uploader = o.optString("owner.screenname"),
                durationSec = o.optLong("duration"),
                thumbUrl = o.optString("thumbnail_360_url").ifBlank { null },
                site = "Dailymotion",
                isAudio = false,
            )
        }
        return items.distinctBy { it.url }
    }

    /** Mixcloud's public search API (DJ sets, radio shows, podcasts): no key needed. */
    private fun mixcloudSearch(query: String, limit: Int): List<FeedItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = httpText("https://api.mixcloud.com/search/?q=$q&type=cloudcast&limit=${limit.coerceIn(1, 50)}", 10_000)
        val list = JSONObject(text).optJSONArray("data") ?: return emptyList()
        val items = mutableListOf<FeedItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val path = o.optString("url")
            if (!path.startsWith("http")) continue
            items += FeedItem(
                url = path,
                title = o.optString("name").ifBlank { path },
                uploader = o.optJSONObject("user")?.optString("name").orEmpty(),
                durationSec = o.optLong("audio_length"),
                thumbUrl = o.optJSONObject("pictures")?.optString("medium")?.ifBlank { null },
                site = "Mixcloud",
                isAudio = true,
            )
        }
        return items.distinctBy { it.url }
    }

    /** The Internet Archive's public search API: free films, shows, concerts, radio and music. */
    private fun archiveSearch(query: String, limit: Int): List<FeedItem> {
        val clean = query.map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim()
        val q = URLEncoder.encode("($clean) AND mediatype:(movies OR audio)", "UTF-8")
        val url = "https://archive.org/advancedsearch.php?q=$q&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=mediatype" +
            "&sort%5B%5D=downloads+desc&rows=${limit.coerceIn(1, 50)}&page=1&output=json"
        val docs = JSONObject(httpText(url, 10_000)).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val items = mutableListOf<FeedItem>()
        for (i in 0 until docs.length()) {
            val o = docs.optJSONObject(i) ?: continue
            val id = o.optString("identifier")
            if (id.isBlank()) continue
            val creator = o.opt("creator").let { if (it is JSONArray) it.optString(0) else (it as? String).orEmpty() }
            items += FeedItem(
                url = "https://archive.org/details/$id",
                title = (o.opt("title") as? String).orEmpty().ifBlank { id },
                uploader = creator,
                durationSec = 0L,
                thumbUrl = "https://archive.org/services/img/$id",
                site = "Internet Archive",
                isAudio = o.optString("mediatype") == "audio",
            )
        }
        return items.distinctBy { it.url }
    }

    private fun engineSearch(spec: SiteSpec, query: String, limit: Int): List<FeedItem> {
        val req = YoutubeDLRequest("${spec.prefix}$limit:$query").apply {
            addOption("--flat-playlist")
            addOption("--socket-timeout", "10")
            addOption("--print", "%(id)s|||%(title)s|||%(channel,uploader)s|||%(duration)s|||%(webpage_url,url)s|||%(thumbnail)s")
        }
        val out: String = YoutubeDL.getInstance().execute(req, null, null).out ?: ""
        val items = mutableListOf<FeedItem>()
        for (line in out.lines()) {
            val parts = line.split("|||")
            if (parts.size < 6) continue
            val id = parts[0].trim()
            if (id.isEmpty() || id == "NA") continue
            val title = parts.subList(1, parts.size - 4).joinToString("|||")
            val uploader = parts[parts.size - 4].let { if (it == "NA") "" else it }
            val duration = parts[parts.size - 3].trim().toDoubleOrNull()?.toLong() ?: 0L
            var url = parts[parts.size - 2].trim()
            val thumb = parts[parts.size - 1].trim().let { if (it.startsWith("http")) it else null }
            if (!url.startsWith("http")) {
                if (spec.label == "YouTube") url = "https://www.youtube.com/watch?v=$id" else continue
            }
            items += FeedItem(
                url = url,
                title = if (title == "NA") id else title,
                uploader = uploader,
                durationSec = duration,
                thumbUrl = if (spec.label == "YouTube") youtubeThumb(id) else thumb,
                site = spec.label,
                isAudio = spec.audio,
            )
        }
        return items.distinctBy { it.url }
    }

    // ---- sites without a search of their own: a web search limited to that site ----
    private fun htmlText(s: String): String = s.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun unwrapSearchLink(raw: String): String? {
        val u = raw.replace("&amp;", "&")
        val enc = Regex("[?&]uddg=([^&]+)").find(u)?.groupValues?.get(1)
        return when {
            enc != null -> URLDecoder.decode(enc, "UTF-8")
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            else -> null
        }
    }

    private fun isVideoPage(domain: String, url: String): Boolean {
        val u = url.lowercase()
        return when (domain) {
            "tiktok.com" -> u.contains("/video/")
            "instagram.com" -> u.contains("/reel/") || u.contains("/reels/") || u.contains("/p/") || u.contains("/tv/")
            "facebook.com" -> u.contains("/watch") || u.contains("/videos/") || u.contains("/reel/")
            "snapchat.com" -> u.contains("/spotlight/") || u.contains("/t/")
            "dailymotion.com" -> u.contains("/video/")
            "bandcamp.com" -> u.contains("/track/")
            "mixcloud.com" -> Regex("mixcloud\\.com/[^/?#]+/[^/?#]+/?").containsMatchIn(u) && !u.contains("/discover/") && !u.contains("/search/")
            "archive.org" -> u.contains("/details/")
            else -> true
        }
    }

    /** (address, title) pairs from DuckDuckGo's plain-HTML results. */
    private fun duckDuckGo(domain: String, query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode("$query site:$domain", "UTF-8")
        val html = httpText("https://html.duckduckgo.com/html/?q=$q", 9_000)
        val anchor = Regex("<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
        return anchor.findAll(html).mapNotNull { m ->
            val real = unwrapSearchLink(m.groupValues[1]) ?: return@mapNotNull null
            real to htmlText(m.groupValues[2])
        }.toList()
    }

    /** Bing wraps result links in a redirect; the real address is base64 inside the "u" parameter. */
    private fun unwrapBing(raw: String): String? {
        val u = raw.replace("&amp;", "&")
        if (!u.contains("bing.com/ck/")) return if (u.startsWith("http")) u else null
        val enc = Regex("[?&]u=([^&]+)").find(u)?.groupValues?.get(1) ?: return null
        if (!enc.startsWith("a1")) return null
        return try {
            String(Base64.decode(enc.substring(2), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun bing(domain: String, query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode("$query site:$domain", "UTF-8")
        val html = httpText("https://www.bing.com/search?q=$q&setlang=en&count=50", 9_000)
        val anchor = Regex("<li class=\"b_algo\"[\\s\\S]*?<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>")
        return anchor.findAll(html).mapNotNull { m ->
            val real = unwrapBing(m.groupValues[1]) ?: return@mapNotNull null
            real to htmlText(m.groupValues[2])
        }.toList()
    }

    /** Mojeek: an independent search engine that doesn't block phones; result links are plain. */
    private fun mojeek(domain: String, query: String): List<Pair<String, String>> {
        val q = URLEncoder.encode("$query site:$domain", "UTF-8")
        val html = httpText("https://www.mojeek.com/search?q=$q", 9_000)
        val tag = Regex("<a\\s+([^>]*)>([\\s\\S]*?)</a>")
        return tag.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            if (!attrs.contains("class=\"title\"")) return@mapNotNull null
            val href = Regex("href=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
            if (!href.startsWith("http")) return@mapNotNull null
            href to htmlText(m.groupValues[2])
        }.toList()
    }

    /**
     * Instagram and Facebook have no public search of their own, so their videos are looked up through
     * two web search engines (the second only when the first one doesn't give enough). It is slower and
     * less complete than a real search: sometimes few or no results come back.
     */
    private fun webSearch(spec: SiteSpec, query: String, limit: Int): List<FeedItem> {
        val domain = spec.webDomain ?: return emptyList()
        // a result counts only when its host is the site itself or one of its sub-domains
        val found = LinkedHashMap<String, FeedItem>()
        fun add(r: Pair<String, String>) {
            val (url, title) = r
            val h = hostOf(url).removePrefix("www.")
            if (!(h == domain || h.endsWith(".$domain")) || found.containsKey(url)) return
            found[url] = FeedItem(url, title.ifBlank { url }, "", 0L, null, null, spec.label, spec.audio)
        }
        var tried = 0
        var failed = 0
        tried++
        try { duckDuckGo(domain, query).forEach(::add) } catch (_: Exception) { failed++ }
        if (found.values.count { isVideoPage(domain, it.url) } < limit) {
            tried++
            try { bing(domain, query).forEach(::add) } catch (_: Exception) { failed++ }
        }
        if (failed == tried && found.isEmpty()) throw IOException("no search engine answered")
        val all = found.values.toList()
        val videos = all.filter { isVideoPage(domain, it.url) }
        return (if (videos.isNotEmpty()) videos else all).take(limit)
    }

    /**
     * Blocking: every (address, title) pair the two web search engines return for [query] limited to [domain].
     * Unlike [webSearch] nothing is cut to a limit first, so the caller can pick the song pages out of all of them.
     * Throws only when neither engine could be reached.
     */
    fun siteLinks(domain: String, query: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        var failed = 0
        try { duckDuckGo(domain, query).forEach { (u, t) -> out.putIfAbsent(u, t) } } catch (_: Exception) { failed++ }
        try { bing(domain, query).forEach { (u, t) -> out.putIfAbsent(u, t) } } catch (_: Exception) { failed++ }
        try { mojeek(domain, query).forEach { (u, t) -> out.putIfAbsent(u, t) } } catch (_: Exception) { failed++ }
        if (failed == 3 && out.isEmpty()) throw IOException("no search engine answered")
        return out.map { it.key to it.value }.filter {
            val h = hostOf(it.first).removePrefix("www.")
            h == domain || h.endsWith(".$domain")
        }
    }

    /**
     * Blocking: ONE address that the main player can stream (picture and sound together, or the sound alone for music).
     * Used to play playlist links without downloading them.
     */
    fun playableStream(pageUrl: String, audio: Boolean): StreamInfo? {
        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", "15")
            addOption("-f", if (audio) "ba[ext=m4a]/ba/b" else "b[height<=720][ext=mp4]/b[height<=720]/b[ext=mp4]/b")
            addOption("-j")
        }
        val out: String = YoutubeDL.getInstance().execute(req, null, null).out ?: ""
        val line = out.lines().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        val o = JSONObject(line)
        var url = o.optString("url")
        var src: JSONObject = o
        if (!url.startsWith("http")) {
            val rf = o.optJSONArray("requested_formats")
            if (rf != null && rf.length() > 0) { src = rf.getJSONObject(0); url = src.optString("url") }
        }
        if (!url.startsWith("http")) return null
        val h = src.optJSONObject("http_headers") ?: o.optJSONObject("http_headers")
        val headers = mutableMapOf<String, String>()
        h?.keys()?.forEach { k -> headers[k] = h.optString(k) }
        return StreamInfo(listOf(url), headers)
    }

    /** Blocking: run on a background thread. A link shows that video (or playlist); any other text searches YouTube. */
    fun search(query: String): List<FeedItem> {
        val link = parseLinks(query).firstOrNull()
        return if (link != null) fromLink(link) else fromName(query)
    }

    /**
     * Blocking: where to stream a small copy of the video from, for the in-card preview. Nothing is downloaded.
     * YouTube serves picture and sound as two separate streams, so up to two addresses come back
     * (picture first, sound second) and the player plays them together.
     */
    private val streamCache = object : LinkedHashMap<String, Pair<Long, StreamInfo>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, StreamInfo>>?) = size > 30
    }

    fun streamInfo(pageUrl: String): StreamInfo? {
        // stream addresses stay valid for a while: a repeated preview starts at once
        synchronized(streamCache) { streamCache[pageUrl] }?.let { (at, s) -> if (System.currentTimeMillis() - at < 20 * 60_000L) return s }
        val r = streamInfoUncached(pageUrl)
        if (r != null) synchronized(streamCache) { streamCache[pageUrl] = System.currentTimeMillis() to r }
        return r
    }

    private fun streamInfoUncached(pageUrl: String): StreamInfo? {
        val req = YoutubeDLRequest(pageUrl).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", "10")
            addOption("--no-warnings")
            addOption("--no-check-formats")
            addOption(
                "-f",
                "b[height<=480]/bv*[height<=480][ext=mp4]+ba[ext=m4a]/bv*[height<=480]+ba/b/bv*+ba",
            )
            addOption("-j")
        }
        val out: String = YoutubeDL.getInstance().execute(req, null, null).out ?: ""
        val line = out.lines().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        val o = JSONObject(line)
        val parts = mutableListOf<JSONObject>()
        val rf = o.optJSONArray("requested_formats")
        if (rf != null && rf.length() > 0) for (i in 0 until rf.length()) parts += rf.getJSONObject(i) else parts += o
        val urls = parts.map { it.optString("url") }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return null
        val h = parts.first().optJSONObject("http_headers") ?: o.optJSONObject("http_headers")
        val headers = mutableMapOf<String, String>()
        h?.keys()?.forEach { k -> headers[k] = h.optString(k) }
        return StreamInfo(urls.take(2), headers)
    }

    private fun youtubeThumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    private fun fromName(query: String): List<FeedItem> {
        val req = YoutubeDLRequest("ytsearch$DEFAULT_SEARCH_LIMIT:$query").apply {
            addOption("--flat-playlist")
            addOption("--socket-timeout", "15")
            addOption("--print", "%(id)s|||%(title)s|||%(channel)s|||%(duration)s")
        }
        val out: String = YoutubeDL.getInstance().execute(req, null, null).out ?: ""
        val items = mutableListOf<FeedItem>()
        for (line in out.lines()) {
            val parts = line.split("|||")
            if (parts.size < 4) continue
            val id = parts[0].trim()
            if (id.isEmpty() || id == "NA") continue
            val title = parts.subList(1, parts.size - 2).joinToString("|||")
            val channel = parts[parts.size - 2].let { if (it == "NA") "" else it }
            val duration = parts.last().trim().toDoubleOrNull()?.toLong() ?: 0L
            items += FeedItem(
                url = "https://www.youtube.com/watch?v=$id",
                title = if (title == "NA") id else title,
                uploader = channel,
                durationSec = duration,
                thumbUrl = youtubeThumb(id),
            )
        }
        return items.distinctBy { it.url }
    }

    private fun fromLink(link: String): List<FeedItem> {
        if (playlistKind(link) == 1) {
            val pl = PlaylistLoader.load(playlistUrl(link))
            return pl.entries.distinctBy { it.url }.map { e ->
                val id = sourceId(e.url)
                val yt = e.url.contains("youtube.com") || e.url.contains("youtu.be")
                FeedItem(e.url, e.title.ifBlank { e.url }, pl.title, 0L, if (yt) youtubeThumb(id) else null)
            }
        }
        val p = PreviewLoader.load(link)
        return listOf(FeedItem(link, p.title, p.uploader, p.durationSec, p.thumbUrl, p.thumb))
    }
}
