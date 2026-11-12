package com.example.ytsaver

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

private fun rowKey(i: HistoryItem) = i.uri + i.timeMillis

/** The open row is remembered per tab: opening a file in Media must not open it in History (and the other way round). */
private fun tabKey(history: Boolean, i: HistoryItem) = (if (history) "h:" else "m:") + rowKey(i)

/** History = the download history: everything downloaded through the app (imported device files live in Media). */
@Composable
fun HistoryTab(vm: DownloaderViewModel) = ItemsTab(vm, history = true)

/** Media = the downloaded and imported files, with the Audio / Video sub-tabs and "Import from device". */
@Composable
fun MediaTab(vm: DownloaderViewModel) = ItemsTab(vm, history = false)

@Composable
private fun ItemsTab(vm: DownloaderViewModel, history: Boolean) {
    val mediaAll by vm.history.collectAsStateWithLifecycle()
    val all = remember(mediaAll, history) { if (history) mediaAll.filter { !it.isDeviceFile } else mediaAll }
    val mediaSearchOn by vm.librarySearch.collectAsStateWithLifecycle()
    val historySearchOn by vm.historySearch.collectAsStateWithLifecycle()
    val searchOn = if (history) historySearchOn else mediaSearchOn      // one switch per tab (Settings -> Behavior)
    val sitesOn by vm.sourceSites.collectAsStateWithLifecycle()
    val groupBySite by vm.groupBySite.collectAsStateWithLifecycle()
    val folders by FolderStore.folders.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var originFilter by remember { mutableIntStateOf(0) }            // 0 = all, 1 = downloaded (Media only); imported files are the "Device" site chip
    var siteFilter by remember { mutableStateOf<String?>(null) }
    var openFolderId by remember { mutableStateOf<String?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<MediaFolder?>(null) }
    var moveItems by remember { mutableStateOf<List<HistoryItem>?>(null) }
    var vaultItems by remember { mutableStateOf<List<HistoryItem>?>(null) }     // "Move to vault" waiting for the confirmation
    val ctx = LocalContext.current
    // Audio / Video tab, sort order and the open row live in the ViewModel: they are still there after media was played
    val sub by vm.historySub.collectAsStateWithLifecycle()          // 0 = audio, 1 = video
    var selectMode by remember { mutableStateOf(false) }
    // view: 0 = list, 1 = grid, 2 = sliders (Audio and Video rows on ONE page); the old grid on/off flag is migrated
    val viewPrefKey = if (history) "history_view" else "media_view"
    val viewDefault = if (history) 0 else 2
    var viewMode by remember {
        mutableIntStateOf(
            Prefs.str(ctx, viewPrefKey)?.toIntOrNull()
                ?: if (!history && Prefs.bool(ctx, "media_grid", false)) 1 else viewDefault,
        )
    }
    var viewMenu by remember { mutableStateOf(false) }
    var filtersExpanded by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val grid = viewMode == 1
    val sliders = viewMode == 2
    var selected by remember { mutableStateOf(setOf<String>()) }   // uris
    val expanded by vm.historyExpanded.collectAsStateWithLifecycle()   // the one row that is open
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var addToPlaylistsOpen by remember { mutableStateOf(false) }
    var shareItem by remember { mutableStateOf<HistoryItem?>(null) }
    var removeItem by remember { mutableStateOf<HistoryItem?>(null) }   // waiting for the remove confirmation
    val sortPair by vm.historySort.collectAsStateWithLifecycle()        // key: 0 = date, 1 = name, 2 = size
    val sortKey = sortPair.first
    val sortAsc = sortPair.second                                       // date/size: newest/biggest first by default
    var sortMenu by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    val showThumbs by vm.historyThumbs.collectAsStateWithLifecycle()
    val categoryIds = listOf("all", "audio", "video", "youtube", "facebook")
    val visibleCategories = remember(history, categoryIds) {
        categoryIds.filter { Prefs.categoryFilter(ctx, history, it) }
    }
    val categoryPrefKey = if (history) "history_category_filter" else "media_category_filter"
    var categoryFilter by remember(history) {
        mutableStateOf(Prefs.str(ctx, categoryPrefKey)?.takeIf { it in categoryIds } ?: "all")
    }
    val activeCategory = if (searchOn && categoryFilter in visibleCategories) categoryFilter else "all"

    val audioCount = all.count { it.format == "MP3" }
    val videoCount = all.count { it.format == "MP4" }
    // search / filters (Settings -> Behavior can switch them off)
    val q = if (searchOn) query.trim() else ""
    val effOrigin = if (searchOn && !history) originFilter else 0
    val effSite = if (sitesOn) siteFilter else null
    val searching = q.isNotEmpty() || effOrigin != 0 || effSite != null || activeCategory != "all"
    val sitesPresent = remember(all, sub, history) {
        all.filter { history || (if (sub == 0) it.format == "MP3" else it.format == "MP4") }.map { siteOfItem(it) }.distinct().sorted()
    }
    val filterPanelAvailable = visibleCategories.isNotEmpty() || (searchOn && !history) || (sitesOn && sitesPresent.size > 1)

    // folders of this kind (Media only); a file is either in a folder or in the main list
    val folderList = remember(folders, sub, history) { if (history) emptyList() else folders.filter { it.kind == sub }.sortedBy { it.name.lowercase() } }
    val openFolder = folderList.firstOrNull { it.id == openFolderId }
    val inFolder = remember(folderList) { folderList.flatMap { it.uris }.toSet() }

    val items = remember(all, sub, sortKey, sortAsc, history, q, effOrigin, effSite, activeCategory, groupBySite, sitesOn, folderList, openFolderId, sliders) {
        var list = if (history || sliders) all else all.filter { if (sub == 0) it.format == "MP3" else it.format == "MP4" }
        if (q.isNotEmpty()) list = list.filter { prettyName(it.name).contains(q, ignoreCase = true) || siteOfItem(it).contains(q, ignoreCase = true) }
        if (effOrigin == 1) list = list.filter { !it.isDeviceFile }
        effSite?.let { st -> list = list.filter { siteOfItem(it) == st } }
        list = when (activeCategory) {
            "audio" -> list.filter { it.format == "MP3" }
            "video" -> list.filter { it.format == "MP4" }
            "youtube" -> list.filter { siteOfItem(it).contains("youtube", ignoreCase = true) }
            "facebook" -> list.filter { siteOfItem(it).contains("facebook", ignoreCase = true) }
            else -> list
        }
        val of = folderList.firstOrNull { it.id == openFolderId }
        list = when {
            of != null -> list.filter { it.uri in of.uris }                          // inside a folder
            sliders -> list                                                          // sliders: every file, folders or not
            !history && !searching -> list.filter { it.uri !in inFolder }            // main list: files that are in folders are inside them
            else -> list                                                             // searching: everywhere
        }
        val sorted = when (sortKey) {
            1 -> list.sortedBy { prettyName(it.name).lowercase() }
            2 -> list.sortedBy { it.sizeBytes }
            else -> list.sortedBy { it.timeMillis }
        }
        // name reads A→Z first; date and size read newest / biggest first
        val ascending = if (sortKey == 1) !sortAsc else sortAsc
        val ordered = if (ascending) sorted else sorted.asReversed()
        // "Group by site": a stable sort by website keeps the chosen order inside each group
        if (groupBySite && sitesOn) ordered.sortedBy { siteOfItem(it) } else ordered
    }
    val chosen = items.filter { it.uri in selected }
    val visibleFolders = if (!history && openFolder == null && !searching) folderList else emptyList()
    val grouping = groupBySite && sitesOn

    fun exitSelect() { selectMode = false; selected = emptySet() }
    BackHandler(enabled = openFolder != null) { openFolderId = null; exitSelect() }

    Column(Modifier.fillMaxSize()) {
        if (!history && !sliders) TabRow(selectedTabIndex = sub, containerColor = Color.Transparent) {
            Tab(selected = sub == 0, onClick = { vm.setHistorySub(0); openFolderId = null; exitSelect() }, text = { Text(tr("Audio", "صوت") + " ($audioCount)") })
            Tab(selected = sub == 1, onClick = { vm.setHistorySub(1); openFolderId = null; exitSelect() }, text = { Text(tr("Video", "فيديو") + " ($videoCount)") })
        }

        // inside a folder: a back arrow and the folder's name
        if (openFolder != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openFolderId = null; exitSelect() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع"))
                }
                Sym(R.drawable.ic_folder, 22.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(openFolder.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // audio / video that is already on the phone, and folders of this kind
        if (!history && openFolder == null) Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = { importOpen = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Sym(R.drawable.ic_download, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(tr("Import from device", "استيراد من الجهاز"), fontSize = 13.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = { newFolderOpen = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Sym(R.drawable.ic_folder_add, 22.dp)      // folder with a plus = new folder
            }
        }

        // search and collapsible filters (Settings -> Behavior)
        if (searchOn && all.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(tr("Search by name or website", "ابحث بالاسم أو الموقع"), color = Muted, fontSize = 14.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Clear", "مسح"), tint = Muted, modifier = Modifier.size(18.dp))
                    }
                } else Spacer(Modifier.height(32.dp))
                if (filterPanelAvailable) {
                    IconButton(
                        onClick = { filtersExpanded = !filtersExpanded },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = tr("Filters", "عوامل التصفية"),
                            tint = if (filtersExpanded || activeCategory != "all" || effOrigin != 0 || effSite != null) MaterialTheme.colorScheme.primary else Muted,
                        )
                    }
                }
            }
        }
        if (searchOn && filtersExpanded && filterPanelAvailable && all.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visibleCategories.forEach { id ->
                    val label = when (id) {
                        "audio" -> tr("Audio", "صوت")
                        "video" -> tr("Video", "فيديو")
                        "youtube" -> "YouTube"
                        "facebook" -> "Facebook"
                        else -> tr("All", "الكل")
                    }
                    VvChip(
                        label,
                        selected = activeCategory == id,
                        onClick = {
                            categoryFilter = id
                            Prefs.putStr(ctx, categoryPrefKey, id)
                            if (id == "all") { originFilter = 0; siteFilter = null }
                        },
                    )
                }
                if (searchOn && !history) {
                    VvChip(tr("Downloaded", "منزَّل"), selected = effOrigin == 1, onClick = { originFilter = if (originFilter == 1) 0 else 1 })
                }
                if (sitesOn && sitesPresent.size > 1) sitesPresent.forEach { st ->
                    VvChip(st, selected = effSite == st, onClick = { siteFilter = if (siteFilter == st) null else st })
                }
            }
        }

        if (items.isEmpty() && visibleFolders.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (searching) tr("Nothing matches.", "لا توجد نتائج مطابقة.")
                    else if (openFolder != null) tr("This folder is empty. In the list, tap Select, choose files and use Move to folder.", "هذا المجلد فارغ. في القائمة اضغط تحديد ثم اختر الملفات واستخدم نقل إلى مجلد.")
                    else if (history) tr("No downloads yet.", "لا توجد تنزيلات بعد.")
                    else if (sub == 0) tr("No audio yet. Download some, or import from your device.", "لا توجد ملفات صوتية بعد. نزّل بعضها أو استورد من جهازك.")
                    else tr("No video yet. Download some, or import from your device.", "لا توجد ملفات فيديو بعد. نزّل بعضها أو استورد من جهازك."),
                    color = Muted,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectMode) tr("${selected.size} selected", "تم تحديد ${selected.size}")
                    else if (history) tr("${items.size} download${if (items.size == 1) "" else "s"}", "${items.size} تنزيل")
                    else tr("${items.size} file${if (items.size == 1) "" else "s"}", "${items.size} ملف"),
                    color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val tight = PaddingValues(horizontal = 8.dp)
                Row {
                    Box {
                        TextButton(onClick = { sortMenu = true }, contentPadding = tight) {
                            Text(tr("Sort", "ترتيب"), maxLines = 1)
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                0 to tr("Date", "التاريخ"),
                                1 to tr("Name", "الاسم"),
                                2 to tr("Size", "الحجم"),
                            ).forEach { (key, label) ->
                                val active = sortKey == key
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label + if (active) (if ((key == 1) != sortAsc) "  ↑" else "  ↓") else "",
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        // picking the active option again flips its direction
                                        if (active) vm.setHistorySort(sortKey, !sortAsc) else vm.setHistorySort(key, false)
                                        sortMenu = false
                                    },
                                )
                            }
                            if (sitesOn) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            tr("Group by site", "تجميع حسب الموقع") + if (groupBySite) "  ✓" else "",
                                            fontWeight = if (groupBySite) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = { vm.setGroupBySite(!groupBySite); sortMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { viewMenu = true }, contentPadding = tight) {
                            Text(
                                when (viewMode) { 1 -> "▦ " + tr("Grid", "شبكة"); 2 -> "≋ " + tr("Sliders", "شرائح"); else -> "☰ " + tr("List", "قائمة") },
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                            listOf(
                                0 to "☰  " + tr("List view", "عرض قائمة"),
                                1 to "▦  " + tr("Grid view", "عرض شبكة"),
                                2 to "≋  " + tr("Sliders (Audio + Video)", "شرائح (صوت + فيديو)"),
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal) },
                                    onClick = {
                                        viewMode = mode
                                        Prefs.putStr(ctx, viewPrefKey, mode.toString())
                                        if (!history) Prefs.putBool(ctx, "media_grid", mode == 1)
                                        if (mode == 2) exitSelect()
                                        viewMenu = false
                                    },
                                )
                            }
                        }
                    }
                    if (history) {
                        Box {
                            IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = tr("More options", "خيارات إضافية"))
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                if (!sliders) DropdownMenuItem(
                                    text = { Text(if (selectMode) tr("Done", "تم") else tr("Select", "تحديد")) },
                                    onClick = { if (selectMode) exitSelect() else selectMode = true; moreMenu = false },
                                )
                                if (selectMode && !sliders) DropdownMenuItem(
                                    text = { Text(tr("Select all", "تحديد الكل")) },
                                    onClick = { selected = items.map { it.uri }.toSet(); moreMenu = false },
                                )
                                if (openFolder == null) DropdownMenuItem(
                                    text = { Text(tr("Clear all", "مسح الكل"), color = Red) },
                                    onClick = { confirmClear = true; moreMenu = false },
                                )
                            }
                        }
                    } else {
                        if (!sliders) TextButton(onClick = { if (selectMode) exitSelect() else selectMode = true }, contentPadding = tight) {
                            Text(if (selectMode) tr("Done", "تم") else tr("Select", "تحديد"), maxLines = 1)
                        }
                        if (selectMode && !sliders) {
                            TextButton(onClick = { selected = items.map { it.uri }.toSet() }, contentPadding = tight) {
                                Text(tr("Select all", "تحديد الكل"), maxLines = 1)
                            }
                        } else if (openFolder == null) {
                            TextButton(onClick = { confirmClear = true }, contentPadding = tight) {
                                Text(tr("Clear all", "مسح الكل"), maxLines = 1)
                            }
                        }
                    }
                }
            }

            if (sliders) SlidersView(items, vm, Modifier.weight(1f)) else if (grid) LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(visibleFolders, key = { "folder:" + it.id }, span = { GridItemSpan(maxLineSpan) }) { fo ->
                    val mine = all.filter { it.uri in fo.uris }
                    FolderCard(
                        folder = fo, count = mine.size, bytes = mine.sumOf { it.sizeBytes },
                        onOpen = { openFolderId = fo.id; exitSelect() },
                        onRename = { renameFolder = fo },
                        onDelete = { deleteFolder = fo },
                    )
                }
                var lastSiteG: String? = null
                items.forEach { item ->
                    val site = siteOfItem(item)
                    if (grouping && site != lastSiteG) {
                        lastSiteG = site
                        item(key = "site:$site", span = { GridItemSpan(maxLineSpan) }) {
                            Text(site, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    item(key = rowKey(item)) {
                        HistoryGridCard(
                            item = item, vm = vm, selectMode = selectMode, isSelected = item.uri in selected,
                            showThumb = item.format == "MP4",
                            source = if (sitesOn) site else null,
                            movable = !history,
                            onToggleSelect = { selected = if (item.uri in selected) selected - item.uri else selected + item.uri },
                            onHoldSelect = { selectMode = true; selected = selected + item.uri; vm.setHistoryExpanded(null) },
                            onShare = { shareItem = item },
                            onRemove = { removeItem = item },
                            onMove = if (history) null else ({ moveItems = listOf(item) }),
                            onVault = if (history) null else ({ vaultItems = listOf(item) }),
                        )
                    }
                }
            } else             LazyColumn(
                Modifier.weight(1f).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                // folders first (Media, main list)
                items(visibleFolders, key = { "folder:" + it.id }) { fo ->
                    val mine = all.filter { it.uri in fo.uris }
                    FolderCard(
                        folder = fo, count = mine.size, bytes = mine.sumOf { it.sizeBytes },
                        onOpen = { openFolderId = fo.id; exitSelect() },
                        onRename = { renameFolder = fo },
                        onDelete = { deleteFolder = fo },
                    )
                }
                var lastSite: String? = null
                items.forEach { item ->
                    val site = siteOfItem(item)
                    if (grouping && site != lastSite) {
                        lastSite = site
                        item(key = "site:$site") {
                            Text(site, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    item(key = rowKey(item)) {
                        HistoryRow(
                            item = item,
                            vm = vm,
                            selectMode = selectMode,
                            isSelected = item.uri in selected,
                            isExpanded = expanded == tabKey(history, item),
                            compact = !history,
                            showThumb = showThumbs && item.format == "MP4",
                            siteBadge = if (sitesOn) site else null,
                            onToggleSelect = { selected = if (item.uri in selected) selected - item.uri else selected + item.uri },
                            onHoldSelect = { selectMode = true; selected = selected + item.uri; vm.setHistoryExpanded(null) },
                            onToggleExpand = { vm.setHistoryExpanded(if (expanded == tabKey(history, item)) null else tabKey(history, item)) },
                            onShare = { shareItem = item },
                            onRemove = { removeItem = item },
                            onSwipeDelete = { removeItem = item },     // asks first: delete the file or only remove it from the list
                            onMove = if (history) null else ({ moveItems = listOf(item) }),
                            onVault = if (history) null else ({ vaultItems = listOf(item) }),
                        )
                    }
                }
            }

            // multi-select actions
            if (selectMode && chosen.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.playNow(chosen); exitSelect() },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Sym(R.drawable.ic_play)
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Play (${chosen.size})", "تشغيل (${chosen.size})"), maxLines = 1, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { addToPlaylistsOpen = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Sym(R.drawable.ic_queue_music)
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Add to playlists", "إضافة إلى قوائم"), maxLines = 1, fontSize = 13.sp)
                        }
                    }
                    if (!history) OutlinedButton(
                        onClick = { vaultItems = chosen },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Sym(R.drawable.ic_lock)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Move to vault (${chosen.size})", "نقل إلى الخزنة (${chosen.size})"), maxLines = 1, fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!history) OutlinedButton(
                            onClick = { moveItems = chosen },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Sym(R.drawable.ic_folder)
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Move to folder", "نقل إلى مجلد"), maxLines = 1, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { confirmDeleteSelected = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Delete (${chosen.size})", "حذف (${chosen.size})"), color = Red, fontSize = 14.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // the selected items go to the current playlist (Now playing) or to one of the saved playlists
    if (addToPlaylistsOpen) {
        BulkAddToPlaylistDialog(
            count = chosen.size,
            onCurrent = {
                val n = vm.addToQueue(chosen)
                toast(
                    ctx,
                    if (n > 0) L.t("$n added to the current playlist", "تمت إضافة $n إلى قائمة التشغيل الحالية")
                    else L.t("Already in the current playlist", "موجود بالفعل في قائمة التشغيل الحالية"),
                )
                addToPlaylistsOpen = false
                exitSelect()
            },
            onPlaylist = { p ->
                val n = Library.addToPlaylist(p.id, chosen.map { it.toEntry() })
                toast(
                    ctx,
                    if (n > 0) L.t("$n added to \"${p.name}\"", "تمت إضافة $n إلى \"${p.name}\"")
                    else L.t("Nothing added: already there, or this playlist doesn't take this kind of item.", "لم تتم إضافة شيء: موجود بالفعل أو أن القائمة لا تقبل هذا النوع."),
                )
                addToPlaylistsOpen = false
                exitSelect()
            },
            onDismiss = { addToPlaylistsOpen = false },
        )
    }

    // ---- folders ----
    if (newFolderOpen) {
        FolderNameDialog(tr("New folder", "مجلد جديد"), "", onDismiss = { newFolderOpen = false }) { n ->
            FolderStore.create(n, sub)
            newFolderOpen = false
        }
    }
    renameFolder?.let { fo ->
        FolderNameDialog(tr("Rename folder", "إعادة تسمية المجلد"), fo.name, onDismiss = { renameFolder = null }) { n ->
            FolderStore.rename(fo.id, n); renameFolder = null
        }
    }
    deleteFolder?.let { fo ->
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text(tr("Delete \"${fo.name}\"?", "حذف \"${fo.name}\"؟")) },
            text = { Text(tr("Only the folder is deleted. Its files go back to the main list and stay on your phone.", "يُحذف المجلد فقط. تعود ملفاته إلى القائمة الرئيسية وتبقى على هاتفك.")) },
            confirmButton = {
                TextButton(onClick = {
                    FolderStore.delete(fo.id)
                    if (openFolderId == fo.id) openFolderId = null
                    deleteFolder = null
                }) { Text(tr("Delete", "حذف"), color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
    moveItems?.let { list ->
        val uris = list.map { it.uri }.toSet()
        fun done(target: String?, name: String?) {
            FolderStore.move(uris, target)
            toast(
                ctx,
                if (target == null) L.t("${uris.size} file(s) moved to the main list", "تم نقل ${uris.size} ملف إلى القائمة الرئيسية")
                else L.t("${uris.size} file(s) moved to \"$name\"", "تم نقل ${uris.size} ملف إلى \"$name\""),
            )
            moveItems = null
            exitSelect()
        }
        MoveToFolderDialog(
            count = list.size,
            kind = sub,
            folders = folders,
            current = openFolderId,
            onPick = { id -> done(id, folders.firstOrNull { it.id == id }?.name) },
            onNew = { n -> val fo = FolderStore.create(n, sub); done(fo.id, fo.name) },
            onDismiss = { moveItems = null },
        )
    }

    vaultItems?.let { list -> VaultMoveFlow(list, onDone = { vaultItems = null; exitSelect() }) }

    if (importOpen && !history) {
        DeviceImportDialog(
            startAudio = sub == 0,
            exclude = all.map { it.uri }.toSet(),
            onDismiss = { importOpen = false },
            onImport = { list ->
                vm.importDeviceMedia(list)
                // show what was just imported: the tab of the first file
                list.firstOrNull()?.let { vm.setHistorySub(if (it.format == "MP3") 0 else 1) }
            },
        )
    }

    val si = shareItem
    if (si != null) {
        AlertDialog(
            onDismissRequest = { shareItem = null },
            title = { Text(tr("Share", "مشاركة")) },
            text = {
                Text(
                    tr(
                        "Send the downloaded file, or just the link to the original video, through WhatsApp, Telegram and other apps.",
                        "أرسل الملف الذي تم تنزيله أو رابط الفيديو الأصلي عبر واتساب وتيليجرام وتطبيقات أخرى.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    shareItem = null
                    if (fileExists(ctx, si)) shareFile(ctx, si)
                    else toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
                }) { Text(tr("Share file", "مشاركة الملف")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { shareItem = null; shareLink(ctx, si) },
                    enabled = si.sourceUrl.isNotBlank(),
                ) { Text(tr("Share link", "مشاركة الرابط")) }
            },
        )
    }

    // one download: delete the file or only remove it from the list
    val ri = removeItem
    if (ri != null && ri.isDeviceFile) {
        AlertDialog(
            onDismissRequest = { removeItem = null },
            title = { Text(tr("Remove from the app?", "إزالة من التطبيق؟")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(prettyName(ri.name), fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(tr("This file was imported from your phone. It is only removed from the app's lists; the file itself stays where it is.",
                        "تم استيراد هذا الملف من هاتفك. تتم إزالته من قوائم التطبيق فقط ويبقى الملف في مكانه."))
                }
            },
            confirmButton = { TextButton(onClick = { vm.removeHistory(ri, deleteFile = false); removeItem = null }) { Text(tr("Remove", "إزالة"), color = Red) } },
            dismissButton = { TextButton(onClick = { removeItem = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    } else if (ri != null) {
        RemoveDialog(
            title = tr("Remove this download?", "إزالة هذا التنزيل؟"),
            name = prettyName(ri.name),
            body = tr(
                "Do you want to delete the file from your phone permanently, or only remove it from the history list?",
                "هل تريد حذف الملف من هاتفك نهائيًا، أم إزالته من قائمة السجل فقط؟",
            ),
            deleteLabel = tr("Delete file permanently", "حذف الملف نهائيًا"),
            onDelete = { vm.removeHistory(ri, deleteFile = true); removeItem = null },
            onListOnly = { vm.removeHistory(ri, deleteFile = false); removeItem = null },
            onCancel = { removeItem = null },
        )
    }

    // the selected downloads
    if (confirmDeleteSelected) {
        RemoveDialog(
            title = tr("Remove ${chosen.size} selected?", "إزالة ${chosen.size} من المحدد؟"),
            name = null,
            body = tr(
                "Do you want to delete the selected files from your phone permanently, or only remove them from the history list?",
                "هل تريد حذف الملفات المحددة من هاتفك نهائيًا، أم إزالتها من قائمة السجل فقط؟",
            ),
            deleteLabel = tr("Delete files permanently", "حذف الملفات نهائيًا"),
            onDelete = { vm.removeHistoryItems(chosen, deleteFiles = true); confirmDeleteSelected = false; exitSelect() },
            onListOnly = { vm.removeHistoryItems(chosen, deleteFiles = false); confirmDeleteSelected = false; exitSelect() },
            onCancel = { confirmDeleteSelected = false },
        )
    }

    // Clear all (audio and video)
    if (confirmClear) {
        RemoveDialog(
            title = tr("Clear history?", "مسح السجل؟"),
            name = null,
            body = tr(
                "Do you want to delete all your downloaded files from your phone permanently, or only clear the history list? Files that are kept stay in your Downloads folder.",
                "هل تريد حذف جميع ملفاتك المنزّلة من هاتفك نهائيًا، أم مسح قائمة السجل فقط؟ الملفات التي يتم الاحتفاظ بها تبقى في مجلد التنزيلات.",
            ),
            deleteLabel = tr("Delete all files permanently", "حذف كل الملفات نهائيًا"),
            onDelete = { vm.removeHistoryItems(all, deleteFiles = true); confirmClear = false; exitSelect() },
            onListOnly = { vm.clearHistory(); confirmClear = false; exitSelect() },
            onCancel = { confirmClear = false },
        )
    }
}

/** Three-way confirmation: delete the file(s) from the phone, only remove from the list, or cancel. */
@Composable
private fun RemoveDialog(
    title: String,
    name: String?,
    body: String,
    deleteLabel: String,
    onDelete: () -> Unit,
    onListOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (name != null) Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(body)
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = onDelete) { Text(deleteLabel, color = Red) }
                TextButton(onClick = onListOnly) { Text(tr("Remove from list only", "إزالة من القائمة فقط")) }
                TextButton(onClick = onCancel) { Text(tr("Cancel", "إلغاء")) }
            }
        },
    )
}

/** One download: a compact line that opens (on tap) into all of its details and actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: HistoryItem,
    vm: DownloaderViewModel,
    selectMode: Boolean,
    isSelected: Boolean,
    isExpanded: Boolean,
    compact: Boolean,          // Media: an opened file shows only its action buttons
    showThumb: Boolean,
    siteBadge: String?,
    onToggleSelect: () -> Unit,
    onHoldSelect: () -> Unit,        // long press: starts select mode with this file ticked
    onToggleExpand: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onSwipeDelete: () -> Unit,
    onMove: (() -> Unit)?,
    onVault: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val longDate = remember { SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()) }
    val shortDate = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val fmt = runCatching { Format.valueOf(item.format) }.getOrDefault(Format.MP3)
    val q = if (item.isDeviceFile) tr("Device file", "ملف الجهاز") else qualityLabel(fmt, item.quality)
    val open = isExpanded && !selectMode
    val haptic = LocalHapticFeedback.current
    val pad = PaddingValues(horizontal = 6.dp)

    fun play() {
        if (fileExists(ctx, item)) vm.playNow(listOf(item))
        else toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
    }

    // swipe right = delete now, swipe left = add to Favorites. The row always springs back; the action runs on release.
    val swipeDelete by rememberUpdatedState(onSwipeDelete)
    val entry = item.toEntry()
    val swipeFav by rememberUpdatedState<() -> Unit> {
        val already = Library.favorites.value.any { it.entry.syncKey == entry.syncKey }
        if (already) toast(ctx, L.t("Already in Favorites", "موجود بالفعل في المفضلة"))
        else {
            Library.toggleFavorite(entry)
            toast(ctx, L.t("Added to Favorites", "تمت الإضافة إلى المفضلة"))
        }
    }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> swipeDelete()
                SwipeToDismissBoxValue.EndToStart -> swipeFav()
                else -> {}
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !selectMode,
        enableDismissFromEndToStart = !selectMode,
        backgroundContent = {
            val dir = swipeState.dismissDirection
            val del = dir == SwipeToDismissBoxValue.StartToEnd
            val fav = dir == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                    .background(if (del) Red else if (fav) HeartColor else Color.Transparent)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (del) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (del) Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White)
                if (fav) Sym(R.drawable.ic_heart, 24.dp, tint = Color.White)
            }
        },
    ) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectMode) onToggleSelect() else onToggleExpand() },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (selectMode) onToggleSelect() else onHoldSelect() },
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ---- the compact line ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectMode) Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                Box(Modifier.padding(end = 10.dp).size(90.dp).clip(RoundedCornerShape(Vv.rSmall))) {
                    when {
                        item.format == "MP4" && showThumb -> VideoThumb(item, Modifier.fillMaxSize())
                        item.format == "MP4" -> Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Sym(R.drawable.ic_play, 24.dp, tint = Muted) }
                        else -> TrackArtImage(item, Modifier.fillMaxSize(), iconSize = 30.dp)
                    }
                    if (!selectMode) {
                        IconButton(
                            onClick = ::play,
                            modifier = Modifier.align(Alignment.Center).size(30.dp)
                                .background(Color.Black.copy(alpha = 0.48f), CircleShape),
                        ) {
                            Sym(R.drawable.ic_play, 18.dp, tint = Color.White)
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        prettyName(item.name), fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        maxLines = if (open) 3 else 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (siteBadge != null) {
                            Box(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                                Text(siteBadge, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("${if (item.isDeviceFile) item.name.substringAfterLast('.', item.format).uppercase() else item.format} • $q",
                            color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("${siteBadge ?: siteOfItem(item)} • ${formatSize(item.sizeBytes)} • ${shortDate.format(item.timeMillis)}",
                        color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!selectMode) {
                    HeartButton(item.toEntry(), 36.dp)
                    // the standard "More options" three-dot menu
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                            Sym(R.drawable.ic_more, 22.dp, tint = Muted)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Share", "مشاركة")) },
                                leadingIcon = { Sym(R.drawable.ic_share, 20.dp) },
                                onClick = { menu = false; onShare() },
                            )
                            if (onMove != null) DropdownMenuItem(
                                text = { Text(tr("Move to folder", "نقل إلى مجلد")) },
                                leadingIcon = { Sym(R.drawable.ic_folder, 20.dp) },
                                onClick = { menu = false; onMove() },
                            )
                            if (onVault != null) DropdownMenuItem(
                                text = { Text(tr("Move to vault", "نقل إلى الخزنة")) },
                                leadingIcon = { Sym(R.drawable.ic_lock, 20.dp) },
                                onClick = { menu = false; onVault() },
                            )
                            if (compact) DropdownMenuItem(
                                text = { Text(tr("File Location On Device", "موقع الملف على الجهاز")) },
                                leadingIcon = { Sym(R.drawable.ic_location, 20.dp) },
                                onClick = { menu = false; openLocation(ctx, item) },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Delete", "حذف"), color = Red) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(20.dp)) },
                                onClick = { menu = false; onRemove() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (open) tr("Hide details", "إخفاء التفاصيل") else tr("Details", "التفاصيل")) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = { menu = false; onToggleExpand() },
                            )
                        }
                    }
                }
            }

            // ---- all the details ----
            if (open) {
                if (showThumb && !compact) {
                    Spacer(Modifier.height(8.dp))
                    VideoThumb(
                        item,
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).clickable { play() },
                    )
                }
                if (!compact) Spacer(Modifier.height(6.dp))
                if (!compact) Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                if (!compact) Spacer(Modifier.height(10.dp))

                if (!compact) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailLine(tr("Format", "الصيغة"), if (item.isDeviceFile) item.name.substringAfterLast('.', item.format).uppercase() else item.format)
                    DetailLine(tr("Quality", "الجودة"), q)
                    DetailLine(tr("Size", "الحجم"), formatSize(item.sizeBytes))
                    DetailLine(if (item.isDeviceFile) tr("Added to phone", "تاريخ الإضافة للهاتف") else tr("Downloaded", "تاريخ التنزيل"), longDate.format(item.timeMillis))
                    DetailLine(tr("Saved to", "مكان الحفظ"), item.location)
                    if (item.sourceUrl.isNotBlank()) DetailLine(tr("Link", "الرابط"), item.sourceUrl)
                }

                if (compact) {
                    // Media tab: a round Play button in the centre, Share and Add to playlist beside it
                    var addOpenC by remember(item.uri) { mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                        Column(
                            Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { onShare() }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Sym(R.drawable.ic_share, 26.dp, tint = MaterialTheme.colorScheme.onSurface) }
                            Text(tr("Share", "مشاركة"), fontSize = 12.sp, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).padding(vertical = 4.dp)) {
                            FilledIconButton(onClick = { play() }, modifier = Modifier.size(56.dp)) {
                                Sym(R.drawable.ic_play, 30.dp, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            Text(tr("Play", "تشغيل"), fontSize = 12.sp, maxLines = 1)
                        }
                        Column(
                            Modifier.width(96.dp).clip(RoundedCornerShape(12.dp)).clickable { addOpenC = true }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Sym(R.drawable.ic_queue_music, 26.dp, tint = MaterialTheme.colorScheme.onSurface) }
                            Text(tr("Add to playlist", "إضافة لقائمة"), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    if (addOpenC) AddToPlaylistDialog(
                        listOf(item.toEntry()),
                        onNowPlaying = {
                            val n = vm.addToQueue(listOf(item))
                            toast(
                                ctx,
                                if (n > 0) L.t("Added to Now playing", "تمت الإضافة إلى قائمة التشغيل الحالية")
                                else L.t("Already in Now playing", "موجود بالفعل في قائمة التشغيل الحالية"),
                            )
                        },
                    ) { addOpenC = false }
                } else {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { play() },
                        contentPadding = pad,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_play, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Play", "تشغيل"), fontSize = 13.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onShare,
                        contentPadding = pad,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_share, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Share", "مشاركة"), fontSize = 13.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { openLocation(ctx, item) },
                        contentPadding = pad,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_location, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Location", "الموقع"), fontSize = 13.sp, maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { openLink(ctx, item) },
                        enabled = item.sourceUrl.isNotBlank(),
                        contentPadding = pad,
                        modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_link, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Go to link", "فتح الرابط"), fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = {
                            val n = vm.addToQueue(listOf(item))
                            toast(
                                ctx,
                                if (n > 0) L.t("Added to Now playing", "تمت الإضافة إلى قائمة التشغيل الحالية")
                                else L.t("Already in Now playing", "موجود بالفعل في قائمة التشغيل الحالية"),
                            )
                        },
                        contentPadding = pad,
                        modifier = Modifier.weight(1f),
                    ) {
                        Sym(R.drawable.ic_queue_add, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Now playing", "التشغيل الحالي"), fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(
                        onClick = onRemove,
                        contentPadding = pad,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Remove", "إزالة"), fontSize = 12.sp, maxLines = 1)
                    }
                }

                // ---- favorites / playlists ----
                var addOpen by remember(item.uri) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) HeartButton(item.toEntry(), 40.dp)
                    TextButton(onClick = { addOpen = true }, contentPadding = pad) {
                        Sym(R.drawable.ic_queue_music, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Add to playlist", "إضافة إلى قائمة تشغيل"), fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (addOpen) AddToPlaylistDialog(listOf(item.toEntry())) { addOpen = false }
                }

                // ---- download this link again, in another format / quality ----
                if (!compact && item.sourceUrl.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("Download this link again", "تنزيل هذا الرابط مجددًا"), color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))

                    var dlFormat by remember(item.uri) { mutableStateOf(fmt) }
                    var dlQuality by remember(item.uri) { mutableIntStateOf(item.quality) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DropdownPicker(
                            label = dlFormat.name,
                            options = Format.values().toList(),
                            optionLabel = { f -> formatOptionLabel(f) },
                            onSelect = { dlFormat = it; dlQuality = defaultQuality(it) },
                            enabled = true,
                        )
                        DropdownPicker(
                            label = qualityLabel(dlFormat, dlQuality),
                            options = qualityOptions(dlFormat),
                            optionLabel = { v -> qualityLabel(dlFormat, v) },
                            onSelect = { dlQuality = it },
                            enabled = true,
                        )
                        Button(
                            onClick = { vm.downloadLink(item.sourceUrl, dlFormat, dlQuality) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Sym(R.drawable.ic_download, 18.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Download", "تنزيل"), fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
    }   // SwipeToDismissBox
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(92.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Grid view of a media file: preview on top, name, details and a menu. Tap plays (or ticks in select mode); hold starts selecting. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryGridCard(
    item: HistoryItem,
    vm: DownloaderViewModel,
    selectMode: Boolean,
    isSelected: Boolean,
    showThumb: Boolean,
    source: String?,
    movable: Boolean,
    onToggleSelect: () -> Unit,
    onHoldSelect: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onMove: (() -> Unit)?,
    onVault: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val fmt = runCatching { Format.valueOf(item.format) }.getOrDefault(Format.MP3)
    val q = if (item.isDeviceFile) tr("Device file", "ملف الجهاز") else qualityLabel(fmt, item.quality)
    fun play() {
        if (fileExists(ctx, item)) vm.playNow(listOf(item))
        else toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selectMode) onToggleSelect() else play() },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (selectMode) onToggleSelect() else onHoldSelect() },
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(90.dp).clip(RoundedCornerShape(Vv.rSmall))) {
                when {
                    item.format == "MP4" && showThumb -> VideoThumb(item, Modifier.fillMaxSize())
                    item.format == "MP4" -> Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Sym(R.drawable.ic_play, 24.dp, tint = Muted) }
                    else -> TrackArtImage(item, Modifier.fillMaxSize(), iconSize = 30.dp)
                }
                if (selectMode) Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() }, modifier = Modifier.align(Alignment.TopStart))
            }
            Text(prettyName(item.name), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(source, item.format, q).joinToString(" • "), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatSize(item.sizeBytes)} • ${SimpleDateFormat("MMM d", Locale.getDefault()).format(item.timeMillis)}",
                color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!selectMode) Row(verticalAlignment = Alignment.CenterVertically) {
                HeartButton(item.toEntry(), 34.dp)
                Spacer(Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) { Sym(R.drawable.ic_more, 20.dp, tint = Muted) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("Share", "مشاركة")) }, leadingIcon = { Sym(R.drawable.ic_share, 20.dp) }, onClick = { menu = false; onShare() })
                        if (onMove != null) DropdownMenuItem(text = { Text(tr("Move to folder", "نقل إلى مجلد")) }, leadingIcon = { Sym(R.drawable.ic_folder, 20.dp) }, onClick = { menu = false; onMove() })
                        if (onVault != null) DropdownMenuItem(text = { Text(tr("Move to vault", "نقل إلى الخزنة")) }, leadingIcon = { Sym(R.drawable.ic_lock, 20.dp) }, onClick = { menu = false; onVault() })
                        if (movable) DropdownMenuItem(text = { Text(tr("File Location On Device", "موقع الملف على الجهاز")) }, leadingIcon = { Sym(R.drawable.ic_location, 20.dp) }, onClick = { menu = false; openLocation(ctx, item) })
                        DropdownMenuItem(
                            text = { Text(tr("Delete", "حذف"), color = Red) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(20.dp)) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
        }
    }
}

/** A preview frame of the downloaded video file. */
@Composable
private fun VideoThumb(item: HistoryItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { VideoThumbs.load(ctx, item.uri) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Sym(R.drawable.ic_play, 20.dp, tint = Muted)
        }
    }
}

/**
 * "Sliders" view: ONE page with a row of Audio and a row of Video, both swiping sideways, in the same card style as the
 * VV dashboard. Tapping a card plays it (a row plays on as a queue).
 */
@Composable
private fun SlidersView(items: List<HistoryItem>, vm: DownloaderViewModel, modifier: Modifier) {
    val ctx = LocalContext.current
    val audio = remember(items) { items.filter { it.format == "MP3" } }
    val video = remember(items) { items.filter { it.format == "MP4" } }

    fun playFrom(list: List<HistoryItem>, index: Int) {
        val item = list[index]
        if (!fileExists(ctx, item)) {
            toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
            return
        }
        // the whole row becomes the queue, starting at the tapped card; files that are gone are skipped
        val playable = list.filter { fileExists(ctx, it) }
        vm.playNow(playable, playable.indexOf(item).coerceAtLeast(0))
    }

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Vv.lg), contentPadding = PaddingValues(bottom = Vv.xl)) {
        if (audio.isNotEmpty()) item(key = "row-audio") {
            Column(verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
                VvSectionHeader(tr("Audio", "صوت") + " (${audio.size})", modifier = Modifier.padding(horizontal = 24.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(Vv.md)) {
                    itemsIndexed(audio, key = { _, m -> "a:" + rowKey(m) }) { i, m ->
                        MediaCard(m, subtitle = formatSize(m.sizeBytes)) { playFrom(audio, i) }
                    }
                }
            }
        }
        if (video.isNotEmpty()) item(key = "row-video") {
            Column(verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
                VvSectionHeader(tr("Video", "فيديو") + " (${video.size})", modifier = Modifier.padding(horizontal = 24.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(Vv.md)) {
                    itemsIndexed(video, key = { _, m -> "v:" + rowKey(m) }) { i, m ->
                        MediaCard(m, subtitle = formatSize(m.sizeBytes)) { playFrom(video, i) }
                    }
                }
            }
        }
        if (audio.isEmpty() && video.isEmpty()) item(key = "row-empty") {
            VvEmptyState(
                title = tr("Nothing here yet", "لا يوجد شيء هنا بعد"),
                message = tr("Saved and imported songs and videos appear here, in rows you can swipe.", "تظهر هنا الأغاني والفيديوهات المحفوظة والمستوردة في صفوف يمكنك تمريرها."),
            )
        }
    }
}
