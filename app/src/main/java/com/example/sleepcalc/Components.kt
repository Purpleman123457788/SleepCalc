package com.example.sleepcalc

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

/** Короткая вибрация вибромотором (работает там, где системная вибрация отключена). */
fun buzz(ctx: Context, ms: Long) {
    try {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (v == null || !v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
        // вибрации нет, ничего страшного
    }
}

/** Короткий «тик»: системный или усиленный, в зависимости от настроек и телефона. */
fun tick(view: View) {
    if (!hapticsOn) return
    if (hapticStrong) buzz(view.context, 18)
    else view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** Цвета кнопки-таблетки: заливка, текст и рамка (плавно меняются при выборе). */
data class PillColors(val bg: Color, val fg: Color, val line: Color)

@Composable
fun pillColors(selected: Boolean): PillColors {
    val cs = MaterialTheme.colorScheme
    val m = LocalMaterialStyle.current
    val bgT = if (m) {
        if (selected) cs.secondaryContainer else cs.surfaceContainer
    } else {
        if (selected) cs.primary else cs.surface
    }
    val fgT = if (m) {
        if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    } else {
        if (selected) cs.onPrimary else cs.onSurfaceVariant
    }
    val lineT = if (m) Color.Transparent else if (selected) cs.primary else cs.outlineVariant
    val bg by animateColorAsState(bgT, tween(220), label = "pillBg")
    val fg by animateColorAsState(fgT, tween(220), label = "pillFg")
    val line by animateColorAsState(lineT, tween(220), label = "pillLine")
    return PillColors(bg, fg, line)
}

/** Ряд равных кнопок-переключателей, как нижние вкладки. Текст по центру. */
@Composable
fun SegmentRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEachIndexed { i, label ->
            val sel = selected == i
            val c = pillColors(sel)
            Box(
                Modifier.weight(1f).height(52.dp)
                    .clip(shape)
                    .background(c.bg)
                    .border(1.dp, c.line, shape)
                    .clickable {
                        if (!sel) {
                            tick(view)
                            onSelect(i)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = c.fg
                )
            }
        }
    }
}

/** Заголовок раздела в настройках. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp)
    )
}

@Composable
fun OptionChip(selected: Boolean, text: String, onClick: () -> Unit) {
    if (LocalMaterialStyle.current) {
        FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(text) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
            )
        )
    }
}

@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) {
    val m = LocalMaterialStyle.current
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = if (m) cs.surfaceContainerHigh else cs.surface),
        border = if (m) null else BorderStroke(1.dp, cs.outlineVariant),
        shape = RoundedCornerShape(if (m) 28.dp else 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

/** Плавное появление блока: прозрачность и сдвиг снизу. index задаёт задержку (каскад). */
@Composable
fun Reveal(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 380, delayMillis = index * 60),
        label = "reveal"
    )
    Box(Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 48f }) { content() }
}

/** Маленький блок «подпись + значение». */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Строка с переключателем: заголовок, пояснение, Switch. */
@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

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
        val view = LocalView.current
        var first by remember { mutableStateOf(true) }
        // Короткий «тик» при каждом изменении часов или минут на циферблате
        LaunchedEffect(state.hour, state.minute) {
            if (first) first = false else tick(view)
        }
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { onChange(state.hour * 60 + state.minute); show = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Отмена") } },
            text = {
                // Прокрутка нужна на маленьких экранах и в горизонтальной ориентации
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = state)
                }
            }
        )
    }
}
