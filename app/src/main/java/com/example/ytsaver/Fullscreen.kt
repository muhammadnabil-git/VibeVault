package com.example.ytsaver

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Turns the screen to landscape when a video goes fullscreen and puts it back when fullscreen ends.
 * It remembers how the screen was before: a phone that was upright goes back upright (then auto-rotate is
 * handed back to the phone's own setting a moment later, so the app doesn't stay locked).
 */
class FullscreenOrientation(private val activity: Activity?) {
    private var previous = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var wasPortrait = true
    private var active = false
    private val handler = Handler(Looper.getMainLooper())

    fun enter() {
        val act = activity ?: return
        handler.removeCallbacksAndMessages(null)
        if (!active) {
            previous = act.requestedOrientation
            wasPortrait = act.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            active = true
        }
        // Force landscape even when the device's auto-rotate setting is off.
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    fun exit() {
        val act = activity ?: return
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        if (wasPortrait) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            handler.postDelayed({
                if (!act.isFinishing && !act.isDestroyed && !active) act.requestedOrientation = previous
            }, 700)
        } else {
            act.requestedOrientation = previous
        }
    }
}

/**
 * Fullscreen picture for a [player] that normally lives in a small card. The card stays exactly where it is underneath,
 * so closing this returns to the same place and the same playback position. Back also closes it.
 */
@OptIn(UnstableApi::class)
@Composable
fun FullscreenVideoDialog(player: Player, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val orient = remember(ctx) { FullscreenOrientation(ctx.findActivity()) }
    DisposableEffect(Unit) {
        orient.enter()
        onDispose { orient.exit() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val c = WindowCompat.getInsetsController(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)    // no sleep while watching fullscreen
            }
            onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = true
                        keepScreenOn = true
                        setFullscreenButtonClickListener { onDismiss() }
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
