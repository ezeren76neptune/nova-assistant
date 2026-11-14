package app.nova.assistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nova.assistant.R
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.speech.VoskModelManager
import app.nova.assistant.speech.WakeWordEngine
import app.nova.assistant.ui.AssistActivity
import app.nova.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Foreground service (type = microphone) that keeps the wake-word engine alive, decides when it
 * may listen (calls, battery, charging, screen-off, user pause) and owns the persistent notification.
 */
class ListenerService : Service() {

    companion object {
        const val ACTION_PAUSE = "app.nova.assistant.action.PAUSE"
        const val ACTION_RESUME = "app.nova.assistant.action.RESUME"
        const val ACTION_STOP = "app.nova.assistant.action.STOP"
        const val ACTION_ASK = "app.nova.assistant.ASK"
        const val EXTRA_MINUTES = "minutes"
        const val NOTIF_ID = 1001
        private const val HOLD_COND = "conditions"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private val _pausedUntil = MutableStateFlow(0L)
        /** 0 = not paused, Long.MAX_VALUE = paused until resumed, else epoch millis. */
        val pausedUntil: StateFlow<Long> = _pausedUntil

        @Volatile private var instance: ListenerService? = null

        fun start(ctx: Context, reason: String) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d("svc", "not starting ($reason): microphone permission missing")
                return
            }
            AppLog.d("svc", "start requested ($reason)")
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ListenerService::class.java))
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException (31+), SecurityException, IllegalStateException
                AppLog.d("svc", "cannot start listener ($reason)", t)
                Notifications.showResumePrompt(ctx, "Tap to resume listening")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenerService::class.java))
        }

        fun pauseFor(ctx: Context, minutes: Int?) {
            _pausedUntil.value = if (minutes == null || minutes <= 0) Long.MAX_VALUE
            else System.currentTimeMillis() + minutes * 60_000L
            AppLog.d("svc", "paused ${minutes?.let { "for $it min" } ?: "until resumed"}")
            instance?.evaluate() ?: ctx.app.wake.hold(HOLD_COND)
        }

        fun resume(ctx: Context) {
            _pausedUntil.value = 0L
            AppLog.d("svc", "resumed")
            val svc = instance
            if (svc != null) svc.evaluate() else if (ctx.app.settings[K.wakeEnabled]) start(ctx, "resume")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reasonFlow = MutableStateFlow<String?>(null)
    private var setupDone = false
    private var receiverRegistered = false
    private var autoDownloadTried = false
    private var batteryLevel = -1
    private var charging = false
    private lateinit var wakeLock: PowerManager.WakeLock

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) readBattery(i)
            evaluate()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:listen").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            Notifications.showResumePrompt(this, "Tap to resume listening")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!setupDone) setup()
        when (intent?.action) {
            ACTION_PAUSE -> pauseFor(this, intent.getIntExtra(EXTRA_MINUTES, -1).takeIf { it > 0 })
            ACTION_RESUME -> resume(this)
            ACTION_STOP -> app.settings[K.wakeEnabled] = false
        }
        if (!app.settings[K.wakeEnabled]) {
            stopSelf(); return START_NOT_STICKY
        }
        evaluate()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        runCatching { NotificationManagerCompat.from(this).cancel(Notifications.PROMPT_ID) }
        true
    } catch (t: Throwable) {
        AppLog.d("svc", "startForeground(microphone) refused", t)
        false
    }

    private fun setup() {
        setupDone = true
        _running.value = true
        val a = app
        a.wake.onWake = { trailing -> a.controller.onWakeWord(trailing) }
        a.wake.start()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let { readBattery(it) }
        receiverRegistered = true

        if (a.vosk.state.value is VoskModelManager.State.Missing && !autoDownloadTried) {
            autoDownloadTried = true
            AppLog.d("svc", "wake word model missing — downloading")
            a.vosk.download()
        }
        if (a.settings[K.preloadOnStart]) a.llm.preloadInBackground()

        // periodic conditions check
        scope.launch {
            while (isActive) {
                evaluate()
                delay(4000)
            }
        }
        // settings that affect the engine / notification
        scope.launch {
            a.settings.changes.collect { key ->
                when (key) {
                    K.wakePhrase.key, K.wakeVariants.key, K.wakeMode.key, K.audioSource.key -> {
                        a.wake.reconfigure(); updateNotification()
                    }
                    K.wakeEnabled.key -> if (!a.settings[K.wakeEnabled]) stopSelf() else evaluate()
                    K.notifMinimized.key -> updateNotification()
                    K.pauseDuringCalls.key, K.onlyWhenCharging.key, K.pauseBatteryBelow.key,
                    K.listenScreenOff.key, K.holdWakeLock.key -> evaluate()
                }
            }
        }
        // throttled notification refresh
        scope.launch {
            combine(a.wake.status, _pausedUntil, a.wake.silentInput, a.vosk.state, reasonFlow) { s, p, m, v, r ->
                listOf(s, p, m, v, r)
            }.conflate().collect {
                updateNotification()
                delay(1000)
            }
        }
        AppLog.d("svc", "listener service running")
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level >= 0) batteryLevel = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun inCall(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    /** Recomputes whether the wake engine may listen right now. Main thread. */
    fun evaluate() {
        if (!setupDone) return
        val s = app.settings
        val now = System.currentTimeMillis()
        val pu = _pausedUntil.value
        if (pu != 0L && pu != Long.MAX_VALUE && now >= pu) {
            _pausedUntil.value = 0L
            AppLog.d("svc", "pause expired — listening again")
        }
        val pm = getSystemService(PowerManager::class.java)
        val interactive = pm.isInteractive
        val paused = _pausedUntil.value
        val reason: String? = when {
            !s[K.wakeEnabled] -> "Wake word is turned off"
            paused == Long.MAX_VALUE -> "Tap Resume to listen again"
            paused != 0L -> "Until ${android.text.format.DateFormat.getTimeFormat(this).format(Date(paused))} — tap Resume to listen now"
            s[K.pauseDuringCalls] && inCall() -> "Paused during a call"
            s[K.onlyWhenCharging] && !charging -> "Only listens while charging"
            s[K.pauseBatteryBelow] > 0 && batteryLevel in 0 until s[K.pauseBatteryBelow] && !charging ->
                "Battery below ${s[K.pauseBatteryBelow]}%"
            !s[K.listenScreenOff] && !interactive -> "Paused while the screen is off"
            else -> null
        }
        if (reason == null) app.wake.release(HOLD_COND) else app.wake.hold(HOLD_COND)
        if (reasonFlow.value != reason) {
            AppLog.d("svc", "conditions: ${reason ?: "listening"}")
            reasonFlow.value = reason
        }
        val needLock = reason == null && s[K.holdWakeLock] && !interactive
        try {
            if (needLock) wakeLock.acquire(10 * 60 * 1000L) else if (wakeLock.isHeld) wakeLock.release()
        } catch (t: Throwable) {
            AppLog.d("svc", "wake lock error", t)
        }
    }

    private fun titleCase(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun describe(): Pair<String, String> {
        val a = app
        val phrase = titleCase(a.settings[K.wakePhrase])
        val reason = reasonFlow.value
        val status = a.wake.status.value
        return when {
            a.wake.silentInput.value && status == WakeWordEngine.Status.LISTENING ->
                "Microphone blocked" to "Android or another app is blocking the mic"
            reason != null -> (if (_pausedUntil.value != 0L) "Paused" else "Not listening") to reason
            status == WakeWordEngine.Status.NO_PERMISSION -> "Microphone permission needed" to "Open Nova to grant it"
            status == WakeWordEngine.Status.NO_MODEL -> when (val v = a.vosk.state.value) {
                is VoskModelManager.State.Downloading -> "Wake word model downloading… ${(v.progress * 100).toInt()}%" to "One-time download (about 40 MB)"
                is VoskModelManager.State.Unpacking -> "Installing wake word model…" to "Almost ready"
                is VoskModelManager.State.Failed -> "Wake word model download failed" to v.msg
                else -> "Wake word model missing" to "Open Nova to download it"
            }
            status == WakeWordEngine.Status.ERROR -> "Microphone unavailable" to "Retrying…"
            status == WakeWordEngine.Status.PAUSED && "assistant" in a.wake.holdReasons() ->
                "${a.settings[K.assistantName]} is active" to "Listening to you"
            status == WakeWordEngine.Status.STOPPED -> "Starting…" to "Say “$phrase” or tap Ask"
            else -> "Listening for “$phrase”" to "Say it anytime, or tap Ask"
        }
    }

    private fun buildNotification(): Notification {
        val s = app.settings
        val minimized = s[K.notifMinimized]
        val channel = if (minimized) Notifications.CH_LISTEN_MIN else Notifications.CH_LISTEN
        val (title, text) = if (setupDone) describe() else "${s[K.assistantName]} is starting…" to "Preparing the wake word"
        val paused = _pausedUntil.value != 0L
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val askPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, AssistActivity::class.java).setAction(ACTION_ASK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val togglePi = PendingIntent.getService(
            this, 2,
            Intent(this, ListenerService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            flags,
        )
        val contentPi = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (minimized) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPi)
            .addAction(0, "Ask", askPi)
            .addAction(0, if (paused) "Resume" else "Pause", togglePi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (!setupDone) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (t: SecurityException) {
            // POST_NOTIFICATIONS not granted; the FGS keeps running regardless
        } catch (t: Throwable) {
            AppLog.d("svc", "notification update failed", t)
        }
    }

    override fun onDestroy() {
        AppLog.d("svc", "listener service stopped")
        scope.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(receiver) }
        receiverRegistered = false
        if (setupDone) {
            app.wake.stop()
            app.wake.onWake = null
            app.wake.release(HOLD_COND)
        }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        _running.value = false
        instance = null
        super.onDestroy()
    }
}

object Notifications {
    const val CH_LISTEN = "listen"
    const val CH_LISTEN_MIN = "listen_min"
    const val CH_ALARM = "alarm"
    const val CH_GENERAL = "general"
    const val PROMPT_ID = 1002

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val listen = NotificationChannel(CH_LISTEN, "Wake word listening", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent notification while Nova listens for its wake word"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val listenMin = NotificationChannel(CH_LISTEN_MIN, "Wake word listening (minimized)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Collapsed version of the listening notification"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val alarm = NotificationChannel(CH_ALARM, "Timers & reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Nova's built-in timers and reminders"
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500)
            setBypassDnd(true)
        }
        val general = NotificationChannel(CH_GENERAL, "General", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Setup hints and status messages"
        }
        nm.createNotificationChannels(listOf(listen, listenMin, alarm, general))
    }

    /** Shown when Android refuses to (re)start the microphone service from the background. */
    fun showResumePrompt(ctx: Context, why: String) {
        try {
            createChannels(ctx)
            val pi = PendingIntent.getForegroundService(
                ctx, 10, Intent(ctx, ListenerService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CH_GENERAL)
                .setSmallIcon(R.drawable.ic_stat_nova)
                .setContentTitle("${ctx.app.settings[K.assistantName]} isn't listening")
                .setContentText(why)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$why. Android only lets the microphone start from a tap after a reboot or update."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) NotificationManagerCompat.from(ctx).notify(PROMPT_ID, n)
        } catch (t: Throwable) {
            AppLog.d("svc", "resume prompt failed", t)
        }
    }
}
