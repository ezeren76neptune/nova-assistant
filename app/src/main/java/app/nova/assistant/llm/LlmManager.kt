package app.nova.assistant.llm

import android.os.SystemClock
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.ChatMsg
import app.nova.assistant.core.K
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns the on-device (or remote) language model: loading, keeping it warm, unloading when idle,
 * and serialising every native call onto one thread.
 */
class LlmManager(private val app: NovaApp) {
    sealed interface Status {
        data object None : Status
        data object Idle : Status
        data object Loading : Status
        data class Ready(val info: String) : Status
        data class Failed(val msg: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.None)
    val status: StateFlow<Status> = _status

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-llm") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var engineKey: String? = null
    private var unloadJob: Job? = null
    private val s get() = app.settings

    private val llmKeys = setOf(
        K.llmBackend.key, K.modelPath.key, K.llmGpu.key, K.llmContext.key, K.llmMaxOutput.key,
        K.llmTemperature.key, K.llmTopK.key, K.llmTopP.key, K.llmThinking.key, K.llmConstrained.key,
        K.promptFormat.key, K.remoteUrl.key, K.remoteModel.key, K.remoteKey.key, K.unloadMinutes.key,
    )

    init {
        refreshIdleStatus()
        app.appScope.launch {
            s.changes.collect { key -> if (key in llmKeys) onConfigChanged() }
        }
    }

    private fun config(): LlmConfig {
        val path = s[K.modelPath]
        var backend = s[K.llmBackend]
        // A .litertlm file can only be run by LiteRT-LM.
        if (backend == "mediapipe" && path.lowercase().endsWith(".litertlm")) backend = "litertlm"
        return LlmConfig(
            backend = backend,
            path = path,
            gpu = s[K.llmGpu],
            context = s[K.llmContext].coerceIn(512, 32768),
            maxOutput = s[K.llmMaxOutput].coerceIn(32, 4096),
            temperature = s[K.llmTemperature].coerceIn(0f, 2f),
            topK = s[K.llmTopK].coerceIn(1, 200),
            topP = s[K.llmTopP].coerceIn(0.05f, 1f),
            thinking = s[K.llmThinking],
            constrained = s[K.llmConstrained],
            promptFormat = s[K.promptFormat],
            remoteUrl = s[K.remoteUrl],
            remoteModel = s[K.remoteModel],
            remoteKey = s[K.remoteKey],
        )
    }

    private fun LlmConfig.engineKey(): String =
        if (backend == "remote") "remote|$remoteUrl|$remoteModel|$remoteKey" else loadKey

    fun isConfigured(): Boolean = when (s[K.llmBackend]) {
        "none" -> false
        "remote" -> s[K.remoteUrl].isNotBlank() && s[K.remoteModel].isNotBlank()
        else -> s[K.modelPath].let { it.isNotBlank() && File(it).exists() }
    }

    fun isReady(): Boolean = engine != null && engineKey == config().engineKey()

    fun describe(): String {
        engine?.let { if (engineKey == config().engineKey()) return it.info }
        return when (s[K.llmBackend]) {
            "none" -> "No AI model (quick commands only)"
            "remote" -> "${s[K.remoteModel]} · server ${s[K.remoteUrl]}"
            else -> {
                val p = s[K.modelPath]
                if (p.isBlank()) "No model file chosen"
                else {
                    val eng = if (ModelCatalog.engineForPath(p) == "mediapipe" && s[K.llmBackend] == "mediapipe") "MediaPipe" else "LiteRT-LM"
                    File(p).nameWithoutExtension + " · " + eng + if (s[K.llmGpu]) " GPU" else " CPU"
                }
            }
        }
    }

    fun preloadInBackground() {
        if (!isConfigured() || isReady()) return
        app.appScope.launch {
            try {
                ensureLoaded()
                scheduleUnload()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.d("llm", "preload failed", t)
            }
        }
    }

    suspend fun ensureLoaded() {
        mutex.withLock { ensureLocked() }
    }

    private suspend fun ensureLocked(): LlmEngine {
        val cfg = config()
        val key = cfg.engineKey()
        engine?.let {
            if (engineKey == key) {
                it.updateConfig(cfg)
                return it
            }
        }
        closeEngineLocked()
        if (!isConfigured()) {
            _status.value = Status.None
            error(
                when (cfg.backend) {
                    "none" -> "No AI model is enabled."
                    "remote" -> "Set the server URL and model name in AI model settings."
                    else -> "Choose or download a model file in AI model settings."
                }
            )
        }
        _status.value = Status.Loading
        AppLog.d("llm", "loading ${cfg.backend}: ${cfg.path.ifBlank { cfg.remoteUrl }} (gpu=${cfg.gpu}, ctx=${cfg.context})")
        val e: LlmEngine = when (cfg.backend) {
            "remote" -> RemoteEngine(cfg)
            "mediapipe" -> MediaPipeEngine(app, cfg)
            else -> LiteRtLmEngine(app, cfg)
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            withContext(dispatcher) { e.load() }
        } catch (t: Throwable) {
            runCatching { withContext(dispatcher) { e.close() } }
            val msg = t.message ?: t.javaClass.simpleName
            _status.value = Status.Failed(msg)
            AppLog.d("llm", "load failed", t)
            if (t is CancellationException) throw t
            throw IllegalStateException("Couldn't load the model: $msg", t)
        }
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        engine = e
        engineKey = key
        _status.value = Status.Ready(e.info + String.format(Locale.US, " · loaded in %.1f s", secs))
        AppLog.d("llm", "ready: ${e.info} in ${"%.1f".format(Locale.US, secs)} s")
        return e
    }

    /** [messages] must end with the new user message. Returns the raw model text. */
    suspend fun generate(key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String =
        mutex.withLock {
            val e = ensureLocked()
            cancelUnload()
            val t0 = SystemClock.elapsedRealtime()
            try {
                withContext(dispatcher) { e.generate(key, system, messages, onPartial) }.also {
                    AppLog.d("llm", "generated ${it.length} chars in ${SystemClock.elapsedRealtime() - t0} ms: ${it.take(160)}")
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) AppLog.d("llm", "generate failed", t)
                throw t
            } finally {
                scheduleUnload()
            }
        }

    suspend fun test(prompt: String): String {
        val t0 = SystemClock.elapsedRealtime()
        val out = generate(
            "test-" + System.nanoTime(),
            "You are a voice assistant on a phone. Reply ONLY with JSON like {\"say\":\"short reply\"}.",
            listOf(ChatMsg.user(prompt)),
        ) {}
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        return out.trim() + String.format(Locale.US, " (%.1f s)", secs)
    }

    fun cancel() {
        engine?.let { runCatching { it.cancel() } }
    }

    fun unload() {
        cancelUnload()
        app.appScope.launch {
            mutex.withLock {
                closeEngineLocked()
                refreshIdleStatus()
            }
        }
    }

    private suspend fun closeEngineLocked() {
        val e = engine ?: return
        engine = null
        engineKey = null
        withContext(dispatcher) { runCatching { e.close() } }
        AppLog.d("llm", "model unloaded")
    }

    private fun refreshIdleStatus() {
        if (engine == null) _status.value = if (isConfigured()) Status.Idle else Status.None
    }

    private fun onConfigChanged() {
        val e = engine
        if (e == null) { refreshIdleStatus(); return }
        if (engineKey != config().engineKey()) {
            app.appScope.launch {
                mutex.withLock {
                    if (engine != null && engineKey != config().engineKey()) closeEngineLocked()
                    refreshIdleStatus()
                }
            }
        } else {
            e.updateConfig(config())
            scheduleUnload()
        }
    }

    private fun cancelUnload() {
        unloadJob?.cancel()
        unloadJob = null
    }

    private fun scheduleUnload() {
        cancelUnload()
        val minutes = s[K.unloadMinutes]
        if (minutes <= 0 || engine == null) return
        unloadJob = app.appScope.launch {
            delay(minutes * 60_000L)
            AppLog.d("llm", "idle for $minutes min, unloading")
            mutex.withLock {
                closeEngineLocked()
                refreshIdleStatus()
            }
        }
    }
}
