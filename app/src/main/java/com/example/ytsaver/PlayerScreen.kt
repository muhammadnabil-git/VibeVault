package com.example.ytsaver

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compose-observable copy of the bits of player state the UI needs. */
class PlayerSnapshot {
    var isPlaying by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var audioOnly by mutableStateOf(false)

    fun refresh(p: Player) {
        isPlaying = p.isPlaying
        index = p.currentMediaItemIndex
        count = p.mediaItemCount
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        audioOnly = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
    }
}

@Composable
fun rememberSnapshot(player: Player): PlayerSnapshot {
    val snap = remember(player) { PlayerSnapshot() }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = snap.refresh(p)
        }
        player.addListener(l)
        snap.refresh(player)
        onDispose { player.removeListener(l) }
    }
    return snap
}

/** Audio-only mode = the video track of the current file is switched off. */
fun setAudioOnly(p: Player, on: Boolean) {
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on)
        .build()
}

/** Swipe up/down on the right half = volume, on the left half = brightness. */
class SwipeController(
    private val ctx: Context,
    private val activity: Activity?,
    private val onHud: (String) -> Unit,
) {
    @Volatile var enabled = true

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var mode = 0              // 0 undecided, 1 volume, 2 brightness, -1 ignore (horizontal / tap)
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var startVol = 0
    private var startBright = 0.5f
    private var accum = 0f

    fun attach(view: View) {
        view.setOnTouchListener { v, ev ->
            var consumed = false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastY = ev.y; mode = 0
                }
                MotionEvent.ACTION_MOVE -> if (enabled) {
                    if (mode == 0) {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (abs(dy) > slop && abs(dy) > abs(dx)) {
                            accum = 0f
                            lastY = ev.y
                            if (downX > v.width / 2f) {
                                mode = 1
                                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            } else {
                                mode = 2
                                startBright = currentBrightness()
                            }
                        } else if (abs(dx) > slop) {
                            mode = -1
                        }
                    }
                    if (mode > 0) {
                        accum += (lastY - ev.y) / (v.height * 0.7f)   // finger up = more
                        lastY = ev.y
                        apply()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    consumed = mode > 0   // a swipe: don't let the player treat it as a tap
                    mode = 0
                }
            }
            consumed || mode > 0
        }
    }

    private fun apply() {
        if (mode == 1) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val v = (startVol + accum * max).roundToInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            onHud(L.t("Volume", "الصوت") + " ${if (max > 0) v * 100 / max else 0}%")
        } else if (mode == 2) {
            val b = (startBright + accum).coerceIn(0.02f, 1f)
            setBrightness(b)
            onHud(L.t("Brightness", "السطوع") + " ${(b * 100).roundToInt()}%")
        }
    }

    private fun currentBrightness(): Float {
        val w = activity?.window ?: return 0.5f
        val b = w.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun setBrightness(b: Float) {
        val w = activity?.window ?: return
        val lp = w.attributes
        lp.screenBrightness = b
        w.attributes = lp
    }

    /** Back to the phone's own brightness setting. */
    fun restore() = setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
}

@Composable
fun PlayerScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val player = remember { PlayerHolder.get(ctx) }
    val entries by PlayerHolder.entries.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val snap = rememberSnapshot(player)

    var fullscreen by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }
    val sleepLeft by SleepTimer.remainingMs.collectAsStateWithLifecycle()
    val sleepEof by SleepTimer.endOfFile.collectAsStateWithLifecycle()
    var hud by remember { mutableStateOf<String?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }

    val current = entries.getOrNull(snap.index)
    val showVideo = current?.format == "MP4" && !snap.audioOnly
    val title = current?.let { prettyName(it.name) } ?: ""

    // nothing left to play
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) onClose() }

    // playback errors
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast(ctx, L.t("Can't play this file", "تعذّر تشغيل هذا الملف"))
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val swipe = remember(activity) {
        SwipeController(ctx, activity) { text -> hud = text; hudTick++ }
    }
    SideEffect { swipe.enabled = showVideo }

    LaunchedEffect(hudTick) {
        if (hudTick > 0) {
            delay(900)
            hud = null
        }
    }

    // Hide / show the system bars for fullscreen
    LaunchedEffect(fullscreen) {
        val act = activity ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Always restore bars, orientation and brightness when leaving the player screen
    DisposableEffect(Unit) {
        onDispose {
            swipe.restore()
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // fullscreen always turns the screen to landscape; leaving it brings back the orientation the screen had before
    val orient = remember(activity) { FullscreenOrientation(activity) }
    fun enterFullscreen() {
        fullscreen = true
        orient.enter()
    }
    fun exitFullscreen() {
        fullscreen = false
        orient.exit()
    }

    LaunchedEffect(showVideo) { if (!showVideo && fullscreen) exitFullscreen() }
    val castCtx = androidx.compose.ui.platform.LocalContext.current

    BackHandler { if (fullscreen) exitFullscreen() else onClose() }

    // the same video/song in the other format (for the quick audio <-> video switch)
    val counterpart = remember(current, history) {
        current?.let { c -> history.firstOrNull { it.format != c.format && sourceId(it.sourceUrl) == sourceId(c.sourceUrl) } }
    }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text(tr("← Back", "رجوع")) }
                Text(title, maxLines = 1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                // Cast to TV: opens the phone's Cast / Smart View screen (works for downloaded and streamed media)
                IconButton(onClick = { openCast(castCtx) }) {
                    Sym(R.drawable.ic_tv, 24.dp, tint = MaterialTheme.colorScheme.onBackground)
                }
                TextButton(onClick = { vm.stopPlayback() }) { Text(tr("Stop", "إيقاف")) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = true
                        swipe.attach(this)
                    }
                },
                update = { v ->
                    // video and audio alike: the controls fade out by themselves and a tap on the screen brings them back
                    v.controllerShowTimeoutMs = 3000
                    v.controllerHideOnTouch = true
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            if (!showVideo) {
                // the logo is only a faint watermark: no frame, nothing that can cover or block the play button in the middle
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VvLogo(280.dp, Modifier.alpha(0.09f))
                }
                // the song's name sits at the top, away from the controls
                Text(
                    title, textAlign = TextAlign.Center, fontSize = 16.sp, maxLines = 3,
                    modifier = Modifier.align(Alignment.TopCenter).padding(start = 32.dp, end = 32.dp, top = 20.dp),
                )
            } else {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val pill = Modifier.background(Color(0x99000000), RoundedCornerShape(20.dp))
                    TextButton(
                        onClick = {
                            val portrait = ctx.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                            activity?.requestedOrientation =
                                if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        },
                        modifier = pill,
                    ) { Text(tr("Rotate", "تدوير"), color = Color.White, fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            if (fullscreen) {
                                exitFullscreen()
                            } else {
                                enterFullscreen()
                            }
                        },
                        modifier = pill,
                    ) {
                        Text(
                            if (fullscreen) tr("Exit fullscreen", "إنهاء ملء الشاشة") else tr("Fullscreen", "ملء الشاشة"),
                            color = Color.White, fontSize = 12.sp,
                        )
                    }
                }
            }

            hud?.let {
                Box(
                    Modifier.align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text(it, color = Color.White, fontSize = 16.sp) }
            }
        }

        if (!fullscreen) {
            // shuffle / repeat / loop / list / audio<->video
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // symbols that light up when active: shuffle, repeat all, loop the current file
                FilterChip(
                    selected = snap.shuffle,
                    onClick = { player.shuffleModeEnabled = !snap.shuffle },
                    leadingIcon = { ModeSymbol(R.drawable.ic_shuffle) },
                    label = { Text(tr("Shuffle", "عشوائي")) },
                )
                FilterChip(
                    selected = snap.repeatMode == Player.REPEAT_MODE_ALL,
                    onClick = {
                        player.repeatMode =
                            if (snap.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                    },
                    leadingIcon = { ModeSymbol(R.drawable.ic_repeat) },
                    label = { Text(tr("Repeat", "تكرار الكل")) },
                )
                FilterChip(
                    selected = snap.repeatMode == Player.REPEAT_MODE_ONE,
                    onClick = {
                        player.repeatMode =
                            if (snap.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                    },
                    leadingIcon = { ModeSymbol(R.drawable.ic_repeat_one) },
                    label = { Text(tr("Loop", "تكرار الملف")) },
                )
                FilterChip(
                    selected = showList,
                    onClick = { showList = !showList },
                    label = { Text(tr("List", "القائمة") + " (${entries.size})") },
                )
                FilterChip(
                    selected = sleepLeft != null || sleepEof,
                    onClick = { showSleep = true },
                    label = { Text(sleepLeft?.let { tr("Sleep", "النوم") + " " + formatCountdown(it) } ?: tr("Sleep timer", "مؤقّت النوم")) },
                )
                FilterChip(
                    selected = false,
                    onClick = { showEq = true },
                    label = { Text(tr("Equalizer", "المعادل")) },
                )
                if (current != null) {
                    FilterChip(
                        selected = false,
                        onClick = { vm.closePlayer(); vm.openBrowser(lyricsSearchUrl(current.name)) },
                        label = { Text(tr("Lyrics", "الكلمات")) },
                    )
                }
                if (showSleep) SleepTimerDialog(onClose = { showSleep = false })
                if (showEq) EqualizerDialog(onClose = { showEq = false })

                // quick audio <-> video switch
                if (current != null && current.format == "MP4") {
                    if (snap.audioOnly) {
                        FilterChip(
                            selected = false,
                            onClick = { setAudioOnly(player, false) },
                            label = { Text(tr("Video", "فيديو")) },
                        )
                    } else {
                        FilterChip(
                            selected = false,
                            onClick = {
                                if (counterpart != null && fileExists(ctx, counterpart)) {
                                    PlayerHolder.swapCurrent(ctx, counterpart)   // the MP3 of the same video
                                } else {
                                    setAudioOnly(player, true)                   // just the audio track of this file
                                }
                            },
                            label = { Text(tr("Audio", "صوت")) },
                        )
                    }
                } else if (current != null && counterpart != null) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            if (fileExists(ctx, counterpart)) {
                                setAudioOnly(player, false)
                                PlayerHolder.swapCurrent(ctx, counterpart)       // the MP4 of the same song
                            } else {
                                toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
                            }
                        },
                        label = { Text(tr("Video", "فيديو")) },
                    )
                }
            }

            // Now playing list
            if (showList) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    itemsIndexed(entries) { i, e ->
                        val isCurrent = i == snap.index
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { player.seekTo(i, 0L); player.playWhenReady = true }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", color = Muted, modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    prettyName(e.name), maxLines = 1,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                )
                                val q = runCatching { qualityLabel(Format.valueOf(e.format), e.quality) }.getOrDefault("")
                                Text("${e.format} • $q", color = Muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { PlayerHolder.removeAt(ctx, i) }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }
}

/** The symbol inside a player-mode chip (takes the chip's colour, so it lights up when the mode is on). */
@Composable
private fun ModeSymbol(res: Int) {
    Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(18.dp))
}

/** Small bar shown above the bottom edge while something is in "Now playing" and the player screen is closed. */
@Composable
fun MiniPlayer(onOpen: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { PlayerHolder.get(ctx) }
    val entries by PlayerHolder.entries.collectAsStateWithLifecycle()
    val snap = rememberSnapshot(player)
    val cur = entries.getOrNull(snap.index) ?: return

    // playback position for the thin progress line (polled twice a second while the card is on screen)
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, snap.index) {
        while (true) {
            posMs = player.currentPosition
            durMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(500)
        }
    }

    // a floating, rounded card: [previous | seek back] [play/pause] [next | seek forward] [close]
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(prettyName(cur.name), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    tr("Now playing", "قيد التشغيل") + " • ${snap.index + 1}/${snap.count}",
                    color = Muted, fontSize = 11.sp,
                )
            }
            val many = snap.count > 1
            // one file: seek back 10 s; several files: previous track
            IconButton(
                onClick = {
                    if (many) player.seekToPreviousMediaItem()
                    else player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                },
                modifier = Modifier.size(40.dp),
            ) { Sym(if (many) R.drawable.ic_skip_previous else R.drawable.ic_replay_10, 24.dp) }
            FilledIconButton(
                onClick = {
                    if (snap.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                modifier = Modifier.size(44.dp),
            ) { Sym(if (snap.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, 24.dp) }
            // one file: seek forward 10 s; several files: next track
            IconButton(
                onClick = {
                    if (many) player.seekToNextMediaItem()
                    else {
                        val d = player.duration
                        val t = player.currentPosition + 10_000L
                        player.seekTo(if (d != C.TIME_UNSET) t.coerceAtMost(d) else t)
                    }
                },
                modifier = Modifier.size(40.dp),
            ) { Sym(if (many) R.drawable.ic_skip_next else R.drawable.ic_forward_10, 24.dp) }
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_stop, 20.dp, tint = Muted) }
        }
        // progress line: tap anywhere on it to jump there
        Box(
            Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 16.dp)
                .pointerInput(durMs) {
                    detectTapGestures { o ->
                        if (durMs > 0 && size.width > 0) player.seekTo((durMs * (o.x / size.width)).toLong().coerceIn(0L, durMs))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(Muted.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(frac).height(3.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.height(2.dp))
    }
}
