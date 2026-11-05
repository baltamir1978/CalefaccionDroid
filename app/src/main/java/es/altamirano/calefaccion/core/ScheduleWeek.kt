package es.altamirano.calefaccion.core

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * La programación de Netatmo es una lista de cambios: cada entrada de la `timetable` lleva un
 * `m_offset` en minutos desde el lunes a las 00:00 y la zona que rige hasta el siguiente cambio.
 * Aquí se expande a franjas por día. La semana es cíclica: lo que hay antes del primer cambio lo
 * cubre la última zona de la lista. Traducción de `ScheduleWeek.swift`.
 */
data class ScheduleSlot(val day: Int, val start: Int, val end: Int, val zoneId: Int) {
    val duration get() = end - start
}

data class ScheduleDay(val index: Int, val slots: List<ScheduleSlot>)

object ScheduleWeek {
    const val MINUTES_PER_DAY = 1440
    const val MINUTES_PER_WEEK = MINUTES_PER_DAY * 7

    fun days(timetable: List<TimetableEntry>?): List<ScheduleDay> {
        val changes = (timetable ?: emptyList())
            .map { (it.mOffset ?: 0).coerceIn(0, MINUTES_PER_WEEK - 1) to it.zoneId }
            .sortedBy { it.first }
        if (changes.isEmpty()) return emptyList()
        val intervals = mutableListOf<Triple<Int, Int, Int>>()
        if (changes.first().first > 0) intervals += Triple(0, changes.first().first, changes.last().second)
        changes.forEachIndexed { i, (offset, zone) ->
            val end = if (i + 1 < changes.size) changes[i + 1].first else MINUTES_PER_WEEK
            if (end > offset) intervals += Triple(offset, end, zone)
        }
        return (0 until 7).map { day ->
            val dayStart = day * MINUTES_PER_DAY
            val dayEnd = dayStart + MINUTES_PER_DAY
            val slots = intervals.mapNotNull { (s, e, z) ->
                val start = maxOf(s, dayStart)
                val end = minOf(e, dayEnd)
                if (start < end) ScheduleSlot(day, start - dayStart, end - dayStart, z) else null
            }.sortedBy { it.start }
            ScheduleDay(day, merged(slots))
        }
    }

    private fun merged(slots: List<ScheduleSlot>): List<ScheduleSlot> {
        val out = mutableListOf<ScheduleSlot>()
        for (s in slots) {
            val prev = out.lastOrNull()
            if (prev != null && prev.zoneId == s.zoneId && prev.end == s.start) out[out.lastIndex] = prev.copy(end = s.end)
            else out += s
        }
        return out
    }

    /** Lunes = 0 … domingo = 6. */
    fun dayIndex(cal: Calendar = Calendar.getInstance()): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
}

// --- Lo que se manda a synchomeschedule / createnewhomeschedule -----------------------------

@Serializable data class SyncZone(val id: Int, val type: Int, val rooms: List<SyncRoom>)
@Serializable data class SyncRoom(val id: String, val thermSetpointTemperature: Double)
@Serializable data class SyncTimetableEntry(val zoneId: Int, val mOffset: Int)

/**
 * La semana tipo de un horario nuevo: las cuatro zonas que Netatmo exige (confort, noche,
 * ausente, antihielo) más una eco; confort a las 6:30, eco a las 9:00, confort a las 17:00 y
 * noche a partir de las 23:00.
 */
object ScheduleTemplate {
    const val COMFORT = 0; const val NIGHT = 1; const val AWAY = 2; const val FROST = 3; const val ECO = 4
    const val COMFORT_TEMP = 20.0; const val NIGHT_TEMP = 17.0; const val ECO_TEMP = 18.0
    const val AWAY_TEMP = 12.0; const val FROST_TEMP = 7.0
    private val dayChanges = listOf(390 to COMFORT, 540 to ECO, 1020 to COMFORT, 1380 to NIGHT)

    fun zones(roomIds: List<String>): List<SyncZone> {
        fun zone(id: Int, type: Int, t: Double) = SyncZone(id, type, roomIds.map { SyncRoom(it, t) })
        return listOf(
            zone(COMFORT, 0, COMFORT_TEMP), zone(NIGHT, 1, NIGHT_TEMP), zone(AWAY, 2, AWAY_TEMP),
            zone(FROST, 3, FROST_TEMP), zone(ECO, 5, ECO_TEMP),
        )
    }

    fun timetable(): List<SyncTimetableEntry> =
        listOf(SyncTimetableEntry(NIGHT, 0)) + (0 until 7).flatMap { d ->
            dayChanges.map { (m, z) -> SyncTimetableEntry(z, d * ScheduleWeek.MINUTES_PER_DAY + m) }
        }
}

/** «Tengo frío»: 1 °C sobre la temperatura actual, al medio grado. */
fun warmUpTarget(measured: Double?, setpoint: Double?): Double {
    val base = measured ?: setpoint ?: 20.0
    return Math.round((base + 1) * 2) / 2.0
}
