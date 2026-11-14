package app.nova.assistant.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.ui.theme.NovaTheme
import kotlinx.coroutines.launch

/**
 * Translucent bottom popup used on the lock screen, when no overlay is possible, or when chosen in
 * settings. Also the entry point for the system assist gesture (ACTION_ASSIST), headset button
 * (VOICE_COMMAND), shortcuts, tiles and the notification's "Ask" action.
 */
class AssistActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FROM_PRESENTER = "from_presenter"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LISTEN = "listen"
        const val ACTION_ASK = "app.nova.assistant.ASK"
    }

    private var registered = false
    private var closingFromPresenter = false

    /** True while this activity is started (visible). Used by the presenter to bring it back to front. */
    @Volatile var isOnScreen = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyWindowFlags()
        onBackPressedDispatcher.addCallback(this) { app.controller.dismiss() }
        if (!handleIntent(intent)) return

        setContent { NovaTheme { Content() } }

        lifecycleScope.launch {
            var seenVisible = app.controller.state.value.visible
            app.controller.state.collect { s ->
                if (s.visible) seenVisible = true
                else if (seenVisible && !isFinishing) {
                    closingFromPresenter = true
                    finish()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_PRESENTER, false)) {
            register()
        } else if (registered) {
            app.controller.activate(sourceOf(intent), intent.getBooleanExtra(EXTRA_LISTEN, true))
        } else {
            handleIntent(intent)
        }
    }

    /** Returns false when the activity finished itself (the overlay took over). */
    private fun handleIntent(i: Intent): Boolean {
        val a = app
        if (i.getBooleanExtra(EXTRA_FROM_PRESENTER, false)) {
            if (!a.controller.state.value.visible) {
                // controller already dismissed before we came up
                finishQuietly(); return false
            }
            register()
            return true
        }
        val source = sourceOf(i)
        val listen = i.getBooleanExtra(EXTRA_LISTEN, true)
        val locked = a.popup.isLocked()
        if (!locked && a.settings[K.popupMode] != "activity" && a.popup.canUseOverlay()) {
            AppLog.d("popup", "AssistActivity($source) handing off to overlay")
            finishQuietly()
            a.controller.activate(source, listen)
            return false
        }
        register()
        a.controller.activate(source, listen)
        return true
    }

    private fun register() {
        val p = app.popup
        val old = p.activity
        if (old != null && old !== this) {
            p.activity = null
            old.finishFromPresenter()
        }
        p.activity = this
        registered = true
    }

    private fun sourceOf(i: Intent): String = when (i.action) {
        Intent.ACTION_ASSIST -> "assist"
        Intent.ACTION_VOICE_COMMAND -> "voice_command"
        ACTION_ASK -> i.getStringExtra(EXTRA_SOURCE) ?: "ask"
        else -> i.getStringExtra(EXTRA_SOURCE) ?: "activity"
    }

    private fun applyWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!app.settings[K.popupShowWhenLocked]) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    /** Called by [PopupPresenter.hide]. */
    fun finishFromPresenter() {
        closingFromPresenter = true
        if (!isFinishing) finish()
    }

    private fun finishQuietly() {
        closingFromPresenter = true
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        isOnScreen = true
    }

    override fun onStop() {
        isOnScreen = false
        super.onStop()
        if (registered && !closingFromPresenter && !isChangingConfigurations && !isFinishing) {
            // short grace period: showing over the lock screen can briefly stop/start the activity
            android.os.Handler(mainLooper).postDelayed({
                if (!isOnScreen && !isFinishing && !isDestroyed && !closingFromPresenter) {
                    app.controller.onPopupGone("popup screen left")
                }
            }, 700)
        }
    }

    override fun onDestroy() {
        val a = app
        if (a.popup.activity === this) a.popup.activity = null
        if (registered && !closingFromPresenter && isFinishing && !isChangingConfigurations &&
            a.controller.state.value.visible
        ) {
            a.controller.dismiss()
        }
        super.onDestroy()
    }

    @Composable
    private fun Content() {
        val st by app.controller.state.collectAsState()
        val dismissOutside = app.settings[K.popupDismissOutside]
        val noRipple = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize()) {
            // Scrim: the popup's Surface blocks touches over itself, so taps here are truly "outside".
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .clickable(interactionSource = noRipple, indication = null) {
                        if (dismissOutside) app.controller.dismiss()
                    },
            )
            AssistantPopup(
                st,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}
