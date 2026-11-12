package com.example.ytsaver

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Music Detector's own settings: which recognition engines may be asked, and whether the live progress card is
 * shown on the Detector screen. Stored on this device. "All" (the default) asks every engine in tier order.
 */
object DetectorPrefs {
    private const val KEY_ENGINES = "detector_engines"     // "all" or a comma-separated list of engine names
    private const val KEY_CARD = "detector_card2"          // "1" shown, anything else hidden (off by default)

    private var app: Context? = null

    var showCard by mutableStateOf(false)
        private set
    var useAll by mutableStateOf(true)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Safe to call many times: it reads the saved values once per process. */
    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        showCard = Prefs.str(ctx, KEY_CARD) == "1"
        val names = QuotaStore.specs.map { it.name }
        val saved = Prefs.str(ctx, KEY_ENGINES).orEmpty()
        val picked = saved.split(',').filter { it in names }.toSet()
        if (saved.isBlank() || saved == "all" || picked.isEmpty()) {
            useAll = true
            selected = names.toSet()
        } else {
            useAll = false
            selected = picked
        }
    }

    fun enabled(name: String): Boolean = useAll || name in selected

    fun changeShowCard(on: Boolean) {
        showCard = on
        save()
    }

    fun changeUseAll(on: Boolean) {
        useAll = on
        if (!on && selected.isEmpty()) selected = QuotaStore.specs.map { it.name }.toSet()
        save()
    }

    /** At least one engine always stays on. */
    fun toggle(name: String, on: Boolean) {
        val next = if (on) selected + name else selected - name
        if (next.isEmpty()) return
        selected = next
        if (next.size == QuotaStore.specs.size) useAll = true
        save()
    }

    private fun save() {
        val c = app ?: return
        Prefs.putStr(c, KEY_CARD, if (showCard) "1" else "0")
        Prefs.putStr(c, KEY_ENGINES, if (useAll) "all" else selected.joinToString(","))
    }
}

/** The body of Settings → Detector Consumption: use all engines or pick them one by one, with what is left of each allowance. */
@Composable
fun DetectorConsumptionContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("The engines the Music Detector asks, one after the other in this order, until one finds the song. Allowance counts are kept on this device and may differ slightly from the services' own.",
                "المحركات التي يسألها كاشف الموسيقى واحدًا بعد الآخر بهذا الترتيب حتى يجد أحدها الأغنية. تُحتسب الحصص على هذا الجهاز وقد تختلف قليلًا عن حسابات الخدمات نفسها."),
            color = Muted, fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Use all engines", "استخدام كل المحركات"))
                Text(
                    tr("Turn off to choose which engines are used.", "أوقفه لاختيار المحركات المستخدمة."),
                    color = Muted, fontSize = 12.sp,
                )
            }
            Switch(checked = DetectorPrefs.useAll, onCheckedChange = { DetectorPrefs.changeUseAll(it) })
        }
        QuotaStore.specs.forEach { s ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = DetectorPrefs.enabled(s.name),
                    onCheckedChange = { DetectorPrefs.toggle(s.name, it) },
                    enabled = !DetectorPrefs.useAll,
                )
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text("${s.tier}. ${s.name}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    QuotaUsage(s)
                }
            }
        }
        if (!DetectorPrefs.useAll) {
            Text(
                tr("At least one engine stays on.", "يبقى محرك واحد على الأقل مفعّلًا."),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}
