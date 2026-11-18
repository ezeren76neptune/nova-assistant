package app.nova.assistant.home

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.nova.assistant.NovaApp
import app.nova.assistant.core.AppLog
import app.nova.assistant.core.K
import app.nova.assistant.core.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

data class HaEntity(val id: String, val name: String, val state: String, val attrs: JSONObject) {
    val domain: String get() = id.substringBefore('.')
}

/**
 * Home Assistant over its REST API: sign-in (OAuth or long-lived token), device list, service calls,
 * and HA's own Assist (conversation) for anything Nova can't map itself.
 */
class HomeAssistant(private val app: NovaApp) {
    private val s get() = app.settings
    private val mutex = Mutex()

    @Volatile var entities: List<HaEntity> = emptyList()
        private set
    @Volatile private var fetchedAt = 0L

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun isConfigured() = s[K.haUrl].isNotBlank() && (s[K.haToken].isNotBlank() || s[K.haRefreshToken].isNotBlank())

    fun baseUrl(): String {
        var u = s[K.haUrl].trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u
    }

    // ---------------------------------------------------------------- auth

    private suspend fun bearer(): String {
        s[K.haToken].trim().takeIf { it.isNotBlank() }?.let { return it }
        val exp = s[K.haAccessExpiry].toLongOrNull() ?: 0L
        val cached = s[K.haAccessToken]
        if (cached.isNotBlank() && System.currentTimeMillis() < exp - 60_000) return cached
        val refresh = s[K.haRefreshToken].takeIf { it.isNotBlank() } ?: error("Not signed in to Home Assistant")
        val body = "grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(CLIENT_ID)}"
        val (code, text) = raw("POST", "/auth/token", body, form = true, auth = null)
        if (code !in 200..299) error("Home Assistant sign-in expired (HTTP $code) — sign in again in Nova → Home Assistant")
        val o = JSONObject(text)
        s[K.haAccessToken] = o.getString("access_token")
        s[K.haAccessExpiry] = (System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000).toString()
        return s[K.haAccessToken]
    }

    /** Finishes the in-app sign-in: trades the OAuth code for tokens. */
    suspend fun exchangeCode(code: String): Boolean {
        val body = "grant_type=authorization_code&code=${enc(code)}&client_id=${enc(CLIENT_ID)}"
        val (status, text) = raw("POST", "/auth/token", body, form = true, auth = null)
        if (status !in 200..299) { AppLog.d("ha", "token exchange failed $status $text"); return false }
        val o = JSONObject(text)
        s[K.haRefreshToken] = o.optString("refresh_token")
        s[K.haAccessToken] = o.getString("access_token")
        s[K.haAccessExpiry] = (System.currentTimeMillis() + o.optLong("expires_in", 1800) * 1000).toString()
        s[K.haToken] = ""
        AppLog.d("ha", "signed in to ${baseUrl()}")
        refreshInBackground(force = true)
        return true
    }

    fun authorizeUrl(): String =
        "${baseUrl()}/auth/authorize?response_type=code&client_id=${enc(CLIENT_ID)}&redirect_uri=${enc(REDIRECT_URI)}&state=nova"

    fun signOut() {
        s[K.haRefreshToken] = ""; s[K.haAccessToken] = ""; s[K.haAccessExpiry] = ""; s[K.haToken] = ""
        entities = emptyList(); fetchedAt = 0
        _status.value = "Signed out"
    }

    // ---------------------------------------------------------------- http

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /**
     * Some networks (a VPN, another Wi-Fi's DNS) can't resolve a local name like homeassistant.local even though the
     * server is reachable. For http:// URLs, fall back to the address the name last resolved to.
     */
    private suspend fun raw(method: String, path: String, body: String?, form: Boolean = false, auth: String?): Pair<Int, String> {
        val base = baseUrl()
        val host = runCatching { URL(base).host }.getOrNull()
        try {
            val r = rawAt(base, method, path, body, form, auth)
            if (host != null && !host.first().isDigit()) withContext(Dispatchers.IO) {
                runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull()
                    ?.takeIf { it != s[K.haLastIp] }?.let { s[K.haLastIp] = it }
            }
            return r
        } catch (e: java.net.UnknownHostException) {
            val ip = s[K.haLastIp]
            if (ip.isBlank() || host == null || !base.startsWith("http://")) throw e
            AppLog.d("ha", "can't resolve $host on this network — trying its last address $ip")
            return rawAt(base.replaceFirst("://$host", "://$ip"), method, path, body, form, auth)
        }
    }

    private suspend fun rawAt(base: String, method: String, path: String, body: String?, form: Boolean, auth: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 8000
                conn.readTimeout = 15000
                auth?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                code to text
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun api(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val r = raw(method, path, body?.toString(), auth = bearer())
        if (r.first == 401 && s[K.haToken].isBlank()) {
            s[K.haAccessExpiry] = "0" // force a refresh once
            return raw(method, path, body?.toString(), auth = bearer())
        }
        return r
    }

    // ---------------------------------------------------------------- data

    suspend fun refresh(force: Boolean = false): List<HaEntity> = mutex.withLock {
        if (!force && entities.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 30_000) return entities
        val (code, text) = api("GET", "/api/states")
        if (code !in 200..299) error("Home Assistant returned HTTP $code")
        val arr = JSONArray(text)
        val list = ArrayList<HaEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("entity_id")
            val attrs = o.optJSONObject("attributes") ?: JSONObject()
            val name = attrs.optString("friendly_name").ifBlank { id.substringAfter('.').replace('_', ' ') }
            list += HaEntity(id, name, o.optString("state"), attrs)
        }
        entities = list
        fetchedAt = System.currentTimeMillis()
        _status.value = "Connected · ${list.size} entities"
        list
    }

    fun refreshInBackground(force: Boolean = false) {
        if (!isConfigured()) return
        app.appScope.launch {
            runCatching { refresh(force) }.onFailure {
                AppLog.d("ha", "refresh failed", it); _status.value = "Error: ${it.message}"
            }
        }
    }

    suspend fun state(id: String): HaEntity? {
        val (code, text) = api("GET", "/api/states/$id")
        if (code !in 200..299) return null
        val o = JSONObject(text)
        val attrs = o.optJSONObject("attributes") ?: JSONObject()
        val e = HaEntity(id, attrs.optString("friendly_name").ifBlank { id }, o.optString("state"), attrs)
        entities = entities.map { if (it.id == id) e else it }
        return e
    }

    suspend fun callService(domain: String, service: String, data: JSONObject): Boolean {
        val (code, text) = api("POST", "/api/services/$domain/$service", data)
        AppLog.d("ha", "call $domain.$service $data -> $code")
        if (code !in 200..299) AppLog.d("ha", "service error: ${text.take(300)}")
        return code in 200..299
    }

    /** Home Assistant's own Assist pipeline — understands areas, aliases and everything HA exposes. */
    suspend fun assist(text: String): String? {
        val body = JSONObject().put("text", text).put("language", "en")
        val (code, resp) = api("POST", "/api/conversation/process", body)
        if (code !in 200..299) return null
        val o = JSONObject(resp)
        return o.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech")
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun test(): String {
        val (code, text) = api("GET", "/api/config")
        if (code !in 200..299) error("HTTP $code ${text.take(120)}")
        val cfg = JSONObject(text)
        val list = refresh(force = true)
        return "Connected to ${cfg.optString("location_name", "Home Assistant")} " +
            "(version ${cfg.optString("version")}) · ${list.size} entities, ${controllable().size} controllable"
    }

    // ---------------------------------------------------------------- what Nova may control

    fun widgetIds(): Set<String> = s[K.haWidgets].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun favoriteIds(): Set<String> = s[K.haFavorites].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setFavorite(id: String, fav: Boolean) {
        val f = favoriteIds().toMutableSet(); if (fav) f += id else f -= id
        s[K.haFavorites] = f.joinToString(",")
    }

    /** Entity IDs seen on the Home Assistant app's widget screen (captured by the accessibility service). */
    fun addWidgetIds(ids: Collection<String>) {
        val cur = widgetIds()
        val all = cur + ids
        if (all.size != cur.size) {
            s[K.haWidgets] = all.joinToString(",")
            AppLog.d("ha", "imported widget entities: ${(all - cur).joinToString()}")
        }
    }

    fun removeWidgetId(id: String) { s[K.haWidgets] = (widgetIds() - id).joinToString(",") }

    /** Devices Nova may act on, widget + favorite entities first. */
    fun controllable(): List<HaEntity> {
        val pinned = widgetIds() + favoriteIds()
        val onlyPinned = s[K.haExposeMode] == "pinned"
        return entities.filter { (it.domain in CONTROL_DOMAINS || it.id in pinned) && (!onlyPinned || it.id in pinned) }
            .sortedWith(compareByDescending<HaEntity> { it.id in pinned }.thenBy { it.name.lowercase() })
    }

    /** Widget/favorite entities (even ones the HA app shows that aren't in a control domain, e.g. sensors). */
    fun pinned(): List<HaEntity> {
        val ids = widgetIds() + favoriteIds()
        return ids.map { id -> entities.firstOrNull { it.id == id } ?: HaEntity(id, id.substringAfter('.').replace('_', ' '), "?", JSONObject()) }
    }

    // ---------------------------------------------------------------- matching

    private val fillers = Regex("\\b(the|my|our|a|an|please|in|on|at|of)\\b")

    private fun clean(s: String) = TextNorm.basic(s).replace(fillers, " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Best entity for a spoken name ("garage door", "kitchen lights", "script.garage_door").
     * [domains] limits the search; widget/favorite devices win ties.
     */
    fun match(query: String, domains: Set<String>? = null, pool: List<HaEntity> = controllable()): Pair<HaEntity, Float>? {
        val raw = query.trim().lowercase()
        pool.firstOrNull { it.id == raw }?.let { return it to 1f }
        val q = clean(query)
        if (q.isEmpty()) return null
        val qs = q.removeSuffix("s")
        val pinned = widgetIds() + favoriteIds()
        val qWords = q.split(' ').toSet()
        var best: HaEntity? = null
        var bestScore = 0f
        for (e in pool) {
            if (domains != null && e.domain !in domains) continue
            val names = buildList {
                add(clean(e.name)); add(clean(e.id.substringAfter('.').replace('_', ' ')))
                e.attrs.optJSONArray("aliases")?.let { a -> for (i in 0 until a.length()) add(clean(a.optString(i))) }
            }.filter { it.isNotEmpty() }
            var sc = 0f
            for (n in names) {
                val ns = n.removeSuffix("s")
                val v = when {
                    n == q || ns == qs -> 1f
                    Regex("\\b${Regex.escape(q)}\\b").containsMatchIn(n) -> 0.9f
                    Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(q) -> 0.85f
                    else -> {
                        val nWords = n.split(' ').toSet()
                        val overlap = (qWords intersect nWords).size.toFloat() / maxOf(qWords.size, nWords.size)
                        maxOf(overlap * 0.85f, TextNorm.similarity(n, q) * 0.8f)
                    }
                }
                if (v > sc) sc = v
            }
            if (e.id in pinned) sc += 0.04f
            if (e.state == "unavailable") sc -= 0.1f
            if (sc > bestScore) { bestScore = sc; best = e }
        }
        return best?.let { it to bestScore.coerceAtMost(1f) }
    }

    /** Should this action ask first? (unlocking, opening garages/doors/gates, disarming) */
    fun isSensitive(e: HaEntity, action: String): Boolean {
        if (!s[K.haConfirmSecurity]) return false
        val n = (e.name + " " + e.id).lowercase()
        val doorish = Regex("garage|door|gate|lock|entry|front|back door").containsMatchIn(n) ||
            e.attrs.optString("device_class") in setOf("garage", "door", "gate")
        return when (e.domain) {
            "lock" -> action in setOf("unlock", "open", "off", "toggle")
            "alarm_control_panel" -> action.contains("disarm") || action == "off"
            "cover", "valve" -> doorish && action in setOf("open", "toggle", "on")
            "script", "button", "input_button", "switch" -> doorish
            else -> false
        }
    }

    // ---------------------------------------------------------------- discovery

    /** Finds Home Assistant on the local network (zeroconf _home-assistant._tcp). */
    suspend fun discover(ctx: Context, timeoutMs: Long = 5000): List<String> {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = CopyOnWriteArrayList<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val attrs = r.attributes.mapValues { String(it.value ?: ByteArray(0)) }
                        listOfNotNull(attrs["internal_url"], attrs["base_url"], attrs["external_url"])
                            .filter { it.startsWith("http") }.forEach { if (it !in found) found += it.trimEnd('/') }
                        @Suppress("DEPRECATION")
                        val host = if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull()?.hostAddress else r.host?.hostAddress
                        host?.let { h -> "http://${if (h.contains(':')) "[$h]" else h}:${r.port}".let { if (it !in found) found += it } }
                    }
                })
            }
        }
        runCatching { nsd.discoverServices("_home-assistant._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { return emptyList() }
        delay(timeoutMs)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return found.toList()
    }

    companion object {
        /** OAuth client: HA accepts any URL as client id when the redirect is on the same host. */
        const val CLIENT_ID = "http://nova.local/"
        const val REDIRECT_URI = "http://nova.local/callback"

        val CONTROL_DOMAINS = setOf(
            "light", "switch", "fan", "cover", "lock", "script", "scene", "button", "input_button", "input_boolean",
            "climate", "media_player", "vacuum", "automation", "siren", "humidifier", "valve", "water_heater",
            "input_number", "number", "input_select", "select", "alarm_control_panel", "remote", "lawn_mower",
        )

        val ENTITY_ID = Regex("\\b(" + (CONTROL_DOMAINS + setOf("sensor", "binary_sensor", "camera", "todo", "weather", "person", "device_tracker", "zone", "sun"))
            .joinToString("|") + ")\\.[a-z0-9_]+\\b")
    }
}
