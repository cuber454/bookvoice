package com.cuber.bookvoice

import android.content.Context
import java.text.Normalizer

/**
 * Язык книги — по буквам, которых нет у соседей (29.10.2026, просьба Сержа:
 * «есть ли стопроцентный способ определять язык книги, чтобы спросить: изменился
 * язык, переключить синтезатор на белорусский?»).
 *
 * Стопроцентного способа нет ни у кого, но для русского, украинского и
 * белорусского он практически безошибочен: у каждого языка есть буквы, которых
 * нет у двух других.
 *  - «ў» — только белорусский;
 *  - «ї», «є», «ґ» — только украинский;
 *  - белорусский пишет «і» там, где русский пишет «и», и почти не употребляет «и».
 * Поэтому считаем не одну букву, а несколько признаков и требуем явного перевеса.
 * Нет перевеса — молчим: лишний вопрос хуже пропуска.
 *
 * ПОДМЕНЫ. В книгах белорусские буквы часто набраны похожими чужими символами
 * (латинская «i» вместо «і», «у» с крышечкой вместо «ў»). Лечим двумя приёмами:
 *  1) нормализация склеивает надстрочные знаки с буквой («у» + крышечка → «ў»);
 *  2) внутри слова, где есть хоть одна кириллическая буква, похожие латинские
 *     заменяем на кириллические: настоящее латинское слово посреди кириллической
 *     фразы не встречается, а подмена — встречается постоянно.
 *
 * Определяем ТОЛЬКО эти три языка (решение Сержа: «остальные пока не детектить»).
 * Смешанную книгу считаем по большинству, и всё равно спрашиваем, а не
 * переключаем молча.
 */
object BookLang {

    const val RU = "ru"
    const val UK = "uk"
    const val BE = "be"

    /** Меньше этого числа кириллических букв — судить не о чем. */
    private const val MIN_LETTERS = 400

    /** Название языка в предложном падеже: «книга на белорусском». */
    fun nameIn(lang: String): String = when (lang) {
        UK -> "украинском"
        BE -> "белорусском"
        else -> "русском"
    }

    /** Название языка в именительном: «белорусский голос». */
    fun nameOf(lang: String): String = when (lang) {
        UK -> "украинский"
        BE -> "белорусский"
        else -> "русский"
    }

    /** Похожие латинские буквы внутри кириллического слова — на кириллические. */
    private fun foldLatin(c: Char): Char = when (c) {
        'a' -> 'а'
        'c' -> 'с'
        'e' -> 'е'
        'i' -> 'і'
        'o' -> 'о'
        'p' -> 'р'
        'x' -> 'х'
        'y' -> 'у'
        'u' -> 'у'
        else -> c
    }

    private fun isCyrillicLetter(c: Char): Boolean = c in 'а'..'я' || c == 'ё' || c == 'і' ||
        c == 'ї' || c == 'є' || c == 'ґ' || c == 'ў' || c == 'ђ' || c == 'ћ'

    private fun isLatinLetter(c: Char): Boolean = c in 'a'..'z'

    /**
     * Язык по тексту книги: [BookLang.RU], [BookLang.UK], [BookLang.BE] или null,
     * если уверенности нет (короткий текст, смесь, не кириллица).
     */
    fun detect(book: BookDocument?): String? {
        if (book == null || !book.hasText) return null
        // Берём до двух тысяч предложений или ~20 тысяч букв — этого с избытком
        // хватает, а книга в сотни страниц не тормозит открытие.
        val sb = StringBuilder(24000)
        var taken = 0
        outer@ for (ch in book.chapters) {
            for (s in ch.sentences) {
                if (sb.length >= 20000) break@outer
                sb.append(s.text).append(' ')
                if (++taken >= 2000) break@outer
            }
        }
        return detect(sb)
    }

    /** Язык по произвольному тексту. null — не понять или не наш язык. */
    fun detect(text: CharSequence): String? {
        // Нормализация склеивает «у» с надстрочной крышечкой в «ў».
        val norm = runCatching { Normalizer.normalize(text, Normalizer.Form.NFC) }
            .getOrDefault(text.toString())
        var cyr = 0
        var lat = 0
        var iUkr = 0      // і
        var iRus = 0      // и
        var breve = 0     // ў
        var yi = 0        // ї
        var ye = 0        // є
        var ge = 0        // ґ
        // Идём словами: подмены ищем внутри слова, а не по всему тексту.
        var wordHasCyr = false
        val word = StringBuilder(32)
        fun flush() {
            if (word.isEmpty()) return
            // В кириллическом слове похожие латинские — это подмена.
            for (k in word.indices) {
                val ch = word[k]
                val c = if (wordHasCyr && isLatinLetter(ch)) foldLatin(ch) else ch
                when (c) {
                    'і' -> iUkr++
                    'и' -> iRus++
                    'ў' -> breve++
                    'ї' -> yi++
                    'є' -> ye++
                    'ґ' -> ge++
                }
            }
            word.setLength(0)
            wordHasCyr = false
        }
        for (ch in norm) {
            val c = ch.lowercaseChar()
            if (isCyrillicLetter(c)) {
                cyr++
                wordHasCyr = true
                word.append(c)
            } else if (isLatinLetter(c)) {
                lat++
                word.append(c)
            } else {
                flush()
            }
        }
        flush()
        if (cyr < MIN_LETTERS) return null
        // Латиницы почти столько же, сколько кириллицы — это не наша книга
        // (английский с вставками или наоборот): не гадаем.
        if (lat > cyr / 2) return null

        val n = cyr.toDouble()
        val ukMark = yi + ye + ge
        // Украинский: «ї», «є», «ґ» не встречаются ни в русском, ни в белорусском.
        if (ukMark >= maxOf(2, (n * 0.0004).toInt())) return UK
        // Белорусский: «ў» — его личная буква.
        if (breve >= maxOf(1, (n * 0.0002).toInt())) return BE
        // Белорусский без «ў» (так бывает в коротких отрывках): «і» вместо «и»,
        // причём «и» почти нет.
        if (iUkr >= maxOf(20, (n * 0.01).toInt()) && iRus < iUkr / 2) return BE
        // Русский: «и» есть, и его заметно много.
        if (iRus >= maxOf(20, (n * 0.02).toInt())) return RU
        return null
    }

    /** Один ли это язык. Коды приходят с двух сторон: от нас «ru»/«uk»/«be», а от
     *  Android — то «ru», то трёхбуквенное «rus»/«ukr»/«bel». Сравниваем по
     *  началу кода, иначе белорусский голос не узнали бы. */
    fun sameLang(a: String?, b: String?): Boolean {
        val x = base(a) ?: return false
        val y = base(b) ?: return false
        return x == y
    }

    /** ОСНОВНОЙ язык читалки (29.10.2026, просьба Сержа): «русский всегда
     *  основной — на русскую книгу не спрашивать».
     *
     *  Берём язык СИСТЕМЫ, если у движка есть его голоса (у Сержа в системе
     *  русский); иначе русский, если движок его знает; иначе первый язык движка.
     *  На книге этого языка вопрос «переключить голос?» не задаётся вообще —
     *  голос ставится молча ([MainActivity.askBookLanguage]). */
    fun preferred(c: android.content.Context, voices: List<android.speech.tts.Voice>): String? {
        if (voices.isEmpty()) return RU
        val system = java.util.Locale.getDefault().language.lowercase()
        if (system.isNotEmpty() && voices.any { sameLang(system, VoicePick.codeOf(it)) }) {
            return base(system) ?: system
        }
        if (voices.any { sameLang(RU, VoicePick.codeOf(it)) }) return RU
        return VoicePick.codeOf(voices.first()).takeIf { it.isNotEmpty() }
    }

    private fun base(code: String?): String? {
        val c = code?.trim()?.lowercase() ?: return null
        if (c.isEmpty()) return null
        return when {
            c.startsWith("ru") -> RU
            c.startsWith("uk") -> UK
            c.startsWith("be") -> BE
            else -> c
        }
    }

    // ——— Спросили ли уже про эту книгу ———
    private const val PREFS = "reader"
    private const val KEY_PREFIX = "lang_asked_"
    private const val SILENT = "silent"

    /** Ключ книги: адрес файла. Хэш — чтобы имя настройки не росло. */
    private fun key(uri: String): String = KEY_PREFIX + Integer.toHexString(uri.hashCode())

    /** Уже спрашивали про эту книгу (и про какой язык) — тогда молчим. */
    fun asked(c: Context, uri: String): String? = c
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(key(uri), null)

    /** Запомнить, что про эту книгу спросили (или человек велел не спрашивать). */
    fun remember(c: Context, uri: String, lang: String?, silent: Boolean = false) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key(uri), if (silent) SILENT else (lang ?: SILENT))
            .apply()
    }
}
