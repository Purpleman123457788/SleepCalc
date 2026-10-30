package com.example.sleepcalc

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalTime

/** Данные для всех виджетов. */
data class WidgetInfo(
    val sleepy: LocalTime,
    val left: String,       // «через 3 ч 20 мин»
    val short: String,      // «через 3 ч»
    val sleptMin: Int,
    val deficit: Int,
    val windowMin: Int,
    val wake: LocalTime,
    val fresh: Boolean,     // посчитано по записи сна, а не по введённым данным
    val progress: Int       // сколько процентов окна бодрствования уже прошло
)

object WidgetCalc {
    fun load(ctx: Context): WidgetInfo {
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
        val shortText = when {
            left > 1440 - 180 -> "уже пора"
            left >= 60 -> "через ${left / 60} ч"
            else -> "через $left мин"
        }
        val since = (nowMin - wake.toSecondOfDay() / 60 + 1440) % 1440
        val progress = if (left > 1440 - 180) 100 else (since * 100 / f.windowMin).coerceIn(0, 100)

        return WidgetInfo(f.sleepy, leftText, shortText, f.sleptMin, f.deficit, f.windowMin, wake, fresh, progress)
    }
}

private fun openApp(ctx: Context): PendingIntent =
    PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

private fun buildStandard(ctx: Context): RemoteViews {
    val i = WidgetCalc.load(ctx)
    val rv = RemoteViews(ctx.packageName, R.layout.widget_sleep)
    rv.setTextViewText(R.id.w_time, i.sleepy.s())
    rv.setTextViewText(R.id.w_sub, i.left)
    rv.setTextViewText(
        R.id.w_note,
        "Сон ${fmtDur(i.sleptMin)} · " + if (i.fresh) "по записи сна" else "по вашим данным"
    )
    rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    return rv
}

private fun buildSmall(ctx: Context): RemoteViews {
    val i = WidgetCalc.load(ctx)
    val rv = RemoteViews(ctx.packageName, R.layout.widget_small)
    rv.setTextViewText(R.id.w_time, i.sleepy.s())
    rv.setTextViewText(R.id.w_sub, i.short)
    rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    return rv
}

private fun buildWide(ctx: Context): RemoteViews {
    val i = WidgetCalc.load(ctx)
    val rv = RemoteViews(ctx.packageName, R.layout.widget_wide)
    rv.setTextViewText(R.id.w_time, i.sleepy.s())
    rv.setTextViewText(R.id.w_sub, i.left)
    rv.setTextViewText(R.id.w_s1, "Сон ${fmtDur(i.sleptMin)}")
    rv.setTextViewText(R.id.w_s2, "Дефицит: " + if (i.deficit > 0) fmtDur(i.deficit) else "нет")
    rv.setTextViewText(R.id.w_s3, "Окно ${fmtDur(i.windowMin)}")
    rv.setProgressBar(R.id.w_bar, 100, i.progress, false)
    rv.setTextViewText(R.id.w_from, "подъём ${i.wake.s()}")
    rv.setTextViewText(R.id.w_to, "сон ${i.sleepy.s()}")
    rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    return rv
}

private fun refreshOne(ctx: Context, cls: Class<*>, build: (Context) -> RemoteViews) {
    val mgr = AppWidgetManager.getInstance(ctx)
    val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
    if (ids.isEmpty()) return
    val views = build(ctx)
    for (id in ids) mgr.updateAppWidget(id, views)
}

/** Стандартный виджет 3×2. */
class SleepWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildStandard(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        /** Обновляет все виджеты приложения на рабочем столе (всех размеров). */
        fun refreshAll(ctx: Context) {
            try {
                refreshOne(ctx, SleepWidget::class.java) { buildStandard(it) }
                refreshOne(ctx, SleepWidgetSmall::class.java) { buildSmall(it) }
                refreshOne(ctx, SleepWidgetWide::class.java) { buildWide(it) }
            } catch (e: Exception) {
                // виджеты обновятся в следующий раз
            }
        }
    }
}

/** Компактный виджет 2×2. */
class SleepWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildSmall(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}

/** Широкий виджет 4×2 с полоской прогресса. */
class SleepWidgetWide : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildWide(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
