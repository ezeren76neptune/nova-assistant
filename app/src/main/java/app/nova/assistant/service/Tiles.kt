package app.nova.assistant.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.ui.AssistActivity

/** Quick Settings tile: wake word on/off. */
class WakeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val s = app.settings
        val on = !s[K.wakeEnabled]
        s[K.wakeEnabled] = on
        if (on) ListenerService.start(this, "tile") else ListenerService.stop(this)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = app.settings[K.wakeEnabled]
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Wake word"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            t.subtitle = if (!on) "Off" else if (ListenerService.pausedUntil.value != 0L) "Paused" else "Listening"
        }
        t.updateTile()
    }
}

/** Quick Settings tile: open the assistant popup right now. */
class AskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.label = "Ask ${app.settings[K.assistantName]}"
            it.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val i = Intent(this, AssistActivity::class.java)
            .setAction(ListenerService.ACTION_ASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (t: Throwable) {
            AppLog.d("tile", "ask tile failed", t)
            app.controller.activate("tile")
        }
    }
}
