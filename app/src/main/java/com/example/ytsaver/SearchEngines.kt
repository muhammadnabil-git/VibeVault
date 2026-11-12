package com.example.ytsaver

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A website the user added to the Search tab. It is searched by a web search limited to [domain]. */
class CustomEngine(val label: String, val domain: String)

/**
 * The Search tab's engines: the built-in websites plus the ones the user added. Which ones are asked is chosen in
 * Settings → Behavior → Search engines ("all", or a picked few). Stored on this device.
 */
object SearchEngines {
    private const val KEY_ENABLED = "search_engines"            // "all" or the picked names, one per line
    private const val KEY_CUSTOM = "search_engines_custom"      // "name<TAB>domain", one per line

    /** The websites that come with the app (TikTok, Snapchat, Bilibili, Spotify, Anghami, Shazam and Rumble are not searched). */
    val builtIn = listOf(
        SiteSpec("YouTube", "ytsearch", null, false),
        SiteSpec("SoundCloud", "scsearch", null, true),
        SiteSpec("Instagram", null, "instagram.com", false),
        SiteSpec("Dailymotion", null, "dailymotion.com", false),
        SiteSpec("Facebook", null, "facebook.com", false),
        SiteSpec("Bandcamp", null, "bandcamp.com", true),
        SiteSpec("Mixcloud", null, "mixcloud.com", true),
        SiteSpec("Internet Archive", null, "archive.org", false),
    )

    private var app: Context? = null

    var custom by mutableStateOf<List<CustomEngine>>(emptyList())
        private set
    var useAll by mutableStateOf(true)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Every engine, built-in first, in the order they are searched and shown. */
    val all: List<SiteSpec>
        get() = builtIn + custom.map { SiteSpec(it.label, null, it.domain, false) }

    /** The engines that are asked. Never empty: if nothing is picked the built-in ones are used. */
    fun active(): List<SiteSpec> = all.filter { enabled(it.label) }.ifEmpty { builtIn }

    fun enabled(label: String): Boolean = useAll || label in selected

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        custom = Prefs.str(ctx, KEY_CUSTOM).orEmpty().lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) CustomEngine(p[0], p[1]) else null
        }
        val saved = Prefs.str(ctx, KEY_ENABLED).orEmpty()
        val names = all.map { it.label }
        val picked = saved.lines().filter { it in names }.toSet()
        if (saved.isBlank() || saved == "all" || picked.isEmpty()) {
            useAll = true
            selected = names.toSet()
        } else {
            useAll = false
            selected = picked
        }
    }

    private fun save() {
        val c = app ?: return
        Prefs.putStr(c, KEY_CUSTOM, custom.joinToString("\n") { it.label + "\t" + it.domain })
        Prefs.putStr(c, KEY_ENABLED, if (useAll) "all" else selected.joinToString("\n"))
    }

    fun changeUseAll(on: Boolean) {
        useAll = on
        if (!on && selected.isEmpty()) selected = all.map { it.label }.toSet()
        save()
    }

    /** At least one engine always stays on. */
    fun toggle(label: String, on: Boolean) {
        val next = if (on) selected + label else selected - label
        if (next.isEmpty()) return
        selected = next
        if (next.size == all.size) useAll = true
        save()
    }

    /** "ok", or why the name / website can't be used. [editing] is the engine being changed (its own name and website are allowed). */
    fun check(name: String, site: String, editing: CustomEngine? = null): String {
        val n = name.trim()
        if (n.isEmpty()) return tr0("Enter a name.", "اكتب اسمًا.")
        val domain = domainOf(site) ?: return tr0("Enter a website like example.com.", "اكتب موقعًا مثل example.com.")
        val others = all.filter { editing == null || it.label != editing.label }
        if (others.any { it.label.equals(n, ignoreCase = true) }) return tr0("There is already an engine with this name.", "يوجد محرك بهذا الاسم بالفعل.")
        if (others.any { it.webDomain == domain }) return tr0("This website is already in the list.", "هذا الموقع موجود بالفعل في القائمة.")
        return "ok"
    }

    private fun tr0(en: String, ar: String) = L.t(en, ar)

    /** "https://www.example.com/page" or "example.com" -> "example.com"; null when it can't be a website. */
    fun domainOf(input: String): String? =
        cleanUrl(input.trim())?.let { hostOf(it).removePrefix("www.") }?.takeIf { it.contains('.') }

    fun add(name: String, site: String) {
        val d = domainOf(site) ?: return
        val label = name.trim().replace('\t', ' ').replace('\n', ' ')
        custom = custom + CustomEngine(label, d)
        if (!useAll) selected = selected + label
        save()
    }

    fun edit(old: CustomEngine, name: String, site: String) {
        val d = domainOf(site) ?: return
        val label = name.trim().replace('\t', ' ').replace('\n', ' ')
        custom = custom.map { if (it === old) CustomEngine(label, d) else it }
        if (old.label in selected) selected = selected - old.label + label
        save()
    }

    fun remove(old: CustomEngine) {
        custom = custom.filter { it !== old }
        selected = selected - old.label
        if (selected.isEmpty()) { useAll = true; selected = all.map { it.label }.toSet() }
        save()
    }
}

/** The Search tab looks in these engines (built-in and added by the user, the ones switched on in Settings). */
val SEARCH_SITES: List<SiteSpec> get() = SearchEngines.active()

/** The body of Settings → Behavior → Search engines: use all or pick them one by one, and add, edit or remove your own. */
@Composable
fun SearchEnginesContent() {
    var dialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomEngine?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("The websites the Search tab looks in, all at the same time. Add your own website: it is searched by a web search limited to that site.",
                "المواقع التي يبحث فيها تبويب البحث في وقت واحد. أضف موقعك الخاص: يُبحث فيه ببحث ويب محدود بذلك الموقع."),
            color = Muted, fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Use all engines", "استخدام كل المحركات"))
                Text(tr("Turn off to choose which engines are used.", "أوقفه لاختيار المحركات المستخدمة."), color = Muted, fontSize = 12.sp)
            }
            Switch(checked = SearchEngines.useAll, onCheckedChange = { SearchEngines.changeUseAll(it) })
        }
        SearchEngines.builtIn.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = SearchEngines.enabled(s.label),
                    onCheckedChange = { SearchEngines.toggle(s.label, it) },
                    enabled = !SearchEngines.useAll,
                )
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${s.label}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(s.webDomain ?: tr("Download engine search", "بحث محرك التنزيل"), color = Muted, fontSize = 11.sp)
                }
            }
        }
        SearchEngines.custom.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = SearchEngines.enabled(c.label),
                    onCheckedChange = { SearchEngines.toggle(c.label, it) },
                    enabled = !SearchEngines.useAll,
                )
                Column(Modifier.weight(1f)) {
                    Text("${SearchEngines.builtIn.size + i + 1}. ${c.label}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(c.domain, color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { editing = c; dialog = true }) { Text(tr("Edit", "تعديل")) }
                TextButton(onClick = { SearchEngines.remove(c) }) { Text(tr("Remove", "حذف"), color = Red) }
            }
        }
        if (!SearchEngines.useAll) {
            Text(tr("At least one engine stays on.", "يبقى محرك واحد على الأقل مفعّلًا."), color = Muted, fontSize = 12.sp)
        }
        OutlinedButton(onClick = { editing = null; dialog = true }, shape = RoundedCornerShape(10.dp)) {
            Text(tr("Add a search engine", "إضافة محرك بحث"))
        }
    }
    if (dialog) EngineDialog(editing) { dialog = false; editing = null }
}

@Composable
private fun EngineDialog(editing: CustomEngine?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(editing?.label.orEmpty()) }
    var site by remember { mutableStateOf(editing?.domain.orEmpty()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (editing == null) tr("Add a search engine", "إضافة محرك بحث") else tr("Edit search engine", "تعديل محرك البحث")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = "" }, singleLine = true,
                    label = { Text(tr("Name", "الاسم")) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = site, onValueChange = { site = it; error = "" }, singleLine = true,
                    label = { Text(tr("Website (example.com)", "الموقع (example.com)")) }, modifier = Modifier.fillMaxWidth(),
                )
                if (error.isNotBlank()) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = SearchEngines.check(name, site, editing)
                if (r != "ok") { error = r; return@TextButton }
                if (editing == null) SearchEngines.add(name, site) else SearchEngines.edit(editing, name, site)
                onClose()
            }) { Text(tr("Save", "حفظ")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel", "إلغاء")) } },
    )
}
