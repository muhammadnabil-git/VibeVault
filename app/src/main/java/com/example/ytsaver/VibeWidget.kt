package com.example.ytsaver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Extra of the intent that a widget shortcut sends to the app: which page to open ("search", "media", "radio"...). */
const val EXTRA_VV_OPEN = "vv_open"

/**
 * Home-screen widgets of four different shapes, all driven by the one player of the app:
 *  - [VibeWidget]        wide bar (4x1): logo, what is playing, previous / play-pause / next
 *  - [VibeWidgetSquare]  square card (2x2): big title with the controls under it
 *  - [VibeWidgetRound]   round button (1x1): tap = play / pause
 *  - [VibeWidgetQuick]   wide dashboard (4x2): now playing + shortcuts to Identify, Radio, Search, Media and History
 * The buttons only control the player that already exists; if the app is not running there is nothing to control, so a
 * tap just opens the app (Android does not allow starting audio from a widget in the background).
 */
abstract class BaseVibeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        VibeWidget.refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != VibeWidget.ACTION_PLAY_PAUSE && action != VibeWidget.ACTION_NEXT && action != VibeWidget.ACTION_PREV) return
        val p = PlayerHolder.peek()
        if (p == null || p.mediaItemCount == 0) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        when (action) {
            VibeWidget.ACTION_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
            VibeWidget.ACTION_NEXT -> p.seekToNextMediaItem()
            VibeWidget.ACTION_PREV -> p.seekToPreviousMediaItem()
        }
        VibeWidget.refresh(context)
    }
}

class VibeWidget : BaseVibeWidget() {
    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.ytsaver.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.ytsaver.WIDGET_NEXT"
        const val ACTION_PREV = "com.example.ytsaver.WIDGET_PREV"

        private fun control(ctx: Context, cls: Class<*>, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, (cls.name + action).hashCode(),
                Intent(ctx, cls).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun open(ctx: Context, page: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (page != null) i.putExtra(EXTRA_VV_OPEN, page)
            return PendingIntent.getActivity(ctx, ("open:" + page).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private class Now(val title: String, val subtitle: String, val playing: Boolean, val idle: Boolean)

        private fun now(app: Context): Now {
            val p = PlayerHolder.peek()
            val cur = if (p != null) PlayerHolder.entries.value.getOrNull(p.currentMediaItemIndex) else null
            val playing = p?.isPlaying == true
            return Now(
                title = cur?.let { prettyName(it.name) } ?: app.getString(R.string.widget_idle_title),
                subtitle = if (cur == null) app.getString(R.string.widget_idle_subtitle) else (if (playing) L.t("Playing", "قيد التشغيل") else L.t("Paused", "متوقف مؤقتًا")),
                playing = playing, idle = cur == null,
            )
        }

        /** The three transport buttons that a layout may contain, wired to the player. */
        private fun wireControls(rv: RemoteViews, app: Context, cls: Class<*>, n: Now, hasPrevNext: Boolean) {
            rv.setImageViewResource(R.id.widget_play, if (n.playing) R.drawable.ic_pause else R.drawable.ic_play)
            rv.setOnClickPendingIntent(R.id.widget_play, control(app, cls, ACTION_PLAY_PAUSE))
            if (hasPrevNext) {
                rv.setOnClickPendingIntent(R.id.widget_next, control(app, cls, ACTION_NEXT))
                rv.setOnClickPendingIntent(R.id.widget_prev, control(app, cls, ACTION_PREV))
            }
        }

        /** Redraws every placed widget (of all four shapes) from the player's current state. Cheap; called when playback changes. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            fun ids(c: Class<*>) = mgr.getAppWidgetIds(ComponentName(app, c))
            val bar = ids(VibeWidget::class.java)
            val square = ids(VibeWidgetSquare::class.java)
            val round = ids(VibeWidgetRound::class.java)
            val quick = ids(VibeWidgetQuick::class.java)
            if (bar.isEmpty() && square.isEmpty() && round.isEmpty() && quick.isEmpty()) return
            val n = now(app)

            if (bar.isNotEmpty()) {
                val rv = RemoteViews(app.packageName, R.layout.widget_player)
                rv.setTextViewText(R.id.widget_title, n.title)
                rv.setTextViewText(R.id.widget_subtitle, n.subtitle)
                wireControls(rv, app, VibeWidget::class.java, n, true)
                rv.setOnClickPendingIntent(R.id.widget_root, open(app, null))
                rv.setOnClickPendingIntent(R.id.widget_logo, open(app, null))
                mgr.updateAppWidget(bar, rv)
            }
            if (square.isNotEmpty()) {
                val rv = RemoteViews(app.packageName, R.layout.widget_square)
                rv.setTextViewText(R.id.widget_title, if (n.idle) n.title + "\n" + n.subtitle else n.title)
                wireControls(rv, app, VibeWidgetSquare::class.java, n, true)
                rv.setOnClickPendingIntent(R.id.widget_root, open(app, null))
                mgr.updateAppWidget(square, rv)
            }
            if (round.isNotEmpty()) {
                val rv = RemoteViews(app.packageName, R.layout.widget_round)
                wireControls(rv, app, VibeWidgetRound::class.java, n, false)
                rv.setOnClickPendingIntent(R.id.widget_root, control(app, VibeWidgetRound::class.java, ACTION_PLAY_PAUSE))
                mgr.updateAppWidget(round, rv)
            }
            if (quick.isNotEmpty()) {
                val rv = RemoteViews(app.packageName, R.layout.widget_quick)
                rv.setTextViewText(R.id.widget_title, n.title)
                rv.setTextViewText(R.id.widget_subtitle, n.subtitle)
                wireControls(rv, app, VibeWidgetQuick::class.java, n, true)
                rv.setOnClickPendingIntent(R.id.widget_logo, open(app, null))
                rv.setOnClickPendingIntent(R.id.q_identify, open(app, "identify"))
                rv.setOnClickPendingIntent(R.id.q_radio, open(app, "radio"))
                rv.setOnClickPendingIntent(R.id.q_search, open(app, "search"))
                rv.setOnClickPendingIntent(R.id.q_media, open(app, "media"))
                rv.setOnClickPendingIntent(R.id.q_history, open(app, "history"))
                mgr.updateAppWidget(quick, rv)
            }
        }
    }
}

class VibeWidgetSquare : BaseVibeWidget()
class VibeWidgetRound : BaseVibeWidget()
class VibeWidgetQuick : BaseVibeWidget()
