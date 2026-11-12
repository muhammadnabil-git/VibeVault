package com.example.ytsaver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** One website shortcut of the Search tab. */
data class Shortcut(val name: String, val url: String)

const val MAX_SHORTCUTS = 5

/** Search tab: how many results each website returns at first (Settings → Behavior); "Show more" adds this many again. */
val SEARCH_LIMITS = listOf(10, 15, 25, 50)
const val DEFAULT_SEARCH_LIMIT = 15

/** The built-in websites of the Search tab. */
val FACTORY_SHORTCUTS = listOf(
    Shortcut("YouTube", "https://m.youtube.com"),
    Shortcut("Shazam", "https://www.shazam.com"),
    Shortcut("Spotify", "https://open.spotify.com"),
    Shortcut("Dailymotion", "https://www.dailymotion.com"),
    Shortcut("SoundCloud", "https://soundcloud.com"),
)

/** The built-in list of versions 7.3 to 7.4.9 (Instagram and Anghami instead of Shazam and Dailymotion). Only used to recognise untouched defaults. */
private val FACTORY_SHORTCUTS_V3 = listOf(
    Shortcut("YouTube", "https://m.youtube.com"),
    Shortcut("Instagram", "https://www.instagram.com"),
    Shortcut("Spotify", "https://open.spotify.com"),
    Shortcut("Anghami", "https://play.anghami.com"),
    Shortcut("SoundCloud", "https://soundcloud.com"),
)

/** The built-in list of version 7.4 (TikTok and Facebook instead of Spotify and Anghami). Only used to recognise untouched defaults. */
private val FACTORY_SHORTCUTS_V2 = listOf(
    Shortcut("YouTube", "https://m.youtube.com"),
    Shortcut("Instagram", "https://www.instagram.com"),
    Shortcut("TikTok", "https://www.tiktok.com"),
    Shortcut("Facebook", "https://m.facebook.com"),
    Shortcut("SoundCloud", "https://soundcloud.com"),
)

/** The built-in list of the previous version (replaced above). Only used to recognise untouched old defaults. */
private val FACTORY_SHORTCUTS_V1 = listOf(
    Shortcut("YouTube", "https://m.youtube.com"),
    Shortcut("SoundCloud", "https://m.soundcloud.com"),
    Shortcut("Dailymotion", "https://www.dailymotion.com"),
    Shortcut("TED", "https://www.ted.com"),
    Shortcut("Mixcloud", "https://www.mixcloud.com"),
)

object Prefs {
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("media_saver", Context.MODE_PRIVATE)

    fun firstUpdateDone(ctx: Context) = sp(ctx).getBoolean("first_update_done", false)
    fun setFirstUpdateDone(ctx: Context) = sp(ctx).edit().putBoolean("first_update_done", true).apply()

    fun setLastEngineUpdate(ctx: Context, t: Long) = sp(ctx).edit().putLong("last_engine_update", t).apply()
    fun engineStale(ctx: Context): Boolean {
        val last = sp(ctx).getLong("last_engine_update", 0L)
        return last == 0L || System.currentTimeMillis() - last > STALE_MS
    }

    /** "en" or "ar". First launch follows the phone's language. */
    fun isArabic(ctx: Context): Boolean {
        val saved = sp(ctx).getString("lang", null)
        return if (saved != null) saved == "ar" else Locale.getDefault().language == "ar"
    }
    fun setArabic(ctx: Context, ar: Boolean) = sp(ctx).edit().putString("lang", if (ar) "ar" else "en").apply()

    // ---- download defaults (Settings) ----
    fun defaultFormat(ctx: Context): Format =
        if (sp(ctx).getString("def_format", "MP3") == "MP4") Format.MP4 else Format.MP3
    fun setDefaultFormat(ctx: Context, f: Format) = sp(ctx).edit().putString("def_format", f.name).apply()

    /** Default quality for new links, remembered separately for MP3 and MP4. */
    fun prefQuality(ctx: Context, f: Format): Int {
        val q = sp(ctx).getInt("def_quality_${f.name}", defaultQuality(f))
        return if (q in qualityOptions(f)) q else defaultQuality(f)
    }
    fun setPrefQuality(ctx: Context, f: Format, q: Int) = sp(ctx).edit().putInt("def_quality_${f.name}", q).apply()

    /** The first-run permission walk-through has been shown (or skipped). */
    fun permSetupDone(ctx: Context) = sp(ctx).getBoolean("perm_setup_done", false)
    fun setPermSetupDone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("perm_setup_done", v).apply()

    /** Jump to the History tab when a download finishes while the app is open. */
    fun autoOpenHistory(ctx: Context) = sp(ctx).getBoolean("auto_history", true)
    fun setAutoOpenHistory(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_history", v).apply()

    private fun readShortcuts(ctx: Context, key: String): List<Shortcut>? {
        val raw = sp(ctx).getString(key, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Shortcut(o.optString("name"), o.optString("url"))
            }.filter { it.url.isNotBlank() }.take(MAX_SHORTCUTS)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeShortcuts(ctx: Context, key: String, list: List<Shortcut>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    /**
     * One time: a list that is still exactly the old built-in one (the user never changed it) is dropped,
     * so the new built-in five appear. Lists the user customised are left alone.
     */
    private fun migrateShortcuts(ctx: Context) {
        val version = sp(ctx).getInt("shortcut_factory_version", 1)
        if (version >= 4) return
        val e = sp(ctx).edit()
        for (key in listOf("shortcut_defaults", "shortcuts")) {
            val saved = readShortcuts(ctx, key)
            if ((version < 2 && saved == FACTORY_SHORTCUTS_V1) || saved == FACTORY_SHORTCUTS_V2 || saved == FACTORY_SHORTCUTS_V3) e.remove(key)
        }
        e.putInt("shortcut_factory_version", 4).apply()
    }

    /** The user's own default websites (Settings). Falls back to the built-in list. */
    fun shortcutDefaults(ctx: Context): List<Shortcut> {
        migrateShortcuts(ctx)
        return readShortcuts(ctx, "shortcut_defaults") ?: FACTORY_SHORTCUTS
    }
    fun setShortcutDefaults(ctx: Context, list: List<Shortcut>) = writeShortcuts(ctx, "shortcut_defaults", list)

    /** The websites shown in the Search tab right now (editable there, 5 at most). */
    fun shortcuts(ctx: Context): List<Shortcut> {
        migrateShortcuts(ctx)
        return readShortcuts(ctx, "shortcuts") ?: shortcutDefaults(ctx)
    }
    fun setShortcuts(ctx: Context, list: List<Shortcut>) = writeShortcuts(ctx, "shortcuts", list)

    /** Search tab: results can be played as a preview right inside the result card. */
    fun searchPreview(ctx: Context) = sp(ctx).getBoolean("search_preview", true)
    fun setSearchPreview(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("search_preview", v).apply()

    /** History > Video: every row shows a preview image of the downloaded video. */
    fun historyThumbs(ctx: Context) = sp(ctx).getBoolean("history_thumbs", true)
    fun setHistoryThumbs(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("history_thumbs", v).apply()

    /** Media and History category filters are independently configurable from Settings → Behavior. */
    fun categoryFilter(ctx: Context, history: Boolean, id: String): Boolean =
        sp(ctx).getBoolean("${if (history) "history" else "media"}_filter_$id", true)
    fun setCategoryFilter(ctx: Context, history: Boolean, id: String, enabled: Boolean) =
        sp(ctx).edit().putBoolean("${if (history) "history" else "media"}_filter_$id", enabled).apply()

    fun showHomeLibraryTabs(ctx: Context) = sp(ctx).getBoolean("home_library_tabs", true)
    fun setShowHomeLibraryTabs(ctx: Context, enabled: Boolean) = sp(ctx).edit().putBoolean("home_library_tabs", enabled).apply()
    fun showMediaLibraryTabs(ctx: Context) = sp(ctx).getBoolean("media_library_tabs", true)
    fun setShowMediaLibraryTabs(ctx: Context, enabled: Boolean) = sp(ctx).edit().putBoolean("media_library_tabs", enabled).apply()
    fun homeRecommendations(ctx: Context, kind: String) = sp(ctx).getBoolean("home_recommendations_$kind", true)
    fun setHomeRecommendations(ctx: Context, kind: String, enabled: Boolean) =
        sp(ctx).edit().putBoolean("home_recommendations_$kind", enabled).apply()

    // ---- v7.1 switches (Settings -> Behavior) ----
    fun resumePlayback(ctx: Context) = sp(ctx).getBoolean("resume_playback", true)
    fun setResumePlayback(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("resume_playback", v).apply()
    fun undoDelete(ctx: Context) = sp(ctx).getBoolean("undo_delete", true)
    fun setUndoDelete(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("undo_delete", v).apply()
    fun librarySearch(ctx: Context) = sp(ctx).getBoolean("library_search", true)
    fun setLibrarySearch(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("library_search", v).apply()
    fun historySearch(ctx: Context) = sp(ctx).getBoolean("history_search", true)
    fun setHistorySearch(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("history_search", v).apply()
    fun sourceSites(ctx: Context) = sp(ctx).getBoolean("source_sites", true)
    fun setSourceSites(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("source_sites", v).apply()
    fun embedMeta(ctx: Context) = sp(ctx).getBoolean("embed_meta", true)
    fun setEmbedMeta(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("embed_meta", v).apply()
    fun groupBySite(ctx: Context) = sp(ctx).getBoolean("group_by_site", false)
    fun setGroupBySite(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("group_by_site", v).apply()

    // ---- generic string / number slots (account, sync, library) ----
    fun str(ctx: Context, key: String): String? = sp(ctx).getString(key, null)
    fun putStr(ctx: Context, key: String, v: String?) {
        if (v == null) sp(ctx).edit().remove(key).apply() else sp(ctx).edit().putString(key, v).apply()
    }
    fun bool(ctx: Context, key: String, default: Boolean): Boolean = sp(ctx).getBoolean(key, default)
    fun putBool(ctx: Context, key: String, v: Boolean) = sp(ctx).edit().putBoolean(key, v).apply()
    fun long(ctx: Context, key: String): Long = sp(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) = sp(ctx).edit().putLong(key, v).apply()

    // ---- light / dark ----
    fun lightMode(ctx: Context) = sp(ctx).getBoolean("light_mode", false)
    fun setLightMode(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("light_mode", v).apply()

    // ---- History tab remembers where the user was ----
    fun historySub(ctx: Context) = sp(ctx).getInt("history_sub", 0)
    fun setHistorySub(ctx: Context, v: Int) = sp(ctx).edit().putInt("history_sub", v).apply()
    fun historySortKey(ctx: Context) = sp(ctx).getInt("history_sort_key", 0)
    fun historySortAsc(ctx: Context) = sp(ctx).getBoolean("history_sort_asc", false)
    fun setHistorySort(ctx: Context, key: Int, asc: Boolean) =
        sp(ctx).edit().putInt("history_sort_key", key).putBoolean("history_sort_asc", asc).apply()

    // ---- Bluetooth auto-play ----
    fun btEnabled(ctx: Context) = sp(ctx).getBoolean("bt_enabled", false)
    fun setBtEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("bt_enabled", v).apply()
    /**
     * "background" = just play; "open" = open the app and play. Exactly one of the two is always selected:
     * anything that isn't "open" means "background".
     */
    fun btMode(ctx: Context): String = if (sp(ctx).getString("bt_mode", "background") == "open") "open" else "background"
    fun setBtMode(ctx: Context, v: String) = sp(ctx).edit().putString("bt_mode", if (v == "open") "open" else "background").apply()

    // ---- search results per website ----
    fun searchLimit(ctx: Context): Int =
        sp(ctx).getInt("search_limit", DEFAULT_SEARCH_LIMIT).let { if (it in SEARCH_LIMITS) it else DEFAULT_SEARCH_LIMIT }
    fun setSearchLimit(ctx: Context, v: Int) = sp(ctx).edit().putInt("search_limit", if (v in SEARCH_LIMITS) v else DEFAULT_SEARCH_LIMIT).apply()

    // ---- the in-app browser remembers mobile / desktop per website: -1 = not chosen, 0 = mobile, 1 = desktop ----
    fun siteMode(ctx: Context, host: String): Int = sp(ctx).getInt("site_mode_$host", -1)
    fun setSiteMode(ctx: Context, host: String, desktop: Boolean) =
        sp(ctx).edit().putInt("site_mode_$host", if (desktop) 1 else 0).apply()
}
