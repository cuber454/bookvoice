package com.cuber.bookvoice

import android.content.Context

/**
 * Цвет подсветки ЧИТАЕМОЙ фразы (30.10.2026, просьба Сержа и тестера).
 *
 * На чёрной теме подсветка из темы (тёмно-сине-серая `#3E5262`) на чёрном
 * фоне почти не видна: «серенькая, её плохо видно». Здесь один выбор на все
 * случаи жизни — и для тех, кто видит цвет, и для тех, кто различает только
 * светлое и тёмное:
 *
 * - `THEME` — как было, цвет темы (ничего не меняем);
 * - `INVERSE` — ОБРАТНЫЕ цвета страницы: фон становится цветом букв, а буквы —
 *   цветом фона. В чёрной теме это белая подсветка с чёрными буквами, и видно её
 *   лучше всего при любой болезни глаз: не нужно различать оттенки;
 * - `BLUE` — синяя с белыми буквами (цвет видит большинство);
 * - `YELLOW` — жёлтая с почти чёрными буквами (самый заметный «маркер»).
 *
 * Красим ТОЛЬКО читаемое предложение: выделение фрагмента и закладки живут
 * своими цветами ([Palette.SEL_ANCHOR], [Palette.SEL_RANGE]).
 *
 * `INVERSE` берёт цвета у [Palette], а не зашитые: если человек сменит тему или
 * высокую контрастность, «обратные» останутся обратными к НОВОЙ странице.
 */
object SelColor {

    /** Ключ в тех же prefs «reader», что и прочие настройки чтения. */
    const val KEY = "sel_current_color"

    private const val THEME = "theme"
    private const val INVERSE = "inverse"
    private const val BLUE = "blue"
    private const val YELLOW = "yellow"

    /** Значения в порядке показа в диалоге: он же порядок строк. */
    val VALUES = listOf(THEME, INVERSE, BLUE, YELLOW)

    /** Подписи пунктов. Название говорит, ЧТО человек увидит, а не как это
     *  называется в палитре: «белый фон, чёрные буквы» понятнее, чем «инверсия». */
    fun labelRes(value: String): Int = when (value) {
        INVERSE -> R.string.sel_color_inverse
        BLUE -> R.string.sel_color_blue
        YELLOW -> R.string.sel_color_yellow
        else -> R.string.sel_color_theme
    }

    /** Что выбрано сейчас. Незнакомое значение (prefs с будущей версии) читаем
     *  как «как сейчас», а не падаем. */
    fun value(ctx: Context): String {
        val v = prefs(ctx).getString(KEY, THEME)
        return if (v in VALUES) v!! else THEME
    }

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    /** Цвет фона подсветки читаемой фразы. Синий — ТЁМНО-синий: 30.10.2026 Серж
     *  сказал, что первый вариант «светлит», и попросил «прям вот тёмно-синий»,
     *  чтобы белые буквы не сливались. */
    fun bg(ctx: Context): Int = when (value(ctx)) {
        INVERSE -> Palette.INK
        BLUE -> 0xFF16346B.toInt()
        YELLOW -> 0xFFF2C94C.toInt()
        else -> Palette.SEL_CURRENT
    }

    /** Цвет букв на подсветке; 0 — не перекрашивать (буквы остаются цвета
     *  страницы). */
    fun fg(ctx: Context): Int = when (value(ctx)) {
        INVERSE -> Palette.BG
        BLUE -> 0xFFFFFFFF.toInt()
        YELLOW -> 0xFF101418.toInt()
        else -> 0
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
