package app.nova.assistant.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.sqrt

/**
 * "Voice ID": only wake for the enrolled speaker. Uses Vosk's speaker model (x-vectors) — fully offline.
 * Enrollment averages a few recordings of the wake phrase into a voiceprint; at wake time the last couple of
 * seconds of audio are embedded and compared (cosine similarity) against it.
 */
class VoiceId(private val app: NovaApp) {
    private val dir = File(app.filesDir, "vosk/spk")
    private var model: SpeakerModel? = null
    private var job: Job? = null

    private val _modelState = MutableStateFlow(if (isInstalled()) "Ready" else "Not downloaded")
    val modelState: StateFlow<String> = _modelState

    /** Last verification score (for the live test panel). */
    val lastScore = MutableStateFlow<Float?>(null)

    fun isInstalled() = File(dir, "final.ext.raw").exists() || File(dir, "mfcc.conf").exists()

    fun profile(): FloatArray? = runCatching {
        val a = JSONArray(app.settings[K.voiceIdProfile])
        if (a.length() == 0) null else FloatArray(a.length()) { a.getDouble(it).toFloat() }
    }.getOrNull()

    fun isEnrolled() = profile() != null
    fun isActive() = app.settings[K.voiceIdEnabled] && isEnrolled() && isInstalled()

    @Synchronized
    private fun spk(): SpeakerModel? {
        if (model == null && isInstalled()) model = runCatching { SpeakerModel(dir.absolutePath) }
            .onFailure { AppLog.d("voiceid", "speaker model load failed", it) }.getOrNull()
        return model
    }

    fun download() {
        if (job?.isActive == true) return
        job = app.appScope.launch(Dispatchers.IO) {
            val zip = File(app.filesDir, "vosk/spk.zip")
            try {
                zip.parentFile?.mkdirs()
                _modelState.value = "Downloading…"
                val conn = URL(SPK_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 13_900_000L
                conn.inputStream.use { inp ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L
                        while (isActive) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            _modelState.value = "Downloading… ${(done * 100 / total).coerceAtMost(100)}%"
                        }
                    }
                }
                val tmp = File(app.filesDir, "vosk/spk_unpack").apply { deleteRecursively(); mkdirs() }
                val canon = tmp.canonicalPath + File.separator
                ZipInputStream(zip.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val f = File(tmp, e.name)
                        if (!f.canonicalPath.startsWith(canon)) continue
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    }
                }
                val found = tmp.walkTopDown().maxDepth(3).firstOrNull { File(it, "mfcc.conf").exists() || File(it, "final.ext.raw").exists() }
                    ?: error("not a speaker model")
                synchronized(this@VoiceId) {
                    model?.close(); model = null
                    dir.deleteRecursively()
                    if (!found.renameTo(dir)) found.copyRecursively(dir, overwrite = true)
                }
                tmp.deleteRecursively()
                _modelState.value = "Ready"
                AppLog.d("voiceid", "speaker model installed")
            } catch (t: Throwable) {
                AppLog.d("voiceid", "speaker model download failed", t)
                _modelState.value = "Download failed: ${t.message}"
            } finally {
                zip.delete()
            }
        }
    }

    // ---------------------------------------------------------------- audio helpers

    /** Keeps only the voiced part of a clip (drops leading/trailing silence), with a little padding. */
    fun trim(pcm: ShortArray, n: Int = pcm.size): ShortArray {
        val frame = 320 // 20 ms
        val frames = n / frame
        if (frames < 5) return pcm.copyOf(n)
        val rms = FloatArray(frames) { f ->
            var s = 0.0
            for (i in f * frame until (f + 1) * frame) s += pcm[i].toDouble() * pcm[i]
            sqrt(s / frame).toFloat()
        }
        val floor = rms.sorted()[frames / 5].coerceAtLeast(60f)
        val voiced = rms.indices.filter { rms[it] > floor * 2.5f }
        if (voiced.isEmpty()) return pcm.copyOf(n)
        val start = ((voiced.first() - 8).coerceAtLeast(0)) * frame
        val end = ((voiced.last() + 8).coerceAtMost(frames)) * frame
        return pcm.copyOfRange(start, end)
    }

    /** Speaker embedding of a clip, or null if too short / model missing. */
    fun embed(pcm: ShortArray): FloatArray? {
        val m = app.vosk.model() ?: return null
        val s = spk() ?: return null
        return try {
            Recognizer(m, 16000f).use { r ->
                r.setSpeakerModel(s)
                var off = 0
                while (off < pcm.size) {
                    val len = minOf(3200, pcm.size - off)
                    r.acceptWaveForm(pcm.copyOfRange(off, off + len), len)
                    off += len
                }
                val o = JSONObject(r.finalResult)
                val arr = o.optJSONArray("spk") ?: return null
                if (o.optInt("spk_frames", 0) < 40) return null
                normalize(FloatArray(arr.length()) { arr.getDouble(it).toFloat() })
            }
        } catch (t: Throwable) {
            AppLog.d("voiceid", "embedding failed", t); null
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0.0; for (x in v) n += x * x
        val l = sqrt(n).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / l }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var d = 0f; for (i in a.indices) d += a[i] * b[i]; return d
    }

    /** 0..1-ish similarity of this clip to the enrolled voice, or null if it can't be judged. */
    fun score(pcm: ShortArray): Float? {
        val p = profile() ?: return null
        val v = embed(trim(pcm)) ?: return null
        if (v.size != p.size) return null
        return cosine(v, p).also { lastScore.value = it }
    }

    /** Wake-time check. True = it's you (or Voice ID is off). */
    fun verify(pcm: ShortArray): Boolean {
        if (!isActive()) return true
        val sc = score(pcm)
        val th = app.settings[K.voiceIdThreshold]
        val ok = sc != null && sc >= th
        AppLog.d("voiceid", "voice check ${sc?.let { "%.2f".format(it) } ?: "n/a"} vs ${"%.2f".format(th)} → ${if (ok) "accepted" else "rejected"}")
        return ok
    }

    // ---------------------------------------------------------------- enrollment

    /** Records one utterance: waits for speech, stops after ~0.7 s of silence (max [maxMs]). */
    @SuppressLint("MissingPermission")
    suspend fun recordUtterance(maxMs: Long = 4000, level: (Float) -> Unit = {}): ShortArray? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val out = ShortArray(16000 * 6)
        var len = 0
        val chunk = ShortArray(800) // 50 ms
        var heard = false
        var silentMs = 0
        var noise = 300f
        val start = System.currentTimeMillis()
        try {
            rec.startRecording()
            while (isActive && System.currentTimeMillis() - start < maxMs + 2000) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var s = 0.0; for (i in 0 until n) s += chunk[i].toDouble() * chunk[i]
                val rms = sqrt(s / n).toFloat()
                level((rms / 4000f).coerceIn(0f, 1f))
                if (!heard && System.currentTimeMillis() - start < 400) noise = maxOf(noise, rms * 0.8f)
                if (len + n <= out.size) { System.arraycopy(chunk, 0, out, len, n); len += n }
                if (rms > noise * 2.5f) { heard = true; silentMs = 0 } else if (heard) silentMs += 50
                if (heard && silentMs > 700) break
                if (!heard && System.currentTimeMillis() - start > maxMs) break
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
        if (!heard) null else trim(out, len)
    }

    /**
     * Saves the voiceprint from enrollment clips. Returns a message for the UI.
     * The threshold is set just under how similar your own samples are to each other.
     */
    fun enroll(clips: List<ShortArray>): String {
        val vecs = clips.mapNotNull { embed(it) }
        if (vecs.size < 3) return "Couldn't get enough clear samples (${vecs.size}). Try again somewhere quieter."
        val dim = vecs[0].size
        val mean = normalize(FloatArray(dim) { i -> vecs.sumOf { it[i].toDouble() }.toFloat() / vecs.size })
        // leave-one-out self similarity → how much your own voice varies
        val self = vecs.indices.map { k ->
            val others = vecs.filterIndexed { i, _ -> i != k }
            val m = normalize(FloatArray(dim) { i -> others.sumOf { it[i].toDouble() }.toFloat() / others.size })
            cosine(vecs[k], m)
        }
        val minSelf = self.min()
        val th = (minSelf - 0.12f).coerceIn(0.25f, 0.8f)
        app.settings[K.voiceIdProfile] = JSONArray(mean.map { it.toDouble() }).toString()
        app.settings[K.voiceIdThreshold] = th
        app.settings[K.voiceIdSamples] = vecs.size
        AppLog.d("voiceid", "enrolled ${vecs.size} samples, self-similarity ${self.joinToString { "%.2f".format(it) }}, threshold ${"%.2f".format(th)}")
        return "Voice saved from ${vecs.size} samples. Your samples matched each other at ${"%.0f".format(minSelf * 100)}%+; " +
            "Nova will wake for voices above ${"%.0f".format(th * 100)}%."
    }

    fun clear() {
        app.settings[K.voiceIdProfile] = ""
        app.settings[K.voiceIdSamples] = 0
        lastScore.value = null
    }

    companion object {
        const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    }
}
