package com.example.sleepcalc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Показывает уведомление «Доступно обновление». */
object UpdateNotifier {
    private const val CHANNEL = "updates"

    /** Возвращает true, если уведомление показано. */
    fun show(ctx: Context, av: UpdateState.Available): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Доступно обновление SleepCalc")
            .setContentText("Версия 1.${av.code}. Откройте приложение, чтобы обновить.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
        return true
    }
}

/** Раз в 12 часов проверяет новую версию в фоне (один лёгкий запрос). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Settings.load(ctx).notifyUpdates) return Result.success()
        try {
            val av = Updater.fetchLatest(ctx)
            if (av != null && av.code > Prefs.getInt(ctx, "notifiedCode", 0)) {
                if (UpdateNotifier.show(ctx, av)) Prefs.putInt(ctx, "notifiedCode", av.code)
            }
        } catch (e: Exception) {
            // нет сети или лимит GitHub: попробуем в следующий раз
        }
        return Result.success()
    }
}

object UpdateScheduler {
    private const val NAME = "update-check"

    fun apply(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
