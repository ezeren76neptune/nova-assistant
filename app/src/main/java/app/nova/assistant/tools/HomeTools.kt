package app.nova.assistant.tools

import app.nova.assistant.core.K
import app.nova.assistant.core.Pending
import app.nova.assistant.core.ToolOutcome
import app.nova.assistant.home.HaEntity
import app.nova.assistant.home.HomeAssistant
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Home Assistant devices: control, status, and HA's own Assist as a catch-all. */
object HomeTools {
    private fun ToolContext.ha(): HomeAssistant? = app.home.takeIf { it.isConfigured() }

    private fun ToolContext.notConnected() =
        fail("Home Assistant isn't connected yet. Open Nova, then Home Assistant, and sign in.")

    /** Normalizes the many ways people/models say an action. */
    private fun normAction(a: String?, value: String?): String {
        val x = (a ?: "").lowercase().trim()
        return when {
            x.isEmpty() -> if (value != null) "set" else "toggle"
            x.contains("toggle") -> "toggle"
            x.contains("unlock") -> "unlock"
            x.contains("lock") -> "lock"
            x.contains("open") || x.contains("raise") -> "open"
            x.contains("close") || x.contains("shut") || x.contains("lower") -> "close"
            x.contains("stop") || x.contains("halt") -> "stop"
            x.contains("disarm") -> "disarm"
            x.contains("arm") -> if (x.contains("home") || x.contains("stay")) "arm_home" else if (x.contains("night")) "arm_night" else "arm_away"
            x.contains("pause") -> "pause"
            x.contains("next") || x.contains("skip") -> "next"
            x.contains("prev") -> "previous"
            x.contains("play") || x.contains("resume") -> "play"
            x.contains("dock") || x.contains("return") || x.contains("home base") -> "dock"
            x.contains("up") || x.contains("bright") || x.contains("increase") || x.contains("raise") -> "up"
            x.contains("down") || x.contains("dim") || x.contains("decrease") -> "down"
            x.contains("set") || x.contains("level") || x.contains("temp") || x.contains("bright") -> "set"
            x.contains("off") || x.contains("disable") || x.contains("deactivate") -> "off"
            x.contains("on") || x.contains("enable") || x.contains("activate") || x.contains("start") ||
                x.contains("run") || x.contains("trigger") || x.contains("press") || x.contains("execute") -> "on"
            else -> x
        }
    }

    /** Maps (entity, action, value) to a Home Assistant service call. */
    private fun serviceFor(e: HaEntity, action: String, value: String?): Pair<String, JSONObject>? {
        val d = e.domain
        val data = JSONObject().put("entity_id", e.id)
        val num = value?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()
        fun on() = "$d.turn_on" to data
        fun off() = "$d.turn_off" to data
        fun toggle() = "$d.toggle" to data
        return when (d) {
            "script" -> if (action == "off" || action == "stop") off() else "script.turn_on" to data
            "scene" -> "scene.turn_on" to data
            "button", "input_button" -> "$d.press" to data
            "automation" -> when (action) { "off" -> off(); "toggle" -> toggle(); "on" -> on(); else -> "automation.trigger" to data }
            "cover" -> when (action) {
                "open", "on", "up" -> "cover.open_cover" to data
                "close", "off", "down" -> "cover.close_cover" to data
                "stop", "pause" -> "cover.stop_cover" to data
                "set" -> num?.let { "cover.set_cover_position" to data.put("position", it.toInt().coerceIn(0, 100)) }
                else -> "cover.toggle" to data
            }
            "valve" -> when (action) { "open", "on" -> "valve.open_valve" to data; "close", "off" -> "valve.close_valve" to data; else -> "valve.toggle" to data }
            "lock" -> when (action) {
                "lock", "close", "on" -> "lock.lock" to data
                "unlock", "off" -> "lock.unlock" to data
                "open" -> "lock.open" to data
                else -> if (e.state == "locked") "lock.unlock" to data else "lock.lock" to data
            }
            "light" -> when (action) {
                "off" -> off(); "toggle" -> toggle()
                "set" -> num?.let { "light.turn_on" to data.put("brightness_pct", it.toInt().coerceIn(0, 100)) } ?: on()
                "up" -> "light.turn_on" to data.put("brightness_step_pct", num?.toInt() ?: 20)
                "down" -> "light.turn_on" to data.put("brightness_step_pct", -(num?.toInt() ?: 20))
                else -> on()
            }
            "fan" -> when (action) {
                "off" -> off(); "toggle" -> toggle()
                "set" -> num?.let { "fan.set_percentage" to data.put("percentage", it.toInt().coerceIn(0, 100)) } ?: on()
                "up" -> "fan.increase_speed" to data
                "down" -> "fan.decrease_speed" to data
                else -> on()
            }
            "climate", "water_heater" -> when (action) {
                "off" -> off(); "on" -> on()
                "set", "up", "down" -> {
                    val cur = e.attrs.optDouble("temperature", Double.NaN)
                    val t = num ?: if (!cur.isNaN()) cur + (if (action == "up") 1 else -1) else null
                    t?.let { "$d.set_temperature" to data.put("temperature", it) }
                }
                else -> toggle()
            }
            "media_player" -> when (action) {
                "play" -> "media_player.media_play" to data
                "pause", "stop" -> "media_player.media_pause" to data
                "next" -> "media_player.media_next_track" to data
                "previous" -> "media_player.media_previous_track" to data
                "set" -> num?.let { "media_player.volume_set" to data.put("volume_level", (it / 100.0).coerceIn(0.0, 1.0)) }
                "up" -> "media_player.volume_up" to data
                "down" -> "media_player.volume_down" to data
                "off" -> off(); "on" -> on()
                else -> "media_player.media_play_pause" to data
            }
            "vacuum" -> when (action) {
                "dock", "off", "close" -> "vacuum.return_to_base" to data
                "stop" -> "vacuum.stop" to data
                "pause" -> "vacuum.pause" to data
                else -> "vacuum.start" to data
            }
            "lawn_mower" -> when (action) { "dock", "off" -> "lawn_mower.dock" to data; "pause", "stop" -> "lawn_mower.pause" to data; else -> "lawn_mower.start_mowing" to data }
            "input_number", "number" -> num?.let { "$d.set_value" to data.put("value", it) }
            "input_select", "select" -> value?.let { "$d.select_option" to data.put("option", it) }
            "alarm_control_panel" -> when (action) {
                "disarm", "off" -> "alarm_control_panel.alarm_disarm" to data
                "arm_home" -> "alarm_control_panel.alarm_arm_home" to data
                "arm_night" -> "alarm_control_panel.alarm_arm_night" to data
                else -> "alarm_control_panel.alarm_arm_away" to data
            }
            "sensor", "binary_sensor", "weather", "person", "device_tracker", "sun", "zone", "camera" -> null
            else -> when (action) { "off" -> "homeassistant.turn_off" to data; "toggle" -> "homeassistant.toggle" to data; else -> "homeassistant.turn_on" to data }
        }
    }

    /** "Garage door is open", "Kitchen light is on at 60%", "Thermostat: heat, 68° (set to 70°)". */
    fun describe(e: HaEntity): String {
        val a = e.attrs
        val unit = a.optString("unit_of_measurement")
        return when (e.domain) {
            "light" -> if (e.state == "on" && a.has("brightness")) "${e.name} is on at ${Math.round(a.optDouble("brightness") / 2.55)}%" else "${e.name} is ${e.state}"
            "climate" -> buildString {
                append("${e.name} is ${e.state}")
                if (a.has("current_temperature")) append(", ${fmt(a.optDouble("current_temperature"))}°")
                if (a.has("temperature") && !a.isNull("temperature")) append(" (set to ${fmt(a.optDouble("temperature"))}°)")
            }
            "cover" -> "${e.name} is ${e.state}" + (if (a.has("current_position")) " (${a.optInt("current_position")}%)" else "")
            "media_player" -> "${e.name} is ${e.state}" + (a.optString("media_title").takeIf { it.isNotBlank() }?.let { ": $it" + (a.optString("media_artist").takeIf { x -> x.isNotBlank() }?.let { r -> " by $r" } ?: "") } ?: "")
            "button", "input_button" -> "${e.name} is a button" + (ago(e.state)?.let { ", last pressed $it" } ?: "")
            "script", "automation" -> "${e.name} is ${if (e.state == "on") "running" else if (e.domain == "automation") e.state else "idle"}" +
                (ago(e.attrs.optString("last_triggered"))?.let { ", last ran $it" } ?: "")
            "scene" -> "${e.name} is a scene" + (ago(e.state)?.let { ", last turned on $it" } ?: "")
            "sensor" -> "${e.name} is ${e.state}${if (unit.isNotBlank()) " $unit" else ""}"
            else -> "${e.name} is ${e.state}"
        }
    }

    /** "2 minutes ago" for an ISO timestamp, else null. */
    private fun ago(iso: String?): String? = runCatching {
        if (iso.isNullOrBlank() || !iso.contains('T')) return null
        val t = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        android.text.format.DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    }.getOrNull()

    private val stateless = setOf("button", "input_button", "script", "scene")

    private fun fmt(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else String.format("%.1f", d)

    private fun pastTense(e: HaEntity, service: String): String {
        val verb = service.substringAfter('.')
        return when {
            e.domain == "script" && verb == "turn_on" -> "Ran ${e.name}."
            e.domain == "scene" -> "${e.name} scene on."
            verb == "press" -> "Pressed ${e.name}."
            verb == "trigger" -> "Triggered ${e.name}."
            else -> ""
        }
    }

    private suspend fun ToolContext.control(e: HaEntity, action: String, value: String?): ToolOutcome {
        val ha = app.home
        val (service, data) = serviceFor(e, action, value)
            ?: return fail(if (e.domain in setOf("sensor", "binary_sensor")) describe(e) + "." else "I can't ${action} ${e.name}.")
        val run: suspend () -> ToolOutcome = {
            val ok = ha.callService(service.substringBefore('.'), service.substringAfter('.'), data)
            if (!ok) fail("Home Assistant didn't accept that for ${e.name}.")
            else {
                pastTense(e, service).takeIf { it.isNotEmpty() }?.let { ok(it) } ?: run {
                    delay(900)
                    val now = runCatching { ha.state(e.id) }.getOrNull() ?: e
                    ok(describe(now) + ".")
                }
            }
        }
        if (ha.isSensitive(e, action)) {
            val verb = when (action) { "unlock" -> "Unlock"; "open" -> "Open"; "disarm" -> "Disarm"; else -> "Run" }
            return ToolOutcome("$verb ${e.name}?", confirm = Pending("$verb ${e.name}?", verb, run))
        }
        return run()
    }

    private suspend fun ToolContext.resolve(name: String, action: String?): Pair<HaEntity, Float>? {
        val ha = app.home
        if (ha.entities.isEmpty()) runCatching { ha.refresh() }
        val domains = when (action) {
            "lock", "unlock" -> setOf("lock")
            "open", "close" -> setOf("cover", "lock", "valve", "script", "button", "input_button", "switch")
            else -> null
        }
        return ha.match(name, domains)?.takeIf { it.second >= 0.6f } ?: ha.match(name)
    }

    val tools = listOf(
        Tool(
            "home_control",
            "device:str name or entity_id, action:str on|off|toggle|open|close|stop|lock|unlock|run|set|up|down|play|pause, value?:str (brightness %, temperature, position, option)",
            "Control a Home Assistant device: lights, switches, garage/covers, locks, scripts, scenes, fans, climate, media, vacuum",
            "Home", "Talking to Home Assistant…",
        ) { a ->
            val ha = ha() ?: return@Tool notConnected()
            val name = a.str("device", "entity", "entity_id", "name", "target") ?: return@Tool fail("Which device?")
            val value = a.str("value", "level", "brightness", "temperature", "position", "percent", "option")
            val action = normAction(a.str("action", "command", "service", "state"), value)
            val m = resolve(name, action)
            if (m == null || m.second < 0.55f) {
                // not one of ours — let Home Assistant's own Assist try ("turn off the living room lights")
                if (settings[K.haAssistFallback]) {
                    val said = runCatching { ha.assist("${if (action == "on") "turn on" else if (action == "off") "turn off" else action} $name") }.getOrNull()
                    if (said != null) return@Tool ok(said)
                }
                return@Tool fail("I couldn't find a device called $name in Home Assistant.")
            }
            control(m.first, action, value)
        },
        Tool(
            "home_status",
            "device?:str name or entity_id (omit for an overview of your main devices)",
            "Check Home Assistant devices or sensors (is the garage open, temperature, what's on)",
            "Home", "Checking…",
        ) { a ->
            val ha = ha() ?: return@Tool notConnected()
            runCatching { ha.refresh() }.onFailure { return@Tool fail("Couldn't reach Home Assistant: ${it.message}") }
            val name = a.str("device", "entity", "entity_id", "name")
            if (name == null) {
                val list = ha.pinned().ifEmpty { ha.controllable().take(15) }
                if (list.isEmpty()) return@Tool ok("I don't see any devices yet.")
                val lines = list.take(25).joinToString("\n") { describe(it) }
                return@Tool ok(list.take(3).joinToString(". ") { describe(it) } + ".", data = lines, needsModel = true)
            }
            val stateful = ha.entities.filter { it.domain !in stateless && it.state != "unavailable" }
            val m = (ha.match(name, pool = stateful)?.takeIf { it.second >= 0.7f }
                ?: ha.match(name, pool = ha.entities)?.takeIf { it.second >= 0.55f })
                ?: return@Tool ha.assist("what is the state of $name")?.let { ok(it) } ?: fail("I couldn't find $name in Home Assistant.")
            val e = runCatching { ha.state(m.first.id) }.getOrNull() ?: m.first
            val text = when {
                e.domain in stateless && Regex("open|close|shut|on|off|locked").containsMatchIn(name.lowercase() + " " + (a.str("question") ?: "")) ||
                    e.domain in stateless ->
                    "${describe(e)}. It's a ${if (e.domain.contains("button")) "button" else e.domain} in Home Assistant, so I can't tell whether it's open or on — there's no sensor for that."
                e.state == "unavailable" -> "${e.name} is unavailable in Home Assistant right now."
                else -> describe(e) + "."
            }
            ok(text, data = "${e.id}: ${e.state} ${e.attrs.toString().take(800)}")
        },
        Tool(
            "home_assist",
            "text:str the request in plain words",
            "Pass a smart-home request to Home Assistant's own Assist (rooms/areas, groups, anything not in your device list)",
            "Home", "Asking Home Assistant…",
        ) { a ->
            val ha = ha() ?: return@Tool notConnected()
            val text = a.str("text", "query", "request", "command") ?: return@Tool fail("What should I ask Home Assistant?")
            val said = runCatching { ha.assist(text) }.getOrElse { return@Tool fail("Couldn't reach Home Assistant: ${it.message}") }
            ok(said ?: "Home Assistant didn't answer.")
        },
    )
}
