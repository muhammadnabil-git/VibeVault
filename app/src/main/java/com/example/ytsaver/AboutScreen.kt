package com.example.ytsaver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp

/** About the app: shown inside Settings. */
@Composable
fun AboutSection() {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "7.3.5"
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_logo_vv), contentDescription = "VibeVault", modifier = Modifier.size(64.dp))
            Column {
                Text("VibeVault", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(tr("Save. Organize. Play.", "احفظ. نظّم. شغّل."), color = Muted)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoRow(tr("Developer", "المطوّر"), "M.Nabil")
                InfoRow(tr("Version", "الإصدار"), version)
            }
        }

        TestersCard(version)

        OutlinedButton(
            onClick = { sendBugReport(ctx) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(tr("Report a problem", "الإبلاغ عن مشكلة")) }

        Button(
            onClick = { sendEmail(ctx) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(tr("Send email", "إرسال بريد إلكتروني")) }

        Text(
            tr(
                "Please only download content you own or have permission to save.",
                "يرجى تنزيل المحتوى الذي تملكه أو لديك إذن بحفظه فقط.",
            ),
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 16.sp)
    }
}

/** Collapsible card: version, developer, the people who tested and contributed, and the device it runs on. */
@Composable
private fun TestersCard(version: String) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val code = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toString() }.getOrNull() ?: "-"
    }
    val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Testers info", "معلومات المختبرين"), fontWeight = FontWeight.SemiBold)
                    if (!open) Text(tr("Version $version • Developer M.Nabil", "الإصدار $version • المطوّر M.Nabil"), color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                Text(if (open) "▴" else "▾", color = Muted, fontSize = 18.sp)
            }
            if (open) {
                InfoRow(tr("Version", "الإصدار"), "$version (build $code)")
                InfoRow(tr("Developer", "المطوّر"), "M.Nabil")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Testing and Contributions:", "الاختبار والمساهمات:"), color = Muted, fontSize = 12.sp)
                    listOf("O.Ashraf", "M.Attia", "S.ElSayed", "A.ElKersh").forEach { Text(it, fontSize = 16.sp) }
                }
                InfoRow(tr("Your device", "جهازك"), device)
            }
        }
    }
}
