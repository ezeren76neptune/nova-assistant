package app.nova.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nova.assistant.app
import app.nova.assistant.core.K
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings → Wake word → Voice ID: enroll, strictness, live score. */
@Composable
fun VoiceIdSection() {
    val nova = LocalContext.current.app
    val vid = nova.voiceId
    val modelState by vid.modelState.collectAsState()
    val score by vid.lastScore.collectAsState()
    val samples by rememberPref(K.voiceIdSamples)
    var enabled by rememberPref(K.voiceIdEnabled)
    val threshold by rememberPref(K.voiceIdThreshold)
    var enrolling by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    PrefSection("Voice ID")
    Text(
        "Only wake up for your voice. Record yourself saying the wake word a few times; Nova keeps a voiceprint " +
            "on the phone and ignores other people. Runs offline (13 MB model). It's a filter, not a lock — " +
            "doors and the garage still ask to confirm.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    PrefRow(
        "Only wake for my voice",
        when {
            samples == 0 -> "Record your voice first"
            !vid.isInstalled() -> "Speaker model: $modelState"
            else -> "Voiceprint from $samples samples"
        },
        onClick = { if (samples == 0) enrolling = true else enabled = !enabled },
        trailing = {
            androidx.compose.material3.Switch(checked = enabled && samples > 0, onCheckedChange = { on ->
                if (on && samples == 0) enrolling = true else enabled = on
            })
        },
    )
    ActionPref(if (samples > 0) "Re-record my voice" else "Record my voice", "Say the wake word 5 times in a normal voice") { enrolling = true }
    if (samples > 0) {
        SliderPref(
            "Strictness", K.voiceIdThreshold, 0.2f..0.9f,
            summary = "Higher = fewer false wakes from other people, but may miss you. Set automatically when you record.",
            format = { "${(it * 100).toInt()}%" },
        )
        Text(
            score?.let { "Last wake-word check: ${(it * 100).toInt()}% match (needs ${(threshold * 100).toInt()}%) — ${if (it >= threshold) "accepted" else "rejected"}" }
                ?: "Say the wake word to see how well it matches.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        ActionPref("Forget my voice") { vid.clear(); enabled = false; msg = "Voiceprint deleted." }
    }
    if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    if (enrolling) EnrollDialog(onClose = { result ->
        enrolling = false
        if (result != null) { msg = result; if (nova.voiceId.isEnrolled()) enabled = true }
    })
}

@Composable
private fun EnrollDialog(onClose: (String?) -> Unit) {
    val nova = LocalContext.current.app
    val vid = nova.voiceId
    val phrase = nova.settings[K.wakePhrase]
    val scope = rememberCoroutineScope()
    val clips = remember { mutableStateListOf<ShortArray>() }
    var level by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Find a quiet spot, then tap Start.") }
    var job by remember { mutableStateOf<Job?>(null) }
    val total = 5

    fun finish(result: String?) {
        job?.cancel()
        nova.wake.release("enroll")
        onClose(result)
    }

    fun start() {
        job = scope.launch {
            withContext(Dispatchers.IO) { nova.wake.hold("enroll", wait = true) }
            if (!vid.isInstalled()) {
                vid.download()
                while (!vid.isInstalled()) {
                    status = "Getting the speaker model… ${vid.modelState.value}"
                    if (vid.modelState.value.startsWith("Download failed")) { status = vid.modelState.value; return@launch }
                    delay(400)
                }
            }
            if (nova.vosk.model() == null) { status = "The wake-word model isn't downloaded yet."; return@launch }
            clips.clear()
            while (clips.size < total) {
                status = "Say “$phrase” now (${clips.size + 1} of $total)"
                val clip = vid.recordUtterance(level = { level = it })
                level = 0f
                if (clip == null) { status = "Didn't hear you — say “$phrase” (${clips.size + 1} of $total)"; delay(900); continue }
                clips += clip
                status = "Got it."
                delay(500)
            }
            status = "Saving your voiceprint…"
            val result = withContext(Dispatchers.Default) { vid.enroll(clips.toList()) }
            finish(result)
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Record your voice") },
        text = {
            Column {
                Text(status, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { clips.size / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${clips.size} / $total samples", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = job == null, onClick = { start() }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = { finish(null) }) { Text("Cancel") } },
    )
}
