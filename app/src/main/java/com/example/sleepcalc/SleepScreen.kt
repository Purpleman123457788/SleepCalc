package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val ru: Locale = Locale.forLanguageTag("ru")

/** Отправляет сводку за неделю текстом в любой мессенджер. */
private fun shareReport(ctx: Context, nights: List<Night>, goalMin: Int) {
    val w = SleepStats.week(nights, goalMin)
    val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", ru)
    val text = buildString {
        append("Мой сон за неделю (SleepCalc)\n")
        append("Ночей: ${w.count}, средний сон ${fmtDur(w.avgMin)}, долг сна ${fmtDur(w.debt)}\n")
        append("Средний отбой ${w.avgBed?.s() ?: "--:--"}, подъём ${w.avgWake?.s() ?: "--:--"}\n\n")
        nights.takeLast(7).forEach {
            append("${it.wakeDate.format(dayFmt)}: ${it.bedTime.s()} → ${it.wakeTime.s()}, ${fmtDur(it.minutes)}\n")
        }
    }
    val i = Intent(Intent.ACTION_SEND)
    i.type = "text/plain"
    i.putExtra(Intent.EXTRA_TEXT, text)
    try {
        ctx.startActivity(Intent.createChooser(i, "Поделиться отчётом"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось открыть список приложений", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SleepScreen(settings: Settings) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    val goal = settings.goalMin

    var nights by remember { mutableStateOf(SleepLog.load(ctx)) }
    var start by remember { mutableLongStateOf(SleepLog.activeStart(ctx)) }
    var showManual by remember { mutableStateOf(false) }
    var mBed by remember { mutableIntStateOf(23 * 60) }
    var mWake by remember { mutableIntStateOf(7 * 60) }
    var mode by remember { mutableIntStateOf(0) }   // 0 = длительность, 1 = время отбоя
    var days by remember { mutableIntStateOf(7) }

    // Текущее время, обновляется раз в 20 секунд (для таймера «идёт сон»)
    val now by produceState(SleepLog.nowMin()) {
        while (true) {
            delay(20_000)
            value = SleepLog.nowMin()
        }
    }

    val dayFmt = DateTimeFormatter.ofPattern("EE", ru)
    val numFmt = DateTimeFormatter.ofPattern("d", ru)
    val rowFmt = DateTimeFormatter.ofPattern("EEE, d MMM", ru)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- Отслеживание ----------
        Reveal(0) {
            Card2 {
                if (start == 0L) {
                    Text("Отслеживание сна", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "Нажмите перед сном, а утром «Проснулся». Приложение запишет время и посчитает сон.",
                        color = cs.onSurfaceVariant
                    )
                    Button(
                        onClick = { start = SleepLog.start(ctx); tick(view) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Лёг спать") }
                } else {
                    val spent = (now - start).toInt().coerceAtLeast(0)
                    Text("Идёт сон", color = cs.onSurfaceVariant)
                    Text(fmtDur(spent), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("с ${minToTime(start).s()}", color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val ok = SleepLog.finish(ctx)
                                start = 0L
                                nights = SleepLog.load(ctx)
                                if (ok) tick(view)
                                else Toast.makeText(ctx, "Запись слишком короткая или длинная, не сохранена", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text("Проснулся") }
                        OutlinedButton(
                            onClick = { SleepLog.cancel(ctx); start = 0L },
                            modifier = Modifier.height(52.dp)
                        ) { Text("Отмена") }
                    }
                }
            }
        }

        // ---------- Ручной ввод ----------
        OutlinedButton(
            onClick = { showManual = !showManual },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) { Text(if (showManual) "Скрыть ручной ввод" else "Добавить ночь вручную") }

        if (showManual) {
            Card2 {
                TimeField("Лёг спать", mBed, icon = 2) { mBed = it }
                TimeField("Проснулся", mWake, icon = 2) { mWake = it }
                Button(
                    onClick = {
                        if (SleepLog.addManual(ctx, mBed, mWake)) {
                            nights = SleepLog.load(ctx)
                            showManual = false
                        } else {
                            Toast.makeText(ctx, "Проверьте время: сон от 30 минут до 20 часов", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Сохранить") }
            }
        }

        // ---------- Оценка и последняя ночь ----------
        if (nights.isNotEmpty()) {
            ScoreCard(nights, goal)
            val latest = nights.last()
            RateCard(latest) { r, t ->
                SleepLog.update(ctx, latest.copy(rating = r, tags = t))
                nights = SleepLog.load(ctx)
            }
        }

        // ---------- График ----------
        Text("График сна", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip(mode == 0, "Длительность") { mode = 0 }
            OptionChip(mode == 1, "Время отбоя") { mode = 1 }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip(days == 7, "7 дней") { days = 7 }
            OptionChip(days == 14, "14 дней") { days = 14 }
            OptionChip(days == 30, "30 дней") { days = 30 }
        }

        val shown = nights.takeLast(days)

        if (shown.isEmpty()) {
            Card2 {
                Text(
                    "Пока нет записей. Нажмите «Лёг спать» перед сном или добавьте ночь вручную. Можно включить автоотслеживание в настройках.",
                    color = cs.onSurfaceVariant
                )
            }
        } else {
            val avgDur = shown.map { it.minutes }.average().toInt()
            val avgBedOff = shown.map { SleepStats.bedOffset(it.bedTime) }.average().toInt()
            val avgBed = LocalTime.ofSecondOfDay((((avgBedOff + 18 * 60) % 1440) * 60).toLong())
            val avgWakeMin = shown.map { it.wakeTime.toSecondOfDay() / 60 }.average().toInt()
            val avgWake = LocalTime.ofSecondOfDay(avgWakeMin * 60L)

            Card2 {
                Row {
                    Stat("Средний сон", fmtDur(avgDur), Modifier.weight(1f))
                    Stat("Средний отбой", avgBed.s(), Modifier.weight(1f))
                    Stat("Подъём", avgWake.s(), Modifier.weight(1f))
                }
            }

            val bars = shown.map { n ->
                val label = n.wakeDate.format(if (days > 14) numFmt else dayFmt)
                if (mode == 0) {
                    Bar(label, "%.1f".format(n.minutes / 60.0), n.minutes / 720f, n.minutes >= goal)
                } else {
                    val o = SleepStats.bedOffset(n.bedTime)
                    Bar(label, n.bedTime.s(), o / 720f, o <= 360)
                }
            }

            Card2 {
                BarChart(bars)
                Text(
                    if (mode == 0) "Светлые столбики: норма сна выполнена. Шкала до 12 часов."
                    else "Чем выше столбик, тем позже вы легли. Светлые: отбой до полуночи.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }

            // ---------- Выводы и календарь ----------
            InsightsCard(nights, goal)
            HeatmapCard(nights, goal)

            // ---------- История ----------
            Card2 {
                Text("История", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                shown.reversed().take(10).forEach { n ->
                    val extra = buildString {
                        if (n.rating > 0) append(" · ★${n.rating}")
                        if (n.sensor) append(" · датчики") else if (n.auto) append(" · авто")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.wakeDate.format(rowFmt), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${n.bedTime.s()} → ${n.wakeTime.s()} · ${fmtDur(n.minutes)}$extra",
                                color = cs.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            SleepLog.remove(ctx, n)
                            nights = SleepLog.load(ctx)
                        }) { Text("Удалить") }
                    }
                }
            }

            OutlinedButton(
                onClick = { shareReport(ctx, nights, goal) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Поделиться отчётом за неделю") }
        }
    }
}
