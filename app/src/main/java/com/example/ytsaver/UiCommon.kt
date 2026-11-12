package com.example.ytsaver

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

const val CONTACT_EMAIL = "bakalico100@gmail.com"

val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5A5F),
    onPrimary = Color.White,
    background = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1E),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF26262B),
)

/** Light / dark switch. Read while drawing, so every screen redraws when it changes. */
object ThemeState {
    var light by mutableStateOf(false)
}

val LightColors = lightColorScheme(
    primary = Color(0xFFD7343A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Color(0xFF6B4F50),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD9),
    onSecondaryContainer = Color(0xFF2C1516),
    background = Color(0xFFECEDF1),
    onBackground = Color(0xFF16161A),
    surface = Color.White,
    onSurface = Color(0xFF16161A),
    surfaceVariant = Color(0xFFDDDFE5),
    onSurfaceVariant = Color(0xFF3E4046),
    outline = Color(0xFF6A6C73),
    outlineVariant = Color(0xFFABAEB5),
    error = Color(0xFFB3261E),
)

// Text / status colours: readable on the dark and on the light theme
val Muted: Color get() = if (ThemeState.light) Color(0xFF55585F) else Color(0xFF9A9AA2)
val Green: Color get() = if (ThemeState.light) Color(0xFF1B7F45) else Color(0xFF5EDC8A)
val Red: Color get() = if (ThemeState.light) Color(0xFFC62828) else Color(0xFFFF6B6B)
val Amber: Color get() = if (ThemeState.light) Color(0xFF8A5600) else Color(0xFFFFC857)
val AmberCard: Color get() = if (ThemeState.light) Color(0xFFFFF1CC) else Color(0xFF2A2415)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

fun mimeOf(item: HistoryItem) = if (item.format == "MP3") "audio/mpeg" else "video/mp4"

/** Share sheet with the actual file (WhatsApp, Telegram, Facebook, etc.). */
fun shareFile(ctx: Context, item: HistoryItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(item))
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(
            Intent.createChooser(send, L.t("Share via", "مشاركة عبر")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(ctx, L.t("No app found to share with.", "لا يوجد تطبيق للمشاركة."))
    }
}

/** Share sheet with the link of the original video. */
fun shareLink(ctx: Context, item: HistoryItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "${prettyName(item.name)}\n${item.sourceUrl}")
    try {
        ctx.startActivity(
            Intent.createChooser(send, L.t("Share link via", "مشاركة الرابط عبر")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(ctx, L.t("No app found to share with.", "لا يوجد تطبيق للمشاركة."))
    }
}

/** True when the entry has a readable file on this phone (downloaded or imported). */
fun entryHasFile(ctx: Context, e: PlEntry): Boolean {
    val local = e.localUri ?: return false
    return try {
        val u = Uri.parse(local)
        if (u.scheme == "file") java.io.File(u.path.orEmpty()).exists()
        else ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { true } ?: false
    } catch (_: Exception) { false }
}

/** True when the entry has a web link (online items and downloads from a link; imported files have none). */
fun entryHasLink(e: PlEntry): Boolean = e.pageUrl.startsWith("http")

/** Shares the file itself ([asFile] = true) or its web link. */
fun shareEntry(ctx: Context, e: PlEntry, asFile: Boolean) {
    val send = if (asFile && entryHasFile(ctx, e)) {
        Intent(Intent.ACTION_SEND).setType(if (e.isAudio) "audio/*" else "video/*")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.localUri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else if (entryHasLink(e)) {
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${e.title}\n${e.pageUrl}")
    } else { toast(ctx, L.t("Nothing to share.", "لا يوجد ما يمكن مشاركته.")); return }
    try {
        ctx.startActivity(Intent.createChooser(send, L.t("Share via", "مشاركة عبر")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(ctx, L.t("No app found to share with.", "لا يوجد تطبيق للمشاركة."))
    }
}

/** Asks "link or file?" only when both exist; otherwise shares the one that is available straight away. Returns true when a question must be shown. */
fun shareEntryOrAsk(ctx: Context, e: PlEntry): Boolean {
    val file = entryHasFile(ctx, e)
    val link = entryHasLink(e)
    if (file && link) return true
    shareEntry(ctx, e, asFile = file)
    return false
}

@androidx.compose.runtime.Composable
fun ShareChoiceDialog(e: PlEntry, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(L.t("Share", "مشاركة")) },
        text = { androidx.compose.material3.Text(L.t("Share only the link, or the file itself?", "مشاركة الرابط فقط أم الملف نفسه؟")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { shareEntry(ctx, e, true); onDismiss() }) { androidx.compose.material3.Text(L.t("The file", "الملف")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { shareEntry(ctx, e, false); onDismiss() }) { androidx.compose.material3.Text(L.t("Only the link", "الرابط فقط")) } },
    )
}

/** Opens the folder holding the file in the phone's file manager. */
fun openLocation(ctx: Context, item: HistoryItem) {
    val sub = item.location.substringAfter('/', "")          // e.g. "MediaSaver"
    val docId = if (sub.isEmpty()) "Download" else "Download/$sub"
    val folder = Uri.parse(
        "content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(docId),
    )
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(folder, "vnd.android.document/directory"),
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
    )
    for (i in attempts) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    toast(ctx, L.t("Saved in ", "تم الحفظ في ") + item.location)
}

/** Opens the original link in the phone's default browser. */
fun openLink(ctx: Context, item: HistoryItem) {
    val url = item.sourceUrl
    if (url.isBlank()) {
        toast(ctx, L.t("No link saved for this download.", "لا يوجد رابط محفوظ لهذا التنزيل."))
        return
    }
    val uri = Uri.parse(url)
    try {
        val browser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        browser.data = uri
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(browser)
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            toast(ctx, L.t("No browser found.", "لم يتم العثور على متصفح."))
        }
    }
}

fun sendEmail(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, "VibeVault")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        toast(ctx, L.t("No email app found. Contact: ", "لا يوجد تطبيق بريد. التواصل: ") + CONTACT_EMAIL)
    }
}

/** Accent colour of the notification icon (the electric violet of the VibeVault emblem). */
val NOTIF_COLOR = 0xFF8B83FF.toInt()

/** The real, coloured app icon as a bitmap (shown as the large icon in download notifications). */
fun appIconBitmap(ctx: Context): Bitmap? = try {
    val d = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher)
    if (d == null) null else {
        val size = 192
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        b
    }
} catch (_: Exception) {
    null
}

/** A small symbol (vector drawable) that takes the colour of the text/button it sits in. */
@Composable
fun Sym(res: Int, size: Dp = 18.dp, tint: Color = LocalContentColor.current) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** The app logo, faint, in the middle of the background of a screen. */
@Composable
fun LogoWatermark() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_logo_watermark),
            contentDescription = null,
            alpha = 0.07f,
            modifier = Modifier.size(300.dp),
        )
    }
}

/** Downloads thumbnails from the web once and keeps the latest ones in memory. */
object ThumbCache {
    private val cache = LruCache<String, Bitmap>(80)

    suspend fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection()
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }
}

/** A thumbnail loaded from [url] (or shown straight from [bitmap] when we already have it). */
@Composable
fun RemoteThumb(url: String?, modifier: Modifier, bitmap: Bitmap? = null) {
    val loaded by produceState<Bitmap?>(bitmap, url, bitmap) {
        if (bitmap == null && url != null) value = ThumbCache.get(url)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        loaded?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Opens the phone's Cast / Smart View screen, to send the screen (and its sound) to a TV. */
fun openCast(ctx: Context) {
    val actions = listOf("android.settings.CAST_SETTINGS", "android.settings.WIFI_DISPLAY_SETTINGS")
    for (a in actions) {
        try {
            ctx.startActivity(android.content.Intent(a).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
        }
    }
    toast(ctx, L.t("Open Cast / Smart View from your phone's quick settings.", "افتح البث / Smart View من الإعدادات السريعة في هاتفك."))
}
