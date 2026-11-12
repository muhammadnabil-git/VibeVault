package com.example.ytsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore of what the user created: playlists, favorites, Bluetooth rules and a few settings.
 * The file is plain JSON that the user keeps. It never contains passwords, account data or the vault, and
 * restoring MERGES with what is on the phone (newer entries win, deletions are respected), so nothing newer is overwritten.
 */
object BackupRestore {
    private const val FORMAT = 1
    private val BOOL_KEYS = mapOf("eq_on" to false, "live_bg" to false, "resume_playback" to true) +
        HomePrefs.SECTIONS.associate { "home_$it" to true }
    private val STR_KEYS = listOf("theme", "eq_levels", "eq_preset")

    fun export(ctx: Context): String {
        val settings = JSONObject()
        BOOL_KEYS.forEach { (k, d) -> settings.put(k, Prefs.bool(ctx, k, d)) }
        STR_KEYS.forEach { k -> Prefs.str(ctx, k)?.let { settings.put(k, it) } }
        return JSONObject()
            .put("app", "VibeVault")
            .put("format", FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("library", Library.snapshot())
            .put("settings", settings)
            .toString(2)
    }

    class Result(val ok: Boolean, val playlists: Int, val favorites: Int, val message: String)

    /** Validates first; nothing is changed when the file is not a VibeVault backup. */
    fun import(ctx: Context, text: String): Result {
        val o = try { JSONObject(text) } catch (e: Exception) {
            return Result(false, 0, 0, L.t("This is not a VibeVault backup file.", "هذا ليس ملف نسخة احتياطية من VibeVault."))
        }
        if (o.optString("app") != "VibeVault" || o.optInt("format", 0) !in 1..FORMAT) {
            return Result(false, 0, 0, L.t("This backup was made by a different or newer version.", "هذه النسخة أُنشئت بإصدار مختلف أو أحدث."))
        }
        val lib = o.optJSONObject("library") ?: return Result(false, 0, 0, L.t("The backup has no library.", "النسخة لا تحتوي على مكتبة."))
        val before = Library.snapshot()
        Library.mergeRemote(lib)
        val after = Library.snapshot()
        val s = o.optJSONObject("settings")
        if (s != null) {
            BOOL_KEYS.keys.forEach { k -> if (s.has(k)) Prefs.putBool(ctx, k, s.optBoolean(k)) }
            STR_KEYS.forEach { k -> if (s.has(k)) Prefs.putStr(ctx, k, s.optString(k)) }
        }
        val dp = (after.optJSONArray("playlists")?.length() ?: 0) - (before.optJSONArray("playlists")?.length() ?: 0)
        val df = (after.optJSONArray("favorites")?.length() ?: 0) - (before.optJSONArray("favorites")?.length() ?: 0)
        return Result(true, dp.coerceAtLeast(0), df.coerceAtLeast(0), "")
    }

    fun suggestedName(): String = "VibeVault-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
}

/** Settings card: save a backup file / restore from one. */
@Composable
fun BackupCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(BackupRestore.export(ctx).toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) { false }
            }
            toast(ctx, if (ok) L.t("Backup saved.", "تم حفظ النسخة الاحتياطية.") else L.t("Couldn't save the backup file.", "تعذّر حفظ ملف النسخة الاحتياطية."))
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                        // a backup is a few hundred KB at most: refuse anything absurd instead of reading it all
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        var tooBig = false
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > 5_000_000) { tooBig = true; break }
                            out.write(buf, 0, n)
                        }
                        if (tooBig) null else String(out.toByteArray(), Charsets.UTF_8)
                    }
                } catch (e: Exception) { null }
            }
            if (text == null) {
                toast(ctx, L.t("Couldn't read that file.", "تعذّرت قراءة هذا الملف."))
                return@launch
            }
            val r = BackupRestore.import(ctx, text)
            toast(
                ctx,
                if (r.ok) L.t("Restored: ${r.playlists} new playlist(s), ${r.favorites} new favorite(s). Restart the app to apply settings.",
                    "تمت الاستعادة: ${r.playlists} قائمة جديدة و${r.favorites} مفضلة جديدة. أعد تشغيل التطبيق لتطبيق الإعدادات.")
                else r.message,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
        Text(
            tr(
                "Save your playlists, favorites and Bluetooth rules to a file you keep. Restoring merges the file with what is on this phone; it never deletes newer changes. Passwords, your account and the vault are not included.",
                "احفظ قوائم التشغيل والمفضلة وقواعد البلوتوث في ملف تحتفظ به. تدمج الاستعادة الملف مع ما على هذا الهاتف ولا تحذف التغييرات الأحدث. لا تشمل كلمات المرور ولا الحساب ولا الخزنة.",
            ),
            color = Muted, fontSize = VvText.caption,
        )
        VvSecondaryButton(tr("Save a backup", "حفظ نسخة احتياطية"), { save.launch(BackupRestore.suggestedName()) }, Modifier.fillMaxWidth())
        VvSecondaryButton(tr("Restore from a backup", "استعادة من نسخة احتياطية"), { load.launch(arrayOf("application/json", "text/plain", "*/*")) }, Modifier.fillMaxWidth())
    }
}

// =====================================================================================================
// Playlist sharing + bug reporting
// =====================================================================================================

/** A readable text version of a playlist: name, and for every song its title and web link (never a path on this phone). */
fun playlistShareText(p: Playlist): String = buildString {
    append("VibeVault — ").append(p.name).append('\n')
    p.entries.forEachIndexed { i, e ->
        append(i + 1).append(". ").append(e.title)
        if (e.uploader.isNotBlank()) append(" — ").append(e.uploader)
        append('\n')
        if (e.pageUrl.isNotBlank()) append("   ").append(e.pageUrl).append('\n')
    }
}

fun sharePlaylist(ctx: Context, p: Playlist) {
    if (p.entries.isEmpty()) {
        toast(ctx, L.t("This playlist is empty.", "قائمة التشغيل فارغة."))
        return
    }
    try {
        ctx.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, p.name)
                    .putExtra(Intent.EXTRA_TEXT, playlistShareText(p)),
                L.t("Share playlist", "مشاركة القائمة"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(ctx, L.t("No app to share with.", "لا يوجد تطبيق للمشاركة."))
    }
}

/** An email to the developer that already contains the technical details that help to find a bug (no personal data). */
fun sendBugReport(ctx: Context) {
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    val body = "\n\n— Please describe what happened above this line —\n" +
        "VibeVault $version\n" +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n"
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, "VibeVault $version — bug report")
                .putExtra(Intent.EXTRA_TEXT, body)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(ctx, L.t("No email app found. Contact: ", "لا يوجد تطبيق بريد. التواصل: ") + CONTACT_EMAIL)
    }
}
