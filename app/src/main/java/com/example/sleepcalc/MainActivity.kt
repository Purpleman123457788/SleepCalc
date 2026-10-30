package com.example.sleepcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var settings by remember { mutableStateOf(Settings.load(ctx)) }
            hapticsOn = settings.haptics
            // Отключаем «резиновый» эффект на краях списка
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                SleepTheme(settings.theme) {
                    SleepApp(settings) { new ->
                        settings = new
                        new.save(ctx)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SleepApp(settings: Settings, onSettingsChange: (Settings) -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    // При запуске: автозапись сна (если включена), чистка старых загрузок, поиск обновления
    LaunchedEffect(Unit) {
        Updater.cleanup(ctx)
        if (settings.autoSleep) AutoSleep.importNow(ctx)
        if (settings.autoUpdate) Updater.checkNow(ctx, true)
    }

    val titles = listOf("Циклы сна", "Когда захочется спать", "Мой сон")

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (!showSettings) BottomTabs(tab) { tab = it } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(settings, onSettingsChange) { showSettings = false }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        titles[tab],
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
                UpdateBanner()
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val dir = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(tween(320)) { w -> w / 5 * dir } + fadeIn(tween(320))) togetherWith
                                (slideOutHorizontally(tween(220)) { w -> -w / 5 * dir } + fadeOut(tween(180)))
                        },
                        label = "screen"
                    ) { t ->
                        when (t) {
                            0 -> CyclesScreen(settings)
                            1 -> ForecastScreen(settings)
                            else -> SleepScreen(settings)
                        }
                    }
                }
            }
        }
    }
}

/** Плашка сверху: прогресс скачивания обновления или кнопка «Установить». */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    when (val st = Updater.state) {
        is UpdateState.Downloading -> {
            Box(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                Card2 {
                    Text("Скачиваю обновление: ${st.percent}%")
                    LinearProgressIndicator(
                        progress = { st.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        is UpdateState.Ready -> {
            Box(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp)) {
                Card2 {
                    Text("Обновление скачано")
                    Button(onClick = { Updater.install(ctx, st.file) }) { Text("Установить") }
                }
            }
        }
        else -> {}
    }
}
