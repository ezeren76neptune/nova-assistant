package app.nova.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nova.assistant.BuildConfig
import app.nova.assistant.R
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.service.ListenerService
import app.nova.assistant.ui.Setup.openFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        content = content,
    )
}

@Composable
fun SettingHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------- Wake word

@Composable
fun WakeScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val phrase by rememberPref(K.wakePhrase)
    val mode by rememberPref(K.wakeMode)
    val voskState by app.vosk.state.collectAsState()
    var unknown by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(phrase, voskState) {
        unknown = withContext(Dispatchers.IO) { runCatching { app.vosk.unknownWords(phrase) }.getOrDefault(emptyList()) }
    }
    val reconfigure = { _: Any -> app.wake.reconfigure() }

    SettingsScroll {
        PrefSection("Phrase")
        TextPref(
            "Wake phrase", K.wakePhrase,
            summary = "Say this to wake the assistant. Two or three syllables or more works best.",
            placeholder = "hey nova", onSaved = reconfigure,
        )
        if (unknown.isNotEmpty() && mode == "grammar") {
            Text(
                "These words aren't in the offline vocabulary: ${unknown.joinToString()}. " +
                    "Switch Detection mode to Free speech, or add sound-alike variants below.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        TextPref(
            "Sound-alike variants", K.wakeVariants,
            summary = "Comma-separated phrases the recognizer might hear instead (e.g. \"hey noah, a nova\"). Also trigger the assistant.",
            onSaved = reconfigure,
        )
        ChoicePref(
            "Detection mode", K.wakeMode,
            listOf(
                "grammar" to "Precise (low battery) — listens only for your phrase",
                "free" to "Free speech — any made-up word, fuzzy match, command in the same breath",
            ),
            onChange = reconfigure,
        )

        PrefSection("Tuning")
        SliderPref(
            "Sensitivity", K.wakeSensitivity, 0f..1f,
            summary = "Higher triggers more easily (and more false alarms).",
            format = { "${(it * 100).toInt()}%" },
        )
        SwitchPref(
            "Only when the wake word starts what you say", K.wakeNeedsLead,
            "Ignores \"hey nova\" in the middle of other talk (TV, conversations). Say it after a short pause.",
        )
        ChoicePref(
            "While the phone plays sound", K.wakeWhilePlaying,
            listOf(
                "strict" to "Stricter — ignore the phrase in the middle of other talk (TV, videos)",
                "pause" to "Pause the wake word while the speaker plays",
                "normal" to "Listen normally",
            ),
        )
        SwitchPref("Trigger on partial result", K.wakeOnPartial, "Faster: reacts as soon as the phrase is heard, before you pause.")
        SwitchPref(
            "Command in the same breath", K.commandSameBreath,
            "Free mode: \"Hey Nova set a timer for 5 minutes\" runs without waiting for a second prompt.",
        )
        IntSliderPref("Cooldown after triggering", K.wakeCooldownMs, 500..5000, 250, format = { "${it} ms" })
        ChoicePref(
            "Microphone source", K.audioSource,
            listOf(
                "voice_recognition" to "Voice recognition (default)",
                "mic" to "Raw microphone",
                "unprocessed" to "Unprocessed",
                "voice_communication" to "Voice call (echo cancelling — fewer self-triggers from the speaker)",
            ),
            summary = "Applies the next time listening restarts.",
            onChange = reconfigure,
        )
        SwitchPref("Beep when triggered", K.wakeBeep)
        SwitchPref("Vibrate when triggered", K.wakeVibrate)

        VoiceIdSection()

        PrefSection("Offline speech model")
        TextPref("Model download URL", K.voskModelUrl, summary = "Any Vosk model zip (other languages: alphacephei.com/vosk/models).")
        ActionPref("Re-download model", "Downloads and installs the model from the URL above.") { app.vosk.download() }
        when (val s = voskState) {
            is app.nova.assistant.speech.VoskModelManager.State.Downloading ->
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(16.dp))
            is app.nova.assistant.speech.VoskModelManager.State.Failed -> SettingHint("Last download failed: ${s.msg}")
            else -> {}
        }

        PrefSection("Live test")
        WakeTestPanel()
    }
}

@Composable
fun WakeTestPanel() {
    val app = LocalContext.current.app
    val heard by app.wake.heard.collectAsState()
    val level by app.wake.level.collectAsState()
    val status by app.wake.status.collectAsState()
    val running by ListenerService.running.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Engine: ${status.name.lowercase()}" + if (!running) " (listener service not running)" else "", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text("Mic level", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text("Heard:", style = MaterialTheme.typography.bodySmall)
            Text(heard.ifBlank { "…" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Say your wake phrase and watch what the engine hears. If it hears something different, add that as a sound-alike variant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Speech input

@Composable
fun SpeechScreen() {
    SettingsScroll {
        PrefSection("Recognizer")
        ChoicePref(
            "Speech engine", K.sttEngine,
            listOf(
                "auto" to "Automatic (Google / system, falls back to offline)",
                "system" to "System recognizer (Google — most accurate)",
                "ondevice" to "On-device system recognizer (Android 12+, private)",
                "vosk" to "Vosk offline (fully private, less accurate)",
            ),
        )
        TextPref("Language", K.sttLanguage, summary = "BCP-47 tag like en-US or de-DE. Blank = phone language.", placeholder = "en-US")
        SwitchPref("Prefer offline recognition", K.sttPreferOffline, "Asks the system recognizer to stay on-device when it can.")
        IntSliderPref("Silence before finishing", K.sttSilenceMs, 500..4000, 100, format = { "${it} ms" })
        IntSliderPref("Maximum listening time", K.sttMaxSeconds, 5..60, 1, format = { "${it} s" })

        PrefSection("Conversation")
        ChoicePref(
            "Keep listening after a reply", K.continuous,
            listOf(
                "never" to "Never",
                "after_question" to "When the assistant asks a question",
                "always" to "Always (hands-free conversation)",
            ),
        )
        SwitchPref("Pause or duck music while listening/speaking", K.duckMedia)
        SwitchPref("Close the popup if nothing was said", K.dismissOnNoSpeech)
    }
}

// ---------------------------------------------------------------- Voice output

@Composable
fun VoiceScreen() {
    val app = LocalContext.current.app
    val name by rememberPref(K.assistantName)
    val engines = remember { runCatching { app.speaker.engines() }.getOrDefault(emptyList()) }
    SettingsScroll {
        PrefSection("Text to speech")
        SwitchPref("Speak replies", K.ttsEnabled)
        SwitchPref("Only when I spoke", K.ttsOnlyVoice, "Stay quiet when you typed the request.")
        SliderPref("Speech rate", K.ttsRate, 0.5f..2.0f, format = { "${fmtNum(it, 2)}×" })
        SliderPref("Pitch", K.ttsPitch, 0.5f..2.0f, format = { "${fmtNum(it, 2)}×" })
        ChoicePref(
            "Voice engine", K.ttsEngine,
            listOf("" to "System default") + engines,
            onChange = { app.speaker.reinit() },
        )
        SwitchPref("Read reminders and timers aloud", K.speakReminders)
        ActionPref("Test voice", "Plays a sample with the current settings.") {
            app.speaker.speak("Hi, I'm $name. This is how I sound.") {}
        }
        SettingHint("Install more voices in Android Settings → Text-to-speech output (e.g. Google's high-quality offline voices).")
    }
}

// ---------------------------------------------------------------- Actions

@Composable
fun ActionsScreen() {
    SettingsScroll {
        PrefSection("Confirm before…")
        SwitchPref("Calling", K.confirmCalls)
        SwitchPref("Sending texts", K.confirmTexts)
        SwitchPref("Sending messages in other apps (WhatsApp, replies…)", K.confirmApps)

        PrefSection("Messaging")
        ChoicePref(
            "Send texts", K.smsMethod,
            listOf("direct" to "Directly in the background (SMS permission)", "app" to "Open my messaging app"),
        )
        SwitchPref("Auto-press Send", K.autoSend, "Uses the accessibility service to press Send in WhatsApp / Messages after filling the text.")
        TextPref("Country calling code", K.countryCode, summary = "Added to numbers without one (WhatsApp needs international numbers).", number = true)
        TextPref(
            "Contact nicknames", K.contactAliases, multiline = true,
            summary = "One per line, e.g. \"mom = Jane Doe\" or \"boss = +1 555 123 4567\".",
            placeholder = "mom = Jane Doe",
        )

        PrefSection("Clock")
        ChoicePref(
            "Timers", K.timerImpl,
            listOf("clock" to "Clock app", "builtin" to "Built-in (can check and cancel by voice)"),
        )
        SwitchPref("Set alarms silently", K.alarmSkipUi, "Don't open the Clock app when setting alarms.")

        PrefSection("Apps")
        ChoicePref(
            "Web search", K.searchEngine,
            listOf(
                "browser" to "Default browser (its own search engine)",
                "system" to "Google app",
                "google" to "Google in browser",
                "duckduckgo" to "DuckDuckGo",
                "bing" to "Bing",
                "perplexity" to "Perplexity",
            ),
        )
        ChoicePref(
            "Music app", K.musicApp,
            listOf(
                "auto" to "Automatic (first installed: Spotify, YouTube Music, …)",
                "spotify" to "Spotify", "youtube_music" to "YouTube Music", "youtube" to "YouTube",
                "apple_music" to "Apple Music", "amazon_music" to "Amazon Music", "deezer" to "Deezer", "tidal" to "Tidal",
                "ask" to "Ask every time (Android's app picker)",
            ),
        )
        SwitchPref("Auto-press the camera shutter", K.autoShutter, "\"Take a photo\" opens the camera and presses the shutter after a short countdown.")
        SwitchPref("Screen automation tricks", K.uiAutomation, "Flip Wi-Fi/Bluetooth/airplane etc. by tapping Quick Settings and settings switches for you.")

        PrefSection("Places & units")
        TextPref("Home address", K.homeAddress, summary = "Used for \"navigate home\".")
        TextPref("Work address", K.workAddress, summary = "Used for \"navigate to work\".")
        ChoicePref("Temperature", K.tempUnit, listOf("fahrenheit" to "Fahrenheit (°F)", "celsius" to "Celsius (°C)"))
    }
}

// ---------------------------------------------------------------- Tools

@Composable
fun ToolsScreen() {
    val app = LocalContext.current.app
    var version by remember { mutableIntStateOf(0) }
    val groups = remember { app.tools.all.groupBy { it.category } }
    SettingsScroll {
        SettingHint("Turning a tool off hides it from the AI model and from quick commands. Fewer tools also make the on-device model a little faster.")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { app.settings[K.disabledTools] = ""; version++ }) { Text("Enable all") }
        }
        groups.forEach { (cat, list) ->
            PrefSection(cat)
            list.forEach { t ->
                var on by remember(t.name, version) { mutableStateOf(app.tools.isEnabled(t)) }
                PrefRow(
                    t.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    t.description + if (t.params.isNotBlank()) "  (${t.params})" else "",
                    onClick = { on = !on; app.settings.setToolEnabled(t.name, on) },
                ) {
                    Switch(checked = on, onCheckedChange = { on = it; app.settings.setToolEnabled(t.name, it) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Popup

@Composable
fun PopupScreen() {
    val app = LocalContext.current.app
    SettingsScroll {
        PrefSection("Popup")
        ChoicePref(
            "Popup style", K.popupMode,
            listOf(
                "auto" to "Automatic (overlay on top of any app, full screen popup on the lock screen)",
                "overlay" to "Always overlay",
                "activity" to "Always a separate screen",
            ),
        )
        IntSliderPref(
            "Auto-dismiss after", K.popupAutoDismissSec, 0..60, 1,
            summary = "Closes when you haven't touched it or spoken for this long (and right away when it was listening and heard nothing).",
            format = { if (it == 0) "Never" else "$it s" },
        )
        SwitchPref("Tap outside to dismiss", K.popupDismissOutside)
        SwitchPref("Show over the lock screen", K.popupShowWhenLocked, "Wakes the screen when you say the wake word with the phone locked.")
        SliderPref("Text size", K.popupTextScale, 0.8f..1.6f, format = { "${(it * 100).toInt()}%" })
        SwitchPref("Show the whole conversation", K.popupShowHistory, "Off = only the latest question and answer.")
        SwitchPref(
            "Accessibility button shortcut", K.a11yButton,
            "Puts Android's floating accessibility button on screen; tapping it opens Nova. Off keeps the screen clean — the accessibility service still works either way.",
        )
        ActionPref("Preview popup", "Opens the popup without listening.") { app.controller.activate("preview", listen = false) }
    }
}

// ---------------------------------------------------------------- Background

@Composable
fun BackgroundScreen() {
    val ctx = LocalContext.current
    val activity = ctx.findHostActivity()
    SettingsScroll {
        PrefSection("Always-on listening")
        SwitchPref("Start when the phone boots", K.startOnBoot)
        SwitchPref("Listen while the screen is off", K.listenScreenOff)
        SwitchPref("Only listen while charging", K.onlyWhenCharging)
        IntSliderPref(
            "Pause below battery level", K.pauseBatteryBelow, 0..50, 5,
            format = { if (it == 0) "Never" else "$it%" },
        )
        SwitchPref("Pause during phone calls", K.pauseDuringCalls)
        SwitchPref("Keep the CPU awake while listening", K.holdWakeLock, "More reliable with the screen off; uses a bit more battery.")
        SwitchPref("Minimize the persistent notification", K.notifMinimized, "Moves it to the silent section without a status-bar icon.")

        PrefSection("Keep Nova alive")
        ActionPref("Ignore battery optimization", "Most important for screen-off listening.") {
            activity?.openFirst(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        ActionPref("Open app info", "Battery → Unrestricted, OEM autostart, and \"Allow restricted settings\" (⋮ menu).") {
            activity?.openFirst(Setup.appDetails(ctx))
        }
        ActionPref("Restart the listener", "Stops and starts the background wake-word service.") {
            ListenerService.stop(ctx)
            ListenerService.start(ctx, "restart")
        }
        SettingHint(
            "Phone-maker tips:\n" +
                "• Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add Nova.\n" +
                "• Xiaomi / Redmi / POCO: App info → Autostart on, Battery saver → No restrictions; lock Nova in Recents.\n" +
                "• OnePlus / OPPO / Realme: App info → Battery → Allow background activity; enable Auto launch.\n" +
                "• Huawei: Battery → App launch → Nova → Manage manually (all on).\n" +
                "• Pixel / stock Android: battery optimization exemption is enough.\n" +
                "Setting Nova as the default digital assistant and enabling its accessibility service also keep it running.",
        )
    }
}

// ---------------------------------------------------------------- Memory

@Composable
fun MemoryScreen() {
    val app = LocalContext.current.app
    val facts by app.memory.facts.collectAsState()
    val notes by app.memory.notes.collectAsState()
    val history by app.memory.history.collectAsState()
    var newFact by remember { mutableStateOf("") }
    SettingsScroll {
        SwitchPref("Use memory", K.memoryEnabled, "Things you ask Nova to remember are given to the AI model in every conversation.")
        PrefSection("Personal index")
        SwitchPref(
            "Remember notifications for lookups", K.indexNotifications,
            "Keeps the last 30 days of notifications privately on this phone so you can ask about them later (like \"what's the address the dentist sent\").",
        )
        SwitchPref(
            "Give the AI relevant info automatically", K.contextLookup,
            "Searches your notifications, texts and calendar for each question and hands matches to the AI — faster answers, nothing leaves the phone.",
        )
        ActionPref("Clear notification index", "${app.index.size()} notifications stored") { app.index.clear() }
        PrefSection("Things Nova knows about you")
        if (facts.isEmpty()) SettingHint("Nothing yet. Say \"remember that my car is a blue Civic\".")
        facts.forEachIndexed { i, f ->
            DeletableRow(f) { app.memory.setFacts(facts.filterIndexed { j, _ -> j != i }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFact, onValueChange = { newFact = it },
                placeholder = { Text("Add a fact") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (newFact.isNotBlank()) { app.memory.addFact(newFact); newFact = "" } }) { Text("Add") }
        }

        PrefSection("Notes")
        if (notes.isEmpty()) SettingHint("Say \"take a note…\".")
        notes.reversed().forEach { n ->
            DeletableRow(n) { app.memory.setNotes(notes - n) }
        }

        PrefSection("Recent conversation")
        if (history.isEmpty()) SettingHint("No history yet.")
        else TextButton(onClick = { app.memory.clearHistory() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear history") }
        history.takeLast(50).reversed().forEach { (u, a) ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("You: $u", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun DeletableRow(text: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_close), "Delete", Modifier.size(18.dp))
        }
    }
}

// ---------------------------------------------------------------- Logs

@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Nova logs", lines.joinToString("\n")))
                Toast.makeText(ctx, "Logs copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(lines) { _, l ->
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- About

@Composable
fun AboutScreen() {
    val app = LocalContext.current.app
    SettingsScroll {
        PrefSection("Nova ${BuildConfig.VERSION_NAME}")
        SettingHint(
            "How it works:\n\n" +
                "• A tiny offline speech model (Vosk) listens for your wake word inside a foreground service.\n" +
                "• When it hears it, Nova captures your request with the speech recognizer you picked.\n" +
                "• Common commands (timers, alarms, calls, texts, flashlight, volume, media, navigation…) are handled instantly by built-in rules.\n" +
                "• Everything else goes to the AI model running on your phone (or your own server), which can call ~50 phone tools.\n" +
                "• The accessibility service lets Nova read and operate the screen, and shows the popup over any app.\n\n" +
                "Nothing you say leaves the phone unless you choose the Google recognizer, a remote server, or an internet tool like weather.",
        )
        PrefSection("Assistant")
        TextPref("Assistant name", K.assistantName)
        SettingHint("Tools available: ${app.tools.all.size}")
    }
}
