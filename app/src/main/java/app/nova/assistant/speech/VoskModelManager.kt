package app.nova.assistant.speech

import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads, unpacks and loads the offline Vosk speech model used for the wake word. */
class VoskModelManager(private val app: NovaApp) {
    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Unpacking : State
        data object Ready : State
        data class Failed(val msg: String) : State
    }

    private val root = File(app.filesDir, "vosk")
    private val modelDir = File(root, "model")
    private val _state = MutableStateFlow<State>(if (isInstalled()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state
    private var model: Model? = null
    private var vocab: Set<String>? = null
    private var job: Job? = null

    fun isInstalled() = File(modelDir, "conf").exists() || File(modelDir, "am").exists()

    @Synchronized
    fun model(): Model? {
        if (model == null && isInstalled()) {
            model = try {
                Model(modelDir.absolutePath)
            } catch (t: Throwable) {
                AppLog.d("vosk", "model load failed", t); _state.value = State.Failed("Model load failed: ${t.message}"); null
            }
        }
        return model
    }

    /** Words of the phrase the offline model doesn't know (grammar mode can't hear those). */
    fun unknownWords(phrase: String): List<String> {
        val v = vocab ?: run {
            val f = File(modelDir, "graph/words.txt")
            if (!f.exists()) return emptyList()
            f.useLines { lines -> lines.map { it.substringBefore(' ') }.toHashSet() }.also { vocab = it }
        }
        return TextNorm.basic(phrase).split(' ').filter { it.isNotBlank() && it !in v }
    }

    fun download(url: String = app.settings[K.voskModelUrl]) {
        if (job?.isActive == true) return
        job = app.appScope.launch(Dispatchers.IO) {
            val zip = File(root, "download.zip")
            try {
                root.mkdirs()
                _state.value = State.Downloading(0f)
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                // follow cross-protocol redirects manually
                var hops = 0
                while (conn.responseCode in 300..399 && hops++ < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    conn = URL(URL(url), loc).openConnection() as HttpURLConnection
                }
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 41_000_000L
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (isActive) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                _state.value = State.Unpacking
                val tmp = File(root, "unpack").apply { deleteRecursively(); mkdirs() }
                unzip(zip, tmp)
                val found = tmp.walkTopDown().maxDepth(3).firstOrNull { File(it, "am").isDirectory || File(it, "conf").isDirectory }
                    ?: error("Archive doesn't look like a Vosk model")
                synchronized(this@VoskModelManager) {
                    model?.close(); model = null; vocab = null
                    modelDir.deleteRecursively()
                    if (!found.renameTo(modelDir)) found.copyRecursively(modelDir, overwrite = true)
                }
                tmp.deleteRecursively()
                _state.value = State.Ready
                AppLog.d("vosk", "model installed from $url")
                app.wake.reconfigure()
            } catch (t: Throwable) {
                AppLog.d("vosk", "download failed", t)
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                zip.delete()
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val canonical = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(canonical)) continue // zip-slip guard
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
    }

    fun delete() {
        synchronized(this) { model?.close(); model = null; vocab = null; modelDir.deleteRecursively() }
        _state.value = State.Missing
    }
}
