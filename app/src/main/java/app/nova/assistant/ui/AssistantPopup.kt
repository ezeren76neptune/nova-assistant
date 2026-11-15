package app.nova.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nova.assistant.R
import app.nova.assistant.app
import app.nova.assistant.core.K
import app.nova.assistant.core.Phase
import app.nova.assistant.core.UiState
import app.nova.assistant.core.Who
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The assistant bottom sheet: what you said, the reply, confirmation buttons and a box to keep chatting.
 * Shared by the overlay window and [AssistActivity].
 */
@Composable
fun AssistantPopup(state: UiState, modifier: Modifier = Modifier, onRequestTyping: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.app
    val ctl = app.controller
    val scale = app.settings[K.popupTextScale].coerceIn(0.7f, 1.8f)
    val name = app.settings[K.assistantName].ifBlank { "Nova" }
    val showHistory = app.settings[K.popupShowHistory]
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxH = (config.screenHeightDp * 0.6f).dp
    val dismissPx = with(density) { 110.dp.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragY.roundToInt()) }
            .graphicsLayer { alpha = (1f - dragY / (dismissPx * 3f)).coerceIn(0.3f, 1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    ctl.userInteracted()
                }
            },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .heightIn(max = maxH)
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
        ) {
            // Drag handle + header: swipe down to dismiss.
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> dragY = (dragY + d).coerceAtLeast(0f) },
                        onDragStopped = { velocity ->
                            if (dragY > dismissPx || velocity > 2500f) {
                                ctl.dismiss("swiped down")
                            } else {
                                animate(dragY, 0f) { v, _ -> dragY = v }
                            }
                        },
                    ),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Orb(state.phase, state.level, Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        statusText(state, name),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp * scale),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = { ctl.dismiss("close button") }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Transcript(state, showHistory, scale, Modifier.weight(1f, fill = false))

            state.pending?.let { p ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = { ctl.confirm(false) }) { Text("Cancel") }
                    Button(onClick = { ctl.confirm(true) }) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(p.confirmLabel.ifBlank { "Confirm" })
                    }
                }
            }

            InputRow(state, name, scale, onRequestTyping)
        }
    }
}

private fun statusText(state: UiState, name: String): String = state.status.ifBlank {
    when (state.phase) {
        Phase.LISTENING -> "Listening…"
        Phase.THINKING -> "Thinking…"
        else -> name
    }
}

@Composable
private fun Transcript(state: UiState, showHistory: Boolean, scale: Float, modifier: Modifier) {
    val turns = if (showHistory) state.turns else {
        val i = state.turns.indexOfLast { it.who == Who.USER }
        if (i >= 0) state.turns.subList(i, state.turns.size) else state.turns
    }
    val showPartial = state.phase == Phase.LISTENING && state.partial.isNotBlank()
    val showStream = state.streaming.isNotBlank()
    val showDots = state.phase == Phase.THINKING && !showStream
    val showHint = turns.isEmpty() && !showPartial && !showStream && !showDots
    val count = turns.size + (if (showPartial) 1 else 0) + (if (showStream) 1 else 0) + (if (showDots) 1 else 0) + (if (showHint) 1 else 0)
    val listState = rememberLazyListState()
    LaunchedEffect(count, state.streaming.length, state.partial.length) {
        if (count > 0) listState.scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(turns, key = { it.id }) { Bubble(it.who, it.text, scale) }
        if (showPartial) item(key = "partial") { Bubble(Who.USER, state.partial, scale, faded = true) }
        if (showStream) item(key = "stream") { Bubble(Who.ASSISTANT, state.streaming, scale) }
        if (showDots) item(key = "dots") { TypingDots() }
        if (showHint && state.phase != Phase.LISTENING) item(key = "hint") {
            Text(
                "Type or tap the mic to talk",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 14.sp * scale,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

@Composable
private fun Bubble(who: Who, text: String, scale: Float, faded: Boolean = false) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (who) {
        Who.USER -> cs.primaryContainer to cs.onPrimaryContainer
        Who.ASSISTANT -> cs.surfaceVariant to cs.onSurfaceVariant
        Who.INFO -> cs.secondaryContainer to cs.onSecondaryContainer
        Who.ERROR -> cs.errorContainer to cs.onErrorContainer
    }
    val end = who == Who.USER
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (end) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (faded) bg.copy(alpha = 0.5f) else bg,
            contentColor = fg,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (end) 18.dp else 4.dp, bottomEnd = if (end) 4.dp else 18.dp,
            ),
            modifier = Modifier
                .widthIn(max = maxW)
                .pointerInput(text) { detectTapGestures(onLongPress = { copyText(ctx, text) }) },
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                fontSize = 15.sp * scale,
                lineHeight = 20.sp * scale,
                fontStyle = if (faded) FontStyle.Italic else FontStyle.Normal,
                color = if (faded) fg.copy(alpha = 0.75f) else fg,
            )
        }
    }
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Nova", text))
    if (Build.VERSION.SDK_INT < 33) runCatching { Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "dotPhase")
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val d = ((phase - i + 3f) % 3f)
            val a = if (d < 1f) 0.35f + 0.65f * (1f - kotlin.math.abs(d - 0.5f) * 2f) else 0.35f
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(c))
        }
    }
}

@Composable
private fun Orb(phase: Phase, level: Float, modifier: Modifier) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.tertiary
    val c3 = MaterialTheme.colorScheme.secondary
    val inf = rememberInfiniteTransition(label = "orb")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "rot")
    val breathe by inf.animateFloat(
        0.86f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(90), label = "lvl")
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val s = when (phase) {
            Phase.LISTENING -> 0.66f + 0.34f * lvl
            Phase.SPEAKING -> breathe
            Phase.THINKING -> 0.78f
            else -> 0.8f
        }
        if (phase == Phase.LISTENING) {
            drawCircle(c1.copy(alpha = 0.18f + 0.25f * lvl), radius = r * (0.8f + 0.2f * lvl))
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(c2, c1), center = center, radius = (r * s).coerceAtLeast(1f)),
            radius = r * s,
        )
        if (phase == Phase.THINKING) {
            val stroke = r * 0.16f
            rotate(rot) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, c3, c2)),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

@Composable
private fun InputRow(state: UiState, name: String, scale: Float, onRequestTyping: () -> Unit) {
    val ctl = LocalContext.current.app.controller
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusTick by remember { mutableIntStateOf(0) }
    val requestTyping by rememberUpdatedState(onRequestTyping)
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        delay(120) // the overlay window becomes focusable asynchronously
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    fun send() {
        val t = text.trim()
        if (t.isNotEmpty()) {
            ctl.submitText(t)
            text = ""
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        requestTyping()
                        ctl.setTyping(true)
                        focusTick++
                    }
                },
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; ctl.userInteracted() },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Ask $name…", fontSize = 15.sp * scale) },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp * scale),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
        Spacer(Modifier.width(4.dp))
        val listening = state.phase == Phase.LISTENING
        IconButton(onClick = { ctl.onMicButton() }) {
            Icon(
                painterResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic),
                contentDescription = if (listening) "Stop listening" else "Speak",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (text.isNotBlank()) {
            FilledIconButton(
                onClick = { send() },
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
            }
        }
    }
}
