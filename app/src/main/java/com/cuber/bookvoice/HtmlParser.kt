package com.cuber.bookvoice

import java.util.Locale

/**
 * HTML-книга (msg5025). Текст в файле уже лежит готовым — остаётся снять
 * разметку, нарезать главы по заголовкам и прочитать сноски по месту.
 *
 * Главы — по `<h1>`…`<h6>`: другого разделения в HTML нет. Нет ни одного
 * заголовка — вся книга одной главой без названия.
 *
 * Сноски. Разметка тут вольная, единого способа нет, поэтому путь такой:
 * ищем блок сносок — контейнер (`div`, `section`, `ol`, `<aside>`), у которого
 * в class или id написано «footnotes»/«сноски»/«примечания»; вырезаем его из
 * текста и собираем из него карту «метка → текст заметки»; ссылки внутри книги
 * (`<a href="#метка">`) находим по этой карте, саму метку из речи убираем, а
 * заметку читаем сразу за абзацем со ссылкой — тем же правилом, что в FB2
 * (msg4789, msg5025).
 *
 * Если разметка нестандартная и блок сносок не опознан, мы ничего не выдумываем
 * и ничего не теряем: заметки остаются на своих местах в конце книги и
 * читаются обычным текстом. Это и было обещано Сергею.
 */
internal object HtmlParser {

    fun parse(data: ByteArray): BookDocument? {
        val page = BookParser.decodeText(data)
        val meta = meta(page)
        val cut = noteCut(page)                       // границы блока сносок
        val notes = cut?.let { htmlNoteMap(page.substring(it.first, it.second)) }.orEmpty()
        val body = if (cut == null) page else {
            page.substring(0, cut.first) + " " + page.substring(cut.second)
        }
        val blocks = blocks(BookParser.stripNoise(body), notes)
        val chapters = BookParser.blocksToChapters(blocks)
        if (chapters.isEmpty()) return null
        return BookDocument(meta?.title, meta?.author, chapters)
    }

    fun peek(data: ByteArray): BookParser.BookMeta? = meta(BookParser.decodeText(data))

    // ---------------- Метаданные ----------------

    private fun meta(page: String): BookParser.BookMeta? {
        val title = Regex("<title[^>]*>(.*?)</title\\s*>", DOT_ALL)
            .find(page)
            ?.let { BookParser.unescape(BookParser.stripTags(it.groupValues[1])).trim() }
            ?.takeIf { it.isNotEmpty() }
        // Сначала обычный порядок атрибутов, потом обратный: генераторы пишут
        // и `name="author" content="…"`, и наоборот.
        val author = AUTHOR_RE.firstNotNullOfOrNull { re ->
            re.find(page)?.groupValues?.get(1)?.let { BookParser.unescape(it).trim() }
        }?.takeIf { it.isNotEmpty() }
        if (title == null && author == null) return null
        return BookParser.BookMeta(title, author)
    }

    private val DOT_ALL = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    private val AUTHOR_RE = listOf(
        Regex("<meta[^>]*\\bname\\s*=\\s*\"author\"[^>]*\\bcontent\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE),
        Regex("<meta[^>]*\\bcontent\\s*=\\s*\"([^\"]*)\"[^>]*\\bname\\s*=\\s*\"author\"", RegexOption.IGNORE_CASE),
    )

    // ---------------- Блок сносок ----------------

    /** Контейнер, который сам себя называет блоком сносок. Слово-признак ищем
     *  в class или id — по-другому генераторы его не помечают. */
    private val NOTE_REGION = Regex(
        "<(div|section|ol|ul|aside|dl)\\b[^>]*\\b(?:class|id)\\s*=\\s*\"[^\"]*(?:footnote|сноск|примеч|endnote)[^\"]*\"[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** Границы блока сносок: [start, end) — от открывающего тега до конца
     *  парного закрывающего. */
    private fun noteCut(page: String): Pair<Int, Int>? {
        val m = NOTE_REGION.find(page) ?: return null
        val tag = m.groupValues[1].lowercase(Locale.ROOT)
        return m.range.first to matchingClose(page, tag, m.range.first)
    }

    /** Конец элемента [tag], открытого в позиции [from]: считаем вложенные
     *  теги того же имени. Не нашли закрывающего — берём до конца файла. */
    private fun matchingClose(html: String, tag: String, from: Int): Int {
        val open = Regex("<" + tag + "\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</" + tag + "\\s*>", RegexOption.IGNORE_CASE)
        var depth = 0
        var i = from
        while (i < html.length) {
            val o = open.find(html, i)
            val c = close.find(html, i)
            if (c == null) return html.length
            if (o != null && o.range.first < c.range.first) {
                depth++
                i = o.range.last + 1
            } else {
                depth--
                if (depth == 0) return c.range.last + 1
                i = c.range.last + 1
            }
        }
        return html.length
    }

    /** Заметки из блока сносок: метка → текст. У заметки обычно есть свой
     *  элемент с id или name (`<li id="fn1">`, `<p id="fn1">`); в старом виде
     *  `<a name="fn1"></a>1. Текст` у метки содержимого нет, и заметка идёт
     *  следом до следующей метки. */
    private fun htmlNoteMap(region: String): Map<String, String> {
        val out = HashMap<String, String>()
        val open = Regex("<([A-Za-z][\\w-]*)\\b([^>]*)>", RegexOption.IGNORE_CASE)
        for (m in open.findAll(region)) {
            val tag = m.groupValues[1].lowercase(Locale.ROOT)
            val attrs = m.groupValues[2]
            if (attrs.trimEnd().endsWith("/")) continue
            val id = BookParser.attr(attrs, "id") ?: BookParser.attr(attrs, "name") ?: continue
            if (out.containsKey(id)) continue
            val end = matchingClose(region, tag, m.range.first)
            val to = end.coerceIn(m.range.last + 1, region.length)
            var inner = region.substring(m.range.last + 1, to)
            if (inner.isBlank()) {
                val next = open.findAll(region, m.range.last + 1).firstOrNull { t ->
                    val a = t.groupValues[2]
                    BookParser.attr(a, "id") != null || BookParser.attr(a, "name") != null
                }
                inner = region.substring(m.range.last + 1, next?.range?.first ?: region.length)
            }
            val text = BookParser.xhtmlParagraphs(inner).joinToString(" ").trim()
            if (text.isNotBlank()) out[id] = text
        }
        return out
    }

    // ---------------- Текст книги ----------------

    /** Разметка → абзацы. Похоже на [BookParser.xhtmlParagraphs], но, кроме
     *  границ абзацев, следит за двумя вещами: заголовок становится названием
     *  главы, а ссылка на сноску — самой сноской (метка в речь не идёт). */
    private fun blocks(html: String, notes: Map<String, String>): List<BookParser.BlockParagraph> {
        val out = ArrayList<BookParser.BlockParagraph>()
        val cur = StringBuilder()
        val curNotes = ArrayList<String>()
        var headTag: String? = null
        val headBuf = StringBuilder()
        var skipAnchor = false

        fun flush() {
            val text = cur.toString().trim()
            if (text.isNotEmpty() || curNotes.isNotEmpty()) {
                out.add(BookParser.BlockParagraph(text, null, curNotes.toList()))
            }
            cur.setLength(0)
            curNotes.clear()
        }

        fun addText(part: String) {
            val norm = BookParser.unescape(part.replace(Regex("\\s+"), " ").trim())
            if (norm.isEmpty()) return
            val buf = if (headTag != null) headBuf else cur
            if (buf.isNotEmpty() && buf.last() != ' ') buf.append(' ')
            buf.append(norm)
        }

        val n = html.length
        var i = 0
        while (i < n) {
            if (html[i] != '<') {
                val next = html.indexOf('<', i)
                val end = if (next < 0) n else next
                // Внутри ссылки на сноску стоит её метка («1», «*») — в текст
                // книги она не идёт, заметку назовёт слово «Сноска.».
                if (!skipAnchor) addText(html.substring(i, end))
                i = end
                continue
            }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                if (end < 0) break
                i = end + 3
                continue
            }
            val close = html.indexOf('>', i)
            if (close < 0) break
            val raw = html.substring(i + 1, close).trim()
            i = close + 1
            val closing = raw.startsWith("/")
            val name = raw.removePrefix("/").trim()
                .substringBefore(' ').substringBefore('\t').substringBefore('/')
                .substringAfter(':').lowercase(Locale.ROOT)
            val heading = name.length == 2 && name[0] == 'h' && name[1] in '1'..'6'

            when {
                heading && closing -> if (headTag == name) {
                    headTag = null
                    val title = headBuf.toString().trim()
                    headBuf.setLength(0)
                    flush()
                    if (title.isNotEmpty()) out.add(BookParser.BlockParagraph(title, title))
                }
                heading -> if (headTag == null) {
                    flush()
                    headTag = name
                    headBuf.setLength(0)
                }
                name == "a" -> if (closing) {
                    skipAnchor = false
                } else {
                    val target = BookParser.attr(raw, "href")
                        ?.takeIf { it.startsWith("#") }?.substring(1)
                    val note = target?.let { notes[it] }
                    if (note != null) {
                        skipAnchor = true
                        curNotes.add(note)
                    }
                }
                // Внутри заголовка границ абзацев нет: <h2><span>…</span></h2>.
                headTag == null && (name == "br" || BookParser.blockTagNames.contains(name)) -> flush()
            }
        }
        // Оборванный файл: незакрытый заголовок не теряем.
        if (headTag != null && headBuf.isNotBlank()) {
            val title = headBuf.toString().trim()
            headBuf.setLength(0)
            headTag = null
            flush()
            out.add(BookParser.BlockParagraph(title, title))
        }
        flush()
        return out
    }
}
