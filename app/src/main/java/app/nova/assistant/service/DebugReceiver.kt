package app.nova.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K

/**
 * Test hook: `adb shell am broadcast -n app.nova.assistant/.service.DebugReceiver --es text "volume up"`.
 * Protected by android.permission.DUMP in the manifest, so only adb/system can send it — not other apps.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("dump")?.let { pkg ->
            val tree = AssistAccessibilityService.instance?.dumpTree(pkg.ifBlank { null }) ?: "accessibility service not running"
            tree.lines().chunked(40).forEach { AppLog.d("dump", "\n" + it.joinToString("\n")) }
            return
        }
        intent.getStringExtra("log")?.let { n ->
            val app = context.app
            AppLog.d("dump", "voiceId: enrolled=${app.voiceId.isEnrolled()} active=${app.voiceId.isActive()} samples=${app.settings[K.voiceIdSamples]} threshold=${app.settings[K.voiceIdThreshold]} model=${app.voiceId.modelState.value} llm=${app.llm.status.value}")
            AppLog.lines.value.takeLast(n.toIntOrNull() ?: 60).chunked(30).forEach { AppLog.d("dump", "\n" + it.joinToString("\n")) }
            return
        }
        intent.getStringExtra("ha")?.let { q ->
            val ha = context.app.home
            AppLog.d("dump", "HA configured=${ha.isConfigured()} entities=${ha.entities.size} widgets=${ha.widgetIds()} favorites=${ha.favoriteIds()}")
            ha.entities.filter { q.isBlank() || it.id.contains(q, true) || it.name.contains(q, true) }.take(80)
                .chunked(25).forEach { c -> AppLog.d("dump", "\n" + c.joinToString("\n") { "${it.id} | ${it.name} | ${it.state} | ${it.attrs.optString("device_class")}" }) }
            return
        }
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        AppLog.d("debug", "adb command: $text")
        context.app.controller.runTyped(text)
    }
}
