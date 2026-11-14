package app.nova.assistant.tools

import android.content.Intent
import android.provider.Settings
import app.nova.assistant.agent.Fmt
import app.nova.assistant.core.K
import app.nova.assistant.core.Pending
import app.nova.assistant.core.ToolOutcome
import app.nova.assistant.service.NotificationWatcher

/** Reading, replying to and clearing notifications (needs notification access). */
object NotificationTools {
    val tools = listOf(
        Tool("read_notifications", "app?:str, count?:int", "Read the user's recent notifications (optionally from one app)", "Notifications", "Checking notifications…") { a ->
            readNotifs(a)
        },
        Tool("reply_notification", "to:str, message:str", "Reply to a message notification (WhatsApp, SMS, Telegram…) by person or app", "Notifications", "Replying…") { a ->
            replyNotif(a)
        },
        Tool("clear_notifications", "", "Clear all notifications", "Notifications") {
            val w = notif
            if (w == null) noAccess() else {
                w.clearAll(); ok("Cleared your notifications.")
            }
        },
    )

    private fun ToolContext.noAccess(): ToolOutcome {
        launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        return fail("I need notification access for that. I opened the setting — turn on Nova, then ask again.")
    }

    private fun ToolContext.readNotifs(a: Args): ToolOutcome {
        val w = notif ?: return noAccess()
        val appName = a.str("app", "from", "package", "source")
        val count = (a.int("count", "limit", "n") ?: 8).coerceIn(1, 25)
        val list = w.recent(appName, count)
        if (list.isEmpty()) return ok(if (appName != null) "No notifications from $appName." else "You don't have any notifications.")
        val spoken = (if (list.size == 1) "You have one notification. " else "You have ${list.size} notifications. ") +
            list.take(3).joinToString(" ") { n ->
                val who = n.title.ifBlank { "" }
                val body = n.text.take(140)
                if (who.isNotBlank()) "${n.appLabel}, $who: $body." else "${n.appLabel}: $body."
            }
        val data = list.joinToString("\n") { n ->
            "${n.appLabel} | ${n.title}: ${n.text} (${Fmt.time(ctx, n.time)}${if (n.canReply) ", can reply" else ""})"
        }
        return ok(spoken, data = data, needsModel = true)
    }

    private fun ToolContext.replyNotif(a: Args): ToolOutcome {
        if (notif == null) return noAccess()
        val to = a.str("to", "contact", "name", "person", "app", "who")
        val msg = a.str("message", "text", "reply", "body")
        if (to == null) return ToolOutcome("Who should I reply to?", ask = { ans -> replyTo(ans, msg) })
        return replyTo(to, msg)
    }

    private fun ToolContext.replyTo(to: String, msg: String?): ToolOutcome {
        if (msg.isNullOrBlank()) return ToolOutcome("What should I say to $to?", ask = { m -> replyTo(to, m) })
        if (settings[K.confirmApps]) {
            val q = "Reply to $to: “$msg”?"
            return ToolOutcome(q, confirm = Pending(q, "Send") { doReply(to, msg) })
        }
        return doReply(to, msg)
    }

    private fun ToolContext.doReply(to: String, msg: String): ToolOutcome {
        val w = NotificationWatcher.instance ?: return noAccess()
        val who = w.reply(to, msg) ?: return fail("I couldn't find a message from $to that I can reply to.")
        return ok("Replied to $who.")
    }
}
