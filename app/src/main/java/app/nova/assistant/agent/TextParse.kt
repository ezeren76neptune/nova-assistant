package app.nova.assistant.agent

import android.content.Context
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Parsing of spoken numbers, durations, clock times and dates. */
object TextParse {
    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )

    private fun clean(w: String) = w.lowercase().trim(',', '.', '?', '!', ';', ':')
    private fun isNumWord(w: String) = clean(w).let { it in units || it in tens }

    /** "twenty five minutes" -> "25 minutes", "seven thirty" -> "7 30", "one hundred and five" -> "105". */
    fun wordsToDigits(s: String): String {
        val toks = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            if (!isNumWord(toks[i])) { out += toks[i]; i++; continue }
            var total = 0L
            var cur = 0L
            var last = 0 // 0 start, 1 tens, 2 unit, 3 hundred, 4 thousand
            var j = i
            loop@ while (j < toks.size) {
                val t = clean(toks[j])
                when {
                    t in tens && last in setOf(0, 3, 4) -> { cur += tens.getValue(t); last = 1 }
                    t in units && (last in setOf(0, 3, 4) || (last == 1 && units.getValue(t) in 1..9)) -> { cur += units.getValue(t); last = 2 }
                    t == "hundred" && last in setOf(1, 2) && cur in 1..99 -> { cur *= 100; last = 3 }
                    t == "thousand" && last in setOf(1, 2, 3) -> { total += cur * 1000; cur = 0; last = 4 }
                    t == "and" && last in setOf(3, 4) && j + 1 < toks.size && isNumWord(toks[j + 1]) -> {}
                    else -> break@loop
                }
                j++
            }
            out += (total + cur).toString()
            i = j
        }
        return out.joinToString(" ")
    }

    /** First number in the text (digits or words). */
    fun firstNumber(s: String): Double? =
        Regex("(\\d+(?:\\.\\d+)?)").find(wordsToDigits(s))?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseDurationSeconds(s: String): Int? {
        var t = " " + wordsToDigits(s.lowercase()) + " "
        t = t.replace(Regex("\\b(a )?half an hour\\b|\\bhalf hour\\b"), "30 minutes")
            .replace(Regex("\\b(a )?quarter (of an |an )?hour\\b"), "15 minutes")
            .replace(Regex("\\b(an?|one) (hours?|minutes?|seconds?|mins?|secs?|hrs?)\\b"), "1 $2")
            .replace(Regex("(\\d+) and a half (hours?|minutes?|seconds?|mins?|secs?|hrs?)\\b"), "$1.5 $2")
        val re = Regex("(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b( and a half)?")
        var total = 0.0
        var found = false
        for (m in re.findAll(t)) {
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            val mult = when (m.groupValues[2].first()) { 'h' -> 3600.0; 'm' -> 60.0; else -> 1.0 }
            total += v * mult
            if (m.groupValues[3].isNotEmpty()) total += 0.5 * mult
            found = true
        }
        return if (found && total >= 1) total.roundToInt() else null
    }

    private val durationUnit = Regex("\\b(hours?|hrs?|minutes?|mins?|seconds?|secs?)\\b")

    private data class Clock(val h: Int, val m: Int, val meridiem: String?)

    private fun rawClock(input: String): Clock? {
        val t = " " + wordsToDigits(
            input.lowercase().replace("a.m.", "am").replace("p.m.", "pm").replace("o'clock", " ").replace("oclock", " ")
        ).replace(Regex("(\\d)(am|pm)\\b"), "$1 $2") + " "
        if (Regex("\\b(noon|midday)\\b").containsMatchIn(t)) return Clock(12, 0, "x")
        if (Regex("\\bmidnight\\b").containsMatchIn(t)) return Clock(0, 0, "x")
        val mer = when {
            Regex("\\b(am|in the morning|this morning)\\b").containsMatchIn(t) -> "am"
            Regex("\\b(pm|in the (afternoon|evening)|at night|tonight|this (afternoon|evening))\\b").containsMatchIn(t) -> "pm"
            else -> null
        }
        Regex("\\b(half|quarter) past (\\d{1,2})\\b").find(t)?.let {
            return Clock(it.groupValues[2].toInt(), if (it.groupValues[1] == "half") 30 else 15, mer)
        }
        Regex("\\bquarter (to|till|til) (\\d{1,2})\\b").find(t)?.let {
            val h = it.groupValues[2].toInt()
            return Clock(if (h == 0) 23 else h - 1, 45, mer)
        }
        Regex("\\b(\\d{1,2}) oh (\\d)\\b").find(t)?.let { return Clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), mer) }
        Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(t)?.let { return Clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), mer) }
        Regex("\\b(\\d{1,2}) ([0-5]\\d)\\b(?!\\s*(hours?|minutes?|mins?|seconds?|secs?|days?))").find(t)?.let {
            return Clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), mer)
        }
        Regex("\\b(?:at|for)? ?([0-2]?\\d)([0-5]\\d)\\s*(am|pm)\\b").find(t)?.let {
            return Clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3])
        }
        Regex("\\b(?:at|for) ([0-2]?\\d)([0-5]\\d)\\b").find(t)?.let {
            return Clock(it.groupValues[1].toInt(), it.groupValues[2].toInt(), mer)
        }
        Regex("\\b(\\d{1,2})\\s*(am|pm)\\b").find(t)?.let { return Clock(it.groupValues[1].toInt(), 0, it.groupValues[2]) }
        Regex("\\b(?:at|for|by|around) (\\d{1,2})\\b(?!\\s*(hours?|minutes?|mins?|seconds?|secs?|days?|%|percent))").find(t)?.let {
            return Clock(it.groupValues[1].toInt(), 0, mer)
        }
        if (!durationUnit.containsMatchIn(t)) {
            Regex("\\b(\\d{1,2})\\b").find(t)?.let { return Clock(it.groupValues[1].toInt(), 0, mer) }
        }
        return null
    }

    private fun to24(c: Clock, now: Calendar, preferDaytime: Boolean): Pair<Int, Int>? {
        var h = c.h
        val m = c.m
        if (h !in 0..23 || m !in 0..59) return null
        when (c.meridiem) {
            "x" -> return h to m
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
            null -> if (h in 1..12) {
                if (preferDaytime) {
                    if (h in 1..6) h += 12 // "tomorrow at 3" means 3 pm
                } else {
                    // next occurrence of h:mm, choosing between am and pm
                    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    val am = (h % 12) * 60 + m
                    val pm = am + 12 * 60
                    val cands = listOf(am, pm).map { if (it > nowMin) it - nowMin else it + 1440 - nowMin }
                    h = if (cands[0] <= cands[1]) h % 12 else h % 12 + 12
                }
            }
        }
        return h to m
    }

    /** Clock time in 24h. Without am/pm the next occurrence from now is chosen. */
    fun parseClockTime(s: String): Pair<Int, Int>? {
        val c = rawClock(s) ?: return null
        return to24(c, Calendar.getInstance(), preferDaytime = false)
    }

    private val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** Absolute time in millis for phrases like "in 20 minutes", "tomorrow at 5pm", "friday at 3", "2026-11-14 15:00". */
    fun parseWhen(s: String, now: Long = System.currentTimeMillis()): Long? {
        val t = s.lowercase().trim()
        val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ t]+(\\d{1,2}):(\\d{2}))?").find(t)?.let { m ->
            cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toIntOrNull() ?: 9, m.groupValues[5].toIntOrNull() ?: 0, 0)
            return cal.timeInMillis
        }
        if (Regex("\\b(in|after|from now|later)\\b").containsMatchIn(t) || Regex("^\\s*(an?|\\d+|[a-z]+) (hours?|minutes?|mins?|seconds?)").containsMatchIn(t)) {
            parseDurationSeconds(t)?.let { return now + it * 1000L }
        }
        var dayOffset: Int? = null
        when {
            Regex("\\bday after tomorrow\\b").containsMatchIn(t) -> dayOffset = 2
            Regex("\\btomorrow\\b").containsMatchIn(t) -> dayOffset = 1
            Regex("\\b(today|tonight|this (morning|afternoon|evening))\\b").containsMatchIn(t) -> dayOffset = 0
            else -> weekdays.forEachIndexed { i, d ->
                if (Regex("\\b$d\\b").containsMatchIn(t)) {
                    val today = cal.get(Calendar.DAY_OF_WEEK) - 1
                    var diff = (i - today + 7) % 7
                    if (diff == 0) diff = 7
                    if (Regex("\\bnext $d\\b").containsMatchIn(t) && diff < 7 && false) diff += 7
                    dayOffset = diff
                }
            }
        }
        val stripped = t.replace(Regex("\\b(day after tomorrow|tomorrow|today|next|this|on|${weekdays.joinToString("|")})\\b"), " ")
        val clock = rawClock(stripped)
        if (dayOffset == null && clock == null) return parseDurationSeconds(t)?.let { now + it * 1000L }
        cal.add(Calendar.DAY_OF_YEAR, dayOffset ?: 0)
        if (clock != null) {
            val hm = to24(clock, Calendar.getInstance().apply { timeInMillis = now }, preferDaytime = (dayOffset ?: 0) >= 1)
                ?: return null
            cal.set(Calendar.HOUR_OF_DAY, hm.first); cal.set(Calendar.MINUTE, hm.second)
        } else {
            val tonight = t.contains("tonight") || t.contains("evening")
            cal.set(Calendar.HOUR_OF_DAY, if (tonight) 20 else if (t.contains("afternoon")) 15 else 9)
            cal.set(Calendar.MINUTE, 0)
        }
        if (dayOffset == null && cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** Removes duration phrases ("for 10 minutes", "an hour and a half") — leaves the rest, e.g. a label. */
    fun removeDurations(s: String): String {
        var t = " " + wordsToDigits(s.lowercase()) + " "
        t = t.replace(Regex("\\b(for |of )?(a )?(half an hour|half hour|quarter (of an |an )?hour)\\b"), " ")
            .replace(Regex("\\b(for |of )?(\\d+(?:\\.\\d+)?|an?|one)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b( and a half)?( and)?"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun iso(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}

/** Human-friendly formatting for speech. */
object Fmt {
    fun duration(secs: Int): String {
        val h = secs / 3600
        val m = secs % 3600 / 60
        val s = secs % 60
        val parts = buildList {
            if (h > 0) add("$h hour${if (h == 1) "" else "s"}")
            if (m > 0) add("$m minute${if (m == 1) "" else "s"}")
            if (s > 0 && h == 0) add("$s second${if (s == 1) "" else "s"}")
        }
        return when (parts.size) {
            0 -> "0 seconds"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }

    fun clock(ctx: Context, h: Int, m: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        return android.text.format.DateFormat.getTimeFormat(ctx).format(c.time)
    }

    fun time(ctx: Context, millis: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(millis))

    fun date(millis: Long): String = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(millis))

    fun dateTime(ctx: Context, millis: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val dayDiff = ((startOfDay(c) - startOfDay(now)) / 86_400_000L).toInt()
        val day = when (dayDiff) {
            0 -> "today"
            1 -> "tomorrow"
            in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(c.time)
        }
        return "$day at ${time(ctx, millis)}"
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** Small, exact-enough calculator so math never depends on a tiny model's arithmetic. */
object Calc {
    fun eval(expr: String): Double? = try {
        val p = Parser(prepare(expr))
        val v = p.expr()
        p.skip()
        if (p.pos < p.s.length || v.isNaN() || v.isInfinite()) null else v
    } catch (_: Throwable) {
        null
    }

    fun format(v: Double): String {
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    fun fromWords(s: String): String? = fromSpeech(s)

    /** Turns spoken math into an expression, or null if it doesn't look like math. */
    fun fromSpeech(s: String): String? {
        var t = " " + TextParse.wordsToDigits(s.lowercase()) + " "
        t = t.replace(Regex("\\b(what's|what is|whats|calculate|compute|how much is|solve|equals?|is equal to)\\b"), " ")
            .replace(Regex("(\\d+(?:\\.\\d+)?)\\s*(%|percent) of"), "($1/100)*")
            .replace(Regex("\\bsquare root of\\b"), " sqrt ")
            .replace(Regex("\\bcube root of\\b"), " cbrt ")
            .replace(Regex("\\b(to the power of|raised to( the power of)?|to the)\\b"), "^")
            .replace(Regex("\\bsquared\\b"), "^2").replace(Regex("\\bcubed\\b"), "^3")
            .replace(Regex("\\b(plus|and)\\b"), "+").replace(Regex("\\bminus\\b"), "-")
            .replace(Regex("\\b(times|multiplied by|x)\\b"), "*").replace(Regex("\\b(divided by|over)\\b"), "/")
            .replace(Regex("\\bmod(ulo)?\\b"), "%")
            .replace('×', '*').replace('÷', '/')
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (!Regex("^[0-9.+\\-*/^%() a-z]+$").matches(t)) return null
        val words = Regex("[a-z]+").findAll(t).map { it.value }.toSet()
        if (!words.all { it in setOf("sqrt", "cbrt", "sin", "cos", "tan", "log", "ln", "abs", "pi", "e") }) return null
        if (!Regex("\\d").containsMatchIn(t)) return null
        if (!Regex("[+\\-*/^%]|sqrt|cbrt|sin|cos|tan|log|ln|abs").containsMatchIn(t)) return null
        return t
    }

    private fun prepare(e: String) = e.lowercase().replace('×', '*').replace('÷', '/').replace("**", "^").replace(",", "")

    private class Parser(val s: String) {
        var pos = 0
        fun skip() { while (pos < s.length && s[pos] == ' ') pos++ }
        fun eat(c: Char): Boolean { skip(); return if (pos < s.length && s[pos] == c) { pos++; true } else false }

        fun expr(): Double {
            var v = term()
            while (true) v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v }
        }

        fun term(): Double {
            var v = factor()
            while (true) v = when { eat('*') -> v * factor(); eat('/') -> v / factor(); eat('%') -> v % factor(); else -> return v }
        }

        fun factor(): Double {
            val b = unary()
            return if (eat('^')) Math.pow(b, factor()) else b
        }

        fun unary(): Double = when {
            eat('-') -> -unary()
            eat('+') -> unary()
            else -> primary()
        }

        fun primary(): Double {
            skip()
            if (eat('(')) { val v = expr(); eat(')'); return v }
            val start = pos
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDouble()
            }
            while (pos < s.length && s[pos].isLetter()) pos++
            return when (val name = s.substring(start, pos)) {
                "pi" -> Math.PI
                "e" -> Math.E
                "sqrt" -> Math.sqrt(unary())
                "cbrt" -> Math.cbrt(unary())
                "sin" -> Math.sin(Math.toRadians(unary()))
                "cos" -> Math.cos(Math.toRadians(unary()))
                "tan" -> Math.tan(Math.toRadians(unary()))
                "log" -> Math.log10(unary())
                "ln" -> Math.log(unary())
                "abs" -> abs(unary())
                else -> error("unexpected '$name'")
            }
        }
    }
}
