package app.nova.assistant.core

import app.nova.assistant.NovaApp
import app.nova.assistant.agent.AgentUi
import app.nova.assistant.speech.CommandRecognizer
import app.nova.assistant.speech.Recognizers
import app.nova.assistant.speech.SttError
import app.nova.assistant.speech.SttListener
import app.nova.assistant.ui.PermissionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The assistant's state machine: wake → listen → think (agent/LLM/tools) → speak → (listen again | idle).
 * Everything UI-facing is in [state]; the popup (overlay or activity) just renders it.
 */
class AssistantController(private val app: NovaApp) {
    private val s get() = app.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Text of the current screen handed over by the assistant gesture (VoiceInteractionSession). */
    @Volatile var assistScreenText: String? = null

    private var conv = Conversation()
    private var recognizer: CommandRecognizer? = null
    private var agentJob: Job? = null
    private var listenJob: Job? = null
    private var dismissJob: Job? = null
    private var lastInputVoice = false
    private var ids = 0L

    private fun update(f: UiState.() -> UiState) = _state.update(f)

    private val ui = object : AgentUi {
        override fun streaming(text: String) = update { copy(streaming = text) }
        override fun status(text: String) = update { copy(status = text) }
        override fun found(lines: List<String>) {
            if (lines.isEmpty()) return
            val more = if (lines.size > 1) "  (+${lines.size - 1} more)" else ""
            addTurn(Who.INFO, "From your phone: " + lines.first().take(110) + more)
        }
    }

    // ---- entry points -------------------------------------------------------------------------

    fun activate(source: String, listen: Boolean = true) {
        scope.launch {
            AppLog.d("ctl", "activate from $source (listen=$listen)")
            freshIfStale()
            show()
            if (listen) startListening() else toReady()
        }
    }

    fun onWakeWord(trailing: String?) {
        scope.launch {
            if (_state.value.phase == Phase.LISTENING) return@launch
            // a stray wake word (TV, other people) must not throw away a request that's being answered
            if (_state.value.phase == Phase.THINKING) { AppLog.d("ctl", "wake word ignored while thinking"); return@launch }
            app.audio.wakeCue()
            // warm the model up while the user is still talking
            if (s[K.preloadOnWake]) app.llm.preloadInBackground()
            // a new wake word is a new request — unless the open popup is waiting for a yes/no or an answer
            val answeringPopup = _state.value.visible && (conv.pending != null || conv.awaiting != null)
            if (!answeringPopup) freshIfStale()
            show()
            if (!trailing.isNullOrBlank()) submit(trailing, voice = true) else startListening()
        }
    }

    fun startListening() {
        cancelDismiss()
        stopSpeaking()
        stopRecognizer()
        if (_state.value.phase == Phase.THINKING) { agentJob?.cancel(); app.llm.cancel() }
        update { copy(phase = Phase.LISTENING, partial = "", streaming = "", level = 0f, status = "Listening…", typing = false) }
        listenJob?.cancel()
        listenJob = scope.launch {
            // free the microphone from the wake-word engine before the recognizer grabs it
            withContext(Dispatchers.IO) { app.wake.hold(HOLD, wait = true) }
            if (_state.value.phase != Phase.LISTENING) return@launch
            app.audio.focusForListening()
            begin(Recognizers.create(app))
        }
    }

    private fun begin(rec: CommandRecognizer) {
        recognizer = rec
        AppLog.d("ctl", "listening with ${rec.name}")
        rec.start(object : SttListener {
            override fun onPartial(text: String) {
                if (recognizer === rec) update { copy(partial = app.customWords.correct(text)) }
            }
            override fun onLevel(level: Float) {
                if (recognizer === rec) update { copy(level = level) }
            }
            override fun onFinal(text: String) {
                scope.launch {
                    if (recognizer !== rec) return@launch
                    recognizer = null
                    if (text.isBlank()) noSpeech() else submit(text, voice = true)
                }
            }
            override fun onError(kind: SttError, detail: String) {
                scope.launch {
                    if (recognizer !== rec) return@launch
                    recognizer = null
                    AppLog.d("ctl", "stt ${rec.name} error $kind: $detail")
                    when (kind) {
                        SttError.NO_SPEECH -> noSpeech()
                        SttError.PERMISSION -> {
                            PermissionActivity.request(app, listOf(android.Manifest.permission.RECORD_AUDIO))
                            addTurn(Who.ERROR, "I need microphone permission to hear you.")
                            toReady()
                        }
                        else -> {
                            val fb = Recognizers.fallback(app, rec)
                            if (fb != null && _state.value.phase == Phase.LISTENING) begin(fb)
                            else { addTurn(Who.ERROR, "Speech recognition failed ($detail). You can type instead."); toReady() }
                        }
                    }
                }
            }
        })
    }

    fun onMicButton() {
        if (_state.value.phase == Phase.LISTENING) {
            val r = recognizer
            if (r != null) r.stop() else toReady()
        } else startListening()
    }

    fun submitText(text: String) = submit(text, voice = false)

    /** adb test hook (DebugReceiver): open the popup and run a typed command. */
    fun runTyped(text: String, source: String = "debug") {
        activate(source, listen = false)
        submit(text, voice = false)
    }

    fun submit(text: String, voice: Boolean) {
        // fix words the recognizer is known to mishear (Settings → Custom words)
        val t = (if (voice) app.customWords.correct(text) else text).trim()
        if (voice && t != text.trim()) AppLog.d("ctl", "custom words: \"${text.trim()}\" → \"$t\"")
        AppLog.d("ctl", (if (voice) "heard: " else "typed: ") + t.take(200))
        if (t.isEmpty()) return
        cancelDismiss()
        stopRecognizer()
        stopSpeaking()
        lastInputVoice = voice
        conv.lastActive = System.currentTimeMillis()
        if (!_state.value.visible) show()
        addTurn(Who.USER, t)
        update { copy(phase = Phase.THINKING, partial = "", streaming = "", status = "Thinking…", pending = null, level = 0f) }
        app.wake.release(HOLD)
        app.audio.releaseFocus()
        agentJob?.cancel()
        val c = conv
        agentJob = scope.launch {
            val r = try {
                app.agent.handle(t, c, ui)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.d("ctl", "agent failed", e)
                AgentResult("Sorry, something went wrong: ${e.message ?: e.javaClass.simpleName}", error = true)
            }
            if (c === conv) deliver(t, r)
        }
    }

    fun confirm(yes: Boolean) {
        val p = conv.pending ?: return
        conv.pending = null
        update { copy(pending = null) }
        cancelDismiss()
        if (!yes) {
            deliver("(cancel)", AgentResult("Okay, cancelled."))
            return
        }
        update { copy(phase = Phase.THINKING, status = "Working…") }
        val c = conv
        agentJob?.cancel()
        agentJob = scope.launch {
            val o = try { p.action() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                ToolOutcome.fail("That didn't work: ${e.message}")
            }
            c.carryOver += "[The user confirmed: ${p.question} → ${o.message}]"
            if (c === conv) deliver("(confirmed)", AgentResult(o.message, error = !o.success, dismiss = o.dismiss, quiet = o.quiet))
        }
    }

    /** Closes the popup and stops every sound Nova makes (voice, listening, earcons hold, audio focus). */
    fun dismiss(reason: String = "closed") {
        if (_state.value.visible) AppLog.d("ctl", "popup closed ($reason)")
        cancelDismiss()
        listenJob?.cancel()
        stopRecognizer()
        stopSpeaking()
        agentJob?.cancel()
        app.llm.cancel()
        app.wake.release(HOLD)
        app.audio.releaseFocus()
        conv.lastActive = System.currentTimeMillis()
        update { copy(visible = false, phase = Phase.IDLE, partial = "", streaming = "", status = "", level = 0f, typing = false) }
        app.popup.hide()
        // closing the popup ends the chat (unless the user chose to keep chats for a while)
        if (s[K.conversationMinutes] <= 0) newConversation()
    }

    /** The popup disappeared without the user pressing close (app switch, overlay lost…): go silent. */
    fun onPopupGone(reason: String) {
        if (!_state.value.visible) return
        AppLog.d("ctl", "popup gone ($reason) — stopping all sound")
        dismiss()
    }

    fun onOutsideTouch() {
        if (s[K.popupDismissOutside] && !_state.value.typing) dismiss("tapped outside")
    }

    fun userInteracted() {
        cancelDismiss()
        if (_state.value.phase == Phase.READY) scheduleDismiss()
    }

    fun setTyping(typing: Boolean) {
        if (typing) {
            cancelDismiss()
            if (_state.value.phase == Phase.LISTENING) { stopRecognizer(); toReady(schedule = false) }
            if (_state.value.phase == Phase.SPEAKING) { stopSpeaking(); toReady(schedule = false) }
        }
        update { copy(typing = typing) }
        if (!typing) scheduleDismiss()
    }

    fun newConversation() {
        agentJob?.cancel()
        val prev = conv
        conv = Conversation()
        // not chat history — just what was last adjusted, so "set it to 50%" right after still means the volume
        if (System.currentTimeMillis() - prev.lastActive < 90_000) conv.lastTool = prev.lastTool
        update { copy(turns = emptyList(), pending = null, streaming = "", partial = "") }
    }

    // ---- internals --------------------------------------------------------------------------

    private fun deliver(userText: String, r: AgentResult) {
        conv.pending = r.pending
        conv.lastActive = System.currentTimeMillis()
        if (r.reply.isNotBlank()) addTurn(if (r.error) Who.ERROR else Who.ASSISTANT, r.reply)
        if (!userText.startsWith("(")) app.memory.logExchange(userText, r.reply)
        update { copy(streaming = "", status = "", pending = r.pending?.let { PendingUi(it.question, it.confirmLabel) }) }

        val listenAgain = !r.dismiss && lastInputVoice && when (s[K.continuous]) {
            "always" -> true
            "after_question" -> r.expectReply || r.pending != null || r.reply.trimEnd().endsWith("?")
            else -> false
        }
        val next: () -> Unit = {
            when {
                r.dismiss -> dismiss("action done")
                listenAgain -> startListening()
                else -> toReady()
            }
        }
        // the popup may already be gone (e.g. hidden for a screenshot): never talk without it on screen
        if (!app.popup.isShowing()) {
            AppLog.d("ctl", "popup not on screen — not speaking")
            if (r.dismiss) { dismiss("action done"); return }
        }
        val speak = s[K.ttsEnabled] && (lastInputVoice || !s[K.ttsOnlyVoice]) && r.reply.isNotBlank() &&
            app.popup.isShowing() && !r.quiet
        if (speak) {
            update { copy(phase = Phase.SPEAKING, status = "") }
            app.wake.hold(HOLD_TTS) // don't let our own voice trigger the wake word
            app.audio.focusForSpeaking()
            app.speaker.speak(r.reply) {
                scope.launch {
                    app.wake.release(HOLD_TTS)
                    if (_state.value.phase == Phase.SPEAKING) next()
                }
            }
        } else next()
    }

    /** Listening ended with nothing said: the recognizer already waited out the silence, so close right away. */
    private fun noSpeech() {
        if (s[K.dismissOnNoSpeech] && !_state.value.typing && _state.value.pending == null) dismiss("nothing said")
        else toReady()
    }

    private fun toReady(schedule: Boolean = true) {
        app.wake.release(HOLD)
        app.audio.releaseFocus()
        update { copy(phase = Phase.READY, status = "", partial = "", level = 0f) }
        if (schedule) scheduleDismiss()
    }

    private fun show() {
        update { copy(visible = true) }
        app.popup.show()
    }

    /** Every new ask starts a new chat; with "keep chat for N min" set, recent chats continue instead. */
    private fun freshIfStale() {
        val minutes = s[K.conversationMinutes]
        val idleMs = System.currentTimeMillis() - conv.lastActive
        if (minutes <= 0 || idleMs > minutes * 60_000L) newConversation()
    }

    private fun addTurn(who: Who, text: String) = update { copy(turns = turns + Turn(++ids, who, text)) }

    private fun stopRecognizer() {
        recognizer?.cancel()
        recognizer = null
    }

    private fun stopSpeaking() {
        app.speaker.stop()
        app.wake.release(HOLD_TTS)
    }

    private fun scheduleDismiss() {
        cancelDismiss()
        val sec = s[K.popupAutoDismissSec]
        if (sec <= 0 || _state.value.typing || !_state.value.visible) return
        // a pending "Send this?" gets longer before it quietly goes away (and is cancelled)
        val waitMs = sec * 1000L * (if (_state.value.pending != null) 3 else 1)
        dismissJob = scope.launch {
            delay(waitMs)
            val st = _state.value
            if (st.phase == Phase.READY && !st.typing) dismiss("idle ${waitMs / 1000}s")
        }
    }

    private fun cancelDismiss() {
        dismissJob?.cancel(); dismissJob = null
    }

    companion object {
        const val HOLD = "assistant"
        const val HOLD_TTS = "tts"
    }
}
