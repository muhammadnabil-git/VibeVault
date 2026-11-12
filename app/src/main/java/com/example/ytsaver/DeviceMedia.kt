package com.example.ytsaver

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Where imported files are marked in the history ("Saved to": Device / Music/). */
const val LOCATION_DEVICE = "Device"

val HistoryItem.isDeviceFile: Boolean get() = location.startsWith(LOCATION_DEVICE)

/**
 * Audio / video files that are already on the phone (internal storage), found through MediaStore.
 * They become normal History items (Audio / Video tabs) and can be added to playlists. The files are never copied,
 * moved or deleted: removing them from the app only removes them from the lists.
 */
object DeviceMedia {
    // only formats the app's player (ExoPlayer) can play
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mka")
    private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "ts")

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(ctx: Context, audio: Boolean): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) {
            if (audio) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_MEDIA_VIDEO
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    /** Blocking: run on a background thread. Newest files first. */
    fun scan(ctx: Context, audio: Boolean): List<HistoryItem> {
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val exts = if (audio) AUDIO_EXT else VIDEO_EXT
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        // ringtones, alarms and notification sounds are not music
        val selection = if (audio) {
            "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0"
        } else null
        val out = ArrayList<HistoryItem>()
        try {
            ctx.contentResolver.query(base, proj, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in exts) continue
                    val size = c.getLong(iSize)
                    if (size <= 0L) continue
                    val uri = ContentUris.withAppendedId(base, c.getLong(iId)).toString()
                    val path = (c.getString(iPath) ?: "").trim('/')
                    out += HistoryItem(
                        name = name,
                        format = if (audio) "MP3" else "MP4",     // the app's two kinds: Audio and Video
                        quality = 0,
                        sizeBytes = size,
                        timeMillis = c.getLong(iAdded) * 1000L,
                        location = if (path.isEmpty()) LOCATION_DEVICE else "$LOCATION_DEVICE/$path/",
                        uri = uri,
                        sourceUrl = "",
                    )
                }
            }
        } catch (_: Exception) {
            // permission revoked while scanning, or an odd vendor provider: show what we have
        }
        return out
    }
}

/**
 * "Import from device": pick audio / video files of the phone. [exclude] = uris that are already there (hidden).
 * [onImport] gets the chosen files (as History items).
 */
@Composable
fun DeviceImportDialog(
    startAudio: Boolean,
    exclude: Set<String>,
    title: String = tr("Import from device", "استيراد من الجهاز"),
    onDismiss: () -> Unit,
    onImport: (List<HistoryItem>) -> Unit,
) {
    val ctx = LocalContext.current
    var audio by rememberSaveable { mutableStateOf(startAudio) }
    var permTick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val granted = remember(audio, permTick) { DeviceMedia.hasPermission(ctx, audio) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        denied = !DeviceMedia.hasPermission(ctx, audio)
    }

    var files by remember { mutableStateOf<List<HistoryItem>?>(null) }
    LaunchedEffect(audio, granted) {
        files = null
        if (granted) files = withContext(Dispatchers.IO) { DeviceMedia.scan(ctx, audio) }
    }
    var picked by remember { mutableStateOf(mapOf<String, HistoryItem>()) }
    var query by remember { mutableStateOf("") }
    val visible = remember(files, query, exclude) {
        (files ?: emptyList()).filter { it.uri !in exclude && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = audio, onClick = { audio = true }, label = { Text(tr("Audio", "صوت")) })
                    FilterChip(selected = !audio, onClick = { audio = false }, label = { Text(tr("Video", "فيديو")) })
                }
                if (!granted) {
                    Text(
                        tr("To list the audio / video files on your phone, VibeVault needs permission to read them. The files are not copied or changed.",
                            "لعرض ملفات الصوت والفيديو على هاتفك يحتاج التطبيق إلى إذن قراءتها. لا يتم نسخ الملفات أو تغييرها."),
                        color = Muted, fontSize = 13.sp,
                    )
                    Button(onClick = { launcher.launch(DeviceMedia.permissions()) }) { Text(tr("Allow access", "السماح بالوصول")) }
                    if (denied) {
                        Text(
                            tr("Access was refused. You can allow it in the app's Android settings.", "تم رفض الإذن. يمكنك السماح به من إعدادات أندرويد للتطبيق."),
                            color = Amber, fontSize = 12.sp,
                        )
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text(tr("Open app settings", "فتح إعدادات التطبيق")) }
                    }
                } else if (files == null) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(tr("Search by name", "بحث بالاسم")) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("${visible.size} file(s) • ${picked.size} selected", "${visible.size} ملف • تم تحديد ${picked.size}"),
                            color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { picked = picked + visible.associateBy { it.uri } }, enabled = visible.isNotEmpty()) {
                            Text(tr("Select all", "تحديد الكل"), fontSize = 12.sp)
                        }
                        TextButton(onClick = { picked = emptyMap() }, enabled = picked.isNotEmpty()) {
                            Text(tr("Clear", "مسح"), fontSize = 12.sp)
                        }
                    }
                    if (visible.isEmpty()) {
                        Text(
                            if (audio) tr("No new audio files found.", "لا توجد ملفات صوتية جديدة.") else tr("No new video files found.", "لا توجد ملفات فيديو جديدة."),
                            color = Muted,
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(visible, key = { it.uri }) { h ->
                                val on = h.uri in picked
                                val toggle = { picked = if (on) picked - h.uri else picked + (h.uri to h) }
                                Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = on, onCheckedChange = { toggle() })
                                    Column(Modifier.weight(1f)) {
                                        Text(prettyName(h.name), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            h.name.substringAfterLast('.', "").uppercase() + " • " + formatSize(h.sizeBytes) +
                                                (if (h.location.length > LOCATION_DEVICE.length) " • " + h.location.removePrefix("$LOCATION_DEVICE/").trimEnd('/') else ""),
                                            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onImport(picked.values.toList()); onDismiss() }) {
                Text(tr("Import (${picked.size})", "استيراد (${picked.size})"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}
