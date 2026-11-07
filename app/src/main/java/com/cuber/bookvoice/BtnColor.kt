package com.cuber.bookvoice

import android.content.Context

/**
 * Цвет нижнего ряда кнопок на экране книги (30.10.2026, просьба Сержа).
 *
 * Жалоба: «кнопки внизу белого цвета, и ночью, когда зажигаешь экран, они бьют по
 * глазам». Ряд — «Пред.», «Читать/Пауза», «След.», «Голос» — самый яркий кусок
 * тёмного экрана, потому что кнопки нарисованы светлой заливкой темы.
 *
 * Три положения, и хватит:
 * - `THEME` — как сейчас, ничего не меняем (на случай, если новый вид не понравится:
 *   откат в один тап);
 * - `BLUE` — синяя заливка, белые надписи и значки: привычно глазу, яркость
 *   средняя, ночью не бьёт;
 * - `DARK` — почти чёрная заливка, светло-серые надписи: самый тихий вариант для
 *   тёмной комнаты.
 *
 * Красятся ВСЕ кнопки окна книги, а не только нижний ряд (30.10.2026, замечание
 * Сержа: «кнопки раскрашиваются только назад, вперёд и нижний ряд, а быстрее,
 * медленнее, главы так и остались белыми»). Перечень не пишем руками — обходим
 * окно и красим всё, что кнопка (`MainActivity.collectButtons`): иначе следующая
 * добавленная кнопка снова окажется белой.
 */
object BtnColor {

    /** Ключ в тех же prefs «reader», что и прочие настройки чтения. */
    const val KEY = "reader_btn_color"

    private const val THEME = "theme"
    private const val BLUE = "blue"
    private const val DARK = "dark"

    /** Значения в порядке показа в диалоге. */
    val VALUES = listOf(THEME, BLUE, DARK)

    fun labelRes(value: String): Int = when (value) {
        BLUE -> R.string.btn_color_blue
        DARK -> R.string.btn_color_dark
        else -> R.string.btn_color_theme
    }

    fun value(ctx: Context): String {
        val v = prefs(ctx).getString(KEY, THEME)
        return if (v in VALUES) v!! else THEME
    }

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    /** Цвет заливки; 0 — оставить как в теме. */
    fun bg(ctx: Context): Int = when (value(ctx)) {
        BLUE -> 0xFF1F4E8C.toInt()
        DARK -> 0xFF14171A.toInt()
        else -> 0
    }

    /** Обводка кнопок; 0 — какой была, обводку не трогаем.
     *
     *  30.10.2026, жалоба Сержа: «в чёрном цвете у кнопок нет рамок — чёрное на
     *  чёрном, кнопки как бы размылись». Рамка была только у тех кнопок, что и в
     *  теме нарисованы контуром (`materialButtonOutlinedStyle`), а «назад,
     *  пауза, вперёд, главы, скорость» были заливкой. Поэтому в тёмном варианте
     *  контур рисуем ВСЕМ кнопкам — он и отделяет кнопку от чёрной страницы. */
    fun stroke(ctx: Context): Int = when (value(ctx)) {
        DARK -> 0xFF5B6470.toInt()
        else -> 0
    }

    /** Толщина обводки в пикселях (1.5dp); 0 — не трогать. */
    fun strokePx(ctx: Context): Int =
        if (stroke(ctx) == 0) 0 else (1.5f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** Цвет надписей и значков; 0 — оставить как в теме. */
    fun fg(ctx: Context): Int = when (value(ctx)) {
        BLUE -> 0xFFFFFFFF.toInt()
        DARK -> 0xFFC9CDD2.toInt()
        else -> 0
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
