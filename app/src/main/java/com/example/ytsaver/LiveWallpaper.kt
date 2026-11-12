package com.example.ytsaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A source of pictures that keeps changing: the app shows its latest picture behind the screens. */
class PictureSource(val id: String, val en: String, val ar: String, val enNote: String, val arNote: String)

/**
 * "Live pictures" backgrounds. A new picture is fetched from an online source every day (or every few hours, or each
 * time the app opens), kept on the phone, and drawn behind the screens with an adjustable dimming so text stays readable.
 * No account or key is needed for any of the sources.
 */
object LiveWallpaper {
    val sources = listOf(
        PictureSource("photo", "Random Photos", "صور عشوائية", "A random high-quality photograph (Picsum)", "صورة عشوائية عالية الجودة (Picsum)"),
    )

    /** When the picture changes: 0 = every time the app opens (at least 10 minutes apart), 24 = once a day, -1 = keep the current one. */
    val intervals = listOf(0, 24, KEEP)
    const val KEEP = -1

    private const val UA = "VibeVault/7.7 (live pictures)"
    private const val FILE = "live_picture.jpg"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var started = false

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _credit = MutableStateFlow("")
    val credit: StateFlow<String> = _credit.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()
    private val _source = MutableStateFlow("off")
    val source: StateFlow<String> = _source.asStateFlow()
    private val _every = MutableStateFlow(24)
    val every: StateFlow<Int> = _every.asStateFlow()
    private val _dim = MutableStateFlow(70)
    val dim: StateFlow<Int> = _dim.asStateFlow()

    /** Reads the settings and the last picture from the phone. Call once at start-up. */
    @Synchronized
    fun init(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        // earlier versions had more sources; they all became "Random Photos"
        _source.value = Prefs.str(app, "livepic_source").let { if (it == null || it == "off") "off" else "photo" }
        _every.value = Prefs.str(app, "livepic_every")?.toIntOrNull()?.takeIf { it in intervals } ?: 24
        _dim.value = Prefs.str(app, "livepic_dim")?.toIntOrNull()?.coerceIn(30, 95) ?: 70
        _credit.value = Prefs.str(app, "livepic_credit").orEmpty()
        if (_source.value != "off") scope.launch {
            val f = File(app.filesDir, FILE)
            if (f.exists()) _bitmap.value = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        }
    }

    fun setSource(ctx: Context, id: String) {
        val app = ctx.applicationContext
        _source.value = id
        Prefs.putStr(app, "livepic_source", id)
        _error.value = ""
        if (id == "off") {
            job?.cancel()
            _busy.value = false
            _bitmap.value = null
            _credit.value = ""
            File(app.filesDir, FILE).delete()
            Prefs.putStr(app, "livepic_credit", null)
            Prefs.putStr(app, "livepic_time", null)
        } else refresh(app)
    }

    fun setEvery(ctx: Context, hours: Int) {
        _every.value = hours
        Prefs.putStr(ctx.applicationContext, "livepic_every", hours.toString())
    }

    fun setDim(ctx: Context, percent: Int) {
        _dim.value = percent.coerceIn(30, 95)
        Prefs.putStr(ctx.applicationContext, "livepic_dim", _dim.value.toString())
    }

    /** Gets a new picture now when the chosen time has passed (called whenever the app comes to the front). */
    fun refreshIfDue(ctx: Context) {
        val app = ctx.applicationContext
        init(app)
        if (_source.value == "off" || _busy.value) return
        val last = Prefs.str(app, "livepic_time")?.toLongOrNull() ?: 0L
        // "Keep": the picture stays until the user asks for a new one
        if (_every.value == KEEP) { if (_bitmap.value == null) refresh(app); return }
        val gap = if (_every.value == 0) 10L * 60_000 else _every.value * 3_600_000L
        if (_bitmap.value == null || System.currentTimeMillis() - last >= gap) refresh(app)
    }

    /** Gets a new picture now. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val src = _source.value
        if (src == "off") return
        job?.cancel()
        _busy.value = true
        _error.value = ""
        job = scope.launch {
            try {
                val (bmp, credit) = fetch(src)
                // the picture is kept as a small JPEG so it is there at the next start without the internet
                File(app.filesDir, FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                Prefs.putStr(app, "livepic_credit", credit)
                Prefs.putStr(app, "livepic_time", System.currentTimeMillis().toString())
                if (_source.value == src) {
                    _bitmap.value = bmp
                    _credit.value = credit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = (e.message ?: e.javaClass.simpleName).take(120)
            } finally {
                _busy.value = false
            }
        }
    }

    /** Uses a picture from the phone as the background. It stays ("Keep") until a new picture is asked for. */
    fun importPicture(ctx: Context, uri: android.net.Uri) {
        val app = ctx.applicationContext
        init(app)
        job?.cancel()
        _busy.value = true
        _error.value = ""
        job = scope.launch {
            try {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read the picture")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0) throw IllegalStateException("not a picture")
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1920) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IllegalStateException("not a picture")
                File(app.filesDir, FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                val credit = "imported picture"
                Prefs.putStr(app, "livepic_credit", credit)
                Prefs.putStr(app, "livepic_time", System.currentTimeMillis().toString())
                if (_source.value == "off") { _source.value = "photo"; Prefs.putStr(app, "livepic_source", "photo") }
                _every.value = KEEP
                Prefs.putStr(app, "livepic_every", KEEP.toString())
                _bitmap.value = bmp
                _credit.value = credit
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = (e.message ?: e.javaClass.simpleName).take(120)
            } finally {
                _busy.value = false
            }
        }
    }

    // ------------------------------------------------------------------------------------------ fetching

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12_000
        c.readTimeout = 25_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads a picture and shrinks it so its longest side is about 1920 px. Null when the address doesn't give a picture. */
    private fun picture(url: String): Bitmap? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1920) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(@Suppress("UNUSED_PARAMETER") src: String): Pair<Bitmap, String> = photo()

    private fun photo(): Pair<Bitmap, String> {
        val bmp = picture("https://picsum.photos/1080/1920.jpg?random=${System.currentTimeMillis()}") ?: throw java.io.IOException("Picsum gave no picture")
        return bmp to "Picsum"
    }
}

/** The live picture behind the screens, dimmed with the theme's background colour so everything stays readable. */
@Composable
fun LivePicture(modifier: Modifier = Modifier) {
    val bmp by LiveWallpaper.bitmap.collectAsStateWithLifecycle()
    val dim by LiveWallpaper.dim.collectAsStateWithLifecycle()
    val bg = MaterialTheme.colorScheme.background
    Crossfade(targetState = bmp, label = "livePicture", modifier = modifier.fillMaxSize()) { b ->
        if (b != null) {
            Box(Modifier.fillMaxSize()) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(bg.copy(alpha = dim / 100f)))
            }
        }
    }
}
