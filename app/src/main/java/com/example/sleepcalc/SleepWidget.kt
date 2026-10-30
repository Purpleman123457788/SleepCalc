package com.example.sleepcalc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalTime

/** Виджет рабочего стола: когда захочется спать. */
class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        /** Обновляет все добавленные на рабочий стол виджеты. */
        fun refreshAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, SleepWidget::class.java))
                if (ids.isEmpty()) return
                val views = build(ctx)
                for (id in ids) mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                // виджет обновится в следующий раз
            }
        }

        fun build(ctx: Context): RemoteViews {
            val goal = Settings.load(ctx).goalMin
            val last = SleepLog.load(ctx).lastOrNull()
            val fresh = last != null && SleepLog.nowMin() - last.wake < 36 * 60

            val bed = if (last != null && fresh) last.bedTime
            else LocalTime.ofSecondOfDay(Prefs.getInt(ctx, "fc_bed", 23 * 60) * 60L)
            val wake = if (last != null && fresh) last.wakeTime
            else LocalTime.ofSecondOfDay(Prefs.getInt(ctx, "fc_wake", 7 * 60) * 60L)

            val f = SleepMath.forecast(bed, wake, goal)

            val nowMin = LocalTime.now().toSecondOfDay() / 60
            val left = (f.sleepy.toSecondOfDay() / 60 - nowMin + 1440) % 1440
            val leftText = when {
                left > 1440 - 180 -> "уже пора спать"
                left >= 60 -> "через ${left / 60} ч ${left % 60} мин"
                else -> "через $left мин"
            }
            val source = if (fresh) "по записи сна" else "по вашим данным"

            val rv = RemoteViews(ctx.packageName, R.layout.widget_sleep)
            rv.setTextViewText(R.id.w_time, f.sleepy.s())
            rv.setTextViewText(R.id.w_sub, leftText)
            rv.setTextViewText(R.id.w_note, "Сон ${fmtDur(f.sleptMin)} · $source")

            val open = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            return rv
        }
    }
}
