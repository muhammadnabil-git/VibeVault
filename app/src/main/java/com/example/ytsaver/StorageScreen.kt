package com.example.ytsaver

import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private class StorageCat(val id: Int, val title: String, val color: Color, val note: String?, val items: List<HistoryItem>) {
    val bytes: Long = items.sumOf { it.sizeBytes }
}

/** Gigabytes with one decimal, smaller sizes in MB / KB. */
private fun big(b: Long): String =
    if (b >= 1_000_000_000L) String.format(java.util.Locale.US, "%.2f GB", b / 1_000_000_000.0) else formatSize(b)

/**
 * Settings -> Storage overview: how much space the downloads and the imported files take, per kind, and
 * (tap a row) which files are the biggest. Read-only: nothing is deleted from here.
 */
@Composable
fun StorageScreen(vm: DownloaderViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val all by vm.history.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    val cats = remember(all) {
        listOf(
            StorageCat(0, L.t("Downloaded audio", "صوت منزَّل"), Color(0xFF4F8EF7), null, all.filter { !it.isDeviceFile && it.format == "MP3" }),
            StorageCat(1, L.t("Downloaded video", "فيديو منزَّل"), Color(0xFF7E57C2), null, all.filter { !it.isDeviceFile && it.format == "MP4" }),
            StorageCat(2, L.t("Imported audio", "صوت مستورد"), Color(0xFF26A69A),
                L.t("Imported files are not copied: they stay where they are on your phone.", "الملفات المستوردة لا تُنسخ: تبقى في مكانها على هاتفك."),
                all.filter { it.isDeviceFile && it.format == "MP3" }),
            StorageCat(3, L.t("Imported video", "فيديو مستورد"), Color(0xFFFFA726),
                L.t("Imported files are not copied: they stay where they are on your phone.", "الملفات المستوردة لا تُنسخ: تبقى في مكانها على هاتفك."),
                all.filter { it.isDeviceFile && it.format == "MP4" }),
        )
    }
    val libTotal = cats.sumOf { it.bytes }
    val downloadsTotal = cats[0].bytes + cats[1].bytes

    // the phone's storage, and what the app keeps for itself (settings, download engine, cache): measured off the main thread
    val phone = remember {
        runCatching {
            val st = StatFs(Environment.getDataDirectory().path)
            st.totalBytes to st.availableBytes
        }.getOrNull()
    }
    val appData by produceState<Long?>(null) {
        value = withContext(Dispatchers.IO) {
            val dirs = listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir, ctx.codeCacheDir)
            dirs.sumOf { d -> runCatching { d.walkTopDown().filter(File::isFile).sumOf { f -> f.length() } }.getOrDefault(0L) }
        }
    }
    var openId by remember { mutableIntStateOf(-1) }
    var showAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع")) }
            Text(tr("Storage overview", "نظرة على التخزين"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- the phone ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Your phone", "هاتفك"), fontWeight = FontWeight.SemiBold)
                    if (phone != null) {
                        val (total, free) = phone
                        val used = (total - free).coerceAtLeast(0L)
                        Bar(listOf(downloadsTotal to MaterialTheme.colorScheme.primary, (used - downloadsTotal).coerceAtLeast(0L) to Muted.copy(alpha = 0.55f), free to Muted.copy(alpha = 0.15f)))
                        Text(
                            tr("${big(used)} used of ${big(total)} • ${big(free)} free", "${big(used)} مستخدم من ${big(total)} • ${big(free)} متاح"),
                            color = Muted, fontSize = 13.sp,
                        )
                        Text(
                            tr("VibeVault downloads: ${big(downloadsTotal)}", "تنزيلات VibeVault: ${big(downloadsTotal)}"),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(tr("The phone's storage couldn't be read.", "تعذّرت قراءة تخزين الهاتف."), color = Muted)
                    }
                }
            }

            // ---- the library ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Downloads and imports", "التنزيلات والمستورد"), fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("${all.size} file(s) • ${big(libTotal)} in total. Tap a row to see its biggest files.", "${all.size} ملف • ${big(libTotal)} إجمالًا. اضغط صفًا لرؤية أكبر ملفاته."),
                        color = Muted, fontSize = 13.sp,
                    )
                    Bar(cats.map { it.bytes to it.color })
                    cats.forEach { c ->
                        val isOpen = openId == c.id
                        Column(
                            Modifier.fillMaxWidth().clip4().clickable { openId = if (isOpen) -1 else c.id; showAll = false }.padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(c.color, RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        tr("${c.items.size} file(s) • ${if (libTotal > 0) (c.bytes * 100 / libTotal) else 0}%", "${c.items.size} ملف • ${if (libTotal > 0) (c.bytes * 100 / libTotal) else 0}%"),
                                        color = Muted, fontSize = 12.sp,
                                    )
                                }
                                Text(big(c.bytes), fontWeight = FontWeight.SemiBold)
                                Text(if (isOpen) "  ▴" else "  ▾", color = Muted)
                            }
                            if (isOpen) {
                                c.note?.let { Text(it, color = Muted, fontSize = 12.sp) }
                                val sorted = c.items.sortedByDescending { it.sizeBytes }
                                val shown = if (showAll) sorted else sorted.take(10)
                                val maxB = (sorted.firstOrNull()?.sizeBytes ?: 1L).coerceAtLeast(1L)
                                if (sorted.isEmpty()) Text(tr("Nothing here yet.", "لا يوجد شيء هنا بعد."), color = Muted, fontSize = 13.sp)
                                shown.forEach { f ->
                                    Column(
                                        Modifier.fillMaxWidth().clip4().clickable {
                                            if (fileExists(ctx, f)) vm.playNow(listOf(f))
                                            else toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
                                        }.padding(start = 22.dp, top = 4.dp, bottom = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Row {
                                            Text(prettyName(f.name), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(formatSize(f.sizeBytes), color = Muted, fontSize = 12.sp)
                                        }
                                        Box(Modifier.fillMaxWidth().height(4.dp).background(Muted.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
                                            Box(Modifier.fillMaxWidth((f.sizeBytes.toFloat() / maxB).coerceIn(0.02f, 1f)).height(4.dp).background(c.color, RoundedCornerShape(2.dp)))
                                        }
                                    }
                                }
                                if (sorted.size > 10) {
                                    TextButton(onClick = { showAll = !showAll }) {
                                        Text(if (showAll) tr("Show fewer", "عرض أقل") else tr("Show all ${sorted.size}", "عرض الكل (${sorted.size})"))
                                    }
                                }
                                Text(tr("Tap a file to play it.", "اضغط ملفًا لتشغيله."), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 22.dp))
                            }
                        }
                    }
                }
            }

            // ---- duplicates ----
            val dupGroups = remember(all) { DuplicateFinder.groups(all) }
            val dupRemovable = remember(all) { DuplicateFinder.removable(all) }
            var confirmDup by remember { mutableStateOf(false) }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Duplicates", "الملفات المكررة"), fontWeight = FontWeight.SemiBold)
                    if (dupGroups.isEmpty()) {
                        Text(tr("No duplicate files found.", "لم يتم العثور على ملفات مكررة."), color = Muted, fontSize = 13.sp)
                    } else {
                        Text(
                            tr(
                                "${dupGroups.size} song(s)/video(s) are saved more than once. Removing the older copies frees ${big(dupRemovable.sumOf { it.sizeBytes })}.",
                                "${dupGroups.size} عنصر محفوظ أكثر من مرة. حذف النسخ الأقدم يوفّر ${big(dupRemovable.sumOf { it.sizeBytes })}.",
                            ),
                            color = Muted, fontSize = 13.sp,
                        )
                        dupGroups.take(5).forEach { g ->
                            Text("• " + prettyName(g.first().name) + "  ×" + g.size, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (dupGroups.size > 5) Text(tr("…and ${dupGroups.size - 5} more", "…و${dupGroups.size - 5} أخرى"), color = Muted, fontSize = 12.sp)
                        Button(onClick = { confirmDup = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Remove older copies (keep the newest)", "حذف النسخ الأقدم (إبقاء الأحدث)"))
                        }
                    }
                }
            }
            if (confirmDup) {
                AlertDialog(
                    onDismissRequest = { confirmDup = false },
                    title = { Text(tr("Delete ${dupRemovable.size} file(s)?", "حذف ${dupRemovable.size} ملف؟")) },
                    text = { Text(tr("The newest copy of each stays. The older files are deleted from your phone and cannot be restored.", "تبقى أحدث نسخة من كل عنصر. تُحذف الملفات الأقدم من هاتفك ولا يمكن استعادتها.")) },
                    confirmButton = {
                        TextButton(onClick = { vm.removeHistoryItems(dupRemovable, deleteFiles = true); confirmDup = false }) {
                            Text(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmDup = false }) { Text(tr("Cancel", "إلغاء")) } },
                )
            }

            // ---- the app itself ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("App data", "بيانات التطبيق"), fontWeight = FontWeight.SemiBold)
                    Text(
                        appData?.let { big(it) } ?: tr("Measuring…", "جارٍ القياس…"),
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        tr("Settings, playlists, the download engine and unfinished downloads kept by the app.", "الإعدادات وقوائم التشغيل ومحرك التنزيل والتنزيلات غير المكتملة التي يحتفظ بها التطبيق."),
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun Modifier.clip4() = this.then(Modifier.clip(RoundedCornerShape(8.dp)))

/** A horizontal stacked bar; the segments are proportional to their byte counts. */
@Composable
private fun Bar(parts: List<Pair<Long, Color>>) {
    val total = parts.sumOf { it.first }
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Muted.copy(alpha = 0.12f))) {
        if (total > 0) parts.filter { it.first > 0 }.forEach { (b, c) ->
            Box(Modifier.weight(b.toFloat()).fillMaxHeight().background(c))
        }
    }
}
