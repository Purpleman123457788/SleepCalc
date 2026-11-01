package com.example.sleepcalc

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
