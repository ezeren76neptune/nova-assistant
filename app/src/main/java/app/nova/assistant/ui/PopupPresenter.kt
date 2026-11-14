package app.nova.assistant.ui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.nova.assistant.NovaApp
import app.nova.assistant.R
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.service.AssistAccessibilityService
import app.nova.assistant.ui.theme.NovaTheme

/**
 * Decides HOW the assistant popup appears:
 *  - accessibility overlay (no permission needed, sits over any app),
 *  - app overlay (SYSTEM_ALERT_WINDOW),
 *  - or the translucent [AssistActivity] (lock screen, or when no overlay is possible).
 */
class PopupPresenter(private val app: NovaApp) {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayWindow? = null
    private var lastLaunch = 0L

    /** The activity-mode popup, registered by [AssistActivity] itself. */
    var activity: AssistActivity? = null

    /** Activity popup requested but not registered yet (it takes a moment to come up). */
    @Volatile private var launchPendingUntil = 0L

    fun isShowing(): Boolean = overlay != null || activity != null || System.currentTimeMillis() < launchPendingUntil

    fun show() = onMain {
        if (overlay != null) return@onMain
        activity?.let { a ->
            if (!a.isOnScreen) launchActivity(bringToFront = true)
            return@onMain
        }
        val s = app.settings
        val mode = s[K.popupMode]
        if (mode == "activity" || (isLocked() && s[K.popupShowWhenLocked])) {
            launchActivity(); return@onMain
        }
        val host = overlayHost()
        if (host == null) {
            if (mode == "overlay") AppLog.d("popup", "overlay mode requested but no overlay host (enable accessibility or 'display over other apps')")
            launchActivity(); return@onMain
        }
        val w = OverlayWindow(app, this, host.first, host.second)
        if (w.show()) overlay = w else launchActivity()
    }

    fun hide() = onMain {
        launchPendingUntil = 0
        overlay?.remove()
        overlay = null
        activity?.let { a -> activity = null; a.finishFromPresenter() }
    }

    fun setFocusable(f: Boolean) = onMain { overlay?.setFocusable(f) }

    /** The overlay window was torn down by the system (not by us). */
    internal fun onOverlayLost(w: OverlayWindow) = onMain {
        if (overlay !== w) return@onMain
        overlay = null
        app.controller.onPopupGone("overlay removed by system")
    }

    /** The accessibility service (our overlay host) is going away. */
    fun onAccessibilityGone() = onMain {
        val w = overlay ?: return@onMain
        if (!w.hostedByAccessibility) return@onMain
        w.remove()
        overlay = null
        app.controller.onPopupGone("accessibility service disconnected")
    }

    /** Accessibility service first (TYPE_ACCESSIBILITY_OVERLAY), then the app if it may draw overlays. */
    fun overlayHost(): Pair<Context, Boolean>? {
        AssistAccessibilityService.instance?.let { return it to true }
        if (Settings.canDrawOverlays(app)) return app to false
        return null
    }

    fun canUseOverlay(): Boolean = overlayHost() != null

    fun isLocked(): Boolean {
        val km = app.getSystemService(KeyguardManager::class.java)
        val pm = app.getSystemService(PowerManager::class.java)
        return (km?.isKeyguardLocked == true) || (pm?.isInteractive == false)
    }

    private fun launchActivity(bringToFront: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!bringToFront && activity == null && now - lastLaunch < 1500) return
        lastLaunch = now
        launchPendingUntil = now + 2500
        val i = Intent(app, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_FROM_PRESENTER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (bringToFront) i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val contexts = listOfNotNull<Context>(AssistAccessibilityService.instance, app)
        for (c in contexts) {
            try {
                c.startActivity(i); return
            } catch (t: Throwable) {
                AppLog.d("popup", "start AssistActivity via ${c.javaClass.simpleName} failed", t)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** A Compose popup living in its own window at the bottom of the screen, over whatever app is open. */
internal class OverlayWindow(
    private val app: NovaApp,
    private val presenter: PopupPresenter,
    private val host: Context,
    private val a11y: Boolean,
) {
    val hostedByAccessibility get() = a11y
    private var removing = false
    private val wm = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayLifecycleOwner()
    private var root: OverlayRoot? = null
    private var focusable = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (a11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM
        @Suppress("DEPRECATION")
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        windowAnimations = android.R.style.Animation_InputMethod
        title = "Nova assistant"
    }

    fun show(): Boolean {
        val themed = ContextThemeWrapper(host, R.style.Theme_Nova)
        owner.create()
        val compose = ComposeView(themed).apply {
            setContent {
                NovaTheme {
                    val st by app.controller.state.collectAsState()
                    AssistantPopup(st, onRequestTyping = { presenter.setFocusable(true) })
                }
            }
        }
        val r = OverlayRoot(
            themed,
            onBack = { app.controller.dismiss() },
            onOutside = { if (!focusable) app.controller.onOutsideTouch() },
            onDetached = { if (!removing) presenter.onOverlayLost(this) },
        )
        r.setViewTreeLifecycleOwner(owner)
        r.setViewTreeSavedStateRegistryOwner(owner)
        r.setViewTreeViewModelStoreOwner(owner)
        r.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return try {
            wm.addView(r, params)
            root = r
            owner.resume()
            AppLog.d("popup", "overlay shown (${if (a11y) "accessibility" else "app"} overlay)")
            true
        } catch (t: Throwable) {
            AppLog.d("popup", "overlay addView failed", t)
            owner.destroy()
            false
        }
    }

    fun setFocusable(f: Boolean) {
        val r = root ?: return
        if (focusable == f) return
        focusable = f
        params.flags = if (f) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(r, params) }.onFailure { AppLog.d("popup", "updateViewLayout failed", it) }
    }

    fun remove() {
        removing = true
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
        owner.destroy()
    }
}

/** Root view: back key closes the popup, touches outside it (optionally) dismiss. */
@SuppressLint("ViewConstructor")
internal class OverlayRoot(
    ctx: Context,
    private val onBack: () -> Unit,
    private val onOutside: () -> Unit,
    private val onDetached: () -> Unit = {},
) : FrameLayout(ctx) {
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onDetached()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside(); return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** Minimal lifecycle so Compose can run in a window that has no Activity. */
internal class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) return
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        val s = registry.currentState
        if (s == Lifecycle.State.INITIALIZED || s == Lifecycle.State.DESTROYED) return
        if (s.isAtLeast(Lifecycle.State.RESUMED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        if (s.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
