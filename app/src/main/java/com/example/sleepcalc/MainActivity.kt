package com.example.sleepcalc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/* ============================================================
 *  0. НАСТРОЙКИ (хранятся в SharedPreferences)
 * ============================================================ */
data class Settings(
    val theme: Int = 2,        // 0 = как в системе, 1 = светлая, 2 = тёмная
    val softMin: Int = 10,     // за сколько минут мягкий будильник (0 = выключен)
    val awakeHours: Int = 15   // сколько часов бодрствуем после полноценного сна
) {
    companion object {
        private const val FILE = "sleepcalc_settings"
        fun load(ctx: Context): Settings {
            val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return Settings(sp.getInt("theme", 2), sp.getInt("softMin", 10), sp.getInt("awakeHours", 15))
        }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("theme", theme)
            .putInt("softMin", softMin)
            .putInt("awakeHours", awakeHours)
            .apply()
    }
}

/* ============================================================
 *  1. ЛОГИКА РАСЧЁТОВ (без UI)
 * ============================================================ */
object SleepMath {
    const val CYCLE_MIN = 90L          // один цикл сна = 90 минут
    const val FALL_ASLEEP_MIN = 15L    // в среднем 15 минут на засыпание
    val CYCLE_RANGE = 3..7             // 3..7 циклов = 4.5 .. 10.5 часов сна
    const val FULL_SLEEP_MIN = 450L    // полноценный сон = 5 циклов = 7.5 часов
    const val NIGHT_CYCLES = 5         // сколько циклов советуем на ночь

    data class Option(val time: LocalTime, val cycles: Int)

    /** «Лягу в X» -> когда вставать: X + 15 мин (засыпание) + N * 90 мин. */
    fun wakeOptions(bed: LocalTime): List<Option> =
        CYCLE_RANGE.map { n -> Option(bed.plusMinutes(FALL_ASLEEP_MIN + n * CYCLE_MIN), n) }

    /** «Хочу проснуться в X» -> когда ложиться: X - N * 90 мин - 15 мин (засыпание). */
    fun bedOptions(wake: LocalTime): List<Option> =
        CYCLE_RANGE.reversed().map { n -> Option(wake.minusMinutes(n * CYCLE_MIN + FALL_ASLEEP_MIN), n) }

    data class SleepForecast(
        val sleptMinutes: Long,
        val verdict: String,
        val sleepyTime: LocalTime,
        val sleepyFrom: LocalTime,
        val sleepyTo: LocalTime,
        val cycles: Int,
        val wakeAlarm: LocalTime,
        val softAlarm: LocalTime
    )

    /**
     * Прогноз сонливости по прошлому сну.
     * 1) Сон = подъём - отбой (если меньше нуля, прибавляем 24 ч).
     * 2) Реальный сон = время в постели - 15 минут на засыпание.
     * 3) Недосып = 7.5 ч - реальный сон; за каждый недоспанный час окно бодрствования
     *    сокращается на 45 минут (грубая оценка).
     * 4) Захочется спать = подъём + окно бодрствования (не менее 8 ч).
     * 5) Если лечь в это время: будильник = отбой + 15 мин + 5 циклов * 90 мин.
     */
    fun forecast(bedTime: LocalTime, wakeTime: LocalTime, awakeHours: Int, softMin: Int): SleepForecast {
        var slept = Duration.between(bedTime, wakeTime).toMinutes()
        if (slept < 0) slept += 24 * 60

        val realSleep = (slept - FALL_ASLEEP_MIN).coerceAtLeast(0L)
        val deficit = (FULL_SLEEP_MIN - realSleep).coerceAtLeast(0L)
        val awakeWindow = (awakeHours * 60L - deficit * 3 / 4).coerceAtLeast(8 * 60L)
        val sleepy = wakeTime.plusMinutes(awakeWindow)

        val hours = realSleep / 60.0
        val text = when {
            hours < 5 -> "Вы спали ${fmt(slept)}. Это мало, сонливость придёт раньше обычного."
            hours < 7 -> "Вы спали ${fmt(slept)}. Немного недоспали."
            hours <= 9.5 -> "Вы спали ${fmt(slept)}. Отличный сон."
            else -> "Вы спали ${fmt(slept)}. Это много, возможна вялость."
        }

        val wake = sleepy.plusMinutes(FALL_ASLEEP_MIN + NIGHT_CYCLES * CYCLE_MIN)
        return SleepForecast(
            sleptMinutes = slept,
            verdict = text,
            sleepyTime = sleepy,
            sleepyFrom = sleepy.minusMinutes(30),
            sleepyTo = sleepy.plusMinutes(30),
            cycles = NIGHT_CYCLES,
            wakeAlarm = wake,
            softAlarm = wake.minusMinutes(softMin.toLong())
        )
    }

    fun fmt(minutes: Long) = "${minutes / 60} ч ${minutes % 60} мин"
}

/* ============================================================
 *  2. БУДИЛЬНИКИ через системное приложение «Часы»
 * ============================================================ */
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

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun LocalTime.s(): String = format(timeFmt)

/* ============================================================
 *  3. ТЕМА: чёрно-белая (светлая и тёмная)
 * ============================================================ */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0A0A0A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFF6B6B6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF0A0A0A),
    background = Color.White,
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFF4F4F4),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEAEAEA),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFCDCDCD),
    outlineVariant = Color(0xFFE0E0E0),
    surfaceContainerHigh = Color(0xFFF4F4F4),
    surfaceContainerHighest = Color(0xFFE8E8E8)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFA0A0A0),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFF2F2F2),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF262626)
)

@Composable
fun SleepTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme

    // Цвет статус-бара и панели навигации в цвет фона
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/* ============================================================
 *  4. UI
 * ============================================================ */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var settings by remember { mutableStateOf(Settings.load(ctx)) }
            SleepTheme(settings.theme) {
                SleepApp(settings) { new ->
                    settings = new
                    new.save(ctx)
                }
            }
        }
    }
}

@Composable
fun SleepApp(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (!showSettings) BottomTabs(tab) { tab = it } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(settings, onSettingsChange) { showSettings = false }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (tab == 0) "Циклы сна" else "Когда захочется спать",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    if (tab == 0) CyclesScreen(settings) else StatsScreen(settings)
                }
            }
        }
    }
}

/** Нижние вкладки в виде скруглённой таблетки. */
@Composable
fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().background(cs.background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(28.dp))
                .padding(4.dp)
        ) {
            listOf("Циклы сна", "Статистика").forEachIndexed { i, title ->
                val selected = tab == i
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selected) cs.primary else Color.Transparent)
                        .clickable { onTab(i) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) cs.onPrimary else cs.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun OptionChip(selected: Boolean, text: String, onClick: () -> Unit) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
    )
)

@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) = Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth()
) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }

/** Поле выбора времени + диалог с TimePicker. Время хранится в минутах от полуночи. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { show = true },
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Text(LocalTime.ofSecondOfDay(minutes * 60L).s(), fontSize = 24.sp, fontWeight = FontWeight.Bold)
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

/* ---------- Экран 1: Циклы сна ---------- */
@Composable
fun CyclesScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var mode by rememberSaveable { mutableIntStateOf(0) }          // 0 = лягу в…, 1 = проснуться в…
    var bedMin by rememberSaveable { mutableIntStateOf(23 * 60) }   // 23:00
    var wakeMin by rememberSaveable { mutableIntStateOf(7 * 60) }   // 07:00
    val btnText = if (settings.softMin > 0) "2 будильника" else "Будильник"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip(mode == 0, "Лягу спать в…") { mode = 0 }
            OptionChip(mode == 1, "Хочу проснуться в…") { mode = 1 }
        }

        if (mode == 0) {
            TimeField("Лягу спать в", bedMin) { bedMin = it }
            Text("Варианты пробуждения (+15 мин на засыпание + N×90 мин):", color = cs.onSurfaceVariant)
            SleepMath.wakeOptions(LocalTime.ofSecondOfDay(bedMin * 60L)).forEach { opt ->
                Card2 {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.time.s(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Text("${opt.cycles} цикл. · ${opt.cycles * 1.5} ч сна", color = cs.onSurfaceVariant)
                            if (opt.cycles in 5..6) {
                                Text("рекомендуется", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(onClick = { AlarmHelper.setAlarms(ctx, opt.time, "Подъём", settings.softMin) }) {
                            Text(btnText)
                        }
                    }
                }
            }
        } else {
            TimeField("Хочу проснуться в", wakeMin) { wakeMin = it }
            Text("Во сколько нужно лечь (−N×90 мин −15 мин на засыпание):", color = cs.onSurfaceVariant)
            SleepMath.bedOptions(LocalTime.ofSecondOfDay(wakeMin * 60L)).forEach { opt ->
                Card2 {
                    Text(opt.time.s(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("${opt.cycles} цикл. · ${opt.cycles * 1.5} ч сна", color = cs.onSurfaceVariant)
                    if (opt.cycles in 5..6) {
                        Text("рекомендуется", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Button(
                onClick = { AlarmHelper.setAlarms(ctx, LocalTime.ofSecondOfDay(wakeMin * 60L), "Подъём", settings.softMin) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (settings.softMin > 0) "Поставить 2 будильника на утро" else "Поставить будильник на утро") }
        }
    }
}
/* ---------- Экран 2: Статистика (когда захочется спать) ---------- */
@Composable
fun StatsScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var bedMin by rememberSaveable { mutableIntStateOf(23 * 60) }   // лёг 23:00
    var wokeMin by rememberSaveable { mutableIntStateOf(7 * 60) }   // проснулся 07:00
    var report by remember(settings.awakeHours, settings.softMin) {
        mutableStateOf<SleepMath.SleepForecast?>(null)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimeField("Лёг спать (вечером/ночью)", bedMin) { bedMin = it; report = null }
        TimeField("Проснулся (утром)", wokeMin) { wokeMin = it; report = null }

        Button(
            onClick = {
                report = SleepMath.forecast(
                    LocalTime.ofSecondOfDay(bedMin * 60L),
                    LocalTime.ofSecondOfDay(wokeMin * 60L),
                    settings.awakeHours,
                    settings.softMin
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Рассчитать") }

        report?.let { r ->
            Card2 {
                Text("Сон", color = cs.onSurfaceVariant)
                Text(SleepMath.fmt(r.sleptMinutes), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(r.verdict, color = cs.onSurfaceVariant)
            }
            Card2 {
                Text("Захочется спать примерно в", color = cs.onSurfaceVariant)
                Text(r.sleepyTime.s(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("Интервал: ${r.sleepyFrom.s()} – ${r.sleepyTo.s()}", color = cs.onSurfaceVariant)
            }
            Card2 {
                Text("Рекомендация", color = cs.onSurfaceVariant)
                val alarms = if (settings.softMin > 0)
                    "Будильники: ${r.softAlarm.s()} и ${r.wakeAlarm.s()}."
                else
                    "Будильник: ${r.wakeAlarm.s()}."
                Text(
                    "Если ляжете в ${r.sleepyTime.s()}, поспите ${r.cycles} цикл. (${r.cycles * 1.5} ч). $alarms",
                    fontSize = 16.sp
                )
                Button(onClick = { AlarmHelper.setAlarms(ctx, r.wakeAlarm, "Подъём", settings.softMin) }) {
                    Text(if (settings.softMin > 0) "Поставить 2 будильника" else "Поставить будильник")
                }
            }
        }
    }
}

/* ---------- Экран настроек ---------- */
@Composable
fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 26.sp, color = cs.onBackground)
            }
            Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Card2 {
            Text("Тема", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Светлая: чёрное на белом. Тёмная: белое на чёрном.", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(settings.theme == 0, "Системная") { onChange(settings.copy(theme = 0)) }
                OptionChip(settings.theme == 1, "Светлая") { onChange(settings.copy(theme = 1)) }
                OptionChip(settings.theme == 2, "Тёмная") { onChange(settings.copy(theme = 2)) }
            }
        }

        Card2 {
            Text("Мягкий будильник", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("За сколько минут до основного (в минутах). «Выкл» ставит один будильник.", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(settings.softMin == 0, "Выкл") { onChange(settings.copy(softMin = 0)) }
                listOf(5, 10, 15, 20).forEach { m ->
                    OptionChip(settings.softMin == m, "$m") { onChange(settings.copy(softMin = m)) }
                }
            }
        }

        Card2 {
            Text("Бодрствование после сна", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Через сколько часов после полноценного сна обычно хочется спать (в часах).", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(14, 15, 16, 17).forEach { h ->
                    OptionChip(settings.awakeHours == h, "$h") { onChange(settings.copy(awakeHours = h)) }
                }
            }
        }
    }
}
