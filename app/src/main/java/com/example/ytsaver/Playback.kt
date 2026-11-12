package com.example.ytsaver

import android.app.Application
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun uriReadable(ctx: Context, uri: String): Boolean = try {
    ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
} catch (_: Exception) {
    false
}

/**
 * Plays playlists and favorites in the app's one player: downloaded files straight away, web links streamed
 * (nothing is downloaded). Links are looked up one after another while the first track is already playing.
 */
object PlaylistPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
        firstItemStarted = true
    }

    /** False from the moment a new list is asked for until its first item has replaced what was in the player. */
    @Volatile var firstItemStarted = true
        private set

    /** True while links are still being looked up and added to Now playing. */
    fun isBusy(): Boolean = job?.isActive == true

    /**
     * Starts at [start], then continues to the end of the list and wraps around to the beginning.
     * With [append] nothing is replaced: the items are added to the end of Now playing (and nothing starts by itself
     * when the player was idle).
     */
    fun play(app: Application, entries: List<PlEntry>, start: Int = 0, append: Boolean = false) {
        if (entries.isEmpty()) return
        if (!append) { job?.cancel(); firstItemStarted = false }
        val s = start.coerceIn(0, entries.size - 1)
        val ordered = entries.drop(s) + entries.take(s)
        job = scope.launch {
            var first = true
            var skipped = 0
            for (e in ordered) {
                val item = try {
                    resolve(app, e)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (_: Exception) {
                    null
                }
                if (item == null) { skipped++; continue }
                withContext(Dispatchers.Main) {
                    if (first && !append) PlayerHolder.playNow(app, listOf(item)) else PlayerHolder.addToQueue(app, listOf(item))
                    firstItemStarted = true
                }
                first = false
            }
            firstItemStarted = true
            if (skipped > 0) {
                withContext(Dispatchers.Main) {
                    toast(app, L.t("$skipped item(s) couldn't be played.", "تعذّر تشغيل $skipped عنصر."))
                }
            }
        }
    }

    private fun resolve(app: Application, e: PlEntry): HistoryItem? {
        // 1) the downloaded file of this entry, when it is still on this phone
        if (e.localUri != null && uriReadable(app, e.localUri)) return itemOf(e, e.localUri, "Downloads")
        // 2) the same media downloaded on THIS phone (the playlist may come from the account / another device):
        //    play the local file, so it works offline and costs no data
        if (e.pageUrl.isNotBlank()) {
            val id = sourceId(e.pageUrl)
            val mine = HistoryStore.items.value.filter { sourceId(it.sourceUrl) == id && uriReadable(app, it.uri) }
            val pick = mine.firstOrNull { it.format == (if (e.isAudio) "MP3" else "MP4") } ?: mine.firstOrNull()
            if (pick != null) return itemOf(e, pick.uri, "Downloads")
        }
        // 3) otherwise stream it from its web link
        if (e.pageUrl.isBlank()) return null
        if (!DownloadEngine.ensureInit(app)) return null
        val s = FeedLoader.playableStream(e.pageUrl, e.isAudio) ?: return null
        val url = s.urls.firstOrNull() ?: return null
        PlayerHolder.registerHeaders(url, s.headers)
        return itemOf(e, url, "Online")
    }

    private fun itemOf(e: PlEntry, uri: String, location: String): HistoryItem {
        val safe = e.title.ifBlank { "Media" }.replace('/', ' ')
        return HistoryItem(
            name = safe + if (e.isAudio) ".mp3" else ".mp4",
            format = if (e.isAudio) "MP3" else "MP4",
            quality = 0,
            sizeBytes = 0L,
            timeMillis = System.currentTimeMillis(),
            location = location,
            uri = uri,
            sourceUrl = e.pageUrl,
        )
    }
}
