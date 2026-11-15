package app.nova.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A word Nova should hear correctly: what you mean, plus the ways speech recognition mishears it. */
data class CustomWord(val word: String, val note: String = "", val aliases: List<String> = emptyList())

/**
 * Your own vocabulary (names, acronyms, jargon). Android's recognizer can't be retrained, so Nova learns how it
 * mis-hears each word (by listening to you say it) and fixes those mishearings in what you said. The words are
 * also sent to the recognizer as biasing hints (Android 13+) and listed for the AI model.
 */
class CustomWords(private val settings: Settings) {
    private val _words = MutableStateFlow(load())
    val words: StateFlow<List<CustomWord>> = _words

    private fun load(): List<CustomWord> = runCatching {
        val a = JSONArray(settings[K.customWords])
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val al = o.optJSONArray("aliases") ?: JSONArray()
            CustomWord(o.getString("word"), o.optString("note"), List(al.length()) { al.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<CustomWord>) {
        _words.value = list
        val a = JSONArray()
        list.forEach { w -> a.put(JSONObject().put("word", w.word).put("note", w.note).put("aliases", JSONArray(w.aliases))) }
        settings[K.customWords] = a.toString()
        compiled = null
    }

    fun upsert(w: CustomWord) {
        val cleaned = w.copy(word = w.word.trim(), note = w.note.trim(),
            aliases = w.aliases.map { TextNorm.basic(it) }.filter { it.isNotBlank() && it != TextNorm.basic(w.word) }.distinct())
        save(_words.value.filterNot { it.word.equals(cleaned.word, true) } + cleaned)
    }

    fun remove(word: String) = save(_words.value.filterNot { it.word.equals(word, true) })

    fun removeAlias(word: String, alias: String) {
        _words.value.firstOrNull { it.word == word }?.let { upsert(it.copy(aliases = it.aliases - alias)) }
    }

    /** Spoken forms that don't need learning: "MSOE" → "m s o e", "msoe". */
    fun builtInAliases(word: String): List<String> {
        val w = word.trim()
        val out = mutableListOf<String>()
        if (w.length in 2..8 && w.all { it.isLetter() } && w == w.uppercase()) {
            out += w.lowercase().toList().joinToString(" ")
            out += w.lowercase()
        }
        return out.filter { it != TextNorm.basic(w) }
    }

    /** Common words never auto-learned as corrections (they'd fire on everyday speech). */
    private val tooCommon = setOf(
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "at", "it", "is", "are", "was", "be", "i", "you", "he", "she",
        "we", "they", "me", "my", "your", "this", "that", "what", "so", "no", "not", "yes", "yeah", "okay", "ok", "oh", "hey",
        "hi", "go", "get", "do", "can", "will", "for", "with", "as", "but", "if", "up", "out", "now", "here", "there", "one",
        "two", "too", "to", "for", "four", "see", "sea", "know", "no", "new", "time", "call", "text", "play", "open", "set",
    )

    /** Picks corrections from what the recognizer heard while you said [word] (only consistent, non-common ones). */
    fun learnedAliases(word: String, heard: List<List<String>>): List<String> {
        val target = TextNorm.basic(word)
        val counts = HashMap<String, Int>()
        heard.forEach { attempt ->
            attempt.map { TextNorm.basic(it) }.filter { it.isNotBlank() }.distinct().forEachIndexed { i, h ->
                // the recognizer's top guess always counts; lower alternatives need to repeat across attempts
                counts[h] = (counts[h] ?: 0) + if (i == 0) 2 else 1
            }
        }
        return counts.filter { (h, n) -> h != target && n >= 2 && h !in tooCommon && h.length >= 2 }
            .keys.sortedByDescending { counts[it] }.take(8)
    }

    private class Rule(val regex: Regex, val word: String)
    @Volatile private var compiled: List<Rule>? = null

    private fun rules(): List<Rule> = compiled ?: _words.value.flatMap { w ->
        (w.aliases + builtInAliases(w.word)).distinct().filter { it.isNotBlank() }.map { alias ->
            // words of the alias, separated by any spaces/dots/dashes: "m s o e" also matches "M.S.O.E."
            val raw = alias.split(' ').filter { it.isNotEmpty() }
            val parts = raw.map { Regex.escape(it) }
            // spelled-out letters ("m s o e") may end with a dot too: "M.S.O.E."
            val tail = if (raw.size > 1 && raw.all { it.length == 1 }) "\\.?" else ""
            Rule(Regex("(?i)(?<![\\p{L}\\p{N}])" + parts.joinToString("[\\s.,'-]*") + tail + "(?![\\p{L}\\p{N}])"), w.word) to alias.length
        }
    }.sortedByDescending { it.second }.map { it.first }.also { compiled = it }

    /** Replaces learned mishearings with your words. */
    fun correct(text: String): String {
        var t = text
        for (r in rules()) t = r.regex.replace(t, Regex.escapeReplacement(r.word))
        return t
    }

    fun biasing(): ArrayList<String> = ArrayList(_words.value.map { it.word })

    /** For the AI prompt. */
    fun promptLines(): String = _words.value.joinToString("\n") { w -> "- ${w.word}" + (w.note.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") }
}
