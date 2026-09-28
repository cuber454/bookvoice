package com.cuber.bookvoice

import java.util.Locale

/**
 * «Умная замена» (28.09.2026, идея Сержа): показать приложению мусорную строку — и
 * получить правило, которое убирает все такие строки в этой книге.
 *
 *  Зачем. В сборниках после каждого анекдота идёт подпись вида
 *  «28. 09. 1995 (С лекций по программированию МГУ)»: год и факультет меняются,
 *  а слушать это каждый раз не надо. Писать регулярное выражение руками —
 *  работа не для человека, поэтому его собирает приложение.
 *
 *  Как. Берём строку-образец и разбиваем на слова. Числа сразу становятся
 *  образцом «любое число» — год и дата почти всегда разные. Дальше по очереди
 *  пробуем заменить образцом каждое слово и смотрим, насколько больше строк в
 *  книге под это подходит; выигрышное слово остаётся образцом, остальные — как
 *  были. Так находятся те слова, которые в книге меняются (факультет, вуз), и не
 *  трогаются те, что одинаковы.
 *
 *  Что получается на выходе. Обычное правило словаря с пустой заменой: строка
 *  исчезает из чтения целиком. Образец привязан к началу и концу фразы — значит
 *  сработает только там, где вся фраза и есть такая подпись, и не съест кусок из
 *  середины анекдота.
 */
object DictSmart {

    /** Что нашли: готовое правило, сколько строк в книге оно уберёт и примеры. */
    class Found(
        val rule: Dict.Rule,
        val count: Int,
        val examples: List<String>,
    )

    /** Сколько строк показываем владельцу для проверки. */
    private const val EXAMPLES = 3

    /** Сколько раз пробуем обобщить слово. Больше — дольше считаем, а пользы мало. */
    private const val TRIES = 10

    /** Сколько слов перебираем за одну попытку. */
    private const val CANDIDATES = 12

    /** Ищем похожие строки по образцу [sample] и собираем правило для книги. */
    fun find(book: BookDocument, bookName: String, sample: String): Found? {
        val text = sample.trim()
        if (text.isEmpty()) return null
        val sentences = allSentences(book)
        if (sentences.isEmpty()) return null
        val sampleTokens = tokens(text)
        if (sampleTokens.isEmpty()) return null
        // Образец: числа — «любое число», остальное как есть.
        val parts = sampleTokens.map { mask(it) }.toMutableList()
        var best = matches(sentences, parts)
        // По очереди обобщаем слова: оставляем то, от которого совпадений больше
        // всего. Так «программированию» и «МГУ» сами становятся образцами.
        // Пробуем только слова подлиннее (короткие союзы и предлоги обобщать
        // бессмысленно) и не больше [CANDIDATES] за раз — иначе перебор по большой
        // книге занимал бы секунды.
        var tries = 0
        while (tries < TRIES) {
            var bestIndex = -1
            var bestMatches = best
            var tried = 0
            for (i in parts.indices) {
                if (isWildcard(parts[i]) || parts[i].length < 3) continue
                if (tried++ >= CANDIDATES) break
                val saved = parts[i]
                parts[i] = WILD
                val cur = matches(sentences, parts)
                if (cur.size > bestMatches.size) {
                    bestIndex = i
                    bestMatches = cur
                }
                parts[i] = saved
            }
            if (bestIndex < 0) break
            parts[bestIndex] = WILD
            best = bestMatches
            tries++
        }
        val pattern = build(parts)
        val rule = Dict.Rule(
            find = pattern,
            repl = "",
            regex = true,
            off = false,
            engines = emptyList(),
            book = bookName,
        )
        val check = runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            ?: return null
        val hit = sentences.filter { check.matches(it) }
        if (hit.isEmpty()) return null
        return Found(rule, hit.size, hit.take(EXAMPLES))
    }

    // ——— Внутреннее ———

    private const val WILD = "\u0000"

    private fun isWildcard(part: String) = part == WILD

    private fun allSentences(book: BookDocument): List<String> =
        book.chapters.flatMap { ch -> ch.sentences.map { it.text.trim() } }
            .filter { it.isNotEmpty() }

    /** Слова строки: разделитель — пробел. Пустые выбрасываем. */
    private fun tokens(s: String): List<String> =
        s.split(' ', '\n', '\t', '\u00a0').map { it.trim() }.filter { it.isNotEmpty() }

    /** Числа (в том числе «28.» «09.» «1995») — в образец «любое число». */
    private fun mask(token: String): String =
        if (token.any { it.isDigit() }) WILD else token

    private fun matches(sentences: List<String>, parts: List<String>): List<String> {
        val re = runCatching { Regex(build(parts), setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
            ?: return emptyList()
        return sentences.filter { re.matches(it) }
    }

    /** Собрать образец: слова через «любые пробелы», буквы экранируем как есть,
     *  а вся фраза привязана к началу и концу строки. */
    private fun build(parts: List<String>): String {
        val sb = StringBuilder("^\\s*")
        for ((i, part) in parts.withIndex()) {
            if (i > 0) sb.append("\\s+")
            sb.append(if (isWildcard(part)) "\\S+" else escape(part))
        }
        sb.append("\\s*$")
        return sb.toString()
    }

    /** Экранирование для регулярного выражения: знаки вроде скобок, точек и
     *  звёздочек должны читаться буквально. */
    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (!ch.isLetterOrDigit() && ch != ' ' && ch != '_' && ch.code < 128) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Строка для списка: то же правило, но короче — без служебных знаков. */
    fun humanLine(pattern: String): String =
        pattern.replace("^\\s*", "").replace("\\s*$", "")
            .replace("\\s+", " ")
            .replace("\\S+", "…")
            .replace("\\", "")
            .lowercase(Locale.ROOT)
}
