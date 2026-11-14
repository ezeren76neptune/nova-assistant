package app.nova.assistant.ui

import android.app.DownloadManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.llm.CatalogModel
import app.nova.assistant.llm.DownloadProgress
import app.nova.assistant.llm.LlmManager
import app.nova.assistant.llm.ModelCatalog
import app.nova.assistant.llm.ModelDownloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private fun DownloadProgress?.active() = this != null && status in setOf(
    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
)

private fun mb(bytes: Long) = "${bytes / 1_000_000} MB"

@Composable
fun ModelScreen() {
    val ctx = LocalContext.current
    val app = ctx.app
    val status by app.llm.status.collectAsState()
    var backend by rememberPref(K.llmBackend)
    var path by rememberPref(K.modelPath)
    val hfToken by rememberPref(K.hfToken)

    // Poll download progress once a second while this screen is visible.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); tick++ }
    }

    var testing by remember { mutableStateOf(false) }
    var testOut by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var urlDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    val customDownloads = remember { mutableStateOf(listOf<String>()) }

    fun use(file: File) {
        path = file.absolutePath
        backend = ModelCatalog.engineForPath(file.absolutePath)
        app.llm.unload()
        Toast.makeText(ctx, "Using ${file.name}", Toast.LENGTH_SHORT).show()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importProgress = 0f
            app.appScope.launch {
                try {
                    val f = ModelDownloads.importFromUri(ctx, uri) { p -> importProgress = p }
                    use(f)
                } catch (t: Throwable) {
                    AppLog.d("model", "import failed", t)
                    Toast.makeText(ctx, "Import failed: ${t.message}", Toast.LENGTH_LONG).show()
                } finally {
                    importProgress = null
                }
            }
        }
    }

    SettingsScroll {
        // ---------------- current model
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Current model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val configured = remember(backend, path, status) { app.llm.isConfigured() }
                Text(
                    when {
                        backend == "none" -> "None — quick commands only"
                        backend == "remote" -> "Server: ${app.settings[K.remoteModel]} @ ${app.settings[K.remoteUrl]}"
                        path.isBlank() -> "No model file chosen yet — download one below."
                        else -> app.llm.describe() + "\n" + path
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (backend != "none" && backend != "remote" && path.isNotBlank() && !File(path).exists()) {
                    Text("The model file is missing.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(llmStatusText(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status is LlmManager.Status.Loading || testing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = configured, onClick = {
                        app.appScope.launch {
                            runCatching { app.llm.ensureLoaded() }.onFailure {
                                Toast.makeText(ctx, "Load failed: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Load now") }
                    OutlinedButton(onClick = { app.llm.unload() }) { Text("Unload") }
                    FilledTonalButton(enabled = configured && !testing, onClick = {
                        testing = true; testOut = null
                        app.appScope.launch {
                            val t0 = System.currentTimeMillis()
                            testOut = runCatching { app.llm.test("Say hello in five words.") }.fold(
                                { "$it\n\n(${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)} s incl. loading)" },
                                { "Error: ${it.message ?: it.javaClass.simpleName}" },
                            )
                            testing = false
                        }
                    }) { Text("Test") }
                }
                testOut?.let {
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        PrefSection("Engine")
        ChoicePref(
            "Where the AI runs", K.llmBackend,
            listOf(
                "litertlm" to "On this phone — LiteRT-LM (.litertlm files, recommended)",
                "mediapipe" to "On this phone — MediaPipe (.task files)",
                "remote" to "Server on my network (OpenAI-compatible: Ollama, LM Studio, llama.cpp)",
                "none" to "None — quick commands only",
            ),
            onChange = { app.llm.unload() },
        )

        // ---------------- catalog
        PrefSection("Download a model")
        SettingHint(
            "Bigger models understand more but need more RAM and load slower. 2–3 GB models want a phone with 8 GB+ RAM. " +
                "Downloads continue in the background (see the notification).",
        )
        ModelCatalog.models.forEach { m ->
            CatalogCard(m, tick, path, hfToken, onUse = ::use, onDelete = { confirmDelete = it })
        }

        // ---------------- files on the phone
        val catalogFiles = remember { ModelCatalog.models.map { it.file }.toSet() }
        val others = remember(tick) {
            ModelCatalog.modelsDir(ctx).listFiles()
                ?.filter { it.isFile && it.name !in catalogFiles && (it.name.endsWith(".litertlm") || it.name.endsWith(".task")) }
                .orEmpty()
        }
        PrefSection("Your own model files")
        importProgress?.let {
            SettingHint("Importing… ${(it * 100).toInt()}%")
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import model file…") }
            OutlinedButton(onClick = { urlDialog = true }) { Text("Download from URL…") }
        }
        customDownloads.value.forEach { name ->
            val p = remember(tick) { ModelDownloads.progress(ctx, name) }
            if (p.active()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Downloading $name — ${mb(p!!.done)}${if (p.total > 0) " / ${mb(p.total)}" else ""}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { ModelDownloads.cancel(ctx, name) }) { Text("Cancel") }
                }
            }
        }
        if (others.isEmpty()) SettingHint("Imported or custom-downloaded .litertlm / .task files show up here.")
        others.forEach { f ->
            val p = remember(tick) { ModelDownloads.progress(ctx, f.name) }
            if (!p.active()) {
                PrefRow(f.name, "${mb(f.length())}${if (f.absolutePath == path) " · in use" else ""}") {
                    Row {
                        if (f.absolutePath != path) TextButton(onClick = { use(f) }) { Text("Use") }
                        TextButton(onClick = { confirmDelete = f }) { Text("Delete") }
                    }
                }
            }
        }
        TextPref(
            "Hugging Face token", K.hfToken, password = true,
            summary = "Only for gated models (Gemma 3 etc.): create a read token at huggingface.co/settings/tokens and accept the model's license on its page.",
        )

        // ---------------- remote
        PrefSection("Server (optional)")
        TextPref("Server URL", K.remoteUrl, summary = "OpenAI-compatible base URL, e.g. http://192.168.1.10:11434/v1 for Ollama.")
        TextPref("Model name", K.remoteModel, summary = "e.g. qwen3:4b, llama3.2")
        TextPref("API key", K.remoteKey, password = true, summary = "Leave blank for Ollama / LM Studio.")

        // ---------------- generation
        PrefSection("Generation")
        SwitchPref("Use the GPU", K.llmGpu, "Much faster on most phones; falls back to CPU automatically.", onChange = { app.llm.unload() })
        IntSliderPref("Context size", K.llmContext, 1024..8192, 512, summary = "Tokens of conversation the model can see. Bigger = more RAM.", format = { "$it tokens" })
        IntSliderPref("Max reply length", K.llmMaxOutput, 64..1024, 32, format = { "$it tokens" })
        SliderPref("Temperature", K.llmTemperature, 0f..1.5f, summary = "Lower = more predictable tool use.")
        IntSliderPref("Top-K", K.llmTopK, 1..100, 1)
        SliderPref("Top-P", K.llmTopP, 0.1f..1f)
        SwitchPref("Thinking mode", K.llmThinking, "Let reasoning models think before answering (slower).")
        SwitchPref("Constrained JSON output", K.llmConstrained, "Forces valid JSON from the model. Helps tiny models; not supported by every model.")
        ChoicePref(
            "Prompt format (MediaPipe)", K.promptFormat,
            listOf(
                "auto" to "Automatic from file name",
                "gemma" to "Gemma",
                "chatml" to "ChatML (Qwen, SmolLM, LFM)",
                "llama3" to "Llama 3",
                "phi" to "Phi",
                "raw" to "Plain text",
            ),
        )
        SwitchPref("Load the model when the wake word is heard", K.preloadOnWake, "Warms the model up while you're still talking.")
        SwitchPref("Load the model when Nova starts", K.preloadOnStart, "Fastest replies, but keeps RAM busy.")
        IntSliderPref("Unload after idle", K.unloadMinutes, 0..120, 5, format = { if (it == 0) "Never" else "$it min" })

        // ---------------- behaviour
        PrefSection("Personality & behaviour")
        TextPref("Assistant name", K.assistantName)
        TextPref("Your name", K.userName, summary = "So the assistant can address you.")
        TextPref("Personality", K.personality, summary = "Describe how the assistant should talk.")
        TextPref("Custom instructions", K.customInstructions, multiline = true, summary = "Extra rules added to every conversation.")
        IntSliderPref("Max tool steps per request", K.maxToolSteps, 1..8, 1)
        SwitchPref("Let the model explain tool results", K.summarizeResults, "E.g. summarize your notifications or calendar instead of reading them raw.")
        SwitchPref("Quick commands", K.quickCommands, "Handle common requests instantly without the model.")
        SwitchPref("Show tool calls in the popup", K.showToolCalls)
        SwitchPref("Allow internet tools", K.internetTools, "Weather and other lookups that need the network.")
        SwitchPref("Use memory", K.memoryEnabled)
        IntSliderPref(
            "Keep the chat after closing for", K.conversationMinutes, 0..60, 1,
            summary = "Off (default): every new ask and every time the popup closes starts a new chat. Follow-ups inside the open popup always continue it.",
            format = { if (it == 0) "Off — new chat every time" else "$it min" },
        )
    }

    if (urlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("Download a model") },
            text = {
                Column {
                    Text("Direct link to a .litertlm or .task file.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = url.trim()
                    val name = Uri.parse(u).lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "model.litertlm"
                    if (u.startsWith("http")) {
                        runCatching { ModelDownloads.start(ctx, u, name, hfToken.ifBlank { null }) }
                            .onSuccess { customDownloads.value = customDownloads.value + name }
                            .onFailure { Toast.makeText(ctx, "Download failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                    urlDialog = false
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("Frees ${mb(f.length())}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    if (f.absolutePath == path) { app.llm.unload(); path = "" }
                    ModelDownloads.cancel(ctx, f.name)
                    f.delete()
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogCard(
    m: CatalogModel,
    tick: Int,
    currentPath: String,
    hfToken: String,
    onUse: (File) -> Unit,
    onDelete: (File) -> Unit,
) {
    val ctx = LocalContext.current
    val file = remember(m.id) { ModelCatalog.fileFor(ctx, m) }
    val progress = remember(tick, m.id) { ModelDownloads.progress(ctx, m.file) }
    val exists = remember(tick, m.id) { file.exists() && file.length() > 0 }
    val inUse = file.absolutePath == currentPath
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = if (inUse) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (m.gated) {
                    Text("needs HF token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (m.sizeMb >= 1000) "${"%.1f".format(m.sizeMb / 1000.0)} GB" else "${m.sizeMb} MB", style = MaterialTheme.typography.labelMedium)
            }
            Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (m.engine == "mediapipe") "MediaPipe (.task)" else "LiteRT-LM (.litertlm)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            when {
                progress.active() -> {
                    val p = progress!!
                    val frac = if (p.total > 0) p.done.toFloat() / p.total else 0f
                    Text(
                        (if (p.status == DownloadManager.STATUS_PAUSED) "Paused (waiting for network)… " else "Downloading… ") +
                            "${mb(p.done)}${if (p.total > 0) " / ${mb(p.total)}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    TextButton(onClick = { ModelDownloads.cancel(ctx, m.file) }) { Text("Cancel") }
                }
                exists -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (inUse) Text("In use ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { onUse(file) }) { Text("Use") }
                    TextButton(onClick = { onDelete(file) }) { Text("Delete") }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress?.status == DownloadManager.STATUS_FAILED) {
                        Text("Download failed. ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    FilledTonalButton(onClick = {
                        if (m.gated && hfToken.isBlank()) {
                            Toast.makeText(ctx, "Add your Hugging Face token below first, and accept the license on the model's page.", Toast.LENGTH_LONG).show()
                        } else {
                            runCatching { ModelDownloads.start(ctx, m.url, m.file, hfToken.ifBlank { null }) }
                                .onFailure { Toast.makeText(ctx, "Download failed: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text("Download") }
                }
            }
        }
    }
}
