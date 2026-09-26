package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Куда положить скачанную книгу (0.4.83, msg7004).
 *
 * Раньше это умело только окно каталога ([CatalogActivity]), потому что и
 * скачивание жило в нём. Теперь загрузку ведёт служба ([DownloadService]) — она
 * продолжает работать, когда окно закрыли, — поэтому запись вынесена сюда и
 * работает от одного [Context].
 *
 * Порядок и правила прежние (msg1165/1167, msg1170/1172, 0.4.69):
 * 1) выбрана своя папка (SAF) — пишем в неё через DocumentsContract;
 * 2) папка не умеет createDocument (сторонние проводники вроде MixPlorer) —
 *    пробуем реальным путём по разрешению «Доступ ко всем файлам»;
 * 3) и это не вышло — кладём во внутреннюю память приложения и говорим об этом
 *    владельцу ([Placed.fallback]).
 */
object DownloadStore {

    /** Результат записи: uri книги, имя файла и оговорка «легло не туда, куда
     *  просили» — по ней служба предупреждает владельца. */
    data class Placed(val uri: String, val name: String, val fallback: Boolean)

    /** Форматы, у которых zip — это и есть сам формат: их не разворачиваем
     *  никогда. EPUB — контейнер из zip по стандарту, DOCX и ODT тоже архивы
     *  (из чужого каталога они тоже могут прийти). */
    private val zippedFormats = setOf(".epub", ".docx", ".odt")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun write(ctx: Context, title: String, fmt: OpdsFormat, bytes: ByteArray): Placed {
        val tree = prefs(ctx).getString(OpdsPrefs.KEY_DL_DIR, null)
        val base = safeName(title)
        // 0.4.69: каталог отдаёт часть книг АРХИВОМ под книжным расширением
        // (flibusta: fb2+zip, txt+zip, html+zip, rtf+zip — проверено на живом
        // OPDS: тип application/zip, имя у сервера «Название.fb2.zip»). Пишем
        // то, что внутри: файл должен быть тем, чем называется.
        val (data, ext) = honestFile(ctx, fmt, bytes)
        if (tree == null) return writeInternalTo(ctx, base, ext, data)

        return try {
            writeToTree(ctx, Uri.parse(tree), base, ext, data)
        } catch (e: Exception) {
            // 0.3.47 (msg1165/1167): выбранная SAF-папка не умеет createDocument
            // (сторонние проводники вроде MixPlorer). Раньше каждая загрузка сюда
            // падала с непонятным «книга не скачалась».
            Diag.log(ctx, "opds", "SAF-папка не пишет (createDocument): ${e.message}")
            val real = runCatching {
                if (!AllFiles.granted(ctx)) return@runCatching null
                val dir = AllFiles.resolveDir(Uri.parse(tree)) ?: return@runCatching null
                if (!dir.isDirectory) dir.mkdirs()
                val f = uniqueFile(dir, base, ext)
                f.writeBytes(data)
                f
            }.getOrNull()
            if (real != null) {
                Diag.log(ctx, "opds", "записал реальным путём: ${real.absolutePath}")
                Placed(Uri.fromFile(real).toString(), real.name, fallback = false)
            } else {
                // Реальный путь не вышел — внутренняя память (как 0.3.47).
                // Сбрасываем нерабочую папку и предупреждаем пользователя.
                Diag.log(ctx, "opds", "папка не пишет файлы, качаю во внутреннюю: ${e.message}")
                prefs(ctx).edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                writeInternalTo(ctx, base, ext, data).copy(fallback = true)
            }
        }
    }

    /** Внутренняя папка приложения (всегда работает). */
    private fun writeInternalTo(ctx: Context, base: String, ext: String, bytes: ByteArray): Placed {
        val dir = File(ctx.filesDir, "books").apply { mkdirs() }
        val file = uniqueFile(dir, base, ext)
        file.writeBytes(bytes)
        return Placed(Uri.fromFile(file).toString(), file.name, fallback = false)
    }

    private fun writeToTree(
        ctx: Context,
        tree: Uri,
        base: String,
        ext: String,
        bytes: ByteArray,
    ): Placed {
        val doc = DocumentsContract.createDocument(
            ctx.contentResolver, tree, mimeForExt(ext), base + ext,
        ) ?: throw OpdsException("не удалось создать файл в выбранной папке")
        val out = ctx.contentResolver.openOutputStream(doc)
            ?: throw OpdsException("не удалось записать файл в выбранную папку")
        out.use { it.write(bytes) }
        return Placed(doc.toString(), queryDisplayName(ctx, doc) ?: base + ext, fallback = false)
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Что сохранить и под каким расширением (0.4.69).
     *
     *  Каталог помечает упакованные ссылки «+zip», и файл приходит архивом с
     *  книжным расширением. Разворачиваем один файл книги и сохраняем под его
     *  собственным расширением. Не вышло (внутри не книга, архив битый, запись
     *  больше потолка) — сохраняем как есть, но с честным `.zip`: читалка
     *  открывает архив по содержимому, а имя больше не врёт.
     *
     *  Решаем по СОДЕРЖИМОМУ, а не по типу ссылки: архив — это первые байты PK,
     *  как и везде у нас. Тип ссылки врёт и сам (flibusta отдаёт pdf как
     *  application/pdf файлом, а fb2 и txt — архивом под тем же «application/
     *  fb2+zip»/«txt+zip»), а содержимое не врёт никогда. */
    fun honestFile(ctx: Context, fmt: OpdsFormat, bytes: ByteArray): Pair<ByteArray, String> {
        if (fmt.ext in zippedFormats) return bytes to fmt.ext
        if (!BookParser.isZip(bytes)) return bytes to fmt.ext
        val inner = BookParser.unpackBookFile(bytes)
        if (inner == null) {
            Diag.log(
                ctx, "opds",
                "книга пришла архивом, а книги внутри нет (${bytes.size} байт) — сохраняю как .zip"
            )
            return bytes to ".zip"
        }
        Diag.log(
            ctx, "opds",
            "книга пришла архивом ${fmt.ext} — разворачиваю: ${bytes.size} → ${inner.first.size} байт, ${inner.second}"
        )
        return inner
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        ".epub" -> "application/epub+zip"
        ".fb2" -> "application/x-fictionbook+xml"
        ".txt" -> "text/plain"
        ".pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, base + ext)
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    /** Имя файла из названия книги: буквы, цифры и безобидная пунктуация;
     *  пустое — «book». Длина ограничена, чтобы длинные названия не упирались
     *  в предел файловой системы. */
    fun safeName(s: String): String {
        val clean = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trim('.').take(90)
        return clean.ifBlank { "book" }
    }
}
