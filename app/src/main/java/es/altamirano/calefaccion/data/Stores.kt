package es.altamirano.calefaccion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import es.altamirano.calefaccion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Un fichero cifrado con una clave AES del Android Keystore, que no sale del chip: el
 * equivalente al Llavero de iOS. `EncryptedSharedPreferences` está obsoleto; esto no necesita
 * ninguna librería. Se escribe a un temporal y se renombra: nunca queda a medias, que con el
 * `refresh_token` de Netatmo (cambia en cada renovación) sería perder la sesión.
 */
class SecureFile(context: Context, name: String) {
    private val file = File(context.noBackupFilesDir, name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    fun read(): String? = try {
        if (!file.exists()) null else {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, bytes[0].toInt()))
            String(cipher.doFinal(bytes, 1 + bytes[0], bytes.size - 1 - bytes[0]))
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(text.toByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + body)
        tmp.renameTo(file)
    }

    @Synchronized
    fun clear() { file.delete() }

    private companion object { const val ALIAS = "calefaccion" }
}

@Serializable
data class Credentials(val clientId: String = "", val clientSecret: String = "") {
    val isComplete get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    companion object {
        /**
         * La misma Redirect URI que la app de iOS: así no hay que tocar nada en dev.netatmo.com
         * (y la de iOS deja de usarse).
         */
        const val REDIRECT_URI = "calefaccion-netatmo://oauth-callback"
        val fallback get() = Credentials(BuildConfig.NETATMO_CLIENT_ID, BuildConfig.NETATMO_CLIENT_SECRET)
    }
}

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    /** Se da por caducado dos minutos antes, como en iOS. */
    val isExpired get() = System.currentTimeMillis() >= expiresAt - 120_000
}

class SecureStore(context: Context) {
    private val tokensFile = SecureFile(context, "netatmo-tokens.bin")
    private val credsFile = SecureFile(context, "netatmo-credentials.bin")

    fun tokens(): Tokens? = tokensFile.read()?.let { runCatching { json.decodeFromString<Tokens>(it) }.getOrNull() }
    fun saveTokens(t: Tokens) = tokensFile.write(json.encodeToString(Tokens.serializer(), t))
    fun clearTokens() = tokensFile.clear()

    fun credentials(): Credentials =
        credsFile.read()?.let { runCatching { json.decodeFromString<Credentials>(it) }.getOrNull() }
            ?.takeIf { it.isComplete } ?: Credentials.fallback
    fun saveCredentials(c: Credentials) = credsFile.write(json.encodeToString(Credentials.serializer(), c))
}

/** Preferencias: duración del ajuste manual, casas ocultas, avisos y la casa de cada widget. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private val _hidden = MutableStateFlow(prefs.getStringSet("hidden", emptySet())!!.toSet())
    val hiddenHomes: StateFlow<Set<String>> = _hidden.asStateFlow()
    private val _duration = MutableStateFlow(prefs.getInt("duration", 0))
    val manualDurationMinutes: StateFlow<Int> = _duration.asStateFlow()
    private val _alerts = MutableStateFlow(prefs.getBoolean("alerts", false))
    val alertsEnabled: StateFlow<Boolean> = _alerts.asStateFlow()

    fun setHidden(homeId: String, hidden: Boolean) {
        val s = if (hidden) _hidden.value + homeId else _hidden.value - homeId
        _hidden.value = s
        prefs.edit().putStringSet("hidden", s).apply()
    }

    fun setDuration(minutes: Int) { _duration.value = minutes; prefs.edit().putInt("duration", minutes).apply() }
    fun setAlerts(on: Boolean) { _alerts.value = on; prefs.edit().putBoolean("alerts", on).apply() }

    /** Fin del ajuste manual según la preferencia; null deja que Netatmo decida. */
    fun manualEndTime(now: Long = System.currentTimeMillis()): Long? =
        _duration.value.takeIf { it > 0 }?.let { (now / 1000) + it * 60L }

    fun widgetHome(widgetId: Int): String? = prefs.getString("widget-$widgetId", null)
    fun setWidgetHome(widgetId: Int, homeId: String) = prefs.edit().putString("widget-$widgetId", homeId).apply()

    // Avisos ya mandados: clave → cuándo (para no repetir).
    fun alertSentAt(key: String): Long? = prefs.getLong("sent-$key", 0).takeIf { it > 0 }
    fun markAlert(key: String) = prefs.edit().putLong("sent-$key", System.currentTimeMillis()).apply()
    fun clearAlert(key: String) = prefs.edit().remove("sent-$key").apply()
}

/** Lo último que se supo de cada casa, para los widgets y los mosaicos (como `SharedStore`). */
@Serializable
data class HomeSnapshot(
    val id: String,
    val name: String,
    val thermostatRoomId: String? = null,
    val current: Double? = null,
    val target: Double? = null,
    val boilerOn: Boolean = false,
    val mode: String? = null,
    val updatedAt: Long = 0,
)

class SnapshotCache(context: Context) {
    private val file = File(context.filesDir, "casas.json")
    private val _homes = MutableStateFlow(read())
    val homes: StateFlow<List<HomeSnapshot>> = _homes.asStateFlow()

    private fun read(): List<HomeSnapshot> = runCatching {
        json.decodeFromString<List<HomeSnapshot>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(list: List<HomeSnapshot>) {
        _homes.value = list
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSnapshot.serializer()), list))
        tmp.renameTo(file)
    }

    @Synchronized
    fun update(id: String, f: (HomeSnapshot) -> HomeSnapshot) =
        save(_homes.value.map { if (it.id == id) f(it) else it })
}
