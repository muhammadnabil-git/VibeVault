package com.example.ytsaver

import androidx.compose.runtime.mutableStateMapOf
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Search results from a web search come without a length. This looks it up in the background, a few at a time, as
 * the cards appear, so the card can show the duration before the video is played. The numbers are kept for the session.
 */
object DurationCache {
    private val known = mutableStateMapOf<String, Long>()       // seconds; -1 = couldn't be found
    private val asked = HashSet<String>()
    private val gate = Semaphore(3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Seconds, or 0 when not known (yet). Reading it inside a composable updates the card when the number arrives. */
    fun get(url: String): Long = known[url]?.coerceAtLeast(0) ?: 0

    /** Starts the lookup once per address; it carries on even if the card scrolls away. */
    fun ensure(url: String) {
        synchronized(asked) { if (!asked.add(url)) return }
        scope.launch {
            gate.withPermit {
                val d = runCatching {
                    val req = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--socket-timeout", "10")
                    }
                    (YoutubeDL.getInstance().getInfo(req).duration as Number?)?.toLong() ?: 0L
                }.getOrDefault(0L)
                known[url] = if (d > 0) d else -1
            }
        }
    }
}
