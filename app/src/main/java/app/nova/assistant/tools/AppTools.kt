package app.nova.assistant.tools

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.TextNorm
import app.nova.assistant.core.ToolOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cache of launchable apps for fuzzy "open X". */
object AppIndex {
    data class Entry(val label: String, val pkg: String, val norm: String)

    private var cache: List<Entry> = emptyList()
    private var loadedAt = 0L

    fun norm(s: String): String = TextNorm.basic(s)
        .replace(Regex("\\b(the|my|app|application|apps)\\b"), " ")
        .replace(" ", "")

    @Synchronized
    @Suppress("DEPRECATION")
    fun apps(ctx: Context): List<Entry> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - loadedAt < 5 * 60_000) return cache
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(main, 0)
            .map { val label = it.loadLabel(pm).toString(); Entry(label, it.activityInfo.packageName, norm(label)) }
            .filter { it.norm.isNotEmpty() }
            .distinctBy { it.pkg }
        loadedAt = System.currentTimeMillis()
        return cache
    }

    /** Best match with its score (0..1), or null. */
    fun match(ctx: Context, name: String): Pair<Entry, Float>? {
        val q = norm(name)
        if (q.isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0f
        for (e in apps(ctx)) {
            val s = when {
                e.norm == q -> 1f
                e.norm.startsWith(q) -> 0.85f
                q.startsWith(e.norm) && e.norm.length >= 4 -> 0.8f
                e.norm.contains(q) && q.length >= 3 -> 0.75f
                else -> TextNorm.similarity(e.norm, q).let { if (it >= 0.75f) it * 0.9f else 0f }
            }
            if (s > bestScore || (s == bestScore && best != null && e.label.length < best.label.length)) {
                best = e; bestScore = s
            }
        }
        return if (best != null && bestScore >= 0.6f) best to bestScore else null
    }

    fun invalidate() { loadedAt = 0 }
}

object AppTools {
    private const val MAPS = "com.google.android.apps.maps"

    /** Generic app categories → intent, used when no app label matches well. */
    private fun synonym(ctx: Context, name: String): Pair<String, Intent>? {
        val n = TextNorm.basic(name).removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        fun sel(cat: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat)
        return when (n) {
            "camera", "the camera", "camera app" -> "the camera" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .apply { cameraPkg(ctx, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)?.let { setPackage(it) } }
            "phone", "dialer", "dial pad", "keypad" -> "the dialer" to Intent(Intent.ACTION_DIAL)
            "messages", "messaging", "sms", "texts", "text messages" ->
                "messages" to (Telephony.Sms.getDefaultSmsPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: sel(Intent.CATEGORY_APP_MESSAGING))
            "browser", "web browser", "internet", "web" -> "the browser" to
                (defaultBrowser(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) } ?: sel(Intent.CATEGORY_APP_BROWSER))
            "maps", "map", "navigation" -> "maps" to sel(Intent.CATEGORY_APP_MAPS)
            "gallery", "photos", "pictures", "my photos" -> "your photos" to sel(Intent.CATEGORY_APP_GALLERY)
            "settings", "system settings" -> "settings" to Intent(Settings.ACTION_SETTINGS)
            "calculator" -> "the calculator" to sel(Intent.CATEGORY_APP_CALCULATOR)
            "clock", "alarms", "alarm", "timer", "timers" -> "the clock" to Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "calendar" -> "your calendar" to sel(Intent.CATEGORY_APP_CALENDAR)
            "contacts", "people" -> "contacts" to sel(Intent.CATEGORY_APP_CONTACTS)
            "email", "mail", "inbox" -> "email" to sel(Intent.CATEGORY_APP_EMAIL)
            "music", "music player" -> "music" to
                (musicAppFor(ctx, null)?.let { ctx.packageManager.getLaunchIntentForPackage(it.pkg) } ?: sel(Intent.CATEGORY_APP_MUSIC))
            "play store", "app store", "store", "google play" -> "the Play Store" to Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q="))
            else -> null
        }
    }

    private fun playFromSearch(q: String, pkg: String?) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        .putExtra(SearchManager.QUERY, q)
        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        .apply { if (pkg != null) setPackage(pkg) }

    /**
     * The phone's camera app: your default if you set one, otherwise the preinstalled (system) camera —
     * so "take a picture" never shows an "Open with" picker (Snapchat, Instagram… also accept camera intents).
     */
    fun cameraPkg(ctx: android.content.Context, action: String): String? {
        val pm = ctx.packageManager
        val probe = Intent(action)
        pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { info ->
            if (info.packageName != "android" && !info.name.contains("Resolver")) return info.packageName
        }
        fun handlers(act: String) = pm.queryIntentActivities(Intent(act), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
        fun isSystem(i: android.content.pm.ActivityInfo) = (i.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val all = handlers(action) + handlers(MediaStore.ACTION_IMAGE_CAPTURE)
        val system = all.filter(::isSystem)
        return (system.firstOrNull { it.packageName.contains("camera", true) }
            ?: system.firstOrNull()
            ?: all.firstOrNull { it.packageName.contains("camera", true) })?.packageName
    }

    /** The app web links open in (Android's default browser), or null if none is set. */
    fun defaultBrowser(ctx: android.content.Context): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val info = ctx.packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return null
        // no default chosen → Android resolves to its own chooser ("android" / ResolverActivity)
        if (info.packageName == "android" || info.name.contains("Resolver")) return null
        return info.packageName
    }

    private const val YOUTUBE = "com.google.android.youtube"
    /** Stock YouTube or a patched build (ReVanced / RVX) — whichever is installed and enabled. */
    private val YOUTUBE_PKGS = listOf(YOUTUBE, "app.revanced.android.youtube", "app.rvx.android.youtube", "com.vanced.android.youtube")
    fun youtubePkg(ctx: android.content.Context): String =
        YOUTUBE_PKGS.firstOrNull { installed(ctx, it) } ?: YOUTUBE

    data class MusicApp(val key: String, val pkg: String, val label: String)

    /** Known music apps, in the order "Automatic" prefers them. */
    val MUSIC_APPS = listOf(
        MusicApp("spotify", "com.spotify.music", "Spotify"),
        MusicApp("youtube_music", "com.google.android.apps.youtube.music", "YouTube Music"),
        MusicApp("apple_music", "com.apple.android.music", "Apple Music"),
        MusicApp("amazon_music", "com.amazon.mp3", "Amazon Music"),
        MusicApp("deezer", "deezer.android.app", "Deezer"),
        MusicApp("tidal", "com.aspiro.tidal", "Tidal"),
        MusicApp("pandora", "com.pandora.android", "Pandora"),
        MusicApp("soundcloud", "com.soundcloud.android", "SoundCloud"),
        MusicApp("youtube", YOUTUBE, "YouTube"),
    )

    /** Words that mean "just play music" rather than a specific song. */
    private val GENERIC_MUSIC = setOf(
        "", "music", "some music", "my music", "something", "anything", "a song", "songs", "some songs", "tunes",
        "some tunes", "my songs", "it", "spotify", "youtube music", "yt music", "apple music", "amazon music",
    )

    private fun installed(ctx: android.content.Context, pkg: String) =
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    /**
     * Which app to play in. A named app wins; "auto" picks the first installed music app (no chooser);
     * "ask" (or nothing installed) returns null = let Android ask.
     */
    fun musicAppFor(ctx: android.content.Context, name: String?): MusicApp? {
        val n = TextNorm.basic(name ?: "auto").replace(' ', '_')
        if (n == "ask") return null
        MUSIC_APPS.firstOrNull { n == it.key || n.replace("_", "") == it.key.replace("_", "") || TextNorm.basic(it.label).replace(' ', '_') == n }
            ?.let { m ->
                val real = if (m.key == "youtube") m.copy(pkg = youtubePkg(ctx)) else m
                if (installed(ctx, real.pkg)) return real
            }
        // "auto" / "default" / unknown / named app not installed → best installed music app (YouTube only as last resort)
        return MUSIC_APPS.firstOrNull { it.key != "youtube" && installed(ctx, it.pkg) }
            ?: MusicApp("youtube", youtubePkg(ctx), "YouTube").takeIf { installed(ctx, it.pkg) }
    }

    private fun youtubeUrl(q: String) = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))

    /** Opens a search in the browser (or the Google app), per Settings → Web search. */
    fun ToolContext.openSearch(q: String): ToolOutcome {
        val engine = settings[K.searchEngine]
        val browser = defaultBrowser(ctx)
        when (engine) {
            // default: the phone's default browser, with that browser's own search engine — no app picker
            "browser" -> if (browser != null &&
                launch(Intent(Intent.ACTION_WEB_SEARCH).setPackage(browser).putExtra(SearchManager.QUERY, q))
            ) return ToolOutcome("Searching for $q.", dismiss = true)
            // Google app (falls back to Android's picker if it isn't there)
            "system" -> if (launch(Intent(Intent.ACTION_WEB_SEARCH).setPackage("com.google.android.googlequicksearchbox").putExtra(SearchManager.QUERY, q)) ||
                launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
            ) return ToolOutcome("Searching for $q.", dismiss = true)
        }
        val base = when (engine) {
            "duckduckgo" -> "https://duckduckgo.com/?q="
            "bing" -> "https://www.bing.com/search?q="
            "perplexity" -> "https://www.perplexity.ai/search?q="
            else -> "https://www.google.com/search?q="
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(base + Uri.encode(q))).addCategory(Intent.CATEGORY_BROWSABLE)
        return if ((browser != null && launch(Intent(view).setPackage(browser))) || launch(view)) ToolOutcome("Searching for $q.", dismiss = true)
        else fail("I couldn't open a browser.")
    }

    val tools: List<Tool> = listOf(
        Tool("open_app", "name:str", "Open an app by name", "Apps", "Opening…") { a ->
            val name = a.str("name", "app", "app_name", "package") ?: return@Tool fail("Which app?")
            val pm = ctx.packageManager
            val m = AppIndex.match(ctx, name)
            if (m != null && m.second >= 0.85f) {
                pm.getLaunchIntentForPackage(m.first.pkg)?.let { if (launch(it)) return@Tool ToolOutcome("Opening ${m.first.label}.", dismiss = true) }
            }
            synonym(ctx, name)?.let { (label, intent) ->
                if (launch(intent)) return@Tool ToolOutcome("Opening $label.", dismiss = true)
            }
            if (m != null) {
                pm.getLaunchIntentForPackage(m.first.pkg)?.let { if (launch(it)) return@Tool ToolOutcome("Opening ${m.first.label}.", dismiss = true) }
            }
            AppIndex.invalidate()
            fail("I couldn't find an app called $name.")
        },

        Tool(
            "web_search", "query:str, open?:bool",
            "Look something up on the internet and get the results back to explain to the user. open=true only if they want the browser",
            "Apps", "Searching the web…",
        ) { a ->
            val q = a.str("query", "q", "text", "search") ?: return@Tool fail("What should I search for?")
            if (a.bool("open", "browser") != true && settings[K.searchMode] == "speak") {
                val r = WebSearch.lookup(q)
                if (r.hits.isEmpty()) return@Tool fail("I couldn't get search results right now. Check the connection, or say \"open a search for $q\" to use the browser.")
                return@Tool ToolOutcome(
                    r.plainAnswer(), data = r.forModel(), needsModel = true, preferModel = true,
                    note = "Searched the web: ${r.sources()}",
                )
            }
            openSearch(q)
        },

        Tool("open_url", "url:str", "Open a website", "Apps") { a ->
            var url = a.str("url", "link", "address", "site") ?: return@Tool fail("Which website?")
            url = url.trim()
            if (!url.contains("://")) url = "https://" + url.replace(" ", "")
            val uri = Uri.parse(url)
            val view = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            val browser = defaultBrowser(ctx)
            if ((browser != null && launch(Intent(view).setPackage(browser))) || launch(view)) ToolOutcome("Opening ${uri.host?.removePrefix("www.") ?: url}.", dismiss = true)
            else fail("I couldn't open that link.")
        },

        Tool(
            "play_media",
            "query?:str song/artist/playlist/podcast (empty = just start the music app), app?:str spotify|youtube_music|youtube|apple_music|amazon_music|deezer|tidal|pandora|soundcloud",
            "Play music, a song, artist, playlist, podcast or video (or just start/resume the music app)", "Apps", "Finding it…",
        ) { a ->
            val q = a.str("query", "song", "name", "what", "title", "artist")?.trim()
                ?.takeUnless { TextNorm.basic(it) in GENERIC_MUSIC } ?: ""
            val asked = a.str("app", "service", "source", "provider")
            val app = musicAppFor(ctx, asked ?: settings[K.musicApp])
            when (app?.key) {
                null -> {
                    // "Ask every time": let Android show its chooser
                    if (launch(playFromSearch(q, null))) return@Tool ToolOutcome(if (q.isEmpty()) "Pick an app to play music." else "Playing $q.", dismiss = true)
                }
                "youtube" -> {
                    if (q.isEmpty()) {
                        ctx.packageManager.getLaunchIntentForPackage(app.pkg)?.let { if (launch(it)) return@Tool ToolOutcome("Opening YouTube.", dismiss = true) }
                    } else {
                        val yt = Intent(Intent.ACTION_SEARCH).setPackage(app.pkg).putExtra("query", q)
                        if (launch(yt) || launch(youtubeUrl(q))) return@Tool ToolOutcome("Here's $q on YouTube.", dismiss = true)
                    }
                }
                else -> {
                    val pkg = app.pkg
                    // Spotify + a specific request: pick the result in Spotify's UI (it ignores search intents)
                    if (pkg == SpotifyAuto.PKG && q.isNotEmpty() && a11y != null && settings[K.uiAutomation]) {
                        val nova = this.app
                        nova.appScope.launch {
                            val got = SpotifyAuto.play(nova, q) { launch(it) }
                            if (got == null) {
                                AppLog.d("spotify", "automation failed for '$q' — leaving Spotify's search open")
                                launch(playFromSearch(q, pkg))
                            }
                        }
                        return@Tool ToolOutcome("Playing $q on Spotify.", dismiss = true, quiet = true)
                    }
                    val session = MediaPlayback.controllerFor(pkg)
                    val started = when {
                        // already running: drive its player directly (no UI needed)
                        session != null && q.isEmpty() -> { session.transportControls.play(); true }
                        session != null -> { session.transportControls.playFromSearch(q, android.os.Bundle()); true }
                        else -> launch(playFromSearch(q, pkg)) ||
                            (ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(it) } ?: false)
                    }
                    if (started) {
                        // opening the app isn't the same as playing: keep pressing play until it really plays
                        val nova = this.app
                        nova.appScope.launch {
                            MediaPlayback.ensurePlaying(nova, pkg, q) { launch(playFromSearch(q, pkg)) }
                        }
                        return@Tool ToolOutcome(
                            if (q.isEmpty()) "Playing music on ${app.label}." else "Playing $q on ${app.label}.",
                            dismiss = true, quiet = true,
                        )
                    }
                }
            }
            if (q.isNotEmpty() && launch(youtubeUrl(q))) return@Tool ToolOutcome("Here's $q on YouTube.", dismiss = true)
            fail("I couldn't find a music app to play that.")
        },

        Tool("navigate", "destination:str, mode?:str driving|walking|transit|bicycling", "Start turn-by-turn directions", "Apps", "Getting directions…") { a ->
            var dest = a.str("destination", "to", "place", "address", "query", "location") ?: return@Tool fail("Where to?")
            val d = TextNorm.basic(dest).removePrefix("my ")
            if (d == "home" || d == "house") settings[K.homeAddress].takeIf { it.isNotBlank() }?.let { dest = it }
            if (d == "work" || d == "office" || d == "job") settings[K.workAddress].takeIf { it.isNotBlank() }?.let { dest = it }
            val mode = a.str("mode", "by", "travel_mode", "travelmode")?.lowercase() ?: "driving"
            val q = Uri.encode(dest)
            if (mode.contains("transit") || mode.contains("bus") || mode.contains("train") || mode.contains("public") || mode.contains("subway")) {
                val u = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=transit")
                if (launch(Intent(Intent.ACTION_VIEW, u).setPackage(MAPS)) || launch(Intent(Intent.ACTION_VIEW, u))) {
                    return@Tool ToolOutcome("Getting transit directions to $dest.", dismiss = true)
                }
            }
            val m = when {
                mode.contains("walk") || mode.contains("foot") -> "w"
                mode.contains("bik") || mode.contains("cycl") -> "b"
                else -> "d"
            }
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=$m")).setPackage(MAPS))) {
                return@Tool ToolOutcome("Starting directions to $dest.", dismiss = true)
            }
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))) ToolOutcome("Here's $dest on the map.", dismiss = true)
            else fail("I couldn't find a maps app.")
        },

        Tool("show_map", "query:str", "Show a place or 'X near me' on the map", "Apps") { a ->
            val q = a.str("query", "place", "location", "destination") ?: return@Tool fail("What should I look for?")
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(q)}")))) ToolOutcome("Here's $q on the map.", dismiss = true)
            else fail("I couldn't find a maps app.")
        },

        Tool("take_photo", "selfie?:bool, video?:bool", "Open the camera for a photo, selfie or video", "Apps") { a ->
            val video = a.bool("video", "record") == true
            val selfie = a.bool("selfie", "front", "front_camera") == true
            val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            if (selfie) {
                i.putExtra("android.intent.extras.CAMERA_FACING", 1)
                    .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
            val cam = cameraPkg(ctx, i.action!!)
            val opened = (cam != null && launch(Intent(i).setPackage(cam))) ||
                (cam != null && ctx.packageManager.getLaunchIntentForPackage(cam)?.let { launch(it) } == true) ||
                launch(i)
            if (!opened) return@Tool fail("I couldn't open the camera.")
            val svc = a11y
            val auto = settings[K.autoShutter] && svc != null && !video
            if (auto && svc != null) {
                app.appScope.launch {
                    delay(3000)
                    svc.autoClick(null, emptyList(), listOf("Shutter", "Take photo", "Take picture", "Capture", "Camera shutter"), 5000)
                }
            }
            ToolOutcome(
                when {
                    video -> "Opening the video camera."
                    auto -> "Say cheese — taking it in three seconds."
                    selfie -> "Opening the selfie camera."
                    else -> "Opening the camera."
                },
                dismiss = true,
            )
        },

        Tool("copy_to_clipboard", "text:str", "Copy text to the clipboard", "Apps") { a ->
            val t = a.str("text", "content", "value") ?: return@Tool fail("What should I copy?")
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nova", t))
            ok("Copied to the clipboard.")
        },
    )
}
