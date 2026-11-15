package app.nova.assistant.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class Pref<T>(val key: String, val default: T)
class BoolPref(key: String, default: Boolean) : Pref<Boolean>(key, default)
class IntPref(key: String, default: Int) : Pref<Int>(key, default)
class FloatPref(key: String, default: Float) : Pref<Float>(key, default)
class StrPref(key: String, default: String) : Pref<String>(key, default)

/** Every user-facing option lives here. Defaults aim for "works out of the box". */
object K {
    // Identity
    val assistantName = StrPref("assistant_name", "Nova")
    val userName = StrPref("user_name", "")
    val personality = StrPref("personality", "friendly, upbeat and concise")

    // Wake word
    val wakeEnabled = BoolPref("wake_enabled", true)
    val wakePhrase = StrPref("wake_phrase", "hey nova")
    val wakeVariants = StrPref("wake_variants", "")   // sound-alikes widen detection but add false wakes
    val wakeMode = StrPref("wake_mode", "grammar")          // grammar | free
    val wakeSensitivity = FloatPref("wake_sensitivity", 0.5f)
    val wakeOnPartial = BoolPref("wake_on_partial", true)
    val wakeCooldownMs = IntPref("wake_cooldown_ms", 1500)
    val commandSameBreath = BoolPref("command_same_breath", true)
    val voskModelUrl = StrPref("vosk_model_url", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
    val audioSource = StrPref("audio_source", "voice_recognition") // voice_recognition | mic | unprocessed | voice_communication
    val wakeWhilePlaying = StrPref("wake_while_playing", "strict") // strict | pause | normal — when the phone's speaker plays sound
    val wakeBeep = BoolPref("wake_beep", true)
    val wakeVibrate = BoolPref("wake_vibrate", true)

    // Voice ID (only wake for the enrolled speaker)
    val voiceIdEnabled = BoolPref("voice_id_enabled", false)
    val voiceIdProfile = StrPref("voice_id_profile", "")
    val voiceIdThreshold = FloatPref("voice_id_threshold", 0.5f)
    val voiceIdSamples = IntPref("voice_id_samples", 0)

    // Background / battery
    val startOnBoot = BoolPref("start_on_boot", true)
    val listenScreenOff = BoolPref("listen_screen_off", true)
    val onlyWhenCharging = BoolPref("only_when_charging", false)
    val pauseBatteryBelow = IntPref("pause_battery_below", 0)
    val pauseDuringCalls = BoolPref("pause_during_calls", true)
    val holdWakeLock = BoolPref("hold_wake_lock", true)
    val notifMinimized = BoolPref("notif_minimized", false)

    // Speech input
    val sttEngine = StrPref("stt_engine", "auto")            // auto | system | ondevice | vosk
    val sttLanguage = StrPref("stt_language", "")
    val sttPreferOffline = BoolPref("stt_prefer_offline", false)
    val sttSilenceMs = IntPref("stt_silence_ms", 1200)
    val sttMaxSeconds = IntPref("stt_max_seconds", 15)
    val continuous = StrPref("continuous", "after_question") // never | after_question | always
    val duckMedia = BoolPref("duck_media", true)

    // Custom vocabulary (words Nova should hear right)
    val customWords = StrPref("custom_words", "[]")

    // Voice output
    val ttsEnabled = BoolPref("tts_enabled", true)
    val ttsOnlyVoice = BoolPref("tts_only_voice", true)
    val ttsRate = FloatPref("tts_rate", 1.05f)
    val ttsPitch = FloatPref("tts_pitch", 1.0f)
    val ttsEngine = StrPref("tts_engine", "")
    val speakReminders = BoolPref("speak_reminders", true)

    // AI model
    val llmBackend = StrPref("llm_backend", "litertlm")      // litertlm | mediapipe | remote | none
    val modelPath = StrPref("model_path", "")
    val llmGpu = BoolPref("llm_gpu", true)
    val llmContext = IntPref("llm_context", 4096)
    val llmMaxOutput = IntPref("llm_max_output", 320)
    val llmTemperature = FloatPref("llm_temperature", 0.3f)
    val llmTopK = IntPref("llm_top_k", 40)
    val llmTopP = FloatPref("llm_top_p", 0.95f)
    val llmThinking = BoolPref("llm_thinking", false)
    val llmConstrained = BoolPref("llm_constrained", false)
    val promptFormat = StrPref("prompt_format", "auto")      // auto | gemma | chatml | llama3 | phi | raw
    val remoteUrl = StrPref("remote_url", "http://192.168.1.10:11434/v1")
    val remoteModel = StrPref("remote_model", "qwen3:4b")
    val remoteKey = StrPref("remote_key", "")
    val hfToken = StrPref("hf_token", "")
    val customInstructions = StrPref("custom_instructions", "")
    val maxToolSteps = IntPref("max_tool_steps", 4)
    val summarizeResults = BoolPref("summarize_results", true)
    val quickCommands = BoolPref("quick_commands", true)
    val preloadOnWake = BoolPref("preload_on_wake", true)
    val preloadOnStart = BoolPref("preload_on_start", false)
    val unloadMinutes = IntPref("unload_minutes", 15)
    val showToolCalls = BoolPref("show_tool_calls", false)
    val internetTools = BoolPref("internet_tools", true)
    val memoryEnabled = BoolPref("memory_enabled", true)
    val conversationMinutes = IntPref("conversation_minutes", 0)   // 0 = every new ask / dismiss starts a fresh chat

    // Actions
    val confirmCalls = BoolPref("confirm_calls", false)
    val confirmTexts = BoolPref("confirm_texts", true)
    val confirmApps = BoolPref("confirm_apps", true)
    val smsMethod = StrPref("sms_method", "direct")           // direct | app
    val autoSend = BoolPref("auto_send", true)
    val timerImpl = StrPref("timer_impl", "clock")            // clock | builtin
    val alarmSkipUi = BoolPref("alarm_skip_ui", true)
    val searchEngine = StrPref("search_engine", "browser")    // browser | system | google | duckduckgo | bing | perplexity
    val musicApp = StrPref("music_app", "auto")               // auto | ask | spotify | youtube_music | youtube | apple_music…
    val countryCode = StrPref("country_code", "1")
    val contactAliases = StrPref("contact_aliases", "")       // lines: mom = Jane Doe
    val homeAddress = StrPref("home_address", "")
    val workAddress = StrPref("work_address", "")
    val tempUnit = StrPref("temp_unit", "fahrenheit")         // fahrenheit | celsius
    val disabledTools = StrPref("disabled_tools", "")
    val autoShutter = BoolPref("auto_shutter", false)
    val uiAutomation = BoolPref("ui_automation", true)        // accessibility tricks for toggles

    // Home Assistant
    val haUrl = StrPref("ha_url", "")
    val haToken = StrPref("ha_token", "")                  // optional long-lived access token
    val haRefreshToken = StrPref("ha_refresh_token", "")   // from in-app sign-in
    val haAccessToken = StrPref("ha_access_token", "")
    val haAccessExpiry = StrPref("ha_access_expiry", "")
    val haWidgets = StrPref("ha_widgets", "")              // entity ids imported from the HA app's widgets
    val haFavorites = StrPref("ha_favorites", "")
    val haExposeMode = StrPref("ha_expose_mode", "all")    // all | pinned
    val haConfirmSecurity = BoolPref("ha_confirm_security", true)
    val haAssistFallback = BoolPref("ha_assist_fallback", true)

    // Popup
    val popupMode = StrPref("popup_mode", "auto")             // auto | overlay | activity
    val popupAutoDismissSec = IntPref("popup_auto_dismiss", 5)   // close when untouched and silent this long
    val popupDismissOutside = BoolPref("popup_dismiss_outside", true)
    val popupShowWhenLocked = BoolPref("popup_when_locked", true)
    val popupTextScale = FloatPref("popup_text_scale", 1.0f)
    val popupShowHistory = BoolPref("popup_show_history", true)
    val dismissOnNoSpeech = BoolPref("dismiss_no_speech", true)
    val a11yButton = BoolPref("a11y_button", false)           // Android's floating accessibility button → opens Nova

    // Internal
    val firstRunDone = BoolPref("first_run_done", false)
}

class Settings(ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changes: SharedFlow<String> = _changes.asSharedFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) _changes.tryEmit(key)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(p: Pref<T>): T = when (p) {
        is BoolPref -> prefs.getBoolean(p.key, p.default)
        is IntPref -> prefs.getInt(p.key, p.default)
        is FloatPref -> prefs.getFloat(p.key, p.default)
        is StrPref -> prefs.getString(p.key, p.default) ?: p.default
    } as T

    operator fun <T> set(p: Pref<T>, v: T) {
        prefs.edit {
            when (p) {
                is BoolPref -> putBoolean(p.key, v as Boolean)
                is IntPref -> putInt(p.key, v as Int)
                is FloatPref -> putFloat(p.key, v as Float)
                is StrPref -> putString(p.key, v as String)
            }
        }
    }

    /** All wake phrases (main + sound-alike variants), normalized. */
    fun wakePhrases(): List<String> {
        val main = this[K.wakePhrase]
        val variants = this[K.wakeVariants].split(',', '\n')
        return (listOf(main) + variants).map { TextNorm.basic(it) }.filter { it.isNotBlank() }.distinct()
    }

    fun disabledToolSet(): Set<String> =
        this[K.disabledTools].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setToolEnabled(name: String, enabled: Boolean) {
        val s = disabledToolSet().toMutableSet()
        if (enabled) s.remove(name) else s.add(name)
        this[K.disabledTools] = s.joinToString(",")
    }

    fun aliases(): Map<String, String> = this[K.contactAliases].lines().mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else TextNorm.basic(line.substring(0, i)) to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()
}

object TextNorm {
    /** lowercase, strip punctuation, collapse spaces. */
    fun basic(s: String): String = s.lowercase()
        .replace('’', '\'')
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 similarity. */
    fun similarity(a: String, b: String): Float {
        val m = maxOf(a.length, b.length)
        if (m == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / m
    }

    /** Crude phonetic key: collapses vowels/similar consonants so "nova" ~ "noah va". */
    fun phonetic(s: String): String {
        val x = basic(s).replace(" ", "")
            .replace("ph", "f").replace("ck", "k").replace("qu", "kw").replace("x", "ks")
            .replace(Regex("[ckq]"), "k").replace(Regex("[sz]"), "s").replace(Regex("[dt]"), "t")
            .replace(Regex("[bp]"), "p").replace(Regex("[vf]"), "f").replace(Regex("[mn]"), "n")
            .replace(Regex("[aeiouyhw]+"), "a")
        return x.replace(Regex("(.)\\1+"), "$1")
    }
}
