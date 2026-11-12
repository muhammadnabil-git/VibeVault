package com.example.ytsaver

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================================================
// Sleep timer: pauses playback after a chosen time, or when the current file ends
// =====================================================================================================

object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var listener: Player.Listener? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    /** Time left of a running countdown, or null when there is none. */
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    private val _endOfFile = MutableStateFlow(false)
    /** True while "stop after this file" is armed. */
    val endOfFile: StateFlow<Boolean> = _endOfFile.asStateFlow()

    val active: Boolean get() = _remainingMs.value != null || _endOfFile.value

    /** Pauses after [minutes]. The countdown uses cancellable coroutine delays (no blocking sleep). */
    fun startMinutes(ctx: Context, minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val player = PlayerHolder.get(ctx)
        val end = System.currentTimeMillis() + minutes * 60_000L
        _remainingMs.value = minutes * 60_000L
        job = scope.launch {
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                _remainingMs.value = left
                delay(minOf(1_000L, left))
            }
            player.pause()
            _remainingMs.value = null
            job = null
        }
    }

    /** Pauses when the file that is playing now reaches its end. */
    fun startEndOfFile(ctx: Context) {
        cancel()
        val player = PlayerHolder.get(ctx)
        player.pauseAtEndOfMediaItems = true
        _endOfFile.value = true
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // it paused by itself at the end of the file: the timer is spent
                if (!isPlaying && player.playbackState != Player.STATE_BUFFERING && !player.playWhenReady) cancel()
            }
        }
        listener = l
        player.addListener(l)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
        if (_endOfFile.value || listener != null) {
            val p = PlayerHolder.peek()
            listener?.let { p?.removeListener(it) }
            p?.pauseAtEndOfMediaItems = false
        }
        listener = null
        _endOfFile.value = false
    }
}

fun formatCountdown(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// =====================================================================================================
// Equalizer (Android's built-in audio effect, attached to the player's audio session)
// =====================================================================================================

class EqInfo(val presets: List<String>, val bandFreqHz: List<Int>, val minLevel: Int, val maxLevel: Int)

object EqualizerController {
    private const val TAG = "VibeEq"
    private var eq: Equalizer? = null
    private var sessionId = 0
    private var hooked = false

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _levels = MutableStateFlow<List<Int>>(emptyList())
    /** Current level (millibel) of every band. */
    val levels: StateFlow<List<Int>> = _levels.asStateFlow()
    private val _preset = MutableStateFlow(-1)
    val preset: StateFlow<Int> = _preset.asStateFlow()
    private val _supported = MutableStateFlow(true)
    val supported: StateFlow<Boolean> = _supported.asStateFlow()

    var info: EqInfo? = null
        private set

    /** Creates the effect for the current audio session (call from the main thread). Safe to call again. */
    @Synchronized
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        val player = PlayerHolder.get(app)
        if (!hooked) {
            hooked = true
            player.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) { rebuild(app, audioSessionId) }
            })
        }
        rebuild(app, player.audioSessionId)
    }

    @Synchronized
    private fun rebuild(app: Context, session: Int) {
        if (session == 0) return                          // no audio session yet
        if (eq != null && session == sessionId) return
        runCatching { eq?.release() }
        eq = null
        try {
            val e = Equalizer(0, session)
            val range = e.bandLevelRange
            info = EqInfo(
                presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) },
                bandFreqHz = (0 until e.numberOfBands.toInt()).map { e.getCenterFreq(it.toShort()) / 1000 },
                minLevel = range[0].toInt(), maxLevel = range[1].toInt(),
            )
            eq = e
            sessionId = session
            _supported.value = true
            restore(app, e)
        } catch (ex: Exception) {
            // some devices / audio paths do not offer the effect: the screen then explains it
            Log.w(TAG, "equalizer unavailable: ${ex.javaClass.simpleName}")
            _supported.value = false
        }
    }

    private fun restore(app: Context, e: Equalizer) {
        val on = Prefs.bool(app, "eq_on", false)
        val saved = Prefs.str(app, "eq_levels")
        val n = e.numberOfBands.toInt()
        val lv = runCatching { JSONArray(saved ?: "[]").let { a -> (0 until a.length()).map { a.getInt(it) } } }.getOrDefault(emptyList())
        val levels = if (lv.size == n) lv else (0 until n).map { e.getBandLevel(it.toShort()).toInt() }
        levels.forEachIndexed { i, l -> runCatching { e.setBandLevel(i.toShort(), l.toShort()) } }
        runCatching { e.setEnabled(on) }      // (Java setter returns an Int, so it is called as a function)
        _enabled.value = on
        _levels.value = levels
        _preset.value = Prefs.str(app, "eq_preset")?.toIntOrNull() ?: -1
    }

    private fun save(app: Context) {
        Prefs.putBool(app, "eq_on", _enabled.value)
        Prefs.putStr(app, "eq_levels", JSONArray(_levels.value).toString())
        Prefs.putStr(app, "eq_preset", _preset.value.toString())
    }

    @Synchronized
    fun setEnabled(app: Context, on: Boolean) {
        val e = eq ?: return
        runCatching { e.setEnabled(on) }
        _enabled.value = on
        save(app.applicationContext)
    }

    @Synchronized
    fun usePreset(app: Context, index: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(index.toShort())
            _levels.value = (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
            _preset.value = index
            e.setEnabled(true)
            _enabled.value = true
        }
        save(app.applicationContext)
    }

    @Synchronized
    fun setBand(app: Context, band: Int, level: Int) {
        val e = eq ?: return
        val inf = info ?: return
        val l = level.coerceIn(inf.minLevel, inf.maxLevel)
        runCatching { e.setBandLevel(band.toShort(), l.toShort()) }
        _levels.value = _levels.value.toMutableList().also { if (band in it.indices) it[band] = l }
        _preset.value = -1                       // hand-made curve
        save(app.applicationContext)
    }
}

// =====================================================================================================
// Recently played: a short list of what was started last (kept on the phone; vault files are never recorded)
// =====================================================================================================

object PlayLog {
    private const val KEY = "recently_played"
    private const val MAX = 40

    @Synchronized
    fun add(ctx: Context, items: List<HistoryItem>) {
        val first = items.firstOrNull() ?: return
        if (first.uri.contains("/vault/")) return
        val list = read(ctx).filterNot { it.first == first.uri }.toMutableList()
        list.add(0, first.uri to System.currentTimeMillis())
        write(ctx, list.take(MAX))
    }

    @Synchronized
    fun recent(ctx: Context, n: Int): List<String> = read(ctx).take(n).map { it.first }

    @Synchronized
    fun clear(ctx: Context) = Prefs.putStr(ctx, KEY, null)

    private fun read(ctx: Context): List<Pair<String, Long>> = try {
        val a = JSONArray(Prefs.str(ctx, KEY) ?: "[]")
        (0 until a.length()).map { val o = a.getJSONObject(it); o.getString("u") to o.optLong("t") }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(ctx: Context, list: List<Pair<String, Long>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.first).put("t", it.second)) }
        Prefs.putStr(ctx, KEY, a.toString())
    }
}
