package com.example.sleepcalc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

@Composable
fun ForecastScreen(settings: Settings) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var bedMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_bed", 23 * 60)) }
    var wakeMin by remember { mutableIntStateOf(Prefs.getInt(ctx, "fc_wake", 7 * 60)) }
    val history = remember { SleepLog.load(ctx) }
    val last = remember { history.lastOrNull() }

    // Расчёт идёт сразу при изменении времени или нормы сна
    val f = remember(bedMin, wakeMin, settings.goalMin) {
        SleepMath.forecast(
            LocalTime.ofSecondOfDay(bedMin * 60L),
            LocalTime.ofSecondOfDay(wakeMin * 60L),
            settings.goalMin,
            history
        )
    }
    val nowOff = (LocalTime.now().toSecondOfDay() / 60 - wakeMin + 1440) % 1440
    val curIdx = f.phases.indexOfLast { it.offset <= nowOff }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TimeField("Лёг спать (накануне)", bedMin, icon = 0) { bedMin = it; Prefs.putInt(ctx, "fc_bed", it) }
        TimeField("Проснулся (сегодня)", wakeMin, icon = 1) { wakeMin = it; Prefs.putInt(ctx, "fc_wake", it) }

        if (last != null) {
            OutlinedButton(
                onClick = {
                    bedMin = last.bedTime.toSecondOfDay() / 60
                    wakeMin = last.wakeTime.toSecondOfDay() / 60
                    Prefs.putInt(ctx, "fc_bed", bedMin)
                    Prefs.putInt(ctx, "fc_wake", wakeMin)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Взять из записи сна: ${last.bedTime.s()} → ${last.wakeTime.s()}") }
        }

        Reveal(0) {
            Card2 {
                Text("Итоги сна", color = cs.onSurfaceVariant)
                Row {
                    Stat("Сон", fmtDur(f.sleptMin), Modifier.weight(1f))
                    Stat("Циклов", "%.1f".format(f.cycles), Modifier.weight(1f))
                    Stat("Дефицит", if (f.deficit > 0) fmtDur(f.deficit) else "нет", Modifier.weight(1f))
                }
                Text("Бодрствовать до сна: ${fmtDur(f.windowMin)}", color = cs.onSurfaceVariant)
                Text(
                    if (f.anchorNights > 0) "Учтён ваш ритм по ${f.anchorNights} прошлым ночам."
                    else "Ритм оценён по этой ночи. Чем больше ночей в журнале, тем точнее прогноз.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Reveal(1) {
            Card2 {
                Text("Захочется спать примерно в", color = cs.onSurfaceVariant)
                Text(f.sleepy.s(), fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Row {
                    Stat("Начнёт клонить", f.early.s(), Modifier.weight(1f))
                    Stat("Скорее всего", f.sleepy.s(), Modifier.weight(1f))
                    Stat("Трудно не уснуть", f.late.s(), Modifier.weight(1f))
                }
                Text(
                    "Раньше или позже зависит от света, кофеина, нагрузки и настроения. Это оценка по модели, а не измерение.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Reveal(2) {
            Card2 {
                Text("Сонливость в течение дня", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                SleepCurve(f, wakeMin)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0, 360, 720, 1080, 1440).forEach { off ->
                        Text(
                            LocalTime.ofSecondOfDay(((wakeMin + off) % 1440) * 60L).s(),
                            fontSize = 11.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "Линия выше пунктира: хочется спать. Широкая полоса: раньше и позже. Яркая черта: вероятнее всего. Пунктир слева: сейчас.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant
                )
            }
        }

        Text("Ваш день по часам", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        f.phases.forEachIndexed { i, p -> PhaseCard(i, p, i == curIdx && nowOff <= f.sleepyM + 120) }

        Reveal(10) {
            Card2 {
                Text("Рекомендация", color = cs.onSurfaceVariant)
                val soft = f.alarm.minusMinutes(settings.softMin.toLong())
                val alarms = if (settings.softMin > 0)
                    "Будильники: ${soft.s()} и ${f.alarm.s()}."
                else
                    "Будильник: ${f.alarm.s()}."
                Text(
                    "Если лечь в ${f.sleepy.s()}, 5 циклов сна (7.5 ч) закончатся около ${f.alarm.s()}. $alarms",
                    fontSize = 16.sp
                )
                Button(onClick = { AlarmHelper.setAlarms(ctx, f.alarm, "Подъём", settings.softMin) }) {
                    Text(if (settings.softMin > 0) "Поставить 2 будильника" else "Поставить будильник")
                }
            }
        }
    }
}

/** График сонливости: площадь под кривой, порог, полоса «раньше–позже», время сна и «сейчас». */
@Composable
fun SleepCurve(f: Forecast, wakeMin: Int) {
    val cs = MaterialTheme.colorScheme
    val line = cs.primary
    val grid = cs.outlineVariant
    val soft = cs.onSurfaceVariant
    val nowOff = (LocalTime.now().toSecondOfDay() / 60 - wakeMin + 1440) % 1440

    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val w = size.width
        val h = size.height
        val maxV = 1.25f
        fun px(m: Float): Float = w * m / 1440f
        fun py(v: Float): Float = h - (v.coerceIn(0f, maxV) / maxV) * h

        // сетка каждые 6 часов
        for (k in 1..3) {
            val x = px(k * 360f)
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
        }
        // полоса «раньше–позже»
        val ex = px(f.earlyM.toFloat())
        val lx = px(f.lateM.toFloat())
        drawRect(line.copy(alpha = 0.12f), Offset(ex, 0f), Size(lx - ex, h))
        // порог сна
        drawLine(
            soft.copy(alpha = 0.6f),
            Offset(0f, py(1f)),
            Offset(w, py(1f)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        )
        // кривая и заливка
        val path = Path()
        val fill = Path()
        fill.moveTo(0f, h)
        f.curve.forEachIndexed { i, v ->
            val x = px(i * 30f)
            val y = py(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(w, h)
        fill.close()
        drawPath(fill, line.copy(alpha = 0.14f))
        drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // вероятное время сна
        val sx = px(f.sleepyM.toFloat())
        drawLine(line, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2.dp.toPx())
        drawCircle(line, 5.dp.toPx(), Offset(sx, py(1f)))
        // сейчас
        val nx = px(nowOff.toFloat())
        drawLine(
            soft,
            Offset(nx, 0f),
            Offset(nx, h),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
    }
}

/** Иконки фаз дня: 0 восход, 1 молния, 2 мишень, 3 кофе, 4 рост, 5 луна, 6 кровать. */
@Composable
fun PhaseIcon(kind: Int, color: Color) {
    when (kind) {
        5 -> TabIcon(0, color)
        6 -> SegIcon(0, color)
        else -> Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val sw = w * 0.09f
            when (kind) {
                0 -> {
                    drawLine(color, Offset(w * 0.08f, w * 0.74f), Offset(w * 0.92f, w * 0.74f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawArc(
                        color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(w * 0.27f, w * 0.38f), size = Size(w * 0.46f, w * 0.46f),
                        style = Stroke(width = sw, cap = StrokeCap.Round)
                    )
                    drawLine(color, Offset(w * 0.5f, w * 0.1f), Offset(w * 0.5f, w * 0.24f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(color, Offset(w * 0.16f, w * 0.36f), Offset(w * 0.25f, w * 0.44f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(color, Offset(w * 0.84f, w * 0.36f), Offset(w * 0.75f, w * 0.44f), strokeWidth = sw, cap = StrokeCap.Round)
                }
                1 -> {
                    val p = Path().apply {
                        moveTo(w * 0.58f, w * 0.06f)
                        lineTo(w * 0.26f, w * 0.54f)
                        lineTo(w * 0.48f, w * 0.54f)
                        lineTo(w * 0.40f, w * 0.94f)
                        lineTo(w * 0.76f, w * 0.42f)
                        lineTo(w * 0.54f, w * 0.42f)
                        close()
                    }
                    drawPath(p, color)
                }
                2 -> {
                    val c = Offset(w / 2, w / 2)
                    drawCircle(color, radius = w * 0.42f, center = c, style = Stroke(width = sw))
                    drawCircle(color, radius = w * 0.22f, center = c, style = Stroke(width = sw))
                    drawCircle(color, radius = w * 0.07f, center = c)
                }
                3 -> {
                    drawRoundRect(color, Offset(w * 0.12f, w * 0.4f), Size(w * 0.5f, w * 0.44f), CornerRadius(w * 0.1f))
                    drawArc(
                        color, startAngle = -90f, sweepAngle = 180f, useCenter = false,
                        topLeft = Offset(w * 0.5f, w * 0.48f), size = Size(w * 0.34f, w * 0.26f),
                        style = Stroke(width = sw)
                    )
                    drawLine(color, Offset(w * 0.26f, w * 0.12f), Offset(w * 0.26f, w * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
                    drawLine(color, Offset(w * 0.46f, w * 0.12f), Offset(w * 0.46f, w * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
                }
                else -> {
                    val trend = Path().apply {
                        moveTo(w * 0.1f, w * 0.74f)
                        lineTo(w * 0.38f, w * 0.46f)
                        lineTo(w * 0.56f, w * 0.62f)
                        lineTo(w * 0.88f, w * 0.28f)
                    }
                    val arrow = Path().apply {
                        moveTo(w * 0.66f, w * 0.26f)
                        lineTo(w * 0.9f, w * 0.26f)
                        lineTo(w * 0.9f, w * 0.5f)
                    }
                    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(trend, color, style = st)
                    drawPath(arrow, color, style = st)
                }
            }
        }
    }
}

/** Карточка фазы дня: иконка в круге, название и время в одной строке, ниже объяснение и совет. */
@Composable
fun PhaseCard(index: Int, p: Phase, current: Boolean) {
    val cs = MaterialTheme.colorScheme
    Reveal(index + 3) {
        Card2 {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (current) cs.primary else cs.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    PhaseIcon(p.kind, if (current) cs.onPrimary else cs.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            p.title,
                            modifier = Modifier.weight(1f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(p.time.s(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (current) {
                                Text("сейчас", fontSize = 11.sp, color = cs.primary, fontWeight = FontWeight.Bold)
                            } else if (p.nextDay) {
                                Text("завтра", fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    Text(p.info, fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Text(p.tip, fontSize = 13.sp)
                }
            }
        }
    }
}
