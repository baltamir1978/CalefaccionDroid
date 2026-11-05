package es.altamirano.calefaccion.net

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import es.altamirano.calefaccion.CalefaccionApp
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/**
 * La sesión de Netatmo, compartida con Tiempo ES (TiempoDroid, su pestaña Actual y su widget).
 *
 * Netatmo cambia el `refresh_token` en cada renovación y el viejo deja de valer: si las dos apps
 * renovaran la misma sesión por su cuenta, cada una tumbaría la de la otra. Así que la sesión es
 * de Calefacción y Tiempo le pide aquí un `access_token` válido (`call("token", stale)`), que
 * Calefacción renueva cuando hace falta. Sólo contesta a Tiempo firmada con su clave.
 *
 * Para que valga para la estación meteorológica, la sesión tiene que incluir `read_station`
 * (`AuthManager`, SCOPES): con una sesión iniciada antes de la 0.2 hay que volver a entrar.
 */
class SessionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "token") return null
        val caller = callingPackage ?: return null
        if (caller != TIEMPO || !signedBy(caller)) throw SecurityException("Sólo para Tiempo ES")
        val app = context?.applicationContext as? CalefaccionApp ?: return null
        val auth = app.auth
        return try {
            val token = runBlocking { if (arg != null) auth.refresh(arg) else auth.validAccessToken() }
            Bundle().apply {
                putString("access_token", token)
                putLong("expires_at", auth.tokens.value?.expiresAt ?: (System.currentTimeMillis() + 3600_000))
            }
        } catch (e: Exception) {
            Bundle().apply { putString("error", e.message) }
        }
    }

    private fun signedBy(pkg: String): Boolean = runCatching {
        val info = context!!.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo!!.apkContentsSigners.any { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } in TIEMPO_CERTS
        }
    }.getOrDefault(false)

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        const val TIEMPO = "es.altamirano.tiempo"
        /** SHA-256 del certificado de release de Tiempo y del de depuración de este equipo. */
        val TIEMPO_CERTS = setOf(
            "435010C239621F7ED81386E3013718747E66F785A94A71D51C80EEF170F882E3",
            "1389502CE36317BFB67513DF22DD4033809FE39B1183D67B295C0E26814BFA53",
        )
    }
}
