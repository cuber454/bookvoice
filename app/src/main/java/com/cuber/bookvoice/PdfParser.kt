package com.cuber.bookvoice

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * PDF-инструкции (msg727-752): читаем только текстовый слой, без OCR.
 *
 * Что умеем:
 *  - Если у PDF есть дерево закладок (bookmarks) — из него делаем главы-разделы
 *    (по закладке на страницу; шум вроде «■ …», шагов «1. …» и повторяющихся
 *    «Указание» выкидываем, соседние закладки одной страницы схлопываются).
 *  - Нет закладок / они «пустые» — главами становятся отдельные страницы
 *    («Стр. N»), чтобы читать и перематывать было можно и без структуры.
 *  - Скан (страницы-картинки без текста) или файл без извлекаемого текста
 *    возвращаем как [BookDocument.unreadable] = PDF_NO_TEXT_LAYER, пароль —
 *    PDF_ENCRYPTED. OCR в v1 не делаем.
 *
 * Движок — pdfbox-android. Название/автора берём из метаданных PDF (Info).
 */
object PdfParser {

    // Страница «читаемая», если в ней хотя бы столько непробельных символов
    // и нет кучи символов-замен (бывает при шрифтах без таблицы Unicode).
    private const val READABLE_MIN_CHARS = 40
    private const val REPLACE_MAX_RATIO = 0.2f

    // Меньше 150 символов на весь документ или читаемые страницы — редкость —
    // считаем, что текстового слоя по сути нет (титульник со сканом дальше).
    private const val BLIND_MIN_TOTAL_CHARS = 150

    // Сколько глав-разделов нужно из закладок, чтобы их использовать; меньше —
    // возвращаемся к постраничному разбиению.
    private const val MIN_OUTLINE_SECTIONS = 2

    // Служебные «заголовки»-ярлыки, которые закладки часто вешают на блоки
    // предупреждений. Сами по себе разделом не являются.
    private val noiseLabels = setOf(
        "указание", "указания", "внимание", "важно", "опасность", "осторожно",
        "примечание", "примечания", "совет", "советы", "список",
        "tip", "tips", "warning", "warnings", "caution", "danger", "note", "notes",
    )

    /** Разбор PDF из байтов (файл уже целиком в памяти). Для больших файлов не
     *  годится — используй [parse] с File, чтобы не держать файл и распаковку
     *  в куче (msg2619). */
    fun parse(data: ByteArray): BookDocument? = tryParseDoc { PDDocument.load(data) }

    /** Разбор PDF с диска (msg2619): файл не грузим в память целиком, а распакованные
     *  потоки pdfbox пишет во временные файлы в [scratchDir], а не в кучу. Большой
     *  текстовый учебник (39 МБ «Чалавек i свет») разворачивался в памяти и валил
     *  приложение OOM — теперь ложится на диск. [scratchDir] — папка приложения
     *  (cacheDir), гарантированно писучая. */
    fun parse(file: File, scratchDir: File): BookDocument? = tryParseDoc {
        val mem = MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir)
        PDDocument.load(file, mem)
    }

    /** Общий каркас: грузим документ, снимаем пустую защиту, строим главы. Любой
     *  сбой — включая нехватку памяти (OutOfMemoryError — это Error, обычный catch
     *  его не ловит, и падение роняло всё приложение) — отдаём причиной, а не
     *  крашем. */
    private inline fun tryParseDoc(load: () -> PDDocument): BookDocument? {
        var doc: PDDocument? = null
        return try {
            doc = load()
            val pages = doc!!.documentCatalog.pages
            val n = pages.count
            if (n <= 0) return null
            // Зашифрованные: снимаем защиту пустым паролем, если она пустая.
            if (doc.isEncrypted) {
                runCatching { doc.setAllSecurityToBeRemoved(true) }
            }
            build(doc, n)
        } catch (_: OutOfMemoryError) {
            // Большой PDF не влез в кучу при разборе (msg2587/2619). Не роняем.
            BookDocument(null, null, emptyList(), BookDocument.Unreadable.PDF_OUT_OF_MEMORY)
        } catch (_: Exception) {
            // Не открылся. Если файл действительно под паролем — скажем про это;
            // иначе это битый/не-PDF файл — null (ридер покажет общую ошибку).
            if (doc != null && doc.isEncrypted) {
                BookDocument(null, null, emptyList(), BookDocument.Unreadable.PDF_ENCRYPTED)
            } else {
                null
            }
        } finally {
            runCatching { doc?.close() }
        }
    }

    private fun build(doc: PDDocument, n: Int): BookDocument {
        // Один проход по документу: забираем текст каждой страницы отдельно,
        // сразу вычищая то, что распознаватель сканов вписал в текст книги.
        val pageTexts = PageTextStripper().run {
            getText(doc)
            cleanPages(pages)
        }

        val readablePages = ArrayList<Int>(n)
        var totalChars = 0
        for (i in 0 until n) {
            val t = pageTexts.getOrElse(i) { "" }
            if (isPageReadable(t)) {
                readablePages.add(i)
                totalChars += t.count { !it.isWhitespace() }
            }
        }

        // Скан/«слепой» файл — текстового слоя нет или почти нет.
        val sparse = n >= 5 && readablePages.size * 10 < n
        if (readablePages.isEmpty() || totalChars < BLIND_MIN_TOTAL_CHARS || sparse) {
            return BookDocument(
                null, null, emptyList(), BookDocument.Unreadable.PDF_NO_TEXT_LAYER,
            )
        }

        val infoTitle = doc.documentInformation.title
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

        // Главы: из закладок, если те дают осмысленные разделы; иначе — страницы.
        val sections = outlineSections(doc)
        val chapters = if (sections.size >= MIN_OUTLINE_SECTIONS) {
            chaptersFromSections(sections, pageTexts, n)
        } else {
            chaptersFromPages(readablePages, pageTexts)
        }

        if (chapters.isEmpty()) {
            return BookDocument(
                null, null, emptyList(), BookDocument.Unreadable.PDF_NO_TEXT_LAYER,
            )
        }
        return BookDocument(infoTitle, null, chapters)
    }

    // ---------------- Читаемость страницы ----------------

    private fun isPageReadable(raw: String): Boolean {
        val flat = raw.filterNot { it.isWhitespace() }
        if (flat.length < READABLE_MIN_CHARS) return false
        var replace = 0
        for (c in flat) if (c == '�') replace++
        return replace.toFloat() / flat.length <= REPLACE_MAX_RATIO
    }

    // ---------------- Чистка мусора распознавателя (0.4.71) ----------------

    /** Строка страницы: текст и координаты. По координатам видно то, чего не
     *  видно по тексту: колонтитул и номер стоят в ПОЛЕ, отделённые от текста
     *  полосой пустого места, а буквица — слева от своей строки. */
    private class PdfLine(val y: Float, val x: Float, var text: String) {
        /** Строка стоит в поле (верхнем или нижнем краю набора). */
        var margin = false

        /** Уже выброшена или склеена с буквицей. */
        var dropped = false

        fun norm(): String = text.replace(Regex("\\s+"), " ").trim()
    }

    /** Во сколько раз промежуток до соседа должен быть больше обычного, чтобы
     *  строку считать стоящей в поле. */
    private const val MARGIN_GAP_RATIO = 2.5f

    /** Отступ строки от левого края набора, с которого строка считается
     *  «сдвинутой буквицей» (в точках PDF). */
    private const val CAP_INDENT = 10f

    /** Докуда вверх от буквицы искать начало абзаца: тот же запас в промежутках. */
    private const val CAP_GAP_RATIO = 3f

    /** Номер страницы — строка из одних цифр (не длиннее четырёх). */
    private fun isPageNumber(t: String): Boolean = t.length in 1..4 && t.all { it.isDigit() }

    /** Буквица — одиночная буква (реже две): распознаватель сканов выносит её
     *  отдельной строкой, потому что в книге она набрана крупно и в стороне.
     *  Только кириллица: латинская «I» в таких книгах — это римская цифра части. */
    private fun isLoneLetter(t: String): Boolean =
        t.length in 1..2 && t.all { it in 'А'..'я' || it == 'Ё' || it == 'ё' }

    /** Медиана промежутков между строками страницы (сверху вниз). Меньше трёх
     *  строк — считать нечего, отдаём −1. */
    private fun medianGap(sorted: List<PdfLine>): Float {
        if (sorted.size < 3) return -1f
        val gaps = ArrayList<Float>(sorted.size - 1)
        for (i in 1 until sorted.size) gaps.add(sorted[i].y - sorted[i - 1].y)
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /**
     * Вычистить из страниц то, что распознаватель сканов вписал в текст книги
     * (0.4.71, книга Сергея «Жизнь в поместье»: 176 страниц, из них 70 с
     * колонтитулом, 123 с номером страницы, плюс 69 буквиц отдельной строкой).
     *
     * Правила — общие для сканов, а не под эту книгу:
     *  1. Колонтитул: строка стоит в поле и тот же текст стоит в поле на многих
     *     страницах. По тексту одного колонтитула не поймать — он бывает разным
     *     от главы к главе, поэтому решает МЕСТО, а повтор лишь подтверждает.
     *  2. Номер страницы: строка из одних цифр в поле — выбрасываем на каждой
     *     такой странице (номера у страниц разные, повтором их не поймать).
     *  3. Буквица: одиночную букву не выбрасываем — от неё зависит первая буква
     *     абзаца («ЖИЛИ МЫ…» иначе станет «ИЛИ МЫ…»). Ищем её абзац: вверх по
     *     непрерывному блоку строк, сдвинутых вправо от левого края набора, — и
     *     приклеиваем букву к первой строке этого блока.
     *
     * Цифры ВНЕ поля не трогаем: это числа из текста и номера в печатном
     * содержании. Не тронутое по ошибке лучше выброшенного.
     */
    private fun cleanPages(pages: List<List<PdfLine>>): List<String> {
        val n = pages.size
        val med = FloatArray(n)
        val minX = FloatArray(n)
        for (i in 0 until n) {
            val sorted = pages[i].sortedBy { it.y }
            med[i] = medianGap(sorted)
            val m = med[i]
            if (m > 1f && sorted.size >= 2) {
                if (sorted[1].y - sorted[0].y >= MARGIN_GAP_RATIO * m) sorted[0].margin = true
                val last = sorted.size - 1
                if (sorted[last].y - sorted[last - 1].y >= MARGIN_GAP_RATIO * m) sorted[last].margin = true
            }
            // Обычный левый край набора: самый левый край среди строк НЕ в поле.
            // По нему видно сдвиг строк, который оставляет буквица.
            var left = Float.MAX_VALUE
            for (l in pages[i]) if (!l.margin) left = minOf(left, l.x)
            minX[i] = if (left == Float.MAX_VALUE) 0f else left
        }

        // Тексты, стоящие в поле на многих страницах, — колонтитулы.
        val marginCount = HashMap<String, Int>()
        for (page in pages) {
            for (l in page) {
                if (!l.margin) continue
                val t = l.norm()
                if (isPageNumber(t) || isLoneLetter(t)) continue
                marginCount[t] = (marginCount[t] ?: 0) + 1
            }
        }
        val limit = maxOf(3, n / 100)

        var heads = 0
        var numbers = 0
        var caps = 0
        for (i in 0 until n) {
            val page = pages[i]
            for (l in page) {
                if (!l.margin) continue
                val t = l.norm()
                if (isPageNumber(t)) {
                    l.dropped = true
                    numbers++
                } else if ((marginCount[t] ?: 0) >= limit) {
                    l.dropped = true
                    heads++
                }
            }
            val sorted = page.sortedBy { it.y }
            for ((idx, l) in sorted.withIndex()) {
                val t = l.norm()
                if (l.dropped || l.margin || !isLoneLetter(t)) continue
                var host: PdfLine? = null
                var k = idx - 1
                while (k >= 0) {
                    val up = sorted[k]
                    if (up.dropped || up.margin) break
                    if (sorted[k + 1].y - up.y > CAP_GAP_RATIO * med[i]) break
                    if (up.x > minX[i] + CAP_INDENT && up.x > l.x) {
                        host = up
                        k--
                        continue
                    }
                    break
                }
                if (host != null) {
                    host.text = t + host.text
                    l.dropped = true
                    caps++
                }
            }
        }
        if (heads > 0 || numbers > 0 || caps > 0) {
            Diag.log(
                "pdf",
                "чистка скана: колонтитулов $heads, номеров страниц $numbers, " +
                    "буквиц склеено $caps (страниц $n)"
            )
        }
        val top = marginCount.entries.sortedByDescending { it.value }.take(3)
            .filter { it.value >= limit }
        for (e in top) Diag.log("pdf", "колонтитул «${e.key.take(60)}» на ${e.value} страницах")

        return pages.map { page ->
            page.filter { !it.dropped }.joinToString(" ") { it.norm() }
        }
    }

    // ---------------- Дерево закладок -> разделы ----------------

    /** Разделы в порядке чтения: (заголовок, страница начала). В дереве идём
     *  сверху вниз (preorder): закладка-родитель раньше своих подзакладок,
     *  поэтому на странице с несколькими закладками первой остаётся основная
     *  рубрика, а вложенные предупреждения той же страницы схлопываются. */
    private fun outlineSections(doc: PDDocument): ArrayList<Pair<String, Int>> {
        val root = doc.documentCatalog.documentOutline ?: return ArrayList()
        val tree = doc.documentCatalog.pages
        val candidates = ArrayList<Pair<String, Int>>()

        fun walk(node: PDOutlineNode) {
            var child = node.firstChild
            while (child != null) {
                val title = cleanHeading(child.title)
                if (title != null && title.length >= 2 && title.length <= 120) {
                    val page = try {
                        val pg = child.findDestinationPage(doc)
                        if (pg != null) tree.indexOf(pg) else -1
                    } catch (_: Exception) {
                        -1
                    }
                    if (page >= 0) candidates.add(title to page)
                }
                if (child.hasChildren()) walk(child)
                child = child.nextSibling
            }
        }
        walk(root)

        // Схлопываем: оставляем только закладки на новых страницах (страница
        // строго больше предыдущей оставленной). Так «указание» на той же
        // странице, что и его раздел, не породит отдельную главу.
        val out = ArrayList<Pair<String, Int>>(candidates.size)
        var lastPage = -1
        for ((t, p) in candidates) {
            if (p <= lastPage) continue
            out.add(t to p)
            lastPage = p
        }
        return out
    }

    /** Заголовок закладки → кандидат в название раздела, либо null (шум). */
    private fun cleanHeading(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val t = raw.replace(Regex("\\s+"), " ").trim()
            // Снять маркеры списков, которыми производители помечают буллеты.
            .trim('■', '●', '•', '◦', '▪', '·', '*', '»', ':', '.', '-', '–')
            .trim()
        if (t.isEmpty()) return null
        // Служебные блоки-ярлыки («Указание») и «[ru]/[pl]»-обёртки языков.
        val lower = t.lowercase()
        if (lower.contains('[')) return null
        if (noiseLabels.contains(lower.trimEnd(' ', ':', '.', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0'))) return null
        // Шаги инструкции «1. …» / «2. …» и обрывки-продолжения со строчной буквы.
        if (t[0].isDigit()) return null
        if (t[0].isLowerCase()) return null
        // Обрывки вроде «T..T.2..» (мусорные закладки Word/FrameMaker).
        if (t.count { it.isLetter() } < 3) return null
        return t
    }

    // ---------------- Сборка глав ----------------

    /** Главы по разделам из закладок: раздел начинается на странице своей
     *  закладки и длится до закладки следующего раздела. Первый раздел вбирает
     *  и страницы до своей закладки (обложка, печатное «Содержание»). */
    private fun chaptersFromSections(
        sections: ArrayList<Pair<String, Int>>,
        pageTexts: List<String>,
        n: Int,
    ): ArrayList<Chapter> {
        val out = ArrayList<Chapter>(sections.size)
        for (i in sections.indices) {
            val start = if (i == 0) 0 else sections[i].second
            val end = if (i + 1 < sections.size) sections[i + 1].second else n
            val text = joinPages(pageTexts, start, end)
            if (text.isBlank()) continue
            val sentences = TextSplit.fromParagraphs(listOf(text))
            if (sentences.isEmpty()) continue
            out.add(Chapter(sections[i].first, sentences, major = true))
        }
        return out
    }

    /** Запасной вариант без структуры: по читаемой странице — глава «Стр. N».
     *  [Chapter.autoTitle] помечает такое название как НАШЕ, а не книжное: в
     *  оглавлении оно нужно (переход к странице), а вслух его не читаем —
     *  слушать «Стр. 1, Стр. 2…» на каждой странице нечего. */
    private fun chaptersFromPages(
        readable: List<Int>,
        pageTexts: List<String>,
    ): ArrayList<Chapter> {
        val out = ArrayList<Chapter>(readable.size)
        for (i in readable) {
            val text = pageTexts[i].replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val sentences = TextSplit.fromParagraphs(listOf(text))
            if (sentences.isEmpty()) continue
            out.add(Chapter("Стр. ${i + 1}", sentences, major = true, autoTitle = true))
        }
        return out
    }

    /** Склеить текст страниц диапазона в один поток (страницы — через пробел,
     *  чтобы предложение, переходящее через границу страницы, не рвалось). */
    private fun joinPages(pageTexts: List<String>, from: Int, to: Int): String =
        (from until to).joinToString(" ") { i ->
            pageTexts.getOrElse(i) { "" }.replace(Regex("\\s+"), " ").trim()
        }.trim()

    // ---------------- Извлечение текста по страницам ----------------

    /** Один проход по документу: [pages] — текст каждой страницы по порядку.
     *  Перехватываем стандартный вывод PDFTextStripper в буфер на страницу. */
    private class PageTextStripper : PDFTextStripper() {
        private val line = StringBuilder()
        private var lineY = Float.NaN
        private var lineX = Float.NaN
        private val pageLines = ArrayList<PdfLine>()

        /** Строки каждой страницы с координатами: по ним [cleanPages] ищет
         *  колонтитулы, номера страниц и буквицы. */
        val pages = ArrayList<List<PdfLine>>()

        override fun startPage(page: PDPage) {
            pageLines.clear()
            line.setLength(0)
            lineY = Float.NaN
            lineX = Float.NaN
        }

        // Перенос строки внутри PDFTextStripper идёт отдельным вызовом — без
        // него слова соседних строк склеивались бы в одно («wordword»).
        override fun writeLineSeparator() {
            flushLine()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (textPositions.isNotEmpty()) {
                val p = textPositions[0]
                if (lineY.isNaN()) {
                    lineY = p.yDirAdj
                    lineX = p.xDirAdj
                }
            }
            line.append(text)
        }

        override fun writeString(text: String) {
            line.append(text)
        }

        override fun endPage(page: PDPage) {
            flushLine()
            pages.add(ArrayList(pageLines))
            pageLines.clear()
        }

        private fun flushLine() {
            val t = line.toString()
            if (t.isNotBlank()) {
                pageLines.add(PdfLine(if (lineY.isNaN()) -1f else lineY, if (lineX.isNaN()) -1f else lineX, t))
            }
            line.setLength(0)
            lineY = Float.NaN
            lineX = Float.NaN
        }
    }
}
