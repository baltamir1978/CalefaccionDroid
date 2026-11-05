package es.altamirano.calefaccion.net

import android.net.Uri
import es.altamirano.calefaccion.core.CreateScheduleResponse
import es.altamirano.calefaccion.core.Home
import es.altamirano.calefaccion.core.HomeSchedule
import es.altamirano.calefaccion.core.HomeStatus
import es.altamirano.calefaccion.core.HomeStatusResponse
import es.altamirano.calefaccion.core.HomesDataResponse
import es.altamirano.calefaccion.core.MeasurePoint
import es.altamirano.calefaccion.core.MeasureScale
import es.altamirano.calefaccion.core.MeasureType
import es.altamirano.calefaccion.core.NetatmoErrorResponse
import es.altamirano.calefaccion.core.NetatmoJson
import es.altamirano.calefaccion.core.RoomMeasureResponse
import es.altamirano.calefaccion.core.ScheduleTemplate
import es.altamirano.calefaccion.core.SetpointMode
import es.altamirano.calefaccion.core.SyncRoom
import es.altamirano.calefaccion.core.SyncTimetableEntry
import es.altamirano.calefaccion.core.SyncZone
import es.altamirano.calefaccion.core.ThermMode
import es.altamirano.calefaccion.core.flattenMeasures
import es.altamirano.calefaccion.data.Credentials
import es.altamirano.calefaccion.data.SecureStore
import es.altamirano.calefaccion.data.Settings
import es.altamirano.calefaccion.data.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val API = "https://api.netatmo.com"
private const val SCOPES = "read_thermostat write_thermostat"

sealed class NetatmoError(message: String) : Exception(message) {
    class NotConfigured : NetatmoError("Faltan las credenciales de Netatmo")
    class NotAuthenticated : NetatmoError("No has iniciado sesión en Netatmo")
    class RefreshFailed : NetatmoError("No se pudo renovar la sesión")
    class Http(val status: Int, val code: Int?, detail: String?) : NetatmoError("Error $status${code?.let { " ($it)" } ?: ""}: ${detail ?: "sin detalle"}")
    class Network(cause: IOException) : NetatmoError(cause.message ?: "sin red")
    class OAuth(reason: String) : NetatmoError(reason)
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

/**
 * OAuth 2 de Netatmo (código de autorización; Netatmo no ofrece PKCE). Se abre la página de
 * Netatmo en una pestaña del navegador y vuelve por `calefaccion-netatmo://oauth-callback`.
 */
class AuthManager(private val store: SecureStore) {
    private val _tokens = MutableStateFlow(store.tokens())
    val tokens: StateFlow<Tokens?> = _tokens.asStateFlow()
    val isAuthenticated get() = _tokens.value != null

    private val refreshLock = Mutex()
    private var pendingState: String? = null

    val credentials: Credentials get() = store.credentials()
    fun saveCredentials(c: Credentials) = store.saveCredentials(c)

    /** La dirección de la página de Netatmo donde se inicia sesión. */
    fun authorizationUri(): Uri {
        val c = credentials
        if (!c.isComplete) throw NetatmoError.NotConfigured()
        val state = UUID.randomUUID().toString()
        pendingState = state
        return Uri.parse("$API/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", c.clientId)
            .appendQueryParameter("redirect_uri", Credentials.REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("response_type", "code")
            .build()
    }

    /** Vuelve de Netatmo con el código: se cambia por los tokens. */
    suspend fun handleCallback(uri: Uri) {
        uri.getQueryParameter("error")?.let { throw NetatmoError.OAuth(it) }
        val code = uri.getQueryParameter("code") ?: throw NetatmoError.OAuth("No llegó el código de autorización")
        val state = uri.getQueryParameter("state")
        if (pendingState != null && state != pendingState) throw NetatmoError.OAuth("El parámetro «state» no coincide")
        val c = credentials
        val t = requestToken(mapOf(
            "grant_type" to "authorization_code", "client_id" to c.clientId, "client_secret" to c.clientSecret,
            "code" to code, "redirect_uri" to Credentials.REDIRECT_URI, "scope" to SCOPES,
        ))
        store.saveTokens(t)
        _tokens.value = t
    }

    fun logout() {
        store.clearTokens()
        _tokens.value = null
    }

    suspend fun validAccessToken(): String {
        val t = _tokens.value ?: throw NetatmoError.NotAuthenticated()
        return if (!t.isExpired) t.accessToken else refresh(t.accessToken)
    }

    /**
     * Renueva una sola vez aunque lo pidan varias peticiones a la vez. Netatmo **cambia el
     * `refresh_token` en cada renovación** y el viejo deja de valer: el nuevo se guarda siempre.
     */
    suspend fun refresh(staleAccess: String? = null): String = refreshLock.withLock {
        val current = _tokens.value ?: throw NetatmoError.NotAuthenticated()
        // Otra petición ya renovó mientras se esperaba el cerrojo.
        if (staleAccess != null && current.accessToken != staleAccess && !current.isExpired) return current.accessToken
        val c = credentials
        val t = try {
            requestToken(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refreshToken,
                "client_id" to c.clientId, "client_secret" to c.clientSecret))
        } catch (e: NetatmoError.Http) {
            logout()
            throw NetatmoError.RefreshFailed()
        }
        store.saveTokens(t)
        _tokens.value = t
        t.accessToken
    }

    private suspend fun requestToken(params: Map<String, String>): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val req = Request.Builder().url("$API/oauth2/token").post(form).build()
        val body = try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw httpError(r.code, text)
                text
            }
        } catch (e: IOException) {
            throw NetatmoError.Network(e)
        }
        val o = NetatmoJson.parseToJsonElement(body) as JsonObject
        val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: o["expire_in"]?.jsonPrimitive?.longOrNull ?: 10800
        Tokens(o["access_token"]!!.jsonPrimitive.content, o["refresh_token"]!!.jsonPrimitive.content,
            System.currentTimeMillis() + expires * 1000)
    }
}

private fun httpError(status: Int, body: String): NetatmoError.Http {
    val err = runCatching { NetatmoJson.decodeFromString(NetatmoErrorResponse.serializer(), body) }.getOrNull()?.error
    return NetatmoError.Http(status, err?.code, err?.message)
}

/** La API Energy: lo que usa la app de iOS (`EnergyService.swift`). */
class Energy(private val auth: AuthManager, private val settings: Settings) {

    private suspend fun call(path: String, query: Map<String, String> = emptyMap(), form: Map<String, String>? = null, retry: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val token = auth.validAccessToken()
            val url = "$API$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            if (form != null) builder.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
            val (code, text) = try {
                http.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw NetatmoError.Network(e)
            }
            if ((code == 401 || code == 403) && retry) {
                // Token caducado: renovar y reintentar una vez.
                auth.refresh(token)
                return@withContext call(path, query, form, retry = false)
            }
            if (code !in 200..299) throw httpError(code, text)
            text
        }

    suspend fun homes(): List<Home> =
        NetatmoJson.decodeFromString(HomesDataResponse.serializer(), call("/api/homesdata")).body.homes

    suspend fun status(homeId: String): HomeStatus =
        NetatmoJson.decodeFromString(HomeStatusResponse.serializer(), call("/api/homestatus", mapOf("home_id" to homeId))).body.home

    suspend fun setRoomTemperature(homeId: String, roomId: String, temp: Double, endTime: Long? = settings.manualEndTime()) {
        val form = mutableMapOf("home_id" to homeId, "room_id" to roomId, "mode" to SetpointMode.MANUAL.api,
            "temp" to "%.1f".format(Locale.US, temp))
        endTime?.let { form["endtime"] = it.toString() }
        call("/api/setroomthermpoint", form = form)
    }

    suspend fun setRoomMode(homeId: String, roomId: String, mode: SetpointMode) {
        call("/api/setroomthermpoint", form = mapOf("home_id" to homeId, "room_id" to roomId, "mode" to mode.api))
    }

    suspend fun setThermMode(homeId: String, mode: ThermMode) {
        call("/api/setthermmode", form = mapOf("home_id" to homeId, "mode" to mode.api))
    }

    suspend fun switchSchedule(homeId: String, scheduleId: String) {
        call("/api/switchhomeschedule", form = mapOf("home_id" to homeId, "schedule_id" to scheduleId))
    }

    /**
     * `synchomeschedule` exige reenviar el horario **completo**: se parte del que trajo
     * `homesdata`, se cambian sólo las temperaturas y se reenvía con la `timetable` intacta.
     */
    suspend fun syncScheduleTemperatures(homeId: String, schedule: HomeSchedule, thermostatRoomId: String?,
                                         zoneTemps: Map<Int, Double>, awayTemp: Double, hgTemp: Double) {
        val zones = (schedule.zones ?: emptyList()).map { zone ->
            val newTemp = when (zone.type) { 2 -> awayTemp; 3 -> hgTemp; else -> zoneTemps[zone.id] }
            SyncZone(zone.id, zone.type ?: 0, (zone.rooms ?: emptyList()).map { r ->
                val applies = thermostatRoomId == null || r.id == thermostatRoomId
                SyncRoom(r.id, if (newTemp != null && applies) newTemp else r.thermSetpointTemperature ?: 0.0)
            })
        }
        val form = scheduleForm(homeId, zones, timetableOf(schedule), awayTemp, hgTemp).toMutableMap()
        form["schedule_id"] = schedule.id
        schedule.name?.let { form["name"] = it }
        call("/api/synchomeschedule", form = form)
    }

    suspend fun createSchedule(home: Home, name: String): String? = create(home.id, name,
        ScheduleTemplate.zones(home.heatingRooms.map { it.id }), ScheduleTemplate.timetable(),
        ScheduleTemplate.AWAY_TEMP, ScheduleTemplate.FROST_TEMP)

    suspend fun duplicateSchedule(homeId: String, s: HomeSchedule, name: String): String? = create(homeId, name,
        (s.zones ?: emptyList()).map { z -> SyncZone(z.id, z.type ?: 0, (z.rooms ?: emptyList()).map { SyncRoom(it.id, it.thermSetpointTemperature ?: 0.0) }) },
        timetableOf(s), s.awayTemp ?: ScheduleTemplate.AWAY_TEMP, s.hgTemp ?: ScheduleTemplate.FROST_TEMP)

    suspend fun renameSchedule(homeId: String, scheduleId: String, name: String) {
        call("/api/renamehomeschedule", form = mapOf("home_id" to homeId, "schedule_id" to scheduleId, "name" to name))
    }

    suspend fun deleteSchedule(homeId: String, scheduleId: String) {
        call("/api/deletehomeschedule", form = mapOf("home_id" to homeId, "schedule_id" to scheduleId))
    }

    suspend fun roomMeasure(homeId: String, roomId: String, type: MeasureType, scale: MeasureScale, from: Long, to: Long): List<MeasurePoint> {
        val text = call("/api/getroommeasure", mapOf(
            "home_id" to homeId, "room_id" to roomId, "scale" to scale.api, "type" to type.api,
            "date_begin" to (from / 1000).toString(), "date_end" to (to / 1000).toString(), "optimize" to "false"))
        return flattenMeasures(NetatmoJson.decodeFromString(RoomMeasureResponse.serializer(), text).body)
    }

    private suspend fun create(homeId: String, name: String, zones: List<SyncZone>, tt: List<SyncTimetableEntry>, away: Double, hg: Double): String? {
        val form = scheduleForm(homeId, zones, tt, away, hg) + ("name" to name)
        return NetatmoJson.decodeFromString(CreateScheduleResponse.serializer(), call("/api/createnewhomeschedule", form = form)).body?.scheduleId
    }

    private fun timetableOf(s: HomeSchedule) = (s.timetable ?: emptyList()).map { SyncTimetableEntry(it.zoneId, it.mOffset ?: 0) }

    private fun scheduleForm(homeId: String, zones: List<SyncZone>, tt: List<SyncTimetableEntry>, away: Double, hg: Double) = mapOf(
        "home_id" to homeId,
        "zones" to NetatmoJson.encodeToString(ListSerializer(SyncZone.serializer()), zones),
        "timetable" to NetatmoJson.encodeToString(ListSerializer(SyncTimetableEntry.serializer()), tt),
        "away_temp" to "%.1f".format(Locale.US, away),
        "hg_temp" to "%.1f".format(Locale.US, hg),
    )

}
