package com.example.sleepcalc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch

private fun statusText(st: UpdateState): String = when (st) {
    is UpdateState.Idle -> ""
    is UpdateState.Checking -> "Проверяю…"
    is UpdateState.UpToDate -> "У вас последняя версия"
    is UpdateState.Available -> "Доступна новая версия: 1.${st.code}"
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
    val gms = remember { SleepSensing.available(ctx) }

    // Доступ «Статистика использования»: перепроверяем, когда возвращаемся в приложение
    var access by remember { mutableStateOf(AutoSleep.hasAccess(ctx)) }
    var resumes by remember { mutableIntStateOf(0) }

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askActivity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            SleepSensing.enable(ctx)
        } else {
            // Без разрешения работает умный разбор экрана, ему нужен автоанализ и доступ к статистике
            onChange(settings.copy(autoSleep = true))
            if (!access) AutoSleep.openAccessSettings(ctx)
        }
    }

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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 26.sp, color = cs.onBackground)
            }
            Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        // ---------- Оформление ----------
        SectionTitle("Оформление")
        Card2 {
            Text("Стиль", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Material (Google): на Android 12 и новее берёт цвета ваших обоев, на старых телефонах палитру Google.",
                color = cs.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(settings.style == 0, "Чёрно-белый") { onChange(settings.copy(style = 0)) }
                OptionChip(settings.style == 1, "Material (Google)") { onChange(settings.copy(style = 1)) }
            }
        }
        Card2 {
            Text("Тема", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Светлая или тёмная. «Системная» следует настройкам телефона.", color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(settings.theme == 0, "Системная") { onChange(settings.copy(theme = 0)) }
                OptionChip(settings.theme == 1, "Светлая") { onChange(settings.copy(theme = 1)) }
                OptionChip(settings.theme == 2, "Тёмная") { onChange(settings.copy(theme = 2)) }
            }
        }

        // ---------- Будильники ----------
        SectionTitle("Будильники")
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
            Text("Два будильника", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Как ставить основной и мягкий. Если на вашем телефоне ставится только один, выберите «По очереди».",
                color = cs.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(settings.alarmMode == 0, "Авто") { onChange(settings.copy(alarmMode = 0)) }
                OptionChip(settings.alarmMode == 1, "Быстро") { onChange(settings.copy(alarmMode = 1)) }
                OptionChip(settings.alarmMode == 2, "По очереди") { onChange(settings.copy(alarmMode = 2)) }
            }
        }

        // ---------- Сон ----------
        SectionTitle("Сон")
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
                "Повышенная точность сна",
                "Приложение само выбирает лучший способ для вашего телефона. С сервисами Google это датчики сна Google (движение и свет). Без них умный разбор экрана. Микрофон не используется, фоновых служб нет.",
                settings.preciseSleep
            ) { on ->
                if (on) {
                    // Без сервисов Google движком становится разбор экрана, ему нужно автоотслеживание
                    onChange(settings.copy(preciseSleep = true, autoSleep = if (gms) settings.autoSleep else true))
                    if (gms) {
                        if (SleepSensing.hasPermission(ctx)) {
                            SleepSensing.enable(ctx)
                        } else if (Build.VERSION.SDK_INT >= 29) {
                            askActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    } else if (!access) {
                        AutoSleep.openAccessSettings(ctx)
                    } else {
                        scope.launch {
                            val n = AutoSleep.importNow(ctx)
                            Toast.makeText(ctx, "Найдено ночей: $n", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    onChange(settings.copy(preciseSleep = false))
                    SleepSensing.disable(ctx)
                }
            }
            if (settings.preciseSleep) {
                val engine = when {
                    SleepSensing.googleActive(ctx) ->
                        "Способ: датчики сна Google плюс разбор экрана. Ночь появится во вкладке «Сон» через несколько минут после пробуждения."
                    gms ->
                        "Сервисы Google есть, но нет разрешения «Физическая активность». Пока работает умный разбор экрана. Чтобы подключить датчики Google, разрешите активность в настройках приложения."
                    else ->
                        "Способ: умный разбор экрана (сервисов Google на телефоне нет). Учитываются разблокировки и длина включений, короткие взгляды на уведомления не считаются пробуждением. Нужен доступ «Статистика использования»."
                }
                Text(engine, color = cs.onSurfaceVariant)
                if (!gms && !access) {
                    Button(
                        onClick = { AutoSleep.openAccessSettings(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Выдать доступ") }
                }
            }
        }

        // ---------- Приложение ----------
        SectionTitle("Приложение")
        Card2 {
            SwitchRow(
                "Вибрация",
                "Отклик при переключении вкладок и выборе времени",
                settings.haptics
            ) { onChange(settings.copy(haptics = it)) }
            if (settings.haptics) {
                Text(
                    "Если при выборе времени нет вибрации, выберите «Усиленная».",
                    color = cs.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip(settings.hapticMode == 0, "Авто") { onChange(settings.copy(hapticMode = 0)) }
                    OptionChip(settings.hapticMode == 1, "Системная") { onChange(settings.copy(hapticMode = 1)) }
                    OptionChip(settings.hapticMode == 2, "Усиленная") {
                        onChange(settings.copy(hapticMode = 2))
                        buzz(ctx, 18)
                    }
                }
            }
        }
        Card2 {
            SwitchRow(
                "Автообновление",
                "Скачивать и устанавливать новые версии при запуске",
                settings.autoUpdate
            ) { onChange(settings.copy(autoUpdate = it)) }
            SwitchRow(
                "Уведомления об обновлениях",
                "Раз в 12 часов тихо проверяет новую версию и присылает уведомление",
                settings.notifyUpdates
            ) { on ->
                onChange(settings.copy(notifyUpdates = on))
                UpdateScheduler.apply(ctx, on)
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Text(
                "Если уведомления не приходят (Huawei, Honor, Xiaomi, Oppo, Vivo), разрешите SleepCalc автозапуск и работу в фоне в настройках батареи.",
                fontSize = 13.sp,
                color = cs.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    try {
                        ctx.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Откройте настройки приложения вручную", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Настройки приложения") }
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
