package com.example.ytsaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private enum class DPhase { Idle, Listening, Analyzing, Found, NotFound, Failed }

/**
 * Music Detector. Listens through the microphone and, while it listens, runs the recognition chain (ACRCloud, Shazam,
 * AudD, or only the ones switched on in Settings → Detector Consumption) over what was heard so far. The first service that knows the song ends the listening at once;
 * Last.fm then adds the cover, genres and background. All keys are built in.
 */
@Composable
fun DetectorScreen(vm: DownloaderViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(DPhase.Idle) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<SongInfo?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var links by remember { mutableStateOf<PlatformLinks?>(null) }     // where the song lives on each music app
    var linksLoading by remember { mutableStateOf(false) }
    val stop = remember { AtomicBoolean(false) }

    remember { DetectorPrefs.init(ctx) }
    LaunchedEffect(Unit) { QuotaStore.init(ctx); DetectorStatus.reset() }

    fun reset() { job?.cancel(); stop.set(true); phase = DPhase.Idle; progress = 0f; message = ""; links = null; linksLoading = false; DetectorStatus.reset() }
    BackHandler { reset(); onClose() }

    fun listen() {
        stop.set(false)
        LinkResolver.warmUp(ctx)        // SoundCloud's search key is read while the song is being recognised
        phase = DPhase.Listening
        progress = 0f
        message = ""
        links = null
        linksLoading = false
        DetectorStatus.reset()
        DetectorStatus.stage = DetectorStatus.Stage.Listening
        val found = AtomicReference<TrackMatch?>(null)
        val runSkip: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val attempts = ConcurrentLinkedQueue<Deferred<ChainResult>>()
        val lastSnapshot = AtomicInteger(0)
        job = scope.launch {
            val outer = this
            // Phase 1+2 while recording: a pass down the chain at 6, 11 and 16 seconds; a match stops the recording at once
            val pcm = withContext(Dispatchers.IO) {
                AudioCapture.record(
                    20, { progress = it; DetectorStatus.heardSec = (it * 20).toInt() }, { stop.get() || found.get() != null },
                    checkpoints = intArrayOf(6, 11, 16),
                ) { snap ->
                    lastSnapshot.set(snap.durationSec)
                    attempts += outer.async(Dispatchers.IO) {
                        RecognitionChain.find(snap, runSkip).also { r -> r.match?.let { found.compareAndSet(null, it) } }
                    }
                }
            }
            if (phase == DPhase.Idle) return@launch
            phase = DPhase.Analyzing
            DetectorStatus.stage = DetectorStatus.Stage.Chain
            try {
                while (found.get() == null && attempts.any { it.isActive }) delay(100)
                val results = ArrayList<ChainResult>()
                if (found.get() == null) {
                    attempts.forEach { results += it.await() }
                    // stopped well after the last pass: one more with everything that was heard
                    if (found.get() == null && pcm != null && pcm.durationSec - lastSnapshot.get() >= 4) {
                        val r = withContext(Dispatchers.IO) { RecognitionChain.find(pcm, runSkip) }
                        results += r
                        r.match?.let { found.compareAndSet(null, it) }
                    }
                }
                val match = found.get()
                if (match != null) {
                    // Phase 3: Last.fm (+ MusicBrainz for what it lacks)
                    DetectorStatus.stage = DetectorStatus.Stage.Details
                    // the music-app links are looked up at the same time as the details, and fill in the buttons when ready
                    linksLoading = true
                    // buttons light up one by one as each link is found; the search keeps going until every strategy is spent
                    val linkJob = outer.async(Dispatchers.IO) {
                        runCatching { LinkResolver.resolve(match, stop = { stop.get() }, onUpdate = { links = it }) }.getOrDefault(PlatformLinks.NONE)
                    }
                    outer.launch { val r = linkJob.await(); links = if (r.urls.isEmpty()) (links ?: r) else r; linksLoading = false }
                    info = withContext(Dispatchers.IO) { MusicDetector.enrich(match) }
                    phase = DPhase.Found
                    DetectorStatus.stage = DetectorStatus.Stage.Done
                } else if (pcm == null && results.isEmpty()) {
                    phase = DPhase.Failed
                    message = L.t("Couldn't hear enough. Check the microphone and try again.", "لم أسمع بما يكفي. تحقّق من الميكروفون وحاول مجددًا.")
                } else if (results.any { it.answered }) {
                    phase = DPhase.NotFound
                    message = L.t("Couldn't recognise this. Move closer to the music and try again.", "لم أتعرّف على هذا. اقترب من الموسيقى وحاول مجددًا.")
                } else if (results.isNotEmpty() && results.all { it.offline }) {
                    phase = DPhase.Failed
                    message = L.t("No internet connection.", "لا يوجد اتصال بالإنترنت.")
                } else {
                    // Phase 4, failure state: every free allowance is used up
                    phase = DPhase.Failed
                    message = L.t("Free daily audio recognition limits reached. Please try matching this track again tomorrow.",
                        "تم بلوغ حدود التعرّف الصوتي المجانية اليومية. يرجى محاولة مطابقة هذا المقطع مرة أخرى غدًا.")
                    toast(ctx, message)
                }
                if (phase != DPhase.Found) DetectorStatus.stage = DetectorStatus.Stage.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DetectorStatus.stage = DetectorStatus.Stage.Failed
                phase = DPhase.Failed
                message = L.t("Something went wrong", "حدث خطأ ما")
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else toast(ctx, L.t("The microphone permission is needed to listen.", "إذن الميكروفون مطلوب للاستماع."))
    }

    Box(Modifier.fillMaxSize()) {
    LogoWatermark()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { reset(); onClose() }) { Text(tr("← Back", "رجوع")) }
            Text(tr("Music Detector", "كاشف الموسيقى"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // the big listen button
            if (phase != DPhase.Found) {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
                Box(Modifier.padding(top = 8.dp).size(190.dp), contentAlignment = Alignment.Center) {
                    if (phase == DPhase.Listening) {
                        Box(Modifier.size(150.dp).scale(ring).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
                    }
                    Box(
                        Modifier.size(150.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(enabled = phase != DPhase.Analyzing) {
                                if (phase == DPhase.Listening) {
                                    stop.set(true)      // stop early: what was heard so far is used
                                } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    listen()
                                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (phase == DPhase.Analyzing) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                        else Sym(R.drawable.ic_music_search, 72.dp, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Text(
                    when (phase) {
                        DPhase.Listening -> tr("Listening… tap to stop", "جارٍ الاستماع… اضغط للإيقاف")
                        DPhase.Analyzing -> tr("Finding the song…", "جارٍ البحث عن الأغنية…")
                        else -> tr("Tap to listen", "اضغط للاستماع")
                    },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
                if (phase == DPhase.Listening) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                if (phase == DPhase.Idle) {
                    Text(
                        tr("Play the song near the phone and tap the button. The cover, genres and details come from Last.fm.",
                            "شغّل الأغنية قرب الهاتف واضغط الزر. تأتي تفاصيل الغلاف والنوع من Last.fm."),
                        color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    )
                }
                if ((phase == DPhase.NotFound || phase == DPhase.Failed) && message.isNotBlank()) {
                    Text(message, color = if (phase == DPhase.Failed) Red else Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                // the live progress card stays under the button until the song is found and its details replace it
                if (phase != DPhase.Idle && DetectorPrefs.showCard) DetectorProgressCard()
            }

            val song = info
            if (phase == DPhase.Found && song != null) {
                ResultCard(song)

                // open the song in its official music app (only the services that have it are live)
                PlatformButtons(links, linksLoading)

                // the two actions, under the metadata
                val query = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" ")
                Button(
                    onClick = { vm.sendSongToSearch(query); onClose() },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text(tr("Send to Search", "إرسال إلى البحث")) }
                Button(
                    onClick = { vm.sendSongToDownload(query); onClose() },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text(tr("Send to Download queue", "إرسال إلى قائمة التنزيل")) }
                TextButton(onClick = { reset() }) { Text(tr("Identify another song", "التعرّف على أغنية أخرى")) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
    }
}

@Composable
private fun ResultCard(s: SongInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val cover: Bitmap? = s.cover
            if (cover != null) {
                Image(cover.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)))
            } else {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Sym(R.drawable.ic_music_search, 72.dp, tint = Muted)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (s.artist.isNotBlank()) Text(s.artist, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Meta(tr("Album", "الألبوم"), s.album)
            Meta(tr("Year", "السنة"), s.year)
            Meta(tr("Genre", "النوع"), s.genres)
            if (s.durationSec > 0) Meta(tr("Length", "المدة"), "%d:%02d".format(s.durationSec / 60, s.durationSec % 60))
            if (s.score >= 0) Meta(tr("Match", "التطابق"), "${(s.score * 100).toInt()}%")
            if (s.via.isNotBlank()) Meta(tr("Identified by", "تم التعرّف بواسطة"), s.via)
            if (s.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(s.summary, color = Muted, fontSize = 12.sp, maxLines = 6)
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
    }
}
