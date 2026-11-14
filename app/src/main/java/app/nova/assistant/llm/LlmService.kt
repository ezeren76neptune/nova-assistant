package app.nova.assistant.llm

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.ChatMsg
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MSG_LOAD = 1
private const val MSG_GENERATE = 2
private const val MSG_CANCEL = 3
private const val MSG_UNLOAD = 4
private const val MSG_LOADED = 10
private const val MSG_PARTIAL = 11
private const val MSG_DONE = 12
private const val MSG_ERROR = 13

internal fun LlmConfig.toBundle() = Bundle().apply {
    putString("backend", backend); putString("path", path); putBoolean("gpu", gpu); putInt("context", context)
    putInt("maxOutput", maxOutput); putFloat("temperature", temperature); putInt("topK", topK); putFloat("topP", topP)
    putBoolean("thinking", thinking); putBoolean("constrained", constrained); putString("promptFormat", promptFormat)
    putString("remoteUrl", remoteUrl); putString("remoteModel", remoteModel); putString("remoteKey", remoteKey)
}

internal fun llmConfigOf(b: Bundle) = LlmConfig(
    backend = b.getString("backend") ?: "litertlm", path = b.getString("path") ?: "", gpu = b.getBoolean("gpu"),
    context = b.getInt("context"), maxOutput = b.getInt("maxOutput"), temperature = b.getFloat("temperature"),
    topK = b.getInt("topK"), topP = b.getFloat("topP"), thinking = b.getBoolean("thinking"),
    constrained = b.getBoolean("constrained"), promptFormat = b.getString("promptFormat") ?: "auto",
    remoteUrl = b.getString("remoteUrl") ?: "", remoteModel = b.getString("remoteModel") ?: "", remoteKey = b.getString("remoteKey") ?: "",
)

/**
 * Hosts the on-device model in its own process (`:llm`, see the manifest). The model uses 1.5–3 GB, and some
 * phones (OnePlus "o-kill") kill whichever process holds that much memory — keeping it here means only this
 * process dies, while the wake word, accessibility service and popup keep running in the main process.
 */
class LlmService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-llm") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var engine: LlmEngine? = null
    private var engineKey: String? = null
    private var current: Job? = null
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(Handler(Looper.getMainLooper()) { m -> handle(m); true })
        AppLog.d("llm", "model process started (pid ${Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun send(to: Messenger?, what: Int, id: Long, fill: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, what)
        m.data = Bundle().apply { putLong("id", id); fill() }
        try { to?.send(m) } catch (_: RemoteException) {}
    }

    private suspend fun ensure(cfg: LlmConfig): Pair<LlmEngine, String?> = mutex.withLock {
        engine?.let { if (engineKey == cfg.loadKey) { it.updateConfig(cfg); return it to null } }
        engine?.let { e -> withContext(dispatcher) { runCatching { e.close() } } }
        engine = null; engineKey = null
        val e: LlmEngine = if (cfg.backend == "mediapipe") MediaPipeEngine(this, cfg) else LiteRtLmEngine(this, cfg)
        val t0 = SystemClock.elapsedRealtime()
        try {
            withContext(dispatcher) { e.load() }
        } catch (t: Throwable) {
            withContext(dispatcher) { runCatching { e.close() } }
            throw t
        }
        engine = e; engineKey = cfg.loadKey
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        AppLog.d("llm", "ready: ${e.info} in ${"%.1f".format(Locale.US, secs)} s")
        e to e.info + String.format(Locale.US, " · loaded in %.1f s", secs)
    }

    private fun handle(m: Message) {
        val reply = m.replyTo
        val data = m.data
        val id = data.getLong("id")
        when (m.what) {
            MSG_LOAD -> scope.launch {
                try {
                    val (e, info) = ensure(llmConfigOf(data))
                    send(reply, MSG_LOADED, id) { putString("info", info ?: e.info) }
                } catch (t: Throwable) {
                    AppLog.d("llm", "load failed", t)
                    send(reply, MSG_ERROR, id) { putString("error", "Couldn't load the model: ${t.message ?: t.javaClass.simpleName}") }
                }
            }
            MSG_GENERATE -> {
                val job = scope.launch {
                    try {
                        val cfg = llmConfigOf(data)
                        val (e, info) = ensure(cfg)
                        info?.let { send(reply, MSG_LOADED, -1) { putString("info", it) } }
                        val arr = JSONArray(data.getString("messages") ?: "[]")
                        val msgs = List(arr.length()) { i -> arr.getJSONObject(i).let { ChatMsg(it.getString("r"), it.getString("t")) } }
                        val t0 = SystemClock.elapsedRealtime()
                        val out = withContext(dispatcher) {
                            e.generate(data.getString("key") ?: "", data.getString("system") ?: "", msgs) { partial ->
                                send(reply, MSG_PARTIAL, id) { putString("text", partial) }
                            }
                        }
                        AppLog.d("llm", "generated ${out.length} chars in ${SystemClock.elapsedRealtime() - t0} ms")
                        send(reply, MSG_DONE, id) { putString("text", out) }
                    } catch (c: CancellationException) {
                        send(reply, MSG_ERROR, id) { putString("error", "cancelled") }
                    } catch (t: Throwable) {
                        AppLog.d("llm", "generate failed", t)
                        send(reply, MSG_ERROR, id) { putString("error", t.message ?: t.javaClass.simpleName) }
                    }
                }
                current = job
            }
            MSG_CANCEL -> {
                engine?.let { runCatching { it.cancel() } }
                current?.cancel()
            }
            MSG_UNLOAD -> scope.launch {
                mutex.withLock {
                    engine?.let { e -> withContext(dispatcher) { runCatching { e.close() } } }
                    engine = null; engineKey = null
                }
            }
        }
    }

    override fun onDestroy() {
        // last client unbound (idle unload): free the model, then end the process so every native/GPU byte is returned
        val e = engine
        engine = null
        scope.cancel()
        Thread {
            runCatching { e?.close() }
            AppLog.d("llm", "model process exiting")
            Process.killProcess(Process.myPid())
        }.start()
        super.onDestroy()
    }
}

/** Main-process side: binds [LlmService], sends requests, streams replies, notices when the process dies. */
internal class LocalLlmClient(private val ctx: Context, private val onDied: () -> Unit) {
    class ProcessDied : RuntimeException("The AI model process was stopped by the system")

    companion object { const val LOAD_WATCHDOG_MS = 45_000L }

    private class Pending(val onPartial: (String) -> Unit, val cont: CancellableContinuation<Bundle>)

    @Volatile private var service: Messenger? = null
    @Volatile var loadedKey: String? = null; private set
    @Volatile var info: String? = null; private set
    private var bound = false
    private val waiters = mutableListOf<CancellableContinuation<Messenger>>()
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val ids = AtomicLong()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { m -> onReply(m); true })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            service = m
            val ws = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            ws.forEach { if (it.isActive) it.resume(m) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLog.d("llm", "model process died — the rest of Nova keeps running")
            service = null; loadedKey = null; info = null
            // drop the dead binding: don't let Android respawn a 1.5 GB process on its own —
            // the next request starts a fresh one
            if (bound) { runCatching { ctx.unbindService(this) }; bound = false }
            pending.keys.toList().forEach { id -> pending.remove(id)?.cont?.let { if (it.isActive) it.resumeWithException(ProcessDied()) } }
            onDied()
        }

        override fun onBindingDied(name: ComponentName?) {
            if (bound || service != null) onServiceDisconnected(name)
        }
    }

    private suspend fun connect(): Messenger {
        service?.let { return it }
        return try { connectInner() } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            AppLog.d("llm", "model process didn't start in time")
            if (bound) { runCatching { ctx.unbindService(conn) }; bound = false }
            throw ProcessDied()
        }
    }

    private suspend fun connectInner(): Messenger {
        return withTimeout(15_000) {
            suspendCancellableCoroutine { c ->
                synchronized(waiters) { waiters += c }
                if (!bound) {
                    bound = ctx.bindService(Intent(ctx, LlmService::class.java), conn, Context.BIND_AUTO_CREATE)
                    if (!bound) {
                        synchronized(waiters) { waiters.remove(c) }
                        c.resumeWithException(IllegalStateException("Couldn't start the AI model service"))
                    }
                }
                service?.let { s -> synchronized(waiters) { waiters.remove(c) }; if (c.isActive) c.resume(s) }
            }
        }
    }

    private suspend fun request(what: Int, data: Bundle, onPartial: (String) -> Unit = {}): Bundle {
        val s = connect()
        val id = ids.incrementAndGet()
        return suspendCancellableCoroutine { c ->
            pending[id] = Pending(onPartial, c)
            val m = Message.obtain(null, what)
            m.data = data.apply { putLong("id", id) }
            m.replyTo = incoming
            try {
                s.send(m)
            } catch (_: RemoteException) {
                pending.remove(id); c.resumeWithException(ProcessDied())
            }
            c.invokeOnCancellation {
                pending.remove(id)
                runCatching { service?.send(Message.obtain(null, MSG_CANCEL).apply { this.data = Bundle().apply { putLong("id", id) } }) }
            }
        }
    }

    private fun onReply(m: Message) {
        val d = m.data
        val id = d.getLong("id")
        when (m.what) {
            MSG_PARTIAL -> pending[id]?.onPartial?.invoke(d.getString("text") ?: "")
            MSG_LOADED -> if (id == -1L) info = d.getString("info") else pending.remove(id)?.cont?.let { if (it.isActive) it.resume(d) }
            MSG_DONE -> pending.remove(id)?.cont?.let { if (it.isActive) it.resume(d) }
            MSG_ERROR -> pending.remove(id)?.cont?.let {
                val err = d.getString("error") ?: "error"
                if (it.isActive) it.resumeWithException(if (err == "cancelled") CancellationException(err) else IllegalStateException(err))
            }
        }
    }

    /**
     * Loads the model. A normal load takes ~10 s; occasionally one stalls for minutes, so after
     * [LOAD_WATCHDOG_MS] the model process is restarted and the load tried once more.
     */
    suspend fun load(cfg: LlmConfig): String {
        for (attempt in 1..2) {
            val b = kotlinx.coroutines.withTimeoutOrNull(if (attempt == 1) LOAD_WATCHDOG_MS else LOAD_WATCHDOG_MS * 2) {
                request(MSG_LOAD, cfg.toBundle())
            }
            if (b != null) {
                loadedKey = cfg.loadKey
                info = b.getString("info")
                return info ?: ""
            }
            AppLog.d("llm", "model load stalled (attempt $attempt) — restarting the model process")
            killModelProcess()
        }
        throw IllegalStateException("The AI model is taking too long to load. Try again in a moment.")
    }

    /** SIGKILLs our own `:llm` process (same UID) and drops the connection. */
    fun killModelProcess() {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        am?.runningAppProcesses?.firstOrNull { it.processName.endsWith(":llm") }?.let { Process.killProcess(it.pid) }
        shutdown()
    }

    suspend fun generate(cfg: LlmConfig, key: String, system: String, messages: List<ChatMsg>, onPartial: (String) -> Unit): String {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
        val data = cfg.toBundle().apply {
            putString("key", key); putString("system", system); putString("messages", arr.toString())
        }
        val b = request(MSG_GENERATE, data, onPartial)
        loadedKey = cfg.loadKey
        return b.getString("text") ?: ""
    }

    fun cancel() {
        runCatching { service?.send(Message.obtain(null, MSG_CANCEL).apply { data = Bundle().apply { putLong("id", 0) } }) }
    }

    /** Ends the model process (frees all of its memory). */
    fun shutdown() {
        if (bound) runCatching { ctx.unbindService(conn) }
        bound = false
        service = null; loadedKey = null; info = null
        pending.keys.toList().forEach { id -> pending.remove(id)?.cont?.let { if (it.isActive) it.resumeWithException(CancellationException("model unloaded")) } }
    }
}
