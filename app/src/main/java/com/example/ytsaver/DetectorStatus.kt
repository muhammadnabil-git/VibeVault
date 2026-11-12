package com.example.ytsaver

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class QPeriod { Total, Month, Day, Unlimited }

/** What a service's free plan allows. */
class QuotaSpec(val name: String, val tier: Int, val limit: Int, val period: QPeriod, val note: String = "")

/**
 * How much of each service's free allowance is used. The services don't report this (except Shazam's RapidAPI
 * headers, used when they come), so the requests that really reached a service are counted on this device and
 * stored; monthly and daily counts start again when the month / day changes.
 */
object QuotaStore {
    val specs = listOf(
        QuotaSpec("ACRCloud", 1, 5_000, QPeriod.Total),
        QuotaSpec("Shazam", 2, 500, QPeriod.Month),
        QuotaSpec("SongFinder", 3, 300, QPeriod.Month),
        QuotaSpec("Shazam Audio", 4, 100, QPeriod.Month),
        QuotaSpec("AudioTag", 5, 900, QPeriod.Month),       // 10,800 free seconds a month = about 900 clips of 12 s
        QuotaSpec("AudD", 6, 10, QPeriod.Day),
    )

    private val used = mutableStateMapOf<String, Int>()
    private val usedKey = HashMap<String, String>()          // the day / month each in-memory count belongs to
    private val exhaustedAt = HashMap<String, Long>()       // when a service itself last said "limit reached"
    /** A day-limited service that looks used up is asked again after this long: the server decides, not our guess. */
    private const val PROBE_MS = 30L * 60 * 1000
    private val skipNextCount = HashSet<String>()
    private var app: Context? = null

    private fun periodKey(p: QPeriod): String {
        val f = when (p) {
            QPeriod.Month -> "yyyy-MM"
            QPeriod.Day -> "yyyy-MM-dd"
            else -> return "total"
        }
        return SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }

    private fun spec(name: String) = specs.first { it.name == name }

    @Synchronized
    fun init(ctx: Context) {
        app = ctx.applicationContext
        // one-time clean-up: earlier versions filled a counter to its limit on any 429 / 401 / 403, not only on a real
        // "allowance used up", so monthly and daily counters stuck at their limit are cleared once
        val migrate = Prefs.str(ctx, "quota_fix_v1").isNullOrEmpty()
        for (s in specs) {
            val saved = Prefs.str(ctx, "quota_${s.name}").orEmpty().split('|')
            var n = if (saved.size == 2 && saved[0] == periodKey(s.period)) saved[1].toIntOrNull() ?: 0 else 0
            if (migrate && s.period != QPeriod.Total && s.period != QPeriod.Unlimited && n >= s.limit) n = 0
            used[s.name] = n
            usedKey[s.name] = periodKey(s.period)
            exhaustedAt[s.name] = Prefs.str(ctx, "quotax_${s.name}").orEmpty().toLongOrNull() ?: 0L
            if (migrate) save(s.name)
        }
        if (migrate) Prefs.putStr(ctx, "quota_fix_v1", "1")
    }

    /** Forgets a pending "the service just told the real figure" flag, e.g. after that request failed. */
    @Synchronized
    fun clearSkip(name: String) {
        skipNextCount.remove(name)
    }

    /** Sets a service's counter back to zero. */
    @Synchronized
    fun reset(name: String) {
        used[name] = 0
        exhaustedAt[name] = 0L
        save(name)
    }

    private fun save(name: String) {
        val c = app ?: return
        Prefs.putStr(c, "quota_$name", "${usedKey[name] ?: periodKey(spec(name).period)}|${used[name] ?: 0}")
        Prefs.putStr(c, "quotax_$name", (exhaustedAt[name] ?: 0L).toString())
    }

    /** A new day / month starts the count again. Checked on every access, not only when a screen opens. */
    @Synchronized
    private fun roll(name: String) {
        val now = periodKey(spec(name).period)
        if (usedKey[name] != now) {
            usedKey[name] = now
            used[name] = 0
            exhaustedAt[name] = 0L
            save(name)
        }
    }

    fun used(name: String): Int { roll(name); return used[name] ?: 0 }

    /**
     * True when the service must not be asked now. Monthly and total allowances are real, so a full counter blocks.
     * A daily allowance (AudD without a token) is only our estimate: when the counter is full we keep asking
     * every [PROBE_MS], and a request that goes through sets the counter right again.
     */
    @Synchronized
    fun blocked(s: QuotaSpec): Boolean {
        if (s.period == QPeriod.Unlimited) return false
        if (remaining(s) > 0) return false
        if (s.period != QPeriod.Day) return true
        return System.currentTimeMillis() - (exhaustedAt[s.name] ?: 0L) < PROBE_MS
    }
    fun remaining(s: QuotaSpec): Int = (s.limit - used(s.name)).coerceAtLeast(0)

    /** A request reached the service. */
    @Synchronized
    fun count(name: String) {
        if (skipNextCount.remove(name)) return          // the service just told the real figure
        val s = spec(name)
        // a daily service answered although our count was full: the guess was wrong or the day has reset
        used[name] = if (s.period == QPeriod.Day && remaining(s) == 0) 1 else used(name) + 1
        exhaustedAt[name] = 0L
        save(name)
    }

    /** The service refused with "limit reached". */
    @Synchronized
    fun markExhausted(name: String) {
        val s = spec(name)
        if (s.period == QPeriod.Unlimited) return
        roll(name)
        used[name] = s.limit
        exhaustedAt[name] = System.currentTimeMillis()
        save(name)
    }

    /** When the last request to [name] was made; kept across restarts so a per-minute limit holds after the app is reopened. */
    @Synchronized
    fun lastCall(name: String): Long {
        val c = app ?: return 0L
        return Prefs.str(c, "lastcall_$name").orEmpty().toLongOrNull() ?: 0L
    }

    @Synchronized
    fun markCall(name: String) {
        val c = app ?: return
        Prefs.putStr(c, "lastcall_$name", System.currentTimeMillis().toString())
    }

    /** The service itself reported how many are left. */
    @Synchronized
    fun fromServer(name: String, limit: Int, remaining: Int) {
        val s = spec(name)
        roll(name)
        used[name] = (limit - remaining).coerceIn(0, maxOf(limit, s.limit))
        skipNextCount += name
        save(name)
    }
}

enum class PStatus { Waiting, Querying, NoMatch, Matched, Limit, Unavailable, Off }

/** The live state of one detection, shown by the progress card. Written from background threads. */
object DetectorStatus {
    /** Idle, Listening (recording + chain), Chain (recording over, chain finishing), Details (Last.fm), Done, Failed. */
    enum class Stage { Idle, Listening, Chain, Details, Done, Failed }

    var stage by mutableStateOf(Stage.Idle)
    var passes by mutableIntStateOf(0)
    var heardSec by mutableIntStateOf(0)
    var matchedBy by mutableStateOf("")
    val providers = mutableStateMapOf<String, PStatus>()
    /** The last error text of an engine that failed or refused in this detection, shown under its row. */
    val errors = mutableStateMapOf<String, String>()

    fun reset() {
        stage = Stage.Idle; passes = 0; heardSec = 0; matchedBy = ""
        providers.clear()
        errors.clear()
        QuotaStore.specs.forEach { providers[it.name] = if (DetectorPrefs.enabled(it.name)) PStatus.Waiting else PStatus.Off }
    }
}
