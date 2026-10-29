package com.example.sleepcalc

import java.time.LocalTime
import java.time.format.DateTimeFormatter

val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.s(): String = format(timeFmt)

fun fmtDur(min: Int): String = "${min / 60} ч ${min % 60} мин"

/** Одна фаза дня: смещение от пробуждения в минутах, время и тексты. */
data class Phase(
    val offset: Int,
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
    val windowMin: Int,
    val sleepy: LocalTime,
    val alarm: LocalTime,
    val phases: List<Phase>
)

object SleepMath {
    const val CYCLE_MIN = 90L
    const val FALL_ASLEEP_MIN = 15L
    val CYCLE_RANGE = 3..7

    data class Option(val time: LocalTime, val cycles: Int)

    /** «Лягу в X» -> когда вставать: X + 15 мин + N * 90 мин. */
    fun wakeOptions(bed: LocalTime): List<Option> =
        CYCLE_RANGE.map { n -> Option(bed.plusMinutes(FALL_ASLEEP_MIN + n * CYCLE_MIN), n) }

    /** «Хочу проснуться в X» -> когда ложиться: X - N * 90 мин - 15 мин. */
    fun bedOptions(wake: LocalTime): List<Option> =
        CYCLE_RANGE.reversed().map { n -> Option(wake.minusMinutes(n * CYCLE_MIN + FALL_ASLEEP_MIN), n) }

    /**
     * Двухпроцессная модель сна (давление сна + циркадный ритм).
     * Дефицит = норма - сон (не меньше 0). Окно бодрствования = 960 - дефицит минут,
     * но не меньше 720 и не больше 960.
     */
    fun forecast(bed: LocalTime, wake: LocalTime, goalMin: Int): Forecast {
        var slept = (wake.toSecondOfDay() - bed.toSecondOfDay()) / 60
        if (slept < 0) slept += 1440
        val deficit = (goalMin - slept).coerceAtLeast(0)
        val window = (960 - deficit).coerceIn(720, 960)
        val w0 = wake.toSecondOfDay() / 60

        fun ph(off: Int, title: String, info: String, tip: String): Phase {
            val t = w0 + off
            return Phase(off, LocalTime.ofSecondOfDay((t % 1440) * 60L), t >= 1440, title, info, tip)
        }

        val sleep = ph(
            window,
            "Идеальное окно для засыпания",
            "Давление сна и циркадный спад совпадают, засыпать легче всего.",
            "Ложитесь в течение получаса. Если пропустить окно, можно поймать «второе дыхание» и уснуть позже."
        )

        val phases = listOf(
            ph(
                0,
                "Сонная инерция (первые 30 минут)",
                "Кора мозга ещё включается: внимание и скорость реакции снижены.",
                "Не садитесь за руль и не решайте сложных задач. Откройте шторы, умойтесь прохладной водой."
            ),
            ph(
                45,
                "Пик кортизола (CAR)",
                "Через 30–45 минут после подъёма кортизол резко растёт и «включает» организм.",
                "Выйдите на яркий свет и подвигайтесь. Кофе лучше выпить чуть позже, когда пик спадёт."
            ),
            ph(
                180,
                "Первый пик концентрации",
                "Бдительность, логика и рабочая память на максимуме, температура тела растёт.",
                "Самое время для сложной аналитической работы и важных решений."
            ),
            ph(
                480,
                "Послеобеденный спад",
                "Температура тела падает, а давление сна (аденозин) уже заметно накопилось.",
                "Лёгкие задачи, прогулка на свету или короткий сон до 20 минут. Кофе лучше не позже этого времени."
            ),
            ph(
                660,
                "Второе дыхание",
                "Циркадный сигнал бодрствования достигает пика прямо перед выработкой мелатонина.",
                "Подходит для тренировки или бытовых дел. Не засиживайтесь, это время обманчиво."
            ),
            ph(
                window - 120,
                "Ворота сна (мелатонин, DLMO)",
                "Начинается выработка мелатонина, температура тела снижается, организм готовится ко сну.",
                "Приглушите свет, уберите экраны или включите ночной режим, не ешьте плотно, проветрите комнату."
            ),
            sleep
        ).sortedBy { it.offset }

        return Forecast(
            sleptMin = slept,
            cycles = slept / 90.0,
            deficit = deficit,
            windowMin = window,
            sleepy = sleep.time,
            alarm = sleep.time.plusMinutes(FALL_ASLEEP_MIN + 5 * CYCLE_MIN),
            phases = phases
        )
    }
}
