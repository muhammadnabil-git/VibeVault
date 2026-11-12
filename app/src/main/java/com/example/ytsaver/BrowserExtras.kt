package com.example.ytsaver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// =====================================================================================================
// Saved passwords: encrypted with a key that never leaves the phone's secure hardware (Android Keystore)
// =====================================================================================================

class SavedLogin(val host: String, val user: String, val pass: String)

object PasswordStore {
    private const val ALIAS = "media_saver_browser_pw"
    private const val KEY = "browser_logins"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String? = try {
        val b = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    } catch (_: Exception) {
        null      // e.g. the phone was restored from a backup: the key is not there any more
    }

    fun enabled(ctx: Context) = Prefs.bool(ctx, "browser_pw_on", true)
    fun setEnabled(ctx: Context, v: Boolean) = Prefs.putBool(ctx, "browser_pw_on", v)

    @Synchronized
    fun all(ctx: Context): List<SavedLogin> = try {
        val arr = JSONArray(Prefs.str(ctx, KEY) ?: "[]")
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val p = decrypt(o.optString("p")) ?: return@mapNotNull null
            SavedLogin(o.optString("h"), o.optString("u"), p)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun forHost(ctx: Context, host: String): List<SavedLogin> = all(ctx).filter { it.host == host }

    @Synchronized
    private fun write(ctx: Context, list: List<SavedLogin>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("h", it.host).put("u", it.user).put("p", encrypt(it.pass))) }
        Prefs.putStr(ctx, KEY, arr.toString())
    }

    /** The newest login goes first, so it is the one filled in. */
    fun save(ctx: Context, host: String, user: String, pass: String) {
        val rest = all(ctx).filterNot { it.host == host && it.user == user }
        write(ctx, listOf(SavedLogin(host, user, pass)) + rest)
    }

    fun delete(ctx: Context, host: String, user: String) = write(ctx, all(ctx).filterNot { it.host == host && it.user == user })
    fun clear(ctx: Context) = Prefs.putStr(ctx, KEY, null)
}

/** Called by the page script that watches sign-in forms. Only passes what the user just typed; nothing can be read back. */
class BrowserBridge(
    /** The address of the page the user is looking at (main frame); the bridge only trusts logins that belong to it. */
    private val currentUrl: () -> String,
    private val onLogin: (host: String, user: String, pass: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun credentials(host: String?, user: String?, pass: String?) {
        if (host.isNullOrBlank() || pass.isNullOrEmpty()) return
        if (host.length > 253 || (user?.length ?: 0) > 320 || pass.length > 1024) return
        main.post {
            // a frame or script of another site cannot make the app offer to save a password for a different site,
            // and nothing is offered for pages that are not encrypted
            val page = currentUrl()
            val pageHost = hostOf(page).removePrefix("www.").substringBefore(':')
            if (page.startsWith("https://") && pageHost.equals(host.removePrefix("www."), ignoreCase = true)) {
                onLogin(host, user.orEmpty(), pass)
            }
        }
    }
}

/** Watches sign-in forms (a submit, or a tap on a button next to a password field) and reports what was typed. */
const val CAPTURE_LOGIN_JS = """(function(){if(window.__msPw)return;window.__msPw=1;
function grab(scope){try{var s=scope||document;var p=s.querySelector('input[type=password]');if(!p||!p.value)return;
var ins=s.querySelectorAll('input');var u='';
for(var i=0;i<ins.length;i++){if(ins[i]===p)break;var t=(ins[i].type||'text').toLowerCase();
if((t==='text'||t==='email'||t==='tel')&&ins[i].value)u=ins[i].value;}
MSBridge.credentials(location.hostname,u,p.value);}catch(e){}}
document.addEventListener('submit',function(e){grab(e.target)},true);
document.addEventListener('click',function(e){var b=e.target&&e.target.closest?e.target.closest('button,input[type=submit],[role=button]'):null;
if(b)grab(b.form||null)},true);})();"""

/** Fills the saved login into the sign-in form of the page, when there is one and it is still empty. */
fun fillLoginJs(user: String, pass: String): String =
    "(function(u,p){function set(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');" +
        "d.set.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
        "var pw=document.querySelector('input[type=password]');if(!pw||pw.value)return;" +
        "var ins=document.querySelectorAll('input');var us=null;" +
        "for(var i=0;i<ins.length;i++){if(ins[i]===pw)break;var t=(ins[i].type||'text').toLowerCase();" +
        "if(t==='text'||t==='email'||t==='tel')us=ins[i];}" +
        "if(us&&!us.value&&u)set(us,u);set(pw,p);})(" + JSONObject.quote(user) + "," + JSONObject.quote(pass) + ");"

// =====================================================================================================
// Bookmarks and history
// =====================================================================================================

class BrowserMark(val title: String, val url: String, val time: Long)

object BrowserData {
    private const val BOOKMARKS = "browser_bookmarks"
    private const val HISTORY = "browser_history"

    private fun read(ctx: Context, key: String): List<BrowserMark> = try {
        val arr = JSONArray(Prefs.str(ctx, key) ?: "[]")
        (0 until arr.length()).map { val o = arr.getJSONObject(it); BrowserMark(o.optString("t"), o.optString("u"), o.optLong("at")) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(ctx: Context, key: String, list: List<BrowserMark>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("at", it.time)) }
        Prefs.putStr(ctx, key, arr.toString())
    }

    fun bookmarks(ctx: Context) = read(ctx, BOOKMARKS)
    fun isBookmarked(ctx: Context, url: String) = bookmarks(ctx).any { it.url == url }

    /** Returns true when the page is now bookmarked. */
    fun toggleBookmark(ctx: Context, title: String, url: String): Boolean {
        val list = bookmarks(ctx)
        return if (list.any { it.url == url }) {
            write(ctx, BOOKMARKS, list.filterNot { it.url == url }); false
        } else {
            write(ctx, BOOKMARKS, listOf(BrowserMark(title.ifBlank { url }, url, System.currentTimeMillis())) + list); true
        }
    }

    fun removeBookmark(ctx: Context, url: String) = write(ctx, BOOKMARKS, bookmarks(ctx).filterNot { it.url == url })

    fun history(ctx: Context) = read(ctx, HISTORY)

    fun addHistory(ctx: Context, title: String, url: String) {
        if (!url.startsWith("http")) return
        val list = history(ctx).filterNot { it.url == url }
        write(ctx, HISTORY, (listOf(BrowserMark(title.ifBlank { url }, url, System.currentTimeMillis())) + list).take(300))
    }

    fun clearHistory(ctx: Context) = Prefs.putStr(ctx, HISTORY, null)
}

// =====================================================================================================
// Songs on Spotify / Shazam pages
// =====================================================================================================

/** What the browser could read from an open song page. */
class SongPage(val title: String, val artist: String, val previewUrl: String?) {
    val query: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
}

object SongPages {
    /** True for a page of ONE song on Spotify or Shazam. */
    fun isSongPage(url: String): Boolean {
        val h = hostOf(url)
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').lowercase()
        return (h.contains("spotify.") && (path.startsWith("track/") || path.contains("/track/"))) ||
            (h.contains("shazam.") && (path.contains("track/") || path.contains("song/")))
    }

    /** Runs in the page: collects its title, the sharing tags and the structured data (JSON-LD). */
    const val READ_JS = """(function(){var r={title:document.title||'',og:'',desc:'',audio:'',ld:[]};
function m(sel){var e=document.querySelector(sel);return e?(e.content||''):'';}
r.og=m('meta[property="og:title"]');r.desc=m('meta[property="og:description"]')||m('meta[name=description]');
r.audio=m('meta[property="og:audio"]')||m('meta[property="og:audio:url"]')||m('meta[name="music:preview_url:url"]');
document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){r.ld.push(s.textContent)});
return JSON.stringify(r);})()"""

    /** Parses the answer of [READ_JS] (the string the WebView returns is itself JSON-quoted). */
    fun parse(url: String, evalResult: String?): SongPage? {
        val raw = try { JSONTokener(evalResult ?: return null).nextValue() as? String ?: return null } catch (_: Exception) { return null }
        val o = try { JSONObject(raw) } catch (_: Exception) { return null }
        var title = ""
        var artist = ""
        var preview = o.optString("audio").takeIf { it.startsWith("http") }
        // 1) structured data: the most reliable (Shazam has it)
        val ld = o.optJSONArray("ld") ?: JSONArray()
        for (i in 0 until ld.length()) {
            val node = try { JSONTokener(ld.getString(i)).nextValue() } catch (_: Exception) { continue }
            val objs = when (node) {
                is JSONObject -> listOf(node)
                is JSONArray -> (0 until node.length()).mapNotNull { node.optJSONObject(it) }
                else -> emptyList()
            }
            for (j in objs) {
                if (!j.optString("@type").contains("Music")) continue
                if (title.isBlank()) title = j.optString("name")
                if (artist.isBlank()) {
                    val by = j.opt("byArtist") ?: j.opt("artist")
                    artist = when (by) {
                        is JSONObject -> by.optString("name")
                        is JSONArray -> by.optJSONObject(0)?.optString("name").orEmpty()
                        is String -> by
                        else -> ""
                    }
                }
                if (preview == null) {
                    val a = j.opt("audio")
                    val cu = when (a) { is JSONObject -> a.optString("contentUrl"); is String -> a; else -> "" }
                    if (cu.startsWith("http")) preview = cu
                }
            }
        }
        // 2) the page title: "Song - song and lyrics by Artist | Spotify"
        val pageTitle = o.optString("title")
        if (title.isBlank() || artist.isBlank()) {
            Regex("^(.*) - song(?: and lyrics)? by (.*?) \\| Spotify$").find(pageTitle)?.let {
                if (title.isBlank()) title = it.groupValues[1]
                if (artist.isBlank()) artist = it.groupValues[2]
            }
        }
        // 3) sharing tags: og:title = song, the description starts with the artist ("Artist · Song · 2020")
        if (title.isBlank()) title = o.optString("og").ifBlank { pageTitle }
        if (artist.isBlank()) {
            val d = o.optString("desc").substringAfter("on Spotify. ", o.optString("desc"))
            if (d.contains(" · ")) artist = d.substringBefore(" · ").trim()
        }
        // 4) Shazam titles: "Song - Artist | Shazam", "Song by Artist - Shazam"
        if (artist.isBlank()) {
            val t = title.replace(Regex("\\s*[|\\-–]\\s*Shazam.*$", RegexOption.IGNORE_CASE), "")
            Regex("^(.*?)\\s+by\\s+(.*)$").find(t)?.let { title = it.groupValues[1]; artist = it.groupValues[2] }
                ?: Regex("^(.*?)\\s+[-–]\\s+(.*)$").find(t)?.let { title = it.groupValues[1]; artist = it.groupValues[2] }
        }
        title = title.replace(Regex("\\s*[|\\-–]\\s*(Spotify|Shazam).*$", RegexOption.IGNORE_CASE), "").trim()
        if (title.isBlank()) return null
        return SongPage(title, artist.trim(), preview)
    }
}

// =====================================================================================================
// Dialogs
// =====================================================================================================

@Composable
fun SavedPasswordsDialog(ctx: Context, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(PasswordStore.all(ctx)) }
    var on by remember { mutableStateOf(PasswordStore.enabled(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Saved passwords", "كلمات المرور المحفوظة")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Offer to save passwords", "اقتراح حفظ كلمات المرور"), Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { on = it; PasswordStore.setEnabled(ctx, it) })
                }
                Text(
                    tr("They are encrypted with a key kept in the phone's secure storage and are used only to fill sign-in forms in this browser.",
                        "تُشفَّر بمفتاح محفوظ في التخزين الآمن للهاتف وتُستخدم فقط لملء نماذج الدخول في هذا المتصفح."),
                    color = Muted, fontSize = 12.sp,
                )
                if (list.isEmpty()) Text(tr("Nothing saved yet.", "لا شيء محفوظ بعد."), color = Muted)
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list, key = { it.host + "\u0000" + it.user }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(l.user.ifBlank { tr("(no user name)", "(بدون اسم مستخدم)") }, color = Muted, fontSize = 12.sp, maxLines = 1)
                            }
                            TextButton(onClick = { PasswordStore.delete(ctx, l.host, l.user); list = PasswordStore.all(ctx) }) {
                                Text(tr("Delete", "حذف"), color = Red)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "إغلاق")) } },
    )
}

/** Bookmarks or history: tap a row to open it. */
@Composable
fun BrowserListDialog(ctx: Context, history: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(if (history) BrowserData.history(ctx) else BrowserData.bookmarks(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (history) tr("History", "السجل") else tr("Bookmarks", "الإشارات المرجعية")) },
        text = {
            if (list.isEmpty()) Text(if (history) tr("No pages visited yet.", "لم تُزَر صفحات بعد.") else tr("No bookmarks yet.", "لا توجد إشارات مرجعية بعد."), color = Muted)
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(list, key = { it.url }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(m.url); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            Text(m.url, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!history) TextButton(onClick = { BrowserData.removeBookmark(ctx, m.url); list = BrowserData.bookmarks(ctx) }) {
                            Text("✕", color = Red)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "إغلاق")) } },
        dismissButton = if (history && list.isNotEmpty()) {
            { TextButton(onClick = { BrowserData.clearHistory(ctx); list = emptyList() }) { Text(tr("Clear history", "مسح السجل"), color = Red) } }
        } else null,
    )
}

/** Choose what "Clear browsing data" removes. */
@Composable
fun ClearBrowsingDataDialog(onClear: (cookies: Boolean, cache: Boolean, history: Boolean, passwords: Boolean) -> Unit, onDismiss: () -> Unit) {
    var cookies by remember { mutableStateOf(true) }
    var cache by remember { mutableStateOf(true) }
    var hist by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf(false) }
    @Composable
    fun line(text: String, v: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = v, onCheckedChange = set)
            Spacer(Modifier.width(4.dp))
            Text(text)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Clear browsing data", "مسح بيانات التصفح")) },
        text = {
            Column {
                line(tr("Cookies and logins", "ملفات الارتباط وتسجيلات الدخول"), cookies) { cookies = it }
                line(tr("Cached pages and images", "الصفحات والصور المخزنة مؤقتًا"), cache) { cache = it }
                line(tr("History", "السجل"), hist) { hist = it }
                line(tr("Saved passwords", "كلمات المرور المحفوظة"), pw) { pw = it }
            }
        },
        confirmButton = { TextButton(onClick = { onClear(cookies, cache, hist, pw); onDismiss() }) { Text(tr("Clear", "مسح"), color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}
