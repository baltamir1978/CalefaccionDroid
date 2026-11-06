@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.calefaccion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.app
import es.altamirano.calefaccion.core.BatteryLevel
import es.altamirano.calefaccion.core.Home
import es.altamirano.calefaccion.core.ThermMode
import es.altamirano.calefaccion.data.HousesState
import es.altamirano.calefaccion.data.STEP
import kotlinx.coroutines.launch

@Composable
fun OverviewScreen(onOpenHome: (Home) -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.app
    val state by app.houses.state.collectAsStateWithLifecycle()
    val hidden by app.settings.hiddenHomes.collectAsStateWithLifecycle()
    val actionError by app.houses.actionError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val visible = state.homes.filter { it.id !in hidden }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary, actionIconContentColor = MaterialTheme.colorScheme.primary),
                navigationIcon = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.AccountCircle, stringResource(R.string.account)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { menu = false; scope.launch { app.houses.refreshStatuses() } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; app.auth.logout() })
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && !state.loaded -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error != null && !state.loaded -> Column(Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.load_failed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    Button(onClick = { scope.launch { app.houses.load() } }) { Text(stringResource(R.string.retry)) }
                }
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                    scope.launch { refreshing = true; app.houses.refreshStatuses(); refreshing = false }
                }) {
                    // En una ventana ancha (modo escritorio, tableta) las casas van en columnas.
                    LazyVerticalGrid(GridCells.Adaptive(360.dp), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = gutter(max = 1200.dp), vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (visible.isEmpty() && state.loaded) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.VisibilityOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.all_hidden), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.all_hidden_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(visible, key = { it.id }) { home -> HomeCard(home, state, onOpenHome) }
                    }
                }
            }
        }
    }

    actionError?.let { msg ->
        AlertDialog(onDismissRequest = { app.houses.clearActionError() },
            title = { Text(stringResource(R.string.apply_failed)) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { app.houses.clearActionError() }) { Text(stringResource(R.string.ok)) } })
    }
}

@Composable
private fun HomeCard(home: Home, s: HousesState, onOpen: (Home) -> Unit) {
    val app = LocalContext.current.app
    val colors = MaterialTheme.colorScheme
    val boiler = s.boilerOn(home)
    val batteries = remember(s.statuses[home.id]) { app.houses.batteries(home) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Cabecera: nombre, pilas del peor dispositivo y la caldera.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(home.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            batteries.firstOrNull()?.let { BatteryBars(it.level) }
            Spacer(Modifier.weight(1f))
            BoilerPill(boiler)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatTemp(s.current(home)), fontSize = 46.sp, fontWeight = FontWeight.Bold,
                    color = if (boiler) Orange else colors.onSurface)
                Text(stringResource(R.string.current_temp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (!s.reachable(home)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(14.dp), tint = Danger)
                    Text(" " + stringResource(R.string.offline), fontSize = 11.sp, color = Danger)
                }
            }
            if (s.thermostatRoomId(home) != null) ThermostatControl(home, s)
        }
        ModePicker(home, s)
        if (s.manualOverride(home)) ManualOverrideRow(home, s)
        val low = batteries.filter { it.isLow }
        if (low.isNotEmpty()) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Danger.copy(alpha = 0.10f)).padding(10.dp, 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BatteryBars(BatteryLevel.LOW)
            Text("  " + if (low.size == 1) stringResource(R.string.low_battery_in, low[0].name)
                else stringResource(R.string.low_battery_in_n, low.size),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Danger)
        }
        // Pie: horario activo y el enlace al detalle.
        Row(verticalAlignment = Alignment.CenterVertically) {
            home.selectedSchedule?.name?.let { name ->
                Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Text("  $name", fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable { onOpen(home) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.schedule_and_usage), fontSize = 13.sp, color = Orange)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Orange)
            }
        }
    }
}

@Composable
private fun BoilerPill(on: Boolean) {
    val label = stringResource(if (on) R.string.boiler_on_a11y else R.string.boiler_off_a11y)
    Row(
        Modifier.clip(CircleShape).background(if (on) Orange else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 5.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(Icons.Filled.LocalFireDepartment, null, Modifier.size(12.dp), tint = fg)
        Text(" " + stringResource(if (on) R.string.boiler_on else R.string.boiler_off), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** El control vertical + / objetivo / − que evoca el termostato Netatmo. */
@Composable
private fun ThermostatControl(home: Home, s: HousesState) {
    val app = LocalContext.current.app
    val enabled = s.reachable(home)
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Orange.copy(alpha = 0.08f))
            .border(1.5.dp, Orange.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StepButton(Icons.Filled.Add, stringResource(R.string.raise), enabled) { app.houses.adjustTarget(home, STEP) }
        Text(formatTemp(s.target(home)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(if (s.isBusy(home)) R.string.applying else R.string.target), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        StepButton(Icons.Filled.Remove, stringResource(R.string.lower), enabled) { app.houses.adjustTarget(home, -STEP) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(9.dp)).background(Orange.copy(alpha = if (enabled) 0.18f else 0.06f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Orange else Orange.copy(alpha = 0.4f)) }
}

@Composable
private fun ModePicker(home: Home, s: HousesState) {
    val app = LocalContext.current.app
    val current = s.mode(home)
    val modes = listOf(ThermMode.SCHEDULE to R.string.mode_schedule, ThermMode.AWAY to R.string.mode_away, ThermMode.FROST_GUARD to R.string.mode_hg)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = current == mode, enabled = !s.isBusy(home),
                onClick = { if (current != mode) app.houses.setMode(home, mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(activeContainerColor = Orange.copy(alpha = 0.18f), activeContentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text(stringResource(label), fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun ManualOverrideRow(home: Home, s: HousesState) {
    val app = LocalContext.current.app
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Orange.copy(alpha = 0.10f)).padding(10.dp, 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PanTool, null, Modifier.size(16.dp), tint = Orange)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.manual_set), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            s.manualEnd(home)?.let { Text(stringResource(R.string.until, formatEnd(it)), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedButton(onClick = { app.houses.resumeSchedule(home) }, enabled = !s.isBusy(home),
            border = BorderStroke(1.dp, Orange.copy(alpha = 0.6f)), contentPadding = PaddingValues(10.dp, 4.dp)) {
            Text(stringResource(R.string.back_to_schedule), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Orange)
        }
    }
}

/** Las pilas como en iOS: una batería con cuatro barras; roja si está baja. */
@Composable
fun BatteryBars(level: BatteryLevel, height: Dp = 12.dp) {
    val color = if (level.isLow) Danger else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(when (level) {
        BatteryLevel.LOW -> R.string.battery_low; BatteryLevel.MEDIUM -> R.string.battery_medium
        BatteryLevel.HIGH -> R.string.battery_high; BatteryLevel.FULL -> R.string.battery_full
    })
    Row(Modifier.semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.size(height * 2.1f, height).border(height * 0.09f, color, RoundedCornerShape(height * 0.22f)).padding(height * 0.16f),
            horizontalArrangement = Arrangement.spacedBy(height * 0.1f),
        ) {
            for (i in 1..4) Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(1.dp))
                .background(if (i <= level.bars) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)))
        }
        Box(Modifier.width(height * 0.11f).height(height * 0.38f).background(color))
    }
}
