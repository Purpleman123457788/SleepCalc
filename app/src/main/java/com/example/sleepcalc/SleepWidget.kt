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
    val early: LocalTime,
    val late: LocalTime,
    val left: String,        // «через 3 ч 20 мин»
    val short: String,       // «через 3 ч»
    val sleptMin: Int,
    val deficit: Int,
    val windowMin: Int,
    val wake: LocalTime,
    val fresh: Boolean,      // посчитано по записи сна, а не по введённым данным
    val progress: Int,       // сколько процентов бодрствования до сна уже прошло
    val nowPhase: String,    // текущая фаза дня
    val nextPhase: String,   // следующая фаза дня
    val nextTime: String     // время следующей фазы
)

object WidgetCalc {
    fun load(ctx: Context): WidgetInfo {
        val goal = Settings.load(ctx).goalMin
        val history = SleepLog.load(ctx)
        val last = history.lastOrNull()
        val fresh = last != null && SleepLog.nowMin() - last.wake < 36 * 60

        val bed = if (last != null && fresh) last.bedTime
        else LocalTime.ofSecondOfDay(Prefs.getInt(ctx, "fc_bed", 23 * 60) * 60L)
        val wake = if (last != null && fresh) last.wakeTime
        else LocalTime.ofSecondOfDay(Prefs.getInt(ctx, "fc_wake", 7 * 60) * 60L)

        val f = SleepMath.forecast(bed, wake, goal, history)

        val nowMin = LocalTime.now().toSecondOfDay() / 60
        val nowOff = (nowMin - wake.toSecondOfDay() / 60 + 1440) % 1440

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
        val progress = if (left > 1440 - 180) 100 else (nowOff * 100 / f.sleepyM).coerceIn(0, 100)

        val cur = f.phases.lastOrNull { it.offset <= nowOff }
        val nxt = f.phases.firstOrNull { it.offset > nowOff }

        return WidgetInfo(
            sleepy = f.sleepy,
            early = f.early,
            late = f.late,
            left = leftText,
            short = shortText,
            sleptMin = f.sleptMin,
            deficit = f.deficit,
            windowMin = f.windowMin,
            wake = wake,
            fresh = fresh,
            progress = progress,
            nowPhase = cur?.title?.substringBefore(" (") ?: "Подъём",
            nextPhase = nxt?.title?.substringBefore(" (") ?: "Пора спать",
            nextTime = nxt?.time?.s() ?: "--:--"
        )
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
    rv.setTextViewText(R.id.w_v1, "${i.early.s()} – ${i.late.s()}")
    rv.setTextViewText(R.id.w_v2, fmtDur(i.sleptMin))
    rv.setTextViewText(R.id.w_v3, i.nextTime)
    rv.setProgressBar(R.id.w_bar, 100, i.progress, false)
    rv.setTextViewText(R.id.w_from, "подъём ${i.wake.s()}")
    rv.setTextViewText(R.id.w_to, "сон ${i.sleepy.s()}")
    rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    return rv
}

private fun buildSmall(ctx: Context): RemoteViews {
    val i = WidgetCalc.load(ctx)
    val rv = RemoteViews(ctx.packageName, R.layout.widget_small)
    rv.setTextViewText(R.id.w_time, i.sleepy.s())
    rv.setTextViewText(R.id.w_sub, i.short)
    rv.setProgressBar(R.id.w_bar, 100, i.progress, false)
    rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
    return rv
}

private fun buildWide(ctx: Context): RemoteViews {
    val i = WidgetCalc.load(ctx)
    val rv = RemoteViews(ctx.packageName, R.layout.widget_wide)
    rv.setTextViewText(R.id.w_time, i.sleepy.s())
    rv.setTextViewText(R.id.w_sub, i.left)
    rv.setTextViewText(R.id.w_v1, "${i.early.s()} – ${i.late.s()}")
    rv.setTextViewText(R.id.w_v2, fmtDur(i.sleptMin))
    rv.setTextViewText(R.id.w_v3, if (i.deficit > 0) fmtDur(i.deficit) else "нет")
    rv.setTextViewText(R.id.w_v4, fmtDur(i.windowMin))
    rv.setProgressBar(R.id.w_bar, 100, i.progress, false)
    rv.setTextViewText(R.id.w_from, "Сейчас: ${i.nowPhase}")
    rv.setTextViewText(R.id.w_to, "Далее ${i.nextTime}")
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
