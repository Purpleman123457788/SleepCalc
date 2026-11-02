package com.example.sleepcalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

val soundNames = listOf("Белый шум", "Розовый шум", "Коричневый шум", "Океан")
val soundDescs = listOf(
    "Ровный шум, как у радио без станции",
    "Мягкий, как дождь за окном",
    "Глубокий низкий гул",
    "Медленные накатывающие волны"
)

/** Состояние звука, которое видит экран. */
object SoundState {
    var playing by mutableStateOf(false)
    var type by mutableIntStateOf(1)
    var endAtMs by mutableLongStateOf(0L)

    @Volatile
    var volume = 0.7f
}

/** Генератор шума прямо на телефоне, без файлов и интернета. */
private class NoiseGen(private val type: Int, private val rate: Int) {
    private val rnd = java.util.Random()
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0
    private var brown = 0.0
    private var t = 0.0

    fun fill(out: ShortArray) {
        for (i in out.indices) {
            val white = rnd.nextDouble() * 2.0 - 1.0
            val v: Double = when (type) {
                0 -> white * 0.5
                1 -> {
                    b0 = 0.99886 * b0 + white * 0.0555179
                    b1 = 0.99332 * b1 + white * 0.0750759
                    b2 = 0.96900 * b2 + white * 0.1538520
                    b3 = 0.86650 * b3 + white * 0.3104856
                    b4 = 0.55000 * b4 + white * 0.5329522
                    b5 = -0.7616 * b5 - white * 0.0168980
                    val p = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
                    b6 = white * 0.115926
                    p
                }
                2 -> {
                    brown = (brown + 0.02 * white) / 1.02
                    brown * 3.5
                }
                else -> {
                    brown = (brown + 0.02 * white) / 1.02
                    t += 1.0 / rate
                    val swell = 0.55 + 0.45 * sin(2 * PI * t / 9.0)
                    brown * 3.5 * swell
                }
            }
            out[i] = (v.coerceIn(-1.0, 1.0) * 32767.0 * 0.8).toInt().toShort()
        }
    }
}

/**
 * Служба звуков для сна. Играет шум при выключенном экране,
 * сама выключается по таймеру с плавным затуханием.
 */
class SoundService : Service() {
    @Volatile
    private var running = false

    @Volatile
    private var gen = 0
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_STOP = "com.example.sleepcalc.SOUND_STOP"
        private const val CHANNEL = "sound"

        fun start(ctx: Context, type: Int, minutes: Int) {
            try {
                val i = Intent(ctx, SoundService::class.java)
                i.putExtra("type", type)
                i.putExtra("minutes", minutes)
                ContextCompat.startForegroundService(ctx, i)
                SoundState.type = type
            } catch (e: Exception) {
                Toast.makeText(ctx, "Не удалось запустить звук: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        fun stop(ctx: Context) {
            try {
                val i = Intent(ctx, SoundService::class.java)
                i.action = ACTION_STOP
                ctx.startService(i)
            } catch (e: Exception) {
                SoundState.playing = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val type = intent?.getIntExtra("type", 1) ?: 1
        val minutes = intent?.getIntExtra("minutes", 0) ?: 0
        try {
            showForeground(type, minutes)
        } catch (e: Exception) {
            // работаем без уведомления
        }
        try {
            begin(type, minutes)
        } catch (e: Exception) {
            shutdown()
        }
        return START_NOT_STICKY
    }

    private fun showForeground(type: Int, minutes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Звуки для сна", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, SoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = soundNames[type.coerceIn(0, 3)] + if (minutes > 0) ", таймер $minutes мин" else ""
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Звуки для сна")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1003, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1003, n)
        }
    }

    private fun begin(type: Int, minutes: Int) {
        // Останавливаем предыдущий звук, если был
        running = false
        try {
            worker?.join(1500)
        } catch (e: Exception) {
            // ждать больше не нужно
        }
        gen++
        val my = gen
        running = true

        val rate = 22050
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        releaseWake()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepcalc:sound").also {
            it.acquire(if (minutes > 0) minutes * 60_000L + 30_000L else 8 * 3_600_000L)
        }

        val endAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        SoundState.type = type
        SoundState.endAtMs = endAt
        SoundState.playing = true

        worker = Thread {
            val buf = ShortArray(2048)
            val noise = NoiseGen(type.coerceIn(0, 3), rate)
            var timeUp = false
            try {
                track.play()
                var fade = 1f
                while (running && my == gen) {
                    if (endAt > 0) {
                        val left = endAt - System.currentTimeMillis()
                        if (left <= 0) {
                            timeUp = true
                            break
                        }
                        if (left < 10_000) fade = left / 10_000f
                    }
                    noise.fill(buf)
                    track.setVolume(SoundState.volume * fade)
                    track.write(buf, 0, buf.size)
                }
            } catch (e: Exception) {
                // звук прервался
            } finally {
                try {
                    track.stop()
                } catch (e: Exception) {
                    // уже остановлен
                }
                track.release()
            }
            if (timeUp && my == gen) {
                Handler(Looper.getMainLooper()).post { if (my == gen) shutdown() }
            }
        }.also { it.start() }
    }

    private fun releaseWake() {
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // нечего освобождать
        }
        wake = null
    }

    @Suppress("DEPRECATION")
    private fun shutdown() {
        running = false
        gen++
        val t = worker
        worker = null
        try {
            t?.join(1500)
        } catch (e: Exception) {
            // ждать больше не нужно
        }
        SoundState.playing = false
        SoundState.endAtMs = 0L
        releaseWake()
        try {
            stopForeground(true)
        } catch (e: Exception) {
            // уведомления не было
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        SoundState.playing = false
        releaseWake()
        super.onDestroy()
    }
}
