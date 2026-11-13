package es.altamirano.calefaccion.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sólo `invalid_grant` cierra la sesión: un fallo pasajero de Netatmo no debe obligar a volver a entrar. */
class TokenErrorTest {
    @Test fun invalidGrantEndsTheSession() =
        assertTrue(isInvalidGrant(400, """{"error":"invalid_grant"}"""))

    @Test fun otherOAuthErrorsDoNot() {
        assertFalse(isInvalidGrant(400, """{"error":"invalid_request"}"""))
        assertFalse(isInvalidGrant(400, """{"error":{"code":21,"message":"Invalid [refresh_token]"}}"""))
    }

    @Test fun serverTroubleDoesNot() {
        assertFalse(isInvalidGrant(500, """{"error":"invalid_grant"}"""))
        assertFalse(isInvalidGrant(503, "<html>Service Unavailable</html>"))
        assertFalse(isInvalidGrant(429, ""))
        assertFalse(isInvalidGrant(400, ""))
    }
}

/** `synchomeschedule` reenvía el horario entero: un fallo aquí cambia la calefacción de casa. */
class SyncZonesTest {
    private fun zone(id: Int, type: Int, vararg rooms: Pair<String, Double?>) =
        es.altamirano.calefaccion.core.ScheduleZone(id, type = type, rooms = rooms.map { es.altamirano.calefaccion.core.ZoneRoomTemp(it.first, it.second) })

    private val schedule = es.altamirano.calefaccion.core.HomeSchedule("s", zones = listOf(
        zone(0, 0, "salon" to 21.0, "cuarto" to 19.0),   // confort
        zone(1, 1, "salon" to 17.0, "cuarto" to 16.0),   // noche
        zone(2, 2, "salon" to 12.0, "cuarto" to 12.0),   // ausente
        zone(3, 3, "salon" to 7.0, "cuarto" to 7.0),     // antihielo
        zone(4, 5, "salon" to 18.0, "cuarto" to null),   // eco
    ))

    private fun temps(zones: List<es.altamirano.calefaccion.core.SyncZone>, id: Int) =
        zones.first { it.id == id }.rooms.associate { it.id to it.thermSetpointTemperature }

    @Test fun onlyTheThermostatRoomChanges() {
        val z = syncZones(schedule, "salon", mapOf(0 to 22.5), awayTemp = 13.0, hgTemp = 8.0)
        org.junit.Assert.assertEquals(mapOf("salon" to 22.5, "cuarto" to 19.0), temps(z, 0))
        // Sin temperatura nueva, la zona se reenvía como estaba.
        org.junit.Assert.assertEquals(mapOf("salon" to 17.0, "cuarto" to 16.0), temps(z, 1))
    }

    @Test fun awayAndFrostTakeTheirOwnTemperatures() {
        val z = syncZones(schedule, "salon", mapOf(2 to 30.0, 3 to 30.0), awayTemp = 13.0, hgTemp = 8.0)
        org.junit.Assert.assertEquals(mapOf("salon" to 13.0, "cuarto" to 12.0), temps(z, 2))
        org.junit.Assert.assertEquals(mapOf("salon" to 8.0, "cuarto" to 7.0), temps(z, 3))
    }

    @Test fun withoutThermostatRoomEveryRoomChanges() {
        val z = syncZones(schedule, null, mapOf(0 to 20.0), awayTemp = 12.0, hgTemp = 7.0)
        org.junit.Assert.assertEquals(mapOf("salon" to 20.0, "cuarto" to 20.0), temps(z, 0))
    }

    @Test fun zonesKeepTheirOrderIdsAndTypes() {
        val z = syncZones(schedule, "salon", emptyMap(), awayTemp = 12.0, hgTemp = 7.0)
        org.junit.Assert.assertEquals(listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3, 4 to 5), z.map { it.id to it.type })
        // Una habitación sin temperatura en el horario va a 0.0, como hasta ahora.
        org.junit.Assert.assertEquals(mapOf("salon" to 18.0, "cuarto" to 0.0), temps(z, 4))
    }

    @Test fun emptySchedule() =
        assertTrue(syncZones(es.altamirano.calefaccion.core.HomeSchedule("s"), "salon", emptyMap(), 12.0, 7.0).isEmpty())
}
