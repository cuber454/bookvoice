package com.cuber.bookvoice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лёгкий файловый лог диагностики медиа/аудиофокуса. Пишет построчно в
 * `filesDir/diag.log`, свежие строки — в конец. Файл держим небольшим:
 * перевалил за мегабайт — оставляем последние 600 КБ (см. [MAX_BYTES]).
 *
 * Зачем: разобраться раз и навсегда, кто получает медиа-кнопки гарнитуры и
 * «волшебное касание» TalkBack — наше приложение или чужой плеер. Лог виден
 * слепому пользователю не нужен: файл отправляется в чат кнопкой в
 * Настройках → «Отправить лог в Telegram».
 */
object Diag {
    /** Порог, после которого журнал обрезаем, и сколько хвоста оставляем.
     *
     *  30.09.2026. Было «держим 8000 строк» — при средней строке 190 знаков это
     *  полтора мегабайта, то есть БОЛЬШЕ самого порога. Получалась ловушка: файл
     *  один раз перевалил за мегабайт, дальше КАЖДАЯ новая строка читала и
     *  переписывала весь файл целиком (1,5 МБ на диск, на потоке чтения), а сам
     *  журнал так и оставался полуторамегабайтным — тестер присылал именно такие,
     *  и чтение при этом подтормаживало. Теперь обрезка по байтам с запасом:
     *  пока порог не перевалили — файл не трогаем вовсе, после обрезки остаётся
     *  600 КБ, и следующий раз файл пишется целиком не раньше, чем наберётся
     *  ещё столько же. */
    private const val MAX_BYTES = 1_200_000L
    private const val KEEP_BYTES = 600_000

    /** Потолок длины одной строки: в журнале попадаются простыни (например,
     *  «shelf: строка «…»» на 325 знаков), а для разбора хватает начала. */
    private const val MAX_LINE = 220

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
            var line = "[${fmt.format(Date())}] $tag: $msg"
            if (line.length > MAX_LINE) line = line.take(MAX_LINE) + "…"
            FileOutputStream(f, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
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
        // 30.09.2026: вместе с журналом забываем и след заминок (StallLog) —
        // человек, нажавший «Очистить журнал», ждёт пустого листа и в окне
        // «Не засыпать».
        StallLog.clear(c)
    }

    fun fileUri(c: Context): File = file(c)

    /** Оставить хвост [KEEP_BYTES] байт — по границе строки, чтобы файл не
     *  начинался с огрызка. Считаем именно байты, а не знаки: журнал русский,
     *  и знак занимает два байта. Первой строкой пишем отметку об обрезке —
     *  иначе в присланном журнале непонятно, почему он начинается с середины. */
    private fun trim(f: File) {
        val bytes = f.readBytes()
        if (bytes.size <= KEEP_BYTES) return
        var from = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
        var nl = from
        while (nl < bytes.size && bytes[nl] != '\n'.code.toByte()) nl++
        from = if (nl < bytes.size) nl + 1
        else (bytes.size - KEEP_BYTES / 2).coerceAtLeast(0)
        val keep = bytes.copyOfRange(from, bytes.size)
        val head = ("[${fmt.format(Date())}] diag: журнал обрезан по размеру " +
            "(оставлено ${keep.size / 1024} КБ)\n").toByteArray(Charsets.UTF_8)
        FileOutputStream(f, false).use {
            it.write(head)
            it.write(keep)
        }
    }
}
