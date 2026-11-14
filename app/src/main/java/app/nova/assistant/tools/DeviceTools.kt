package app.nova.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import app.nova.assistant.agent.Fmt
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.launch
import app.nova.assistant.service.ToggleResult
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Flashlight, volume, media keys, brightness, DND, ringer, system toggles, settings pages, battery. */
object DeviceTools {

    // ---------- flashlight state tracking ----------
    private object Torch {
        @Volatile var on = false
        private var registered = false

        fun cameraId(cm: CameraManager): String? = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        fun ensureCallback(ctx: Context) {
            if (registered) return
            registered = true
            runCatching {
                ctx.getSystemService(CameraManager::class.java).registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { on = enabled }
                }, Handler(Looper.getMainLooper()))
            }
        }
    }

    // ---------- toggle_setting ladder ----------
    private class Spec(
        val key: String,
        val label: String,
        val tiles: List<String>,
        val fallbacks: () -> List<Intent>,
    )

    private fun spec(name: String): Spec? {
        val n = name.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            n == "wifi" || n == "wlan" || n == "internet" || n.contains("wifi") && !n.contains("hotspot") -> Spec(
                "wifi", "Wi-Fi", listOf("Wi-Fi", "Wi‑Fi", "WiFi", "Internet"),
            ) { listOf(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS), Intent(Settings.ACTION_WIFI_SETTINGS)) }
            n.contains("bluetooth") || n == "bt" -> Spec("bluetooth", "Bluetooth", listOf("Bluetooth")) {
                listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            n.contains("airplane") || n.contains("flight") || n.contains("aeroplane") || n.contains("plane") -> Spec(
                "airplane", "Airplane mode", listOf("Airplane mode", "Flight mode", "Aeroplane mode"),
            ) { listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
            n.contains("hotspot") || n.contains("tether") -> Spec(
                "hotspot", "Hotspot", listOf("Hotspot", "Mobile Hotspot", "Wi-Fi hotspot", "Wi‑Fi hotspot"),
            ) {
                listOf(
                    Intent("android.settings.TETHER_SETTINGS"),
                    Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                    Intent(Settings.ACTION_WIRELESS_SETTINGS),
                )
            }
            n.contains("location") || n.contains("gps") -> Spec("location", "Location", listOf("Location")) {
                listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            n.contains("nfc") -> Spec("nfc", "NFC", listOf("NFC")) {
                listOf(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_NFC) else Intent(Settings.ACTION_NFC_SETTINGS), Intent(Settings.ACTION_NFC_SETTINGS))
            }
            n.contains("data") || n.contains("cellular") || n.contains("mobile") -> Spec(
                "mobile_data", "Mobile data", listOf("Mobile data", "Mobile Data", "Data"),
            ) {
                listOfNotNull(
                    if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else null,
                    if (Build.VERSION.SDK_INT >= 28) Intent(Settings.ACTION_DATA_USAGE_SETTINGS) else null,
                    Intent(Settings.ACTION_WIRELESS_SETTINGS),
                )
            }
            n.contains("rotat") -> Spec("auto_rotate", "Auto-rotate", listOf("Auto-rotate", "Auto rotate", "Rotation", "Auto-rotate screen")) {
                listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
            n.contains("dark") || n.contains("night") -> Spec("dark_mode", "Dark theme", listOf("Dark theme", "Dark mode")) {
                listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
            n.contains("battery") || n.contains("saver") || n.contains("powersav") -> Spec(
                "battery_saver", "Battery Saver", listOf("Battery Saver", "Battery saver", "Power saving", "Power saving mode"),
            ) { listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            else -> null
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** Step 1: APIs Android still lets ordinary apps use. Returns null to continue the ladder. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun ToolContext.directToggle(sp: Spec, on: Boolean?): ToolOutcome? {
        try {
            when (sp.key) {
                "wifi" -> if (Build.VERSION.SDK_INT < 29) {
                    val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
                    val target = on ?: !wm.isWifiEnabled
                    if (wm.isWifiEnabled == target) return ok("Wi-Fi is already ${onOff(target)}.")
                    if (wm.setWifiEnabled(target)) return ok("Wi-Fi is ${onOff(target)}.")
                }
                "bluetooth" -> if (Build.VERSION.SDK_INT < 33) {
                    if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) return null
                    val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return fail("This phone doesn't have Bluetooth.")
                    val target = on ?: !ad.isEnabled
                    if (ad.isEnabled == target) return ok("Bluetooth is already ${onOff(target)}.")
                    if (if (target) ad.enable() else ad.disable()) return ok("Bluetooth is ${onOff(target)}.")
                }
                "auto_rotate" -> if (Settings.System.canWrite(ctx)) {
                    val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    val target = on ?: !cur
                    if (cur == target) return ok("Auto-rotate is already ${onOff(target)}.")
                    Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (target) 1 else 0)
                    return ok("Auto-rotate is ${onOff(target)}.")
                }
            }
        } catch (t: Throwable) {
            AppLog.d("device", "direct toggle ${sp.key} failed", t)
        }
        return null
    }

    private fun ToolContext.toggleMessage(sp: Spec, on: Boolean?, r: ToggleResult): ToolOutcome? = when (r) {
        ToggleResult.CHANGED -> ok(if (on != null) "${sp.label} is ${onOff(on)}." else "Toggled ${sp.label}.")
        ToggleResult.ALREADY -> ok(if (on != null) "${sp.label} is already ${onOff(on)}." else "${sp.label} is unchanged.")
        else -> null
    }

    private suspend fun ToolContext.toggle(sp: Spec, on: Boolean?): ToolOutcome {
        directToggle(sp, on)?.let { return it }
        val svc = a11y
        if (settings[K.uiAutomation] && svc != null) {
            try {
                if (sp.key == "wifi" && Build.VERSION.SDK_INT >= 29) {
                    val r = svc.toggleInSettings(Intent(Settings.Panel.ACTION_WIFI), listOf("Wi-Fi", "Wi‑Fi", "Use Wi-Fi", "Use Wi‑Fi"), on)
                    toggleMessage(sp, on, r)?.let { return it }
                } else if (sp.key == "mobile_data" && Build.VERSION.SDK_INT >= 29) {
                    val r = svc.toggleInSettings(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), listOf("Mobile data", "Mobile Data", "Cellular data"), on)
                    toggleMessage(sp, on, r)?.let { return it }
                }
                val r = svc.toggleQuickSetting(sp.tiles, on)
                toggleMessage(sp, on, r)?.let { return it }
            } catch (t: Throwable) {
                AppLog.d("device", "ui automation toggle ${sp.key} failed", t)
            }
        }
        for (i in sp.fallbacks()) {
            if (launch(i)) {
                val hint = on?.let { " — switch it ${onOff(it)} there" } ?: ""
                return ToolOutcome("I opened ${sp.label} settings for you$hint.", dismiss = true)
            }
        }
        return fail("I couldn't change ${sp.label} on this phone.")
    }

    // ---------- open_settings ----------
    private fun settingsPage(ctx: Context, page: String): Pair<String, List<Intent>> {
        val p = page.lowercase()
        fun i(a: String) = Intent(a)
        return when {
            p.contains("nova") || p.contains("this app") || p.contains("assistant app") ->
                "Nova app" to listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            p.contains("hotspot") || p.contains("tether") -> "hotspot" to listOf(i("android.settings.TETHER_SETTINGS"), i(Settings.ACTION_WIRELESS_SETTINGS))
            p.contains("wi") && p.contains("fi") || p.contains("wifi") -> "Wi-Fi" to listOf(i(Settings.ACTION_WIFI_SETTINGS))
            p.contains("bluetooth") -> "Bluetooth" to listOf(i(Settings.ACTION_BLUETOOTH_SETTINGS))
            p.contains("display") || p.contains("screen") || p.contains("brightness") -> "display" to listOf(i(Settings.ACTION_DISPLAY_SETTINGS))
            p.contains("wallpaper") -> "wallpaper" to listOf(i(Intent.ACTION_SET_WALLPAPER), i(Settings.ACTION_DISPLAY_SETTINGS))
            p.contains("disturb") || p.contains("dnd") || p.contains("zen") -> "Do Not Disturb" to listOf(i("android.settings.ZEN_MODE_SETTINGS"), i(Settings.ACTION_SOUND_SETTINGS))
            p.contains("sound") || p.contains("volume") || p.contains("ringtone") || p.contains("vibrat") -> "sound" to listOf(i(Settings.ACTION_SOUND_SETTINGS))
            p.contains("battery") || p.contains("power") -> "battery" to listOf(i(Intent.ACTION_POWER_USAGE_SUMMARY), i(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            p.contains("default") -> "default apps" to listOf(i(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            p.contains("voice") || p.contains("assistant") -> "assistant" to listOf(i(Settings.ACTION_VOICE_INPUT_SETTINGS), i(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            p.contains("app") -> "apps" to listOf(i(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), i(Settings.ACTION_APPLICATION_SETTINGS))
            p.contains("location") || p.contains("gps") -> "location" to listOf(i(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            p.contains("security") || p.contains("lock") || p.contains("fingerprint") -> "security" to listOf(i(Settings.ACTION_SECURITY_SETTINGS))
            p.contains("privacy") || p.contains("permission") -> "privacy" to listOf(i(Settings.ACTION_PRIVACY_SETTINGS))
            p.contains("accessib") -> "accessibility" to listOf(i(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            p.contains("notification") -> "notification" to listOf(i("android.settings.NOTIFICATION_SETTINGS"), i(Settings.ACTION_SETTINGS))
            p.contains("storage") -> "storage" to listOf(i(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            p.contains("date") || p.contains("time") -> "date and time" to listOf(i(Settings.ACTION_DATE_SETTINGS))
            p.contains("language") || p.contains("keyboard") || p.contains("input") -> "language" to listOf(i(Settings.ACTION_LOCALE_SETTINGS))
            p.contains("developer") -> "developer" to listOf(i(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            p.contains("about") || p.contains("phone info") || p.contains("device info") -> "about phone" to listOf(i(Settings.ACTION_DEVICE_INFO_SETTINGS))
            p.contains("data") || p.contains("usage") -> "data usage" to listOfNotNull(
                if (Build.VERSION.SDK_INT >= 28) i(Settings.ACTION_DATA_USAGE_SETTINGS) else null, i(Settings.ACTION_WIRELESS_SETTINGS),
            )
            p.contains("network") || p.contains("mobile") || p.contains("sim") -> "network" to listOf(i(Settings.ACTION_WIRELESS_SETTINGS))
            p.contains("nfc") -> "NFC" to listOf(i(Settings.ACTION_NFC_SETTINGS))
            p.contains("airplane") || p.contains("flight") -> "airplane mode" to listOf(i(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            p.contains("account") || p.contains("sync") -> "accounts" to listOf(i(Settings.ACTION_SYNC_SETTINGS))
            else -> "" to listOf(i(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Moves [stream] to [target]. Some phones (OnePlus/ColorOS) silently ignore setStreamVolume from apps,
     * so after trying it we verify and fall back to volume-key style steps, re-reading the level each step
     * and stopping at the step closest to the target. Shows the system volume panel at the end.
     */
    private suspend fun setVolumeExact(am: AudioManager, stream: Int, target: Int): Int {
        val start = am.getStreamVolume(stream)
        if (start != target) {
            runCatching { am.setStreamVolume(stream, target, 0) }
            kotlinx.coroutines.delay(150)
        }
        var cur = am.getStreamVolume(stream)
        if (cur != target) {
            app.nova.assistant.core.AppLog.d("volume", "setStreamVolume ignored ($start -> $cur, wanted $target); stepping")
            var guard = 0
            while (cur != target && guard++ < 60) {
                val up = target > cur
                am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                var next = am.getStreamVolume(stream)
                if (next == cur) { kotlinx.coroutines.delay(60); next = am.getStreamVolume(stream) }
                if (next == cur) break // at a limit, or blocked
                val passed = if (up) next > target else next < target
                if (passed) {
                    // overshot: keep whichever step is closer to what was asked
                    if (kotlin.math.abs(next - target) > kotlin.math.abs(cur - target)) {
                        am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE, 0)
                        next = am.getStreamVolume(stream)
                    }
                    cur = next
                    break
                }
                cur = next
            }
        }
        runCatching { am.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) }
        return cur
    }

    /** Reads a 0-100 percent from "50", 50, "50%", or a 0..1 fraction like 0.5. */
    private fun percentArg(a: Args, vararg keys: String): Int? {
        val raw = a.str(*keys) ?: return null
        val v = raw.replace("%", "").replace("percent", "").trim().toDoubleOrNull() ?: return null
        val p = if (v > 0 && v <= 1.0 && raw.contains('.')) v * 100 else v
        return Math.round(p).toInt().coerceIn(0, 100)
    }

    private fun streamFor(name: String?): Pair<Int, String> {
        val n = name?.lowercase() ?: "media"
        return when {
            n.contains("ring") -> AudioManager.STREAM_RING to "Ring"
            n.contains("alarm") -> AudioManager.STREAM_ALARM to "Alarm"
            n.contains("notif") -> AudioManager.STREAM_NOTIFICATION to "Notification"
            n.contains("call") || n.contains("voice") -> AudioManager.STREAM_VOICE_CALL to "Call"
            n.contains("system") -> AudioManager.STREAM_SYSTEM to "System"
            else -> AudioManager.STREAM_MUSIC to "Media"
        }
    }

    private fun policyAccess(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun ToolContext.askPolicyAccess(): ToolOutcome {
        launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return fail("I need Do Not Disturb access for that. I've opened the setting — allow Nova, then ask again.")
    }

    val tools: List<Tool> = listOf(
        Tool("flashlight", "on?:bool", "Turn the flashlight on or off (toggles if omitted)", "Device") { a ->
            val cm = ctx.getSystemService(CameraManager::class.java)
            Torch.ensureCallback(ctx)
            val id = Torch.cameraId(cm) ?: return@Tool fail("This phone doesn't have a flashlight.")
            val target = a.bool("on", "state", "enabled", "enable") ?: !Torch.on
            cm.setTorchMode(id, target)
            Torch.on = target
            ok(if (target) "Flashlight on." else "Flashlight off.")
        },

        Tool(
            "volume",
            "action:str set|up|down|mute|unmute|max|get, level?:int target percent 0-100 (for set), by?:int percent to move (for up/down, default 10), stream?:str media|ring|alarm|notification|call",
            "Read or change a volume. set = exact percent; up/down = move by a percent", "Device",
        ) { a ->
            val am = ctx.getSystemService(AudioManager::class.java)
            val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
            val (stream, label) = streamFor(a.str("stream", "type", "channel") ?: if (inCall) "call" else null)
            val level = percentArg(a, "level", "value", "percent", "volume", "to")
            val by = percentArg(a, "by", "amount", "step", "delta")
            val rawAction = a.str("action", "direction", "command", "mode")?.lowercase()
            var action = when {
                rawAction == null -> if (level != null) "set" else if (by != null) "up" else "get"
                rawAction.contains("unmute") -> "unmute"
                rawAction.contains("mute") || rawAction.contains("silence") -> "mute"
                rawAction.contains("max") || rawAction.contains("full") -> "max"
                rawAction.contains("min") -> "min"
                rawAction.contains("get") || rawAction.contains("current") || rawAction.contains("check") || rawAction.contains("what") -> "get"
                rawAction.contains("up") || rawAction.contains("increase") || rawAction.contains("raise") || rawAction.contains("louder") -> "up"
                rawAction.contains("down") || rawAction.contains("decrease") || rawAction.contains("lower") || rawAction.contains("quieter") || rawAction.contains("softer") || rawAction.contains("reduce") -> "down"
                level != null -> "set"
                else -> "get"
            }
            // small models often send {"action":"up","level":50} for "turn it up to 50%": a level is a target
            if ((action == "up" || action == "down") && level != null && by == null) action = "set"
            val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
            val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
            val before = am.getStreamVolume(stream)
            fun pct(idx: Int) = Math.round(idx * 100f / max)
            fun idxFor(p: Int) = Math.round(p.coerceIn(0, 100) * max / 100f).coerceIn(min, max)
            val target: Int? = when (action) {
                "set" -> idxFor(level ?: 50)
                "max" -> max
                "min" -> min
                "up" -> (before + maxOf(1, Math.round(max * (by ?: 10) / 100f))).coerceAtMost(max)
                "down" -> (before - maxOf(1, Math.round(max * (by ?: 10) / 100f))).coerceAtLeast(min)
                else -> null
            }
            try {
                when (action) {
                    "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "get" -> {}
                    else -> {
                        if (am.isStreamMute(stream) && (target ?: 0) > 0) am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                        setVolumeExact(am, stream, target ?: before)
                    }
                }
            } catch (e: SecurityException) {
                return@Tool fail("Android won't let me change the ${label.lowercase()} volume while Do Not Disturb is on.")
            }
            val after = am.getStreamVolume(stream)
            if (action == "mute" || (am.isStreamMute(stream) && action != "get")) return@Tool ok("$label volume muted.")
            val now = pct(after)
            val msg = when {
                action == "get" -> "$label volume is at $now percent."
                target != null && after == before && target != before && before != max && before != min ->
                    "Android didn't let me change the ${label.lowercase()} volume. It's at $now percent."
                action == "up" && before == max -> "$label volume is already at maximum."
                action == "down" && before == min -> "$label volume is already at its lowest."
                else -> "$label volume $now percent."
            }
            ok(msg, data = "stream=$label before=${pct(before)}% after=$now% (index $after/$max)")
        },

        Tool("media_control", "action:str play|pause|toggle|next|previous|stop|now_playing", "Control music/video playback or say what's playing", "Device") { a ->
            val action = a.str("action", "command")?.lowercase() ?: "toggle"
            if (action.contains("now") || action.contains("info") || action.contains("what") || action.contains("current")) {
                val n = notif ?: return@Tool fail("To see what's playing I need notification access. Turn it on in Nova → Setup.")
                return@Tool ok(n.nowPlaying()?.let { "Now playing: $it." } ?: "Nothing seems to be playing.")
            }
            val (code, msg) = when {
                action.contains("pause") -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
                action.contains("play") || action.contains("resume") || action.contains("continue") -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
                action.contains("next") || action.contains("skip") -> KeyEvent.KEYCODE_MEDIA_NEXT to "Skipping."
                action.contains("prev") || action.contains("back") || action.contains("last") -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Going back."
                action.contains("stop") -> KeyEvent.KEYCODE_MEDIA_STOP to "Stopped."
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "Done."
            }
            val am = ctx.getSystemService(AudioManager::class.java)
            // prefer the most relevant app's own controls (playing one first), fall back to a media key
            val sessions = notif?.mediaControllers().orEmpty()
            val c = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            val tc = c?.transportControls
            val handled = tc != null && runCatching {
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> tc.pause()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> tc.play()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> tc.skipToNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tc.skipToPrevious()
                    KeyEvent.KEYCODE_MEDIA_STOP -> tc.stop()
                    else -> if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                }
            }.isSuccess
            if (!handled) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY && c != null) {
                val nova = this.app
                nova.appScope.launch { MediaPlayback.ensurePlaying(nova, c.packageName, "") { false } }
            }
            ToolOutcome(msg, dismiss = true, quiet = true)
        },

        Tool("brightness", "level?:int 0-100, action?:str up|down|max|min|auto", "Set screen brightness", "Device") { a ->
            if (!Settings.System.canWrite(ctx)) {
                launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                return@Tool fail("I need permission to modify system settings. I've opened it — allow Nova, then ask again.")
            }
            val cr = ctx.contentResolver
            val action = a.str("action", "mode", "direction")?.lowercase() ?: ""
            val level = a.int("level", "value", "percent", "brightness")
            if (action.contains("auto") || action.contains("adaptive")) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return@Tool ok("Adaptive brightness is on.")
            }
            val curRaw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
            val curPct = sqrt(curRaw / 255.0) * 100
            val newPct = when {
                level != null -> level.toDouble()
                action.contains("up") || action.contains("increase") || action.contains("bright") || action.contains("raise") -> curPct + 15
                action.contains("down") || action.contains("decrease") || action.contains("dim") || action.contains("lower") -> curPct - 15
                action.contains("max") || action.contains("full") -> 100.0
                action.contains("min") || action.contains("low") -> 0.0
                else -> return@Tool fail("Tell me a brightness level, or up or down.")
            }.coerceIn(0.0, 100.0)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val raw = (255 * (newPct / 100).pow(2)).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, raw)
            ok("Brightness ${newPct.roundToInt()} percent.")
        },

        Tool("do_not_disturb", "on:bool", "Turn Do Not Disturb on or off", "Device") { a ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) return@Tool askPolicyAccess()
            val on = a.bool("on", "enabled", "state", "enable") ?: (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            ok(if (on) "Do Not Disturb is on." else "Do Not Disturb is off.")
        },

        Tool("ringer_mode", "mode:str normal|vibrate|silent", "Set the ringer to normal, vibrate or silent", "Device") { a ->
            val mode = a.str("mode", "state", "ringer")?.lowercase() ?: return@Tool fail("Normal, vibrate, or silent?")
            val am = ctx.getSystemService(AudioManager::class.java)
            val (target, msg) = when {
                mode.contains("vib") -> AudioManager.RINGER_MODE_VIBRATE to "Your phone is on vibrate."
                mode.contains("sil") || mode.contains("mute") || mode.contains("quiet") -> AudioManager.RINGER_MODE_SILENT to "Your phone is on silent."
                else -> AudioManager.RINGER_MODE_NORMAL to "Your ringer is back on."
            }
            if ((target == AudioManager.RINGER_MODE_SILENT || am.ringerMode == AudioManager.RINGER_MODE_SILENT) && !policyAccess(ctx)) {
                return@Tool askPolicyAccess()
            }
            try {
                am.ringerMode = target
            } catch (e: SecurityException) {
                return@Tool askPolicyAccess()
            }
            ok(msg)
        },

        Tool(
            "toggle_setting",
            "setting:str wifi|bluetooth|airplane|location|nfc|hotspot|mobile_data|auto_rotate|dark_mode|battery_saver, on?:bool",
            "Turn a system setting on or off", "Device", "Changing the setting…",
        ) { a ->
            val name = a.str("setting", "name", "feature") ?: return@Tool fail("Which setting?")
            val sp = spec(name) ?: return@Tool fail("I don't know how to change $name yet.")
            toggle(sp, a.bool("on", "enabled", "state", "enable", "value"))
        },

        Tool("open_settings", "page?:str", "Open a settings page (wifi, bluetooth, display, sound, battery, apps, location, privacy, notifications, storage, about...)", "Device") { a ->
            val page = a.str("page", "name", "setting", "section") ?: ""
            val (label, intents) = settingsPage(ctx, page)
            for (i in intents) {
                if (launch(i)) return@Tool ToolOutcome(if (label.isEmpty()) "Opening settings." else "Opening $label settings.", dismiss = true)
            }
            if (launch(Intent(Settings.ACTION_SETTINGS))) ToolOutcome("Opening settings.", dismiss = true) else fail("I couldn't open settings.")
        },

        Tool("battery_status", "", "Battery level and charging state", "Device") {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            var pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct <= 0 && st != null) {
                val lvl = st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = st.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (lvl >= 0) pct = lvl * 100 / scale
            }
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val sb = StringBuilder("Battery is at $pct percent")
            if (status == BatteryManager.BATTERY_STATUS_FULL) sb.append(" and fully charged")
            else if (charging) {
                sb.append(
                    when (plugged) {
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> ", charging wirelessly"
                        BatteryManager.BATTERY_PLUGGED_USB -> ", charging over USB"
                        else -> ", charging"
                    },
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    val rem = bm.computeChargeTimeRemaining()
                    if (rem > 60_000) sb.append(" — full in about ${Fmt.duration((rem / 1000).toInt())}")
                }
            }
            sb.append('.')
            ok(sb.toString(), data = "battery=$pct% charging=$charging plugged=$plugged")
        },
    )
}
