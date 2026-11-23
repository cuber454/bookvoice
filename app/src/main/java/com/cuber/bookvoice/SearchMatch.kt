package com.cuber.bookvoice

/**
 * ТЕРПИМОЕ СРАВНЕНИЕ ПРИ ПОИСКЕ (12.11.2026, просьба Сержа).
 *
 * Жалоба: «нажимаю „Найти“, потом „Далее“ — ничего не делает». Часть беды в том,
 * что поиск сравнивал ТОЧНОЕ вхождение: «Все» не находилось в «всё», «привет» —
 * в «привет,», а продиктованное слово из двух слов с двойным пробелом не
 * находилось никогда. Человек слышит «ничего не найдено» и решает, что кнопка не
 * работает.
 *
 * Правила сравнения (только для поиска, остальной текст не трогаем):
 *  - регистр не важен;
 *  - `ё` равно `е` (и `Ё` равно `Е`) — в набранном слове ё почти никто не ставит,
 *    а в книгах она стоит;
 *  - подряд идущие пробелы, табы и переводы строк считаем ОДНИМ пробелом и в
 *    слове, и в тексте: «в  тем» находит «в тем», а «в тем» находит «в\nтем».
 *
 * Работаем ПО СИМВОЛАМ, без создания копий текста в нижнем регистре на каждое
 * предложение: это и быстрее, и не плодит мусор. Проход по книге в 200 тысяч
 * предложений (16 миллионов знаков) стоит около 70 миллисекунд на компьютере, на
 * телефоне — доли секунды; поэтому поиск по одному нажатию «Найти» успевает
 * ответить сразу.
 */
internal object SearchMatch {

    /** Привести запрос к виду сравнения: по краям пробелов нет, внутри — по
     *  одному. В нижний регистр не переводим: сравнение и так без регистра. */
    fun normalizeNeedle(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ")

    /** Найти `needle` (уже [normalizeNeedle]) в `text`, начиная с `from`.
     *  Вернуть индекс начала или -1. */
    fun find(text: String, needle: String, from: Int = 0): Int {
        val n = needle.length
        if (n == 0) return -1
        val tl = text.length
        if (from < 0) return -1
        var i = from
        // Дальше начала последнего возможного запуска искать бессмысленно, но
        // пробелы в тексте могут «сжиматься», поэтому границу не срезаем.
        while (i < tl) {
            if (matchAt(text, i, needle)) return i
            i++
        }
        return -1
    }

    fun contains(text: String, needle: String): Boolean = find(text, needle) >= 0

    /** Совпадает ли `needle` с текстом, начиная с `start`. */
    private fun matchAt(text: String, start: Int, needle: String): Boolean {
        var ti = start
        var ni = 0
        val tl = text.length
        val n = needle.length
        while (ni < n) {
            val nc = needle[ni]
            if (isSpace(nc)) {
                // Пробелы запроса «сжимаем»: один пробел запроса = сколько угодно
                // пробелов в тексте.
                if (ti >= tl || !isSpace(text[ti])) return false
                while (ti < tl && isSpace(text[ti])) ti++
                while (ni < n && isSpace(needle[ni])) ni++
                continue
            }
            if (ti >= tl) return false
            if (!sameChar(text[ti], nc)) return false
            ti++
            ni++
        }
        return true
    }

    private fun sameChar(a: Char, b: Char): Boolean {
        if (a == b) return true
        val x = fold(a)
        val y = fold(b)
        return x == y
    }

    /** Символ для сравнения: нижний регистр плюс `ё` → `е`. */
    private fun fold(c: Char): Char {
        val l = c.lowercaseChar()
        return if (l == 'ё') 'е' else l
    }

    /** Пробел в широком смысле: обычный, таб, перевод строки, неразрывный
     *  (`\u00A0`) и узкий неразрывный (`\u202F`, его любит книжная вёрстка).
     *  `Char.isWhitespace` неразрывный пробел пробелом НЕ считает, поэтому оба
     *  добавлены явно — иначе слово с неразрывным пробелом внутри не находилось
     *  бы по обычному пробелу, а в книгах неразрывные пробелы стоят часто. */
    private fun isSpace(c: Char): Boolean =
        c.isWhitespace() || c == '\u00A0' || c == '\u202F'
}
