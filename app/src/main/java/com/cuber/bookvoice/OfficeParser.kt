package com.cuber.bookvoice

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * DOCX и ODT — «офисные» книги (msg5025). Оба формата это zip-архив с XML
 * внутри, ровно как EPUB, поэтому сторонних библиотек не нужно: zip и разбор
 * XML в приложении уже есть, APK не растёт и разрешений не прибавляется.
 *
 * Сноски читаются ПО МЕСТУ — тем же правилом, что в FB2 (msg4789): заметка
 * идёт сразу за абзацем, где стоит ссылка на неё, со словом-подсказкой
 * «Сноска.». Отличие от FB2 одно: у DOCX и ODT номера сноски в самом тексте
 * нет (разметка хранит только «здесь сноска»), поэтому вырезать из абзаца
 * нечего — меток не бывает, а заметка просто встаёт следом.
 *
 * Главы. В офисных книгах заголовок — это абзац со стилем заголовка, а не
 * отдельный тег. Стиль берём по имени (`heading 1`, «Заголовок 1», `Title`) и
 * по уровню структуры (`outlineLvl` / `outline-level`); у ODT заголовки — это
 * прямо `<text:h>`. Книга без единого заголовка читается одной главой без
 * названия: резать нечем, и терять нечего.
 */
internal object OfficeParser {

    // ---------------- Общее для обоих форматов ----------------

    /** Абзац книги: текст, название главы (если абзац оказался заголовком) и
     *  тексты сносок, которые в нём стоят. */
    private class Para(val text: String, val title: String?, val notes: List<String>) {
        fun toBlock() = BookParser.BlockParagraph(text, title, notes)
    }

    /** Открыть только нужные записи архива. Книги тащат внутри картинки и
     *  шрифты — в память их не берём: [wanted] отбирает по имени. */
    private fun readEntries(data: ByteArray, wanted: Set<String>): HashMap<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val key = BookParser.normName(e.name)
                    if (key in wanted) {
                        val bytes = zis.readBytes()
                        if (bytes.isNotEmpty()) out[key] = bytes
                    }
                }
                e = zis.nextEntry
            }
        }
        return out
    }

    private fun parser(xml: String): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }

    /** Имя элемента без префикса пространства имён и в нижнем регистре: парсер
     *  отдаёт и `w:footnoteReference`, и `footnoteReference` — зависит от режима
     *  разбора. Сравниваем всегда в нижнем регистре (`footnotereference`). */
    private fun local(name: String): String =
        name.substringAfterLast(':').lowercase(Locale.ROOT)

    /** Значение атрибута по локальному имени — по той же причине, что [local]. */
    private fun xattr(xp: XmlPullParser, name: String): String? {
        for (i in 0 until xp.attributeCount) {
            val n = xp.getAttributeName(i)
            if (n == name || n.endsWith(":$name")) return xp.getAttributeValue(i)
        }
        return null
    }

    /** Текст первого тега с указанным полным именем (`dc:title`). */
    private fun tagText(xml: String, fullName: String): String? {
        val re = Regex(
            "<" + Regex.escape(fullName) + "(?:\\s[^>]*)?>(.*?)</" + Regex.escape(fullName) + "\\s*>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return re.find(xml)
            ?.let { BookParser.unescape(BookParser.stripTags(it.groupValues[1])).trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    // ---------------- DOCX ----------------

    private const val DOCX_DOCUMENT = "word/document.xml"
    private const val DOCX_FOOTNOTES = "word/footnotes.xml"
    private const val DOCX_ENDNOTES = "word/endnotes.xml"
    private const val DOCX_STYLES = "word/styles.xml"
    private const val DOCX_CORE = "docprops/core.xml"

    fun parseDocx(data: ByteArray): BookDocument? {
        val files = readEntries(
            data,
            setOf(DOCX_DOCUMENT, DOCX_FOOTNOTES, DOCX_ENDNOTES, DOCX_STYLES, DOCX_CORE),
        )
        val document = files[DOCX_DOCUMENT] ?: return null
        val headings = headingStyles(files[DOCX_STYLES]?.let { BookParser.decodeText(it) })
        // Сноски и концевые сноски — один общий словарь: для читателя разницы
        // между «внизу страницы» и «в конце книги» нет, и то и другое он хочет
        // слышать на месте ссылки.
        val notes = HashMap<String, String>()
        notes.putAll(docxNotes(files[DOCX_FOOTNOTES]?.let { BookParser.decodeText(it) }))
        notes.putAll(docxNotes(files[DOCX_ENDNOTES]?.let { BookParser.decodeText(it) }))

        val paras = docxParagraphs(BookParser.decodeText(document), headings, notes)
        val chapters = BookParser.blocksToChapters(paras.map { it.toBlock() })
        if (chapters.isEmpty()) return null

        val core = files[DOCX_CORE]?.let { BookParser.decodeText(it) }
        return BookDocument(
            title = core?.let { tagText(it, "dc:title") },
            author = core?.let { tagText(it, "dc:creator") },
            chapters = chapters,
        )
    }

    fun peekDocx(data: ByteArray): BookParser.BookMeta? {
        val core = readEntries(data, setOf(DOCX_CORE))[DOCX_CORE] ?: return null
        val text = BookParser.decodeText(core)
        val title = tagText(text, "dc:title")
        val author = tagText(text, "dc:creator")
        if (title == null && author == null) return null
        return BookParser.BookMeta(title, author)
    }

    /** Абзацы тела документа. Заголовок отличается по стилю абзаца или по
     *  уровню структуры; сноска — по ссылке внутри абзаца. */
    private fun docxParagraphs(
        xml: String,
        headings: Set<String>,
        notes: Map<String, String>,
    ): List<Para> {
        val xp = parser(xml)
        val out = ArrayList<Para>()
        var inPara = false
        var capture = false              // внутри <w:t> — берём текст
        var skipText = false             // внутри поля/удалённого текста — не берём
        var heading = false
        var style: String? = null
        val buf = StringBuilder()
        val here = ArrayList<String>()

        fun flush() {
            val text = buf.toString().replace(Regex("\\s+"), " ").trim()
            val styleNow = style
            val isHeading = heading || (styleNow != null && inHeadings(styleNow, headings))
            if (text.isNotEmpty() || here.isNotEmpty()) {
                out.add(Para(text, if (isHeading) text.takeIf { it.isNotEmpty() } else null, here.toList()))
            }
            buf.setLength(0)
            here.clear()
            inPara = false
            heading = false
            style = null
        }

        var type = xp.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> {
                    when (local(xp.name)) {
                        "p" -> if (!inPara) {
                            inPara = true
                            buf.setLength(0)
                            here.clear()
                        }
                        "t" -> capture = true
                        "instrtext", "deltext" -> skipText = true
                        "pstyle" -> style = xattr(xp, "val")
                        // Уровень структуры проставлен у абзацев, оформленных
                        // как заголовок, — даже если имя стиля нам незнакомо.
                        "outlinelvl" -> heading = true
                        "tab", "br" -> if (inPara) buf.append(' ')
                        "footnotereference", "endnotereference" -> {
                            // Номер сноски в текст абзаца не попадает: он лежит
                            // в отдельном файле, и метку вырезать не из чего.
                            val id = xattr(xp, "id")
                            val note = id?.let { notes[it] }
                            if (note != null) here.add(note)
                        }
                        else -> {}
                    }
                }
                XmlPullParser.TEXT -> {
                    if (capture && !skipText && inPara) buf.append(xp.text)
                }
                XmlPullParser.END_TAG -> when (local(xp.name)) {
                    "t" -> capture = false
                    "instrtext", "deltext" -> skipText = false
                    "p" -> if (inPara) flush()
                    else -> {}
                }
            }
            type = xp.next()
        }
        if (inPara) flush()
        return out
    }

    /** Сноски из word/footnotes.xml или word/endnotes.xml: id → текст. Записи
     *  `separator` и `continuationSeparator` — служебные линии-разделители,
     *  у них нет текста заметки; их пропускаем по атрибуту type. */
    private fun docxNotes(xml: String?): Map<String, String> {
        if (xml == null) return emptyMap()
        val out = HashMap<String, String>()
        val xp = parser(xml)
        var id: String? = null
        var skip = false
        var capture = false
        val buf = StringBuilder()

        var type = xp.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when (local(xp.name)) {
                    "footnote", "endnote" -> {
                        id = xattr(xp, "id")
                        skip = xattr(xp, "type") != null
                        buf.setLength(0)
                    }
                    "t" -> capture = true
                    "tab", "br" -> if (id != null && !skip) buf.append(' ')
                    else -> {}
                }
                XmlPullParser.TEXT -> if (capture && id != null && !skip) buf.append(xp.text)
                XmlPullParser.END_TAG -> when (local(xp.name)) {
                    "t" -> capture = false
                    "footnote", "endnote" -> {
                        val text = buf.toString().replace(Regex("\\s+"), " ").trim()
                        if (id != null && !skip && text.isNotEmpty()) out[id] = text
                        id = null
                        skip = false
                    }
                    else -> {}
                }
            }
            type = xp.next()
        }
        return out
    }

    /** Стили-заголовки: styleId в нижнем регистре. В word/styles.xml у стиля
     *  есть и машинный id, и человеческое имя (`heading 1`, «Заголовок 1») —
     *  годятся оба, поэтому в набор кладём id, а имя проверяем шаблоном. */
    private fun headingStyles(xml: String?): Set<String> {
        if (xml == null) return emptySet()
        val out = HashSet<String>()
        val xp = parser(xml)
        var id: String? = null
        var name: String? = null
        var inStyle = false

        var type = xp.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when (local(xp.name)) {
                    "style" -> {
                        id = xattr(xp, "styleId")
                        name = null
                        inStyle = true
                    }
                    "name" -> if (inStyle) name = xattr(xp, "val")
                    else -> {}
                }
                XmlPullParser.END_TAG -> if (local(xp.name) == "style") {
                    if (inStyle && id != null && name != null && NAME_IS_HEADING.containsMatchIn(name)) {
                        out.add(id.lowercase(Locale.ROOT))
                    }
                    inStyle = false
                }
            }
            type = xp.next()
        }
        return out
    }

    /** Стиль абзаца — заголовок? Машинный id сверяем с таблицей стилей, а если
     *  таблицы нет — по самому id: Word зовёт встроенные заголовки `Heading1`,
     *  русская версия — «Заголовок1». */
    private fun inHeadings(style: String, headings: Set<String>): Boolean {
        val s = style.lowercase(Locale.ROOT)
        return headings.contains(s) || NAME_IS_HEADING.containsMatchIn(s)
    }

    private val NAME_IS_HEADING = Regex(
        "^(?:heading|заголовок|title|название|chapter|глава)\\b",
        RegexOption.IGNORE_CASE,
    )

    // ---------------- ODT ----------------

    private const val ODT_CONTENT = "content.xml"
    private const val ODT_META = "meta.xml"

    fun parseOdt(data: ByteArray): BookDocument? {
        val files = readEntries(data, setOf(ODT_CONTENT, ODT_META))
        val content = files[ODT_CONTENT] ?: return null
        val paras = odtParagraphs(BookParser.decodeText(content))
        val chapters = BookParser.blocksToChapters(paras.map { it.toBlock() })
        if (chapters.isEmpty()) return null

        val meta = files[ODT_META]?.let { BookParser.decodeText(it) }
        return BookDocument(
            title = meta?.let { tagText(it, "dc:title") },
            // «Первоначальный автор» — тот, кто набрал текст; dc:creator мог
            // позже переписаться правкой чужого человека, поэтому он второй.
            author = meta?.let {
                tagText(it, "meta:initial-creator") ?: tagText(it, "dc:creator")
            },
            chapters = chapters,
        )
    }

    fun peekOdt(data: ByteArray): BookParser.BookMeta? {
        val meta = readEntries(data, setOf(ODT_META))[ODT_META] ?: return null
        val text = BookParser.decodeText(meta)
        val title = tagText(text, "dc:title")
        val author = tagText(text, "meta:initial-creator") ?: tagText(text, "dc:creator")
        if (title == null && author == null) return null
        return BookParser.BookMeta(title, author)
    }

    /** Абзацы ODT. Заголовки тут — отдельный тег `<text:h>`, а сноска
     *  (`<text:note>`) лежит ПРЯМО ВНУТРИ абзаца: её тело — часть той же
     *  разметки. Поэтому считаем глубину заметки: пока мы внутри неё, абзацы
     *  её тела не закрывают абзац книги, а текст идёт в заметку. */
    private fun odtParagraphs(xml: String): List<Para> {
        val xp = parser(xml)
        val out = ArrayList<Para>()
        var inPara = false
        var heading = false
        var noteDepth = 0
        var inCitation = false
        val buf = StringBuilder()
        val noteBuf = StringBuilder()
        val notes = ArrayList<String>()

        fun flush() {
            val text = buf.toString().replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty() || notes.isNotEmpty()) {
                out.add(Para(text, if (heading) text.takeIf { it.isNotEmpty() } else null, notes.toList()))
            }
            buf.setLength(0)
            notes.clear()
            inPara = false
            heading = false
        }

        var type = xp.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when (local(xp.name)) {
                    "h" -> if (!inPara && noteDepth == 0) {
                        inPara = true
                        heading = true
                        buf.setLength(0)
                        notes.clear()
                    }
                    "p" -> if (!inPara && noteDepth == 0) {
                        inPara = true
                        buf.setLength(0)
                        notes.clear()
                    }
                    "note" -> {
                        noteDepth++
                        noteBuf.setLength(0)
                    }
                    // Метка сноски («1») — служебная: заметку называет слово
                    // «Сноска.», а номер в текст книги не идёт.
                    "note-citation" -> inCitation = true
                    "line-break", "tab", "s" -> {
                        val space = " "
                        if (noteDepth > 0 && !inCitation) noteBuf.append(space)
                        else if (inPara) buf.append(space)
                    }
                    else -> {}
                }
                XmlPullParser.TEXT -> when {
                    // Метка сноски («1») — служебная: заметку называет слово
                    // «Сноска.», поэтому в текст книги номер не идёт.
                    inCitation -> {}
                    noteDepth > 0 -> noteBuf.append(xp.text)
                    inPara -> buf.append(xp.text)
                    else -> {}
                }
                XmlPullParser.END_TAG -> when (local(xp.name)) {
                    "note-citation" -> inCitation = false
                    "note" -> {
                        noteDepth--
                        if (noteDepth == 0) {
                            val text = noteBuf.toString().replace(Regex("\\s+"), " ").trim()
                            if (text.isNotEmpty()) notes.add(text)
                        }
                    }
                    "h", "p" -> if (noteDepth == 0 && inPara) flush()
                    else -> {}
                }
            }
            type = xp.next()
        }
        if (inPara) flush()
        return out
    }
}
