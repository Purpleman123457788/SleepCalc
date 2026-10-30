package com.example.sleepcalc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SegmentRow(
            options = listOf("Лягу спать", "Проснуться"),
            selected = mode,
            onSelect = { mode = it },
            icons = listOf(0, 1)
        )

        if (mode == 0) {
            TimeField("Лягу спать в", bedMin) { bedMin = it; Prefs.putInt(ctx, "cyc_bed", it) }
            Text("Варианты пробуждения (+15 мин на засыпание + N×90 мин):", color = cs.onSurfaceVariant)
            wakeOpts.forEachIndexed { i, opt ->
                OptionCard(i, opt.time.s(), opt.cycles, btnText) {
                    AlarmHelper.setAlarms(ctx, opt.time, "Подъём", settings.softMin)
                }
            }
        } else {
            TimeField("Хочу проснуться в", wakeMin) { wakeMin = it; Prefs.putInt(ctx, "cyc_wake", it) }
            Text("Во сколько нужно лечь (−N×90 мин −15 мин на засыпание):", color = cs.onSurfaceVariant)
            bedOpts.forEachIndexed { i, opt -> OptionCard(i, opt.time.s(), opt.cycles, null, null) }
            Button(
                onClick = { AlarmHelper.setAlarms(ctx, LocalTime.ofSecondOfDay(wakeMin * 60L), "Подъём", settings.softMin) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (settings.softMin > 0) "Поставить 2 будильника на утро" else "Поставить будильник на утро") }
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
