package com.example.ytsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Which cover each playlist shows. Stored on this phone only (the pictures are not synced).
 *  - AUTO (default): the picture of the first media of the playlist that has one.
 *  - MOSAIC: a collage of up to four pictures of its media, loaded from the internet (or taken from the files).
 *  - CUSTOM: an image the user picked from the device, kept as a small copy in the app's own folder.
 */
object CoverStore {
    const val AUTO = "auto"
    const val MOSAIC = "mosaic"
    const val CUSTOM = "custom"
    private const val KEY = "playlist_covers"

    private val _modes = MutableStateFlow<Map<String, String>>(emptyMap())
    val modes: StateFlow<Map<String, String>> = _modes.asStateFlow()

    /** Goes up whenever a custom picture is replaced, so the screen reloads it. */
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev.asStateFlow()

    fun load(ctx: Context) {
        val m = HashMap<String, String>()
        runCatching {
            val o = JSONObject(Prefs.str(ctx, KEY) ?: "{}")
            o.keys().forEach { k -> m[k] = o.optString(k, AUTO) }
        }
        _modes.value = m
    }

    private fun save(ctx: Context, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        Prefs.putStr(ctx, KEY, o.toString())
        _modes.value = m
    }

    private fun file(ctx: Context, id: String) = File(File(ctx.filesDir, "covers"), "$id.jpg")

    fun setMode(ctx: Context, id: String, mode: String) {
        if (mode != CUSTOM) runCatching { file(ctx, id).delete() }
        val m = HashMap(_modes.value)
        if (mode == AUTO) m.remove(id) else m[id] = mode
        save(ctx, m)
        _rev.value = _rev.value + 1
    }

    /** Called when a playlist is deleted. */
    fun forget(ctx: Context, id: String) {
        runCatching { file(ctx, id).delete() }
        val m = HashMap(_modes.value)
        if (m.remove(id) != null) save(ctx, m)
    }

    /** Copies the picked image (made smaller) into the app's folder. Returns false when it cannot be read. */
    suspend fun setCustom(ctx: Context, id: String, uri: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp == null) {
                    false
                } else {
                    val longest = maxOf(bmp.width, bmp.height)
                    val scaled = if (longest > 720) {
                        val k = 720f / longest
                        Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                    } else {
                        bmp
                    }
                    val f = file(ctx, id)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    true
                }
            }.getOrDefault(false)
        }
        if (ok) {
            val m = HashMap(_modes.value)
            m[id] = CUSTOM
            save(ctx, m)
            _rev.value = _rev.value + 1
        }
        return ok
    }

    suspend fun loadCustom(ctx: Context, id: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file(ctx, id).absolutePath) }.getOrNull()
    }
}

/** Up to [max] different pictures of the media of a playlist: the web thumbnail, or a picture taken from the file. */
private suspend fun coverBitmaps(ctx: Context, entries: List<PlEntry>, max: Int): List<Bitmap> {
    val out = mutableListOf<Bitmap>()
    val seen = HashSet<String>()
    for (e in entries) {
        if (out.size >= max) break
        val url = e.thumbUrl?.takeIf { it.isNotBlank() }
        if (url != null && url in seen) continue
        var bmp: Bitmap? = url?.let { ThumbCache.get(it) }
        if (bmp == null && e.localUri != null) {
            val u = e.localUri
            bmp = withContext(Dispatchers.IO) { VideoThumbs.load(ctx, u) }
        }
        if (bmp != null) {
            out += bmp
            if (url != null) seen += url
        }
    }
    return out
}

@Composable
private fun CoverImage(bmp: Bitmap, modifier: Modifier) {
    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** The cover picture of a playlist (see [CoverStore]). Falls back to a music icon while it loads or when there is none. */
@Composable
fun PlaylistCover(p: Playlist, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val modes by CoverStore.modes.collectAsStateWithLifecycle()
    val rev by CoverStore.rev.collectAsStateWithLifecycle()
    val mode = modes[p.id] ?: CoverStore.AUTO
    val keys = p.entries.map { it.key }
    val pics by produceState<List<Bitmap>>(emptyList(), mode, rev, keys) {
        value = when (mode) {
            CoverStore.CUSTOM -> listOfNotNull(CoverStore.loadCustom(ctx, p.id))
            CoverStore.MOSAIC -> coverBitmaps(ctx, p.entries, 4)
            else -> coverBitmaps(ctx, p.entries, 1)
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        when {
            pics.isEmpty() -> Sym(R.drawable.ic_queue_music, 32.dp, tint = MaterialTheme.colorScheme.primary)
            pics.size == 1 -> CoverImage(pics[0], Modifier.fillMaxSize())
            pics.size == 2 -> Row(Modifier.fillMaxSize()) {
                CoverImage(pics[0], Modifier.weight(1f).fillMaxHeight())
                CoverImage(pics[1], Modifier.weight(1f).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    CoverImage(pics[0], Modifier.weight(1f).fillMaxHeight())
                    CoverImage(pics[1], Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f)) {
                    CoverImage(pics[2], Modifier.weight(1f).fillMaxHeight())
                    if (pics.size > 3) CoverImage(pics[3], Modifier.weight(1f).fillMaxHeight())
                    else Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}
