package app.nova.assistant.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.KeyEvent
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.TextNorm
import android.view.accessibility.AccessibilityNodeInfo
import app.nova.assistant.service.AssistAccessibilityService
import app.nova.assistant.service.NotificationWatcher
import kotlinx.coroutines.delay

/**
 * Makes music actually start. Opening a music app (or a "play from search" intent) often just shows the
 * app without pressing play — especially from a cold start. This drives the app's own media session
 * (via the notification-listener access) until it reports PLAYING, with media-button fallbacks.
 */
object MediaPlayback {
    fun controllerFor(pkg: String): MediaController? =
        NotificationWatcher.instance?.mediaControllers()?.firstOrNull { it.packageName == pkg }

    private fun isPlaying(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun isLoading(c: MediaController?) = c?.playbackState?.state in setOf(
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    private val filler = setOf("the", "by", "song", "songs", "playlist", "album", "music", "some", "my", "a", "on", "from", "radio", "station")

    /** Does what's playing look like what was asked for? */
    private fun matches(c: MediaController, query: String): Boolean {
        val md = c.metadata ?: return false
        val text = TextNorm.basic(
            listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                .mapNotNull { md.getString(it) }.joinToString(" "),
        )
        val words = TextNorm.basic(query).split(' ').filter { it.length >= 3 && it !in filler }
        return words.isEmpty() || words.any { text.contains(it) }
    }

    fun sendMediaButton(ctx: Context, pkg: String, code: Int) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching {
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code)))
            }
        }
    }

    /**
     * Keeps nudging [pkg] until it plays ([query] if given, else whatever it had). [openSearch] re-sends the
     * app's play-from-search intent (used if it's playing the wrong thing).
     */
    suspend fun ensurePlaying(app: NovaApp, pkg: String, query: String, openSearch: () -> Boolean) {
        val start = System.currentTimeMillis()
        var plays = 0
        var searched = false
        var reopened = false
        var buttoned = false
        delay(900)
        while (System.currentTimeMillis() - start < 11_000) {
            val c = controllerFor(pkg)
            val elapsed = System.currentTimeMillis() - start
            when {
                c != null && isPlaying(c) -> {
                    if (query.isBlank() || matches(c, query) || (searched && reopened)) {
                        AppLog.d("media", "$pkg playing after ${elapsed} ms")
                        return
                    }
                    // it resumed something else — ask for the right thing
                    if (!searched) { runCatching { c.transportControls.playFromSearch(query, Bundle()) }; searched = true }
                    else if (!reopened) { openSearch(); reopened = true }
                }
                c != null && isLoading(c) -> {}
                c != null -> {
                    if (query.isNotBlank()) {
                        // a specific request: only press play if the right thing is loaded — never resume the old queue
                        if (matches(c, query) && plays < 3) { runCatching { c.transportControls.play() }; plays++ }
                        else if (!searched) { runCatching { c.transportControls.playFromSearch(query, Bundle()) }; searched = true }
                    } else if (plays < 3) {
                        runCatching { c.transportControls.play() }; plays++
                    }
                }
                query.isBlank() && !buttoned && elapsed > 2500 -> {
                    // no media session yet (cold start, or no notification access): wake the app's player
                    sendMediaButton(app, pkg, KeyEvent.KEYCODE_MEDIA_PLAY); buttoned = true
                }
            }
            delay(650)
        }
        if (query.isBlank() && !isPlaying(controllerFor(pkg))) {
            AppLog.d("media", "$pkg still not playing — sending system play key")
            val am = app.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
    }
}

/**
 * Spotify ignores "play from search" from other apps (it only opens the search page), so this drives
 * Spotify's own UI through the accessibility service: search → pick the right result → press its Play.
 */
object SpotifyAuto {
    const val PKG = "com.spotify.music"

    enum class Kind { ANY, PLAYLIST, ARTIST, SONG, ALBUM, PODCAST }

    /** "my workout playlist" → (PLAYLIST, "workout"), "songs by daft punk" → (ARTIST, "daft punk"). */
    fun classify(raw: String): Pair<Kind, String> {
        var q = TextNorm.basic(raw)
        fun strip(vararg words: String) { words.forEach { q = q.replace(Regex("\\b$it\\b"), " ") }; q = q.replace(Regex("\\s+"), " ").trim() }
        val kind = when {
            Regex("\\bplaylists?\\b").containsMatchIn(q) -> Kind.PLAYLIST.also { strip("playlists?", "the", "my", "a") }
            Regex("\\balbum\\b").containsMatchIn(q) -> Kind.ALBUM.also { strip("album", "the") }
            Regex("\\b(podcast|episode)\\b").containsMatchIn(q) -> Kind.PODCAST.also { strip("podcast", "episode", "the", "latest") }
            Regex("^(songs|music|tracks|stuff|something) (by|from)\\b").containsMatchIn(q) ->
                Kind.ARTIST.also { q = q.replace(Regex("^(songs|music|tracks|stuff|something) (by|from) "), "") }
            Regex("^(the )?artist\\b").containsMatchIn(q) -> Kind.ARTIST.also { strip("the", "artist") }
            Regex("^(the )?(song|track)\\b").containsMatchIn(q) -> Kind.SONG.also { q = q.replace(Regex("^(the )?(song|track) "), "") }
            else -> Kind.ANY
        }
        return kind to q.ifBlank { TextNorm.basic(raw) }
    }

    private class Row(val node: AccessibilityNodeInfo, val title: String, val subtitle: String)

    private fun childText(n: AccessibilityNodeInfo, idSuffix: String): String? {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.viewIdResourceName?.endsWith(":id/$idSuffix") == true) return c.text?.toString()
        }
        return null
    }

    private fun rows(a: AssistAccessibilityService): List<Row> {
        val out = mutableListOf<Row>()
        a.roots(PKG).forEach { root ->
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || out.size > 40) return
                if (n.viewIdResourceName?.endsWith(":id/row_root") == true && n.isVisibleToUser) {
                    val t = childText(n, "title"); val s = childText(n, "subtitle")
                    if (t != null && s != null) out += Row(n, t, s)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
        }
        return out
    }

    private fun typeOf(sub: String) = sub.substringBefore('•').trim().lowercase()

    private fun pick(rows: List<Row>, kind: Kind): Row? {
        val want = when (kind) {
            Kind.PLAYLIST -> setOf("playlist")
            Kind.ARTIST -> setOf("artist")
            Kind.SONG -> setOf("song")
            Kind.ALBUM -> setOf("album", "single", "ep")
            Kind.PODCAST -> setOf("podcast", "episode", "show")
            Kind.ANY -> null
        }
        if (want != null) return rows.firstOrNull { typeOf(it.subtitle) in want }
        // top result — but a genre/mood or profile page has no Play button, so take the first playlist then
        val top = rows.firstOrNull() ?: return null
        return if (typeOf(top.subtitle) in setOf("genre", "mood", "profile", "category"))
            rows.firstOrNull { typeOf(it.subtitle) == "playlist" } ?: rows.getOrNull(1)
        else top
    }

    private fun insideNowPlayingBar(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        var d = 0
        while (p != null && d++ < 12) {
            if (p.viewIdResourceName?.let { it.contains("now_playing") || it.endsWith("play_pause_button") } == true) return true
            p = p.parent
        }
        return false
    }

    /** The big Play button on a playlist/artist/album page ("Play playlist", "Play artist", …). */
    private fun playButton(a: AssistAccessibilityService): AccessibilityNodeInfo? {
        val rx = Regex("^(play|shuffle play)( (playlist|artist|album|podcast|show|single|ep|mix|radio|songs|all).*)?$", RegexOption.IGNORE_CASE)
        for (root in a.roots(PKG)) {
            a.findFirst(root) { n ->
                val d = n.contentDescription?.toString()?.trim() ?: n.text?.toString()?.trim() ?: return@findFirst false
                n.isVisibleToUser && rx.matches(d) && d.contains(' ') && !insideNowPlayingBar(n)
            }?.let { return it }
        }
        return null
    }

    private suspend fun <T> poll(timeoutMs: Long, step: Long = 200, f: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            f()?.let { return it }
            delay(step)
        }
        return null
    }

    /** Returns a short description of what was started, or null if it couldn't be done. */
    suspend fun play(app: NovaApp, rawQuery: String, launch: (Intent) -> Boolean): String? {
        val a = AssistAccessibilityService.instance ?: return null
        val (kind, query) = classify(rawQuery)
        val before = MediaPlayback.controllerFor(PKG)?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (!launch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:search:" + android.net.Uri.encode(query))).setPackage(PKG))) return null
        delay(700)
        val row = poll(7000) { rows(a).takeIf { it.isNotEmpty() }?.let { pick(it, kind) } }
            ?: run { AppLog.d("spotify", "no usable result for '$query' ($kind)"); return null }
        AppLog.d("spotify", "picking '${row.title}' (${row.subtitle}) for '$query' ($kind)")
        if (!a.clickNode(row.node)) return null
        val type = typeOf(row.subtitle)
        if (type != "song" && type != "episode") {
            val btn = poll(6000) { playButton(a) } ?: run { AppLog.d("spotify", "no Play button on '${row.title}' page"); return null }
            delay(250)
            a.clickNode(btn)
        }
        // confirm Spotify switched to it; if it loaded but sits paused, press play (the new context is queued now)
        var pressed = 0
        val ok = poll(9000, 300) {
            val c = MediaPlayback.controllerFor(PKG)
            val title = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val changed = title != null && (title != before || type == "song")
            val st = c?.playbackState?.state
            when {
                st == PlaybackState.STATE_PLAYING && changed -> true
                changed && st == PlaybackState.STATE_PAUSED && pressed < 2 -> { c.transportControls.play(); pressed++; null }
                else -> null
            }
        }
        AppLog.d("spotify", "playing='${ok == true}' after choosing '${row.title}'")
        return "${row.title} (${type.ifBlank { "result" }})"
    }
}
