package com.example.ytsaver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_PLAYLIST = "playlist_flags"
        const val EXTRA_FORMATS = "formats"       // one per url
        const val EXTRA_QUALITIES = "qualities"   // one per url
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_SHOW_DETAILS = "show_details"
        const val ACTION_CANCEL = "com.example.ytsaver.CANCEL"
        const val ACTION_PAUSE = "com.example.ytsaver.PAUSE"
        const val ACTION_RESUME = "com.example.ytsaver.RESUME"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val RESULT_ID = 2
        private const val WAKE_MS = 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private lateinit var nm: NotificationManager
    private val largeIcon by lazy { appIconBitmap(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL || action == ACTION_PAUSE || action == ACTION_RESUME) {
            scope.launch {
                when (action) {
                    ACTION_CANCEL -> DownloadEngine.cancel()
                    ACTION_PAUSE -> DownloadEngine.pause()
                    else -> DownloadEngine.resume()
                }
            }
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }

        // Must go foreground right away after startForegroundService()
        ServiceCompat.startForeground(
            this, NOTIF_ID, ongoing(L.t("Starting…", "جارٍ البدء…"), 0, true, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        if (intent == null) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        val urls = intent.getStringArrayListExtra(EXTRA_URLS)
        if (urls.isNullOrEmpty()) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        val flagArr = intent.getBooleanArrayExtra(EXTRA_PLAYLIST) ?: BooleanArray(urls.size)
        val formats = intent.getStringArrayListExtra(EXTRA_FORMATS)
        val qualities = intent.getIntArrayExtra(EXTRA_QUALITIES)
        val links = urls.mapIndexed { idx, u ->
            val format = runCatching { Format.valueOf(formats?.getOrNull(idx) ?: "") }.getOrDefault(Format.MP3)
            QueueLink(
                url = u,
                playlist = flagArr.getOrElse(idx) { false },
                format = format,
                quality = qualities?.getOrNull(idx) ?: defaultQuality(format),
            )
        }

        // Links sent while a download is running simply join the queue; otherwise a new worker starts.
        if (DownloadEngine.submit(links)) startWorker()
        return START_NOT_STICKY
    }

    private fun startWorker() {
        val prev = job   // a previous worker may still be finishing its notification
        val me = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            prev?.join()
            val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mediasaver:download")
            wake.acquire(WAKE_MS)

            // Refresh the notification every ~second so status messages never get stuck
            val watcher = launch {
                while (isActive) {
                    val s = DownloadEngine.state.value
                    if (s.phase == Phase.Working) {
                        nm.notify(
                            NOTIF_ID,
                            ongoing(s.message, (s.progress * 100).toInt(), s.progress <= 0f && !s.paused, s.paused),
                        )
                        // don't keep the CPU awake while paused
                        if (s.paused) { if (wake.isHeld) wake.release() }
                        else if (!wake.isHeld) wake.acquire(WAKE_MS)
                    }
                    delay(1000)
                }
            }
            try {
                DownloadEngine.runQueue(application)
            } finally {
                watcher.cancel()
                if (wake.isHeld) wake.release()
            }

            val s = DownloadEngine.state.value
            val last = job === self          // false if a newer worker was already started for new links
            if (last) stopForeground(STOP_FOREGROUND_REMOVE)
            if (s.phase == Phase.Done || s.phase == Phase.Error) {
                val ok = s.phase == Phase.Done
                val names = s.savedNames
                val grouped = ok && names.size > 1
                val title = when {
                    !ok -> L.t("Download failed", "فشل التنزيل")
                    grouped -> L.t("Downloads complete", "اكتملت التنزيلات")
                    else -> L.t("Download complete", "اكتمل التنزيل")
                }
                val b = NotificationCompat.Builder(this@DownloadService, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_app)
                    .setColor(NOTIF_COLOR)
                    .setLargeIcon(largeIcon)
                    .setContentTitle(title)
                    .setContentText(s.message.replace("\n", " "))
                    .setContentIntent(if (ok) openHistory(grouped) else openApp())
                    .setAutoCancel(true)
                if (ok && names.size == 1) {
                    // one file: every detail is visible without expanding the notification
                    val lines = s.message.lines()
                    b.setContentText(lines.firstOrNull().orEmpty())
                    lines.getOrNull(1)?.let { b.setSubText(it) }
                } else if (grouped) {
                    // one grouped notification listing every file; tapping it shows the details
                    val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
                    names.take(6).forEach { inbox.addLine(it) }
                    if (names.size > 6) inbox.addLine("…")
                    inbox.setSummaryText(L.t("${names.size} files", "${names.size} ملفات"))
                    b.setStyle(inbox)
                } else {
                    b.setStyle(NotificationCompat.BigTextStyle().bigText(s.message))
                }
                nm.notify(RESULT_ID, b.build())
            }
            if (last) stopSelf()
        }
        job = me
        me.start()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Tapping the "download complete" notification lands on the History tab (and shows file details for groups). */
    private fun openHistory(showDetails: Boolean): PendingIntent = PendingIntent.getActivity(
        this, if (showDetails) 11 else 10,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_OPEN_TAB, 1)
            .putExtra(EXTRA_SHOW_DETAILS, showDetails),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(code: Int, action: String): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, DownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoing(text: String, pct: Int, indeterminate: Boolean, paused: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setColor(NOTIF_COLOR)
            .setLargeIcon(largeIcon)
            .setContentTitle(if (paused) "VibeVault " + L.t("(paused)", "(متوقف مؤقتًا)") else "VibeVault")
            .setContentText(text)
            .setSubText(DownloadEngine.state.value.currentTitle.ifBlank { null })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openApp())
            .apply {
                if (paused) addAction(0, L.t("Resume", "استئناف"), actionIntent(3, ACTION_RESUME))
                else addAction(0, L.t("Pause", "إيقاف مؤقت"), actionIntent(2, ACTION_PAUSE))
            }
            .addAction(0, L.t("Cancel", "إلغاء"), actionIntent(1, ACTION_CANCEL))
            .build()
}
