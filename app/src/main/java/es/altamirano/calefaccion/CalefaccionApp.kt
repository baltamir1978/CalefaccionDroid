package es.altamirano.calefaccion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import es.altamirano.calefaccion.data.Houses
import es.altamirano.calefaccion.data.SecureStore
import es.altamirano.calefaccion.data.Settings
import es.altamirano.calefaccion.data.SnapshotCache
import es.altamirano.calefaccion.net.AuthManager
import es.altamirano.calefaccion.net.Energy
import es.altamirano.calefaccion.widget.refreshOutside
import es.altamirano.calefaccion.work.AlertsWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CalefaccionApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings; private set
    lateinit var auth: AuthManager; private set
    lateinit var energy: Energy; private set
    lateinit var cache: SnapshotCache; private set
    lateinit var houses: Houses; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        auth = AuthManager(SecureStore(this))
        energy = Energy(auth, settings)
        cache = SnapshotCache(this)
        // Cada vez que cambia lo que se sabe de las casas: widgets, mosaicos y atajos al día.
        houses = Houses(this, scope, energy, settings, cache) { refreshOutside(this) }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AlertsWorker.CHANNEL, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        AlertsWorker.schedule(this, settings.alertsEnabled.value)
    }
}

val Context.app: CalefaccionApp get() = applicationContext as CalefaccionApp
