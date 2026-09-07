package com.cuber.bookvoice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лёгкий файловый лог диагностики медиа/аудиофокуса. Пишет построчно в
 * `filesDir/diag.log`, свежие строки — в конец. Файл держим небольшим
 * (хвост ~2500 строк): если разросся — обрезаем при очередной записи.
 *
 * Зачем: разобраться раз и навсегда, кто получает медиа-кнопки гарнитуры и
 * «волшебное касание» TalkBack — наше приложение или чужой плеер. Лог виден
 * слепому пользователю не нужен: файл отправляется в чат кнопкой в
 * Настройках → «Отправить лог в Telegram».
 */
object Diag {
    private const val MAX_BYTES = 250_000L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null

    private fun file(c: Context): File {
        var d = dir
        if (d == null) {
            d = c.filesDir
            dir = d
        }
        return File(d, "diag.log")
    }

    @Synchronized
    fun log(c: Context, tag: String, msg: String) {
        try {
            val f = file(c)
            val line = "[${fmt.format(Date())}] $tag: $msg\n"
            FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            if (f.length() > MAX_BYTES) trim(f)
        } catch (_: Exception) {
        }
    }

    /** Строка-заголовок нового запуска приложения — по ней в логе видно границу сеанса. */
    fun header(c: Context) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val ver = runCatching {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        log(
            c, "app",
            "=== BookVoice v$ver $day, устройство ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) ===",
        )
    }

    fun clear(c: Context) {
        file(c).delete()
    }

    fun fileUri(c: Context): File = file(c)

    private fun trim(f: File) {
        val txt = f.readText(Charsets.UTF_8)
        val lines = txt.lineSequence().filter { it.isNotBlank() }.toMutableList()
        if (lines.size <= 2500) return
        val keep = lines.takeLast(2500).joinToString("\n") + "\n"
        f.writeText(keep, Charsets.UTF_8)
    }
}
