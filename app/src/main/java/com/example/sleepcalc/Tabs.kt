package com.example.sleepcalc

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Нижние вкладки: три одинаковые кнопки, активная инвертируется. */
@Composable
fun BottomTabs(tab: Int, onTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val titles = listOf("Циклы", "Прогноз", "Сон")
    val shape = RoundedCornerShape(20.dp)

    Row(
        Modifier.fillMaxWidth().background(cs.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        titles.forEachIndexed { i, title ->
            val selected = tab == i
            val bg by animateColorAsState(
                targetValue = if (selected) cs.primary else cs.surface,
                animationSpec = tween(220),
                label = "tabBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                animationSpec = tween(220),
                label = "tabFg"
            )
            val line by animateColorAsState(
                targetValue = if (selected) cs.primary else cs.outlineVariant,
                animationSpec = tween(220),
                label = "tabLine"
            )
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.1f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                label = "iconScale"
            )
            Column(
                Modifier.weight(1f).height(64.dp)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, line, shape)
                    .clickable {
                        if (!selected) {
                            tick(view)
                            onTab(i)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) {
                    TabIcon(i, fg)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = fg
                )
            }
        }
    }
}

/** Иконки вкладок, нарисованные кодом: 0 = луна, 1 = часы, 2 = столбики. */
@Composable
fun TabIcon(index: Int, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        when (index) {
            0 -> {
                val moon = Path.combine(
                    PathOperation.Difference,
                    Path().apply { addOval(Rect(Offset.Zero, size)) },
                    Path().apply {
                        addOval(Rect(Offset(w * 0.38f, -w * 0.12f), Size(w * 0.85f, w * 0.85f)))
                    }
                )
                drawPath(moon, color)
            }
            1 -> {
                val c = Offset(w / 2, w / 2)
                drawCircle(color, radius = w * 0.44f, center = c, style = Stroke(width = w * 0.12f))
                drawLine(color, c, Offset(w / 2, w * 0.24f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
                drawLine(color, c, Offset(w * 0.7f, w / 2), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
            }
            else -> {
                val bar = w * 0.22f
                val r = CornerRadius(w * 0.08f)
                fun col(x: Float, h: Float) = drawRoundRect(
                    color, Offset(x * w, size.height - h * size.height), Size(bar, h * size.height), r
                )
                col(0.08f, 0.45f)
                col(0.39f, 0.95f)
                col(0.70f, 0.65f)
            }
        }
    }
}
