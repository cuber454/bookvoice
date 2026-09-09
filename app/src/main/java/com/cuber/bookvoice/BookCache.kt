package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Кэш разобранных книг (msg3081+): тяжёлый PDF (или любой медленный разбор)
 * после первого открытия сохраняется «готовым» — текстом со структурой глав —
 * в скрытую служебную папку приложения. Повторное открытие той же книги не
 * разбирает файл заново, а читает готовую копию — почти мгновенно.
 *
 * Храним не больше [LIMIT] книг, вытесняя давно не открывавшиеся (LRU). Запись
 * привязана к сигнатуре файла-оригинала (размер + время изменения): файл
 * заменили — кэш устарел, книга разберётся заново. Удаление/замена файла
 * книги рано или поздно убирает и её кэш (get на недоступном оригинале
 * снимает запись; лишние файлы вытесняются по лимиту).
 *
 * Формат кэш-файла построчный (быстро и без тяжёлого DOM): заголовок, книга,
 * главы, предложения. Предложения и заголовки по построению не содержат
 * переводов строк (TextSplit/PdfParser их снимают), поэтому '\n' — безопасный
 * разделитель; на всякий случай при записи переносы заменяются пробелом.
 * Файл кладём во filesDir — служебная папка приложения, пользователю не
 * видна, в Библиотеке/недавних не появляется.
 */
object BookCache {

    /** Сколько разобранных книг держим (решение msg3101 — пять). */
    private const val LIMIT = 5

    /** Разбор дольше этого порога считаем «дорогим» и сохраняем в кэш. */
    private const val SLOW_MS = 2000L

    private const val MAGIC = "BV1"

    /** Кэш-запись: uri книги → файл с разбором + сигнатура оригинала. */
    private data class Entry(
        val uri: String,
        val file: String,
        val size: Long,
        val mtime: Long,
        var used: Long,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "parsed_cache").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), "index.json")

    private fun loadIndex(context: Context): MutableList<Entry> {
        val f = indexFile(context)
        if (!f.exists()) return ArrayList()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        o.getString("uri"),
                        o.getString("f"),
                        o.optLong("sz"),
                        o.optLong("mt"),
                        o.optLong("used"),
                    )
                )
            }
            out
        }.getOrElse { ArrayList() }
    }

    private fun saveIndex(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("uri", e.uri)
                    put("f", e.file)
                    put("sz", e.size)
                    put("mt", e.mtime)
                    put("used", e.used)
                }
            )
        }
        runCatching { indexFile(context).writeText(arr.toString()) }
    }

    /** Подпись файла-оригинала для инвалидации: (размер, время изменения).
     *  Для file:// берём напрямую с диска; для content:// (SAF) — только размер
     *  через ContentResolver (даты у content-uri нет). Недоступно — (-1, -1). */
    fun signature(context: Context, uri: Uri): Pair<Long, Long> {
        if (uri.scheme == "file") {
            val p = uri.path ?: return -1L to -1L
            val f = File(p)
            if (!f.isFile) return -1L to -1L
            return f.length() to f.lastModified()
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrElse { -1L }.let { it to 0L }
    }

    /** Достать разобранную книгу из кэша. Валидна только при совпавшей сигнатуре
     *  оригинала; иначе запись снимаем (файл заменили) и возвращаем null. */
    fun get(context: Context, uri: Uri): BookDocument? {
        val u = uri.toString()
        val (sz, mt) = signature(context, uri)
        if (sz < 0) return null
        val idx = loadIndex(context)
        val e = idx.firstOrNull { it.uri == u } ?: return null
        if (e.size != sz || e.mtime != mt) {
            drop(context, idx, u)
            return null
        }
        val f = File(dir(context), e.file)
        if (!f.isFile) {
            drop(context, idx, u)
            return null
        }
        val doc = readDoc(f)
        if (doc == null) {
            drop(context, idx, u)
            return null
        }
        // Обращение к кэшу — свежая дата использования (LRU).
        e.used = System.currentTimeMillis()
        saveIndex(context, idx)
        return doc
    }

    /** Сохранить разобранную книгу. Прежняя запись того же uri перезаписывается;
     *  при превышении лимита вытесняются самые давно не использовавшиеся. */
    fun put(context: Context, uri: Uri, doc: BookDocument) {
        val u = uri.toString()
        val (sz, mt) = signature(context, uri)
        if (sz < 0) return
        val idx = loadIndex(context)
        val old = idx.firstOrNull { it.uri == u }
        if (old != null) {
            runCatching { File(dir(context), old.file).delete() }
            idx.remove(old)
        }
        val name = Integer.toHexString(u.hashCode()) + ".bv"
        if (!writeDoc(File(dir(context), name), doc)) return
        idx.add(Entry(u, name, sz, mt, System.currentTimeMillis()))
        idx.sortByDescending { it.used }
        while (idx.size > LIMIT) {
            val victim = idx.removeAt(idx.lastIndex)
            runCatching { File(dir(context), victim.file).delete() }
        }
        saveIndex(context, idx)
    }

    /** Книга удалена из библиотеки — убрать и её кэш сразу. */
    fun remove(context: Context, uri: Uri) {
        val u = uri.toString()
        val idx = loadIndex(context)
        val old = idx.firstOrNull { it.uri == u } ?: return
        runCatching { File(dir(context), old.file).delete() }
        idx.remove(old)
        saveIndex(context, idx)
    }

    /** Порог «дорогого» разбора — кэшируем только то, что реально заставляет ждать. */
    fun isSlow(elapsedMs: Long): Boolean = elapsedMs >= SLOW_MS

    private fun drop(context: Context, idx: MutableList<Entry>, u: String) {
        val old = idx.firstOrNull { it.uri == u } ?: return
        runCatching { File(dir(context), old.file).delete() }
        idx.remove(old)
        saveIndex(context, idx)
    }

    // ---------------- Сериализация BookDocument ----------------

    private fun writeDoc(f: File, doc: BookDocument): Boolean = runCatching {
        FileWriter(f).use { w ->
            w.write(MAGIC)
            w.write("\n")
            w.write(doc.title ?: "")
            w.write("\n")
            w.write(doc.author ?: "")
            w.write("\n")
            w.write(doc.chapters.size.toString())
            w.write("\n")
            for (ch in doc.chapters) {
                w.write(clean(ch.title ?: ""))
                w.write("\n")
                w.write(if (ch.major) "1" else "0")
                w.write(if (ch.nested) "1" else "0")
                w.write("\n")
                w.write(ch.sentences.size.toString())
                w.write("\n")
                for (s in ch.sentences) {
                    w.write(if (s.paragraphStart) "1" else "0")
                    w.write(clean(s.text))
                    w.write("\n")
                }
            }
        }
        true
    }.getOrElse { false }

    private fun readDoc(f: File): BookDocument? = runCatching {
        BufferedReader(FileReader(f)).use { r ->
            if (r.readLine() != MAGIC) return null
            val title = r.readLine()?.takeIf { it.isNotEmpty() }
            val author = r.readLine()?.takeIf { it.isNotEmpty() }
            val n = r.readLine()?.toIntOrNull() ?: return null
            if (n < 0) return null
            val chapters = ArrayList<Chapter>(n)
            repeat(n) {
                val chTitle = r.readLine()?.takeIf { it.isNotEmpty() }
                val flags = r.readLine() ?: return null
                val nS = r.readLine()?.toIntOrNull() ?: return null
                if (nS < 0) return null
                val major = flags.isNotEmpty() && flags[0] == '1'
                val nested = flags.length > 1 && flags[1] == '1'
                val sents = ArrayList<Sentence>(nS)
                repeat(nS) {
                    val line = r.readLine() ?: return null
                    val ps = line.isNotEmpty() && line[0] == '1'
                    sents.add(Sentence(if (line.length > 1) line.substring(1) else "", ps))
                }
                chapters.add(Chapter(chTitle, sents, major, nested))
            }
            BookDocument(title, author, chapters)
        }
    }.getOrNull()

    private fun clean(t: String): String {
        if (t.indexOf('\n') < 0 && t.indexOf('\r') < 0) return t
        return t.replace('\n', ' ').replace('\r', ' ')
    }
}
