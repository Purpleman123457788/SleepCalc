package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/* ============================================================
 *  1. ЛОГИКА РАСЧЁТОВ (без UI, легко тестируется)
 * ============================================================ */
object SleepMath {
    const val CYCLE_MIN = 90L              // один цикл сна = 90 минут
    const val FALL_ASLEEP_MIN = 15L        // в среднем 15 минут на засыпание
    const val SOFT_ALARM_OFFSET_MIN = 10L  // «мягкий» будильник — за 10 минут до основного
    val CYCLE_RANGE = 3..7                 // 3..7 циклов = 4.5 .. 10.5 часов сна (5 вариантов)

    const val FULL_SLEEP_MIN = 450L        // полноценный сон = 5 циклов = 7.5 часов
    const val BASE_AWAKE_MIN = 15 * 60L    // после полноценного сна бодрствуем примерно 16 часов
    const val NIGHT_CYCLES = 5             // сколько циклов советуем на ночь

    data class Option(val time: LocalTime, val cycles: Int)

    /**
     * «Лягу в X» -> когда вставать.
     * Время пробуждения = X + 15 мин (засыпание) + N * 90 мин.
     * LocalTime сам корректно переходит через полночь.
     */
    fun wakeOptions(bed: LocalTime): List<Option> =
        CYCLE_RANGE.map { n -> Option(bed.plusMinutes(FALL_ASLEEP_MIN + n * CYCLE_MIN), n) }

    /**
     * «Хочу проснуться в X» -> когда ложиться.
     * Время отбоя = X - N * 90 мин - 15 мин (засыпание).
     */
    fun bedOptions(wake: LocalTime): List<Option> =
        CYCLE_RANGE.reversed().map { n -> Option(wake.minusMinutes(n * CYCLE_MIN + FALL_ASLEEP_MIN), n) }

    data class SleepForecast(
        val sleptMinutes: Long,       // сколько человек был в постели
        val verdict: String,          // оценка сна
        val isGood: Boolean,
        val sleepyTime: LocalTime,    // когда захочется спать
        val sleepyFrom: LocalTime,    // начало интервала (−30 мин)
        val sleepyTo: LocalTime,      // конец интервала (+30 мин)
        val cycles: Int,              // сколько циклов советуем поспать
        val wakeAlarm: LocalTime,     // основной будильник
        val softAlarm: LocalTime      // мягкий будильник
    )

    /**
     * Прогноз сонливости по прошлому сну.
     *
     * 1) Время сна = подъём − отбой (если получилось отрицательным, значит через полночь, +24 ч).
     * 2) Реальный сон = время в постели − 15 минут на засыпание.
     * 3) Недосып = 7.5 ч − реальный сон. За каждый недоспанный час окно бодрствования
     *    сокращается на 45 минут (это грубая оценка, а не медицинская).
     * 4) Захочется спать = время подъёма + окно бодрствования (16 ч минус сокращение, не менее 8 ч).
     * 5) Если лечь в это время, то будильник = отбой + 15 мин + 5 циклов * 90 мин.
     */
    fun forecast(bedTime: LocalTime, wakeTime: LocalTime): SleepForecast {
        var slept = Duration.between(bedTime, wakeTime).toMinutes()
        if (slept < 0) slept += 24 * 60

        val realSleep = (slept - FALL_ASLEEP_MIN).coerceAtLeast(0L)
        val deficit = (FULL_SLEEP_MIN - realSleep).coerceAtLeast(0L)
        val awakeWindow = (BASE_AWAKE_MIN - deficit * 3 / 4).coerceAtLeast(8 * 60L)

        val sleepy = wakeTime.plusMinutes(awakeWindow)

        val hours = realSleep / 60.0
        val (text, good) = when {
            hours < 5 -> "Вы спали ${fmt(slept)}. Это мало, сонливость придёт раньше обычного." to false
            hours < 7 -> "Вы спали ${fmt(slept)}. Немного недоспали." to false
            hours <= 9.5 -> "Вы спали ${fmt(slept)}. Отличный сон!" to true
            else -> "Вы спали ${fmt(slept)}. Это много, возможна вялость." to false
        }

        // Если лечь в момент сонливости: отбой + 15 мин на засыпание + N*90 мин
        val wake = sleepy.plusMinutes(FALL_ASLEEP_MIN + NIGHT_CYCLES * CYCLE_MIN)
        return SleepForecast(
            sleptMinutes = slept,
            verdict = text,
            isGood = good,
            sleepyTime = sleepy,
            sleepyFrom = sleepy.minusMinutes(30),
            sleepyTo = sleepy.plusMinutes(30),
            cycles = NIGHT_CYCLES,
            wakeAlarm = wake,
            softAlarm = wake.minusMinutes(SOFT_ALARM_OFFSET_MIN)
        )
    }

    fun fmt(minutes: Long) = "${minutes / 60} ч ${minutes % 60} мин"
}

/* ============================================================
 *  2. БУДИЛЬНИКИ через системное приложение «Часы»
 * ============================================================ */
object AlarmHelper {
    /** Ставит два будильника: мягкий (за 10 мин до) и основной. */
    fun setTwoAlarms(context: Context, main: LocalTime, label: String) {
        val soft = main.minusMinutes(SleepMath.SOFT_ALARM_OFFSET_MIN)
        try {
            listOf(soft to "$label (мягкий)", main to label).forEach { (t, msg) ->
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, t.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, msg)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true) // без открытия экрана «Часов»
                }
                context.startActivity(intent)
            }
            Toast.makeText(context, "Будильники: ${soft.s()} и ${main.s()}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Не найдено приложение «Часы»", Toast.LENGTH_LONG).show()
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun LocalTime.s(): String = format(timeFmt)

/* ============================================================
 *  3. ТЕМА (тёмная, минималистичная)
 * ============================================================ */
private val SleepColors = darkColorScheme(
    primary = Color(0xFF9AA5FF),
    onPrimary = Color(0xFF0B1020),
    background = Color(0xFF0B1020),
    surface = Color(0xFF141A2E),
    surfaceVariant = Color(0xFF1C2340),
    onBackground = Color(0xFFE6E8F5),
    onSurface = Color(0xFFE6E8F5),
    secondary = Color(0xFF6FD6B4)
)

@Composable
fun SleepTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = SleepColors, content = content)

/* ============================================================
 *  4. UI
 * ============================================================ */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SleepTheme { SleepApp() } }
    }
}

@Composable
fun SleepApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("🌙") }, label = { Text("Циклы сна") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("☀️") }, label = { Text("Статистика") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) CyclesScreen() else StatsScreen()
        }
    }
}

/** Кнопка-поле выбора времени + диалог с TimePicker. Время хранится в минутах от полуночи. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(LocalTime.ofSecondOfDay(minutes * 60L).s(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    if (show) {
        val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { onChange(state.hour * 60 + state.minute); show = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Отмена") } },
            text = { TimePicker(state = state) }
        )
    }
}

@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth()
) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }

/* ---------- Экран 1: Циклы сна ---------- */
@Composable
fun CyclesScreen() {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableIntStateOf(0) }         // 0 = лягу в…, 1 = проснуться в…
    var bedMin by rememberSaveable { mutableIntStateOf(23 * 60) }  // 23:00
    var wakeMin by rememberSaveable { mutableIntStateOf(7 * 60) }  // 07:00

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Циклы сна", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0 }, label = { Text("Лягу спать в…") })
            FilterChip(mode == 1, { mode = 1 }, label = { Text("Хочу проснуться в…") })
        }

        if (mode == 0) {
            TimeField("Лягу спать в", bedMin) { bedMin = it }
            Text("Варианты пробуждения (+15 мин на засыпание + N×90 мин):", color = Color.Gray)
            SleepMath.wakeOptions(LocalTime.ofSecondOfDay(bedMin * 60L)).forEach { opt ->
                Card2 {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.time.s(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${opt.cycles} цикл. · ${opt.cycles * 1.5} ч сна" +
                                    if (opt.cycles in 5..6) " · рекомендуется" else "",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Button(onClick = { AlarmHelper.setTwoAlarms(ctx, opt.time, "Подъём") }) {
                            Text("2 будильника")
                        }
                    }
                }
            }
        } else {
            TimeField("Хочу проснуться в", wakeMin) { wakeMin = it }
            Text("Во сколько нужно лечь (−N×90 мин −15 мин на засыпание):", color = Color.Gray)
            SleepMath.bedOptions(LocalTime.ofSecondOfDay(wakeMin * 60L)).forEach { opt ->
                Card2 {
                    Text(opt.time.s(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${opt.cycles} цикл. · ${opt.cycles * 1.5} ч сна" +
                            if (opt.cycles in 5..6) " · рекомендуется" else "",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Button(
                onClick = { AlarmHelper.setTwoAlarms(ctx, LocalTime.ofSecondOfDay(wakeMin * 60L), "Подъём") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Поставить 2 будильника на утро") }
        }
    }
}

/* ---------- Экран 2: Статистика (прогноз, когда захочется спать) ---------- */
@Composable
fun StatsScreen() {
    val ctx = LocalContext.current
    var bedMin by rememberSaveable { mutableIntStateOf(23 * 60) }   // лёг 23:00
    var wokeMin by rememberSaveable { mutableIntStateOf(7 * 60) }   // проснулся 07:00
    var report by remember { mutableStateOf<SleepMath.SleepForecast?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Когда захочется спать", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        TimeField("Лёг спать (вечером/ночью)", bedMin) { bedMin = it; report = null }
        TimeField("Проснулся (утром)", wokeMin) { wokeMin = it; report = null }

        Button(
            onClick = {
                report = SleepMath.forecast(
                    LocalTime.ofSecondOfDay(bedMin * 60L),
                    LocalTime.ofSecondOfDay(wokeMin * 60L)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Рассчитать") }

        report?.let { r ->
            Card2 {
                Text("Сон", color = Color.Gray)
                Text(SleepMath.fmt(r.sleptMinutes), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    r.verdict,
                    color = if (r.isGood) MaterialTheme.colorScheme.secondary else Color(0xFFFFB170)
                )
            }
            Card2 {
                Text("Захочется спать примерно в", color = Color.Gray)
                Text(r.sleepyTime.s(), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("Интервал: ${r.sleepyFrom.s()} – ${r.sleepyTo.s()}", color = Color.Gray)
            }
            Card2 {
                Text("Рекомендация", color = Color.Gray)
                Text(
                    "Если ляжете в ${r.sleepyTime.s()}, поспите ${r.cycles} цикл. (${r.cycles * 1.5} ч). " +
                        "Будильники: ${r.softAlarm.s()} и ${r.wakeAlarm.s()}.",
                    fontSize = 16.sp
                )
                Button(onClick = { AlarmHelper.setTwoAlarms(ctx, r.wakeAlarm, "Подъём") }) {
                    Text("Поставить 2 будильника")
                }
            }
        }
    }
}
