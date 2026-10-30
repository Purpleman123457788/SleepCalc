package com.example.sleepcalc

import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** Один столбик графика. fraction от 0 до 1, good = светлый цвет. */
data class Bar(val label: String, val value: String, val fraction: Float, val good: Boolean)

@Composable
fun BarChart(bars: List<Bar>) {
    val cs = MaterialTheme.colorScheme
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val maxH = 160.dp
    val few = bars.size <= 14
    val valueSize = if (bars.size <= 7) 11.sp else 8.sp

    Row(
        Modifier.fillMaxWidth().height(maxH + 48.dp),
        horizontalArrangement = Arrangement.spacedBy(if (few) 6.dp else 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { b ->
            val h by animateDpAsState(
                targetValue = if (go) maxH * b.fraction.coerceIn(0.04f, 1f) else 0.dp,
                animationSpec = tween(600),
                label = "bar"
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (few) Text(b.value, fontSize = valueSize, maxLines = 1, softWrap = false, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth(if (few) 0.7f else 0.8f).height(h)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (b.good) cs.primary else cs.outline)
                )
                Spacer(Modifier.height(4.dp))
                Text(b.label, fontSize = if (few) 11.sp else 9.sp, maxLines = 1, softWrap = false, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Минуты после 18:00 (чтобы отбой около полуночи считался «непрерывно»). */
private fun bedOffset(t: LocalTime): Int = (t.toSecondOfDay() / 60 - 18 * 60 + 1440) % 1440

@Composable
fun SleepScreen(settings: Settings) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme

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
            shape = RoundedCornerShape(18.dp)
        ) { Text(if (showManual) "Скрыть ручной ввод" else "Добавить ночь вручную") }

        if (showManual) {
            Card2 {
                TimeField("Лёг спать", mBed) { mBed = it }
                TimeField("Проснулся", mWake) { mWake = it }
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
                    "Пока нет записей. Нажмите «Лёг спать» перед сном или добавьте ночь вручную.",
                    color = cs.onSurfaceVariant
                )
            }
        } else {
            val avgDur = shown.map { it.minutes }.average().toInt()
            val avgBedOff = shown.map { bedOffset(it.bedTime) }.average().toInt()
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
                    Bar(label, "%.1f".format(n.minutes / 60.0), n.minutes / 720f, n.minutes >= settings.goalMin)
                } else {
                    val o = bedOffset(n.bedTime)
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

            // ---------- История ----------
            Card2 {
                Text("История", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                shown.reversed().take(10).forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.wakeDate.format(rowFmt), fontWeight = FontWeight.SemiBold)
                            Text(
                                "${n.bedTime.s()} → ${n.wakeTime.s()} · ${fmtDur(n.minutes)}",
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
        }
    }
}
