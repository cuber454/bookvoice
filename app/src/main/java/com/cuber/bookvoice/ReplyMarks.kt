package com.cuber.bookvoice

import android.content.Context

/**
 * Знаки, которыми в книге помечена прямая речь: настройка и её хранение
 * (30.09.2026).
 *
 *  Зачем понадобилось. Разбор ([Roles]) узнаёт реплику по длинному тире в начале
 *  фразы, и это верно для большинства книг. Но разметка бывает и другой: в
 *  сборнике анекдотов диалог помечен дефисом после номера («#8.1. - Зачем…»), в
 *  иных книгах — звёздочкой или кавычкой. Раньше каждая такая книга требовала
 *  правки кода; теперь знаки задаются в окне «Чтение по ролям», а приложение
 *  само показывает, чем фразы в открытой книге начинаются.
 *
 *  Как хранится. Есть ОБЩИЙ набор знаков — он работает во всех книгах, и обычно
 *  его хватает. Плюс галочка «Запомнить для этой книги»: тогда набор и правило
 *  «после номера» запоминаются за конкретной книгой, а остальные книги живут с
 *  общим набором. Действующий набор всегда один, и [of] его и отдаёт.
 */
object ReplyMarks {

    /** Реплика после номера («#8.1. - Зачем…»). По умолчанию включено. */
    const val KEY_NUMBER = "reply_marks_number"

    /** Свои знаки реплики, через пробел: «*», «-», ««», можно и слово. */
    const val KEY_MARKS = "reply_marks_own"

    /** Приставки книжного набора: наличие ключа и означает «у книги свой набор». */
    private const val BOOK_MARKS = "reply_marks_book_"
    private const val BOOK_NUMBER = "reply_num_book_"
    private const val BOOK_REMEMBER = "reply_marks_remember_"

    private const val MAX_MARKS = 8
    private const val MAX_MARK_LEN = 16

    fun prefs(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Действующий набор знаков: свой у книги, если он для неё сохранён, иначе
     *  общий. [book] — имя файла книги (как в словаре), null — книга не открыта. */
    data class Marks(val list: List<String>, val number: Boolean, val fromBook: Boolean) {
        /** Словами для строки состояния: «длинное тире» наследие разбора, поэтому
         *  его в наборе не перечисляем — говорим про свои знаки и про номер. */
        fun words(): String {
            val own = list.joinToString(", ") { Roles.markName(it) }
            return when {
                own.isEmpty() && number -> "номер с тире"
                own.isEmpty() -> "только длинное тире"
                number -> "$own, номер с тире"
                else -> own
            }
        }
    }

    fun of(c: Context, book: String?): Marks {
        val p = prefs(c)
        val key = book?.trim()?.takeIf { it.isNotEmpty() }?.let { Dict.key(it) }
        if (key != null && p.contains(BOOK_MARKS + key)) {
            return Marks(
                parse(p.getString(BOOK_MARKS + key, "").orEmpty()),
                p.getBoolean(BOOK_NUMBER + key, true),
                true,
            )
        }
        return Marks(
            parse(p.getString(KEY_MARKS, "").orEmpty()),
            p.getBoolean(KEY_NUMBER, true),
            false,
        )
    }

    /** Сохранить набор: в книжный, если для этой книги включено «запомнить»,
     *  иначе в общий. Сразу применяет его к разбору. */
    fun save(c: Context, book: String?, list: List<String>, number: Boolean) {
        val p = prefs(c)
        val key = book?.trim()?.takeIf { it.isNotEmpty() }?.let { Dict.key(it) }
        val remembered = key != null && p.getBoolean(BOOK_REMEMBER + key, false)
        val clean = normalize(list)
        if (remembered) {
            p.edit()
                .putString(BOOK_MARKS + key, clean.joinToString(" "))
                .putBoolean(BOOK_NUMBER + key, number)
                .apply()
        } else {
            p.edit()
                .putString(KEY_MARKS, clean.joinToString(" "))
                .putBoolean(KEY_NUMBER, number)
                .apply()
        }
        apply(c, book)
        Diag.log(
            c, "roles",
            "знаки реплики: ${if (clean.isEmpty()) "нет" else clean.joinToString(" ")}" +
                ", после номера ${if (number) "да" else "нет"}, " +
                if (remembered) "для книги «$book»" else "общие",
        )
    }

    /** Добавить один знак к действующему набору (действие из книги). Возвращает
     *  набор, который получился. */
    fun addMark(c: Context, book: String?, mark: String): Marks {
        val cur = of(c, book)
        save(c, book, cur.list + mark, cur.number)
        return of(c, book)
    }

    /** Запомнить набор за книгой или вернуть книгу к общему набору. */
    fun setRemember(c: Context, book: String?, on: Boolean) {
        val key = book?.trim()?.takeIf { it.isNotEmpty() }?.let { Dict.key(it) } ?: return
        val p = prefs(c)
        if (on) {
            val common = of(c, null)
            p.edit()
                .putBoolean(BOOK_REMEMBER + key, true)
                .putString(BOOK_MARKS + key, common.list.joinToString(" "))
                .putBoolean(BOOK_NUMBER + key, common.number)
                .apply()
        } else {
            p.edit()
                .remove(BOOK_REMEMBER + key)
                .remove(BOOK_MARKS + key)
                .remove(BOOK_NUMBER + key)
                .apply()
        }
        apply(c, book)
        Diag.log(
            c, "roles",
            "знаки реплики: ${if (on) "свой набор для книги «$book»" else "вернул книгу «$book» к общим знакам"}",
        )
    }

    /** Включено ли «запомнить для этой книги» (для строки-галочки). */
    fun remembered(c: Context, book: String?): Boolean {
        val key = book?.trim()?.takeIf { it.isNotEmpty() }?.let { Dict.key(it) } ?: return false
        return prefs(c).getBoolean(BOOK_REMEMBER + key, false)
    }

    /** Поставить действующий набор в разбор. Зовётся при открытии книги и после
     *  каждой правки: сам [Roles] о настройках не знает. */
    fun apply(c: Context, book: String?) {
        val m = of(c, book)
        Roles.setMarks(m.list, m.number)
    }

    /** Знаки из строки: по пробелам, повторы убираем, длинные обрезаем. */
    private fun parse(s: String): List<String> =
        normalize(s.split(' ', '\n', '\t', ',').map { it.trim() })

    /** То же для окна правки: то, что человек вписал в поле. */
    fun fromText(s: String): List<String> = parse(s)

    private fun normalize(src: List<String>): List<String> =
        src.map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_MARK_LEN) it.substring(0, MAX_MARK_LEN) else it }
            .distinct()
            .take(MAX_MARKS)
}
