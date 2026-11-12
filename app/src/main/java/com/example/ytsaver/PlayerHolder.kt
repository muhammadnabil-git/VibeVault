package com.example.ytsaver

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun prettyName(name: String): String = name.substringBeforeLast('.').replace('_', ' ')

/**
 * One player for the whole app. The screen and the background playback service
 * share it, so audio/video keeps playing when the app is not on screen.
 */
object PlayerHolder {
    private var player: ExoPlayer? = null

    private val _entries = MutableStateFlow<List<HistoryItem>>(emptyList())
    /** The "Now playing" list, in the same order as the player's media items. */
    val entries: StateFlow<List<HistoryItem>> = _entries.asStateFlow()

    private val _playing = MutableStateFlow(false)
    /** True while something is playing (the animated background freezes then). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    // some sites only serve a stream to a request that carries their cookies / referer / user-agent
    private val streamHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun registerHeaders(url: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) streamHeaders[url] = headers
    }

    @OptIn(UnstableApi::class)
    @Synchronized
    fun get(ctx: Context): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val upstream = ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx.applicationContext, http)) { spec ->
            val h = streamHeaders[spec.uri.toString()]
            if (h != null) spec.withAdditionalHeaders(h) else spec
        }
        val p = ExoPlayer.Builder(ctx.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(upstream))
            .setAudioAttributes(MEDIA_ATTRIBUTES, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)       // keeps the CPU and Wi-Fi awake while a stream plays with the screen off
            .setSeekBackIncrementMs(10_000L)        // the seek buttons (app, lock screen, car) jump 10 s
            .setSeekForwardIncrementMs(10_000L)
            .build()
        player = p
        attachBookkeeping(p, ctx.applicationContext)
        return p
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 1) remembers the position of the playing file (Settings -> "Resume where you left off");
     * 2) keeps [entries] equal to the player's list when something else (the car's screen, a controller) changed it.
     */
    private fun attachBookkeeping(p: ExoPlayer, app: Context) {
        val tick = object : Runnable {
            override fun run() {
                saveResume(p, app, false)
                if (p.isPlaying) main.postDelayed(this, 5_000L)
            }
        }
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                runCatching { VibeWidget.refresh(app) }
                main.removeCallbacks(tick)
                if (isPlaying) main.postDelayed(tick, 5_000L) else saveResume(p, app, true)
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) {
                // a file that played to its end is finished: next time it starts from the beginning
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    oldPosition.mediaItem?.mediaId?.let { ResumeStore.forget(app, it) }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    p.currentMediaItem?.mediaId?.let { ResumeStore.forget(app, it) }
                }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                main.post { syncEntries(p) }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                main.post { runCatching { VibeWidget.refresh(app) } }
            }
        })
    }

    private fun saveResume(p: ExoPlayer, app: Context, force: Boolean) {
        if (!Prefs.resumePlayback(app)) return
        val id = p.currentMediaItem?.mediaId ?: return
        if (id.startsWith("http")) return          // streamed links have no stable position
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        ResumeStore.put(app, id, p.currentPosition, dur, force)
    }

    /** Rebuilds [entries] from the player's own list when they differ (e.g. a controller replaced the list). */
    private fun syncEntries(p: ExoPlayer) {
        val n = p.mediaItemCount
        val ids = (0 until n).map { p.getMediaItemAt(it).mediaId }
        if (ids == _entries.value.map { it.uri }) return
        if (n == 0) return                         // clear() / removal paths update the list themselves
        val known = (_entries.value + HistoryStore.items.value).associateBy { it.uri }
        _entries.value = (0 until n).map { i ->
            val m = p.getMediaItemAt(i)
            known[m.mediaId] ?: HistoryItem(
                name = m.mediaMetadata.title?.toString() ?: m.mediaId.substringAfterLast('/'),
                format = "MP3", quality = 0, sizeBytes = 0L, timeMillis = System.currentTimeMillis(),
                location = "", uri = m.mediaId, sourceUrl = "",
            )
        }
    }

    fun peek(): ExoPlayer? = player

    private val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private var focusIgnored = false

    /**
     * A playlist started by an alarm, with the app in the background, can have its audio-focus request refused by Android;
     * the player then sits paused although its notification is showing. In that case playback goes ahead without waiting
     * for focus (like an alarm clock) and focus handling is switched back on as soon as playback stops.
     */
    @Synchronized
    fun ignoreAudioFocus() {
        val p = player ?: return
        if (focusIgnored) return
        focusIgnored = true
        p.setAudioAttributes(MEDIA_ATTRIBUTES, false)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && p.playWhenReady.not()) restoreAudioFocus()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED || state == androidx.media3.common.Player.STATE_IDLE) restoreAudioFocus()
            }
        })
    }

    @Synchronized
    fun restoreAudioFocus() {
        val p = player ?: return
        if (!focusIgnored) return
        focusIgnored = false
        p.setAudioAttributes(MEDIA_ATTRIBUTES, true)
    }

    private fun mediaItemOf(h: HistoryItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(h.uri)
            .setUri(h.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(prettyName(h.name)).build())
            .build()

    private fun ensureService(ctx: Context) {
        try {
            ctx.applicationContext.startService(Intent(ctx.applicationContext, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Replace the Now playing list and start playing. */
    fun playNow(ctx: Context, items: List<HistoryItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val p = get(ctx)
        val start = startIndex.coerceIn(0, items.size - 1)
        // resume where this file was left (Settings -> Behavior)
        val resumeAt = if (Prefs.resumePlayback(ctx)) ResumeStore.get(ctx, items[start].uri) else 0L
        p.setMediaItems(items.map(::mediaItemOf), start, resumeAt)
        if (resumeAt > 0) {
            val s = resumeAt / 1000
            toast(ctx, L.t("Resuming from %d:%02d".format(s / 60, s % 60), "استئناف من %d:%02d".format(s / 60, s % 60)))
        }
        _entries.value = items
        p.prepare()
        p.playWhenReady = true
        PlayLog.add(ctx, listOf(items[start]))
        ensureService(ctx)
    }

    /** Append to the Now playing list (skips files that are already in it). Returns how many were added. */
    fun addToQueue(ctx: Context, items: List<HistoryItem>): Int {
        val p = get(ctx)
        val have = _entries.value.map { it.uri }.toSet()
        val fresh = items.filter { it.uri !in have }.distinctBy { it.uri }
        if (fresh.isEmpty()) return 0
        if (_entries.value.isEmpty()) {
            p.setMediaItems(fresh.map(::mediaItemOf), 0, 0L)
            p.prepare()
            p.playWhenReady = false
        } else {
            p.addMediaItems(fresh.map(::mediaItemOf))
        }
        _entries.value = _entries.value + fresh
        ensureService(ctx)
        return fresh.size
    }

    fun removeAt(ctx: Context, index: Int) {
        val list = _entries.value
        if (index !in list.indices) return
        get(ctx).removeMediaItem(index)
        _entries.value = list.toMutableList().also { it.removeAt(index) }
        if (_entries.value.isEmpty()) clear(ctx)
    }

    /** Takes a file out of Now playing (used when the file is deleted from the phone). Main thread only. */
    fun removeByUri(ctx: Context, uri: String) {
        val i = _entries.value.indexOfFirst { it.uri == uri }
        if (i >= 0) removeAt(ctx, i)
    }

    /** Swap the current item for another file (e.g. the MP3 of the same video), keeping the position. */
    fun swapCurrent(ctx: Context, newItem: HistoryItem) {
        val p = get(ctx)
        val idx = p.currentMediaItemIndex
        val list = _entries.value
        if (idx !in list.indices) return
        val pos = p.currentPosition
        p.replaceMediaItem(idx, mediaItemOf(newItem))
        p.seekTo(idx, pos)
        _entries.value = list.toMutableList().also { it[idx] = newItem }
    }

    fun clear(ctx: Context) {
        PlaylistPlayer.cancel()
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _entries.value = emptyList()
        try {
            ctx.applicationContext.stopService(Intent(ctx.applicationContext, PlaybackService::class.java))
        } catch (_: Exception) {
        }
    }
}
