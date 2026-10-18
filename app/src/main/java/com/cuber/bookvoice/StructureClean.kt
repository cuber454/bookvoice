package com.cuber.bookvoice

/**
 * ОБЩИЙ ШАГ РАЗБОРА КНИГИ — один код на все форматы (15.10.2026).
 *
 * Зачем. Раньше чистка была вшита внутрь каждого парсера: PDF чистился глубоко
 * (геометрия, разрядка, колонки, колонтитулы — [PdfParser]), а fb2, epub, txt и
 * docx шли по разметке почти без чистки. Из-за этого в криво сконвертированной
 * книге синтезатор читал вслух «стр. 15», «downloaded from», линейки из чёрточек
 * и «звёздочка звёздочка звёздочка» на разделителях сцен, а главы находились
 * только там, где их разместил сам файл. Теперь ЛЮБАЯ книга после разбора
 * проходит через этот один шаг — как у читалки Reciter (разбор их правил —
 * `docs/BACKLOG.md`, раздел «Что взять у читалки Reciter»).
 *
 * Что делает (по частям, каждую можно выключить галочкой в опытных настройках,
 * см. [ParsePrefs.parts]):
 *  1. УБИРАЕТ МУСОРНЫЕ СТРОКИ: «стр. 12», линейку из чёрточек, «downloaded
 *     from»/«скачано с», строку только из адреса, строку doi. Берём только
 *     КОРОТКИЕ строки ([JUNK_MAX]) — иначе под шаблон мог бы попасть кусок
 *     настоящего текста. ОДИНОКИЙ НОМЕР («12») убираем с оговоркой: такие строки
 *     бывают и номерами страниц, и СПИСКОМ ПРИМЕЧАНИЙ — различаем по шагу между
 *     ними ([NOTE_GAP]); на книгах Сержа «Блокада» и «Омский альманах» это
 *     оказались ровно примечания, и они остаются на месте.
 *  2. УБИРАЕТ РАЗДЕЛИТЕЛИ СЦЕН («***», «* * *», «• • •», «— — —»): вслух это
 *     читается словами и только мешает. Пауза на месте разделителя остаётся
 *     естественной — по границе соседних абзацев.
 *  3. СКЛЕИВАЕТ РВАНЫЕ СТРОКИ: строка, оборванная посреди фразы (нет знака
 *     конца, следующая начинается со строчной буквы), читается вместе со
 *     следующей; перенос с дефисом склеивается без дефиса и без пробела — тем же
 *     правилом, что давно работает в [PdfParser]. СТИХИ НЕ ТРОГАЕМ: если обе
 *     строки короткие, склейки нет (в стихах каждая строка короткая и без знака
 *     конца, склейка слила бы стихотворение в одну фразу).
 *  4. ИЩЕТ ГЛАВЫ — только там, где их нет: если в книге МЕНЬШЕ [TRUSTED_CHAPTERS]
 *     глав со своим названием, ищем строки вида «Глава 7», «Часть II», «Пролог»,
 *     «Эпилог» и делаем их началом главы. Правило «своя разметка есть — своей не
 *     выдумываем» взято у Reciter: у них на этом ломалось оглавление (реплики
 *     героев капсом попадали в главы).
 *
 * Чего шаг НЕ делает (сознательно): не переписывает текст по смыслу (это уже
 * задача модели, фаза 6.5), не расставляет ударения, не трогает картинки и
 * сноски, не склеивает абзацы между собой.
 *
 * ВАЖНО ПРО ПОЗИЦИИ. Разбор меняет число предложений, а на их позициях держатся
 * место чтения, метки, сноски и картинки. Поэтому шаг зовётся ОДИН раз — при
 * разборе книги, ДО записи в кэш ([BookCache.put]): дальше книга всюду идёт уже
 * разобранной, и позиции никуда не съезжают. Кэш привязан к отпечатку настроек
 * разбора ([ParsePrefs.stamp]) и к метке формата ([BookCache.MAGIC]), поэтому
 * после смены правил или галочек книга разберётся заново сама.
 */
object StructureClean {

    /** Что включено у человека. Все три части по умолчанию включены. */
    data class Options(
        val junk: Boolean = true,
        val merge: Boolean = true,
        val headings: Boolean = true,
    )

    /** Что шаг сделал — только для журнала ([Diag]); в интерфейсе чисел нет
     *  (решение Сержа 15.10.2026: «нужна ли нам статистика» — не нужна). */
    data class Stats(
        var junkRemoved: Int = 0,
        var separatorsRemoved: Int = 0,
        var merged: Int = 0,
        var headingsFound: Int = 0,
        var ms: Long = 0L,
    ) {
        fun toLog(): String =
            "разбор структуры: мусора $junkRemoved, разделителей $separatorsRemoved, " +
                "склеено $merged, глав найдено $headingsFound, ${ms} мс"
    }

    /** Результат шага: новая книга, счётчики и КАРТА МЕСТА ([remap]).
     *
     *  [remap] переводит место чтения из СТАРЫХ координат (глава, предложение) в
     *  новые. Зачем: чистка выбрасывает строки, и место, сохранённое прежним
     *  разбором, уехало бы вперёд ровно на число выброшенных строк — у книги с
     *  сотней мусорных строк человек оказался бы далеко от своего места. null
     *  означает «структура глав изменилась» (мы искали главы сами, главы
     *  появились или исчезли): честно пересчитать место в этом случае нельзя, и
     *  лучше не трогать его вовсе, чем поставить наугад. */
    data class Result(
        val doc: BookDocument,
        val stats: Stats,
        val remap: ((Int, Int) -> Pair<Int, Int>?)? = null,
    )

    /** Мусорные строки. Шаблоны те же, что у Reciter (`StructuralAnalyzer`), плюс
     *  наши: их набор и порядок разобран в `docs/BACKLOG.md`. Одинокий номер
     *  страницы здесь НЕ лежит: у него своя проверка ([dropJunk]) — в книге такие
     *  же строки бывают СПИСКОМ ПРИМЕЧАНИЙ. */
    private val JUNK = listOf(
        Regex("^(стр|стр\\.|страница|page|pg)\\s*\\.?\\s*\\d+$", RegexOption.IGNORE_CASE),
        Regex("^[-=_~]{3,}$"),
        Regex("^(downloaded from|скачано с|взято с|источник)\\b.{0,80}$", RegexOption.IGNORE_CASE),
        Regex("^<?https?://\\S+>?$", RegexOption.IGNORE_CASE),
        Regex("^doi:\\s*10\\.\\S+$", RegexOption.IGNORE_CASE),
        // 16.10.2026 (сборка 264x): ЯРЛЫКИ ПЕЧАТНОГО БЛОКА — «Форзац 2»,
        // «Нахзац 1». В учебнике «Человек и мир» первой фразой книги шло
        // «Форзац 2. П. С. Лопух О. В.» — служебная надпись с форзаца, и читать
        // её человеку незачем.
        // 17.10.2026: разобраны варианты, которых первая версия не ловила —
        // номер римский КИРИЛЛИЦЕЙ («Форзац ІІ»), без точки после номера
        // («Форзац 2 П. С. Лопух О. В.»), с запятой или скобкой. Фразу, которая
        // НАЧИНАЕТСЯ со слова «Форзац» и продолжается словами («Форзац — это
        // внутренняя сторона обложки»), правило по-прежнему не трогает: номер
        // обязателен. */
        Regex("^(форзац|нахзац|обложка)\\s*(\\d{1,2}|[IVXІі]{1,4})?[\\s.,)]*$", RegexOption.IGNORE_CASE),
        Regex("^(форзац|нахзац)\\s*(\\d{1,2}|[IVXІі]{1,4})[\\s.,)].{0,60}$", RegexOption.IGNORE_CASE),
    )

    /** Одинокий номер страницы: «12», «- 12 -». */
    private val PAGE_NUMBER = Regex("^[-—–\\s]*\\d{1,4}[-—–\\s]*$")

    /** Разделитель сцены: три и больше знаков-«звёздочек», точек, тире, пробелов. */
    private val SEPARATOR = Regex("^[\\s*•·∙—–-]{3,}$")

    /** Средний шаг между одинокими номерами, при котором считаем их СПИСКОМ
     *  ПРИМЕЧАНИЙ, а не номерами страниц: примечание идёт через строку текста
     *  (`1`, текст, `2`, текст…), а номер страницы — раз в десятки абзацев.
     *  Проверено на книгах Сержа 15.10.2026: в «Блокаде» и «Омском альманахе»
     *  такие номера — примечания (шаг ровно 2), и вырезать их нельзя: без номера
     *  примечание теряет связь со сноской. */
    private const val NOTE_GAP = 4

    /** Меньше трёх одиноких номеров — не трогаем вовсе: на двух строках ничего
     *  не докажешь, а ошибка стоила бы текста. */
    private const val PAGE_NUMBERS_MIN = 3

    /** Начало заголовка главы. Только эти слова: иначе под «главу» попадёт
     *  обычная короткая строка вроде «Он вернулся». */
    private val HEADING = Regex(
        "^(глава|часть|раздел|книга|пролог|эпилог|приложение|chapter|part|prologue|epilogue)\\b.{0,60}$",
        RegexOption.IGNORE_CASE,
    )

    /** Знаки конца фразы: после них строка не «рваная». */
    private const val TERMINAL = ".!?…:;»\")"

    /** Мусорной считаем только короткую строку: длинная строка с текстом под
     *  шаблон попасть не должна. */
    private const val JUNK_MAX = 90

    /** Сколько глав со своим названием считаем настоящей разметкой. Меньше —
     *  ищем главы сами (правило Reciter: `TRUSTED_STRUCTURE_MIN = 3`). */
    private const val TRUSTED_CHAPTERS = 3

    /** Границы защиты стихов: если ОБЕ строки не длиннее этого — не склеиваем. */
    private const val VERSE_MAX = 45

    /** Короче этого строку не считаем оборванной фразой: это может быть
     *  заголовок, подпись или стихотворная строка. */
    private const val MERGE_MIN = 20

    /** Как часто сюда приходят: одна книга — один вызов. */
    fun clean(doc: BookDocument, opts: Options): Result {
        val t0 = System.currentTimeMillis()
        val stats = Stats()
        // По каждой старой главе — карта «старый номер предложения → новый».
        // Собираем её ПО ХОДУ чистки: потом по ней пересчитывается место чтения.
        val moves = ArrayList<IntArray>(doc.chapters.size)
        val chapters = ArrayList<Chapter>(doc.chapters.size)
        for (ch in doc.chapters) {
            val oldSize = ch.sentences.size
            // src — для каждого уцелевшего предложения номера исходных, из которых
            // оно собрано: склейка объединяет два и больше, и все они должны
            // указывать на одно новое место.
            var sentences: List<Sentence> = ch.sentences
            var src: List<List<Int>> = ch.sentences.indices.map { listOf(it) }
            if (opts.junk) {
                val r = dropJunk(sentences, stats)
                sentences = r.first
                src = r.second.map { listOf(it) }
            }
            if (opts.merge) {
                val r = mergeBroken(sentences, src, stats)
                sentences = r.first
                src = r.second
            }
            val moved = IntArray(oldSize) { -1 }
            src.forEachIndexed { newIdx, group ->
                for (o in group) if (o in moved.indices) moved[o] = newIdx
            }
            // Выброшенный мусор приклеиваем к СЛЕДУЮЩЕМУ уцелевшему предложению
            // (а в конце главы — к предыдущему): место чтения, стоявшее на
            // выброшенной строке, не должно пропасть.
            var next = -1
            for (i in moved.indices.reversed()) {
                if (moved[i] >= 0) next = moved[i] else if (next >= 0) moved[i] = next
            }
            var prev = 0
            for (i in moved.indices) {
                if (moved[i] >= 0) prev = moved[i] else moved[i] = prev
            }
            moves.add(moved)
            chapters.add(if (sentences === ch.sentences) ch else ch.copy(sentences = sentences))
        }
        var out: List<Chapter> = chapters
        var structureKept = true
        if (opts.headings && realChapterCount(out) < TRUSTED_CHAPTERS) {
            val before = out.size
            out = splitByHeadings(out, stats)
            if (out.size != before) structureKept = false
        }
        // Глава, у которой после чистки не осталось ни названия, ни текста, книге
        // не нужна: это пустая запись в оглавлении. Номера глав после такого
        // выбрасывания сдвигаются — карту места тогда не отдаём вовсе.
        val beforeFilter = out.size
        out = out.filter { it.sentences.isNotEmpty() || !it.title.isNullOrBlank() }
        if (out.size != beforeFilter) structureKept = false
        stats.ms = System.currentTimeMillis() - t0
        val remap: ((Int, Int) -> Pair<Int, Int>?)? = if (structureKept && moves.isNotEmpty()) {
            { chIdx, sIdx ->
                val ch = chIdx.coerceIn(0, moves.size - 1)
                val m = moves[ch]
                if (m.isEmpty()) ch to 0 else ch to m[sIdx.coerceIn(0, m.size - 1)]
            }
        } else null
        return Result(doc.copy(chapters = out), stats, remap)
    }

    // ---------------- 1-2. Мусор и разделители ----------------

    /**
     * Убрать мусорные строки и разделители сцен.
     *
     * Одинокие номера ([PAGE_NUMBER]) обрабатываются ОТДЕЛЬНО и осторожно: такие
     * строки бывают и номерами страниц (их убирать надо), и СПИСКОМ ПРИМЕЧАНИЙ
     * (их убирать нельзя). Различаем по шагу: у примечаний номер стоит через
     * строку текста, у страниц — редко. Проверка сделана по книгам Сержа
     * 15.10.2026: в «Блокаде» 261 такой номер идёт с шагом 2 — это примечания
     * (см. [NOTE_GAP]).
     */
    private fun dropJunk(list: List<Sentence>, stats: Stats): Pair<List<Sentence>, List<Int>> {
        val drop = BooleanArray(list.size)
        for ((i, s) in list.withIndex()) {
            val t = s.text.trim()
            if (t.isEmpty() || t.length > JUNK_MAX) continue
            if (SEPARATOR.matches(t)) {
                drop[i] = true
                stats.separatorsRemoved++
            } else if (JUNK.any { it.matches(t) }) {
                drop[i] = true
                stats.junkRemoved++
            }
        }
        // Одинокие номера: только если их много и они стоят РЕДКО (номера страниц).
        val nums = ArrayList<Int>()
        for ((i, s) in list.withIndex()) {
            val t = s.text.trim()
            if (t.isNotEmpty() && t.length <= JUNK_MAX && PAGE_NUMBER.matches(t)) nums.add(i)
        }
        if (nums.size >= PAGE_NUMBERS_MIN && medianGap(nums) > NOTE_GAP) {
            for (i in nums) {
                if (!drop[i]) {
                    drop[i] = true
                    stats.junkRemoved++
                }
            }
        }
        val out = ArrayList<Sentence>(list.size)
        val kept = ArrayList<Int>(list.size)
        for ((i, s) in list.withIndex()) {
            if (!drop[i]) {
                out.add(s)
                kept.add(i)
            }
        }
        return out to kept
    }

    /** Медиана шага между соседними номерами (список уже по возрастанию). */
    private fun medianGap(sortedIdx: List<Int>): Int {
        if (sortedIdx.size < 2) return Int.MAX_VALUE
        val gaps = ArrayList<Int>(sortedIdx.size - 1)
        for (k in 1 until sortedIdx.size) gaps.add(sortedIdx[k] - sortedIdx[k - 1])
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    // ---------------- 3. Рваные строки ----------------

    /** Склейка рваных строк. Возвращает новый список и, для каждого нового
     *  предложения, номера ИСХОДНЫХ предложений, из которых оно собрано (по ним
     *  и строится карта места чтения: [src] — номера для текущего списка). */
    private fun mergeBroken(
        list: List<Sentence>,
        src: List<List<Int>>,
        stats: Stats,
    ): Pair<List<Sentence>, List<List<Int>>> {
        if (list.size < 2) return list to src
        val out = ArrayList<Sentence>(list.size)
        val groups = ArrayList<List<Int>>(list.size)
        var i = 0
        while (i < list.size) {
            var cur = list[i]
            var group = ArrayList(src.getOrElse(i) { listOf(i) })
            while (i + 1 < list.size && shouldMerge(cur.text, list[i + 1].text)) {
                cur = Sentence(join(cur.text, list[i + 1].text), cur.paragraphStart)
                group.addAll(src.getOrElse(i + 1) { listOf(i + 1) })
                i++
                stats.merged++
            }
            out.add(cur)
            groups.add(group)
            i++
        }
        return out to groups
    }

    /** Склеивать ли [a] с [b]. Правило и защита от стихов — в шапке файла. */
    internal fun shouldMerge(a: String, b: String): Boolean {
        val la = a.trim()
        val lb = b.trim()
        if (la.length < MERGE_MIN || lb.isEmpty()) return false
        if (TERMINAL.indexOf(la.last()) >= 0) return false
        val first = lb.first()
        if (!first.isLowerCase()) return false
        if (la.length <= VERSE_MAX && lb.length <= VERSE_MAX) return false
        if (HEADING.containsMatchIn(lb)) return false
        return true
    }

    /** Склейка: перенос с дефисом убираем вместе с дефисом, остальное — через
     *  пробел (как в [PdfParser] на стыке строк). */
    internal fun join(a: String, b: String): String {
        val la = a.trim()
        val lb = b.trim()
        val cut = la.lastOrNull()
        // '‐' — типографский дефис, '\u00AD' — мягкий перенос (его PdfParser
        // снимает ещё при разборе, но fb2 и txt приходят как есть).
        return if ((cut == '-' || cut == '‐' || cut == '\u00AD') && lb.first().isLowerCase()) {
            la.dropLast(1) + lb
        } else {
            la + " " + lb
        }
    }

    // ---------------- 4. Главы ----------------

    private fun realChapterCount(list: List<Chapter>): Int =
        list.count { !it.title.isNullOrBlank() && !it.autoTitle }

    /** Ищем главы по строкам-заголовкам. Работаем только когда своей разметки
     *  глав в книге нет ([TRUSTED_CHAPTERS]): там, где книга всё расставила сама,
     *  мы не вмешиваемся. */
    private fun splitByHeadings(list: List<Chapter>, stats: Stats): List<Chapter> {
        val out = ArrayList<Chapter>(list.size)
        for (ch in list) {
            val sentences = ch.sentences
            if (sentences.isEmpty()) {
                out.add(ch)
                continue
            }
            // Глава без своего названия, которая НАЧИНАЕТСЯ с заголовка: название
            // берём оттуда, саму строку из текста убираем — иначе диктор прочитал
            // бы её дважды (в оглавлении и в тексте).
            var title = ch.title
            var start = 0
            if (title.isNullOrBlank() || ch.autoTitle) {
                val head = headingText(sentences[0])
                if (head != null) {
                    title = head
                    start = 1
                    stats.headingsFound++
                }
            }
            var pieceStart = start
            for (i in (start + 1) until sentences.size) {
                val head = headingText(sentences[i]) ?: continue
                val body = sentences.subList(pieceStart, i)
                if (body.isNotEmpty()) {
                    out.add(
                        ch.copy(
                            title = if (pieceStart == start) title else null,
                            sentences = ArrayList(body),
                            autoTitle = if (pieceStart == start) ch.autoTitle else true,
                        )
                    )
                }
                title = head
                pieceStart = i + 1
                stats.headingsFound++
            }
            val rest = sentences.subList(pieceStart, sentences.size)
            if (rest.isNotEmpty()) {
                out.add(
                    ch.copy(
                        title = if (pieceStart == start) title else null,
                        sentences = ArrayList(rest),
                        autoTitle = if (pieceStart == start) ch.autoTitle else true,
                    )
                )
            }
        }
        return out
    }

    /** Текст заголовка, если строка на него похожа; иначе null. */
    private fun headingText(s: Sentence): String? {
        val t = s.text.trim()
        if (t.isEmpty() || t.length > 60) return null
        if (!HEADING.containsMatchIn(t)) return null
        val core = t.trimEnd('.', ' ', ':', ';', '—', '-', '–')
        if (core.isEmpty()) return null
        // Строка целиком капсом — это скорее реплика героя, чем глава (на этом
        // ломалось оглавление у Reciter); у нас такое отсекает само слово-начало.
        return core
    }
}
