package com.example.sleepcalc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

@Composable
fun ForecastScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var bedMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_bed", 23 * 60)) }
    var wakeMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_wake", 7 * 60)) }
    val last = remember { SleepLog.load(ctx).lastOrNull() }

    // Расчёт идёт сразу при изменении времени или нормы сна
    val f = remember(bedMin, wakeMin, settings.goalMin) {
        SleepMath.forecast(
            LocalTime.ofSecondOfDay(bedMin * 60L),
            LocalTime.ofSecondOfDay(wakeMin * 60L),
            settings.goalMin
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimeField("Лёг спать (накануне)", bedMin) { bedMin = it; Prefs.putInt(ctx, "fc_bed", it) }
        TimeField("Проснулся (сегодня)", wakeMin) { wakeMin = it; Prefs.putInt(ctx, "fc_wake", it) }

        if (last != null) {
            OutlinedButton(
                onClick = {
                    bedMin = last.bedTime.toSecondOfDay() / 60
                    wakeMin = last.wakeTime.toSecondOfDay() / 60
                    Prefs.putInt(ctx, "fc_bed", bedMin)
                    Prefs.putInt(ctx, "fc_wake", wakeMin)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) { Text("Взять из записи сна: ${last.bedTime.s()} → ${last.wakeTime.s()}") }
        }

        Reveal(0) {
            Card2 {
                Text("Итоги сна", color = cs.onSurfaceVariant)
                Row {
                    Stat("Сон", fmtDur(f.sleptMin), Modifier.weight(1f))
                    Stat("Циклов", "%.1f".format(f.cycles), Modifier.weight(1f))
                    Stat("Дефицит", if (f.deficit > 0) fmtDur(f.deficit) else "нет", Modifier.weight(1f))
                }
                Text("Окно бодрствования сегодня: ${fmtDur(f.windowMin)}", color = cs.onSurfaceVariant)
            }
        }

        Reveal(1) {
            Card2 {
                Text("Захочется спать примерно в", color = cs.onSurfaceVariant)
                Text(f.sleepy.s(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Интервал: ${f.sleepy.minusMinutes(30).s()} – ${f.sleepy.plusMinutes(30).s()}",
                    color = cs.onSurfaceVariant
                )
            }
        }

        Text("Ваш день по часам", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        f.phases.forEachIndexed { i, p -> PhaseCard(i, p) }

        Reveal(7) {
            Card2 {
                Text("Рекомендация", color = cs.onSurfaceVariant)
                val soft = f.alarm.minusMinutes(settings.softMin.toLong())
                val alarms = if (settings.softMin > 0)
                    "Будильники: ${soft.s()} и ${f.alarm.s()}."
                else
                    "Будильник: ${f.alarm.s()}."
                Text(
                    "Если лечь в ${f.sleepy.s()}, 5 циклов сна (7.5 ч) закончатся около ${f.alarm.s()}. $alarms",
                    fontSize = 16.sp
                )
                Button(onClick = { AlarmHelper.setAlarms(ctx, f.alarm, "Подъём", settings.softMin) }) {
                    Text(if (settings.softMin > 0) "Поставить 2 будильника" else "Поставить будильник")
                }
            }
        }
    }
}

/** Одна строка таймлайна: время, название фазы, объяснение и совет. */
@Composable
fun PhaseCard(index: Int, p: Phase) {
    val cs = MaterialTheme.colorScheme
    Reveal(index + 2) {
        Card2 {
            Row {
                Column(Modifier.width(80.dp)) {
                    Text(p.time.s(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (p.nextDay) Text("завтра", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(p.info, fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Text(p.tip, fontSize = 13.sp)
                }
            }
        }
    }
}
