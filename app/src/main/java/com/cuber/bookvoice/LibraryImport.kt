package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * Книга, открытая снаружи (проводник, Telegram, письмо), становится книгой
 * библиотеки (30.09.2026, просьба Сержа: «ничего не спрашивать, добавлять
 * автоматически в библиотеку и читать уже из библиотеки»).
 *
 *  Почему копия, а не ссылка. У чужого проводника (MixPlorer и подобные) ссылка
 *  живёт до перезапуска приложения: сегодня книга открылась, а завтра «в
 *  библиотеке» файла уже нет — и запись с местом чтения оказывается мёртвой.
 *  Копия в папке книг снимает эту зависимость совсем.
 *
 *  Куда кладём. Сначала в выбранную папку книг обычным SAF. Папки сторонних
 *  проводников не умеют createDocument — тогда пишем реальным путём, а для него
 *  нужен «Доступ ко всем файлам»: без него бросаем [NeedAccess], и владелец сам
 *  решает — дать доступ (копирование повторится) или положить копию во внутреннюю
 *  память приложения.
 */
object LibraryImport {

    /** Куда легла копия: ссылка, имя файла и признак «в папке книг». */
    data class Placed(val uri: Uri, val name: String, val inLibraryFolder: Boolean)

    /** Папка книг не пишется без «Доступа ко всем файлам». */
    class NeedAccess : Exception("нужен доступ ко всем файлам")

    /** Книга уже внутри библиотеки (папка книг или память приложения) — копия не
     *  нужна, читаем на месте. */
    fun inside(context: Context, uri: Uri, booksTree: Uri?): Boolean {
        if (uri.scheme == "file") {
            val p = uri.path ?: return false
            val own = File(context.filesDir, "books").absolutePath
            if (p.startsWith(own + File.separator)) return true
            val dir = booksTree?.let { AllFiles.resolveDir(it) } ?: return false
            return p.startsWith(dir.absolutePath + File.separator)
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        val treeId = booksTree?.let {
            runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()
        } ?: return false
        return docId.startsWith(treeId)
    }

    /** Имя файла для копии: без пути, без служебных префиксов чужого хранилища и
     *  без знаков, которые нельзя в имени файла. Расширение сохраняем — по нему
     *  читалка узнаёт формат. */
    fun cleanName(raw: String): String {
        var s = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        // «content:…», «file:…», имя тома вида «0A99-3BD7:Books» — не часть имени.
        s = s.substringAfterLast(':').trim()
        s = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (s.isEmpty() || s == "." || s == "..") s = "book"
        return s.take(120)
    }

    /** Забрать книгу в библиотеку. [booksTree] — выбранная папка книг (может не
     *  быть). Бросает [NeedAccess], если папка есть, но писать в неё без доступа
     *  нельзя; прочие сбои летят наружу — вызывающий уходит во внутреннюю память. */
    fun import(context: Context, uri: Uri, booksTree: Uri?, rawName: String): Placed {
        val name = cleanName(rawName)
        if (booksTree == null) return copyInternal(context, uri, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")

        // 1) Штатный SAF — так пишет большинство папок, включая папку книг.
        val bySaf = runCatching { copyToTree(context, uri, booksTree, name) }.getOrNull()
        if (bySaf != null) return bySaf

        // 2) Реальный путь: папки сторонних проводников умеют только его.
        if (!AllFiles.granted(context)) throw NeedAccess()
        val dir = AllFiles.resolveDir(booksTree) ?: throw NeedAccess()
        if (!dir.isDirectory) dir.mkdirs()
        val file = uniqueFile(dir, base, ext)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: error("провайдер не дал файл")
        Diag.log(
            context, "lib",
            "книга снаружи забрана в папку книг: ${file.name} (${file.length()} Б)",
        )
        return Placed(Uri.fromFile(file), file.name, true)
    }

    /** Копия во внутреннюю память приложения — последний запасной путь, работает
     *  всегда и не зависит ни от проводника, ни от разрешений. */
    fun copyInternal(context: Context, uri: Uri, rawName: String): Placed {
        val name = cleanName(rawName)
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val file = uniqueFile(dir, name.substringBeforeLast('.', name), name.substringAfterLast('.', ""))
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        } ?: error("провайдер не дал файл")
        Diag.log(
            context, "lib",
            "книга снаружи забрана в память приложения: ${file.name} (${file.length()} Б)",
        )
        return Placed(Uri.fromFile(file), file.name, false)
    }

    /** Файл уже с таким именем — не перетираем: добавляем номер впереди. */
    private fun uniqueFile(dir: File, base: String, ext: String): File {
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var f = File(dir, base + suffix)
        var n = 1
        while (f.exists()) {
            f = File(dir, "$n-$base$suffix")
            n++
        }
        return f
    }

    private fun copyToTree(context: Context, uri: Uri, tree: Uri, name: String): Placed {
        // Файл с таким именем уже лежит в папке книг — не плодим копию: считаем,
        // что книга уже забрана (30.09.2026). Иначе повторное открытие той же
        // книги из проводника оставляло бы в папке «Книга (1).fb2».
        findInTree(context, tree, name)?.let { return Placed(it, name, true) }
        val doc = DocumentsContract.createDocument(
            context.contentResolver, tree, mimeFor(name), name,
        ) ?: error("папка не создала документ")
        try {
            context.contentResolver.openOutputStream(doc)?.use { out ->
                context.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
                    ?: error("провайдер не дал файл")
            } ?: error("папка не дала поток на запись")
        } catch (e: Exception) {
            // Недописанный файл в папке книг не оставляем: иначе скан найдёт битую
            // книгу и человек получит «формат не поддерживается».
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) }
            throw e
        }
        Diag.log(context, "lib", "книга снаружи забрана в папку книг (SAF): $name")
        return Placed(doc, queryName(context, doc) ?: name, true)
    }

    /** Документ с таким именем прямо в папке книг (поиск только в корне дерева:
     *  глубже книги ищет скан полки, а копии мы кладём в корень). */
    private fun findInTree(context: Context, tree: Uri, name: String): Uri? = runCatching {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        context.contentResolver.query(kids, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                if (n.equals(name, ignoreCase = true)) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
            null
        }
    }.getOrNull()

    private fun queryName(context: Context, uri: Uri): String? = runCatching {        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "fb2" -> "application/fb2"
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "html", "htm", "xhtml" -> "text/html"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }
}
