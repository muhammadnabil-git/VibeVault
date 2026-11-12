package com.example.ytsaver

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class HistoryItem(
    val name: String,
    val format: String,      // "MP3" or "MP4"
    val quality: Int,
    val sizeBytes: Long,
    val timeMillis: Long,
    val location: String,    // e.g. "Downloads/MediaSaver"
    val uri: String,         // MediaStore uri of the saved file
    val sourceUrl: String,
    val artworkUrl: String? = null,
)

fun formatSize(b: Long): String =
    if (b >= 1_000_000) String.format(Locale.US, "%.1f MB", b / 1_000_000.0)
    else String.format(Locale.US, "%.0f KB", b / 1_000.0)

/** Same video => same id, whatever tracking parameters the link carries. */
fun sourceId(url: String): String {
    val id = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")
        .find(url)?.groupValues?.get(1)
    return id ?: url.trim().substringBefore('#').trimEnd('/')
}

/** Preview frames of downloaded videos, taken from the file itself and kept in memory. */
object VideoThumbs {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(60)

    /** Blocking: run on a background thread. Null when the file is gone or has no picture. */
    fun load(ctx: Context, uri: String): android.graphics.Bitmap? {
        cache.get(uri)?.let { return it }
        val u = Uri.parse(uri)
        var bmp: android.graphics.Bitmap? = runCatching {
            ctx.contentResolver.loadThumbnail(u, android.util.Size(480, 270), null)
        }.getOrNull()
        if (bmp == null) {
            val r = android.media.MediaMetadataRetriever()
            bmp = try {
                r.setDataSource(ctx, u)
                r.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: r.getFrameAtTime(0L)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
        if (bmp != null) cache.put(uri, bmp)
        return bmp
    }
}

fun fileExists(ctx: Context, item: HistoryItem): Boolean = try {
    ctx.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { true } ?: false
} catch (e: Exception) {
    false
}

/**
 * Deletes the saved file from the phone (our own MediaStore entry in Downloads).
 * Returns true when the file is gone afterwards (also when it was already deleted).
 */
fun deleteSavedFile(ctx: Context, item: HistoryItem): Boolean {
    // files imported from the phone's own storage belong to the user, not to the app: they are never deleted from here
    if (item.isDeviceFile) return true
    if (!fileExists(ctx, item)) return true
    try {
        ctx.contentResolver.delete(Uri.parse(item.uri), null, null)
    } catch (_: Exception) {
        // e.g. the file belongs to another install of the app: Android refuses silent deletion
    }
    return !fileExists(ctx, item)
}

object HistoryStore {
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    private var file: File? = null

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "history.json")
        file = f
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            _items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    name = o.optString("name"),
                    format = o.optString("format"),
                    quality = o.optInt("quality"),
                    sizeBytes = o.optLong("size"),
                    timeMillis = o.optLong("time"),
                    location = o.optString("location"),
                    uri = o.optString("uri"),
                    sourceUrl = o.optString("url"),
                    artworkUrl = o.optString("artwork").takeIf { it.startsWith("http") },
                )
            }
        } catch (_: Exception) {
            // corrupt file: start fresh
        }
    }

    @Synchronized
    fun add(ctx: Context, item: HistoryItem) {
        load(ctx)
        // drop stale entries (same source + format + quality) whose file was deleted
        _items.update { list ->
            listOf(item) + list.filterNot { sameSource(it, item) && !fileExists(ctx, it) }
        }
        save()
    }

    /** Adds files imported from the device. Files that are already in the history are skipped. Returns how many were added. */
    @Synchronized
    fun addAll(ctx: Context, items: List<HistoryItem>): Int {
        load(ctx)
        val have = _items.value.map { it.uri }.toSet()
        val fresh = items.filter { it.uri !in have }.distinctBy { it.uri }
        if (fresh.isEmpty()) return 0
        _items.update { fresh + it }
        save()
        return fresh.size
    }

    private fun sameSource(a: HistoryItem, b: HistoryItem) =
        a.format == b.format && a.quality == b.quality && sourceId(a.sourceUrl) == sourceId(b.sourceUrl)

    /** A previously downloaded file (still on the phone) for this link + format + quality. */
    fun findExisting(ctx: Context, url: String, format: String, quality: Int): HistoryItem? {
        load(ctx)
        val key = sourceId(url.trim())
        return _items.value.firstOrNull {
            it.format == format && it.quality == quality && sourceId(it.sourceUrl) == key && fileExists(ctx, it)
        }
    }

    @Synchronized
    fun remove(ctx: Context, item: HistoryItem) {
        load(ctx)
        _items.update { list -> list.filterNot { it.uri == item.uri && it.timeMillis == item.timeMillis } }
        save()
    }

    @Synchronized
    fun removeAll(ctx: Context, items: List<HistoryItem>) {
        load(ctx)
        val gone = items.map { it.uri to it.timeMillis }.toSet()
        _items.update { list -> list.filterNot { (it.uri to it.timeMillis) in gone } }
        save()
    }

    @Synchronized
    fun clear(ctx: Context) {
        load(ctx)
        _items.value = emptyList()
        save()
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _items.value.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name).put("format", it.format).put("quality", it.quality)
                        .put("size", it.sizeBytes).put("time", it.timeMillis)
                        .put("location", it.location).put("uri", it.uri).put("url", it.sourceUrl)
                        .put("artwork", it.artworkUrl.orEmpty()),
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
