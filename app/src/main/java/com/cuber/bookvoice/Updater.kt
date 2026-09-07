package com.cuber.bookvoice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Автообновление (задача #35). Спрашивает у GitHub последний релиз BookVoice
 * (репозиторий cuber454/bookvoice), сравнивает с установленной версией и, если
 * вышла новее, даёт ссылку на APK; качает его во внутреннее хранилище и
 * передаёт системному установщику.
 *
 * Где показывается — решают страницы: вручную из «Настроек» (кнопка
 * «Проверить обновление») и автоматически с полки, когда книга не читается.
 * Скачанный APK кладём в filesDir — FileProvider уже отдаёт эту папку наружу
 * (file_paths.xml), так что установщик получает наш файл через content://.
 */
object Updater {

    /** GitHub API: последний релиз репозитория. tag_name = "v0.3.90" и т.п. */
    private const val API_LATEST = "https://api.github.com/repos/cuber454/bookvoice/releases/latest"
    private const val UA = "Mozilla/5.0 (Linux; Android) BookVoice-Updater"
    private const val APK_FILE = "BookVoice-update.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Сведения о релизе: версия без "v" и прямая ссылка на APK-ассет. */
    data class Release(val version: String, val apkUrl: String)

    /** Текущая версия приложения (versionName), напр. "0.3.89". */
    fun currentVersion(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** "0.3.90" > "0.3.89"? Сравнение по числовым сегментам. */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Последний релиз с APK; null — нет сети, релиза или APK в нём. */
    fun latestRelease(): Release? = runCatching {
        val req = Request.Builder()
            .url(API_LATEST)
            .header("User-Agent", UA)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name", "").removePrefix("v")
            if (tag.isBlank()) return null
            val assets = obj.optJSONArray("assets") ?: return null
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "").endsWith(".apk")) {
                    apk = a.optString("browser_download_url", "")
                    break
                }
            }
            val url = apk?.takeIf { it.isNotBlank() } ?: return null
            Release(tag, url)
        }
    }.getOrNull()

    /** Скачивает APK в filesDir/[APK_FILE]. Прогресс — (готово, всего) байтов,
     *  всего может быть -1 (сервер размера не прислал). Возвращает файл или null. */
    fun downloadApk(c: Context, url: String, onProgress: (done: Long, total: Long) -> Unit): File? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val total = body.contentLength()
            val out = File(c.filesDir, APK_FILE)
            body.byteStream().use { input ->
                out.outputStream().use { fos ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            out
        }
    }.getOrNull()

    /** Разрешено ли приложению ставить APK из «своего» источника (Android 8+). */
    fun canInstallPackages(c: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Экран «Разрешить установку из этого источника» для нашего приложения. */
    fun openInstallSourceSettings(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val i = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${c.packageName}"),
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
            }
        }
    }

    /** Передать скачанный APK системному установщику. */
    fun launchInstaller(act: Activity): Boolean = runCatching {
        val f = File(act.filesDir, APK_FILE)
        if (!f.exists()) return false
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(i)
        true
    }.getOrDefault(false)
}
