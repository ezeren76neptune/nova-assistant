package app.nova.assistant.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.service.AssistAccessibilityService
import app.nova.assistant.service.NotificationWatcher

/** The setup checklist: every permission / special access Nova can use, with a one-tap fix. */
object Setup {
    data class Item(
        val id: String,
        val title: String,
        val detail: String,
        val ok: Boolean,
        val important: Boolean,
        val fix: (Activity) -> Unit,
    )

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Every runtime permission Nova asks for on this Android version. */
    fun runtimePermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun missingRuntime(ctx: Context) = runtimePermissions().filterNot { granted(ctx, it) }

    /** Tries each intent until one opens. */
    fun Activity.openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (t: Throwable) {
                AppLog.d("setup", "can't open ${i.action}", t)
            }
        }
    }

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))

    private fun runtime(ctx: Context, id: String, title: String, detail: String, perms: List<String>, important: Boolean = false) =
        Item(id, title, detail, perms.all { granted(ctx, it) }, important) { act -> PermissionActivity.request(act, perms) }

    fun isDefaultAssistant(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service")?.contains(ctx.packageName) == true
        }
    } catch (_: Throwable) {
        false
    }

    fun items(ctx: Context): List<Item> {
        val app = ctx.app
        val pm = ctx.getSystemService(PowerManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val list = mutableListOf<Item>()

        list += runtime(ctx, "mic", "Microphone", "Needed for the wake word and voice commands.", listOf(Manifest.permission.RECORD_AUDIO), important = true)
        if (Build.VERSION.SDK_INT >= 33) {
            list += runtime(ctx, "notif", "Notifications", "Shows the persistent \"listening\" notification, timers and reminders.", listOf(Manifest.permission.POST_NOTIFICATIONS), important = true)
        }
        list += Item(
            "a11y", "Accessibility service",
            "Lets Nova read and tap the screen, take screenshots, lock the phone, press Send in WhatsApp, flip quick-setting toggles, and stay alive in the background. " +
                "On Android 13+ a sideloaded app's toggle may be greyed out (\"Restricted setting\"): open App info → ⋮ → Allow restricted settings, then try again.",
            AssistAccessibilityService.isEnabled(ctx), true,
        ) { act -> act.openFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(act)) }
        list += Item(
            "overlay", "Display over other apps",
            "Shows the assistant popup at the bottom of any screen (used when the accessibility service is off).",
            Settings.canDrawOverlays(ctx), true,
        ) { act -> act.openFirst(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(act)), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        list += Item(
            "battery", "Ignore battery optimization",
            "Stops Android from killing the wake-word listener when the screen is off.",
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true, true,
        ) { act ->
            act.openFirst(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(act)),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        list += Item(
            "assistant", "Default digital assistant",
            "Set Nova as the default assistant app: enables long-press home / power / corner swipe, the headset button, and keeps Nova alive. " +
                "Pick Nova under Digital assistant app.",
            isDefaultAssistant(ctx), false,
        ) { act ->
            act.openFirst(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )
        }
        list += Item(
            "wake_model", "Wake-word model",
            "Small offline speech model (~40 MB) that listens for your wake word.",
            app.vosk.isInstalled(), true,
        ) { _ -> app.vosk.download() }
        list += Item(
            "ai_model", "AI model",
            "An on-device language model (or a server on your network) that handles everything beyond quick commands.",
            app.llm.isConfigured(), true,
        ) { act ->
            act.startActivity(
                Intent(act, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, "MODEL")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        list += runtime(ctx, "contacts", "Contacts", "Call and text people by name.", listOf(Manifest.permission.READ_CONTACTS))
        list += runtime(
            ctx, "phone", "Phone & call log", "Place calls directly, \"call back\", missed calls, pause listening during calls.",
            listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE),
        )
        list += runtime(ctx, "sms", "SMS", "Send texts without opening an app, and read your messages aloud.", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS))
        list += runtime(ctx, "calendar", "Calendar", "Read your schedule and add events.", listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        list += runtime(ctx, "location", "Location", "Weather and \"where am I\".", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        list += Item(
            "notif_access", "Notification access",
            "Read your notifications, reply to WhatsApp/Telegram/etc. messages, and see what's playing.",
            NotificationWatcher.isEnabled(ctx), false,
        ) { act ->
            val intents = mutableListOf<Intent>()
            if (Build.VERSION.SDK_INT >= 30) {
                intents += Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(act, NotificationWatcher::class.java).flattenToString(),
                )
            }
            intents += Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            act.openFirst(*intents.toTypedArray())
        }
        list += Item(
            "dnd", "Do Not Disturb access",
            "Turn Do Not Disturb and silent mode on or off.",
            nm?.isNotificationPolicyAccessGranted == true, false,
        ) { act -> act.openFirst(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        list += Item(
            "write_settings", "Modify system settings",
            "Change screen brightness and auto-rotate.",
            Settings.System.canWrite(ctx), false,
        ) { act -> act.openFirst(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(act)), Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)) }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            list += Item(
                "exact_alarms", "Alarms & reminders",
                "Fire built-in timers and reminders at the exact time.",
                am?.canScheduleExactAlarms() == true, false,
            ) { act -> act.openFirst(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(act)), appDetails(act)) }
        }
        return list
    }
}
