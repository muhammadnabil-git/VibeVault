package com.example.ytsaver

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A playing pattern of one playlist: the weekly calendar (which days) and, optionally, a time window.
 *  - It is a CONDITION: while it is on, the Bluetooth auto-play of this playlist only starts on those days / inside that window.
 *  - With [autoStart] the playlist also starts by itself at [startMin] on those days.
 */
data class PlSchedule(
    val enabled: Boolean = false,
    val days: Int = ALL_DAYS,         // bit 0 = Monday ... bit 6 = Sunday
    val allDay: Boolean = true,       // true = the whole day, false = only between startMin and endMin
    val startMin: Int = 8 * 60,       // minutes after midnight
    val endMin: Int = 22 * 60,
    val autoStart: Boolean = false,
) {
    fun hasDay(dayIndex: Int) = days and (1 shl dayIndex) != 0

    /** True when the playlist may play at this moment. A playlist without an active schedule always may. */
    fun allows(now: LocalDateTime): Boolean {
        if (!enabled) return true
        val today = now.dayOfWeek.value - 1
        val yesterday = (today + 6) % 7
        if (allDay || startMin == endMin) return hasDay(today)
        val m = now.hour * 60 + now.minute
        return if (startMin < endMin) hasDay(today) && m >= startMin && m < endMin
        else (hasDay(today) && m >= startMin) || (hasDay(yesterday) && m < endMin)   // window across midnight
    }

    /** The first auto-start moment strictly after [after]. */
    fun nextStart(after: LocalDateTime): LocalDateTime? {
        for (d in 0..7) {
            val date = after.toLocalDate().plusDays(d.toLong())
            if (!hasDay(date.dayOfWeek.value - 1)) continue
            val t = date.atTime(startMin / 60, startMin % 60)
            if (t.isAfter(after)) return t
        }
        return null
    }

    /** The latest auto-start moment that is not after [now] and not older than 10 minutes (the alarm may arrive a bit late). */
    fun recentStart(now: LocalDateTime): LocalDateTime? {
        for (d in 0..1) {
            val date = now.toLocalDate().minusDays(d.toLong())
            if (!hasDay(date.dayOfWeek.value - 1)) continue
            val t = date.atTime(startMin / 60, startMin % 60)
            if (!t.isAfter(now) && t.isAfter(now.minusMinutes(10))) return t
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("on", enabled).put("days", days).put("all", allDay)
        .put("s", startMin).put("e", endMin).put("auto", autoStart)

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111   // Mon - Fri
        const val WEEKEND = 0b1100000    // Sat, Sun

        fun fromJson(o: JSONObject?): PlSchedule? = o?.let {
            PlSchedule(
                enabled = it.optBoolean("on"), days = it.optInt("days", ALL_DAYS), allDay = it.optBoolean("all", true),
                startMin = it.optInt("s", 8 * 60), endMin = it.optInt("e", 22 * 60), autoStart = it.optBoolean("auto"),
            )
        }
    }
}

private val DAYS_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAYS_AR = listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")

fun dayName(i: Int): String = if (L.ar) DAYS_AR[i] else DAYS_EN[i]

/** 12-hour clock with AM / PM: 0 -> "12:00 AM", 13*60+5 -> "1:05 PM". (Stored values are always minutes after midnight, 0-1439.) */
fun hhmm(min: Int): String {
    val h = (min / 60) % 24
    val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ap = if (h < 12) L.t("AM", "ص") else L.t("PM", "م")
    return String.format(Locale.US, "%d:%02d %s", h12, m, ap)
}

/** One line for lists: "Mon, Wed • 08:00–22:00" (or "Every day", "Off"). */
fun PlSchedule?.summary(): String {
    if (this == null || !enabled) return L.t("No schedule", "بدون جدول")
    val dayText = when (days) {
        PlSchedule.ALL_DAYS -> L.t("Every day", "كل يوم")
        PlSchedule.WEEKDAYS -> L.t("Weekdays", "أيام الأسبوع")
        PlSchedule.WEEKEND -> L.t("Weekend", "عطلة نهاية الأسبوع")
        0 -> L.t("No day selected", "لم تُحدَّد أيام")
        else -> (0..6).filter { hasDay(it) }.joinToString(", ") { dayName(it) }
    }
    val time = if (allDay) "" else " • ${hhmm(startMin)}–${hhmm(endMin)}"
    val auto = if (autoStart) " • " + L.t("starts at ${hhmm(startMin)}", "تبدأ ${hhmm(startMin)}") else ""
    return dayText + time + auto
}

// ----------------------------------------------------------------------
// Alarm: starts playlists that have "start automatically" on
// ----------------------------------------------------------------------

object PlaylistScheduler {
    const val ACTION = "com.example.ytsaver.SCHEDULE_FIRE"
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun pending(app: Context): PendingIntent =
        PendingIntent.getBroadcast(
            app, 4711, Intent(app, ScheduleReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Sets (or clears) the one alarm for the nearest auto-start of all playlists. Call after every change and after a restart.
     * It is an "alarm clock" alarm: exact to the minute, delivered even in Doze, needs no special permission, and Android lets
     * the app start its foreground playback service when it fires, even if the app is closed and nothing is connected.
     */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(app)
        am.cancel(pi)
        val now = LocalDateTime.now()
        val next = Library.playlists.value.mapNotNull { p ->
            val s = p.schedule
            if (s != null && s.enabled && s.autoStart && s.days != 0) s.nextStart(now) else null
        }.minOrNull() ?: return
        val at = next.atZone(zone).toInstant().toEpochMilli()
        try {
            val show = PendingIntent.getActivity(
                app, 4712, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } catch (_: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) { }
        }
    }

    /**
     * The alarm went off: play the playlist whose start time it is, then set the next alarm.
     * @return the playlist that was started, or null when nothing was due.
     */
    fun fire(app: Application): Playlist? {
        Library.load(app)
        val now = LocalDateTime.now()
        var started: Playlist? = null
        for (p in Library.playlists.value) {
            val s = p.schedule ?: continue
            if (!s.enabled || !s.autoStart || p.entries.isEmpty()) continue
            val t = s.recentStart(now) ?: continue
            val stamp = t.atZone(zone).toInstant().toEpochMilli()
            if (Prefs.long(app, "sched_last_${p.id}") >= stamp) continue     // already started for this moment
            Prefs.putLong(app, "sched_last_${p.id}", stamp)
            if (started == null) {
                started = p
                PlaylistPlayer.play(app, p.entries, 0)
            }
        }
        reschedule(app)
        return started
    }
}

/**
 * The alarm arrives here. A receiver only lives a few seconds, but a playlist of web links needs longer to be
 * looked up and then has to play to its end, so the work is handed to [ScheduleService] (a foreground service).
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? Application ?: return
        Library.load(app)
        L.ar = Prefs.isArabic(app)
        if (intent.action == PlaylistScheduler.ACTION) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, ScheduleService::class.java))
            } catch (_: Exception) {
                PlaylistScheduler.fire(app)        // best effort when Android refuses the service
            }
        } else {
            PlaylistScheduler.reschedule(app)
        }
    }
}

/**
 * Keeps the app alive (foreground service + wake lock) from the moment a scheduled playlist starts until it has played
 * to its end, so playback never depends on the app being open, on Bluetooth, or on the screen being on.
 */
class ScheduleService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var running = false
    private var startedAt = 0L
    private var pausedSince = 0L
    private var playedOnce = false
    private var kicks = 0

    private val tick = object : Runnable {
        override fun run() {
            if (stillNeeded()) handler.postDelayed(this, 1_500) else finish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sched_play", "Scheduled playlists", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "sched_play")
            .setSmallIcon(R.drawable.ic_stat_app)
            .setColor(NOTIF_COLOR)
            .setContentTitle(L.t("Scheduled playlist", "قائمة تشغيل مجدولة"))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application
        L.ar = Prefs.isArabic(app)
        // must become a foreground service at once; it is allowed because the alarm just fired
        try {
            ServiceCompat.startForeground(
                this, 41, notification(L.t("Starting…", "جارٍ البدء…")),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (_: Exception) {
        }
        if (wake == null) {
            wake = try {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibevault:schedule")
                    .also { it.acquire(3 * 60_000L) }
            } catch (_: Exception) {
                null
            }
        }
        val p = PlaylistScheduler.fire(app)
        if (p != null) {
            running = true
            startedAt = System.currentTimeMillis()
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(41, notification(L.t("Playing \"${p.name}\"", "تشغيل \"${p.name}\"")))
            } catch (_: Exception) {
            }
            playedOnce = false
            kicks = 0
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 1_500)
        } else if (!running) {
            finish()
        }
        return START_NOT_STICKY
    }

    /** True while the playlist is still being looked up or is playing (or was paused for less than 10 minutes). */
    private fun stillNeeded(): Boolean {
        val now = System.currentTimeMillis()
        val busy = PlaylistPlayer.isBusy()
        val p = PlayerHolder.peek()
        if (p == null || p.mediaItemCount == 0) return busy && now - startedAt < 3 * 60_000L
        ensureStarted(p, now)
        if (p.playbackState == Player.STATE_ENDED) return busy
        return if (p.playWhenReady) {
            pausedSince = 0L
            true
        } else {
            if (pausedSince == 0L) pausedSince = now
            now - pausedSince < 10 * 60_000L
        }
    }

    /**
     * The first item must really start: being listed in the media notification is not enough. While nothing has played yet,
     * a player that is idle, paused or held back by a refused audio-focus request is started again; after a few tries it
     * stops waiting for audio focus. Once it has played, the user stays in control (pausing is respected).
     */
    private fun ensureStarted(p: androidx.media3.exoplayer.ExoPlayer, now: Long) {
        if (p.isPlaying) { playedOnce = true; return }
        if (playedOnce || now - startedAt > 90_000L) return
        if (p.playbackState == Player.STATE_ENDED) return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        kicks++
        if (kicks >= 3 && (p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE || !p.playWhenReady)) {
            PlayerHolder.ignoreAudioFocus()
        }
        p.playWhenReady = true
        p.play()
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wake = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        super.onDestroy()
    }
}

// ----------------------------------------------------------------------
// The editor
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    // 12-hour dial with the AM / PM selector; state.hour is still 0-23, so what is saved is unambiguous
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(tr("OK", "موافق")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** Weekly calendar + time window of one playlist. */
@Composable
fun ScheduleDialog(playlist: Playlist, onDismiss: () -> Unit) {
    val start = playlist.schedule ?: PlSchedule()
    var enabled by remember { mutableStateOf(start.enabled) }
    var days by remember { mutableIntStateOf(start.days) }
    var limit by remember { mutableStateOf(!start.allDay) }
    var from by remember { mutableIntStateOf(start.startMin) }
    var to by remember { mutableIntStateOf(start.endMin) }
    var auto by remember { mutableStateOf(start.autoStart) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    val order = if (LocalAr.current) listOf(5, 6, 0, 1, 2, 3, 4) else (0..6).toList()    // the Arabic week starts on Saturday

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Schedule", "الجدول"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(playlist.name, color = Muted, maxLines = 1)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Use a schedule for this playlist", "استخدام جدول لهذه القائمة"), fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("When on, the Bluetooth auto-play of this playlist only starts on the chosen days and inside the chosen time window.",
                                    "عند التفعيل، يبدأ التشغيل التلقائي بالبلوتوث لهذه القائمة فقط في الأيام والأوقات المحددة."),
                                color = Muted, fontSize = 12.sp,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }

                    if (enabled) {
                        Text(tr("Days of the week", "أيام الأسبوع"), color = Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            order.forEach { i ->
                                val on = days and (1 shl i) != 0
                                Box(
                                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (on) MaterialTheme.colorScheme.primary else Muted.copy(alpha = 0.15f))
                                        .clickable { days = days xor (1 shl i) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        dayName(i), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center,
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { days = PlSchedule.ALL_DAYS }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("Every day", "كل يوم"), fontSize = 12.sp) }
                            TextButton(onClick = { days = PlSchedule.WEEKDAYS }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("Weekdays", "أيام الأسبوع"), fontSize = 12.sp) }
                            TextButton(onClick = { days = PlSchedule.WEEKEND }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("Weekend", "العطلة"), fontSize = 12.sp) }
                        }

                        Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Only within a time window", "ضمن فترة زمنية محددة فقط"), fontWeight = FontWeight.SemiBold)
                                Text(tr("Off = the whole day.", "إيقاف = اليوم كاملًا."), color = Muted, fontSize = 12.sp)
                            }
                            Switch(checked = limit, onCheckedChange = { limit = it })
                        }
                        if (limit || auto) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { pickFrom = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                    Text(tr("From", "من") + " " + hhmm(from))
                                }
                                if (limit) OutlinedButton(onClick = { pickTo = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                    Text(tr("To", "إلى") + " " + hhmm(to))
                                }
                            }
                            if (limit && from > to) {
                                Text(tr("The window continues past midnight into the next day.", "تمتد الفترة بعد منتصف الليل إلى اليوم التالي."), color = Muted, fontSize = 12.sp)
                            }
                        }

                        Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Start playing automatically", "بدء التشغيل تلقائيًا"), fontWeight = FontWeight.SemiBold)
                                Text(
                                    tr("Plays this playlist by itself at the start time on the chosen days, even if the app is closed and no Bluetooth device is connected, and keeps playing until the playlist ends.",
                                        "تُشغَّل القائمة تلقائيًا في وقت البدء في الأيام المحددة، حتى لو كان التطبيق مغلقًا ولا يوجد جهاز بلوتوث متصل، وتستمر حتى نهاية القائمة."),
                                    color = Muted, fontSize = 12.sp,
                                )
                            }
                            Switch(checked = auto, onCheckedChange = { auto = it })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) }
                    TextButton(onClick = {
                        Library.setSchedule(playlist.id, PlSchedule(enabled, days, !limit, from, to, auto))
                        onDismiss()
                    }) { Text(tr("Save", "حفظ")) }
                }
            }
        }
    }
    if (pickFrom) TimeDialog(tr("From", "من"), from, { pickFrom = false }) { from = it; pickFrom = false }
    if (pickTo) TimeDialog(tr("To", "إلى"), to, { pickTo = false }) { to = it; pickTo = false }
}
