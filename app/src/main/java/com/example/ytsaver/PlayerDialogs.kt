package com.example.ytsaver

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URLEncoder

@Composable
fun SleepTimerDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val left by SleepTimer.remainingMs.collectAsStateWithLifecycle()
    val eof by SleepTimer.endOfFile.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Sleep timer", "مؤقّت النوم")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
                val status = when {
                    left != null -> tr("Pausing in ", "سيتوقف التشغيل بعد ") + formatCountdown(left ?: 0L)
                    eof -> tr("Pausing when this file ends", "سيتوقف التشغيل عند نهاية هذا الملف")
                    else -> tr("Playback will pause by itself.", "سيتوقف التشغيل تلقائيًا.")
                }
                Text(status, color = Muted, fontSize = VvText.caption)
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    VvSecondaryButton(tr("$m minutes", "$m دقيقة"), { SleepTimer.startMinutes(ctx, m); onClose() }, Modifier.fillMaxWidth())
                }
                VvSecondaryButton(tr("End of this file", "نهاية هذا الملف"), { SleepTimer.startEndOfFile(ctx); onClose() }, Modifier.fillMaxWidth())
                if (left != null || eof) {
                    VvPrimaryButton(tr("Turn off the timer", "إيقاف المؤقّت"), { SleepTimer.cancel(); onClose() }, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close", "إغلاق")) } },
    )
}

@Composable
fun EqualizerDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { EqualizerController.attach(ctx) }
    val supported by EqualizerController.supported.collectAsStateWithLifecycle()
    val enabled by EqualizerController.enabled.collectAsStateWithLifecycle()
    val levels by EqualizerController.levels.collectAsStateWithLifecycle()
    val preset by EqualizerController.preset.collectAsStateWithLifecycle()
    val info = EqualizerController.info

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Equalizer", "المعادل الصوتي")) },
        text = {
            if (!supported || info == null) {
                VvErrorState(
                    tr(
                        "This phone does not offer an equalizer for the current audio output. Start playing something first, then open this again.",
                        "هذا الهاتف لا يوفّر معادلًا صوتيًا لمخرج الصوت الحالي. ابدأ تشغيل شيء أولًا ثم افتح هذه الشاشة مجددًا.",
                    ),
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Vv.sm)) {
                    Row(Modifier.fillMaxWidth().height(Vv.touch), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Turn on", "تفعيل"), Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { EqualizerController.setEnabled(ctx, it) })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Vv.sm)) {
                        info.presets.forEachIndexed { i, name ->
                            VvChip(name, selected = preset == i, onClick = { EqualizerController.usePreset(ctx, i) })
                        }
                    }
                    info.bandFreqHz.forEachIndexed { band, hz ->
                        val level = levels.getOrNull(band) ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", fontSize = VvText.caption, color = Muted, modifier = Modifier.width(64.dp))
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { EqualizerController.setBand(ctx, band, it.toInt()) },
                                valueRange = info.minLevel.toFloat()..info.maxLevel.toFloat(),
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close", "إغلاق")) } },
    )
}

/**
 * Lyrics: instead of copying lyrics into the app (they are copyrighted), the song is searched on the web in the in-app
 * browser, so the lyrics are read on the site that is licensed to show them.
 */
fun lyricsSearchUrl(songName: String): String =
    "https://www.google.com/search?q=" + URLEncoder.encode(prettyName(songName) + " lyrics", "UTF-8")
