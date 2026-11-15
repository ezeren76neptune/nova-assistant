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
private val DECOYS = listOf(
    "the", "a", "and", "to", "of", "in", "it", "is", "that", "you", "i", "he", "she", "we", "they", "this", "what",
    "so", "yeah", "yes", "okay", "oh", "well", "like", "just", "know", "now", "no", "not", "go", "get", "got", "see",
    "do", "did", "have", "was", "be", "are", "for", "on", "at", "with", "my", "your", "me", "him", "her", "them",
    "there", "here", "all", "one", "two", "right", "good", "really", "think", "want", "can", "will", "would", "about",
    "out", "up", "down", "over", "never", "other", "nothing", "every", "very", "open", "hello", "hi", "hey", "new",
    "no way", "how", "who", "why", "where", "when", "come", "time", "look", "make", "said", "say", "man", "baby",
)

private const val HOLD_MEDIA = "media playing"

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
                if (app.settings[K.wakeWhilePlaying] == "pause") {
                    if (speakerPlaying()) holds.add(HOLD_MEDIA) else holds.remove(HOLD_MEDIA)
                } else holds.remove(HOLD_MEDIA)
                if (holds.isNotEmpty()) {
                    mic?.let { runCatching { it.stop() }; it.release() }; mic = null; recording = false; releaseEffects()
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
                    confirmRec?.close(); confirmRec = null
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
                        if (streakCount >= need && System.currentTimeMillis() > partialSuppressUntil && matchPartial(p, free)) {
                            // early guesses carry no confidence — re-decode the last 2 s with word confidences first
                            val strict = strictNow()
                            val (ok0, detail) = confirmFromBuffer(model, requireLead = strict)
                            val ok = ok0 && (!strict || phraseLeads(p))
                            if (ok) trigger(null, "partial \"$p\", $detail${if (strict) ", speaker playing" else ""}")
                            else {
                                AppLog.d("wake", "ignored a possible wake word: partial \"$p\", $detail")
                                partialSuppressUntil = System.currentTimeMillis() + 1500
                            }
                            rec.reset(); partialStreak = ""; streakCount = 0
                        }
                    }
                } else if (free) {
                    val p = JSONObject(rec.partialResult).optString("partial")
                    if (p.isNotBlank()) heard.value = p
                }
            } catch (t: Throwable) {
                AppLog.d("wake", "engine error", t)
                mic?.let { runCatching { it.release() } }; mic = null; recording = false; releaseEffects()
                runCatching { rec?.close() }; rec = null
                _status.value = Status.ERROR
                waitFor(3000)
            }
        }
        mic?.let { runCatching { it.stop() }; it.release() }
        recording = false
        releaseEffects()
        runCatching { rec?.close() }
        runCatching { confirmRec?.close() }; confirmRec = null
        level.value = 0f
    }

    @Volatile private var partialSuppressUntil = 0L
    private var confirmRec: Recognizer? = null

    /**
     * Wake phrases + everyday "decoy" words + [unk]. With only the phrase to choose from, the decoder forces any
     * speech (TV, people talking) into "hey nova"; decoys give ordinary speech somewhere else to land.
     */
    private fun grammar(): String {
        val phrases = app.settings.wakePhrases()
        val phraseWords = phrases.flatMap { it.split(' ') }.toSet()
        val g = JSONArray()
        phrases.forEach { g.put(it) }
        DECOYS.filter { it !in phraseWords && it !in phrases }.forEach { g.put(it) }
        g.put("[unk]")
        return g.toString()
    }

    /** Re-decodes the last 2 s of audio with word confidences (partials have none). */
    private fun confirmFromBuffer(model: Model, requireLead: Boolean = false): Pair<Boolean, String> {
        val r = confirmRec ?: Recognizer(model, 16000f, grammar()).also { it.setWords(true); confirmRec = it }
        r.reset()
        val audio = recentAudio(2000)
        var off = 0
        while (off < audio.size) {
            val len = minOf(1600, audio.size - off)
            r.acceptWaveForm(audio.copyOfRange(off, off + len), len)
            off += len
        }
        val o = JSONObject(r.finalResult)
        val (phrase, conf) = phraseConfidence(o) ?: return false to "re-check heard \"${o.optString("text")}\""
        if (requireLead && !phraseLeads(o.optString("text")))
            return false to "re-check heard \"${o.optString("text")}\" — phrase not at the start while the speaker plays"
        val threshold = 0.9 - sensitivity() * 0.5
        return (conf >= threshold) to "re-check \"$phrase\" confidence ${"%.2f".format(conf)} (needs ${"%.2f".format(threshold)})"
    }

    /** Best wake phrase in a final result and the average confidence of its words. */
    private fun phraseConfidence(o: JSONObject): Pair<String, Double>? {
        val text = TextNorm.basic(o.optString("text"))
        val words = o.optJSONArray("result")
        var best: Pair<String, Double>? = null
        for (phrase in app.settings.wakePhrases()) {
            if (!(" $text ").contains(" $phrase ")) continue
            val pw = phrase.split(' ')
            var conf = 0.0
            if (words != null && words.length() >= pw.size) {
                val list = List(words.length()) { words.getJSONObject(it) }
                val start = (0..list.size - pw.size).firstOrNull { i -> pw.indices.all { list[i + it].optString("word") == pw[it] } }
                // every word of the phrase must be confident, not just the average
                if (start != null) conf = pw.indices.map { list[start + it].optDouble("conf", 0.0) }.min()
            }
            if (best == null || conf > best.second) best = phrase to conf
        }
        return best
    }

    private fun buildRecognizer(model: Model): Recognizer {
        val free = app.settings[K.wakeMode] == "free"
        val r = if (free) Recognizer(model, 16000f) else Recognizer(model, 16000f, grammar())
        r.setWords(true)
        runCatching { r.setEndpointerMode(if (free) 0 else 1) } // short endpointing for the wake grammar
        AppLog.d("wake", "recognizer ready (mode=${if (free) "free" else "grammar"}, phrases=${app.settings.wakePhrases()})")
        return r
    }

    private val micEffects = mutableListOf<android.media.audiofx.AudioEffect>()
    private var effectsLogged = false

    /** Echo cancellation + noise suppression on the wake mic, so the phone's own sound is subtracted. */
    private fun attachEffects(r: AudioRecord) {
        val sid = r.audioSessionId
        val aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
            runCatching { android.media.audiofx.AcousticEchoCanceler.create(sid)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (android.media.audiofx.NoiseSuppressor.isAvailable())
            runCatching { android.media.audiofx.NoiseSuppressor.create(sid)?.apply { enabled = true } }.getOrNull() else null
        listOfNotNull(aec, ns).forEach { micEffects += it }
        if (!effectsLogged) {
            effectsLogged = true
            AppLog.d("wake", "echo cancellation ${if (aec?.enabled == true) "on" else "not available"}, noise suppression ${if (ns?.enabled == true) "on" else "not available"}")
        }
    }

    private fun releaseEffects() {
        micEffects.forEach { runCatching { it.release() } }
        micEffects.clear()
    }

    private var playbackCheckedAt = 0L
    private var playbackOnSpeaker = false

    /** Is the phone playing sound out loud right now (not through headphones/Bluetooth)? */
    fun speakerPlaying(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - playbackCheckedAt < 800) return playbackOnSpeaker
        playbackCheckedAt = now
        val am = app.getSystemService(android.media.AudioManager::class.java)
        playbackOnSpeaker = am.isMusicActive && run {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val devs = am.getAudioDevicesForAttributes(
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                )
                devs.isEmpty() || devs.any { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
            } else {
                val ext = setOf(
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID,
                )
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).none { it.type in ext }
            }
        }
        return playbackOnSpeaker
    }

    /** While the speaker plays, the phrase must start the utterance — "… hey nova" mid-talk is the TV, not you. */
    private fun strictNow() = app.settings[K.wakeWhilePlaying] == "strict" && speakerPlaying()

    private fun phraseLeads(text: String): Boolean {
        val t = text.replace("[unk]", " unk ").let { TextNorm.basic(it) }
        return app.settings.wakePhrases().any { t == it || t.startsWith("$it ") }
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
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); AppLog.d("wake", "AudioRecord init failed (mic busy?)"); null }
            else r.also { attachEffects(it) }
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
            trigger(if (app.settings[K.commandSameBreath] && trailing.split(' ').size >= 2) trailing else null, "heard \"$text\"")
            return
        }
        // grammar mode: every word of the phrase must be confident
        val (phrase, conf) = phraseConfidence(o) ?: return
        if (strictNow() && !phraseLeads(text)) {
            AppLog.d("wake", "ignored a possible wake word while the speaker plays: heard \"$text\"")
            return
        }
        val threshold = 0.9 - sensitivity() * 0.5
        if (conf >= threshold) { trigger(null, "heard \"$text\", confidence ${"%.2f".format(conf)}"); return }
        AppLog.d("wake", "ignored a possible wake word: heard \"$text\", '$phrase' confidence ${"%.2f".format(conf)} < ${"%.2f".format(threshold)}")
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

    private fun trigger(trailing: String?, why: String = "") {
        val now = System.currentTimeMillis()
        if (now - lastTrigger < app.settings[K.wakeCooldownMs] || now - resumedAt < 400) return
        // Voice ID: only wake for the enrolled voice
        if (app.voiceId.isActive() && !app.voiceId.verify(recentAudio(if (trailing != null) 3000 else 2500))) {
            lastTrigger = now // don't re-check the same words a moment later
            return
        }
        lastTrigger = now
        AppLog.d("wake", "WAKE WORD detected ($why)${trailing?.let { " + \"$it\"" } ?: ""}")
        main.post { onWake?.invoke(trailing) }
    }
}
