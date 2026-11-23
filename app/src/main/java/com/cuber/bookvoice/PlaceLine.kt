package com.cuber.bookvoice

import android.content.Context

/**
 * ЧТО ПИСАТЬ В СТРОКЕ МЕСТА на экране книги (12.11.2026, просьба Сержа).
 *
 * История: 30.10.2026 Серж попросил убрать номер предложения из строки места
 * («· 271 из 614» — номера ничего не говорят), а 12.11.2026 попросил вернуть:
 * «чтобы предложение тоже показывало, сколько прочитано — глава 5, предложение
 * 185». Чтобы не переделывать это в третий раз по вкусу, выбор отдан человеку:
 *
 * - `BOTH` — «Глава 5 из 21 · предложение 185» (по умолчанию: счётчик
 *   прочитанного виден);
 * - `CHAPTER` — «Глава 5 из 21» — прежнее поведение.
 *
 * Строку собирает [ReaderEngine.chapterPlaceLine] (`withSentence`), а показывает
 * `MainActivity.updatePosition`. Карточка в шторке и объявление места кнопкой
 * гарнитуры этой настройки НЕ слушают: там предложения не показываем.
 */
object PlaceLine {

    /** Ключ в тех же prefs «reader», что и прочие настройки чтения. */
    const val KEY = "place_line_mode"

    private const val BOTH = "both"
    private const val CHAPTER = "chapter"

    /** Значения в порядке показа в диалоге: он же порядок строк. */
    val VALUES = listOf(BOTH, CHAPTER)

    /** Подписи пунктов говорят, что человек УВИДИТ в строке. */
    fun labelRes(value: String): Int = when (value) {
        CHAPTER -> R.string.place_line_chapter
        else -> R.string.place_line_both
    }

    /** Что выбрано сейчас. Незнакомое значение (prefs с будущей версии) читаем
     *  как «глава и предложение», а не падаем. */
    fun value(ctx: Context): String {
        val v = prefs(ctx).getString(KEY, BOTH)
        return if (v in VALUES) v!! else BOTH
    }

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    /** Показывать ли номер предложения. */
    fun withSentence(ctx: Context): Boolean = value(ctx) != CHAPTER

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
