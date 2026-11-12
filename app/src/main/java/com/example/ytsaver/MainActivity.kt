package com.example.ytsaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: DownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveWallpaper.init(applicationContext)
        SearchEngines.init(applicationContext)
        handleIntent(intent, coldStart = true)
        setContent {
            val ar by vm.ar.collectAsStateWithLifecycle()
            val light by vm.lightMode.collectAsStateWithLifecycle()
            val themeId by vm.theme.collectAsStateWithLifecycle()
            val liveBg by vm.liveBackground.collectAsStateWithLifecycle()
            val job by vm.state.collectAsStateWithLifecycle()
            val playing by PlayerHolder.playing.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalAr provides ar,
                LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme(
                    colorScheme = animatedScheme(if (themeId == THEME_WALLPAPER) wallpaperScheme(light) else schemeFor(themeId, light)),
                    shapes = Shapes(
                        extraSmall = RoundedCornerShape(10.dp),
                        small = RoundedCornerShape(14.dp),
                        medium = RoundedCornerShape(18.dp),
                        large = RoundedCornerShape(24.dp),
                        extraLarge = RoundedCornerShape(30.dp),
                    ),
                ) {
                    SystemBarsEffect(light)
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            // the live picture (Settings > Live pictures), under the animated shapes
                            LivePicture()
                            // frozen while a download or playback runs, so it costs no battery then
                            if (liveBg) LiveBackground(paused = job.phase == Phase.Working || playing)
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = Vv.maxContentWidth).fillMaxSize()) { App(vm) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, coldStart = false)
    }

    override fun onStart() {
        super.onStart()
        vm.appInForeground = true
        LiveWallpaper.refreshIfDue(applicationContext)      // a new live picture when it is due
    }

    override fun onStop() {
        vm.appInForeground = false
        VaultStore.lock(applicationContext)      // the vault locks when the app goes to the background
        super.onStop()
    }

    /** Tapping the "download complete" notification opens the History tab (and the file details for groups). */
    private fun handleIntent(i: Intent?, coldStart: Boolean) {
        val authLink = i?.dataString?.takeIf {
            i?.data?.host == "mediasaver-c3ee7.firebaseapp.com" &&
                i?.data?.path?.startsWith("/__/auth/links") == true
        }
        if (!authLink.isNullOrBlank()) {
            i.data = null
            lifecycleScope.launch {
                try {
                    when (VaultRecovery.completeEmailLink(applicationContext, authLink)) {
                        RecoveryLinkPurpose.SETUP -> {
                            val hasPin = VaultStore.hasPin(applicationContext)
                            if (!hasPin) {
                                if (coldStart) {
                                    VaultStore.screenOpen.value = true
                                    vm.openSettings()
                                }
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    L.t("Recovery email verified. Set your vault PIN.", "تم تأكيد بريد الاسترداد. عيّن رمز الخزنة."),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        RecoveryLinkPurpose.RESET -> {
                            VaultStore.authorizePinReset()
                            vm.openSettings()
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                L.t("Recovery email verified. Choose a new vault PIN.", "تم تأكيد بريد الاسترداد. اختر رمزًا جديدًا للخزنة."),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                        null -> Unit
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        e.message ?: "The recovery link could not be verified.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        if (i != null && i.getBooleanExtra(EXTRA_BT_OPEN, false)) {
            // started by a Bluetooth device connecting: show the player as soon as the first track is ready
            vm.requestOpenPlayerWhenReady()
            i.removeExtra(EXTRA_BT_OPEN)
        }
        // a shortcut of the home-screen widget: open that page, with VV underneath so Back returns to it
        val page = i?.getStringExtra(EXTRA_VV_OPEN)
        if (i != null && page != null) {
            i.removeExtra(EXTRA_VV_OPEN)
            vm.closePlayer(); vm.closeFavorites(); vm.closePlaylists(); vm.closeSettings(); vm.closeBrowser()
            vm.closeRadio(); vm.closeDetector()
            when (page) {
                "identify" -> { vm.setTab(4); vm.openDetector() }
                "radio" -> { vm.setTab(4); vm.openRadio() }
                "search" -> vm.setTab(0)
                "download" -> vm.setTab(1)
                "media" -> vm.setTab(2)
                "history" -> vm.setTab(3)
                else -> vm.setTab(4)
            }
        }
        if (i != null && i.getIntExtra(DownloadService.EXTRA_OPEN_TAB, -1) == 1) {
            vm.closePlayer()
            vm.closeFavorites()
            vm.closePlaylists()
            vm.closeSettings()
            vm.closeBrowser()
            vm.setTab(2)
            if (i.getBooleanExtra(DownloadService.EXTRA_SHOW_DETAILS, false)) vm.showLatestGroupDetails()
            i.removeExtra(DownloadService.EXTRA_OPEN_TAB)
            i.removeExtra(DownloadService.EXTRA_SHOW_DETAILS)
        }
    }
}

/** Status / navigation bar icons and colour follow the light / dark theme. */
@Suppress("DEPRECATION")
@Composable
private fun SystemBarsEffect(light: Boolean) {
    val view = LocalView.current
    val bg = MaterialTheme.colorScheme.background
    SideEffect {
        val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        w.decorView.setBackgroundColor(bg.toArgb())
        w.statusBarColor = bg.toArgb()
        w.navigationBarColor = bg.toArgb()
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
fun App(vm: DownloaderViewModel) {
    val ctx = LocalContext.current
    val tab by vm.tab.collectAsStateWithLifecycle()
    val playerOpen by vm.playerOpen.collectAsStateWithLifecycle()
    val notes by vm.notifications.collectAsStateWithLifecycle()
    val notesOpen by vm.notesOpen.collectAsStateWithLifecycle()
    val expandId by vm.expandId.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val queue by PlayerHolder.entries.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val radioOpen by vm.radioOpen.collectAsStateWithLifecycle()
    val detectorOpen by vm.detectorOpen.collectAsStateWithLifecycle()
    val browserUrl by vm.browserUrl.collectAsStateWithLifecycle()
    val job by vm.state.collectAsStateWithLifecycle()
    val favoritesOpen by vm.favoritesOpen.collectAsStateWithLifecycle()
    val playlistsOpen by vm.playlistsOpen.collectAsStateWithLifecycle()
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    // the (i) button is shown once after the first install and once after every update; opening it hides it for good
    val appVersion = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var showGuideButton by remember { mutableStateOf(Prefs.str(ctx, "guide_seen_version") != appVersion) }

    // "Removed – Undo" after a removal from the Media / History lists
    val undo by vm.undo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(undo?.id) {
        val u = undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(u.message, actionLabel = L.t("Undo", "تراجع"), duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) vm.performUndo() else vm.clearUndo()
    }

    // start / stop the Bluetooth watcher to match the saved settings (also after the phone restarts)
    LaunchedEffect(Unit) { vm.refreshBluetooth() }

    // First run (and Settings → Permissions): the permissions are asked for one by one with the system's own dialogs
    val permSetup by vm.permSetup.collectAsStateWithLifecycle()
    if (permSetup != 0) PermissionSetupDialog(review = permSetup == 2, onFinish = vm::closePermissionSetup)

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        if (playerOpen) {
            PlayerScreen(vm, onClose = vm::closePlayer)
        } else if (browserUrl != null) {
            BrowserScreen(vm, browserUrl.orEmpty(), onClose = vm::closeBrowser)
        } else if (radioOpen) {
            RadioScreen(vm, onClose = vm::closeRadio)
            if (queue.isNotEmpty() && !Radio.isRadioPlaying()) MiniPlayer(onOpen = vm::openPlayer, onStop = vm::stopPlayback)
        } else if (detectorOpen) {
            DetectorScreen(vm, onClose = vm::closeDetector)
        } else if (settingsOpen) {
            SettingsScreen(vm, onClose = vm::closeSettings)
        } else if (favoritesOpen || playlistsOpen) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (favoritesOpen) FavoritesScreen(vm, onClose = vm::closeFavorites)
                else PlaylistsScreen(vm, onClose = vm::closePlaylists)
            }
            if (queue.isNotEmpty()) MiniPlayer(onOpen = vm::openPlayer, onStop = vm::stopPlayback)
            BottomTabs(favoritesOpen, playlistsOpen, vm)
        } else if (tab == 4) {
            // ---- VV = the one main screen: header + dashboard. Search / Download / Media / History open from it.
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                    Text("VibeVault", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    // (i) = the full guide of the app's features
                    if (showGuideButton) IconButton(
                        onClick = {
                            Prefs.putStr(ctx, "guide_seen_version", appVersion)
                            showGuideButton = false
                            guideOpen = true
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = tr("App guide", "دليل التطبيق"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // melody + lens = Music Detector, radio = FM Radio, bell = notifications, gear = settings
                    // (favorites and playlists are the two tabs at the bottom)
                    IconButton(onClick = vm::openDetector, modifier = Modifier.size(34.dp)) {
                        Sym(R.drawable.ic_music_search, 22.dp, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = vm::openRadio, modifier = Modifier.size(34.dp)) {
                        Sym(R.drawable.ic_radio, 22.dp, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = vm::openNotifications, modifier = Modifier.size(34.dp)) {
                        val unread = notes.count { !it.read }
                        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
                            Icon(Icons.Filled.Notifications, contentDescription = tr("Notifications", "الإشعارات"))
                        }
                    }
                    IconButton(onClick = vm::openSettings, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Settings, contentDescription = tr("Settings", "الإعدادات"))
                    }
                }
            }
            HorizontalDivider(color = Muted.copy(alpha = 0.25f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LogoWatermark()
                HomeTab(vm)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (queue.isNotEmpty()) {
                MiniPlayer(onOpen = vm::openPlayer, onStop = vm::stopPlayback)
            }
            if (Prefs.showHomeLibraryTabs(ctx)) BottomTabs(false, false, vm)
        } else {
            // ---- a page opened from VV: its own window with a back arrow; Back (button or gesture) returns to VV
            BackHandler { vm.setTab(4) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.setTab(4) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع"))
                }
                Text(
                    when (tab) {
                        0 -> tr("Search", "بحث")
                        1 -> tr("Download", "التنزيل")
                        2 -> tr("Media", "الوسائط")
                        else -> tr("History", "السجل")
                    },
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f),
                )
                VvLogo(28.dp)
                Spacer(Modifier.width(8.dp))
            }
            HorizontalDivider(color = Muted.copy(alpha = 0.25f))

            // a download started from another page stays visible here; tap it to see the details
            if (job.phase == Phase.Working && tab != 1) {
                Column(
                    Modifier.fillMaxWidth().clickable { vm.setTab(1) }.padding(horizontal = 24.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(job.message.replace("\n", " "), color = Muted, fontSize = 12.sp, maxLines = 1)
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LogoWatermark()
                when (tab) {
                    0 -> FeedTab(vm)
                    1 -> DownloadTab(vm)
                    2 -> MediaTab(vm)
                    else -> HistoryTab(vm)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (queue.isNotEmpty()) {
                MiniPlayer(onOpen = vm::openPlayer, onStop = vm::stopPlayback)
            }
            if (tab == 2 && Prefs.showMediaLibraryTabs(ctx)) BottomTabs(false, false, vm)
        }
    }

    if (guideOpen) FeaturesGuideDialog(onClose = { guideOpen = false })

    if (notesOpen) {
        NotificationsDialog(
            notes = notes,
            startExpanded = expandId,
            onClose = vm::closeNotifications,
            onClear = vm::clearNotifications,
            onUpdateEngine = vm::updateEngine,
            onPlayFile = { uri ->
                val item = history.firstOrNull { it.uri == uri }
                if (item != null && fileExists(ctx, item)) {
                    vm.closeNotifications()
                    vm.playNow(listOf(item))
                } else {
                    toast(ctx, L.t("File not found. It may have been moved or deleted.", "الملف غير موجود. ربما تم نقله أو حذفه."))
                }
            },
        )
    }
}

// ----------------------------------------------------------------------
// Notification bell popup (grouped, expandable entries)
// ----------------------------------------------------------------------

@Composable
fun NotificationsDialog(
    notes: List<AppNotification>,
    startExpanded: Long?,
    onClose: () -> Unit,
    onClear: () -> Unit,
    onUpdateEngine: () -> Unit,
    onPlayFile: (String) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()) }
    var expanded by remember(startExpanded) { mutableStateOf(startExpanded) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Notifications", "الإشعارات"))
                if (notes.isNotEmpty()) TextButton(onClick = onClear) { Text(tr("Clear all", "مسح الكل")) }
            }
        },
        text = {
            if (notes.isEmpty()) {
                Text(tr("No notifications yet", "لا توجد إشعارات بعد"), color = Muted)
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(notes, key = { it.id }) { n ->
                        val accent = when (n.type) {
                            "DONE", "ENGINE_UPDATED" -> Green
                            "FAILED", "ENGINE_UPDATE_FAILED" -> Red
                            "ENGINE_NEEDS_UPDATE" -> Amber
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        val canExpand = n.files.size > 1          // only grouped notifications expand
                        val single = n.files.size == 1          // a single file shows everything right away
                        val isOpen = expanded == n.id
                        Column(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .then(if (canExpand) Modifier.clickable { expanded = if (isOpen) null else n.id } else Modifier)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!n.read) Text("● ", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                Text(n.title, color = accent, fontWeight = FontWeight.SemiBold)
                            }
                            if (!single) Text(n.text, fontSize = 13.sp)
                            if (single) {
                                val f = n.files[0]
                                val q = runCatching { qualityLabel(Format.valueOf(f.format), f.quality) }.getOrDefault("")
                                Text(prettyName(f.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("${f.format} • $q • ${formatSize(f.sizeBytes)}", color = Muted, fontSize = 12.sp)
                            }
                            Text(fmt.format(n.timeMillis), color = Muted, fontSize = 11.sp)
                            if (single) {
                                TextButton(onClick = { onPlayFile(n.files[0].uri) }) { Text(tr("Play", "تشغيل")) }
                            }

                            if (canExpand) {
                                Text(
                                    (if (isOpen) "▲ " else "▼ ") +
                                        tr("${n.files.size} files — tap for details",
                                            "${n.files.size} ملفات — اضغط للتفاصيل"),
                                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                                )
                            }
                            if (isOpen) {
                                n.files.forEach { f ->
                                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Text(prettyName(f.name), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        val q = runCatching { qualityLabel(Format.valueOf(f.format), f.quality) }.getOrDefault("")
                                        Text("${f.format} • $q • ${formatSize(f.sizeBytes)}", color = Muted, fontSize = 12.sp)
                                        TextButton(onClick = { onPlayFile(f.uri) }) { Text(tr("Play", "تشغيل")) }
                                    }
                                }
                            }
                            if (n.type == "ENGINE_NEEDS_UPDATE") {
                                TextButton(onClick = onUpdateEngine) { Text(tr("Update now", "حدّث الآن")) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close", "إغلاق")) } },
    )
}

/**
 * The two tabs at the bottom of the screen: Favorites and Playlists. Tapping the open one again goes back to the
 * main tabs (Search / Download / Media / History).
 */
@Composable
private fun BottomTabs(favoritesOpen: Boolean, playlistsOpen: Boolean, vm: DownloaderViewModel) {
    HorizontalDivider(color = Muted.copy(alpha = 0.25f))
    Row(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)) {
        BottomTab(
            selected = favoritesOpen, label = tr("Favorites", "المفضلة"),
            onClick = { if (favoritesOpen) vm.closeFavorites() else vm.openFavorites() },
        ) { tint -> Sym(if (favoritesOpen) R.drawable.ic_heart else R.drawable.ic_heart_outline, 24.dp, tint = if (favoritesOpen) HeartColor else tint) }
        BottomTab(
            selected = playlistsOpen, label = tr("Playlists", "قوائم التشغيل"),
            onClick = { if (playlistsOpen) vm.closePlaylists() else vm.openPlaylists() },
        ) { tint -> Sym(R.drawable.ic_queue_music, 24.dp, tint = tint) }
    }
}

@Composable
private fun RowScope.BottomTab(selected: Boolean, label: String, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Muted
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(color)
        Text(label, color = color, fontSize = 11.sp, maxLines = 1)
    }
}
