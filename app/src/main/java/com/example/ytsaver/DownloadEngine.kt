package com.example.ytsaver

import android.app.Application
import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class Format { MP3, MP4 }
enum class Phase { Idle, Working, Done, Error }

data class JobState(
    val phase: Phase = Phase.Idle,
    val progress: Float = 0f,
    val message: String = "",
    val engineReady: Boolean = false,
    val paused: Boolean = false,
    val updating: Boolean = false,
    val currentTitle: String = "",
    val savedNames: List<String> = emptyList(),
    /** [taskKey] of the file being downloaded right now. */
    val activeKey: String = "",
    /** [taskKey]s of the files already handled (saved, skipped or failed) in the current run. */
    val finishedKeys: Set<String> = emptySet(),
    /** [originKey]s of the lines whose files were all saved (or were already saved) in the current run. */
    val okKeys: Set<String> = emptySet(),
    /** [originKey]s of the lines where something failed in the current run. */
    val badKeys: Set<String> = emptySet(),
)

/** One link the user entered, with its own format + quality; [playlist] = expand it into its videos. */
data class QueueLink(val url: String, val playlist: Boolean, val format: Format, val quality: Int)

private data class Task(val url: String, val title: String, val format: Format, val quality: Int, val origin: String)

/** Identifies the line a link came from (a playlist line expands into many files that share it). */
fun originKey(l: QueueLink): String = taskKey(l.url, l.format, l.quality) + if (l.playlist) "|list" else ""

/** Same video + same format + same quality = same download. */
fun taskKey(url: String, format: Format, quality: Int): String = "${sourceId(url)}|${format.name}|$quality"

private sealed interface Outcome {
    data class Saved(val name: String, val size: Long, val uri: String) : Outcome
    data class Failed(val message: String) : Outcome
    object Cancelled : Outcome
}

fun qualityOptions(f: Format): List<Int> =
    if (f == Format.MP3) listOf(320, 192, 128) else listOf(1080, 720, 480, 360)

fun defaultQuality(f: Format): Int = if (f == Format.MP3) 320 else 720

fun qualityLabel(f: Format, q: Int): String = if (f == Format.MP3) "$q kbps" else "${q}p"

object DownloadEngine {
    private const val PROCESS_ID = "ytsaver"
    private const val SAVE_DIR = "MediaSaver"          // folder name kept so files saved by older versions stay in the same place
    private const val MIN_FREE_BYTES = 100_000_000L     // a download is not started with less than 100 MB free
    private const val MIN_BYTES = 50_000L                      // smaller = treated as a failed download
    private const val MAX_RETRIES = 8                          // retries while online but connection is flaky
    private const val MAX_OFFLINE_WAIT_MS = 30 * 60 * 1000L    // wait up to 30 min for the network to return
    private const val PARTIAL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // unfinished downloads kept 7 days
    private const val HOURS6 = 6L * 60 * 60 * 1000

    private val _state = MutableStateFlow(JobState(message = L.t("Starting engine…", "جارٍ تشغيل المحرك…")))
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var cancelled = false
    @Volatile private var paused = false
    @Volatile private var inDownload = false   // true only while a file is actually downloading
    @Volatile private var prefix = ""          // "(3/25) " in front of status messages for queues

    // ---- network switching (Wi-Fi <-> mobile data) must not stop a download ----
    @Volatile private var netEpoch = 0                 // +1 every time the default network changes or drops
    @Volatile private var restartRequested = false     // we killed yt-dlp on purpose because the network changed
    @Volatile private var processing = false           // download finished, now merging / converting (no network needed)
    @Volatile private var everSawNetwork = false
    @Volatile private var lastNetwork: Network? = null
    @Volatile private var lastRestartMs = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Watches the default network. When it switches while a file is downloading, the interrupted file is restarted at once. */
    private fun startNetworkWatch(app: Application) {
        stopNetworkWatch(app)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = lastNetwork
                lastNetwork = network
                val switched = everSawNetwork && prev != network
                everSawNetwork = true
                if (switched) onNetworkSwitched()
            }

            override fun onLost(network: Network) {
                if (lastNetwork == network) {
                    lastNetwork = null
                    netEpoch++
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {
            // without the watcher the retry loop below still recovers, just a little later
        }
    }

    private fun stopNetworkWatch(app: Application) {
        val cb = netCallback ?: return
        netCallback = null
        try {
            app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        everSawNetwork = false
        lastNetwork = null
    }

    private fun onNetworkSwitched() {
        netEpoch++
        if (!inDownload || paused || cancelled || processing) return
        val now = System.currentTimeMillis()
        if (now - lastRestartMs < 4000) return      // several callbacks for one switch
        lastRestartMs = now
        // The old connection is dead (and YouTube links are tied to the old IP address): stop yt-dlp now.
        // downloadOne() starts it again; it resolves the link on the new network and continues the saved .part files.
        restartRequested = true
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    // Links waiting to be picked up by the worker. New links can arrive at any time, also while it is running.
    private val lock = Any()
    private val pending = ArrayDeque<QueueLink>()
    private var running = false                // guarded by lock

    /**
     * Adds links to the queue. Returns true when no worker is running, meaning the caller must now
     * start one ([runQueue]); false when the links simply joined the run that is already in progress.
     */
    fun submit(links: List<QueueLink>): Boolean = synchronized(lock) {
        pending.addAll(links)
        if (running) false else { running = true; true }
    }

    @Synchronized
    fun ensureInit(app: Application): Boolean {
        if (initialized) return true
        return try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
            _state.update {
                it.copy(
                    engineReady = true,
                    message = if (it.phase == Phase.Idle && !it.updating)
                        L.t("Paste a link to start", "الصق رابطًا للبدء") else it.message,
                )
            }
            true
        } catch (e: Exception) {
            _state.update {
                it.copy(phase = Phase.Error, message = L.t("Engine failed to start: ", "تعذّر تشغيل المحرك: ") + e.message)
            }
            false
        }
    }

    /** Returns true if the engine is now up to date. Logs the result in the notification bell. */
    fun updateEngine(app: Application, auto: Boolean = false): Boolean {
        _state.update {
            it.copy(
                updating = true,
                message = if (auto) L.t("Updating download engine…", "جارٍ تحديث محرك التنزيل…")
                else L.t("Updating engine…", "جارٍ تحديث المحرك…"),
            )
        }
        return try {
            // The nightly channel carries the fixes for YouTube changes first; the stable channel is the fallback.
            val result = try {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY)
            } catch (e: Exception) {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            }
            val status = result?.toString() ?: "DONE"
            Prefs.setLastEngineUpdate(app, System.currentTimeMillis())
            val text = if (status.contains("ALREADY"))
                L.t("Download engine is already up to date.", "محرك التنزيل محدّث بالفعل.")
            else L.t("Download engine updated to the latest version.", "تم تحديث محرك التنزيل إلى أحدث إصدار.")
            _state.update { it.copy(updating = false, message = text) }
            // the automatic check at every launch stays quiet when there was nothing new
            if (!(auto && status.contains("ALREADY"))) NotificationStore.add(app, "ENGINE_UPDATED", L.t("Download engine", "محرك التنزيل"), text)
            true
        } catch (e: Exception) {
            _state.update { it.copy(updating = false, message = L.t("Update failed: ", "فشل التحديث: ") + e.message) }
            if (!auto) NotificationStore.add(
                app, "ENGINE_UPDATE_FAILED", L.t("Engine update failed", "فشل تحديث المحرك"),
                L.t("Check your internet connection and try again.", "تحقّق من اتصالك بالإنترنت وحاول مرة أخرى."),
            )
            false
        }
    }

    fun markStarting() =
        _state.update {
            it.copy(
                phase = Phase.Working, progress = 0f, message = L.t("Starting…", "جارٍ البدء…"),
                paused = false, currentTitle = "", savedNames = emptyList(),
                activeKey = "", finishedKeys = emptySet(), okKeys = emptySet(), badKeys = emptySet(),
            )
        }

    fun reportError(msg: String) =
        _state.update { it.copy(phase = Phase.Error, message = msg, paused = false, currentTitle = "", activeKey = "") }

    /** Stop and discard the partial files (the whole queue stops, including links still waiting). */
    fun cancel() {
        cancelled = true
        paused = false
        synchronized(lock) { pending.clear() }
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    /** Stop the download process but keep the partial files. */
    fun pause() {
        if (_state.value.phase != Phase.Working || paused || !inDownload) return
        paused = true
        _state.update { it.copy(message = L.t("Pausing…", "جارٍ الإيقاف المؤقت…"), paused = true) }
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    fun resume() {
        if (!paused) return
        paused = false
        _state.update { it.copy(message = L.t("Resuming…", "جارٍ الاستئناف…"), paused = false) }
    }

    // ------------------------------------------------------------------
    // Queue: single links, bulk links and whole playlists
    // ------------------------------------------------------------------

    /**
     * Blocking. Call from a background thread (the service does) after [submit] returned true.
     * Keeps working until the queue is empty. Links submitted while it runs are picked up between files.
     */
    fun runQueue(app: Application) {
        if (!ensureInit(app)) {
            synchronized(lock) { pending.clear(); running = false }
            return
        }
        cancelled = false
        paused = false
        prefix = ""
        startNetworkWatch(app)
        markStarting()
        _state.update { it.copy(message = L.t("Preparing…", "جارٍ التحضير…")) }

        val tasks = ArrayDeque<Task>()
        val seen = HashSet<String>()
        val expandErrors = mutableListOf<String>()
        val files = mutableListOf<NotifFile>()
        var total = 0
        var index = 0
        var saved = 0
        var skipped = 0
        var failed = 0
        var lastName = ""
        var lastSize = 0L
        var lastError = ""
        var lastFormat = Format.MP3
        var lastQuality = 0
        var closed = false   // true once the queue was emptied and the worker slot was released

        fun addTask(t: Task) {
            if (seen.add(taskKey(t.url, t.format, t.quality))) { tasks.addLast(t); total++ }
        }

        try {
            while (!cancelled) {
                // 1) take whatever was queued since the last round; expand playlists into single videos
                val batch = synchronized(lock) { pending.toList().also { pending.clear() } }
                for (l in batch) {
                    if (cancelled) break
                    val origin = originKey(l)
                    if (l.playlist) {
                        _state.update { it.copy(message = prefix + L.t("Reading playlist…", "جارٍ قراءة قائمة التشغيل…")) }
                        try {
                            val info = PlaylistLoader.load(playlistUrl(l.url), PROCESS_ID)
                            if (info.entries.isEmpty()) {
                                expandErrors += L.t("Playlist is empty or private.", "قائمة التشغيل فارغة أو خاصة.")
                                _state.update { it.copy(badKeys = it.badKeys + origin) }
                            } else {
                                info.entries.forEach { addTask(Task(it.url, it.title, l.format, l.quality, origin)) }
                            }
                        } catch (e: Exception) {
                            if (!cancelled) {
                                expandErrors += lastLine(e)
                                _state.update { it.copy(badKeys = it.badKeys + origin) }
                            }
                        }
                    } else {
                        addTask(Task(l.url, "", l.format, l.quality, origin))
                    }
                }
                if (cancelled) break

                val t = tasks.removeFirstOrNull()
                if (t == null) {
                    // nothing left: finish, unless more links arrived in the meantime
                    val finished = synchronized(lock) {
                        if (pending.isEmpty()) { running = false; true } else false
                    }
                    if (finished) { closed = true; break } else continue
                }

                // 2) download one by one
                index++
                val key = taskKey(t.url, t.format, t.quality)
                prefix = if (total > 1) "($index/$total) " else ""
                lastFormat = t.format
                lastQuality = t.quality
                _state.update {
                    it.copy(
                        progress = 0f, currentTitle = t.title, activeKey = key,
                        message = prefix + L.t("Starting…", "جارٍ البدء…"),
                    )
                }

                if (HistoryStore.findExisting(app, t.url, t.format.name, t.quality) != null) {
                    skipped++
                    _state.update { it.copy(finishedKeys = it.finishedKeys + key, okKeys = it.okKeys + t.origin) }
                    continue
                }
                var good = false
                var bad = false
                when (val o = downloadOne(app, t.url, t.format, t.quality)) {
                    is Outcome.Saved -> {
                        saved++; lastName = o.name; lastSize = o.size; good = true
                        files += NotifFile(o.name, t.format.name, t.quality, o.size, o.uri)
                    }
                    is Outcome.Failed -> { failed++; lastError = o.message; bad = true }
                    Outcome.Cancelled -> break
                }
                _state.update {
                    it.copy(
                        finishedKeys = it.finishedKeys + key,
                        okKeys = if (good) it.okKeys + t.origin else it.okKeys,
                        badKeys = if (bad) it.badKeys + t.origin else it.badKeys,
                    )
                }
            }
        } finally {
            stopNetworkWatch(app)
            // normal end already released the slot under the lock; cancel / crash release it here
            if (!closed) synchronized(lock) { pending.clear(); running = false }
        }
        prefix = ""
        if (cancelled) { onCancelled(saved); return }

        // 3) summary
        if (total == 0) {
            val err = expandErrors.firstOrNull() ?: L.t("no videos found", "لم يتم العثور على فيديوهات")
            failedSummary(app, L.t("Failed: ", "فشل: ") + err, err)
            return
        }
        if (lastError.isEmpty()) lastError = expandErrors.firstOrNull() ?: ""
        when {
            saved > 0 -> {
                val pretty = prettyName(lastName)
                val msg = if (total == 1) {
                    L.t("Saved ", "تم الحفظ ") + lastName + "\n${formatSize(lastSize)} → Downloads/$SAVE_DIR"
                } else buildString {
                    append(L.t("Downloaded $saved of $total", "تم تنزيل $saved من $total"))
                    if (skipped > 0) append(L.t(" • $skipped already downloaded (skipped)", " • تم تخطي $skipped (تم تنزيلها مسبقًا)"))
                    if (failed > 0) append(L.t(" • $failed failed", " • فشل $failed"))
                    append("\n").append(L.t("Saved in Downloads/$SAVE_DIR", "الحفظ في Downloads/$SAVE_DIR"))
                }
                _state.update {
                    it.copy(
                        phase = Phase.Done, progress = 1f, message = msg, currentTitle = "", activeKey = "",
                        savedNames = files.map { f -> prettyName(f.name) },
                    )
                }
                NotificationStore.add(
                    app, "DONE",
                    if (total == 1) L.t("Download complete", "اكتمل التنزيل") else L.t("Downloads complete", "اكتملت التنزيلات"),
                    if (total == 1) "$pretty • ${formatSize(lastSize)}" else msg.substringBefore('\n'),
                    files,
                )
                if (failed > 0 && looksOutdated(lastError)) suggestUpdate(app)
            }
            failed > 0 -> {
                val msg = if (total == 1) L.t("Failed: ", "فشل: ") + lastError
                else L.t("Failed: $failed of $total items failed. Last error: $lastError", "فشل: $failed من $total عناصر. آخر خطأ: $lastError")
                failedSummary(app, msg, lastError)
            }
            else -> {
                val msg = if (total == 1) {
                    val q = qualityLabel(lastFormat, lastQuality)
                    L.t("Already downloaded as ${lastFormat.name} at $q. Pick another format or quality to download it again.",
                        "تم التنزيل مسبقًا بصيغة ${lastFormat.name} وجودة $q. اختر صيغة أو جودة أخرى لتنزيله مجددًا.")
                } else {
                    L.t("All $total items were already downloaded in the selected formats and qualities.",
                        "جميع العناصر ($total) تم تنزيلها مسبقًا بالصيغ والجودات المختارة.")
                }
                _state.update { it.copy(phase = Phase.Idle, progress = 0f, message = msg, currentTitle = "", activeKey = "") }
                NotificationStore.add(app, "INFO", L.t("Nothing new to download", "لا يوجد جديد للتنزيل"), msg)
            }
        }
    }

    private fun failedSummary(app: Application, message: String, rawError: String) {
        val outdated = looksOutdated(rawError)
        reportError(
            if (outdated) message + "\n" + L.t("Try \"Update download engine\" below.", "جرّب «تحديث محرك التنزيل» بالأسفل.")
            else message,
        )
        NotificationStore.add(app, "FAILED", L.t("Download failed", "فشل التنزيل"), rawError.take(160))
        if (outdated) suggestUpdate(app)
    }

    private fun suggestUpdate(app: Application) =
        NotificationStore.addOnce(
            app, "ENGINE_NEEDS_UPDATE", L.t("Engine update needed", "مطلوب تحديث المحرك"),
            L.t("Some downloads failed in a way an engine update usually fixes.",
                "فشلت بعض التنزيلات بسبب مشكلة يحلّها تحديث المحرك عادةً."),
            HOURS6,
        )

    private fun looksOutdated(err: String): Boolean {
        val l = err.lowercase()
        return listOf(
            "sign in", "unable to extract", "nsig", "http error 403", "format is not available",
            "po token", "not a bot",
        ).any { it in l }
    }

    // ------------------------------------------------------------------
    // One file: pause/resume, automatic resume after network loss
    // ------------------------------------------------------------------

    private fun downloadOne(app: Application, url: String, format: Format, quality: Int): Outcome {
        val mp3 = format == Format.MP3
        cleanOldPartials(app)
        // Same video + format + quality => same folder => yt-dlp continues the .part files
        val work = workDir(app, url, format, quality).apply { mkdirs() }
        val savedBytes = work.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (savedBytes > 0) {
            _state.update {
                it.copy(message = prefix + L.t("Resuming (${formatSize(savedBytes)} already saved)…", "جارٍ الاستئناف (تم حفظ ${formatSize(savedBytes)})…"))
            }
        }
        work.setLastModified(System.currentTimeMillis())

        var keepPartial = true // partial files survive network failures and app kills
        inDownload = true
        try {
            var attempt = 0
            var updatedOnce = false              // "sign in / bot / extract" errors: update the engine once and try again
            var altClients = false               // …with other YouTube client types
            var embed = Prefs.embedMeta(app)     // Settings -> Behavior: title, artist and cover art inside the file
            while (true) {
                if (paused && !cancelled) {
                    waitWhilePaused()
                    if (cancelled) return Outcome.Cancelled
                    _state.update { it.copy(message = prefix + L.t("Resuming…", "جارٍ الاستئناف…"), paused = false) }
                }
                val epochAtStart = netEpoch
                restartRequested = false
                try {
                    executeOnce(url, format, quality, work, embed, altClients)
                    break
                } catch (e: Exception) {
                    if (cancelled) return Outcome.Cancelled
                    if (paused) continue // we stopped the process on purpose; loop top waits for Resume

                    // writing the title / artist / cover art failed: the media itself is fine, so go again without that step
                    if (embed && Regex("thumbnail|embed|metadata|postprocess|mutagen|atomicparsley", RegexOption.IGNORE_CASE)
                            .containsMatchIn(e.message ?: "")) {
                        embed = false
                        continue
                    }

                    val errLine = lastLine(e)
                    // the network changed (or dropped) while this attempt was running
                    val switched = restartRequested || netEpoch != epochAtStart
                    restartRequested = false
                    val offline = !isOnline(app)

                    if (!offline && !switched && !updatedOnce && looksOutdated(errLine)) {
                        updatedOnce = true
                        altClients = true
                        _state.update { it.copy(message = prefix + L.t("Updating the download engine and trying again…", "جارٍ تحديث المحرك وإعادة المحاولة…")) }
                        updateEngine(app, auto = true)
                        _state.update { it.copy(updating = false) }
                        continue
                    }
                    if (!offline && !switched && !looksLikeNetworkError(errLine)) {
                        keepPartial = false // real error (sign-in, private video…): nothing worth resuming
                        return Outcome.Failed(errLine)
                    }
                    if (!offline && !switched && ++attempt > MAX_RETRIES) {
                        return Outcome.Failed(
                            L.t("Connection problem. Your progress is saved. Tap Download to resume.",
                                "مشكلة في الاتصال. تم حفظ تقدّمك. اضغط تنزيل للاستئناف."),
                        )
                    }
                    if (offline) {
                        if (!waitForNetwork(app)) {
                            return if (cancelled) Outcome.Cancelled
                            else Outcome.Failed(
                                L.t("No connection for too long. Your progress is saved. Tap Download to resume.",
                                    "انقطع الاتصال لفترة طويلة. تم حفظ تقدّمك. اضغط تنزيل للاستئناف."),
                            )
                        }
                    } else if (switched) {
                        // Wi-Fi <-> mobile data: not an error, just carry on with the saved progress
                        _state.update {
                            it.copy(message = prefix + L.t("Network changed. Resuming…", "تغيّرت الشبكة. جارٍ الاستئناف…"))
                        }
                        sleepUnlessCancelled(1500)
                    } else {
                        _state.update {
                            it.copy(message = prefix + L.t("Connection problem. Retrying ($attempt/$MAX_RETRIES)…",
                                "مشكلة في الاتصال. إعادة المحاولة ($attempt/$MAX_RETRIES)…"))
                        }
                        sleepUnlessCancelled(3000L * attempt)
                    }
                    if (cancelled) return Outcome.Cancelled
                    if (!paused) _state.update { it.copy(message = prefix + L.t("Resuming…", "جارٍ الاستئناف…")) }
                }
            }
            paused = false
            _state.update { it.copy(paused = false) }
            if (cancelled) return Outcome.Cancelled

            // ---- File-size check: no more 0-byte "successes" ----
            val wantedExt = if (mp3) "mp3" else "mp4"
            val leftoverStream = Regex("""\.f\d+[\w-]*\.""") // un-merged video/audio pieces
            val out = work.listFiles()
                ?.filter { it.isFile && it.extension.equals(wantedExt, true) && !leftoverStream.containsMatchIn(it.name) }
                ?.maxByOrNull { it.length() }

            return when {
                out == null -> {
                    keepPartial = false
                    Outcome.Failed(L.t("no $wantedExt file was produced.", "لم يتم إنشاء ملف $wantedExt."))
                }
                out.length() < MIN_BYTES -> {
                    keepPartial = false
                    Outcome.Failed(L.t("file is only ${formatSize(out.length())}. Not saved.", "حجم الملف ${formatSize(out.length())} فقط. لم يتم الحفظ."))
                }
                else -> {
                    val (savedUri, saved) = saveToDownloads(app, out, mp3)
                    keepPartial = false
                    if (saved != out.length()) {
                        Outcome.Failed(L.t("copy incomplete (${formatSize(saved)} of ${formatSize(out.length())}).",
                            "النسخ غير مكتمل (${formatSize(saved)} من ${formatSize(out.length())})."))
                    } else {
                        HistoryStore.add(
                            app,
                            HistoryItem(
                                name = out.name,
                                format = format.name,
                                quality = quality,
                                sizeBytes = saved,
                                timeMillis = System.currentTimeMillis(),
                                location = "Downloads/$SAVE_DIR",
                                uri = savedUri.toString(),
                                sourceUrl = url,
                            ),
                        )
                        Outcome.Saved(out.name, saved, savedUri.toString())
                    }
                }
            }
        } catch (e: Exception) {
            // e.g. storage full while saving: the finished file stays so a retry only needs to copy it
            return Outcome.Failed(lastLine(e))
        } finally {
            inDownload = false
            // Cancel = discard. Network failures / app kills keep the partial files for resuming.
            if (!keepPartial || cancelled) work.deleteRecursively()
        }
    }

    /** Fails early, with a clear message, when the phone has too little free space (instead of a cryptic error half way). */
    private fun ensureFreeSpace(dir: File, needBytes: Long) {
        val free = runCatching { dir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free < needBytes) {
            throw IOException(
                L.t("Not enough free storage (${formatSize(free)} free, ${formatSize(needBytes)} needed). Free some space and try again.",
                    "لا توجد مساحة تخزين كافية (المتاح ${formatSize(free)} والمطلوب ${formatSize(needBytes)}). أفرغ بعض المساحة وحاول مجددًا."),
            )
        }
    }

    private fun executeOnce(url: String, format: Format, quality: Int, work: File, embed: Boolean, altClients: Boolean = false) {
        ensureFreeSpace(work, MIN_FREE_BYTES)
        val mp3 = format == Format.MP3
        val req = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            // no --restrict-filenames: keeps Arabic (and other non-Latin) titles in the file name
            addOption("--continue")                     // resume partial files
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--socket-timeout", "15")          // a dead connection after a network switch is noticed fast
            addOption("--retry-sleep", "3")
            // speed: several fragments at once (DASH / HLS streams), bigger buffers, and a re-connect when a server throttles
            addOption("--concurrent-fragments", "8")
            addOption("--buffer-size", "256K")
            addOption("--http-chunk-size", "10M")        // chunked requests avoid YouTube's slow single long connection
            addOption("--throttled-rate", "200K")        // below this speed yt-dlp asks for a fresh, unthrottled address
            addOption("--no-mtime")
            // second attempt after an engine update: ask YouTube through client types that need no sign-in
            if (altClients) addOption("--extractor-args", "youtube:player_client=tv,web_safari,mweb,android_vr")
            addOption("-o", "${work.absolutePath}/%(title).80s.%(ext)s")
            if (embed) {
                addOption("--embed-metadata")             // title, artist (uploader), date, description
            }
            if (mp3) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "${quality}K")
                if (embed) {
                    addOption("--embed-thumbnail")        // cover art inside the MP3
                    addOption("--convert-thumbnails", "jpg")
                }
            } else {
                addOption(
                    "-f",
                    "bv*[height<=$quality][ext=mp4]+ba[ext=m4a]/b[height<=$quality][ext=mp4]/b[height<=$quality]/b",
                )
                addOption("--merge-output-format", "mp4")
            }
        }
        processing = false
        YoutubeDL.getInstance().execute(req, PROCESS_ID) { p, eta, line ->
            processing = p >= 100f      // drops back to false when the next stream (audio after video) starts
            // speed comes from yt-dlp's own progress line: "[download]  45.2% of 10MiB at 1.20MiB/s ETA 00:05"
            val speed = SPEED_RE.find(line ?: "")?.groupValues?.get(1)?.let { prettySpeed(it) }
            val left = if (eta > 0) formatEta(eta) else null
            val extra = listOfNotNull(speed, left?.let { L.t("$it left", "متبقي $it") }).joinToString(" · ")
            _state.update {
                it.copy(
                    progress = p.coerceIn(0f, 100f) / 100f,
                    message = prefix + if (p >= 100f) L.t("Processing…", "جارٍ المعالجة…")
                    else L.t("Downloading ", "جارٍ التنزيل ") + "${p.coerceAtLeast(0f).toInt()}%" +
                        (if (extra.isNotEmpty()) "\n$extra" else ""),
                )
            }
        }
    }

    private val SPEED_RE = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")

    /** "1.20MiB/s" -> "1.20 MiB/s" */
    private fun prettySpeed(raw: String): String =
        raw.replace(Regex("""(?<=\d)(?=[KMGT]?i?B/s)"""), " ").replace("  ", " ").trim()

    /** 75 -> "1:15", 3700 -> "1:01:40" */
    private fun formatEta(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun workDir(app: Application, url: String, format: Format, quality: Int): File {
        val id = sourceId(url)
        val key = MessageDigest.getInstance("SHA-1")
            .digest("$id|${format.name}|$quality".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
        return File(File(app.filesDir, "partial"), key)
    }

    private fun cleanOldPartials(app: Application) {
        val now = System.currentTimeMillis()
        File(app.filesDir, "partial").listFiles()?.forEach { dir ->
            val newest = dir.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
            if (now - newest > PARTIAL_MAX_AGE_MS) dir.deleteRecursively()
        }
    }

    private fun lastLine(e: Exception): String =
        e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(200) ?: L.t("unknown error", "خطأ غير معروف")

    private fun looksLikeNetworkError(line: String): Boolean {
        val l = line.lowercase()
        return listOf(
            "timed out", "timeout", "connection", "network", "temporary failure", "reset by peer",
            "urlopen error", "eof", "incompleteread", "http error 5", "ssl", "name or service",
            "getaddrinfo", "no route", "broken pipe", "transporterror", "unable to download",
        ).any { it in l }
    }

    private fun isOnline(app: Application): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Blocks until the user taps Resume or cancels. */
    private fun waitWhilePaused() {
        _state.update {
            it.copy(message = prefix + L.t("Paused. Tap Resume to continue.", "متوقف مؤقتًا. اضغط استئناف للمتابعة."), paused = true)
        }
        while (paused && !cancelled) sleepUnlessCancelled(300)
    }

    /** Returns true when the network is back, false if cancelled or timed out. */
    private fun waitForNetwork(app: Application): Boolean {
        _state.update {
            it.copy(message = prefix + L.t("Connection lost. Waiting for network… will resume automatically",
                "انقطع الاتصال. بانتظار الشبكة… سيتم الاستئناف تلقائيًا"))
        }
        val deadline = System.currentTimeMillis() + MAX_OFFLINE_WAIT_MS
        while (!cancelled && System.currentTimeMillis() < deadline) {
            if (isOnline(app)) {
                _state.update { it.copy(message = prefix + L.t("Network is back. Resuming…", "عادت الشبكة. جارٍ الاستئناف…")) }
                sleepUnlessCancelled(2000)
                return true
            }
            sleepUnlessCancelled(2000)
        }
        return false
    }

    private fun sleepUnlessCancelled(ms: Long) {
        var left = ms
        try {
            while (left > 0 && !cancelled) {
                Thread.sleep(minOf(500L, left))
                left -= 500L
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()          // keep the interrupt so the caller's cancellation still works
        }
    }

    private fun onCancelled(saved: Int) =
        _state.update {
            it.copy(
                phase = Phase.Idle, progress = 0f, paused = false, currentTitle = "", activeKey = "",
                message = if (saved > 0) L.t("Download cancelled. $saved item(s) were already saved.", "تم إلغاء التنزيل. تم حفظ $saved عنصر مسبقًا.")
                else L.t("Download cancelled.", "تم إلغاء التنزيل."),
            )
        }

    /**
     * Copies the finished file into Downloads through MediaStore as ONE transaction: the entry stays "pending" (invisible
     * to other apps) while it is written, and is made visible only when every byte was copied and verified. On ANY failure
     * (storage full, cancelled, short copy) the half-written entry is deleted, so no broken or permanently pending file is
     * left behind.
     */
    private fun saveToDownloads(app: Application, file: File, mp3: Boolean): Pair<Uri, Long> {
        ensureFreeSpace(Environment.getExternalStorageDirectory(), file.length() + 20_000_000L)   // the copy needs room for a second file
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, if (mp3) "audio/mpeg" else "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create file in Downloads")
        var committed = false
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open the new file in Downloads for writing")
            val copied = out.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (copied != file.length()) throw IOException("copy incomplete ($copied of ${file.length()} bytes)")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) < 1) throw IOException("Could not publish the saved file")
            committed = true
            return uri to copied
        } finally {
            if (!committed) {
                try { resolver.delete(uri, null, null) } catch (e: Exception) {
                    Log.w("DownloadEngine", "could not remove the unfinished entry: ${e.javaClass.simpleName}")
                }
            }
        }
    }
}
