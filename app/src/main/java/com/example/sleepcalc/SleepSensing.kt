package com.example.sleepcalc

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest

/**
 * Датчики сна от Google (Sleep API). Телефон сам определяет сон по движению и свету
 * с минимальным расходом батареи. Наших фоновых служб нет: Google присылает готовый результат.
 */
object SleepSensing {
    /** Есть ли на телефоне сервисы Google. */
    fun available(ctx: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

    /** Выдано ли разрешение «Физическая активность». */
    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Работают ли сейчас датчики Google: сервисы есть, разрешение выдано, сбоев не было.
     * Если нет, приложение само использует умный разбор экрана.
     */
    fun googleActive(ctx: Context): Boolean =
        available(ctx) && hasPermission(ctx) && !Prefs.sp(ctx).getBoolean("sleepApiFail", false)

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, SleepReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, 77, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun enable(ctx: Context) {
        if (!available(ctx) || !hasPermission(ctx)) return
        try {
            val task = ActivityRecognition.getClient(ctx).requestSleepSegmentUpdates(
                pending(ctx),
                SleepSegmentRequest.getDefaultSleepSegmentRequest()
            )
            task.addOnSuccessListener {
                Prefs.sp(ctx).edit().putBoolean("sleepApiFail", false).apply()
            }
            task.addOnFailureListener {
                Prefs.sp(ctx).edit().putBoolean("sleepApiFail", true).apply()
            }
        } catch (e: Exception) {
            Prefs.sp(ctx).edit().putBoolean("sleepApiFail", true).apply()
        }
    }

    fun disable(ctx: Context) {
        try {
            ActivityRecognition.getClient(ctx).removeSleepSegmentUpdates(pending(ctx))
        } catch (e: Exception) {
            // нечего отключать
        }
    }
}

/** Принимает готовые ночи от Google и кладёт их в журнал. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SleepSegmentEvent.hasEvents(intent)) return
        for (e in SleepSegmentEvent.extractEvents(intent)) {
            if (e.status != 0) continue
            val n = Night(e.startTimeMillis / 60000, e.endTimeMillis / 60000, true, true)
            if (n.minutes < 150) continue
            SleepLog.addSensor(context, n)
        }
        SleepWidget.refreshAll(context)
    }
}
