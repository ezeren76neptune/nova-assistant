package app.nova.assistant.core

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.nova.assistant.NovaApp
import app.nova.assistant.tools.Contacts
import app.nova.assistant.service.NotificationWatcher
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A private, on-device "what's on my phone" index — like Siri/Gemini personal context, without the cloud.
 * Notifications are captured as they arrive (they disappear otherwise); texts and calendar are searched live.
 * [search] is a fast keyword match (no model call), used to hand the AI relevant context up front and
 * behind the search_phone tool.
 */
class PersonalIndex(private val app: NovaApp) {
    data class Item(val source: String, val title: String, val text: String, val time: Long, val app: String = "") {
        fun line(): String {
            val day = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(time))
            val body = text.replace('\n', ' ').take(220)
            return when (source) {
                "sms" -> "Text $title ($day): $body"
                "calendar" -> "Calendar: $title — $body"
                else -> "$app notification ($day) $title: $body"
            }
        }
    }

    private val file = File(app.filesDir, "notification_index.jsonl")
    private val notifs = ArrayDeque<Item>()
    private val _items = kotlinx.coroutines.flow.MutableStateFlow<List<Item>>(emptyList())
    /** Saved notifications, newest first (for Settings → Memory → Notification index). */
    val items: kotlinx.coroutines.flow.StateFlow<List<Item>> get() { load(); return _items }

    private fun publish() { _items.value = synchronized(notifs) { notifs.toList().asReversed() } }

    private fun rewrite() {
        runCatching { file.writeText(notifs.joinToString("\n", postfix = if (notifs.isEmpty()) "" else "\n") { it.json() }) }
    }

    /** Deletes one saved notification. */
    fun remove(item: Item) {
        load()
        synchronized(notifs) { if (notifs.remove(item)) rewrite() }
        publish()
    }
    @Volatile private var loaded = false

    private fun load() {
        if (loaded) return
        synchronized(notifs) {
            if (loaded) return
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            runCatching {
                file.takeIf { it.exists() }?.forEachLine { l ->
                    runCatching {
                        val o = JSONObject(l)
                        val it = Item("notification", o.getString("ti"), o.getString("tx"), o.getLong("t"), o.optString("a"))
                        if (it.time > cutoff) notifs.addLast(it)
                    }
                }
            }
            while (notifs.size > MAX) notifs.removeFirst()
            loaded = true
        }
        publish()
    }

    fun size(): Int { load(); return notifs.size }

    /** Called by the notification listener for every new notification. */
    fun addNotification(appLabel: String, title: String, text: String, time: Long, force: Boolean = false): Boolean {
        if ((!force && !app.settings[K.indexNotifications]) || (title.isBlank() && text.isBlank())) return false
        load()
        val item = Item("notification", title.trim(), text.trim(), time, appLabel)
        synchronized(notifs) {
            // updates of the same conversation repeat the same text — keep one
            if (notifs.any { it.app == item.app && it.title == item.title && it.text == item.text }) return false
            notifs.addLast(item)
            val compact = notifs.size > MAX + 200
            while (notifs.size > MAX) notifs.removeFirst()
            if (compact) rewrite() else runCatching { file.appendText(item.json() + "\n") }
        }
        publish()
        return true
    }

    // ---------------------------------------------------------------- on-demand indexing

    /** What the last "Index phone now" found. */
    data class Report(
        val time: Long, val notifications: List<Item>, val newNotifications: Int, val texts: List<Item>, val events: List<Item>,
        val notifAccess: Boolean, val smsAccess: Boolean, val calendarAccess: Boolean,
    )

    private val _report = kotlinx.coroutines.flow.MutableStateFlow<Report?>(null)
    val report: kotlinx.coroutines.flow.StateFlow<Report?> = _report

    @Volatile private var snapTexts: List<Item>? = null
    @Volatile private var snapEvents: List<Item>? = null
    @Volatile private var snapAt = 0L

    /** Re-reads current notifications, texts and calendar now (call off the main thread). */
    fun reindex(): Report {
        load()
        val watcher = NotificationWatcher.instance
        val added = watcher?.indexActive() ?: 0
        val t = texts(); val e = calendar()
        snapTexts = t; snapEvents = e; snapAt = System.currentTimeMillis()
        val r = Report(snapAt, _items.value, added, t, e, watcher != null, has(Manifest.permission.READ_SMS), has(Manifest.permission.READ_CALENDAR))
        _report.value = r
        AppLog.d("index", "indexed: ${r.notifications.size} notifications ($added new), ${t.size} texts, ${e.size} calendar events")
        return r
    }

    /** Texts + calendar from the last snapshot (refreshed when older than 10 minutes) — fast lookups. */
    private fun snapshot(): List<Item> {
        if (snapTexts == null || System.currentTimeMillis() - snapAt > 10 * 60_000L) {
            snapTexts = texts(); snapEvents = calendar(); snapAt = System.currentTimeMillis()
        }
        return snapTexts.orEmpty() + snapEvents.orEmpty()
    }

    private fun Item.json() = JSONObject().put("ti", title).put("tx", text).put("t", time).put("a", app).toString()

    fun clear() {
        synchronized(notifs) { notifs.clear(); file.delete() }
        publish()
    }

    // ---------------------------------------------------------------- search

    private fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    private fun texts(limit: Int = 400): List<Item> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        val out = ArrayList<Item>()
        val since = System.currentTimeMillis() - 90 * 86_400_000L
        runCatching {
            app.contentResolver.query(
                Uri.parse("content://sms"), arrayOf("address", "body", "date", "type"),
                "date > ?", arrayOf(since.toString()), "date DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val addr = c.getString(0) ?: ""
                    val who = runCatching { Contacts.nameForNumber(app, addr) }.getOrNull() ?: addr
                    val sent = c.getInt(3) == 2
                    out += Item("sms", if (sent) "to $who" else "from $who", c.getString(1) ?: "", c.getLong(2))
                }
            }
        }
        return out
    }

    private fun calendar(): List<Item> {
        if (!has(Manifest.permission.READ_CALENDAR)) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Item>()
        runCatching {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now - 7 * 86_400_000L)
            ContentUris.appendId(b, now + 60 * 86_400_000L)
            app.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val f = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: continue
                    val loc = c.getString(2).orEmpty()
                    val desc = c.getString(3).orEmpty().take(200)
                    out += Item("calendar", title, listOf(f.format(Date(c.getLong(1))), loc.takeIf { it.isNotBlank() }?.let { "at $it" }, desc)
                        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "), c.getLong(1))
                }
            }
        }
        return out
    }

    private fun terms(q: String): List<String> =
        TextNorm.basic(q).split(' ').filter { it.length >= 3 && it !in STOP }.distinct()

    /** Best matches for a request, across notifications, texts and calendar. Fast: plain keyword scoring. */
    fun search(query: String, limit: Int = 5): List<Item> {
        val qs = terms(query)
        if (qs.isEmpty()) return emptyList()
        load()
        val now = System.currentTimeMillis()
        val pool = synchronized(notifs) { notifs.toList() } + snapshot()
        val scored = pool.mapNotNull { it ->
            val hay = TextNorm.basic("${it.title} ${it.text} ${it.app}")
            val words = hay.split(' ').toHashSet()
            var hit = 0f
            for (t in qs) {
                hit += when {
                    t in words || t.removeSuffix("s") in words -> 1f
                    t.length >= 5 && words.any { w -> w.length >= 5 && (w.startsWith(t.take(5)) || t.startsWith(w.take(5))) } -> 0.6f
                    else -> 0f
                }
            }
            if (hit <= 0f) return@mapNotNull null
            val coverage = hit / qs.size
            val ageDays = kotlin.math.abs(now - it.time) / 86_400_000f
            val recency = 0.25f / (1f + ageDays / 7f)
            // an address or time in the text makes it more useful for "directions to…", "when is…"
            val useful = if (Regex("\\d{2,5} [a-z]+ (st|street|ave|avenue|rd|road|blvd|dr|drive|ln|lane|way|ct|pl)\\b").containsMatchIn(hay)) 0.15f else 0f
            it to (coverage + recency + useful)
        }
        return scored.filter { it.second >= 0.55f }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * Items to hand the AI with a request (empty when off or nothing relevant). Only for requests about the
     * user's own stuff — "who won the World Series" must go to the web, not to a text that mentions it.
     */
    fun contextFor(query: String): List<Item> {
        if (!app.settings[K.contextLookup]) return emptyList()
        if (!PERSONAL.containsMatchIn(TextNorm.basic(query))) return emptyList()
        return runCatching { search(query, 5) }.getOrDefault(emptyList())
    }

    fun contextBlock(items: List<Item>): String =
        "[Possibly relevant, found on the user's phone — use only if it actually answers the request]\n" + items.joinToString("\n") { "- " + it.line() }

    companion object {
        private val PERSONAL = Regex(
            "\\b(my|mine|me|i|im|ive|id|our|ours|us|we|sent|send|texted|text|texts|message|messages|messaged|email|emailed|mail|" +
                "notification|notifications|said|told|asked|appointment|appt|reservation|booking|order|package|delivery|shipment|" +
                "tracking|flight|meeting|event|party|code|address|confirmation|bill|payment|receipt|invite|invitation)\\b"
        )
        private const val MAX = 1500
        private const val KEEP_DAYS = 30
        private val STOP = setOf(
            "the", "and", "for", "with", "you", "your", "are", "what", "when", "where", "who", "how", "can", "could", "would",
            "please", "get", "give", "tell", "show", "find", "need", "want", "have", "has", "had", "was", "this", "that",
            "there", "them", "they", "from", "about", "into", "directions", "direction", "navigate", "navigation", "maps",
            "map", "take", "drive", "route", "call", "text", "message", "send", "open", "set", "make", "next", "today",
            "tomorrow", "nova", "hey", "does", "did", "will", "just", "any", "some", "know", "remind", "check", "look",
        )
    }
}
