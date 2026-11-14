package app.nova.assistant.service

import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.speech.CommandRecognizer
import app.nova.assistant.speech.SttError
import app.nova.assistant.speech.SttListener
import app.nova.assistant.speech.VoskRecognizer
import app.nova.assistant.ui.AssistActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets Nova be chosen as the device's "Digital assistant app". The system keeps this service bound
 * (a strong keep-alive) and routes the assist gesture / long-press home / power to [AssistSession].
 */
class AssistVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AppLog.d("voice", "Nova is the active assistant")
        if (app.settings[K.wakeEnabled]) ListenerService.start(this, "assistant role")
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val app = context.app
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            try {
                startAssistantActivity(
                    Intent(context, AssistActivity::class.java)
                        .setAction(ListenerService.ACTION_ASK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                AppLog.d("voice", "startAssistantActivity failed", t)
                app.controller.activate("assist_gesture")
            }
        } else {
            app.controller.activate("assist_gesture")
        }
        // Keep the (invisible) session window briefly so assist data can arrive, then get out of the way.
        main.postDelayed({ runCatching { hide() } }, 400)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        super.onHandleAssist(state)
        val structure = state.assistStructure ?: return
        val text = flatten(structure, 4000)
        if (text.isNotBlank()) {
            context.app.controller.assistScreenText = text
            AppLog.d("voice", "received screen context (${text.length} chars)")
        }
    }

    private fun flatten(s: AssistStructure, max: Int): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        fun visit(n: AssistStructure.ViewNode, depth: Int) {
            if (sb.length >= max || depth > 40) return
            if (n.visibility != android.view.View.VISIBLE) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val piece = (if (t.isNotEmpty()) t else d).replace('\n', ' ')
            if (piece.isNotEmpty() && seen.add(piece)) sb.append(piece).append('\n')
            for (i in 0 until n.childCount) visit(n.getChildAt(i), depth + 1)
        }
        for (w in 0 until s.windowNodeCount) {
            s.getWindowNodeAt(w)?.rootViewNode?.let { visit(it, 0) }
        }
        return sb.toString().take(max)
    }
}

/**
 * Offline speech recognizer (Vosk) exposed to the system. Android requires the assistant app to
 * provide one; it also makes Nova usable as a voice-input provider for other apps.
 */
class RecognitionProxyService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rec: CommandRecognizer? = null
    private var holding = false

    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback) {
        rec?.cancel(); rec = null
        val app = app
        if (!app.vosk.isInstalled()) {
            AppLog.d("voice", "recognition requested but the offline model isn't installed")
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { app.wake.hold(HOLD, wait = true) }
            holding = true
            val r = VoskRecognizer(app)
            rec = r
            var readySent = false
            fun ready() {
                if (!readySent) { readySent = true; runCatching { listener.readyForSpeech(Bundle()) } }
            }
            r.start(object : SttListener {
                override fun onReady() = ready()
                override fun onPartial(text: String) {
                    ready()
                    runCatching { listener.partialResults(results(text)) }
                }
                override fun onLevel(level: Float) {
                    runCatching { listener.rmsChanged(level * 10f) }
                }
                override fun onFinal(text: String) {
                    runCatching {
                        if (text.isBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                        else { listener.endOfSpeech(); listener.results(results(text)) }
                    }
                    finish(r)
                }
                override fun onError(kind: SttError, detail: String) {
                    val code = when (kind) {
                        SttError.NO_SPEECH -> SpeechRecognizer.ERROR_NO_MATCH
                        SttError.PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        SttError.BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        SttError.FATAL -> SpeechRecognizer.ERROR_CLIENT
                    }
                    runCatching { listener.error(code) }
                    finish(r)
                }
            })
            ready()
        }
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun finish(r: CommandRecognizer) {
        scope.launch {
            if (rec === r) rec = null
            releaseHold()
        }
    }

    private fun releaseHold() {
        if (holding) { holding = false; app.wake.release(HOLD) }
    }

    override fun onStopListening(listener: RecognitionService.Callback) {
        rec?.stop()
    }

    override fun onCancel(listener: RecognitionService.Callback) {
        rec?.cancel(); rec = null
        releaseHold()
    }

    override fun onDestroy() {
        rec?.cancel(); rec = null
        releaseHold()
        app.wake.release(HOLD)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val HOLD = "recognition_service"
    }
}
