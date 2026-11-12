package com.example.ytsaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val KIND_LOCAL = "LOCAL"     // only downloaded files
const val KIND_ONLINE = "ONLINE"   // only web links (streamed)
const val KIND_MIXED = "MIXED"     // both

/** One song / video of a playlist or of the favorites: a downloaded file and / or a web link. */
data class PlEntry(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val thumbUrl: String?,
    val pageUrl: String,          // the web link (streamed, or used again if the file is missing on another device)
    val localUri: String?,        // the downloaded file on this phone, if there is one
    val isAudio: Boolean,
    val site: String,
) {
    /** Same media = same key: the saved file when we have one, otherwise the web link. */
    val key: String get() = localUri ?: pageUrl

    /**
     * Identity of the media across devices: the web link. A file path only exists on one phone,
     * so it is used only for media that has no link at all.
     */
    val syncKey: String get() = pageUrl.ifBlank { localUri ?: "" }
}

data class Playlist(
    val id: String,
    val name: String,
    val kind: String,
    val entries: List<PlEntry>,
    val updatedAt: Long,
    val created: Long = 0L,       // creation time: keeps the playlists in the same order on every device
    val schedule: PlSchedule? = null,   // weekly calendar / time window (see Schedule.kt)
)

data class FavItem(val entry: PlEntry, val addedAt: Long)

/** "When this Bluetooth device connects, play this playlist." The device is matched by MAC address or by name. */
data class BtRule(val id: String, val deviceName: String, val deviceAddress: String, val playlistId: String)

fun siteLabelOf(url: String): String {
    val h = hostOf(url)
    return when {
        h.contains("youtube.") || h.contains("youtu.be") -> "YouTube"
        h.contains("soundcloud.") -> "SoundCloud"
        h.contains("spotify.") -> "Spotify"
        h.contains("anghami.") -> "Anghami"
        h.contains("tiktok.") -> "TikTok"
        h.contains("snapchat.") -> "Snapchat"
        h.contains("instagram.") -> "Instagram"
        h.contains("facebook.") || h.contains("fb.watch") -> "Facebook"
        h.contains("dailymotion.") -> "Dailymotion"
        h.contains("bilibili.") -> "Bilibili"
        h.contains("shazam.") -> "Shazam"
        h.contains("archive.org") -> "Internet Archive"
        h.isBlank() -> ""
        else -> h.removePrefix("www.").removePrefix("m.").substringBefore('.').replaceFirstChar { it.uppercase() }
    }
}

fun guessIsAudio(url: String): Boolean {
    val h = hostOf(url)
    return h.contains("soundcloud.") || h.contains("mixcloud.") || h.contains("bandcamp.") ||
        h.contains("spotify.") || h.contains("anghami.") || h.contains("shazam.")
}

fun HistoryItem.toEntry() = PlEntry(
    title = prettyName(name), uploader = "", durationSec = 0L, thumbUrl = null,
    pageUrl = sourceUrl, localUri = uri, isAudio = format == "MP3",
    site = siteLabelOf(sourceUrl),
)

fun FeedItem.toEntry() = PlEntry(
    title = title, uploader = uploader, durationSec = durationSec, thumbUrl = thumbUrl,
    pageUrl = url, localUri = null, isAudio = isAudio, site = site.ifBlank { siteLabelOf(url) },
)

private fun PlEntry.toJson(): JSONObject = JSONObject()
    .put("title", title).put("uploader", uploader).put("dur", durationSec)
    .put("thumb", thumbUrl ?: "").put("page", pageUrl).put("local", localUri ?: "")
    .put("audio", isAudio).put("site", site)

private fun entryOf(o: JSONObject) = PlEntry(
    title = o.optString("title"), uploader = o.optString("uploader"), durationSec = o.optLong("dur"),
    thumbUrl = o.optString("thumb").ifBlank { null }, pageUrl = o.optString("page"),
    localUri = o.optString("local").ifBlank { null }, isAudio = o.optBoolean("audio"), site = o.optString("site"),
)

private class Snap(
    val playlists: List<Playlist>,
    val favs: List<FavItem>,
    val rules: List<BtRule>,
    val tomb: Map<String, Long>,
)

private fun parseSnap(o: JSONObject): Snap {
    val pls = mutableListOf<Playlist>()
    val pa = o.optJSONArray("playlists") ?: JSONArray()
    for (i in 0 until pa.length()) {
        val p = pa.optJSONObject(i) ?: continue
        val ea = p.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until ea.length()).mapNotNull { ea.optJSONObject(it)?.let(::entryOf) }
        pls += Playlist(p.optString("id"), p.optString("name"), p.optString("kind", KIND_MIXED), entries, p.optLong("at"), p.optLong("cr"), PlSchedule.fromJson(p.optJSONObject("sch")))
    }
    val favs = mutableListOf<FavItem>()
    val fa = o.optJSONArray("favorites") ?: JSONArray()
    for (i in 0 until fa.length()) {
        val f = fa.optJSONObject(i) ?: continue
        favs += FavItem(entryOf(f), f.optLong("at"))
    }
    val rules = mutableListOf<BtRule>()
    val ra = o.optJSONArray("bt") ?: JSONArray()
    for (i in 0 until ra.length()) {
        val r = ra.optJSONObject(i) ?: continue
        rules += BtRule(r.optString("id"), r.optString("name"), r.optString("addr"), r.optString("pl"))
    }
    val tomb = mutableMapOf<String, Long>()
    val to = o.optJSONObject("deleted")
    to?.keys()?.forEach { k -> tomb[k] = to.optLong(k) }
    return Snap(pls.filter { it.id.isNotBlank() }, favs.filter { it.entry.key.isNotBlank() }, rules.filter { it.id.isNotBlank() }, tomb)
}

/** Playlists, favorites and Bluetooth rules. Saved on the phone, and merged with the account's cloud copy when you sync. */
object Library {
    private var appCtx: Context? = null
    private var loaded = false

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _favorites = MutableStateFlow<List<FavItem>>(emptyList())
    val favorites: StateFlow<List<FavItem>> = _favorites.asStateFlow()
    private val _btRules = MutableStateFlow<List<BtRule>>(emptyList())
    val btRules: StateFlow<List<BtRule>> = _btRules.asStateFlow()
    private val _changes = MutableStateFlow(0L)
    /** Changes every time something is edited (used to trigger the account sync). */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private var tomb = HashMap<String, Long>()   // "pl:<id>" / "fav:<key>" / "bt:<id>" -> when it was deleted

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        appCtx = ctx.applicationContext
        val raw = Prefs.str(ctx, "library_json")
        if (raw != null) {
            try {
                val s = parseSnap(JSONObject(raw))
                _playlists.value = s.playlists
                _favorites.value = s.favs
                _btRules.value = s.rules
                tomb = HashMap(s.tomb)
            } catch (_: Exception) {
            }
        }
        loaded = true
    }

    @Synchronized
    fun snapshot(): JSONObject {
        val pa = JSONArray()
        _playlists.value.forEach { p ->
            val ea = JSONArray()
            p.entries.forEach { ea.put(it.toJson()) }
            val po = JSONObject().put("id", p.id).put("name", p.name).put("kind", p.kind).put("at", p.updatedAt).put("cr", p.created).put("entries", ea)
            p.schedule?.let { po.put("sch", it.toJson()) }
            pa.put(po)
        }
        val fa = JSONArray()
        _favorites.value.forEach { fa.put(it.entry.toJson().put("at", it.addedAt)) }
        val ra = JSONArray()
        _btRules.value.forEach { ra.put(JSONObject().put("id", it.id).put("name", it.deviceName).put("addr", it.deviceAddress).put("pl", it.playlistId)) }
        val del = JSONObject()
        tomb.forEach { (k, v) -> del.put(k, v) }
        return JSONObject().put("playlists", pa).put("favorites", fa).put("bt", ra).put("deleted", del)
    }

    private fun commit() {
        appCtx?.let {
            Prefs.putStr(it, "library_json", snapshot().toString())
            PlaylistScheduler.reschedule(it)
        }
        _changes.value = System.currentTimeMillis()
    }

    private fun now() = System.currentTimeMillis()

    // ---------------- playlists ----------------
    @Synchronized
    fun createPlaylist(name: String, kind: String): Playlist {
        val t = now()
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifBlank { "Playlist" }, kind, emptyList(), t, t)
        _playlists.value = _playlists.value + p
        commit()
        return p
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, updatedAt = now()) else it }
        commit()
    }

    /** Sets the weekly calendar / time window of a playlist. */
    @Synchronized
    fun setSchedule(id: String, schedule: PlSchedule?) {
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(schedule = schedule, updatedAt = now()) else it }
        commit()
    }

    @Synchronized
    fun deletePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        tomb["pl:$id"] = now()
        _btRules.value.filter { it.playlistId == id }.forEach { tomb["bt:${it.id}"] = now() }
        _btRules.value = _btRules.value.filter { it.playlistId != id }
        commit()
    }

    /** What a playlist of this kind accepts. Online lists keep only the web link, local lists need the saved file. */
    private fun fit(kind: String, e: PlEntry): PlEntry? = when (kind) {
        KIND_LOCAL -> if (e.localUri != null) e else null
        KIND_ONLINE -> if (e.pageUrl.isNotBlank()) e.copy(localUri = null) else null
        else -> if (e.localUri != null || e.pageUrl.isNotBlank()) e else null
    }

    /** Returns how many were added (already-present and not-allowed ones are skipped). */
    @Synchronized
    fun addToPlaylist(id: String, entries: List<PlEntry>): Int {
        val pl = _playlists.value.firstOrNull { it.id == id } ?: return 0
        val have = pl.entries.map { it.key }.toMutableSet()
        val fresh = mutableListOf<PlEntry>()
        for (e in entries) {
            val f = fit(pl.kind, e) ?: continue
            if (have.add(f.key)) fresh += f
        }
        if (fresh.isEmpty()) return 0
        _playlists.value = _playlists.value.map { if (it.id == id) it.copy(entries = it.entries + fresh, updatedAt = now()) else it }
        commit()
        return fresh.size
    }

    /** Deletes several playlists at once (the bulk "Select → Remove"). Downloaded files are not touched. */
    @Synchronized
    fun deletePlaylists(ids: Set<String>) {
        if (ids.isEmpty()) return
        val t = now()
        ids.forEach { tomb["pl:$it"] = t }
        _btRules.value.filter { it.playlistId in ids }.forEach { tomb["bt:${it.id}"] = t }
        _btRules.value = _btRules.value.filter { it.playlistId !in ids }
        _playlists.value = _playlists.value.filter { it.id !in ids }
        commit()
    }

    @Synchronized
    fun removeFromPlaylist(id: String, key: String) {
        _playlists.value = _playlists.value.map {
            if (it.id == id) it.copy(entries = it.entries.filter { e -> e.key != key }, updatedAt = now()) else it
        }
        commit()
    }

    /** Puts the entries of a playlist in the order of [keys] (drag to reorder). Entries not named keep their place at the end. */
    @Synchronized
    fun setOrder(id: String, keys: List<String>) {
        _playlists.value = _playlists.value.map { p ->
            if (p.id != id) p else {
                val byKey = p.entries.associateBy { it.key }
                val ordered = keys.mapNotNull { byKey[it] }
                val rest = p.entries.filter { it.key !in keys }
                p.copy(entries = ordered + rest, updatedAt = now())
            }
        }
        commit()
    }

    /**
     * Copies ([move] = false) or moves one entry from playlist [fromId] to [toId].
     * Returns true when the target has the entry afterwards (it may refuse it: an online-only list takes no local-only file).
     */
    @Synchronized
    fun transfer(fromId: String, toId: String, entry: PlEntry, move: Boolean): Boolean {
        addToPlaylist(toId, listOf(entry))
        val there = _playlists.value.firstOrNull { it.id == toId }?.entries?.any { it.key == entry.key || it.syncKey == entry.syncKey } == true
        if (there && move) removeFromPlaylist(fromId, entry.key)
        return there
    }

    /** Fills in the name / picture of an entry that was added as a bare link. */
    @Synchronized
    fun updateMeta(pageUrl: String, title: String, uploader: String, durationSec: Long, thumbUrl: String?) {
        fun fix(e: PlEntry) = if (e.pageUrl == pageUrl && e.localUri == null)
            e.copy(title = title.ifBlank { e.title }, uploader = uploader, durationSec = durationSec, thumbUrl = thumbUrl ?: e.thumbUrl) else e
        val t = now()
        _playlists.value = _playlists.value.map { p ->
            if (p.entries.any { it.pageUrl == pageUrl && it.localUri == null }) p.copy(entries = p.entries.map(::fix), updatedAt = t) else p
        }
        _favorites.value = _favorites.value.map { it.copy(entry = fix(it.entry)) }
        commit()
    }

    // ---------------- favorites ----------------
    /** Returns true when the item is now a favorite. */
    @Synchronized
    fun toggleFavorite(e: PlEntry): Boolean {
        val k = e.syncKey
        val exists = _favorites.value.any { it.entry.syncKey == k }
        if (exists) {
            _favorites.value = _favorites.value.filter { it.entry.syncKey != k }
            tomb["fav:$k"] = now()
        } else {
            _favorites.value = listOf(FavItem(e, now())) + _favorites.value
        }
        commit()
        return !exists
    }

    // ---------------- Bluetooth rules ----------------
    @Synchronized
    fun addBtRule(deviceName: String, deviceAddress: String, playlistId: String) {
        val name = deviceName.trim()
        val addr = deviceAddress.trim().uppercase()
        if (name.isEmpty() && addr.isEmpty()) return
        // one playlist per device: a new rule replaces the old one of the same device
        val same = _btRules.value.filter { (addr.isNotEmpty() && it.deviceAddress == addr) || (name.isNotEmpty() && it.deviceName.equals(name, true)) }
        same.forEach { tomb["bt:${it.id}"] = now() }
        _btRules.value = _btRules.value.filterNot { r -> same.any { it.id == r.id } } +
            BtRule(UUID.randomUUID().toString(), name, addr, playlistId)
        commit()
    }

    @Synchronized
    fun removeBtRule(id: String) {
        _btRules.value = _btRules.value.filter { it.id != id }
        tomb["bt:$id"] = now()
        commit()
    }

    // ---------------- account sync ----------------
    /** The same playlist as [prev], but with the downloaded files of this phone put back on the entries that came from the cloud. */
    private fun relink(p: Playlist, prev: Playlist?): Playlist {
        if (prev == null || p === prev) return p
        val entries = p.entries.map { e ->
            if (e.localUri != null || e.pageUrl.isBlank()) e
            else prev.entries.firstOrNull { it.pageUrl == e.pageUrl && it.localUri != null }
                ?.let { e.copy(localUri = it.localUri) } ?: e
        }
        return p.copy(entries = entries, created = if (p.created != 0L) p.created else prev.created)
    }

    /**
     * Merges the cloud copy into this phone: the newest edit of every playlist / favorite wins, deletions are kept.
     * Downloaded files are never touched: the cloud only knows web links, so entries coming from the cloud
     * get this phone's own file back when it has one (see [relink]).
     */
    @Synchronized
    fun mergeRemote(remote: JSONObject?) {
        if (remote == null) return
        val rs = try { parseSnap(remote) } catch (_: Exception) { return }
        val t = HashMap(tomb)
        rs.tomb.forEach { (k, v) -> if (v > (t[k] ?: 0L)) t[k] = v }

        val localById = _playlists.value.associateBy { it.id }
        val byId = LinkedHashMap<String, Playlist>()
        (_playlists.value + rs.playlists).forEach { p ->
            val cur = byId[p.id]
            if (cur == null || p.updatedAt > cur.updatedAt) byId[p.id] = p
        }
        val pls = byId.values
            .filter { (t["pl:${it.id}"] ?: 0L) < it.updatedAt }
            .map { relink(it, localById[it.id]) }
            .sortedBy { it.created }       // stable: playlists without a creation time keep their order

        val favMap = LinkedHashMap<String, FavItem>()
        (_favorites.value + rs.favs).forEach { f ->
            val k = f.entry.syncKey
            val cur = favMap[k]
            if (cur == null || f.addedAt > cur.addedAt) {
                favMap[k] = if (cur != null && f.entry.localUri == null) f.copy(entry = f.entry.copy(localUri = cur.entry.localUri)) else f
            }
        }
        val favs = favMap.values.filter { (t["fav:${it.entry.syncKey}"] ?: 0L) < it.addedAt }.sortedByDescending { it.addedAt }

        val ruleMap = LinkedHashMap<String, BtRule>()
        (_btRules.value + rs.rules).forEach { ruleMap.putIfAbsent(it.id, it) }
        val rules = ruleMap.values.filter { t["bt:${it.id}"] == null && pls.any { p -> p.id == it.playlistId } }

        tomb = t
        _playlists.value = pls
        _favorites.value = favs
        _btRules.value = rules
        appCtx?.let {
            Prefs.putStr(it, "library_json", snapshot().toString())   // saved, but not "changed" (no sync loop)
            PlaylistScheduler.reschedule(it)
        }
    }

    /** Another account is signing in on this phone: its playlists must not leak into the new account. Files are not touched. */
    @Synchronized
    fun resetAll() {
        _playlists.value = emptyList()
        _favorites.value = emptyList()
        _btRules.value = emptyList()
        tomb = HashMap()
        appCtx?.let { Prefs.putStr(it, "library_json", snapshot().toString()) }
    }
}
