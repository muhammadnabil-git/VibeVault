package com.example.ytsaver

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One downloaded file listed inside a grouped notification. */
data class NotifFile(
    val name: String,
    val format: String,
    val quality: Int,
    val sizeBytes: Long,
    val uri: String,
)

/** type: DONE, FAILED, ENGINE_UPDATED, ENGINE_UPDATE_FAILED, ENGINE_NEEDS_UPDATE, INFO */
data class AppNotification(
    val id: Long,
    val timeMillis: Long,
    val type: String,
    val title: String,
    val text: String,
    val read: Boolean,
    val files: List<NotifFile> = emptyList(),
)

/** The history shown behind the bell icon. */
object NotificationStore {
    private const val MAX = 100

    private val _items = MutableStateFlow<List<AppNotification>>(emptyList())
    val items: StateFlow<List<AppNotification>> = _items.asStateFlow()

    private var file: File? = null
    private var lastId = 0L

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "notifications.json")
        file = f
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fa = o.optJSONArray("files")
                val files = if (fa == null) emptyList() else (0 until fa.length()).map { k ->
                    val fo = fa.getJSONObject(k)
                    NotifFile(
                        fo.optString("name"), fo.optString("format"), fo.optInt("quality"),
                        fo.optLong("size"), fo.optString("uri"),
                    )
                }
                AppNotification(
                    id = o.optLong("id"),
                    timeMillis = o.optLong("time"),
                    type = o.optString("type"),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    read = o.optBoolean("read"),
                    files = files,
                )
            }
            lastId = list.maxOfOrNull { it.id } ?: 0L
            _items.value = list
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(ctx: Context, type: String, title: String, text: String, files: List<NotifFile> = emptyList()) {
        load(ctx)
        val now = System.currentTimeMillis()
        val id = maxOf(now, lastId + 1)
        lastId = id
        _items.update { (listOf(AppNotification(id, now, type, title, text, false, files)) + it).take(MAX) }
        save()
    }

    /** Adds the entry unless one of the same type was added within [withinMs]. */
    @Synchronized
    fun addOnce(ctx: Context, type: String, title: String, text: String, withinMs: Long) {
        load(ctx)
        val now = System.currentTimeMillis()
        if (_items.value.any { it.type == type && now - it.timeMillis < withinMs }) return
        add(ctx, type, title, text)
    }

    @Synchronized
    fun markAllRead(ctx: Context) {
        load(ctx)
        if (_items.value.none { !it.read }) return
        _items.update { list -> list.map { it.copy(read = true) } }
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
            _items.value.forEach { n ->
                val fa = JSONArray()
                n.files.forEach {
                    fa.put(
                        JSONObject().put("name", it.name).put("format", it.format).put("quality", it.quality)
                            .put("size", it.sizeBytes).put("uri", it.uri),
                    )
                }
                arr.put(
                    JSONObject().put("id", n.id).put("time", n.timeMillis).put("type", n.type)
                        .put("title", n.title).put("text", n.text).put("read", n.read).put("files", fa),
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
