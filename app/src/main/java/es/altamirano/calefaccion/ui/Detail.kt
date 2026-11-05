@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.calefaccion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.app
import es.altamirano.calefaccion.core.Home
import es.altamirano.calefaccion.core.HomeSchedule
import es.altamirano.calefaccion.core.MeasurePoint
import es.altamirano.calefaccion.core.MeasureScale
import es.altamirano.calefaccion.core.MeasureType
import es.altamirano.calefaccion.core.ScheduleWeek
import es.altamirano.calefaccion.core.ScheduleZone
import es.altamirano.calefaccion.data.MAX_TEMP
import es.altamirano.calefaccion.data.MIN_TEMP
import es.altamirano.calefaccion.data.STEP
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
            navigationIconContentColor = MaterialTheme.colorScheme.primary, actionIconContentColor = MaterialTheme.colorScheme.primary),
    )
}

/** Una sección agrupada al estilo de los `List` de iOS: cabecera, tarjeta blanca y pie. */
fun LazyListScope.section(header: Int?, footer: String? = null, content: @Composable () -> Unit) {
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            header?.let {
                Text(stringResource(it).uppercase(Locale.getDefault()), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
            footer?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 6.dp))
            }
        }
    }
}

@Composable
fun zoneName(z: ScheduleZone?): String {
    if (z == null) return ""
    if (!z.name.isNullOrEmpty()) return z.name
    return when (z.type) {
        0 -> stringResource(R.string.zone_comfort); 1 -> stringResource(R.string.zone_night)
        2 -> stringResource(R.string.zone_away); 3 -> stringResource(R.string.zone_hg)
        4 -> stringResource(R.string.zone_comfort_plus); 5 -> stringResource(R.string.zone_eco)
        else -> stringResource(R.string.zone_n, z.id)
    }
}

private enum class NameAction { CREATE, RENAME, DUPLICATE }

@Composable
fun DetailScreen(homeId: String, onBack: () -> Unit, onWeek: (String) -> Unit, onEdit: (String) -> Unit) {
    val app = LocalContext.current.app
    val state by app.houses.state.collectAsStateWithLifecycle()
    val home = state.homes.firstOrNull { it.id == homeId } ?: run { onBack(); return }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var nameAction by remember { mutableStateOf<Pair<NameAction, HomeSchedule?>?>(null) }
    var toDelete by remember { mutableStateOf<HomeSchedule?>(null) }
    val schedules = home.schedules ?: emptyList()
    val active = home.selectedSchedule
    val copyOf = stringResource(R.string.copy_of, "%s")
    val schedulesHint = stringResource(R.string.schedules_hint)
    val editHint = stringResource(R.string.edit_temps_hint, active?.name ?: "")
    val batteryHint = stringResource(R.string.battery_hint)

    fun run(action: suspend () -> Unit) = scope.launch {
        working = true; error = null
        try { action(); app.houses.load() } catch (e: Exception) { error = e.message }
        working = false
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        BackBar(home.name, onBack) {
            if (working) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            IconButton(onClick = { nameAction = NameAction.CREATE to null }) { Icon(Icons.Filled.Add, stringResource(R.string.new_schedule)) }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            section(R.string.schedules, schedulesHint) {
                schedules.forEachIndexed { i, s ->
                    var menu by remember { mutableStateOf(false) }
                    val isActive = s.id == active?.id
                    Row(Modifier.fillMaxWidth().clickable(enabled = !isActive && !working) {
                        run { app.energy.switchSchedule(home.id, s.id) }
                    }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isActive) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (isActive) Orange else MaterialTheme.colorScheme.outline)
                        Text(s.name ?: stringResource(R.string.schedule), Modifier.weight(1f).padding(horizontal = 12.dp),
                            fontWeight = if (isActive) FontWeight.SemiBold else null)
                        if (isActive) Text(stringResource(R.string.active), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; nameAction = NameAction.RENAME to s })
                                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { menu = false; nameAction = NameAction.DUPLICATE to s })
                                // Netatmo no deja borrar el horario activo, ni el último.
                                if (schedules.size > 1 && !isActive) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; toDelete = s })
                            }
                        }
                    }
                    if (i < schedules.lastIndex) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            active?.let { s ->
                section(null, editHint) {
                    NavRow(Icons.Filled.CalendarMonth, stringResource(R.string.view_week)) { onWeek(s.id) }
                    HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    NavRow(Icons.Filled.Thermostat, stringResource(R.string.edit_temps)) { onEdit(s.id) }
                }
            }
            val batteries = app.houses.batteries(home)
            if (batteries.isNotEmpty()) section(R.string.battery, if (batteries.any { it.isLow }) batteryHint else null) {
                batteries.forEachIndexed { i, b ->
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name, Modifier.weight(1f), color = if (b.isLow) Danger else MaterialTheme.colorScheme.onSurface)
                        BatteryBars(b.level, 14.dp)
                    }
                    if (i < batteries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            state.thermostatRoomId(home)?.let { roomId -> usageSections(home, roomId) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    nameAction?.let { (action, schedule) ->
        var draft by remember(action, schedule) {
            mutableStateOf(when (action) {
                NameAction.CREATE -> ""
                NameAction.RENAME -> schedule?.name ?: ""
                NameAction.DUPLICATE -> nextCopyName(copyOf.format(schedule?.name ?: ""), schedules)
            })
        }
        AlertDialog(onDismissRequest = { nameAction = null },
            title = { Text(stringResource(when (action) {
                NameAction.CREATE -> R.string.new_schedule; NameAction.RENAME -> R.string.rename_schedule
                NameAction.DUPLICATE -> R.string.duplicate_schedule })) },
            text = { OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.schedule_name)) }, singleLine = true) },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
                val name = draft.trim(); nameAction = null
                run {
                    when (action) {
                        NameAction.CREATE -> app.energy.createSchedule(home, name)
                        NameAction.RENAME -> app.energy.renameSchedule(home.id, schedule!!.id, name)
                        NameAction.DUPLICATE -> app.energy.duplicateSchedule(home.id, schedule!!, name)
                    }
                }
            }) { Text(stringResource(if (action == NameAction.RENAME) R.string.rename else R.string.create)) } },
            dismissButton = { TextButton(onClick = { nameAction = null }) { Text(stringResource(R.string.cancel)) } })
    }
    toDelete?.let { s ->
        AlertDialog(onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_schedule_q)) }, text = { Text(stringResource(R.string.delete_cant_undo)) },
            confirmButton = { TextButton(onClick = { toDelete = null; run { app.energy.deleteSchedule(home.id, s.id) } }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.schedule_change_failed)) },
            text = { Text(msg) }, confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}

/** «Copia de X», y si ya existe, «Copia de X 2», 3… */
private fun nextCopyName(base: String, schedules: List<HomeSchedule>): String {
    val taken = schedules.mapNotNull { it.name }.toSet()
    if (base !in taken) return base
    var i = 2
    while ("$base $i" in taken) i++
    return "$base $i"
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Orange)
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

// --- Uso: gráficas dibujadas aquí (sin librería) --------------------------------------------

private fun LazyListScope.usageSections(home: Home, roomId: String) {
    item {
        val app = LocalContext.current.app
        var temps by remember { mutableStateOf<List<MeasurePoint>?>(null) }
        var boiler by remember { mutableStateOf<List<MeasurePoint>?>(null) }
        LaunchedEffect(home.id) {
            val now = System.currentTimeMillis()
            temps = runCatching { app.energy.roomMeasure(home.id, roomId, MeasureType.TEMPERATURE, MeasureScale.HOUR, now - 24 * 3600_000L, now) }.getOrDefault(emptyList())
            boiler = runCatching { app.energy.roomMeasure(home.id, roomId, MeasureType.BOILER_ON, MeasureScale.DAY, now - 14 * 86400_000L, now) }.getOrDefault(emptyList())
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.usage_title).uppercase(Locale.getDefault()), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.temp_24h), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when (val t = temps) {
                    null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> if (t.isEmpty()) Text(stringResource(R.string.no_data_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else LineChart(t)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.boiler_time) + " · " + stringResource(R.string.usage_days), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                when (val b = boiler) {
                    null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> if (b.isEmpty()) Text(stringResource(R.string.no_usage), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else BarChart(b)
                }
            }
            Text(stringResource(R.string.usage_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 6.dp))
        }
    }
}

@Composable
private fun LineChart(points: List<MeasurePoint>) {
    val min = points.minOf { it.value }; val max = points.maxOf { it.value }
    val lo = kotlin.math.floor(min) - 0.5; val hi = kotlin.math.ceil(max) + 0.5
    val t0 = points.first().time; val t1 = points.last().time.coerceAtLeast(t0 + 1)
    val desc = "${formatTemp(min)} – ${formatTemp(max)}"
    Row {
        Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(formatTemp(hi), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTemp(lo), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(140.dp).padding(start = 6.dp).semantics { contentDescription = desc }) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = (p.time - t0).toFloat() / (t1 - t0) * size.width
                val y = size.height - ((p.value - lo) / (hi - lo)).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Orange, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

@Composable
private fun BarChart(points: List<MeasurePoint>) {
    val max = points.maxOf { it.value }.coerceAtLeast(1.0)
    val dayFmt = java.text.SimpleDateFormat("d", Locale.getDefault())
    val total = points.sumOf { it.value }
    val desc = "${(total / 3600).toInt()} h"
    Column {
        Text("${String.format(Locale.getDefault(), "%.1f", max / 3600)} h", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = desc }) {
            val n = points.size
            val slot = size.width / n
            points.forEachIndexed { i, p ->
                val h = (p.value / max).toFloat() * size.height
                drawRoundRect(Orange, Offset(i * slot + slot * 0.15f, size.height - h), Size(slot * 0.7f, h),
                    androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            points.forEach { p ->
                Text(dayFmt.format(Date(p.time)), Modifier.weight(1f), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

// --- Horario semanal (sólo lectura) -------------------------------------------------------

@Composable
fun WeekScreen(homeId: String, scheduleId: String, onBack: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.houses.state.collectAsStateWithLifecycle()
    val home = state.homes.firstOrNull { it.id == homeId } ?: run { onBack(); return }
    val schedule = home.schedules?.firstOrNull { it.id == scheduleId } ?: run { onBack(); return }
    val roomId = state.thermostatRoomId(home)
    val zones = (schedule.zones ?: emptyList()).associateBy { it.id }
    val days = remember(schedule) { ScheduleWeek.days(schedule.timetable) }
    val today = ScheduleWeek.dayIndex()
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    val dayNames = remember {
        val f = java.text.SimpleDateFormat("EEEE", Locale.getDefault())
        val c = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY) }
        (0 until 7).map { f.format(c.time).replaceFirstChar { ch -> ch.uppercase() }.also { c.add(Calendar.DAY_OF_YEAR, 1) } }
    }

    val weekHint = stringResource(R.string.week_hint)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { BackBar(schedule.name ?: "", onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (days.isEmpty()) item {
                Text(stringResource(R.string.no_week, schedule.name ?: ""), Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else section(R.string.week, weekHint) {
                days.forEachIndexed { i, day ->
                    val open = expanded[day.index] == true
                    Column(Modifier.fillMaxWidth().clickable { expanded[day.index] = !open }.padding(16.dp, 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (day.index == today) stringResource(R.string.today, dayNames[day.index]) else dayNames[day.index],
                                Modifier.weight(1f), fontWeight = if (day.index == today) FontWeight.Bold else null, fontSize = 14.sp)
                            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.height(6.dp))
                        DayBar(day.slots.map { it.start to it.end }, day.slots.map { zoneColor(zones[it.zoneId]?.type) })
                        if (open) day.slots.forEach { slot ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(zoneColor(zones[slot.zoneId]?.type)))
                                Text("  ${formatMinuteOfDay(slot.start)}", fontSize = 13.sp, modifier = Modifier.width(64.dp))
                                Text(zoneName(zones[slot.zoneId]), Modifier.weight(1f), fontSize = 13.sp)
                                Text(formatTemp(zones[slot.zoneId]?.temperature(roomId)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (i < days.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            section(R.string.zones) {
                (schedule.zones ?: emptyList()).sortedBy { it.id }.forEachIndexed { i, z ->
                    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(zoneColor(z.type)))
                        Text(zoneName(z), Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text(formatTemp(z.temperature(roomId)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < (schedule.zones?.size ?: 0) - 1) HorizontalDivider(Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Una barra de 24 h con las franjas en su color y marcas cada 6 h. */
@Composable
private fun DayBar(spans: List<Pair<Int, Int>>, colors: List<androidx.compose.ui.graphics.Color>) {
    val tick = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(5.dp))) {
        spans.forEachIndexed { i, (s, e) ->
            val x0 = s / 1440f * size.width; val x1 = e / 1440f * size.width
            drawRect(colors[i], Offset(x0, 0f), Size(x1 - x0, size.height))
        }
        for (h in listOf(6, 12, 18)) {
            val x = h / 24f * size.width
            drawLine(tick, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}

// --- Editor de temperaturas de un horario ------------------------------------------------

@Composable
fun EditScreen(homeId: String, scheduleId: String, onBack: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.houses.state.collectAsStateWithLifecycle()
    val home = state.homes.firstOrNull { it.id == homeId } ?: run { onBack(); return }
    val schedule = home.schedules?.firstOrNull { it.id == scheduleId } ?: run { onBack(); return }
    val roomId = state.thermostatRoomId(home)
    val scope = rememberCoroutineScope()

    // Las zonas normales se editan; ausente y antihielo van aparte, como en iOS.
    val editable = remember(schedule) { (schedule.zones ?: emptyList()).filter { it.type != 2 && it.type != 3 }.sortedBy { it.id } }
    val original = remember(schedule) { editable.associate { it.id to (it.temperature(roomId) ?: 20.0) } }
    val origAway = remember(schedule) { schedule.awayTemp ?: schedule.zones?.firstOrNull { it.type == 2 }?.temperature(roomId) ?: 12.0 }
    val origHg = remember(schedule) { schedule.hgTemp ?: schedule.zones?.firstOrNull { it.type == 3 }?.temperature(roomId) ?: 7.0 }
    val temps = remember(schedule) { mutableStateMapOf<Int, Double>().apply { putAll(original) } }
    var away by remember(schedule) { mutableStateOf(origAway) }
    var hg by remember(schedule) { mutableStateOf(origHg) }
    var confirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val zoneNames = editable.associate { it.id to zoneName(it) }
    val awayLabel = stringResource(R.string.zone_away); val hgLabel = stringResource(R.string.zone_hg)
    val editHint = stringResource(R.string.edit_temps_hint, schedule.name ?: "")
    val changes = buildList {
        editable.forEach { z -> if (temps[z.id] != original[z.id]) add(Triple(zoneNames[z.id] ?: "", original[z.id]!!, temps[z.id]!!)) }
        if (away != origAway) add(Triple(awayLabel, origAway, away))
        if (hg != origHg) add(Triple(hgLabel, origHg, hg))
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        BackBar(stringResource(R.string.edit_temps), onBack) {
            TextButton(enabled = changes.isNotEmpty() && !saving, onClick = { confirm = true }) { Text(stringResource(R.string.review)) }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            section(R.string.zones, editHint) {
                editable.forEachIndexed { i, z ->
                    TempRow(zoneNames[z.id] ?: "", zoneColor(z.type), temps[z.id] ?: 20.0) { temps[z.id] = (it).coerceIn(MIN_TEMP, MAX_TEMP) }
                    if (i < editable.lastIndex) HorizontalDivider(Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            section(R.string.special) {
                TempRow(awayLabel, zoneColor(2), away) { away = it.coerceIn(MIN_TEMP, MAX_TEMP) }
                HorizontalDivider(Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TempRow(hgLabel, zoneColor(3), hg) { hg = it.coerceIn(MIN_TEMP, MAX_TEMP) }
            }
        }
    }

    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.confirm_changes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                changes.forEach { (label, from, to) -> Text("$label: ${formatTemp(from)} → ${formatTemp(to)}") }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.overwrite_hint, schedule.name ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = {
            confirm = false
            scope.launch {
                saving = true
                try {
                    app.energy.syncScheduleTemperatures(home.id, schedule, roomId, temps.toMap(), away, hg)
                    app.houses.load()
                    onBack()
                } catch (e: Exception) { error = e.message }
                saving = false
            }
        }) { Text(stringResource(R.string.save_to_netatmo)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.save_failed)) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } })
    }
}

@Composable
private fun TempRow(label: String, color: androidx.compose.ui.graphics.Color, value: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp))
        IconButton(onClick = { onChange(value - STEP) }) { Icon(Icons.Filled.Remove, stringResource(R.string.lower), tint = Orange) }
        Text(formatTemp(value), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange(value + STEP) }) { Icon(Icons.Filled.Add, stringResource(R.string.raise), tint = Orange) }
    }
}

