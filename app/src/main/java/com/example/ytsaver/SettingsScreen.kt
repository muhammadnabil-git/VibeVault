package com.example.ytsaver

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The gear page: every editable setting, the language choice, and the About information. */
@Composable
fun SettingsScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val ar by vm.ar.collectAsStateWithLifecycle()
    val defFormat by vm.defaultFormat.collectAsStateWithLifecycle()
    val defQuality by vm.defaultQ.collectAsStateWithLifecycle()
    val autoHistory by vm.autoHistory.collectAsStateWithLifecycle()
    val searchPreview by vm.searchPreview.collectAsStateWithLifecycle()
    val historyThumbs by vm.historyThumbs.collectAsStateWithLifecycle()
    val resumePlayback by vm.resumePlayback.collectAsStateWithLifecycle()
    val undoDelete by vm.undoDelete.collectAsStateWithLifecycle()
    val librarySearch by vm.librarySearch.collectAsStateWithLifecycle()
    val historySearch by vm.historySearch.collectAsStateWithLifecycle()
    val sourceSites by vm.sourceSites.collectAsStateWithLifecycle()
    val embedMeta by vm.embedMeta.collectAsStateWithLifecycle()
    var storageOpen by rememberSaveable { mutableStateOf(false) }
    var behaviorOpen by rememberSaveable { mutableStateOf(false) }      // the Behavior page
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }   // the Appearance page
    val siteDefaults by vm.shortcutDefaults.collectAsStateWithLifecycle()
    val light by vm.lightMode.collectAsStateWithLifecycle()
    val btEnabled by vm.btEnabled.collectAsStateWithLifecycle()
    val btMode by vm.btMode.collectAsStateWithLifecycle()
    val searchLimit by vm.searchLimit.collectAsStateWithLifecycle()
    val rules by Library.btRules.collectAsStateWithLifecycle()
    val playlists by Library.playlists.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    remember { DetectorPrefs.init(ctx); QuotaStore.init(ctx); SearchEngines.init(ctx) }      // the Detector's engine choice and allowance counts
    var ruleDialog by remember { mutableStateOf(false) }
    var schedFor by remember { mutableStateOf<String?>(null) }     // playlist id whose schedule is being edited
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setBtEnabled(true)
        else toast(ctx, L.t("Bluetooth permission is needed for auto-play.", "إذن البلوتوث مطلوب للتشغيل التلقائي."))
    }
    var siteDialog by remember { mutableStateOf<Int?>(null) }      // index being edited, -1 = new
    BackHandler(onBack = { if (behaviorOpen) behaviorOpen = false else if (appearanceOpen) appearanceOpen = false else onClose() })
    if (storageOpen) {
        StorageScreen(vm, onBack = { storageOpen = false })
        return
    }
    val vaultOpen by VaultStore.screenOpen.collectAsStateWithLifecycle()
    if (vaultOpen) {
        VaultScreen(vm, onBack = { VaultStore.screenOpen.value = false })
        return
    }

    // "Open the app and play" needs the "Display over other apps" permission, or Android blocks the app from opening itself
    var canOpenApp by remember { mutableStateOf(BluetoothWatch.canOpenApp(ctx)) }
    var overlayDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) canOpenApp = BluetoothWatch.canOpenApp(ctx) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (overlayDialog) {
        AlertDialog(
            onDismissRequest = { overlayDialog = false },
            title = { Text(tr("Allow the app to open itself", "السماح للتطبيق بفتح نفسه")) },
            text = {
                Text(
                    tr("To open the app when your car or headphones connect, Android needs \"Display over other apps\" turned on for VibeVault. Without it the music still plays, and a notification appears that opens the app when tapped.",
                        "لفتح التطبيق عند اتصال سيارتك أو سماعتك، يحتاج أندرويد إلى تفعيل \"العرض فوق التطبيقات الأخرى\" لـ VibeVault. بدونه تعمل الموسيقى أيضًا، ويظهر إشعار يفتح التطبيق عند الضغط عليه."),
                )
            },
            confirmButton = { TextButton(onClick = { overlayDialog = false; BluetoothWatch.openOverlaySettings(ctx) }) { Text(tr("Allow", "السماح")) } },
            dismissButton = { TextButton(onClick = { overlayDialog = false }) { Text(tr("Not now", "ليس الآن")) } },
        )
    }

    if (ruleDialog) BtRuleDialog(vm, null) { ruleDialog = false }
    schedFor?.let { id -> playlists.firstOrNull { it.id == id }?.let { ScheduleDialog(it) { schedFor = null } } }

    siteDialog?.let { di ->
        ShortcutDialog(
            existing = siteDefaults.getOrNull(di),
            onSave = { vm.saveDefaultShortcut(di, it); siteDialog = null },
            onDismiss = { siteDialog = null },
        )
    }

    Box(Modifier.fillMaxSize()) {
    LogoWatermark()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (behaviorOpen) behaviorOpen = false else if (appearanceOpen) appearanceOpen = false else onClose() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع"))
            }
            Text(if (behaviorOpen) tr("Behavior", "السلوك") else if (appearanceOpen) tr("Appearance", "المظهر") else tr("Settings", "الإعدادات"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (behaviorOpen) {
            // ---- download defaults ----
            SettingsCard(tr("Default Download Option", "خيار التنزيل الافتراضي")) {
                Text(tr("Format", "الصيغة"), color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Format.values().forEach { f ->
                        FilterChip(selected = defFormat == f, onClick = { vm.setDefaultFormat(f) }, label = { Text(formatOptionLabel(f)) })
                    }
                }
                Text(tr("Quality", "الجودة"), color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualityOptions(defFormat).forEach { q ->
                        FilterChip(selected = defQuality == q, onClick = { vm.setDefaultQuality(q) }, label = { Text(qualityLabel(defFormat, q)) })
                    }
                }
                Text(
                    tr("You can still change the format and quality of every link on its own line.",
                        "ما زال بإمكانك تغيير الصيغة والجودة لكل رابط في سطره."),
                    color = Muted, fontSize = 12.sp,
                )
            }

            // ---- Playlist Playing Patterns: which Bluetooth device plays which playlist ----
            CollapsibleCard(tr("Playlist Playing Patterns", "أنماط تشغيل قوائم التشغيل")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Play a playlist when a Bluetooth device connects", "تشغيل قائمة عند اتصال جهاز بلوتوث"))
                        Text(
                            tr("Your car, headphones or speaker. Needs the Bluetooth permission and keeps a small notification while waiting.",
                                "سيارتك أو سماعتك أو مكبّر الصوت. يحتاج إذن البلوتوث ويُبقي إشعارًا صغيرًا أثناء الانتظار."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = btEnabled,
                        onCheckedChange = { on ->
                            if (!on) vm.setBtEnabled(false)
                            else if (BluetoothWatch.hasPermission(ctx)) vm.setBtEnabled(true)
                            else btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        },
                    )
                }
                Text(tr("When the device connects (only one can be on)", "عند اتصال الجهاز (خيار واحد فقط)"), color = Muted, fontSize = 12.sp)
                BtModeOption(btMode == "background", tr("Play in the background", "تشغيل في الخلفية")) { vm.setBtMode("background") }
                BtModeOption(btMode == "open", tr("Open the app and play", "فتح التطبيق والتشغيل")) {
                    vm.setBtMode("open")
                    if (!canOpenApp) overlayDialog = true
                }
                if (btMode == "open") {
                    if (canOpenApp) {
                        Text(
                            tr("The app will open by itself and start playing when the device connects.",
                                "سيفتح التطبيق من تلقاء نفسه ويبدأ التشغيل عند اتصال الجهاز."),
                            color = Muted, fontSize = 12.sp,
                        )
                    } else {
                        Text(
                            tr("Android blocks apps from opening themselves unless \"Display over other apps\" is allowed. Until then the music plays and a notification opens the app when tapped.",
                                "يمنع أندرويد التطبيقات من فتح نفسها ما لم يُسمح بـ \"العرض فوق التطبيقات الأخرى\". حتى ذلك الحين تعمل الموسيقى ويظهر إشعار يفتح التطبيق عند الضغط عليه."),
                            color = Amber, fontSize = 12.sp,
                        )
                        OutlinedButton(onClick = { BluetoothWatch.openOverlaySettings(ctx) }, shape = RoundedCornerShape(10.dp)) {
                            Text(tr("Allow opening the app", "السماح بفتح التطبيق"))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                if (rules.isEmpty()) {
                    Text(tr("No device assigned yet.", "لم يتم ربط أي جهاز بعد."), color = Muted, fontSize = 13.sp)
                }
                rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Sym(R.drawable.ic_bluetooth, 18.dp, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.deviceName.ifBlank { r.deviceAddress }, fontSize = 14.sp, maxLines = 1)
                            Text(
                                "→ " + (playlists.firstOrNull { it.id == r.playlistId }?.name ?: "?") +
                                    if (r.deviceName.isNotBlank() && r.deviceAddress.isNotBlank()) "  (${r.deviceAddress})" else "",
                                color = Muted, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                        TextButton(onClick = { Library.removeBtRule(r.id); vm.refreshBluetooth() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(tr("Remove", "إزالة"), color = Red)
                        }
                    }
                }
                OutlinedButton(onClick = { ruleDialog = true }, shape = RoundedCornerShape(10.dp)) {
                    Text(tr("Add a device and playlist", "إضافة جهاز وقائمة تشغيل"))
                }

                // ---- weekly calendar / time window of each playlist ----
                Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
                Text(tr("Schedule: days and times", "الجدول: الأيام والأوقات"), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Give a playlist its own weekly calendar and time window. It then plays by Bluetooth only on those days and times, and can also start by itself at its start time.",
                        "امنح كل قائمة تقويمها الأسبوعي وفترتها الزمنية. عندها تُشغَّل بالبلوتوث في تلك الأيام والأوقات فقط، ويمكنها أيضًا أن تبدأ تلقائيًا في وقت البدء."),
                    color = Muted, fontSize = 12.sp,
                )
                if (playlists.isEmpty()) {
                    Text(tr("Create a playlist first (melody icon at the top).", "أنشئ قائمة تشغيل أولًا (أيقونة النغمة في الأعلى)."), color = Muted, fontSize = 13.sp)
                }
                playlists.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 14.sp, maxLines = 1)
                            Text(p.schedule.summary(), color = if (p.schedule?.enabled == true) MaterialTheme.colorScheme.primary else Muted, fontSize = 12.sp, maxLines = 2)
                        }
                        TextButton(onClick = { schedFor = p.id }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(tr("Edit", "تعديل"))
                        }
                    }
                }
            }

            // ---- behaviour ----
            SettingsCard(tr("Behavior", "السلوك")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Open Media when a download finishes", "فتح الوسائط عند اكتمال التنزيل"))
                        Text(
                            tr("Only while the app is open on screen.", "فقط أثناء فتح التطبيق على الشاشة."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = autoHistory, onCheckedChange = vm::setAutoHistory)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Play video preview in Search", "تشغيل معاينة الفيديو في البحث"))
                        Text(
                            tr("Search results can be played inside their own card before you send them to Download.",
                                "يمكن تشغيل نتائج البحث داخل بطاقتها قبل إرسالها إلى التنزيل."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = searchPreview, onCheckedChange = vm::setSearchPreview)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Show video previews in Media and History", "عرض صور معاينة الفيديو في الوسائط والسجل"))
                        Text(
                            tr("A preview image on every row of the Video list.",
                                "صورة معاينة في كل صف من قائمة الفيديو."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = historyThumbs, onCheckedChange = vm::setHistoryThumbs)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var artistArt by remember { mutableStateOf(TrackArt.enabled(ctx)) }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Artist and album covers", "صور الفنانين والألبومات"))
                        Text(
                            tr("When a track has no embedded cover, look up a matching official album cover and then the artist photo on Deezer. Turn this off to keep only embedded artwork.",
                                "عند غياب غلاف داخل الملف، يُبحث عن غلاف ألبوم مطابق ثم صورة الفنان عبر Deezer. أوقف هذا الخيار للاكتفاء بالغلاف المضمّن."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = artistArt, onCheckedChange = { artistArt = it; Prefs.putBool(ctx, "artist_art", it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Resume where you left off", "استئناف من حيث توقفت"))
                        Text(
                            tr("A file you stopped in the middle starts again from that point.",
                                "الملف الذي أوقفته في المنتصف يبدأ من النقطة نفسها."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = resumePlayback, onCheckedChange = vm::setResumePlayback)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Undo after removing", "التراجع بعد الإزالة"))
                        Text(
                            tr("After removing items from the Media or History lists, a bar offers Undo for a few seconds.",
                                "بعد إزالة عناصر من قوائم الوسائط أو السجل يظهر شريط للتراجع لثوانٍ."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = undoDelete, onCheckedChange = vm::setUndoDelete)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Search and filter in the Media tab", "البحث والتصفية في تبويب الوسائط"))
                        Text(
                            tr("A search box and filters above the Media lists.",
                                "مربع بحث ومرشحات فوق قوائم الوسائط."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = librarySearch, onCheckedChange = vm::setLibrarySearch)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Search and filter in the History tab", "البحث والتصفية في تبويب السجل"))
                        Text(
                            tr("A search box and filters above the History list.",
                                "مربع بحث ومرشحات فوق قائمة السجل."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = historySearch, onCheckedChange = vm::setHistorySearch)
                }
                Text(tr("Media category filters", "مرشحات الوسائط"), fontWeight = FontWeight.SemiBold)
                categoryFilterSettings(ctx, history = false)
                Text(tr("History category filters", "مرشحات السجل"), fontWeight = FontWeight.SemiBold)
                categoryFilterSettings(ctx, history = true)
                PreferenceSwitch(
                    key = "home_library_tabs",
                    title = tr("Favorites and Playlists buttons on Home", "أزرار المفضلة وقوائم التشغيل في الرئيسية"),
                    initial = Prefs.showHomeLibraryTabs(ctx),
                    onChange = { Prefs.setShowHomeLibraryTabs(ctx, it) },
                )
                PreferenceSwitch(
                    key = "media_library_tabs",
                    title = tr("Favorites and Playlists buttons on Media", "أزرار المفضلة وقوائم التشغيل في الوسائط"),
                    initial = Prefs.showMediaLibraryTabs(ctx),
                    onChange = { Prefs.setShowMediaLibraryTabs(ctx, it) },
                )
                PreferenceSwitch(
                    key = "home_recommendations_audio",
                    title = tr("Recommended music on Home", "الموسيقى المقترحة في الرئيسية"),
                    initial = Prefs.homeRecommendations(ctx, "audio"),
                    onChange = { Prefs.setHomeRecommendations(ctx, "audio", it) },
                )
                PreferenceSwitch(
                    key = "home_recommendations_video",
                    title = tr("Recommended videos on Home", "الفيديوهات المقترحة في الرئيسية"),
                    initial = Prefs.homeRecommendations(ctx, "video"),
                    onChange = { Prefs.setHomeRecommendations(ctx, "video", it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Show the source website", "إظهار الموقع المصدر"))
                        Text(
                            tr("A small site badge on every file, site filters, and Group by site in the Sort menu.",
                                "شارة صغيرة للموقع على كل ملف، ومرشحات المواقع، وخيار التجميع حسب الموقع في قائمة الترتيب."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = sourceSites, onCheckedChange = vm::setSourceSites)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Add title, artist and cover art to downloads", "إضافة العنوان والفنان وصورة الغلاف للتنزيلات"))
                        Text(
                            tr("Written into new downloads (cover art in MP3) so other music apps show them properly.",
                                "تُكتب في التنزيلات الجديدة (صورة الغلاف في MP3) لتعرضها تطبيقات الموسيقى الأخرى بشكل صحيح."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = embedMeta, onCheckedChange = vm::setEmbedMeta)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Show the Radar progress card", "إظهار بطاقة تقدّم الرادار"))
                        Text(
                            tr("The live card under the listen button: the detection phases and each engine's state. Allowances stay in Settings → Radar Usage.",
                                "البطاقة الحية تحت زر الاستماع: مراحل الكشف وحالة كل محرك. تبقى الحصص في الإعدادات ← استخدام الرادار."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = DetectorPrefs.showCard, onCheckedChange = { DetectorPrefs.changeShowCard(it) })
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Results per website in Search", "عدد النتائج لكل موقع في البحث"))
                    Text(
                        tr("How many videos each website returns. \"Show more results\" in Search loads this many more each time.",
                            "عدد الفيديوهات التي يعيدها كل موقع. زر \"عرض المزيد من النتائج\" في البحث يحمّل هذا العدد في كل مرة."),
                        color = Muted, fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SEARCH_LIMITS.forEach { n ->
                            FilterChip(selected = searchLimit == n, onClick = { vm.setSearchLimit(n) }, label = { Text("$n") })
                        }
                    }
                }
            }

            // ---- Search engines: which websites the Search tab looks in; add, edit or remove your own ----
            CollapsibleCard(
                tr("Search engines", "محركات البحث"),
                summary = if (SearchEngines.useAll) tr("All engines", "كل المحركات") + " (${SearchEngines.all.size})" else SearchEngines.selected.joinToString(" • "),
            ) {
                SearchEnginesContent()
            }

            // ---- default video websites (Search tab) ----
            CollapsibleCard(tr("Default video websites", "مواقع الفيديو الافتراضية"), summary = siteDefaults.joinToString(" • ") { it.name }) {
                Text(
                    tr("The websites shown in the Search tab (up to $MAX_SHORTCUTS). Changes here also update the Search tab, and its Reset button returns to this list.",
                        "المواقع الظاهرة في تبويب البحث (حتى $MAX_SHORTCUTS). التغييرات هنا تُحدّث تبويب البحث أيضًا، وزر إعادة الضبط فيه يعيد هذه القائمة."),
                    color = Muted, fontSize = 12.sp,
                )
                siteDefaults.forEachIndexed { i, sc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(hostOf(sc.url), color = Muted, fontSize = 12.sp, maxLines = 1)
                        }
                        TextButton(onClick = { siteDialog = i }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(tr("Edit", "تعديل"))
                        }
                        TextButton(onClick = { vm.removeDefaultShortcut(i) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(tr("Remove", "إزالة"), color = Red)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (siteDefaults.size < MAX_SHORTCUTS) {
                        OutlinedButton(onClick = { siteDialog = -1 }, shape = RoundedCornerShape(10.dp)) {
                            Text(tr("Add website", "إضافة موقع"))
                        }
                    }
                    TextButton(onClick = vm::restoreFactoryShortcuts) {
                        Text(tr("Restore original list", "استعادة القائمة الأصلية"))
                    }
                }
            }

            // ---- Radar Usage: recognition engines and remaining allowances ----
            CollapsibleCard(
                tr("Radar Usage", "استخدام الرادار"),
                summary = if (DetectorPrefs.useAll) tr("All engines", "كل المحركات") else DetectorPrefs.selected.joinToString(" • "),
            ) {
                DetectorConsumptionContent()
            }

            } else if (appearanceOpen) {
            // ---- language (start) and light / dark mode (end) ----
            SettingsCard(tr("Language", "اللغة")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !ar, onClick = { vm.setArabic(false) }, label = { Text("EN") })
                        FilterChip(selected = ar, onClick = { vm.setArabic(true) }, label = { Text("عر") })
                    }
                    OutlinedButton(
                        onClick = { vm.setLightMode(!light) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Sym(if (light) R.drawable.ic_moon else R.drawable.ic_sun, 20.dp, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Text(if (light) tr("Dark", "داكن") else tr("Light", "فاتح"), fontSize = 13.sp)
                    }
                }
            }

            // ---- themes: colours change at once; the animated background is optional ----
            val themeId by vm.theme.collectAsStateWithLifecycle()
            val liveBg by vm.liveBackground.collectAsStateWithLifecycle()
            CollapsibleCard(tr("Themes", "السمات")) {
                // side slider: swipe sideways to see every theme; the last one follows the phone's wallpaper
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppThemes.forEach { t ->
                        val on = themeId == t.id
                        Column(
                            Modifier.width(58.dp).clickable { vm.setTheme(t.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                Modifier.size(40.dp)
                                    .background(t.swatch, androidx.compose.foundation.shape.CircleShape)
                                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else Muted, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Text("✓", color = if (t.id == "amoled") Color.White else Color.Black, fontWeight = FontWeight.Bold) }
                            Text(tr(t.en, t.ar), fontSize = 11.sp, maxLines = 1, color = if (on) MaterialTheme.colorScheme.onSurface else Muted)
                        }
                    }
                    // colours taken from the phone's wallpaper
                    val wpOn = themeId == THEME_WALLPAPER
                    Column(
                        Modifier.width(58.dp).clickable { vm.setTheme(THEME_WALLPAPER) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.sweepGradient(
                                        listOf(Color(0xFFFF5A5F), Color(0xFFFF9A3C), Color(0xFFFFD54F), Color(0xFF2ECC71), Color(0xFF29B6F6), Color(0xFFB388FF), Color(0xFFFF5A5F)),
                                    ),
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                                .border(if (wpOn) 3.dp else 1.dp, if (wpOn) MaterialTheme.colorScheme.onSurface else Muted, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (wpOn) Box(Modifier.size(22.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                                Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                        Text(tr("Wallpaper", "الخلفية"), fontSize = 11.sp, maxLines = 1, color = if (wpOn) MaterialTheme.colorScheme.onSurface else Muted)
                    }
                }
                Text(
                    tr("Slide sideways for more themes. \"Wallpaper\" uses the colours of your phone's wallpaper.",
                        "مرّر جانبًا لمزيد من السمات. \"الخلفية\" تستخدم ألوان خلفية هاتفك."),
                    color = Muted, fontSize = 12.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Animated background", "خلفية متحركة"))
                        Text(
                            tr("Soft glowing shapes move slowly behind the screens. It pauses while a download or playback runs to save battery.",
                                "أشكال متوهجة ناعمة تتحرك ببطء خلف الشاشات. تتوقف أثناء التنزيل أو التشغيل لتوفير البطارية."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Switch(checked = liveBg, onCheckedChange = vm::setLiveBackground)
                }
            }

            // ---- live pictures: a changing online picture behind the screens ----
            LivePicturesCard()

            } else {
            SettingsLinkCard(
                tr("Appearance", "المظهر"),
                tr("Language, themes and live pictures", "اللغة والسمات والصور الحيّة"),
            ) { appearanceOpen = true }

            SettingsLinkCard(
                tr("Behavior", "السلوك"),
                tr("Download option, playback, search engines, video websites and music detector", "خيار التنزيل والتشغيل ومحركات البحث ومواقع الفيديو وكاشف الموسيقى"),
            ) { behaviorOpen = true }

            // ---- storage overview ----
            SettingsCard(tr("Storage", "التخزين")) {
                Text(
                    tr("See how much space your downloads and imported files take, and which files are the biggest.",
                        "اعرف المساحة التي تشغلها تنزيلاتك وملفاتك المستوردة وأي الملفات أكبر."),
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedButton(onClick = { storageOpen = true }, shape = RoundedCornerShape(10.dp)) {
                    Text(tr("Storage overview", "نظرة على التخزين"))
                }
            }

            // ---- backup and restore ----
            SettingsCard(tr("Backup and restore", "النسخ الاحتياطي والاستعادة")) { BackupCard() }

            SettingsGroupTitle(tr("Privacy and account", "الخصوصية والحساب"))

            // ---- vault ----
            SettingsCard(tr("Vault", "الخزنة")) {
                VaultSettingsCard(onOpen = { VaultStore.screenOpen.value = true })
            }

            // ---- permissions: run the set-up again (each one has its own system dialog) ----
            CollapsibleCard(tr("Permissions", "الأذونات")) {
                Text(
                    tr("Notifications, audio and video access, Bluetooth, opening the app by itself and background running. Each one is asked with Android's own dialog and explained first.",
                        "الإشعارات والوصول إلى الصوت والفيديو والبلوتوث وفتح التطبيق تلقائيًا والعمل في الخلفية. يُطلب كل منها عبر نافذة أندرويد الرسمية بعد شرح الغرض منه."),
                    color = Muted, fontSize = 13.sp,
                )
                OutlinedButton(onClick = vm::openPermissionReview, shape = RoundedCornerShape(10.dp)) {
                    Text(tr("Review permissions", "مراجعة الأذونات"))
                }
            }

            // ---- account (Firebase) + cloud sync ----
            CollapsibleCard(tr("Account and sync", "الحساب والمزامنة")) { AccountSection(vm) }

            SettingsGroupTitle(tr("Help", "المساعدة"))

            // ---- the app guide (the (i) button of the main screen shows once, then lives here) ----
            CollapsibleCard(tr("App guide", "دليل التطبيق")) {
                Text(tr("Everything VibeVault can do", "كل ما يستطيع VibeVault فعله"), color = Muted, fontSize = 12.sp)
                GuideSections()
            }

            // ---- about ----
            Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.3f)))
            SettingsGroupTitle(tr("About", "حول"))
            AboutSection()
            }
        }
    }
    }
}

/** A small heading that groups the cards below it. */
@Composable
private fun SettingsGroupTitle(title: String) {
    Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp))
}

/** One of two mutually exclusive choices: selecting it always deselects the other. */
@Composable
private fun BtModeOption(selected: Boolean, title: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun categoryFilterSettings(ctx: android.content.Context, history: Boolean) {
    val ids = listOf("all", "audio", "video", "youtube", "facebook")
    ids.forEach { id ->
        val label = when (id) {
            "audio" -> tr("Audio", "صوت")
            "video" -> tr("Video", "فيديو")
            "youtube" -> "YouTube"
            "facebook" -> "Facebook"
            else -> tr("All", "الكل")
        }
        val title = if (history) tr("History: $label", "السجل: $label") else tr("Media: $label", "الوسائط: $label")
        PreferenceSwitch(
            key = "${if (history) "history" else "media"}_filter_$id",
            title = title,
            initial = Prefs.categoryFilter(ctx, history, id),
            onChange = { Prefs.setCategoryFilter(ctx, history, id, it) },
        )
    }
}

@Composable
private fun PreferenceSwitch(key: String, title: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember(key) { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
        )
    }
}

/** A card with a title and a short summary that opens another page when tapped. */
@Composable
private fun SettingsLinkCard(title: String, summary: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(summary, color = Muted, fontSize = 12.sp, maxLines = 2)
            }
            Text("›", color = Muted, fontSize = 22.sp)
        }
    }
}

/** A card that shows only its title (and an optional one-line summary) until it is tapped. */
@Composable
private fun CollapsibleCard(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (!open && summary != null) Text(summary, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(if (open) "▴" else "▾", color = Muted, fontSize = 18.sp)
            }
            if (open) content()
        }
    }
}

/** A collapsible block inside a card. */
@Composable
private fun CollapsibleSub(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Muted.copy(alpha = 0.25f)))
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = Muted, fontSize = 18.sp)
        }
        if (open) content()
    }
}

/** Gmail sign up / sign in through Firebase. Once signed in, playlists, favorites and settings sync by themselves. */
@Composable
private fun AccountSection(vm: DownloaderViewModel) {
    val ctx = LocalContext.current
    val email by vm.accountEmail.collectAsStateWithLifecycle()
    val status by vm.accountStatus.collectAsStateWithLifecycle()
    val busy by vm.accountBusy.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val syncErr by vm.syncError.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var mail by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    // never keep a typed password around once it has been used
    LaunchedEffect(email) { pass = ""; pass2 = ""; mismatch = false }

    if (email == null) {
        Text(
            tr("Sign in with your Gmail account to keep your playlists, favorites and settings on all your devices.",
                "ادخل بحساب Gmail لتبقى قوائم تشغيلك ومفضلتك وإعداداتك على كل أجهزتك."),
            color = Muted, fontSize = 13.sp,
        )
        if (Account.googleAvailable(ctx)) {
            Button(
                onClick = { vm.accountGoogleSignIn(ctx) },
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(tr("Sign in with Google", "الدخول بحساب Google")) }
            Text(tr("or with an email and password (Gmail address)", "أو بالبريد وكلمة المرور (عنوان Gmail)"), color = Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !creating, onClick = { creating = false; mismatch = false }, label = { Text(tr("Sign in", "دخول")) })
            FilterChip(selected = creating, onClick = { creating = true; mismatch = false }, label = { Text(tr("Create account", "إنشاء حساب")) })
        }
        OutlinedTextField(
            value = mail, onValueChange = { mail = it }, singleLine = true,
            label = { Text(tr("Gmail address", "عنوان Gmail")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it; mismatch = false }, singleLine = true,
            label = { Text(tr("Password", "كلمة المرور")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (creating) {
            OutlinedTextField(
                value = pass2, onValueChange = { pass2 = it; mismatch = false }, singleLine = true,
                label = { Text(tr("Repeat the password", "أعد كتابة كلمة المرور")) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = mismatch,
                supportingText = if (mismatch) { { Text(tr("The passwords don't match.", "كلمتا المرور غير متطابقتين.")) } } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                if (creating) {
                    if (pass != pass2) mismatch = true else vm.accountSignUp(mail, pass)
                } else {
                    vm.accountSignIn(mail, pass)
                }
            },
            enabled = !busy && mail.isNotBlank() && pass.isNotEmpty(),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text(if (creating) tr("Create account", "إنشاء حساب") else tr("Sign in", "دخول")) }
        if (!creating) {
            TextButton(onClick = { vm.accountResetPassword(mail) }, enabled = !busy) {
                Text(tr("Forgot password?", "نسيت كلمة المرور؟"), fontSize = 13.sp)
            }
        }
    } else {
        Text(tr("Signed in as", "مسجّل الدخول باسم"), color = Muted, fontSize = 12.sp)
        Text(email.orEmpty(), fontWeight = FontWeight.SemiBold)
        val syncText = when (sync) {
            SyncState.Connecting -> tr("Connecting to your account…", "جارٍ الاتصال بحسابك…")
            SyncState.Synced -> tr("Synced. Playlists, favorites and settings are up to date.", "متزامن. قوائم التشغيل والمفضلة والإعدادات محدّثة.")
            SyncState.Pending -> tr("Saving your changes to the cloud…", "جارٍ حفظ تغييراتك في السحابة…")
            SyncState.Offline -> tr("Offline. Changes are kept on this phone and sync by themselves when you're back online.",
                "غير متصل. التغييرات محفوظة على هذا الهاتف وستتزامن تلقائيًا عند عودة الاتصال.")
            SyncState.Error -> syncErr
            SyncState.Off -> ""
        }
        if (syncText.isNotBlank()) Text(syncText, color = if (sync == SyncState.Error) Amber else Muted, fontSize = 12.sp)
        val last = Account.lastSyncMillis(ctx)
        if (last > 0 && sync == SyncState.Synced) {
            Text(
                tr("Last sync: ", "آخر مزامنة: ") + SimpleDateFormat("MMM d • HH:mm", Locale.getDefault()).format(Date(last)),
                color = Muted, fontSize = 12.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::syncNow, enabled = !busy, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                Text(tr("Sync now", "مزامنة الآن"))
            }
            OutlinedButton(onClick = vm::accountSignOut, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                Text(tr("Sign out", "خروج"))
            }
        }
        Text(
            tr("Downloaded files always stay on this phone and keep playing offline.",
                "الملفات المنزّلة تبقى دائمًا على هذا الهاتف وتعمل دون اتصال."),
            color = Muted, fontSize = 12.sp,
        )
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) Text(status, fontSize = 13.sp)
}


@Composable
private fun LivePicturesCard() {
    val ctx = LocalContext.current
    val source by LiveWallpaper.source.collectAsStateWithLifecycle()
    val every by LiveWallpaper.every.collectAsStateWithLifecycle()
    val dim by LiveWallpaper.dim.collectAsStateWithLifecycle()
    val busy by LiveWallpaper.busy.collectAsStateWithLifecycle()
    val credit by LiveWallpaper.credit.collectAsStateWithLifecycle()
    val error by LiveWallpaper.error.collectAsStateWithLifecycle()
    remember { LiveWallpaper.init(ctx) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) LiveWallpaper.importPicture(ctx, u) }
    CollapsibleCard(tr("Live pictures", "صور حيّة")) {
        Text(
            tr("A picture from the internet that changes by itself shows behind the app. No account is needed.",
                "صورة من الإنترنت تتغير تلقائيًا تظهر خلف التطبيق. لا حاجة لحساب."),
            color = Muted, fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = source == "off", onClick = { LiveWallpaper.setSource(ctx, "off") }, label = { Text(tr("Off", "إيقاف")) })
            LiveWallpaper.sources.forEach { s ->
                FilterChip(selected = source == s.id, onClick = { LiveWallpaper.setSource(ctx, s.id) }, label = { Text(tr(s.en, s.ar), maxLines = 1) })
            }
        }
        LiveWallpaper.sources.firstOrNull { it.id == source }?.let { Text(tr(it.enNote, it.arNote), color = Muted, fontSize = 12.sp) }
        if (source == "off") {
            OutlinedButton(onClick = { pick.launch("image/*") }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                Text(tr("Import a picture", "استيراد صورة"))
            }
        }
        if (source != "off") {
            Text(tr("Change the picture", "تغيير الصورة"), color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveWallpaper.intervals.forEach { h ->
                    FilterChip(
                        selected = every == h, onClick = { LiveWallpaper.setEvery(ctx, h) },
                        label = {
                            Text(
                                when (h) {
                                    0 -> tr("Each opening", "عند كل فتح")
                                    24 -> tr("Daily", "يوميًا")
                                    else -> tr("Keep", "إبقاء")
                                },
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Text(tr("Dimming: $dim%", "التعتيم: $dim%"), color = Muted, fontSize = 12.sp)
            Slider(
                value = dim.toFloat(), onValueChange = { LiveWallpaper.setDim(ctx, it.toInt()) },
                valueRange = 30f..95f, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { LiveWallpaper.refresh(ctx) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                    Text(tr("New picture now", "صورة جديدة الآن"))
                }
                OutlinedButton(onClick = { pick.launch("image/*") }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                    Text(tr("Import", "استيراد"))
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            if (credit.isNotBlank()) Text(tr("Now: $credit", "الآن: $credit"), color = Muted, fontSize = 12.sp, maxLines = 2)
            if (error.isNotBlank()) Text(tr("Couldn't update: $error", "تعذّر التحديث: $error"), color = Amber, fontSize = 12.sp)
        }
    }
}
