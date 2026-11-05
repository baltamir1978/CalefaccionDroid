package es.altamirano.calefaccion.data

import android.content.Context
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.core.Home
import es.altamirano.calefaccion.core.HomeStatus
import es.altamirano.calefaccion.core.ModuleBattery
import es.altamirano.calefaccion.core.RoomStatus
import es.altamirano.calefaccion.core.SetpointMode
import es.altamirano.calefaccion.core.ThermMode
import es.altamirano.calefaccion.core.batteries
import es.altamirano.calefaccion.core.warmUpTarget
import es.altamirano.calefaccion.net.Energy
import es.altamirano.calefaccion.net.NetatmoError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MIN_TEMP = 5.0
const val MAX_TEMP = 30.0
const val STEP = 0.5

data class HousesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val homes: List<Home> = emptyList(),
    val statuses: Map<String, HomeStatus> = emptyMap(),
    /** Objetivo que se ve mientras se aplica (el control responde al momento). */
    val localTargets: Map<String, Double> = emptyMap(),
    val localModes: Map<String, ThermMode> = emptyMap(),
    val busy: Set<String> = emptySet(),
    val lastRefresh: Long = 0,
) {
    fun thermostatRoomId(home: Home) = home.heatingRooms.firstOrNull()?.id

    fun roomStatus(home: Home): RoomStatus? {
        val rooms = statuses[home.id]?.rooms ?: return null
        val id = thermostatRoomId(home)
        return rooms.firstOrNull { it.id == id } ?: rooms.firstOrNull { it.thermMeasuredTemperature != null }
    }

    fun current(home: Home) = roomStatus(home)?.thermMeasuredTemperature
    fun target(home: Home) = localTargets[home.id] ?: roomStatus(home)?.thermSetpointTemperature
    fun mode(home: Home) = localModes[home.id] ?: statuses[home.id]?.currentThermMode ?: home.currentThermMode ?: ThermMode.SCHEDULE
    fun boilerOn(home: Home) = statuses[home.id]?.modules?.any { it.boilerStatus == true } == true
    fun reachable(home: Home) = roomStatus(home)?.reachable ?: true
    fun manualOverride(home: Home) = roomStatus(home)?.thermSetpointMode.let { it == "manual" || it == "max" }
    fun manualEnd(home: Home): Long? = roomStatus(home)?.thermSetpointEndTime?.takeIf { it > 0 }?.times(1000)
    fun isBusy(home: Home) = home.id in busy
}

/**
 * El estado de las casas, en un solo sitio para la pantalla, los widgets, los mosaicos y los
 * avisos. Traducción de `OverviewViewModel.swift`, más «Tengo frío» (`WarmUpHomeIntent.swift`).
 */
class Houses(
    private val context: Context,
    private val scope: CoroutineScope,
    private val energy: Energy,
    private val settings: Settings,
    private val cache: SnapshotCache,
    private val onCacheChanged: () -> Unit,
) {
    private val _state = MutableStateFlow(HousesState())
    val state: StateFlow<HousesState> = _state.asStateFlow()
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()
    private val applyJobs = mutableMapOf<String, Job>()

    fun clearActionError() { _actionError.value = null }

    fun batteries(home: Home): List<ModuleBattery> =
        _state.value.statuses[home.id]?.batteries(home, context.getString(R.string.valve), context.getString(R.string.thermostat))
            ?: emptyList()

    suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val homes = energy.homes()
            _state.update { it.copy(homes = homes) }
            refreshStatuses()
            _state.update { it.copy(loading = false, loaded = true) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    suspend fun refreshStatuses() {
        for (home in _state.value.homes) {
            val status = runCatching { energy.status(home.id) }.getOrNull() ?: continue
            _state.update { s ->
                val modes = status.currentThermMode?.let { s.localModes + (home.id to it) } ?: s.localModes
                val withStatus = s.copy(statuses = s.statuses + (home.id to status), localModes = modes)
                // Si no hay un cambio en curso, el objetivo vuelve a ser el de Netatmo.
                val setpoint = withStatus.roomStatus(home)?.thermSetpointTemperature
                if (home.id !in applyJobs && setpoint != null && setpoint > 0)
                    withStatus.copy(localTargets = s.localTargets + (home.id to setpoint)) else withStatus
            }
        }
        _state.update { it.copy(lastRefresh = System.currentTimeMillis()) }
        updateCache()
    }

    /** Al volver a la app: si lo último tiene más de 2 minutos. */
    fun refreshIfStale() {
        val s = _state.value
        if (!s.loaded) { scope.launch { load() }; return }
        if (System.currentTimeMillis() - s.lastRefresh > 120_000) scope.launch { refreshStatuses() }
    }

    /** + / −: se ve al momento y se manda pasados 0,9 s sin tocar, como en iOS. */
    fun adjustTarget(home: Home, delta: Double) {
        val s = _state.value
        val roomId = s.thermostatRoomId(home) ?: return
        val target = ((s.target(home) ?: 20.0) + delta).coerceIn(MIN_TEMP, MAX_TEMP)
        _state.update { it.copy(localTargets = it.localTargets + (home.id to target)) }
        applyJobs[home.id]?.cancel()
        applyJobs[home.id] = scope.launch {
            delay(900)
            _state.update { it.copy(busy = it.busy + home.id) }
            try {
                energy.setRoomTemperature(home.id, roomId, target)
            } catch (e: Exception) {
                _actionError.value = e.message
            }
            applyJobs.remove(home.id)
            _state.update { it.copy(busy = it.busy - home.id) }
            refreshStatuses()
        }
    }

    fun setMode(home: Home, mode: ThermMode) {
        val previous = _state.value.mode(home)
        _state.update { it.copy(localModes = it.localModes + (home.id to mode)) }
        run(home, onFailure = { _state.update { it.copy(localModes = it.localModes + (home.id to previous)) } }) {
            energy.setThermMode(home.id, mode)
        }
    }

    fun resumeSchedule(home: Home) {
        val roomId = _state.value.thermostatRoomId(home) ?: return
        run(home) { energy.setRoomMode(home.id, roomId, SetpointMode.HOME) }
    }

    private fun run(home: Home, onFailure: () -> Unit = {}, action: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = it.busy + home.id) }
            try {
                action()
            } catch (e: Exception) {
                onFailure()
                _actionError.value = e.message
            }
            _state.update { it.copy(busy = it.busy - home.id) }
            refreshStatuses()
        }
    }

    /** Las casas que se ven (las ocultas no cuentan para widgets ni atajos). */
    fun visibleSnapshots(): List<HomeSnapshot> = cache.homes.value.filter { it.id !in settings.hiddenHomes.value }

    /**
     * «Tengo frío»: el objetivo 1 °C sobre la temperatura actual durante 2 horas, sin abrir la
     * app. Con una sola casa va directo; con varias hay que decir cuál (nunca se elige sola).
     */
    suspend fun warmUp(homeId: String): Pair<String, Double> {
        var snap = cache.homes.value.firstOrNull { it.id == homeId }
        if (snap?.thermostatRoomId == null) {
            // Sin caché (la app no se ha abierto): preguntar a Netatmo.
            val home = energy.homes().firstOrNull { it.id == homeId } ?: throw NetatmoError.OAuth(context.getString(R.string.warm_no_home))
            snap = HomeSnapshot(home.id, home.name, home.heatingRooms.firstOrNull()?.id)
        }
        val roomId = snap.thermostatRoomId ?: throw NetatmoError.OAuth(context.getString(R.string.warm_no_thermostat))
        val room = energy.status(homeId).rooms?.firstOrNull { it.id == roomId }
        val target = warmUpTarget(room?.thermMeasuredTemperature, room?.thermSetpointTemperature)
        energy.setRoomTemperature(homeId, roomId, target, System.currentTimeMillis() / 1000 + 2 * 3600)
        cache.update(homeId) { it.copy(target = target, updatedAt = System.currentTimeMillis()) }
        onCacheChanged()
        scope.launch { if (_state.value.loaded) refreshStatuses() }
        return snap.name to target
    }

    /** Activa o quita el modo ausente (el interruptor del mosaico). */
    suspend fun toggleAway(homeId: String): ThermMode {
        val snap = cache.homes.value.firstOrNull { it.id == homeId }
        val next = if (snap?.mode == ThermMode.AWAY.api) ThermMode.SCHEDULE else ThermMode.AWAY
        energy.setThermMode(homeId, next)
        cache.update(homeId) { it.copy(mode = next.api, updatedAt = System.currentTimeMillis()) }
        onCacheChanged()
        scope.launch { if (_state.value.loaded) refreshStatuses() }
        return next
    }

    private fun updateCache() {
        val s = _state.value
        cache.save(s.homes.map { h ->
            HomeSnapshot(h.id, h.name, s.thermostatRoomId(h), s.current(h), s.target(h), s.boilerOn(h),
                s.mode(h).api, System.currentTimeMillis())
        })
        onCacheChanged()
    }
}
