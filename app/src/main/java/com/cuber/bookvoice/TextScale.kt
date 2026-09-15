package com.cuber.bookvoice

import android.content.Context
import android.content.res.Configuration

/**
 * Размер текста (msg5730) — одна ручка на всё приложение: обычный / крупный /
 * очень крупный. Просьба Сергея: читалкой должны уметь пользоваться
 * слабовидящие, а не только незрячие.
 *
 * Почему множитель конфигурации, а не обход надписей. В приложении ВСЕ размеры
 * заданы в sp — и в разметке, и в коде (аудит 2026-09-15: 40 значений, ни одного
 * в dp/px, ни одного `setTextSize` с чужой единицей). Значит, довольно одного
 * [Configuration.fontScale] на окно: он поднимает разом и строки Настроек, и
 * текст книги, и заголовки. Обходить сорок надписей по одной — гарантированно
 * забыть новую.
 *
 * Почему ПОВЕРХ системного. Своя ручка «размер шрифта» есть у самого Android, и
 * человек мог ей уже воспользоваться. Наша её не отменяет, а продолжает:
 * системный множитель читается и умножается на наш. Приложение и раньше слушало
 * системный (никаких своих переопределений в нём не было), так что «обычный»
 * оставляет всё ровно как было.
 *
 * Потолок [MAX] — чтобы «очень крупный» поверх уже выкрученной системы не
 * превратил окно в одну строку на экран.
 *
 * Применение — в `attachBaseContext` каждого окна ([wrap]): поднятую
 * конфигурацию получают и разметка, и все строки, построенные кодом.
 */
object TextScale {

    /** Ключ в тех же prefs «reader», что и прочие настройки чтения. */
    const val KEY = "text_scale"

    private const val NORMAL = "normal"
    private const val LARGE = "large"
    private const val HUGE = "huge"

    /** Значения в порядке показа в диалоге: он же порядок строк. */
    val VALUES = listOf(NORMAL, LARGE, HUGE)

    /** Подписи строк диалога — в нижнем регистре: та же строка идёт в резюме
     *  строки настроек («Размер текста: крупный»). */
    fun labelRes(value: String): Int = when (value) {
        LARGE -> R.string.text_scale_large
        HUGE -> R.string.text_scale_huge
        else -> R.string.text_scale_normal
    }

    /** Во сколько раз крупнее обычного. */
    fun factor(value: String): Float = when (value) {
        LARGE -> 1.25f
        HUGE -> 1.5f
        else -> 1f
    }

    /** Потолок итогового множителя (системный × наш). */
    private const val MAX = 2.4f

    /** Что выбрано сейчас. Незнакомое значение (prefs с будущей версии) читаем
     *  как обычный размер, а не падаем. */
    fun value(ctx: Context): String {
        val v = prefs(ctx).getString(KEY, NORMAL)
        return if (v in VALUES) v!! else NORMAL
    }

    fun set(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY, value).apply()
    }

    /** Контекст окна с поднятым размером текста. При обычном размере отдаём
     *  контекст как есть: лишняя обёртка ничего не меняет, а стоит ресурсов. */
    fun wrap(base: Context): Context {
        val f = factor(value(base))
        if (f == 1f) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.fontScale = (cfg.fontScale * f).coerceAtMost(MAX)
        return base.createConfigurationContext(cfg)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
