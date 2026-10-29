package com.example.sleepcalc

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

/** Короткий «тик» вибрации (если вибрация включена в настройках). */
fun tick(view: View) {
    if (hapticsOn) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
            text = { TimePicker(state = state) }
        )
    }
}
