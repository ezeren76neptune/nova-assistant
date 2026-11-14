package app.nova.assistant.core

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Long-term facts ("remember that..."), notes, and a log of past exchanges. */
class MemoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nova_memory", Context.MODE_PRIVATE)

    private val _facts = MutableStateFlow(loadList("facts"))
    val facts: StateFlow<List<String>> = _facts
    private val _notes = MutableStateFlow(loadList("notes"))
    val notes: StateFlow<List<String>> = _notes
    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<Pair<String, String>>> = _history

    private fun loadList(key: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveList(key: String, l: List<String>) = prefs.edit { putString(key, JSONArray(l).toString()) }

    private fun loadHistory(): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        List(a.length()) { val o = a.getJSONObject(it); o.getString("u") to o.getString("a") }
    }.getOrDefault(emptyList())

    fun addFact(f: String) {
        val l = (_facts.value + f.trim()).distinct().takeLast(100)
        _facts.value = l; saveList("facts", l)
    }

    /** Removes facts containing the keyword; returns how many were removed. */
    fun forget(keyword: String): Int {
        val k = keyword.lowercase().trim()
        val keep = _facts.value.filterNot { it.lowercase().contains(k) }
        val n = _facts.value.size - keep.size
        _facts.value = keep; saveList("facts", keep)
        return n
    }

    fun setFacts(l: List<String>) { _facts.value = l; saveList("facts", l) }

    fun addNote(n: String) {
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date())
        val l = (_notes.value + "$stamp — ${n.trim()}").takeLast(300)
        _notes.value = l; saveList("notes", l)
    }

    fun setNotes(l: List<String>) { _notes.value = l; saveList("notes", l) }

    fun logExchange(user: String, assistant: String) {
        val l = (_history.value + (user to assistant)).takeLast(200)
        _history.value = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("u", it.first).put("a", it.second)) }
        prefs.edit { putString("history", a.toString()) }
    }

    fun clearHistory() { _history.value = emptyList(); prefs.edit { putString("history", "[]") } }
}
