package com.example.ytsaver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ytsaver.DetectorStatus.Stage

private enum class Step { Pending, Active, Done, Failed }

/** The card under the listen button: the four phases, each service with its state, and what is left of its free allowance. */
@Composable
fun DetectorProgressCard() {
    val st = DetectorStatus.stage
    val chainStarted = DetectorStatus.passes > 0
    val failed = st == Stage.Failed

    val p1 = when { st == Stage.Listening -> Step.Active; st == Stage.Idle -> Step.Pending; else -> Step.Done }
    val p2 = when {
        st == Stage.Details || st == Stage.Done -> Step.Done
        failed -> Step.Failed
        st == Stage.Listening && !chainStarted -> Step.Pending
        else -> Step.Active
    }
    val p3 = when { st == Stage.Details -> Step.Active; st == Stage.Done -> Step.Done; else -> Step.Pending }
    val p4 = when { st == Stage.Done -> Step.Done; failed -> Step.Failed; else -> Step.Pending }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Detection progress", "تقدّم الكشف"), fontSize = 15.sp, fontWeight = FontWeight.Bold)

            PhaseRow(p1, tr("1  Listening", "١  الاستماع"),
                if (st == Stage.Listening) tr("${DetectorStatus.heardSec}s heard", "تم سماع ${DetectorStatus.heardSec} ث")
                else if (DetectorStatus.heardSec > 0) tr("${DetectorStatus.heardSec}s recorded", "تم تسجيل ${DetectorStatus.heardSec} ث") else "")
            PhaseRow(p2, tr("2  Fingerprint chain", "٢  سلسلة البصمة"),
                when {
                    DetectorStatus.matchedBy.isNotBlank() -> tr("matched by ${DetectorStatus.matchedBy}", "تمت المطابقة عبر ${DetectorStatus.matchedBy}")
                    chainStarted -> tr("pass ${DetectorStatus.passes}", "المحاولة ${DetectorStatus.passes}")
                    else -> tr("starts after a few seconds", "تبدأ بعد ثوانٍ")
                })
            PhaseRow(p3, tr("3  Details (Last.fm)", "٣  التفاصيل (Last.fm)"), "")
            PhaseRow(p4, tr("4  Result", "٤  النتيجة"), "")

            Spacer(Modifier.height(2.dp))
            Text(tr("Services and free allowance", "الخدمات والحصة المجانية"), color = Muted, fontSize = 12.sp)
            QuotaStore.specs.forEach { ProviderRow(it) }
            Text(
                tr("Allowance counts are kept on this device and may differ slightly from the services' own.",
                    "تُحتسب الحصص على هذا الجهاز وقد تختلف قليلًا عن حسابات الخدمات نفسها."),
                color = Muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PhaseRow(step: Step, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepDot(step)
        Text(title, fontSize = 13.sp, fontWeight = if (step == Step.Active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (step == Step.Pending) Muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun StepDot(step: Step, size: Dp = 16.dp) {
    when (step) {
        Step.Active -> CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp)
        Step.Done -> Text("✓", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(size))
        Step.Failed -> Text("✕", color = Red, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(size))
        Step.Pending -> androidx.compose.foundation.layout.Box(
            Modifier.padding(3.dp).size(size - 6.dp).clip(RoundedCornerShape(50)).background(Muted.copy(alpha = 0.35f)))
    }
}

/** The usage bar and "Used · remaining" line of one engine; shared by the progress card and Settings → Radar Usage. */
@Composable
fun QuotaUsage(s: QuotaSpec) {
    val remaining = QuotaStore.remaining(s)
    val per = when (s.period) {
        QPeriod.Total -> tr("total", "إجمالي")
        QPeriod.Month -> tr("per month", "شهريًا")
        QPeriod.Day -> tr("per day", "يوميًا")
        QPeriod.Unlimited -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (s.period == QPeriod.Unlimited) {
            Text(tr("Unlimited · ${s.note}", "غير محدود · ${s.note}"), color = Muted, fontSize = 11.sp)
        } else {
            LinearProgressIndicator(
                progress = { if (s.limit == 0) 0f else QuotaStore.used(s.name).toFloat() / s.limit },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (remaining == 0) Red else MaterialTheme.colorScheme.primary,
            )
            Text(
                tr("Used ${QuotaStore.used(s.name)} · remaining $remaining of ${"%,d".format(s.limit)} $per",
                    "المستخدم ${QuotaStore.used(s.name)} · المتبقي $remaining من ${"%,d".format(s.limit)} $per"),
                color = Muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ProviderRow(s: QuotaSpec) {
    val status = DetectorStatus.providers[s.name] ?: PStatus.Waiting
    val (label, color) = when (status) {
        PStatus.Waiting -> tr("waiting", "في الانتظار") to Muted
        PStatus.Querying -> tr("asking…", "جارٍ السؤال…") to MaterialTheme.colorScheme.primary
        PStatus.NoMatch -> tr("no match", "لا نتيجة") to Muted
        PStatus.Matched -> tr("matched", "تمت المطابقة") to Green
        PStatus.Limit -> tr("limit reached", "بلغ الحد") to Red
        PStatus.Unavailable -> tr("unavailable", "غير متاح") to Amber
        PStatus.Off -> tr("off", "متوقف") to Muted
    }
    val remaining = QuotaStore.remaining(s)
    val unlimited = s.period == QPeriod.Unlimited
    val per = when (s.period) {
        QPeriod.Total -> tr("total", "إجمالي")
        QPeriod.Month -> tr("per month", "شهريًا")
        QPeriod.Day -> tr("per day", "يوميًا")
        QPeriod.Unlimited -> ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == PStatus.Querying) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Text("${s.tier}. ${s.name}", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(label, color = color, fontSize = 12.sp)
        }
        val err = DetectorStatus.errors[s.name]
        if (!err.isNullOrBlank() && (status == PStatus.Unavailable || status == PStatus.Limit || status == PStatus.NoMatch)) {
            Text(err, color = Amber, fontSize = 10.sp, maxLines = 2)
        }
        if (unlimited) {
            Text(tr("Unlimited · ${s.note}", "غير محدود · ${s.note}"), color = Muted, fontSize = 11.sp)
        } else {
            LinearProgressIndicator(
                progress = { if (s.limit == 0) 0f else QuotaStore.used(s.name).toFloat() / s.limit },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (remaining == 0) Red else MaterialTheme.colorScheme.primary,
            )
            Text(
                tr("Used ${QuotaStore.used(s.name)} · remaining $remaining of ${"%,d".format(s.limit)} $per",
                    "المستخدم ${QuotaStore.used(s.name)} · المتبقي $remaining من ${"%,d".format(s.limit)} $per"),
                color = Muted, fontSize = 11.sp,
            )
        }
    }
}
