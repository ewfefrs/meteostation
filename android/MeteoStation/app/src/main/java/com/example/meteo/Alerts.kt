package com.example.meteo

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit

class MeteoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannel(this)
        Alerts.schedule(this, Settings(this).alertsEnabled)
    }
}

object Alerts {
    private const val CHANNEL = "meteo_alerts"
    private const val WORK = "meteo_alert_check"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Предупреждения о погоде", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Падение давления, сухой воздух"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Проверка раз в 15 минут (минимальный период WorkManager). */
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (enabled) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

/** Фоновая проверка: быстрое падение давления и сухой воздух. */
class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = Settings(applicationContext)
        if (!s.alertsEnabled) return Result.success()
        val api = MeteoApi(s.host)
        return try {
            val now = api.now()
            val cache = HistoryCache(applicationContext)
            val old = cache.load()
            val records = cache.merge(old, api.history(old.lastOrNull()?.ts ?: 0L))
            val nowSec = System.currentTimeMillis() / 1000

            val dp = Physics.tendency3h(records, s.altitude)
            if (dp != null && dp <= -s.pressureDrop && nowSec - s.lastPressureAlert > 3 * 3600) {
                Alerts.notify(
                    applicationContext, 1, "Давление быстро падает",
                    String.format(Locale.forLanguageTag("ru"), "За 3 часа: %.1f гПа. Вероятно приближение циклона: усиление ветра, осадки.", dp)
                )
                s.lastPressureAlert = nowSec
            }
            val h = now.h
            if (s.indoor && h != null && h < s.humidityLow && nowSec - s.lastHumidityAlert > 6 * 3600) {
                Alerts.notify(
                    applicationContext, 2, "Сухой воздух",
                    String.format(Locale.forLanguageTag("ru"), "Влажность %.0f %% — ниже нормы (30–45 %% по ГОСТ 30494-2011). Включите увлажнитель.", h)
                )
                s.lastHumidityAlert = nowSec
            }
            Result.success()
        } catch (e: Exception) {
            Result.success()   // станция вне сети — попробуем в следующий раз
        }
    }
}
