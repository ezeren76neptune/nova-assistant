package app.nova.assistant.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.nova.assistant.app
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.home.HomeAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ToggleResult { CHANGED, ALREADY, NOT_FOUND, FAILED }

/**
 * Nova's accessibility service: global actions (home/back/lock/screenshot), reading and driving
 * other apps' UI, flipping Quick Settings tiles / settings switches, auto-pressing "Send", the
 * accessibility-button shortcut, a trusted overlay host, and a keep-alive for the listener service.
 */
class AssistAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AssistAccessibilityService? = null
            private set

        private const val SYSTEMUI = "com.android.systemui"

        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(ctx, AssistAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }

    @Volatile
    var currentPackage: String? = null
        private set

    private var buttonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var settingsJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyFlags()
        AppLog.d("a11y", "accessibility service connected")
        if (app.settings[K.wakeEnabled]) ListenerService.start(this, "a11y")
        settingsJob = scope.launch {
            app.settings.changes.collect { key -> if (key == K.a11yButton.key) applyFlags() }
        }
    }

    /**
     * The floating accessibility button is opt-in (Settings → Popup): requesting it makes Android
     * pin a shortcut button on screen, which most people don't want.
     */
    private fun applyFlags() {
        val wantButton = app.settings[K.a11yButton]
        try {
            serviceInfo?.let { info ->
                var f = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                f = if (wantButton) f or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
                else f and AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON.inv()
                info.flags = f
                serviceInfo = info
            }
        } catch (t: Throwable) {
            AppLog.d("a11y", "could not update service flags", t)
        }
        if (wantButton && buttonCallback == null) {
            try {
                val cb = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        app.controller.activate("a11y_button")
                    }
                }
                accessibilityButtonController.registerAccessibilityButtonCallback(cb)
                buttonCallback = cb
            } catch (t: Throwable) {
                AppLog.d("a11y", "accessibility button unavailable", t)
            }
        } else if (!wantButton) {
            buttonCallback?.let { runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) } }
            buttonCallback = null
        }
    }

    @Volatile private var lastHaScan = 0L

    /** On the Home Assistant app's widget screens, remember the entity IDs your widgets use. */
    private fun scanHomeAssistantWidgets(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastHaScan < 1200) return
        lastHaScan = now
        val texts = ArrayList<String>()
        roots(pkg).forEach { root ->
            var count = 0
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (count++ > 800 || depth > 40) return
                n.text?.toString()?.let { texts += it }
                n.contentDescription?.toString()?.let { texts += it }
                for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
            }
            visit(root, 0)
        }
        if (texts.none { it.contains("widget", ignoreCase = true) }) return
        val ids = texts.flatMap { t -> HomeAssistant.ENTITY_ID.findAll(t.lowercase()).map { it.value }.toList() }.toSet()
        if (ids.isNotEmpty()) app.home.addWidgetIds(ids)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { p ->
            if (p.startsWith("io.homeassistant.companion.android")) scanHomeAssistantWidgets(p)
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != SYSTEMUI) currentPackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        settingsJob?.cancel(); settingsJob = null
        runCatching { app.popup.onAccessibilityGone() }
        buttonCallback?.let { runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) } }
        buttonCallback = null
        if (instance === this) instance = null
    }

    // ---------------------------------------------------------------- basics

    fun global(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (t: Throwable) {
        AppLog.d("a11y", "global action $action failed", t); false
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 60)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = try {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(g, null, null)
    } catch (t: Throwable) {
        AppLog.d("a11y", "gesture failed", t); false
    }

    // ---------------------------------------------------------------- window roots

    /** Roots of every window except Nova's own (optionally only one package). */
    internal fun roots(pkg: String? = null): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                val p = r.packageName?.toString()
                if (p == packageName) return@forEach
                if (pkg != null && p != pkg) return@forEach
                out += r
            }
        } catch (t: Throwable) {
            AppLog.d("a11y", "windows unavailable", t)
        }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { r ->
                val p = r.packageName?.toString()
                if (p != packageName && (pkg == null || p == pkg)) out += r
            }
        }
        return out
    }

    /** Roots of the foreground application window(s), skipping Nova, system bars and the keyboard. */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() == packageName) return@forEach
                out += r
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName?.toString() != packageName) out += it }
        }
        return out
    }

    // ---------------------------------------------------------------- reading

    /** Debug: one line per node (depth, class, text, description, view id, flags, bounds). */
    fun dumpTree(pkg: String? = null, maxNodes: Int = 700): String {
        val sb = StringBuilder()
        var count = 0
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > maxNodes || depth > 60) return
            n.getBoundsInScreen(r)
            val flags = buildString {
                if (n.isClickable) append('C'); if (n.isLongClickable) append('L'); if (n.isScrollable) append('S')
                if (n.isCheckable) append(if (n.isChecked) "X" else "x"); if (!n.isVisibleToUser) append('h')
            }
            sb.append("  ".repeat(depth.coerceAtMost(30)))
                .append(n.className?.toString()?.substringAfterLast('.')).append(' ')
                .append(n.text?.let { "t=\"${it.toString().replace('\n', ' ').take(60)}\" " } ?: "")
                .append(n.contentDescription?.let { "d=\"${it.toString().replace('\n', ' ').take(60)}\" " } ?: "")
                .append(n.viewIdResourceName?.let { "id=${it.substringAfter(":id/")} " } ?: "")
                .append("[$flags] ${r.left},${r.top},${r.right},${r.bottom}\n")
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        roots(pkg).forEach { visit(it, 0) }
        return sb.toString()
    }

    fun screenText(maxChars: Int = 4000): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        var count = 0
        val rs = appRoots()
        rs.firstOrNull()?.packageName?.toString()?.let { pkg ->
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            sb.append("App: ").append(label).append('\n')
        }
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > 500 || sb.length >= maxChars || depth > 40) return
            if (!n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val piece = (if (t.isNotEmpty()) t else d).replace('\n', ' ')
            if (piece.isNotEmpty() && seen.add(piece)) {
                val cls = n.className?.toString().orEmpty()
                val prefix = when {
                    n.isEditable -> "[field] "
                    n.isCheckable -> if (n.isChecked) "[on] " else "[off] "
                    n.isClickable && (cls.contains("Button") || t.isEmpty()) -> "[button] "
                    else -> ""
                }
                sb.append(prefix).append(piece).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        rs.forEach { visit(it, 0) }
        val out = sb.toString().take(maxChars)
        return if (out.lines().count { it.isNotBlank() } <= 1) out + "(no readable text on screen)" else out
    }

    // ---------------------------------------------------------------- finding

    private fun norm(s: CharSequence?): String =
        s?.toString()?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}]"), "").orEmpty()

    private fun labelScore(n: AccessibilityNodeInfo, wanted: List<String>, rawWanted: List<String>): Int {
        var best = 0
        val id = n.viewIdResourceName
        if (id != null && rawWanted.any { it == id }) return 4
        val cands = buildList {
            add(norm(n.text)); add(norm(n.contentDescription))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(norm(n.stateDescription))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(norm(n.paneTitle))
        }.filter { it.isNotEmpty() }
        for (c in cands) for (w in wanted) {
            val s = when {
                c == w -> 3
                c.startsWith(w) -> 2
                c.contains(w) -> 1
                else -> 0
            }
            if (s > best) best = s
        }
        return best
    }

    private fun findByLabels(
        roots: List<AccessibilityNodeInfo>,
        labels: List<String>,
        minScore: Int = 1,
        predicate: (AccessibilityNodeInfo) -> Boolean = { true },
    ): AccessibilityNodeInfo? {
        val wanted = labels.map { norm(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var visited = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > 2500 || depth > 50 || bestScore >= 4) return
            if (n.isVisibleToUser && predicate(n)) {
                val s = labelScore(n, wanted, labels)
                if (s > bestScore) { bestScore = s; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        return if (bestScore >= minScore) best else null
    }

    internal fun findFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var visited = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (visited++ > 2500 || depth > 50) return null
            if (pred(n)) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                visit(c, depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, 0)
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent; depth++
        }
        return null
    }

    internal fun clickNode(n: AccessibilityNodeInfo): Boolean {
        clickableAncestor(n)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    // ---------------------------------------------------------------- acting

    suspend fun clickText(text: String, timeoutMs: Long = 0): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val n = findByLabels(roots(), listOf(text))
            if (n != null) return clickNode(n)
            if (System.currentTimeMillis() >= deadline) return false
            delay(150)
        }
    }

    fun typeText(text: String, submit: Boolean): Boolean {
        val rs = roots()
        val target = rs.firstNotNullOfOrNull { r -> r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } }
            ?: rs.firstNotNullOfOrNull { r -> findFirst(r) { it.isEditable && it.isVisibleToUser } }
            ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    fun scroll(direction: String): Boolean {
        val dir = direction.lowercase().trim()
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        appRoots().forEach { visit(it, 0) }
        best?.let { node ->
            val directional = when (dir) {
                "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                else -> null
            }
            if (directional != null && node.performAction(directional.id)) return true
            val fallback = if (dir == "up" || dir == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (node.performAction(fallback)) return true
        }
        // swipe gesture fallback ("scroll down" = finger moves up)
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (dir) {
            "up" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
            "left" -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
            "right" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
            else -> gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)
        }
    }

    // ---------------------------------------------------------------- toggles

    private fun parseOnOff(s: CharSequence?): Boolean? {
        val words = s?.toString()?.lowercase()?.split(Regex("[^a-z]+"))?.filter { it.isNotEmpty() } ?: return null
        return when {
            "off" in words || "disabled" in words -> false
            "on" in words || "enabled" in words -> true
            else -> null
        }
    }

    private fun stateOf(n: AccessibilityNodeInfo?): Boolean? {
        if (n == null) return null
        if (n.isCheckable) return n.isChecked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) parseOnOff(n.stateDescription)?.let { return it }
        return null
    }

    /** Best guess at a tile/row's on/off state, looking at the node, its clickable ancestor and their children. */
    private fun tileState(node: AccessibilityNodeInfo): Boolean? {
        val anc = clickableAncestor(node)
        stateOf(anc)?.let { return it }
        stateOf(node)?.let { return it }
        val scope = anc ?: node
        for (i in 0 until scope.childCount) {
            val c = scope.getChild(i) ?: continue
            stateOf(c)?.let { return it }
            val t = norm(c.text)
            if (t == "on") return true
            if (t == "off") return false
        }
        parseOnOff(anc?.contentDescription)?.let { return it }
        return null
    }

    private suspend fun closeShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!global(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) { global(GLOBAL_ACTION_BACK) }
        } else {
            global(GLOBAL_ACTION_BACK); delay(150); global(GLOBAL_ACTION_BACK)
        }
    }

    suspend fun toggleQuickSetting(labels: List<String>, desired: Boolean?): ToggleResult {
        if (!global(GLOBAL_ACTION_QUICK_SETTINGS)) return ToggleResult.FAILED
        delay(700)
        val deadline = System.currentTimeMillis() + 2500
        var node: AccessibilityNodeInfo? = null
        while (node == null && System.currentTimeMillis() < deadline) {
            node = findByLabels(roots(SYSTEMUI), labels)
                // some OEMs host Quick Settings in a differently named SystemUI package
                ?: findByLabels(roots().filter { it.packageName?.toString()?.contains("systemui") == true }, labels)
            if (node == null) delay(200)
        }
        if (node == null) {
            AppLog.d("a11y", "quick setting $labels not found")
            closeShade()
            return ToggleResult.NOT_FOUND
        }
        val state = tileState(node)
        if (desired != null && state == desired) {
            closeShade()
            return ToggleResult.ALREADY
        }
        val clicked = clickNode(node)
        delay(400)
        closeShade()
        AppLog.d("a11y", "quick setting $labels: was=$state desired=$desired clicked=$clicked")
        return if (clicked) ToggleResult.CHANGED else ToggleResult.FAILED
    }

    private fun findSwitchNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        findFirst(node) { it.isCheckable }?.let { return it }
        var parent = node.parent
        var level = 0
        while (parent != null && level < 3) {
            val sw = findFirst(parent) { it.isCheckable && it.isVisibleToUser && it.className?.toString()?.contains("Switch") == true }
                ?: findFirst(parent) { it.isCheckable && it.isVisibleToUser }
            if (sw != null) return sw
            parent = parent.parent; level++
        }
        return null
    }

    suspend fun toggleInSettings(intent: Intent, labels: List<String>, desired: Boolean?): ToggleResult {
        val before = rootInActiveWindow?.packageName?.toString()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AppLog.d("a11y", "cannot open $intent", t)
            return ToggleResult.FAILED
        }
        delay(600)
        val started = System.currentTimeMillis()
        val deadline = started + 4000
        var node: AccessibilityNodeInfo? = null
        while (node == null && System.currentTimeMillis() < deadline) {
            val active = rootInActiveWindow
            val pkg = active?.packageName?.toString()
            val changed = pkg != null && pkg != packageName && (pkg != before || System.currentTimeMillis() - started > 1200)
            if (active != null && changed) node = findByLabels(listOf(active), labels)
            if (node == null) delay(200)
        }
        if (node == null) {
            AppLog.d("a11y", "settings label $labels not found")
            return ToggleResult.NOT_FOUND
        }
        val sw = findSwitchNear(node)
        if (sw == null) {
            if (desired == null && clickNode(node)) {
                delay(500); global(GLOBAL_ACTION_BACK); return ToggleResult.CHANGED
            }
            global(GLOBAL_ACTION_BACK)
            return ToggleResult.NOT_FOUND
        }
        if (desired != null && sw.isChecked == desired) {
            delay(300); global(GLOBAL_ACTION_BACK)
            return ToggleResult.ALREADY
        }
        val clicked = clickNode(sw)
        delay(500)
        global(GLOBAL_ACTION_BACK)
        AppLog.d("a11y", "settings toggle $labels desired=$desired clicked=$clicked")
        return if (clicked) ToggleResult.CHANGED else ToggleResult.FAILED
    }

    suspend fun autoClick(
        pkg: String?,
        viewIds: List<String>,
        labels: List<String>,
        timeoutMs: Long = 8000,
        delayMs: Long = 0,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val target = findAutoTarget(pkg, viewIds, labels)
            if (target != null) {
                if (delayMs > 0) {
                    delay(delayMs)
                    val fresh = findAutoTarget(pkg, viewIds, labels) ?: target
                    return clickNode(fresh).also { AppLog.d("a11y", "autoClick $viewIds/$labels -> $it") }
                }
                return clickNode(target).also { AppLog.d("a11y", "autoClick $viewIds/$labels -> $it") }
            }
            delay(150)
        }
        AppLog.d("a11y", "autoClick timed out for $pkg $viewIds $labels")
        return false
    }

    private fun findAutoTarget(pkg: String?, viewIds: List<String>, labels: List<String>): AccessibilityNodeInfo? {
        val rs = roots(pkg)
        for (r in rs) for (id in viewIds) {
            val found = runCatching { r.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (found != null) return found
        }
        if (labels.isEmpty()) return null
        return findByLabels(rs, labels, minScore = 3) { it.isEnabled }
    }
}
