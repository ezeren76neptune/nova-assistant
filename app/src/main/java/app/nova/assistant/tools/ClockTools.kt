package app.nova.assistant.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import app.nova.assistant.R
import app.nova.assistant.agent.Fmt
import app.nova.assistant.agent.TextParse
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.ToolOutcome
import app.nova.assistant.service.Notifications
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Timers, alarms and reminders. */
object ClockTools {
    val tools = listOf(
        Tool("set_timer", "seconds:int, label?:str", "Start a countdown timer", "Clock", "Setting a timer…") { a -> setTimer(a) },
        Tool("cancel_timer", "label?:str", "Cancel a running timer", "Clock") { a -> cancelTimer(a) },
        Tool("timer_status", "", "Say how much time is left on running timers", "Clock") { timerStatus() },
        Tool("set_alarm", "hour:int 0-23, minute:int, label?:str, days?:[mon..sun]", "Set an alarm (24h time; days makes it repeat)", "Clock", "Setting an alarm…") { a -> setAlarm(a) },
        Tool("dismiss_alarm", "hour?:int, minute?:int, label?:str, all?:bool", "Turn off / dismiss an alarm", "Clock") { a -> dismissAlarm(a) },
        Tool("show_alarms", "", "Open the list of alarms", "Clock") {
            if (launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))) ok("Here are your alarms.") else fail("I couldn't open your alarms.")
        },
        Tool("next_alarm", "", "Tell when the next alarm will ring", "Clock") { nextAlarm() },
        Tool("set_reminder", "text:str, in_minutes?:int, at?:str \"YYYY-MM-DD HH:MM\" or \"HH:MM\"", "Remind the user about something at a time", "Clock", "Setting a reminder…") { a -> setReminder(a) },
        Tool("list_reminders", "", "List upcoming reminders", "Clock") { listReminders() },
        Tool("cancel_reminder", "text?:str, all?:bool", "Cancel reminders matching text (or all)", "Clock") { a -> cancelReminder(a) },
    )

    // ---------- timers ----------

    private fun ToolContext.setTimer(a: Args): ToolOutcome {
        val secs = a.int("seconds", "duration", "secs", "length")
            ?: a.int("minutes", "mins")?.let { it * 60 }
            ?: a.int("hours")?.let { it * 3600 }
            ?: a.str("time", "duration_text", "for")?.let { TextParse.parseDurationSeconds(it) }
        val label = a.str("label", "name", "message", "title")
        if (secs == null || secs <= 0) {
            return ToolOutcome("How long should the timer be?", ask = { ans ->
                val s = TextParse.parseDurationSeconds(ans)
                if (s == null || s <= 0) fail("Sorry, I didn't catch how long.") else startTimer(s, label)
            })
        }
        return startTimer(secs, label)
    }

    private fun ToolContext.startTimer(secs: Int, label: String?): ToolOutcome {
        val forLabel = label?.let { " for $it" } ?: ""
        val done = "Timer set for ${Fmt.duration(secs)}$forLabel."
        if (secs <= 86400 && settings[K.timerImpl] != "builtin") {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (launch(i)) return ok(done)
            AppLog.d("clock", "no clock app for timers, using built-in")
        }
        Reminders.add(ctx, System.currentTimeMillis() + secs * 1000L, label ?: "Timer", "timer", secs)
        return ok(done)
    }

    private fun ToolContext.cancelTimer(a: Args): ToolOutcome {
        val label = a.str("label", "name")
        val builtin = Reminders.list(ctx).filter { it.kind == "timer" }
        if (builtin.isNotEmpty()) {
            val n = Reminders.cancel(ctx) { it.kind == "timer" && (label == null || it.text.contains(label, ignoreCase = true)) }
            if (n > 0) return ok(if (n == 1) "Timer cancelled." else "Cancelled $n timers.")
        }
        if (settings[K.timerImpl] != "builtin" && launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))) {
            return ok("I opened your timers in the Clock app — tap stop or delete to cancel it.")
        }
        return ok("You don't have any timers running.")
    }

    private fun ToolContext.timerStatus(): ToolOutcome {
        val now = System.currentTimeMillis()
        val timers = Reminders.list(ctx).filter { it.kind == "timer" && it.at > now }.sortedBy { it.at }
        if (timers.isEmpty()) {
            if (settings[K.timerImpl] != "builtin" && launch(Intent(AlarmClock.ACTION_SHOW_TIMERS))) {
                return ok("Here are your timers.")
            }
            return ok("You don't have any timers running.")
        }
        val parts = timers.map {
            val left = ((it.at - now) / 1000).toInt().coerceAtLeast(1)
            val name = if (it.text.equals("Timer", true)) "Your timer" else "Your ${it.text} timer"
            "$name has ${Fmt.duration(left)} left"
        }
        return ok(parts.joinToString(". ") + ".", data = parts.joinToString("\n"))
    }

    // ---------- alarms ----------

    private val dayCodes = listOf(
        "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
    )
    private val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
    private val weekend = listOf(Calendar.SATURDAY, Calendar.SUNDAY)

    private fun parseDays(tokens: List<String>): List<Int> {
        val s = tokens.joinToString(" ").lowercase()
        if (s.isBlank()) return emptyList()
        if (Regex("every ?day|daily|all|everyday").containsMatchIn(s)) return dayCodes.map { it.second }
        val out = linkedSetOf<Int>()
        if (s.contains("weekday")) out += weekdays
        if (s.contains("weekend")) out += weekend
        Regex("[a-z]+").findAll(s).forEach { m ->
            val w = m.value
            if (w.startsWith("week")) return@forEach
            dayCodes.firstOrNull { w.startsWith(it.first) }?.let { out += it.second }
        }
        return out.sorted()
    }

    private fun daysText(days: List<Int>): String = when {
        days.isEmpty() -> ""
        days.size == 7 -> " every day"
        days.toSet() == weekdays.toSet() -> " on weekdays"
        days.toSet() == weekend.toSet() -> " on weekends"
        else -> " on " + days.mapNotNull { d -> dayCodes.firstOrNull { it.second == d }?.first?.replaceFirstChar { it.uppercase() } }.joinToString(", ")
    }

    private fun ToolContext.setAlarm(a: Args): ToolOutcome {
        var hour = a.int("hour", "hours", "h")
        var minute = a.int("minute", "minutes", "min", "m") ?: 0
        if (hour == null) {
            a.str("time", "at", "when")?.let { TextParse.parseClockTime(it) }?.let { hour = it.first; minute = it.second }
        }
        val label = a.str("label", "name", "message", "title")
        val days = parseDays(a.list("days", "repeat", "recurrence"))
        val h0 = hour
        if (h0 == null) {
            return ToolOutcome("What time should I set the alarm for?", ask = { ans ->
                val t = TextParse.parseClockTime(ans)
                if (t == null) fail("Sorry, I didn't catch the time.") else alarmAt(t.first, t.second, label, days)
            })
        }
        var h = h0
        val period = a.str("ampm", "period", "am_pm", "meridiem")?.lowercase()
        if (period != null) {
            if (period.startsWith("p") && h in 1..11) h += 12
            if (period.startsWith("a") && h == 12) h = 0
        }
        return alarmAt(h.coerceIn(0, 23), minute.coerceIn(0, 59), label, days)
    }

    private fun ToolContext.alarmAt(h: Int, m: Int, label: String?, days: List<Int>): ToolOutcome {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, settings[K.alarmSkipUi])
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        return if (launch(i)) ok("Alarm set for ${Fmt.clock(ctx, h, m)}${daysText(days)}${label?.let { ", labeled $it" } ?: ""}.")
        else fail("I couldn't find a clock app to set the alarm.")
    }

    private fun ToolContext.dismissAlarm(a: Args): ToolOutcome {
        val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        val hour = a.int("hour", "hours")
        val label = a.str("label", "name")
        val desc: String
        when {
            a.bool("all") == true -> {
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                desc = "Turning off your alarms."
            }
            hour != null -> {
                val minute = a.int("minute", "minutes") ?: 0
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                desc = "Turning off your ${Fmt.clock(ctx, hour.coerceIn(0, 23), minute.coerceIn(0, 59))} alarm."
            }
            label != null -> {
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                desc = "Turning off your $label alarm."
            }
            else -> {
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                desc = "Turning off your next alarm."
            }
        }
        return if (launch(i)) ok(desc) else fail("I couldn't reach your clock app.")
    }

    private fun ToolContext.nextAlarm(): ToolOutcome {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val info = am?.nextAlarmClock ?: return ok("You don't have any alarms set.")
        val t = info.triggerTime
        val text = "Your next alarm is ${dayWord(t)} at ${Fmt.time(ctx, t)}."
        return ok(text, data = text)
    }

    /** "today", "tomorrow", "Friday", "on Nov 30, 2026". */
    internal fun dayWord(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        fun dayIndex(x: Calendar) = x.get(Calendar.YEAR) * 400 + x.get(Calendar.DAY_OF_YEAR)
        val diff = dayIndex(c) - dayIndex(today)
        return when {
            diff == 0 -> "today"
            diff == 1 -> "tomorrow"
            diff in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(t))
            else -> "on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
        }
    }

    // ---------- reminders ----------

    private fun ToolContext.setReminder(a: Args): ToolOutcome {
        val text = a.str("text", "message", "what", "reminder", "title", "task")
        val now = System.currentTimeMillis()
        val at: Long? = a.int("in_minutes", "minutes", "in")?.let { now + it * 60_000L }
            ?: a.int("in_seconds", "seconds")?.let { now + it * 1000L }
            ?: a.int("in_hours", "hours")?.let { now + it * 3_600_000L }
            ?: a.str("at", "time", "when", "datetime", "date")?.let { TextParse.parseWhen(it, now) }
        if (text == null) {
            return ToolOutcome("What should I remind you about?", ask = { ans ->
                if (at != null) scheduleReminder(ans, at) else askWhen(ans)
            })
        }
        if (at == null) return askWhen(text)
        return scheduleReminder(text, at)
    }

    private fun ToolContext.askWhen(text: String): ToolOutcome =
        ToolOutcome("When should I remind you?", ask = { ans ->
            val now = System.currentTimeMillis()
            val t = TextParse.parseWhen(ans, now) ?: TextParse.parseDurationSeconds(ans)?.let { now + it * 1000L }
            if (t == null) fail("Sorry, I didn't catch when.") else scheduleReminder(text, t)
        })

    private fun ToolContext.scheduleReminder(text: String, at: Long): ToolOutcome {
        if (at <= System.currentTimeMillis()) return fail("That time has already passed.")
        val clean = text.trim().removePrefix("to ").trim()
        Reminders.add(ctx, at, clean, "reminder", 0)
        return ok("Okay, I'll remind you ${Fmt.dateTime(ctx, at)}: $clean.")
    }

    private fun ToolContext.listReminders(): ToolOutcome {
        val now = System.currentTimeMillis()
        val l = Reminders.list(ctx).filter { it.at > now }.sortedBy { it.at }
        if (l.isEmpty()) return ok("You don't have any reminders.")
        val lines = l.map {
            if (it.kind == "timer") "Timer ${it.text} ends ${Fmt.dateTime(ctx, it.at)}"
            else "${it.text} — ${Fmt.dateTime(ctx, it.at)}"
        }
        val spoken = "You have ${l.size} reminder${if (l.size == 1) "" else "s"}: " + lines.take(3).joinToString("; ") + "."
        return ok(spoken, data = lines.joinToString("\n"), needsModel = true)
    }

    private fun ToolContext.cancelReminder(a: Args): ToolOutcome {
        val text = a.str("text", "message", "what", "reminder")
        val all = a.bool("all") == true || text == null
        val n = Reminders.cancel(ctx) { it.kind == "reminder" && (all || it.text.contains(text!!, ignoreCase = true)) }
        return when {
            n == 0 && text != null -> ok("I couldn't find a reminder about $text.")
            n == 0 -> ok("You don't have any reminders.")
            n == 1 -> ok("Reminder cancelled.")
            else -> ok("Cancelled $n reminders.")
        }
    }
}

data class Rem(val id: Int, val at: Long, val text: String, val kind: String, val secs: Int)

/** Nova's own timers/reminders: AlarmManager → [ReminderReceiver] → notification (+ spoken). */
object Reminders {
    private const val PREFS = "nova_reminders"
    const val ACTION_FIRE = "app.nova.assistant.REMINDER_FIRE"
    const val ACTION_DISMISS = "app.nova.assistant.REMINDER_DISMISS"
    const val EXTRA_ID = "id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun list(ctx: Context): List<Rem> = runCatching {
        val a = JSONArray(prefs(ctx).getString("items", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            Rem(o.getInt("id"), o.getLong("at"), o.optString("text"), o.optString("kind", "reminder"), o.optInt("secs"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(ctx: Context, l: List<Rem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("kind", it.kind).put("secs", it.secs)) }
        prefs(ctx).edit { putString("items", a.toString()) }
    }

    @Synchronized
    fun add(ctx: Context, at: Long, text: String, kind: String, secs: Int): Rem {
        val p = prefs(ctx)
        val id = p.getInt("next_id", 7000).let { if (it > 2_000_000_000) 7000 else it }
        p.edit { putInt("next_id", id + 1) }
        val r = Rem(id, at, text, kind, secs)
        save(ctx, list(ctx) + r)
        schedule(ctx, r)
        AppLog.d("reminders", "scheduled $kind #$id '$text' at ${Date(at)}")
        return r
    }

    /** Removes matching items and their alarms; returns how many. */
    @Synchronized
    fun cancel(ctx: Context, pred: (Rem) -> Boolean): Int {
        val all = list(ctx)
        val gone = all.filter(pred)
        if (gone.isEmpty()) return 0
        val am = ctx.getSystemService(AlarmManager::class.java)
        gone.forEach { am?.cancel(pi(ctx, it.id)) }
        save(ctx, all - gone.toSet())
        return gone.size
    }

    /** Removes and returns the item (when it fires). */
    @Synchronized
    fun take(ctx: Context, id: Int): Rem? {
        val all = list(ctx)
        val r = all.firstOrNull { it.id == id } ?: return null
        save(ctx, all - r)
        return r
    }

    private fun pi(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, id,
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(ctx: Context, r: Rem) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = pi(ctx, r.id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p)
        } catch (e: SecurityException) {
            AppLog.d("reminders", "exact alarm denied, using inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, p)
        }
    }

    /** Re-arm after boot / app update: future ones re-scheduled, recently missed ones fire now. */
    @Synchronized
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val all = list(ctx)
        val keep = mutableListOf<Rem>()
        for (r in all) {
            when {
                r.at > now -> { keep += r; schedule(ctx, r) }
                now - r.at < 15 * 60_000L -> postNotification(ctx, r, missed = true)
                else -> AppLog.d("reminders", "dropping stale #${r.id} '${r.text}'")
            }
        }
        save(ctx, keep)
    }

    fun postNotification(ctx: Context, r: Rem, missed: Boolean = false) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val dismiss = PendingIntent.getBroadcast(
            ctx, r.id + 1_000_000,
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ID, r.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val timer = r.kind == "timer"
        val title = when {
            timer -> if (r.text.equals("Timer", true)) "Timer done" else "${r.text} timer done"
            missed -> "Missed reminder"
            else -> "Reminder"
        }
        val body = if (timer) "${Fmt.duration(r.secs)} timer finished" else r.text
        val n = NotificationCompat.Builder(ctx, Notifications.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(if (timer) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(dismiss)
            .setDeleteIntent(dismiss)
            .addAction(0, "Dismiss", dismiss)
            .build()
        if (timer) n.flags = n.flags or Notification.FLAG_INSISTENT
        try {
            nm.notify(r.id, n)
        } catch (t: Throwable) {
            AppLog.d("reminders", "notify failed", t)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, -1)
        when (intent.action) {
            Reminders.ACTION_DISMISS -> {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
            }
            else -> {
                val r = Reminders.take(ctx, id) ?: return
                AppLog.d("reminders", "firing ${r.kind} #${r.id} '${r.text}'")
                Reminders.postNotification(ctx, r)
                val app = ctx.app
                if (app.settings[K.speakReminders]) {
                    val pending = goAsync()
                    val finished = AtomicBoolean(false)
                    val finish = { if (finished.compareAndSet(false, true)) runCatching { pending.finish() } }
                    val say = if (r.kind == "timer") {
                        if (r.text.equals("Timer", true)) "Your timer is done." else "Your ${r.text} timer is done."
                    } else "Reminder: ${r.text}"
                    try {
                        app.speaker.speak(say) { finish() }
                    } catch (t: Throwable) {
                        AppLog.d("reminders", "speak failed", t); finish()
                    }
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 9000)
                }
            }
        }
    }
}
