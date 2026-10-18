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
 * Храним не больше [MAX_BYTES] разобранного текста, вытесняя давно не
 * открывавшиеся (LRU). Потолок по ОБЪЁМУ, а не по числу книг (msg4895): книги
 * очень разные — тонкий FB2 даёт мегабайт, крупный PDF пять, и при счётном
 * потолке мелочь впустую вытесняла тяжёлые PDF. [LIMIT] остаётся вторым
 * предохранителем — чтобы тысячи мелких записей не раздували индекс. Запись
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

    /** Сколько разобранного текста держим (msg4895 — 300 МБ). Основной потолок:
     *  место на диске предсказуемо, а мелкие книги не вытесняют крупные. */
    private const val MAX_BYTES = 300L * 1024L * 1024L

    /** Второй предохранитель — предел числа записей (msg3101 — пять, msg3149 —
     *  пятьдесят, msg4895 — триста). Держит индекс и число файлов в узде. */
    private const val LIMIT = 300

    /** Разбор дольше этого порога считаем «дорогим» и сохраняем в кэш. */
    private const val SLOW_MS = 2000L

    /** Метка формата. 0.4.80: BV1 → BV2 — в строку флагов главы добавлен третий
     *  признак (autoTitle). 09.10.2026: BV2 → BV3 — PDF разбирается по-новому
     *  (переносы, разряженные колонтитулы, мягкие переносы), и старый разбор
     *  обязан уступить место новому: иначе Серж услышал бы прежние «качествен-
     *  ное». Разбор от этого не меняется, только текст внутри.
     *  09.10.2026: BV3 → BV4 — правило разрядки стало строже (кусок не длиннее
     *  двух знаков, номер страницы кончает набор, считаются все начала набора):
     *  в «Звонке для родителя» колонтитул «З во но к дл я р од ит ел я» стоял на
     *  каждой странице и читался вслух. Кэш BV3 хранит ещё не чищенный текст,
     *  поэтому его тоже надо разобрать заново.
     *  09.10.2026: BV4 → BV5 — разбор по геометрии: склейка разрядки по зазорам,
     *  чтение страницы по колонкам (см. PDF: ПЕРЕНОСЫ, РАЗРЯДКА в AGENTS.md).
     *  09.10.2026, вечер: BV5 → BV6 — правило разряженных колонтитулов доработано, и
     *  МЕТКУ ТЕПЕРЬ ПРОВЕРЯЕМ ПРИ ЧТЕНИИ ([readDoc]): прежде файл чужой метки
     *  просто отбрасывался как «битый», но до этой проверки доходило не всегда —
     *  книга поднималась из старого кэша, и правка разбора не доезжала до
     *  человека (Серж поставил 253i, а «З во но к дл я р од ит ел я» звучал снова).
     *  ПРАВИЛО: меняешь разбор — поднимай MAGIC, и запись прежней метки уходит
     *  сама, без «Очистить кэш» руками.
     *  15.10.2026: BV7 → BV8 — ПОЯВИЛСЯ ОБЩИЙ ШАГ РАЗБОРА ([StructureClean]) для
     *  всех форматов: он убирает мусорные строки и разделители сцен, склеивает
     *  рваные строки и ищет главы там, где их нет. Текст книги до этой правки
     *  не чищен, поэтому старый кэш обязан уступить: иначе человек не услышал бы
     *  нового разбора, подняв книгу из кэша (ровно этот урок был в 253i).
     *  15.10.2026: BV8 → BV9 — В PDF ПОЯВИЛИСЬ АБЗАЦЫ ВНУТРИ СТРАНИЦЫ: прежде
     *  текст страницы уходил в книгу ОДНИМ абзацем, и книга на тысячу с лишним
     *  абзацев выглядела в ленте двумя сотнями строк-страниц («Звонок для
     *  родителя»: «глав 253, строк 506» вместо абзацев книги). Текст в кэше BV8
     *  тот же, но нарезан не так, и место чтения в нём считается по строкам
     *  ленты — кэш обязан уступить (урок 253i: без подъёма метки правку не
     *  видно вовсе).
     *  16.10.2026: BV9 → BVA — ГЛАВЫ PDF ПО КЕГЛЮ (сборка 263x): у книги без
     *  закладок главы теперь настоящие, а не страницы. Состав глав в кэше BV9
     *  другой, и место чтения в нём считается по СТАРЫМ главам — после смены
     *  метки книга разберётся заново. */
    private const val MAGIC = "BVB"

    /** Кэш-запись: uri книги → файл с разбором + сигнатура оригинала. */
    private data class Entry(
        val uri: String,
        val file: String,
        val size: Long,
        val mtime: Long,
        var used: Long,
        /** Объём кэш-файла в байтах (0 у записей старых версий — считаем с диска). */
        var bytes: Long,
        /** Отпечаток чисток разбора PDF ([ExpertPrefs.pdfStamp], 09.10.2026):
         *  сменили галочку в опытных настройках — разбор книги не годится. */
        val stamp: Long,
        /** Версия ПРАВИЛ разбора ([PdfParser.RULES], 09.10.2026): правила правят —
         *  прежний текст не годится, и книга разбирается заново сама. Без этого
         *  поля (записи до 253l) считаем 0 — то есть «правила неизвестны». */
        val rules: Int,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "parsed_cache").apply { mkdirs() }

    /** Отпечаток НАСТРОЕК РАЗБОРА: чистки PDF ([ExpertPrefs.pdfStamp]) плюс
     *  общий разбор ([ParsePrefs.stamp] — режим и части). Одна точка правды:
     *  и запись кэша, и проверка при чтении берут число отсюда, поэтому новая
     *  ручка разбора не забудется ни в одном из двух мест. */
    private fun stamp(context: Context): Long =
        ExpertPrefs.pdfStamp(context) * 31L + ParsePrefs.stamp(context)

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
                        o.optLong("b"),
                        o.optLong("st"),
                        o.optInt("rv"),
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
                    put("b", e.bytes)
                    put("st", e.stamp)
                    put("rv", e.rules)
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
        // Галочки разбора PDF сменились — прежний разбор книги не годится
        // (09.10.2026): книгу разберут заново с новым набором чисток. С
        // 15.10.2026 сюда же входит отпечаток ОБЩЕГО РАЗБОРА ([ParsePrefs]):
        // режим «как в файле» и три галочки частей — смена любого из них делает
        // прежний текст книги негодным.
        if (e.stamp != stamp(context)) {
            drop(context, idx, u)
            return null
        }
        // ПРАВИЛА разбора изменились — прежний текст книги больше не годится
        // (09.10.2026, урок сборок 253i и 253k): правку правил человек иначе не
        // увидит, книга поднимется из кэша со старым текстом. Версию правил
        // поднимает разработчик ([PdfParser.RULES]) — здесь она только читается.
        if (e.rules != PdfParser.RULES) {
            Diag.log(
                context, "cache",
                "правила разбора изменились (${e.rules} → ${PdfParser.RULES}) — " +
                    "разбираю книгу заново: ${e.file}",
            )
            drop(context, idx, u)
            return null
        }
        val f = File(dir(context), e.file)
        if (!f.isFile) {
            drop(context, idx, u)
            return null
        }
        when (val r = readDoc(f)) {
            is Read.Ok -> {
                // Обращение к кэшу — свежая дата использования (LRU).
                e.used = System.currentTimeMillis()
                saveIndex(context, idx)
                return r.doc
            }
            // Файл помечен ПРЕЖНЕЙ меткой формата: его писал разбор старой
            // сборки. Снимаем запись и разбираем книгу заново — иначе правка
            // правил разбора не доедет до книги (09.10.2026, сборка 253i: Серж
            // поставил сборку с починенными колонтитулами, а книга поднялась из
            // кэша BV5, и «З во но к дл я р од ит ел я» звучал снова).
            Read.Old -> {
                Diag.log(
                    context, "cache",
                    "разобранный текст прежней метки — разбираю книгу заново: ${e.file}",
                )
                drop(context, idx, u)
                return null
            }
            Read.Broken -> {
                drop(context, idx, u)
                return null
            }
        }
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
        val f = File(dir(context), name)
        if (!writeDoc(f, doc)) return
        idx.add(
            Entry(
                u, name, sz, mt, System.currentTimeMillis(), f.length(),
                stamp(context), PdfParser.RULES,
            )
        )
        idx.sortByDescending { it.used }
        evict(context, idx)
        saveIndex(context, idx)
    }

    /** Вытеснение по давности: пока не уложились в [MAX_BYTES] и [LIMIT].
     *  Последнюю запись не трогаем — иначе только что разобранная книга, которая
     *  одна больше потолка, не закэшируется вовсе. */
    private fun evict(context: Context, idx: MutableList<Entry>) {
        var total = idx.sumOf { entryBytes(context, it) }
        while ((idx.size > LIMIT || total > MAX_BYTES) && idx.size > 1) {
            val victim = idx.removeAt(idx.lastIndex)
            runCatching { File(dir(context), victim.file).delete() }
            total -= entryBytes(context, victim)
        }
    }

    /** Объём записи: из индекса, а у записей старых версий (без поля) — с диска. */
    private fun entryBytes(context: Context, e: Entry): Long =
        if (e.bytes > 0) e.bytes else runCatching { File(dir(context), e.file).length() }.getOrDefault(0L)

    /** Сколько места занимает кэш прямо сейчас. Считаем по факту (в обход
     *  индекса): так в цифру попадают и файлы-сироты прошлых версий.
     *  23.09.2026: сюда же входят обложки полки ([BookCovers]) — Сергей просил
     *  оставить в настройках ОДНУ строку кэша, как было, без отдельной для
     *  обложек. Поэтому и размер, и очистка у них общие с разобранным текстом. */
    fun usedBytes(context: Context): Long {
        var total = 0L
        dir(context).listFiles()?.forEach { f ->
            if (f.isFile && f.name != "index.json") total += f.length()
        }
        return total + BookCovers.usedBytes(context)
    }

    /** Потолок для показа пользователю (Настройки → «Разобранный текст»). */
    fun budgetBytes(): Long = MAX_BYTES

    /** Сколько разобранных книг лежит в кэше. */
    fun count(context: Context): Int = loadIndex(context).size

    /** Полная очистка кэша (строка в Настройках, msg4895). Файлы книг,
     *  настройки и места чтения не трогаются — книги просто разберутся заново
     *  при следующем открытии, а обложки достанутся снова. Одна кнопка на весь
     *  кэш: и разобранный текст, и обложки полки (23.09.2026). */
    fun clearAll(context: Context) {
        val d = dir(context)
        var n = 0
        d.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        Diag.log(context, "cache", "разобранный текст очищен вручную: файлов $n")
        BookCovers.clearAll(context)
    }

    /** Книга удалена из библиотеки — убрать и её кэш сразу: разобранный текст
     *  и обложку (23.09.2026, чтобы картинки удалённых книг не лежали сиротами). */
    fun remove(context: Context, uri: Uri) {
        val u = uri.toString()
        // Сначала обложка: у книги может не быть разобранного текста (её ещё
        // не открывали), а картинка в кэше уже лежит — ранний выход ниже
        // оставил бы её сиротой.
        BookCovers.remove(context, uri)
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

    /** Что вышло из чтения кэш-файла. [Old] — файл писал разбор другой сборки
     *  (метка формата не наша): это не поломка, книгу просто разбираем заново. */
    private sealed class Read {
        class Ok(val doc: BookDocument) : Read()
        object Old : Read()
        object Broken : Read()
    }

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
                // 0.4.80: третий флаг — autoTitle (0.4.71): название главы
                // придумали мы («Стр. N» у PDF без закладок), вслух его не
                // читаем. До этой правки флаг терялся при чтении из кэша, и
                // «Стр. 1, Стр. 2…» снова звучали на каждой странице.
                w.write(if (ch.autoTitle) "1" else "0")
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

    private fun readDoc(f: File): Read = runCatching {
        BufferedReader(FileReader(f)).use { r ->
            val magic = r.readLine()
            // Метка не наша — файл писал разбор другой сборки. Это НЕ «битый»
            // файл: разница только в формате, и книгу надо разобрать заново.
            if (magic != MAGIC) return Read.Old
            val title = r.readLine()?.takeIf { it.isNotEmpty() }
            val author = r.readLine()?.takeIf { it.isNotEmpty() }
            val n = r.readLine()?.toIntOrNull() ?: return Read.Broken
            if (n < 0) return Read.Broken
            val chapters = ArrayList<Chapter>(n)
            repeat(n) {
                val chTitle = r.readLine()?.takeIf { it.isNotEmpty() }
                val flags = r.readLine() ?: return Read.Broken
                val nS = r.readLine()?.toIntOrNull() ?: return Read.Broken
                if (nS < 0) return Read.Broken
                val major = flags.isNotEmpty() && flags[0] == '1'
                val nested = flags.length > 1 && flags[1] == '1'
                val autoTitle = flags.length > 2 && flags[2] == '1'
                val sents = ArrayList<Sentence>(nS)
                repeat(nS) {
                    val line = r.readLine() ?: return Read.Broken
                    val ps = line.isNotEmpty() && line[0] == '1'
                    sents.add(Sentence(if (line.length > 1) line.substring(1) else "", ps))
                }
                chapters.add(Chapter(chTitle, sents, major, nested, autoTitle))
            }
            Read.Ok(BookDocument(title, author, chapters))
        }
    }.getOrElse { Read.Broken }

    private fun clean(t: String): String {
        if (t.indexOf('\n') < 0 && t.indexOf('\r') < 0) return t
        return t.replace('\n', ' ').replace('\r', ' ')
    }
}
