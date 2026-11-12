package com.example.ytsaver

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Every key, host and token of the Music Detector. They are built in on purpose: nothing here is ever asked from the user. */
object DetectorKeys {
    const val ACRCLOUD_HOST = "identify-eu-west-1.acrcloud.com"
    const val ACRCLOUD_ACCESS_KEY = "7a006ee8dca895b1e43d9a5457db151f"
    const val ACRCLOUD_SECRET = "gDpMz3zIxq7nkp1ROkFq326izZSDxHjBmOWWxNMg"

    const val AUDD_TOKEN = ""                 // tokenless mode (10 requests a day)

    const val LASTFM = "61afa1fe2868fb73f1290d774fa8713d"
    const val LASTFM_SHARED_SECRET = ""       // not needed

    const val SHAZAM_RAPID_KEY = "262d3ed8b8msh249bd0400144699p162abfjsn24b02cef09fe"
    const val SHAZAM_RAPID_HOST = "shazam-core.p.rapidapi.com"

    // The same RapidAPI key is used, but each API needs its own subscription on RapidAPI.
    // Recognize endpoints taken from each API's RapidAPI documentation.
    const val SHAZAM_AUDIO_HOST = "shazam-api-audio-recognition-for-songs-music-metadata.p.rapidapi.com"
    const val SHAZAM_AUDIO_URL = "https://shazam-api-audio-recognition-for-songs-music-metadata.p.rapidapi.com/detect_audio_by_post"
    // AudioTag: its own API (audiotag.info), 3 hours of audio a month free, about 900 clips of 12 s. A new key can be made
    // at user.audiotag.info → "API keys" if this one is ever replaced.
    const val AUDIOTAG_URL = "https://audiotag.info/api"
    const val AUDIOTAG_KEY = "8cc6638ddb353109b9903155fc024094"

    const val SONGFINDER_HOST = "songfinder-file-recognition.p.rapidapi.com"
    const val SONGFINDER_URL = "https://songfinder-file-recognition.p.rapidapi.com/api/rapidapi/recognize/file"}

/** A verified song from one of the recognition services. Only title and artist are certain. */
class TrackMatch(
    val title: String, val artist: String, val via: String,
    val album: String = "", val year: String = "", val genres: String = "",
    val durationSec: Int = 0, val coverUrl: String? = null,
    val seeds: List<String> = emptyList(),      // links to this very song on a music service, when the service gave them
)

/**
 * A service refused the request: HTTP 429 / 401 / 403 or the service's own code.
 * [quotaGone] is true only when the service clearly said the free allowance is used up. Otherwise it may be a short
 * rate limit or a refused key, which must not be recorded as "allowance finished".
 */
class ProviderLimit(message: String, val quotaGone: Boolean = false) : Exception(message)

/** What one pass down the chain came to. */
class ChainResult(
    val match: TrackMatch?,
    val answered: Boolean,      // at least one service really looked and found nothing
    val offline: Boolean,       // every service that was tried could not be reached at all
)

/**
 * Phase 1 + 2: the cascading fingerprint chain. The services are asked one after the other and the first real match
 * ends it. A service that is out of allowance, refuses the key, times out or fails in any other way is passed over
 * silently and the next one is asked. Blocking: call it from a background thread.
 */
object RecognitionChain {
    private const val UA = "VibeVault/7.7 (Android music detector)"
    private const val RATE_COOLDOWN_MS = 90_000L                      // a short rate limit (HTTP 429) clears after about a minute
    private const val DAILY_PROBE_MS = 30L * 60 * 1000               // a daily-limited service is asked again after 30 minutes
    private const val LIMIT_COOLDOWN_MS = 6L * 60 * 60 * 1000       // a service that said "limit" is left alone for a while

    /** [minGapMs]: least time between two requests to this service. [minClipSec]: it is only asked with at least this much audio. */
    private class Provider(val name: String, val minGapMs: Long = 0, val minClipSec: Int = 0, val longClip: Boolean = false, val run: (clip: Pcm, wav: ByteArray) -> TrackMatch?)

    private val providers = listOf(
        Provider("ACRCloud") { _, wav -> acrCloud(wav) },          // tier 1: 5,000 upfront
        Provider("Shazam") { _, wav -> shazam(wav) },              // tier 2: 500 a month
        // tier 3: RapidAPI free plan, 300 a month and 1 request a minute: asked once per listening, with 10 s or more of audio
        Provider("SongFinder", minGapMs = 90_000, minClipSec = 10) { _, wav -> songFinder(wav) },
        Provider("Shazam Audio") { _, wav -> shazamAudio(wav) },   // tier 4: RapidAPI free plan, about 100 requests
        // tier 5: 3 free hours of audio a month; asked once per listening with 15 s or more of audio (up to 20 s, 8 kHz mono)
        Provider("AudioTag", minGapMs = 30_000, minClipSec = 15, longClip = true) { _, wav -> audioTag(wav) },
        Provider("AudD") { _, wav -> audd(wav) },                  // tier 6: tokenless, 10 a day
    )

    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val lastCall = HashMap<String, Long>()

    /** True (and remembers the time) if at least [gapMs] has passed since the last request to [name]. */
    @Synchronized
    private fun reserve(name: String, gapMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = maxOf(lastCall[name] ?: 0L, QuotaStore.lastCall(name))
        if (now - last < gapMs) return false
        lastCall[name] = now
        QuotaStore.markCall(name)
        return true
    }

    /**
     * Runs the chain over [pcm]. [runSkip] is shared by the passes of one listening, so a service that already failed
     * in this listening isn't asked again.
     */
    fun find(pcm: Pcm, runSkip: MutableSet<String>): ChainResult {
        val clip = prepare(pcm)
        val wav = wav(clip)
        // a longer clip, in 8 kHz mono, for the services that do better with more audio (AudioTag)
        val longClip by lazy { prepare(pcm, 20) }
        val longWav by lazy { this@RecognitionChain.wav(to8k(longClip)) }
        var answered = false
        var tried = 0
        var unreachable = 0
        synchronized(DetectorStatus) { DetectorStatus.passes += 1 }
        for (p in providers) {
            // engines switched off in Settings → Detector Consumption are never asked
            if (!DetectorPrefs.enabled(p.name)) { DetectorStatus.providers[p.name] = PStatus.Off; continue }
            if ((cooldownUntil[p.name] ?: 0L) > System.currentTimeMillis()) { DetectorStatus.providers[p.name] = PStatus.Limit; continue }
            if (p.name in runSkip) { DetectorStatus.providers[p.name] = PStatus.Unavailable; continue }
            // a limited service whose counted allowance is gone is not asked at all
            val quota = QuotaStore.specs.firstOrNull { it.name == p.name }
            if (quota != null && QuotaStore.blocked(quota)) {
                DetectorStatus.providers[p.name] = PStatus.Limit
                continue
            }
            val useClip = if (p.longClip) longClip else clip
            val useWav = if (p.longClip) longWav else wav
            if (useClip.durationSec < p.minClipSec) continue              // too little audio for this one; later passes have more
            if (p.minGapMs > 0 && !reserve(p.name, p.minGapMs)) continue  // asked too recently for its rate limit
            tried++
            DetectorStatus.providers[p.name] = PStatus.Querying
            try {
                val m = p.run(useClip, useWav)
                answered = true
                QuotaStore.count(p.name)
                if (m != null) {
                    DetectorStatus.providers[p.name] = PStatus.Matched
                    DetectorStatus.matchedBy = p.name
                    return ChainResult(m, true, false)
                }
                DetectorStatus.providers[p.name] = PStatus.NoMatch
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderLimit) {
                Log.w("RecognitionChain", "${p.name} refused: ${e.message} (quotaGone=${e.quotaGone})")
                DetectorStatus.errors[p.name] = e.message.orEmpty().replace(Regex("\\s+"), " ").take(170)
                // a plain 429 is usually a per-minute limit: wait a minute, not hours
                val rateOnly = !e.quotaGone && e.message.orEmpty().startsWith("HTTP 429")
                val daily = QuotaStore.specs.firstOrNull { it.name == p.name }?.period == QPeriod.Day
                cooldownUntil[p.name] = System.currentTimeMillis() + if (rateOnly) RATE_COOLDOWN_MS else if (daily) DAILY_PROBE_MS else LIMIT_COOLDOWN_MS
                // only an explicit "allowance used up" fills the counter; a refused key or a short rate limit does not
                if (e.quotaGone) QuotaStore.markExhausted(p.name)
                QuotaStore.clearSkip(p.name)
                DetectorStatus.providers[p.name] = PStatus.Limit
            } catch (e: Exception) {
                Log.w("RecognitionChain", "${p.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                DetectorStatus.errors[p.name] = "${e.javaClass.simpleName}: ${e.message}".replace(Regex("\\s+"), " ").take(170)
                QuotaStore.clearSkip(p.name)
                runSkip += p.name
                DetectorStatus.providers[p.name] = PStatus.Unavailable
                if (e is UnknownHostException || e is ConnectException) unreachable++
            }
        }
        return ChainResult(null, answered, tried > 0 && unreachable == tried)
    }

    // ---------------------------------------------------------------- audio

    /** What is sent to the services: the last [maxSec] seconds as mono 16 kHz. Small, and what they are tuned for. */
    private fun prepare(p: Pcm, maxSec: Int = 12): Pcm {
        val ch = p.channels.coerceAtLeast(1)
        val frames = p.samples.size / ch
        val keep = minOf(frames, p.sampleRate * maxSec)
        val start = frames - keep
        val outRate = 16_000
        val outN = (keep.toLong() * outRate / p.sampleRate).toInt()
        val ratio = p.sampleRate.toDouble() / outRate
        val out = ShortArray(outN)
        for (i in 0 until outN) {
            val s = start + (i * ratio).toInt()
            val e = (start + ((i + 1) * ratio).toInt()).coerceAtMost(frames).coerceAtLeast(s + 1)
            var sum = 0L
            var cnt = 0
            for (f in s until e) for (c in 0 until ch) { sum += p.samples[f * ch + c]; cnt++ }
            out[i] = if (cnt > 0) (sum / cnt).toShort() else 0
        }
        return Pcm(out, outRate, 1, outN / outRate)
    }

    /** 16 kHz mono to 8 kHz mono (two samples averaged into one): the format AudioTag works with natively. */
    private fun to8k(p: Pcm): Pcm {
        val n = p.samples.size / 2
        val out = ShortArray(n) { i -> ((p.samples[2 * i].toInt() + p.samples[2 * i + 1].toInt()) / 2).toShort() }
        return Pcm(out, 8_000, 1, p.durationSec)
    }

    private fun wav(p: Pcm): ByteArray {
        val dataLen = p.samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1)
        bb.putShort(p.channels.toShort()); bb.putInt(p.sampleRate); bb.putInt(p.sampleRate * p.channels * 2)
        bb.putShort((p.channels * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        bb.asShortBuffer().put(p.samples)
        return bb.array()
    }

    // ---------------------------------------------------------------- http

    /** Sends the request and returns the body. 429 / 401 / 403 mean "limit or key refused"; any other failure is an IOException. */
    private fun send(c: HttpURLConnection, body: ByteArray?, onResponse: ((HttpURLConnection) -> Unit)? = null): String {
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 25_000
            c.setRequestProperty("User-Agent", UA)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            onResponse?.invoke(c)
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429 || code == 401 || code == 403) throw ProviderLimit("HTTP $code ${text.take(200)}")
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return text
        } finally {
            c.disconnect()
        }
    }

    private class Multipart {
        val boundary = "----mediasaver" + System.nanoTime()
        private val out = ByteArrayOutputStream()
        fun field(name: String, value: String) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        fun file(name: String, filename: String, mime: String, data: ByteArray) = apply {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            out.write(data)
            out.write("\r\n".toByteArray())
        }
        fun bytes(): ByteArray { out.write("--$boundary--\r\n".toByteArray()); return out.toByteArray() }
    }

    private fun open(url: String) = URL(url).openConnection() as HttpURLConnection

    // ---------------------------------------------------------------- tier 1: ACRCloud

    private fun acrCloud(wav: ByteArray): TrackMatch? {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(DetectorKeys.ACRCLOUD_SECRET.toByteArray(), "HmacSHA1")) }
        val sig = Base64.encodeToString(
            mac.doFinal("POST\n/v1/identify\n${DetectorKeys.ACRCLOUD_ACCESS_KEY}\naudio\n1\n$ts".toByteArray()), Base64.NO_WRAP)
        val mp = Multipart()
            .field("access_key", DetectorKeys.ACRCLOUD_ACCESS_KEY).field("sample_bytes", wav.size.toString())
            .field("timestamp", ts).field("signature", sig).field("data_type", "audio").field("signature_version", "1")
            .file("sample", "clip.wav", "audio/wav", wav)
        val c = open("https://${DetectorKeys.ACRCLOUD_HOST}/v1/identify")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${mp.boundary}")
        val json = JSONObject(send(c, mp.bytes()))
        val status = json.optJSONObject("status")?.optInt("code", -1) ?: -1
        when (status) {
            0 -> {}
            1001, 2004, 2005 -> return null                           // no result / nothing to fingerprint
            3003 -> throw ProviderLimit("ACRCloud code 3003 (limit exceeded)", quotaGone = true)
            3001, 3002, 3014, 3015 -> throw ProviderLimit("ACRCloud code $status (request or key refused)")
            else -> throw IOException("ACRCloud code $status")
        }
        val r = json.optJSONObject("metadata")?.optJSONArray("music")?.optJSONObject(0) ?: return null
        val title = r.optString("title")
        if (title.isBlank()) return null
        fun names(key: String) = r.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val ext = r.optJSONObject("external_metadata")
        val art = ext?.optJSONObject("spotify")?.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("http") }
            ?: ext?.optJSONObject("deezer")?.optJSONObject("album")?.optString("cover_big")?.takeIf { it.startsWith("http") }
        val seeds = listOfNotNull(
            ext?.optJSONObject("spotify")?.optJSONObject("track")?.optString("id")?.takeIf { it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" },
            ext?.optJSONObject("youtube")?.optString("vid")?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" },
        )
        return TrackMatch(
            title = title, artist = names("artists").joinToString(", "), via = "ACRCloud", seeds = seeds,
            album = r.optJSONObject("album")?.optString("name").orEmpty(), year = r.optString("release_date").take(4),
            genres = names("genres").filter { it != "Music" }.take(3).joinToString(", "),
            durationSec = r.optInt("duration_ms", 0) / 1000, coverUrl = art,
        )
    }

    // ---------------------------------------------------------------- tier 2: Shazam (RapidAPI)

    private fun shazam(wav: ByteArray): TrackMatch? {
        val mp = Multipart().file("file", "audio.wav", "audio/wav", wav)
        val c = open("https://${DetectorKeys.SHAZAM_RAPID_HOST}/v1/tracks/recognize")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${mp.boundary}")
        c.setRequestProperty("X-RapidAPI-Key", DetectorKeys.SHAZAM_RAPID_KEY)
        c.setRequestProperty("X-RapidAPI-Host", DetectorKeys.SHAZAM_RAPID_HOST)
        var planEmpty = false
        val body = try {
            send(c, mp.bytes()) { r ->
                // RapidAPI says how many requests the plan has left
                val left = r.getHeaderField("x-ratelimit-requests-remaining")?.trim()?.toIntOrNull()
                val max = r.getHeaderField("x-ratelimit-requests-limit")?.trim()?.toIntOrNull()
                if (left != null && max != null && max > 0) {
                    QuotaStore.fromServer("Shazam", max, left)
                    if (left == 0) planEmpty = true
                }
            }
        } catch (e: ProviderLimit) {
            // a refusal counts as "allowance gone" only when the plan itself reported nothing left
            throw if (planEmpty) ProviderLimit(e.message.orEmpty(), quotaGone = true) else e
        }
        val t = JSONObject(body).optJSONObject("track") ?: return null
        val title = t.optString("title")
        if (title.isBlank()) return null
        var album = ""
        var year = ""
        val sections = t.optJSONArray("sections")
        if (sections != null) for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i) ?: continue
            if (s.optString("type") != "SONG") continue
            val md = s.optJSONArray("metadata") ?: continue
            for (j in 0 until md.length()) {
                val m = md.optJSONObject(j) ?: continue
                when (m.optString("title")) {
                    "Album" -> album = m.optString("text")
                    "Released" -> year = m.optString("text").takeLast(4).takeIf { y -> y.all { it.isDigit() } }.orEmpty()
                }
            }
        }
        val apple = Regex("https://music\\.apple\\.com/[^\"\\\\\\s]+").find(t.optJSONObject("hub")?.toString().orEmpty().replace("\\/", "/"))?.value
        val img = t.optJSONObject("images")
        val cover = (img?.optString("coverarthq")?.takeIf { it.startsWith("http") } ?: img?.optString("coverart")?.takeIf { it.startsWith("http") })
        return TrackMatch(
            title, t.optString("subtitle"), "Shazam", album = album, year = year,
            genres = t.optJSONObject("genres")?.optString("primary").orEmpty(), coverUrl = cover,
            seeds = listOfNotNull(apple),
        )
    }

    // ---------------------------------------------------------------- tier 3: SongFinder (RapidAPI, file upload)

    private fun songFinder(wav: ByteArray): TrackMatch? {
        val mp = Multipart().file("file", "audio.wav", "audio/wav", wav)
        val c = open(DetectorKeys.SONGFINDER_URL)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${mp.boundary}")
        c.setRequestProperty("X-RapidAPI-Key", DetectorKeys.SHAZAM_RAPID_KEY)
        c.setRequestProperty("X-RapidAPI-Host", DetectorKeys.SONGFINDER_HOST)
        val body = send(c, mp.bytes())
        return parseGeneric(body, "SongFinder")
    }

    // The exact reply shape of the SongFinder service is not known here, so the first JSON object that has a title and an
    // artist is taken (Shazam-style "title" + "subtitle", or "title" + "artist" as text, an object or a list).

    private fun parseGeneric(body: String, via: String): TrackMatch? {
        val root = try {
            JSONTokener(body).nextValue()
        } catch (_: Exception) {
            DetectorStatus.errors[via] = "not JSON: " + body.replace(Regex("\\s+"), " ").take(150)
            return null
        }
        val match = walk(root, via)
        // nothing found: keep the start of the reply, so the progress card shows what the service really said
        if (match == null) DetectorStatus.errors[via] = "reply: " + body.replace(Regex("\\s+"), " ").take(170)
        return match
    }

    private fun jsonText(o: JSONObject, key: String): String = (o.opt(key) as? String).orEmpty().trim()

    private fun artistOf(o: JSONObject): String {
        for (k in listOf("subtitle", "artist", "artists", "artist_name", "author")) {
            val v = o.opt(k)
            if (v is String) {
                if (v.isNotBlank()) return v.trim()
            } else if (v is JSONObject) {
                val n = v.optString("name")
                if (n.isNotBlank()) return n
            } else if (v is JSONArray) {
                val names = ArrayList<String>()
                for (i in 0 until v.length()) {
                    val e = v.opt(i)
                    val n = if (e is String) e else if (e is JSONObject) e.optString("name") else ""
                    if (n.isNotBlank()) names += n
                }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
        }
        return ""
    }

    private fun walk(n: Any?, via: String): TrackMatch? {
        if (n is JSONObject) {
            val title = jsonText(n, "title").ifBlank { jsonText(n, "song") }
            val artist = artistOf(n)
            if (title.isNotBlank() && artist.isNotBlank()) {
                val imgs = n.optJSONObject("images")
                val cover = listOfNotNull(
                    imgs?.let { jsonText(it, "coverarthq") }, imgs?.let { jsonText(it, "coverart") },
                    jsonText(n, "cover"), jsonText(n, "image"), jsonText(n, "artwork"),
                ).firstOrNull { it.startsWith("http") }
                return TrackMatch(title, artist, via, album = jsonText(n, "album"), year = jsonText(n, "release_date").take(4), coverUrl = cover)
            }
            val keys = n.keys()
            while (keys.hasNext()) walk(n.opt(keys.next()), via)?.let { return it }
        } else if (n is JSONArray) {
            for (i in 0 until n.length()) walk(n.opt(i), via)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- tier 4: Shazam Audio (RapidAPI, file upload)

    private fun shazamAudio(wav: ByteArray): TrackMatch? {
        val mp = Multipart().file("audioFile", "audio.wav", "audio/wav", wav)      // field name from the API's Body tab
        val c = open(DetectorKeys.SHAZAM_AUDIO_URL)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${mp.boundary}")
        c.setRequestProperty("X-RapidAPI-Key", DetectorKeys.SHAZAM_RAPID_KEY)
        c.setRequestProperty("X-RapidAPI-Host", DetectorKeys.SHAZAM_AUDIO_HOST)
        var planEmpty = false
        val body = try {
            send(c, mp.bytes()) { r ->
                // RapidAPI says how many requests the plan has left
                val left = r.getHeaderField("x-ratelimit-requests-remaining")?.trim()?.toIntOrNull()
                val max = r.getHeaderField("x-ratelimit-requests-limit")?.trim()?.toIntOrNull()
                if (left != null && max != null && max > 0) {
                    QuotaStore.fromServer("Shazam Audio", max, left)
                    if (left == 0) planEmpty = true
                }
            }
        } catch (e: ProviderLimit) {
            throw if (planEmpty) ProviderLimit(e.message.orEmpty(), quotaGone = true) else e
        }
        // "file not uploaded" = the service didn't find the audio in the form field it expects: a fault, not "no match",
        // so it isn't asked again during this listening
        if (body.contains("uplad", ignoreCase = true)) throw IOException("Shazam Audio: " + body.replace(Regex("\\s+"), " ").take(120))
        return parseGeneric(body, "Shazam Audio")
    }

    // ---------------------------------------------------------------- tier 5: AudioTag (upload, then fetch the result with a token)

    /** One call of the AudioTag API (multipart POST). A reply with success=false becomes the matching exception. */
    private fun audioTagCall(form: Multipart): JSONObject {
        val c = open(DetectorKeys.AUDIOTAG_URL)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${form.boundary}")
        val json = JSONObject(send(c, form.bytes()))
        if (!json.optBoolean("success", false)) {
            val err = (json.opt("error") as? String).orEmpty()
            when {
                err.contains("credit", ignoreCase = true) -> throw ProviderLimit("AudioTag: $err", quotaGone = true)
                err.contains("api key", ignoreCase = true) -> throw ProviderLimit("AudioTag: $err")
                else -> throw IOException("AudioTag: $err")
            }
        }
        return json
    }

    private fun audioTag(wav: ByteArray): TrackMatch? {
        val start = audioTagCall(
            Multipart().field("apikey", DetectorKeys.AUDIOTAG_KEY).field("action", "identify").file("file", "clip.wav", "audio/wav", wav),
        )
        val token = (start.opt("token") as? String).orEmpty()
        if (token.isBlank()) throw IOException("AudioTag: no token in the reply")
        for (i in 1..24) {                                  // the answer is usually ready within a few seconds
            Thread.sleep(500)
            val r = audioTagCall(
                Multipart().field("apikey", DetectorKeys.AUDIOTAG_KEY).field("action", "get_result").field("token", token),
            )
            when (val result = (r.opt("result") as? String).orEmpty()) {
                "wait" -> continue
                "not found" -> return null
                "found" -> return audioTagMatch(r)
                else -> throw IOException("AudioTag: $result")
            }
        }
        throw IOException("AudioTag: no answer after 12 s")
    }

    /** data[0].tracks[0] is a list: [title, artist, album, year]. */
    private fun audioTagMatch(r: JSONObject): TrackMatch? {
        val best = r.optJSONArray("data")?.optJSONObject(0) ?: return null
        val track = best.optJSONArray("tracks")?.optJSONArray(0) ?: return null
        val title = (track.opt(0) as? String).orEmpty().trim()
        if (title.isBlank()) return null
        val artist = (track.opt(1) as? String).orEmpty().trim()
        val album = (track.opt(2) as? String).orEmpty().trim()
        val yr = track.opt(3)?.toString().orEmpty()
        return TrackMatch(title, artist, "AudioTag", album = album, year = if (yr.length == 4 && yr.all { it.isDigit() }) yr else "")
    }

    // ---------------------------------------------------------------- tier 6: AudD, tokenless

    private fun audd(wav: ByteArray): TrackMatch? {
        val mp = Multipart().field("return", "apple_music,spotify").file("file", "clip.wav", "audio/wav", wav)
        val c = open("https://api.audd.io/")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=${mp.boundary}")
        val json = JSONObject(send(c, mp.bytes()))
        if (json.optString("status") == "error") {
            val err = json.optJSONObject("error")
            val errCode = err?.optInt("error_code", 0) ?: 0
            val msg = "AudD error $errCode ${err?.optString("error_message").orEmpty().take(120)}"
            // 901 / 902: the request limit is reached; anything else is a bad request or key, not a spent allowance
            if (errCode == 901 || errCode == 902) throw ProviderLimit(msg, quotaGone = true)
            throw IOException(msg)
        }
        val r = json.optJSONObject("result") ?: return null
        val title = r.optString("title")
        if (title.isBlank()) return null
        val apple = r.optJSONObject("apple_music")
        val art = apple?.optJSONObject("artwork")?.optString("url")?.replace("{w}", "600")?.replace("{h}", "600")?.takeIf { it.startsWith("http") }
        val seeds = listOfNotNull(
            apple?.optString("url")?.takeIf { it.startsWith("http") },
            r.optJSONObject("spotify")?.optJSONObject("external_urls")?.optString("spotify")?.takeIf { it.startsWith("http") },
        )
        return TrackMatch(
            title, r.optString("artist"), "AudD", seeds = seeds, album = r.optString("album"), year = r.optString("release_date").take(4),
            durationSec = (apple?.optLong("durationInMillis") ?: 0L).toInt() / 1000, coverUrl = art,
        )
    }
}
