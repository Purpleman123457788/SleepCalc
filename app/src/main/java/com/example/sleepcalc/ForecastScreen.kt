package com.example.sleepcalc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import java.time.LocalTime

@Composable
fun ForecastScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var bedMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_bed", 23 * 60)) }
    var wakeMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_wake", 7 * 60)) }
    val history = remember { SleepLog.load(ctx) }
    val last = remember { history.lastOrNull() }
    var showBreath by remember { mutableStateOf(false) }
    var remOn by remember { mutableStateOf(BedtimeReminder.enabled(ctx)) }
    var lead by remember { mutableIntStateOf(BedtimeReminder.lead(ctx)) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Расчёт идёт сразу при изменении времени или нормы сна
    val f = remember(bedMin, wakeMin, settings.goalMin) {
        SleepMath.forecast(
            LocalTime.ofSecondOfDay(bedMin * 60L),
            LocalTime.ofSecondOfDay(wakeMin * 60L),
            settings.goalMin,
            history
        )
    }
    val nowOff = (LocalTime.now().toSecondOfDay() / 60 - wakeMin + 1440) % 1440
    val curIdx = f.phases.indexOfLast { it.offset <= nowOff }

    if (showBreath) BreathingDialog { showBreath = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimeField("Лёг спать (накануне)", bedMin, icon = 2) { bedMin = it; Prefs.putInt(ctx, "fc_bed", it) }
        TimeField("Проснулся (сегодня)", wakeMin, icon = 2) { wakeMin = it; Prefs.putInt(ctx, "fc_wake", it) }

        if (last != null) {
            OutlinedButton(
                onClick = {
                    bedMin = last.bedTime.toSecondOfDay() / 60
                    wakeMin = last.wakeTime.toSecondOfDay() / 60
                    Prefs.putInt(ctx, "fc_bed", bedMin)
                    Prefs.putInt(ctx, "fc_wake", wakeMin)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
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
                Text("Бодрствовать до сна: ${fmtDur(f.windowMin)}", color = cs.onSurfaceVariant)
                Text(
                    if (f.anchorNights > 0) "Учтён ваш ритм по ${f.anchorNights} прошлым ночам."
                    else "Ритм оценён по этой ночи. Чем больше ночей в журнале, тем точнее прогноз.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Reveal(1) {
            Card2 {
                Text("Захочется спать примерно в", color = cs.onSurfaceVariant)
                Text(f.sleepy.s(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Row {
                    Stat("Начнёт клонить", f.early.s(), Modifier.weight(1f))
                    Stat("Скорее всего", f.sleepy.s(), Modifier.weight(1f))
                    Stat("Трудно не уснуть", f.late.s(), Modifier.weight(1f))
                }
                Text(
                    "Раньше или позже зависит от света, кофеина, нагрузки и настроения. Это оценка по модели, а не измерение.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Reveal(2) {
            Card2 {
                Text("Сонливость в течение дня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                SleepCurve(f, wakeMin)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0, 360, 720, 1080, 1440).forEach { off ->
                        Text(
                            LocalTime.ofSecondOfDay(((wakeMin + off) % 1440) * 60L).s(),
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Линия выше пунктира: хочется спать. Широкая полоса: раньше и позже. Яркая черта: вероятнее всего. Пунктир слева: сейчас.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Text("Ваш день по часам", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        f.phases.forEachIndexed { i, p -> PhaseCard(i, p, i == curIdx && nowOff <= f.sleepyM + 120) }

        // ---------- Подготовка ко сну ----------
        Reveal(8) {
            Card2 {
                Text("Подготовка ко сну", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Вечер под ваше время сна ${f.sleepy.s()}:", color = cs.onSurfaceVariant)
                val plan = listOf(
                    f.sleepy.minusMinutes(540) to "Последний кофе и чай с кофеином",
                    f.sleepy.minusMinutes(240) to "Закончить интенсивную тренировку",
                    f.sleepy.minusMinutes(180) to "Последний плотный приём пищи",
                    f.sleepy.minusMinutes(90) to "Приглушить свет, убрать экраны",
                    f.sleepy.minusMinutes(60) to "Тёплый душ, проветрить комнату",
                    f.sleepy to "Лечь в кровать"
                )
                plan.forEach { (t, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.s(), Modifier.width(68.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(text, Modifier.weight(1f), fontSize = 14.sp)
                    }
                }
                OutlinedButton(
                    onClick = { showBreath = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp)
                ) { Text("Дыхание 4-7-8 для расслабления") }
            }
        }

        // ---------- Напоминание ----------
        Reveal(9) {
            Card2 {
                SwitchRow(
                    "Напоминание о подготовке ко сну",
                    "Придёт уведомление за выбранное время до того, как захочется спать. Время сдвигается вместе с прогнозом.",
                    remOn
                ) { on ->
                    remOn = on
                    BedtimeReminder.setEnabled(ctx, on)
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (remOn) {
                    Text("За сколько напомнить:", color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90).forEach { m ->
                            OptionChip(lead == m, "$m мин") {
                                lead = m
                                BedtimeReminder.setLead(ctx, m)
                            }
                        }
                    }
                }
            }
        }

        Reveal(10) {
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
