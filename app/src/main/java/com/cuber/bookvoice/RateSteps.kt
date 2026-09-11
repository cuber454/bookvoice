package com.cuber.bookvoice

/**
 * Значения ползунков скорости и тона (msg3929, msg3933).
 *
 * Скорость: 0.5–4.0 ровным шагом 0.1 — как и было прежде, только потолок выше
 * (msg3933: на 5.0 с укрупнённым шагом Сергей не согласился, хочет по одной
 * десятой на всём диапазоне).
 *
 * Тон оставлен прежним (0.5–2.0 шагом 0.1): выше двух он не значит ничего
 * хорошего, а привычку менять незачем.
 *
 * Значения — единственный источник и для ползунка в настройках
 * (SettingsActivity.addRateSlider), и для ползунков диалога голоса
 * (MainActivity.addRateSliderTo), и для кнопок «Медленнее/Быстрее»
 * (MainActivity.nudgeSpeed).
 */
object RateSteps {

    /** 0.5 … 4.0 ровным шагом 0.1 — 36 значений. */
    val SPEED: List<Float> = tenths(0.5f, 4.0f)

    /** 0.5 … 2.0 шагом 0.1 — как было. */
    val PITCH: List<Float> = tenths(0.5f, 2.0f)

    /** Десятые от [from] до [to] включительно. Число значений считается по
     *  границам, а не задано числом: потолок поменяется — список сам за ним
     *  потянется. */
    private fun tenths(from: Float, to: Float): List<Float> {
        val n = Math.round((to - from) * 10f)
        return (0..n).map { from + it * 0.1f }
    }

    /** Подпись значения: «1.0», «3.4». Через «%.2f» с отбрасыванием хвостового
     *  нуля, чтобы накопленная в float-сложении пыль (0.70000005) не всплыла
     *  в озвучке лишним знаком. */
    fun label(v: Float): String {
        val s = String.format(java.util.Locale.ROOT, "%.2f", v)
        return if (s.endsWith("0")) s.dropLast(1) else s
    }

    /** Ближайшее к [v] значение из [values]: им показываем сохранённую настройку,
     *  даже если она не совпадает с шагом (например, записана старой версией). */
    fun indexOf(values: List<Float>, v: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        for (i in values.indices) {
            val d = kotlin.math.abs(values[i] - v)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
    }

    /** Соседнее значение по списку — шаг «медленнее/быстрее». На границе
     *  возвращает то же значение: вызывающий сам решает, что это «дальше некуда». */
    fun neighbour(values: List<Float>, v: Float, up: Boolean): Float {
        val i = indexOf(values, v)
        val ni = (i + if (up) 1 else -1).coerceIn(0, values.size - 1)
        return values[ni]
    }
}
