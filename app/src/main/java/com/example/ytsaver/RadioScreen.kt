package com.example.ytsaver

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** FM Radio: a big radio with a tuning dial. Stations are internet streams placed on the dial by the frequency in their name. */
@Composable
fun RadioScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ar by vm.ar.collectAsStateWithLifecycle()
    val stations by Radio.stations.collectAsStateWithLifecycle()
    val loading by Radio.loading.collectAsStateWithLifecycle()
    val error by Radio.error.collectAsStateWithLifecycle()
    val entries by PlayerHolder.entries.collectAsStateWithLifecycle()
    val playing by PlayerHolder.playing.collectAsStateWithLifecycle()

    var country by remember { mutableStateOf(Radio.savedCountry(ctx)) }
    var tenths by remember { mutableIntStateOf(Radio.savedTenths(ctx)) }
    var tuned by remember { mutableStateOf(false) }          // true once the user turned the dial: only then does it start playing
    var typeDialog by remember { mutableStateOf(false) }
    // which list is shown: 0 = favourites, 1 = on the dial, 2 = all stations
    val favorites by Radio.favorites.collectAsStateWithLifecycle()
    var listMode by remember { mutableIntStateOf(-1) }

    BackHandler(onBack = onClose)
    LaunchedEffect(country) { Radio.load(ctx, country) }
    LaunchedEffect(Unit) {
        Radio.loadFavorites(ctx)
        if (listMode < 0) listMode = if (Radio.favorites.value.isNotEmpty()) 0 else 1
    }

    val here = remember(stations, tenths) { stationAt(stations, tenths) }
    val now = entries.firstOrNull()?.takeIf { it.location == "radio" }
    val onAir = now != null && playing
    val stationsNow by rememberUpdatedState(stations)

    fun tune(t: Int) {
        val v = t.coerceIn(FM_MIN, FM_MAX)
        if (v == tenths) return
        tenths = v
        tuned = true
        Radio.saveTenths(ctx, v)
    }

    /** Plays a station straight away (favourites, list rows). The dial follows when the station has a frequency. */
    fun playStation(st: RadioStation) {
        st.tenths?.let { tenths = it; Radio.saveTenths(ctx, it) }
        tuned = false
        Radio.play(ctx, st)
    }

    // like a real radio: when the dial settles on a station it plays; between stations it goes quiet
    LaunchedEffect(tenths, tuned) {
        if (!tuned) return@LaunchedEffect
        delay(650)
        val st = stationAt(stationsNow, tenths)
        if (st != null) Radio.play(ctx, st) else Radio.stop(ctx)
        tuned = false
    }

    Box(Modifier.fillMaxSize()) {
    LogoWatermark()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back", "رجوع")) }
            Column(Modifier.weight(1f)) {
                Text(tr("FM Radio", "راديو FM"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(tr("Live stations", "محطات مباشرة"), color = Muted, fontSize = 12.sp)
            }
            DropdownPicker(
                label = Radio.countryName(country, ar),
                options = Radio.countries,
                optionLabel = { Radio.countryName(it, ar) },
                onSelect = { country = it; Radio.saveCountry(ctx, it); Radio.load(ctx, it, force = true) },
                enabled = true,
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // quick access: the saved favourite stations, one tap each
            if (favorites.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    favorites.forEach { f ->
                        val isNow = now?.uri == f.url
                        VvChip(
                            label = "♥  " + (f.tenths?.let { "${fmText(it)}  ${f.name}" } ?: f.name),
                            selected = isNow, onClick = { playStation(f) },
                        )
                    }
                }
            }
            RadioBody(
                tenths = tenths,
                stations = stations,
                stationName = when {
                    loading && stations.isEmpty() -> tr("Loading stations…", "جارٍ تحميل المحطات…")
                    here != null -> here.name
                    else -> tr("No station on this frequency", "لا توجد محطة على هذا التردد")
                },
                status = when {
                    here == null -> tr("Turn the dial or tap Next station", "حرّك المؤشر أو اضغط المحطة التالية")
                    onAir && now?.uri == here.url -> tr("On air", "على الهواء")
                    tuned -> tr("Tuning…", "جارٍ الضبط…")
                    else -> tr("Tuned", "تم الضبط")
                },
                onAir = onAir && here != null && now?.uri == here.url,
                favorite = here?.let { h -> favorites.any { it.url == h.url } } ?: false,
                onFavorite = here?.let { h -> { Radio.toggleFavorite(ctx, h) } },
                onTune = ::tune,
                onType = { typeDialog = true },
            )

            // the buttons around the knob: jump to the previous / next known station, or nudge by 0.1 MHz
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton("⏮", tr("Previous station", "المحطة السابقة")) { jumpFrequency(stations, tenths, -1)?.let(::tune) }
                RoundButton("−", tr("Down 0.1", "−0.1")) { tune(tenths - 1) }
                TuningKnob(tenths, onStep = { tune(tenths + it) })
                RoundButton("+", tr("Up 0.1", "+0.1")) { tune(tenths + 1) }
                RoundButton("⏭", tr("Next station", "المحطة التالية")) { jumpFrequency(stations, tenths, 1)?.let(::tune) }
            }
            Text(
                tr("Drag the dial or the knob. ⏮ ⏭ jump to the next well-known working station.", "اسحب المؤشر أو المقبض. ⏮ ⏭ تقفز إلى أقرب محطة معروفة تعمل."),
                color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(),
            )

            val canPlay = stations.isNotEmpty()
            Box(
                Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp))
                    .background(
                        if (canPlay) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                        else Brush.horizontalGradient(listOf(Muted.copy(alpha = 0.3f), Muted.copy(alpha = 0.3f))),
                    )
                    .clickable(enabled = canPlay) {
                        if (onAir) {
                            Radio.stop(ctx)
                        } else {
                            val st = here ?: jumpFrequency(stations, tenths, 1)?.let { f -> tune(f); stationAt(stations, f) }
                            st?.let { Radio.play(ctx, it) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (onAir) tr("■  Stop", "■  إيقاف") else tr("▶  Play", "▶  تشغيل"),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            if (error.isNotEmpty()) {
                Text(error, color = Red, fontSize = 13.sp)
                TextButton(onClick = { Radio.load(ctx, country, force = true) }) { Text(tr("Try again", "حاول مجددًا")) }
            }

            // station list
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VvChip(tr("Favorites", "المفضلة") + " (${favorites.size})", selected = listMode == 0, onClick = { listMode = 0 })
                VvChip(tr("On the dial", "على المؤشر"), selected = listMode == 1, onClick = { listMode = 1 })
                VvChip(tr("All stations", "كل المحطات"), selected = listMode == 2, onClick = { listMode = 2 })
            }
            val list = remember(stations, favorites, listMode) {
                when (listMode) {
                    0 -> favorites
                    1 -> stations.filter { it.tenths != null }.sortedBy { it.tenths }
                    else -> stations.sortedByDescending { it.votes }
                }
            }.take(150)
            if (list.isEmpty() && !loading) {
                Text(
                    if (listMode == 0) tr("No favorites yet. Tune a station and tap the heart, or tap the heart on any station below.",
                        "لا توجد مفضلة بعد. اضبط محطة واضغط على القلب، أو اضغط القلب على أي محطة.")
                    else tr("No stations to show.", "لا توجد محطات للعرض."),
                    color = Muted,
                )
            }
            list.forEach { st ->
                val isNow = now?.uri == st.url
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isNow) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)))
                            else Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)),
                        )
                        .border(1.dp, if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Muted.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .clickable {
                            if (listMode == 0) playStation(st)
                            else if (st.tenths != null) { tune(st.tenths) } else Radio.play(ctx, st)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // the station's logo (a radio symbol until it loads, or when the station has none)
                    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Sym(R.drawable.ic_radio, 22.dp, tint = Muted)
                        if (st.logo.isNotBlank()) RemoteThumb(url = st.logo, modifier = Modifier.fillMaxSize())
                    }
                    Text(st.tenths?.let { fmText(it) } ?: "—", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(48.dp))
                    Text(st.name, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.ok) Green else Muted))
                    val fav = favorites.any { it.url == st.url }
                    Box(Modifier.size(34.dp).clip(CircleShape).clickable { Radio.toggleFavorite(ctx, st) }, contentAlignment = Alignment.Center) {
                        Sym(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline, 20.dp, tint = if (fav) HeartColor else Muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    }

    if (typeDialog) {
        var text by remember { mutableStateOf(fmText(tenths)) }
        val parsed = text.replace(',', '.').toDoubleOrNull()?.let { (it * 10).roundToInt() }?.takeIf { it in FM_MIN..FM_MAX }
        AlertDialog(
            onDismissRequest = { typeDialog = false },
            title = { Text(tr("Type a frequency", "اكتب التردد")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("MHz (87.5 – 108.0)") }, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = { TextButton(onClick = { parsed?.let { tune(it) }; typeDialog = false }, enabled = parsed != null) { Text(tr("Tune", "ضبط")) } },
            dismissButton = { TextButton(onClick = { typeDialog = false }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
}

/** The window and the knob are dark, so a dark theme colour (e.g. the plain light themes) is brightened to stay readable on them. */
private fun glow(c: Color): Color = if (c.luminance() < 0.45f) lerp(c, Color.White, 0.6f) else c

/** The radio itself: station badge, name and status, and a lit tuning window with the scale. */
@Composable
private fun RadioBody(
    tenths: Int, stations: List<RadioStation>, stationName: String, status: String,
    onAir: Boolean, favorite: Boolean, onFavorite: (() -> Unit)?, onTune: (Int) -> Unit, onType: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "onair")
    val dot by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "dot")
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.30f), cs.secondary.copy(alpha = 0.18f), cs.surface)))
            .border(1.dp, cs.primary.copy(alpha = 0.35f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // station badge
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Brush.linearGradient(listOf(cs.primary, cs.secondary))),
                contentAlignment = Alignment.Center,
            ) { Sym(R.drawable.ic_radio, 30.dp, tint = cs.onPrimary) }
            Column(Modifier.weight(1f)) {
                Text(stationName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (onAir) Red.copy(alpha = dot) else Muted.copy(alpha = 0.5f)))
                    Text(status, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            // heart: save / remove the tuned station in Favorites
            if (onFavorite != null) {
                IconButton(onClick = onFavorite) {
                    Sym(if (favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline, 26.dp, tint = if (favorite) HeartColor else Muted)
                }
            }
        }

        // the lit window
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFF0B0E11))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clickable(onClick = onType)) {
                Text(fmText(tenths), fontSize = 38.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = glow(cs.primary))
                Text("  MHz FM", fontSize = 12.sp, color = glow(cs.primary).copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
            }
            FmDial(tenths, stations, onTune)
            Text(tr("tap the number to type a frequency", "اضغط على الرقم لكتابة التردد"), color = Color(0xFF7C8590), fontSize = 11.sp)
        }
    }
}

/** The scale from 87.5 to 108 MHz. Dots on top mark where stations are; touch or drag to move the needle. */
@Composable
private fun FmDial(tenths: Int, stations: List<RadioStation>, onTune: (Int) -> Unit) {
    val primary = glow(MaterialTheme.colorScheme.primary)
    val measurer = rememberTextMeasurer()
    val tune by rememberUpdatedState(onTune)
    val animatedTenths by animateFloatAsState(tenths.toFloat(), animationSpec = tween(280), label = "fm-frequency")
    val marks = remember(stations) { stations.mapNotNull { st -> st.tenths?.let { t -> t to st } }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.any { it.ok } } }
    val labelStyle = TextStyle(color = Color(0xFF9AA3AD), fontSize = 10.sp)

    Canvas(
        Modifier.fillMaxWidth().height(78.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> tune(FM_MIN + ((o.x / size.width).coerceIn(0f, 1f) * (FM_MAX - FM_MIN)).roundToInt()) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    tune(FM_MIN + ((change.position.x / size.width).coerceIn(0f, 1f) * (FM_MAX - FM_MIN)).roundToInt())
                }
            },
    ) {
        val w = size.width
        fun xFloat(t: Float) = (t - FM_MIN).coerceIn(0f, (FM_MAX - FM_MIN).toFloat()) / (FM_MAX - FM_MIN) * w
        fun xInt(t: Int) = xFloat(t.toFloat())
        // station dots
        marks.forEach { (t, ok) ->
            drawCircle(if (ok) primary else Color(0xFF55606B), radius = if (ok) 3.2.dp.toPx() else 2.dp.toPx(), center = Offset(xInt(t), 6.dp.toPx()))
        }
        // ticks: every 0.1 short, every 0.5 medium, every whole MHz long
        val base = size.height - 16.dp.toPx()
        for (t in FM_MIN..FM_MAX) {
            val whole = t % 10 == 0
            val half = t % 5 == 0
            val len = if (whole) 22.dp.toPx() else if (half) 14.dp.toPx() else 7.dp.toPx()
            drawLine(Color(0xFF6B7682), Offset(xInt(t), base), Offset(xInt(t), base - len), strokeWidth = if (whole) 1.6f else 1f)
            if (t in setOf(880, 920, 960, 1000, 1040, 1080)) {
                val r = measurer.measure((t / 10).toString(), labelStyle)
                drawText(r, topLeft = Offset(xInt(t) - r.size.width / 2f, base + 2.dp.toPx()))
            }
        }
        // needle
        val nx = xFloat(animatedTenths)
        drawCircle(primary.copy(alpha = 0.18f), radius = 11.dp.toPx(), center = Offset(nx, base - 10.dp.toPx()))
        drawCircle(primary.copy(alpha = 0.32f), radius = 6.dp.toPx(), center = Offset(nx, base - 10.dp.toPx()))
        drawRect(primary.copy(alpha = 0.08f), topLeft = Offset(nx - 5.dp.toPx(), 0f), size = Size(10.dp.toPx(), base))
        drawLine(primary, Offset(nx, 0f), Offset(nx, base), strokeWidth = 2.6f)
        val tri = Path().apply {
            moveTo(nx - 6.dp.toPx(), base + 1.dp.toPx()); lineTo(nx + 6.dp.toPx(), base + 1.dp.toPx()); lineTo(nx, base - 8.dp.toPx()); close()
        }
        drawPath(tri, primary)
    }
}

/** A round knob that turns with the frequency. Drag it sideways (or up and down) to tune finely. */
@Composable
private fun TuningKnob(tenths: Int, onStep: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val step by rememberUpdatedState(onStep)
    val primary = glow(cs.primary)
    val body = cs.surfaceVariant
    Canvas(
        Modifier.size(104.dp).pointerInput(Unit) {
            var acc = 0f
            val perStep = 9.dp.toPx()
            detectDragGestures { change, drag ->
                change.consume()
                acc += drag.x - drag.y
                val n = (acc / perStep).toInt()
                if (n != 0) { step(n); acc -= n * perStep }
            }
        },
    ) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(body.copy(alpha = 1f), Color.Black.copy(alpha = 0.55f)), center = c, radius = r), radius = r, center = c)
        drawCircle(Color.White.copy(alpha = 0.12f), radius = r, center = c, style = Stroke(2f))
        val angle = -135f + (tenths - FM_MIN).toFloat() / (FM_MAX - FM_MIN) * 270f
        // ridges around the rim
        rotate(angle, c) {
            for (i in 0 until 24) {
                rotate(i * 15f, c) { drawLine(Color.White.copy(alpha = 0.10f), Offset(c.x, c.y - r * 0.80f), Offset(c.x, c.y - r * 0.95f), strokeWidth = 2f) }
            }
            drawLine(primary, Offset(c.x, c.y - r * 0.28f), Offset(c.x, c.y - r * 0.80f), strokeWidth = 7f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = r * 0.22f, center = c)
    }
}

@Composable
private fun RoundButton(label: String, description: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
                .semantics { contentDescription = description }.clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface) }
    }
}
