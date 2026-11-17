package app.nova.assistant.agent

import app.nova.assistant.NovaApp
import app.nova.assistant.core.AgentResult
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.ChatMsg
import app.nova.assistant.core.Conversation
import app.nova.assistant.core.K
import app.nova.assistant.core.Pending
import app.nova.assistant.core.TextNorm
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface AgentUi {
    fun streaming(text: String)
    fun status(text: String)
    /** Things from the phone's personal index that were handed to the AI with this request. */
    fun found(lines: List<String>) {}
    /** A side note for the popup (e.g. which websites a search used). */
    fun info(text: String) {}
}

/**
 * Turns one user utterance into an answer:
 * pending confirmation → slot filling → quick commands (instant, no model) → on-device LLM with tool calls.
 */
class Agent(private val app: NovaApp) {
    private val s get() = app.settings

    suspend fun handle(input: String, conv: Conversation, ui: AgentUi): AgentResult {
        conv.pending?.let { p ->
            when (yesNo(input)) {
                true -> {
                    conv.pending = null
                    ui.status("Working…")
                    val o = runPending(p)
                    conv.carryOver += "[The user confirmed: ${p.question} → ${o.message}]"
                    return fromOutcome(o, conv, ui)
                }
                false -> {
                    conv.pending = null
                    conv.carryOver += "[The user cancelled: ${p.question}]"
                    return AgentResult("Okay, cancelled.")
                }
                null -> conv.pending = null
            }
        }
        conv.awaiting?.let { a ->
            conv.awaiting = null
            return a(input)
        }
        if (s[K.quickCommands]) {
            val q = QuickCommands.parse(input, app, conv.lastTool)
            if (q != null) {
                AppLog.d("agent", "quick: $q")
                runQuick(q, input, conv, ui)?.let { return it }
            }
        }
        return llm(input, conv, ui)
    }

    private suspend fun runPending(p: Pending): ToolOutcome = try {
        p.action()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        ToolOutcome.fail("That didn't work: ${t.message}")
    }

    private suspend fun runQuick(q: Q, input: String, conv: Conversation, ui: AgentUi): AgentResult? = when (q) {
        is Q.Say -> AgentResult(q.text, dismiss = q.dismiss)
        is Q.Ask -> {
            conv.awaiting = { ans ->
                val next = q.then(ans)
                if (next != null) runQuick(next, "$input → $ans", conv, ui) ?: llm("$input — $ans", conv, ui)
                else handle(ans, conv, ui)
            }
            AgentResult(q.question, expectReply = true)
        }
        is Q.Tool -> {
            val tool = app.tools[q.name]
            if (tool == null || !app.tools.isEnabled(tool)) null
            else {
                ui.status(tool.status)
                conv.lastTool = tool.name
                val o = app.tools.run(tool.name, q.args)
                o.note?.let(ui::info)
                if (o.preferModel && o.success && o.data != null && s[K.summarizeResults] && app.llm.isConfigured()) {
                    // e.g. "search for X": let the AI explain the results instead of reading a raw snippet
                    conv.llmMessages += ChatMsg.user(takeCarryOver(conv) + input)
                    conv.llmMessages += ChatMsg.assistant(JSONObject().put("tool", tool.name).put("args", q.args).toString())
                    conv.llmMessages += ChatMsg.user(resultMessage(tool.name, o))
                    ui.status("Thinking…")
                    // if the model fails, still read out what was found
                    runModel(conv, ui, startStep = 1).let { r -> if (r.error) AgentResult(o.message, conversational = true) else r }
                } else {
                    conv.carryOver += "[Earlier the user said \"$input\" and I ran ${tool.name} → \"${o.message.take(300)}\"]"
                    fromOutcome(o, conv, ui)
                }
            }
        }
    }

    private fun fromOutcome(o: ToolOutcome, conv: Conversation, ui: AgentUi): AgentResult {
        o.confirm?.let { return AgentResult(it.question, pending = it, expectReply = true) }
        o.ask?.let { ask ->
            conv.awaiting = { ans ->
                val next = try { ask(ans) } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                    ToolOutcome.fail("That didn't work: ${t.message}")
                }
                fromOutcome(next, conv, ui)
            }
            return AgentResult(o.message, expectReply = true)
        }
        return AgentResult(o.message, error = !o.success, dismiss = o.dismiss, expectReply = o.message.trimEnd().endsWith("?"), quiet = o.quiet)
    }

    // ---- LLM path ---------------------------------------------------------------------------

    private suspend fun llm(input: String, conv: Conversation, ui: AgentUi): AgentResult {
        if (!app.llm.isConfigured()) {
            return AgentResult(
                "I can do quick commands like timers, alarms, calls, texts and settings, but no AI model is set up " +
                    "for anything else yet. Open Nova → AI model to download one.",
            )
        }
        ui.status(if (app.llm.isReady()) "Thinking…" else "Loading model…")
        // personal context (notifications, texts, calendar) found by a fast local search — saves tool round-trips
        val found = app.index.contextFor(input)
        if (found.isNotEmpty()) {
            AppLog.d("agent", "context: ${found.size} item(s) from the phone")
            ui.found(found.map { it.line() })
        }
        val first = buildString {
            append(takeCarryOver(conv))
            if (found.isNotEmpty()) append(app.index.contextBlock(found)).append("\n")
            append(input)
        }
        conv.llmMessages += ChatMsg.user(first)
        return runModel(conv, ui)
    }

    private fun takeCarryOver(conv: Conversation): String {
        if (conv.carryOver.isEmpty()) return ""
        return conv.carryOver.joinToString("\n", postfix = "\n").also { conv.carryOver.clear() }
    }

    private fun resultMessage(tool: String, o: ToolOutcome): String {
        val data = (o.data ?: o.message).take(3000)
        val how = if (tool == "web_search")
            "Now explain what was found, in your own words, with {\"say\":...}: answer the user's actual question (in depth if they asked for detail) and mention the site you got it from."
        else "Now answer the user's request with {\"say\":...} (or call another tool if needed)."
        return "TOOL_RESULT $tool: $data\n$how"
    }

    /** The model's turn(s): generate, run tool calls, until it says something. */
    private suspend fun runModel(conv: Conversation, ui: AgentUi, startStep: Int = 0): AgentResult {
        val system = Prompt.system(app)
        val maxSteps = s[K.maxToolSteps].coerceIn(1, 8)
        for (step in startStep until maxSteps.coerceAtLeast(startStep + 1)) {
            val raw = try {
                app.llm.generate(conv.id, system, conv.llmMessages.toList()) { partial ->
                    ui.streaming(Prompt.previewSay(partial))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.d("agent", "generate failed", t)
                // keep the conversation consistent for the next attempt
                conv.llmMessages += ChatMsg.assistant("{\"say\":\"(error)\"}")
                return AgentResult("The AI model failed: ${t.message ?: t.javaClass.simpleName}", error = true)
            }
            AppLog.d("agent", "model: ${raw.take(400)}")
            conv.llmMessages += ChatMsg.assistant(raw)
            ui.streaming("")
            when (val p = ReplyParser.parse(raw)) {
                is ReplyParser.Parsed.Say -> {
                    val text = p.text.ifBlank { "Okay." }
                    return AgentResult(text, expectReply = text.trimEnd().endsWith("?"), conversational = true)
                }
                is ReplyParser.Parsed.Call -> {
                    val tool = app.tools[p.name]
                    if (tool == null || !app.tools.isEnabled(tool)) {
                        conv.llmMessages += ChatMsg.user("TOOL_ERROR: there is no enabled tool named \"${p.name}\". Use one from the TOOLS list or reply with {\"say\":...}.")
                        continue
                    }
                    ui.status(if (s[K.showToolCalls]) "${tool.status} (${tool.name})" else tool.status)
                    conv.lastTool = tool.name
                    val o = app.tools.run(tool.name, p.args)
                    o.note?.let(ui::info)
                    when {
                        o.confirm != null -> {
                            conv.carryOver += "[${tool.name} is waiting for the user to confirm: ${o.confirm.question}]"
                            return AgentResult(o.confirm.question, pending = o.confirm, expectReply = true)
                        }
                        o.ask != null -> {
                            conv.carryOver += "[${tool.name} asked the user: ${o.message}]"
                            return fromOutcome(o, conv, ui)
                        }
                        o.dismiss -> {
                            conv.carryOver += "TOOL_RESULT ${tool.name}: ${o.message}"
                            return AgentResult(o.message, dismiss = true, error = !o.success, quiet = o.quiet)
                        }
                        o.needsModel && s[K.summarizeResults] && step < maxSteps - 1 -> {
                            conv.llmMessages += ChatMsg.user(resultMessage(tool.name, o))
                            ui.status("Thinking…")
                        }
                        !o.success && step < maxSteps - 1 && o.message.isNotBlank() && !s[K.summarizeResults] -> {
                            return AgentResult(o.message, error = true)
                        }
                        else -> {
                            conv.carryOver += "TOOL_RESULT ${tool.name}: ${o.message.take(500)}"
                            return AgentResult(o.message.ifBlank { "Done." }, error = !o.success, quiet = o.quiet)
                        }
                    }
                }
            }
        }
        return AgentResult("Done.")
    }

    private fun yesNo(input: String): Boolean? {
        val t = TextNorm.basic(input)
        if (Regex("^(yes|yeah|yep|yup|ya|sure|ok|okay|confirm|confirmed|do it|go ahead|send( it)?|call( them| him| her)?|correct|right|please do|yes please|absolutely|of course|sounds good|reply|post it)\\b").containsMatchIn(t)) return true
        if (Regex("^(no|nope|nah|cancel|stop|don't|do not|never ?mind|wait|abort)\\b").containsMatchIn(t)) return false
        return null
    }
}

object Prompt {
    fun system(app: NovaApp): String {
        val s = app.settings
        val now = Date()
        val date = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US).format(now)
        val tools = app.tools.enabled().joinToString("\n") { t ->
            "${t.name}(${t.params}) - ${t.description}"
        }
        val facts = if (s[K.memoryEnabled]) app.memory.facts.value.takeLast(40) else emptyList()
        return buildString {
            append("You are ${s[K.assistantName]}, a voice assistant running on the user's Android phone. ")
            append("Personality: ${s[K.personality]}.\n")
            s[K.userName].takeIf { it.isNotBlank() }?.let { append("The user's name is $it.\n") }
            append("Now: $date (${TimeZone.getDefault().id}).\n\n")
            append(
                """
                Reply with exactly ONE JSON object and nothing else:
                - to act on the phone: {"tool":"tool_name","args":{...}}
                - to talk to the user: {"say":"your reply"}
                After a tool runs you receive TOOL_RESULT; then reply with {"say":...} or call another tool.
                Rules:
                - Use a tool whenever the user asks you to do something on the phone. Never claim you did something without calling the tool.
                - "say" is spoken aloud: plain natural sentences, no markdown, no lists, no emojis. Usually 1-2 short sentences; when the user asks you to explain, go in depth, or tell them about something, give a full spoken explanation (up to about 8 sentences).
                - If required information is missing (who to text, what to say), ask with "say".
                - Times are 24-hour (hour 0-23). Durations are in seconds. Dates are YYYY-MM-DD HH:MM.
                - Don't repeat a tool call that already succeeded. Lines in [brackets] are things that already happened.
                - For questions you can answer yourself (facts, advice, writing, math explanations), just "say" the answer.
                - You can explain any topic, including health, medical and dental procedures, medicines, law and money, clearly like a knowledgeable friend. Never refuse with "I can't give medical advice" or "I'm not a medical professional" — even when they ask for advice, give practical general guidance, then at most one short line about when to see a doctor or dentist.
                - If you don't know, or it needs current information (news, scores, prices, opening hours, recent events), call web_search right away instead of offering to. Then explain the TOOL_RESULT in your own words and name the site it came from. Only pass open=true if the user asks to see the page or browser.
                - When the user refers to something personal ("my appointment", "the restaurant Sam texted", "my package"), use the [Possibly relevant] lines if present, or search_phone / calendar_events, to find the details (address, time) before acting. For directions, call navigate with the actual address you found.
                - The user's words come from speech recognition and may contain sound-alike mistakes ("cavity feeling" = cavity filling, "clawed" = Claude). Answer what they most likely meant; only ask if it's genuinely unclear.

                TOOLS:
                """.trimIndent()
            )
            append("\n").append(tools).append("\n\n")
            append(
                """
                EXAMPLES:
                User: set a timer for 2 minutes
                {"tool":"set_timer","args":{"seconds":120}}
                User: text Sam that I'm on my way
                {"tool":"send_text","args":{"contact":"Sam","message":"I'm on my way"}}
                User: can you give me medical advice about a toothache?
                {"say":"Sure. Rinse with warm salt water, take ibuprofen or acetaminophen as the label says, and hold a cold pack on your cheek for 15 minutes at a time. Skip very hot, cold or sugary food. If it lasts more than a day or two, or you get swelling or a fever, see a dentist soon."}
                User: why is the sky blue?
                {"say":"Sunlight scatters off air molecules, and blue light scatters the most, so the sky looks blue."}
                User: what's on my screen?
                {"tool":"read_screen","args":{}}
                User: TOOL_RESULT read_screen: Inbox. Jane: Lunch tomorrow? ...
                {"say":"You're in your inbox. The top message is from Jane asking about lunch tomorrow."}
                User: will I need an umbrella tomorrow?
                {"tool":"weather","args":{"day":"tomorrow"}}
                User: who won the World Series this year?
                {"tool":"web_search","args":{"query":"World Series winner this year"}}
                """.trimIndent()
            )
            if (app.home.isConfigured() && app.home.entities.isNotEmpty()) {
                val pinned = app.home.pinned()
                val more = app.home.controllable().filter { e ->
                    pinned.none { it.id == e.id } && e.state != "unavailable" && e.state != "unknown" && !e.id.startsWith("switch.cloud_")
                }
                val list = (pinned + more).take(60)
                append("\n\nHOME DEVICES (Home Assistant — use home_control / home_status; the first ones are the user's main devices):\n")
                list.forEach { append("- ").append(it.name).append(" [").append(it.id).append("] ").append(it.state).append('\n') }
                append("For rooms/areas or anything not listed, use home_assist with the user's words.\n")
            }
            app.customWords.promptLines().takeIf { it.isNotBlank() }?.let {
                append("\n\nWords and names the user uses (spell them exactly like this):\n").append(it).append('\n')
            }
            if (facts.isNotEmpty()) {
                append("\n\nThings you know about the user:\n")
                facts.forEach { append("- ").append(it).append('\n') }
            }
            s[K.customInstructions].takeIf { it.isNotBlank() }?.let { append("\n\nExtra instructions from the user:\n").append(it) }
        }
    }

    /** What to show in the popup while the model is still typing its JSON. */
    fun previewSay(partial: String): String {
        val t = ReplyParser.stripThink(partial).trim().removePrefix("```json").removePrefix("```").trim()
        if (!t.startsWith("{")) return t
        val m = Regex("\"(say|reply|response|answer|text|message)\"\\s*:\\s*\"").find(t) ?: return ""
        if (Regex("\"(tool|name|function|action)\"\\s*:").containsMatchIn(t.substring(0, m.range.first))) return ""
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                when (t[i + 1]) { 'n' -> sb.append(' '); 't' -> sb.append(' '); else -> sb.append(t[i + 1]) }
                i += 2; continue
            }
            if (c == '"') break
            sb.append(c); i++
        }
        return sb.toString()
    }
}

object ReplyParser {
    sealed interface Parsed {
        data class Say(val text: String) : Parsed
        data class Call(val name: String, val args: JSONObject) : Parsed
    }

    fun stripThink(s: String): String = s
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("(?s)<think>.*$"), "")
        .replace(Regex("(?s)<\\|channel\\|>thought.*?<channel\\|>"), "")

    fun parse(raw: String): Parsed {
        var t = stripThink(raw).trim()
        t = t.replace(Regex("^```(?:json)?\\s*"), "").replace(Regex("\\s*```$"), "").trim()
        if (t.startsWith("json", ignoreCase = true)) t = t.substring(4).trim()
        val obj = extractObject(t)
        if (obj != null) {
            interpret(obj)?.let { return it }
        }
        Regex("\"say\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)").find(t)?.let {
            return Parsed.Say(unescape(it.groupValues[1]))
        }
        // plain-text answer (model ignored the protocol)
        val plain = t.trim().trim('{', '}').trim()
        return Parsed.Say(plain)
    }

    private fun interpret(o: JSONObject): Parsed? {
        val nameKey = listOf("tool", "name", "function", "action", "tool_name").firstOrNull { o.optString(it).isNotBlank() }
        val name = nameKey?.let { o.optString(it).trim() }
        if (name != null && name.lowercase() !in setOf("say", "reply", "respond", "answer", "none")) {
            val args = argsOf(o, nameKey)
            return Parsed.Call(name, args)
        }
        val say = listOf("say", "reply", "response", "answer", "text", "message", "content").firstNotNullOfOrNull { k ->
            o.optString(k).takeIf { it.isNotBlank() && o.opt(k) is String }
        } ?: if (name != null) argsOf(o, nameKey).let { a -> a.optString("text").ifBlank { a.optString("message") } }.takeIf { it.isNotBlank() } else null
        return say?.let { Parsed.Say(it) }
    }

    private fun argsOf(o: JSONObject, nameKey: String?): JSONObject {
        for (k in listOf("args", "arguments", "parameters", "params", "input")) {
            when (val v = o.opt(k)) {
                is JSONObject -> return v
                is String -> runCatching { return JSONObject(v) }
            }
        }
        // flattened style: {"tool":"set_timer","seconds":60}
        val copy = JSONObject()
        o.keys().forEach { if (it != nameKey) copy.put(it, o.get(it)) }
        return copy
    }

    /** First balanced {...} object; repairs a truncated one by closing strings/braces. */
    fun extractObject(s: String): JSONObject? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull() ?: lenient(s.substring(start, i + 1)) }
            }
        }
        // truncated: close what is open
        val sb = StringBuilder(s.substring(start))
        if (inStr) sb.append('"')
        repeat(depth.coerceAtLeast(0)) { sb.append('}') }
        return runCatching { JSONObject(sb.toString()) }.getOrNull() ?: lenient(sb.toString())
    }

    private fun lenient(s: String): JSONObject? = runCatching {
        // single quotes / trailing commas from sloppy models
        JSONObject(s.replace(Regex(",\\s*([}\\]])"), "$1").replace('\'', '"'))
    }.getOrNull()

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\")
}
