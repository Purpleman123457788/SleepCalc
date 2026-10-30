package com.example.sleepcalc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.widget.Toast
import java.time.LocalTime

object AlarmHelper {
    private fun intentFor(context: Context, t: LocalTime, msg: String): Intent {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, t.hour)
        i.putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, msg)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    /**
     * Ставит основной будильник и (если softMin > 0) мягкий за softMin минут до него.
     * На телефонах Huawei и Honor второй будильник запускается с паузой,
     * иначе приложение «Часы» теряет один из них.
     */
    fun setAlarms(context: Context, main: LocalTime, label: String, softMin: Int) {
        val items = buildList {
            if (softMin > 0) add(main.minusMinutes(softMin.toLong()) to "$label (мягкий)")
            add(main to label)
        }
        val first = items.first()
        try {
            context.startActivity(intentFor(context, first.first, first.second))
        } catch (e: Exception) {
            Toast.makeText(context, "Не найдено приложение «Часы»", Toast.LENGTH_LONG).show()
            return
        }
        val handler = Handler(Looper.getMainLooper())
        items.drop(1).forEachIndexed { k, item ->
            val job = Runnable {
                try {
                    context.startActivity(intentFor(context, item.first, item.second))
                } catch (e: Exception) {
                    // второй будильник не поставился, первый уже установлен
                }
            }
            if (alarmGapMs <= 0L) job.run() else handler.postDelayed(job, alarmGapMs * (k + 1))
        }
        val times = items.joinToString(" и ") { it.first.s() }
        Toast.makeText(context, "Поставлено: $times", Toast.LENGTH_LONG).show()
    }
}
