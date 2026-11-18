package app.nova.assistant.agent

import app.nova.assistant.tools.WebSearch
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.TextNorm
import app.nova.assistant.tools.Contacts
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Result of the deterministic fast path. */
sealed interface Q {
    data class Tool(val name: String, val args: JSONObject = JSONObject()) : Q
    data class Say(val text: String, val dismiss: Boolean = false) : Q
    data class Ask(val question: String, val then: (String) -> Q?) : Q
}

/**
 * Rule-based recognizer for everyday commands. It runs before the LLM so timers, calls, texts,
 * toggles etc. are instant and work with no model at all. Conservative: returns null when unsure.
 */
object QuickCommands {
    private val cache = HashMap<String, Regex>()
    internal fun rx(p: String): Regex = synchronized(cache) { cache.getOrPut(p) { Regex(p) } }
    internal fun rxi(p: String): Regex = synchronized(cache) { cache.getOrPut("(?i)$p") { Regex(p, RegexOption.IGNORE_CASE) } }

    fun parse(input: String, app: NovaApp, lastTool: String? = null): Q? = try {
        Parser(app, input, lastTool).run()
    } catch (t: Throwable) {
        AppLog.d("quick", "parse failed for '$input'", t)
        null
    }

    /** Lowercase; a.m./p.m. → am/pm; keep digits, ':', '%', '.' between digits and + - * /. */
    fun normalize(s: String): String {
        var x = s.lowercase(Locale.US).replace('’', '\'').replace('‘', '\'')
        x = x.replace(Regex("""\ba\.\s?m\b\.?"""), "am").replace(Regex("""\bp\.\s?m\b\.?"""), "pm")
        x = x.replace(Regex("""(?<=\d)\.(?=\d)"""), "\u0001")
        x = x.replace(Regex("""(?<=[a-z])-|-(?=[a-z])"""), " ")
        x = x.replace(Regex("""[^a-z0-9:'%+*/\-\u0001 ]"""), " ")
        x = x.replace('\u0001', '.')
        x = x.replace(Regex("""(^|\s)'+|'+(?=\s|$)"""), " ")
        return x.replace(Regex("""\s+"""), " ").trim()
    }
}

internal fun args(vararg kv: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((k, v) in kv) when (v) {
        null -> {}
        is List<*> -> put(k, JSONArray(v))
        else -> put(k, v)
    }
}

private fun MatchResult.g(i: Int): String = groupValues.getOrElse(i) { "" }

private val FILLERS = listOf(
    "hey there", "hey", "hi", "hello", "ok", "okay", "yo", "um", "uh", "so",
    "can you please", "could you please", "would you please", "will you please", "please",
    "can you", "could you", "would you", "will you", "i want you to", "i need you to", "i'd like you to",
    "id like you to", "i would like you to", "go ahead and", "just", "kindly", "quickly",
)
private val TRAILERS = listOf("for me please", "please", "for me", "thanks", "thank you", "right now", "now", "if you can", "real quick")

private val RELATIONS = setOf(
    "mom", "mum", "mother", "mommy", "mama", "dad", "father", "daddy", "papa", "wife", "husband", "boyfriend",
    "girlfriend", "partner", "sister", "brother", "son", "daughter", "grandma", "grandpa", "grandmother",
    "grandfather", "aunt", "uncle", "babe", "honey", "boss",
)

private val TIME_WORDS = setOf(
    "in", "at", "on", "by", "this", "next", "tomorrow", "tonight", "today", "morning", "afternoon", "evening", "night",
    "noon", "midnight", "midday", "am", "pm", "oclock", "o'clock", "minute", "minutes", "min", "mins", "hour", "hours",
    "hr", "hrs", "second", "seconds", "sec", "secs", "day", "days", "week", "weeks", "a", "an", "and", "half", "quarter",
    "past", "to", "from", "now", "the", "after", "before", "of", "day", "later",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "mondays", "tuesdays", "wednesdays", "thursdays", "fridays", "saturdays", "sundays",
)

private class Parser(val app: NovaApp, input: String, val lastTool: String? = null) {
    val lower0: String
    val d: String
    val raw: String
    val rawT: String

    init {
        val prefixes = (app.settings.wakePhrases() + TextNorm.basic(app.settings[K.assistantName]) + FILLERS)
            .filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        lower0 = stripLead(QuickCommands.normalize(input), prefixes)
        d = TextParse.wordsToDigits(stripTrail(lower0))
        raw = stripRaw(input.replace('’', '\'').trim(), prefixes)
        rawT = raw.trimEnd('.', '!', '?', ' ', ',')
    }

    private fun stripLead(s0: String, prefixes: List<String>): String {
        var s = s0
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (p in prefixes) {
                if (s == p) { s = ""; changed = true; break }
                if (s.startsWith("$p ")) { s = s.substring(p.length + 1).trimStart(); changed = true; break }
            }
        }
        return s
    }

    private fun stripTrail(s0: String): String {
        var s = s0
        var changed = true
        while (changed) {
            changed = false
            for (t in TRAILERS) {
                if (s.endsWith(" $t") && s.length > t.length + 1) { s = s.dropLast(t.length + 1).trimEnd(); changed = true; break }
            }
        }
        return s
    }

    private fun stripRaw(input: String, prefixes: List<String>): String {
        val alt = prefixes.joinToString("|") { p -> p.split(' ').joinToString("""[\s,]+""") { Regex.escape(it) } }
        var r = Regex("""^(?:(?:$alt)(?:[\s,.!?:;]+|$))+""", RegexOption.IGNORE_CASE).replace(input, "")
        r = Regex("""(?:[\s,]+(?:please|thanks|thank you))+[\s.!]*$""", RegexOption.IGNORE_CASE).replace(r, "")
        return r.trim()
    }

    // ---------- helpers ----------
    fun rx(p: String) = QuickCommands.rx(p)
    fun rxi(p: String) = QuickCommands.rxi(p)
    fun has(p: String, s: String = d) = rx(p).containsMatchIn(s)
    fun full(p: String, s: String = d) = rx(p).matches(s)
    fun match(p: String, s: String = d) = rx(p).find(s)
    fun words(s: String = d) = if (s.isBlank()) 0 else s.trim().split(' ').size
    fun tool(name: String, vararg kv: Pair<String, Any?>) = Q.Tool(name, args(*kv))
    fun isQuestion() = has("""^(how|what|why|when|who|where|which|can i|is|are|does|do|did|should|could|would)\b""")

    fun run(): Q? {
        if (d.isEmpty()) return Q.Say("Yes? How can I help?")
        return dismiss() ?: followUp() ?: home() ?: timeDate() ?: timers() ?: reminders() ?: alarms() ?: calls() ?: messages() ?: news() ?: readers()
            ?: flashlight() ?: ringer() ?: volume() ?: media() ?: play() ?: navigation() ?: search() ?: system()
            ?: accessoryBattery() ?: battery() ?: dnd() ?: toggles() ?: brightness() ?: camera() ?: memory() ?: weather() ?: location()
            ?: calendar() ?: listening() ?: screen() ?: calc() ?: open()
    }

    // ---------- dismiss / time ----------
    fun dismiss(): Q? {
        val bye = """^(stop|cancel|never ?mind|nothing|forget it|that's all|thats all|that is all|that's it|thats it|no thanks|no thank you|bye|bye bye|goodbye|good bye|see you|see ya|later|shut up|be quiet|quiet|exit|close|dismiss|go away|all done|i'm done|im done|done|stop talking|nothing else|never mind that)$"""
        if (full(bye) || full(bye, lower0)) return Q.Say("", dismiss = true)
        val thanks = """^(thanks|thank you|thank you so much|thanks a lot|thanks so much|cheers|great thanks|perfect thanks|awesome thanks|ty|thx|appreciate it|thank you very much)$"""
        if (full(thanks) || full(thanks, lower0)) return Q.Say("You're welcome!", dismiss = true)
        return null
    }

    fun timeDate(): Q? {
        if (full("""^(what time is it|what's the time|whats the time|what is the time|tell me the time|time|current time|the time|what's the current time|whats the current time|what is the current time|do you know what time it is|do you have the time|time please|got the time|what time is it right now)$"""))
            return Q.Say("It's ${Fmt.time(app, System.currentTimeMillis())}.")
        if (full("""^((what's|whats|what is)( the| today's| todays)? date( today)?|what day is (it|today)( today)?|(what's|whats|what is) today|(today's|todays) date|what date is it( today)?|what is today's date|(what's|whats) the date today)$"""))
            return Q.Say("Today is ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}.")
        return null
    }

    // ---------- timers ----------
    fun timers(): Q? {
        if (!has("""\btimers?\b""")) return null
        if (full("""^(cancel|stop|delete|remove|clear|end|turn off|kill|dismiss|reset)( the| my| all| all my| all the| all of my| that| this)? timers?$""")) return tool("cancel_timer")
        match("""^(?:cancel|stop|delete|remove|clear|end|turn off|dismiss)(?: the| my)? (.+?) timer$""")?.let { return tool("cancel_timer", "label" to it.g(1)) }
        if (full("""^(how much time is left|how much time('s| is)? left|how long( is)? left|how much longer|time left|check( the| my)? timers?|timer status|how('s| is) (my|the) timer( doing)?|how much time is (left|remaining) on (my|the) timer|how long is left on (my|the) timer|(what's|whats) left on (my|the) timer|how much time (do i have )?left on (my|the) timer)$"""))
            return tool("timer_status")
        val setVerb = has("""^(set|start|create|make|put|add|begin|run|give me)\b""")
        if (isQuestion()) return null
        var secs = TextParse.parseDurationSeconds(d)
        if (secs == null) {
            match("""\btimer (?:for |of )?(\d+(?:\.\d+)?)$|^(?:a |an )?(\d+(?:\.\d+)?) timer$""")?.let { m ->
                val n = m.g(1).ifEmpty { m.g(2) }.toDoubleOrNull()
                if (n != null) secs = (n * 60).roundToInt()
            }
        }
        val s = secs
        if (s != null && s > 0 && words() <= 16) return tool("set_timer", "seconds" to s, "label" to timerLabel())
        if (setVerb && full("""^(set|start|create|make|add|begin)( me)?( a| an| the| another| new)? timer$""")) {
            return Q.Ask("How long should the timer be?") { ans ->
                val n = QuickCommands.normalize(ans)
                val v = TextParse.parseDurationSeconds(n)
                    ?: TextParse.wordsToDigits(n).trim().toDoubleOrNull()?.let { (it * 60).roundToInt() }
                v?.let { tool("set_timer", "seconds" to it) }
            }
        }
        return null
    }

    fun timerLabel(): String? {
        val stop = setOf("me", "a", "an", "the", "timer", "it", "now", "minutes", "seconds", "hours", "new", "another", "one", "countdown", "quick")
        val t = TextParse.removeDurations(d)
        val lab = match("""\b(?:called|named|labeled|labelled|label|for)\s+([a-z][a-z' ]*)$""", t)?.g(1)
            ?: match("""^(?:set|start|create|make)(?: a| an| the| my)? ([a-z][a-z ]*?) timer\b""", t)?.g(1)
        return lab?.trim()?.removePrefix("the ")?.removePrefix("my ")?.removeSuffix(" timer")
            ?.takeIf { it.isNotBlank() && it !in stop && it.split(' ').size <= 4 }
    }

    // ---------- reminders ----------
    fun reminders(): Q? {
        val m = rxi("""^(?:remind me|set (?:a |an )?reminder|create (?:a )?reminder|add (?:a )?reminder|make (?:a )?reminder|new reminder)(?:\s+(?:to|about|that|of))?\s*(.*)$""").find(rawT)
            ?: return null
        val body = m.g(1).trim()
        if (body.isEmpty()) {
            return Q.Ask("What should I remind you about?") { what ->
                Q.Ask("When should I remind you?") { w -> whenQ(what, w) }
            }
        }
        rxi("""^((?:in|at|on|tomorrow|tonight|today|this|next)\b.*?)\s+(?:to|about|that)\s+(.+)$""").find(body)?.let { mm ->
            val at = TextParse.parseWhen(QuickCommands.normalize(mm.g(1)))
            if (at != null) return reminder(mm.g(2), at)
        }
        splitTimeSuffix(body)?.let { (text, at) -> return reminder(text, at) }
        return Q.Ask("When should I remind you?") { w -> whenQ(body, w) }
    }

    fun whenQ(what: String, w: String): Q {
        val at = TextParse.parseWhen(QuickCommands.normalize(w))
        return if (at != null) reminder(what, at) else Q.Say("Sorry, I didn't catch when. Try again with a time.")
    }

    fun reminder(text: String, at: Long): Q {
        val t = text.trim().removePrefix("to ").removePrefix("about ").trim().trimEnd(',', '.')
        return tool("set_reminder", "text" to t, "at" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at)))
    }

    fun isTimeWord(w: String): Boolean {
        if (w in TIME_WORDS) return true
        return Regex("""^\d+(?:[:.]\d+)?(?:am|pm)?$""").matches(w)
    }

    /** Splits "call mom tomorrow at 5pm" into ("call mom", time) — leftmost all-time-words suffix that parses. */
    fun splitTimeSuffix(body: String): Pair<String, Long>? {
        val ws = body.split(Regex("\\s+"))
        val starters = setOf("in", "at", "on", "by", "tomorrow", "tonight", "today", "this", "next", "noon", "midnight") +
            listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        for (i in 1 until ws.size) {
            val w = ws[i].lowercase(Locale.US).trim(',', '.', '!', '?')
            if (w !in starters) continue
            val norm = TextParse.wordsToDigits(QuickCommands.normalize(ws.drop(i).joinToString(" ")))
            if (!norm.split(' ').all { isTimeWord(it) }) continue
            val at = TextParse.parseWhen(norm) ?: continue
            return ws.take(i).joinToString(" ").trimEnd(',', ' ') to at
        }
        return null
    }

    // ---------- alarms ----------
    fun alarms(): Q? {
        val isWake = has("""^wake me( up)?\b""")
        if (!has("""\balarms?\b""") && !isWake) return null
        if (has("""^(when|what time) is my (next )?alarm|^(what's|whats|what is) my next alarm|^do i have (any |an )?alarms?( set)?|^is there an alarm( set)?|^when('s| is) my alarm|^what time('s| is) my alarm"""))
            return tool("next_alarm")
        if (full("""^(show|open|list|see|view|check)( me)?( all)?( my| the)? alarms$|^alarms$""")) return tool("show_alarms")
        match("""^(?:cancel|delete|remove|turn off|dismiss|disable|clear|stop|get rid of|switch off|kill)( all| all my| all of my| all the)?(?: my| the)? alarms?(.*)$""")?.let { m ->
            val rest = m.g(2).trim()
            if (m.g(1).isNotBlank() || (rest.isEmpty() && d.contains("alarms"))) return tool("dismiss_alarm", "all" to true)
            labelOf(rest)?.let { return tool("dismiss_alarm", "label" to it) }
            TextParse.parseClockTime(rest)?.let { (h, mi) -> return tool("dismiss_alarm", "hour" to h, "minute" to mi) }
            return tool("dismiss_alarm")
        }
        match("""^(?:cancel|delete|remove|turn off|disable|dismiss|switch off)(?: my| the)? (.+?) alarm$""")?.let { m ->
            TextParse.parseClockTime(m.g(1))?.let { (h, mi) -> return tool("dismiss_alarm", "hour" to h, "minute" to mi) }
            return tool("dismiss_alarm", "label" to m.g(1))
        }
        val setLike = isWake || has("""^(set|create|make|add|put|schedule|start|new)\b.*\balarm""") ||
            has("""^(an |a |my )?alarm\b""") || has("""\balarm (for|at) """)
        if (!setLike || isQuestion()) return null
        val days = alarmDays()
        val label = labelOf(d)
        var rest = d.replace(rx("""\b(called|named|labeled|labelled|label|saying|that says)\b.*$"""), " ")
        match("""\bin (.+)$""", rest)?.let { mm ->
            TextParse.parseDurationSeconds(mm.g(1))?.let { secs ->
                val c = Calendar.getInstance().apply { add(Calendar.SECOND, secs) }
                return tool("set_alarm", "hour" to c.get(Calendar.HOUR_OF_DAY), "minute" to c.get(Calendar.MINUTE), "label" to label)
            }
        }
        rest = rest.replace(rx("""\b(every ?day|daily|weekdays?|weekends?|every|on|mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|saturdays?|sundays?|and|tomorrow)\b"""), " ")
        val t = TextParse.parseClockTime(rest)
        if (t == null) {
            if (full("""^(set|create|make|add)( me)?( an| a| my| the)?( new)? alarm$""") || (isWake && words() <= 3)) {
                return Q.Ask("What time should I set the alarm for?") { ans ->
                    TextParse.parseClockTime(TextParse.wordsToDigits(QuickCommands.normalize(ans)))?.let { (h, m) ->
                        tool("set_alarm", "hour" to h, "minute" to m, "label" to label, "days" to days)
                    }
                }
            }
            return null
        }
        return tool("set_alarm", "hour" to t.first, "minute" to t.second, "label" to label, "days" to days)
    }

    fun labelOf(s: String): String? =
        match("""\b(?:called|named|labeled|labelled|label|saying|that says)\s+(.+)$""", s)?.g(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun alarmDays(): List<String>? {
        val all = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        return when {
            has("""\b(every ?day|daily|each day|every single day)\b""") -> all
            has("""\bweekdays?\b""") && !has("""\bweekends?\b""") -> all.take(5)
            has("""\bweekends?\b""") -> listOf("sat", "sun")
            else -> {
                val map = listOf("monday" to "mon", "tuesday" to "tue", "wednesday" to "wed", "thursday" to "thu", "friday" to "fri", "saturday" to "sat", "sunday" to "sun")
                val found = map.filter { (w, _) -> has("""\b${w}s?\b""") }.map { it.second }
                if (found.isNotEmpty() && (has("""\bevery\b""") || has("""\b(mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays)\b"""))) found else null
            }
        }
    }

    // ---------- calls ----------
    fun calls(): Q? {
        if (full("""^(call( them| him| her| it)? back|return (the |my |that )?(last |missed )?call|redial|call (the )?last (number|caller|person)|call back (the )?last (number|person|caller)|call the last person (who|that) called( me)?|call back whoever (just )?called( me)?|call back the missed call)$"""))
            return tool("call_back")
        match("""^give (.+?) a (?:call|ring)$""")?.let { return callTool(it.g(1)) }
        val m = match("""^(?:call|phone|dial|ring|facetime|video call)(?: up)? (.+?)(?: on (?:the )?speaker(?: ?phone)?| on speakerphone| right away)?$""")
            ?: return null
        return callTool(m.g(1))
    }

    fun callTool(who0: String): Q? {
        val who = who0.trim().trim(',', '.')
        if (who.isEmpty() || who in setOf("me", "it", "that", "this", "them", "him", "her") ||
            Regex("""^(me|it|a|an)\b""").containsMatchIn(who)) return null
        return tool("call", "contact" to who)
    }

    // ---------- messages ----------
    fun score(name: String): Float {
        val n = TextNorm.basic(name).removePrefix("my ")
        if (n in RELATIONS) return 0.8f
        if (Regex("""^[+\d][\d\s()-]{2,}$""").matches(name.trim())) return 1f
        return try { Contacts.bestScore(app, name) } catch (t: Throwable) { 0f }
    }

    fun cleanName(s: String) = s.trim().trim(',', '.', ':', ';', '!', '?', '"').trim().removePrefix("to ").trim()
    fun cleanMsg(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()

    fun split(rest: String, threshold: Float = 0.75f): Pair<String, String>? {
        val ws = rest.trim().split(Regex("\\s+"))
        if (ws.size < 2) return null
        var bestK = 0
        var bestScore = 0f
        for (k in 1..minOf(3, ws.size - 1)) {
            val sc = score(cleanName(ws.take(k).joinToString(" ")))
            if (sc >= threshold && sc > bestScore + 0.01f) { bestK = k; bestScore = sc }
        }
        if (bestK == 0) return null
        val msg = ws.drop(bestK).joinToString(" ")
            .replace(Regex("""^(?:saying|that says|that|to say|says)[\s:,]+""", RegexOption.IGNORE_CASE), "")
        if (msg.isBlank()) return null
        return cleanName(ws.take(bestK).joinToString(" ")) to cleanMsg(msg)
    }

    fun messages(): Q? {
        rxi("""^reply to (.+?)[\s,]+(?:saying|that|with|and say)[\s:,]+(.+)$""").find(rawT)?.let {
            return tool("reply_notification", "to" to cleanName(it.g(1)), "message" to cleanMsg(it.g(2)))
        }
        rxi("""^(?:send |write |compose |draft )?(?:an |a )?e-?mail (?:to )?(.+?)(?:[\s,]+(?:saying|that says|about|with subject|that)[\s:,]+(.+))?$""").find(rawT)?.let {
            if (!has("""^(send |write |compose |draft )?(an |a )?e ?mail\b""")) return@let
            if (has("""^(check|read|open)\b""")) return@let
            return tool("compose_email", "to" to cleanName(it.g(1)), "body" to it.g(2).ifBlank { null })
        }
        var r = raw
        var appName: String? = null
        rxi("""\s*\b(?:on|via|using|through|with|in|over)\s+(whatsapp|whats app|telegram|signal|facebook messenger|messenger)\b""").find(r)?.let {
            appName = canonApp(it.g(1)); r = r.removeRange(it.range).trim()
        }
        rxi("""^(?:send\s+(?:a\s+|an\s+)?)?(whatsapp|whats app|telegram)\b""").find(r)?.let { if (appName == null) appName = canonApp(it.g(1)) }
        val app = appName
        val verb = """(?:send\s+(?:a\s+|an\s+)?)?(?:text|message|sms|txt|whatsapp|whats app|telegram|imessage|msg)(?:\s+message)?"""
        val sayW = """(?:saying|that says|which says|to say|that|says|with the message|with message|and say|and tell (?:them|him|her))"""

        fun sendTool(c: String, msg: String): Q =
            if (app != null) tool("send_app_message", "app" to app, "contact" to c, "message" to msg)
            else tool("send_text", "contact" to c, "message" to msg)

        fun send(contact: String, msg: String?): Q {
            val c = cleanName(contact)
            if (msg.isNullOrBlank()) return Q.Ask("What should the message say?") { ans -> sendTool(c, cleanMsg(ans)) }
            return sendTool(c, cleanMsg(msg))
        }

        rxi("""^$verb\s+to\s+(.+?)[\s,]+$sayW[\s:,]+(.+)$""").find(r)?.let { return send(it.g(1), it.g(2)) }
        rxi("""^$verb\s+(.+?)[\s,]+$sayW[\s:,]+(.+)$""").find(r)?.let {
            if (!it.g(1).lowercase().startsWith("to ")) return send(it.g(1), it.g(2))
        }
        rxi("""^$verb\s+to\s+(.+)$""").find(r)?.let { m ->
            val rest = m.g(1).trimEnd('.', '!', '?')
            split(rest)?.let { (c, msg) -> return send(c, msg) }
            return send(rest, null)
        }
        rxi("""^(?:text|message|txt|sms|whatsapp|msg)\s+(.+)$""").find(r)?.let { m ->
            val rest = m.g(1)
            split(rest)?.let { (c, msg) -> return send(c, msg) }
            // just a name ("text Sam"): same bar as the contact lookup, then ask what to say
            if (score(cleanName(rest)) >= 0.6f && rest.trim().split(Regex("\\s+")).size <= 3) return send(rest, null)
            return null
        }
        rxi("""^tell\s+(?!me\b|us\b|a\b|an\b|the\b|you\b|everyone\b|everybody\b|them\b|him\b|her\b)(.+)$""").find(r)?.let { m ->
            split(m.g(1), 0.85f)?.let { (c, msg) ->
                return send(c, msg.replace(Regex("""^(that|to)\s+""", RegexOption.IGNORE_CASE), ""))
            }
        }
        return null
    }

    fun canonApp(s: String): String = when (s.lowercase(Locale.US).replace(" ", "")) {
        "whatsapp" -> "whatsapp"
        "telegram" -> "telegram"
        "signal" -> "signal"
        else -> "messenger"
    }

    // ---------- readers ----------
    fun readers(): Q? {
        match("""^(?:read|check|show|any|do i have any|what are|get|read out|tell me)(?: me)?(?: my| the)?(?: new| latest| recent| last| unread)? (?:text messages|texts|messages|sms|text|message)(?: from (.+))?$""")?.let {
            return tool("read_texts", "contact" to it.g(1).ifBlank { null })
        }
        match("""^(?:did|has) (.+?) (?:texted|text|messaged|message|sent) me(?: anything| back)?$""")?.let {
            return tool("read_texts", "contact" to it.g(1))
        }
        match("""^(?:read|check|what are|what're|any|do i have any|read out|tell me|what's in)(?: me)?(?: my| the)?(?: new| latest| recent| unread)? notifications?(?: from (.+))?$""")?.let {
            return tool("read_notifications", "app" to it.g(1).ifBlank { null })
        }
        if (full("""^(who (just )?called( me)?|(any |did i have any |do i have any |did i miss any )?missed calls?|(show|read|check|list)( me)?( my)? (recent|missed|last) calls?|who was (that|it)( calling)?|(what was|who is|who was) the last (call|caller|person who called( me)?)|recent calls|call (history|log))$""")) {
            return tool("recent_calls", "missed_only" to d.contains("missed"))
        }
        return null
    }

    // ---------- device ----------
    fun flashlight(): Q? {
        if (!has("""\b(flashlight|flash light|torch|torchlight)\b""") || words() > 8 || isQuestion()) return null
        val on = when {
            has("""\b(off|disable|deactivate|kill|stop)\b""") -> false
            has("""\b(on|enable|activate|start|light)\b""") -> true
            else -> null
        }
        return tool("flashlight", "on" to on)
    }

    fun ringer(): Q? {
        val m = match("""^(?:put|set|turn|switch|change|make)(?: my| the)? (?:phone|ringer|it|ring)(?: on| to| into| in)? (silent|vibrate|vibration|ring|normal|sound|loud)(?: mode)?$""")
            ?: match("""^(silent|vibrate|vibration|ring|normal|sound|ringer|loud) mode(?: on)?$""")
            ?: match("""^(?:turn on|enable|activate|go to|go into|switch to) (silent|vibrate|vibration) mode$""")
            ?: match("""^(vibrate)(?: only)?$""")
        if (m != null) return tool("ringer_mode", "mode" to ringMode(m.g(1)))
        if (full("""^(turn off|disable|exit) (silent|vibrate|vibration) mode$""")) return tool("ringer_mode", "mode" to "normal")
        if (full("""^(silence|mute) (my |the )?(phone|ringer|ringtone)$""")) return tool("ringer_mode", "mode" to "silent")
        if (full("""^(unmute|unsilence|un mute) (my |the )?(phone|ringer)$""")) return tool("ringer_mode", "mode" to "normal")
        return null
    }

    fun ringMode(s: String) = when (s) {
        "silent" -> "silent"
        "vibrate", "vibration" -> "vibrate"
        else -> "normal"
    }

    /** Home Assistant devices: "turn off the kitchen light", "open the garage", "is the garage door open". */
    fun home(): Q? {
        val ha = app.home
        if (!ha.isConfigured() || ha.entities.isEmpty()) return null
        // things that belong to the phone, not the house — only an exact device name may take these
        val phoneThing = Regex("""\b(wi ?fi|bluetooth|flash ?light|torch|airplane|flight mode|hotspot|location|gps|nfc|mobile data|data|rotation|auto rotate|dark mode|battery saver|do not disturb|dnd|volume|sound|music|brightness|screen|phone|alarms?|timers?|notifications?|camera|spotify|youtube)\b""")
        fun hit(name: String, domains: Set<String>? = null, min: Float = 0.8f): String? {
            val m = ha.match(name, domains) ?: return null
            val need = if (phoneThing.containsMatchIn(name)) 0.97f else min
            return m.first.id.takeIf { m.second >= need }
        }
        val houseWords = Regex("""\b(lights?|lamps?|fans?|garage|door|gate|heater|heat|ac|air conditioning|thermostat|tv|television|plug|outlet|blinds?|shades?|curtains?|lock|vacuum|sprinklers?|scene|room|kitchen|bedroom|living room|bathroom|office|porch|yard|basement|hallway|hall|downstairs|upstairs|house|everything)\b""")
        var action: String? = null
        var name: String? = null
        var value: String? = null
        match("""^(?:turn|switch|power)\s+(on|off)\s+(.+)$""")?.let { action = it.g(1); name = it.g(2) }
            ?: match("""^(?:turn|switch|power)\s+(.+?)\s+(on|off)$""")?.let { action = it.g(2); name = it.g(1) }
            ?: match("""^toggle\s+(.+)$""")?.let { action = "toggle"; name = it.g(1) }
            ?: match("""^(open|close|shut|lock|unlock)\s+(.+)$""")?.let { action = if (it.g(1) == "shut") "close" else it.g(1); name = it.g(2) }
            ?: match("""^(?:run|activate|start|trigger|execute|press|fire)\s+(.+?)(?:\s+(?:script|scene|automation|routine|button))?$""")?.let { action = "on"; name = it.g(1) }
            ?: match("""^(?:set|turn|put|dim|brighten)\s+(.+?)\s+(?:to|at)\s+(\d{1,3})\s*(?:%|percent|degrees?)?$""")?.let { action = "set"; name = it.g(1); value = it.g(2) }
            ?: match("""^(dim|brighten)\s+(.+)$""")?.let { action = if (it.g(1) == "dim") "down" else "up"; name = it.g(2) }
        val n = name?.trim()?.removePrefix("the ")?.removePrefix("my ")?.trim()
        if (action != null && !n.isNullOrEmpty()) {
            val domains = when (action) {
                "lock", "unlock" -> setOf("lock")
                "open", "close" -> setOf("cover", "lock", "valve", "script", "button", "input_button", "switch")
                else -> null
            }
            val id = hit(n, domains) ?: if (domains != null) hit(n) else null
            if (id != null) return tool("home_control", "device" to id, "action" to action, "value" to value)
            // clearly about the house but not a device Nova knows → Home Assistant's own Assist (rooms, areas, groups)
            if (app.settings[K.haAssistFallback] && houseWords.containsMatchIn(n) && !phoneThing.containsMatchIn(n))
                return tool("home_assist", "text" to rawT)
            return null
        }
        match("""^(?:is|are)\s+(?:the\s+|my\s+)?(.+?)\s+(?:still\s+)?(on|off|open|opened|closed|shut|locked|unlocked|running|home|away)$""")?.let { m ->
            hit(m.g(1), min = 0.75f)?.let { return tool("home_status", "device" to it) }
        }
        match("""^(?:what(?:'s| is)|check)\s+(?:the\s+)?(?:temperature|temp|status|state|humidity)\s+(?:of|in|on|for|at)\s+(?:the\s+)?(.+)$""")?.let { m ->
            hit(m.g(1), min = 0.7f)?.let { return tool("home_status", "device" to it) }
            if (app.settings[K.haAssistFallback]) return tool("home_assist", "text" to rawT)
        }
        return null
    }

    /** "set it to 50%", "a bit more", "turn it down" right after a volume/brightness change. */
    fun followUp(): Q? {
        val lt = lastTool ?: return null
        if (lt == "web_search") {
            // "open it" / "show me the page" after a spoken search
            if (!full("""^(?:ok(?:ay)? )?(?:open|show|pull up|bring up|go to)(?: me)?(?: it| that| this| them| the (?:page|link|site|website|article|results?|source|first (?:one|result|link)))?(?: (?:in|on) (?:the |my )?(?:browser|phone|screen))?(?: please)?$""")) return null
            val last = WebSearch.last ?: return null
            val url = (last.pages.firstOrNull()?.first ?: last.hits.firstOrNull())?.url
            return if (url != null && !has("""\bresults?\b""")) tool("open_url", "url" to url) else tool("web_search", "query" to last.query, "open" to true)
        }
        if (lt != "volume" && lt != "brightness") return null
        if (words() > 7) return null
        if (has("""\b(timer|alarm|call|text|message|remind|play|open|weather|search|navigate|note|flashlight|wifi|bluetooth)\b""")) return null
        if (!has("""\b(it|that|up|down|higher|lower|louder|quieter|brighter|dimmer|darker|more|less|again)\b|\d""")) return null
        if (lt == "volume") return volume(force = true)
        val num = match("""(\d{1,3})\s*(percent|%)?""")?.g(1)?.toIntOrNull()
        val up = has("""\b(up|higher|brighter|more|increase|raise)\b""")
        val down = has("""\b(down|lower|dimmer|darker|less|decrease|reduce)\b""")
        return when {
            num != null -> tool("brightness", "level" to num.coerceIn(0, 100))
            up -> tool("brightness", "action" to "up")
            down -> tool("brightness", "action" to "down")
            else -> null
        }
    }

    fun volume(force: Boolean = false): Q? {
        val explicit = force ||
            has("""\b(volume|louder|quieter|softer|mute|unmute|un mute)\b""") ||
            (has("""\b(sound|music|audio|media|ringer|ringtone)\b""") &&
                has("""\b(up|down|louder|quieter|lower|higher|raise|increase|decrease|reduce|max|maximum|percent)\b|%|\d""")) ||
            full("""^((it's|its|it is) too (loud|quiet)|too (loud|quiet)|i can't hear( it| that| anything)?|turn (it|that) (up|down)( a bit| a little| a lot| more| some)?)$""")
        if (!explicit) return null
        if (!force && has("""\b(brightness|bright|dim|screen)\b""")) return null
        val stream = match("""\b(media|music|ring|ringer|ringtone|alarm|notification|call|in call)\b""")?.g(1)?.let {
            when (it) {
                "music", "media" -> "media"
                "ring", "ringer", "ringtone" -> "ring"
                "alarm" -> "alarm"
                "notification" -> "notification"
                else -> "call"
            }
        }
        if (full("""^(unmute|un mute)( the| my)?( sound| volume| media| music| audio| it| video)?$""")) return tool("volume", "action" to "unmute", "stream" to stream)
        if (full("""^(mute|silence)( the| my| this)?( sound| volume| media| music| audio| it| video)?$""")) return tool("volume", "action" to "mute", "stream" to stream)
        if (has("""\b(what's|whats|what is|how loud|current|check)\b""") && !has("""\b(set|turn|make|up|down)\b"""))
            return tool("volume", "action" to "get", "stream" to stream)
        val up = has("""\b(up|louder|increase|raise|boost|higher|crank|pump|more)\b|too quiet|can't hear""")
        val down = has("""\b(down|quieter|softer|lower|decrease|reduce|less)\b|too loud""")
        // "by 20" → move by that much
        match("""\bby (\d{1,3})\s*(percent|%)?""")?.let { m ->
            val n = m.g(1).toInt().coerceIn(1, 100)
            return tool("volume", "action" to (if (down && !up) "down" else "up"), "by" to n, "stream" to stream)
        }
        if (has("""\b(max|maximum|full blast|all the way up)\b""")) return tool("volume", "action" to "max", "stream" to stream)
        if (has("""\b(min|minimum|all the way down)\b""")) return tool("volume", "action" to "set", "level" to 0, "stream" to stream)
        if (has("""\b(half|halfway)\b""")) return tool("volume", "action" to "set", "level" to 50, "stream" to stream)
        // "to 50" / "at 30%" → exact level
        match("""\b(?:to|at)\s+(\d{1,3})\s*(percent|%)?""")?.let { m ->
            return tool("volume", "action" to "set", "level" to m.g(1).toInt().coerceIn(0, 100), "stream" to stream)
        }
        match("""(\d{1,3})\s*(percent|%)?""")?.let { m ->
            var n = m.g(1).toInt()
            // "volume up 20 percent" (no "to") = move by 20
            if ((up || down) && n > 0) {
                return tool("volume", "action" to (if (down && !up) "down" else "up"), "by" to n.coerceIn(1, 100), "stream" to stream)
            }
            if (n <= 10 && m.g(2).isEmpty()) n *= 10   // "volume 7" ≈ 70 %
            return tool("volume", "action" to "set", "level" to n.coerceIn(0, 100), "stream" to stream)
        }
        if (up || down) {
            val by = when {
                has("""\b(a little|a bit|slightly|little bit|a tad|a touch|a smidge)\b""") -> 5
                has("""\b(a lot|way|much|lots|a bunch)\b""") -> 25
                else -> null
            }
            return tool("volume", "action" to (if (down && !up) "down" else "up"), "by" to by, "stream" to stream)
        }
        return null
    }

    fun media(): Q? {
        if (full("""^((what's|whats|what is)( this| the| that)? (song|track|music|playing)( called)?|what song is (this|playing|that)|who sings (this|that)( song)?|what am i listening to|name (this|that) song|(what's|whats) playing( now)?|what is playing( now)?)$"""))
            return tool("media_control", "action" to "now_playing")
        if (full("""^((pause|stop)( the| my| this)?( music| song| playback| video| media| audio| podcast| track| spotify| youtube| player| it| playing| everything)|pause)$"""))
            return tool("media_control", "action" to "pause")
        if (full("""^((resume|continue|unpause|un pause|keep playing)( the| my)?( music| song| playback| video| media| audio| podcast| track| it)?( again| playing)?|play( the| my)?( music| song| it)?( again)?|start( the| my)? (music|song|playback))$"""))
            return tool("media_control", "action" to "play")
        if (full("""^((next|skip)( song| track| one| this( song| track| one)?| it| ahead)?|(play|go to|skip to)( the)? next( song| track| one)?|skip (this|the|that) (song|track|one))$"""))
            return tool("media_control", "action" to "next")
        if (full("""^((previous|last|prior)( song| track| one)|previous|(play|go to|go back to)( the)? (previous|last) (song|track|one)|go back a (song|track)|replay( this| that| the)?( song| track)?|(restart|start over)( the| this)? (song|track))$"""))
            return tool("media_control", "action" to "previous")
        return null
    }

    fun play(): Q? {
        // "play spotify", "open spotify and play", "play music on spotify", "play some music", "resume spotify"
        val apps = "spotify|youtube music|yt music|apple music|amazon music|deezer|tidal|pandora|soundcloud"
        (rxi("""^(?:play|resume|put on|start playing)(?: some| my)?(?: music| songs| tunes)?(?:\s+(?:on|in|using|with|from))?\s+($apps)(?: music)?$""").find(rawT)
            ?: rxi("""^(?:open|launch|start|go to)\s+($apps)\s+and\s+(?:play|start playing|put on)(?: some| my)?(?: music| songs| tunes| something)?$""").find(rawT))?.let { mm ->
            return tool("play_media", "query" to "", "app" to musicKey(mm.g(1)))
        }
        if (full("""^(play|put on|start playing)( some| my| me some)? (music|songs|tunes)$|^play something$|^play some music$""")) return tool("play_media", "query" to "")
        val m = rxi("""^(?:play|put on|start playing|listen to|i want to (?:hear|listen to))\s+(.+?)(?:\s+(?:on|in|using|with|from)\s+(spotify|youtube music|yt music|youtube|pandora|soundcloud|apple music|amazon music|deezer|tidal))?$""").find(rawT)
            ?: return null
        val q = m.g(1).trim()
        if (q.isEmpty()) return null
        val appName = m.g(2).takeIf { it.isNotBlank() }?.let { musicKey(it) }
        return tool("play_media", "query" to q, "app" to appName)
    }

    fun musicKey(s: String) = when (s.lowercase(Locale.US)) {
        "yt music", "youtube music" -> "youtube_music"
        "apple music" -> "apple_music"
        "amazon music" -> "amazon_music"
        else -> s.lowercase(Locale.US)
    }

    /** "my dentist appointment", "the meeting", "Sam's party" — needs looking up, not a literal Maps search. */
    private val vaguePlace = Regex("""\b(my|our|the|that|this|next|his|her|their)\b.*\b(appointment|appt|meeting|event|reservation|class|party|interview|flight|dinner|lunch|breakfast|game|concert|show|dentist|doctor|doctors|orthodontist|therapist|vet|haircut|practice|rehearsal|shift|pickup|pick up|delivery)\b|\b\w+'s (house|place|apartment|party)\b""")

    fun navigation(): Q? {
        if (full("""^(navigate|take me|drive|get me|bring me|go|head|directions|guide me)( back)?( to)? home$""")) return tool("navigate", "destination" to "home")
        if (full("""^(navigate|take me|drive|get me|directions|guide me)( back)?( to)? (work|the office|my office)$""")) return tool("navigate", "destination" to "work")
        rxi("""^(?:navigate|drive|take me|get me|bring me|guide me|route me|start navigation|give me directions|get directions|show me directions|directions|show me the way|how do i get|how do i go|walk me|find a route|find directions|walking directions|driving directions|transit directions)(?: me)?(?: back)?(?: (?:to|towards|for))?\s+(.+?)(?:\s+(?:by|on|using|via)\s+(car|foot|bike|bicycle|transit|bus|train|public transport|public transit|walking))?$""").find(rawT)?.let { m ->
            val dest = m.g(1).trim().removePrefix("to ").trim()
            if (dest.isEmpty()) return null
            if (app.llm.isConfigured() && vaguePlace.containsMatchIn(dest.lowercase())) return null
            val modeWord = (m.g(2) + " " + d).lowercase(Locale.US)
            val mode = when {
                Regex("""\b(walk|walking|foot)\b""").containsMatchIn(modeWord) -> "walking"
                Regex("""\b(bike|biking|bicycle|cycling|bicycling)\b""").containsMatchIn(modeWord) -> "bicycling"
                Regex("""\b(transit|bus|train|subway|metro|public)\b""").containsMatchIn(modeWord) -> "transit"
                else -> null
            }
            return tool("navigate", "destination" to dest, "mode" to mode)
        }
        rxi("""^(?:show|find|where is|where's|locate|show me)(?: me)?(?: the| a)? (.+?) on (?:the |a )?maps?$""").find(rawT)?.let { return tool("show_map", "query" to it.g(1)) }
        rxi("""^(?:find |show me |search for |look for |where is |where's |are there any |is there a |is there an |any )?(?:the |a |an |some )?(?:nearest |closest |good |best )?(.+?) (?:near me|nearby|around here|close by|around me|in the area|close to me)$""").find(rawT)?.let {
            return tool("show_map", "query" to "${it.g(1)} near me")
        }
        rxi("""^(?:find|where is|where's|locate)(?: the| a)? (?:nearest|closest) (.+)$""").find(rawT)?.let { return tool("show_map", "query" to "${it.g(1)} near me") }
        rxi("""^(?:show me |open )?(?:the |a )?maps? (?:of|for) (.+)$""").find(rawT)?.let { return tool("show_map", "query" to it.g(1)) }
        return null
    }

    // ---------- news ----------
    fun news(): Q? {
        if (!has("""\b(news|headlines?)\b""") || words() > 12) return null
        if (has("""\b(play|podcast|app|open|launch|channel|text|message|send|remind|search|google|notification|notifications)\b""")) return null
        val about = match("""\b(?:news|headlines?)(?: stories| articles| updates)?\s+(?:about|on|regarding|for|from|in)\s+(.+)$""")?.groupValues?.get(1)
            ?.removePrefix("the ")?.takeIf { it !in setOf("today", "right now", "this morning", "tonight") }
        val section = match("""\b(world|business|tech|technology|science|health|sports?|entertainment|economy|finance|markets|national|international)\s+(?:news|headlines?)\b""")?.groupValues?.get(1)
        return tool("news", "topic" to (about ?: section))
    }

    fun search(): Q? {
        rxi("""^(?:open|do|start|pull up) (?:a |an )?(?:web |google |internet )?search (?:for|on|about) (.+)$""").find(rawT)?.let {
            return tool("web_search", "query" to it.g(1).trim(), "open" to true)
        }
        val m = rxi("""^(?:search(?: the web| google| online| the internet| the net)?(?: for)?|google|look up|lookup|web search(?: for)?|bing|duckduckgo|find (?:information|info|out) (?:on|about))\s+(.+)$""").find(rawT)
            ?: return null
        val q = m.g(1).trim()
        if (q.isEmpty() || Regex("""'s (phone )?number|\bcontact\b""").containsMatchIn(q.lowercase(Locale.US))) return null
        // "look it up" / "search that" refers to the conversation — let the AI fill in what "it" is
        if (Regex("""^(it|that|this|them|those)( up| online| for me| on the (web|internet))*$""").matches(q.lowercase(Locale.US))) return null
        // "search for X in the browser" / "open a search for X"
        val inBrowser = Regex("""\s+(in|on|with) (the |my )?(browser|chrome|firefox|google)$""").find(q)
        return if (inBrowser != null) tool("web_search", "query" to q.substring(0, inBrowser.range.first), "open" to true)
        else tool("web_search", "query" to q)
    }

    fun system(): Q? {
        val a = when {
            full("""^((go |take me |return |head |jump )?(back )?(to )?(the )?home( screen| page)?|(press|hit|tap)( the)? home( button)?|exit (this |the )?app|close (this |the )?app|minimize( this| the)?( app)?)$""") -> "home"
            full("""^((go )?back|(press|hit|tap|click)( the)? back( button)?|previous (screen|page)|go to the previous (screen|page))$""") -> "back"
            full("""^((show |open |go to )?(the |my )?(recent apps|recents|recent|app switcher|overview|multitasking|open apps|recent screens)|switch apps)$""") -> "recents"
            full("""^(open|show|pull down|expand|bring down|swipe down)( the| my)? notifications?( shade| panel| bar| drawer| center| centre)?$""") -> "notifications"
            full("""^((open|show|pull down|expand)( the)? quick (settings|toggles|panel|tiles)|quick settings)$""") -> "quick_settings"
            full("""^(close|hide|collapse|dismiss)( the| my)? (notifications?|notification (shade|panel)|quick settings|shade)$""") -> "close_shade"
            full("""^(lock( the| my)?( phone| screen| device| it)?|(turn|switch|shut) off( the)? (screen|display)|screen off)$""") -> "lock"
            full("""^((take|grab|capture|make|get|save)( a| me a)? (screenshot|screen shot|screen capture|screengrab|screen grab|picture of (the|my) screen)|screenshot( this| that| it)?|screen shot)$""") -> "screenshot"
            full("""^((open |show |bring up )?(the )?power (menu|options|button menu)|(power off|shut down|shutdown|turn off|restart|reboot|switch off)( my| the)? (phone|device)|restart|reboot|power off|shut down)$""") -> "power_menu"
            full("""^((enter |start |open |use |turn on )?split ?screen( mode)?|split the screen)$""") -> "split_screen"
            else -> null
        } ?: return null
        return tool("system_action", "action" to a)
    }

    /** "how much battery do my airpods have", "airpods battery", "check my headphones battery" */
    fun accessoryBattery(): Q? {
        if (!has("""\b(battery|batteries|charge|charged|percent|juice)\b""")) return null
        val m = match("""\b(air ?pods?( pro| max)?|ear ?buds?|ear ?pods|buds|beats|head ?phones?|head ?set|watch|galaxy buds|pixel buds|bose|sony)\b""")
            ?: return null
        return tool("device_battery", "device" to m.g(1).replace(" ", ""))
    }

    fun battery(): Q? {
        if (full("""^((what's|whats|what is|how's|hows|how is|check|tell me|show( me)?)?( my| the| phone| phone's| my phone's)? battery( level| percentage| percent| status| life| charge| health)?( at| left| like| now| level)?|how much (battery|charge|juice)( do i have| is| have i got)?( left)?|(am i|is (my |the )?phone) charging|how long (will|until) (my )?(battery|phone)( last| is full| full| be charged| charged))$"""))
            return tool("battery_status")
        return null
    }

    fun dnd(): Q? {
        if (!has("""\b(do not disturb|dnd|don't disturb|dont disturb|focus mode)\b""") || words() > 9 || isQuestion()) return null
        val on = when {
            has("""\b(off|disable|deactivate|stop|end|exit|cancel|leave)\b""") -> false
            has("""\b(on|enable|activate|start|put me|set|enter|go into|switch to|turn)\b""") -> true
            else -> return null
        }
        return tool("do_not_disturb", "on" to on)
    }

    fun toggles(): Q? {
        val names = linkedMapOf(
            "battery_saver" to listOf("battery saver mode", "battery saver", "battery saving mode", "battery saving", "power saving mode", "power saving", "power saver", "low power mode"),
            "airplane" to listOf("airplane mode", "aeroplane mode", "flight mode", "plane mode", "airplane"),
            "hotspot" to listOf("mobile hotspot", "wifi hotspot", "wi fi hotspot", "personal hotspot", "hotspot", "tethering"),
            "mobile_data" to listOf("mobile data", "cellular data", "cell data", "mobile internet", "cellular", "data"),
            "wifi" to listOf("wifi", "wi fi", "wireless", "wlan"),
            "bluetooth" to listOf("bluetooth", "blue tooth"),
            "location" to listOf("location services", "location", "gps"),
            "nfc" to listOf("nfc"),
            "auto_rotate" to listOf("auto rotate", "autorotate", "auto rotation", "screen rotation", "rotation"),
            "dark_mode" to listOf("dark mode", "dark theme", "night mode", "light mode", "light theme"),
        )
        val alt = names.values.flatten().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val m = match("""^(turn|switch|toggle|put|set|enable|disable|activate|deactivate|start|stop|go into|go to)?(?: the| my)?\s*(on|off)?\s*(?:the |my )?($alt)(?: mode| setting| feature)?\s*(on|off)?$""")
            ?: return null
        val verb = m.g(1)
        val state = m.g(2).ifBlank { m.g(4) }
        val name = m.g(3)
        if (verb.isBlank() && state.isBlank()) return null
        val setting = names.entries.first { name in it.value }.key
        var desired: Boolean? = when {
            state == "on" -> true
            state == "off" -> false
            verb in setOf("enable", "activate", "start", "go into", "go to") -> true
            verb in setOf("disable", "deactivate", "stop") -> false
            verb == "toggle" -> null
            else -> return null
        }
        if (name == "light mode" || name == "light theme") desired = desired?.not()
        return tool("toggle_setting", "setting" to setting, "on" to desired)
    }

    fun brightness(): Q? {
        if (!has("""\b(brightness|brighter|dimmer|dim|brighten|darker)\b""") || words() > 9 || has("""^(what|how|why)\b""")) return null
        match("""brightness(?: level)?(?: to| at)? (\d{1,3})(?: percent|%)?$""")?.let {
            var n = it.g(1).toInt()
            if (n <= 10 && !d.contains("percent") && !d.contains("%")) n *= 10
            return tool("brightness", "level" to n.coerceIn(0, 100))
        }
        val action = when {
            has("""\b(auto|automatic|adaptive)\b""") -> "auto"
            has("""\b(max|maximum|full|highest|all the way up)\b""") -> "max"
            has("""\b(min|minimum|lowest|all the way down)\b""") -> "min"
            has("""\b(up|increase|raise|brighter|brighten|more|higher|bump)\b""") -> "up"
            has("""\b(down|decrease|lower|reduce|dim|dimmer|darker|less)\b""") -> "down"
            else -> return null
        }
        return tool("brightness", "action" to action)
    }

    fun camera(): Q? {
        match("""^(?:take|snap|grab|shoot|capture|get)(?: a| me a| us a| an)? (photo|picture|pic|selfie|snapshot|shot|photograph)(?: of me| of us)?$""")?.let {
            return tool("take_photo", "selfie" to (it.g(1) == "selfie" || d.endsWith("of me") || d.endsWith("of us")))
        }
        if (full("""^((take|record|shoot|capture|start recording|film|make)( a| me a)? video( of me)?|record( a)? video|start (a )?video)$"""))
            return tool("take_photo", "video" to true, "selfie" to d.endsWith("of me"))
        if (full("""^(open|launch|start)( the)? (front|selfie) camera$""")) return tool("take_photo", "selfie" to true)
        if (full("""^((open|launch|start|show)( the| my)? (camera|camera app)|camera)$""")) return tool("take_photo")
        return null
    }

    // ---------- memory & notes ----------
    fun memory(): Q? {
        rxi("""^remember(?: that)?[\s,:]+(.+)$""").find(rawT)?.let {
            val f = it.g(1).trim()
            if (Regex("""^(me|when|what|how|who|where|why|if)\b""", RegexOption.IGNORE_CASE).containsMatchIn(f)) return null
            return tool("remember", "fact" to f)
        }
        rxi("""^(?:keep in mind|note|don't forget|dont forget) that\s+(.+)$""").find(rawT)?.let { return tool("remember", "fact" to it.g(1).trim()) }
        if (full("""^(what do you (know|remember) about me|what have i told you( about me)?|what do you remember|list( my| your)? memories|show( me)?( my| your)? memories|what are my memories|(what's|whats) in your memory)$"""))
            return tool("list_memories")
        rxi("""^forget(?: that| about| what i said about)?\s+(.+)$""").find(rawT)?.let {
            val f = it.g(1).trim()
            if (f.lowercase(Locale.US) in setOf("it", "that", "about it", "everything")) return null
            return tool("forget", "fact" to f)
        }
        rxi("""^(?:take|make|write|add|create|jot down|save|start)(?: a| me a| down a)? note(?: saying| that says| that| to say| to self| to myself| reading)?[\s:,]*(.*)$""").find(rawT)?.let {
            val t = it.g(1).trim()
            return if (t.isEmpty()) Q.Ask("What should the note say?") { ans -> tool("save_note", "text" to ans.trim()) }
            else tool("save_note", "text" to t)
        }
        rxi("""^note to (?:self|myself)[\s:,]+(.+)$""").find(rawT)?.let { return tool("save_note", "text" to it.g(1).trim()) }
        rxi("""^(?:add|put) (.+?) (?:to|on|in) (?:my |the )?(shopping|grocery|groceries|to do|todo|to-do) list$""").find(rawT)?.let {
            val list = if (it.g(2).lowercase(Locale.US).let { l -> l.startsWith("shop") || l.startsWith("groc") }) "Shopping list" else "To-do"
            return tool("save_note", "text" to "$list: ${it.g(1).trim()}")
        }
        if (full("""^((read|show|list|what are|what's in|whats in|open)( me)?( all)?( of)? my notes|my notes)$""")) return tool("list_notes")
        return null
    }

    // ---------- info ----------
    fun weather(): Q? {
        if (!has("""\b(weather|forecast|temperature|how hot|how cold|rain|raining|rainy|snow|snowing|umbrella|jacket|sunny|humid|humidity|windy|storm|stormy)\b""")) return null
        if (words() > 14) return null
        if (has("""\btemperature\b""") && !has("""\b(outside|today|tomorrow|weather|in [a-z]|out there|right now)""")) return null
        val day = when {
            has("""\btomorrow\b""") -> "tomorrow"
            has("""\b(this week|week|weekend|next few days|7 day|5 day|next week)\b""") -> "week"
            else -> "today"
        }
        val loc = match("""\b(?:in|for|at|near) ([a-z][a-z .'-]*?)(?: today| tomorrow| tonight| this week| this weekend| next week| right now| now| this morning| this afternoon| this evening| later)?$""")?.g(1)?.trim()
            ?.takeIf {
                it.isNotEmpty() && it !in setOf("the morning", "the afternoon", "the evening", "the area", "here", "my area", "my location", "town", "the week", "the weekend") &&
                    !it.startsWith("the next")
            }
        return tool("weather", "location" to loc?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(Locale.US) } }, "day" to day)
    }

    fun location(): Q? {
        if (full("""^(where am i|where are we|(what's|whats|what is) my (current )?(location|address)|what (city|town) am i in|where am i right now|where are we right now|my location|current location)$"""))
            return tool("where_am_i")
        return null
    }

    fun calendar(): Q? {
        // "what's next", "when's my next meeting", "what do I have left today" → from right now
        if (full("""^(?:what's|whats|what is|when's|whens|when is)(?: the| my)? next(?: thing| event| meeting| appointment| class| item)?(?: on| in| for)?(?: my| the)?(?: calendar| schedule| agenda)?(?: today)?$""") ||
            full("""^(?:what's|whats|what is)(?: the)? (?:next|coming up)(?: on my (?:calendar|schedule|agenda)| today| for me| for today)?$""") ||
            full("""^(?:what do i have|what have i got|do i have anything|is there anything)(?: else)? (?:next|coming up|later|later today|left today|else today|left on my calendar)$""") ||
            full("""^(?:what's|whats|what is) (?:left|remaining) (?:today|on my calendar|on my schedule)( today)?$""")
        ) return tool("calendar_events", "day" to "next")
        val period = """(today|tomorrow|this week|the week|next week)"""
        val m = match("""^(?:what's|whats|what is|show me|read me|tell me|check|list)?(?: on)?(?: my| the)? (?:calendar|schedule|agenda|events|meetings|appointments)(?: for| on| like)?(?: $period)?$""")
            ?: match("""^what do i have(?: going on| planned| scheduled| on)?(?: $period)$""")
            ?: match("""^(?:do i have|are there|have i got) any (?:meetings|events|appointments|plans)(?: $period)?$""")
            ?: match("""^(?:what's|whats|what is) (?:on )?my (?:calendar|schedule|agenda)(?: for)?(?: $period)?$""")
            ?: match("""^(?:what's|whats|what is) (?:next on my calendar|my next (?:meeting|event|appointment))$""")
            ?: return null
        val p = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: "today"
        return when (p) {
            "tomorrow" -> tool("calendar_events", "day" to "tomorrow")
            "this week", "the week" -> tool("calendar_events", "day" to "today", "days" to 7)
            "next week" -> {
                val c = Calendar.getInstance()
                val toMon = (Calendar.MONDAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                c.add(Calendar.DAY_OF_YEAR, if (toMon == 0) 7 else toMon)
                tool("calendar_events", "day" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time), "days" to 7)
            }
            else -> tool("calendar_events", "day" to "today")
        }
    }

    fun listening(): Q? {
        val m = match("""^(?:stop listening|go to sleep|pause listening|stop listening to me|turn off (?:the )?wake word|disable (?:the )?wake word|stop the wake word|don't listen|dont listen|stop listening for (?:the )?wake word|sleep|take a break)(?: for (\d+(?:\.\d+)?) (minutes?|mins?|hours?|hrs?))?$""")
            ?: return null
        val n = m.g(1).toDoubleOrNull()
        val minutes = n?.let { if (m.g(2).startsWith("h")) (it * 60).roundToInt() else it.roundToInt() }
        return tool("stop_listening", "minutes" to minutes)
    }

    fun screen(): Q? {
        match("""^(?:scroll|swipe|page)(?: the page| the screen| this)?(?: (up|down|left|right))?(?: a bit| a little)?$""")?.let {
            return tool("scroll", "direction" to it.g(1).ifBlank { "down" })
        }
        rxi("""^(?:type|input|enter text)[\s:,]+(.+?)(\s+and\s+(?:send|submit|press enter|hit enter|search|go))?$""").find(rawT)?.let {
            return tool("type_text", "text" to it.g(1).trim(), "submit" to it.g(2).isNotBlank())
        }
        rxi("""^(?:tap|click|press|hit|select|touch)(?: on)?(?: the)?\s+(.+?)(?:\s+(?:button|link|icon|tab|option))?$""").find(rawT)?.let {
            val t = it.g(1).trim().removeSurrounding("\"").removeSurrounding("'")
            if (t.isEmpty()) return null
            return tool("tap", "text" to t)
        }
        if (!app.llm.isConfigured() &&
            has("""^((what's|whats|what is) on (my|the|this) screen|read (my |the |this )?(screen|page)|(summarize|read) (this|the) (page|screen|article))""")
        ) return tool("read_screen")
        return null
    }

    fun calc(): Q? {
        val e = Calc.fromWords(d) ?: return null
        return tool("calculate", "expression" to e)
    }

    fun open(): Q? {
        val m = rxi("""^(?:open|launch|start|run|load|bring up|pull up|fire up|go to|switch to)(?: up)?(?: the| my)?\s+(.+?)(?:\s+(?:app|application))?$""").find(rawT)
            ?: return null
        val target = m.g(1).trim()
        val tl = QuickCommands.normalize(target)
        if (tl.isEmpty() || tl.split(' ').size > 5) return null
        when {
            tl in setOf("camera", "the camera", "camera app") -> return tool("take_photo")
            tl in setOf("settings", "phone settings", "system settings", "the settings", "device settings") -> return tool("open_settings")
            tl.endsWith(" settings") -> return tool("open_settings", "page" to tl.removeSuffix(" settings").removePrefix("the ").trim())
            tl.startsWith("settings for ") -> return tool("open_settings", "page" to tl.removePrefix("settings for ").trim())
        }
        val url = target.replace(Regex("\\s+dot\\s+", RegexOption.IGNORE_CASE), ".")
        if (Regex("""^(https?://)?(www\.)?[\w-]+(\.[\w-]+)*\.(com|org|net|io|dev|app|co|edu|gov|me|ai|tv|us|uk|de|ca|info|xyz)(/\S*)?$""", RegexOption.IGNORE_CASE).matches(url))
            return tool("open_url", "url" to url)
        return tool("open_app", "name" to target)
    }
}
