package es.altamirano.calefaccion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleWeekTest {
    @Test fun cyclicWeekCoversMondayMorningWithLastZone() {
        // Lunes 6:30 confort, 23:00 noche; nada antes del lunes 6:30 → lo cubre la última (noche).
        val days = ScheduleWeek.days(listOf(TimetableEntry(0, 390), TimetableEntry(1, 1380)))
        assertEquals(7, days.size)
        val monday = days[0].slots
        assertEquals(ScheduleSlot(0, 0, 390, 1), monday[0])
        assertEquals(ScheduleSlot(0, 390, 1380, 0), monday[1])
        assertEquals(ScheduleSlot(0, 1380, 1440, 1), monday[2])
        // El martes entero es noche: se junta en una sola franja.
        assertEquals(listOf(ScheduleSlot(1, 0, 1440, 1)), days[1].slots)
    }

    @Test fun templateHasFourChangesPerDayPlusStart() {
        val tt = ScheduleTemplate.timetable()
        assertEquals(1 + 7 * 4, tt.size)
        val days = ScheduleWeek.days(tt.map { TimetableEntry(it.zoneId, it.mOffset) })
        val sunday = days[6].slots.map { it.start to it.zoneId }
        assertEquals(listOf(0 to ScheduleTemplate.NIGHT, 390 to ScheduleTemplate.COMFORT, 540 to ScheduleTemplate.ECO,
            1020 to ScheduleTemplate.COMFORT, 1380 to ScheduleTemplate.NIGHT), sunday)
        assertEquals(setOf(0, 1, 2, 3, 5), ScheduleTemplate.zones(listOf("r")).map { it.type }.toSet())
    }

    @Test fun emptyTimetable() = assertTrue(ScheduleWeek.days(null).isEmpty())
}

class CoreTest {
    @Test fun warmUpRoundsToHalfDegree() {
        assertEquals(21.5, warmUpTarget(20.4, null), 1e-9)
        assertEquals(22.0, warmUpTarget(20.8, 19.0), 1e-9)
        assertEquals(20.0, warmUpTarget(null, 19.0), 1e-9)
        assertEquals(21.0, warmUpTarget(null, null), 1e-9)
    }

    @Test fun batteryThresholds() {
        assertEquals(BatteryLevel.FULL, BatteryLevel.ofState("max"))
        assertEquals(BatteryLevel.LOW, BatteryLevel.ofState("very_low"))
        assertEquals(BatteryLevel.LOW, BatteryLevel.ofMillivolts(2300, "NRV"))
        assertEquals(BatteryLevel.HIGH, BatteryLevel.ofMillivolts(2800, "NRV"))
        assertEquals(BatteryLevel.MEDIUM, BatteryLevel.ofMillivolts(3400, "NATherm1"))
        assertNull(BatteryLevel.ofMillivolts(0, null))
    }

    @Test fun parsesHomesAndStatus() {
        val homes = NetatmoJson.decodeFromString(HomesDataResponse.serializer(), """
            {"body":{"homes":[{"id":"h1","name":"La Granja","rooms":[{"id":"r2","name":"Salón"},{"id":"r1","name":"Baño"}],
             "modules":[{"id":"m1","type":"NATherm1","room_id":"r2"},{"id":"g","type":"NAPlug"}],
             "schedules":[{"id":"s1","name":"Invierno","selected":true,"zones":[{"id":0,"type":0,"rooms":[{"id":"r2","therm_setpoint_temperature":20.5}]}],
                           "timetable":[{"zone_id":0,"m_offset":0}],"away_temp":12,"hg_temp":7}],"therm_mode":"schedule"}]},"status":"ok"}
        """).body.homes
        val h = homes.single()
        assertEquals("Salón", h.heatingRooms.single().name)
        assertEquals("Invierno", h.selectedSchedule?.name)
        assertEquals(20.5, h.selectedSchedule!!.zones!![0].temperature("r2")!!, 1e-9)
        assertEquals(ThermMode.SCHEDULE, h.currentThermMode)

        val st = NetatmoJson.decodeFromString(HomeStatusResponse.serializer(), """
            {"body":{"home":{"id":"h1","therm_mode":"away","rooms":[{"id":"r2","reachable":true,"therm_measured_temperature":19.2,
             "therm_setpoint_temperature":21,"therm_setpoint_mode":"manual","therm_setpoint_end_time":1700000000,"heating_power_request":30}],
             "modules":[{"id":"m1","type":"NATherm1","battery_state":"low"},{"id":"g","boiler_status":true}]}},"status":"ok"}
        """).body.home
        assertEquals(ThermMode.AWAY, st.currentThermMode)
        assertEquals(19.2, st.rooms!![0].thermMeasuredTemperature!!, 1e-9)
        assertEquals(1700000000L, st.rooms!![0].thermSetpointEndTime)
        val b = st.batteries(h, "Válvula", "Termostato").single()
        assertEquals("Salón", b.name)
        assertTrue(b.isLow)
    }

    @Test fun flattensMeasures() {
        val pts = flattenMeasures(listOf(MeasureBlock(1000.0, 3600.0, listOf(listOf(19.0), listOf(null), listOf(20.0)))))
        assertEquals(listOf(MeasurePoint(1_000_000, 19.0), MeasurePoint((1000 + 7200) * 1000L, 20.0)), pts)
    }
}
