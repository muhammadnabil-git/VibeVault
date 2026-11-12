package com.example.ytsaver

import android.content.Context
import org.json.JSONObject

/**
 * "Resume where you left off": the last position of every file that was played (newest 200 files),
 * kept on the phone. Used only while the switch in Settings -> Behavior is on.
 */
object ResumeStore {
    private const val KEY = "resume_json"
    private const val MAX = 200
    private const val MIN_MS = 10_000L          // shorter than this: start from the beginning
    private const val END_MARGIN_MS = 8_000L    // within this of the end counts as finished
    private val map = LinkedHashMap<String, Long>()
    private var loaded = false
    private var lastSaved = 0L

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(Prefs.str(ctx, KEY) ?: return)
            o.keys().forEach { k -> map[k] = o.optLong(k) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun get(ctx: Context, uri: String): Long {
        if (uri.contains("/vault/")) return 0L          // vault files leave no trace
        load(ctx)
        val p = map[uri] ?: return 0L
        return if (p >= MIN_MS) p else 0L
    }

    /** Remembers [posMs] of [uri]; a position near the end (or the start) forgets it instead. */
    @Synchronized
    fun put(ctx: Context, uri: String, posMs: Long, durationMs: Long, force: Boolean = false) {
        if (uri.contains("/vault/")) return
        load(ctx)
        val finished = durationMs > 0 && posMs >= durationMs - END_MARGIN_MS
        if (finished || posMs < MIN_MS) {
            if (map.remove(uri) != null) persist(ctx)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 4_000L && map.containsKey(uri)) return
        map.remove(uri)
        map[uri] = posMs
        while (map.size > MAX) map.remove(map.keys.first())
        lastSaved = now
        persist(ctx)
    }

    /** The newest remembered files, most recent first: (uri, position in ms). Used by "Continue listening". */
    @Synchronized
    fun recent(ctx: Context, n: Int): List<Pair<String, Long>> {
        load(ctx)
        return map.entries.toList().asReversed().filter { it.value >= MIN_MS }.take(n).map { it.key to it.value }
    }

    @Synchronized
    fun forget(ctx: Context, uri: String) {
        load(ctx)
        if (map.remove(uri) != null) persist(ctx)
    }

    private fun persist(ctx: Context) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        Prefs.putStr(ctx, KEY, o.toString())
    }
}
