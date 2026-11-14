package app.nova.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.nova.assistant.agent.Calc
import app.nova.assistant.agent.Fmt
import app.nova.assistant.agent.TextParse
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Time, date, math, weather, location, calendar. */
object InfoTools {

    // ---------- HTTP / location helpers ----------
    private fun httpJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "NovaAssistant/0.1 (Android)")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun ToolContext.hasLocation() =
        has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    private suspend fun ToolContext.deviceLocation(): Location? {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val best = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused")
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null && System.currentTimeMillis() - best.time < 30 * 60_000) return best
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                val fresh = withTimeoutOrNull(8000) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cs = CancellationSignal()
                        try {
                            lm.getCurrentLocation(provider, cs, ContextCompat.getMainExecutor(ctx)) { loc ->
                                if (cont.isActive) cont.resume(loc)
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        }
                        cont.invokeOnCancellation { cs.cancel() }
                    }
                }
                if (fresh != null) return fresh
            }
        }
        return best
    }

    @Suppress("DEPRECATION")
    private suspend fun ToolContext.reverseGeocode(lat: Double, lon: Double): android.location.Address? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            runCatching { Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        }

    private data class Place(val lat: Double, val lon: Double, val name: String)

    private suspend fun geocode(name: String): Place? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(name.substringBefore(',').trim(), "UTF-8")
        val j = httpJson("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        val label = listOf(r.optString("name"), r.optString("admin1"))
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        Place(r.getDouble("latitude"), r.getDouble("longitude"), label.ifBlank { name })
    }

    private fun wmo(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55 -> "drizzly"
        56, 57 -> "freezing drizzle"
        61 -> "light rain"
        63 -> "rain"
        65 -> "heavy rain"
        66, 67 -> "freezing rain"
        71 -> "light snow"
        73 -> "snow"
        75 -> "heavy snow"
        77 -> "snow grains"
        80, 81 -> "rain showers"
        82 -> "heavy rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorms"
        96, 99 -> "thunderstorms with hail"
        else -> "mixed conditions"
    }

    private fun isSnow(code: Int) = code in 71..77 || code in 85..86

    // ---------- calendar helpers ----------
    private fun startOfDay(c: Calendar): Long {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private val weekdays = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    /** Returns (start of day millis, spoken label). */
    private fun dayStart(day: String): Pair<Long, String> {
        val d = day.lowercase().trim()
        val c = Calendar.getInstance()
        when {
            d.isEmpty() || d.contains("today") || d == "now" -> return startOfDay(c) to "today"
            d.contains("tomorrow") -> { c.add(Calendar.DAY_OF_YEAR, 1); return startOfDay(c) to "tomorrow" }
            d.contains("yesterday") -> { c.add(Calendar.DAY_OF_YEAR, -1); return startOfDay(c) to "yesterday" }
            d.contains("week") -> return startOfDay(c) to "this week"
        }
        weekdays.indexOfFirst { d.contains(it) || d.startsWith(it.take(3)) }.takeIf { it >= 0 }?.let { idx ->
            val today = c.get(Calendar.DAY_OF_WEEK) - 1
            var diff = (idx - today + 7) % 7
            if (diff == 0 && d.contains("next")) diff = 7
            c.add(Calendar.DAY_OF_YEAR, diff)
            return startOfDay(c) to "on ${weekdays[idx].replaceFirstChar { it.uppercase() }}"
        }
        parseIso(d)?.let { c.timeInMillis = it; return startOfDay(c) to "on ${SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(it))}" }
        TextParse.parseWhen(d)?.let { c.timeInMillis = it; return startOfDay(c) to "on ${SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(it))}" }
        return startOfDay(Calendar.getInstance()) to "today"
    }

    private fun parseIso(s: String): Long? {
        val t = s.trim()
        for (f in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")) {
            try {
                val df = SimpleDateFormat(f, Locale.US).apply { isLenient = false }
                return df.parse(t)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun ToolContext.primaryCalendarId(): Long? {
        val cr = ctx.contentResolver
        val sel = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        try {
            cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY), sel, args, null,
            )?.use { cur ->
                var first: Long? = null
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (first == null) first = id
                    if (cur.getInt(1) == 1) return id
                }
                return first
            }
        } catch (t: Throwable) {
            AppLog.d("calendar", "IS_PRIMARY query failed, retrying", t)
        }
        return runCatching {
            cr.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), sel, args, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
    }

    val tools: List<Tool> = listOf(
        Tool("get_time", "", "Current time", "Info") {
            ok("It's ${Fmt.time(ctx, System.currentTimeMillis())}.")
        },

        Tool("get_date", "", "Today's date", "Info") {
            ok("Today is ${SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())}.")
        },

        Tool("calculate", "expression:str", "Evaluate a math expression exactly (use * / + - ^ % and parentheses)", "Info") { a ->
            val e = a.str("expression", "expr", "query", "math", "input") ?: return@Tool fail("What should I calculate?")
            val v = Calc.eval(e) ?: return@Tool fail("I couldn't work that out.")
            val r = Calc.format(v)
            ok("That's $r.", data = "$e = $r")
        },

        Tool("weather", "location?:str, day?:str today|tomorrow|week|weekend|<weekday>", "Current weather and forecast", "Info", "Checking the weather…") { a ->
            if (!settings[K.internetTools]) return@Tool fail("Internet tools are turned off in Nova's settings.")
            val locName = a.str("location", "city", "place", "where")
                ?.takeUnless { val l = it.lowercase().trim(); l == "here" || l == "current" || l == "my location" || l == "current location" }
            val day = (a.str("day", "when", "date", "time") ?: "today").lowercase()
            val place: Place = if (locName != null) {
                geocode(locName) ?: return@Tool fail("I couldn't find a place called $locName.")
            } else {
                if (!hasLocation()) {
                    requirePerms(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, why = "use your location")
                        ?.let { return@Tool it }
                }
                val loc = deviceLocation() ?: return@Tool fail("I couldn't get your location. Is location turned on?")
                val addr = reverseGeocode(loc.latitude, loc.longitude)
                Place(loc.latitude, loc.longitude, addr?.locality ?: addr?.subAdminArea ?: "your area")
            }
            val f = settings[K.tempUnit] != "celsius"
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7&temperature_unit=${if (f) "fahrenheit" else "celsius"}" +
                (if (f) "&wind_speed_unit=mph" else "")
            val j = try {
                withContext(Dispatchers.IO) { httpJson(url) }
            } catch (t: Throwable) {
                AppLog.d("weather", "fetch failed", t)
                return@Tool fail("I couldn't reach the weather service.")
            }
            val cur = j.getJSONObject("current")
            val temp = cur.optDouble("temperature_2m").roundToInt()
            val feels = cur.optDouble("apparent_temperature").roundToInt()
            val code = cur.optInt("weather_code")
            val wind = cur.optDouble("wind_speed_10m").roundToInt()
            val daily = j.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            data class Day(val date: Date, val code: Int, val hi: Int, val lo: Int, val pop: Int?)
            val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val days = (0 until dates.length()).map { i ->
                Day(
                    dayFmt.parse(dates.getString(i)) ?: Date(),
                    daily.getJSONArray("weather_code").optInt(i),
                    daily.getJSONArray("temperature_2m_max").optDouble(i).roundToInt(),
                    daily.getJSONArray("temperature_2m_min").optDouble(i).roundToInt(),
                    daily.getJSONArray("precipitation_probability_max").let { arr -> if (arr.isNull(i)) null else arr.optInt(i) },
                )
            }
            val wd = SimpleDateFormat("EEEE", Locale.getDefault())
            fun rain(d: Day) = d.pop?.takeIf { it >= 10 }?.let { ", $it percent chance of ${if (isSnow(d.code)) "snow" else "rain"}" } ?: ""
            fun dayLine(d: Day) = "${wmo(d.code)}, high ${d.hi}, low ${d.lo}${rain(d)}"
            val unitWord = "degrees"
            val message = when {
                day.contains("week") && !day.contains("end") || day.contains("forecast") ->
                    "This week in ${place.name}: " + days.take(7).joinToString("; ") {
                        "${wd.format(it.date).take(3)} ${wmo(it.code)} ${it.hi}/${it.lo}"
                    } + "."
                day.contains("weekend") -> {
                    val wk = days.filter { val c = Calendar.getInstance().apply { time = it.date }; c.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY) }
                    if (wk.isEmpty()) "I don't have the weekend forecast yet."
                    else "This weekend in ${place.name}: " + wk.joinToString("; ") { "${wd.format(it.date)}, ${dayLine(it)}" } + "."
                }
                day.contains("tomorrow") && days.size > 1 -> "Tomorrow in ${place.name}: ${dayLine(days[1])}."
                else -> {
                    val idx = weekdays.indexOfFirst { day.contains(it) }
                    val target = if (idx >= 0) days.firstOrNull { wd.format(it.date).lowercase().startsWith(weekdays[idx].take(3)) } else null
                    if (target != null && target != days.first()) "${wd.format(target.date)} in ${place.name}: ${dayLine(target)}."
                    else {
                        val t = days.firstOrNull()
                        "It's $temp $unitWord and ${wmo(code)} in ${place.name}" +
                            (if (kotlin.math.abs(feels - temp) >= 4) ", feels like $feels" else "") + "." +
                            (t?.let { " Today: high ${it.hi}, low ${it.lo}${rain(it)}." } ?: "")
                    }
                }
            }
            val data = buildString {
                append("place=${place.name}; now: $temp°${if (f) "F" else "C"}, feels $feels, ${wmo(code)}, wind $wind ${if (f) "mph" else "km/h"}\n")
                days.forEach { append("${dayFmt.format(it.date)} (${wd.format(it.date)}): ${wmo(it.code)}, hi ${it.hi}, lo ${it.lo}, precip ${it.pop ?: "?"}%\n") }
            }
            ToolOutcome(message, data = data, needsModel = true)
        },

        Tool("where_am_i", "", "The user's current location / address", "Info", "Finding you…") {
            if (!hasLocation()) {
                requirePerms(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, why = "use your location")
                    ?.let { return@Tool it }
            }
            val loc = deviceLocation() ?: return@Tool fail("I couldn't get your location. Is location turned on?")
            val addr = reverseGeocode(loc.latitude, loc.longitude)
            val line = addr?.getAddressLine(0)
            val msg = if (line != null) "You're near $line." else "You're at ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}."
            ok(msg, data = "lat=${loc.latitude}, lon=${loc.longitude}, accuracy=${loc.accuracy.roundToInt()}m, address=${line ?: "unknown"}")
        },

        Tool("calendar_events", "day?:str today|tomorrow|YYYY-MM-DD|<weekday>, days?:int", "List calendar events", "Info", "Checking your calendar…") { a ->
            requirePerms(Manifest.permission.READ_CALENDAR, why = "read your calendar")?.let { return@Tool it }
            val dayStr = a.str("day", "date", "when") ?: "today"
            val (start, label) = dayStart(dayStr)
            val days = (a.int("days", "count", "range") ?: if (dayStr.lowercase().contains("week")) 7 else 1).coerceIn(1, 31)
            val end = start + days * 86_400_000L
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, start)
            ContentUris.appendId(b, end)
            data class Ev(val title: String, val begin: Long, val allDay: Boolean, val location: String?)
            val events = mutableListOf<Ev>()
            ctx.contentResolver.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0) ?: continue
                    events += Ev(t, c.getLong(1), c.getInt(2) == 1, c.getString(3)?.takeIf { it.isNotBlank() })
                }
            }
            val multi = days > 1
            val wdShort = SimpleDateFormat("EEE", Locale.getDefault())
            val utcDay = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }
            fun spoken(e: Ev): String = when {
                e.allDay -> "${e.title}${if (multi) " on ${utcDay.format(Date(e.begin))}" else ""} (all day)"
                multi -> "${e.title} ${wdShort.format(Date(e.begin))} at ${Fmt.time(ctx, e.begin)}"
                else -> "${e.title} at ${Fmt.time(ctx, e.begin)}"
            }
            if (events.isEmpty()) return@Tool ok("Nothing on your calendar $label.", data = "No events $label.", needsModel = true)
            val head = events.take(4).map(::spoken)
            val list = if (head.size == 1) head[0] else head.dropLast(1).joinToString(", ") + " and " + head.last()
            val more = if (events.size > 4) ", plus ${events.size - 4} more" else ""
            val n = events.size
            val msg = "You have $n event${if (n == 1) "" else "s"} $label: $list$more."
            val data = events.joinToString("\n") { e ->
                "${if (e.allDay) utcDay.format(Date(e.begin)) + " all day" else Fmt.dateTime(ctx, e.begin)} — ${e.title}${e.location?.let { " @ $it" } ?: ""}"
            }
            ToolOutcome(msg, data = data, needsModel = true)
        },

        Tool(
            "add_calendar_event",
            "title:str, start:str \"YYYY-MM-DD HH:MM\", duration_minutes?:int, location?:str, all_day?:bool",
            "Add an event to the calendar", "Info", "Adding the event…",
        ) { a ->
            val title = a.str("title", "name", "summary", "event", "what") ?: return@Tool fail("What's the event called?")
            val startStr = a.str("start", "time", "date", "when", "at", "datetime") ?: return@Tool fail("When is it?")
            val start = parseIso(startStr) ?: TextParse.parseWhen(startStr)
                ?: return@Tool fail("I couldn't understand the time \"$startStr\".")
            val dur = (a.int("duration_minutes", "duration", "minutes", "length") ?: 60).coerceIn(5, 24 * 60)
            val allDay = a.bool("all_day", "allday", "all_day_event") == true || startStr.trim().matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            val loc = a.str("location", "place", "where")
            // all-day events are stored as UTC midnight
            val (dtStart, dtEnd) = if (allDay) {
                val local = Calendar.getInstance().apply { timeInMillis = start }
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
                }
                utc.timeInMillis to utc.timeInMillis + 86_400_000L
            } else start to start + dur * 60_000L
            val whenText = if (allDay) SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(start)) else Fmt.dateTime(ctx, start)

            if (has(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)) {
                val calId = primaryCalendarId()
                if (calId != null) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, dtStart)
                        put(CalendarContract.Events.DTEND, dtEnd)
                        put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                        if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
                        loc?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    }
                    val uri = runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
                        .onFailure { AppLog.d("calendar", "insert failed", it) }.getOrNull()
                    if (uri != null) return@Tool ok("Added $title on $whenText.")
                }
            }
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, if (allDay) start else dtStart)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, if (allDay) start + 86_400_000L else dtEnd)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            loc?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            if (launch(i)) ToolOutcome("I opened your calendar with $title on $whenText — just tap save.", dismiss = true)
            else fail("I couldn't find a calendar app.")
        },
    )
}
