package com.example.sleepcalc

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Дыхание 4-7-8: вдох 4 секунды, задержка 7, выдох 8, четыре цикла. */
@Composable
fun BreathingDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var phase by remember { mutableIntStateOf(0) }
    var cycle by remember { mutableIntStateOf(1) }
    var seconds by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0.55f) }
    val names = listOf("Вдох", "Задержка", "Выдох")

    LaunchedEffect(running) {
        if (!running) {
            scale.animateTo(0.55f, tween(500))
            return@LaunchedEffect
        }
        for (c in 1..4) {
            cycle = c
            phase = 0
            tick(view)
            launch { scale.animateTo(1f, tween(4000, easing = LinearEasing)) }
            for (s in 4 downTo 1) { seconds = s; delay(1000) }
            phase = 1
            tick(view)
            for (s in 7 downTo 1) { seconds = s; delay(1000) }
            phase = 2
            tick(view)
            launch { scale.animateTo(0.55f, tween(8000, easing = LinearEasing)) }
            for (s in 8 downTo 1) { seconds = s; delay(1000) }
        }
        done = true
        running = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface)
        ) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Дыхание 4-7-8", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Вдох 4 секунды, задержка 7, выдох 8. Четыре цикла помогают успокоиться перед сном.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = cs.onSurfaceVariant
                )
                Box(Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape)
                            .background(cs.primary.copy(alpha = 0.08f))
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                            .clip(CircleShape)
                            .background(cs.primary.copy(alpha = 0.22f))
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (running) names[phase] else if (done) "Готово" else "Готовы?",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (running) Text("$seconds", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    if (done) "Хорошая работа. Теперь можно ложиться."
                    else if (running) "Цикл $cycle из 4"
                    else "Сядьте или лягте удобно и расслабьте плечи.",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = cs.onSurfaceVariant
                )
                Button(
                    onClick = {
                        if (running) {
                            running = false
                        } else {
                            done = false
                            running = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (running) "Остановить" else if (done) "Ещё раз" else "Начать") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        }
    }
}
