package app.nova.assistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.CallLog.Calls as C
import android.provider.ContactsContract.CommonDataKinds.Email as E
import android.provider.ContactsContract.CommonDataKinds.Phone as P
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import app.nova.assistant.agent.Fmt
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.Pending
import app.nova.assistant.core.TextNorm
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface ContactResult {
    data class Found(val name: String, val number: String, val label: String) : ContactResult
    data class Ambiguous(val names: List<String>) : ContactResult
    data object NotFound : ContactResult
    data object NoPermission : ContactResult
}

/** Fuzzy contact lookup with aliases ("mom = Jane Doe"), number types and ambiguity detection. */
object Contacts {
    private data class Row(
        val contactId: Long, val name: String, val norm: String, val number: String,
        val type: Int, val superPrimary: Boolean, val starred: Boolean,
    )

    private var cache: List<Row> = emptyList()
    private var cacheAt = 0L

    private val typeWords = linkedMapOf(
        "cell phone" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        "mobile" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        "cell" to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        "work" to ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
        "office" to ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
        "home" to ContactsContract.CommonDataKinds.Phone.TYPE_HOME,
    )

    private fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun rows(ctx: Context): List<Row>? {
        if (!granted(ctx)) return null
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAt < 120_000) return cache
        val out = mutableListOf<Row>()
        try {
            ctx.contentResolver.query(
                P.CONTENT_URI,
                arrayOf(P.CONTACT_ID, P.DISPLAY_NAME, P.NUMBER, P.TYPE, P.IS_SUPER_PRIMARY, P.STARRED),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    out += Row(c.getLong(0), name, TextNorm.basic(name), number, c.getInt(3), c.getInt(4) != 0, c.getInt(5) != 0)
                }
            }
        } catch (t: Throwable) {
            AppLog.d("contacts", "query failed", t)
        }
        cache = out; cacheAt = now
        return out
    }

    private fun clean(q: String): String {
        var s = TextNorm.basic(q).replace(Regex("'s\\b"), "").replace("'", "")
        s = s.replace(Regex("^(my|the|to|for)\\s+"), "")
        s = s.replace(Regex("\\s+(phone|number|on)$"), "")
        return s.trim()
    }

    private fun looksLikeNumber(q: String) = q.count { it.isDigit() } >= 3 && q.none { it.isLetter() }

    private fun score(q: String, name: String): Float {
        if (name.isEmpty()) return 0f
        if (name == q) return 1f
        val first = name.substringBefore(' ')
        if (first == q) return 0.9f
        if (name.startsWith(q)) return 0.85f
        if (name.split(' ').any { it == q } || (q.contains(' ') && name.contains(q))) return 0.75f
        return maxOf(TextNorm.similarity(q, name), TextNorm.similarity(q, first)) * 0.8f
    }

    private data class Scored(val id: Long, val rows: List<Row>, val score: Float)

    private fun rank(q: String, rows: List<Row>): List<Scored> = rows.groupBy { it.contactId }
        .map { (id, rs) -> Scored(id, rs, score(q, rs.first().norm)) }
        .sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.rows.first().starred })

    /** Applies aliases; returns either a direct number or the cleaned name query. */
    private fun applyAliases(ctx: Context, query: String): Pair<String?, String> {
        val q = clean(query)
        val aliases = ctx.app.settings.aliases()
        val target = aliases[q] ?: aliases[TextNorm.basic(query)] ?: return null to q
        return if (looksLikeNumber(target)) target to q else null to clean(target)
    }

    fun resolve(ctx: Context, query: String): ContactResult {
        val raw = query.trim()
        if (looksLikeNumber(raw)) return ContactResult.Found(raw, raw.filter { it.isDigit() || it == '+' }, "")
        val (aliasNumber, q0) = applyAliases(ctx, raw)
        if (aliasNumber != null) return ContactResult.Found(q0, aliasNumber.filter { it.isDigit() || it == '+' }, "")
        var q = q0
        var wantType: Int? = null
        for ((w, t) in typeWords) {
            if (q.endsWith(" $w")) { wantType = t; q = q.removeSuffix(" $w").trim(); break }
        }
        q = q.removeSuffix(" s").trim()
        val rows = rows(ctx) ?: return ContactResult.NoPermission
        if (q.isBlank() || rows.isEmpty()) return ContactResult.NotFound
        val ranked = rank(q, rows)
        val best = ranked.first()
        if (best.score < 0.6f) return ContactResult.NotFound
        val close = ranked.filter { best.score - it.score < 0.05f }
            .distinctBy { it.rows.first().norm }
        if (close.size > 1) {
            val bestStarred = best.rows.first().starred
            val othersStarred = close.drop(1).any { it.rows.first().starred }
            if (!(bestStarred && !othersStarred)) return ContactResult.Ambiguous(close.take(4).map { it.rows.first().name })
        }
        val pick = best.rows.firstOrNull { wantType != null && it.type == wantType }
            ?: best.rows.firstOrNull { it.superPrimary }
            ?: best.rows.firstOrNull { it.type == P.TYPE_MOBILE }
            ?: best.rows.first()
        val label = P.getTypeLabel(ctx.resources, pick.type, "").toString().lowercase()
        return ContactResult.Found(pick.name, pick.number, label)
    }

    /** Top match score 0..1 (QuickCommands uses it to split "text mom I'm late"). */
    fun bestScore(ctx: Context, query: String): Float {
        val raw = query.trim()
        if (raw.isEmpty()) return 0f
        if (looksLikeNumber(raw)) return 0.95f
        val aliases = ctx.app.settings.aliases()
        if (aliases.containsKey(clean(raw))) return 1f
        val rows = rows(ctx) ?: return 0f
        if (rows.isEmpty()) return 0f
        var q = clean(raw)
        for (w in typeWords.keys) if (q.endsWith(" $w")) { q = q.removeSuffix(" $w").trim(); break }
        if (q.isBlank()) return 0f
        return rank(q, rows).first().score
    }

    fun nameForNumber(ctx: Context, number: String): String? {
        if (!granted(ctx) || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (t: Throwable) {
            null
        }
    }

    fun emailFor(ctx: Context, query: String): String? {
        if (!granted(ctx)) return null
        val (_, q) = applyAliases(ctx, query)
        var best: Pair<Float, String>? = null
        try {
            ctx.contentResolver.query(E.CONTENT_URI, arrayOf(E.DISPLAY_NAME, E.ADDRESS), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val addr = c.getString(1) ?: continue
                    val s = score(q, TextNorm.basic(name))
                    if (best == null || s > best!!.first) best = s to addr
                }
            }
        } catch (t: Throwable) {
            AppLog.d("contacts", "email query failed", t)
        }
        return best?.takeIf { it.first >= 0.6f }?.second
    }

    /** All numbers of the best-matching contact, "mobile 555-1234". */
    fun numbersOf(ctx: Context, name: String): List<String> {
        val rows = rows(ctx) ?: return emptyList()
        val target = rows.filter { it.name == name }
        return target.map { "${P.getTypeLabel(ctx.resources, it.type, "").toString().lowercase()} ${it.number}".trim() }.distinct()
    }
}

/** Calls, texts, messaging apps, call log, email. */
object CommTools {
    private val smsSendIds = listOf(
        "com.google.android.apps.messaging:id/send_message_button_icon",
        "com.google.android.apps.messaging:id/send_message_button_container",
        "com.samsung.android.messaging:id/send_button1",
    )
    private val smsSendLabels = listOf("Send SMS", "Send message", "Send", "SMS")

    val tools = listOf(
        Tool("call", "contact:str", "Phone call a contact name or number", "Phone", "Calling…") { a ->
            val who = a.str("contact", "name", "number", "to", "person", "who")
            if (who == null) ToolOutcome("Who should I call?", ask = { ans -> callTarget(ans) }) else callTarget(who)
        },
        Tool("call_back", "", "Call back the most recent incoming or missed call", "Phone", "Calling back…") { callBack() },
        Tool("send_text", "contact:str, message:str", "Send an SMS text message", "Phone", "Texting…") { a ->
            val who = a.str("contact", "name", "to", "number", "recipient", "person")
            val msg = a.str("message", "text", "body", "content", "msg")
            if (who == null) ToolOutcome("Who should I text?", ask = { ans -> textTo(ans, msg) }) else textTo(who, msg)
        },
        Tool("send_app_message", "app:str whatsapp|telegram|signal|messenger, contact:str, message:str", "Send a message through a messaging app", "Phone", "Sending…") { a ->
            appMessage(a)
        },
        Tool("read_texts", "contact?:str, count?:int", "Read recent received SMS messages", "Phone", "Checking messages…") { a -> readTexts(a) },
        Tool("recent_calls", "count?:int, missed_only?:bool", "List recent / missed calls", "Phone", "Checking calls…") { a -> recentCalls(a) },
        Tool("compose_email", "to?:str, subject?:str, body?:str", "Start an email draft", "Phone") { a -> composeEmail(a) },
        Tool("find_contact", "name:str", "Look up a contact's phone number", "Phone") { a -> findContact(a) },
    )

    private fun firstName(n: String) = n.substringBefore(' ')

    private fun joinOr(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
    }

    private fun ToolContext.contactsNeeded(): ToolOutcome =
        requirePerms(Manifest.permission.READ_CONTACTS, why = "look up your contacts")
            ?: fail("I couldn't read your contacts.")

    // ---------- calls ----------

    private fun ToolContext.callTarget(who: String): ToolOutcome = when (val r = Contacts.resolve(ctx, who)) {
        ContactResult.NoPermission -> contactsNeeded()
        ContactResult.NotFound -> fail("I couldn't find $who in your contacts.")
        is ContactResult.Ambiguous -> ToolOutcome("Which one: ${joinOr(r.names)}?", ask = { ans -> callTarget(ans) })
        is ContactResult.Found -> confirmCall(r)
    }

    private fun ToolContext.confirmCall(r: ContactResult.Found): ToolOutcome {
        val desc = r.name + if (r.label.isNotBlank()) " (${r.label})" else ""
        if (settings[K.confirmCalls]) {
            val q = "Call $desc?"
            return ToolOutcome(q, confirm = Pending(q, "Call") { dial(r) })
        }
        return dial(r)
    }

    private fun ToolContext.dial(r: ContactResult.Found): ToolOutcome {
        val uri = Uri.fromParts("tel", r.number, null)
        val direct = has(Manifest.permission.CALL_PHONE)
        if (!launch(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri))) return fail("I couldn't start the call.")
        return if (direct) ToolOutcome("Calling ${r.name}.", dismiss = true)
        else ok("I opened the dialer for ${r.name}. Allow Nova's phone permission to call directly.")
    }

    private fun ToolContext.callBack(): ToolOutcome {
        requirePerms(Manifest.permission.READ_CALL_LOG, why = "check your recent calls")?.let { return it }
        var found: ContactResult.Found? = null
        try {
            ctx.contentResolver.query(
                C.CONTENT_URI, arrayOf(C.NUMBER, C.CACHED_NAME, C.TYPE),
                "${C.TYPE} IN (${C.INCOMING_TYPE}, ${C.MISSED_TYPE}, ${C.REJECTED_TYPE})", null, "${C.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && found == null) {
                    val num = c.getString(0)
                    if (num.isNullOrBlank()) continue
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: Contacts.nameForNumber(ctx, num) ?: num
                    found = ContactResult.Found(name, num, "")
                }
            }
        } catch (t: Throwable) {
            AppLog.d("comm", "call log failed", t)
        }
        val f = found ?: return ok("I don't see any recent calls to return.")
        return confirmCall(f)
    }

    // ---------- SMS ----------

    private fun ToolContext.textTo(who: String, msg: String?): ToolOutcome = when (val r = Contacts.resolve(ctx, who)) {
        ContactResult.NoPermission -> contactsNeeded()
        ContactResult.NotFound -> fail("I couldn't find $who in your contacts.")
        is ContactResult.Ambiguous -> ToolOutcome("Which one: ${joinOr(r.names)}?", ask = { ans -> textTo(ans, msg) })
        is ContactResult.Found ->
            if (msg.isNullOrBlank()) ToolOutcome("What should the message say?", ask = { m -> textFound(r, m) })
            else textFound(r, msg)
    }

    private fun ToolContext.textFound(r: ContactResult.Found, msg: String): ToolOutcome {
        if (settings[K.confirmTexts]) {
            val q = "Text ${r.name}: “$msg”?"
            return ToolOutcome(q, confirm = Pending(q, "Send") { deliverSms(r, msg) })
        }
        return deliverSms(r, msg)
    }

    private fun ToolContext.deliverSms(r: ContactResult.Found, msg: String): ToolOutcome {
        val wantDirect = settings[K.smsMethod] == "direct"
        if (wantDirect && has(Manifest.permission.SEND_SMS)) {
            try {
                @Suppress("DEPRECATION")
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                if (sms != null) {
                    val parts = sms.divideMessage(msg)
                    sms.sendMultipartTextMessage(r.number, null, parts, null, null)
                    AppLog.d("comm", "SMS sent to ${r.name} (${parts.size} part(s))")
                    return ok("Sent to ${firstName(r.name)}.")
                }
            } catch (t: Throwable) {
                AppLog.d("comm", "direct SMS failed, opening app", t)
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(r.number))).putExtra("sms_body", msg)
        if (!launch(i)) return fail("I couldn't open your messaging app.")
        val svc = a11y
        if (settings[K.autoSend] && svc != null) {
            val pkg = runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
            app.appScope.launch {
                val sent = runCatching { svc.autoClick(pkg, smsSendIds, smsSendLabels, 8000, 500) }.getOrDefault(false)
                AppLog.d("comm", "auto-send in messaging app: $sent")
            }
            return ok("Sending to ${firstName(r.name)}.")
        }
        val hint = if (wantDirect) " Allow Nova's SMS permission to send texts directly." else ""
        return ok("Your message to ${firstName(r.name)} is ready — just tap send.$hint")
    }

    // ---------- messaging apps ----------

    private fun ToolContext.appMessage(a: Args): ToolOutcome {
        val appName = (a.str("app", "platform", "service", "via") ?: "whatsapp").lowercase()
        val who = a.str("contact", "name", "to", "recipient", "person")
        val msg = a.str("message", "text", "body", "content")
        return when {
            "whats" in appName -> {
                if (who == null) ToolOutcome("Who should I message on WhatsApp?", ask = { ans -> whatsApp(ans, msg) })
                else whatsApp(who, msg)
            }
            "telegram" in appName -> share("org.telegram.messenger", "Telegram", who, msg)
            "signal" in appName -> share("org.thoughtcrime.securesms", "Signal", who, msg)
            "messenger" in appName || "facebook" in appName -> share("com.facebook.orca", "Messenger", who, msg)
            "sms" in appName || "text" in appName -> if (who == null) fail("Who should I text?") else textTo(who, msg)
            else -> fail("I can send messages with WhatsApp, Telegram, Signal or Messenger.")
        }
    }

    private fun ToolContext.whatsApp(who: String, msg: String?): ToolOutcome = when (val r = Contacts.resolve(ctx, who)) {
        ContactResult.NoPermission -> contactsNeeded()
        ContactResult.NotFound -> fail("I couldn't find $who in your contacts.")
        is ContactResult.Ambiguous -> ToolOutcome("Which one: ${joinOr(r.names)}?", ask = { ans -> whatsApp(ans, msg) })
        is ContactResult.Found -> {
            if (msg.isNullOrBlank()) ToolOutcome("What should the message say?", ask = { m -> confirmWhatsApp(r, m) })
            else confirmWhatsApp(r, msg)
        }
    }

    private fun ToolContext.confirmWhatsApp(r: ContactResult.Found, msg: String): ToolOutcome {
        if (settings[K.confirmApps]) {
            val q = "WhatsApp ${r.name}: “$msg”?"
            return ToolOutcome(q, confirm = Pending(q, "Send") { openWhatsApp(r, msg) })
        }
        return openWhatsApp(r, msg)
    }

    private fun ToolContext.waDigits(number: String): String {
        val t = number.trim()
        var d = t.filter { it.isDigit() }
        if (t.startsWith("00")) d = d.drop(2)
        else if (!t.startsWith("+") && d.length <= 10) d = settings[K.countryCode].filter { it.isDigit() } + d.removePrefix("0")
        return d
    }

    private fun ToolContext.openWhatsApp(r: ContactResult.Found, msg: String): ToolOutcome {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${waDigits(r.number)}&text=${Uri.encode(msg)}")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (!launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) continue
            val svc = a11y
            if (settings[K.autoSend] && svc != null) {
                app.appScope.launch {
                    val sent = runCatching { svc.autoClick(pkg, listOf("$pkg:id/send"), listOf("Send"), 10000, 600) }.getOrDefault(false)
                    AppLog.d("comm", "WhatsApp auto-send: $sent")
                }
                return ok("Sending on WhatsApp to ${firstName(r.name)}.")
            }
            return ok("Your WhatsApp message to ${firstName(r.name)} is ready — tap send.")
        }
        return fail("WhatsApp isn't installed.")
    }

    private fun ToolContext.share(pkg: String, label: String, who: String?, msg: String?): ToolOutcome {
        if (msg.isNullOrBlank()) {
            return ToolOutcome("What should the message say?", ask = { m -> share(pkg, label, who, m) })
        }
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg).setPackage(pkg)
        return if (launch(i)) ok("I opened $label with your message — pick ${who ?: "the"}${if (who != null) "'s" else ""} chat to send it.")
        else fail("$label isn't installed.")
    }

    // ---------- reading ----------

    private fun ToolContext.whenText(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        val sameDay = c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) "at ${Fmt.time(ctx, t)}" else Fmt.dateTime(ctx, t)
    }

    private fun ToolContext.readTexts(a: Args): ToolOutcome {
        requirePerms(Manifest.permission.READ_SMS, why = "read your messages")?.let { return it }
        val count = (a.int("count", "limit", "n", "number") ?: 5).coerceIn(1, 20)
        val who = a.str("contact", "from", "name", "person")
        var filterDigits: String? = null
        var filterName: String? = null
        if (who != null) {
            when (val r = Contacts.resolve(ctx, who)) {
                is ContactResult.Found -> { filterDigits = r.number.filter { it.isDigit() }.takeLast(7); filterName = r.name }
                is ContactResult.Ambiguous -> return ToolOutcome("Which one: ${joinOr(r.names)}?", ask = { ans ->
                    readTexts(Args(org.json.JSONObject().put("contact", ans).put("count", count)))
                })
                ContactResult.NoPermission -> { filterName = who }
                ContactResult.NotFound -> return fail("I couldn't find $who in your contacts.")
            }
        }
        val names = HashMap<String, String>()
        fun nameOf(addr: String) = names.getOrPut(addr) { Contacts.nameForNumber(ctx, addr) ?: addr }
        val items = try {
            // SMS and MMS (group chats and messages with photos are MMS) from the last 90 days
            Messages.recent(ctx, System.currentTimeMillis() - 90 * 86_400_000L, count, incomingOnly = true) { addr ->
                when {
                    filterDigits != null -> addr.filter { it.isDigit() }.endsWith(filterDigits!!)
                    filterName != null -> nameOf(addr).contains(filterName!!, ignoreCase = true)
                    else -> true
                }
            }
        } catch (t: Throwable) {
            AppLog.d("comm", "message query failed", t)
            return fail("I couldn't read your messages.")
        }
        if (items.isEmpty()) return ok(if (filterName != null) "No messages from $filterName in the last 90 days." else "You don't have any text messages.")
        val spoken = items.take(3).joinToString(" ") {
            "From ${firstName(nameOf(it.address))}${if (it.group) " in a group chat" else ""} ${whenText(it.date)}: ${it.text().take(160).trimEnd('.', ' ')}."
        }
        val data = items.joinToString("\n") {
            "${nameOf(it.address)}${if (it.group) " (group chat)" else ""} (${Fmt.dateTime(ctx, it.date)}${if (!it.read) ", unread" else ""}): ${it.text()}"
        }
        return ok(spoken, data = data, needsModel = true)
    }

    private fun ToolContext.recentCalls(a: Args): ToolOutcome {
        requirePerms(Manifest.permission.READ_CALL_LOG, why = "check your recent calls")?.let { return it }
        val count = (a.int("count", "limit", "n") ?: 5).coerceIn(1, 20)
        val missedOnly = a.bool("missed_only", "missed") == true
        val lines = mutableListOf<String>()
        try {
            ctx.contentResolver.query(
                C.CONTENT_URI, arrayOf(C.NUMBER, C.CACHED_NAME, C.TYPE, C.DATE),
                if (missedOnly) "${C.TYPE} = ${C.MISSED_TYPE}" else null, null, "${C.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && lines.size < count) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: Contacts.nameForNumber(ctx, num) ?: num.ifBlank { "an unknown number" }
                    val kind = when (c.getInt(2)) {
                        C.INCOMING_TYPE -> "Call from $name"
                        C.OUTGOING_TYPE -> "You called $name"
                        C.MISSED_TYPE -> "Missed call from $name"
                        C.REJECTED_TYPE -> "Declined call from $name"
                        C.BLOCKED_TYPE -> "Blocked call from $name"
                        C.VOICEMAIL_TYPE -> "Voicemail from $name"
                        else -> "Call with $name"
                    }
                    lines += "$kind ${whenText(c.getLong(3))}"
                }
            }
        } catch (t: Throwable) {
            AppLog.d("comm", "call log failed", t)
            return fail("I couldn't read your call history.")
        }
        if (lines.isEmpty()) return ok(if (missedOnly) "No missed calls." else "No recent calls.")
        return ok(lines.take(3).joinToString(". ") + ".", data = lines.joinToString("\n"), needsModel = true)
    }

    private fun ToolContext.composeEmail(a: Args): ToolOutcome {
        val toRaw = a.str("to", "email", "recipient", "contact")
        val subject = a.str("subject", "title")
        val body = a.str("body", "text", "message", "content")
        var to: String? = null
        if (toRaw != null) {
            to = if (toRaw.contains('@')) toRaw.trim() else {
                if (!has(Manifest.permission.READ_CONTACTS)) return contactsNeeded()
                Contacts.emailFor(ctx, toRaw) ?: return fail("I couldn't find an email address for $toRaw.")
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        to?.let { i.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        subject?.let { i.putExtra(Intent.EXTRA_SUBJECT, it) }
        body?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
        return if (launch(i)) ok("Your email draft${to?.let { " to $it" } ?: ""} is ready.") else fail("I couldn't find an email app.")
    }

    private fun ToolContext.findContact(a: Args): ToolOutcome {
        val name = a.str("name", "contact", "person", "who") ?: return ToolOutcome("Whose number do you want?", ask = { ans ->
            findContact(Args(org.json.JSONObject().put("name", ans)))
        })
        return when (val r = Contacts.resolve(ctx, name)) {
            ContactResult.NoPermission -> contactsNeeded()
            ContactResult.NotFound -> fail("I couldn't find $name in your contacts.")
            is ContactResult.Ambiguous -> ToolOutcome("I found ${joinOr(r.names)}. Which one?", ask = { ans ->
                findContact(Args(org.json.JSONObject().put("name", ans)))
            })
            is ContactResult.Found -> {
                val all = Contacts.numbersOf(ctx, r.name).ifEmpty { listOf(r.number) }
                val spoken = if (all.size == 1) "${r.name}'s ${r.label.ifBlank { "number" }}${if (r.label.isNotBlank()) " number" else ""} is ${r.number}."
                else "${r.name}: " + all.joinToString(", ") + "."
                ok(spoken, data = "${r.name}: " + all.joinToString("; "), needsModel = true)
            }
        }
    }
}
