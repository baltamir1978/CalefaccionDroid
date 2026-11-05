package es.altamirano.calefaccion.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.altamirano.calefaccion.R
import es.altamirano.calefaccion.app
import es.altamirano.calefaccion.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Avisos de batería baja y de termostato sin conexión (`AlertsService.swift`). WorkManager mira
 * las casas cada hora (Netatmo va a limitar las consultas periódicas: ver CLAUDE.md) en segundo plano; no hace falta Google Play (GrapheneOS).
 *
 * - Batería baja: se avisa, y se recuerda a los tres días si sigue baja.
 * - Sin conexión: se avisa una vez, y otra si vuelve a pasar tras recuperarse.
 */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.auth.isAuthenticated || !app.settings.alertsEnabled.value) return Result.success()
        app.houses.load()
        val state = app.houses.state.value
        if (state.error != null) return Result.retry()
        val hidden = app.settings.hiddenHomes.value
        for (home in state.homes.filter { it.id !in hidden }) {
            for (b in app.houses.batteries(home)) {
                val key = "battery-${b.id}"
                if (!b.isLow) { app.settings.clearAlert(key); continue }
                val last = app.settings.alertSentAt(key)
                if (last == null || System.currentTimeMillis() - last >= BATTERY_REMINDER_MS) {
                    notify(key, applicationContext.getString(R.string.battery_low),
                        applicationContext.getString(R.string.alert_battery_body, b.name, home.name))
                }
            }
            val reachable = state.roomStatus(home)?.reachable ?: continue
            val key = "offline-${home.id}"
            if (reachable) app.settings.clearAlert(key)
            else if (app.settings.alertSentAt(key) == null) {
                notify(key, applicationContext.getString(R.string.alert_offline_title),
                    applicationContext.getString(R.string.alert_offline_body, home.name))
            }
        }
        return Result.success()
    }

    private fun notify(key: String, title: String, body: String) {
        val ctx = applicationContext
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(key.hashCode(), n)
        ctx.app.settings.markAlert(key)
    }

    companion object {
        const val CHANNEL = "avisos"
        private const val NAME = "avisos-termostatos"
        private const val BATTERY_REMINDER_MS = 3L * 24 * 3600 * 1000

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<AlertsWorker>(60, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
