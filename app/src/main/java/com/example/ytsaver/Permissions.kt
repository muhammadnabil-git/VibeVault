package com.example.ytsaver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** How a permission is obtained. */
private enum class PermKind {
    RUNTIME,   // the system's own permission pop-up
    OVERLAY,   // "Display over other apps": Android has no pop-up for it, only its settings page
    BATTERY,   // "don't optimise battery": the system shows a confirmation dialog
}

private class PermStep(
    val kind: PermKind,
    val perms: List<String>,
    val title: String,
    val why: String,
    val denied: String,
)

private fun steps(): List<PermStep> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermStep(
            PermKind.RUNTIME, listOf(Manifest.permission.POST_NOTIFICATIONS),
            L.t("Notifications", "الإشعارات"),
            L.t("Shows download progress, the player controls while music plays, and scheduled-playlist alerts.",
                "لإظهار تقدّم التنزيل وأزرار المشغّل أثناء تشغيل الموسيقى وتنبيهات القوائم المجدولة."),
            L.t("Downloads and playback still work, but you won't see their progress or the player controls in the notification shade.",
                "يستمر التنزيل والتشغيل بالعمل، لكنك لن ترى تقدّمهما أو أزرار المشغّل في شريط الإشعارات."),
        ))
    }
    add(PermStep(
        PermKind.RUNTIME,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
        L.t("Audio and video on this phone", "الصوت والفيديو على هذا الهاتف"),
        L.t("Lets you import music and videos from your phone's storage into History and your playlists.",
            "لتتمكن من استيراد الموسيقى والفيديو من ذاكرة الهاتف إلى السجل وقوائم التشغيل."),
        L.t("You can still download and play from links. \"Import from device\" stays unavailable until you allow it.",
            "يمكنك التنزيل والتشغيل من الروابط، لكن \"الاستيراد من الجهاز\" يبقى غير متاح حتى تسمح بذلك."),
    ))
    if (Build.VERSION.SDK_INT >= 31) {
        add(PermStep(
            PermKind.RUNTIME, listOf(Manifest.permission.BLUETOOTH_CONNECT),
            L.t("Nearby devices (Bluetooth)", "الأجهزة القريبة (بلوتوث)"),
            L.t("Lets the app list your paired headphones or car and notice when one connects, to start its playlist.",
                "ليعرض التطبيق سماعاتك أو سيارتك المقترنة ويعرف عند اتصال أحدها، ليبدأ تشغيل قائمته."),
            L.t("Bluetooth auto-play can't list or detect your devices. Scheduled playlists are not affected: they never need Bluetooth.",
                "لا يستطيع التشغيل التلقائي عبر بلوتوث عرض أجهزتك أو اكتشافها. القوائم المجدولة غير متأثرة: لا تحتاج بلوتوث أبدًا."),
        ))
    }
    add(PermStep(
        PermKind.OVERLAY, emptyList(),
        L.t("Display over other apps", "العرض فوق التطبيقات الأخرى"),
        L.t("Lets the app open itself when a Bluetooth device connects (\"Open the app and play\"). Android has no pop-up for this one, so the button opens its settings page: turn it on for VibeVault and come back.",
            "ليفتح التطبيق نفسه عند اتصال جهاز بلوتوث (\"فتح التطبيق والتشغيل\"). لا يوجد نافذة منبثقة لهذا الإذن في أندرويد، لذا يفتح الزر صفحة إعداداته: فعّله لـ VibeVault ثم ارجع."),
        L.t("The music still starts by itself, and a notification opens the app when tapped.",
            "تبدأ الموسيقى من تلقاء نفسها، ويظهر إشعار يفتح التطبيق عند الضغط عليه."),
    ))
    add(PermStep(
        PermKind.BATTERY, emptyList(),
        L.t("Run in the background", "العمل في الخلفية"),
        L.t("Stops Android from putting the app to sleep, so scheduled playlists start on time and play to their end even when the app is closed. Android asks you to confirm.",
            "يمنع أندرويد من إيقاف التطبيق لتوفير الطاقة، فتبدأ القوائم المجدولة في وقتها وتعمل حتى نهايتها حتى مع إغلاق التطبيق. سيطلب أندرويد تأكيدك."),
        L.t("Schedules still work, but some phones may delay or stop playback to save battery.",
            "تبقى الجداول تعمل، لكن بعض الهواتف قد تؤخر التشغيل أو توقفه لتوفير الطاقة."),
    ))
}

private fun granted(ctx: Context, s: PermStep): Boolean = when (s.kind) {
    PermKind.RUNTIME -> s.perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    PermKind.OVERLAY -> Settings.canDrawOverlays(ctx)
    PermKind.BATTERY -> ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
}

private fun openAppSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/**
 * Walks the user through the permissions one by one, each with the system's own dialog and a plain explanation of what it
 * is for and what happens without it. In the first run only the permissions that are not granted yet are asked for;
 * [review] shows all of them (Settings → Permissions). The system settings are opened only where Android has no dialog
 * (Display over other apps) or after a permission was refused for good.
 */
@Composable
fun PermissionSetupDialog(review: Boolean, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }          // bumped when the app comes back to the screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val all = remember { steps() }
    val list = remember { if (review) all else all.filter { !granted(ctx, it) } }
    var index by remember { mutableIntStateOf(0) }
    var refused by remember { mutableStateOf(false) }          // the current step was refused
    var forGood by remember { mutableStateOf(false) }          // ...and Android will not ask again

    val done = list.isEmpty() || index >= list.size
    LaunchedEffect(done) { if (done) onFinish() }

    fun next() { refused = false; forGood = false; index++ }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val cur = list.getOrNull(index)
        if (cur == null || result.values.all { it }) {
            next()
        } else {
            refused = true
            val act = ctx as? Activity
            // when Android won't show its dialog any more the only way to allow it is the app's settings page
            forGood = act != null && cur.perms.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(act, it)
            }
        }
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val cur = list.getOrNull(index)
        if (cur == null || granted(ctx, cur)) next() else refused = true
    }

    if (done) return
    val step = list[index]
    val tick = refresh                                          // read so the dialog refreshes after coming back from settings
    val isOn = tick >= 0 && granted(ctx, step)

    AlertDialog(
        onDismissRequest = { },
        title = { Text(tr("Permission ${index + 1} of ${list.size}", "الإذن ${index + 1} من ${list.size}")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(step.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(step.why, fontSize = 14.sp)
                if (isOn) {
                    Text(tr("✓ Allowed", "✓ مسموح"), color = Green, fontWeight = FontWeight.SemiBold)
                } else if (refused) {
                    Text(step.denied, color = Amber, fontSize = 13.sp)
                    if (forGood) {
                        Text(
                            tr("Android won't show this question again. You can allow it in the app's settings.",
                                "لن يعرض أندرويد هذا السؤال مرة أخرى. يمكنك السماح به من إعدادات التطبيق."),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                isOn -> TextButton(onClick = { next() }) { Text(tr("Next", "التالي")) }
                refused && forGood -> TextButton(onClick = { openAppSettings(ctx) }) { Text(tr("Open settings", "فتح الإعدادات")) }
                else -> TextButton(onClick = {
                    when (step.kind) {
                        PermKind.RUNTIME -> runtimeLauncher.launch(step.perms.toTypedArray())
                        PermKind.OVERLAY -> BluetoothWatch.openOverlaySettings(ctx)
                        PermKind.BATTERY -> try {
                            batteryLauncher.launch(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
                            )
                        } catch (_: Exception) {
                            refused = true
                        }
                    }
                }) { Text(if (refused) tr("Try again", "حاول مجددًا") else tr("Allow", "السماح")) }
            }
        },
        dismissButton = {
            Row {
                if (!isOn) TextButton(onClick = { next() }) { Text(if (refused) tr("Continue", "متابعة") else tr("Skip", "تخطي")) }
                TextButton(onClick = onFinish) { Text(tr("Close", "إغلاق")) }
            }
        },
    )
}
