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

/** Период времени: начало и конец (минуты с 1970 года). */
private data class Gap(val start: Long, val end: Long)

/**
 * Автоопределение сна по истории экрана. Никакой фоновой работы:
 * Android сам хранит моменты включения и выключения экрана, мы читаем их
 * только при открытии приложения.
 */
object AutoSleep {
    private const val SCREEN_ON = 15         // экран включился
    private const val SCREEN_OFF = 16        // экран выключился
    private const val KEYGUARD_HIDDEN = 18   // телефон разблокирован
    private const val MIN_QUIET = 30         // короче 30 минут паузы не считаем
    private const val MIN_SLEEP = 180        // сон не короче 3 часов
    private const val MAX_BREAK = 20         // обычный режим: короткие пробуждения склеиваем
    private const val FALL_ASLEEP = 10L      // точный режим: сдвиг на засыпание, минут

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
        try {
            Toast.makeText(ctx, "Найдите SleepCalc в списке и включите доступ", Toast.LENGTH_LONG).show()
            val i = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(
                ctx,
                "Откройте: Настройки > Особые права > Доступ к данным об использовании",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** События экрана и разблокировки: время в миллисекундах и тип события. */
    private fun rawEvents(ctx: Context, fromMin: Long, toMin: Long): List<Pair<Long, Int>> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(fromMin * 60000, toMin * 60000)
        val e = UsageEvents.Event()
        val out = ArrayList<Pair<Long, Int>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.eventType
            if (t == SCREEN_ON || t == SCREEN_OFF || t == KEYGUARD_HIDDEN) out.add(e.timeStamp to t)
        }
        return out
    }

    /** Только включения и выключения экрана, время в минутах. */
    private fun screenOnly(raw: List<Pair<Long, Int>>): List<Pair<Long, Boolean>> =
        raw.filter { it.second == SCREEN_ON || it.second == SCREEN_OFF }
            .map { (it.first / 60000) to (it.second == SCREEN_ON) }

    /**
     * Обычный разбор, можно ли считать два тихих периода одним сном.
     * Обычный режим: перерыв до 20 минут.
     * Точный режим: перерыв до 12 минут, либо до 45 минут, если за это время экран включался
     * не больше двух раз (это уведомления, а не настоящее бодрствование).
     */
    private fun canMerge(events: List<Pair<Long, Boolean>>, a: Gap, b: Gap, precise: Boolean): Boolean {
        val brk = b.start - a.end
        if (!precise) return brk <= MAX_BREAK
        if (brk <= 12) return true
        val glances = events.count { it.second && it.first >= a.end && it.first <= b.start }
        return brk <= 45 && glances <= 2
    }

    /** Общий хвост: склеиваем близкие периоды, оставляем похожие на ночной сон, берём самый длинный. */
    private fun finish(quiet: List<Gap>, canMerge: (Gap, Gap) -> Boolean, shift: Long): List<Night> {
        val merged = ArrayList<Gap>()
        for (g in quiet) {
            val last = merged.lastOrNull()
            if (last != null && canMerge(last, g)) {
                merged[merged.size - 1] = Gap(last.start, g.end)
            } else {
                merged.add(g)
            }
        }
        val result = ArrayList<Night>()
        for (g in merged) {
            val len = g.end - g.start
            val hour = minToTime(g.start).hour
            if (len < MIN_SLEEP || len > 20 * 60) continue
            if (hour in 6..17) continue
            val n = Night(g.start + shift, g.end, true)
            val last = result.lastOrNull()
            if (last != null && n.wake - last.wake < 14 * 60) {
                if (n.minutes > last.minutes) result[result.size - 1] = n
            } else {
                result.add(n)
            }
        }
        return result
    }

    /** Разбор по выключениям экрана (как раньше). */
    private fun detect(events: List<Pair<Long, Boolean>>, precise: Boolean): List<Night> {
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
        return finish(quiet, { a, b -> canMerge(events, a, b, precise) }, if (precise) FALL_ASLEEP else 0L)
    }

    /**
     * Настоящие использования телефона: экран дольше 2 минут, либо разблокировка
     * и экран дольше 25 секунд. Короткие взгляды на уведомления не считаются.
     */
    private fun realSessions(raw: List<Pair<Long, Int>>, nowMin: Long): List<Gap> {
        val out = ArrayList<Gap>()
        var start = -1L
        var unlocked = false
        for ((t, type) in raw) {
            if (type == SCREEN_ON) {
                if (start < 0) {
                    start = t
                    unlocked = false
                }
            } else if (type == KEYGUARD_HIDDEN) {
                if (start >= 0) unlocked = true
            } else if (type == SCREEN_OFF) {
                if (start >= 0) {
                    val dur = t - start
                    if (dur >= 120_000L || (unlocked && dur >= 25_000L)) {
                        out.add(Gap(start / 60000, t / 60000))
                    }
                    start = -1L
                }
            }
        }
        if (start >= 0) out.add(Gap(start / 60000, nowMin)) // сейчас экран включён
        return out
    }

    /** Умный разбор без сервисов Google: ночь это долгая пауза между настоящими использованиями. */
    private fun detectBySessions(sessions: List<Gap>): List<Night> {
        val quiet = ArrayList<Gap>()
        for (i in 0 until sessions.size - 1) {
            val a = sessions[i].end
            val b = sessions[i + 1].start
            if (b - a >= MIN_QUIET) quiet.add(Gap(a, b))
        }
        return finish(quiet, { p, g -> g.start - p.end <= 25 }, FALL_ASLEEP)
    }

    /** Добавляет в журнал найденные ночи. Возвращает, сколько ночей добавлено или обновлено. */
    suspend fun importNow(ctx: Context): Int = withContext(Dispatchers.IO) {
        if (!hasAccess(ctx)) return@withContext 0
        val precise = Settings.load(ctx).preciseSleep
        // Если датчики Google не работают (нет сервисов, разрешения или был сбой), берём разбор по сессиям
        val useSessions = precise && !SleepSensing.googleActive(ctx)
        val now = SleepLog.nowMin()
        val sp = Prefs.sp(ctx)
        val cursor = sp.getLong("autoCursor", now - 7 * 1440)
        val from = maxOf(now - 7 * 1440, cursor - 24 * 60)
        val raw = rawEvents(ctx, from, now)
        val found = (if (useSessions) detectBySessions(realSessions(raw, now))
        else detect(screenOnly(raw), precise))
            .filter { it.wake > cursor && it.wake <= now }
        var added = 0
        for (n in found) {
            if (SleepLog.addAuto(ctx, n)) added++
        }
        sp.edit().putLong("autoCursor", now).apply()
        added
    }
}
