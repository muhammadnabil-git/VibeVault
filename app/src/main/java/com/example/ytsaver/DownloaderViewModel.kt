package com.example.ytsaver

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One link box in the Download tab. Every box has its own format + quality.
 * [queued] = already handed to the download queue (locked until the queue finishes).
 */
data class LinkRow(
    val id: Int,
    val text: String = "",
    val whole: Boolean = false,
    val format: Format = Format.MP3,
    val quality: Int = defaultQuality(Format.MP3),
    val queued: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val progress: Float = 0f,
    val message: String = "",
    val engineReady: Boolean = false,
    val paused: Boolean = false,
    val updating: Boolean = false,
    val currentTitle: String = "",
    val activeKey: String = "",
    val finishedKeys: Set<String> = emptySet(),
    val badKeys: Set<String> = emptySet(),
)

/** A link that is ready to be sent to the queue, and the box it came from. */
data class PendingLink(val rowId: Int, val link: QueueLink) {
    val key: String get() = originKey(link)
}

/**
 * Links in boxes that are not queued yet. A single video that is already saved in the box's own
 * format + quality is left out (the box shows an "already downloaded" hint instead).
 */
fun pendingLinks(rows: List<LinkRow>, downloaded: Map<Int, List<HistoryItem>>): List<PendingLink> =
    rows.filter { !it.queued }.flatMap { r ->
        parseLinks(r.text).mapNotNull { u ->
            val playlist = isPlaylistRequest(u, r.whole)
            val have = !playlist && downloaded[r.id].orEmpty().any { it.format == r.format.name && it.quality == r.quality }
            if (have) null else PendingLink(r.id, QueueLink(u, playlist, r.format, r.quality))
        }
    }

private data class RowKey(val id: Int, val text: String, val whole: Boolean)

class DownloaderViewModel(private val app: Application) : AndroidViewModel(app) {

    // playlists / favorites / account are loaded first: screens read them straight away
    @Suppress("unused")
    private val libraryReady = run {
        Library.load(app)
        FolderStore.load(app)
        VaultStore.load(app)
        CoverStore.load(app)
        PlaylistScheduler.reschedule(app)     // playlist schedules: set the next alarm
        Account.load(app)
        ThemeState.light = Prefs.lightMode(app)
    }

    // ---------------- language (EN / عر) ----------------
    private val _ar = MutableStateFlow(Prefs.isArabic(app).also { L.ar = it })
    val ar: StateFlow<Boolean> = _ar.asStateFlow()

    fun setArabic(v: Boolean) {
        L.ar = v
        Prefs.setArabic(app, v)
        _ar.value = v
        settingsChanged()
    }

    // ---------------- settings: defaults for new links ----------------
    private val _defFormat = MutableStateFlow(Prefs.defaultFormat(app))
    val defaultFormat: StateFlow<Format> = _defFormat.asStateFlow()
    private val _defQuality = MutableStateFlow(Prefs.prefQuality(app, _defFormat.value))
    val defaultQ: StateFlow<Int> = _defQuality.asStateFlow()
    private val _autoHistory = MutableStateFlow(Prefs.autoOpenHistory(app))
    val autoHistory: StateFlow<Boolean> = _autoHistory.asStateFlow()

    fun setDefaultFormat(f: Format) {
        Prefs.setDefaultFormat(app, f)
        _defFormat.value = f
        _defQuality.value = Prefs.prefQuality(app, f)
        settingsChanged()
    }

    fun setDefaultQuality(q: Int) {
        Prefs.setPrefQuality(app, _defFormat.value, q)
        _defQuality.value = q
        settingsChanged()
    }

    private val _searchPreview = MutableStateFlow(Prefs.searchPreview(app))
    val searchPreview: StateFlow<Boolean> = _searchPreview.asStateFlow()
    private val _historyThumbs = MutableStateFlow(Prefs.historyThumbs(app))
    val historyThumbs: StateFlow<Boolean> = _historyThumbs.asStateFlow()

    private val _searchLimit = MutableStateFlow(Prefs.searchLimit(app))
    /** Search tab: results asked from each website (Settings → Behavior). */
    val searchLimit: StateFlow<Int> = _searchLimit.asStateFlow()

    fun setSearchLimit(v: Int) {
        Prefs.setSearchLimit(app, v)
        _searchLimit.value = Prefs.searchLimit(app)
        settingsChanged()
    }

    fun setSearchPreview(v: Boolean) {
        Prefs.setSearchPreview(app, v)
        _searchPreview.value = v
        if (!v) stopFeedPreview()
        settingsChanged()
    }

    fun setHistoryThumbs(v: Boolean) {
        Prefs.setHistoryThumbs(app, v)
        _historyThumbs.value = v
        settingsChanged()
    }

    // v7.1 switches: Settings -> Behavior
    private val _resumePlayback = MutableStateFlow(Prefs.resumePlayback(app))
    val resumePlayback: StateFlow<Boolean> = _resumePlayback.asStateFlow()
    private val _undoDelete = MutableStateFlow(Prefs.undoDelete(app))
    val undoDelete: StateFlow<Boolean> = _undoDelete.asStateFlow()
    private val _librarySearch = MutableStateFlow(Prefs.librarySearch(app))
    val librarySearch: StateFlow<Boolean> = _librarySearch.asStateFlow()
    private val _historySearch = MutableStateFlow(Prefs.historySearch(app))
    val historySearch: StateFlow<Boolean> = _historySearch.asStateFlow()
    private val _sourceSites = MutableStateFlow(Prefs.sourceSites(app))
    val sourceSites: StateFlow<Boolean> = _sourceSites.asStateFlow()
    private val _embedMeta = MutableStateFlow(Prefs.embedMeta(app))
    val embedMeta: StateFlow<Boolean> = _embedMeta.asStateFlow()
    private val _groupBySite = MutableStateFlow(Prefs.groupBySite(app))
    val groupBySite: StateFlow<Boolean> = _groupBySite.asStateFlow()

    fun setResumePlayback(v: Boolean) { Prefs.setResumePlayback(app, v); _resumePlayback.value = v; settingsChanged() }
    fun setUndoDelete(v: Boolean) { Prefs.setUndoDelete(app, v); _undoDelete.value = v; if (!v) _undo.value = null; settingsChanged() }
    fun setLibrarySearch(v: Boolean) { Prefs.setLibrarySearch(app, v); _librarySearch.value = v; settingsChanged() }
    fun setHistorySearch(v: Boolean) { Prefs.setHistorySearch(app, v); _historySearch.value = v; settingsChanged() }
    fun setSourceSites(v: Boolean) { Prefs.setSourceSites(app, v); _sourceSites.value = v; settingsChanged() }
    fun setEmbedMeta(v: Boolean) { Prefs.setEmbedMeta(app, v); _embedMeta.value = v; settingsChanged() }
    fun setGroupBySite(v: Boolean) { Prefs.setGroupBySite(app, v); _groupBySite.value = v }

    // "Removed – Undo": the last removal from the library lists (files that were really deleted can't come back)
    data class UndoInfo(val id: Long, val message: String, val items: List<HistoryItem>)
    private val _undo = MutableStateFlow<UndoInfo?>(null)
    val undo: StateFlow<UndoInfo?> = _undo.asStateFlow()
    private fun offerUndo(items: List<HistoryItem>) {
        if (items.isEmpty() || !_undoDelete.value) return
        _undo.value = UndoInfo(
            System.nanoTime(),
            L.t("Removed ${items.size} item(s) from the list", "تمت إزالة ${items.size} عنصر من القائمة"),
            items,
        )
    }
    fun clearUndo() { _undo.value = null }
    fun performUndo() {
        val u = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch(Dispatchers.IO) { HistoryStore.addAll(app, u.items) }
    }

    fun setAutoHistory(v: Boolean) {
        Prefs.setAutoOpenHistory(app, v)
        _autoHistory.value = v
        settingsChanged()
    }

    // ---------------- light / dark ----------------
    private val _light = MutableStateFlow(Prefs.lightMode(app))
    val lightMode: StateFlow<Boolean> = _light.asStateFlow()

    fun setLightMode(v: Boolean) {
        ThemeState.light = v
        Prefs.setLightMode(app, v)
        _light.value = v
        settingsChanged()
    }

    // ---------------- colour theme + animated background (Settings → Themes) ----------------
    private val _theme = MutableStateFlow(Prefs.str(app, "theme") ?: "vibevault")
    val theme: StateFlow<String> = _theme.asStateFlow()
    fun setTheme(id: String) {
        if (id != THEME_WALLPAPER && AppThemes.none { it.id == id }) return
        Prefs.putStr(app, "theme", id)
        _theme.value = id
    }

    private val _liveBg = MutableStateFlow(Prefs.bool(app, "live_bg", false))
    val liveBackground: StateFlow<Boolean> = _liveBg.asStateFlow()
    fun setLiveBackground(v: Boolean) {
        Prefs.putBool(app, "live_bg", v)
        _liveBg.value = v
    }

    // ---------------- History tab remembers where the user was ----------------
    private val _historySub = MutableStateFlow(Prefs.historySub(app))
    val historySub: StateFlow<Int> = _historySub.asStateFlow()
    private val _historySort = MutableStateFlow(Prefs.historySortKey(app) to Prefs.historySortAsc(app))
    val historySort: StateFlow<Pair<Int, Boolean>> = _historySort.asStateFlow()

    private val _historyExpanded = MutableStateFlow<String?>(null)
    val historyExpanded: StateFlow<String?> = _historyExpanded.asStateFlow()
    fun setHistoryExpanded(key: String?) { _historyExpanded.value = key }

    fun setHistorySub(i: Int) {
        Prefs.setHistorySub(app, i)
        _historySub.value = i
    }

    fun setHistorySort(key: Int, asc: Boolean) {
        Prefs.setHistorySort(app, key, asc)
        _historySort.value = key to asc
    }

    // ---------------- Bluetooth auto-play ----------------
    private val _btEnabled = MutableStateFlow(Prefs.btEnabled(app))
    val btEnabled: StateFlow<Boolean> = _btEnabled.asStateFlow()
    private val _btMode = MutableStateFlow(Prefs.btMode(app))
    val btMode: StateFlow<String> = _btMode.asStateFlow()

    fun setBtEnabled(v: Boolean) {
        Prefs.setBtEnabled(app, v)
        _btEnabled.value = v
        BluetoothWatch.refresh(app)
        settingsChanged()
    }

    /** "background" or "open": picking one always switches the other off. */
    fun setBtMode(mode: String) {
        Prefs.setBtMode(app, mode)
        _btMode.value = Prefs.btMode(app)
        settingsChanged()
    }

    fun refreshBluetooth() = BluetoothWatch.refresh(app)

    // ---------------- favorites / playlists windows ----------------
    private val _favoritesOpen = MutableStateFlow(false)
    val favoritesOpen: StateFlow<Boolean> = _favoritesOpen.asStateFlow()
    private val _playlistsOpen = MutableStateFlow(false)
    val playlistsOpen: StateFlow<Boolean> = _playlistsOpen.asStateFlow()

    fun openFavorites() { stopFeedPreview(); _playlistsOpen.value = false; _favoritesOpen.value = true }
    fun closeFavorites() { _favoritesOpen.value = false }
    private val _permSetup = MutableStateFlow(if (Prefs.permSetupDone(app)) 0 else 1)
    /** 0 = closed, 1 = first-run permission set-up, 2 = review (Settings → Permissions). */
    val permSetup: StateFlow<Int> = _permSetup.asStateFlow()
    fun openPermissionReview() { _permSetup.value = 2 }
    fun closePermissionSetup() { Prefs.setPermSetupDone(app, true); _permSetup.value = 0 }

    fun openPlaylists() { stopFeedPreview(); _favoritesOpen.value = false; _playlistsOpen.value = true }
    fun closePlaylists() { _playlistsOpen.value = false }

    private var pendingOpenPlayer = false

    /** The player opens as soon as the first track is ready (a streamed link needs a moment to be looked up). */
    fun requestOpenPlayerWhenReady() {
        if (PlayerHolder.entries.value.isNotEmpty()) _playerOpen.value = true else pendingOpenPlayer = true
    }

    /** Plays playlist / favorite entries: downloaded files directly, web links streamed. */
    fun playEntries(entries: List<PlEntry>, start: Int = 0) {
        if (entries.isEmpty()) return
        stopFeedPreview()
        if (entries.any { it.localUri == null }) {
            toast(app, L.t("Preparing… streaming starts in a moment.", "جارٍ التحضير… سيبدأ البث بعد لحظة."))
        }
        pendingOpenPlayer = true
        PlaylistPlayer.play(app, entries, start)
    }

    /** Adds a pasted link to a playlist, then fills in its title and picture. */
    fun addLinkToPlaylist(playlistId: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bare = PlEntry(hostOf(url).removePrefix("www."), "", 0L, null, url, null, guessIsAudio(url), siteLabelOf(url))
            if (Library.addToPlaylist(playlistId, listOf(bare)) == 0) {
                withContext(Dispatchers.Main) {
                    toast(app, L.t("This link was not added (already in the playlist, or the playlist only takes downloaded files).",
                        "لم تتم إضافة الرابط (موجود بالفعل، أو أن القائمة تقبل الملفات المنزّلة فقط)."))
                }
                return@launch
            }
            try {
                DownloadEngine.state.first { it.engineReady }
                val p = PreviewLoader.load(url)
                Library.updateMeta(url, p.title, p.uploader, p.durationSec, p.thumbUrl)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- account + cloud sync (Firebase) ----------------
    val accountEmail: StateFlow<String?> = Account.email
    val accountStatus: StateFlow<String> = Account.status
    val accountBusy: StateFlow<Boolean> = Account.busy
    val syncState: StateFlow<SyncState> = CloudSync.state
    val syncError: StateFlow<String> = CloudSync.error
    private var syncJob: Job? = null
    private var applyingRemote = false

    fun settingsJson(): JSONObject = JSONObject()
        .put("lang", if (_ar.value) "ar" else "en")
        .put("light", _light.value)
        .put("defFormat", _defFormat.value.name)
        .put("defQMp3", Prefs.prefQuality(app, Format.MP3))
        .put("defQMp4", Prefs.prefQuality(app, Format.MP4))
        .put("autoHistory", _autoHistory.value)
        .put("searchPreview", _searchPreview.value)
        .put("historyThumbs", _historyThumbs.value)
        .put("resumePlayback", _resumePlayback.value)
        .put("undoDelete", _undoDelete.value)
        .put("librarySearch", _librarySearch.value)
        .put("historySearch", _historySearch.value)
        .put("sourceSites", _sourceSites.value)
        .put("embedMeta", _embedMeta.value)
        .put("searchLimit", _searchLimit.value)
        .put("btEnabled", _btEnabled.value)
        .put("btMode", _btMode.value)
        .put("shortcuts", JSONArray().also { a -> _shortcuts.value.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url)) } })

    /** Preferences that came from another device (through the cloud). */
    fun applySettings(o: JSONObject) {
        applyingRemote = true
        try {
            if (o.has("lang")) { val a = o.optString("lang") == "ar"; L.ar = a; Prefs.setArabic(app, a); _ar.value = a }
            if (o.has("light")) { val l = o.optBoolean("light"); ThemeState.light = l; Prefs.setLightMode(app, l); _light.value = l }
            if (o.has("defFormat")) {
                val f = if (o.optString("defFormat") == "MP4") Format.MP4 else Format.MP3
                Prefs.setDefaultFormat(app, f); _defFormat.value = f
            }
            if (o.has("defQMp3")) Prefs.setPrefQuality(app, Format.MP3, o.optInt("defQMp3"))
            if (o.has("defQMp4")) Prefs.setPrefQuality(app, Format.MP4, o.optInt("defQMp4"))
            _defQuality.value = Prefs.prefQuality(app, _defFormat.value)
            if (o.has("autoHistory")) { val v = o.optBoolean("autoHistory"); Prefs.setAutoOpenHistory(app, v); _autoHistory.value = v }
            if (o.has("searchPreview")) { val v = o.optBoolean("searchPreview"); Prefs.setSearchPreview(app, v); _searchPreview.value = v }
            if (o.has("historyThumbs")) { val v = o.optBoolean("historyThumbs"); Prefs.setHistoryThumbs(app, v); _historyThumbs.value = v }
            if (o.has("resumePlayback")) { val v = o.optBoolean("resumePlayback"); Prefs.setResumePlayback(app, v); _resumePlayback.value = v }
            if (o.has("undoDelete")) { val v = o.optBoolean("undoDelete"); Prefs.setUndoDelete(app, v); _undoDelete.value = v }
            if (o.has("librarySearch")) { val v = o.optBoolean("librarySearch"); Prefs.setLibrarySearch(app, v); _librarySearch.value = v }
            if (o.has("historySearch")) { val v = o.optBoolean("historySearch"); Prefs.setHistorySearch(app, v); _historySearch.value = v }
            if (o.has("sourceSites")) { val v = o.optBoolean("sourceSites"); Prefs.setSourceSites(app, v); _sourceSites.value = v }
            if (o.has("embedMeta")) { val v = o.optBoolean("embedMeta"); Prefs.setEmbedMeta(app, v); _embedMeta.value = v }
            if (o.has("searchLimit")) { val v = o.optInt("searchLimit"); Prefs.setSearchLimit(app, v); _searchLimit.value = Prefs.searchLimit(app) }
            if (o.has("btMode")) { Prefs.setBtMode(app, o.optString("btMode")); _btMode.value = Prefs.btMode(app) }   // always one of the two
            if (o.has("btEnabled")) { val v = o.optBoolean("btEnabled"); Prefs.setBtEnabled(app, v); _btEnabled.value = v; BluetoothWatch.refresh(app) }
            o.optJSONArray("shortcuts")?.let { arr ->
                val list = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { j -> Shortcut(j.optString("n"), j.optString("u")) } }
                    .filter { it.url.isNotBlank() }
                if (list.isNotEmpty()) commitShortcuts(list.take(MAX_SHORTCUTS))
            }
        } finally {
            applyingRemote = false
        }
    }

    /** A preference was changed on this phone: remember when, and send it to the cloud shortly. */
    fun settingsChanged() {
        if (applyingRemote) return
        Prefs.putLong(app, "settings_at", System.currentTimeMillis())
        scheduleSync()
    }

    /** Signed in: send what changed a moment after the last edit. Offline, Firestore keeps it queued until the connection returns. */
    private fun scheduleSync(delayMs: Long = 1500) {
        if (Account.uid.value == null) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(delayMs)
            CloudSync.pushLocal()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            Account.busy.value = true
            val err = CloudSync.syncNow()
            Account.busy.value = false
            Account.status.value = err ?: L.t("Everything is synced.", "كل شيء متزامن.")
        }
    }

    fun accountSignUp(mail: String, pass: String) {
        viewModelScope.launch {
            Account.busy.value = true
            val err = Account.signUp(mail, pass)
            Account.busy.value = false
            Account.status.value = err ?: L.t(
                "Account created and signed in. We sent a verification link to your Gmail.",
                "تم إنشاء الحساب وتسجيل الدخول. أرسلنا رابط تحقق إلى Gmail.",
            )
        }
    }

    fun accountSignIn(mail: String, pass: String) {
        viewModelScope.launch {
            Account.busy.value = true
            val err = Account.signIn(mail, pass)
            Account.busy.value = false
            Account.status.value = err ?: L.t("Signed in.", "تم تسجيل الدخول.")
        }
    }

    /** Google sign-in (through Firebase). [activityCtx] must be the Activity: the account chooser opens over it. */
    fun accountGoogleSignIn(activityCtx: android.content.Context) {
        viewModelScope.launch {
            Account.busy.value = true
            val err = Account.googleSignIn(activityCtx)
            Account.busy.value = false
            Account.status.value = err ?: L.t("Signed in with Google.", "تم تسجيل الدخول بحساب Google.")
        }
    }

    fun accountResetPassword(mail: String) {
        viewModelScope.launch {
            Account.busy.value = true
            val err = Account.resetPassword(mail)
            Account.busy.value = false
            Account.status.value = err ?: L.t(
                "If this Gmail has an account, a password-reset link is on its way.",
                "إن كان لهذا الحساب وجود، فرابط إعادة تعيين كلمة المرور في طريقه إليك.",
            )
        }
    }

    fun accountSignOut() = Account.signOut(app)

    // ---------------- download state (comes from the engine) ----------------
    val state: StateFlow<UiState> = DownloadEngine.state.map { j ->
        UiState(
            phase = j.phase, progress = j.progress, message = j.message, engineReady = j.engineReady,
            paused = j.paused, updating = j.updating, currentTitle = j.currentTitle,
            activeKey = j.activeKey, finishedKeys = j.finishedKeys, badKeys = j.badKeys,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    // ---------------- link rows (each with its own preview) ----------------
    private var nextId = 1
    private fun newRow(text: String = "") = LinkRow(nextId++, text, format = _defFormat.value, quality = _defQuality.value)
    private val _rows = MutableStateFlow(listOf(LinkRow(0, format = _defFormat.value, quality = _defQuality.value)))
    val rows: StateFlow<List<LinkRow>> = _rows.asStateFlow()

    private val _previews = MutableStateFlow<Map<Int, PreviewState>>(emptyMap())
    val previews: StateFlow<Map<Int, PreviewState>> = _previews.asStateFlow()

    private val previewJobs = mutableMapOf<Int, Job>()
    private val previewGate = kotlinx.coroutines.sync.Semaphore(2)

    fun setRowText(id: Int, text: String) {
        val links = parseLinks(text)
        if (links.size > 1) {
            // several links pasted into one box: give each its own row
            val cur = _rows.value
            val idx = cur.indexOfFirst { it.id == id }
            if (idx < 0) return
            val fresh = links.mapIndexed { k, l ->
                if (k == 0) cur[idx].copy(text = l)
                else LinkRow(nextId++, l, format = cur[idx].format, quality = cur[idx].quality)
            }
            _rows.value = cur.take(idx) + fresh + cur.drop(idx + 1)
            fresh.forEach { schedulePreview(it.id) }
            return
        }
        _rows.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
        schedulePreview(id)
    }

    /**
     * The queue finished. Lines that were downloaded successfully are removed (their files live in the History
     * tab now; if nothing is left, one empty line remains). Failed or cancelled lines stay, editable, for a retry.
     */
    private fun finishRun(s: JobState) {
        val before = _rows.value
        val kept = before.filterNot { r ->
            val key = parseLinks(r.text).singleOrNull()
                ?.let { originKey(QueueLink(it, isPlaylistRequest(it, r.whole), r.format, r.quality)) }
            r.queued && key != null && key in s.okKeys && key !in s.badKeys
        }.map { if (it.queued) it.copy(queued = false) else it }
        val after = kept.ifEmpty { listOf(newRow()) }
        _rows.value = after
        before.filter { b -> after.none { it.id == b.id } }.forEach {
            previewJobs.remove(it.id)?.cancel()
            setPreview(it.id, null)
        }
    }

    /** Allowed at any time, also while a download is running. The new box starts with the default format/quality. */
    fun addRow() {
        _rows.update { list -> list + newRow() }
    }

    /** Puts a link (from the Search tab or the in-app browser) into the Download tab and shows that tab. */
    fun sendToDownload(url: String, asMp3: Boolean = false) {
        val free = _rows.value.firstOrNull { !it.queued && it.text.isBlank() }?.id
        val id = free ?: run { addRow(); _rows.value.last().id }
        setRowText(id, url)
        if (asMp3) setRowFormat(id, Format.MP3)
        _tab.value = 1
    }

    fun setRowFormat(id: Int, f: Format) {
        _rows.update { list ->
            list.map { if (it.id == id && !it.queued) it.copy(format = f, quality = defaultQuality(f)) else it }
        }
    }

    fun setRowQuality(id: Int, q: Int) {
        _rows.update { list -> list.map { if (it.id == id && !it.queued) it.copy(quality = q) else it } }
    }

    fun removeRow(id: Int) {
        if (_rows.value.size <= 1) {
            setRowText(id, "")
            return
        }
        previewJobs.remove(id)?.cancel()
        setPreview(id, null)
        _rows.update { list -> list.filterNot { it.id == id } }
    }

    fun setRowWhole(id: Int, v: Boolean) {
        _rows.update { list -> list.map { if (it.id == id) it.copy(whole = v) else it } }
        schedulePreview(id)
    }

    private fun setPreview(id: Int, st: PreviewState?) {
        _previews.update { m -> if (st == null) m - id else m + (id to st) }
    }

    /** Preview card for one video, or playlist info (title + number of videos) for one playlist link. */
    private fun schedulePreview(id: Int) {
        previewJobs.remove(id)?.cancel()
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        val links = parseLinks(row.text)
        if (links.size != 1) {
            setPreview(id, null)
            return
        }
        val u = links[0]
        val isPlaylist = isPlaylistRequest(u, row.whole)
        previewJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            delay(250) // wait until typing/pasting settles
            setPreview(id, PreviewState(loading = true))
            try {
                // the title and picture of a YouTube link show up at once, before the engine has even answered
                if (!isPlaylist) {
                    val quick = PreviewLoader.quick(u)
                    if (quick != null && isActive && _rows.value.any { it.id == id }) setPreview(id, PreviewState(preview = quick))
                }
                DownloadEngine.state.first { it.engineReady }
                previewGate.withPermit { // two lookups at a time
                    val st = if (isPlaylist) {
                        val pl = PlaylistLoader.load(playlistUrl(u))
                        if (pl.entries.isEmpty()) PreviewState(failed = true) else PreviewState(playlist = pl)
                    } else {
                        PreviewState(preview = PreviewLoader.load(u))
                    }
                    if (isActive && _rows.value.any { it.id == id }) setPreview(id, st)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive && _rows.value.any { it.id == id }) setPreview(id, PreviewState(failed = true))
            }
        }
    }

    // ---------------- Feed: search by name or link ----------------
    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()
    private var feedJob: Job? = null
    private val _recommendations = MutableStateFlow(HomeRecommendations())
    val recommendations: StateFlow<HomeRecommendations> = _recommendations.asStateFlow()
    private var recommendationJob: Job? = null

    /**
     * Keep Home recommendations in the ViewModel so changing pages does not discard results.
     * The only callers are app startup, Search, and successful download completion.
     */
    fun refreshRecommendations(searchQuery: String? = null) {
        searchQuery?.let { RecommendationActivity.recordSearch(app, it) }
        recommendationJob?.cancel()
        val saved = HistoryStore.items.value.toList()
        val searches = RecommendationActivity.searches(app)
        val played = PlayLog.recent(app, 40)
        val musicTopics = RecommendationActivity.topics(searches, saved, played, audio = true)
        val videoTopics = RecommendationActivity.topics(searches, saved, played, audio = false)
        val region = Locale.getDefault().country.takeIf { it.matches(Regex("[A-Za-z]{2}")) }?.uppercase(Locale.ROOT) ?: "US"
        val country = Locale("", region).getDisplayCountry(Locale.ENGLISH).ifBlank { region }
        val musicTrendingQuery = "Trending songs in $country"
        val videoTrendingQuery = "Trending videos in $country"
        val musicQueries = if (musicTopics.isEmpty()) listOf(musicTrendingQuery) else musicTopics.map { "$it songs" }
        val videoQueries = if (videoTopics.isEmpty()) listOf(videoTrendingQuery) else videoTopics
        val musicFallback = FeedItem(
            url = "https://music.youtube.com/charts?gl=$region",
            title = "Trending music in $country",
            uploader = "YouTube Music charts",
            durationSec = 0L,
            thumbUrl = null,
            site = "YouTube Music",
            isAudio = true,
        )
        val videoFallback = FeedItem(
            url = "https://charts.youtube.com/charts/TopVideos/$region",
            title = "Trending videos in $country",
            uploader = "YouTube charts",
            durationSec = 0L,
            thumbUrl = null,
            site = "YouTube",
            isAudio = false,
        )
        val youtube = SiteSpec("YouTube", "ytsearch", null, false)

        _recommendations.update { it.copy(loading = true, musicFailed = false, videosFailed = false) }
        recommendationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(20_000L) { DownloadEngine.state.first { it.engineReady } }
                val (musicResults, videoResults) = coroutineScope {
                    val music = async { searchRecommendationMusic(musicQueries, region) }
                    val videos = async { searchRecommendationVideos(videoQueries, youtube) }
                    music.await() to videos.await()
                }
                var music = interleaveRecommendationResults(musicResults.filterNotNull())
                    .filter { hostOf(it.url).contains("music.youtube.com") }
                    .map { it.copy(site = "YouTube Music", isAudio = true) }
                var videos = interleaveRecommendationResults(videoResults.filterNotNull())
                    .filter { hostOf(it.url).let { host -> host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" } }
                    .map { it.copy(site = "YouTube", isAudio = false) }

                if (music.isEmpty() && musicQueries != listOf(musicTrendingQuery)) {
                    music = interleaveRecommendationResults(searchRecommendationMusic(listOf(musicTrendingQuery), region).filterNotNull())
                        .filter { hostOf(it.url).contains("music.youtube.com") }
                        .map { it.copy(site = "YouTube Music", isAudio = true) }
                }
                if (videos.isEmpty() && videoQueries != listOf(videoTrendingQuery)) {
                    videos = interleaveRecommendationResults(searchRecommendationVideos(listOf(videoTrendingQuery), youtube).filterNotNull())
                        .filter { hostOf(it.url).let { host -> host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" } }
                        .map { it.copy(site = "YouTube", isAudio = false) }
                }

                _recommendations.update { current ->
                    current.copy(
                        music = music.ifEmpty { current.music.ifEmpty { listOf(musicFallback) } },
                        videos = videos.ifEmpty { current.videos.ifEmpty { listOf(videoFallback) } },
                        musicFailed = music.isEmpty(),
                        videosFailed = videos.isEmpty(),
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _recommendations.update {
                    it.copy(
                        music = it.music.ifEmpty { listOf(musicFallback) },
                        videos = it.videos.ifEmpty { listOf(videoFallback) },
                        musicFailed = true,
                        videosFailed = true,
                        loading = false,
                    )
                }
            }
        }
    }

    private suspend fun searchRecommendationMusic(queries: List<String>, region: String): List<List<FeedItem>?> =
        coroutineScope {
            queries.map { query ->
                async { PlatformLinks.youtubeMusicSearch(query, 5, region) }
            }.map { it.await() }
        }

    private suspend fun searchRecommendationVideos(queries: List<String>, site: SiteSpec): List<List<FeedItem>?> =
        coroutineScope {
            queries.map { query ->
                async { FeedLoader.searchSiteTimed(site, query, 5) }
            }.map { it.await() }
        }

    fun setFeedQuery(q: String) { _feed.update { it.copy(query = q) } }

    fun clearFeed() {
        feedJob?.cancel()
        previewJob?.cancel()
        _feed.value = FeedState()
    }

    // ---------------- editable website shortcuts (Search tab) ----------------
    private val _shortcuts = MutableStateFlow(Prefs.shortcuts(app))
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts.asStateFlow()

    private fun commitShortcuts(list: List<Shortcut>) {
        Prefs.setShortcuts(app, list)
        _shortcuts.value = list
        settingsChanged()
    }

    /** Edit mode of the Search tab: drag-and-drop dropped a website from place [from] onto place [to]; saved at once. */
    fun moveShortcutTo(from: Int, to: Int) = moveShortcut(from, to - from)

    /** Moves a website one place earlier ([delta] = -1) or later (+1) (or [delta] places). Saved at once. */
    fun moveShortcut(index: Int, delta: Int) {
        val to = index + delta
        val l = _shortcuts.value.toMutableList()
        if (index !in l.indices || to !in l.indices || to == index) return
        val item = l.removeAt(index)
        l.add(to, item)
        commitShortcuts(l)
    }

    /** index in the list = edit that one; any other index = add a new one (while there is room). */
    fun saveShortcut(index: Int, s: Shortcut) {
        val l = _shortcuts.value.toMutableList()
        if (index in l.indices) l[index] = s else if (l.size < MAX_SHORTCUTS) l.add(s)
        commitShortcuts(l)
    }

    fun removeShortcut(index: Int) {
        val l = _shortcuts.value.toMutableList()
        if (index in l.indices) { l.removeAt(index); commitShortcuts(l) }
    }

    /** Search tab "Reset": back to the user's default websites (Settings). */
    fun resetShortcuts() = commitShortcuts(_shortcutDefaults.value)

    // the defaults themselves (Settings): changing them also updates what the Search tab shows
    private val _shortcutDefaults = MutableStateFlow(Prefs.shortcutDefaults(app))
    val shortcutDefaults: StateFlow<List<Shortcut>> = _shortcutDefaults.asStateFlow()

    private fun commitDefaults(list: List<Shortcut>) {
        Prefs.setShortcutDefaults(app, list)
        _shortcutDefaults.value = list
        commitShortcuts(list)
    }

    fun saveDefaultShortcut(index: Int, s: Shortcut) {
        val l = _shortcutDefaults.value.toMutableList()
        if (index in l.indices) l[index] = s else if (l.size < MAX_SHORTCUTS) l.add(s)
        commitDefaults(l)
    }

    fun removeDefaultShortcut(index: Int) {
        val l = _shortcutDefaults.value.toMutableList()
        if (index in l.indices) { l.removeAt(index); commitDefaults(l) }
    }

    fun restoreFactoryShortcuts() = commitDefaults(FACTORY_SHORTCUTS)

    // inline preview of one search result (plays inside its own card, nowhere else)
    private var previewJob: Job? = null

    fun playFeedPreview(url: String) {
        previewJob?.cancel()
        PlayerHolder.peek()?.pause()          // don't talk over the music / video player
        _feed.update { it.copy(previewUrl = url, previewStream = null, previewLoading = true, previewFailed = false) }
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                DownloadEngine.state.first { it.engineReady }
                val stream = FeedLoader.streamInfo(url)       // remembered for next time: the same result plays at once
                _feed.update {
                    if (it.previewUrl != url) it
                    else it.copy(previewStream = stream, previewLoading = false, previewFailed = stream == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _feed.update { if (it.previewUrl != url) it else it.copy(previewLoading = false, previewFailed = true) }
            }
        }
    }

    fun stopFeedPreview() {
        previewJob?.cancel()
        _feed.update {
            if (it.previewUrl == null) it
            else it.copy(previewUrl = null, previewStream = null, previewLoading = false, previewFailed = false)
        }
    }

    /** Show one website's results, or null = all websites together. */
    fun setFeedSite(site: String?) { _feed.update { it.copy(siteFilter = site) } }

    private fun publishSite(site: String, res: List<FeedItem>?, more: Boolean) {
        _feed.update { st ->
            val results = if (res != null) st.siteResults + (site to res) else st.siteResults
            st.copy(
                siteResults = results,
                siteState = st.siteState + (site to (if (res == null) 2 else 1)),
                siteMore = st.siteMore + (site to more),
                items = interleaveResults(results),
            )
        }
    }

    /** Searches every website at the same time; results appear as each one answers. */
    fun searchFeed() {
        val q = _feed.value.query.trim()
        if (q.isEmpty()) return
        feedJob?.cancel()
        previewJob?.cancel()
        val link = parseLinks(q).firstOrNull()
        if (link == null) refreshRecommendations(q)
        val limit = _searchLimit.value
        val sites = if (link != null) listOf(LINK_SITE) else SEARCH_SITES.map { it.label }
        _feed.update {
            it.copy(
                loading = true, items = emptyList(), message = "", searched = true, searchedQuery = q,
                siteResults = emptyMap(), siteState = sites.associateWith { 0 }, siteFilter = null,
                siteLimit = sites.associateWith { limit }, siteMore = emptyMap(), loadingMore = false,
                previewUrl = null, previewStream = null, previewLoading = false, previewFailed = false,
            )
        }
        feedJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                DownloadEngine.state.first { it.engineReady }
                if (link != null) {
                    val items = try { FeedLoader.search(q) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    publishSite(LINK_SITE, items?.map { it.copy(site = siteLabelOf(it.url), isAudio = guessIsAudio(it.url)) }, false)
                    refreshRecommendations(items?.firstOrNull()?.let { result ->
                        result.uploader.takeIf { it.isNotBlank() } ?: result.title
                    })
                } else {
                    coroutineScope {
                        SEARCH_SITES.forEach { spec ->
                            launch {
                                // each website has its own time limit: a slow one never holds the others back
                                val res = FeedLoader.searchSiteTimed(spec, q, limit)
                                publishSite(spec.label, res, res != null && res.size >= limit)
                            }
                        }
                    }
                }
                _feed.update {
                    it.copy(
                        loading = false,
                        message = if (it.items.isEmpty()) L.t("No results found.", "لا توجد نتائج.") else "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _feed.update {
                    it.copy(
                        loading = false,
                        message = L.t("Search failed. Check your connection and try again.", "فشل البحث. تحقق من اتصالك وحاول مرة أخرى."),
                    )
                }
            }
        }
    }

    /** "Show more results": asks the websites that probably have more for another page of results (the size set in Settings). */
    fun loadMoreFeed() {
        val st = _feed.value
        if (st.loading || st.loadingMore || st.searchedQuery.isBlank()) return
        val step = _searchLimit.value
        val targets = SEARCH_SITES.filter { s -> st.siteMore[s.label] == true && (st.siteFilter == null || st.siteFilter == s.label) }
        if (targets.isEmpty()) return
        val q = st.searchedQuery
        _feed.update { it.copy(loadingMore = true) }
        feedJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    targets.forEach { spec ->
                        launch {
                            val newLimit = (_feed.value.siteLimit[spec.label] ?: step) + step
                            val res = FeedLoader.searchSiteTimed(spec, q, newLimit)
                            _feed.update { cur ->
                                val old = cur.siteResults[spec.label].orEmpty()
                                val merged = if (res == null) old else (old + res).distinctBy { it.url }
                                // a failed try can be repeated; a try that brought nothing new means the end of that website
                                val more = res == null || (merged.size > old.size && res.size >= newLimit)
                                val results = cur.siteResults + (spec.label to merged)
                                cur.copy(
                                    siteResults = results,
                                    items = interleaveResults(results),
                                    siteLimit = cur.siteLimit + (spec.label to (if (res == null) cur.siteLimit[spec.label] ?: step else newLimit)),
                                    siteMore = cur.siteMore + (spec.label to more),
                                )
                            }
                        }
                    }
                }
            } finally {
                _feed.update { it.copy(loadingMore = false) }
            }
        }
    }

    // ---------------- history + notifications ----------------
    val history: StateFlow<List<HistoryItem>> = HistoryStore.items
    val notifications: StateFlow<List<AppNotification>> = NotificationStore.items

    /** row id -> files of that row's video that are already saved (any format/quality) and still on the phone */
    val downloadedByRow: StateFlow<Map<Int, List<HistoryItem>>> =
        combine(
            _rows.map { list -> list.map { RowKey(it.id, it.text, it.whole) } }.distinctUntilChanged(),
            HistoryStore.items,
        ) { keys, h ->
            keys.associate { r ->
                val links = parseLinks(r.text)
                val items = if (links.size != 1 || isPlaylistRequest(links[0], r.whole)) {
                    emptyList()
                } else {
                    val id = sourceId(links[0])
                    h.filter { sourceId(it.sourceUrl) == id && fileExists(app, it) }
                }
                r.id to items
            }
        }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap<Int, List<HistoryItem>>())

    private val _notesOpen = MutableStateFlow(false)
    val notesOpen: StateFlow<Boolean> = _notesOpen.asStateFlow()
    private val _expandId = MutableStateFlow<Long?>(null)
    val expandId: StateFlow<Long?> = _expandId.asStateFlow()

    fun openNotifications() { _notesOpen.value = true }
    fun closeNotifications() {
        _notesOpen.value = false
        _expandId.value = null
        viewModelScope.launch(Dispatchers.IO) { NotificationStore.markAllRead(app) }
    }

    /** Opened from a grouped "downloads complete" notification: show the latest group, expanded. */
    fun showLatestGroupDetails() {
        viewModelScope.launch(Dispatchers.IO) {
            NotificationStore.load(app)
            val n = NotificationStore.items.value.firstOrNull { it.type == "DONE" && it.files.size > 1 }
            if (n != null) {
                _expandId.value = n.id
                _notesOpen.value = true
            }
        }
    }

    fun clearNotifications() {
        viewModelScope.launch(Dispatchers.IO) { NotificationStore.clear(app) }
    }

    /**
     * Removes the entry from the History list. With [deleteFile] the downloaded file is also deleted from
     * the phone (and taken out of Now playing); if it can't be deleted, the entry is kept so the user can retry.
     */
    fun removeHistory(item: HistoryItem, deleteFile: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (deleteFile && item.isDeviceFile) {
                // imported from the phone: it only leaves the app's lists, the file itself is never deleted
                withContext(Dispatchers.Main) {
                    PlayerHolder.removeByUri(app, item.uri)
                    toast(app, L.t("Removed from the app. The file stays on your phone.", "تمت الإزالة من التطبيق. يبقى الملف على هاتفك."))
                }
            } else if (deleteFile) {
                if (!deleteSavedFile(app, item)) {
                    withContext(Dispatchers.Main) {
                        toast(app, L.t("Couldn't delete the file. You can delete it from your file manager.",
                            "تعذّر حذف الملف. يمكنك حذفه من مدير الملفات."))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    PlayerHolder.removeByUri(app, item.uri)   // the player must be touched on the main thread
                    toast(app, L.t("File deleted", "تم حذف الملف"))
                }
            }
            HistoryStore.remove(app, item)
            // a file that was really deleted can't come back; list-only removals and imported files can
            if (!deleteFile || item.isDeviceFile) withContext(Dispatchers.Main) { offerUndo(listOf(item)) }
        }
    }

    /**
     * Removes several entries at once (selection / Clear all). With [deleteFiles] the files are deleted from the
     * phone too; entries whose file could not be deleted stay in the list.
     */
    fun removeHistoryItems(items: List<HistoryItem>, deleteFiles: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!deleteFiles) {
                HistoryStore.removeAll(app, items)
                withContext(Dispatchers.Main) { offerUndo(items) }
                return@launch
            }
            val device = items.filter { it.isDeviceFile }          // imported files: only removed from the lists
            val own = items.filterNot { it.isDeviceFile }
            val gone = own.filter { deleteSavedFile(app, it) }
            val failed = own.size - gone.size
            HistoryStore.removeAll(app, gone + device)
            withContext(Dispatchers.Main) { offerUndo(device) }          // imported files only left the app: they can be put back
            withContext(Dispatchers.Main) {
                (gone + device).forEach { PlayerHolder.removeByUri(app, it.uri) }
                toast(
                    app,
                    if (device.isNotEmpty() && failed == 0)
                        L.t("${gone.size} file(s) deleted. ${device.size} imported file(s) were only removed from the app and stay on your phone.",
                            "تم حذف ${gone.size} ملف. أُزيل ${device.size} ملف مستورد من التطبيق فقط وبقي على هاتفك.")
                    else if (failed == 0) L.t("${gone.size} file(s) deleted", "تم حذف ${gone.size} ملف")
                    else L.t("${gone.size} deleted, $failed couldn't be deleted. Delete those from your file manager.",
                        "تم حذف ${gone.size}، وتعذّر حذف $failed. احذفها من مدير الملفات."),
                )
            }
        }
    }

    /** "Import from device": the chosen files appear in the History tabs. Returns through a toast how many were new. */
    fun importDeviceMedia(items: List<HistoryItem>, then: (Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = HistoryStore.addAll(app, items)
            withContext(Dispatchers.Main) {
                toast(app, if (n > 0) L.t("$n file(s) imported", "تم استيراد $n ملف") else L.t("Already in Media", "موجودة بالفعل في الوسائط"))
                then(n)
            }
        }
    }

    /** Appends the entries of several playlists to Now playing (the bulk "Add to now playing"). */
    fun enqueuePlaylists(entries: List<PlEntry>) {
        if (entries.isEmpty()) return
        stopFeedPreview()
        if (entries.any { it.localUri == null }) {
            toast(app, L.t("Preparing… streaming items are added in a moment.", "جارٍ التحضير… ستُضاف عناصر البث بعد لحظة."))
        }
        PlaylistPlayer.play(app, entries, 0, append = true)
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = HistoryStore.items.value
            HistoryStore.clear(app)
            withContext(Dispatchers.Main) { offerUndo(all) }
        }
    }

    // ---------------- tabs / player ----------------
    // tabs: 0 = Search, 1 = Download, 2 = Media, 3 = History
    private val _tab = MutableStateFlow(4)      // 4 = Home (the dashboard)
    val tab: StateFlow<Int> = _tab.asStateFlow()
    fun setTab(i: Int) {
        if (i != 0) stopFeedPreview()
        _tab.value = i
    }

    // full-screen pages opened from the header / Feed
    // FM Radio and Music Detector pages (the two small icons next to the bell)
    private val _radioOpen = MutableStateFlow(false)
    val radioOpen: StateFlow<Boolean> = _radioOpen.asStateFlow()
    fun openRadio() { _radioOpen.value = true }
    fun closeRadio() { _radioOpen.value = false }
    private val _detectorOpen = MutableStateFlow(false)
    val detectorOpen: StateFlow<Boolean> = _detectorOpen.asStateFlow()
    fun openDetector() { _detectorOpen.value = true }
    fun closeDetector() { _detectorOpen.value = false }

    /** Music Detector -> Search tab: searches every website for "artist title". */
    fun sendSongToSearch(query: String) {
        if (query.isBlank()) return
        _detectorOpen.value = false
        setFeedQuery(query)
        setTab(0)
        searchFeed()
    }

    /** Music Detector -> Download tab: finds the song's link and puts it there as an MP3 line, ready for the Download button. */
    fun sendSongToDownload(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { toast(app, L.t("Finding the song…", "جارٍ البحث عن الأغنية…")) }
            val url = try {
                DownloadEngine.state.first { it.engineReady }
                FeedLoader.search(query).firstOrNull { it.url.startsWith("http") }?.url
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (url == null) toast(app, L.t("Couldn't find a link for this song. Try Send to Search.", "تعذّر العثور على رابط لهذه الأغنية. جرّب الإرسال إلى البحث."))
                else sendToDownload(url, asMp3 = true)
            }
        }
    }

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()
    fun openSettings() { _settingsOpen.value = true }
    fun closeSettings() { _settingsOpen.value = false; VaultStore.screenOpen.value = false }

    private val _browserUrl = MutableStateFlow<String?>(null)
    val browserUrl: StateFlow<String?> = _browserUrl.asStateFlow()
    fun openBrowser(url: String) { stopFeedPreview(); _browserUrl.value = url }
    fun closeBrowser() { _browserUrl.value = null }

    private val _playerOpen = MutableStateFlow(false)
    val playerOpen: StateFlow<Boolean> = _playerOpen.asStateFlow()

    fun openPlayer() { stopFeedPreview(); _playerOpen.value = true }
    fun closePlayer() { _playerOpen.value = false }

    fun playNow(items: List<HistoryItem>, start: Int = 0) {
        stopFeedPreview()
        PlayerHolder.playNow(app, items, start)
        _playerOpen.value = true
    }

    /** Returns how many files were added to Now playing. */
    fun addToQueue(items: List<HistoryItem>): Int = PlayerHolder.addToQueue(app, items)

    fun stopPlayback() {
        PlaylistPlayer.cancel()
        PlayerHolder.clear(app)
        _playerOpen.value = false
    }

    /** Set by the activity (onStart/onStop) so we only redirect when the app is on screen. */
    @Volatile var appInForeground = false

    init {
        // open the player once a playlist's first track is ready
        viewModelScope.launch {
            PlayerHolder.entries.collect {
                if (pendingOpenPlayer && it.isNotEmpty()) {
                    pendingOpenPlayer = false
                    _playerOpen.value = true
                }
            }
        }
        // playlists / favorites changed: send them to the account
        viewModelScope.launch {
            Library.changes.collect { if (it != 0L) scheduleSync() }
        }
        // signed in (or out) with Firebase: start (or stop) following the account's cloud data
        viewModelScope.launch {
            Account.uid.collect { u ->
                if (u != null) CloudSync.start(app, { settingsJson() }, { applySettings(it) }) else CloudSync.stop()
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            HistoryStore.load(app)
            NotificationStore.load(app)
            if (DownloadEngine.ensureInit(app)) {
                // every time the app is opened: bring the download engine up to date automatically
                if (DownloadEngine.updateEngine(app, auto = true)) Prefs.setFirstUpdateDone(app)
                else if (Prefs.engineStale(app)) {
                    NotificationStore.addOnce(
                        app, "ENGINE_NEEDS_UPDATE",
                        L.t("Engine update recommended", "يُنصح بتحديث المحرك"),
                        L.t("The download engine hasn't been updated for a while. Updating keeps downloads working.",
                            "لم يتم تحديث محرك التنزيل منذ فترة. التحديث يُبقي التنزيلات تعمل."),
                        24L * 60 * 60 * 1000,
                    )
                }
            }
            refreshRecommendations()
        }
        // When a download finishes while the app is on screen, jump to the History tab
        viewModelScope.launch {
            var last = Phase.Idle
            DownloadEngine.state.collect { s ->
                if (s.phase == Phase.Done && last != Phase.Done && appInForeground && _autoHistory.value) _tab.value = 2
                if (last == Phase.Working && s.phase != Phase.Working) {
                    finishRun(s)
                    if (s.phase == Phase.Done && s.okKeys.any { it !in s.badKeys }) refreshRecommendations()
                }
                last = s.phase
            }
        }
    }

    // ---------------- engine / download controls ----------------

    fun updateEngine() {
        val s = state.value
        if (s.updating || s.phase == Phase.Working) return
        viewModelScope.launch(Dispatchers.IO) { DownloadEngine.updateEngine(app) }
    }

    fun pause() {
        viewModelScope.launch(Dispatchers.IO) { DownloadEngine.pause() }
    }

    fun resume() {
        viewModelScope.launch(Dispatchers.IO) { DownloadEngine.resume() }
    }

    fun cancel() {
        viewModelScope.launch(Dispatchers.IO) { DownloadEngine.cancel() }
    }

    /** The Download button of one line: sends just that line to the queue (also while other downloads run). */
    fun downloadRow(id: Int) {
        val s = state.value
        if (!s.engineReady || s.updating) return
        val row = _rows.value.firstOrNull { it.id == id } ?: return
        startDownload(pendingLinks(listOf(row), downloadedByRow.value))
    }

    /** Download again from the History tab: this link, in the chosen format + quality. */
    fun downloadLink(url: String, format: Format, quality: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = state.value
            val msg = when {
                url.isBlank() -> L.t("No link saved for this download.", "لا يوجد رابط محفوظ لهذا التنزيل.")
                !s.engineReady || s.updating -> L.t("The download engine is busy. Try again in a moment.", "محرك التنزيل مشغول. حاول بعد قليل.")
                HistoryStore.findExisting(app, url, format.name, quality) != null ->
                    L.t("Already downloaded as ${format.name} at ${qualityLabel(format, quality)}. Pick another format or quality.",
                        "تم التنزيل مسبقًا بصيغة ${format.name} وجودة ${qualityLabel(format, quality)}. اختر صيغة أو جودة أخرى.")
                else -> null
            }
            if (msg != null) {
                withContext(Dispatchers.Main) { toast(app, msg) }
                return@launch
            }
            val started = startDownload(listOf(PendingLink(-1, QueueLink(url, playlistKind(url) == 1, format, quality))))
            if (started) withContext(Dispatchers.Main) { toast(app, L.t("Added to the download queue", "تمت الإضافة إلى قائمة التنزيل")) }
        }
    }

    /** Hands links to the download service. Works while another download is running: they join the queue. */
    private fun startDownload(ready: List<PendingLink>): Boolean {
        if (ready.isEmpty()) return false
        val wasWorking = state.value.phase == Phase.Working
        if (!wasWorking) DownloadEngine.markStarting() // blocks double taps until the service takes over
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, DownloadService::class.java)
                    .putStringArrayListExtra(DownloadService.EXTRA_URLS, ArrayList(ready.map { it.link.url }))
                    .putExtra(DownloadService.EXTRA_PLAYLIST, BooleanArray(ready.size) { ready[it].link.playlist })
                    .putStringArrayListExtra(DownloadService.EXTRA_FORMATS, ArrayList(ready.map { it.link.format.name }))
                    .putExtra(DownloadService.EXTRA_QUALITIES, IntArray(ready.size) { ready[it].link.quality }),
            )
        } catch (e: Exception) {
            val msg = L.t("Couldn't start download: ", "تعذّر بدء التنزيل: ") + e.message
            if (wasWorking) toast(app, msg) else DownloadEngine.reportError(msg)
            return false
        }
        val ids = ready.map { it.rowId }.toSet()
        _rows.update { list -> list.map { if (it.id in ids) it.copy(queued = true) else it } }
        return true
    }
}
