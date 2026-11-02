package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Кольцо с оценкой последней ночи и недельная сводка (тёмная градиентная карточка). */
@Composable
fun ScoreCard(nights: List<Night>, goalMin: Int) {
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

    HeroCard { fg, sub, _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = 10.dp.toPx()
                    val tl = Offset(st / 2, st / 2)
                    val sz = Size(size.width - st, size.height - st)
                    drawArc(
                        fg.copy(alpha = 0.18f), -90f, 360f, false,
                        topLeft = tl, size = sz, style = Stroke(width = st, cap = StrokeCap.Round)
                    )
                    drawArc(
                        fg, -90f, sweep, false,
                        topLeft = tl, size = sz, style = Stroke(width = st, cap = StrokeCap.Round)
                    )
                }
                Text(
                    if (last != null) "$score" else "—",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Оценка последней ночи", fontSize = 12.sp, color = sub)
                Text(
                    if (last != null) SleepStats.verdict(score) else "Пока нет данных",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = fg
                )
                if (last != null) {
                    Text(
                        "${fmtDur(last.minutes)} · ${last.bedTime.s()} → ${last.wakeTime.s()}",
                        fontSize = 13.sp,
                        color = sub
                    )
                }
                if (w.count > 0) Text("За неделю: ${w.avgScore} из 100", fontSize = 13.sp, color = fg)
            }
        }
        if (w.count > 0) {
            Spacer(Modifier.height(6.dp))
            Row {
                HeroStat("Средний сон", fmtDur(w.avgMin), fg, sub, Modifier.weight(1f))
                HeroStat("Долг сна", if (w.debt > 0) fmtDur(w.debt) else "нет", fg, sub, Modifier.weight(1f))
                HeroStat("Серия", "${w.streak} дн.", fg, sub, Modifier.weight(1f))
            }
        }
        if (w.count >= 2) {
            Text(
                "Стабильность режима: ±${w.regularity} мин · средний отбой ${w.avgBed?.s() ?: "--:--"}",
                fontSize = 12.sp,
                color = sub
            )
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, fg: Color, sub: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = sub)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Оценка ночи звёздами и отметки о вечере. Показывается только для ночи за последние 36 часов. */
@Composable
fun RateCard(night: Night, onSave: (Int, Int) -> Unit) {
    if (SleepLog.nowMin() - night.wake > 36 * 60) return
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

/** Один пункт достижений. */
private data class Ach(val title: String, val progress: Float, val note: String)

/** Отправляет все записи таблицей (CSV) в любое приложение. */
private fun exportCsv(ctx: Context, nights: List<Night>) {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val sb = StringBuilder("дата;отбой;подъём;минут;оценка;отметки\n")
    nights.forEach { n ->
        val tags = SleepStats.TAGS.filterIndexed { i, _ -> (n.tags and (1 shl i)) != 0 }.joinToString(",")
        sb.append("${n.wakeDate.format(fmt)};${n.bedTime.s()};${n.wakeTime.s()};${n.minutes};${n.rating};$tags\n")
    }
    val i = Intent(Intent.ACTION_SEND)
    i.type = "text/plain"
    i.putExtra(Intent.EXTRA_SUBJECT, "Мой сон (SleepCalc)")
    i.putExtra(Intent.EXTRA_TEXT, sb.toString())
    try {
        ctx.startActivity(Intent.createChooser(i, "Экспорт записей"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось открыть список приложений", Toast.LENGTH_SHORT).show()
    }
}

/** Умные выводы, достижения и экспорт. */
@Composable
fun InsightsCard(nights: List<Night>, goalMin: Int) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val list = remember(nights, goalMin) { SleepStats.insights(nights, goalMin) }
    val w = remember(nights, goalMin) { SleepStats.week(nights, goalMin) }
    val best = remember(nights, goalMin) {
        nights.maxOfOrNull { n -> SleepStats.score(n, goalMin, emptyList()) } ?: 0
    }
    val steady = w.count >= 5 && w.regularity <= 30
    val noDebt = w.count >= 5 && w.debt <= 30
    val ach = listOf(
        Ach("Серия 3 ночи в норме", (w.streak / 3f).coerceAtMost(1f), "${minOf(w.streak, 3)} из 3"),
        Ach("Серия 7 ночей в норме", (w.streak / 7f).coerceAtMost(1f), "${minOf(w.streak, 7)} из 7"),
        Ach(
            "Стабильный режим",
            if (steady) 1f else (w.count / 5f).coerceAtMost(0.9f),
            if (steady) "готово" else "5 ночей с отбоем ±30 мин"
        ),
        Ach(
            "Долг сна закрыт",
            if (noDebt) 1f else (w.count / 5f).coerceAtMost(0.5f),
            if (noDebt) "готово" else "долг за неделю до 30 мин"
        ),
        Ach("30 ночей в журнале", (nights.size / 30f).coerceAtMost(1f), "${minOf(nights.size, 30)} из 30"),
        Ach("Отличная ночь", (best / 85f).coerceAtMost(1f), if (best >= 85) "готово" else "лучшая: $best из 85")
    )

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

    Card2 {
        Text("Достижения", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        ach.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(
                        a.title,
                        Modifier.weight(1f),
                        fontSize = 14.sp,
                        fontWeight = if (a.progress >= 1f) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        if (a.progress >= 1f) "✓ ${a.note}" else a.note,
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { a.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }

    OutlinedButton(
        onClick = { exportCsv(ctx, nights) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) { Text("Экспорт записей (таблица)") }
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
