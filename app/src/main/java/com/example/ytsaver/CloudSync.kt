package com.example.ytsaver

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class SyncState { Off, Connecting, Synced, Pending, Offline, Error }

/**
 * Keeps the signed-in user's playlists, favorites, Bluetooth rules and preferences in Cloud Firestore,
 * under users/<uid>/ (the security rules only let that user touch it).
 *
 *   users/<uid>/playlists/<playlistId>   name, kind, order (created), entries[] in playlist order, at
 *   users/<uid>/favorites/<hash>         one favorite (a media reference: web link + title…), at
 *   users/<uid>/btRules/<ruleId>         Bluetooth device -> playlist
 *   users/<uid>/tombstones/<hash>        "this was deleted at time X", so a deletion reaches every device
 *   users/<uid>/meta/settings            the user's preferences (one JSON text) + time of the last change
 *
 * The phone's own copy ([Library] + preferences) stays the source of truth and is what playback uses, so
 * sync can never get in the way of offline playback. Downloaded files are device-specific and are NOT uploaded:
 * only web links are. Offline edits are queued by Firestore's own write queue (it survives app restarts) and are
 * sent when the connection returns; live listeners bring the other devices' changes in.
 */
object CloudSync {
    private val _state = MutableStateFlow(SyncState.Off)
    val state: StateFlow<SyncState> = _state.asStateFlow()
    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private var app: Context? = null
    private var uid: String? = null
    private var settingsOut: (() -> JSONObject)? = null
    private var settingsIn: ((JSONObject) -> Unit)? = null
    private val regs = mutableListOf<ListenerRegistration>()

    // what the cloud holds right now (as last reported by the listeners, including our own pending writes)
    private val plDocs = HashMap<String, JSONObject>()
    private val favDocs = HashMap<String, JSONObject>()
    private val btDocs = HashMap<String, JSONObject>()
    private val tombDocs = HashMap<String, JSONObject>()
    private var settingsDoc: JSONObject? = null
    private val ready = BooleanArray(5)
    private val fromCache = BooleanArray(5) { true }
    private val pending = BooleanArray(5)
    private var lastSettingsPush = 0L
    private var serverSeen = false     // true once any answer came from the server (not only from the phone's cache)
    private var graceOver = false      // after a few seconds without the server we call it "offline"
    private var session = 0

    private fun db() = FirebaseFirestore.getInstance()
    private fun base(u: String): DocumentReference = db().collection("users").document(u)

    // ---------------------------------------------------------------- start / stop
    /** Main thread. Starts following the signed-in user's cloud data. Safe to call again. */
    fun start(ctx: Context, settings: () -> JSONObject, apply: (JSONObject) -> Unit) {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (user == null) return
        if (uid == user.uid && regs.isNotEmpty()) return
        stop()
        val a = ctx.applicationContext
        app = a
        settingsOut = settings
        settingsIn = apply
        uid = user.uid
        Library.load(a)

        // A different account signs in on this phone: the previous account's playlists must not flow into it.
        val owner = Prefs.str(a, "lib_owner")
        if (owner != null && owner != user.uid) {
            Library.resetAll()
            Prefs.putLong(a, "settings_at", 0L)
        }
        Prefs.putStr(a, "lib_owner", user.uid)

        _error.value = ""
        _state.value = SyncState.Connecting
        serverSeen = false
        graceOver = false
        val mine = ++session
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (mine == session) { graceOver = true; publish() }
        }, 8_000)
        val b = base(user.uid)
        listen(b.collection("playlists"), 0, plDocs)
        listen(b.collection("favorites"), 1, favDocs)
        listen(b.collection("btRules"), 2, btDocs)
        listen(b.collection("tombstones"), 3, tombDocs)
        regs += b.collection("meta").document("settings").addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { fail(err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener
            settingsDoc = snap.data?.let { jsonOf(it) as? JSONObject }
            fromCache[4] = snap.metadata.isFromCache
            if (!snap.metadata.isFromCache) serverSeen = true
            pending[4] = snap.metadata.hasPendingWrites()
            ready[4] = true
            onData()
        }
    }

    /** Main thread. Stops following the cloud (sign-out). The phone's own copy is left as it is. */
    fun stop() {
        regs.forEach { try { it.remove() } catch (_: Exception) { } }
        regs.clear()
        plDocs.clear(); favDocs.clear(); btDocs.clear(); tombDocs.clear(); settingsDoc = null
        ready.fill(false); fromCache.fill(true); pending.fill(false)
        uid = null
        lastSettingsPush = 0L
        session++
        _state.value = SyncState.Off
        _error.value = ""
    }

    private fun listen(c: CollectionReference, idx: Int, into: MutableMap<String, JSONObject>) {
        regs += c.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { fail(err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener
            into.clear()
            for (d in snap.documents) {
                val m = d.data ?: continue
                (jsonOf(m) as? JSONObject)?.let { into[d.id] = it }
            }
            fromCache[idx] = snap.metadata.isFromCache
            if (!snap.metadata.isFromCache) serverSeen = true
            pending[idx] = snap.metadata.hasPendingWrites()
            ready[idx] = true
            onData()
        }
    }

    private fun fail(e: Exception) {
        val denied = e.message?.contains("PERMISSION_DENIED", true) == true
        _error.value = if (denied) {
            L.t("The cloud refused access. Sign out and in again; if it persists, the Firestore rules haven't been published yet.",
                "رفضت السحابة الوصول. سجّل الخروج والدخول مجددًا؛ وإن استمر ذلك فلم تُنشر قواعد Firestore بعد.")
        } else {
            L.t("Sync problem: ", "مشكلة في المزامنة: ") + (e.message ?: e.javaClass.simpleName)
        }
        _state.value = SyncState.Error
    }

    // ---------------------------------------------------------------- cloud -> phone
    private fun onData() {
        val a = app ?: return
        if (!ready.all { it }) { publish(); return }

        val remote = JSONObject()
            .put("playlists", JSONArray().also { arr -> plDocs.values.forEach { arr.put(it) } })
            .put("favorites", JSONArray().also { arr -> favDocs.values.forEach { arr.put(it) } })
            .put("bt", JSONArray().also { arr -> btDocs.values.forEach { arr.put(it) } })
            .put("deleted", JSONObject().also { d -> tombDocs.values.forEach { t -> if (t.optString("k").isNotBlank()) d.put(t.optString("k"), t.optLong("at")) } })
        Library.mergeRemote(remote)

        // preferences: the newer side wins
        val doc = settingsDoc
        val remoteAt = doc?.optLong("at") ?: 0L
        if (doc != null && remoteAt > Prefs.long(a, "settings_at")) {
            val json = try { JSONObject(doc.optString("json")) } catch (_: Exception) { null }
            if (json != null) {
                try { settingsIn?.invoke(json) } catch (_: Exception) { }
                Prefs.putLong(a, "settings_at", remoteAt)
            }
        }

        pushDiff()
        publish()
    }

    private fun publish() {
        if (uid == null) { _state.value = SyncState.Off; return }
        if (_error.value.isNotEmpty() && !ready.all { it }) { _state.value = SyncState.Error; return }
        if (!ready.all { it }) { _state.value = SyncState.Connecting; return }
        _error.value = ""
        val next = when {
            pending.any { it } -> SyncState.Pending
            fromCache.all { it } && !serverSeen && !graceOver -> SyncState.Connecting
            fromCache.all { it } -> SyncState.Offline
            else -> SyncState.Synced
        }
        if (next == SyncState.Synced && _state.value != SyncState.Synced) {
            app?.let { Prefs.putLong(it, "acct_last_sync", System.currentTimeMillis()) }
        }
        _state.value = next
    }

    // ---------------------------------------------------------------- phone -> cloud
    /** Main thread. Sends whatever differs between this phone and the cloud. Offline, Firestore queues the writes. */
    fun pushLocal() {
        if (uid == null || !ready.all { it }) return
        pushDiff()
    }

    private class Writer(val db: FirebaseFirestore) {
        var batch: WriteBatch = db.batch()
        var n = 0
        var total = 0
        private fun flush() {
            if (n == 0) return
            batch.commit().addOnFailureListener { CloudSync.fail(it) }
            batch = db.batch(); n = 0
        }
        fun set(ref: DocumentReference, o: JSONObject) { batch.set(ref, CloudSync.toMap(o)); n++; total++; if (n >= 400) flush() }
        fun delete(ref: DocumentReference) { batch.delete(ref); n++; total++; if (n >= 400) flush() }
        fun done() = flush()
    }

    private fun pushDiff() {
        val a = app ?: return
        val u = uid ?: return
        val b = base(u)
        val w = Writer(db())
        val local = Library.snapshot()
        val tomb = local.optJSONObject("deleted") ?: JSONObject()

        // playlists
        val localPl = HashMap<String, JSONObject>()
        val pa = local.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            val id = p.optString("id")
            if (id.isBlank()) continue
            val c = cloudPlaylist(p)
            localPl[id] = c
            if (plDocs[id]?.let { canon(it) } != canon(c)) w.set(b.collection("playlists").document(id), c)
        }
        for (id in plDocs.keys) if (id !in localPl && tomb.has("pl:$id")) w.delete(b.collection("playlists").document(id))

        // favorites (only media that has a web link can be shared between devices)
        val localFav = HashSet<String>()
        val fa = local.optJSONArray("favorites") ?: JSONArray()
        for (i in 0 until fa.length()) {
            val f = fa.optJSONObject(i) ?: continue
            val page = f.optString("page")
            if (page.isBlank()) continue
            val id = sha(page)
            localFav += id
            val c = cloudEntry(f).put("at", f.optLong("at"))
            if (favDocs[id]?.let { canon(it) } != canon(c)) w.set(b.collection("favorites").document(id), c)
        }
        for ((id, d) in favDocs) if (id !in localFav && tomb.has("fav:" + d.optString("page"))) w.delete(b.collection("favorites").document(id))

        // Bluetooth rules
        val localBt = HashSet<String>()
        val ra = local.optJSONArray("bt") ?: JSONArray()
        for (i in 0 until ra.length()) {
            val r = ra.optJSONObject(i) ?: continue
            val id = r.optString("id")
            if (id.isBlank()) continue
            localBt += id
            val c = JSONObject().put("id", id).put("name", r.optString("name")).put("addr", r.optString("addr")).put("pl", r.optString("pl"))
            if (btDocs[id]?.let { canon(it) } != canon(c)) w.set(b.collection("btRules").document(id), c)
        }
        for (id in btDocs.keys) if (id !in localBt && tomb.has("bt:$id")) w.delete(b.collection("btRules").document(id))

        // deletions travel as tombstones
        for (k in tomb.keys()) {
            val at = tomb.optLong(k)
            val id = sha(k)
            if ((tombDocs[id]?.optLong("at") ?: -1L) < at) w.set(b.collection("tombstones").document(id), JSONObject().put("k", k).put("at", at))
        }

        // preferences
        var at = Prefs.long(a, "settings_at")
        val remoteAt = settingsDoc?.optLong("at") ?: 0L
        if (settingsDoc == null && at == 0L) { at = System.currentTimeMillis(); Prefs.putLong(a, "settings_at", at) }
        if (at > remoteAt && at != lastSettingsPush) {
            val json = try { settingsOut?.invoke() } catch (_: Exception) { null }
            if (json != null) {
                lastSettingsPush = at
                w.set(b.collection("meta").document("settings"), JSONObject().put("json", json.toString()).put("at", at))
            }
        }
        w.done()
    }

    // ---------------------------------------------------------------- user actions
    /**
     * "Sync now": sends everything that is waiting and waits for the server to confirm it.
     * @return an error message, or null when the cloud is up to date.
     */
    suspend fun syncNow(): String? {
        if (uid == null) return L.t("Sign in first.", "سجّل الدخول أولًا.")
        if (_state.value == SyncState.Error && _error.value.isNotEmpty()) return _error.value
        pushLocal()
        return try {
            withTimeout(20_000) { db().waitForPendingWrites().await() }
            null
        } catch (_: TimeoutCancellationException) {
            L.t("No connection right now. Your changes are saved on this phone and will sync by themselves.",
                "لا يوجد اتصال الآن. تغييراتك محفوظة على هذا الهاتف وستتم مزامنتها تلقائيًا.")
        } catch (e: Exception) {
            L.t("Sync failed: ", "فشلت المزامنة: ") + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- JSON <-> Firestore maps
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(40)

    private fun cloudEntry(e: JSONObject): JSONObject {
        val o = JSONObject()
        for (k in listOf("title", "uploader", "dur", "thumb", "page", "audio", "site")) if (e.has(k)) o.put(k, e.get(k))
        return o   // the file on this phone ("local") is deliberately left out
    }

    private fun cloudPlaylist(p: JSONObject): JSONObject {
        val entries = JSONArray()
        val ea = p.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until ea.length()) ea.optJSONObject(i)?.let { entries.put(cloudEntry(it)) }
        return JSONObject().put("id", p.optString("id")).put("name", p.optString("name")).put("kind", p.optString("kind"))
            .put("at", p.optLong("at")).put("cr", p.optLong("cr")).put("entries", entries)
            .also { o -> p.optJSONObject("sch")?.let { o.put("sch", it) } }      // the playlist's schedule travels with it
            .also { o -> p.optJSONObject("sch")?.let { o.put("sch", it) } }
    }

    /** Stable text of a JSON value (keys sorted), so equal content always compares equal. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun jsonOf(v: Any?): Any = when (v) {
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), jsonOf(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(jsonOf(it)) } }
        null -> ""
        else -> v
    }

    private fun plain(v: Any?): Any = when (v) {
        is JSONObject -> toMap(v)
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        is Int -> v.toLong()
        null -> ""
        else -> v
    }

    private fun toMap(o: JSONObject): Map<String, Any> {
        val m = HashMap<String, Any>()
        for (k in o.keys()) m[k] = plain(o.opt(k))
        return m
    }
}
