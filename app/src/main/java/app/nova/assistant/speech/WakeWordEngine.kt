package app.nova.assistant.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.TextNorm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Always-on custom wake word, fully offline.
 *
 * "grammar" mode restricts Vosk to the wake phrase(s) + [unk] — very cheap and quite precise.
 * "free" mode transcribes everything and fuzzy/phonetically matches the phrase, which also
 * works for made-up names and lets you say the command in the same breath ("Hey Nova set a timer").
 */
class WakeWordEngine(private val app: NovaApp) {
    enum class Status { STOPPED, NO_PERMISSION, NO_MODEL, LISTENING, PAUSED, ERROR }

    private val _status = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = _status
    /** Live transcript of what the wake engine hears (for the "test wake word" screen). */
    val heard = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    /** True when the OS is feeding us pure silence (mic blocked by background restrictions or another app). */
    val silentInput = MutableStateFlow(false)
    var onWake: ((String?) -> Unit)? = null

    private val holds = ConcurrentHashMap.newKeySet<String>()
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var reconfigure = false
    @Volatile private var recording = false
    @Volatile private var lastTrigger = 0L
    @Volatile private var resumedAt = 0L
    private var thread: Thread? = null

    // last 3 s of microphone audio — what Voice ID checks when the wake word fires (engine thread only)
    private val ring = ShortArray(16000 * 3)
    private var ringPos = 0
    private var ringFilled = false

    private fun recentAudio(ms: Int): ShortArray {
        val n = minOf(16 * ms, if (ringFilled) ring.size else ringPos)
        val out = ShortArray(n)
        var src = (ringPos - n + ring.size) % ring.size
        for (i in 0 until n) { out[i] = ring[src]; src = (src + 1) % ring.size }
        return out
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = Thread(::loop, "nova-wake").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        synchronized(lock) { running = false; lock.notifyAll() }
        thread?.join(1500)
        thread = null
        _status.value = Status.STOPPED
    }

    val isRunning get() = running

    /** Pause listening for [reason]. With [wait], blocks (≤1s) until the microphone is released. */
    fun hold(reason: String, wait: Boolean = false) {
        holds.add(reason)
        synchronized(lock) { lock.notifyAll() }
        if (wait) {
            val deadline = System.currentTimeMillis() + 1000
            while (recording && running && System.currentTimeMillis() < deadline) Thread.sleep(15)
        }
    }

    fun release(reason: String) {
        if (holds.remove(reason)) synchronized(lock) { lock.notifyAll() }
    }

    fun holdReasons(): Set<String> = holds.toSet()

    /** Rebuild the recognizer (wake phrase / mode / model changed). */
    fun reconfigure() { reconfigure = true }

    private fun waitFor(ms: Long) = synchronized(lock) { if (running) lock.wait(ms) }

    private fun loop() {
        var rec: Recognizer? = null
        var mic: AudioRecord? = null
        val buf = ShortArray(1600) // 100 ms @16 kHz
        var silentChunks = 0
        var partialStreak = ""
        var streakCount = 0
        while (running) {
            try {
                if (holds.isNotEmpty()) {
                    mic?.let { runCatching { it.stop() }; it.release() }; mic = null; recording = false
                    _status.value = Status.PAUSED; level.value = 0f
                    synchronized(lock) { if (holds.isNotEmpty() && running) lock.wait(1000) }
                    continue
                }
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    _status.value = Status.NO_PERMISSION; waitFor(3000); continue
                }
                val model = app.vosk.model()
                if (model == null) { _status.value = Status.NO_MODEL; waitFor(3000); continue }
                if (rec == null || reconfigure) {
                    rec?.close()
                    reconfigure = false
                    rec = buildRecognizer(model)
                }
                if (mic == null) {
                    mic = openMic()
                    if (mic == null) { _status.value = Status.ERROR; waitFor(2500); continue }
                    mic.startRecording()
                    ringPos = 0; ringFilled = false
                    recording = true
                    rec.reset()
                    resumedAt = System.currentTimeMillis()
                    _status.value = Status.LISTENING
                }
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0) { mic.release(); mic = null; recording = false; waitFor(500) }
                    continue
                }
                for (i in 0 until n) { ring[ringPos] = buf[i]; ringPos = (ringPos + 1) % ring.size; if (ringPos == 0) ringFilled = true }
                // level + "is the OS silencing us?" detection
                var sum = 0.0; var peak = 0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
                level.value = (sqrt(sum / n) / 32768.0 * 6).toFloat().coerceIn(0f, 1f)
                if (peak == 0) { if (++silentChunks == 40) { silentInput.value = true; AppLog.d("wake", "microphone delivers silence (blocked by system or another app?)") } }
                else { if (silentChunks >= 40) silentInput.value = false; silentChunks = 0 }

                val isFinal = rec.acceptWaveForm(buf, n)
                val free = app.settings[K.wakeMode] == "free"
                if (isFinal) {
                    partialStreak = ""; streakCount = 0
                    val r = rec.result
                    handleFinal(r, free)
                } else if (app.settings[K.wakeOnPartial] && !(free && app.settings[K.commandSameBreath])) {
                    val p = JSONObject(rec.partialResult).optString("partial")
                    if (p.isNotBlank()) {
                        heard.value = p
                        if (p == partialStreak) streakCount++ else { partialStreak = p; streakCount = 1 }
                        val need = if (app.settings[K.wakeSensitivity] > 0.7f) 1 else 2
                        if (streakCount >= need && matchPartial(p, free)) {
                            trigger(null); rec.reset(); partialStreak = ""; streakCount = 0
                        }
                    }
                } else if (free) {
                    val p = JSONObject(rec.partialResult).optString("partial")
                    if (p.isNotBlank()) heard.value = p
                }
            } catch (t: Throwable) {
                AppLog.d("wake", "engine error", t)
                mic?.let { runCatching { it.release() } }; mic = null; recording = false
                runCatching { rec?.close() }; rec = null
                _status.value = Status.ERROR
                waitFor(3000)
            }
        }
        mic?.let { runCatching { it.stop() }; it.release() }
        recording = false
        runCatching { rec?.close() }
        level.value = 0f
    }

    private fun buildRecognizer(model: Model): Recognizer {
        val free = app.settings[K.wakeMode] == "free"
        val r = if (free) Recognizer(model, 16000f) else {
            val g = JSONArray()
            app.settings.wakePhrases().forEach { g.put(it) }
            g.put("[unk]")
            Recognizer(model, 16000f, g.toString())
        }
        r.setWords(true)
        runCatching { r.setEndpointerMode(if (free) 0 else 1) } // short endpointing for the wake grammar
        AppLog.d("wake", "recognizer ready (mode=${if (free) "free" else "grammar"}, phrases=${app.settings.wakePhrases()})")
        return r
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val src = when (app.settings[K.audioSource]) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "unprocessed" -> MediaRecorder.AudioSource.UNPROCESSED
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val r = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16000))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); AppLog.d("wake", "AudioRecord init failed (mic busy?)"); null } else r
        } catch (t: Throwable) {
            AppLog.d("wake", "cannot open mic", t); null
        }
    }

    private fun sensitivity() = app.settings[K.wakeSensitivity].coerceIn(0f, 1f)

    private fun matchPartial(p: String, free: Boolean): Boolean {
        val text = TextNorm.basic(p)
        return if (free) fuzzyFind(text) != null
        else app.settings.wakePhrases().any { (" $text ").endsWith(" $it ") || (" $text ").contains(" $it ") }
    }

    private fun handleFinal(json: String, free: Boolean) {
        val o = JSONObject(json)
        val text = TextNorm.basic(o.optString("text"))
        if (text.isBlank()) return
        heard.value = text
        if (free) {
            val end = fuzzyFind(text) ?: return
            val words = text.split(' ')
            val trailing = words.drop(end).joinToString(" ").trim()
            trigger(if (app.settings[K.commandSameBreath] && trailing.split(' ').size >= 2) trailing else null)
            return
        }
        // grammar mode: check confidence of the matched words
        val words = o.optJSONArray("result")
        for (phrase in app.settings.wakePhrases()) {
            if (!(" $text ").contains(" $phrase ")) continue
            val pw = phrase.split(' ')
            var conf = 1.0
            if (words != null && words.length() >= pw.size) {
                val list = List(words.length()) { words.getJSONObject(it) }
                val start = (0..list.size - pw.size).firstOrNull { i -> pw.indices.all { list[i + it].optString("word") == pw[it] } }
                if (start != null) conf = pw.indices.map { list[start + it].optDouble("conf", 1.0) }.average()
            }
            val threshold = 0.9 - sensitivity() * 0.5
            if (conf >= threshold) { trigger(null); return }
            AppLog.d("wake", "heard '$phrase' but confidence ${"%.2f".format(conf)} < ${"%.2f".format(threshold)}")
        }
    }

    /** Returns the word index right after the wake phrase, or null. */
    private fun fuzzyFind(text: String): Int? {
        val words = text.split(' ').filter { it.isNotBlank() }
        val threshold = 0.85f - sensitivity() * 0.3f
        for (phrase in app.settings.wakePhrases()) {
            val n = phrase.split(' ').size
            val pPh = TextNorm.phonetic(phrase)
            for (w in listOf(n, n + 1, n - 1).filter { it >= 1 }) {
                for (i in 0..words.size - w) {
                    val cand = words.subList(i, i + w).joinToString(" ")
                    val sim = maxOf(TextNorm.similarity(cand, phrase), TextNorm.similarity(TextNorm.phonetic(cand), pPh))
                    if (sim >= threshold) return i + w
                }
            }
        }
        return null
    }

    private fun trigger(trailing: String?) {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < app.settings[K.wakeCooldownMs] || now - resumedAt < 400) return
        // Voice ID: only wake for the enrolled voice
        if (app.voiceId.isActive() && !app.voiceId.verify(recentAudio(if (trailing != null) 3000 else 2500))) {
            lastTrigger = now // don't re-check the same words a moment later
            return
        }
        lastTrigger = now
        AppLog.d("wake", "WAKE WORD detected${trailing?.let { " + \"$it\"" } ?: ""}")
        main.post { onWake?.invoke(trailing) }
    }
}
