package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.os.Build
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

    fun write(ctx: Context, title: String, fmt: OpdsFormat, bytes: ByteArray): Placed =
        write(ctx, title, fmt, bytes, null)

    /** [sub] — подпапка внутри папки скачивания («Автор», «Автор — Серия»).
     *  Нужна пакетному скачиванию всей серии или автора (20.11.2026, просьба
     *  Сержа): сотни файлов плоским списком в папке разбирать невозможно, а
     *  полочный скан подпапки видит (ходит на пять уровней). null — пишем
     *  прямо в папку, как раньше; старые вызовы не меняются. Не удалось
     *  создать подпапку — пишем в корень: книга важнее порядка на диске. */
    fun write(
        ctx: Context,
        title: String,
        fmt: OpdsFormat,
        bytes: ByteArray,
        sub: String?,
    ): Placed {
        // 20.11.2026 (Серж: «пускай всё в одну папку качается, в нашу для
        // книг»): отдельная папка скачивания упразднена — цель та же, что у
        // полки, tree-uri папки книг. Старый ключ подметается один раз.
        val p = prefs(ctx)
        if (p.contains(OpdsPrefs.KEY_DL_DIR)) {
            Diag.log(ctx, "opds", "старая папка скачивания (${p.getString(OpdsPrefs.KEY_DL_DIR, "")}) убрана: качаем в папку книг")
            p.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
        }
        val tree = p.getString(LibraryActivity.KEY_TREE, null)
        val base = safeName(title)
        val clean = sub?.split("/")
            ?.joinToString("/") { safeName(it).takeIf { s -> s != "book" } ?: "book" }
            ?.takeIf { it.isNotBlank() && it != "book" }
        // 0.4.69: каталог отдаёт часть книг АРХИВОМ под книжным расширением
        // (flibusta: fb2+zip, txt+zip, html+zip, rtf+zip — проверено на живом
        // OPDS: тип application/zip, имя у сервера «Название.fb2.zip»). Пишем
        // то, что внутри: файл должен быть тем, чем называется.
        val (data, ext) = honestFile(ctx, fmt, bytes)
        if (tree == null) return writeInternalTo(ctx, clean, base, ext, data)

        return try {
            writeToTree(ctx, Uri.parse(tree), clean, base, ext, data)
        } catch (e: Exception) {
            // 0.3.47 (msg1165/1167): выбранная SAF-папка не умеет createDocument
            // (сторонние проводники вроде MixPlorer). Раньше каждая загрузка сюда
            // падала с непонятным «книга не скачалась».
            Diag.log(ctx, "opds", "SAF-папка не пишет (createDocument): ${e.message}")
            val real = runCatching {
                if (!AllFiles.granted(ctx)) return@runCatching null
                val dir = AllFiles.resolveDir(Uri.parse(tree)) ?: return@runCatching null
                val under = if (clean != null) File(dir, clean) else dir
                if (!under.isDirectory) under.mkdirs()
                val f = uniqueFile(under, base, ext)
                f.writeBytes(data)
                f
            }.getOrNull()
            if (real != null) {
                Diag.log(ctx, "opds", "записал реальным путём: ${real.absolutePath}")
                Placed(Uri.fromFile(real).toString(), real.name, fallback = false)
            } else {
                // Реальный путь не вышел — ЭТА книга ляжет во внутреннюю память
                // (как 0.4.47 предупреждало). НИКАКОГО сброса папки: с
                // 20.11.2026 это папка КНИГ, её право живёт своей жизнью
                // (перевыбор — только через строку «Папка книг»).
                Diag.log(ctx, "opds", "папка не пишет файлы, книга легла во внутреннюю: ${e.message}")
                writeInternalTo(ctx, clean, base, ext, data).copy(fallback = true)
            }
        }
    }

    /** Внутренняя папка приложения (всегда работает). */
    private fun writeInternalTo(
        ctx: Context,
        sub: String?,
        base: String,
        ext: String,
        bytes: ByteArray,
    ): Placed {
        val root = File(ctx.filesDir, "books")
        val dir = if (sub != null) File(root, sub) else root
        dir.mkdirs()
        val file = uniqueFile(dir, base, ext)
        file.writeBytes(bytes)
        return Placed(Uri.fromFile(file).toString(), file.name, fallback = false)
    }

    private fun writeToTree(
        ctx: Context,
        tree: Uri,
        sub: String?,
        base: String,
        ext: String,
        bytes: ByteArray,
    ): Placed {
        var parent: Uri = tree
        if (sub != null) {
            for (seg in sub.split("/").filter { it.isNotBlank() }) {
                // Не вышло создать уровень — пишем в то, что есть выше: книга
                // важнее порядка на диске.
                parent = ensureChildDir(ctx, tree, parent, seg) ?: break
            }
        }
        val doc = DocumentsContract.createDocument(
            ctx.contentResolver, parent, mimeForExt(ext), base + ext,
        ) ?: throw OpdsException("не удалось создать файл в выбранной папке")
        val out = ctx.contentResolver.openOutputStream(doc)
            ?: throw OpdsException("не удалось записать файл в выбранную папку")
        out.use { it.write(bytes) }
        return Placed(doc.toString(), queryDisplayName(ctx, doc) ?: base + ext, fallback = false)
    }

    /** Подпапка ([name]) внутри [parent] (parent — uri документа-папки дерева;
     *  сам tree-uri понимают некоторые провайдеры как корень) — существующая по
     *  имени или свежесозданная. null — не вышло. */
    private fun ensureChildDir(ctx: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val docId = dirDocId(ctx, tree, parent)
        val probe = if (docId != null) DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId) else return null
        val found = runCatching {
            ctx.contentResolver.query(
                probe,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iName = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(iMime) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val got = if (iName >= 0) c.getString(iName) else null
                    if (got.equals(name, ignoreCase = true)) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(iId))
                    }
                }
                null
            }
        }.getOrNull()
        if (found != null) return found
        return runCatching {
            DocumentsContract.createDocument(
                ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name,
            )
        }.getOrNull()
    }

    /** Document id папки внутри дерева: для tree-uri — её корень, для document-uri
     *  — последний сегмент пути ([DocumentsContract.findDocumentPath], API 26+).
     *  На старых Android вложенный уровень не найти — тогда пишем туда, где
     *  достали: книга важнее порядка на диске. */
    private fun dirDocId(ctx: Context, tree: Uri, parent: Uri): String? {
        runCatching { DocumentsContract.getTreeDocumentId(parent) }.getOrNull()?.let { return it }
        if (Build.VERSION.SDK_INT >= 26) {
            // Path.getPath() рамework-версии — это Deque<String> document id'ов,
            // последний = сам документ.
            return runCatching {
                DocumentsContract.findDocumentPath(ctx.contentResolver, parent)?.path?.lastOrNull()
            }.getOrNull()
        }
        return null
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
