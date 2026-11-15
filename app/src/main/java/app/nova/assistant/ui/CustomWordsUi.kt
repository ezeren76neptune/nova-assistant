package app.nova.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nova.assistant.NovaApp
import app.nova.assistant.app
import app.nova.assistant.core.CustomWord
import app.nova.assistant.speech.Recognizers
import app.nova.assistant.speech.SttError
import app.nova.assistant.speech.SttListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomWordsScreen() {
    val nova = LocalContext.current.app
    val cw = nova.customWords
    val words by cw.words.collectAsState()
    var newWord by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    var teaching by remember { mutableStateOf<CustomWord?>(null) }
    var test by remember { mutableStateOf("") }

    SettingsScroll {
        PrefSection("Teach a word")
        Text(
            "Names, acronyms or words Nova mishears (MSOE, Claude…). Type it, then say it a few times: Nova learns how " +
                "speech recognition mishears it and fixes that whenever you talk. The words are also sent to the " +
                "recognizer as hints and to the AI model.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = newWord, onValueChange = { newWord = it }, singleLine = true,
            label = { Text("Word, exactly as it should be written") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        OutlinedTextField(
            value = newNote, onValueChange = { newNote = it }, singleLine = true,
            label = { Text("What it is (optional, helps the AI)") },
            placeholder = { Text("e.g. my university, Milwaukee School of Engineering") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = newWord.isNotBlank(), onClick = {
                teaching = CustomWord(newWord.trim(), newNote.trim(), words.firstOrNull { it.word.equals(newWord.trim(), true) }?.aliases.orEmpty())
            }) { Text("Teach by voice") }
            OutlinedButton(enabled = newWord.isNotBlank(), onClick = {
                cw.upsert(CustomWord(newWord.trim(), newNote.trim()))
                newWord = ""; newNote = ""
            }) { Text("Add without recording") }
        }

        PrefSection("Your words (${words.size})")
        if (words.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        words.sortedBy { it.word.lowercase() }.forEach { w ->
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.word, style = MaterialTheme.typography.titleMedium)
                            if (w.note.isNotBlank()) Text(w.note, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { teaching = w }) { Text("Teach again") }
                        TextButton(onClick = { cw.remove(w.word) }) { Text("Delete") }
                    }
                    val shown = w.aliases + cw.builtInAliases(w.word).filter { it !in w.aliases }
                    Text(
                        if (shown.isEmpty()) "No corrections learned yet — tap Teach again and say it a few times."
                        else "Fixes when you say it and it's heard as:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        shown.forEach { a ->
                            val builtIn = a !in w.aliases
                            InputChip(
                                selected = false,
                                onClick = { if (!builtIn) cw.removeAlias(w.word, a) },
                                label = { Text(if (builtIn) a else "$a  ✕") },
                            )
                        }
                    }
                    var extra by remember(w.word) { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = extra, onValueChange = { extra = it }, singleLine = true,
                            label = { Text("Also fix (what it's heard as)") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = extra.isNotBlank(), onClick = {
                            cw.upsert(w.copy(aliases = w.aliases + extra)); extra = ""
                        }) { Text("Add") }
                    }
                }
            }
        }

        PrefSection("Try it")
        OutlinedTextField(
            value = test, onValueChange = { test = it },
            label = { Text("Type what speech recognition heard") },
            placeholder = { Text("e.g. i go to m s o e and use clawed") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (test.isNotBlank()) Text(
            "Nova understands: " + cw.correct(test),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }

    teaching?.let { w ->
        TeachDialog(w, onClose = { saved -> teaching = null; if (saved) { newWord = ""; newNote = "" } })
    }
}

/** Listens once with the same recognizer Nova uses for commands; returns every candidate transcript. */
private suspend fun listenOnce(app: NovaApp, onPartial: (String) -> Unit): List<String> = suspendCancellableCoroutine { c ->
    val rec = Recognizers.create(app)
    var alts: List<String> = emptyList()
    rec.start(object : SttListener {
        override fun onPartial(text: String) = onPartial(text)
        override fun onLevel(level: Float) {}
        override fun onAlternatives(all: List<String>) { alts = all }
        override fun onFinal(text: String) { if (c.isActive) c.resume(alts.ifEmpty { listOf(text) }.filter { it.isNotBlank() }) }
        override fun onError(kind: SttError, detail: String) { if (c.isActive) c.resume(emptyList()) }
    })
    c.invokeOnCancellation { rec.cancel() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeachDialog(w: CustomWord, onClose: (Boolean) -> Unit) {
    val nova = LocalContext.current.app
    val cw = nova.customWords
    val scope = rememberCoroutineScope()
    val total = 3
    val attempts = remember { mutableStateListOf<List<String>>() }
    val learned = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf("Tap Start, then say “${w.word}” each time you're asked, the way you normally would.") }
    var live by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf(false) }

    fun close(save: Boolean) {
        job?.cancel()
        nova.wake.release("teach")
        if (save) cw.upsert(w.copy(aliases = (w.aliases + learned).distinct()))
        onClose(save)
    }

    fun start() {
        job = scope.launch {
            withContext(Dispatchers.IO) { nova.wake.hold("teach", wait = true) }
            nova.speaker.stop()
            attempts.clear(); learned.clear()
            while (attempts.size < total) {
                status = "Say “${w.word}” (${attempts.size + 1} of $total)…"
                live = ""
                level = 0.6f
                val alts = listenOnce(nova) { live = it }
                level = 0f
                if (alts.isEmpty()) { status = "Didn't catch that — try again."; delay(900); continue }
                attempts += alts
                delay(500)
            }
            learned.addAll(cw.learnedAliases(w.word, attempts.toList()))
            status = if (learned.isEmpty()) "It already heard “${w.word}” correctly — nothing to fix. Save to keep it as a hint."
            else "Learned ${learned.size} correction${if (learned.size == 1) "" else "s"}. Tap one to drop it, then Save."
            done = true
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Teach “${w.word}”") },
        text = {
            Column {
                Text(status, style = MaterialTheme.typography.bodyLarge)
                if (live.isNotBlank() && !done) Text("Hearing: $live", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (done) 1f else attempts.size / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                attempts.forEachIndexed { i, a ->
                    Text("${i + 1}. heard “${a.first()}”" + if (a.size > 1) "  (also: ${a.drop(1).take(3).joinToString(", ")})" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (done && learned.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        learned.toList().forEach { a ->
                            InputChip(selected = true, onClick = { learned.remove(a) }, label = { Text("$a → ${w.word}  ✕") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = { close(true) }) { Text("Save") }
            else TextButton(enabled = job == null, onClick = { start() }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = { close(false) }) { Text("Cancel") } },
    )
}
