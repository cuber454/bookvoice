package com.cuber.bookvoice

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * Цвета интерфейса для всего, что строится КОДОМ (msg5730).
 *
 * Зачем объект, а не атрибуты темы на месте. Разметка берёт цвет прямо у темы
 * (`?attr/bvInk`), а строки, которые окна собирают кодом, — сорок с лишним
 * `setTextColor` по всем файлам — так не умеют: у них на руках только число.
 * Здесь одно место, где атрибуты темы превращаются в числа ([init]), и дальше
 * код спрашивает цвет у палитры.
 *
 * Почему значения, а не геттеры с контекстом. Поля меняются РОВНО ОДИН раз за
 * жизнь окна — в его `onCreate`, до первой построенной строки. Разносить
 * `Context` по сорока вызовам ради этого не нужно.
 *
 * Значения по умолчанию — сегодняшние цвета приложения. Пока [init] не позван
 * (или атрибут почему-то не разрешился), всё выглядит как раньше: палитра не
 * может сделать хуже, чем было.
 *
 * Потокобезопасность: пишет только UI-поток в onCreate, читает тоже UI-поток
 * при построении экранов. Гонки нет.
 */
object Palette {

    /** Настройка «высокая контрастность» — в тех же prefs «reader». */
    const val KEY = "high_contrast"

    var BG = 0xFF101418.toInt(); private set
    var PANEL = 0xFF1A232B.toInt(); private set
    var INK = 0xFFE8EAED.toInt(); private set
    var DIM = 0xFF9AA0A6.toInt(); private set
    var ACCENT = 0xFF8AB4F8.toInt(); private set
    var TRACK = 0xFF3C4043.toInt(); private set
    var DANGER = 0xFFEF9A9A.toInt(); private set
    var SEL_CURRENT = 0xFF33404C.toInt(); private set
    var SEL_ANCHOR = 0xFF4B3A66.toInt(); private set
    var SEL_RANGE = 0xFF37415C.toInt(); private set

    /** Включена ли высокая контрастность. */
    fun contrastOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY, false)

    fun setContrast(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, on).apply()
    }

    /**
     * Подготовить окно: выбрать тему и прочитать из неё палитру. Зовётся ПЕРВЫМ
     * делом в `onCreate`, до `super.onCreate` — `setTheme` обязан случиться
     * раньше, чем окно построит первую разметку.
     */
    fun applyTo(act: Activity) {
        if (contrastOn(act)) act.setTheme(R.style.Theme_BookVoice_Contrast)
        init(act)
    }

    private fun init(ctx: Context) {
        BG = attr(ctx, R.attr.bvBg, BG)
        PANEL = attr(ctx, R.attr.bvPanel, PANEL)
        INK = attr(ctx, R.attr.bvInk, INK)
        DIM = attr(ctx, R.attr.bvDim, DIM)
        ACCENT = attr(ctx, R.attr.bvAccent, ACCENT)
        TRACK = attr(ctx, R.attr.bvTrack, TRACK)
        DANGER = attr(ctx, R.attr.bvDanger, DANGER)
        SEL_CURRENT = attr(ctx, R.attr.bvSelCurrent, SEL_CURRENT)
        SEL_ANCHOR = attr(ctx, R.attr.bvSelAnchor, SEL_ANCHOR)
        SEL_RANGE = attr(ctx, R.attr.bvSelRange, SEL_RANGE)
    }

    /** Значение атрибута темы. [fallback] — если тема его не объявила. */
    private fun attr(ctx: Context, id: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(id, tv, true)) return fallback
        return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
