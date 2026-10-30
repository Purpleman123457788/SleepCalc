package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast
import java.time.LocalTime

object AlarmHelper {
    /** Ставит основной будильник и (если softMin > 0) мягкий за softMin минут до него. */
    fun setAlarms(context: Context, main: LocalTime, label: String, softMin: Int) {
        val items = buildList {
            if (softMin > 0) add(main.minusMinutes(softMin.toLong()) to "$label (мягкий)")
            add(main to label)
        }
        try {
            items.forEach { (t, msg) ->
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, t.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                }
                context.startActivity(intent)
            }
            val times = items.joinToString(" и ") { it.first.s() }
            Toast.makeText(context, "Поставлено: $times", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Не найдено приложение «Часы»", Toast.LENGTH_LONG).show()
        }
    }
}
