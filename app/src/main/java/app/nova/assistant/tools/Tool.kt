package app.nova.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.Settings
import app.nova.assistant.core.ToolOutcome
import app.nova.assistant.service.AssistAccessibilityService
import app.nova.assistant.service.ListenerService
import app.nova.assistant.service.NotificationWatcher
import app.nova.assistant.ui.PermissionActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * A phone capability the assistant can use. [params] is the compact signature shown to the model,
 * e.g. "seconds:int, label?:str". [block] runs on the main thread inside a coroutine.
 */
class Tool(
    val name: String,
    val params: String,
    val description: String,
    val category: String,
    val status: String = "Working…",
    val block: suspend ToolContext.(Args) -> ToolOutcome,
)

/** Lenient argument access — small models get key names and types slightly wrong. */
class Args(val json: JSONObject) {
    private fun raw(keys: Array<out String>): Any? {
        for (k in keys) {
            if (json.has(k) && !json.isNull(k)) return json.get(k)
        }
        return null
    }

    fun str(vararg keys: String): String? = raw(keys)?.let {
        when (it) {
            is JSONArray -> (0 until it.length()).joinToString(", ") { i -> it.optString(i) }
            else -> it.toString()
        }
    }?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun double(vararg keys: String): Double? = raw(keys)?.let {
        when (it) {
            is Number -> it.toDouble()
            else -> it.toString().trim().replace(Regex("[^0-9.+-]"), "").toDoubleOrNull()
        }
    }

    fun int(vararg keys: String): Int? = double(*keys)?.let { Math.round(it).toInt() }

    fun bool(vararg keys: String): Boolean? = raw(keys)?.let {
        when (it) {
            is Boolean -> it
            is Number -> it.toInt() != 0
            else -> when (it.toString().trim().lowercase()) {
                "true", "on", "yes", "1", "enable", "enabled" -> true
                "false", "off", "no", "0", "disable", "disabled" -> false
                else -> null
            }
        }
    }

    fun list(vararg keys: String): List<String> = when (val v = raw(keys)) {
        null -> emptyList()
        is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }.filter { it.isNotEmpty() }
        else -> v.toString().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    }

    override fun toString() = json.toString()
}

class ToolContext(val app: NovaApp) {
    val ctx: Context get() = app
    val settings: Settings get() = app.settings
    val a11y: AssistAccessibilityService? get() = AssistAccessibilityService.instance
    val notif: NotificationWatcher? get() = NotificationWatcher.instance

    /**
     * Starts an activity even from the background: prefers the accessibility service's context
     * (system-bound services are exempt from background-activity-start limits), then the app.
     */
    fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contexts = listOfNotNull<Context>(a11y, app)
        for (c in contexts) {
            try {
                c.startActivity(intent); return true
            } catch (e: ActivityNotFoundException) {
                AppLog.d("tool", "no activity for $intent"); return false
            } catch (t: Throwable) {
                AppLog.d("tool", "launch via ${c.javaClass.simpleName} failed", t)
            }
        }
        return false
    }

    fun has(vararg perms: String) = perms.all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    /** null when all granted; otherwise opens the permission prompt and returns a spoken explanation. */
    fun requirePerms(vararg perms: String, why: String): ToolOutcome? {
        if (has(*perms)) return null
        PermissionActivity.request(app, perms.toList())
        return ToolOutcome.fail("I need permission to $why. I've opened the prompt — allow it and ask me again.")
    }

    fun needA11y(what: String): ToolOutcome =
        ToolOutcome.fail("To $what I need Nova's accessibility service turned on. Open Nova → Setup to enable it.")

    fun ok(msg: String, data: String? = null, needsModel: Boolean = false) = ToolOutcome.ok(msg, data, needsModel)
    fun fail(msg: String) = ToolOutcome.fail(msg)
}

class ToolRegistry(private val app: NovaApp) {
    val all: List<Tool> by lazy {
        ClockTools.tools + CommTools.tools + NotificationTools.tools + DeviceTools.tools +
            AppTools.tools + ScreenTools.tools + InfoTools.tools + MemoryTools.tools + HomeTools.tools + BluetoothTools.tools + SearchTools.tools + ControlTools.tools
    }
    private val byName by lazy { all.associateBy { it.name } }

    operator fun get(name: String): Tool? = byName[name] ?: byName[name.lowercase().replace('-', '_').replace(' ', '_')]

    fun isEnabled(t: Tool) = t.name !in app.settings.disabledToolSet()

    fun enabled(): List<Tool> = all.filter(::isEnabled).filter { it.category != "Home" || app.home.isConfigured() }

    suspend fun run(name: String, args: JSONObject): ToolOutcome {
        val tool = get(name) ?: return ToolOutcome.fail("Unknown tool $name")
        if (!isEnabled(tool)) return ToolOutcome.fail("The ${tool.name} action is turned off in settings.")
        AppLog.d("tool", "run ${tool.name} $args")
        return try {
            ToolContext(app).let { c -> tool.block(c, Args(args)) }
        } catch (e: SecurityException) {
            AppLog.d("tool", "${tool.name} security", e)
            ToolOutcome.fail("Android blocked that (${e.message}). Check Nova's permissions in Setup.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.d("tool", "${tool.name} failed", t)
            ToolOutcome.fail("That didn't work: ${t.message ?: t.javaClass.simpleName}")
        }.also { AppLog.d("tool", "${tool.name} -> ${it.message.take(200)}") }
    }
}

/** Assistant self-control. */
object ControlTools {
    val tools = listOf(
        Tool("stop_listening", "minutes?:int", "Stop listening for the wake word (optionally for N minutes)", "Assistant") { a ->
            val m = a.int("minutes", "duration")
            ListenerService.pauseFor(ctx, m)
            ToolOutcome("Okay, I'll stop listening" + (m?.let { " for $it minutes." } ?: " until you turn me back on."), dismiss = true)
        },
        Tool("end_conversation", "", "Close the assistant popup when the user is done", "Assistant") {
            ToolOutcome("", dismiss = true)
        },
    )
}
