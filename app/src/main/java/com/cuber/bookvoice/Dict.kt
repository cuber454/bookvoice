package com.cuber.bookvoice

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/**
 * Словарь произношения (27.09.2026) — правила «что искать → на что менять»,
 * которые применяются к тексту ПЕРЕД синтезом.
 *
 *  Зачем. Движки ошибаются на сокращениях, значках, чужих именах и ударениях, и
 *  починить это можно только подменой текста. Так же сделано у @Voice и Librera
 *  (их формат правил мы и берём за образец), а у multiTTS есть встроенная
 *  «Замена». Отличие наше: правила помечаются движком, для которого они годны,
 *  потому что хитрое написание, подобранное на слух на одном движке, на другом
 *  звучит иначе — это и в файлах @Voice написано прямым текстом.
 *
 *  Где живёт. Отдельным текстовым файлом в папке приложения, а НЕ настройкой:
 *  настройки лежат одним XML и переписываются целиком при любом изменении, а
 *  словарь бывает на сотни килобайт. Файл читается один раз и держится в памяти.
 *
 *  Формат (читаемый, его можно править на компьютере):
 *      ; BookVoice, словарь произношения
 *      "км/ч" "километров в час"
 *      ; движки: org.nobody.multitts
 *      "СВО" "эс-вэ-о"
 *      ; выкл
 *      *"(?i)\b1977\b" "1977-ой"
 *
 *  Строка с точкой с запятой — наша пометка, она относится к СЛЕДУЮЩЕМУ правилу:
 *  «движки» (каким движкам правило адресовано; нет пометки — всем) и «выкл»
 *  (правило выключено). Звёздочка в начале правила — регулярное выражение.
 *  Формат специально совпадает с @Voice/Librera, чтобы готовые словари
 *  импортировались, а наши строки с пометками при выгрузке просто убирались.
 */
object Dict {

    /** Главный выключатель. По умолчанию словарь ВЫКЛЮЧЕН: свежая установка и
     *  импортированный словарь не должны менять чтение, пока человек сам не
     *  включит. */
    const val KEY_ON = "dict_on"

    /** Выключатель группы движка: ключ = приставка + имя пакета движка. */
    const val KEY_GROUP = "dict_group_"

    private const val FILE_NAME = "dictionary.txt"
    private const val HEADER = "; BookVoice, словарь произношения"

    /** Книжные правила живут ОТДЕЛЬНЫМ файлом (28.09.2026, просьба Сержа): мусор,
     *  собранный по книгам, не должен пухнуть в его словаре, а убрать его todo
     *  можно одной командой — и он не боится, что после криво исчезнувшей книги в
     *  словаре останется хвост. Формат тот же, плюс строки-пометки «; книга: …». */
    private const val BOOKS_FILE = "dictionary_books.txt"
    private const val BOOKS_HEADER = "; BookVoice, правила из книг"

    /** Знак ударения — комбинирующее акутное ударение Юникода, ставится ПОСЛЕ
     *  ударной гласной. Его понимают RHVoice (родной способ) и Google (проверено
     *  по обсуждению и на слух); Microsoft-голоса — нет. */
    const val MARK = '\u0301'

    /** Движки, про которые ИЗВЕСТНО, что знак ударения они не понимают: multiTTS
     *  на своих Microsoft-голосах рвёт слово на знаке («замо — к», проверено
     *  27.09.2026). Для них варианты со знаком в подбор не попадают вовсе. */
    private val MARK_IGNORED = setOf("org.nobody.multitts")

    /** Одно правило словаря. [engines] пуст — правило для всех движков.
     *  [book] не пуст — правило собрано по конкретной книге (имя её файла) и
     *  работает только в ней. */
    data class Rule(
        val find: String,
        val repl: String,
        val regex: Boolean = false,
        val off: Boolean = false,
        val engines: List<String> = emptyList(),
        val book: String? = null,
    ) {
        /** Годится ли правило этому движку (по пакету). */
        fun targets(pkg: String?): Boolean =
            engines.isEmpty() || (pkg != null && engines.contains(pkg))

        /** Годится ли правило этой книге: общее — всем, книжное — только своей. */
        fun appliesTo(bookName: String?): Boolean =
            book == null || (bookName != null && key(book) == key(bookName))

        /** Строка для списка: «СВО → эс-вэ-о». Знак ударения вслух не читается,
         *  поэтому в списке показываем текст как есть — его читает диктор. */
        fun line(): String = find + " → " + repl
    }

    /** Ключ книги для правил: имя файла без различия регистра и лишних пробелов —
     *  тем же способом книги сопоставляются в синхронизации. */
    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    // ——— Включение ———

    private fun prefs(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ON, false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply()
    }

    /** Включена ли группа движка. По умолчанию включена — правила для движка
     *  работают, как только включён сам словарь. */
    fun groupOn(c: Context, engine: String): Boolean =
        prefs(c).getBoolean(KEY_GROUP + engine, true)

    fun setGroupOn(c: Context, engine: String, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_GROUP + engine, on).apply()
    }

    // ——— Книжные правила (28.09.2026) ———

    fun booksFile(c: Context): File = File(c.filesDir, BOOKS_FILE)

    @Volatile private var booksCache: List<Rule>? = null
    @Volatile private var booksStamp: Long = -1L

    /** Прочитать книжные правила. Тот же приём, что у словаря: файл читается один
     *  раз, дальше отдаём то, что в памяти, пока время правки не изменилось. */
    fun loadBooks(c: Context): List<Rule> {
        val f = booksFile(c)
        val stamp = if (f.exists()) f.lastModified() else 0L
        val cur = booksCache
        if (cur != null && stamp == booksStamp) return cur
        val list = if (f.exists()) parse(f.readText(Charsets.UTF_8)) else emptyList()
        booksCache = list
        booksStamp = stamp
        return list
    }

    fun saveBooks(c: Context, rules: List<Rule>) {
        runCatching { booksFile(c).writeText(serialize(rules, BOOKS_HEADER), Charsets.UTF_8) }
        booksCache = rules
        booksStamp = booksFile(c).lastModified()
    }

    fun addBookRule(c: Context, r: Rule) {
        saveBooks(c, loadBooks(c) + r)
    }

    fun replaceBookRule(c: Context, old: Rule, new: Rule) {
        val list = loadBooks(c).toMutableList()
        val i = list.indexOf(old)
        if (i < 0) list.add(new) else list[i] = new
        saveBooks(c, list)
    }

    fun removeBookRule(c: Context, r: Rule) {
        saveBooks(c, loadBooks(c).filterNot { it == r })
    }

    /** Книга ушла с полки — её правила уходят вместе с ней (просьба Сержа:
     *  «чтобы это уходило вместе с книгой удаляемой»). Возвращает, сколько убрано. */
    fun forgetBook(c: Context, bookName: String): Int {
        val list = loadBooks(c)
        val kept = list.filterNot { it.book != null && key(it.book) == key(bookName) }
        val gone = list.size - kept.size
        if (gone > 0) {
            saveBooks(c, kept)
            Diag.log(c, "dict", "книга «$bookName» ушла с полки — убрал её правила: $gone")
        }
        return gone
    }

    /** Правила, чья книга не на полке: их видно списком и можно убрать разом. */
    fun orphans(c: Context): List<Rule> {
        val onShelf = BookStore.all(c).map { key(it.name) }.toHashSet()
        return loadBooks(c).filter { it.book != null && key(it.book) !in onShelf }
    }

    /** Убрать ВСЕ книжные правила (одна команда — как просил Серж). */
    fun dropAllBooks(c: Context): Int {
        val n = loadBooks(c).size
        if (n > 0) saveBooks(c, emptyList())
        return n
    }

    // ——— Файл ———

    fun file(c: Context): File = File(c.filesDir, FILE_NAME)

    @Volatile private var cache: List<Rule>? = null
    @Volatile private var cacheStamp: Long = -1L
    @Volatile private var index: Index? = null

    /** Прочитать правила. Файл читается один раз: пока время правки не
     *  изменилось, отдаём то, что в памяти (словарь зовут на каждой фразе). */
    fun load(c: Context): List<Rule> {
        val f = file(c)
        val stamp = if (f.exists()) f.lastModified() else 0L
        val cur = cache
        if (cur != null && stamp == cacheStamp) return cur
        val list = if (f.exists()) parse(f.readText(Charsets.UTF_8)) else emptyList()
        cache = list
        cacheStamp = stamp
        index = null
        return list
    }

    fun save(c: Context, rules: List<Rule>) {
        runCatching { file(c).writeText(serialize(rules), Charsets.UTF_8) }
        cache = rules
        cacheStamp = file(c).lastModified()
        index = null
    }

    fun add(c: Context, r: Rule) {
        val list = load(c).toMutableList()
        list.add(r)
        save(c, list)
    }

    fun replace(c: Context, old: Rule, new: Rule) {
        val list = load(c).toMutableList()
        val i = list.indexOf(old)
        if (i < 0) list.add(new) else list[i] = new
        save(c, list)
    }

    fun remove(c: Context, r: Rule) {
        val list = load(c).toMutableList()
        list.remove(r)
        save(c, list)
    }

    // ——— Импорт и экспорт (28.09.2026) ———
    //
    //  Формат файла тот же, что у нас внутри (см. заголовок объекта), и совпадает
    //  с @Voice/Librera: чужие словари импортируются, а наши при выгрузке теряют
    //  только строки-пометки про движки и выключенные правила — их эти программы
    //  не понимают, поэтому строки с «;» просто убираются.

    /** Имя файла для выгрузки: его видит владелец в проводнике и в облаке. */
    const val EXPORT_NAME = "BookVoice_dictionary.txt"

    /** Добавить правила из чужого словаря. Возвращает (добавлено, пропущено как
     *  повторы). Повтором считаем правило с тем же «что искать», заменой и
     *  признаком регулярного выражения: движки и выключенность — подробности
     *  одного и того же правила, и второй раз его в словаре держать незачем. */
    fun merge(c: Context, incoming: List<Rule>): Pair<Int, Int> {
        val have = load(c).toMutableList()
        val seen = have.map { keyOf(it) }.toHashSet()
        var added = 0
        var skipped = 0
        for (r in incoming) {
            if (r.find.isEmpty()) continue
            if (!seen.add(keyOf(r))) {
                skipped++
                continue
            }
            have.add(r)
            added++
        }
        if (added > 0) save(c, have)
        return added to skipped
    }

    private fun keyOf(r: Rule): String =
        (if (r.regex) "*" else "") + r.find.lowercase(Locale.ROOT) + "\u0000" + r.repl

    /** Включить или выключить сразу всю часть словаря (28.09.2026): в части бывает
     *  сотня правил, и переключать их по одному — работа на полчаса. Возвращает,
     *  сколько правил действительно поменяло состояние. */
    fun setGroupOff(c: Context, group: String, off: Boolean): Int {
        val rules = load(c)
        var n = 0
        val out = rules.map { r ->
            val mine = if (group.isEmpty()) r.engines.isEmpty() else r.engines.contains(group)
            if (mine && r.off != off) {
                n++
                r.copy(off = off)
            } else {
                r
            }
        }
        if (n > 0) save(c, out)
        return n
    }

    /** Текст словаря из байтов: сперва честная попытка UTF-8 (наш формат), при
     *  неудаче — windows-1251: так лежит большинство готовых словарей @Voice и
     *  Librera. BOM убираем, он мешает первой строке. */
    fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { strict.decode(java.nio.ByteBuffer.wrap(b)).toString() }
            .getOrElse { String(b, Charset.forName("windows-1251")) }
    }

    /** Сколько правил в каждой части: «все движки» и по движкам. */
    fun counts(rules: List<Rule>): Pair<Int, Map<String, Int>> {
        var common = 0
        val byEngine = LinkedHashMap<String, Int>()
        for (r in rules) {
            if (r.engines.isEmpty()) common++ else r.engines.forEach {
                byEngine[it] = (byEngine[it] ?: 0) + 1
            }
        }
        return common to byEngine
    }

    // ——— Разбор и запись файла ———

    fun parse(text: String): List<Rule> {
        val out = ArrayList<Rule>()
        var engines: List<String> = emptyList()
        var off = false
        var book: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(";")) {
                val body = line.substring(1).trim()
                val low = body.lowercase(Locale.ROOT)
                when {
                    low.startsWith("движки:") || low.startsWith("engines:") -> {
                        val tail = body.substringAfter(':')
                        engines = tail.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    low.startsWith("выкл") || low.startsWith("off") -> off = true
                    // Пометка книги: правило собрано по ней и работает только в ней.
                    low.startsWith("книга:") || low.startsWith("book:") ->
                        book = body.substringAfter(':').trim().ifBlank { null }
                    else -> Unit // прочие пояснения пропускаем
                }
                continue
            }
            val regex = line.startsWith("*")
            val body = if (regex) line.substring(1).trim() else line
            val pair = twoQuoted(body) ?: continue
            out.add(Rule(pair.first, pair.second, regex, off, engines, book))
            engines = emptyList()
            off = false
            book = null
        }
        return out
    }

    /** Две строки в кавычках из строки правила. */
    private fun twoQuoted(s: String): Pair<String, String>? {
        val first = quoted(s, 0) ?: return null
        val second = quoted(s, first.second) ?: return null
        return first.first to second.first
    }

    /** Строка в кавычках начиная с [from]; возвращает текст и позицию за закрывающей. */
    private fun quoted(s: String, from: Int): Pair<String, Int>? {
        var i = from
        while (i < s.length && s[i] != '"') i++
        if (i >= s.length) return null
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1]); i += 2; continue
            }
            if (ch == '"') return sb.toString() to (i + 1)
            sb.append(ch); i++
        }
        return null
    }

    fun serialize(rules: List<Rule>, header: String = HEADER): String {
        val sb = StringBuilder()
        sb.append(header).append('\n')
        for (r in rules) {
            r.book?.let { sb.append("; книга: ").append(it).append('\n') }
            if (r.engines.isNotEmpty()) sb.append("; движки: ").append(r.engines.joinToString(", ")).append('\n')
            if (r.off) sb.append("; выкл\n")
            if (r.regex) sb.append('*')
            sb.append(quote(r.find)).append(' ').append(quote(r.repl)).append('\n')
        }
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            if (ch == '"' || ch == '\\') sb.append('\\')
            sb.append(ch)
        }
        return sb.append('"').toString()
    }

    // ——— Применение ———

    /** Применить словарь к тексту, который уйдёт движку [engine] (пакет).
     *  [book] — имя файла открытой книги: книжные правила работают только в своей
     *  книге, общие — везде. */
    fun apply(c: Context, text: String, engine: String?, book: String? = null): String {
        if (text.isEmpty()) return text
        if (!enabled(c)) return text
        val rules = load(c)
        val bookRules = if (book.isNullOrBlank()) {
            emptyList()
        } else {
            loadBooks(c).filter { it.appliesTo(book) }
        }
        if (rules.isEmpty() && bookRules.isEmpty()) return text
        return applyWith(rules, bookRules, text, engine) { r -> groupAllowed(c, r) }
    }

    private fun groupAllowed(c: Context, r: Rule): Boolean =
        r.engines.isEmpty() || r.engines.any { groupOn(c, it) }

    /** То же, но без чтения настроек — для проверки одного правила в окне. */
    fun applyRule(r: Rule, text: String): String = applyOne(r, text)

    private fun applyWith(
        rules: List<Rule>,
        extra: List<Rule>,
        text: String,
        engine: String?,
        groupAllowed: (Rule) -> Boolean,
    ): String {
        var out = text
        val idx = indexOf(rules)
        // Книжные правила проверяем все: их на книгу единицы, а указатель по первым
        // буквам строится только по общему словарю (он и кэшируется).
        val candidates = (if (idx.any.isEmpty()) idx.pick(out) else idx.pick(out) + idx.any) + extra
        for (r in candidates) {
            if (r.off || !r.targets(engine) || !groupAllowed(r)) continue
            val next = applyOne(r, out)
            if (next != out) out = next
        }
        return out
    }

    private fun applyOne(r: Rule, text: String): String {
        if (r.find.isEmpty()) return text
        return if (r.regex) {
            runCatching { regexOf(r.find).replace(text, r.repl) }.getOrDefault(text)
        } else {
            runCatching { text.replace(r.find, r.repl, ignoreCase = true) }.getOrDefault(text)
        }
    }

    private val regexCache = HashMap<String, Regex>()

    private fun regexOf(p: String): Regex = regexCache.getOrPut(p) {
        Regex(p, setOf(RegexOption.IGNORE_CASE))
    }

    // ——— Указатель по первым буквам ———
    //  Готовый словарь @Voice — это 2500 правил; честно прогонять их все на
    //  каждой фразе значит потратить десятки миллисекунд. Поэтому правила
    //  разложены по двум первым буквам искомого текста: на фразе проверяются
    //  только те, что вообще могут совпасть.

    private class Index(val map: HashMap<String, MutableList<Rule>>, val any: List<Rule>) {
        fun pick(text: String): List<Rule> {
            if (map.isEmpty()) return emptyList()
            val low = text.lowercase(Locale.ROOT)
            val out = ArrayList<Rule>(8)
            var i = 0
            while (i + 1 < low.length) {
                map[low.substring(i, i + 2)]?.let { out.addAll(it) }
                i++
            }
            return out
        }
    }

    private fun indexOf(rules: List<Rule>): Index {
        index?.let { return it }
        val map = HashMap<String, MutableList<Rule>>()
        val any = ArrayList<Rule>()
        for (r in rules) {
            val hint = hintOf(r)
            if (hint == null) any.add(r)
            else map.getOrPut(hint) { ArrayList() }.add(r)
        }
        val idx = Index(map, any)
        index = idx
        return idx
    }

    /** Первые две буквы искомого текста — по ним правило и находится. Для
     *  регулярного выражения берём кусок в \Q…\E, если он там есть: так записано
     *  большинство правил в готовых словарях. */
    private fun hintOf(r: Rule): String? {
        val src = if (r.regex) {
            val q = Regex("\\\\Q(.*?)\\\\E").find(r.find)?.groupValues?.get(1) ?: return null
            q
        } else {
            r.find
        }
        val low = src.lowercase(Locale.ROOT)
        return if (low.length >= 2) low.substring(0, 2) else null
    }

    // ——— Подбор написания ———

    private val VOWELS = "аеиоуыэюя"

    /** Слова текста — для выбора того, которое будем править. */
    fun words(text: String): List<String> =
        text.split(' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    /** Варианты написания слова: со знаком ударения, с «ё», разбитые дефисами и
     *  с заменёнными гласными. Список показывается диктору, а звучание выбирает
     *  человек — вслепую знак ударения не поставишь. */
    fun candidates(word: String, engine: String?): List<String> {
        val out = LinkedHashSet<String>()
        out.add(word)
        // «ё» вместо «е»: буква всегда ударная, понимают все движки.
        for (i in word.indices) {
            if (word[i] == 'е' || word[i] == 'Е') {
                out.add(word.substring(0, i) + (if (word[i] == 'Е') 'Ё' else 'ё') + word.substring(i + 1))
            }
        }
        // Знак ударения — по одному варианту на каждую гласную. Движкам, которые
        // знак не понимают, такие варианты не предлагаем вовсе: они рвут слово.
        if (engine == null || !MARK_IGNORED.contains(engine)) {
            for (i in word.indices) {
                if (VOWELS.indexOf(word[i].lowercaseChar()) >= 0) {
                    out.add(word.substring(0, i + 1) + MARK + word.substring(i + 1))
                }
            }
        }
        // Дефисы: движок читает куски раздельно, и ударение иногда встаёт иначе.
        for (i in word.indices) {
            if (VOWELS.indexOf(word[i].lowercaseChar()) >= 0 && i + 1 < word.length) {
                out.add(word.substring(0, i + 1) + "-" + word.substring(i + 1))
            }
        }
        // Заменённые гласные: пишем так, как слышится («званит» вместо «звонит»).
        for (i in word.indices) {
            if (VOWELS.indexOf(word[i].lowercaseChar()) < 0) continue
            val sb = StringBuilder()
            for (j in word.indices) {
                val ch = word[j]
                sb.append(if (j != i) reduce(ch) else ch)
            }
            out.add(sb.toString())
        }
        return out.toList()
    }

    /** Безударная гласная в русской речи звучит иначе — так её и пишут, когда
     *  хотят увести ударение в другое место. */
    private fun reduce(ch: Char): Char = when (ch) {
        'о', 'О' -> if (ch == 'О') 'А' else 'а'
        'е', 'Е' -> if (ch == 'Е') 'И' else 'и'
        'я', 'Я' -> if (ch == 'Я') 'И' else 'и'
        'э', 'Э' -> if (ch == 'Э') 'И' else 'и'
        else -> ch
    }

    /** Имена букв через дефис: «СВО» → «эс-вэ-о». Проверено на слух 27.09.2026:
     *  через пробел движок сливает буквы в одно слово, через дефис читает верно. */
    fun letters(word: String): String {
        val parts = ArrayList<String>()
        for (ch in word) {
            if (!ch.isLetter()) continue
            parts.add(nameOf(ch))
        }
        return parts.joinToString("-")
    }

    private val RU_NAMES = mapOf(
        'а' to "а", 'б' to "бэ", 'в' to "вэ", 'г' to "гэ", 'д' to "дэ", 'е' to "е",
        'ё' to "ё", 'ж' to "жэ", 'з' to "зэ", 'и' to "и", 'й' to "й", 'к' to "ка",
        'л' to "эл", 'м' to "эм", 'н' to "эн", 'о' to "о", 'п' to "пэ", 'р' to "эр",
        'с' to "эс", 'т' to "тэ", 'у' to "у", 'ф' to "эф", 'х' to "ха", 'ц' to "цэ",
        'ч' to "че", 'ш' to "ша", 'щ' to "ща", 'ъ' to "твёрдый знак", 'ы' to "ы",
        'ь' to "мягкий знак", 'э' to "э", 'ю' to "ю", 'я' to "я",
    )

    private val EN_NAMES = mapOf(
        'a' to "эй", 'b' to "би", 'c' to "си", 'd' to "ди", 'e' to "и", 'f' to "эф",
        'g' to "джи", 'h' to "эйч", 'i' to "ай", 'j' to "джей", 'k' to "кей",
        'l' to "эл", 'm' to "эм", 'n' to "эн", 'o' to "оу", 'p' to "пи", 'q' to "кью",
        'r' to "ар", 's' to "эс", 't' to "ти", 'u' to "ю", 'v' to "ви",
        'w' to "дабл-ю", 'x' to "экс", 'y' to "уай", 'z' to "зед",
    )

    private fun nameOf(ch: Char): String {
        val low = ch.lowercaseChar()
        RU_NAMES[low]?.let { return it }
        EN_NAMES[low]?.let { return it }
        return ch.toString()
    }

    /** Сколько раз искомое встречается в книге — по нему видно, широкое правило
     *  получилось или слишком узкое. Считаем по уже разобранной книге. */
    fun countInBook(find: String, regex: Boolean): Int {        if (find.isEmpty()) return 0
        val bk = ReaderEngine.bookOrNull() ?: return -1
        var n = 0
        val re = if (regex) runCatching { Regex(find, setOf(RegexOption.IGNORE_CASE)) }.getOrNull() else null
        if (regex && re == null) return -1
        for (ch in bk.chapters) for (s in ch.sentences) {
            val t = s.text
            if (re != null) n += re.findAll(t).count() else {
                var i = t.indexOf(find, 0, ignoreCase = true)
                while (i >= 0) { n++; i = t.indexOf(find, i + 1, ignoreCase = true) }
            }
        }
        return n
    }

    /** Установленные движки синтеза: пакет → имя для человека. Список частей
     *  словаря строится отсюда, поэтому новый движок появляется в окне сам. */
    fun installedEngines(c: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val pm = c.packageManager
            val intent = android.content.Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val list = pm.queryIntentServices(intent, android.content.pm.PackageManager.GET_META_DATA)
            for (ri in list) {
                val pkg = ri.serviceInfo?.packageName ?: continue
                out[pkg] = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            }
        }
        return out
    }
}
