package com.cuber.bookvoice

import java.util.ArrayList

/** Разбивка текста на предложения и сборка списка [Sentence] из абзацев. */
object TextSplit {

    /** Сокращения, после которых точка НЕ заканчивает предложение, даже если дальше
     *  слово с большой буквы. Список нарочно короткий и только про то, что в книгах
     *  встречается постоянно: «и др.» перед новой фразой, «см.», «напр.», «стр.»,
     *  «им. Пушкина», «г. Ленинград», «т. е.» и подобное.
     *
     *  Почему это важно (27.10.2026, жалоба Сержа «строчки рваные»): в книге
     *  «Блокада» есть фраза «переводила Т. М. Рида, Д. Лондона, О. де Бальзака,
     *  Э. Золя, Ж. Верна, Д. Гарнетта и др.» — читалка резала её по инициалам, и в
     *  слух шло «Золя, Ж.» (пауза) «Верна, Д.» (пауза) «Гарнетта и др.». */
    private val ABBREVIATIONS = setOf(
        // «и др.», «см.», «ср.», «напр.», «стр.», «т. е.», «т. д.», «т. п.», «т. к.»
        "др", "см", "ср", "напр", "стр", "с", "те", "тд", "тп", "тк", "т.е", "т.д", "т.п", "т.к",
        // годы, века, города, адреса
        "г", "гг", "в", "вв", "век", "ул", "просп", "пер", "пл", "наб", "обл", "д", "кв", "корп",
        // издания и аппарат книги
        "изд", "изд-во", "кн", "ч", "гл", "рис", "табл", "им", "проф", "акад", "доц", "ред",
        "сост", "предисл", "коммент", "науч",
        // числа и меры
        "т", "п", "пп", "млн", "млрд", "тыс", "руб", "коп", "чел", "экз", "н.э",
        // латиница, которая встречается в библиографии
        "p", "pp", "vol",
    )

    /** Закрывающие знаки: они принадлежат ПРЕДЫДУЩЕМУ предложению, а не следующему.
     *  Без этого «…сказал он: „Нет!“» рвалось на «Нет!» и «“ И всё стихло», и
     *  читалка начинала фразу с кавычки (27.10.2026, видно на тесте разбивки). */
    private const val CLOSERS = "»\"”’)]}"

    /** Знаки, с которых предложение НАЧАТЬСЯ не может: двоеточие, точка с запятой,
     *  запятая. Если они стоят сразу после точки, точка — не конец предложения
     *  («М.: КоЛибри; СПб.: Азбука-Аттикус» — так выглядит описание книги в
     *  примечаниях; 27.10.2026 на этом рвалось «СПб.» + «: Азбука-Аттикус»). */
    private const val CONTINUATION = ":;,"

    /** Режет текст на предложения. Не режет: десятичные дроби, "т.е.", "и т.п.",
     *  инициалы («Ж. Верна», «А. С. Пушкин»), сокращения из [ABBREVIATIONS] и
     *  точку перед знаком из [CONTINUATION]. */
    fun splitToSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        val n = text.length
        fun punct(c: Char) = c == '.' || c == '!' || c == '?' || c == '…'
        while (i < n) {
            val c = text[i]
            if (punct(c)) {
                var j = i
                while (j < n && punct(text[j])) j++
                var k = j
                // Закрывающие кавычки и скобки остаются у СВОЕГО предложения.
                while (k < n && (text[k].isWhitespace() || CLOSERS.indexOf(text[k]) >= 0)) k++
                val prevNum = i > 0 && text[i - 1].isDigit()
                val nextNum = k < n && text[k].isDigit()
                val nextLower = k < n && text[k].isLowerCase()
                val continues = k < n && CONTINUATION.indexOf(text[k]) >= 0
                val abbreviation = continues || prevNum && nextNum || nextLower && (j - i) <= 2 ||
                    afterInitialOrAbbrev(text, i)
                if (!abbreviation) {
                    val piece = text.substring(start, k).trim()
                    if (piece.isNotEmpty()) out.add(piece)
                    start = k
                    i = k
                    continue
                }
            }
            i++
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Точка стоит сразу после инициала или известного сокращения.
     *
     *  Инициал — одиночная БУКВА, за которой нет другой буквы слева: «Ж.» в
     *  «Золя, Ж. Верна», «С.» в «А. С. Пушкин». Проверяем именно «стоит одна»:
     *  иначе «соч.» или «т.д.» узнавались бы как буква.
     *
     *  Сокращение — слово перед точкой (возможно с точками внутри: «т.е») из
     *  [ABBREVIATIONS]. Регистр не важен: «Им. Пушкина» и «им. Пушкина» — оба. */
    private fun afterInitialOrAbbrev(text: String, dot: Int): Boolean {
        var p = dot - 1
        while (p >= 0 && !text[p].isLetter() && text[p] != '.') p--
        if (p < 0 || !text[p].isLetter()) return false
        var q = p
        while (q >= 0 && (text[q].isLetter() || text[q] == '.')) q--
        val word = text.substring(q + 1, p + 1)
        // Инициал: ровно одна БОЛЬШАЯ буква, слева от неё не буква и не цифра.
        // Регистр важен: «я.» в конце фразы — это слово, а не инициал, и склеивать
        // с ним соседнее предложение нельзя.
        if (word.length == 1) {
            if (!word[0].isUpperCase()) return word.lowercase() in ABBREVIATIONS
            if (p - 1 >= 0 && text[p - 1].isLetterOrDigit()) return false
            return true
        }
        val clean = word.lowercase().trim('.')
        return clean in ABBREVIATIONS
    }

    /** Абзацы -> плоский список предложений; первое предложение абзаца помечается paragraphStart. */
    fun fromParagraphs(paragraphs: List<String>): List<Sentence> {
        val res = ArrayList<Sentence>()
        for (p in paragraphs) {
            val parts = splitToSentences(p)
            for ((idx, part) in parts.withIndex()) {
                res.add(Sentence(part, paragraphStart = idx == 0))
            }
        }
        return res
    }
}
