package app.nova.assistant.tools

import app.nova.assistant.core.AppLog
import app.nova.assistant.core.TextNorm
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Looks things up on the web without opening a browser, so Nova can answer out loud:
 * search results (Yahoo, then DuckDuckGo, then Wikipedia) plus the readable text of the top pages.
 * (Bing is not used: without a browser session it quietly searches only one word of the query.)
 * No API keys; only the search query leaves the phone.
 */
object WebSearch {
    data class Hit(val title: String, val url: String, val snippet: String) {
        val site: String get() = runCatching { URI(url).host.removePrefix("www.").removePrefix("m.") }.getOrNull() ?: url
    }

    data class Result(val query: String, val hits: List<Hit>, val pages: List<Pair<Hit, String>>) {
        /** Material for the AI to answer from. */
        fun forModel(max: Int = 3000): String = buildString {
            append("Web results for \"").append(query).append("\":\n")
            hits.take(5).forEachIndexed { i, h -> append(i + 1).append(". ").append(h.title).append(" (").append(h.site).append("): ").append(h.snippet.take(240)).append('\n') }
            pages.forEach { (h, text) -> append("\nFrom ").append(h.site).append(": ").append(text).append('\n') }
        }.take(max)

        /** Spoken answer when no AI model is available: the best snippet as-is. */
        fun plainAnswer(): String {
            val h = hits.firstOrNull() ?: return "I couldn't find anything for $query."
            val text = pages.firstOrNull { it.first == h }?.second?.let(::firstSentences) ?: h.snippet.replace(Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{4} · "), "")
            return "From ${h.site}: $text"
        }

        fun sources(): String = (pages.map { it.first } + hits).map { it.site }.distinct().take(3).joinToString(", ")
    }

    /** The last search, so "open it" can show the page. */
    @Volatile var last: Result? = null

    private const val UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    private const val API_UA = "NovaAssistant/1.0 (Android voice assistant)"

    suspend fun lookup(query: String): Result = withContext(Dispatchers.IO) {
        val hits = relevant(query, runCatching { yahoo(query) }.onFailure { AppLog.d("web", "yahoo failed", it) }.getOrDefault(emptyList()))
            .ifEmpty { relevant(query, runCatching { duckDuckGo(query) }.getOrDefault(emptyList())) }
            .ifEmpty { relevant(query, runCatching { wikipedia(query) }.getOrDefault(emptyList())) }
        // read the top pages (skip video and files) for detail beyond the snippets
        val toRead = hits.filter { h -> listOf("youtube.com", "youtu.be", "tiktok.com", "instagram.com", "facebook.com", "reddit.com").none { h.url.contains(it) } && !h.url.endsWith(".pdf") }.take(2)
        val pages = coroutineScope {
            toRead.map { h -> async { runCatching { pageText(h.url, query) }.getOrNull()?.let { h to it } } }.awaitAll().filterNotNull()
        }
        AppLog.d("web", "\"$query\": ${hits.size} result(s) [${hits.take(3).joinToString { it.site }}], read ${pages.size} page(s)")
        Result(query, hits, pages).also { last = it }
    }

    // ---------------------------------------------------------------- search engines

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Drops result lists that don't mention the query at all (a search engine fobbing us off). */
    private fun relevant(q: String, hits: List<Hit>): List<Hit> {
        val terms = TextNorm.basic(q).split(' ').filter { it.length >= 4 }.toSet()
        if (terms.isEmpty() || hits.isEmpty()) return hits
        val ok = hits.count { h -> TextNorm.basic("${h.title} ${h.snippet} ${h.url}").split(' ').count { it in terms } >= minOf(2, terms.size) }
        return if (ok == 0) emptyList<Hit>().also { AppLog.d("web", "results didn't match the query — ignored") } else hits
    }

    fun yahoo(q: String): List<Hit> {
        val html = get("https://search.yahoo.com/search?p=${enc(q)}") ?: return emptyList()
        return html.split(Regex("<section class=\"dd [^\"]*\\balgo\\b[^\"]*\"")).drop(1).mapNotNull { raw ->
            val b = raw.take(8000)
            val a = Regex("<a class=\"s-title[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*aria-label=\"([^\"]*)\"").find(b)
                ?: Regex("(?s)<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*aria-label=\"([^\"]*)\"").find(b)
                ?: return@mapNotNull null
            var url = entities(a.groupValues[1])
            // links go through r.search.yahoo.com/…/RU=<the real url>/RK=…
            Regex("/RU=([^/]+)/R[KS]=").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (!url.startsWith("http") || url.contains("search.yahoo.com") || url.contains("/aclick")) return@mapNotNull null
            val snippet = (Regex("(?s)<p class=\"s-desc[^\"]*\"[^>]*>(.*?)</p>").find(b)
                ?: Regex("(?s)<div class=\"compText[^\"]*\"[^>]*>(.*?)</div>").find(b))?.let { text(it.groupValues[1]) }.orEmpty()
            Hit(entities(a.groupValues[2]).trim(), url, snippet)
        }.distinctBy { it.url }.take(6)
    }

    fun duckDuckGo(q: String): List<Hit> {
        val html = get("https://html.duckduckgo.com/html/?q=${enc(q)}") ?: return emptyList()
        return Regex("(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>")
            .findAll(html).mapNotNull { m ->
                var url = entities(m.groupValues[1])
                Regex("[?&]uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http") || url.contains("duckduckgo.com/y.js")) null
                else Hit(text(m.groupValues[2]), url, text(m.groupValues[3]))
            }.take(6).toList()
    }

    fun wikipedia(q: String): List<Hit> {
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val js = get("https://$lang.wikipedia.org/w/rest.php/v1/search/page?q=${enc(q)}&limit=3", ua = API_UA) ?: return emptyList()
        val pages = JSONObject(js).optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { i ->
            val p = pages.optJSONObject(i) ?: return@mapNotNull null
            val key = p.optString("key").ifBlank { return@mapNotNull null }
            Hit(p.optString("title", key), "https://$lang.wikipedia.org/wiki/${enc(key).replace("+", "_")}", text(p.optString("excerpt")))
        }
    }

    // ---------------------------------------------------------------- pages

    /** The readable paragraphs of a page, favouring ones that mention the query. */
    fun pageText(url: String, q: String, max: Int = 1100): String? {
        val html = get(url, timeoutMs = 6000, maxBytes = 900_000) ?: return null
        val body = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|header|footer|form|aside|button|select)[^>]*>.*?</\\1>"), " ")
        val paras = Regex("(?is)<(p|li|dd|blockquote)(?:\\s[^>]*)?>(.*?)</\\1>").findAll(body)
            .map { text(it.groupValues[2]) }
            .filter { it.length >= 50 && it.count { c -> c == ' ' } >= 7 && !Regex("(?i)cookie|subscribe|sign up|newsletter|all rights reserved|javascript").containsMatchIn(it) }
            .distinct().toList()
        if (paras.isEmpty()) return null
        val terms = TextNorm.basic(q).split(' ').filter { it.length >= 4 }.toSet()
        fun score(p: String) = TextNorm.basic(p).split(' ').count { it in terms }
        // the opening paragraphs explain the topic; then the ones that match the question best
        val chosen = LinkedHashSet<String>()
        paras.take(2).forEach { chosen += it }
        paras.drop(2).sortedByDescending(::score).filter { score(it) > 0 }.forEach { chosen += it }
        val out = StringBuilder()
        for (p in paras) {
            if (p !in chosen) continue
            if (out.length + p.length > max) { if (out.isEmpty()) out.append(p.take(max)); break }
            out.append(p).append(' ')
        }
        return out.toString().trim().takeIf { it.length >= 60 }
    }

    private fun firstSentences(t: String, n: Int = 2): String =
        Regex("(?<=[.!?])\\s+").split(t).take(n).joinToString(" ").take(400)

    // ---------------------------------------------------------------- http + html

    fun get(url: String, timeoutMs: Int = 7000, maxBytes: Int = 1_500_000, ua: String = UA): String? {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "${Locale.getDefault().toLanguageTag()},en;q=0.8")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    u = URL(u, loc); return@repeat
                }
                if (code !in 200..299) { AppLog.d("web", "HTTP $code for ${u.host}"); return null }
                val type = c.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && listOf("html", "json", "text", "xml").none { type.contains(it) }) return null
                val cs = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1)
                val buf = ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val b = ByteArray(16 * 1024)
                    while (buf.size() < maxBytes) { val n = ins.read(b); if (n <= 0) break; buf.write(b, 0, n) }
                }
                return String(buf.toByteArray(), runCatching { Charset.forName(cs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
            } finally {
                c.disconnect()
            }
        }
        return null
    }

    private fun text(html: String): String =
        entities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "middot" to "·", "copy" to "©", "reg" to "®", "deg" to "°")

    private fun entities(s: String): String = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
            else -> NAMED[e]
        } ?: m.value
    }
}

/** Headlines from Google News' public RSS feeds (top stories, a topic, or a search). No account or key. */
object News {
    data class Story(val title: String, val source: String, val time: Long, val url: String, val related: List<String>)

    @Volatile var last: List<Story> = emptyList()

    private val TOPICS = mapOf(
        "world" to "WORLD", "international" to "WORLD", "nation" to "NATION", "national" to "NATION", "us" to "NATION", "u.s." to "NATION",
        "business" to "BUSINESS", "economy" to "BUSINESS", "money" to "BUSINESS", "finance" to "BUSINESS", "markets" to "BUSINESS",
        "tech" to "TECHNOLOGY", "technology" to "TECHNOLOGY", "science" to "SCIENCE", "health" to "HEALTH",
        "sports" to "SPORTS", "sport" to "SPORTS", "entertainment" to "ENTERTAINMENT", "movies" to "ENTERTAINMENT",
    )

    suspend fun fetch(topic: String?): List<Story> = withContext(Dispatchers.IO) {
        val loc = Locale.getDefault()
        val hl = loc.toLanguageTag().ifBlank { "en-US" }
        val gl = loc.country.ifBlank { "US" }
        val ceid = "$gl:${loc.language.ifBlank { "en" }}"
        val t = topic?.trim()?.lowercase()?.removeSuffix(" news")?.takeIf { it.isNotBlank() && it !in setOf("latest", "recent", "top", "today", "todays", "today's", "the", "breaking") }
        val url = when {
            t == null -> "https://news.google.com/rss?hl=$hl&gl=$gl&ceid=$ceid"
            TOPICS[t] != null -> "https://news.google.com/rss/headlines/section/topic/${TOPICS[t]}?hl=$hl&gl=$gl&ceid=$ceid"
            else -> "https://news.google.com/rss/search?q=${URLEncoder.encode("$t when:7d", "UTF-8")}&hl=$hl&gl=$gl&ceid=$ceid"
        }
        val xml = WebSearch.get(url, ua = "NovaAssistant/1.0 (Android voice assistant)") ?: return@withContext emptyList()
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        Regex("(?s)<item>(.*?)</item>").findAll(xml).mapNotNull { m ->
            val it = m.groupValues[1]
            fun tag(n: String) = Regex("(?s)<$n[^>]*>(.*?)</$n>").find(it)?.groupValues?.get(1)?.let { v -> clean(v) }
            val source = tag("source").orEmpty()
            val title = tag("title")?.removeSuffix(" - $source")?.trim() ?: return@mapNotNull null
            val time = runCatching { fmt.parse(tag("pubDate").orEmpty())!!.time }.getOrDefault(0L)
            // the description lists other outlets' headlines on the same story — extra detail for the summary
            val related = Regex("(?s)<a [^>]*>(.*?)</a>").findAll(clean(Regex("(?s)<description>(.*?)</description>").find(it)?.groupValues?.get(1).orEmpty(), keepTags = true))
                .map { r -> clean(r.groupValues[1]) }.filter { r -> r.isNotBlank() && !r.equals(title, true) }.take(2).toList()
            Story(title, source, time, tag("link").orEmpty(), related)
        }.take(10).toList().also { last = it; AppLog.d("web", "news (${t ?: "top"}): ${it.size} stories") }
    }

    fun forModel(stories: List<Story>, topic: String?): String = buildString {
        append(if (topic.isNullOrBlank()) "Top news headlines right now:\n" else "News about $topic:\n")
        val now = System.currentTimeMillis()
        stories.forEachIndexed { i, s ->
            val age = if (s.time > 0) ((now - s.time) / 3_600_000L).let { h -> if (h < 1) "just now" else if (h < 48) "${h}h ago" else "${h / 24}d ago" } else ""
            append(i + 1).append(". ").append(s.title).append(" (").append(s.source).append(if (age.isNotEmpty()) ", $age" else "").append(")")
            if (s.related.isNotEmpty()) append(" — also: ").append(s.related.joinToString("; "))
            append('\n')
        }
    }.take(3000)

    private fun clean(v: String, keepTags: Boolean = false): String {
        var s = v.replace("<![CDATA[", "").replace("]]>", "")
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
        if (!keepTags) s = s.replace(Regex("<[^>]+>"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    val tool = Tool(
        "news", "topic?:str e.g. world, business, tech, sports, or any subject",
        "Current news headlines (top stories, a section, or news about something). Use this for any news request",
        "Info", "Getting the news…",
    ) { a ->
        val topic = a.str("topic", "query", "about", "subject", "category")
        val stories = News.fetch(topic)
        if (stories.isEmpty()) return@Tool fail("I couldn't get the news right now.")
        val plain = "Here are the top stories: " + stories.take(3).joinToString(". ") { "${it.title}, from ${it.source}" } + "."
        ToolOutcome(
            plain, data = forModel(stories, topic) + "\nTell the user the 4 or 5 most important stories in a few natural spoken sentences (what happened, not just the outlet names).",
            needsModel = true, preferModel = true, note = "Headlines from Google News",
        )
    }
}
