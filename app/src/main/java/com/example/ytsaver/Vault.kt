package com.example.ytsaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** [HistoryItem.location] of a vault file while it is played. */
const val LOCATION_VAULT = "Vault"

/** One file kept in the vault: a private copy inside the app's own storage. */
data class VaultItem(
    val id: String,
    val name: String,
    val format: String,       // "MP3" or "MP4"
    val quality: Int,
    val sizeBytes: Long,
    val addedAt: Long,
    val sourceUrl: String,
    val fileName: String,     // name of the file inside the vault folder
)

fun VaultItem.toHistoryItem(ctx: Context): HistoryItem = HistoryItem(
    name = name, format = format, quality = quality, sizeBytes = sizeBytes, timeMillis = addedAt,
    location = LOCATION_VAULT, uri = Uri.fromFile(File(VaultStore.dir(ctx), fileName)).toString(), sourceUrl = sourceUrl,
)

/**
 * The vault: files moved here leave Media / History / playlists / Favorites / Android Auto and live in the app's
 * private storage (other apps and the gallery can't see them). Opening the vault needs the vault PIN; it locks again
 * when the app goes to the background. Kept on this phone only (not synced, not in backups).
 */
object VaultStore {
    private const val K_HASH = "vault_pin_hash"
    private const val K_SALT = "vault_pin_salt"
    private const val ITERATIONS = 60_000

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()
    /** The vault page is open inside Settings (kept here so it survives the player opening on top of it). */
    val screenOpen = MutableStateFlow(false)

    private var file: File? = null
    private var failures = 0
    @Volatile var lockedUntil = 0L
        private set
    /** Set just before a vault file is played (the player covers the vault page, which must not lock then). */
    @Volatile var keepForPlayer = false

    fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "vault").also { it.mkdirs() }

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "vault.json")
        file = f
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val d = dir(ctx)
            _items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VaultItem(
                    o.optString("id"), o.optString("name"), o.optString("format"), o.optInt("quality"),
                    o.optLong("size"), o.optLong("added"), o.optString("url"), o.optString("file"),
                )
            }.filter { it.id.isNotBlank() && File(d, it.fileName).exists() }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _items.value.forEach {
                arr.put(
                    JSONObject().put("id", it.id).put("name", it.name).put("format", it.format).put("quality", it.quality)
                        .put("size", it.sizeBytes).put("added", it.addedAt).put("url", it.sourceUrl).put("file", it.fileName),
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    // ---------------- PIN ----------------
    fun hasPin(ctx: Context): Boolean = Prefs.str(ctx, K_HASH) != null

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** Stores a new PIN (only its salted hash is kept; the PIN itself is never saved). */
    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Prefs.putStr(ctx, K_SALT, hex(salt))
        Prefs.putStr(ctx, K_HASH, hex(hash(pin, salt)))
    }

    fun verify(ctx: Context, pin: String): Boolean {
        val h = Prefs.str(ctx, K_HASH) ?: return false
        val s = Prefs.str(ctx, K_SALT) ?: return false
        return try {
            MessageDigest.isEqual(unhex(h), hash(pin, unhex(s)))
        } catch (_: Exception) {
            false
        }
    }

    /** 0 = unlocked, 1 = wrong PIN, 2 = too many wrong tries (wait until [lockedUntil]). */
    fun unlock(ctx: Context, pin: String): Int {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return 2
        if (verify(ctx, pin)) {
            failures = 0
            _unlocked.value = true
            return 0
        }
        failures++
        if (failures >= 5) {
            failures = 0
            lockedUntil = now + 30_000L
            return 2
        }
        return 1
    }

    /** Locks the vault and takes any vault file out of Now playing. Call on the main thread. */
    fun lock(ctx: Context) {
        _unlocked.value = false
        val inQueue = PlayerHolder.entries.value.filter { it.location == LOCATION_VAULT }
        if (inQueue.isNotEmpty()) inQueue.forEach { PlayerHolder.removeByUri(ctx, it.uri) }
    }

    // ---------------- moving files ----------------
    class MoveResult(val moved: Int, val originalsKept: Int)

    /** Copies [list] into the vault and takes it out of Media. Imported device files are copied too; their originals stay. */
    suspend fun moveIn(ctx: Context, list: List<HistoryItem>): MoveResult {
        val app = ctx.applicationContext
        load(app)
        var kept = 0
        val done = mutableListOf<HistoryItem>()
        withContext(Dispatchers.IO) {
            val d = dir(app)
            for (h in list) {
                val rawExt = h.name.substringAfterLast('.', "")
                val ext = if (rawExt.length in 2..5 && rawExt.all { it.isLetterOrDigit() }) rawExt else if (h.format == "MP3") "mp3" else "mp4"
                val fileName = UUID.randomUUID().toString() + "." + ext
                val dest = File(d, fileName)
                val ok = try {
                    app.contentResolver.openInputStream(Uri.parse(h.uri))?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    dest.length() > 0
                } catch (_: Exception) {
                    false
                }
                if (!ok) { dest.delete(); continue }
                val v = VaultItem(UUID.randomUUID().toString(), h.name, h.format, h.quality, dest.length(), System.currentTimeMillis(), h.sourceUrl, fileName)
                synchronized(this@VaultStore) { _items.value = listOf(v) + _items.value; save() }
                done += h
                // the original: our own downloads are deleted, the user's own files are never touched
                if (h.isDeviceFile || !deleteSavedFile(app, h)) kept++
            }
            // the file must disappear from every list that could show its title
            HistoryStore.removeAll(app, done)
            val uris = done.map { it.uri }.toSet()
            FolderStore.move(uris, null)
            uris.forEach { ResumeStore.forget(app, it) }
            Library.favorites.value.filter { it.entry.localUri in uris }.forEach { Library.toggleFavorite(it.entry) }
            Library.playlists.value.forEach { p ->
                p.entries.filter { it.localUri in uris }.forEach { Library.removeFromPlaylist(p.id, it.key) }
            }
        }
        withContext(NonCancellable + Dispatchers.Main) { done.forEach { PlayerHolder.removeByUri(app, it.uri) } }
        return MoveResult(done.size, kept)
    }

    /** Puts [list] back into Downloads/MediaSaver and into Media; returns how many were restored. */
    suspend fun moveOut(ctx: Context, list: List<VaultItem>): Int {
        val app = ctx.applicationContext
        val restored = mutableListOf<HistoryItem>()
        val gone = mutableListOf<VaultItem>()
        withContext(Dispatchers.IO) {
            val d = dir(app)
            for (v in list) {
                val src = File(d, v.fileName)
                try {
                    val ext = v.fileName.substringAfterLast('.', "")
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
                        ?: if (v.format == "MP3") "audio/mpeg" else "video/mp4"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, v.name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MediaSaver")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                    var ok = false
                    try {
                        val out = app.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("cannot open the target")
                        val copied = out.use { o -> src.inputStream().use { it.copyTo(o) } }
                        if (copied != src.length()) throw java.io.IOException("short copy")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        ok = app.contentResolver.update(uri, values, null, null) > 0
                    } finally {
                        // a failed restore leaves nothing half-written in Downloads, and the file stays in the vault
                        if (!ok) runCatching { app.contentResolver.delete(uri, null, null) }
                    }
                    if (!ok) continue
                    restored += HistoryItem(v.name, v.format, v.quality, v.sizeBytes, System.currentTimeMillis(), "Downloads/MediaSaver", uri.toString(), v.sourceUrl)
                    gone += v
                    src.delete()
                } catch (_: Exception) {
                    // this file stays in the vault
                }
            }
            if (restored.isNotEmpty()) HistoryStore.addAll(app, restored)
            forget(gone)
        }
        withContext(NonCancellable + Dispatchers.Main) { gone.forEach { PlayerHolder.removeByUri(app, it.toHistoryItem(app).uri) } }
        return gone.size
    }

    suspend fun deleteForever(ctx: Context, list: List<VaultItem>) {
        val app = ctx.applicationContext
        withContext(Dispatchers.IO) {
            list.forEach { File(dir(app), it.fileName).delete() }
            forget(list)
        }
        withContext(NonCancellable + Dispatchers.Main) { list.forEach { PlayerHolder.removeByUri(app, it.toHistoryItem(app).uri) } }
    }

    @Synchronized
    private fun forget(list: List<VaultItem>) {
        val ids = list.map { it.id }.toSet()
        _items.value = _items.value.filter { it.id !in ids }
        save()
    }

    /** Forgotten PIN / start over: deletes every file in the vault and removes the PIN. */
    suspend fun eraseAll(ctx: Context) {
        val app = ctx.applicationContext
        deleteForever(app, _items.value)
        withContext(Dispatchers.IO) { dir(app).listFiles()?.forEach { it.delete() } }      // also any file the index lost
        Prefs.putStr(app, K_HASH, null)
        Prefs.putStr(app, K_SALT, null)
        _unlocked.value = false
    }
}

// =====================================================================================================
//  UI
// =====================================================================================================

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Set the vault PIN ([change] = false) or change it (asks for the current one first). */
@Composable
fun VaultPinDialog(change: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var old by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (change) tr("Change vault PIN", "تغيير رمز الخزنة") else tr("Set a vault PIN", "تعيين رمز الخزنة")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!change) Text(
                    tr("The PIN opens the vault. It cannot be recovered: if you forget it, the only way out is to erase the vault.",
                        "الرمز يفتح الخزنة ولا يمكن استرجاعه: إذا نسيته فالحل الوحيد هو مسح الخزنة."),
                    color = Muted, fontSize = 12.sp,
                )
                if (change) PinField(old, { old = it }, tr("Current PIN", "الرمز الحالي"))
                PinField(p1, { p1 = it }, tr("New PIN (4–12 digits)", "الرمز الجديد (4–12 رقمًا)"))
                PinField(p2, { p2 = it }, tr("Repeat the PIN", "أعد كتابة الرمز"))
                err?.let { Text(it, color = Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (change && !VaultStore.verify(ctx, old)) {
                    err = L.t("The current PIN is wrong.", "الرمز الحالي غير صحيح.")
                } else if (p1.length < 4) {
                    err = L.t("Use at least 4 digits.", "استخدم 4 أرقام على الأقل.")
                } else if (p1 != p2) {
                    err = L.t("The two PINs don't match.", "الرمزان غير متطابقين.")
                } else {
                    VaultStore.setPin(ctx, p1)
                    onDone()
                }
            }) { Text(tr("Save", "حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/**
 * "Move to vault": asks to confirm (and to set a PIN first when there is none), then moves [items].
 * [onDone] is called when the flow ends, whatever the outcome.
 */
@Composable
fun VaultMoveFlow(items: List<HistoryItem>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var needPin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val anyDevice = items.any { it.isDeviceFile }

    fun go() {
        busy = true
        scope.launch {
            val r = VaultStore.moveIn(ctx, items)
            toast(
                ctx,
                if (r.moved == 0) L.t("Nothing was moved: the file(s) could not be read.", "لم يتم نقل شيء: تعذّرت قراءة الملفات.")
                else if (r.originalsKept > 0) L.t("${r.moved} moved to the vault. ${r.originalsKept} original(s) stay on your phone: delete them yourself to hide them completely.",
                    "تم نقل ${r.moved} إلى الخزنة. بقي ${r.originalsKept} من الأصول على هاتفك: احذفها بنفسك لإخفائها تمامًا.")
                else L.t("${r.moved} moved to the vault", "تم نقل ${r.moved} إلى الخزنة"),
            )
            onDone()
        }
    }

    if (needPin) {
        VaultPinDialog(change = false, onDismiss = { needPin = false; onDone() }, onDone = { needPin = false; go() })
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) onDone() },
            title = { Text(tr("Move ${items.size} file(s) to the vault?", "نقل ${items.size} ملف إلى الخزنة؟")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("They leave Media, History, playlists and Favorites and are kept in the app's private storage. You open them from Settings → Vault with your PIN.",
                            "تغادر الوسائط والسجل وقوائم التشغيل والمفضلة وتُحفظ في التخزين الخاص بالتطبيق. تفتحها من الإعدادات ← الخزنة بالرمز."),
                    )
                    if (anyDevice) Text(
                        tr("Files imported from your phone are copied: the original stays where it is until you delete it yourself.",
                            "الملفات المستوردة من هاتفك تُنسخ: يبقى الأصل في مكانه حتى تحذفه بنفسك."),
                        color = Muted, fontSize = 12.sp,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { if (VaultStore.hasPin(ctx)) go() else needPin = true }) {
                    Text(tr("Move to vault", "نقل إلى الخزنة"))
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}

/** Settings -> Vault: the full-screen vault page (PIN first, then Audio / Video lists). */
@Composable
fun VaultScreen(vm: DownloaderViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val unlocked by VaultStore.unlocked.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    // leaving the vault page (back, Settings, another page, the app) locks it again; opening the player on a vault file does not
    DisposableEffect(Unit) {
        VaultStore.keepForPlayer = false
        onDispose { if (!VaultStore.keepForPlayer) VaultStore.lock(ctx) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع")) }
            Text(tr("Vault", "الخزنة"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        if (unlocked) VaultContent(vm) else VaultLocked()
    }
}

@Composable
private fun VaultLocked() {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var setOpen by remember { mutableStateOf(false) }
    val hasPin = remember(setOpen) { VaultStore.hasPin(ctx) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Sym(R.drawable.ic_lock, 48.dp, tint = Muted)
        Spacer(Modifier.height(12.dp))
        if (!hasPin) {
            Text(tr("Set a PIN to start using the vault.", "عيّن رمزًا لبدء استخدام الخزنة."), color = Muted)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { setOpen = true }, shape = RoundedCornerShape(12.dp)) { Text(tr("Set PIN", "تعيين الرمز")) }
        } else {
            Text(tr("Enter your PIN to open the vault.", "أدخل الرمز لفتح الخزنة."), color = Muted)
            Spacer(Modifier.height(12.dp))
            PinField(pin, { pin = it; msg = null }, tr("PIN", "الرمز"))
            msg?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    when (VaultStore.unlock(ctx, pin)) {
                        0 -> pin = ""
                        1 -> { msg = L.t("Wrong PIN.", "الرمز غير صحيح."); pin = "" }
                        else -> {
                            val s = ((VaultStore.lockedUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                            msg = L.t("Too many wrong tries. Wait $s s.", "محاولات خاطئة كثيرة. انتظر $s ثانية.")
                            pin = ""
                        }
                    }
                },
                enabled = pin.length >= 4,
                shape = RoundedCornerShape(12.dp),
            ) { Text(tr("Unlock", "فتح")) }
        }
    }
    if (setOpen) VaultPinDialog(change = false, onDismiss = { setOpen = false }, onDone = { setOpen = false })
}

@Composable
private fun VaultContent(vm: DownloaderViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by VaultStore.items.collectAsStateWithLifecycle()
    var sub by rememberSaveable { mutableIntStateOf(0) }            // 0 = audio, 1 = video
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }    // ids
    var deleteList by remember { mutableStateOf<List<VaultItem>?>(null) }
    val audioCount = all.count { it.format == "MP3" }
    val videoCount = all.count { it.format != "MP3" }
    val list = remember(all, sub) { all.filter { (it.format == "MP3") == (sub == 0) }.sortedByDescending { it.addedAt } }
    val chosen = list.filter { it.id in selected }
    fun exitSelect() { selectMode = false; selected = emptySet() }

    fun restore(l: List<VaultItem>) {
        scope.launch {
            val n = VaultStore.moveOut(ctx, l)
            toast(ctx, L.t("$n restored to Media (Downloads/MediaSaver)", "تمت استعادة $n إلى الوسائط (Downloads/MediaSaver)"))
            exitSelect()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub, containerColor = Color.Transparent) {
            Tab(selected = sub == 0, onClick = { sub = 0; exitSelect() }, text = { Text(tr("Audio", "صوت") + " ($audioCount)") })
            Tab(selected = sub == 1, onClick = { sub = 1; exitSelect() }, text = { Text(tr("Video", "فيديو") + " ($videoCount)") })
        }
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    tr("The vault is empty. In Media, use a file's three-dot menu → Move to vault, or Select → Move to vault.",
                        "الخزنة فارغة. في الوسائط استخدم قائمة النقاط الثلاث للملف ← نقل إلى الخزنة، أو تحديد ← نقل إلى الخزنة."),
                    color = Muted, modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selectMode) tr("${selected.size} selected", "تم تحديد ${selected.size}") else tr("${list.size} file(s)", "${list.size} ملف"),
                    color = Muted,
                )
                Row {
                    TextButton(onClick = { if (selectMode) exitSelect() else selectMode = true }) {
                        Text(if (selectMode) tr("Done", "تم") else tr("Select", "تحديد"))
                    }
                    if (selectMode) TextButton(onClick = { selected = list.map { it.id }.toSet() }) { Text(tr("Select all", "تحديد الكل")) }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(list, key = { it.id }) { v ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = selectMode) {
                            selected = if (v.id in selected) selected - v.id else selected + v.id
                        },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectMode) Checkbox(
                                    checked = v.id in selected,
                                    onCheckedChange = { selected = if (v.id in selected) selected - v.id else selected + v.id },
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(prettyName(v.name), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${v.format} • ${formatSize(v.sizeBytes)}",
                                        color = Muted, fontSize = 12.sp,
                                    )
                                }
                                if (!selectMode) IconButton(onClick = { VaultStore.keepForPlayer = true; vm.playNow(listOf(v.toHistoryItem(ctx))) }) {
                                    Sym(R.drawable.ic_play, 24.dp, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (!selectMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { restore(listOf(v)) }) { Text(tr("Restore to Media", "استعادة إلى الوسائط"), fontSize = 12.sp) }
                                TextButton(onClick = { deleteList = listOf(v) }) { Text(tr("Delete", "حذف"), fontSize = 12.sp, color = Red) }
                            }
                        }
                    }
                }
            }
            if (selectMode && chosen.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { restore(chosen) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Text(tr("Restore (${chosen.size})", "استعادة (${chosen.size})"), maxLines = 1, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { deleteList = chosen }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Delete (${chosen.size})", "حذف (${chosen.size})"), color = Red, maxLines = 1, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    deleteList?.let { l ->
        AlertDialog(
            onDismissRequest = { deleteList = null },
            title = { Text(tr("Delete ${l.size} file(s) for good?", "حذف ${l.size} ملف نهائيًا؟")) },
            text = { Text(tr("They are erased from the vault and can't be recovered.", "تُمسح من الخزنة ولا يمكن استرجاعها.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteList = null
                    scope.launch { VaultStore.deleteForever(ctx, l); exitSelect() }
                }) { Text(tr("Delete", "حذف"), color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteList = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}

/** The Vault card of the Settings page: PIN, open, erase. */
@Composable
fun VaultSettingsCard(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by VaultStore.items.collectAsStateWithLifecycle()
    var pinDialog by remember { mutableStateOf(false) }
    var eraseOpen by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }                 // re-reads "has PIN" after a change
    val hasPin = remember(version) { VaultStore.hasPin(ctx) }
    Text(
        tr("Keep private files away from Media, History and playlists. Files in the vault live in the app's private storage and open only with your PIN. The vault locks by itself as soon as you leave it (or the app).",
            "احتفظ بالملفات الخاصة بعيدًا عن الوسائط والسجل وقوائم التشغيل. ملفات الخزنة تُحفظ في التخزين الخاص بالتطبيق ولا تُفتح إلا بالرمز. تُقفل الخزنة تلقائيًا بمجرد مغادرتها (أو مغادرة التطبيق)."),
        color = Muted, fontSize = 13.sp,
    )
    Text(
        (if (hasPin) tr("PIN is set", "الرمز معيّن") else tr("No PIN yet", "لا يوجد رمز بعد")) + " • " +
            tr("${items.size} file(s) in the vault", "${items.size} ملف في الخزنة"),
        fontSize = 13.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onOpen, shape = RoundedCornerShape(10.dp)) { Text(tr("Open vault", "فتح الخزنة")) }
        OutlinedButton(onClick = { pinDialog = true }, shape = RoundedCornerShape(10.dp)) {
            Text(if (hasPin) tr("Change PIN", "تغيير الرمز") else tr("Set PIN", "تعيين الرمز"))
        }
    }
    if (hasPin) TextButton(onClick = { eraseOpen = true }) {
        Text(tr("Forgot the PIN? Erase the vault", "نسيت الرمز؟ مسح الخزنة"), color = Red, fontSize = 13.sp)
    }
    if (pinDialog) VaultPinDialog(change = hasPin, onDismiss = { pinDialog = false }, onDone = { pinDialog = false; version++ })
    if (eraseOpen) {
        AlertDialog(
            onDismissRequest = { eraseOpen = false },
            title = { Text(tr("Erase the vault?", "مسح الخزنة؟")) },
            text = { Text(tr("Every file in the vault is deleted for good and the PIN is removed. This can't be undone.", "تُحذف كل ملفات الخزنة نهائيًا ويُزال الرمز. لا يمكن التراجع.")) },
            confirmButton = {
                TextButton(onClick = {
                    eraseOpen = false
                    scope.launch { VaultStore.eraseAll(ctx); version++ }
                }) { Text(tr("Erase everything", "مسح كل شيء"), color = Red) }
            },
            dismissButton = { TextButton(onClick = { eraseOpen = false }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}
