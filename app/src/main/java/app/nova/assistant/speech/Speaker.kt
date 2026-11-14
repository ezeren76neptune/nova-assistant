package app.nova.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech for replies. */
class Speaker(private val app: NovaApp) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: Pair<String, () -> Unit>? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    @Volatile var isSpeaking = false
        private set

    init { init() }

    private fun init() {
        ready = false
        val engine = app.settings[K.ttsEngine].ifBlank { null }
        tts = TextToSpeech(app, { status ->
            ready = status == TextToSpeech.SUCCESS
            AppLog.d("tts", "init status=$status engine=${engine ?: "default"}")
            if (ready) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isSpeaking = true }
                    override fun onDone(utteranceId: String?) = finished(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { isSpeaking = false }
                })
                queued?.let { (t, cb) -> queued = null; speak(t, cb) }
            } else {
                queued?.let { (_, cb) -> queued = null; main.post(cb) }
            }
        }, engine)
    }

    private fun finished(id: String?) {
        isSpeaking = false
        val cb = id?.let { callbacks.remove(it) } ?: return
        main.post(cb)
    }

    fun reinit() {
        stop()
        runCatching { tts?.shutdown() }
        init()
    }

    fun engines(): List<Pair<String, String>> =
        tts?.engines?.map { it.name to it.label } ?: emptyList()

    fun speak(text: String, onDone: () -> Unit) {
        val clean = clean(text)
        if (clean.isBlank()) { main.post(onDone); return }
        val t = tts
        if (t == null || !ready) { queued = text to onDone; return }
        t.setSpeechRate(app.settings[K.ttsRate])
        t.setPitch(app.settings[K.ttsPitch])
        app.settings[K.sttLanguage].takeIf { it.isNotBlank() }?.let { runCatching { t.language = Locale.forLanguageTag(it) } }
        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone
        val r = t.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) { callbacks.remove(id); main.post(onDone) }
    }

    fun stop() {
        callbacks.clear()
        queued = null
        isSpeaking = false
        runCatching { tts?.stop() }
    }

    private fun clean(s: String) = s
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[*_#`>|~]"), "")
        .replace(Regex("[\\p{So}\\p{Cn}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Earcons, haptics and audio focus (pause/duck music while listening and speaking). */
class AudioFx(private val app: NovaApp) {
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null
    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull() }

    fun wakeCue() {
        if (app.settings[K.wakeBeep]) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 130) }
        if (app.settings[K.wakeVibrate]) runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun focusForListening() = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    fun focusForSpeaking() = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    private fun request(type: Int) {
        if (!app.settings[K.duckMedia]) return
        releaseFocus()
        val req = AudioFocusRequest.Builder(type)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runCatching { am.requestAudioFocus(req) }
        focus = req
    }

    fun releaseFocus() {
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }
}
