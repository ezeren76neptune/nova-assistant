package app.nova.assistant

import android.app.Application
import android.content.Context
import app.nova.assistant.agent.Agent
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.AssistantController
import app.nova.assistant.core.CustomWords
import app.nova.assistant.core.PersonalIndex
import app.nova.assistant.core.K
import app.nova.assistant.core.MemoryStore
import app.nova.assistant.core.Settings
import app.nova.assistant.home.HomeAssistant
import app.nova.assistant.llm.LlmManager
import app.nova.assistant.service.Notifications
import app.nova.assistant.speech.AudioFx
import app.nova.assistant.speech.Speaker
import app.nova.assistant.speech.VoiceId
import app.nova.assistant.speech.VoskModelManager
import app.nova.assistant.speech.WakeWordEngine
import app.nova.assistant.tools.ToolRegistry
import app.nova.assistant.ui.PopupPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NovaApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: Settings; private set
    lateinit var memory: MemoryStore; private set
    lateinit var vosk: VoskModelManager; private set
    lateinit var wake: WakeWordEngine; private set
    lateinit var llm: LlmManager; private set
    lateinit var tools: ToolRegistry; private set
    lateinit var agent: Agent; private set
    lateinit var speaker: Speaker; private set
    lateinit var audio: AudioFx; private set
    lateinit var controller: AssistantController; private set
    lateinit var popup: PopupPresenter; private set
    lateinit var home: HomeAssistant; private set
    lateinit var voiceId: VoiceId; private set
    lateinit var customWords: CustomWords; private set
    lateinit var index: PersonalIndex; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // The on-device model runs in its own ":llm" process; it needs none of the assistant machinery.
        if (processName().endsWith(":llm")) return
        settings = Settings(this)
        memory = MemoryStore(this)
        customWords = CustomWords(settings)
        index = PersonalIndex(this)
        vosk = VoskModelManager(this)
        voiceId = VoiceId(this)
        wake = WakeWordEngine(this)
        llm = LlmManager(this)
        home = HomeAssistant(this)
        tools = ToolRegistry(this)
        agent = Agent(this)
        speaker = Speaker(this)
        audio = AudioFx(this)
        popup = PopupPresenter(this)
        controller = AssistantController(this)
        Notifications.createChannels(this)
        home.refreshInBackground()
        AppLog.d("app", "Nova started (backend=${settings[K.llmBackend]})")
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault(packageName)

    companion object {
        lateinit var instance: NovaApp
            private set
    }
}

val Context.app: NovaApp get() = applicationContext as NovaApp
