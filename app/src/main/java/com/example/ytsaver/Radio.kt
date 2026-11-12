package com.example.ytsaver

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** One internet radio station. [tenths] is its FM frequency in tenths of a MHz (987 = 98.7) when its name carries one. */
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val votes: Int,
    val ok: Boolean,
    val tenths: Int?,
    val logo: String = "",      // the station's own logo (favicon) from the directory
)

const val FM_MIN = 875      // 87.5 MHz
const val FM_MAX = 1080     // 108.0 MHz

fun fmText(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

/** "Nile FM 104.2", "FM 98,8", "Radio 90 FM", "100.6 MHz" -> frequency in tenths of a MHz; null when the name has none. */
fun parseFrequency(name: String): Int? {
    val decimal = Regex("""(?<![\d.,])(8[7-9]|9\d|10[0-8])\s?[.,]\s?(\d)(?![\d])""").find(name)
    if (decimal != null) {
        val t = decimal.groupValues[1].toInt() * 10 + decimal.groupValues[2].toInt()
        if (t in FM_MIN..FM_MAX) return t
    }
    val whole = Regex("""(?<![\d.,])(8[89]|9\d|10[0-8])(?![\d.,])\s*(?:fm|mhz)|(?:fm|mhz)\s*(8[89]|9\d|10[0-8])(?![\d.,])""", RegexOption.IGNORE_CASE).find(name)
    if (whole != null) {
        val v = (whole.groupValues[1].ifEmpty { whole.groupValues[2] }).toInt() * 10
        if (v in FM_MIN..FM_MAX) return v
    }
    return null
}

/** The station to play at [tenths] (several stations can share one frequency: the best known one wins). */
fun stationAt(list: List<RadioStation>, tenths: Int): RadioStation? =
    list.filter { it.tenths != null && abs(it.tenths - tenths) <= 0 }
        .sortedWith(compareByDescending<RadioStation> { it.ok }.thenByDescending { it.votes }).firstOrNull()

/**
 * The frequencies worth jumping to: where a working, well-known station sits. Falls back to every working
 * station (then every station) when a country has few well-known ones.
 */
fun steadyFrequencies(list: List<RadioStation>): List<Int> {
    val withFreq = list.filter { it.tenths != null }
    val strong = withFreq.filter { it.ok && it.votes >= 10 }
    val pool = when {
        strong.size >= 3 -> strong
        withFreq.any { it.ok } -> withFreq.filter { it.ok }
        else -> withFreq
    }
    return pool.mapNotNull { it.tenths }.distinct().sorted()
}

/** Next (dir = 1) or previous (dir = -1) steady frequency from [from], wrapping around the dial. */
fun jumpFrequency(list: List<RadioStation>, from: Int, dir: Int): Int? {
    val f = steadyFrequencies(list)
    if (f.isEmpty()) return null
    return if (dir > 0) f.firstOrNull { it > from } ?: f.first() else f.lastOrNull { it < from } ?: f.last()
}

/** Internet radio: stations come from the free radio-browser.info directory; playback goes through the app's own player. */
object Radio {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = listOf(
        "https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info",
        "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )

    /** Countries offered in the picker (ISO code). */
    val countries = listOf(
        "EG", "SA", "AE", "KW", "QA", "BH", "OM", "JO", "LB", "SY", "IQ", "PS", "MA", "DZ", "TN", "LY", "SD", "YE",
        "US", "GB", "CA", "AU", "FR", "DE", "ES", "IT", "TR", "NL", "BR", "MX", "IN", "PK", "ID", "RU", "JP", "KR",
    )

    fun countryName(code: String, arabic: Boolean): String =
        Locale("", code).getDisplayCountry(if (arabic) Locale("ar") else Locale.ENGLISH).ifBlank { code }

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()
    private var loadedFor = ""

    fun savedCountry(ctx: Context): String {
        Prefs.str(ctx, "radio_country")?.let { if (it in countries) return it }
        val local = Locale.getDefault().country.uppercase()
        return if (local in countries) local else "EG"
    }

    fun saveCountry(ctx: Context, code: String) = Prefs.putStr(ctx, "radio_country", code)
    fun savedTenths(ctx: Context): Int = (Prefs.str(ctx, "radio_freq")?.toIntOrNull() ?: 980).coerceIn(FM_MIN, FM_MAX)
    fun saveTenths(ctx: Context, t: Int) = Prefs.putStr(ctx, "radio_freq", t.toString())

    /** Loads the stations of a country (cached on the phone, so the list also opens offline). */
    fun load(ctx: Context, country: String, force: Boolean = false) {
        if (!force && loadedFor == country && _stations.value.isNotEmpty()) return
        val app = ctx.applicationContext
        _loading.value = true
        _error.value = ""
        scope.launch {
            val cache = File(app.filesDir, "radio_$country.json")
            var text: String? = null
            for (s in servers) {
                text = runCatching {
                    val c = URL("$s/json/stations/bycountrycodeexact/$country?hidebroken=true&order=votes&reverse=true&limit=400").openConnection() as HttpURLConnection
                    c.connectTimeout = 8_000; c.readTimeout = 15_000
                    c.setRequestProperty("User-Agent", "VibeVault/7.7")
                    if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
                }.getOrNull()
                if (text != null) break
            }
            if (text != null) runCatching { cache.writeText(text) } else if (cache.exists()) text = runCatching { cache.readText() }.getOrNull()
            val list = text?.let { parse(it) }.orEmpty()
            _stations.value = list
            loadedFor = if (list.isNotEmpty()) country else ""
            _error.value = if (list.isEmpty()) L.t("Couldn't load stations. Check your internet connection.", "تعذّر تحميل المحطات. تحقّق من اتصالك بالإنترنت.") else ""
            _loading.value = false
        }
    }

    private fun parse(json: String): List<RadioStation> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            val name = o.optString("name").trim().replace(Regex("\\s+"), " ")
            if (!url.startsWith("http") || name.isEmpty()) null
            else RadioStation(
                id = o.optString("stationuuid", url), name = name, url = url,
                votes = o.optInt("votes"), ok = o.optInt("lastcheckok", 1) == 1, tenths = parseFrequency(name),
                logo = o.optString("favicon").trim().takeIf { it.startsWith("http") }.orEmpty(),
            )
        }.distinctBy { it.url }
    }.getOrDefault(emptyList())

    // ---- favourites: kept on the phone, from any country, for quick access ----
    private val _favorites = MutableStateFlow<List<RadioStation>>(emptyList())
    val favorites: StateFlow<List<RadioStation>> = _favorites.asStateFlow()
    private var favsLoaded = false

    fun loadFavorites(ctx: Context) {
        if (favsLoaded) return
        favsLoaded = true
        _favorites.value = runCatching {
            val arr = JSONArray(Prefs.str(ctx, "radio_favs") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RadioStation(
                    id = o.optString("id"), name = o.optString("name"), url = o.optString("url"),
                    votes = o.optInt("votes"), ok = true, tenths = if (o.has("t")) o.optInt("t") else null,
                    logo = o.optString("lg"),
                )
            }.filter { it.url.startsWith("http") }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(st: RadioStation): Boolean = _favorites.value.any { it.url == st.url }

    fun toggleFavorite(ctx: Context, st: RadioStation) {
        loadFavorites(ctx)
        _favorites.value = if (isFavorite(st)) _favorites.value.filterNot { it.url == st.url } else _favorites.value + st
        val arr = JSONArray()
        _favorites.value.forEach {
            arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("votes", it.votes).put("lg", it.logo).apply { it.tenths?.let { t -> put("t", t) } })
        }
        Prefs.putStr(ctx, "radio_favs", arr.toString())
    }

    // ---- playback (the app's own player: the mini player, the notification and background playback all work) ----

    fun play(ctx: Context, st: RadioStation) {
        // the ".radio" ending is cut off again by the player when it shows the name (it hides file endings)
        val item = HistoryItem(
            name = st.name + ".radio", format = "MP3", quality = 0, sizeBytes = 0L,
            timeMillis = System.currentTimeMillis(), location = "radio", uri = st.url, sourceUrl = "",
        )
        PlayerHolder.playNow(ctx.applicationContext, listOf(item))
        scope.launch {      // polite to the directory: counts the listen so good stations rise in the ranking
            for (s in servers.take(2)) {
                if (runCatching {
                        val c = URL("$s/json/url/${st.id}").openConnection() as HttpURLConnection
                        c.connectTimeout = 5_000; c.readTimeout = 5_000
                        c.setRequestProperty("User-Agent", "VibeVault/7.7")
                        c.responseCode == 200
                    }.getOrDefault(false)) break
            }
        }
    }

    fun stop(ctx: Context) {
        if (isRadioPlaying()) PlayerHolder.clear(ctx.applicationContext)
    }

    fun isRadioPlaying(): Boolean = PlayerHolder.entries.value.firstOrNull()?.location == "radio"
}
