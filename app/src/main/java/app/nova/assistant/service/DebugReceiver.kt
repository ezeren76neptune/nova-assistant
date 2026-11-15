package app.nova.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.CustomWord
import app.nova.assistant.core.K

/**
 * Test hook: `adb shell am broadcast -n app.nova.assistant/.service.DebugReceiver --es text "volume up"`.
 * Protected by android.permission.DUMP in the manifest, so only adb/system can send it — not other apps.
 */
class DebugReceiver : BroadcastReceiver() {
    private fun out(msg: String) { android.util.Log.d("Nova/dump", msg) }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("dump")?.let { pkg ->
            val tree = AssistAccessibilityService.instance?.dumpTree(pkg.ifBlank { null }) ?: "accessibility service not running"
            tree.lines().chunked(40).forEach { out("\n" + it.joinToString("\n")) }
            return
        }
        // simulate the OS killing the model process (same SIGKILL a memory manager sends)
        if (intent.hasExtra("killllm")) {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am.runningAppProcesses?.firstOrNull { it.processName.endsWith(":llm") }?.let {
                AppLog.d("debug", "killing model process ${it.pid}")
                android.os.Process.killProcess(it.pid)
            } ?: AppLog.d("debug", "no model process running")
            return
        }
        // custom words: --es addword "MSOE|my university|emma so we,m so e"   --es correct "i go to m s o e"
        intent.getStringExtra("addword")?.let { spec ->
            val p = spec.split('|')
            context.app.customWords.upsert(CustomWord(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }.split(',').filter { it.isNotBlank() }))
            out("custom word saved: ${context.app.customWords.words.value.firstOrNull { it.word == p[0] }}")
            return
        }
        intent.getStringExtra("delword")?.let { context.app.customWords.remove(it); out("custom word removed: $it"); return }
        intent.getStringExtra("correct")?.let { out("correct: \"$it\" -> \"${context.app.customWords.correct(it)}\""); return }
        intent.getStringExtra("log")?.let { n ->
            val app = context.app
            out("voiceId: enrolled=${app.voiceId.isEnrolled()} active=${app.voiceId.isActive()} samples=${app.settings[K.voiceIdSamples]} threshold=${app.settings[K.voiceIdThreshold]} model=${app.voiceId.modelState.value} llm=${app.llm.status.value}")
            AppLog.lines.value.takeLast(n.toIntOrNull() ?: 60).chunked(30).forEach { out("\n" + it.joinToString("\n")) }
            return
        }
        intent.getStringExtra("ha")?.let { q ->
            val ha = context.app.home
            out("HA configured=${ha.isConfigured()} entities=${ha.entities.size} widgets=${ha.widgetIds()} favorites=${ha.favoriteIds()}")
            ha.entities.filter { q.isBlank() || it.id.contains(q, true) || it.name.contains(q, true) }.take(80)
                .chunked(25).forEach { c -> out("\n" + c.joinToString("\n") { "${it.id} | ${it.name} | ${it.state} | ${it.attrs.optString("device_class")}" }) }
            return
        }
        val text = intent.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
        AppLog.d("debug", "adb command: $text")
        context.app.controller.runTyped(text)
    }
}
