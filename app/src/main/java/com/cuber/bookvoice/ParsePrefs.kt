package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences

/**
 * НАСТРОЙКИ РАЗБОРА КНИГИ (15.10.2026, фаза «единый разбор книги»).
 *
 * Зачем отдельный объект. Разбор книги теперь общий для всех форматов
 * ([StructureClean]), и у него два уровня ручек:
 *  - ОСНОВНАЯ строка в настройках «Чтения» — «Разбор книги» с двумя положениями:
 *    [MODE_FILE] («как в файле» — ничего не трогаем) и [MODE_RULES] («правила» —
 *    наш общий шаг). По умолчанию — правила: они мгновенные, ничего не качают и
 *    уже размеченные книги не портят. Своё положение «как в файле» ЕСТЬ только у
 *    нас: у Reciter такого нет, там разбор либо включён, либо выключен
 *    (`docs/BACKLOG.md`, «У НИХ РАЗБОР ВКЛЮЧЁН ПО УМОЛЧАНИЮ»);
 *  - ЧАСТИ разбора — три галочки в опытных настройках ([KEY_JUNK], [KEY_MERGE],
 *    [KEY_HEADINGS]): нужны, если от одной части какая-то книга пострадает, —
 *    тогда выключаем часть, а не весь разбор.
 *
 * ПО КНИГЕ ОТДЕЛЬНО. Человек может сказать «в этой книге читать как в файле»
 * (меню «⋮» читалки → «Вернуть как в файле») или «разобрать заново». Это хранится
 * списком адресов книг ([KEY_OFF_BOOKS]): общий порядок задаётся в настройках, а
 * по конкретной книге всегда можно поправить, не трогая полку.
 *
 * Файл настроек — тот же «reader», что у [ExpertPrefs] и пометок движка: значения
 * переживают обновление приложения.
 */
object ParsePrefs {

    private const val PREFS = "reader"
    private const val KEY_MODE = "book_parse_mode"

    /** Книги, которым человек велел «читать как в файле» (адреса uri строкой). */
    private const val KEY_OFF_BOOKS = "book_parse_off"

    /** Книги, которым человек велел «разбирать по правилам», хотя общая настройка
     *  говорит другое (16.10.2026, сборка 263x). */
    private const val KEY_ON_BOOKS = "book_parse_on"

    /** Ключи частей разбора — открытые: строки настроек пишут по ним напрямую. */
    const val KEY_JUNK = "parse_part_junk"
    const val KEY_MERGE = "parse_part_merge"
    const val KEY_HEADINGS = "parse_part_headings"

    /** Ничего не трогаем: текст книги ровно такой, каким его отдал файл. */
    const val MODE_FILE = "file"

    /** Наш общий шаг разбора ([StructureClean]) — по умолчанию. */
    const val MODE_RULES = "rules"

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(c: Context): String =
        prefs(c).getString(KEY_MODE, MODE_RULES)?.takeIf { it.isNotEmpty() } ?: MODE_RULES

    fun setMode(c: Context, mode: String) {
        prefs(c).edit().putString(KEY_MODE, mode).apply()
    }

    /** Что именно делает общий шаг — см. [StructureClean.Options]. */
    fun parts(c: Context): StructureClean.Options = StructureClean.Options(
        junk = prefs(c).getBoolean(KEY_JUNK, true),
        merge = prefs(c).getBoolean(KEY_MERGE, true),
        headings = prefs(c).getBoolean(KEY_HEADINGS, true),
    )

    /** Отпечаток настроек разбора — для кэша разобранного текста ([BookCache]):
     *  сменили режим или любую часть — прежний текст книги больше не годится, и
     *  книга разберётся заново сама, без «очистить кэш» руками. */
    fun stamp(c: Context): Long {
        val o = parts(c)
        var s = if (mode(c) == MODE_FILE) 1L else 0L
        if (o.junk) s += 2L
        if (o.merge) s += 4L
        if (o.headings) s += 8L
        return s
    }

    /** Режим ДЛЯ КОНКРЕТНОЙ КНИГИ. Своя отметка книги сильнее общей настройки, и
     *  она бывает ДВУХ родов (16.10.2026, сборка 263x): «как в файле» и
     *  «по правилам». Второе нужно ровно затем, чтобы книгу можно было вернуть к
     *  чистке, даже если общая настройка стоит «как в файле» — иначе выбор
     *  «Разбирать по правилам» в читалке ничего бы не менял. */
    fun modeForBook(c: Context, uri: String): String = when {
        isOffForBook(c, uri) -> MODE_FILE
        isOnForBook(c, uri) -> MODE_RULES
        else -> mode(c)
    }

    fun isOffForBook(c: Context, uri: String): Boolean =
        prefs(c).getStringSet(KEY_OFF_BOOKS, emptySet())?.contains(uri) == true

    /** Книга, которой человек ВЕЛЕЛ разбирать по правилам, хотя общая настройка
     *  другая. */
    fun isOnForBook(c: Context, uri: String): Boolean =
        prefs(c).getStringSet(KEY_ON_BOOKS, emptySet())?.contains(uri) == true

    /** Поставить книге СВОЙ разбор: «как в файле» ([MODE_FILE]) или «по правилам»
     *  ([MODE_RULES]). Зовут из меню «⋮» читалки ([MainActivity.showParseDialog]).
     *  Отметки взаимоисключающие: книга не может быть сразу в двух списках. */
    fun setModeForBook(c: Context, uri: String, mode: String) {
        val e = prefs(c).edit()
        val off = (prefs(c).getStringSet(KEY_OFF_BOOKS, emptySet()) ?: emptySet()).toMutableSet()
        val on = (prefs(c).getStringSet(KEY_ON_BOOKS, emptySet()) ?: emptySet()).toMutableSet()
        if (mode == MODE_FILE) {
            off.add(uri)
            on.remove(uri)
        } else {
            on.add(uri)
            off.remove(uri)
        }
        e.putStringSet(KEY_OFF_BOOKS, off)
        e.putStringSet(KEY_ON_BOOKS, on)
        e.apply()
    }

    /** Снять с книги свою отметку: книга пойдёт по общей настройке. */
    fun clearBookMode(c: Context, uri: String) {
        val off = (prefs(c).getStringSet(KEY_OFF_BOOKS, emptySet()) ?: emptySet()).toMutableSet()
        val on = (prefs(c).getStringSet(KEY_ON_BOOKS, emptySet()) ?: emptySet()).toMutableSet()
        val wasOff = off.remove(uri)
        val wasOn = on.remove(uri)
        if (!wasOff && !wasOn) return
        prefs(c).edit().putStringSet(KEY_OFF_BOOKS, off).putStringSet(KEY_ON_BOOKS, on).apply()
    }

    /** Словами — то, что читалка показывает в строке состояния разбора и читает
     *  вслух по касанию. Чисел здесь нет намеренно (решение Сержа 15.10.2026):
     *  человеку важно одно — трогали книгу или нет. */
    fun modeWord(c: Context, mode: String): String =
        if (mode == MODE_FILE) c.getString(R.string.parse_mode_file) else c.getString(R.string.parse_mode_rules)
}
