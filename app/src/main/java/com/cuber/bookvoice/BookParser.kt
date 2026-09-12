package com.cuber.bookvoice

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Разбор книги из байтов по имени файла. Поддерживает FB2, TXT, ZIP
 * (внутри ищет первую книгу .fb2/.txt/.epub), EPUB и PDF (текстовый слой).
 */
object BookParser {

    fun parse(fileName: String, data: ByteArray): BookDocument? {
        if (data.isEmpty()) return null
        val lower = fileName.lowercase(Locale.ROOT)
        // EPUB — тоже zip; пробуем сначала его (быстро отвалится, если внутри
        // архива нет META-INF/container.xml), иначе ищем fb2/txt как раньше.
        if (isZip(data)) return tryParse { parseEpub(data) ?: parseZip(data) }
        return when {
            lower.endsWith(".pdf") -> PdfParser.parse(data)
            lower.endsWith(".fb2") -> parseFb2(data)
            lower.endsWith(".xml") -> parseFb2(data) ?: parseTxt(decodeText(data))
            lower.endsWith(".txt") -> parseTxt(decodeText(data))
            lower.endsWith(".zip") -> parseZip(data)
            else -> {
                if (looksBinary(data)) null
                else tryParse { parseFb2(data) } ?: parseTxt(decodeText(data))
            }
        }
    }

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 'P'.code.toByte() &&
            data[1] == 'K'.code.toByte() &&
            (data[2] == 3.toByte() || data[2] == 5.toByte() || data[2] == 7.toByte())

    private fun looksBinary(data: ByteArray): Boolean {
        var zero = 0
        for (b in data) if (b == 0.toByte()) zero++
        return data.size > 0 && zero * 20 > data.size
    }

    // ---------------- Быстрые метаданные (название/автор) ----------------

    data class BookMeta(val title: String?, val author: String?, val annotation: String? = null)

    /** Не разбираем всю книгу — только вытаскиваем название, автора и аннотацию
     *  для полки и окна «Информация о книге». */
    fun peekMeta(fileName: String, data: ByteArray): BookMeta? {
        if (data.isEmpty()) return null
        // EPUB-метаданные проверяем первыми: иначе zipMeta принял бы
        // META-INF/container.xml за книгу и назвал бы её «container».
        if (isZip(data)) return epubPeekMeta(data) ?: zipMeta(data)
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".fb2") || lower.endsWith(".xml") ->
                metaFromText(data.copyOfRange(0, minOf(data.size, META_PREFIX)))
            else -> null
        }
    }

    private fun zipMeta(data: ByteArray): BookMeta? {
        var fb2: BookMeta? = null
        var anyTxt: BookMeta? = null
        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && fb2 == null) {
                if (!entry.isDirectory) {
                    val en = entry.name.lowercase(Locale.ROOT)
                    val isBook = en.endsWith(".fb2") || en.endsWith(".xml") || en.endsWith(".txt")
                    if (isBook) {
                        // Читаем только начало записи: метаданные FB2 лежат в начале.
                        val prefix = readPrefix(zis, META_PREFIX)
                        val base = cleanBookName(entry.name)
                        if (en.endsWith(".fb2") || en.endsWith(".xml")) {
                            val m = metaFromText(prefix)
                            val title = m?.title?.takeIf { it.isNotBlank() } ?: base
                            fb2 = BookMeta(title, m?.author, m?.annotation)
                        } else if (anyTxt == null) {
                            // У TXT названия нет — показываем имя файла внутри архива.
                            anyTxt = BookMeta(base, null)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        return fb2 ?: anyTxt
    }

    private fun readPrefix(zis: ZipInputStream, max: Int): ByteArray {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < max) {
            val n = zis.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            if (n > 0) {
                out.write(buf, 0, n)
                total += n
            }
        }
        return out.toByteArray()
    }

    /** Имя файла внутри архива без пути и расширения — запасное «название». */
    private fun cleanBookName(entryName: String): String {
        val base = entryName.substringAfterLast('/').trim()
        val dot = base.lastIndexOf('.')
        val noExt = if (dot > 0) base.substring(0, dot) else base
        return noExt.trim().takeIf { it.isNotBlank() } ?: base
    }

    private fun metaFromText(prefix: ByteArray): BookMeta? {
        val t = decodeText(prefix)
        val title = bookTitleRe.find(t)?.groupValues?.get(1)
            ?.let { unescape(it.trim()) }?.takeIf { it.isNotBlank() }
        val authorBlock = authorRe.find(t)?.value
        var author: String? = null
        if (authorBlock != null) {
            val names = nameRe.findAll(authorBlock)
                .map { unescape(it.groupValues[1].trim()) }
                .filter { it.isNotEmpty() }
                .toList()
            if (names.isNotEmpty()) author = names.joinToString(" ")
        }
        // Аннотация FB2 живёт в <description><annotation>. Внутри — теги
        // абзацев (<p>): снимаем их, потом сущности и схлопываем пробелы,
        // чтобы для окна «Информация» получился сплошной читаемый текст.
        val ann = annotationRe.find(t)?.let { m ->
            unescape(stripTags(m.groupValues[1])).replace(Regex("\\s+"), " ").trim()
        }?.takeIf { it.isNotEmpty() }
        if (title == null && author == null && ann == null) return null
        return BookMeta(title, author, ann)
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&laquo;", "«").replace("&raquo;", "»")
        .replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’")
        .replace("&hellip;", "…").replace("&middot;", "·")
        .replace("&shy;", "").replace("&copy;", "©")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }

    private val bookTitleRe = Regex(
        "<book-title[^>]*>(.*?)</book-title>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val authorRe = Regex(
        "<author>.*?</author>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val nameRe = Regex(
        "<(?:first-name|last-name|middle-name)[^>]*>(.*?)</(?:first-name|last-name|middle-name)>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val annotationRe = Regex(
        "<annotation[^>]*>(.*?)</annotation>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private const val META_PREFIX = 256 * 1024

    private inline fun <T> tryParse(block: () -> T?): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    // ---------------- TXT ----------------

    private val headingRe = Regex(
        "^(?:глава|часть|книга|пролог|эпилог|том|chapter|part)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    private fun parseTxt(text: String): BookDocument {
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = norm.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val chapters = ArrayList<Chapter>()
        var curTitle: String? = null
        val curParagraphs = ArrayList<String>()

        fun close() {
            val s = TextSplit.fromParagraphs(curParagraphs)
            if (s.isNotEmpty()) chapters.add(Chapter(curTitle?.takeIf { it.isNotEmpty() }, s))
            curTitle = null
            curParagraphs.clear()
        }

        for (block in blocks) {
            val singleLine = !block.contains('\n')
            if (singleLine && block.length <= 70 && headingRe.containsMatchIn(block)) {
                close()
                curTitle = block.trim().trimEnd(' ', ':', '.', '…')
            } else {
                curParagraphs.add(block.replace('\n', ' ').trim())
            }
        }
        close()
        return BookDocument(null, null, chapters)
    }

    // ---------------- ZIP ----------------

    /**
     * Найти внутри архива любую читаемую книжку: .fb2, .txt, .xml или даже
     * вложенный архив. Не обрывается на первом попавшемся битом файле —
     * предпочитает корректный FB2, иначе берёт первый любой удавшийся файл.
     */
    private fun parseZip(data: ByteArray): BookDocument? {
        var fb2: BookDocument? = null
        var any: BookDocument? = null
        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && fb2 == null) {
                if (!entry.isDirectory) {
                    val en = entry.name.lowercase(Locale.ROOT)
                    val bytes = zis.readBytes()
                    if (bytes.isEmpty()) {
                        entry = zis.nextEntry
                        continue
                    }
                    when {
                        en.endsWith(".fb2") || en.endsWith(".xml") -> {
                            val d = parseFb2(bytes) ?: if (en.endsWith(".xml")) {
                                parseTxt(decodeText(bytes))
                            } else {
                                null
                            }
                            if (d != null) fb2 = d
                        }
                        any == null -> any = tryParse { parse(entry.name, bytes) }
                    }
                }
                entry = zis.nextEntry
            }
        }
        return fb2 ?: any
    }

    // ---------------- EPUB ----------------

    private data class OpfItem(val href: String, val mediaType: String, val properties: String)

    private const val CONTAINER_PATH = "meta-inf/container.xml"
    private val EPUB_TEXT_EXT = listOf(".xhtml", ".html", ".htm", ".opf", ".ncx", ".xml")
    private val opfItemRe = Regex("<item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val opfItemrefRe = Regex("<itemref\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val opfSpineRe = Regex("<spine\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)

    /** EPUB — zip с книгой: контейнер указывает на OPF, OPF описывает порядок
     *  глав (spine), каждая глава — отдельный XHTML-файл. */
    private fun parseEpub(data: ByteArray): BookDocument? {
        val files = epubFiles(data)
        val container = files[CONTAINER_PATH] ?: return null
        val opfRel = containerRootfile(container) ?: return null
        val opfKey = normName(opfRel)
        val opfText = files[opfKey]?.let { decodeText(it) } ?: return null
        val opfDir = opfKey.substringBeforeLast('/', "")

        // Манифест (id -> файл) и порядок чтения глав (spine).
        val manifest = HashMap<String, OpfItem>()
        for (m in opfItemRe.findAll(opfText)) {
            val id = attr(m.value, "id") ?: continue
            val href = attr(m.value, "href") ?: continue
            manifest[id] = OpfItem(
                href = href,
                mediaType = attr(m.value, "media-type").orEmpty(),
                properties = attr(m.value, "properties").orEmpty(),
            )
        }
        val spineTocId = opfSpineRe.find(opfText)?.let { attr(it.value, "toc") }
        val spine = ArrayList<String>()
        for (m in opfItemrefRe.findAll(opfText)) {
            val id = attr(m.value, "idref") ?: continue
            val href = manifest[id]?.href ?: continue
            val doc = normName(opfDir + "/" + href)
            if (doc.isNotEmpty() && !spine.contains(doc)) spine.add(doc)
        }
        if (spine.isEmpty()) return null

        // Названия глав берём из NCX-оглавления (если есть), иначе — из первой
        // рубрики h1-h6 самой страницы.
        val tocItem = spineTocId?.let { manifest[it] }?.takeIf { it.mediaType.contains("ncx") }
            ?: manifest.values.firstOrNull { it.mediaType.contains("ncx") }
        val titles = HashMap<String, String>()
        tocItem?.let { item ->
            val tocKey = normName(opfDir + "/" + item.href)
            val tocDir = tocKey.substringBeforeLast('/', "")
            files[tocKey]?.let { toc ->
                val navRe = Regex(
                    "(?s)<navLabel>.*?<text>(.*?)</text>.*?<content[^>]*\\bsrc=\"([^\"]+)\"",
                )
                for (m in navRe.findAll(decodeText(toc))) {
                    val label = unescape(m.groupValues[1].trim()).takeIf { it.isNotBlank() } ?: continue
                    val doc = normName(tocDir + "/" + m.groupValues[2].substringBefore('#'))
                    if (doc.isNotEmpty()) titles.putIfAbsent(doc, label)
                }
            }
        }

        val chapters = ArrayList<Chapter>()
        for (doc in spine) {
            val text = files[doc]?.let { decodeText(it) } ?: continue
            val paragraphs = xhtmlParagraphs(stripNoise(text))
            if (paragraphs.isEmpty()) continue
            val title = titles[doc] ?: firstHeading(text)
            chapters.add(Chapter(
                title?.takeIf { it.isNotBlank() },
                TextSplit.fromParagraphs(paragraphs),
            ))
        }
        if (chapters.isEmpty()) return null

        return BookDocument(
            title = opfMeta(opfText, "title"),
            author = opfMeta(opfText, "creator"),
            chapters = chapters,
        )
    }

    /** dc:title / dc:creator из OPF. */
    private fun opfMeta(opf: String, tag: String): String? {
        val re = Regex(
            "<(?:dc|dcterms):" + tag + "[^>]*>(.*?)</(?:dc|dcterms):" + tag + ">",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return re.find(opf)?.let { m ->
            unescape(stripTags(m.groupValues[1])).trim().takeIf { it.isNotEmpty() }
        }
    }

    /** Прочитать только текстовые файлы EPUB (xhtml/opf/ncx) в память. */
    private fun epubFiles(data: ByteArray): HashMap<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val key = normName(e.name)
                    if (key == CONTAINER_PATH || EPUB_TEXT_EXT.any { key.endsWith(it) }) {
                        val b = zis.readBytes()
                        if (b.isNotEmpty()) files[key] = b
                    }
                }
                e = zis.nextEntry
            }
        }
        return files
    }

    /** Путь к OPF из META-INF/container.xml. */
    private fun containerRootfile(container: ByteArray): String? =
        Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(decodeText(container))?.groupValues?.get(1)

    /** Нормализация пути внутри zip: без "./" и "../", всё в нижний регистр. */
    private fun normName(path: String): String {
        val stack = ArrayList<String>()
        for (seg in path.replace('\\', '/').split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/").lowercase(Locale.ROOT)
    }

    /** Значение атрибута из строки XML-тега (в двойных или одинарных кавычках). */
    private fun attr(tag: String, name: String): String? {
        Regex("\\b" + name + "\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(tag)?.let { return it.groupValues[1] }
        return Regex("\\b" + name + "\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
    }

    /** Метаданные EPUB для полки — только container + OPF, без чтения глав. */
    private fun epubPeekMeta(data: ByteArray): BookMeta? {
        val files = epubFiles(data)
        val container = files[CONTAINER_PATH] ?: return null
        val opfRel = containerRootfile(container) ?: return null
        val opfText = files[normName(opfRel)]?.let { decodeText(it) } ?: return null
        val title = opfMeta(opfText, "title")
        val author = opfMeta(opfText, "creator")
        if (title == null && author == null) return null
        return BookMeta(title, author)
    }

    // ---- XHTML главы -> абзацы ----

    /** Блочные теги XHTML — граница абзаца. */
    private val blockTagNames = setOf(
        "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
        "pre", "section", "article", "aside", "header", "footer", "figure",
        "table", "tr", "td", "th", "ul", "ol", "dl", "dt", "dd", "hr",
        "center", "address", "nav", "main",
    )

    /** Выкинуть из страницы то, что не несёт текста книги: скрипты, стили, шапку. */
    private fun stripNoise(html: String): String {
        var s = html
        for (tag in arrayOf("script", "style", "head", "svg", "math", "object", "embed")) {
            s = Regex("(?s)<" + tag + "\\b.*?</" + tag + "\\s*>", RegexOption.IGNORE_CASE)
                .replace(s, " ")
        }
        return s
    }

    /** XHTML -> абзацы: блочные теги и <br> начинают новый абзац, разметка выкинута. */
    private fun xhtmlParagraphs(html: String): List<String> {
        val paragraphs = ArrayList<String>()
        val cur = StringBuilder()
        val n = html.length
        var i = 0
        fun flush() {
            val t = cur.toString().trim()
            if (t.isNotEmpty()) paragraphs.add(t)
            cur.setLength(0)
        }
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                val next = html.indexOf('<', i)
                val end = if (next < 0) n else next
                val norm = unescape(html.substring(i, end).replace(Regex("\\s+"), " ").trim())
                if (norm.isNotEmpty()) {
                    if (cur.isNotEmpty() && cur.last() != ' ') cur.append(' ')
                    cur.append(norm)
                }
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
            var raw = html.substring(i + 1, close).trim()
            i = close + 1
            if (raw.startsWith("/")) raw = raw.substring(1).trim()
            val name = raw.substringBefore(' ').substringBefore('\t')
                .substringAfter(':').substringBefore('/').lowercase(Locale.ROOT)
            if (name == "br" || blockTagNames.contains(name)) flush()
        }
        flush()
        return paragraphs
    }

    /** Первая рубрика h1-h6 страницы — запасное название главы. */
    private fun firstHeading(html: String): String? {
        val re = Regex(
            "<h[1-6][^>]*>(.*?)</h[1-6]>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return re.find(html)?.let { m ->
            unescape(stripTags(m.groupValues[1])).trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), " ")

    // ---------------- FB2 ----------------

    private fun parseFb2(data: ByteArray): BookDocument? {
        // Сначала декодируем байты в текст: многие FB2 лежат в windows-1251 или
        // UTF-16, и отдавать их XML-парсеру «как есть» ненадёжно.
        val text = decodeText(data).trimStart()
        if (!text.startsWith('<')) return null
        val factory = XmlPullParserFactory.newInstance()
        val xp = factory.newPullParser()
        xp.setInput(StringReader(text))

        // --- Метаданные (<description>) до первого <body> ---
        var bookTitle: String? = null
        val authorParts = ArrayList<String>()
        var inDescription = false
        var authorOpen = false
        var bodyStarted = false
        var target = ""                    // "", "bt", "name"
        var btBuf = StringBuilder()
        var nameBuf = StringBuilder()

        var type = xp.eventType
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> when (xp.name) {
                    "description" -> inDescription = true
                    "book-title" -> if (inDescription) { target = "bt"; btBuf = StringBuilder() }
                    "author" -> if (inDescription) authorOpen = true
                    "first-name", "last-name", "middle-name" -> if (authorOpen) {
                        target = "name"
                        nameBuf = StringBuilder()
                    }
                    "body" -> bodyStarted = true
                }
                XmlPullParser.TEXT -> when (target) {
                    "bt" -> btBuf.append(xp.text)
                    "name" -> nameBuf.append(xp.text)
                }
                XmlPullParser.END_TAG -> when (xp.name) {
                    "book-title" -> if (target == "bt") {
                        if (bookTitle == null) bookTitle = btBuf.toString().trim()
                        target = ""
                    }
                    "first-name", "last-name", "middle-name" -> if (target == "name") {
                        val t = nameBuf.toString().trim()
                        if (t.isNotEmpty()) authorParts.add(t)
                        target = ""
                    }
                    "author" -> authorOpen = false
                    "description" -> inDescription = false
                }
            }
            if (bodyStarted) break
            type = xp.next()
        }

        // --- Главы из <body> ---
        val chapters = if (bodyStarted) collectFb2Chapters(xp) else emptyList()
        if (chapters.isEmpty()) return null

        val author = authorParts.joinToString(" ").trim().ifEmpty { null }
        val tt = bookTitle?.takeIf { it.isNotEmpty() }
        return BookDocument(tt, author, chapters)
    }

    /** Ссылка-сноска в абзаце: id заметки и текст метки, как он стоит в книге. */
    private class Fb2NoteRef(val id: String, val mark: String)

    /** Одна секция FB2: заголовок, собственный текст и вложенные секции.
     *  `refs[i]` — сноски абзаца `own[i]` (тексты — в fb2NoteMap). */
    private class Fb2Sec(
        val title: String?,
        val own: List<String>,
        val subs: List<Fb2Sec>,
        val id: String? = null,
        val refs: List<List<Fb2NoteRef>> = emptyList(),
    ) {
        val words: Int get() = own.sumOf { it.split(Regex("\\s+")).size }
    }

    /** Секция <body> FB2 → главы. Главы берём по секциям С СОБСТВЕННЫМ текстом
     *  любой глубины (msg460/#72 — «названия глав из текста»): секции-обёртки
     *  без текста (части I/II, под которыми лежат рассказы) прозрачны, но своё
     *  название отдают первой вложенной главе — иначе часть с одними главами
     *  внутри молчит (msg4797: у книги тестера так пропадали «1. Пламя и сталь»
     *  и «2. Древо и сталь», а «3. Камень и пламя» с «4. Лед и сталь» звучали).
     *  Короткий неназванный текст в самом начале книги (титул, копирайт, «* * *»)
     *  главами не становится — книга начинается с первой названной главы.
     *  FB2, где абзацы лежат прямо в <body> без секций, читаем одной главой,
     *  а не «форматом не поддерживается» (msg4797). */
    private fun collectFb2Chapters(xp: XmlPullParser): List<Chapter> {
        val roots = ArrayList<Fb2Sec>()
        val noteRoots = ArrayList<Fb2Sec>()
        val flat = ArrayList<String>()     // абзацы прямо в <body>, без секций
        var type = xp.next()               // входим внутрь <body>
        var bodies = 0                     // 0 — основной текст, дальше — примечания
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                val name = xp.name
                if (name == "section") {
                    if (bodies == 0) roots.add(readFb2Section(xp))
                    else noteRoots.add(readFb2Section(xp))
                } else if (name == "binary") {
                    // За телами идут картинки <binary> (base64, мегабайты текста) —
                    // дальше в документе нам делать нечего.
                    break
                } else if (bodies == 0 && name in fb2ParagraphTags) {
                    val t = readFb2FlatParagraph(xp, name).trim()
                    if (t.isNotEmpty()) flat.add(t)
                }
            } else if (type == XmlPullParser.END_TAG && xp.name == "body") {
                bodies++
            }
            type = xp.next()
        }
        if (roots.isEmpty() && flat.isNotEmpty()) roots.add(Fb2Sec(null, flat, emptyList()))

        val notes = fb2NoteMap(noteRoots)

        val raw = ArrayList<Chapter>()
        // Для настройки «Кнопки глав шагают» (0.3.37): major = первая текстовая
        // глава корневой секции (начало крупного раздела/части), nested = глава
        // внутри другой главы (подраздел). По корням идём отдельно, чтобы major
        // сбрасывался на каждом крупном разделе.
        for (r in roots) {
            var opened = false
            // Возвращает неистраченный заголовок-обёртку: его получает первая
            // глава под прозрачной секцией, а если такой главы не нашлось —
            // следующий сосед на том же уровне.
            fun walk(sec: Fb2Sec, underChapter: Boolean, prefix: String?): String? {
                var pending = prefix
                val hasOwn = sec.own.isNotEmpty()
                if (hasOwn) {
                    val s = fb2Sentences(sec, notes)
                    if (s.isNotEmpty()) {
                        raw.add(Chapter(fb2Title(pending, sec.title), s, major = !opened, nested = underChapter))
                        opened = true
                        pending = null
                    }
                } else if (!sec.title.isNullOrEmpty()) {
                    pending = fb2Title(pending, sec.title)
                }
                for (sub in sec.subs) pending = walk(sub, underChapter || hasOwn, pending)
                return pending
            }
            walk(r, false, null)
        }

        // Срезаем передний служебный текст: неназванные и очень короткие главы
        // до первой главы с названием (титульный лист, копирайт издательства).
        // Так всегда (msg4653): настройка «Показывать титульный лист» убрана —
        // Сергей: «она ничего не меняет», книга начинается с первой главы.
        val firstTitled = raw.indexOfFirst { it.title != null }
        var from = 0
        while (from < firstTitled && raw[from].title == null && wordsIn(raw[from]) < FRONT_MATTER_MAX_WORDS) from++
        return if (from == 0) raw else ArrayList(raw.subList(from, raw.size))
    }

    /** Название главы с учётом заголовка обёртки-части: «1. Пламя и сталь. Глава 1». */
    private fun fb2Title(prefix: String?, title: String?): String? = when {
        prefix.isNullOrEmpty() -> title
        title.isNullOrEmpty() -> prefix
        else -> "$prefix. $title"
    }

    /** Абзац FB2, лежащий прямо в <body> (книга без секций): собираем его текст
     *  до закрывающего тега и оставляем парсер на этом событии — внешний цикл
     *  продолжит с него. */
    private fun readFb2FlatParagraph(xp: XmlPullParser, tag: String): String {
        val buf = StringBuilder()
        var type = xp.next()
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.TEXT) buf.append(xp.text)
            else if (type == XmlPullParser.END_TAG && xp.name == tag) break
            type = xp.next()
        }
        return buf.toString()
    }

    /** Сноски книги: id заметки → её текст. В FB2 сноски живут отдельными
     *  <body> после основного текста (msg4771/4789, книга тестера @Spartach72 —
     *  40 заметок), а в самом тексте на них ведут ссылки <a type="note">.
     *  Заголовок заметки («1K1») — это метка ссылки, в речь он не идёт. */
    private fun fb2NoteMap(roots: List<Fb2Sec>): Map<String, String> {
        val out = HashMap<String, String>()
        fun walk(sec: Fb2Sec) {
            val body = sec.own.joinToString(" ").trim()
            if (body.isNotEmpty() && sec.id != null) out[sec.id] = body
            for (sub in sec.subs) walk(sub)
        }
        for (r in roots) walk(r)
        return out
    }

    /** Предложения секции с подстановкой сносок: заметка читается сразу после
     *  того абзаца, где стоит ссылка на неё (msg4789 — «переставляй»: в бумаге
     *  сноска стоит на той же странице, а не в конце тома; до конца книги на
     *  слух никто не дойдёт). Метку `[1K1]` из речи убираем, только если заметка
     *  нашлась: битая ссылка остаётся в тексте как есть, ни слова не теряем. */
    private fun fb2Sentences(sec: Fb2Sec, notes: Map<String, String>): List<Sentence> {
        val res = ArrayList<Sentence>()
        for ((i, para) in sec.own.withIndex()) {
            val refs = sec.refs.getOrNull(i).orEmpty()
            val found = refs.filter { notes.containsKey(it.id) }
            val text = if (found.isEmpty()) para else fb2WithoutMarks(para, found)
            res.addAll(TextSplit.fromParagraphs(listOf(text)))
            for (ref in found) {
                val note = notes[ref.id] ?: continue
                res.addAll(TextSplit.fromParagraphs(listOf("$FB2_NOTE_CUE$note")))
            }
        }
        return res
    }

    /** Текст абзаца без найденных меток сносок. Идём по тексту курсором, чтобы
     *  две одинаковые метки в одном абзаце снялись по своим местам. */
    private fun fb2WithoutMarks(para: String, refs: List<Fb2NoteRef>): String {
        val sb = StringBuilder(para)
        var from = 0
        for (ref in refs) {
            if (ref.mark.isEmpty()) continue
            val idx = sb.indexOf(ref.mark, from)
            if (idx < 0) continue
            sb.delete(idx, idx + ref.mark.length)
            from = idx
        }
        // На месте метки остаются лишние пробелы («метку  в тексте») — подчищаем.
        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;:!?…])"), "$1")
            .trim()
    }

    /** Теги FB2, текст которых становится абзацами книги. `text-author` —
     *  подпись под цитатой или эпиграфом («Из рекламного проспекта», «Дьякон
     *  Равак, Лада, третий сектор»): без неё цитата читается как речь самого
     *  рассказчика, а строка, которая в файле есть, из книги пропадает
     *  (msg4771, книга тестера @Spartach72 — 144 такие строки). */
    private val fb2ParagraphTags = setOf("p", "v", "subtitle", "text-author")

    /** Слово-подсказка перед заметкой: без него сноска посреди абзаца звучит как
     *  продолжение рассказа, и слушатель теряет нить (msg4789). */
    private const val FB2_NOTE_CUE = "Сноска. "

    /** Рекурсивно читает один <section> до его закрытия (включая вложенные). */
    private fun readFb2Section(xp: XmlPullParser): Fb2Sec {
        val id = fb2Attr(xp, "id")
        var title: String? = null
        val own = ArrayList<String>()
        val refs = ArrayList<List<Fb2NoteRef>>()
        val subs = ArrayList<Fb2Sec>()
        var collectingTitle = false
        val titleBuf = StringBuilder()
        var inP = false
        val pBuf = StringBuilder()
        var inNote = false                 // внутри <a type="note"> — метки сноски
        var noteId: String? = null
        val noteBuf = StringBuilder()
        var paraRefs = ArrayList<Fb2NoteRef>()
        var inner = 0                      // глубина внутри секции (без вложенных секций)
        var type = xp.next()               // входим внутрь <section>
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> {
                    val name = xp.name
                    if (name == "section") {
                        subs.add(readFb2Section(xp))
                    } else {
                        inner++
                        when (name) {
                            // Название главы — только собственный <title> секции:
                            // заголовок стиха или эпиграфа внутри главы не должен
                            // подменять её имя (msg4797, проверено пробой).
                            "title" -> if (inner == 1) { collectingTitle = true; titleBuf.setLength(0) }
                            "a" -> {
                                // Ссылка-сноска: её текст — метка ([1K1]), а ведёт она в
                                // тело notes. Не сноска (обычная ссылка) — остаётся как была.
                                val href = fb2Attr(xp, "href")
                                if (inP && fb2Attr(xp, "type") == "note" && href?.startsWith("#") == true) {
                                    inNote = true
                                    noteId = href.substring(1)
                                    noteBuf.setLength(0)
                                }
                            }
                            in fb2ParagraphTags -> if (!collectingTitle) { inP = true; pBuf.setLength(0) }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val tx = xp.text
                    when {
                        collectingTitle -> titleBuf.append(tx)
                        // Метку кладём и в абзац, и в сноску: абзац без метки
                        // собирается уже потом, когда известно, нашлась ли заметка.
                        inNote -> { noteBuf.append(tx); pBuf.append(tx) }
                        inP -> pBuf.append(tx)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = xp.name
                    if (name == "section") return Fb2Sec(title, own, subs, id, refs)
                    inner--
                    when (name) {
                        "title" -> if (collectingTitle) {
                            title = titleBuf.toString().trim().takeIf { it.isNotEmpty() }
                            collectingTitle = false
                        }
                        "a" -> if (inNote) {
                            val nid = noteId
                            if (nid != null) paraRefs.add(Fb2NoteRef(nid, noteBuf.toString().trim()))
                            inNote = false
                            noteId = null
                        }
                        in fb2ParagraphTags -> if (inP) {
                            val t = pBuf.toString().trim()
                            if (t.isNotEmpty()) {
                                own.add(t)
                                refs.add(paraRefs)
                            }
                            paraRefs = ArrayList()
                            inP = false
                        }
                    }
                }
            }
            type = xp.next()
        }
        return Fb2Sec(title, own, subs, id, refs)   // документ оборвался — отдаём что собрали
    }

    /** Значение атрибута FB2 по локальному имени. Пространства имён разбираем
     *  руками: атрибут ссылки зовётся `l:href` (xlink), но парсер может отдавать
     *  имя и как `href`, и как `l:href` — зависит от режима разбора. */
    private fun fb2Attr(xp: XmlPullParser, name: String): String? {
        for (i in 0 until xp.attributeCount) {
            val n = xp.getAttributeName(i)
            if (n == name || n.endsWith(":$name")) return xp.getAttributeValue(i)
        }
        return null
    }

    private fun wordsIn(ch: Chapter): Int =
        ch.sentences.sumOf { it.text.split(Regex("\\s+")).size }

    private const val FRONT_MATTER_MAX_WORDS = 30

    // ---------------- Декодирование текста ----------------

    private fun decodeText(data: ByteArray): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte())
            return String(data, 3, data.size - 3, Charsets.UTF_8)
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte())
            return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte())
            return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        strictUtf8(data)?.let { return it }
        return String(data, Charset.forName("windows-1251"))
    }

    /** Строгий UTF-8. Если данные обрезаны на середине многобайтового символа
     *  (префикс для метаданных), до 3 хвостовых байт отбрасываем и пробуем снова —
     *  иначе валидный UTF-8 падал бы в windows-1251 и названия книг «портились»
     *  (РєР°С‡… вместо «Нача…»). */
    private fun strictUtf8(data: ByteArray): String? {
        for (cut in 0..3) {
            val len = data.size - cut
            if (len <= 0) return null
            try {
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return dec.decode(ByteBuffer.wrap(data, 0, len)).toString()
            } catch (_: Exception) {
                // пробуем следующий cut
            }
        }
        return null
    }

    /** Если строка — «побитая» кодировка (UTF-8, прочитанный как windows-1251:
     *  «РќР°С‡Р°Р»Рѕ» вместо «Начало»), возвращает исправленный текст. Хранившиеся
     *  до 0.3.27 испорченные названия чиним при скане, не перечитывая файл. */
    fun unmojibake(s: String): String? {
        if (s.isBlank() || !s.any { it.code > 127 }) return null
        val bytes = try {
            s.toByteArray(Charset.forName("windows-1251"))
        } catch (_: Exception) {
            return null
        }
        val fixed = strictUtf8(bytes) ?: return null
        if (fixed == s) return null
        // Настоящее русское слово обратно в cp1251 не превращается — требуем кириллицу.
        if (!fixed.any { it in 'Ѐ'..'ӿ' }) return null
        return fixed
    }
}
