package com.cuber.bookvoice

import android.content.Context
import kotlin.math.roundToInt

/**
 * Размер шрифта КНИГИ (30.10.2026, просьба тестера).
 *
 * Было три положения: «обычный / крупный / очень крупный». Просьба пришла с
 * другой стороны — мельче: на экран влезало два абзаца, и уйти от этого было
 * некуда. Теперь одно ЧИСЛО в sp (тех же единицах, которыми Android мерит
 * текст): 12…50 шагом 2, где 20 — прежний «обычный». Число одно на ВСЕ книги:
 * настроил под глаза и забыл, а не «в этой книге мелко, а в той крупно».
 *
 * Ручки у значения ДВЕ, а значение одно — поэтому читает и пишет его только
 * этот файл: ползунок в Настройках → «Чтение» ([SettingsActivity.addFontSlider])
 * и щипок двумя пальцами прямо на странице книги ([MainActivity.installPinch]).
 *
 * Почему меняется только книга. Строки настроек, полка и меню продолжают
 * слушаться системного размера шрифта Android: мельче обычного там делать
 * нечего (строка станет неудобной для пальца), а крупнее человек при желании
 * ставит системной ручкой. Прежняя ручка TextScale поднимала ВСЁ окно сразу —
 * она ушла вместе с тремя положениями, а её значение переносится сюда
 * ([fromLegacy]), чтобы у выбравших «крупный» размер книги не откатился.
 */
object BookFont {

    /** Ключ в тех же prefs «reader», что и прочие настройки чтения. */
    const val KEY = "book_font_sp"

    /** Ключ снятой ручки «размер текста: обычный / крупный / очень крупный» —
     *  читаем его только для переноса значения. */
    private const val LEGACY_KEY = "text_scale"

    /** Прежний обычный размер текста книги (item_paragraph.xml, 20sp). */
    const val DEFAULT = 20

    const val MIN = 12
    const val MAX = 50
    const val STEP = 2

    /** Номер последнего положения шкалы: 12, 14, …, 50 — двадцать положений. */
    const val LAST_TICK = (MAX - MIN) / STEP

    /** Сколько sp стоит на положении ползунка [tick] (0 — самое мелкое). */
    fun spOf(tick: Int): Int = MIN + tick.coerceIn(0, LAST_TICK) * STEP

    /** На каком положении шкалы стоит [sp]. */
    fun tickOf(sp: Int): Int = ((sp.coerceIn(MIN, MAX) - MIN) / STEP)

    /** Ближайшее положение шкалы к произвольному числу — нужен щипку: он даёт
     *  множитель, а не готовый размер. */
    fun snap(sp: Float): Int = ((sp / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)

    /** Что стоит сейчас. */
    fun sp(ctx: Context): Int {
        val p = prefs(ctx)
        val saved = p.getInt(KEY, -1)
        if (saved >= 0) return saved.coerceIn(MIN, MAX)
        // Первое обращение после обновления: переносим прежнюю ступеньку, чтобы
        // у выбравших «крупный» или «очень крупный» книга не стала вдруг мелкой.
        val sp = fromLegacy(p.getString(LEGACY_KEY, null))
        p.edit().putInt(KEY, sp).apply()
        return sp
    }

    /** Нынешнее положение шкалы. */
    fun tick(ctx: Context): Int = tickOf(sp(ctx))

    /** Запомнить размер (округляем к положению шкалы: щипок даёт любое число). */
    fun set(ctx: Context, sp: Int) {
        prefs(ctx).edit().putInt(KEY, snap(sp.toFloat())).apply()
    }

    /** Прежние три положения — в число: обычный 20, крупный 24, очень крупный 30
     *  (было 20 × 1,25 и 20 × 1,5, округлённые к шагу 2). */
    private fun fromLegacy(v: String?): Int = when (v) {
        "large" -> 24
        "huge" -> 30
        else -> DEFAULT
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
