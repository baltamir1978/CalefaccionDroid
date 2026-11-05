package es.altamirano.calefaccion.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.app
import es.altamirano.calefaccion.core.ThermMode
import es.altamirano.calefaccion.data.HomeSnapshot
import es.altamirano.calefaccion.ui.CalefaccionTheme
import es.altamirano.calefaccion.ui.MainActivity
import es.altamirano.calefaccion.ui.Orange
import es.altamirano.calefaccion.ui.formatTemp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widgets, mosaicos y atajos al día con lo que se sabe de las casas. */
fun refreshOutside(context: Context) {
    val app = context.app
    app.scope.launch {
        runCatching { StatusWidget().updateAll(context) }
        runCatching { WarmUpWidget().updateAll(context) }
    }
    publishShortcuts(context, app.houses.visibleSnapshots())
    for (c in listOf(WarmUpTile1::class.java, WarmUpTile2::class.java, AwayTile1::class.java, AwayTile2::class.java)) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, c)) }
    }
}

/**
 * La casa de un widget: la que se eligió al ponerlo; con una sola casa visible, esa. Con varias
 * y ninguna elegida, ninguna: **nunca se elige una casa por sorteo** (regla del original).
 */
private suspend fun homeFor(context: Context, id: GlanceId): HomeSnapshot? {
    val app = context.app
    val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    val visible = app.houses.visibleSnapshots()
    val chosen = app.settings.widgetHome(widgetId)
    return app.cache.homes.value.firstOrNull { it.id == chosen } ?: visible.singleOrNull()
}

private val Ink = ColorProvider(Color(0xFF1C1C1E))
private val White = ColorProvider(Color.White)
private val Muted = ColorProvider(Color(0xFF6B6B70))

// --- Estado: actual y objetivo; fondo naranja cuando calienta -------------------------------

class StatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        provideContent {
            val homes by app.cache.homes.collectAsState()
            val home = homes.firstOrNull { it.id == app.settings.widgetHome(GlanceAppWidgetManager(context).getAppWidgetId(id)) }
                ?: app.houses.visibleSnapshots().singleOrNull()
            GlanceTheme { StatusContent(home) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusContent(h: HomeSnapshot?) {
    val ctx = LocalContext.current
    val heating = h?.boilerOn == true
    val fg = if (heating) White else Ink
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
            .background(if (heating) ColorProvider(Orange) else ColorProvider(Color.White))
            .clickable(actionStartActivity<MainActivity>()).padding(12.dp),
    ) {
        if (h == null) {
            Text(ctx.getString(R.string.pick_home), style = TextStyle(color = Muted, fontSize = 13.sp))
            return@Box
        }
        Column(GlanceModifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, maxLines = 1, style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    modifier = GlanceModifier.defaultWeight())
                if (heating) Image(ImageProvider(R.drawable.ic_flame), null, GlanceModifier.size(16.dp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(formatTemp(h.current), style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 34.sp))
            Text("${ctx.getString(R.string.target)} ${formatTemp(h.target)}",
                style = TextStyle(color = if (heating) White else Muted, fontSize = 12.sp))
        }
    }
}

// --- «Tengo frío»: un botón que sube 1 °C durante 2 horas sin abrir la app ----------------

private val HomeKey = ActionParameters.Key<String>("home")

class WarmUpWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val home = homeFor(context, id)
        provideContent { GlanceTheme { WarmUpContent(home) } }
    }
}

@androidx.compose.runtime.Composable
private fun WarmUpContent(h: HomeSnapshot?) {
    val ctx = LocalContext.current
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
            .background(ColorProvider(Orange))
            .clickable(if (h != null) actionRunCallback<WarmUpAction>(actionParametersOf(HomeKey to h.id)) else actionStartActivity<MainActivity>())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.ic_thermometer_up), null, GlanceModifier.size(30.dp))
        Spacer(GlanceModifier.height(4.dp))
        Text(ctx.getString(R.string.warm_up), style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center))
        Text(h?.name ?: ctx.getString(R.string.pick_home), maxLines = 1,
            style = TextStyle(color = White, fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}

class WarmUpAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val homeId = parameters[HomeKey] ?: return
        warmUpWithToast(context, homeId)
    }
}

/** «Tengo frío» desde fuera de la app (widget, mosaico, acceso directo), con un aviso breve. */
suspend fun warmUpWithToast(context: Context, homeId: String) {
    val app = context.app
    val message = if (!app.auth.isAuthenticated) context.getString(R.string.warm_sign_in) else try {
        val (name, target) = app.houses.warmUp(homeId)
        context.getString(R.string.warm_done, name, formatTemp(target))
    } catch (e: Exception) {
        context.getString(R.string.warm_failed) + (e.message?.let { ": $it" } ?: "")
    }
    kotlinx.coroutines.withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
}

class StatusWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = StatusWidget() }
class WarmUpWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WarmUpWidget() }

/** Al poner un widget: con una casa, se elige sola; con varias, se pregunta. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val homes = app.houses.visibleSnapshots()
        if (homes.size == 1) { choose(widgetId, homes[0].id); return }
        setContent {
            CalefaccionTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.pick_home), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.pick_home_hint), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp))
                        if (homes.isEmpty()) Text(stringResource(R.string.warm_sign_in))
                        homes.forEach { h ->
                            ListItem(headlineContent = { Text(h.name) }, modifier = Modifier.clickable { choose(widgetId, h.id) })
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, homeId: String) {
        app.settings.setWidgetHome(widgetId, homeId)
        refreshOutside(this)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

// --- Mosaicos de Ajustes rápidos: «Tengo frío» y «Ausente», uno por casa -----------------

/**
 * Los mosaicos se declaran fijos en el manifiesto, así que hay dos de cada (casa 1 y casa 2,
 * por orden alfabético de las visibles), y cada uno lleva el nombre de su casa: nunca hay duda
 * de a cuál se refiere.
 */
abstract class HomeTile(private val slot: Int, private val away: Boolean) : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    private fun home(): HomeSnapshot? = app.houses.visibleSnapshots().sortedBy { it.name }.getOrNull(slot)

    override fun onStartListening() {
        val tile = qsTile ?: return
        val h = home()
        if (h == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(if (away) R.string.tile_away else R.string.warm_up)
            tile.subtitle = getString(R.string.tile_no_home)
        } else {
            tile.label = getString(if (away) R.string.tile_away else R.string.warm_up)
            tile.subtitle = h.name
            tile.state = if (away) (if (h.mode == ThermMode.AWAY.api) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE) else Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    override fun onClick() {
        val h = home() ?: return
        scope.launch {
            if (away) {
                val message = try {
                    val mode = app.houses.toggleAway(h.id)
                    getString(if (mode == ThermMode.AWAY) R.string.away_on else R.string.away_off, h.name)
                } catch (e: Exception) {
                    getString(R.string.apply_failed)
                }
                Toast.makeText(this@HomeTile, message, Toast.LENGTH_SHORT).show()
            } else {
                warmUpWithToast(this@HomeTile, h.id)
            }
            onStartListening()
        }
    }
}

class WarmUpTile1 : HomeTile(0, away = false)
class WarmUpTile2 : HomeTile(1, away = false)
class AwayTile1 : HomeTile(0, away = true)
class AwayTile2 : HomeTile(1, away = true)

// --- Accesos directos: «Tengo frío en <casa>» -------------------------------------------

fun publishShortcuts(context: Context, homes: List<HomeSnapshot>) {
    val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
    val list = homes.take(max).map { h ->
        ShortcutInfoCompat.Builder(context, "warm-${h.id}")
            .setShortLabel(context.getString(R.string.warm_up) + " · " + h.name)
            .setLongLabel(context.getString(R.string.warm_up) + " · " + h.name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_warm))
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("calefaccion://tengo-frio?casa=${h.id}"))
                .setClass(context, WarmUpActivity::class.java))
            .build()
    }
    runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
}

/** Sin pantalla: hace «Tengo frío» y se cierra (para los accesos directos). */
class WarmUpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val homeId = intent?.data?.getQueryParameter("casa")
            ?: app.houses.visibleSnapshots().singleOrNull()?.id
        if (homeId == null) {
            Toast.makeText(this, R.string.warm_which, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        app.scope.launch { warmUpWithToast(applicationContext, homeId) }
        finish()
    }
}

