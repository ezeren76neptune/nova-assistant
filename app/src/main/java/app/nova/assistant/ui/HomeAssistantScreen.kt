package app.nova.assistant.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nova.assistant.app
import app.nova.assistant.core.K
import app.nova.assistant.tools.HomeTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeAssistantScreen() {
    val ctx = LocalContext.current
    val nova = ctx.app
    val ha = nova.home
    val scope = rememberCoroutineScope()
    val status by ha.status.collectAsState()
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    // pick up changes made elsewhere (sign-in screen, widgets imported from the HA app)
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    LaunchedEffect(Unit) { if (ha.isConfigured()) runCatching { ha.refresh() } }
    @Suppress("UNUSED_EXPRESSION") tick
    val signedIn = ha.isConfigured()

    SettingsScroll {
        PrefSection("Connection")
        Text(
            when {
                msg.isNotBlank() -> msg
                status.isNotBlank() -> status
                signedIn -> "Signed in"
                else -> "Not connected"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        TextPref("Home Assistant address", K.haUrl, summary = "Your Home Assistant URL", placeholder = "http://homeassistant.local:8123")
        ActionPref("Find it on my network", "Looks for Home Assistant on this Wi-Fi") {
            scope.launch {
                msg = "Searching the network…"
                val found = runCatching { ha.discover(ctx) }.getOrDefault(emptyList())
                msg = if (found.isEmpty()) "Didn't find it — type the address instead." else {
                    nova.settings[K.haUrl] = found.first()
                    "Found: ${found.joinToString()}"
                }
            }
        }
        ActionPref(if (signedIn) "Sign in again" else "Sign in to Home Assistant", "Opens your Home Assistant login page inside Nova") {
            if (nova.settings[K.haUrl].isBlank()) msg = "Enter the address first."
            else ctx.startActivity(Intent(ctx, HaLoginActivity::class.java))
        }
        TextPref(
            "Or paste a long-lived access token", K.haToken, password = true,
            summary = "Home Assistant → your profile → Security → Long-lived access tokens",
        )
        ActionPref("Test connection") {
            scope.launch { msg = "Testing…"; msg = runCatching { ha.test() }.getOrElse { "Failed: ${it.message}" } }
        }
        if (signedIn) ActionPref("Sign out") { ha.signOut(); msg = "" }

        PrefSection("Your widgets")
        Text(
            "Open the Home Assistant app → Settings → Companion app → Manage widgets. Nova reads the devices your " +
                "home-screen widgets use and makes them its main devices (needs Nova's accessibility service).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        ActionPref("Open the Home Assistant app") {
            ctx.packageManager.getLaunchIntentForPackage("io.homeassistant.companion.android")
                ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: run { msg = "Home Assistant app not installed." }
        }
        val widgetIds = ha.widgetIds()
        val pinned = ha.pinned()
        if (pinned.isEmpty()) {
            Text("No widgets imported yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        pinned.forEach { e ->
            PrefRow(e.name, "${e.id} · ${if (e.state == "?") "not loaded" else HomeTools.describe(e)}", trailing = {
                TextButton(onClick = {
                    if (e.id in widgetIds) ha.removeWidgetId(e.id) else ha.setFavorite(e.id, false)
                    tick++
                }) { Text("Remove") }
            })
        }

        PrefSection("Behaviour")
        SwitchPref("Confirm before unlocking or opening doors/garage", K.haConfirmSecurity, "Asks \"Open Garage door?\" first, in case the wake word misfires.")
        SwitchPref("Send other smart-home requests to Home Assistant Assist", K.haAssistFallback, "Rooms, areas and anything not in the device list.")
        ChoicePref(
            "Devices Nova can control", K.haExposeMode,
            listOf("all" to "All controllable devices (widgets first)", "pinned" to "Only my widgets and favorites"),
        )

        val all = ha.controllable()
        PrefSection("Devices (${all.size})")
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            placeholder = { Text("Search devices") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val favs = ha.favoriteIds()
        all.filter { filter.isBlank() || it.name.contains(filter, true) || it.id.contains(filter, true) }.take(150).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = e.id in favs || e.id in widgetIds, onCheckedChange = { c ->
                    if (e.id !in widgetIds) { ha.setFavorite(e.id, c); tick++ }
                })
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${e.id} · ${e.state}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
