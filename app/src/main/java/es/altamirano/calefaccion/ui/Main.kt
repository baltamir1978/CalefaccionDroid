@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.calefaccion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.app
import es.altamirano.calefaccion.data.Credentials
import es.altamirano.calefaccion.work.AlertsWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Las pantallas, como una pila: inicio → casa → semana / editor; y ajustes. */
private sealed interface Screen {
    data object Overview : Screen
    data object Settings : Screen
    data class Detail(val homeId: String) : Screen
    data class Week(val homeId: String, val scheduleId: String) : Screen
    data class Edit(val homeId: String, val scheduleId: String) : Screen
}

class MainActivity : ComponentActivity() {
    private val loginError = MutableStateFlow<String?>(null)
    private val loggingIn = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            CalefaccionTheme {
                val tokens by app.auth.tokens.collectAsStateWithLifecycle()
                if (tokens == null) LoginScreen(loginError.collectAsStateWithLifecycle().value,
                    loggingIn.collectAsStateWithLifecycle().value) { openLogin() }
                else AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (app.auth.isAuthenticated) app.houses.refreshIfStale()
    }

    /** La vuelta de Netatmo: `calefaccion-netatmo://oauth-callback?code=…&state=…`. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "calefaccion-netatmo") return
        lifecycleScope.launch {
            loggingIn.value = true
            loginError.value = null
            try {
                app.auth.handleCallback(uri)
                app.houses.load()
            } catch (e: Exception) {
                loginError.value = e.message
            }
            loggingIn.value = false
        }
    }

    private fun openLogin() {
        loginError.value = null
        val uri = try { app.auth.authorizationUri() } catch (e: Exception) {
            loginError.value = getString(R.string.configure_first); return
        }
        CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(Orange.toArgb()).build())
            .build().launchUrl(this, uri)
    }
}

@Composable
private fun AppNavigation() {
    var stack by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.listSaver(
        save = { it.map(::encode) }, restore = { it.map(::decode) })) { mutableStateOf(listOf<Screen>(Screen.Overview)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    if (stack.size > 1) BackHandler(onBack = pop)
    val app = LocalContext.current.app
    LaunchedEffect(Unit) { if (!app.houses.state.value.loaded) app.houses.load() }
    when (val s = stack.last()) {
        Screen.Overview -> OverviewScreen(onOpenHome = { push(Screen.Detail(it.id)) }, onSettings = { push(Screen.Settings) })
        Screen.Settings -> SettingsScreen(pop)
        is Screen.Detail -> DetailScreen(s.homeId, pop, onWeek = { push(Screen.Week(s.homeId, it)) }, onEdit = { push(Screen.Edit(s.homeId, it)) })
        is Screen.Week -> WeekScreen(s.homeId, s.scheduleId, pop)
        is Screen.Edit -> EditScreen(s.homeId, s.scheduleId, pop)
    }
}

private fun encode(s: Screen): String = when (s) {
    Screen.Overview -> "o"; Screen.Settings -> "s"; is Screen.Detail -> "d|${s.homeId}"
    is Screen.Week -> "w|${s.homeId}|${s.scheduleId}"; is Screen.Edit -> "e|${s.homeId}|${s.scheduleId}"
}

private fun decode(t: String): Screen = t.split("|").let { p ->
    when (p[0]) { "s" -> Screen.Settings; "d" -> Screen.Detail(p[1]); "w" -> Screen.Week(p[1], p[2]); "e" -> Screen.Edit(p[1], p[2]); else -> Screen.Overview }
}

// --- Inicio de sesión -------------------------------------------------------------------------

@Composable
private fun LoginScreen(error: String?, busy: Boolean, onLogin: () -> Unit) {
    var settings by remember { mutableStateOf(false) }
    if (settings) { BackHandler { settings = false }; SettingsScreen { settings = false }; return }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Desplazable: en una ventana baja (modo escritorio, móvil tumbado) no cabe entera.
        CenteredScroll(PaddingValues(32.dp)) {
            Image(painterResource(R.drawable.icon_ios), null, Modifier.size(120.dp).clip(RoundedCornerShape(27.dp)))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.login_subtitle), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(16.dp))
            if (busy) CircularProgressIndicator() else Button(onClick = onLogin, colors = ButtonDefaults.buttonColors(containerColor = Orange)) {
                Text(stringResource(R.string.connect_netatmo), color = Color.White)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
            TextButton(onClick = { settings = true }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.settings)) }
        }
    }
}

// --- Ajustes ------------------------------------------------------------------------------

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val tokens by app.auth.tokens.collectAsStateWithLifecycle()
    val duration by app.settings.manualDurationMinutes.collectAsStateWithLifecycle()
    val alerts by app.settings.alertsEnabled.collectAsStateWithLifecycle()
    val hidden by app.settings.hiddenHomes.collectAsStateWithLifecycle()
    val homes by app.houses.state.collectAsStateWithLifecycle()
    val saved = app.auth.credentials
    var clientId by remember { mutableStateOf(saved.clientId) }
    var clientSecret by remember { mutableStateOf(saved.clientSecret) }
    val notificationsOk = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        app.settings.setAlerts(ok); AlertsWorker.schedule(context, ok)
    }
    val savedMsg = stringResource(R.string.credentials_saved)
    val credsHint = stringResource(R.string.credentials_hint)
    val reconnectHint = stringResource(R.string.reconnect_hint)
    val durationHint = stringResource(R.string.manual_duration_hint)
    val alertsHint = stringResource(if (alerts && !notificationsOk) R.string.notifications_off else R.string.alerts_hint)
    val homesHint = stringResource(R.string.visible_homes_hint)

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { BackBar(stringResource(R.string.settings), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter(0.dp))) {
            section(null, credsHint) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Netatmo Connect", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("client_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(clientSecret, { clientSecret = it.trim() }, label = { Text("client_secret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(Credentials.REDIRECT_URI, {}, label = { Text("Redirect URI") }, singleLine = true, readOnly = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = clientId.isNotBlank() && clientSecret.isNotBlank() && (clientId != saved.clientId || clientSecret != saved.clientSecret),
                        onClick = { app.auth.saveCredentials(Credentials(clientId, clientSecret)); Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show() }) {
                        Text(stringResource(R.string.save_credentials))
                    }
                }
            }
            section(R.string.account, if (tokens != null) reconnectHint else null) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 14.dp)) {
                    Text("Netatmo", Modifier.weight(1f))
                    Text(stringResource(if (tokens != null) R.string.connected else R.string.not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tokens != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { app.auth.logout() }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            section(R.string.preferences, durationHint) { DurationPicker(duration) { app.settings.setDuration(it) } }
            section(R.string.alerts, alertsHint) {
                SwitchRow(stringResource(R.string.alerts_toggle), alerts) { on ->
                    if (on && !notificationsOk) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else { app.settings.setAlerts(on); AlertsWorker.schedule(context, on) }
                }
            }
            if (homes.homes.isNotEmpty()) section(R.string.visible_homes, homesHint) {
                homes.homes.forEachIndexed { i, h ->
                    SwitchRow(h.name, h.id !in hidden) { app.settings.setHidden(h.id, !it) }
                    if (i < homes.homes.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White))
    }
}

@Composable
private fun DurationPicker(minutes: Int, onPick: (Int) -> Unit) {
    val options = listOf(0 to R.string.use_netatmo, 60 to R.string.h1, 120 to R.string.h2, 180 to R.string.h3, 240 to R.string.h4)
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, Modifier.padding(16.dp)) {
        OutlinedTextField(stringResource(options.firstOrNull { it.first == minutes }?.second ?: R.string.use_netatmo), {},
            readOnly = true, label = { Text(stringResource(R.string.manual_duration)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { (m, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onPick(m); open = false }) }
        }
    }
}

