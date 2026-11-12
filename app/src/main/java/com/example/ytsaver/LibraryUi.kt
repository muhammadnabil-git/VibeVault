package com.example.ytsaver

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

internal val HeartColor = Color(0xFFE53950)

@Composable
internal fun kindLabel(kind: String): String = when (kind) {
    KIND_LOCAL -> tr("Local (downloads)", "محلية (التنزيلات)")
    KIND_ONLINE -> tr("Online (links)", "أونلاين (روابط)")
    else -> tr("Mixed", "مختلطة")
}

private fun accepts(kind: String, e: PlEntry): Boolean = when (kind) {
    KIND_LOCAL -> e.localUri != null
    KIND_ONLINE -> e.pageUrl.isNotBlank()
    else -> e.localUri != null || e.pageUrl.isNotBlank()
}

/** The heart on every media row: adds / removes it from Favorites. */
@Composable
fun HeartButton(entry: PlEntry, size: Dp = 44.dp) {
    val favs by Library.favorites.collectAsStateWithLifecycle()
    val on = favs.any { it.entry.syncKey == entry.syncKey }
    IconButton(onClick = { Library.toggleFavorite(entry) }, modifier = Modifier.size(size)) {
        Sym(
            if (on) R.drawable.ic_heart else R.drawable.ic_heart_outline, 22.dp,
            tint = if (on) HeartColor else Muted,
        )
    }
}

/**
 * "Add to playlists" for a selection of [count] items: "Add to Current Playlist" (the Now playing list) comes first,
 * followed by every saved playlist. Choosing one adds all the selected items to it.
 */
@Composable
fun BulkAddToPlaylistDialog(count: Int, onCurrent: () -> Unit, onPlaylist: (Playlist) -> Unit, onDismiss: () -> Unit) {
    val playlists by Library.playlists.collectAsStateWithLifecycle()
    val queue by PlayerHolder.entries.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add $count item(s) to", "إضافة $count عنصر إلى")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 1) the current playlist = what is playing now
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onCurrent).padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sym(R.drawable.ic_queue_add, 22.dp, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("Add to Current Playlist", "إضافة إلى قائمة التشغيل الحالية"), fontWeight = FontWeight.SemiBold)
                        Text(tr("Now playing • ${queue.size}", "التشغيل الحالي • ${queue.size}"), color = Muted, fontSize = 12.sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                // 2) all the saved playlists
                if (playlists.isEmpty()) {
                    Text(
                        tr("No saved playlists yet. Create one from the playlists button at the top.", "لا توجد قوائم محفوظة بعد. أنشئ واحدة من زر القوائم في الأعلى."),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                playlists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPlaylist(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_queue_music, 22.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${kindLabel(p.kind)} • ${p.entries.size}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** Pick a playlist for these entries, or create a new one. */
@Composable
fun AddToPlaylistDialog(entries: List<PlEntry>, onNowPlaying: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val playlists by Library.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newKind by remember {
        mutableStateOf(
            when {
                entries.all { it.localUri != null } -> KIND_LOCAL
                entries.all { it.localUri == null } -> KIND_ONLINE
                else -> KIND_MIXED
            },
        )
    }

    fun addTo(p: Playlist) {
        val n = Library.addToPlaylist(p.id, entries)
        toast(
            ctx,
            if (n > 0) L.t("Added to \"${p.name}\"", "تمت الإضافة إلى \"${p.name}\"")
            else L.t("Nothing added: already there, or this playlist doesn't take this kind of item.", "لم تتم إضافة شيء: موجود بالفعل أو أن القائمة لا تقبل هذا النوع."),
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add to playlist", "إضافة إلى قائمة تشغيل")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playlists.isEmpty() && !creating) {
                    Text(tr("You have no playlists yet. Create one:", "ليس لديك قوائم تشغيل بعد. أنشئ واحدة:"), color = Muted)
                }
                if (onNowPlaying != null) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onNowPlaying(); onDismiss() }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_queue_add, 22.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Add to now playing", "إضافة إلى التشغيل الحالي"), fontWeight = FontWeight.SemiBold)
                    }
                }
                playlists.forEach { p ->
                    val ok = entries.any { accepts(p.kind, it) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = ok) { addTo(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Sym(R.drawable.ic_queue_music, 22.dp, tint = if (ok) MaterialTheme.colorScheme.primary else Muted)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (ok) MaterialTheme.colorScheme.onSurface else Muted)
                            Text("${kindLabel(p.kind)} • ${p.entries.size}", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        label = { Text(tr("Playlist name", "اسم القائمة")) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(KIND_LOCAL, KIND_ONLINE, KIND_MIXED).forEach { k ->
                            FilterChip(selected = newKind == k, onClick = { newKind = k }, label = { Text(kindLabel(k).substringBefore(" (").substringBefore("(").trim(), fontSize = 12.sp) })
                        }
                    }
                    Text(
                        when (newKind) {
                            KIND_LOCAL -> tr("Only downloaded files.", "ملفات منزّلة فقط.")
                            KIND_ONLINE -> tr("Only web links, streamed (no download).", "روابط ويب فقط، تُبث دون تنزيل.")
                            else -> tr("Downloaded files and links together.", "ملفات منزّلة وروابط معًا.")
                        },
                        color = Muted, fontSize = 12.sp,
                    )
                    Button(
                        onClick = { addTo(Library.createPlaylist(newName, newKind)) },
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("Create and add", "إنشاء وإضافة")) }
                } else {
                    TextButton(onClick = { creating = true }) { Text(tr("+ New playlist", "+ قائمة تشغيل جديدة")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "إغلاق")) } },
    )
}

/**
 * Choose a Bluetooth device (a paired one, or type its name / MAC address) and the playlist to play when it connects.
 * With [fixed] set the playlist is already decided.
 */
@Composable
fun BtRuleDialog(vm: DownloaderViewModel, fixed: Playlist?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val playlists by Library.playlists.collectAsStateWithLifecycle()
    var paired by remember { mutableStateOf(BluetoothWatch.pairedDevices(ctx)) }
    var name by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(fixed) }
    var pairedOpen by remember { mutableStateOf(true) }
    var nearbyOpen by remember { mutableStateOf(false) }
    val scanner = rememberBtScanner { paired }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) paired = BluetoothWatch.pairedDevices(ctx) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(scanner.scanning) { paired = BluetoothWatch.pairedDevices(ctx) }      // after the permission / Bluetooth turn-on
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        paired = BluetoothWatch.pairedDevices(ctx)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Bluetooth device", "جهاز بلوتوث")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scanner.search(); pairedOpen = false; nearbyOpen = true },
                    enabled = !scanner.scanning, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    if (scanner.scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (scanner.scanning) tr("Searching…", "جارٍ البحث…") else tr("Search Now", "ابحث الآن"))
                }
                if (scanner.message.isNotBlank()) Text(scanner.message, color = Amber, fontSize = 12.sp)
                OutlinedButton(
                    onClick = {
                        try { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { toast(ctx, L.t("Open the phone's Bluetooth settings to pair.", "افتح إعدادات البلوتوث في الهاتف للإقران.")) }
                    },
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    Sym(R.drawable.ic_bluetooth, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Pair a new device", "إقران جهاز جديد"))
                }
                if (!BluetoothWatch.hasPermission(ctx)) {
                    Text(tr("Allow Bluetooth access to list your paired devices (or just type the device below).",
                        "اسمح بالوصول إلى البلوتوث لعرض أجهزتك المقترنة (أو اكتب الجهاز أدناه)."), color = Muted, fontSize = 13.sp)
                    OutlinedButton(onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }, shape = RoundedCornerShape(10.dp)) {
                        Text(tr("Allow", "السماح"))
                    }
                } else if (paired.isNotEmpty()) {
                    BtSectionHeader(tr("Paired devices (tap one)", "الأجهزة المقترنة (اضغط على واحد)"), pairedOpen) { pairedOpen = !pairedOpen }
                    if (pairedOpen) paired.forEach { (n, a) -> BtDeviceRow(n, a) { name = n; addr = a } }
                }
                if (nearbyOpen || scanner.found.isNotEmpty()) {
                    BtSectionHeader(tr("Nearby devices", "أجهزة قريبة"), nearbyOpen) { nearbyOpen = !nearbyOpen }
                    if (nearbyOpen) {
                        // devices that told their name first, the nameless ones (name = address) after them
                        scanner.found.sortedBy { it.first == it.second }.forEach { (n, a) -> BtDeviceRow(if (n == a) tr("Unnamed device", "جهاز بدون اسم") else n, a) { name = if (n == a) "" else n; addr = a } }
                        if (scanner.found.isEmpty()) Text(
                            if (scanner.scanning) tr("Looking for nearby devices…", "جارٍ البحث عن أجهزة قريبة…") else tr("No nearby devices found.", "لم يتم العثور على أجهزة قريبة."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(tr("Device name", "اسم الجهاز")) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = addr, onValueChange = { addr = it }, singleLine = true,
                    label = { Text(tr("MAC address (optional)", "عنوان MAC (اختياري)")) }, modifier = Modifier.fillMaxWidth(),
                )
                if (fixed == null) {
                    Text(tr("Playlist to play", "قائمة التشغيل"), color = Muted, fontSize = 12.sp)
                    if (playlists.isEmpty()) {
                        Text(tr("Create a playlist first (melody icon at the top).", "أنشئ قائمة تشغيل أولًا (أيقونة النغمة في الأعلى)."), color = Muted, fontSize = 13.sp)
                    } else {
                        DropdownPicker(
                            label = chosen?.name ?: tr("Choose…", "اختر…"),
                            options = playlists,
                            optionLabel = { it.name },
                            onSelect = { chosen = it },
                            enabled = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = (name.isNotBlank() || addr.isNotBlank()) && chosen != null,
                onClick = {
                    chosen?.let { Library.addBtRule(name, addr, it.id) }
                    if (!Prefs.btEnabled(ctx) && BluetoothWatch.hasPermission(ctx)) {
                        vm.setBtEnabled(true)
                        toast(ctx, L.t("Bluetooth auto-play turned on.", "تم تفعيل التشغيل التلقائي عبر بلوتوث."))
                    } else {
                        vm.refreshBluetooth()
                    }
                    onDismiss()
                },
            ) { Text(tr("Save", "حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** The Playlists tab (bottom bar): every playlist as a list or as a grid, with all its options. */
@Composable
fun PlaylistsScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val playlists by Library.playlists.collectAsStateWithLifecycle()
    val rules by Library.btRules.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var grid by remember { mutableStateOf(Prefs.bool(ctx, "playlist_grid", false)) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }     // list: the opened card, grid: the opened detail window
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var pickFor by remember { mutableStateOf<Playlist?>(null) }
    var linkFor by remember { mutableStateOf<Playlist?>(null) }
    var btFor by remember { mutableStateOf<Playlist?>(null) }
    var importFor by remember { mutableStateOf<Playlist?>(null) }
    var scheduleFor by remember { mutableStateOf<Playlist?>(null) }
    var transferFor by remember { mutableStateOf<Triple<Playlist, PlEntry, Boolean>?>(null) }   // playlist, track, move (true) / copy (false)
    // bulk mode: "Select" turns the list into checkboxes with Add to Now playing / Remove
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmRemoveSel by remember { mutableStateOf(false) }
    val chosenPls = playlists.filter { it.id in selectedIds }
    fun exitSelect() { selectMode = false; selectedIds = emptySet() }
    BackHandler(onBack = onClose)

    // cover picture from the device (⋮ menu → Cover image from device)
    val scope = rememberCoroutineScope()
    var coverTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val id = coverTargetId
        coverTargetId = null
        if (uri != null && id != null) {
            scope.launch {
                if (!CoverStore.setCustom(ctx, id, uri)) toast(ctx, L.t("This image can't be used.", "تعذّر استخدام هذه الصورة."))
            }
        }
    }
    fun actions(p: Playlist) = PlActions(
        addDownloads = { pickFor = p },
        addLink = { linkFor = p },
        importDevice = { importFor = p },
        rename = { renaming = p },
        delete = { deleting = p },
        coverDevice = { coverTargetId = p.id; coverPicker.launch("image/*") },
        coverWeb = { CoverStore.setMode(ctx, p.id, CoverStore.MOSAIC) },
        coverDefault = { CoverStore.setMode(ctx, p.id, CoverStore.AUTO) },
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Sym(R.drawable.ic_queue_music, 24.dp, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tr("Playlists", "قوائم التشغيل"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // list / grid
            TextButton(
                onClick = { grid = !grid; Prefs.putBool(ctx, "playlist_grid", grid) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (grid) "☰ " + tr("List", "قائمة") else "▦ " + tr("Grid", "شبكة")) }
            TextButton(onClick = { creating = true }, enabled = !selectMode, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr("+ New", "+ جديدة")) }
            TextButton(
                onClick = { if (selectMode) exitSelect() else selectMode = true },
                enabled = playlists.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (selectMode) tr("Done", "تم") else tr("Select", "تحديد")) }
        }
        Text(
            tr("Local = downloaded files, Online = web links played without downloading, Mixed = both.",
                "محلية = ملفات منزّلة، أونلاين = روابط تُشغَّل دون تنزيل، مختلطة = الاثنان."),
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (playlists.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("No playlists yet.", "لا توجد قوائم تشغيل بعد."), color = Muted)
            }
        } else if (!grid) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(playlists, key = { it.id }) { p ->
                    val open = expanded == p.id
                    val myRules = rules.filter { it.playlistId == p.id }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (selectMode) selectedIds = if (p.id in selectedIds) selectedIds - p.id else selectedIds + p.id
                                    else expanded = if (open) null else p.id
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selectMode) {
                                    Checkbox(
                                        checked = p.id in selectedIds,
                                        onCheckedChange = { selectedIds = if (p.id in selectedIds) selectedIds - p.id else selectedIds + p.id },
                                    )
                                }
                                // the cover picture, as in the grid
                                PlaylistCover(p, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(playlistSubtitle(p, myRules), color = Muted, fontSize = 12.sp, maxLines = 2)
                                }
                                if (!selectMode) {
                                    FilledIconButton(onClick = { vm.playEntries(p.entries) }, enabled = p.entries.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                                        Sym(R.drawable.ic_play, 26.dp, tint = MaterialTheme.colorScheme.onPrimary)
                                    }
                                    PlaylistMenuButton(p, actions(p))
                                    Text(if (open) "▴" else "▾", color = Muted)
                                }
                            }
                            if (open && !selectMode) {
                                PlaylistDetail(p, myRules, vm, { scheduleFor = p }, { pickFor = p }, { linkFor = p }, { importFor = p }, { btFor = p }) { e, move -> transferFor = Triple(p, e, move) }
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(playlists, key = { it.id }) { p ->
                    val myRules = rules.filter { it.playlistId == p.id }
                    val picked = p.id in selectedIds
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selectMode) selectedIds = if (picked) selectedIds - p.id else selectedIds + p.id
                            else expanded = p.id
                        },
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // the cover picture is the play button: tap it to play the playlist
                            Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(12.dp))) {
                                PlaylistCover(
                                    p,
                                    Modifier.fillMaxSize().then(
                                        if (!selectMode && p.entries.isNotEmpty()) Modifier.clickable { vm.playEntries(p.entries) } else Modifier,
                                    ),
                                )
                                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                                    if (selectMode) Checkbox(checked = picked, onCheckedChange = { selectedIds = if (picked) selectedIds - p.id else selectedIds + p.id })
                                    else Box(Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)) { PlaylistMenuButton(p, actions(p), tint = Color.White) }
                                }
                            }
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(playlistSubtitle(p, myRules), color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (selectMode) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("${selectedIds.size} selected", "تم تحديد ${selectedIds.size}"),
                    color = Muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selectedIds = playlists.map { it.id }.toSet() }) { Text(tr("Select all", "تحديد الكل")) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = chosenPls.any { it.entries.isNotEmpty() },
                    onClick = {
                        vm.enqueuePlaylists(chosenPls.flatMap { it.entries })
                        toast(ctx, L.t("Added to Now playing: ${chosenPls.size} playlist(s)", "تمت الإضافة إلى التشغيل الحالي: ${chosenPls.size} قائمة"))
                        exitSelect()
                    },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f),
                ) {
                    Sym(R.drawable.ic_queue_add)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Add to Now playing", "إضافة للتشغيل الحالي"), maxLines = 1, fontSize = 13.sp)
                }
                OutlinedButton(
                    enabled = chosenPls.isNotEmpty(),
                    onClick = { confirmRemoveSel = true },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Remove (${chosenPls.size})", "إزالة (${chosenPls.size})"), color = Red, maxLines = 1, fontSize = 13.sp)
                }
            }
        }
    }

    // grid mode: a playlist opens in its own window (schedule, Bluetooth device, tracks)
    if (grid && !selectMode) {
        expanded?.let { id ->
            playlists.firstOrNull { it.id == id }?.let { p ->
                Dialog(onDismissRequest = { expanded = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(10.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(playlistSubtitle(p, rules.filter { it.playlistId == p.id }), color = Muted, fontSize = 12.sp, maxLines = 2)
                                }
                                IconButton(onClick = { vm.playEntries(p.entries) }, enabled = p.entries.isNotEmpty()) {
                                    Sym(R.drawable.ic_play, 24.dp, tint = MaterialTheme.colorScheme.primary)
                                }
                                PlaylistMenuButton(p, actions(p))
                                IconButton(onClick = { expanded = null }) { Icon(Icons.Filled.Close, contentDescription = tr("Close", "إغلاق")) }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlaylistDetail(p, rules.filter { it.playlistId == p.id }, vm, { scheduleFor = p }, { pickFor = p }, { linkFor = p }, { importFor = p }, { btFor = p }) { e, move -> transferFor = Triple(p, e, move) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- small dialogs ----
    if (creating) NamePlaylistDialog(title = tr("New playlist", "قائمة تشغيل جديدة"), initial = "", askKind = true, onDismiss = { creating = false }) { n, k ->
        val p = Library.createPlaylist(n, k)
        expanded = p.id
        creating = false
    }
    renaming?.let { p ->
        NamePlaylistDialog(title = tr("Rename playlist", "إعادة تسمية القائمة"), initial = p.name, askKind = false, onDismiss = { renaming = null }) { n, _ ->
            Library.renamePlaylist(p.id, n); renaming = null
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete \"${p.name}\"?", "حذف \"${p.name}\"؟")) },
            text = { Text(tr("Only the playlist is deleted. Your downloaded files stay.", "تُحذف القائمة فقط. ملفاتك المنزّلة تبقى.")) },
            confirmButton = { TextButton(onClick = { Library.deletePlaylist(p.id); CoverStore.forget(ctx, p.id); vm.refreshBluetooth(); deleting = null }) { Text(tr("Delete", "حذف"), color = Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
    if (confirmRemoveSel) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSel = false },
            title = { Text(tr("Remove ${chosenPls.size} playlist(s)?", "إزالة ${chosenPls.size} قائمة؟")) },
            text = { Text(tr("Only the playlists are deleted. Your downloaded and imported files stay.", "تُحذف القوائم فقط. ملفاتك المنزّلة والمستوردة تبقى.")) },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds.forEach { CoverStore.forget(ctx, it) }
                    Library.deletePlaylists(selectedIds)
                    vm.refreshBluetooth()
                    confirmRemoveSel = false
                    exitSelect()
                }) { Text(tr("Remove", "إزالة"), color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveSel = false }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
    importFor?.let { p ->
        DeviceImportDialog(
            startAudio = true,
            exclude = p.entries.mapNotNull { it.localUri }.toSet(),
            title = tr("Import into \"${p.name}\"", "استيراد إلى \"${p.name}\""),
            onDismiss = { importFor = null },
            onImport = { list ->
                vm.importDeviceMedia(list)     // they also show up in the History tabs
                val n = Library.addToPlaylist(p.id, list.map { it.toEntry() })
                toast(ctx, L.t("$n added to the playlist", "تمت إضافة $n إلى القائمة"))
            },
        )
    }
    scheduleFor?.let { p -> ScheduleDialog(playlists.firstOrNull { it.id == p.id } ?: p) { scheduleFor = null } }
    transferFor?.let { (from, entry, move) ->
        val targets = playlists.filter { it.id != from.id }
        AlertDialog(
            onDismissRequest = { transferFor = null },
            title = { Text(if (move) tr("Move to which playlist?", "النقل إلى أي قائمة؟") else tr("Copy to which playlist?", "النسخ إلى أي قائمة؟")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (targets.isEmpty()) Text(tr("There is no other playlist yet.", "لا توجد قائمة أخرى بعد."), color = Muted, modifier = Modifier.padding(top = 8.dp))
                    targets.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                val ok = Library.transfer(from.id, t.id, entry, move)
                                toast(
                                    ctx,
                                    if (ok) L.t(if (move) "Moved to \"${t.name}\"" else "Copied to \"${t.name}\"", if (move) "تم النقل إلى \"${t.name}\"" else "تم النسخ إلى \"${t.name}\"")
                                    else L.t("This playlist doesn't take this kind of item.", "هذه القائمة لا تقبل هذا النوع."),
                                )
                                transferFor = null
                            }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Sym(R.drawable.ic_queue_music, 22.dp, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${kindLabel(t.kind)} • ${t.entries.size}", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { transferFor = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
    pickFor?.let { p -> PickDownloadsDialog(p, history, onDismiss = { pickFor = null }) }
    linkFor?.let { p -> AddLinkDialog(onDismiss = { linkFor = null }) { url -> vm.addLinkToPlaylist(p.id, url); linkFor = null } }
    btFor?.let { p -> BtRuleDialog(vm, p) { btFor = null } }
}

private fun playlistSubtitle(p: Playlist, myRules: List<BtRule>): String =
    "${kindLabelPlain(p.kind)} • ${p.entries.size}" +
        (if (myRules.isNotEmpty()) " • " + myRules.joinToString(", ") { it.deviceName.ifBlank { it.deviceAddress } } else "") +
        (if (p.schedule?.enabled == true) " • " + p.schedule.summary() else "")

private fun kindLabelPlain(kind: String): String = when (kind) {
    KIND_LOCAL -> L.t("Local (downloads)", "محلية (التنزيلات)")
    KIND_ONLINE -> L.t("Online (links)", "أونلاين (روابط)")
    else -> L.t("Mixed", "مختلطة")
}

/** What the ⋮ menu of a playlist can do. */
private class PlActions(
    val addDownloads: () -> Unit,
    val addLink: () -> Unit,
    val importDevice: () -> Unit,
    val rename: () -> Unit,
    val delete: () -> Unit,
    val coverDevice: () -> Unit,
    val coverWeb: () -> Unit,
    val coverDefault: () -> Unit,
)

/** The ⋮ menu of a playlist: Add downloads, Add link, Import from device, Rename, the cover options and Delete. */
@Composable
private fun PlaylistMenuButton(p: Playlist, a: PlActions, tint: Color = Muted) {
    var menu by remember { mutableStateOf(false) }
    val shareCtx = androidx.compose.ui.platform.LocalContext.current
    val modes by CoverStore.modes.collectAsStateWithLifecycle()
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_more, 22.dp, tint = tint) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr("Add downloads", "إضافة تنزيلات")) },
                enabled = p.kind != KIND_ONLINE,
                onClick = { menu = false; a.addDownloads() },
            )
            DropdownMenuItem(
                text = { Text(tr("Add link", "إضافة رابط")) },
                enabled = p.kind != KIND_LOCAL,
                onClick = { menu = false; a.addLink() },
            )
            DropdownMenuItem(
                text = { Text(tr("Import from device", "استيراد من الجهاز")) },
                enabled = p.kind != KIND_ONLINE,
                onClick = { menu = false; a.importDevice() },
            )
            DropdownMenuItem(
                text = { Text(tr("Rename", "إعادة تسمية")) },
                onClick = { menu = false; a.rename() },
            )
            DropdownMenuItem(
                text = { Text(tr("Share", "مشاركة")) },
                enabled = p.entries.isNotEmpty(),
                onClick = { menu = false; sharePlaylist(shareCtx, p) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(tr("Cover image from device…", "صورة غلاف من الجهاز…")) },
                onClick = { menu = false; a.coverDevice() },
            )
            DropdownMenuItem(
                text = { Text(tr("Cover from the internet", "غلاف من الإنترنت")) },
                enabled = p.entries.isNotEmpty(),
                onClick = { menu = false; a.coverWeb() },
            )
            DropdownMenuItem(
                text = { Text(tr("Default cover", "الغلاف الافتراضي")) },
                enabled = modes[p.id] != null,
                onClick = { menu = false; a.coverDefault() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(tr("Delete", "حذف"), color = Red) },
                onClick = { menu = false; a.delete() },
            )
        }
    }
}

/**
 * What a playlist shows when it is opened: Schedule / Add +, the Bluetooth device section and the tracks
 * (drag the ☰ handle to reorder; each track has Move / Copy / Remove).
 */
@Composable
private fun PlaylistDetail(
    p: Playlist,
    myRules: List<BtRule>,
    vm: DownloaderViewModel,
    onSchedule: () -> Unit,
    onAddDownloads: () -> Unit,
    onAddLink: () -> Unit,
    onImport: () -> Unit,
    onBluetooth: () -> Unit,
    onTransfer: (PlEntry, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onSchedule,
                shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.weight(1f),
            ) { Text(tr("Schedule", "الجدول"), fontSize = 12.sp, maxLines = 1) }
            Box(Modifier.weight(1f)) {
                var addMenu by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { addMenu = true },
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Add +", "إضافة +"), fontSize = 12.sp, maxLines = 1) }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Add downloads", "إضافة تنزيلات")) },
                        enabled = p.kind != KIND_ONLINE,
                        onClick = { addMenu = false; onAddDownloads() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Add link", "إضافة رابط")) },
                        enabled = p.kind != KIND_LOCAL,
                        onClick = { addMenu = false; onAddLink() },
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Import from device", "استيراد من الجهاز")) },
                        enabled = p.kind != KIND_ONLINE,
                        onClick = { addMenu = false; onImport() },
                    )
                }
            }
        }

        // Bluetooth
        Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
        Text(tr("Plays automatically when this Bluetooth device connects:", "تُشغَّل تلقائيًا عند اتصال جهاز البلوتوث هذا:"), color = Muted, fontSize = 12.sp)
        myRules.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sym(R.drawable.ic_bluetooth, 18.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.deviceName.ifBlank { r.deviceAddress }, fontSize = 14.sp)
                    if (r.deviceName.isNotBlank() && r.deviceAddress.isNotBlank()) Text(r.deviceAddress, color = Muted, fontSize = 11.sp)
                }
                IconButton(onClick = { Library.removeBtRule(r.id); vm.refreshBluetooth() }) {
                    Icon(Icons.Filled.Close, contentDescription = tr("Remove", "إزالة"), tint = Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        TextButton(onClick = onBluetooth) {
            Sym(R.drawable.ic_bluetooth, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(tr("Assign a Bluetooth device", "ربط جهاز بلوتوث"), fontSize = 13.sp)
        }

        // the items
        Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
        if (p.entries.isEmpty()) {
            Text(tr("Empty. Use the ⋮ menu to add downloads, links or files from the device, or the playlist button on search results.", "فارغة. استخدم قائمة ⋮ لإضافة تنزيلات أو روابط أو ملفات من الجهاز، أو زر القائمة في نتائج البحث."), color = Muted, fontSize = 13.sp)
        }
        if (p.entries.size > 1) {
            Text(tr("Hold the ☰ handle and drag to change the order.", "اضغط على المقبض ☰ واسحب لتغيير الترتيب."), color = Muted, fontSize = 11.sp)
        }
        // drag-to-reorder: the list is rearranged on screen while dragging and saved when the finger lifts
        var preview by remember(p.id) { mutableStateOf<List<PlEntry>?>(null) }
        var dragKey by remember(p.id) { mutableStateOf<String?>(null) }
        var dragY by remember(p.id) { mutableFloatStateOf(0f) }
        val rowPx = with(LocalDensity.current) { 44.dp.toPx() }
        val shown = preview ?: p.entries
        shown.forEachIndexed { _, e ->
            key(e.key) {
                val dragging = dragKey == e.key
                val liveEntries by rememberUpdatedState(p.entries)   // the gesture outlives recompositions
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragY else 0f }
                        .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(8.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (p.entries.size > 1) {
                        Box(
                            Modifier.size(width = 32.dp, height = 44.dp).pointerInput(e.key) {
                                detectDragGestures(
                                    onDragStart = { preview = liveEntries; dragKey = e.key; dragY = 0f },
                                    onDragEnd = {
                                        preview?.let { l -> Library.setOrder(p.id, l.map { it.key }) }
                                        preview = null; dragKey = null; dragY = 0f
                                    },
                                    onDragCancel = { preview = null; dragKey = null; dragY = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        val list = preview; if (list == null) return@detectDragGestures
                                        val at = list.indexOfFirst { it.key == e.key }
                                        if (dragY > rowPx / 2 && at < list.size - 1) {
                                            preview = list.toMutableList().also { it.add(at + 1, it.removeAt(at)) }
                                            dragY -= rowPx
                                        } else if (dragY < -rowPx / 2 && at > 0) {
                                            preview = list.toMutableList().also { it.add(at - 1, it.removeAt(at)) }
                                            dragY += rowPx
                                        }
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) { Sym(R.drawable.ic_drag_handle, 20.dp, tint = Muted) }
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(e.site, if (e.localUri != null) tr("Downloaded", "منزّل") else tr("Online", "أونلاين")).filter { it.isNotBlank() }.joinToString(" • "),
                            color = Muted, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                    IconButton(onClick = { vm.playEntries(p.entries, p.entries.indexOfFirst { it.key == e.key }.coerceAtLeast(0)) }, modifier = Modifier.size(36.dp)) {
                        Sym(R.drawable.ic_play, 18.dp, tint = MaterialTheme.colorScheme.primary)
                    }
                    var rowMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_more, 20.dp, tint = Muted) }
                        DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Move to playlist…", "نقل إلى قائمة…")) },
                                onClick = { rowMenu = false; onTransfer(e, true) },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Copy to playlist…", "نسخ إلى قائمة…")) },
                                onClick = { rowMenu = false; onTransfer(e, false) },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Remove", "إزالة"), color = Red) },
                                onClick = { rowMenu = false; Library.removeFromPlaylist(p.id, e.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NamePlaylistDialog(title: String, initial: String, askKind: Boolean, onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf(KIND_MIXED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(tr("Name", "الاسم")) }, modifier = Modifier.fillMaxWidth())
                if (askKind) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(KIND_LOCAL to tr("Local", "محلية"), KIND_ONLINE to tr("Online", "أونلاين"), KIND_MIXED to tr("Mixed", "مختلطة")).forEach { (k, l) ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l, fontSize = 12.sp) })
                        }
                    }
                    Text(
                        when (kind) {
                            KIND_LOCAL -> tr("Only downloaded files (works offline).", "ملفات منزّلة فقط (تعمل دون إنترنت).")
                            KIND_ONLINE -> tr("Only web links, streamed without downloading.", "روابط ويب فقط، تُبث دون تنزيل.")
                            else -> tr("Downloaded files and web links together.", "ملفات منزّلة وروابط ويب معًا.")
                        },
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onOk(name, kind) }) { Text(tr("OK", "موافق")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
private fun AddLinkDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add a link", "إضافة رابط")) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(tr("Video or music link", "رابط فيديو أو موسيقى")) },
                trailingIcon = { TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) { Text(tr("Paste", "لصق")) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = parseLinks(text).isNotEmpty(),
                onClick = { parseLinks(text).forEach(onOk) },
            ) { Text(tr("Add", "إضافة")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
private fun PickDownloadsDialog(p: Playlist, history: List<HistoryItem>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf(setOf<String>()) }
    val have = p.entries.map { it.key }.toSet()
    val list = history.filter { it.uri !in have }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add downloads", "إضافة تنزيلات")) },
        text = {
            if (list.isEmpty()) {
                Text(tr("No other downloaded files to add.", "لا توجد ملفات منزّلة أخرى للإضافة."), color = Muted)
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.uri + it.timeMillis }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (h.uri in picked) picked - h.uri else picked + h.uri }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = h.uri in picked, onCheckedChange = { picked = if (h.uri in picked) picked - h.uri else picked + h.uri })
                            Column(Modifier.weight(1f)) {
                                Text(prettyName(h.name), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${h.format} • ${formatSize(h.sizeBytes)}", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = {
                    val n = Library.addToPlaylist(p.id, list.filter { it.uri in picked }.map { it.toEntry() })
                    toast(ctx, L.t("$n added", "تمت إضافة $n"))
                    onDismiss()
                },
            ) { Text(tr("Add (${picked.size})", "إضافة (${picked.size})")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** The heart-symbol screen: favorite online and offline media, in separate Audio / Video tabs. */
@Composable
fun FavoritesScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val favs by Library.favorites.collectAsStateWithLifecycle()
    var sub by rememberSaveable { mutableIntStateOf(0) }          // 0 = audio, 1 = video
    var addFor by remember { mutableStateOf<PlEntry?>(null) }
    val ctx = LocalContext.current
    var selectMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var addSelected by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(Prefs.bool(ctx, "fav_grid", false)) }
    var shareAsk by remember { mutableStateOf<PlEntry?>(null) }
    shareAsk?.let { ShareChoiceDialog(it) { shareAsk = null } }
    fun exitSelect() { selectMode = false; selectedKeys = emptySet() }
    BackHandler { if (selectMode) exitSelect() else onClose() }

    val audio = favs.filter { it.entry.isAudio }
    val video = favs.filter { !it.entry.isAudio }
    val shown = (if (sub == 0) audio else video).map { it.entry }
    // what is ticked, in the order shown (an item that is no longer a favorite drops out by itself)
    val chosen = shown.filter { it.key in selectedKeys }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع")) }
            Text(
                if (selectMode) tr("${chosen.size} selected", "تم تحديد ${chosen.size}") else tr("Favorites", "المفضلة"),
                fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (selectMode) {
                TextButton(onClick = { selectedKeys = shown.map { it.key }.toSet() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("Select all", "تحديد الكل"), maxLines = 1)
                }
            } else {
                TextButton(onClick = { vm.playEntries(shown) }, enabled = shown.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Sym(R.drawable.ic_play, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Play all", "تشغيل الكل"), maxLines = 1)
                }
            }
            TextButton(
                onClick = { grid = !grid; Prefs.putBool(ctx, "fav_grid", grid) }, contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (grid) "☰" else "▦", fontSize = 18.sp) }
            TextButton(
                onClick = { if (selectMode) exitSelect() else selectMode = true },
                enabled = selectMode || shown.isNotEmpty(), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (selectMode) tr("Done", "تم") else tr("Select", "تحديد"), maxLines = 1) }
        }
        TabRow(selectedTabIndex = sub, containerColor = Color.Transparent) {
            Tab(selected = sub == 0, onClick = { sub = 0; selectedKeys = emptySet() }, text = { Text(tr("Audio", "صوت") + " (${audio.size})") })
            Tab(selected = sub == 1, onClick = { sub = 1; selectedKeys = emptySet() }, text = { Text(tr("Video", "فيديو") + " (${video.size})") })
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    tr("No favorites here yet. Tap the heart on any search result or download.", "لا توجد مفضلات هنا بعد. اضغط القلب على أي نتيجة بحث أو تنزيل."),
                    color = Muted, modifier = Modifier.padding(32.dp),
                )
            }
        } else if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(shown, key = { it.key }) { e ->
                    val picked = e.key in selectedKeys
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { if (selectMode) selectedKeys = if (picked) selectedKeys - e.key else selectedKeys + e.key else vm.playEntries(listOf(e)) },
                    ) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box {
                                FavoriteCover(e, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), 24.dp)
                                if (selectMode) Checkbox(checked = picked, onCheckedChange = { selectedKeys = if (picked) selectedKeys - e.key else selectedKeys + e.key }, modifier = Modifier.align(Alignment.TopStart))
                            }
                            Text(e.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (e.localUri != null) tr("Offline", "دون إنترنت") else tr("Online", "أونلاين"),
                                color = if (e.localUri != null) Green else Amber, fontSize = 11.sp,
                            )
                            if (!selectMode) Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.playEntries(listOf(e)) }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_play, 18.dp, tint = MaterialTheme.colorScheme.primary) }
                                IconButton(onClick = { addFor = e }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_queue_add, 18.dp, tint = MaterialTheme.colorScheme.onSurface) }
                                IconButton(onClick = { if (shareEntryOrAsk(ctx, e)) shareAsk = e }, modifier = Modifier.size(36.dp)) { Sym(R.drawable.ic_share, 18.dp, tint = MaterialTheme.colorScheme.onSurface) }
                                Spacer(Modifier.weight(1f))
                                HeartButton(e, 36.dp)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(shown, key = { it.key }) { e ->
                    val picked = e.key in selectedKeys
                    fun toggle() { selectedKeys = if (picked) selectedKeys - e.key else selectedKeys + e.key }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().then(if (selectMode) Modifier.clickable { toggle() } else Modifier),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selectMode) Checkbox(checked = picked, onCheckedChange = { toggle() })
                                FavoriteCover(e, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)), 20.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(e.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(e.site, e.uploader).filter { it.isNotBlank() }.joinToString(" • "),
                                        color = Muted, fontSize = 12.sp, maxLines = 1,
                                    )
                                    Text(
                                        if (e.localUri != null) tr("Offline: downloaded", "دون إنترنت: منزّل") else tr("Online: streamed", "أونلاين: بث"),
                                        color = if (e.localUri != null) Green else Amber, fontSize = 11.sp,
                                    )
                                }
                            }
                            if (!selectMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { vm.playEntries(listOf(e)) },
                                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(38.dp),
                                ) {
                                    Sym(R.drawable.ic_play, 16.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr("Play", "تشغيل"), fontSize = 13.sp)
                                }
                                if (e.localUri == null && e.pageUrl.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = { vm.sendToDownload(e.pageUrl); onClose() },
                                        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(38.dp),
                                    ) {
                                        Sym(R.drawable.ic_download, 16.dp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(tr("Download", "تنزيل"), fontSize = 13.sp)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { addFor = e },
                                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(38.dp),
                                ) {
                                    Sym(R.drawable.ic_queue_add, 16.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr("Playlist", "قائمة"), fontSize = 13.sp)
                                }
                                OutlinedButton(
                                    onClick = { if (shareEntryOrAsk(ctx, e)) shareAsk = e },
                                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(38.dp),
                                ) {
                                    Sym(R.drawable.ic_share, 16.dp)
                                }
                                Spacer(Modifier.weight(1f))
                                HeartButton(e, 40.dp)
                            }
                        }
                    }
                }
            }
        }
        // multi-select actions
        if (selectMode && chosen.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.playEntries(chosen); exitSelect() },
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_play)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Play (${chosen.size})", "تشغيل (${chosen.size})"), maxLines = 1, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { addSelected = true },
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_queue_music)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Add to playlists", "إضافة إلى قوائم"), maxLines = 1, fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Remove from favorites (${chosen.size})", "إزالة من المفضلة (${chosen.size})"), color = Red, maxLines = 1, fontSize = 13.sp)
                }
            }
        }
    }
    addFor?.let { AddToPlaylistDialog(listOf(it)) { addFor = null } }
    if (addSelected) {
        val items = chosen
        AddToPlaylistDialog(
            items,
            onNowPlaying = {
                vm.enqueuePlaylists(items)
                toast(ctx, L.t("Added to Now playing: ${items.size}", "تمت الإضافة إلى التشغيل الحالي: ${items.size}"))
                exitSelect()
            },
        ) { addSelected = false }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Remove from favorites?", "إزالة من المفضلة؟")) },
            text = { Text(tr("${chosen.size} item(s) will be removed from Favorites. The files and links themselves are not deleted.", "ستُزال ${chosen.size} عنصر من المفضلة. لا تُحذف الملفات أو الروابط نفسها.")) },
            confirmButton = {
                TextButton(onClick = {
                    chosen.forEach { Library.toggleFavorite(it) }
                    confirmRemove = false
                    exitSelect()
                }) { Text(tr("Remove", "إزالة"), color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}
