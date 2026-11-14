package app.nova.assistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.TextNorm

data class NotifInfo(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val canReply: Boolean,
)

/** Reads notifications, replies through their inline-reply actions, and exposes media sessions. */
class NotificationWatcher : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotificationWatcher? = null
            private set

        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    private val labelCache = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AppLog.d("notif", "notification listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun active(): List<StatusBarNotification> = try {
        activeNotifications?.toList() ?: emptyList()
    } catch (t: Throwable) {
        AppLog.d("notif", "activeNotifications failed", t); emptyList()
    }

    private fun label(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg.substringAfterLast('.'))
    }

    @Suppress("DEPRECATION")
    private fun info(sbn: StatusBarNotification): NotifInfo {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val msgs = runCatching { ex.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
        val last = (msgs?.lastOrNull() as? Bundle)
        if (last != null) {
            val body = last.getCharSequence("text")?.toString()
            val sender = last.getCharSequence("sender")?.toString()
            if (!body.isNullOrBlank()) text = if (!sender.isNullOrBlank() && sender != title) "$sender: $body" else body
        }
        if (text.isBlank()) text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        return NotifInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            appLabel = label(sbn.packageName),
            title = title,
            text = text,
            time = sbn.postTime,
            canReply = replyAction(n) != null,
        )
    }

    private fun isUseful(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && !sbn.isOngoing &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0

    fun recent(app: String?, count: Int): List<NotifInfo> {
        val filter = app?.let { TextNorm.basic(it) }?.takeIf { it.isNotEmpty() }
        return active().asSequence()
            .filter(::isUseful)
            .map(::info)
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }
            .filter {
                filter == null || TextNorm.basic(it.appLabel).contains(filter) ||
                    it.pkg.contains(filter.replace(" ", ""))
            }
            .sortedByDescending { it.time }
            .take(count.coerceAtLeast(1))
            .toList()
    }

    private fun replyAction(n: Notification): Notification.Action? {
        fun ok(a: Notification.Action) = a.remoteInputs?.any { it.allowFreeFormInput } == true
        n.actions?.firstOrNull(::ok)?.let { return it }
        return runCatching { Notification.WearableExtender(n).actions.firstOrNull(::ok) }.getOrNull()
    }

    fun reply(target: String, message: String): String? {
        val t = TextNorm.basic(target)
        val words = t.split(' ').filter { it.isNotEmpty() }
        val candidates = active().filter { it.packageName != packageName }.sortedByDescending { it.postTime }
        // pass 1: match the person/conversation; pass 2: match the app name
        for (pass in 0..1) {
            for (sbn in candidates) {
                val i = info(sbn)
                val hay = TextNorm.basic(if (pass == 0) i.title else i.appLabel)
                if (t.isNotEmpty() && !hay.contains(t) && !(words.isNotEmpty() && words.all { hay.contains(it) })) continue
                val action = replyAction(sbn.notification) ?: continue
                return try {
                    val intent = Intent()
                    val results = Bundle()
                    action.remoteInputs.forEach { results.putCharSequence(it.resultKey, message) }
                    RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                    action.actionIntent.send(this, 0, intent)
                    AppLog.d("notif", "replied to ${i.title} (${i.appLabel})")
                    i.title.ifBlank { i.appLabel }
                } catch (e: Throwable) {
                    AppLog.d("notif", "reply failed", e); null
                }
            }
        }
        return null
    }

    fun clearAll() {
        runCatching { cancelAllNotifications() }.onFailure { AppLog.d("notif", "clear failed", it) }
    }

    fun mediaControllers(): List<MediaController> = try {
        val msm = getSystemService(MediaSessionManager::class.java)
        msm?.getActiveSessions(ComponentName(this, NotificationWatcher::class.java)) ?: emptyList()
    } catch (t: Throwable) {
        AppLog.d("notif", "media sessions unavailable", t); emptyList()
    }

    fun nowPlaying(): String? {
        val list = mediaControllers()
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return buildString {
            append(title)
            if (!artist.isNullOrBlank()) append(" by ").append(artist)
            append(" (").append(label(c.packageName)).append(if (playing) ")" else ", paused)")
        }
    }
}
