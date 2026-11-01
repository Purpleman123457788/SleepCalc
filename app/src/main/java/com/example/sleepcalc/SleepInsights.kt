package com.example.sleepcalc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Кольцо с оценкой последней ночи и недельная сводка. */
@Composable
fun ScoreCard(nights: List<Night>, goalMin: Int) {
    val cs = MaterialTheme.colorScheme
    val last = nights.lastOrNull()
    val w = remember(nights, goalMin) { SleepStats.week(nights, goalMin) }
    val score = if (last != null) {
        SleepStats.score(last, goalMin, nights.filter { it != last }.takeLast(6))
    } else 0
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val sweep by animateFloatAsState(
        targetValue = if (go) score * 3.6f else 0f,
        animationSpec = tween(900),
        label = "score"
    )
    val track = cs.outlineVariant
    val ring = cs.primary

    Card2 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 10.dp.toPx()
                    val tl = Offset(st / 2, st / 2)
                    val sz = Size(size.width - st, size.height - st)
                    drawArc(track, -90f, 360f, false, topLeft = tl, size = sz, style = Stroke(width = st, cap = StrokeCap.Round))
                    drawArc(ring, -90f, sweep, false, topLeft = tl, size = sz, style = Stroke(width = st, cap = StrokeCap.Round))
                }
                Text(
                    if (last != null) "$score" else "—",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Оценка последней ночи", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Text(
                    if (last != null) SleepStats.verdict(score) else "Пока нет данных",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                if (last != null) {
                    Text(
                        "${fmtDur(last.minutes)} · ${last.bedTime.s()} → ${last.wakeTime.s()}",
                        fontSize = 13.sp,
                        color = cs.onSurfaceVariant
                    )
                }
                if (w.count > 0) Text("За неделю: ${w.avgScore} из 100", fontSize = 13.sp)
            }
        }
        if (w.count > 0) {
            Row {
                Stat("Средний сон", fmtDur(w.avgMin), Modifier.weight(1f))
                Stat("Долг сна", if (w.debt > 0) fmtDur(w.debt) else "нет", Modifier.weight(1f))
                Stat("Серия", "${w.streak} дн.", Modifier.weight(1f))
            }
        }
        if (w.count >= 2) {
            Text(
                "Стабильность режима: ±${w.regularity} мин · средний отбой ${w.avgBed?.s() ?: "--:--"}",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/** Оценка ночи звёздами и отметки о вечере. */
@Composable
fun RateCard(night: Night, onSave: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    var rating by remember(night.bed, night.wake) { mutableIntStateOf(night.rating) }
    var tags by remember(night.bed, night.wake) { mutableIntStateOf(night.tags) }

    Card2 {
        Text("Как вы спали?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Ночь ${night.bedTime.s()} → ${night.wakeTime.s()}. Оценка и отметки помогают найти, что влияет на ваш сон.",
            fontSize = 13.sp,
            color = cs.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (i in 1..5) {
                Text(
                    if (i <= rating) "★" else "☆",
                    fontSize = 34.sp,
                    color = if (i <= rating) cs.primary else cs.outline,
                    modifier = Modifier.clip(CircleShape)
                        .clickable {
                            tick(view)
                            rating = i
                            onSave(rating, tags)
                        }
                        .padding(horizontal = 6.dp)
                )
            }
        }
        Text("Что было вечером:", fontSize = 13.sp, color = cs.onSurfaceVariant)
        SleepStats.TAGS.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, name ->
                    val bit = 1 shl (r * 3 + c)
                    OptionChip((tags and bit) != 0, name) {
                        tags = tags xor bit
                        onSave(rating, tags)
                    }
                }
            }
        }
    }
}

/** Умные выводы по записям. */
@Composable
fun InsightsCard(nights: List<Night>, goalMin: Int) {
    val cs = MaterialTheme.colorScheme
    val list = remember(nights, goalMin) { SleepStats.insights(nights, goalMin) }
    Card2 {
        Text("Умные выводы", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (list.isEmpty()) {
            Text("Нужно хотя бы 3 ночи, чтобы появились выводы.", color = cs.onSurfaceVariant)
        } else {
            list.forEach { s ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", Modifier.width(16.dp))
                    Text(s, Modifier.weight(1f), fontSize = 14.sp)
                }
            }
        }
    }
}

/** Календарь сна за 5 недель: чем ярче клетка, тем ближе сон к норме. */
@Composable
fun HeatmapCard(nights: List<Night>, goalMin: Int) {
    val cs = MaterialTheme.colorScheme
    val byDate = remember(nights) { SleepStats.minutesByDate(nights) }
    val today = LocalDate.now()
    val start = today.minusDays(((today.dayOfWeek.value - 1) + 28).toLong())

    Card2 {
        Text("Календарь сна", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс").forEach {
                Text(
                    it,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0 until 7) {
                    val date = start.plusDays((row * 7 + d).toLong())
                    val mins = byDate[date]
                    val future = date.isAfter(today)
                    val alpha = if (mins == null) 0f
                    else 0.25f + 0.75f * ((mins.toFloat() / goalMin - 0.5f) / 0.6f).coerceIn(0f, 1f)
                    val fill = when {
                        future -> Color.Transparent
                        mins == null -> cs.outlineVariant.copy(alpha = 0.35f)
                        else -> cs.primary.copy(alpha = alpha)
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(fill),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!future) {
                            Text(
                                "${date.dayOfMonth}",
                                fontSize = 10.sp,
                                color = if (mins != null && alpha > 0.6f) cs.onPrimary else cs.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Чем ярче клетка, тем ближе сон к вашей норме. Серые дни без записи.",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant
        )
    }
}
