package app.nova.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.tools.Reminders

/** Re-arms reminders and restarts the wake-word service after boot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        AppLog.d("boot", "received $action")
        runCatching { Reminders.rescheduleAll(context) }.onFailure { AppLog.d("boot", "reschedule failed", it) }
        val s = context.app.settings
        if (s[K.startOnBoot] && s[K.wakeEnabled]) ListenerService.start(context, "boot")
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
