package com.example.sleepcalc

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch

private fun statusText(st: UpdateState): String = when (st) {
    is UpdateState.Idle -> ""
    is UpdateState.Checking -> "Проверяю…"
    is UpdateState.UpToDate -> "У вас последняя версия"
    is UpdateState.Available -> "Доступна новая версия: ${st.name}"
    is UpdateState.Downloading -> "Скачивание: ${st.percent}%"
    is UpdateState.Ready -> "Скачано, осталось установить"
    is UpdateState.Error -> st.message
}

@Composable
fun SettingsScreen(settings: Settings, onChange: (Settings) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val st = Updater.state

    // Доступ «Статистика использования»: перепроверяем, когда возвращаемся в приложение
    var access by remember { mutableStateOf(AutoSleep.hasAccess(ctx)) }
    var resumes by remember { mutableIntStateOf(0) }
    val owner = ctx as? LifecycleOwner
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumes++
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }
    LaunchedEffect(resumes) {
        access = AutoSleep.hasAccess(ctx)
        if (settings.autoSleep && access) {
            val n = AutoSleep.importNow(ctx)
            if (n > 0) Toast.makeText(ctx, "Найдено ночей: $n", Toast.LENGTH_SHORT).show()
        }
    }

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
            Text("Норма сна", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Сколько часов сна вам нужно. От этого зависит дефицит и прогноз.", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(420 to "7", 450 to "7.5", 480 to "8", 540 to "9").forEach { (min, label) ->
                    OptionChip(settings.goalMin == min, label) { onChange(settings.copy(goalMin = min)) }
                }
            }
        }

        Card2 {
            SwitchRow(
                "Автоотслеживание сна",
                "Находит ночной сон по времени выключения экрана. Без фоновой работы, батарею не тратит.",
                settings.autoSleep
            ) { on ->
                onChange(settings.copy(autoSleep = on))
                if (on) {
                    if (access) {
                        scope.launch {
                            val n = AutoSleep.importNow(ctx)
                            Toast.makeText(ctx, "Найдено ночей: $n", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        AutoSleep.openAccessSettings(ctx)
                    }
                }
            }
            if (settings.autoSleep) {
                if (!access) {
                    Text(
                        "Нужен доступ «Статистика использования». Найдите в списке SleepCalc и включите.",
                        color = cs.onSurfaceVariant
                    )
                    Button(
                        onClick = { AutoSleep.openAccessSettings(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Выдать доступ") }
                } else {
                    Text(
                        "Доступ выдан. Ночи добавляются при открытии приложения (за последние 7 дней). Отбой считается по времени, когда погас экран.",
                        color = cs.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val n = AutoSleep.importNow(ctx)
                                Toast.makeText(ctx, "Найдено ночей: $n", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Обновить сейчас") }
                }
            }
        }

        Card2 {
            SwitchRow(
                "Вибрация",
                "Отклик при переключении вкладок и выборе времени",
                settings.haptics
            ) { onChange(settings.copy(haptics = it)) }
        }

        Card2 {
            SwitchRow(
                "Автообновление",
                "Проверять и устанавливать новые версии при запуске",
                settings.autoUpdate
            ) { onChange(settings.copy(autoUpdate = it)) }
            Text(
                "Версия ${Updater.versionName(ctx)} (сборка ${Updater.versionCode(ctx)})",
                color = cs.onSurfaceVariant
            )
            val status = statusText(st)
            if (status.isNotEmpty()) Text(status)
            when (st) {
                is UpdateState.Downloading -> LinearProgressIndicator(
                    progress = { st.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                is UpdateState.Available -> Button(
                    onClick = { scope.launch { Updater.download(ctx) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Скачать и установить") }
                is UpdateState.Ready -> Button(
                    onClick = { Updater.install(ctx, st.file) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Установить") }
                else -> {}
            }
            OutlinedButton(
                onClick = { scope.launch { Updater.checkNow(ctx, false) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Проверить обновление") }
        }
    }
}
