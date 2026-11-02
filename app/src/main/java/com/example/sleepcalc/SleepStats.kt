package com.example.sleepcalc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Недельная сводка. */
data class Week(
    val count: Int,
    val avgMin: Int,
    val avgBed: LocalTime?,
    val avgWake: LocalTime?,
    val debt: Int,
    val regularity: Int,
    val streak: Int,
    val avgScore: Int
)

/** Расчёты по журналу сна: оценка ночи, долг сна, стабильность, умные выводы. */
object SleepStats {
    /** Отметки о вечере (порядок = номера битов). */
    val TAGS = listOf("Кофе", "Алкоголь", "Спорт", "Стресс", "Экран", "Ужин")
    private val PHRASES = listOf(
        "кофе вечером", "алкоголя", "вечерней тренировки", "стресса", "экрана перед сном", "позднего ужина"
    )
    private val DAYS = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")

    /** Минуты после 18:00, чтобы отбой около полуночи не «разрывался». */
    fun bedOffset(t: LocalTime): Int = (t.toSecondOfDay() / 60 - 18 * 60 + 1440) % 1440

    /** Оценка ночи 0..100: длительность (60), время отбоя (25), регулярность (15). */
    fun score(n: Night, goalMin: Int, others: List<Night>): Int {
        val r = n.minutes.toDouble() / goalMin
        val dur = if (r <= 1.0) 60.0 * r else (60.0 * (1.0 - max(0.0, r - 1.1) * 1.5)).coerceAtLeast(0.0)
        val off = bedOffset(n.bedTime)
        val timing = when {
            off in 240..360 -> 25.0
            off > 360 -> 25.0 - (off - 360) / 6.0
            else -> 25.0 - (240 - off) / 12.0
        }.coerceIn(0.0, 25.0)
        val reg = if (others.isEmpty()) {
            10.0
        } else {
            val avg = others.map { bedOffset(it.bedTime) }.average()
            (15.0 - abs(off - avg) / 4.0).coerceIn(0.0, 15.0)
        }
        return (dur + timing + reg).toInt().coerceIn(0, 100)
    }

    fun verdict(score: Int): String = when {
        score >= 85 -> "Отлично"
        score >= 70 -> "Хорошо"
        score >= 55 -> "Нормально"
        else -> "Можно лучше"
    }

    /** Сводка за последние 7 суток. Серия считается только если есть свежая ночь. */
    fun week(nights: List<Night>, goalMin: Int): Week {
        val now = SleepLog.nowMin()
        val recent = nights.filter { now - it.wake <= 7 * 1440 }
        if (recent.isEmpty()) return Week(0, 0, null, null, 0, 0, 0, 0)

        val avg = recent.map { it.minutes }.average().toInt()
        val offs = recent.map { bedOffset(it.bedTime) }
        val meanOff = offs.average()
        val avgBed = LocalTime.ofSecondOfDay((((meanOff.toInt() + 18 * 60) % 1440) * 60).toLong())
        val wakeMean = recent.map { it.wakeTime.toSecondOfDay() / 60 }.average().toInt()
        val avgWake = LocalTime.ofSecondOfDay(wakeMean * 60L)
        val debt = recent.map { max(0, goalMin - it.minutes) }.sum()
        val sd = if (offs.size > 1) {
            sqrt(offs.map { (it - meanOff) * (it - meanOff) }.average()).toInt()
        } else 0

        var streak = 0
        val newest = nights.maxByOrNull { it.wake }
        if (newest != null && now - newest.wake <= 36 * 60) {
            for (n in nights.sortedByDescending { it.wake }) {
                if (n.minutes >= goalMin - 30) streak++ else break
            }
        }
        val scores = recent.map { n -> score(n, goalMin, recent.filter { it != n }) }
        return Week(recent.size, avg, avgBed, avgWake, debt, sd, streak, scores.average().toInt())
    }

    private fun isWeekend(n: Night): Boolean =
        n.wakeDate.dayOfWeek == DayOfWeek.SATURDAY || n.wakeDate.dayOfWeek == DayOfWeek.SUNDAY

    /** Середина сна в минутах после 18:00. */
    private fun mid(list: List<Night>): Double =
        list.map { bedOffset(it.bedTime) + it.minutes / 2.0 }.average()

    /** Текстовые выводы по записям. Нужно хотя бы 3 ночи. */
    fun insights(nights: List<Night>, goalMin: Int): List<String> {
        if (nights.size < 3) return emptyList()
        val out = ArrayList<String>()
        val w = week(nights, goalMin)

        if (w.count > 0) {
            if (w.debt >= 120) {
                out.add("За неделю набежал долг сна ${fmtDur(w.debt)}. Попробуйте несколько дней подряд ложиться на 30–40 минут раньше.")
            } else if (w.debt <= 30) {
                out.add("Баланс сна в норме: долга за неделю почти нет.")
            }
            if (w.avgMin < goalMin - 45) {
                out.add("В среднем вы спите ${fmtDur(w.avgMin)} при норме ${fmtDur(goalMin)}.")
            }
            if (w.regularity > 60) {
                out.add("Время отбоя гуляет примерно на ±${w.regularity} минут. Стабильный режим улучшает качество сна.")
            } else if (w.count >= 4 && w.regularity <= 30) {
                out.add("Режим стабильный: время отбоя почти не меняется. Так держать.")
            }
        }

        // Будни и выходные
        val weekend = nights.filter { isWeekend(it) }
        val weekdays = nights.filter { !isWeekend(it) }
        if (weekdays.size >= 2 && weekend.isNotEmpty()) {
            val d = mid(weekend) - mid(weekdays)
            if (abs(d) >= 60) {
                val dir = if (d > 0) "позже" else "раньше"
                out.add("В выходные вы спите примерно на ${fmtDur(abs(d).toInt())} $dir, чем в будни. Резкая разница сбивает ритм.")
            }
        }

        // Тренд отбоя
        if (nights.size >= 6) {
            val last3 = nights.takeLast(3).map { bedOffset(it.bedTime) }.average()
            val prev3 = nights.takeLast(6).take(3).map { bedOffset(it.bedTime) }.average()
            val d = last3 - prev3
            if (abs(d) >= 20) {
                val dir = if (d > 0) "позже" else "раньше"
                out.add("В последние ночи вы стали ложиться на ${abs(d).toInt()} минут $dir.")
            }
        }

        // Лучший день недели
        if (nights.size >= 7) {
            val byDay = nights.groupBy { it.wakeDate.dayOfWeek }
                .mapValues { e -> e.value.map { it.minutes }.average() }
            val best = byDay.maxByOrNull { it.value }
            val worst = byDay.minByOrNull { it.value }
            if (best != null && worst != null && best.value - worst.value >= 30) {
                out.add("Лучше всего вы спите в ночь на ${DAYS[best.key.value - 1]}.")
            }
        }

        // Влияние отметок
        val tagLines = ArrayList<Pair<Double, String>>()
        for (i in TAGS.indices) {
            val bit = 1 shl i
            val with = nights.filter { (it.tags and bit) != 0 }
            val without = nights.filter { (it.tags and bit) == 0 }
            if (with.size >= 2 && without.size >= 2) {
                val diff = with.map { it.minutes }.average() - without.map { it.minutes }.average()
                if (abs(diff) >= 20) {
                    val dir = if (diff < 0) "меньше" else "больше"
                    tagLines.add(
                        abs(diff) to "После ${PHRASES[i]} вы спите в среднем на ${fmtDur(abs(diff).toInt())} $dir (${with.size} ноч.)."
                    )
                }
            }
        }
        tagLines.sortedByDescending { it.first }.take(2).forEach { out.add(it.second) }

        return out.take(6)
    }

    /** Длительность сна по датам пробуждения. */
    fun minutesByDate(nights: List<Night>): Map<LocalDate, Int> =
        nights.groupBy { it.wakeDate }.mapValues { e -> e.value.maxOf { it.minutes } }
}
