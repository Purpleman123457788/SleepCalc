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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Напоминание о подготовке ко сну. Одно запланированное уведомление в день
 * (без фоновых служб): за выбранное время до того, как захочется спать.
 */
object BedtimeReminder {
    private const val CHANNEL = "bedtime"
    private const val NAME = "bedtime-reminder"

    fun enabled(ctx: Context): Boolean = Prefs.sp(ctx).getBoolean("remOn", false)

    fun lead(ctx: Context): Int = Prefs.getInt(ctx, "remLead", 60)

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.sp(ctx).edit().putBoolean("remOn", on).apply()
        schedule(ctx)
    }

    fun setLead(ctx: Context, minutes: Int) {
        Prefs.putInt(ctx, "remLead", minutes)
        schedule(ctx)
    }

    /** Планирует ближайшее напоминание по текущему прогнозу. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!enabled(ctx)) {
            wm.cancelUniqueWork(NAME)
            return
        }
        try {
            val sleepy = WidgetCalc.load(ctx).sleepy
            val trigger = sleepy.minusMinutes(lead(ctx).toLong())
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var at = now.toLocalDate().atTime(trigger).atZone(zone)
            if (!at.isAfter(now.plusMinutes(1))) at = at.plusDays(1)
            val delay = Duration.between(now, at).toMinutes().coerceAtLeast(1)
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        } catch (e: Exception) {
            // напоминание не запланировано, попробуем при следующем изменении
        }
    }

    fun notifyNow(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Подготовка ко сну", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val sleepy = WidgetCalc.load(ctx).sleepy
        val open = Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Пора готовиться ко сну")
            .setContentText("Приглушите свет и уберите экраны. Захочется спать около ${sleepy.s()}.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1002, n)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (BedtimeReminder.enabled(ctx)) {
            BedtimeReminder.notifyNow(ctx)
            BedtimeReminder.schedule(ctx)
        }
        return Result.success()
    }
}
