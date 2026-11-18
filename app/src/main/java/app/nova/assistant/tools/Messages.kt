package app.nova.assistant.tools

import android.content.Context
import android.net.Uri
import app.nova.assistant.core.AppLog

/**
 * Text messages from Android's message store: plain SMS *and* MMS. On many phones most conversations are MMS —
 * group chats, anything with a photo, long texts — and their words live in a separate "parts" table.
 * (RCS chat messages are private to the Messages app and can't be read by other apps.)
 */
object Messages {
    data class Msg(
        val address: String, val body: String, val date: Long, val sent: Boolean, val read: Boolean,
        val media: String? = null, val group: Boolean = false,
    ) {
        /** What to say/show for the message: its text, or what was attached. */
        fun text(): String = when {
            body.isNotBlank() && media != null -> "$body (with a $media)"
            body.isNotBlank() -> body
            media != null -> "[sent a $media]"
            else -> ""
        }
    }

    /**
     * Newest first, SMS and MMS merged. [accept] filters by the other person's address (cheap check before the
     * message's text is loaded); [incomingOnly] skips messages the user sent.
     */
    fun recent(
        ctx: Context, sinceMs: Long, limit: Int, incomingOnly: Boolean = false,
        smsLimit: Int = limit, mmsLimit: Int = limit, accept: (String) -> Boolean = { true },
    ): List<Msg> {
        val sms = runCatching { sms(ctx, sinceMs, smsLimit, incomingOnly, accept) }.onFailure { AppLog.d("msgs", "sms read failed", it) }.getOrDefault(emptyList())
        val mms = runCatching { mms(ctx, sinceMs, mmsLimit, incomingOnly, accept) }.onFailure { AppLog.d("msgs", "mms read failed", it) }.getOrDefault(emptyList())
        return (sms + mms).sortedByDescending { it.date }.take(limit)
    }

    private fun sms(ctx: Context, sinceMs: Long, limit: Int, incomingOnly: Boolean, accept: (String) -> Boolean): List<Msg> {
        val out = ArrayList<Msg>()
        ctx.contentResolver.query(
            Uri.parse(if (incomingOnly) "content://sms/inbox" else "content://sms"), arrayOf("address", "body", "date", "type", "read"),
            "date > ?", arrayOf(sinceMs.toString()), "date DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && out.size < limit && scanned++ < 3000) {
                val addr = c.getString(0) ?: continue
                if (!accept(addr)) continue
                out += Msg(addr, c.getString(1) ?: "", c.getLong(2), c.getInt(3) == 2, c.getInt(4) != 0)
            }
        }
        return out
    }

    private fun mms(ctx: Context, sinceMs: Long, limit: Int, incomingOnly: Boolean, accept: (String) -> Boolean): List<Msg> {
        data class Head(val id: Long, val date: Long, val sent: Boolean, val read: Boolean)
        val cr = ctx.contentResolver
        val heads = ArrayList<Head>()
        // MMS dates are in seconds
        cr.query(
            Uri.parse("content://mms"), arrayOf("_id", "date", "msg_box", "read"),
            "date > ?" + if (incomingOnly) " AND msg_box = 1" else " AND msg_box IN (1, 2)", arrayOf((sinceMs / 1000).toString()), "date DESC",
        )?.use { c -> while (c.moveToNext() && heads.size < 2000) heads += Head(c.getLong(0), c.getLong(1) * 1000, c.getInt(2) == 2, c.getInt(3) != 0) }

        val out = ArrayList<Msg>()
        val accepted = ArrayList<Pair<Head, Pair<String, Boolean>>>()
        for (h in heads) {
            if (accepted.size >= limit) break
            // sender (137) for incoming, first recipient (151) for sent; more than one other person = group chat
            var from: String? = null
            val to = ArrayList<String>()
            cr.query(Uri.parse("content://mms/${h.id}/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val a = c.getString(0) ?: continue
                    if (a == "insert-address-token") continue
                    when (c.getInt(1)) { 137 -> from = a; 151, 130 -> to += a }
                }
            }
            // incoming: who sent it; sent: who it went to (any of them, for a group)
            val other = (if (h.sent) to.firstOrNull { accept(it) } ?: to.firstOrNull() else from) ?: continue
            val ok = if (h.sent) to.any(accept) else accept(other)
            if (!ok) continue
            accepted += h to (other to (to.size > 1))
        }
        if (accepted.isEmpty()) return out

        // all text parts in one query
        val texts = HashMap<Long, StringBuilder>()
        val media = HashMap<Long, String>()
        accepted.map { it.first.id }.chunked(400).forEach { ids ->
            cr.query(
                Uri.parse("content://mms/part"), arrayOf("mid", "ct", "text"),
                "mid IN (${ids.joinToString(",")})", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val mid = c.getLong(0)
                    val ct = c.getString(1).orEmpty()
                    when {
                        ct == "text/plain" -> c.getString(2)?.takeIf { it.isNotBlank() }?.let { texts.getOrPut(mid) { StringBuilder() }.append(it).append(' ') }
                        ct.startsWith("image/") -> media.putIfAbsent(mid, "photo")
                        ct.startsWith("video/") -> media.putIfAbsent(mid, "video")
                        ct.startsWith("audio/") -> media.putIfAbsent(mid, "voice message")
                        ct == "text/x-vcard" -> media.putIfAbsent(mid, "contact card")
                    }
                }
            }
        }
        for ((h, who) in accepted) {
            val body = texts[h.id]?.toString()?.trim().orEmpty()
            out += Msg(who.first, body, h.date, h.sent, h.read, media[h.id], who.second)
        }
        return out
    }
}
