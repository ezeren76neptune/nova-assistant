package app.nova.assistant.llm

import android.content.Context
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.ChatMsg
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Everything that affects how the model is loaded or sampled. */
internal data class LlmConfig(
    val backend: String,
    val path: String,
    val gpu: Boolean,
    val context: Int,
    val maxOutput: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val thinking: Boolean,
    val constrained: Boolean,
    val promptFormat: String,
    val remoteUrl: String,
    val remoteModel: String,
    val remoteKey: String,
) {
    /** Fields that require reloading the model when they change. Sampling params don't. */
    val loadKey: String
        get() = listOf(backend, path, gpu, context).joinToString("|")

    /** Fields that require a fresh conversation (sampling / templates). */
    val runtimeKey: String
        get() = listOf(maxOutput, temperature, topK, topP, thinking, constrained, promptFormat).joinToString("|")
}

internal interface LlmEngine {
    val info: String
    fun load()
    /** Sampling params may change between calls without a reload. */
    fun updateConfig(cfg: LlmConfig)
    suspend fun generate(key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String
    fun cancel()
    fun close()
}

private const val JSON_SCHEMA =
    """{"type":"object","properties":{"say":{"type":"string"},"tool":{"type":"string"},"args":{"type":"object"}}}"""

// ---------------------------------------------------------------------------------------------
// LiteRT-LM (.litertlm) — Google's on-device engine; chat templates come from the model file.
// ---------------------------------------------------------------------------------------------
internal class LiteRtLmEngine(private val ctx: Context, @Volatile private var cfg: LlmConfig) : LlmEngine {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var convKey: String? = null
    private var convHashes: List<Int> = emptyList()
    @Volatile private var active: Conversation? = null

    override var info: String = ""
        private set

    override fun load() {
        val name = File(cfg.path).nameWithoutExtension
        if (cfg.gpu) {
            try {
                engine = create(Backend.GPU())
                info = "$name · LiteRT-LM GPU"
                AppLog.d("llm", "LiteRT-LM loaded on GPU: $name")
                return
            } catch (t: Throwable) {
                AppLog.d("llm", "GPU init failed, falling back to CPU", t)
            }
        }
        engine = create(Backend.CPU())
        info = "$name · LiteRT-LM CPU" + if (cfg.gpu) " (GPU fallback)" else ""
        AppLog.d("llm", "LiteRT-LM loaded on CPU: $name")
    }

    private fun create(backend: Backend): Engine {
        val e = Engine(
            EngineConfig(
                modelPath = cfg.path,
                backend = backend,
                maxNumTokens = cfg.context,
                cacheDir = ctx.cacheDir.path,
            )
        )
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }

    override fun updateConfig(cfg: LlmConfig) {
        this.cfg = cfg
    }

    override suspend fun generate(key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String {
        require(messages.isNotEmpty()) { "No messages" }
        val c = cfg
        return try {
            attempt(key, system, messages, onPartial, c, c.constrained)
        } catch (e: CancellationException) {
            reset(); throw e
        } catch (t: Throwable) {
            reset()
            if (!c.constrained) throw t
            AppLog.d("llm", "constrained decoding failed, retrying unconstrained", t)
            try {
                attempt(key, system, messages, onPartial, c, false)
            } catch (t2: Throwable) {
                reset(); throw t2
            }
        }
    }

    private suspend fun attempt(
        key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit,
        c: LlmConfig, constrained: Boolean,
    ): String {
        val e = engine ?: error("Model not loaded")
        val hashes = messages.map { it.hashCode() }
        val fullKey = "$key|${c.runtimeKey}|$constrained|${system.hashCode()}"
        val reuse = conversation != null && convKey == fullKey &&
            convHashes.size == messages.size - 1 && convHashes == hashes.dropLast(1)
        if (!reuse) {
            runCatching { conversation?.close() }
            conversation = null
            convHashes = emptyList()
            conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = messages.dropLast(1).map { it.toLiteRt() },
                    samplerConfig = SamplerConfig(c.topK, c.topP.toDouble(), c.temperature.toDouble(), 0),
                    maxOutputToken = c.maxOutput,
                    thinkingConfig = ThinkingConfig(c.thinking),
                    enableResponseFormat = constrained,
                )
            )
            convKey = fullKey
            AppLog.d("llm", "new conversation (${messages.size - 1} prior messages)")
        }
        val conv = conversation!!
        active = conv
        try {
            val out = stream(conv, messages.last().toLiteRt(), onPartial, constrained)
            convHashes = hashes + ChatMsg.assistant(out).hashCode()
            return out
        } finally {
            active = null
        }
    }

    private suspend fun stream(conv: Conversation, msg: Message, onPartial: (String) -> Unit, constrained: Boolean): String =
        suspendCancellableCoroutine { cont ->
            val acc = StringBuilder()
            val cb = object : MessageCallback {
                override fun onMessage(message: Message) {
                    val t = message.text()
                    if (t.isEmpty()) return
                    val snapshot: String
                    synchronized(acc) {
                        val cur = acc.toString()
                        // Some builds stream cumulative text, others deltas: handle both.
                        if (cur.length >= 4 && t.length > cur.length && t.startsWith(cur)) {
                            acc.setLength(0); acc.append(t)
                        } else acc.append(t)
                        snapshot = acc.toString()
                    }
                    onPartial(snapshot)
                }

                override fun onDone() {
                    if (cont.isActive) cont.resume(synchronized(acc) { acc.toString() })
                }

                override fun onError(throwable: Throwable) {
                    if (cont.isActive) cont.resumeWithException(throwable)
                }
            }
            cont.invokeOnCancellation { runCatching { conv.cancelProcess() } }
            try {
                if (constrained) conv.sendMessageAsync(msg, cb, responseFormat = ResponseFormat.json(JSON_SCHEMA))
                else conv.sendMessageAsync(msg, cb)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }

    private fun reset() {
        runCatching { conversation?.close() }
        conversation = null
        convKey = null
        convHashes = emptyList()
    }

    override fun cancel() {
        active?.let { runCatching { it.cancelProcess() } }
    }

    override fun close() {
        reset()
        runCatching { engine?.close() }
        engine = null
    }

    private fun ChatMsg.toLiteRt(): Message =
        if (role == "assistant") Message.model(text) else Message.user(text)

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
}

// ---------------------------------------------------------------------------------------------
// MediaPipe LLM Inference (.task) — we render the chat template ourselves.
// ---------------------------------------------------------------------------------------------
internal class MediaPipeEngine(private val ctx: Context, @Volatile private var cfg: LlmConfig) : LlmEngine {
    private var llm: LlmInference? = null
    @Volatile private var session: LlmInferenceSession? = null

    override var info: String = ""
        private set

    override fun load() {
        val name = File(cfg.path).nameWithoutExtension
        if (cfg.gpu) {
            try {
                llm = create(LlmInference.Backend.GPU)
                info = "$name · MediaPipe GPU"
                AppLog.d("llm", "MediaPipe loaded on GPU: $name")
                return
            } catch (t: Throwable) {
                AppLog.d("llm", "MediaPipe GPU init failed, falling back to CPU", t)
            }
        }
        llm = create(LlmInference.Backend.CPU)
        info = "$name · MediaPipe CPU" + if (cfg.gpu) " (GPU fallback)" else ""
        AppLog.d("llm", "MediaPipe loaded on CPU: $name")
    }

    private fun create(b: LlmInference.Backend): LlmInference = LlmInference.createFromOptions(
        ctx,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(cfg.path)
            .setMaxTokens(cfg.context)
            .setMaxTopK(64)
            .setPreferredBackend(b)
            .build()
    )

    override fun updateConfig(cfg: LlmConfig) {
        this.cfg = cfg
    }

    override suspend fun generate(key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String {
        val l = llm ?: error("Model not loaded")
        val c = cfg
        val fmt = PromptFormat.resolve(c.promptFormat, c.path)
        // Prefill trick: start the model's turn with `{"` so it must continue a JSON object.
        val prompt = PromptFormat.render(fmt, system, messages) + PREFILL
        val emptyTemplates = PromptTemplates.builder()
            .setUserPrefix("").setUserSuffix("")
            .setModelPrefix("").setModelSuffix("")
            .setSystemPrefix("").setSystemSuffix("")
            .build()
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(c.topK.coerceIn(1, 64))
            .setTopP(c.topP)
            .setTemperature(c.temperature)
            .setPromptTemplates(emptyTemplates)
            .build()
        val s = LlmInferenceSession.createFromOptions(l, opts)
        session = s
        try {
            s.addQueryChunk(prompt)
            return suspendCancellableCoroutine { cont ->
                val acc = StringBuilder(PREFILL)
                cont.invokeOnCancellation { runCatching { s.cancelGenerateResponseAsync() } }
                try {
                    val future = s.generateResponseAsync(ProgressListener<String> { partial, done ->
                        val snapshot = synchronized(acc) {
                            if (partial != null) acc.append(partial)
                            acc.toString()
                        }
                        if (partial != null) onPartial(snapshot)
                        if (done && cont.isActive) cont.resume(snapshot)
                    })
                    future.addListener({
                        try {
                            future.get()
                        } catch (t: Throwable) {
                            val cause = (t as? java.util.concurrent.ExecutionException)?.cause ?: t
                            if (cont.isActive) cont.resumeWithException(cause)
                        }
                    }, Executor { it.run() })
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        } finally {
            session = null
            runCatching { s.close() }
        }
    }

    override fun cancel() {
        session?.let { runCatching { it.cancelGenerateResponseAsync() } }
    }

    override fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { llm?.close() }
        llm = null
    }

    companion object {
        const val PREFILL = "{\""
    }
}

/** Chat templates for raw-prompt engines. */
internal object PromptFormat {
    fun resolve(setting: String, path: String): String {
        if (setting != "auto" && setting.isNotBlank()) return setting
        val n = File(path).name.lowercase()
        return when {
            "gemma" in n -> "gemma"
            listOf("qwen", "smol", "lfm", "hammer", "deepseek").any { it in n } -> "chatml"
            "llama" in n -> "llama3"
            "phi" in n -> "phi"
            else -> "chatml"
        }
    }

    fun render(fmt: String, system: String, msgs: List<ChatMsg>): String {
        val sb = StringBuilder()
        when (fmt) {
            "gemma" -> {
                var systemFolded = false
                for (m in msgs) {
                    if (m.role == "assistant") {
                        sb.append("<start_of_turn>model\n").append(m.text).append("<end_of_turn>\n")
                    } else {
                        val t = if (!systemFolded) { systemFolded = true; "$system\n\n${m.text}" } else m.text
                        sb.append("<start_of_turn>user\n").append(t).append("<end_of_turn>\n")
                    }
                }
                if (!systemFolded) sb.append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
                sb.append("<start_of_turn>model\n")
            }
            "llama3" -> {
                sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
                for (m in msgs) {
                    val r = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|start_header_id|>").append(r).append("<|end_header_id|>\n\n").append(m.text).append("<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            "phi" -> {
                sb.append("<|system|>\n").append(system).append("<|end|>\n")
                for (m in msgs) {
                    val r = if (m.role == "assistant") "<|assistant|>" else "<|user|>"
                    sb.append(r).append("\n").append(m.text).append("<|end|>\n")
                }
                sb.append("<|assistant|>\n")
            }
            "raw" -> {
                sb.append("System: ").append(system).append("\n\n")
                for (m in msgs) {
                    sb.append(if (m.role == "assistant") "Assistant: " else "User: ").append(m.text).append("\n")
                }
                sb.append("Assistant: ")
            }
            else -> { // chatml
                sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (m in msgs) {
                    val r = if (m.role == "assistant") "assistant" else "user"
                    sb.append("<|im_start|>").append(r).append("\n").append(m.text).append("<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------------------------------------
// OpenAI-compatible server (Ollama, llama.cpp server, LM Studio, vLLM...).
// ---------------------------------------------------------------------------------------------
internal class RemoteEngine(@Volatile private var cfg: LlmConfig) : LlmEngine {
    @Volatile private var conn: HttpURLConnection? = null

    override val info: String
        get() = "${cfg.remoteModel} · server"

    override fun load() {
        // Nothing to load; the first request reveals connectivity problems.
        AppLog.d("llm", "remote backend ${endpoint(cfg.remoteUrl)} model=${cfg.remoteModel}")
    }

    override fun updateConfig(cfg: LlmConfig) {
        this.cfg = cfg
    }

    override suspend fun generate(key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val c = cfg
            val url = endpoint(c.remoteUrl)
            val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            messages.forEach {
                arr.put(JSONObject().put("role", if (it.role == "assistant") "assistant" else "user").put("content", it.text))
            }
            val body = JSONObject()
                .put("model", c.remoteModel)
                .put("messages", arr)
                .put("stream", true)
                .put("temperature", c.temperature.toDouble())
                .put("top_p", c.topP.toDouble())
                .put("max_tokens", c.maxOutput)
            if (c.constrained) body.put("response_format", JSONObject().put("type", "json_object"))

            val http = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                if (c.remoteKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${c.remoteKey}")
            }
            conn = http
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) runCatching { http.disconnect() } }
            try {
                http.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = http.responseCode
                if (code !in 200..299) {
                    val err = runCatching { http.errorStream?.bufferedReader()?.readText() }.getOrNull()?.take(300)
                    error("Server returned HTTP $code${err?.let { ": $it" } ?: ""}")
                }
                val acc = StringBuilder()
                val raw = StringBuilder()
                http.inputStream.bufferedReader().use { r ->
                    while (true) {
                        ensureActive()
                        val line = r.readLine() ?: break
                        val t = line.trim()
                        if (t.isEmpty()) continue
                        if (t.startsWith("data:")) {
                            val payload = t.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val delta = runCatching { deltaText(JSONObject(payload)) }.getOrNull()
                            if (!delta.isNullOrEmpty()) { acc.append(delta); onPartial(acc.toString()) }
                        } else if (t.startsWith("{")) {
                            // Ollama native NDJSON streams, or a plain non-streamed JSON body split across lines.
                            val obj = runCatching { JSONObject(t) }.getOrNull()
                            val delta = obj?.let { deltaText(it) }
                            if (!delta.isNullOrEmpty()) { acc.append(delta); onPartial(acc.toString()) } else raw.append(line).append('\n')
                        } else raw.append(line).append('\n')
                    }
                }
                if (acc.isEmpty() && raw.isNotBlank()) {
                    val text = runCatching {
                        JSONObject(raw.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    }.getOrNull() ?: raw.toString().trim()
                    acc.append(text)
                    onPartial(text)
                }
                acc.toString()
            } finally {
                handle?.dispose()
                conn = null
                runCatching { http.disconnect() }
            }
        }

    private fun deltaText(o: JSONObject): String? {
        o.optJSONArray("choices")?.optJSONObject(0)?.let { ch ->
            ch.optJSONObject("delta")?.let { d -> if (d.has("content") && !d.isNull("content")) return d.optString("content") }
            ch.optJSONObject("message")?.let { m -> if (m.has("content") && !m.isNull("content")) return m.optString("content") }
            if (ch.has("text")) return ch.optString("text")
            return null
        }
        o.optJSONObject("message")?.let { m -> if (m.has("content")) return m.optString("content") } // Ollama /api/chat
        if (o.has("response")) return o.optString("response") // Ollama /api/generate
        return null
    }

    override fun cancel() {
        val c = conn ?: return
        Thread { runCatching { c.disconnect() } }.start()
    }

    override fun close() = cancel()

    companion object {
        fun endpoint(url: String): String {
            val u = url.trim().trimEnd('/')
            if (u.endsWith("/chat/completions") || u.endsWith("/api/chat")) return u
            val path = runCatching { URL(u).path }.getOrDefault("")
            return if (path.isEmpty() || path == "/") "$u/v1/chat/completions" else "$u/chat/completions"
        }
    }
}
