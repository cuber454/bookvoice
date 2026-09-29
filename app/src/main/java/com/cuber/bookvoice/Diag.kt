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
    private const val MAX_BYTES = 1_200_000L

    /** Сколько последних строк оставляем при обрезке и сколько строк файла держим.
     *  29.09.2026: было 2500 строк (250 КБ) — это примерно двадцать минут чтения, и
     *  журнал за один длинный прогон теста успевал потерять начало: жалобу Сержа
     *  «затормозил на маленькой скорости» пришлось разбирать по чужим следам.
     *  Теперь держим 8000 строк: одного прогона теста хватает целиком. */
    private const val KEEP_LINES = 8000
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
        val d = dir
        if (d == null) {
            dir = c.filesDir
        }
        write(File(dir, "diag.log"), tag, msg)
    }

    /** Строка в журнал БЕЗ контекста: папку журнала помнит первый вызов [log].
     *  Нужна разбору книги ([PdfParser]): он контекста не знает, а написать о
     *  выброшенном мусоре обязан — иначе это гадание, а не проверка. Журнал ещё
     *  не открывали (папка неизвестна) — молчим. */
    @Synchronized
    fun log(tag: String, msg: String) {
        val d = dir ?: return
        write(File(d, "diag.log"), tag, msg)
    }

    private fun write(f: File, tag: String, msg: String) {
        try {
            val line = "[${fmt.format(Date())}] $tag: $msg\n"
            FileOutputStream(f, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            if (f.length() > MAX_BYTES) trim(f)
        } catch (_: Exception) {
        }
    }

    /** Строка-заголовок нового запуска приложения — по ней в логе видно границу сеанса. */
    fun header(c: Context) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val info = runCatching {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            (pi.versionName ?: "?") to code
        }.getOrDefault("?" to 0L)
        log(
            c, "app",
            "=== BookVoice v${info.first} сборка ${info.second} $day, " +
                "устройство ${android.os.Build.MODEL}, " +
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
        if (lines.size <= KEEP_LINES) return
        val keep = lines.takeLast(KEEP_LINES).joinToString("\n") + "\n"
        f.writeText(keep, Charsets.UTF_8)
    }
}
