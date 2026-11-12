package com.example.ytsaver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private class GuideSection(val title: String, val lines: List<String>)

@Composable
private fun guideSections(): List<GuideSection> = listOf(
    GuideSection(
        tr("What VibeVault does", "ما الذي يفعله التطبيق"),
        listOf(
            tr("VibeVault finds audio and video on the websites you use, saves it to your phone as MP3 (audio) or MP4 (video), and plays it in its own player. Everything you save stays on your phone and plays without internet.",
                "يبحث التطبيق عن الصوت والفيديو في المواقع التي تستخدمها، ويحفظه في هاتفك بصيغة MP3 (صوت) أو MP4 (فيديو)، ويشغّله في مشغّله الخاص. كل ما تحفظه يبقى في هاتفك ويعمل دون إنترنت."),
            tr("The tabs are Search (the magnifier), Download, Media and History. The icons at the top are International Radio, Sound Detector (a note with a magnifier), Notifications (bell) and Settings (gear). Favorites (heart) and Playlists are the two tabs at the bottom of the screen; tap the open one again to go back. The small (i) appears once after installing or updating and then lives in Settings → App guide.",
                "التبويبات: البحث (العدسة)، التنزيل، الوسائط، السجل. الأيقونات في الأعلى: الراديو الدولي وكاشف الأغاني (نغمة مع عدسة) والإشعارات (الجرس) والإعدادات (الترس). المفضلة (القلب) وقوائم التشغيل هما التبويبان في أسفل الشاشة؛ اضغط المفتوح منهما مجددًا للعودة. تظهر (i) الصغيرة مرة واحدة بعد التثبيت أو التحديث ثم تبقى في الإعدادات ← دليل التطبيق."),
        ),
    ),
    GuideSection(
        tr("Search tab", "تبويب البحث"),
        listOf(
            tr("• Type a name to search many websites at once, or paste a link. Results appear as each website answers, and a filter chip appears only for websites that returned results. Each result shows its duration before you play it.",
                "• اكتب اسمًا للبحث في عدة مواقع معًا، أو الصق رابطًا. تظهر النتائج فور رد كل موقع، ولا يظهر مرشّح إلا للمواقع التي أعادت نتائج. تعرض كل نتيجة مدتها قبل تشغيلها."),
            tr("• Tap the play button on a result to preview it inside its card (the screen stays awake while you watch). Every result has Favorite (heart), Add to playlist and Download.",
                "• اضغط زر التشغيل على النتيجة لمعاينتها داخل بطاقتها (تبقى الشاشة مضاءة أثناء المشاهدة). لكل نتيجة المفضلة (القلب) وإضافة إلى قائمة وتنزيل."),
            tr("• Settings → Behavior → Search engines: choose which websites are searched, and add, edit or remove your own.",
                "• الإعدادات ← السلوك ← محركات البحث: اختر المواقع التي يُبحث فيها، وأضف مواقعك أو عدّلها أو احذفها."),
            tr("• The website shortcuts under the search bar open the site inside the app. The in-app browser keeps cookies, cache and logins, offers to save passwords, and has bookmarks, history, find in page and Clear browsing data. On a Spotify or Shazam song page the Download button finds the song by name on the open web.",
                "• تفتح اختصارات المواقع تحت شريط البحث الموقع داخل التطبيق. يحتفظ المتصفح الداخلي بملفات الارتباط والذاكرة المؤقتة وتسجيلات الدخول، ويقترح حفظ كلمات المرور، وفيه إشارات مرجعية وسجل وبحث في الصفحة ومسح بيانات التصفح. في صفحة أغنية على Spotify أو Shazam يبحث زر التنزيل عن الأغنية بالاسم على الويب المفتوح."),
        ),
    ),
    GuideSection(
        tr("Download and Media tabs", "تبويبا التنزيل والوسائط"),
        listOf(
            tr("• Paste one or more links in the Download tab; each line gets a preview card and its own format and quality. The progress shows percent, speed and time left.",
                "• الصق رابطًا أو أكثر في تبويب التنزيل؛ يحصل كل سطر على بطاقة معاينة وصيغة وجودة خاصة به. يعرض التقدم النسبة والسرعة والوقت المتبقي."),
            tr("• In Media (Audio / Video) tap a file for Play, Share and Add to playlist. Press and hold a file to start selecting; then tick more files and play them, add them to playlists, move them to the vault or delete them. Swipe right to delete, swipe left to add to Favorites.",
                "• في الوسائط (صوت / فيديو) اضغط ملفًا لتظهر أزرار التشغيل والمشاركة والإضافة إلى قائمة. اضغط مطولًا على ملف لبدء التحديد، ثم علّم ملفات أخرى وشغّلها أو أضفها إلى قوائم أو انقلها إلى الخزنة أو احذفها. اسحب لليمين للحذف ولليسار للإضافة إلى المفضلة."),
            tr("• The player controls hide by themselves for audio and video alike; tap the screen to bring them back.",
                "• تختفي أزرار المشغّل تلقائيًا للصوت والفيديو معًا؛ اضغط الشاشة لإظهارها."),
        ),
    ),
    GuideSection(
        tr("International Radio", "الراديو الدولي"),
        listOf(
            tr("• Tap the radio icon at the top. Pick a country and turn the big dial (or tap the frequency to type it) to tune an FM station by its frequency; the buttons jump to the next or previous well-known station. Stations that list no frequency get a dial position of their own.",
                "• اضغط أيقونة الراديو في الأعلى. اختر دولة وحرّك المؤشر الكبير (أو اضغط التردد لكتابته) لضبط محطة FM بترددها؛ تنقلك الأزرار إلى المحطة المعروفة التالية أو السابقة. المحطات التي لا تذكر ترددًا تحصل على موضع خاص بها على المؤشر."),
            tr("• Below the dial every station shows its own logo. Tap the heart to keep a station as a favorite for one-tap access from any country. Radio plays in the background like any other media, with the mini player and notification.",
                "• تحت المؤشر تظهر كل محطة بشعارها الخاص. اضغط القلب لحفظ محطة في المفضلة للوصول إليها بضغطة من أي دولة. يعمل الراديو في الخلفية كأي وسائط أخرى مع المشغّل المصغّر والإشعار."),
            tr("• Stations come from the free radio-browser.info directory and are cached on your phone, so the list also opens offline.",
                "• تأتي المحطات من دليل radio-browser.info المجاني وتُخزَّن في هاتفك، فتُفتح القائمة دون إنترنت أيضًا."),
        ),
    ),
    GuideSection(
        tr("Sound Detector", "كاشف الأغاني"),
        listOf(
            tr("• Tap the note-with-magnifier icon at the top, play a song near the phone and tap Listen. The detector asks several recognition engines one after another until one finds the song, then shows the cover, title, artist, album, year and more.",
                "• اضغط أيقونة النغمة مع العدسة في الأعلى، شغّل أغنية قرب الهاتف واضغط استماع. يسأل الكاشف عدة محركات تعرّف واحدًا بعد الآخر حتى يجد أحدها الأغنية، ثم يعرض الغلاف والعنوان والفنان والألبوم والسنة وغيرها."),
            tr("• Under the result: Send to Search (looks the song up in the Search tab) or Send to Download queue.",
                "• تحت النتيجة: إرسال إلى البحث (للبحث عن الأغنية في تبويب البحث) أو إرسال إلى قائمة التنزيل."),
            tr("• Settings → Music Detector shows which engines are used and what is left of each allowance; turn engines on or off there. A few seconds of clear sound near the start of the song work best.",
                "• الإعدادات ← كاشف الموسيقى تعرض المحركات المستخدمة وما تبقى من حصة كل منها؛ فعّل المحركات أو أوقفها من هناك. أفضل النتائج مع بضع ثوانٍ من صوت واضح قرب بداية الأغنية."),
        ),
    ),
    GuideSection(
        tr("Themes and live pictures", "السمات والصور الحية"),
        listOf(
            tr("• Settings → Appearance: language, light / dark mode, colour themes (including one from your wallpaper) and an optional animated background. Live pictures put a fresh online photo behind the app (or one you import with Import): choose how often it changes (each opening, daily or keep) and how much it is dimmed. Themes and Live pictures each open on their own inside Appearance.",
                "• الإعدادات ← المظهر: اللغة والوضع الفاتح / الداكن وسمات الألوان (منها سمة من خلفية هاتفك) وخلفية متحركة اختيارية. تضع الصور الحية صورة جديدة من الإنترنت خلف التطبيق: اختر معدل تغييرها (عند كل فتح أو يوميًا أو إبقاء) ودرجة التعتيم."),
        ),
    ),
    GuideSection(
        tr("Vault", "الخزنة"),
        listOf(
            tr("• Move any file of the Media tabs to the vault: three-dot menu → Move to vault, or Select → Move to vault. The file leaves Media, History, playlists, Favorites and Android Auto, and is kept in the app's private storage.",
                "• انقل أي ملف من تبويبات الوسائط إلى الخزنة: قائمة النقاط الثلاث ← نقل إلى الخزنة، أو تحديد ← نقل إلى الخزنة. يغادر الملف الوسائط والسجل وقوائم التشغيل والمفضلة وأندرويد أوتو ويُحفظ في التخزين الخاص بالتطبيق."),
            tr("• Settings → Vault: set or change the PIN, open the vault, or erase it if you forget the PIN. Inside the vault (Audio / Video) you can play, restore a file to Media, or delete it for good.",
                "• الإعدادات ← الخزنة: عيّن الرمز أو غيّره، افتح الخزنة، أو امسحها إذا نسيت الرمز. داخل الخزنة (صوت / فيديو) يمكنك التشغيل أو استعادة الملف إلى الوسائط أو حذفه نهائيًا."),
            tr("• The vault locks by itself as soon as you leave its page (or the app); there is no Lock button. Vault files are not synced to your account and are excluded from Android backups. Files imported from your phone are copied into the vault; the original stays until you delete it yourself.",
                "• تُقفل الخزنة تلقائيًا بمجرد مغادرة صفحتها (أو التطبيق)؛ لا يوجد زر قفل. ملفات الخزنة لا تُزامَن مع حسابك وتُستثنى من النسخ الاحتياطي لأندرويد. الملفات المستوردة من هاتفك تُنسخ إلى الخزنة ويبقى الأصل حتى تحذفه بنفسك."),
        ),
    ),
    GuideSection(
        tr("Favorites (heart tab)", "المفضلة (تبويب القلب)"),
        listOf(
            tr("The heart on any item adds it to Favorites. Favorites keep online items (links) and offline items (files) in separate Audio / Video tabs, can be played, selected in bulk (Select: play, add to playlists or Now playing, remove), shared with the Share button (the file when it is on your phone, otherwise its link), and sync to your account.",
                "القلب على أي عنصر يضيفه إلى المفضلة. تحفظ المفضلة العناصر الأونلاين (الروابط) والأوفلاين (الملفات) في تبويبي صوت / فيديو منفصلين، ويمكن تشغيلها وإضافتها إلى قوائم التشغيل، ومشاركتها بزر المشاركة (الملف إن كان على هاتفك وإلا الرابط)، وتتزامن مع حسابك."),
        ),
    ),
    GuideSection(
        tr("Playlists", "قوائم التشغيل"),
        listOf(
            tr("• Three kinds: Local (downloaded and imported files only, works offline), Online (web links streamed without downloading) and Mixed (both).",
                "• ثلاثة أنواع: محلية (ملفات منزّلة ومستوردة فقط وتعمل دون إنترنت)، أونلاين (روابط ويب تُبث دون تنزيل)، ومختلطة (الاثنان)."),
            tr("• + New creates a playlist. The ☰ / ▦ button switches between List and Grid view (remembered). In Grid view every playlist has a cover picture that is also its play button: tap the cover to play. In List view the round play button does the same. The ⋮ menu of a playlist has Add downloads, Add link, Import from device, Rename, the cover options and Delete. Tap a playlist to open it: Schedule, Add + (downloads, a link or files from the device), its Bluetooth device and its tracks with a play button each.",
                "• \"+ جديدة\" تنشئ قائمة. زر ☰ / ▦ يبدّل بين عرض القائمة وعرض الشبكة (يُحفظ). في عرض الشبكة لكل قائمة صورة غلاف هي أيضًا زر التشغيل: اضغط الغلاف للتشغيل. وفي عرض القائمة يفعل زر التشغيل الدائري الشيء نفسه. قائمة ⋮ للقائمة فيها إضافة تنزيلات وإضافة رابط واستيراد من الجهاز وإعادة تسمية وخيارات الغلاف والحذف. اضغط قائمة لفتحها: الجدول وزر إضافة + (تنزيلات أو رابط أو ملفات من الجهاز) وجهاز البلوتوث ومقاطعها مع زر تشغيل لكل مقطع."),
            tr("• Cover: by default a playlist shows the picture of the first media in it. In the ⋮ menu choose \"Cover image from device\" to use your own picture, \"Cover from the internet\" for a collage made from the pictures of its media, or \"Default cover\" to go back.",
                "• الغلاف: تعرض القائمة افتراضيًا صورة أول عنصر فيها. من قائمة ⋮ اختر \"صورة غلاف من الجهاز\" لاستخدام صورتك، أو \"غلاف من الإنترنت\" لكولاج من صور عناصرها، أو \"الغلاف الافتراضي\" للعودة."),
            tr("• Select: tick several playlists, then Add to Now playing (their items are appended to the queue) or Remove (deletes the playlists only; your files stay). Select all ticks every playlist.",
                "• تحديد: علّم عدة قوائم ثم اختر إضافة للتشغيل الحالي (تُضاف عناصرها إلى نهاية الطابور) أو إزالة (تحذف القوائم فقط وتبقى ملفاتك). \"تحديد الكل\" يعلّم كل القوائم."),
            tr("• Online items that are also downloaded on this phone play from the file, which costs no data. Playlists sync between your devices when you are signed in.",
                "• العناصر الأونلاين التي نزّلتها على هذا الهاتف تُشغَّل من الملف دون استهلاك بيانات. تتزامن القوائم بين أجهزتك عند تسجيل الدخول."),
        ),
    ),
    GuideSection(
        tr("Playing patterns: Bluetooth and Schedule", "أنماط التشغيل: البلوتوث والجدول"),
        listOf(
            tr("• Bluetooth: assign a playlist to your car, headphones or speaker (Settings → Behavior → Playlist Playing Patterns; tap Search Now to turn Bluetooth on and find nearby devices, paired ones stay listed). When it connects, the playlist starts if the app is closed or its player is paused; media that is already playing keeps playing. It plays either in the background or by opening the app (this needs \"Display over other apps\").",
                "• البلوتوث: اربط قائمة بسيارتك أو سماعتك أو مكبّر الصوت (الإعدادات ← أنماط تشغيل قوائم التشغيل). عند اتصاله تبدأ القائمة، إما في الخلفية أو بفتح التطبيق (يتطلب \"العرض فوق التطبيقات الأخرى\")."),
            tr("• Schedule (weekly calendar and time window, per playlist): choose the days of the week, optionally a time window (it may cross midnight; times use the 12-hour clock with AM / PM), and optionally \"Start playing automatically\". With a schedule on, the Bluetooth auto-play of that playlist only starts on those days and inside that window. Playing it by hand always works.",
                "• الجدول (تقويم أسبوعي وفترة زمنية لكل قائمة): اختر أيام الأسبوع، واختياريًا فترة زمنية (يمكنها تجاوز منتصف الليل؛ الوقت بنظام 12 ساعة مع ص / م)، واختياريًا \"بدء التشغيل تلقائيًا\". عند تفعيل الجدول يبدأ التشغيل التلقائي بالبلوتوث لتلك القائمة في تلك الأيام وضمن تلك الفترة فقط. التشغيل اليدوي يعمل دائمًا."),
            tr("• Automatic start uses an exact phone alarm (you may see the alarm-clock icon in the status bar). At the start time the app wakes itself, even if it is closed, and plays the playlist to its end. No Bluetooth device is needed, and swiping the app away does not stop it. Allow \"Run in the background\" when asked (or in Settings → Permissions) for the best reliability.",
                "• يستخدم البدء التلقائي منبّهًا دقيقًا في الهاتف (قد ترى أيقونة المنبّه في شريط الحالة). في وقت البدء يوقظ التطبيق نفسه حتى لو كان مغلقًا ويشغّل القائمة حتى نهايتها. لا حاجة لجهاز بلوتوث، وإغلاق التطبيق من التطبيقات الأخيرة لا يوقف التشغيل. اسمح بـ \"العمل في الخلفية\" عند الطلب (أو من الإعدادات ← الأذونات) لأفضل موثوقية."),
            tr("• Permissions: on the first launch the app asks for each permission one by one with Android's own dialog and explains what it is for and what happens without it. You can review them any time in Settings → Permissions.",
                "• الأذونات: عند أول تشغيل يطلب التطبيق كل إذن على حدة عبر نافذة أندرويد الرسمية ويشرح الغرض منه وما يحدث بدونه. يمكنك مراجعتها في أي وقت من الإعدادات ← الأذونات."),
        ),
    ),
    GuideSection(
        tr("Please note", "تنبيه"),
        listOf(
            tr("Only download content you own or have permission to save. Some websites change often; if one stops working, update the download engine (it is also updated automatically every time the app opens).",
                "نزّل فقط المحتوى الذي تملكه أو لديك إذن بحفظه. بعض المواقع تتغير كثيرًا؛ إذا توقف موقع عن العمل فحدّث محرك التنزيل."),
        ),
    ),

)

/** All the guide sections, one under the other (used by the "i" window and by Settings → App guide). */
@Composable
fun GuideSections() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        guideSections().forEach { sec ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sec.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                sec.lines.forEach { Text(it, fontSize = 13.sp) }
            }
        }
    }
}

/** The "i" window: a scrollable, detailed description of everything the app can do. */
@Composable
fun FeaturesGuideDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(10.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("App guide", "دليل التطبيق"), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = tr("Close", "إغلاق")) }
                }
                Text(tr("Everything VibeVault can do", "كل ما يستطيع VibeVault فعله"), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    GuideSections()
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text(tr("Close", "إغلاق")) }
            }
        }
    }
}
