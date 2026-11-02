package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ruLoc: Locale = Locale.forLanguageTag("ru")

/** Запускает таймер дневного сна через приложение «Часы». */
private fun startNap(ctx: Context, minutes: Int) {
    try {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, "Дневной сон")
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        ctx.startActivity(i)
        Toast.makeText(ctx, "Таймер на $minutes мин запущен", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не найдено приложение «Часы»", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun CyclesScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var mode by rememberSaveable { mutableIntStateOf(0) }  // 0 = лягу, 1 = проснуться
    var bedMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "cyc_bed", 23 * 60)) }
    var wakeMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "cyc_wake", 7 * 60)) }
    val btnText = if (settings.softMin > 0) "2 будильника" else "Будильник"

    val wakeOpts = remember(bedMin) { SleepMath.wakeOptions(LocalTime.ofSecondOfDay(bedMin * 60L)) }
    val bedOpts = remember(wakeMin) { SleepMath.bedOptions(LocalTime.ofSecondOfDay(wakeMin * 60L)) }

    // Данные для главной карточки: когда лучше лечь сегодня
    val info = remember { WidgetCalc.load(ctx) }
    val wakeAt = info.sleepy.plusMinutes(465)
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    val date = LocalDate.now()
        .format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ruLoc))
        .replaceFirstChar { it.uppercase() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Reveal(0) {
            HeroCard { fg, sub, bg ->
                Text("$greeting · $date", fontSize = 13.sp, color = sub)
                Text(
                    if (info.left == "уже пора спать") "Пора ложиться" else "Лечь спать около ${info.sleepy.s()}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
                Text(
                    "${info.left} · 5 циклов дадут подъём в ${wakeAt.s()}",
                    fontSize = 14.sp,
                    color = sub
                )
                LinearProgressIndicator(
                    progress = { info.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = fg,
                    trackColor = fg.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { AlarmHelper.setAlarms(ctx, wakeAt, "Подъём", settings.softMin) },
                    colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (settings.softMin > 0) "Будильники на ${wakeAt.s()}" else "Будильник на ${wakeAt.s()}")
                }
            }
        }

        SegmentRow(
            options = listOf("Лягу спать", "Проснуться"),
            selected = mode,
            onSelect = { mode = it },
            icons = listOf(0, 1)
        )

        // Поле времени меняется вместе с кнопками: плавный переход «Лягу спать в» / «Хочу проснуться в»
        Crossfade(targetState = mode, label = "timeField") { m ->
            if (m == 0) {
                TimeField("Лягу спать в", bedMin, icon = 2) { bedMin = it; Prefs.putInt(ctx, "cyc_bed", it) }
            } else {
                TimeField("Хочу проснуться в", wakeMin, icon = 2) { wakeMin = it; Prefs.putInt(ctx, "cyc_wake", it) }
            }
        }

        if (mode == 0) {
            Text("Варианты пробуждения (+15 мин на засыпание + N×90 мин):", color = cs.onSurfaceVariant)
            wakeOpts.forEachIndexed { i, opt ->
                OptionCard(i + 1, opt.time.s(), opt.cycles, btnText) {
                    AlarmHelper.setAlarms(ctx, opt.time, "Подъём", settings.softMin)
                }
            }
        } else {
            Text("Во сколько нужно лечь (−N×90 мин −15 мин на засыпание):", color = cs.onSurfaceVariant)
            bedOpts.forEachIndexed { i, opt -> OptionCard(i + 1, opt.time.s(), opt.cycles, null, null) }
            Button(
                onClick = { AlarmHelper.setAlarms(ctx, LocalTime.ofSecondOfDay(wakeMin * 60L), "Подъём", settings.softMin) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (settings.softMin > 0) "Поставить 2 будильника на утро" else "Поставить будильник на утро") }
        }

        Reveal(9) {
            Card2 {
                Text("Дневной сон", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "Лучше до 15:00, чтобы не сбить ночной сон. 20 минут: бодрость без сонной инерции. 90 минут: полный цикл.",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 30, 90).forEach { m ->
                        OutlinedButton(
                            onClick = { startNap(ctx, m) },
                            modifier = Modifier.weight(1f)
                        ) { Text("$m мин") }
                    }
                }
            }
        }
    }
}

/** Карточка варианта: время, число циклов и (если нужно) кнопка будильника. */
@Composable
fun OptionCard(index: Int, time: String, cycles: Int, buttonText: String?, onButton: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Reveal(index) {
        Card2 {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(time, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("$cycles цикл. · ${cycles * 1.5} ч сна", color = cs.onSurfaceVariant)
                    if (cycles in 5..6) Text("рекомендуется", fontWeight = FontWeight.SemiBold)
                }
                if (buttonText != null && onButton != null) {
                    Button(onClick = onButton) { Text(buttonText) }
                }
            }
        }
    }
}
