package com.example.sleepcalc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object UpdateConfig {
    /** Ник на GitHub и название репозитория, из которого берутся релизы. */
    const val OWNER = "Purpleman123457788"
    const val REPO = "SleepCalc"
}

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val code: Int, val name: String, val url: String) : UpdateState()
    data class Downloading(val percent: Int) : UpdateState()
    data class Ready(val file: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object Updater {
    var state: UpdateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    @Suppress("DEPRECATION")
    fun versionCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun versionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** Удаляет скачанные ранее файлы обновлений. */
    fun cleanup(ctx: Context) {
        File(ctx.cacheDir, "updates").deleteRecursively()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SleepCalc")
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Запрашивает последний релиз. Возвращает Available, если он новее установленной версии, иначе null. */
    suspend fun fetchLatest(ctx: Context): UpdateState.Available? {
        val json = withContext(Dispatchers.IO) {
            httpGet("https://api.github.com/repos/${UpdateConfig.OWNER}/${UpdateConfig.REPO}/releases/latest")
        }
        val obj = JSONObject(json)
        val tag = obj.getString("tag_name")
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
        val assets = obj.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                url = a.getString("browser_download_url")
                break
            }
        }
        if (url.isEmpty() || code <= versionCode(ctx)) return null
        return UpdateState.Available(code, obj.optString("name", tag), url)
    }

    /** Ищет последний релиз. При autoInstall сразу скачивает и запускает установку. */
    suspend fun checkNow(ctx: Context, autoInstall: Boolean) {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        state = UpdateState.Checking
        try {
            val av = fetchLatest(ctx)
            if (av == null) {
                state = UpdateState.UpToDate
                return
            }
            state = av
            if (autoInstall) download(ctx)
        } catch (e: Exception) {
            state = UpdateState.Error("Не удалось проверить: ${e.message}. Проверьте интернет и что в репозитории есть релиз.")
        }
    }

    /** Скачивает APK из релиза и открывает установщик. */
    suspend fun download(ctx: Context) {
        val av = state as? UpdateState.Available ?: return
        state = UpdateState.Downloading(0)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates")
                dir.deleteRecursively()
                dir.mkdirs()
                val out = File(dir, "SleepCalc-${av.code}.apk")
                val c = URL(av.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "SleepCalc")
                c.connect()
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var last = -1
                        var n = 0
                        while (input.read(buf).also { n = it } >= 0) {
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    state = UpdateState.Downloading(p)
                                }
                            }
                        }
                    }
                }
                out
            }
            state = UpdateState.Ready(file)
            install(ctx, file)
        } catch (e: Exception) {
            state = UpdateState.Error("Не удалось скачать: ${e.message}")
        }
    }

    /** Запускает системный установщик. Если нет разрешения, открывает нужную настройку. */
    fun install(ctx: Context, file: File) {
        try {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    ctx,
                    "Разрешите установку из этого приложения, затем вернитесь и нажмите «Установить»",
                    Toast.LENGTH_LONG
                ).show()
                val i = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось открыть установщик: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
