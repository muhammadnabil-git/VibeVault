package com.example.ytsaver

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** First tab (Search): search by video name or link, plus shortcuts that open video apps in the in-app browser. */
@Composable
fun FeedTab(vm: DownloaderViewModel) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val previewOn by vm.searchPreview.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- search bar ----
        item {
            OutlinedTextField(
                value = feed.query,
                onValueChange = { v ->
                    val grew = v.length - feed.query.length
                    vm.setFeedQuery(v)
                    // a link pasted with the keyboard's own Paste starts the search straight away
                    if (grew > 8 && parseLinks(v).isNotEmpty()) { focus.clearFocus(); vm.searchFeed() }
                },
                singleLine = true,
                placeholder = { Text(tr("Search by video name or paste a link", "ابحث باسم الفيديو أو الصق رابطًا")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Muted) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (feed.query.isNotEmpty()) {
                            IconButton(onClick = vm::clearFeed) {
                                Icon(Icons.Filled.Close, contentDescription = tr("Clear", "مسح"), tint = Muted)
                            }
                        }
                        TextButton(
                            onClick = {
                                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                    vm.setFeedQuery(it)
                                    focus.clearFocus()
                                    vm.searchFeed()      // paste = search right away, no Enter needed
                                }
                            },
                        ) { Text(tr("Paste", "لصق")) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.searchFeed() }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- website shortcuts (editable) ----
        item { ShortcutsSection(vm) }

        // ---- one chip per website: All together, or one website at a time ----
        if (feed.searched && feed.siteState.isNotEmpty()) {
            item { SiteChips(feed, vm) }
        }

        // ---- results ----
        if (feed.loading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Searching…", "جارٍ البحث…"), color = Muted)
                }
            }
        } else if (feed.message.isNotBlank()) {
            item { Text(feed.message, color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        } else if (!feed.searched) {
            item {
                Text(
                    tr(
                        "Type a video or song name to search ${SEARCH_SITES.joinToString(", ") { it.label }} at the same time, or paste any video link. The websites are chosen in Settings → Behavior → Search engines.",
                        "اكتب اسم فيديو أو أغنية للبحث في ${SEARCH_SITES.joinToString("، ") { it.label }} في وقت واحد، أو الصق أي رابط فيديو. تُختار المواقع من الإعدادات ← السلوك ← محركات البحث.",
                    ),
                    color = Muted, fontSize = 13.sp,
                )
            }
        }
        val shown = feed.siteFilter?.let { f -> feed.siteResults[f].orEmpty() } ?: feed.items
        items(shown, key = { it.url }) { item ->
            FeedResult(
                item = item,
                previewEnabled = previewOn,
                feed = feed,
                onPlay = { vm.playFeedPreview(item.url) },
                onStop = vm::stopFeedPreview,
                onDownload = { vm.sendToDownload(item.url) },
            )
        }

        // ---- more results (the page size is set in Settings → Behavior) ----
        val hasMore = feed.siteFilter?.let { feed.siteMore[it] == true } ?: feed.siteMore.values.any { it }
        if (feed.searched && !feed.loading && shown.isNotEmpty() && (hasMore || feed.loadingMore)) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (feed.loadingMore) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = vm::loadMoreFeed, shape = RoundedCornerShape(12.dp)) {
                            Text(tr("Show more results", "عرض المزيد من النتائج"))
                        }
                    }
                }
            }
        }
    }
}

fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').lowercase()

/** Accepts "rumble.com" or a full address; null when it can't be a website. */
fun cleanUrl(input: String): String? {
    val t = input.trim()
    if (t.isEmpty() || t.contains(' ')) return null
    val full = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    val host = hostOf(full)
    return if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.')) full else null
}

/** The row of up to 5 website shortcuts, with Edit mode: add, change, remove, reset. */
@Composable
private fun ShortcutsSection(vm: DownloaderViewModel) {
    val shortcuts by vm.shortcuts.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var dialogIndex by remember { mutableStateOf<Int?>(null) }    // index being edited, or -1 for a new one

    // drag and drop (edit mode): which website is being dragged and how far it has moved sideways
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var slotPx by remember { mutableFloatStateOf(0f) }
    /** The place the dragged website would take if it were dropped now (the row is mirrored in Arabic). */
    fun dropTarget(): Int {
        if (dragFrom < 0 || slotPx <= 0f) return dragFrom
        val moved = (dragX / slotPx).roundToInt() * (if (rtl) -1 else 1)
        return (dragFrom + moved).coerceIn(0, (shortcuts.size - 1).coerceAtLeast(0))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Browse video websites", "تصفّح مواقع الفيديو"), color = Muted, fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                TextButton(onClick = vm::resetShortcuts, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(tr("Reset", "إعادة ضبط"), fontSize = 13.sp)
                }
            }
            TextButton(onClick = { editing = !editing }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (editing) tr("Done", "تم") else tr("Edit", "تعديل"), fontSize = 13.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().onSizeChanged { slotPx = it.width / MAX_SHORTCUTS.toFloat() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shortcuts.forEachIndexed { i, sc ->
                val target = dropTarget()
                val dir = if (rtl) -1f else 1f
                // while another website is dragged, the ones it passes over slide aside to make room
                val aside = when {
                    dragFrom < 0 || i == dragFrom -> 0f
                    dragFrom < target && i in (dragFrom + 1)..target -> -slotPx * dir
                    target < dragFrom && i in target until dragFrom -> slotPx * dir
                    else -> 0f
                }
                val animatedAside by animateFloatAsState(aside, label = "shortcutAside")
                val dragging = dragFrom == i
                ShortcutTile(
                    sc, editing,
                    Modifier.weight(1f)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (dragging) dragX else animatedAside
                            val scale = if (dragging) 1.12f else 1f
                            scaleX = scale
                            scaleY = scale
                            alpha = if (dragging) 0.92f else 1f
                        }
                        .then(
                            if (editing) Modifier.pointerInput(i, shortcuts.size) {
                                detectDragGestures(
                                    onDragStart = { dragFrom = i; dragX = 0f },
                                    onDrag = { change, amount -> change.consume(); dragX += amount.x },
                                    onDragEnd = {
                                        val to = dropTarget()
                                        val from = dragFrom
                                        dragFrom = -1
                                        dragX = 0f
                                        if (from >= 0 && to != from) vm.moveShortcutTo(from, to)     // saved at once
                                    },
                                    onDragCancel = { dragFrom = -1; dragX = 0f },
                                )
                            } else Modifier,
                        ),
                    onClick = { if (editing) dialogIndex = i else vm.openBrowser(sc.url) },
                    onRemove = { vm.removeShortcut(i) },
                )
            }
            if (editing && shortcuts.size < MAX_SHORTCUTS) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { dialogIndex = -1 }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) { Text("+", fontSize = 28.sp, color = Muted) }
                    Text(tr("Add", "إضافة"), fontSize = 10.sp, maxLines = 1)
                }
            }
            val used = shortcuts.size + if (editing && shortcuts.size < MAX_SHORTCUTS) 1 else 0
            repeat(MAX_SHORTCUTS - used) { Spacer(Modifier.weight(1f)) }
        }
        if (shortcuts.isEmpty() && !editing) {
            Text(
                tr("No shortcuts. Tap Edit to add your favourite video websites.",
                    "لا توجد اختصارات. اضغط تعديل لإضافة مواقع الفيديو المفضلة."),
                color = Muted, fontSize = 12.sp,
            )
        }
        if (editing) {
            Text(
                tr("Tap a website to change it, ✕ to remove it, or drag it left or right to rearrange. Up to $MAX_SHORTCUTS.",
                    "اضغط على موقع لتغييره، ✕ لحذفه، أو اسحبه يمينًا أو يسارًا لإعادة الترتيب. حتى $MAX_SHORTCUTS."),
                color = Muted, fontSize = 12.sp,
            )
        }
    }

    val di = dialogIndex
    if (di != null) {
        ShortcutDialog(
            existing = shortcuts.getOrNull(di),
            onSave = { vm.saveShortcut(di, it); dialogIndex = null },
            onDismiss = { dialogIndex = null },
        )
    }
}

/** Add / change one website (name + address). Also used by Settings. */
@Composable
fun ShortcutDialog(existing: Shortcut?, onSave: (Shortcut) -> Unit, onDismiss: () -> Unit) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var address by remember(existing) { mutableStateOf(existing?.url ?: "") }
    var bad by remember(existing) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) tr("Add website", "إضافة موقع") else tr("Edit website", "تعديل الموقع")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(tr("Name", "الاسم")) },
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it; bad = false }, singleLine = true,
                    label = { Text(tr("Website address", "عنوان الموقع")) },
                    placeholder = { Text("likee.video") },
                    isError = bad,
                    supportingText = if (bad) { { Text(tr("Enter a valid address", "أدخل عنوانًا صحيحًا")) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val u = cleanUrl(address)
                if (u == null) bad = true
                else onSave(Shortcut(name.trim().ifEmpty { hostOf(u).removePrefix("www.").removePrefix("m.") }, u))
            }) { Text(tr("Save", "حفظ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
private fun ShortcutTile(sc: Shortcut, editing: Boolean, modifier: Modifier, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShortcutArt(sc)
                Text(sc.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (editing) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Red).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Branded art for the well-known sites; any other site gets a coloured tile with its first letter. */
@Composable
private fun ShortcutArt(sc: Shortcut) {
    val host = remember(sc.url) { hostOf(sc.url) }
    val tile = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
    when {
        host.contains("youtube.") || host.contains("youtu.be") ->
            Box(tile.background(Color(0xFFFF0000)), contentAlignment = Alignment.Center) {
                Sym(R.drawable.ic_play, 32.dp, tint = Color.White)
            }
        host.contains("soundcloud.") -> Box(tile.background(Color(0xFFFF5500)), contentAlignment = Alignment.Center) {
            // the SoundCloud cloud: three round bumps on a flat base
            Canvas(Modifier.size(34.dp, 22.dp)) {
                val w = size.width
                val h = size.height
                drawCircle(Color.White, radius = h * 0.30f, center = Offset(w * 0.22f, h * 0.70f))
                drawCircle(Color.White, radius = h * 0.42f, center = Offset(w * 0.50f, h * 0.52f))
                drawCircle(Color.White, radius = h * 0.32f, center = Offset(w * 0.78f, h * 0.66f))
                drawRect(Color.White, topLeft = Offset(w * 0.22f, h * 0.58f), size = Size(w * 0.56f, h * 0.42f))
                drawRoundRect(
                    Color.White, topLeft = Offset(0f, h * 0.68f), size = Size(w * 0.14f, h * 0.32f),
                    cornerRadius = CornerRadius(w * 0.03f),
                )
            }
        }
        host.contains("instagram.") -> Box(
            tile.background(Brush.linearGradient(listOf(Color(0xFFF9A03F), Color(0xFFDD2A7B), Color(0xFF6B3FD4)))),
            contentAlignment = Alignment.Center,
        ) {
            // the Instagram camera: rounded square, lens, flash dot
            Canvas(Modifier.size(30.dp)) {
                val w = size.width
                val sw = w * 0.09f
                drawRoundRect(
                    Color.White, topLeft = Offset(sw / 2, sw / 2), size = Size(w - sw, w - sw),
                    cornerRadius = CornerRadius(w * 0.28f), style = Stroke(sw),
                )
                drawCircle(Color.White, radius = w * 0.22f, center = center, style = Stroke(sw))
                drawCircle(Color.White, radius = sw * 0.7f, center = Offset(w * 0.76f, w * 0.24f))
            }
        }
        host.contains("spotify.") -> Box(tile.background(Color(0xFF191414)), contentAlignment = Alignment.Center) {
            // the Spotify mark: a green disc with three curved bars
            Canvas(Modifier.size(34.dp)) {
                val w = size.width
                drawCircle(Color(0xFF1DB954), radius = w / 2)
                listOf(0.33f to 0.80f, 0.50f to 0.64f, 0.66f to 0.48f).forEach { (y, half) ->
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * (0.5f - half / 2), w * y)
                        quadraticBezierTo(w * 0.5f, w * (y - 0.09f), w * (0.5f + half / 2), w * y)
                    }
                    drawPath(p, Color(0xFF191414), style = Stroke(w * 0.085f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        host.contains("anghami.") -> Box(
            tile.background(Brush.linearGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)))),
            contentAlignment = Alignment.Center,
        ) {
            // Anghami: a white "a" with sound waves
            Text("a", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        }
        host.contains("tiktok.") -> Box(tile.background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                "♪", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color(0xFF25F4EE), Offset(-4f, 3f), 0f)),
            )
        }
        host.contains("facebook.") || host.contains("fb.com") ->
            Box(tile.background(Color(0xFF1877F2)), contentAlignment = Alignment.Center) {
                Text("f", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            }
        host.contains("shazam.") -> Box(
            tile.background(Brush.linearGradient(listOf(Color(0xFF1FB6FF), Color(0xFF0A5BFF)))),
            contentAlignment = Alignment.Center,
        ) {
            // the Shazam mark: a white "S" (two interlocking arcs) on blue
            Text("S", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        }
        host.contains("dailymotion.") -> Box(tile.background(Color(0xFF0066DC)), contentAlignment = Alignment.Center) {
            Text("d", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        else -> {
            val palette = listOf(0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFF0D9488, 0xFFEA580C, 0xFF4F46E5)
            val color = when {
                host.contains("ted.com") -> Color(0xFFE62B1E)
                host.contains("mixcloud.") -> Color(0xFF52AAD8)
                host.contains("bandcamp.") -> Color(0xFF1DA0C3)
                host.contains("archive.org") -> Color(0xFF3A3A3A)
                host.contains("kwai.") -> Color(0xFFFF5000)
                host.contains("likee.") -> Color(0xFF7B2FF7)
                host.contains("bilibili.") -> Color(0xFFFB7299)
                else -> Color(palette[Math.abs(host.hashCode()) % palette.size])
            }
            val letter = sc.name.trim().firstOrNull()?.uppercase() ?: host.firstOrNull()?.uppercase() ?: "?"
            Box(tile.background(color), contentAlignment = Alignment.Center) {
                Text(letter, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun FeedResult(
    item: FeedItem,
    previewEnabled: Boolean,
    feed: FeedState,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDownload: () -> Unit,
) {
    val previewing = previewEnabled && feed.previewUrl == item.url
    val ctx = LocalContext.current
    // results of a web search come without a length: look it up now, so it shows before the video is played
    LaunchedEffect(item.url) { if (item.durationSec <= 0) DurationCache.ensure(item.url) }
    val durationSec = if (item.durationSec > 0) item.durationSec else DurationCache.get(item.url)
    var addOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (previewing) {
                // the video plays right here, in this card
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        feed.previewLoading -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                            Text(tr("Loading preview…", "جارٍ تحميل المعاينة…"), color = Color.White, fontSize = 12.sp)
                        }
                        feed.previewFailed || feed.previewStream == null -> Text(
                            tr("This video can't be previewed. You can still send it to the Download tab.",
                                "لا يمكن معاينة هذا الفيديو. ما زال بإمكانك إرساله إلى تبويب التنزيل."),
                            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                        else -> InlinePreviewPlayer(feed.previewStream, Modifier.fillMaxSize())
                    }
                    IconButton(onClick = { openCast(ctx) }, modifier = Modifier.align(Alignment.TopStart)) {
                        Sym(R.drawable.ic_tv, 24.dp, tint = Color.White)
                    }
                    IconButton(onClick = onStop, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Close preview", "إغلاق المعاينة"), tint = Color.White)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!previewing) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(10.dp))) {
                        RemoteThumb(url = item.thumbUrl, bitmap = item.thumb, modifier = Modifier.fillMaxSize())
                        val badge = formatDuration(durationSec)
                        if (badge.isNotEmpty()) {
                            Text(
                                badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        if (previewEnabled) {
                            Box(
                                Modifier.fillMaxSize().clickable(onClick = onPlay),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                    contentAlignment = Alignment.Center,
                                ) { Sym(R.drawable.ic_play, 22.dp, tint = Color.White) }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.site.isNotBlank()) Text(item.site, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    if (item.uploader.isNotBlank()) Text(item.uploader, color = Muted, fontSize = 12.sp, maxLines = 1)
                    val d = formatDuration(durationSec)
                    if (d.isNotEmpty()) Text(tr("Duration ", "المدة ") + d, color = Muted, fontSize = 12.sp)
                }
            }
            // like (Favorites), add to a playlist, download: on every result
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeartButton(item.toEntry(), 40.dp)
                OutlinedButton(
                    onClick = { addOpen = true },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Sym(R.drawable.ic_queue_add, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Playlist", "قائمة"), fontSize = 13.sp, maxLines = 1)
                }
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Sym(R.drawable.ic_download, 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Download", "تنزيل"), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
    if (addOpen) AddToPlaylistDialog(listOf(item.toEntry())) { addOpen = false }
}

@Composable
private fun SiteChips(feed: FeedState, vm: DownloaderViewModel) {
    val order = SEARCH_SITES.map { it.label } + LINK_SITE
    // only websites that really returned results get a filter; the ones still searching, failed or empty are left out
    val sites = order.filter { (feed.siteState[it] ?: 0) == 1 && (feed.siteResults[it]?.size ?: 0) > 0 }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = feed.siteFilter == null,
            onClick = { vm.setFeedSite(null) },
            label = { Text(tr("All", "الكل") + " (${feed.items.size})", fontSize = 12.sp) },
        )
        sites.forEach { site ->
            val n = feed.siteResults[site]?.size ?: 0
            FilterChip(
                selected = feed.siteFilter == site,
                onClick = { vm.setFeedSite(site) },
                label = {
                    Text("$site ($n)", fontSize = 12.sp)
                },
            )
        }
    }
}

/**
 * A small player that lives only inside the result card. It is separate from the app's main player,
 * so it never opens another screen, popup or the full player. Released as soon as the card closes.
 */
@OptIn(UnstableApi::class)
@Composable
private fun InlinePreviewPlayer(stream: StreamInfo, modifier: Modifier) {
    val ctx = LocalContext.current
    var failed by remember(stream) { mutableStateOf(false) }
    val player = remember(stream) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        stream.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }
        http.setDefaultRequestProperties(stream.headers.filterKeys { !it.equals("User-Agent", true) })
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx.applicationContext, http))
        val sources = stream.urls.map { factory.createMediaSource(MediaItem.fromUri(it)) }
        ExoPlayer.Builder(ctx.applicationContext)
            // starts playing after half a second of picture instead of the default 2.5 s
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 20_000, 400, 800)
                    .build(),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // picture + sound arrive as two streams on YouTube: play them together
                if (sources.size > 1) setMediaSource(MergingMediaSource(*sources.toTypedArray()))
                else setMediaSource(sources[0])
                prepare()
                playWhenReady = true
            }
    }
    // the screen stays awake while the preview is playing or buffering, and may sleep again when it is paused or closed
    DisposableEffect(player) {
        val window = ctx.findActivity()?.window
        fun keepAwake() {
            val on = player.playWhenReady && (player.isPlaying || player.playbackState == Player.STATE_BUFFERING)
            if (on) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true; keepAwake() }
            override fun onIsPlayingChanged(isPlaying: Boolean) = keepAwake()
            override fun onPlaybackStateChanged(playbackState: Int) = keepAwake()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = keepAwake()
        }
        player.addListener(l)
        keepAwake()
        onDispose {
            player.removeListener(l)
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            player.release()
        }
    }
    var fullscreen by remember(stream) { mutableStateOf(false) }
    if (failed) {
        Text(
            tr("Preview couldn't be played. You can still send it to the Download tab.",
                "تعذّر تشغيل المعاينة. ما زال بإمكانك إرساله إلى تبويب التنزيل."),
            color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = modifier.padding(16.dp),
        )
    } else {
        AndroidView(
            factory = { c ->
                (android.view.LayoutInflater.from(c).inflate(R.layout.preview_player_view, null) as PlayerView).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                    setFullscreenButtonClickListener { fullscreen = true }   // shows the fullscreen button
                }
            },
            // while fullscreen the picture belongs to the fullscreen view; the card takes it back afterwards
            update = { it.player = if (fullscreen) null else player },
            modifier = modifier,
        )
        if (fullscreen) FullscreenVideoDialog(player) { fullscreen = false }
    }
}
