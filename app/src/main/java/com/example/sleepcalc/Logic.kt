package com.example.sleepcalc

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.s(): String = format(timeFmt)

fun fmtDur(min: Int): String = "${min / 60} ч ${min % 60} мин"

/** Одна фаза дня. kind: 0 инерция, 1 кортизол, 2 концентрация, 3 спад, 4 второе дыхание, 5 мелатонин, 6 сон. */
data class Phase(
    val offset: Int,
    val kind: Int,
    val time: LocalTime,
    val nextDay: Boolean,
    val title: String,
    val info: String,
    val tip: String
)

data class Forecast(
    val sleptMin: Int,
    val cycles: Double,
    val deficit: Int,
    val windowMin: Int,        // сколько минут от подъёма до вероятного сна
    val earlyM: Int,           // минуты от подъёма: начнёт клонить
    val sleepyM: Int,          // минуты от подъёма: скорее всего захочется спать
    val lateM: Int,            // минуты от подъёма: трудно не уснуть
    val early: LocalTime,
    val sleepy: LocalTime,
    val late: LocalTime,
    val alarm: LocalTime,
    val phases: List<Phase>,
    val curve: List<Float>,    // сонливость каждые 30 минут от подъёма (1.0 = порог сна)
    val anchorNights: Int      // сколько прошлых ночей учтено при оценке ритма
)

object SleepMath {
    const val CYCLE_MIN = 90L
    const val FALL_ASLEEP_MIN = 15L
    val CYCLE_RANGE = 3..7

    // Параметры двухпроцессной модели (Borbély)
    private const val TAU_RISE = 18.2   // часы: рост давления сна в бодрствовании
    private const val TAU_DECAY = 4.2   // часы: спад давления сна во сне
    private const val H0 = 0.575        // средний порог засыпания
    private const val AMP = 0.07        // размах суточных колебаний порога
    private const val MIN_AWAKE = 360   // раньше 6 часов бодрствования не прогнозируем

    data class Option(val time: LocalTime, val cycles: Int)

    /** «Лягу в X» -> когда вставать: X + 15 мин + N * 90 мин. */
    fun wakeOptions(bed: LocalTime): List<Option> =
        CYCLE_RANGE.map { n -> Option(bed.plusMinutes(FALL_ASLEEP_MIN + n * CYCLE_MIN), n) }

    /** «Хочу проснуться в X» -> когда ложиться: X - N * 90 мин - 15 мин. */
    fun bedOptions(wake: LocalTime): List<Option> =
        CYCLE_RANGE.reversed().map { n -> Option(wake.minusMinutes(n * CYCLE_MIN + FALL_ASLEEP_MIN), n) }

    /** Порог засыпания в данный час суток: самый высокий вечером, самый низкий ранним утром. */
    private fun thr(hour: Double, peak: Double): Double =
        H0 + AMP * cos(2 * PI * (hour - peak) / 24.0)

    /**
     * Прогноз сонливости по двухпроцессной модели:
     * 1) Давление сна во время сна падает (чем короче сон, тем больше остаётся).
     * 2) Днём давление растёт, сонливость наступает, когда оно достигает суточного порога.
     * 3) Фаза суточного ритма берётся по середине вашего сна, с учётом прошлых ночей из журнала.
     * 4) Выдаются три времени: раньше, скорее всего, позже.
     */
    fun forecast(bed: LocalTime, wake: LocalTime, goalMin: Int, history: List<Night> = emptyList()): Forecast {
        val bedMin = bed.toSecondOfDay() / 60
        val wakeMin = wake.toSecondOfDay() / 60
        var slept = wakeMin - bedMin
        if (slept < 0) slept += 1440
        val deficit = (goalMin - slept).coerceAtLeast(0)

        // 1. Ритм: середина сна этой ночи и прошлых ночей (круговое среднее, свежие важнее)
        val mid0 = (bedMin + slept / 2.0) % 1440.0
        var sx = cos(2 * PI * mid0 / 1440.0)
        var sy = sin(2 * PI * mid0 / 1440.0)
        var used = 0
        var skippedSame = false
        val now = SleepLog.nowMin()
        for (n in history.sortedByDescending { it.wake }) {
            if (used >= 6) break
            if (now - n.wake > 14 * 1440) break
            val dw = kotlin.math.abs(n.wakeTime.toSecondOfDay() / 60 - wakeMin)
            if (!skippedSame && minOf(dw, 1440 - dw) <= 60) {
                skippedSame = true   // это та же ночь, что введена сейчас
                continue
            }
            val mid = (n.bedTime.toSecondOfDay() / 60 + n.minutes / 2.0) % 1440.0
            val wgt = 0.8.pow(used + 1)
            sx += wgt * cos(2 * PI * mid / 1440.0)
            sy += wgt * sin(2 * PI * mid / 1440.0)
            used++
        }
        var ang = atan2(sy, sx)
        if (ang < 0) ang += 2 * PI
        val anchor = ang / (2 * PI) * 1440.0
        val peak = (anchor / 60.0 + 17.0) % 24.0   // пик бодрости за 3 часа до обычного отбоя

        // 2. Давление сна после ночи
        val asleep = (slept - FALL_ASLEEP_MIN.toInt()).coerceAtLeast(0) * 480.0 / goalMin
        val sBed = thr(bedMin / 60.0, peak)
        val sWake = sBed * exp(-(asleep / 60.0) / TAU_DECAY)

        // 3. Идём по дню и ищем, когда давление догонит порог
        val curve = ArrayList<Float>()
        var e = -1
        var s = -1
        var l = -1
        var m = 0
        while (m <= 1440) {
            val th = (wakeMin + m) / 60.0
            val sv = 1.0 - (1.0 - sWake) * exp(-(m / 60.0) / TAU_RISE)
            val r = sv / thr(th, peak)
            if (m % 30 == 0) curve.add(r.toFloat())
            if (m >= MIN_AWAKE) {
                if (e < 0 && r >= 0.95) e = m
                if (s < 0 && r >= 1.0) s = m
                if (l < 0 && r >= 1.04) l = m
            }
            m += 5
        }
        if (s < 0) s = 1200
        if (e < 0 || e > s) e = s
        if (l < 0 || l < s) l = minOf(s + 60, 1440)

        fun at(off: Int): LocalTime = LocalTime.ofSecondOfDay(((wakeMin + off) % 1440) * 60L)

        fun ph(off: Int, kind: Int, title: String, info: String, tip: String): Phase {
            val t = wakeMin + off
            return Phase(off, kind, LocalTime.ofSecondOfDay((t % 1440) * 60L), t >= 1440, title, info, tip)
        }

        val phases = listOf(
            ph(
                0, 0,
                "Сонная инерция (первые 30 минут)",
                "Кора мозга ещё включается: внимание и скорость реакции снижены.",
                "Не садитесь за руль и не решайте сложных задач. Откройте шторы, умойтесь прохладной водой."
            ),
            ph(
                45, 1,
                "Пик кортизола (CAR)",
                "Через 30–45 минут после подъёма кортизол резко растёт и «включает» организм.",
                "Выйдите на яркий свет и подвигайтесь. Кофе лучше выпить чуть позже, когда пик спадёт."
            ),
            ph(
                180, 2,
                "Первый пик концентрации",
                "Бдительность, логика и рабочая память на максимуме, температура тела растёт.",
                "Самое время для сложной аналитической работы и важных решений."
            ),
            ph(
                480, 3,
                "Послеобеденный спад",
                "Температура тела падает, а давление сна (аденозин) уже заметно накопилось.",
                "Лёгкие задачи, прогулка на свету или короткий сон до 20 минут. Кофе лучше не позже этого времени."
            ),
            ph(
                maxOf(s - 300, 540), 4,
                "Второе дыхание",
                "Циркадный сигнал бодрствования достигает пика прямо перед выработкой мелатонина.",
                "Подходит для тренировки или бытовых дел. Не засиживайтесь, это время обманчиво."
            ),
            ph(
                s - 120, 5,
                "Ворота сна (мелатонин, DLMO)",
                "Начинается выработка мелатонина, температура тела снижается, организм готовится ко сну.",
                "Приглушите свет, уберите экраны или включите ночной режим, не ешьте плотно, проветрите комнату."
            ),
            ph(
                s, 6,
                "Идеальное окно для засыпания",
                "Давление сна и циркадный спад совпадают, засыпать легче всего.",
                "Ложитесь в течение получаса. Если пропустить окно, можно поймать «второе дыхание» и уснуть позже."
            )
        ).sortedBy { it.offset }

        return Forecast(
            sleptMin = slept,
            cycles = slept / 90.0,
            deficit = deficit,
            windowMin = s,
            earlyM = e,
            sleepyM = s,
            lateM = l,
            early = at(e),
            sleepy = at(s),
            late = at(l),
            alarm = at(s).plusMinutes(FALL_ASLEEP_MIN + 5 * CYCLE_MIN),
            phases = phases,
            curve = curve,
            anchorNights = used
        )
    }
}
