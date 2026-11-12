package com.example.ytsaver

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A folder of the Media tab. [kind] 0 = Audio, 1 = Video: a folder only holds files of its own kind. */
data class MediaFolder(val id: String, val name: String, val kind: Int, val uris: List<String>)

/**
 * The folders of the Media tab (kept on this phone). A file is in at most one folder; deleting a folder
 * only puts its files back in the main list.
 */
object FolderStore {
    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()
    private var file: File? = null

    @Synchronized
    fun load(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "folders.json")
        file = f
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            _folders.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val u = o.optJSONArray("uris") ?: JSONArray()
                MediaFolder(o.optString("id"), o.optString("name"), o.optInt("kind"), (0 until u.length()).map { u.getString(it) })
            }.filter { it.id.isNotBlank() }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            _folders.value.forEach { fo ->
                arr.put(JSONObject().put("id", fo.id).put("name", fo.name).put("kind", fo.kind).put("uris", JSONArray(fo.uris)))
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun create(name: String, kind: Int): MediaFolder {
        val fo = MediaFolder(UUID.randomUUID().toString(), name.trim().ifBlank { "Folder" }, kind, emptyList())
        _folders.value = _folders.value + fo
        save()
        return fo
    }

    @Synchronized
    fun rename(id: String, name: String) {
        _folders.value = _folders.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        save()
    }

    /** The files go back to the main list; nothing is deleted from the phone. */
    @Synchronized
    fun delete(id: String) {
        _folders.value = _folders.value.filter { it.id != id }
        save()
    }

    /** Puts [uris] into folder [toId] (taking them out of any other folder). [toId] = null: back to the main list. */
    @Synchronized
    fun move(uris: Set<String>, toId: String?) {
        if (uris.isEmpty()) return
        _folders.value = _folders.value.map { fo ->
            val kept = fo.uris.filter { it !in uris }
            if (fo.id == toId) fo.copy(uris = kept + uris.filter { it !in kept }) else fo.copy(uris = kept)
        }
        save()
    }
}

/** The website a file came from, for the badge / filter / grouping ("Device" for imported files). */
fun siteOfItem(item: HistoryItem): String =
    if (item.isDeviceFile) L.t("Device", "الجهاز")
    else siteLabelOf(item.sourceUrl).ifBlank { L.t("Other", "أخرى") }

/** A name box: new folder / rename folder. */
@Composable
fun FolderNameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text(tr("Folder name", "اسم المجلد")) },
            )
        },
        confirmButton = { TextButton(onClick = { onOk(name) }, enabled = name.isNotBlank()) { Text(tr("OK", "موافق")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/**
 * Pick the folder for [count] file(s) of [kind] (0 = audio, 1 = video): any existing folder of that kind
 * (except [current]), a new one, or - when the files are inside a folder - back to the main list.
 */
@Composable
fun MoveToFolderDialog(
    count: Int,
    kind: Int,
    folders: List<MediaFolder>,
    current: String?,
    onPick: (String?) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        FolderNameDialog(tr("New folder", "مجلد جديد"), "", onDismiss = { creating = false }) { n -> creating = false; onNew(n) }
        return
    }
    val mine = folders.filter { it.kind == kind && it.id != current }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Move $count file(s) to", "نقل $count ملف إلى")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(R.drawable.ic_folder, 22.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("+ New folder", "+ مجلد جديد"), fontWeight = FontWeight.SemiBold)
                }
                if (current != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(null) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_arrow_back, 22.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Main list (out of the folder)", "القائمة الرئيسية (خارج المجلد)"))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                if (mine.isEmpty()) {
                    Text(
                        if (kind == 0) tr("No other audio folders yet.", "لا توجد مجلدات صوت أخرى بعد.")
                        else tr("No other video folders yet.", "لا توجد مجلدات فيديو أخرى بعد."),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                mine.forEach { fo ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(fo.id) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_folder, 22.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fo.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tr("${fo.uris.size} file(s)", "${fo.uris.size} ملف"), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** A folder row in the Media list: tap to open; the menu renames or deletes the folder (its files are kept). */
@Composable
fun FolderCard(folder: MediaFolder, count: Int, bytes: Long, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Sym(R.drawable.ic_folder, 30.dp, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("$count file(s) • ${formatSize(bytes)}", "$count ملف • ${formatSize(bytes)}"), color = Muted, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_more, 22.dp, tint = Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Rename", "إعادة تسمية")) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(tr("Delete folder (keeps the files)", "حذف المجلد (مع بقاء الملفات)"), color = Red) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
