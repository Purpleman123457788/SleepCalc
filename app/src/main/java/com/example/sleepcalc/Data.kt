package com.example.sleepcalc

import android.content.Context
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Включена ли вибрация (обновляется при смене настроек). */
var hapticsOn = true

/** Использовать вибромотор напрямую (нужно на Huawei и Honor). */
var hapticStrong = false

/** Пауза между запуском двух будильников, миллисекунды. */
var alarmGapMs = 0L

/** Определение производителя телефона. */
object Device {
    val huaweiLike: Boolean by lazy {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(java.util.Locale.ROOT)
        m.contains("huawei") || m.contains("honor")
    }
}

/** Общее хранилище настроек и последних введённых значений. */
object Prefs {
    fun sp(ctx: Context) = ctx.getSharedPreferences("sleepcalc_settings", Context.MODE_PRIVATE)

    fun getInt(ctx: Context, key: String, def: Int): Int = sp(ctx).getInt(key, def)

    fun putInt(ctx: Context, key: String, value: Int) {
        sp(ctx).edit().putInt(key, value).apply()
    }
}

data class Settings(
    val theme: Int = 2,               // 0 = как в системе, 1 = светлая, 2 = тёмная
    val softMin: Int = 10,            // мягкий будильник за N минут (0 = выкл)
    val goalMin: Int = 480,           // норма сна в минутах
    val haptics: Boolean = true,      // вибрация
    val hapticMode: Int = 0,          // 0 = авто, 1 = системная, 2 = усиленная
    val alarmMode: Int = 0,           // 0 = авто, 1 = быстро, 2 = по очереди
    val autoUpdate: Boolean = true,   // автообновление
    val notifyUpdates: Boolean = true,// уведомления о новой версии
    val autoSleep: Boolean = false    // автоотслеживание сна (по умолчанию выключено)
) {
    companion object {
        fun load(ctx: Context): Settings {
            val sp = Prefs.sp(ctx)
            return Settings(
                theme = sp.getInt("theme", 2),
                softMin = sp.getInt("softMin", 10),
                goalMin = sp.getInt("goalMin", 480),
                haptics = sp.getBoolean("haptics", true),
                hapticMode = sp.getInt("hapticMode", 0),
                alarmMode = sp.getInt("alarmMode", 0),
                autoUpdate = sp.getBoolean("autoUpdate", true),
                notifyUpdates = sp.getBoolean("notifyUpdates", true),
                autoSleep = sp.getBoolean("autoSleep", false)
            )
        }
    }

    fun save(ctx: Context) {
        Prefs.sp(ctx).edit()
            .putInt("theme", theme)
            .putInt("softMin", softMin)
            .putInt("goalMin", goalMin)
            .putBoolean("haptics", haptics)
            .putInt("hapticMode", hapticMode)
            .putInt("alarmMode", alarmMode)
            .putBoolean("autoUpdate", autoUpdate)
            .putBoolean("notifyUpdates", notifyUpdates)
            .putBoolean("autoSleep", autoSleep)
            .apply()
    }
}

/** Минуты с 1970 года -> дата и время на телефоне. */
fun minToZoned(m: Long): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochSecond(m * 60), ZoneId.systemDefault())

fun minToTime(m: Long): LocalTime = minToZoned(m).toLocalTime()

/** Одна ночь сна: отбой и пробуждение (минуты с 1970 года). auto = найдена автоматически. */
data class Night(val bed: Long, val wake: Long, val auto: Boolean = false) {
    val minutes: Int get() = (wake - bed).toInt()
    val bedTime: LocalTime get() = minToTime(bed)
    val wakeTime: LocalTime get() = minToTime(wake)
    val wakeDate: LocalDate get() = minToZoned(wake).toLocalDate()
}

/** Журнал сна: записи хранятся в SharedPreferences. */
object SleepLog {
    private const val KEY = "nights"
    private const val ACTIVE = "sleepStart"
    private const val MAX = 90

    fun nowMin(): Long = System.currentTimeMillis() / 60000

    fun load(ctx: Context): List<Night> {
        val raw = Prefs.sp(ctx).getString(KEY, "") ?: ""
        return raw.split(";").mapNotNull { p ->
            val a = p.split(",")
            if (a.size >= 2) {
                val b = a[0].toLongOrNull()
                val w = a[1].toLongOrNull()
                if (b != null && w != null && w > b) Night(b, w, a.size > 2 && a[2] == "a") else null
            } else null
        }.sortedBy { it.wake }
    }

    private fun save(ctx: Context, list: List<Night>) {
        val s = list.takeLast(MAX).joinToString(";") {
            "${it.bed},${it.wake}" + if (it.auto) ",a" else ""
        }
        Prefs.sp(ctx).edit().putString(KEY, s).apply()
    }

    /** Добавляет ночь. Возвращает false, если сон короче 30 минут или длиннее 20 часов. */
    fun add(ctx: Context, n: Night): Boolean {
        if (n.minutes < 30 || n.minutes > 20 * 60) return false
        val list = load(ctx).filter { kotlin.math.abs(it.wake - n.wake) > 12 * 60 } + n
        save(ctx, list.sortedBy { it.wake })
        return true
    }

    /** Автозапись: не трогает ночи, добавленные вручную; свои прежние версии заменяет. */
    fun addAuto(ctx: Context, n: Night): Boolean {
        if (n.minutes < 30 || n.minutes > 20 * 60) return false
        val all = load(ctx)
        val near = all.filter {
            kotlin.math.abs(it.wake - n.wake) <= 14 * 60 || (it.bed < n.wake && n.bed < it.wake)
        }
        if (near.any { !it.auto }) return false
        save(ctx, (all.filter { it !in near } + n).sortedBy { it.wake })
        return true
    }

    fun remove(ctx: Context, n: Night) {
        save(ctx, load(ctx).filter { it != n })
    }

    /** Ночь вручную: время отбоя и пробуждения, пробуждение считается ближайшим в прошлом. */
    fun addManual(ctx: Context, bedMin: Int, wakeMin: Int): Boolean {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var wake = now.toLocalDate().atTime(wakeMin / 60, wakeMin % 60).atZone(zone)
        if (wake.isAfter(now)) wake = wake.minusDays(1)
        val dur = (wakeMin - bedMin + 1440) % 1440
        val bed = wake.minusMinutes(dur.toLong())
        return add(ctx, Night(bed.toEpochSecond() / 60, wake.toEpochSecond() / 60))
    }

    /** Время, когда лёг спать (0, если сейчас сон не отслеживается). */
    fun activeStart(ctx: Context): Long = Prefs.sp(ctx).getLong(ACTIVE, 0L)

    fun start(ctx: Context): Long {
        val m = nowMin()
        Prefs.sp(ctx).edit().putLong(ACTIVE, m).apply()
        return m
    }

    fun cancel(ctx: Context) {
        Prefs.sp(ctx).edit().remove(ACTIVE).apply()
    }

    fun finish(ctx: Context): Boolean {
        val s = activeStart(ctx)
        if (s == 0L) return false
        val ok = add(ctx, Night(s, nowMin()))
        cancel(ctx)
        return ok
    }
}
