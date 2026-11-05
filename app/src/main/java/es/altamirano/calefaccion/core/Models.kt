package es.altamirano.calefaccion.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Netatmo habla en snake_case; aquí, en camelCase, como en iOS (`convertFromSnakeCase`). */
@OptIn(ExperimentalSerializationApi::class)
val NetatmoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
    coerceInputValues = true
}

// --- homesdata ------------------------------------------------------------------------------

@Serializable data class HomesDataResponse(val body: HomesDataBody, val status: String = "")
@Serializable data class HomesDataBody(val homes: List<Home> = emptyList())

@Serializable
data class Home(
    val id: String,
    val name: String = "",
    val country: String? = null,
    val timezone: String? = null,
    val rooms: List<Room> = emptyList(),
    val modules: List<Module> = emptyList(),
    val schedules: List<HomeSchedule>? = null,
    val thermMode: String? = null,
    val thermSetpointDefaultDuration: Int? = null,
) {
    val currentThermMode: ThermMode? get() = ThermMode.of(thermMode)
    val selectedSchedule: HomeSchedule? get() = schedules?.firstOrNull { it.selected == true }

    /** Las habitaciones con termostato o válvula; la primera es «la del termostato». */
    val heatingRooms: List<Room>
        get() {
            val ids = modules.filter { it.isHeatingModule }.mapNotNull { it.roomId }.toSet()
            val filtered = rooms.filter { it.id in ids }
            return (filtered.ifEmpty { rooms }).sortedBy { it.name }
        }
}

@Serializable data class Room(val id: String, val name: String = "", val type: String? = null, val moduleIds: List<String>? = null)

@Serializable
data class Module(val id: String, val type: String = "", val name: String? = null, val roomId: String? = null, val bridge: String? = null) {
    val isHeatingModule: Boolean get() = type in HEATING_TYPES

    fun displayName(home: Home, valve: String, thermostat: String): String {
        if (!name.isNullOrEmpty()) return name
        home.rooms.firstOrNull { it.id == roomId }?.let { return it.name }
        return if (type == "NRV") valve else thermostat
    }

    companion object { val HEATING_TYPES = setOf("NATherm1", "NATherm2", "NRV", "OTM", "BNS") }
}

@Serializable
data class HomeSchedule(
    val id: String,
    val name: String? = null,
    val selected: Boolean? = null,
    val type: String? = null,
    val zones: List<ScheduleZone>? = null,
    val timetable: List<TimetableEntry>? = null,
    val awayTemp: Double? = null,
    val hgTemp: Double? = null,
)

@Serializable
data class ScheduleZone(val id: Int, val name: String? = null, val type: Int? = null, val rooms: List<ZoneRoomTemp>? = null) {
    fun temperature(roomId: String?): Double? =
        if (roomId == null) rooms?.firstOrNull()?.thermSetpointTemperature
        else rooms?.firstOrNull { it.id == roomId }?.thermSetpointTemperature ?: rooms?.firstOrNull()?.thermSetpointTemperature
}

@Serializable data class ZoneRoomTemp(val id: String, val thermSetpointTemperature: Double? = null)
@Serializable data class TimetableEntry(val zoneId: Int, val mOffset: Int? = null)

// --- homestatus -----------------------------------------------------------------------------

@Serializable data class HomeStatusResponse(val body: HomeStatusBody, val status: String = "")
@Serializable data class HomeStatusBody(val home: HomeStatus)

@Serializable
data class HomeStatus(
    val id: String,
    val thermMode: String? = null,
    val rooms: List<RoomStatus>? = null,
    val modules: List<ModuleStatus>? = null,
) {
    val currentThermMode: ThermMode? get() = ThermMode.of(thermMode)
}

@Serializable
data class RoomStatus(
    val id: String,
    val reachable: Boolean? = null,
    val anticipating: Boolean? = null,
    val heatingPowerRequest: Int? = null,
    val thermMeasuredTemperature: Double? = null,
    val thermSetpointTemperature: Double? = null,
    val thermSetpointMode: String? = null,
    val thermSetpointEndTime: Long? = null,
    val openWindow: Boolean? = null,
)

@Serializable
data class ModuleStatus(
    val id: String,
    val type: String? = null,
    val reachable: Boolean? = null,
    val batteryLevel: Int? = null,
    val batteryState: String? = null,
    val boilerStatus: Boolean? = null,
    val bridge: String? = null,
) {
    val battery: BatteryLevel? get() = BatteryLevel.ofState(batteryState) ?: BatteryLevel.ofMillivolts(batteryLevel, type)
}

// --- getroommeasure / respuestas ------------------------------------------------------------

@Serializable data class RoomMeasureResponse(val body: List<MeasureBlock> = emptyList(), val status: String = "")
@Serializable data class MeasureBlock(val begTime: Double, val stepTime: Double? = null, val value: List<List<Double?>> = emptyList())
@Serializable data class StatusResponse(val status: String = "")
@Serializable data class CreateScheduleResponse(val status: String = "", val body: CreateScheduleBody? = null)
@Serializable data class CreateScheduleBody(val scheduleId: String? = null)
@Serializable data class NetatmoErrorResponse(val error: NetatmoErrorBody? = null)
@Serializable data class NetatmoErrorBody(val code: Int? = null, val message: String? = null)

data class MeasurePoint(val time: Long, val value: Double)

enum class MeasureType(val api: String) { TEMPERATURE("temperature"), BOILER_ON("sum_boiler_on") }
enum class MeasureScale(val api: String) { HOUR("1hour"), THREE_HOURS("3hours"), DAY("1day"), WEEK("1week"), MONTH("1month") }

/** Los bloques de `getroommeasure` en puntos (fecha, valor), en orden. */
fun flattenMeasures(blocks: List<MeasureBlock>): List<MeasurePoint> = blocks.flatMap { b ->
    b.value.mapIndexedNotNull { i, row ->
        row.firstOrNull()?.let { MeasurePoint(((b.begTime + i * (b.stepTime ?: 0.0)) * 1000).toLong(), it) }
    }
}.sortedBy { it.time }

// --- Modos ------------------------------------------------------------------------------------

enum class ThermMode(val api: String) {
    SCHEDULE("schedule"), AWAY("away"), FROST_GUARD("hg");
    companion object { fun of(s: String?) = entries.firstOrNull { it.api == s } }
}

enum class SetpointMode(val api: String) { MANUAL("manual"), HOME("home"), MAX("max"), OFF("off") }

// --- Batería ---------------------------------------------------------------------------------

enum class BatteryLevel(val bars: Int) {
    LOW(1), MEDIUM(2), HIGH(3), FULL(4);

    val isLow get() = this == LOW

    companion object {
        fun ofState(state: String?): BatteryLevel? = when (state?.lowercase()) {
            "max", "full" -> FULL
            "high" -> HIGH
            "medium" -> MEDIUM
            "low", "very_low" -> LOW
            else -> null
        }

        /** Umbrales en mV de iOS: la válvula (NRV) va con dos pilas, el termostato con tres. */
        fun ofMillivolts(mv: Int?, type: String?): BatteryLevel? {
            if (mv == null || mv <= 0) return null
            val (full, high, medium) = if (type == "NRV") Triple(3200, 2700, 2400) else Triple(4100, 3600, 3300)
            return when {
                mv >= full -> FULL
                mv >= high -> HIGH
                mv >= medium -> MEDIUM
                else -> LOW
            }
        }
    }
}

data class ModuleBattery(val id: String, val name: String, val level: BatteryLevel) {
    val isLow get() = level.isLow
}

/** La batería de cada termostato y válvula, de peor a mejor. */
fun HomeStatus.batteries(home: Home, valve: String, thermostat: String): List<ModuleBattery> {
    val byId = (modules ?: emptyList()).associateBy { it.id }
    return home.modules.filter { it.isHeatingModule }.mapNotNull { m ->
        byId[m.id]?.battery?.let { ModuleBattery(m.id, m.displayName(home, valve, thermostat), it) }
    }.sortedWith(compareBy({ it.level }, { it.name }))
}
