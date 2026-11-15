package app.nova.assistant.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

enum class SttError { NO_SPEECH, BUSY, FATAL, PERMISSION }

interface SttListener {
    fun onReady() {}
    /** Every candidate transcript (best first), when the recognizer offers alternatives. */
    fun onAlternatives(all: List<String>) {}
    fun onPartial(text: String)
    fun onLevel(level: Float)
    fun onFinal(text: String)
    fun onError(kind: SttError, detail: String)
}

interface CommandRecognizer {
    val name: String
    fun start(l: SttListener)
    fun stop()
    fun cancel()
}

object Recognizers {
    private val preferred = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.as",
        "com.google.android.tts",
        "com.samsung.android.bixby.agent",
    )

    /** A system recognition service that isn't Nova itself (Nova is one too, when it's the default assistant). */
    fun findSystemService(ctx: Context): ComponentName? {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != ctx.packageName }
        val best = list.minByOrNull { si -> preferred.indexOf(si.packageName).let { if (it < 0) 99 else it } }
        return best?.let { ComponentName(it.packageName, it.name) }
    }

    fun create(app: NovaApp): CommandRecognizer {
        val engine = app.settings[K.sttEngine]
        val voskOk = app.vosk.isInstalled()
        return when (engine) {
            "vosk" -> if (voskOk) VoskRecognizer(app, app.settings[K.sttMaxSeconds]) else SystemRecognizer(app, false)
            "ondevice" -> if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
                SystemRecognizer(app, true) else SystemRecognizer(app, false)
            "system" -> SystemRecognizer(app, false)
            else -> when {
                findSystemService(app) != null -> SystemRecognizer(app, false)
                Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app) -> SystemRecognizer(app, true)
                voskOk -> VoskRecognizer(app, app.settings[K.sttMaxSeconds])
                else -> SystemRecognizer(app, false)
            }
        }
    }

    /** Next thing to try after [failed] hit a hard error. */
    fun fallback(app: NovaApp, failed: CommandRecognizer): CommandRecognizer? = when {
        failed is SystemRecognizer && !failed.onDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app) -> SystemRecognizer(app, true)
        failed !is VoskRecognizer && app.vosk.isInstalled() -> VoskRecognizer(app, app.settings[K.sttMaxSeconds])
        else -> null
    }
}

/** Android's SpeechRecognizer (Google / on-device). Must be driven from the main thread. */
class SystemRecognizer(private val app: NovaApp, val onDevice: Boolean) : CommandRecognizer {
    override val name = if (onDevice) "on-device" else "system"
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var listener: SttListener? = null
    private var done = false
    private var lastPartial = ""
    private var retries = 0
    private var offline = app.settings[K.sttPreferOffline]
    private val timeout = Runnable { sr?.stopListening() }

    override fun start(l: SttListener) {
        listener = l
        main.post { begin() }
    }

    private fun begin() {
        if (done) return
        val r = try {
            when {
                onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                else -> Recognizers.findSystemService(app)?.let { SpeechRecognizer.createSpeechRecognizer(app, it) }
                    ?: if (SpeechRecognizer.isRecognitionAvailable(app)) SpeechRecognizer.createSpeechRecognizer(app) else null
            }
        } catch (t: Throwable) {
            AppLog.d("stt", "create failed", t); null
        }
        if (r == null) { finishError(SttError.FATAL, "no speech service"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listener?.onReady() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (done) return
                AppLog.d("stt", "$name error $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (lastPartial.isNotBlank()) finish(lastPartial) else finishError(SttError.NO_SPEECH, "no speech")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                        if (retries++ < 1) restart(400) else finishError(SttError.FATAL, "busy ($error)")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finishError(SttError.PERMISSION, "permission")
                    12, 13 -> // language not supported / unavailable offline
                        if (offline) { offline = false; restart(100) } else finishError(SttError.FATAL, "language unavailable ($error)")
                    else -> if (lastPartial.isNotBlank()) finish(lastPartial) else finishError(SttError.FATAL, "error $error")
                }
            }
            override fun onResults(results: Bundle?) {
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                if (all.isNotEmpty()) listener?.onAlternatives(all)
                finish(all.firstOrNull() ?: lastPartial)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() } ?: return
                lastPartial = text
                listener?.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // your custom words as recognition hints (honoured by Android 13+ recognizers)
            if (Build.VERSION.SDK_INT >= 33) {
                val words = app.customWords.biasing()
                if (words.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, words)
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            app.settings[K.sttLanguage].takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            val silence = app.settings[K.sttSilenceMs].toLong()
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
        }
        try {
            r.startListening(i)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, app.settings[K.sttMaxSeconds] * 1000L)
        } catch (t: Throwable) {
            AppLog.d("stt", "startListening failed", t); finishError(SttError.FATAL, t.message ?: "start failed")
        }
    }

    private fun restart(delayMs: Long) {
        destroyRecognizer()
        main.postDelayed({ begin() }, delayMs)
    }

    private fun destroyRecognizer() {
        main.removeCallbacks(timeout)
        sr?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        sr = null
    }

    private fun finish(text: String) {
        if (done) return
        done = true; destroyRecognizer()
        listener?.onFinal(text)
    }

    private fun finishError(kind: SttError, detail: String) {
        if (done) return
        done = true; destroyRecognizer()
        listener?.onError(kind, detail)
    }

    override fun stop() { main.post { sr?.stopListening() ?: finish(lastPartial) } }

    override fun cancel() {
        main.post { done = true; destroyRecognizer() }
    }
}

/** Fully offline command recognition with the Vosk model (no grammar). */
class VoskRecognizer(private val app: NovaApp, private val maxSeconds: Int = 15) : CommandRecognizer {
    override val name = "vosk"
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    override fun start(l: SttListener) {
        running = true
        thread = Thread({ run(l) }, "nova-vosk-stt").apply { isDaemon = true; start() }
    }

    @SuppressLint("MissingPermission")
    private fun run(l: SttListener) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            l.onError(SttError.PERMISSION, "microphone permission"); return
        }
        val model = app.vosk.model() ?: run { l.onError(SttError.FATAL, "offline model missing"); return }
        var rec: Recognizer? = null
        var mic: AudioRecord? = null
        try {
            rec = Recognizer(model, 16000f)
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16000))
            if (mic.state != AudioRecord.STATE_INITIALIZED) { l.onError(SttError.BUSY, "mic unavailable"); return }
            mic.startRecording()
            l.onReady()
            val buf = ShortArray(1600)
            val started = System.currentTimeMillis()
            var heardSpeech = false
            var lastPartial = ""
            while (running) {
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                l.onLevel((sqrt(sum / n) / 32768.0 * 6).toFloat().coerceIn(0f, 1f))
                val elapsed = System.currentTimeMillis() - started
                if (rec.acceptWaveForm(buf, n)) {
                    val t = JSONObject(rec.result).optString("text").trim()
                    if (t.isNotEmpty()) { running = false; l.onFinal(t); return }
                } else {
                    val p = JSONObject(rec.partialResult).optString("partial").trim()
                    if (p.isNotEmpty() && p != lastPartial) { lastPartial = p; heardSpeech = true; l.onPartial(p) }
                }
                if (stopRequested || elapsed > maxSeconds * 1000L) break
                if (!heardSpeech && elapsed > 6000) { running = false; l.onError(SttError.NO_SPEECH, "no speech"); return }
            }
            if (!running && !stopRequested) return // cancelled
            val t = JSONObject(rec.finalResult).optString("text").trim()
            running = false
            if (t.isNotEmpty()) l.onFinal(t) else l.onError(SttError.NO_SPEECH, "no speech")
        } catch (t: Throwable) {
            AppLog.d("stt", "vosk failed", t)
            l.onError(SttError.FATAL, t.message ?: "vosk error")
        } finally {
            mic?.let { runCatching { it.stop() }; it.release() }
            runCatching { rec?.close() }
        }
    }

    override fun stop() { stopRequested = true }

    override fun cancel() { running = false }
}
