package app.nova.assistant.tools

import app.nova.assistant.core.K
import app.nova.assistant.core.ToolOutcome

/** Long-term memory ("remember that…") and quick notes. */
object MemoryTools {
    val tools = listOf(
        Tool("remember", "fact:str", "Save a fact about the user for future conversations", "Memory") { a ->
            val f = a.str("fact", "text", "info", "memory", "note", "content")
            when {
                !settings[K.memoryEnabled] -> fail("Memory is turned off in Nova's settings.")
                f == null -> ToolOutcome("What should I remember?", ask = { ans -> saveFact(ans) })
                else -> saveFact(f)
            }
        },
        Tool("forget", "fact:str", "Delete remembered facts containing a keyword", "Memory") { a ->
            val k = a.str("fact", "text", "keyword", "about", "memory")
            if (k == null) ToolOutcome("What should I forget?", ask = { ans -> forget(ans) }) else forget(k)
        },
        Tool("list_memories", "", "List what you remember about the user", "Memory") {
            val f = app.memory.facts.value
            if (f.isEmpty()) ok("I don't have anything saved about you yet.")
            else ok(
                "Here's what I remember: " + f.takeLast(5).joinToString("; ") + ".",
                data = f.joinToString("\n"), needsModel = true,
            )
        },
        Tool("save_note", "text:str", "Save a note for the user", "Memory") { a ->
            val t = a.str("text", "note", "content", "message", "body")
            if (t == null) ToolOutcome("What should the note say?", ask = { ans -> saveNote(ans) }) else saveNote(t)
        },
        Tool("list_notes", "", "Read the user's saved notes", "Memory") {
            val n = app.memory.notes.value.asReversed()
            if (n.isEmpty()) ok("You don't have any notes yet.")
            else ok(
                "Your latest note${if (n.size == 1) "" else "s"}: " + n.take(3).joinToString("; ") { it.substringAfter(" — ") } + ".",
                data = n.take(30).joinToString("\n"), needsModel = true,
            )
        },
    )

    private fun ToolContext.saveFact(f: String): ToolOutcome {
        val clean = f.trim().removePrefix("that ").trim()
        if (clean.isEmpty()) return fail("I didn't catch what to remember.")
        app.memory.addFact(clean)
        return ok("Got it, I'll remember that.")
    }

    private fun ToolContext.forget(k: String): ToolOutcome {
        val n = app.memory.forget(k.trim().removePrefix("about ").removePrefix("that ").trim())
        return ok(
            when (n) {
                0 -> "I didn't have anything saved about that."
                1 -> "Okay, I forgot that."
                else -> "Okay, I forgot $n things."
            },
        )
    }

    private fun ToolContext.saveNote(t: String): ToolOutcome {
        if (t.isBlank()) return fail("I didn't catch the note.")
        app.memory.addNote(t)
        return ok("Saved your note.")
    }
}
