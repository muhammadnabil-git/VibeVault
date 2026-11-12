package com.example.ytsaver

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.Player
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

const val EXTRA_BT_OPEN = "bt_open_player"

object BluetoothWatch {
    /** "Display over other apps": what lets the app open itself when a Bluetooth device connects. */
    fun canOpenApp(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Opens Android's "Display over other apps" page of this app. */
    fun openOverlaySettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Starts the watching service when auto-play is on and at least one device has a playlist; stops it otherwise. */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Library.load(app)
        val want = Prefs.btEnabled(app) && Library.btRules.value.isNotEmpty() && hasPermission(app)
        val i = Intent(app, BluetoothWatchService::class.java)
        try {
            if (want) ContextCompat.startForegroundService(app, i) else app.stopService(i)
        } catch (_: Exception) {
        }
    }

    /** Paired devices as (name, address). Empty when the permission is missing. */
    fun pairedDevices(ctx: Context): List<Pair<String, String>> {
        if (!hasPermission(ctx)) return emptyList()
        return try {
            val bm = ctx.getSystemService(BluetoothManager::class.java)
            bm?.adapter?.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

object BtAutoPlay {
    private var lastKey = ""
    private var lastAt = 0L
    private var tookOver = false
    private const val CHANNEL = "bt_autoplay2"   // new id: an existing channel would keep its old, lower importance
    private const val NOTE_ID = 31

    /**
     * A device connected. [audioReady] = it is the audio profile (A2DP / headset) that just came up, a second or two
     * after the first "connected" broadcast: the playlist is then not started again, it is only made sure to be playing.
     */
    fun onConnected(app: Application, dev: BluetoothDevice?, audioReady: Boolean = false) {
        if (!Prefs.btEnabled(app)) return
        L.ar = Prefs.isArabic(app)
        val addr = try { dev?.address } catch (_: Exception) { null }
        val name = try { dev?.name } catch (_: Exception) { null }
        Library.load(app)
        val rule = Library.btRules.value.firstOrNull { r ->
            (r.deviceAddress.isNotBlank() && r.deviceAddress.equals(addr, true)) ||
                (r.deviceName.isNotBlank() && name != null && r.deviceName.equals(name, true))
        } ?: return
        val pl = Library.playlists.value.firstOrNull { it.id == rule.playlistId } ?: return
        if (pl.entries.isEmpty()) return
        // the playlist's own weekly calendar / time window (Playing Patterns → Schedule)
        if (pl.schedule?.allows(java.time.LocalDateTime.now()) == false) return

        val now = System.currentTimeMillis()
        if (audioReady && rule.id == lastKey && now - lastAt < 90_000) {
            if (tookOver) keepPlaying(app, 25_000)                 // the sound route just switched to the device: be sure it plays
            return
        }
        if (rule.id == lastKey && now - lastAt < 15_000) return    // one connect = several broadcasts
        lastKey = rule.id
        lastAt = now

        // Media that is already playing keeps playing: the assigned playlist only takes over when the app is closed
        // or its player is paused / idle / finished.
        val cur = PlayerHolder.peek()
        val busy = cur != null && cur.mediaItemCount > 0 &&
            (cur.isPlaying || (cur.playWhenReady && cur.playbackState == Player.STATE_BUFFERING))
        tookOver = !busy
        if (busy) return
        holdAwake(app)

        val open = Prefs.btMode(app) == "open"
        val launch = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_BT_OPEN, open)

        // 1) the music starts first (the player was closed, paused or idle)
        PlaylistPlayer.play(app, pl.entries, 0)
        keepPlaying(app, 40_000)

        // 2) "Open the app and play": bring the app to the front (see openApp for why this needs care)
        if (open) openApp(app, launch)

        // 3) a notification as well. In "open" mode it is a heads-up one that can also wake a locked screen;
        //    tapping it always opens the app, even when Android refused to let the app open itself.
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bluetooth auto-play", NotificationManager.IMPORTANCE_HIGH),
        )
        val pi = PendingIntent.getActivity(app, 7, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setLargeIcon(appIconBitmap(app))
            .setColor(NOTIF_COLOR)
            .setContentTitle(L.t("Playing \"${pl.name}\"", "تشغيل \"${pl.name}\""))
            .setContentText(L.t("${name ?: addr ?: "Bluetooth"} connected. Tap to open the app.", "تم اتصال ${name ?: addr ?: "بلوتوث"}. اضغط لفتح التطبيق."))
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (open) b.setFullScreenIntent(pi, true)
        try { nm.notify(NOTE_ID, b.build()) } catch (_: Exception) { }
    }

    private val main = Handler(Looper.getMainLooper())
    private var keepToken = 0
    private var wake: PowerManager.WakeLock? = null

    /** The CPU stays awake for a minute: a streamed playlist has to be looked up before the first sound, with the screen off. */
    private fun holdAwake(app: Application) {
        try {
            val w = wake ?: app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibevault:btplay")
                .also { it.setReferenceCounted(false); wake = it }
            w.acquire(60_000L)
        } catch (_: Exception) { }
    }

    /**
     * Makes sure the assigned playlist really ends up playing. For [forMs] after the connection it watches the player:
     * a car that sends "pause" when it connects, an audio-focus request that Android refused, a player left idle or
     * ended, or the user's own earlier pause, are all turned back into playing.
     */
    private fun keepPlaying(app: Application, forMs: Long) {
        val token = ++keepToken
        val until = System.currentTimeMillis() + forMs
        var ticks = 0
        var playingTicks = 0          // ticks in which it was really playing
        val step = object : Runnable {
            override fun run() {
                if (token != keepToken) return
                ticks++
                val p = PlayerHolder.peek()
                // Once the music has really been playing for a moment, a later pause is the USER's (screen, car buttons,
                // notification): stop watching, otherwise the pause is undone again and again until the time runs out.
                if (p != null && p.isPlaying) playingTicks++
                else if (p != null && playingTicks >= 2 && !p.playWhenReady) { keepToken++; return }
                // the new list replaces the old one when its first item is ready; until then the old one is left alone
                if (p != null && p.mediaItemCount > 0 && PlaylistPlayer.firstItemStarted) {
                    if (p.playbackState == Player.STATE_IDLE) p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                    if (!p.playWhenReady) p.playWhenReady = true
                    // wants to play but is held back (audio focus refused in the background): go ahead without waiting for it
                    if (p.playWhenReady && !p.isPlaying && p.playbackState == Player.STATE_READY && ticks >= 3) PlayerHolder.ignoreAudioFocus()
                }
                // the list may still be loading (a streamed link): keep waiting while it is
                if (System.currentTimeMillis() < until) main.postDelayed(this, 600)
            }
        }
        main.post(step)
    }

    /**
     * Android 10+ refuses activity launches from the background. The exception it makes for an app that
     * has "Display over other apps" is, on newer versions, only for apps that also have an overlay window
     * showing. So a 1-pixel invisible overlay is shown for a moment around the launch.
     */
    private fun openApp(app: Application, launch: Intent) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            var wm: WindowManager? = null
            var marker: View? = null
            if (Settings.canDrawOverlays(app)) {
                try {
                    val mgr: WindowManager = app.getSystemService(WindowManager::class.java)
                    val v = View(app)
                    mgr.addView(
                        v,
                        WindowManager.LayoutParams(
                            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT,
                        ),
                    )
                    wm = mgr
                    marker = v
                } catch (_: Exception) {
                    marker = null
                }
            }
            try { app.startActivity(launch) } catch (_: Exception) { }    // without the permission Android may still say no: the notification covers that
            val m = marker
            val w = wm
            if (m != null && w != null) handler.postDelayed({ try { w.removeView(m) } catch (_: Exception) { } }, 2_000)
        }
    }
}

/** Waits (as a foreground service) for a Bluetooth device to connect, then starts the playlist assigned to it. */
class BluetoothWatchService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val audio = i.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED || i.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {}
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                    if (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
                else -> return
            }
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            BtAutoPlay.onConnected(application, dev, audioReady = audio)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bt_watch", "Bluetooth auto-play", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note: Notification = NotificationCompat.Builder(this, "bt_watch")
            .setSmallIcon(R.drawable.ic_stat_app)
            .setLargeIcon(appIconBitmap(this))
            .setColor(NOTIF_COLOR)
            .setContentTitle(L.t("Bluetooth auto-play is on", "التشغيل التلقائي عبر بلوتوث مفعّل"))
            .setContentText(L.t("Waiting for your devices to connect", "بانتظار اتصال أجهزتك"))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, 30, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)       // the sound route: car / speaker / headphones
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)     // hands-free headsets
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}

/** Phone restarted: start waiting for Bluetooth devices again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Library.load(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                BluetoothWatch.refresh(context)
                PlaylistScheduler.reschedule(context)      // alarms do not survive a restart
            }
            // alarms are also lost when the app is updated, and must follow a changed clock / time zone
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                PlaylistScheduler.reschedule(context)
        }
    }
}
