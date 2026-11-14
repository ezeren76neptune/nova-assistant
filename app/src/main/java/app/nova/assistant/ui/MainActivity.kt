package app.nova.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nova.assistant.R
import app.nova.assistant.app
import app.nova.assistant.core.K
import app.nova.assistant.llm.LlmManager
import app.nova.assistant.service.ListenerService
import app.nova.assistant.speech.VoskModelManager
import app.nova.assistant.speech.WakeWordEngine
import app.nova.assistant.ui.theme.NovaTheme
import java.text.DateFormat
import java.util.Date

enum class Screen(val title: String, val subtitle: String) {
    HOME("Nova", ""),
    WAKE("Wake word", "Phrase, sensitivity, offline model, live test"),
    SPEECH("Speech input", "Recognizer, language, follow-up listening"),
    VOICE("Voice output", "Text-to-speech voice, speed, pitch"),
    MODEL("AI model", "On-device model, downloads, server, generation"),
    ACTIONS("Actions & apps", "Confirmations, SMS, timers, search, music, contacts"),
    HOME_ASSISTANT("Home Assistant", "Smart home: sign in, your widgets, devices"),
    TOOLS("Tools", "Turn individual abilities on or off"),
    POPUP("Popup", "Overlay style, auto-dismiss, lock screen"),
    BACKGROUND("Background & battery", "Boot, screen-off listening, battery rules"),
    MEMORY("Memory", "What Nova remembers about you, notes, history"),
    LOGS("Logs", "Diagnostics"),
    ABOUT("About", "Version and how it works"),
}

class MainActivity : ComponentActivity() {
    private val requestedScreen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val nova = this.app
        if (nova.settings[K.wakeEnabled] &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            ListenerService.start(this, "app open")
        }
        if (nova.vosk.state.value is VoskModelManager.State.Missing) nova.vosk.download()
        setContent { NovaTheme { NovaRoot(requestedScreen) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_SCREEN)?.let { requestedScreen.value = it }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaRoot(requested: MutableState<String?>) {
    val ctx = LocalContext.current
    val app = ctx.app
    var stackStr by rememberSaveable { mutableStateOf("HOME") }
    val stack = stackStr.split(',').mapNotNull { n -> Screen.entries.firstOrNull { it.name == n } }.ifEmpty { listOf(Screen.HOME) }
    val current = stack.last()
    val push = { s: Screen -> if (current != s) stackStr = (stack + s).joinToString(",") { it.name } }
    val pop = { if (stack.size > 1) stackStr = stack.dropLast(1).joinToString(",") { it.name } }

    LaunchedEffect(requested.value) {
        requested.value?.let { name -> Screen.entries.firstOrNull { it.name == name }?.let(push) }
        requested.value = null
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    var firstRun by rememberPref(K.firstRunDone)
    val name by rememberPref(K.assistantName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == Screen.HOME) name else current.title) },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = pop) { Text("‹", fontSize = 32.sp) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (current) {
                Screen.HOME -> HomeScreen(push)
                Screen.WAKE -> WakeScreen()
                Screen.SPEECH -> SpeechScreen()
                Screen.VOICE -> VoiceScreen()
                Screen.MODEL -> ModelScreen()
                Screen.ACTIONS -> ActionsScreen()
                Screen.HOME_ASSISTANT -> HomeAssistantScreen()
                Screen.TOOLS -> ToolsScreen()
                Screen.POPUP -> PopupScreen()
                Screen.BACKGROUND -> BackgroundScreen()
                Screen.MEMORY -> MemoryScreen()
                Screen.LOGS -> LogsScreen()
                Screen.ABOUT -> AboutScreen()
            }
        }
    }

    if (!firstRun) {
        AlertDialog(
            onDismissRequest = { firstRun = true },
            title = { Text("Welcome to ${app.settings[K.assistantName]}") },
            text = {
                Text(
                    "Nova is a voice assistant that runs on your phone.\n\n" +
                        "1. Grant the permissions in the Setup checklist (microphone first).\n" +
                        "2. The offline wake-word model is downloading now (~40 MB).\n" +
                        "3. Pick an AI model under AI model — the recommended one downloads straight to the phone.\n" +
                        "4. Turn on the accessibility service and set Nova as your default assistant for the best reliability.\n\n" +
                        "Then just say \"${app.settings[K.wakePhrase]}\".",
                )
            },
            confirmButton = { TextButton(onClick = { firstRun = true }) { Text("Get started") } },
        )
    }
}

@Composable
fun HomeScreen(push: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val activity = ctx.findHostActivity()
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    var wakeOn by rememberPref(K.wakeEnabled)
    val phrase by rememberPref(K.wakePhrase)
    val wakeStatus by app.wake.status.collectAsState()
    val running by ListenerService.running.collectAsState()
    val pausedUntil by ListenerService.pausedUntil.collectAsState()
    val silent by app.wake.silentInput.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        if (res[Manifest.permission.RECORD_AUDIO] == true && wakeOn) ListenerService.start(ctx, "ui")
    }
    val allLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (wakeOn) ListenerService.start(ctx, "ui")
    }

    fun setWake(on: Boolean) {
        wakeOn = on
        if (on) {
            val need = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) ListenerService.start(ctx, "ui") else micLauncher.launch(need.toTypedArray())
        } else {
            ListenerService.stop(ctx)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // --- status card ---
        ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_nova), null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Wake word", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val now = System.currentTimeMillis()
                        val status = when {
                            !wakeOn -> "Off — use the buttons below or the notification"
                            pausedUntil > now -> if (pausedUntil == Long.MAX_VALUE) "Paused until you resume"
                            else "Paused until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))
                            !running -> "Not running — toggle off/on to start"
                            else -> when (wakeStatus) {
                                WakeWordEngine.Status.LISTENING -> "Listening for “$phrase”"
                                WakeWordEngine.Status.NO_MODEL -> "Waiting for the wake-word model"
                                WakeWordEngine.Status.NO_PERMISSION -> "Microphone permission needed"
                                WakeWordEngine.Status.PAUSED -> "Paused (assistant busy or a battery rule)"
                                WakeWordEngine.Status.ERROR -> "Microphone error — see Logs"
                                WakeWordEngine.Status.STOPPED -> "Starting…"
                            }
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = wakeOn, onCheckedChange = { setWake(it) })
                }
                if (silent && wakeOn) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android is giving Nova silence instead of microphone audio. Turn on the accessibility service, set Nova as default assistant, and exempt it from battery optimization.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.controller.activate("app") }) {
                        Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ask now")
                    }
                    OutlinedButton(onClick = { app.controller.activate("app", listen = false) }) { Text("Type a command") }
                }
                if (pausedUntil > System.currentTimeMillis() && wakeOn) {
                    TextButton(onClick = { ListenerService.resume(ctx) }) { Text("Resume listening") }
                }
            }
        }

        // --- setup checklist ---
        val items = remember(tick) { Setup.items(ctx) }
        var showAll by remember { mutableStateOf(false) }
        val done = items.count { it.ok }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("$done of ${items.size} done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                val missing = Setup.missingRuntime(ctx)
                if (missing.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { allLauncher.launch(missing.toTypedArray()) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text("Grant all permissions (${missing.size})") }
                }
                val shown = if (showAll) items else items.filter { !it.ok }.sortedByDescending { it.important }
                if (shown.isEmpty()) {
                    Text("Everything is set up. 🎉", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                shown.forEach { item -> SetupRow(item) { activity?.let { item.fix(it) }; tick++ } }
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showAll) "Show only what's missing" else "Show all items")
                }
            }
        }

        // --- models ---
        Spacer(Modifier.height(12.dp))
        WakeModelCard()
        Spacer(Modifier.height(12.dp))
        AiModelCard { push(Screen.MODEL) }

        // --- sections ---
        PrefSection("Settings")
        Screen.entries.filter { it != Screen.HOME }.forEach { s ->
            PrefRow(s.title, s.subtitle, onClick = { push(s) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SetupRow(item: Setup.Item, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (item.ok) R.drawable.ic_check else R.drawable.ic_warning), null,
            tint = when {
                item.ok -> MaterialTheme.colorScheme.primary
                item.important -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title + if (item.important && !item.ok) "  •  recommended" else "", style = MaterialTheme.typography.bodyLarge)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!item.ok) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onFix) { Text("Fix") }
        }
    }
}

@Composable
fun WakeModelCard() {
    val app = LocalContext.current.app
    val st by app.vosk.state.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Wake-word model (offline)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            when (val s = st) {
                VoskModelManager.State.Missing -> {
                    Text("Not downloaded yet (~40 MB).", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { app.vosk.download() }, modifier = Modifier.padding(top = 8.dp)) { Text("Download") }
                }
                is VoskModelManager.State.Downloading -> {
                    Text("Downloading… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                VoskModelManager.State.Unpacking -> {
                    Text("Unpacking…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                VoskModelManager.State.Ready -> Text("Ready ✓", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                is VoskModelManager.State.Failed -> {
                    Text("Failed: ${s.msg}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { app.vosk.download() }, modifier = Modifier.padding(top = 8.dp)) { Text("Retry") }
                }
            }
        }
    }
}

fun llmStatusText(s: LlmManager.Status): String = when (s) {
    is LlmManager.Status.Ready -> "Loaded — ${s.info}"
    is LlmManager.Status.Failed -> "Error: ${s.msg}"
    is LlmManager.Status.Loading -> "Loading into memory…"
    is LlmManager.Status.Idle -> "Configured (loads on first use)"
    else -> "Not set up"
}

@Composable
fun AiModelCard(onManage: () -> Unit) {
    val app = LocalContext.current.app
    val st by app.llm.status.collectAsState()
    val backend by rememberPref(K.llmBackend)
    val path by rememberPref(K.modelPath)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("AI model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val configured = remember(backend, path, st) { app.llm.isConfigured() }
            Text(
                if (configured) app.llm.describe() else "No model chosen — Nova can still do quick commands (timers, calls, texts…).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(llmStatusText(st), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (st is LlmManager.Status.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(onClick = onManage, modifier = Modifier.padding(top = 8.dp)) { Text(if (configured) "Manage" else "Choose a model") }
        }
    }
}
