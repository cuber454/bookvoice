package com.cuber.bookvoice

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs

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
 * Плюс чистка НАБОРА (09.10.2026, жалоба Сержа «PDF читается рывками: чёрточки
 * идут, переносы слов дурацкие»): перенос на стыке строк и страниц, невидимые
 * мягкие переносы, разряженные колонтитулы («Ч ас ть I.» читались по буквам) и
 * мусорное название из метаданных. Разбор и цифры — docs/HISTORY.md, правила —
 * AGENTS.md, «PDF: переносы, разрядка, невидимые знаки».
 *
 * Движок — pdfbox-android. Название/автора берём из метаданных PDF (Info).
 */
object PdfParser {

    /**
     * ВЕРСИЯ ПРАВИЛ РАЗБОРА (09.10.2026, урок трёх сборок подряд).
     *
     * Поднимать КАЖДЫЙ раз, когда меняются правила чистки текста, — по этому
     * числу кэш разбора понимает, что прежний текст для книги больше не годится
     * ([BookCache]). Зачем не полагаться на память: правило разрядки правили
     * трижды (BV4, BV5, BV6), и дважды выяснялось, что книга поднимается из кэша
     * со СТАРЫМ текстом — правка есть, а человек слышит прежнее. Теперь одна
     * строка вместо трёх мест: поднял число — старый разбор уходит сам.
     *
     * 1 — 09.10.2026: разрядка склеивается по зазорам, колонтитулы по зазорам же
     *     и считаются (BV5).
     * 2 — 09.10.2026, вечер: считать и резать разрядку по СКЛЕЕННОМУ тексту
     *     (`normalizeSpaced`) — иначе склейка по зазорам ломала набор (BV7).
     * 3 — 09.10.2026, вечер: куски разряженного колонтитула сливаются по высоте
     *     строки ([PageTextStripper.flushLine]) — распознаватель отдаёт их
     *     отдельными строками по 1-3 знака.
     * 4 — 09.10.2026, вечер: колонтитул ищем как ПОВТОРЯЮЩЕЕСЯ НАЧАЛО строки, а не
     *     только на краю набора: в «Звонке для родителя» он стоит в середине
     *     верхнего поля и слипается с первой фразой текста.
     */
    const val RULES = 9

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

    // ---------------- Числа чистки набора (09.10.2026) ----------------

    /** Знаки, которыми в наборе помечают перенос в конце строки: обычный дефис,
     *  типографский, неразрывный, «мягкий» и знак переноса распознавателя. */
    private const val HYPHENS = "-\u2010\u2011\u00AD\u00AC\u2212"

    /** Невидимые знаки набора: мягкий перенос, «мягкий дефис» распознавателя,
     *  нулевой пробел, метки направления письма. В тексте их не видно, а
     *  синтезатор на них спотыкается — убираем сразу. */
    private const val INVISIBLE = "\u00AD\u00AC\u200B\u200C\u200D\u200E\u200F\uFEFF"

    /** Куски разряженного заголовка не длиннее этого — это «буквы» набора.
     *  Двойка, а не тройка (09.10.2026, книга «Звонок для родителя»): при тройке
     *  за разрядку сходило обычное начало фразы из коротких слов — «Что не так с
     *  обычной школой» теряло «Что не так с», потому что слитая строка совпадала
     *  с колонтитулом «Ч то н е т ак с …». В разрядке скана куски — буквы и пары
     *  букв, трёхбуквенное слово там уже стоит целым словом. */
    private const val SPACED_TOKEN_MAX = 2

    /** Сколько кусков подряд нужно, чтобы счесть начало строки разряженным. */
    private const val SPACED_MIN_TOKENS = 4

    /** Сколько букв должно быть в разряженном заголовке (защита от мусора). */
    private const val SPACED_MIN_LETTERS = 6

    /** Сколько знаков в строке нужно, чтобы судить о зазорах по геометрии. */
    private const val SPACED_MIN_POSITIONS = 6

    /** Во сколько раз зазор должен быть шире МЕДИАНЫ зазоров строки, чтобы
     *  считать его границей слова. В разрядке буквы стоят через 2-3 pt, а слова
     *  разделены заметно шире, поэтому медиана — это «зазор между буквами». */
    private const val SPACED_KEEP_RATIO = 1.5f

    /** Слово длиннее этого после склейки — значит, склеили лишнее: откат. */
    private const val SPACED_MAX_WORD = 30

    /** Остаток колонтитула, в котором, кроме номера главы, ничего нет. */
    private val HEAD_TAIL = Regex(
        "^(Глава|Часть|Раздел|Приложение)\\s*([IVXLC]+|\\d{1,3})\\.?$",
        RegexOption.IGNORE_CASE
    )

    // ---------------- Что из чисток включено (09.10.2026) ----------------

    /** Выключатели чисток — просьба Сержа: разбор PDF стал умнее, и на каждую
     *  правку своя галочка в опытных настройках, чтобы можно было вернуть прежнее
     *  поведение, если чистка где-то съест нужное. Устроено как у @Voice: там
     *  выключатели колонтитулов и ручка расстояния между символами. */
    class Options(
        /** Колонтитулы, номера страниц и разряженные заголовки убираем. */
        val headers: Boolean = true,
        /** Разрядку («З во но к») склеиваем ПО ГЕОМЕТРИИ зазоров. */
        val spacing: Boolean = true,
        /** Многоколоночную страницу читаем по колонкам. */
        val columns: Boolean = true,
    ) {
        /** Отпечаток набора — для кэша: сменились галочки, разбор не годится. */
        val stamp: Long
            get() = (if (headers) 1L else 0L) or
                (if (spacing) 2L else 0L) or
                (if (columns) 4L else 0L)
    }

    /** Какие чистки применять прямо сейчас. Значение ставит приложение при старте
     *  ([BookVoiceApp]) и при переключении галочек ([SettingsActivity]): разбор
     *  зовут и оттуда, где нет Context ([BookParser] берёт только имя и байты). */
    @Volatile
    var options: Options = Options()

    /** Счётчики правок: их пишем в журнал, по ним видно, что чистка сработала. */
    private class Fixes {
        /** Сколько невидимых знаков снято. */
        var invisible = 0

        /** Сколько слов склеено из переноса. */
        var glued = 0

        /** Сколько разряженных колонтитулов убрано. */
        var spacedCut = 0

        /** Сколько разряженных строк оставлено (повтора нет — похоже на заголовок). */
        var spacedLeft = 0

        /** Сколько слов дошито на границе страниц. */
        var pageGlue = 0

        /** Сколько строк склеено по геометрии зазоров (разрядка «З во но к»). */
        var spacedGlue = 0

        /** Сколько кусков разряженного колонтитула слито в одну строку (253m). */
        var fragments = 0

        /** Сколько страниц читано по колонкам и сколько строк для этого разрезано. */
        var columnsPages = 0
        var columnsSplit = 0
    }

    /** Разряженное начало строки: сколько кусков-«букв», что выходит, если убрать
     *  между ними пробелы, и все начала набора по кускам. */
    private class Spaced(
        val tokens: Int,
        val head: String,
        val prefixes: List<String>,
        /** Куски склеенного начала — по ним режем заголовок, не теряя букв. */
        val parts: List<String>,
    )

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
    fun parse(data: ByteArray): BookDocument? = tryParseDoc(options) { PDDocument.load(data) }

    /** Разбор PDF с диска (msg2619): файл не грузим в память целиком, а распакованные
     *  потоки pdfbox пишет во временные файлы в [scratchDir], а не в кучу. Большой
     *  текстовый учебник (39 МБ «Чалавек i свет») разворачивался в памяти и валил
     *  приложение OOM — теперь ложится на диск. [scratchDir] — папка приложения
     *  (cacheDir), гарантированно писучая. */
    fun parse(file: File, scratchDir: File): BookDocument? = tryParseDoc(options) {
        val mem = MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir)
        PDDocument.load(file, mem)
    }

    /** Общий каркас: грузим документ, снимаем пустую защиту, строим главы. Любой
     *  сбой — включая нехватку памяти (OutOfMemoryError — это Error, обычный catch
     *  его не ловит, и падение роняло всё приложение) — отдаём причиной, а не
     *  крашем. */
    private inline fun tryParseDoc(opts: Options, load: () -> PDDocument): BookDocument? {
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
            build(doc, n, opts)
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

    private fun build(doc: PDDocument, n: Int, opts: Options): BookDocument {
        val fix = Fixes()
        // Один проход по документу: забираем текст каждой страницы отдельно,
        // сразу вычищая то, что распознаватель сканов вписал в текст книги.
        val pageTexts = PageTextStripper(opts, fix).run {
            getText(doc)
            cleanPages(pages, fix, opts)
        }
        // Геометрия — в журнал: по этим числам видно, сработали ли склейка
        // разрядки и чтение по колонкам (09.10.2026).
        if (fix.spacedGlue > 0 || fix.columnsPages > 0) {
            Diag.log(
                "pdf",
                "геометрия разбора: разрядки склеено ${fix.spacedGlue} строк, " +
                    "страниц по колонкам ${fix.columnsPages} (строк разрезано ${fix.columnsSplit})",
            )
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
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { looksLikeTitle(it) }

        // Главы: из закладок, если те дают осмысленные разделы; иначе — страницы.
        val sections = outlineSections(doc)
        val chapters = if (sections.size >= MIN_OUTLINE_SECTIONS) {
            chaptersFromSections(sections, pageTexts, n, fix)
        } else {
            chaptersFromPages(readablePages, pageTexts)
        }

        if (chapters.isEmpty()) {
            return BookDocument(
                null, null, emptyList(), BookDocument.Unreadable.PDF_NO_TEXT_LAYER,
            )
        }
        // Последний шаг чистки: слово, разрезанное границей страницы, дошиваем на
        // место — главы-страницы уже собраны.
        val sewn = gluePageBreaks(chapters, fix)
        if (fix.invisible > 0 || fix.glued > 0 || fix.spacedCut > 0 ||
            fix.pageGlue > 0 || fix.spacedLeft > 0
        ) {
            Diag.log(
                "pdf",
                "чистка набора: переносов склеено ${fix.glued}, слов дошито на стыке " +
                    "страниц ${fix.pageGlue}, невидимых знаков снято ${fix.invisible}, " +
                    "разряженных колонтитулов убрано ${fix.spacedCut} " +
                    "(оставлено разряженных строк ${fix.spacedLeft})"
            )
        }
        return BookDocument(infoTitle, null, sewn)
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
        /** Правый край строки — нужен, чтобы увидеть колонки на странице
         *  ([layoutOrder]). Ставит распознаватель текста, −1 — не знаем. */
        var right: Float = -1f

        /** Координаты знаков строки — нужны только до сборки порядка страницы
         *  ([layoutOrder]), потом их отпускаем: страниц в книге сотни. */
        var pos: List<TextPosition>? = null

        /** Строка стоит в поле (верхнем или нижнем краю набора) с большим
         *  промежутком до соседа — по ней ищется абзац буквицы. */
        var margin = false

        /** Строка на самом краю набора — самая верхняя или самая нижняя на
         *  странице. Колонтитул и номер страницы живут именно там, причём не
         *  всегда отделены промежутком: в учебниках подпись правообладателя
         *  стоит вплотную к тексту, и «промежуток» её не выдаёт. */
        var edge = false

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

    // ---------------- Куски разряженного колонтитула (09.10.2026, сборка 253m) ----------------

    /** Знаков в куске: «З», «во», « I.» — распознаватель отдаёт их отдельными
     *  строками (проверено зондом на PDF: у всех одна высота и разные x). */
    private const val FRAGMENT_MAX = 12

    /** Насколько высоты кусков могут разойтись, чтобы считать их одной строкой. */
    private const val FRAGMENT_Y_TOL = 2f

    /** Сколько слов в начале набрано с заглавной буквы. Оставлено для справки:
     *  на телефоне регистр строк не сохраняется, и по нему судить нельзя. */
    private fun capitalWords(k: String): Int {
        var n = 0
        var atWord = true
        for (c in k) {
            if (c == ' ') {
                atWord = true
                continue
            }
            if (atWord && c.isUpperCase()) n++
            atWord = false
        }
        return n
    }

    /** Строка такой длины, повторённая на многих страницах, — колонтитул; длиннее
     *  не трогаем (это уже текст книги). */
    private const val RUNNING_HEAD_MAX_CHARS = 40

    /** Начало короче этого колонтитулом не считаем: короткие начала — это обычные
     *  слова книги («систем», «ребенк»), они повторяются часто. */
    private const val RUNNING_HEAD_MIN_CHARS = 12

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
    private fun cleanPages(pages: List<List<PdfLine>>, fix: Fixes, opts: Options): List<String> {
        // Невидимые знаки набора снимаем ДО всей разметки (09.10.2026): мягкий
        // перенос стоит внутри слова и в тексте не виден, а синтезатор его
        // читает; после снятия слово само становится целым.
        for (page in pages) for (l in page) {
            if (l.text.any { it in INVISIBLE }) {
                fix.invisible += l.text.count { it in INVISIBLE }
                l.text = l.text.filterNot { it in INVISIBLE }
            }
        }
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
            // Край набора: самая верхняя и самая нижняя строка страницы.
            if (sorted.isNotEmpty()) {
                sorted[0].edge = true
                sorted[sorted.size - 1].edge = true
                // Номер страницы стоит НАД колонтитулом, и «крайней» строкой
                // выходит он, а колонтитул — уже вторая (09.10.2026: из-за этого
                // «Ч ас ть I.» дожил до чтения вслух на 101 странице). Краем
                // считаем и строку сразу за номером — сверху и снизу.
                for (k in 1 until sorted.size) {
                    if (!isPageNumber(sorted[k - 1].norm())) break
                    sorted[k].edge = true
                }
                for (k in sorted.size - 2 downTo 0) {
                    if (!isPageNumber(sorted[k + 1].norm())) break
                    sorted[k].edge = true
                }
            }
            // Обычный левый край набора: самый левый край среди строк НЕ в поле.
            // По нему видно сдвиг строк, который оставляет буквица.
            var left = Float.MAX_VALUE
            for (l in pages[i]) if (!l.margin) left = minOf(left, l.x)
            minX[i] = if (left == Float.MAX_VALUE) 0f else left
        }

        // Тексты, стоящие на краю на многих страницах, — колонтитулы и подписи.
        val edgeCount = HashMap<String, Int>()
        for (page in pages) {
            for (l in page) {
                if (!l.edge) continue
                val t = l.norm()
                if (isPageNumber(t) || isLoneLetter(t)) continue
                edgeCount[t] = (edgeCount[t] ?: 0) + 1
            }
        }
        val limit = maxOf(3, n / 100)

        // Номера страниц — арифметическая последовательность: печатный номер
        // минус номер страницы в файле постоянен для всей книги. Берём самый
        // частый сдвиг: по нему номер узнаётся и там, где он стоит вплотную к
        // тексту, а не на самом краю (в учебнике «Человек и мир. 2 класс» так
        // и было: глава начиналась со «114.» посреди страницы).
        val offsets = HashMap<Int, Int>()
        for ((i, page) in pages.withIndex()) {
            for (l in page) {
                val t = l.norm()
                if (!l.edge || !isPageNumber(t)) continue
                val off = t.toInt() - (i + 1)
                offsets[off] = (offsets[off] ?: 0) + 1
            }
        }
        val best = offsets.maxByOrNull { it.value }
        val numOffset = best?.key ?: 0
        // Сдвигу верим, только если он подтверждён хотя бы третью страниц:
        // иначе это случайные числа, и выбрасывать по ним нельзя.
        val numOffsetTrusted = best != null && best.value * 3 >= n

        var heads = 0
        var numbers = 0
        var caps = 0
        for (i in 0 until n) {
            val page = pages[i]
            // Колонтитулы и номера страниц — под галочкой «Чистить колонтитулы»
            // (09.10.2026): если чистка где-то съест нужное, её снимают, и здесь
            // не выбрасывается ни одна строка.
            if (opts.headers) {
                for (l in page) {
                    val t = l.norm()
                    if (isPageNumber(t)) {
                        val byOffset = numOffsetTrusted && t.toInt() == i + 1 + numOffset
                        if (l.edge || byOffset) {
                            l.dropped = true
                            numbers++
                        }
                        continue
                    }
                    if (l.edge && (edgeCount[t] ?: 0) >= limit) {
                        l.dropped = true
                        heads++
                    }
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

        // НАЧАЛО, ПОВТОРЁННОЕ НА МНОГИХ СТРАНИЦАХ, — КОЛОНТИТУЛ (09.10.2026,
        // сборка 253n). Так ловится колонтитул, который лежит НЕ на краю набора:
        // в «Звонке для родителя» «Звонок для родителя» стоит в середине верхнего
        // поля (y≈384 при номере на y≈580), правила «строки в поле» его не видят
        // вовсе. Куски его распознаватель рвёт на отдельные строки, поэтому сначала
        // их сливает [PageTextStripper.flushLine]; здесь строка уже целая, но часто
        // СЛИПШАЯСЯ с первым словом текста («Звонок для родителя Давайте
        // рассмотрим…»), поэтому сравниваем НАЧАЛО строки, а не строку целиком.
        //
        // Осторожность: кандидат — короткое начало (до [RUNNING_HEAD_MAX_CHARS]
        // знаков), и повтор должен быть не меньше [runLimit] — это заметно больше,
        // чем у «строки на краю» ([limit]): настоящий колонтитул стоит на десятках
        // страниц, а повторяющаяся фраза книги столько раз не встречается. Режем по
        // САМОМУ ДЛИННОМУ совпавшему началу, чтобы не остался хвост.
        if (opts.headers) {
            // Порог повтора — ДЕСЯТАЯ ЧАСТЬ страниц: колонтитул стоит на каждой
            // странице главы, а короткое частое слово книги («систем») до такого
            // порога не дотягивает. Проверено на разобранном тексте книги: при
            // пороге n/20 в кандидатах оставались начала вроде «система », и
            // правило срезало бы живую речь.
            val runLimit = maxOf(limit, n / 10)
            // Считаем начала в НИЖНЕМ регистре: колонтитул бывает набран и
            // прописными («ЗВОНОК ДЛЯ РОДИТЕЛЯ» на титуле), и обычным набором
            // («Звонок для родителя» в верхнем поле) — это одна и та же строка.
            val cand = HashMap<String, Int>()
            for (page in pages) for (l in page) {
                if (l.dropped) continue
                val t = l.norm()
                val low = t.lowercase()
                val max = minOf(RUNNING_HEAD_MAX_CHARS, t.length)
                for (len in SPACED_MIN_LETTERS..max) {
                    val key = low.substring(0, len)
                    if (key.count { it.isLetter() } < SPACED_MIN_LETTERS) continue
                    cand[key] = (cand[key] ?: 0) + 1
                }
            }
            val found = cand.filterValues { it >= runLimit }.keys
            // Оставляем только САМЫЕ ДЛИННЫЕ из прошедших порог начал.
            val longest = found.filter { k -> found.none { it != k && it.startsWith(k) } }
            // Отсев обычной речи — по УСТРОЙСТВУ начала, а не по регистру букв.
            // Первая версия отсева смотрела на заглавные буквы, и на телефоне он не
            // работал вовсе: в тексте строк регистр не сохраняется («звонок для
            // родителя»), поэтому «слов-заглавных=0» было и у колонтитула, и у
            // речи. Признак, который виден всегда: колонтитул — это НЕСКОЛЬКО слов
            // (в начале есть пробел) и он длиннее короткого частого слова книги.
            val headsRun = longest
                .filter { k -> k.length >= RUNNING_HEAD_MIN_CHARS && k.contains(' ') }
                .sortedByDescending { it.length }
            // Отчёт в журнал — ВСЕГДА: без него не видно, искало ли правило что-то
            // вообще (первые прогоны молчали, и понять было нечем).
            val top = cand.entries.sortedByDescending { it.value }.take(3)
                .joinToString("; ") { "«${it.key.take(28)}»=${it.value}" }
            // Показываем ПОЧЕМУ отброшено длинное начало: у «звонок для родителя»
            // не было засчитано ни одной заглавной следом, и правило считало его
            // обычной речью. Без этих чисел причину не видно.
            val dbg = if (longest.isEmpty()) "" else longest.take(4).joinToString("; ") {
                "«${it.take(24)}» счёт=${cand[it]}"
            }
            Diag.log(
                "pdf",
                "начала-колонтитулы: кандидатов ${cand.size}, с повтором ${cand.count { it.value >= runLimit }} " +
                    "(порог $runLimit), самых длинных ${longest.size}, колонтитулов ${headsRun.size}. " +
                    "Частые: $top",
            )
            if (dbg.isNotEmpty()) Diag.log("pdf", "длинные начала: $dbg")
            var runDropped = 0
            if (headsRun.isNotEmpty()) {
                for (page in pages) for (l in page) {
                    if (l.dropped) continue
                    val t = l.norm()
                    val head = headsRun.firstOrNull { t.lowercase().startsWith(it) } ?: continue
                    val rest = t.substring(head.length).trim()
                    if (rest.length <= 2 || HEAD_TAIL.matches(rest)) {
                        l.dropped = true
                    } else {
                        l.text = rest
                    }
                    runDropped++
                }
            }
            for (h in headsRun.take(2)) {
                Diag.log(
                    "pdf",
                    "повторяющееся начало «${h.take(60)}» на ${cand[h]} страницах — " +
                        "убрано $runDropped мест",
                )
            }
        }

        if (heads > 0 || numbers > 0 || caps > 0) {
            Diag.log(
                "pdf",
                "чистка скана: колонтитулов $heads, номеров страниц $numbers " +
                    "(сдвиг номеров $numOffset, подтверждён ${best?.value ?: 0} стр.), " +
                    "буквиц склеено $caps, кусков колонтитула слито ${fix.fragments} " +
                    "(страниц $n)"
            )
        }
        val top = edgeCount.entries.sortedByDescending { it.value }.take(3)
            .filter { it.value >= limit }
        for (e in top) Diag.log("pdf", "колонтитул «${e.key.take(60)}» на ${e.value} страницах")

        // Разряженные колонтитулы (09.10.2026): «Ч ас ть I.», «З ач ем н уж но …?» —
        // распознаватель поставил пробел между буквами, и синтезатор читает
        // заголовок ПО БУКВАМ. Считаем такой текст без пробелов: он стоит на
        // многих страницах, значит это колонтитул, а не заглавие книги.
        // Порог тот же, что у обычных колонтитулов, — [limit]: разряженная фраза
        // книги («В VII в. до н. э.» в учебнике истории) повториться столько раз
        // не может, а колонтитул книги — может.
        //
        // ПОЧЕМУ СЧИТАЕМ ПО СКЛЕЕННОМУ ТЕКСТУ (09.10.2026, вечер, сборка 253k).
        // Сборка 253i впервые применила это правило на телефоне Сержа и… не
        // срезала ничего: «разряженных колонтитулов убрано 0 (оставлено
        // разряженных строк 1)», а в разобранном тексте «З во но к дл я р од ит ел я»
        // стоял на 120 местах. Причина: склейка разрядки по зазорам ([respace],
        // она идёт раньше) уже соединила ЧАСТЬ букв — «Звоно к дл я родителя», — и
        // куски стали длиннее двух знаков. Правило считало такие строки обычным
        // текстом. Теперь обе стороны — и счёт, и срез — идут по СКЛЕЕННОМУ
        // тексту ([normalizeSpaced]): набор перестаёт зависеть от того, как легли
        // зазоры в этой строке.
        val spaced = HashMap<String, Int>()
        if (opts.headers) {
            for (page in pages) for (l in page) {
                if (l.dropped) continue
                val run = normalizeSpaced(l.text) ?: continue
                for (key in run.prefixes) spaced[key] = (spaced[key] ?: 0) + 1
            }
        }
        if (spaced.isNotEmpty()) {
            // Колонтитул — тот, что стоит на многих страницах ([limit]); режем по
            // САМОМУ ДЛИННОМУ известному началу, чтобы не остался хвост.
            val headsSpaced = spaced.filterValues { it >= limit }.keys
                .sortedByDescending { it.length }
            if (headsSpaced.isNotEmpty()) {
                for (page in pages) for (l in page) {
                    if (l.dropped) continue
                    val run = normalizeSpaced(l.text) ?: continue
                    val head = headsSpaced.firstOrNull { run.head.startsWith(it) } ?: continue
                    if (head.length < SPACED_MIN_LETTERS) continue
                    val cut = run.findTokensFor(head) ?: continue
                    val rest = run.parts.drop(cut).joinToString(" ").trim()
                    // От колонтитула остался огрызок в знак-два («I.», «4.») — это
                    // уже не текст, строку выбрасываем.
                    if (rest.length <= 2 || HEAD_TAIL.matches(rest)) {
                        l.dropped = true
                        fix.spacedCut++
                    } else {
                        l.text = rest
                        fix.spacedCut++
                    }
                }
            }
            for (page in pages) for (l in page) {
                if (!l.dropped && normalizeSpaced(l.text) != null) fix.spacedLeft++
            }
            // В журнал — только САМЫЕ ДЛИННЫЕ найденные колонтитулы: в наборе
            // ключей теперь есть и все их начала, и «Звонокдл» рядом с
            // «Звонокдляродителя» читать в журнале бессмысленно.
            val found = spaced.keys.filter { (spaced[it] ?: 0) >= limit }
            val longest = found.filter { k -> found.none { it != k && it.startsWith(k) } }
            for (k in longest.sortedByDescending { spaced[it] ?: 0 }.take(3)) {
                Diag.log("pdf", "разряженный колонтитул «${k.take(60)}» на ${spaced[k]} страницах")
            }
            // Ничего не срезали, а разряженные строки в тексте остались — в
            // журнал ключи и пример строки: по ним видно, почему правило молчит.
            if (fix.spacedCut == 0 && fix.spacedLeft > 0) {
                val sample = pages.asSequence().flatten().firstOrNull { !it.dropped }?.norm() ?: ""
                Diag.log(
                    "pdf",
                    "разряженный текст не срезан: ключей ${spaced.size}, " +
                        "с повтором ${spaced.count { it.value >= limit }} " +
                        "(до ${spaced.values.maxOrNull() ?: 0} стр.), строк без повтора ${fix.spacedLeft}; " +
                        "строка «${sample.take(80)}»",
                )
            }
        }

        // Строки страницы — в один поток. Стык строк — единственное место, где
        // видно перенос, поэтому склейка живёт здесь. Порядок строк уже собран по
        // колонкам — это сделал распознаватель ([PageTextStripper.endPage]).
        return pages.map { page ->
            glueLines(page.filter { !it.dropped }.map { it.norm() }, fix)
        }
    }

    // ---------------- Порядок чтения на многоколоночной странице ----------------

    /** Шаг поиска полосы по ширине страницы, точки. */
    private const val COLUMN_STEP = 2f

    /** Сколько строк на странице нужно, чтобы вообще искать колонки. */
    private const val COLUMN_MIN_LINES = 12

    /** Полоса пустого места посередине: уже этого — не полоса, а отступ. */
    private const val COLUMN_MIN_GUTTER = 10f

    /** Сколько строк должно быть с каждой стороны, чтобы считать это колонками. */
    private const val COLUMN_MIN_SIDE = 5

    /** Зазор такого размера внутри полосы — граница колонок, а не пробел. */
    private const val COLUMN_SPLIT_GAP_MIN = 3f

    /**
     * Порядок чтения страницы: если посередине страницы есть широкая полоса
     * пустого места, читаем ЛЕВУЮ колонку до конца, потом ПРАВУЮ (09.10.2026, как
     * «сложный анализ макета» в @Voice: у них это отдельная галочка, которая сама
     * находит текстовые области и порядок чтения).
     *
     * Почему одной перестановкой не обойтись: PDFTextStripper склеивает строки
     * ЛЕВОЙ и ПРАВОЙ колонки в одну строку — у них общая высота. Поэтому строку,
     * пересёкшую полосу, сначала РАЗРЕЗАЕМ на две (если внутри полосы есть
     * широкий разрыв) или оставляем целой (заголовок во всю ширину).
     *
     * Полосы нет — порядок НЕ трогаем: в обычной книге он и так верный, а лишняя
     * перестановка — риск. Возврат null означает «оставь как было».
     */
    private fun layoutOrder(page: List<PdfLine>, fix: Fixes): List<PdfLine>? {
        if (page.size < COLUMN_MIN_LINES) return null
        for (l in page) {
            if (l.y < 0f || l.x < 0f || l.pos.isNullOrEmpty()) return null
        }
        var minX = Float.MAX_VALUE
        var maxX = -1f
        for (l in page) for (p in l.pos!!) {
            minX = minOf(minX, p.xDirAdj)
            maxX = maxOf(maxX, p.xDirAdj + p.widthDirAdj)
        }
        if (maxX - minX < COLUMN_MIN_GUTTER * 6f) return null

        // Покрытие по ширине: полоса — самая широкая пустая середина страницы.
        val buckets = ((maxX - minX) / COLUMN_STEP).toInt() + 2
        val used = BooleanArray(buckets)
        for (l in page) for (p in l.pos!!) {
            val a = ((p.xDirAdj - minX) / COLUMN_STEP).toInt().coerceIn(0, buckets - 1)
            val b = ((p.xDirAdj + p.widthDirAdj - minX) / COLUMN_STEP).toInt().coerceIn(0, buckets - 1)
            for (k in a..b) used[k] = true
        }
        val lo = (buckets * 0.2f).toInt().coerceIn(0, buckets - 1)
        val hi = (buckets * 0.8f).toInt().coerceIn(lo, buckets - 1)
        var bestLen = 0
        var bestA = -1
        var bestB = -1
        var runA = -1
        for (k in lo..hi) {
            if (!used[k]) {
                if (runA < 0) runA = k
            } else if (runA >= 0) {
                if (k - runA > bestLen) {
                    bestLen = k - runA
                    bestA = runA
                    bestB = k
                }
                runA = -1
            }
        }
        if (runA >= 0 && hi - runA > bestLen) {
            bestLen = hi - runA
            bestA = runA
            bestB = hi
        }
        if (bestLen * COLUMN_STEP < COLUMN_MIN_GUTTER) return null
        val mid = minX + (bestA + bestB) / 2f * COLUMN_STEP
        val gutA = minX + bestA * COLUMN_STEP
        val gutB = minX + bestB * COLUMN_STEP

        val left = ArrayList<PdfLine>(page.size)
        val right = ArrayList<PdfLine>(page.size)
        val full = ArrayList<PdfLine>(4)
        var split = 0
        for (l in page) {
            when {
                l.right <= mid -> left.add(l)
                l.x >= mid -> right.add(l)
                else -> split += splitLine(l, gutA, gutB, left, right, full)
            }
        }
        if (left.size < COLUMN_MIN_SIDE || right.size < COLUMN_MIN_SIDE) return null
        // Полноширинных строк много — это, скорее, врезка или таблица, не колонки.
        if (full.size * 6 > page.size) return null
        val lMin = left.minOf { it.y }
        val lMax = left.maxOf { it.y }
        val rMin = right.minOf { it.y }
        val rMax = right.maxOf { it.y }
        // Колонки должны стоять рядом по высоте: иначе это сдвинутый блок.
        if (minOf(lMax, rMax) - maxOf(lMin, rMin) <= 0f) return null

        // Левая колонка целиком, потом правая; полноширинные строки — по своей
        // высоте (заголовок над колонками остаётся сверху).
        left.sortBy { it.y }
        right.sortBy { it.y }
        full.sortBy { it.y }
        val out = ArrayList<PdfLine>(page.size)
        var li = 0
        var ri = 0
        for (f in full) {
            while (li < left.size && left[li].y <= f.y) out.add(left[li++])
            while (ri < right.size && right[ri].y <= f.y) out.add(right[ri++])
            out.add(f)
        }
        while (li < left.size) out.add(left[li++])
        while (ri < right.size) out.add(right[ri++])

        fix.columnsPages++
        fix.columnsSplit += split
        Diag.log(
            "pdf",
            "страница в колонки: полоса ${(bestLen * COLUMN_STEP).toInt()} pt, " +
                "слева ${left.size}, справа ${right.size}, разрезано строк $split",
        )
        return out
    }

    /** Строка, пересёкшая полосу. Есть внутри полосы ШИРОКИЙ разрыв — это
     *  слипшиеся колонки, режем на две строки; разрыва нет — заголовок во всю
     *  ширину, оставляем целым. Возврат: сколько строк разрезано (0, 1 или 2). */
    private fun splitLine(
        l: PdfLine,
        gutA: Float,
        gutB: Float,
        left: MutableList<PdfLine>,
        right: MutableList<PdfLine>,
        full: MutableList<PdfLine>,
    ): Int {
        val ps = l.pos ?: run { full.add(l); return 0 }
        var cut = -1
        var bestGap = 0f
        for (i in 1 until ps.size) {
            val a = ps[i - 1]
            val b = ps[i]
            val gap = b.xDirAdj - (a.xDirAdj + a.widthDirAdj)
            val at = (a.xDirAdj + a.widthDirAdj + b.xDirAdj) / 2f
            if (at >= gutA - 4f && at <= gutB + 4f && gap > bestGap) {
                bestGap = gap
                cut = i
            }
        }
        val space = spaceWidth(ps)
        if (cut < 0 || bestGap < maxOf(COLUMN_SPLIT_GAP_MIN * space, COLUMN_MIN_GUTTER)) {
            full.add(l)
            return 0
        }
        val a = PdfLine(l.y, l.x, buildText(ps.subList(0, cut)))
        a.right = ps[cut - 1].xDirAdj + ps[cut - 1].widthDirAdj
        // Разрезанные куски читаются как обычный текст: разрядку в них не ищем
        // (её и так снял первый проход), поэтому координат знаков не держим.
        val b = PdfLine(l.y, ps[cut].xDirAdj, buildText(ps.subList(cut, ps.size)))
        b.right = l.right
        var n = 0
        if (a.text.isNotBlank()) {
            left.add(a)
            n++
        }
        if (b.text.isNotBlank()) {
            right.add(b)
            n++
        }
        return n
    }

    /** Текст из знаков строки: пробел там, где зазор шире 0,6 ширины пробела. */
    private fun buildText(ps: List<TextPosition>): String {
        if (ps.isEmpty()) return ""
        val space = spaceWidth(ps)
        val sb = StringBuilder(ps.size + 8)
        for (i in ps.indices) {
            val u = ps[i].unicode ?: continue
            if (i > 0 && space > 0f) {
                val prev = ps[i - 1]
                val gap = ps[i].xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                if (gap > space * 0.6f && sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
            }
            if (u.isBlank()) {
                if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
            } else {
                sb.append(u)
            }
        }
        return sb.toString().trim()
    }

    /** Медиана набора чисел: по ней видно «обычный» зазор строки. */
    private fun median(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        val c = a.clone()
        c.sort()
        return c[c.size / 2]
    }

    /** Ширина пробела шрифта строки: берём медиану по знакам (у части знаков она
     *  нулевая или не посчитана). */
    private fun spaceWidth(ps: List<TextPosition>): Float {
        val w = ArrayList<Float>(ps.size)
        for (p in ps) {
            val s = p.widthOfSpace
            if (s > 0f && !s.isNaN()) w.add(s)
        }
        if (w.isEmpty()) return 0f
        w.sort()
        return w[w.size / 2]
    }

    // ---------------- Перенос, разрядка и прочий мусор набора ----------------

    /** Склеить нормализованные строки (или страницы) в один поток, убирая перенос
     *  на стыке: «качествен- ное» становится «качественное» (09.10.2026).
     *
     *  Правило: дефис ПРИКЛЕЕН к последней букве — значит, это перенос слова, а
     *  не тире (тире стоит отдельно, через пробелы, и под правило не попадает), а
     *  следующая строка начинается с буквы. Тогда убираем пробел, а дефис — если
     *  дальше идёт СТРОЧНАЯ буква: так набран перенос («качествен-» + «ное»).
     *  Заглавная или цифра после дефиса — это, скорее, своё слово с дефисом
     *  («интернет-» + «Магазин», «1-й», «5-6»): дефис оставляем, убираем только
     *  пробел. */
    private fun glueLines(parts: List<String>, fix: Fixes): String {
        val sb = StringBuilder()
        for (t in parts) {
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                if (cutWord(sb) || cutRange(sb)) {
                    if (cutWord(sb) && t[0].isLowerCase()) sb.setLength(sb.length - 1)
                    fix.glued++
                } else {
                    sb.append(' ')
                }
            }
            sb.append(t)
        }
        return sb.toString()
    }

    /** Дефис приклеен к последней букве строки — это перенос слова. */
    private fun cutWord(sb: StringBuilder): Boolean =
        sb.length >= 2 && sb[sb.length - 1] in HYPHENS && sb[sb.length - 2].isLetter()

    /** Дефис приклеен к последней цифре («1-й», «5-6») — дефис оставляем. */
    private fun cutRange(sb: StringBuilder): Boolean =
        sb.length >= 2 && sb[sb.length - 1] in HYPHENS && sb[sb.length - 2].isDigit()

    /** Разряженное начало строки: сколько кусков-«букв» и что выходит, если убрать
     *  между ними пробелы. null — строка выглядит обычно. */
    /**
     * Разряженное начало строки, приведённое к СКЛЕЕННОМУ виду (09.10.2026, вечер).
     *
     * Зачем так, а не по исходным пробелам: склейка по зазорам ([respace]) уже
     * соединила часть букв — «Звоно к дл я родителя». По исходному тексту куски
     * длиннее двух знаков, и правило разрядки считало такую строку обычным
     * текстом — колонтитул доживал до чтения вслух (сборка 253i: срезано 0, а в
     * разобранном тексте «З во но к…» стоял на 120 местах). Здесь склейка одна и
     * та же для счёта и для среза, поэтому набор не зависит от того, как легли
     * зазоры в этой строке.
     *
     * null — строка выглядит обычным текстом (первое же слово длиннее двух знаков)
     * либо в ней нет и шести букв.
     */
    private fun normalizeSpaced(raw: String): Spaced? {
        val toks = raw.trim().split(' ').filter { it.isNotEmpty() }
        var n = 0
        val sb = StringBuilder(32)
        for (t in toks) {
            if (t.length > SPACED_TOKEN_MAX) break
            // Цифра внутри куска — это уже не разрядка, а номер страницы или главы,
            // приклеенный к колонтитулу («…р од ит ел я 46», «…я Глава 7.»). Набор
            // кончаем на цифре, а сам номер из текста НЕ вырезаем: за ним идёт
            // живая фраза («…я 59% детей в России…»).
            if (t.any { it.isDigit() }) break
            sb.append(t)
            n++
        }
        if (n < SPACED_MIN_TOKENS || sb.count { it.isLetter() } < SPACED_MIN_LETTERS) return null
        val head = sb.toString()
        // Все начала набора: колонтитул часто слипается со следующим коротким
        // словом текста («…о д ит ел я на…»), и по целому ключу он не набрал бы
        // повторов. Считаем каждое начало, режем по самому длинному.
        val prefixes = ArrayList<String>(n)
        val acc = StringBuilder(32)
        for (i in 0 until n) {
            acc.append(toks[i])
            if (i + 1 >= SPACED_MIN_TOKENS && acc.count { it.isLetter() } >= SPACED_MIN_LETTERS) {
                prefixes.add(acc.toString())
            }
        }
        return Spaced(n, head, prefixes, toks.subList(0, n))
    }

    /**
     * Сколько первых кусков покрывают ровно [head] знаков (null — не сходится:
     * заголовок кончается посреди куска, резать по нему нельзя). Считаем по тем же
     * кускам, что запомнил [normalizeSpaced]: иначе срез съест лишнюю букву.
     */
    private fun Spaced.findTokensFor(head: String): Int? {
        var len = 0
        for (i in parts.indices) {
            len += parts[i].length
            if (len == head.length) return i + 1
            if (len > head.length) return null
        }
        return null
    }


    /** Дошить слово, разрезанное границей страницы (09.10.2026): в конце страницы
     *  остаётся «…для социа-», а продолжение («лизации нужно…») начинает
     *  следующую. Главы без закладок строятся ПОСТРАНИЧНО, поэтому стыков ровно
     *  столько, сколько страниц. Признак продолжения — первое слово начинается
     *  со СТРОЧНОЙ буквы: ни заголовок, ни номер страницы так не начинается. */
    private fun gluePageBreaks(chapters: List<Chapter>, fix: Fixes): ArrayList<Chapter> {
        val out = ArrayList<Chapter>(chapters.size)
        val lists = ArrayList<MutableList<Sentence>>(chapters.size)
        for (ch in chapters) {
            val sents = ArrayList(ch.sentences)
            lists.add(sents)
            out.add(Chapter(ch.title, sents, ch.major, ch.nested, ch.autoTitle))
        }
        for (i in 0 until lists.size - 1) {
            val a = lists[i]
            val b = lists[i + 1]
            if (a.isEmpty() || b.isEmpty()) continue
            val la = a[a.size - 1]
            val fb = b[0]
            val text = la.text
            if (text.length < 2) continue
            if (text[text.length - 1] !in HYPHENS || !text[text.length - 2].isLetter()) continue
            val word = fb.text.takeWhile { it.isLetter() }
            if (word.isEmpty() || word.length > 12 || !word[0].isLowerCase()) continue
            val rest = fb.text.substring(word.length)
            // Продолжения нет — на строке только слово: тащить его из главы в
            // главу нельзя, там оно и останется.
            if (rest.isEmpty()) continue
            a[a.size - 1] = la.copy(text = text.dropLast(1) + word)
            b[0] = fb.copy(text = rest.trimStart())
            fix.pageGlue++
        }
        return out
    }

    /** Название из метаданных PDF: там попадается мусор — «(=^..^=)» вместо
     *  названия книги (09.10.2026, «Звонок для родителя»). Требуем хотя бы три
     *  буквы и половину знаков — буквы или цифры. Не прошло — названия нет, и
     *  берётся имя файла. */
    private fun looksLikeTitle(t: String): Boolean {
        if (t.count { it.isLetter() } < 3) return false
        return t.count { it.isLetterOrDigit() } * 2 >= t.length
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
        fix: Fixes,
    ): ArrayList<Chapter> {
        val out = ArrayList<Chapter>(sections.size)
        for (i in sections.indices) {
            val start = if (i == 0) 0 else sections[i].second
            val end = if (i + 1 < sections.size) sections[i + 1].second else n
            val text = joinPages(pageTexts, start, end, fix)
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

    /** Склеить текст страниц диапазона в один поток: страницы — через пробел,
     *  чтобы предложение, переходящее через границу страницы, не рвалось, а
     *  перенос на стыке страниц убирает [glueLines] (09.10.2026). */
    private fun joinPages(pageTexts: List<String>, from: Int, to: Int, fix: Fixes): String =
        glueLines(
            (from until to).map { i ->
                pageTexts.getOrElse(i) { "" }.replace(Regex("\\s+"), " ").trim()
            },
            fix,
        )

    // ---------------- Извлечение текста по страницам ----------------

    /** Один проход по документу: [pages] — текст каждой страницы по порядку.
     *  Перехватываем стандартный вывод PDFTextStripper в буфер на страницу.
     *  Заодно держим КООРДИНАТЫ каждого знака строки: по ним [respace] склеивает
     *  разрядку, а [layoutOrder] видит колонки. */
    private class PageTextStripper(
        private val opts: Options,
        private val fix: Fixes,
    ) : PDFTextStripper() {
        private val line = StringBuilder()
        private var lineY = Float.NaN
        private var lineX = Float.NaN
        private var lineRight = Float.NaN
        private val pageLines = ArrayList<PdfLine>()

        /** Знаки текущей строки — в том порядке, как их отдал распознаватель. */
        private var linePositions = ArrayList<TextPosition>()

        /** Строки каждой страницы с координатами: по ним [cleanPages] ищет
         *  колонтитулы, номера страниц и буквицы. */
        val pages = ArrayList<List<PdfLine>>()

        override fun startPage(page: PDPage) {
            pageLines.clear()
            clearLine()
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
                linePositions.addAll(textPositions)
                for (t in textPositions) {
                    val r = t.xDirAdj + t.widthDirAdj
                    if (lineRight.isNaN() || r > lineRight) lineRight = r
                }
            }
            line.append(text)
        }

        override fun writeString(text: String) {
            line.append(text)
        }

        override fun endPage(page: PDPage) {
            flushLine()
            // Порядок чтения по колонкам — ПОСЛЕ сбора страницы: полосу видно
            // только по всем строкам сразу (09.10.2026).
            val ordered = if (opts.columns) layoutOrder(pageLines, fix) ?: pageLines else pageLines
            // Координаты знаков держим только до сборки порядка: страниц в книге
            // сотни, и хранить их все незачем.
            for (l in ordered) l.pos = null
            // ВАЖНО: кладём КОПИЮ списка. Без неё `pageLines.clear()` ниже опустошал
            // бы уже отданную страницу — и вся книга выходила пустой («в PDF нет
            // извлекаемого текста»), на этом сломалась сборка 253g.
            pages.add(ArrayList(ordered))
            pageLines.clear()
        }

        private fun clearLine() {
            line.setLength(0)
            linePositions = ArrayList()
            lineY = Float.NaN
            lineX = Float.NaN
            lineRight = Float.NaN
        }

        private fun flushLine() {
            val raw = line.toString()
            // Разрядка по зазорам — только когда знаки есть и чистка включена.
            val t = if (opts.spacing) respace(raw, linePositions) else raw
            val y = if (lineY.isNaN()) -1f else lineY
            val x = if (lineX.isNaN()) -1f else lineX
            // СЛИЯНИЕ КУСКОВ РАЗРЯЖЕННОГО КОЛОНТИТУЛА (09.10.2026, сборка 253m).
            // У этой книги распознаватель отдаёт колонтитул НЕ строкой, а кусками
            // по одной-две буквы, и каждый кусок — отдельным вызовом с переносом
            // строки: «З» | «во» | «но» | «к » … Проверено зондом на самом PDF
            // (work/pdfgeo/YProbe.java): у всех кусков одна высота (y=384.24) и
            // разные x, а длина куска — 1-3 знака (« I.»). Поэтому склеиваем
            // соседние строки с ТОЙ ЖЕ высотой, пока длина набранного не перевалит
            // за [FRAGMENT_MAX]: настоящая короткая строка книги («Глава 1.») сюда
            // не попадает, а если попадёт — правило разрядки её не тронет, там нет
            // повтора по страницам.
            if (t.isNotBlank() && opts.headers) {
                val prev = pageLines.lastOrNull()
                if (prev != null && prev.y >= 0f && y >= 0f && abs(prev.y - y) < FRAGMENT_Y_TOL &&
                    (t.trim().length <= FRAGMENT_MAX ||
                        (prev.text.trim().length + t.trim().length) <= FRAGMENT_MAX)
                ) {
                    prev.text += t
                    val r = if (lineRight.isNaN()) -1f else lineRight
                    if (r > 0f && r > prev.right) prev.right = r
                    fix.fragments++
                    clearLine()
                    return
                }
            }
            if (t.isNotBlank()) {
                val l = PdfLine(y, x, t)
                l.right = if (lineRight.isNaN()) -1f else lineRight
                l.pos = linePositions
                pageLines.add(l)
            }
            clearLine()
        }

        /** Склеить разрядку ПО ГЕОМЕТРИИ (09.10.2026, по образцу @Voice: у них для
         *  этого ручка «минимальное расстояние между символами для разделения
         *  слов»). Распознаватель ставит пробел там, где зазор между знаками
         *  больше примерно 0,3 ширины знака, — в разряженном наборе («З во но к
         *  дл я р од ит ел я») пробел встаёт между буквами, и синтезатор читает
         *  слово по буквам.
         *
         *  Делаем так: считаем зазоры между соседними знаками строки. В обычном
         *  тексте знаки стоят вплотную (медиана зазора около нуля) — такую строку
         *  не трогаем вовсе. Если медиана заметная, значит буквы раздвинуты, и
         *  тогда склеиваем всё, что уже 1,5 медианы: широкие зазоры — границы
         *  слов. Не сошлось (после склейки пробелов не убавилось, вышло слишком
         *  длинное слово) — возвращаем строку как была. */
        private fun respace(raw: String, ps: List<TextPosition>): String {
            if (ps.size < SPACED_MIN_POSITIONS) return raw
            val space = spaceWidth(ps)
            if (space <= 0f) return raw
            val gaps = FloatArray(ps.size - 1)
            var letters = 0
            var glyphW = 0f
            for (i in 1 until ps.size) {
                val prev = ps[i - 1]
                gaps[i - 1] = ps[i].xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
            }
            for (p in ps) {
                glyphW += p.widthDirAdj
                val u = p.unicode
                if (u != null && u.length == 1 && u[0].isLetter()) letters++
            }
            glyphW /= ps.size
            if (glyphW <= 0f) return raw
            val med = median(gaps)
            // Знаки стоят вплотную — обычный текст, разрядки нет.
            if (med < 0.12f * glyphW) return raw
            // Разрядка — это слово ЦЕЛЫМИ БУКВАМИ: строки из точек и линеек
            // (оглавление «От авторов ……… 3») под правило не подходят.
            if (letters * 5 < ps.size * 3) return raw
            val keep = med * SPACED_KEEP_RATIO
            val sb = StringBuilder(ps.size + 8)
            for (i in ps.indices) {
                val u = ps[i].unicode ?: continue
                if (u.isBlank()) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
                    continue
                }
                if (i > 0 && gaps[i - 1] > keep && sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                    sb.append(' ')
                }
                sb.append(u)
            }
            val out = sb.toString()
            // Защита от склейки всего подряд: слово такой длины — уже не слово.
            if (out.split(' ').any { it.length > SPACED_MAX_WORD }) return raw
            // Разрядка даёт МЕНЬШЕ пробелов, чем было. Не меньше — значит,
            // строку не разряжали, и трогать её нечем.
            if (out.count { it == ' ' } >= raw.count { it == ' ' }) return raw
            if (out.isBlank()) return raw
            fix.spacedGlue++
            return out
        }

        /** Ширина пробела шрифта строки: берём медиану по знакам (у части знаков
         *  она нулевая или не посчитана). */
        private fun spaceWidth(ps: List<TextPosition>): Float {
            val w = ArrayList<Float>(ps.size)
            for (p in ps) {
                val s = p.widthOfSpace
                if (s > 0f && !s.isNaN()) w.add(s)
            }
            if (w.isEmpty()) return 0f
            w.sort()
            return w[w.size / 2]
        }
    }
}
