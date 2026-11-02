package com.example.sleepcalc

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Значок кнопки: пауза-квадрат при игре, треугольник при остановке. */
@Composable
fun PlayIcon(playing: Boolean, color: Color) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        if (playing) {
            drawRoundRect(color, Offset(w * 0.18f, w * 0.18f), Size(w * 0.64f, w * 0.64f), CornerRadius(w * 0.12f))
        } else {
            val p = Path().apply {
                moveTo(w * 0.26f, w * 0.14f)
                lineTo(w * 0.86f, w * 0.5f)
                lineTo(w * 0.26f, w * 0.86f)
                close()
            }
            drawPath(p, color)
        }
    }
}

/** Плитка выбора звука. */
@Composable
private fun SoundTile(
    name: String,
    desc: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val c = pillColors(selected)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape)
            .background(c.bg)
            .border(1.dp, c.line, shape)
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.fg)
        Text(desc, fontSize = 12.sp, color = c.fg.copy(alpha = 0.8f))
    }
}

@Composable
fun SoundsScreen(settings: Settings) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val cs = MaterialTheme.colorScheme
    var type by remember { mutableIntStateOf(Prefs.getInt(ctx, "snd_type", 1).coerceIn(0, 3)) }
    var minutes by remember { mutableIntStateOf(Prefs.getInt(ctx, "snd_min", 30)) }
    var volume by remember { mutableFloatStateOf(Prefs.sp(ctx).getFloat("snd_vol", 0.7f)) }
    LaunchedEffect(Unit) { SoundState.volume = volume }

    val playing = SoundState.playing
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val leftSec = if (playing && SoundState.endAtMs > 0) {
        ((SoundState.endAtMs - now) / 1000).coerceAtLeast(0)
    } else -1L

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseValue"
    )

    fun toggle() {
        tick(view)
        if (SoundState.playing) SoundService.stop(ctx) else SoundService.start(ctx, type, minutes)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Reveal(0) {
            HeroCard { fg, sub, bg ->
                Text(if (playing) "Играет" else "Звуки для сна", fontSize = 13.sp, color = sub)
                Text(soundNames[type], fontSize = 26.sp, fontWeight = FontWeight.Bold, color = fg)
                Text(
                    if (playing) {
                        if (leftSec >= 0) "Осталось ${leftSec / 60} мин ${leftSec % 60} с" else "Без таймера"
                    } else soundDescs[type],
                    fontSize = 14.sp,
                    color = sub
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val sc = if (playing) pulse else 1f
                    Box(
                        Modifier.size(84.dp)
                            .graphicsLayer { scaleX = sc; scaleY = sc }
                            .clip(CircleShape)
                            .background(fg)
                            .clickable { toggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayIcon(playing, bg)
                    }
                }
            }
        }

        Text("Выберите звук", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        (0..3).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { i ->
                    SoundTile(soundNames[i], soundDescs[i], type == i, Modifier.weight(1f)) {
                        tick(view)
                        type = i
                        Prefs.putInt(ctx, "snd_type", i)
                        if (SoundState.playing) SoundService.start(ctx, i, minutes)
                    }
                }
            }
        }

        Card2 {
            Text("Таймер выключения", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120, 0).forEach { m ->
                    OptionChip(minutes == m, if (m == 0) "Без" else "$m") {
                        minutes = m
                        Prefs.putInt(ctx, "snd_min", m)
                        if (SoundState.playing) SoundService.start(ctx, type, m)
                    }
                }
            }
            Text(
                "Минуты. В конце звук плавно затихает. «Без» играет, пока вы не остановите.",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant
            )
        }

        Card2 {
            Text("Громкость", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    SoundState.volume = it
                },
                onValueChangeFinished = {
                    Prefs.sp(ctx).edit().putFloat("snd_vol", volume).apply()
                },
                valueRange = 0.05f..1f
            )
            Text(
                "Звук создаётся самим телефоном, интернет не нужен. Играет при выключенном экране, остановить можно здесь или в уведомлении. Громкость медиа телефона тоже влияет.",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant
            )
        }
    }
}
