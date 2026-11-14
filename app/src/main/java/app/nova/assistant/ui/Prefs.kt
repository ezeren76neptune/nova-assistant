package app.nova.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.nova.assistant.app
import app.nova.assistant.core.BoolPref
import app.nova.assistant.core.FloatPref
import app.nova.assistant.core.IntPref
import app.nova.assistant.core.Pref
import app.nova.assistant.core.StrPref
import java.util.Locale
import kotlin.math.roundToInt

/** A MutableState bound to a setting: writes go straight to SharedPreferences, external changes flow back in. */
@Composable
fun <T> rememberPref(p: Pref<T>): MutableState<T> {
    val settings = LocalContext.current.app.settings
    val state = remember(p.key) { mutableStateOf(settings[p]) }
    LaunchedEffect(p.key) {
        settings.changes.collect { k ->
            if (k == p.key) {
                val v = settings[p]
                if (v != state.value) state.value = v
            }
        }
    }
    return remember(p.key) { PrefState(state) { settings[p] = it } }
}

private class PrefState<T>(private val s: MutableState<T>, private val write: (T) -> Unit) : MutableState<T> {
    override var value: T
        get() = s.value
        set(v) {
            s.value = v; write(v)
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

fun Context.findHostActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun fmtNum(v: Float, digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", v)

@Composable
fun PrefSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun PrefRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchPref(
    title: String,
    pref: BoolPref,
    summary: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit = {},
) {
    var v by rememberPref(pref)
    PrefRow(title, summary, enabled, onClick = { v = !v; onChange(v) }) {
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) }, enabled = enabled)
    }
}

@Composable
fun ActionPref(title: String, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    PrefRow(title, summary, enabled, onClick = onClick) { Text("›", style = MaterialTheme.typography.titleLarge) }
}

@Composable
fun TextPref(
    title: String,
    pref: StrPref,
    summary: String? = null,
    multiline: Boolean = false,
    password: Boolean = false,
    number: Boolean = false,
    placeholder: String = "",
    onSaved: (String) -> Unit = {},
) {
    var v by rememberPref(pref)
    var editing by remember { mutableStateOf(false) }
    val shown = when {
        v.isBlank() -> summary ?: "Not set"
        password -> "•".repeat(minOf(v.length, 12))
        else -> (if (summary != null) "$summary\n" else "") + v.take(160)
    }
    PrefRow(title, shown, onClick = { editing = true })
    if (editing) {
        var text by remember { mutableStateOf(v) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column {
                    if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = !multiline,
                        minLines = if (multiline) 4 else 1,
                        maxLines = if (multiline) 10 else 1,
                        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                password -> KeyboardType.Password
                                number -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    v = if (multiline) text.trim('\n', ' ') else text.trim()
                    editing = false
                    onSaved(v)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ChoicePref(
    title: String,
    pref: StrPref,
    options: List<Pair<String, String>>,
    summary: String? = null,
    onChange: (String) -> Unit = {},
) {
    var v by rememberPref(pref)
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == v }?.second ?: v.ifBlank { "Default" }
    PrefRow(title, if (summary != null) "$label\n$summary" else label, onClick = { open = true })
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (value, text) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { v = value; open = false; onChange(value) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == value, onClick = { v = value; open = false; onChange(value) })
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun SliderPref(
    title: String,
    pref: FloatPref,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    summary: String? = null,
    format: (Float) -> String = { fmtNum(it) },
) {
    var v by rememberPref(pref)
    var temp by remember { mutableFloatStateOf(v) }
    LaunchedEffect(v) { temp = v }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(temp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = temp,
            onValueChange = { temp = it },
            onValueChangeFinished = { v = temp },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
fun IntSliderPref(
    title: String,
    pref: IntPref,
    range: IntRange,
    step: Int = 1,
    summary: String? = null,
    format: (Int) -> String = { it.toString() },
) {
    var v by rememberPref(pref)
    var temp by remember { mutableFloatStateOf(v.toFloat()) }
    LaunchedEffect(v) { temp = v.toFloat() }
    val snapped = { f: Float -> (((f - range.first) / step).roundToInt() * step + range.first).coerceIn(range.first, range.last) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(snapped(temp)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val stepCount = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = temp,
            onValueChange = { temp = it },
            onValueChangeFinished = { v = snapped(temp); temp = v.toFloat() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (stepCount > 200) 0 else stepCount,
        )
    }
}
