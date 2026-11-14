package app.nova.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import app.nova.assistant.core.ToolOutcome
import app.nova.assistant.service.ToggleResult
import kotlinx.coroutines.delay

/** Screen navigation and UI automation through Nova's accessibility service. */
object ScreenTools {

    private fun actionKey(raw: String): String {
        val a = raw.lowercase().replace('-', '_').replace(' ', '_')
        return when {
            a.contains("screenshot") || a.contains("screen_shot") || a.contains("capture") -> "screenshot"
            a.contains("close") || a.contains("dismiss") || a.contains("collapse") || a.contains("hide") -> "close_shade"
            a.contains("quick") -> "quick_settings"
            a.contains("notif") || a.contains("shade") -> "notifications"
            a.contains("home") -> "home"
            a.contains("back") -> "back"
            a.contains("recent") || a.contains("overview") || a.contains("switch") || a.contains("multitask") -> "recents"
            a.contains("lock") -> "lock"
            a.contains("power") || a.contains("restart") || a.contains("shut") -> "power_menu"
            a.contains("split") -> "split_screen"
            else -> a
        }
    }

    val tools: List<Tool> = listOf(
        Tool(
            "system_action",
            "action:str home|back|recents|notifications|quick_settings|lock|screenshot|power_menu|split_screen|close_shade",
            "Phone navigation and system actions", "Screen",
        ) { a ->
            val key = actionKey(a.str("action", "name", "command") ?: return@Tool fail("Which action?"))
            val svc = a11y
            if (svc == null) {
                if (key == "home") {
                    launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    return@Tool ToolOutcome("Going home.", dismiss = true)
                }
                return@Tool needA11y("do that")
            }
            if (key == "screenshot") {
                if (Build.VERSION.SDK_INT < 28) return@Tool fail("Screenshots need Android 9 or newer.")
                app.popup.hide()
                delay(450)
                return@Tool if (svc.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) ToolOutcome("Screenshot taken.", dismiss = true)
                else fail("I couldn't take a screenshot.")
            }
            val (action, msg) = when (key) {
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME to "Going home."
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK to "Going back."
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Here are your recent apps."
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Here are your notifications."
                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Opening quick settings."
                "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to "Opening the power menu."
                "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to "Toggling split screen."
                "lock" -> {
                    if (Build.VERSION.SDK_INT < 28) return@Tool fail("Locking needs Android 9 or newer.")
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locking the phone."
                }
                "close_shade" -> {
                    if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE to "Done."
                    else AccessibilityService.GLOBAL_ACTION_BACK to "Done."
                }
                else -> return@Tool fail("I don't know the action $key.")
            }
            if (svc.global(action)) ToolOutcome(msg, dismiss = true) else fail("Android didn't let me do that.")
        },

        Tool("read_screen", "", "Read the text on screen (to summarize it or answer questions about it)", "Screen", "Reading the screen…") {
            val svc = a11y
            val text = svc?.screenText(4000)?.takeIf { it.isNotBlank() }
                ?: app.controller.assistScreenText?.takeIf { it.isNotBlank() }
            if (text == null) {
                return@Tool if (svc == null) needA11y("read the screen") else fail("I couldn't read anything on the screen.")
            }
            val short = if (text.length > 300) text.take(300).substringBeforeLast(' ') + "…" else text
            ToolOutcome(short, data = text, needsModel = true)
        },

        Tool("tap", "text:str", "Tap a button or link on screen by its visible text", "Screen", "Tapping…") { a ->
            val t = a.str("text", "label", "target", "button", "name") ?: return@Tool fail("What should I tap?")
            val svc = a11y ?: return@Tool needA11y("tap things on screen")
            if (svc.clickText(t, 1500)) ok("Tapped $t.") else fail("I couldn't find \"$t\" on the screen.")
        },

        Tool("type_text", "text:str, submit?:bool", "Type text into the focused text box", "Screen", "Typing…") { a ->
            val t = a.str("text", "content", "value") ?: return@Tool fail("What should I type?")
            val svc = a11y ?: return@Tool needA11y("type for you")
            if (svc.typeText(t, a.bool("submit", "send", "enter") == true)) ok("Typed it.") else fail("I couldn't find a text box to type in.")
        },

        Tool("scroll", "direction:str up|down|left|right", "Scroll the current screen", "Screen") { a ->
            val d = (a.str("direction", "dir", "to") ?: "down").lowercase().let {
                when {
                    it.contains("up") || it.contains("top") -> "up"
                    it.contains("left") -> "left"
                    it.contains("right") -> "right"
                    else -> "down"
                }
            }
            val svc = a11y ?: return@Tool needA11y("scroll")
            if (svc.scroll(d)) ok("Scrolled $d.") else fail("There's nothing to scroll here.")
        },

        Tool("tap_quick_setting", "name:str", "Toggle any Quick Settings tile by its label (e.g. hotspot, screen record, cast)", "Screen", "Opening quick settings…") { a ->
            val name = a.str("name", "tile", "label", "setting") ?: return@Tool fail("Which tile?")
            val svc = a11y ?: return@Tool needA11y("use quick settings")
            when (svc.toggleQuickSetting(listOf(name), null)) {
                ToggleResult.CHANGED -> ok("Toggled $name.")
                ToggleResult.ALREADY -> ok("$name is already set.")
                ToggleResult.NOT_FOUND -> fail("I couldn't find a $name tile in quick settings.")
                ToggleResult.FAILED -> fail("I couldn't tap the $name tile.")
            }
        },
    )
}
