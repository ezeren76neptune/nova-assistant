package app.nova.assistant.tools

/** Searches Nova's private on-device index: notifications, texts and calendar. */
object SearchTools {
    val tools = listOf(
        Tool(
            "search_phone", "query:str keywords, e.g. \"dentist appointment\"",
            "Search the user's recent notifications, text messages and calendar on this phone (addresses, times, codes, who said what)",
            "Info", "Searching your phone…",
        ) { a ->
            val q = a.str("query", "q", "text", "keywords") ?: return@Tool fail("What should I look for?")
            val hits = app.index.search(q, 8)
            if (hits.isEmpty()) return@Tool ok("I didn't find anything about \"$q\" in your notifications, texts or calendar.", needsModel = true)
            ok(hits.first().line(), data = hits.joinToString("\n") { it.line() }, needsModel = true)
        },
        News.tool,
    )
}
