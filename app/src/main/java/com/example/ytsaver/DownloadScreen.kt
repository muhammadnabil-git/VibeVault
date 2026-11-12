package com.example.ytsaver

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DownloadTab(vm: DownloaderViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedByRow.collectAsStateWithLifecycle()
    val working = s.phase == Phase.Working

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(tr("Download audio or video from links or playlists", "نزّل الصوت أو الفيديو من الروابط أو قوائم التشغيل"), color = Muted)

        // ---- pause / cancel, progress and status: at the top, above the links ----
        if (working) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.paused) {
                    Button(
                        onClick = vm::resume,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text(tr("Resume", "استئناف"), fontSize = 16.sp) }
                } else {
                    OutlinedButton(
                        onClick = vm::pause,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text(tr("Pause", "إيقاف مؤقت"), fontSize = 16.sp) }
                }
                OutlinedButton(
                    onClick = vm::cancel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text(tr("Cancel", "إلغاء"), fontSize = 16.sp) }
            }
        }

        if (working && s.currentTitle.isNotBlank()) {
            Text(s.currentTitle, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (working) {
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }

        val msgColor = when (s.phase) {
            Phase.Error -> Red
            Phase.Done -> Green
            else -> MaterialTheme.colorScheme.onSurface
        }
        // after a fully successful run there is nothing to report here: the files are in the History tab
        val allDone = s.phase == Phase.Done && s.badKeys.isEmpty()
        if (s.message.isNotBlank() && !allDone) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.message, color = msgColor, modifier = Modifier.padding(16.dp)) }
        }

        // ---- one block per link: preview, link, format + quality + its own Download button ----
        rows.forEachIndexed { index, row ->
            key(row.id) {
                if (index > 0) {
                    // a line separates every additional download link
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.35f)))
                }
                val links = parseLinks(row.text)
                val single = links.singleOrNull()?.takeIf { !isPlaylistRequest(it, row.whole) }
                val rowKey = single?.let { taskKey(it, row.format, row.quality) }
                val status = when {
                    !row.queued -> null
                    rowKey != null && rowKey in s.finishedKeys -> RowStatus.Finished
                    rowKey != null && rowKey == s.activeKey -> RowStatus.Active
                    else -> RowStatus.Queued
                }
                val canDownload = s.engineReady && !s.updating &&
                    pendingLinks(listOf(row), downloaded).isNotEmpty()
                LinkRowItem(index, row, rows.size, previews[row.id], downloaded[row.id].orEmpty(), status, canDownload, vm)
            }
        }

        // stays available while downloads are running: new links join the queue
        OutlinedButton(
            onClick = vm::addRow,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("+ Add another link", "+ إضافة رابط آخر")) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::updateEngine, enabled = !working && !s.updating) {
                Text(if (s.updating) tr("Updating engine…", "جارٍ تحديث المحرك…") else tr("Update download engine", "تحديث محرك التنزيل"))
            }
        }
    }
}

private enum class RowStatus { Queued, Active, Finished }

/** A small "label ▾" button that opens a dropdown list. */
@Composable
internal fun <T> DropdownPicker(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.height(40.dp),
        ) {
            Text(label, fontSize = 13.sp, maxLines = 1)
            Text(" ▾", fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionLabel(o)) },
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}

/** MP3 / MP4 dropdown label list, shared with the History tab. */
@Composable
internal fun formatOptionLabel(f: Format): String =
    if (f == Format.MP3) tr("MP3 (audio)", "MP3 (صوت)") else tr("MP4 (video)", "MP4 (فيديو)")

@Composable
private fun LinkRowItem(
    index: Int,
    row: LinkRow,
    total: Int,
    preview: PreviewState?,
    savedFiles: List<HistoryItem>,   // this video's files that are already on the phone
    status: RowStatus?,
    canDownload: Boolean,
    vm: DownloaderViewModel,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val locked = row.queued          // already in the download queue: can't be edited
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // this row's own preview: ABOVE the link
        when {
            preview == null -> {}
            preview.loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(tr("Loading preview…", "جارٍ تحميل المعاينة…"), color = Muted)
            }
            preview.preview != null -> PreviewCard(preview.preview)
            preview.playlist != null -> PlaylistCard(preview.playlist)
            preview.failed -> Text(
                tr("Preview unavailable for this link (you can still try to download).", "المعاينة غير متاحة لهذا الرابط (يمكنك محاولة التنزيل)."),
                color = Muted, fontSize = 13.sp,
            )
        }

        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = row.text,
                onValueChange = { vm.setRowText(row.id, it) },
                label = { Text(tr("Link ${index + 1}", "رابط ${index + 1}")) },
                placeholder = { Text(tr("Paste a video or playlist link", "الصق رابط فيديو أو قائمة تشغيل")) },
                minLines = 1,
                maxLines = 3,
                enabled = !locked,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.text.isNotEmpty()) {
                            IconButton(onClick = { vm.setRowText(row.id, "") }, enabled = !locked) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Clear", "مسح"), tint = Muted)
                            }
                        }
                        TextButton(
                            onClick = { clipboard.getText()?.text?.let { vm.setRowText(row.id, it) } },
                            enabled = !locked,
                        ) { Text(tr("Paste", "لصق")) }
                    }
                },
            )
            if (total > 1) {
                IconButton(onClick = { vm.removeRow(row.id) }, enabled = !locked) {
                    Icon(Icons.Filled.Delete, contentDescription = tr("Remove link", "حذف الرابط"), tint = Muted)
                }
            }
        }

        // this link's own format + quality, with its own Download button beside them
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DropdownPicker(
                label = row.format.name,
                options = Format.values().toList(),
                optionLabel = { f -> formatOptionLabel(f) },
                onSelect = { vm.setRowFormat(row.id, it) },
                enabled = !locked,
            )
            fun saved(q: Int) = savedFiles.any { it.format == row.format.name && it.quality == q }
            DropdownPicker(
                label = qualityLabel(row.format, row.quality) + if (saved(row.quality)) " ✓" else "",
                options = qualityOptions(row.format),
                optionLabel = { q -> qualityLabel(row.format, q) + if (saved(q)) " ✓" else "" },
                onSelect = { vm.setRowQuality(row.id, it) },
                enabled = !locked,
            )
            if (status != null) {
                // already in the queue: show where it is instead of the button
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    when (status) {
                        RowStatus.Queued -> Text(tr("In queue", "في قائمة الانتظار"), color = Amber, fontSize = 13.sp, maxLines = 1)
                        RowStatus.Active -> Text(tr("Downloading…", "جارٍ التنزيل…"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1)
                        RowStatus.Finished -> Text(tr("Finished ✓", "تم ✓"), color = Green, fontSize = 13.sp, maxLines = 1)
                    }
                }
            } else {
                Button(
                    onClick = { vm.downloadRow(row.id) },
                    enabled = canDownload,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Sym(R.drawable.ic_download, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Download", "تنزيل"), fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        // this exact format + quality is already on the phone
        val existing = savedFiles.firstOrNull { it.format == row.format.name && it.quality == row.quality }
        if (existing != null && !locked) {
            val next = qualityOptions(row.format).firstOrNull { q -> savedFiles.none { it.format == row.format.name && it.quality == q } }
            val cur = qualityLabel(row.format, row.quality)
            val hint = if (next != null) {
                val n = qualityLabel(row.format, next)
                tr(
                    "Already downloaded as ${row.format.name} at $cur. Pick another format or quality (for example $n) to download it again.",
                    "تم التنزيل مسبقًا بصيغة ${row.format.name} وجودة $cur. اختر صيغة أو جودة أخرى (مثل $n) لتنزيله مجددًا.",
                )
            } else {
                tr(
                    "Already downloaded as ${row.format.name} at $cur. Every quality option in this format is already downloaded. Try the other format.",
                    "تم التنزيل مسبقًا بصيغة ${row.format.name} وجودة $cur. جميع خيارات الجودة في هذه الصيغة منزّلة بالفعل. جرّب الصيغة الأخرى.",
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = AmberCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(hint, color = Amber, fontSize = 14.sp)
                    TextButton(
                        onClick = {
                            if (fileExists(ctx, existing)) vm.playNow(listOf(existing))
                            else toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
                        },
                    ) {
                        Sym(R.drawable.ic_play, 18.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Play", "تشغيل"))
                    }
                }
            }
        }

        // A video link that also carries a playlist: let the user choose
        if (parseLinks(row.text).any { playlistKind(it) == 2 }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Download the whole playlist", "تنزيل قائمة التشغيل بالكامل"))
                    Text(tr("This link is a video inside a playlist", "هذا الرابط لفيديو داخل قائمة تشغيل"), color = Muted, fontSize = 12.sp)
                }
                Switch(checked = row.whole, onCheckedChange = { vm.setRowWhole(row.id, it) }, enabled = !locked)
            }
        }
    }
}

@Composable
private fun PreviewCard(p: Preview) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumbMod = Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp))
            if (p.thumb != null) {
                Image(
                    bitmap = p.thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = thumbMod,
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = thumbMod) {}
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 2, fontSize = 14.sp)
                if (p.uploader.isNotBlank()) Text(p.uploader, color = Muted, fontSize = 12.sp, maxLines = 1)
                val d = formatDuration(p.durationSec)
                if (d.isNotEmpty()) Text(tr("Duration ", "المدة ") + d, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlaylistCard(pl: PlaylistInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("PLAYLIST", "قائمة تشغيل"), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(pl.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val n = pl.entries.size
            val capped = if (n >= PlaylistLoader.MAX_ITEMS) tr(" (first ${PlaylistLoader.MAX_ITEMS})", " (أول ${PlaylistLoader.MAX_ITEMS})") else ""
            Text(
                tr("$n video${if (n == 1) "" else "s"}$capped • downloaded one by one", "$n فيديو$capped • تُنزَّل واحدًا تلو الآخر"),
                color = Muted, fontSize = 13.sp,
            )
            Text(tr("Items you already have are skipped.", "العناصر التي لديك مسبقًا يتم تخطيها."), color = Muted, fontSize = 12.sp)
        }
    }
}
