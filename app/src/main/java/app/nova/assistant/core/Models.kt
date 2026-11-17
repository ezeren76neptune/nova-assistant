package app.nova.assistant.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app ring-buffer log (Settings → Logs) so problems can be diagnosed on the phone. Also kept in a small
 * private file, so it survives the app being killed or updated.
 */
object AppLog {
    private val buf = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: java.io.File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "applog").apply { isDaemon = true } }

    /** Main process only: reload the saved log and keep writing to it. */
    fun init(dir: java.io.File) {
        val f = java.io.File(dir, "nova_log.txt")
        synchronized(this) {
            runCatching { if (f.exists()) f.readLines().takeLast(600).forEach { buf.addLast(it) } }
            file = f
        }
        d("app", "=== Nova started ${SimpleDateFormat("EEE MMM d", Locale.US).format(Date())} ===")
    }

    @Synchronized
    fun d(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w("Nova/$tag", msg, t) else Log.d("Nova/$tag", msg)
        val line = "${fmt.format(Date())} [$tag] $msg" +
            (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        buf.addLast(line)
        while (buf.size > 600) buf.removeFirst()
        _lines.value = buf.toList()
        file?.let { f ->
            writer.execute {
                runCatching {
                    f.appendText(line.replace('\n', ' ') + "\n")
                    if (f.length() > 300_000) f.writeText(f.readLines().takeLast(800).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        buf.clear(); _lines.value = emptyList()
        file?.let { f -> writer.execute { runCatching { f.delete() } } }
    }
}

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, READY }
enum class Who { USER, ASSISTANT, INFO, ERROR }

data class Turn(val id: Long, val who: Who, val text: String)

data class PendingUi(val question: String, val confirmLabel: String)

data class UiState(
    val visible: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val streaming: String = "",
    val status: String = "",
    val turns: List<Turn> = emptyList(),
    val level: Float = 0f,
    val pending: PendingUi? = null,
    val typing: Boolean = false,
)

/** One message in the model's conversation (raw protocol text, not what the UI shows). */
data class ChatMsg(val role: String, val text: String) {
    companion object {
        fun user(t: String) = ChatMsg("user", t)
        fun assistant(t: String) = ChatMsg("assistant", t)
    }
}

/** An action waiting for the user to say yes/no (send this text?, call?). */
class Pending(
    val question: String,
    val confirmLabel: String,
    val action: suspend () -> ToolOutcome,
)

/** Shared result type of every tool. */
data class ToolOutcome(
    val message: String,
    val data: String? = null,
    val needsModel: Boolean = false,
    val success: Boolean = true,
    val confirm: Pending? = null,
    val dismiss: Boolean = false,
    val ask: (suspend (String) -> ToolOutcome)? = null,
    /** Don't speak the message (e.g. music just started — talking would interrupt it). */
    val quiet: Boolean = false,
    /** [message] is only a fallback: even for a quick command, let the AI answer from [data] (web search). */
    val preferModel: Boolean = false,
    /** Shown in the popup as an info line (e.g. which websites were used). */
    val note: String? = null,
) {
    companion object {
        fun ok(msg: String, data: String? = null, needsModel: Boolean = false) = ToolOutcome(msg, data, needsModel)
        fun fail(msg: String) = ToolOutcome(msg, success = false)
    }
}

data class AgentResult(
    val reply: String,
    val pending: Pending? = null,
    val error: Boolean = false,
    val dismiss: Boolean = false,
    val expectReply: Boolean = false,
    val quiet: Boolean = false,
    /** An answer from the AI in a back-and-forth (not a finished task) — Nova listens for a follow-up. */
    val conversational: Boolean = false,
)

/** One popup conversation; survives re-opening the popup for a few minutes. */
class Conversation {
    val id: String = "c" + System.nanoTime()
    val llmMessages = mutableListOf<ChatMsg>()
    var pending: Pending? = null
    var awaiting: (suspend (String) -> AgentResult)? = null
    /** Facts that happened outside the model (quick commands, tool results) to prepend to its next message. */
    val carryOver = mutableListOf<String>()
    /** Last tool that ran — lets follow-ups like "set it to 50%" or "a bit more" target the same thing. */
    var lastTool: String? = null
    var lastActive = System.currentTimeMillis()
}
