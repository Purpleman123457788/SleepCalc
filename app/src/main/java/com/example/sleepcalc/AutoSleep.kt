package com.example.sleepcalc

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Период, когда экран был выключен: начало и конец (минуты с 1970 года). */
private data class Gap(val start: Long, val end: Long)

/**
 * Автоопределение сна по истории экрана. Никакой фоновой работы:
 * Android сам хранит моменты включения и выключения экрана, мы читаем их
 * только при открытии приложения.
 */
object AutoSleep {
    private const val SCREEN_ON = 15    // экран включился
    private const val SCREEN_OFF = 16   // экран выключился
    private const val MIN_QUIET = 30    // короче 30 минут паузы не считаем
    private const val MIN_SLEEP = 180   // сон не короче 3 часов
    private const val MAX_BREAK = 20    // короткие ночные пробуждения склеиваем

    /** Выдан ли доступ «Статистика использования». */
    @Suppress("DEPRECATION")
    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Открывает системную страницу, где этот доступ включается. */
    fun openAccessSettings(ctx: Context) {
        Toast.makeText(ctx, "Найдите SleepCalc в списке и включите доступ", Toast.LENGTH_LONG).show()
        val i = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun screenEvents(ctx: Context, fromMin: Long, toMin: Long): List<Pair<Long, Boolean>> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(fromMin * 60000, toMin * 60000)
        val e = UsageEvents.Event()
        val out = ArrayList<Pair<Long, Boolean>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == SCREEN_ON) out.add((e.timeStamp / 60000) to true)
            else if (e.eventType == SCREEN_OFF) out.add((e.timeStamp / 60000) to false)
        }
        return out
    }

    /** Ищет ночной сон: длинные периоды с выключенным экраном. */
    private fun detect(events: List<Pair<Long, Boolean>>): List<Night> {
        // 1. Периоды «экран выключен» не короче 30 минут
        val quiet = ArrayList<Gap>()
        var offAt = -1L
        for ((t, on) in events) {
            if (!on) {
                if (offAt < 0) offAt = t
            } else if (offAt >= 0) {
                if (t - offAt >= MIN_QUIET) quiet.add(Gap(offAt, t))
                offAt = -1L
            }
        }
        // 2. Склеиваем периоды, между которыми было короткое пробуждение
        val merged = ArrayList<Gap>()
        for (g in quiet) {
            val last = merged.lastOrNull()
            if (last != null && g.start - last.end <= MAX_BREAK) {
                merged[merged.size - 1] = Gap(last.start, g.end)
            } else {
                merged.add(g)
            }
        }
        // 3. Оставляем похожие на ночной сон; из соседних берём самый длинный
        val result = ArrayList<Night>()
        for (g in merged) {
            val len = g.end - g.start
            val hour = minToTime(g.start).hour
            if (len < MIN_SLEEP || len > 20 * 60) continue
            if (hour in 6..17) continue
            val n = Night(g.start, g.end, true)
            val last = result.lastOrNull()
            if (last != null && n.wake - last.wake < 14 * 60) {
                if (n.minutes > last.minutes) result[result.size - 1] = n
            } else {
                result.add(n)
            }
        }
        return result
    }

    /** Добавляет в журнал найденные ночи. Возвращает, сколько ночей добавлено или обновлено. */
    suspend fun importNow(ctx: Context): Int = withContext(Dispatchers.IO) {
        if (!hasAccess(ctx)) return@withContext 0
        val now = SleepLog.nowMin()
        val sp = Prefs.sp(ctx)
        val cursor = sp.getLong("autoCursor", now - 7 * 1440)
        val from = maxOf(now - 7 * 1440, cursor - 24 * 60)
        val found = detect(screenEvents(ctx, from, now)).filter { it.wake > cursor && it.wake <= now }
        var added = 0
        for (n in found) {
            if (SleepLog.addAuto(ctx, n)) added++
        }
        sp.edit().putLong("autoCursor", now).apply()
        added
    }
}
