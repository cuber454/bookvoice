package com.cuber.bookvoice

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatSeekBar

/** Ползунок, у которого шаг подкачки скринридером задаём мы сами.
 *
 *  06.10.2026, жалоба Сержа: «регулирую скорость свайпами, скринридер
 *  перекручивает сразу по пять десятых, например 1,5, 2, 2,5 — как бы это
 *  сделать, чтобы перекручивался хотя бы по две десятых». Так ведёт себя сам
 *  Android: на свайп по ползунку скринридер шлёт «прокрутить вперёд/назад», а
 *  `ProgressBar` в ответ двигает значение на `round((max − min) / 20)` шагов, то
 *  есть на пятую часть всего диапазона. У скорости в ползунке 95 шагов по одной
 *  десятой, пятая часть округляется до пяти шагов — это и есть те самые пять
 *  десятых, которые слышал Серж.
 *
 *  Поэтому перехватываем эти два действия и двигаем на [a11yStep] шагов: у
 *  скорости шаг списка 0.1, значит `a11yStep = 2` даёт 0.2 на свайп.
 *
 *  06.10.2026, второй заход: шаг у скорости не одинаковый по всему диапазону, а
 *  свой на каждом участке — задаётся через [a11yStepOf] (`RateSteps.speedSwipeStep`).
 *  Причина в том, что ухо слышит ОТНОСИТЕЛЬНУЮ разницу: 0.1 на 0.8 — это 12%
 *  (слышно сразу), а 0.1 на 3.5 — 3% (не слышно вовсе). Потому внизу шаг мелкий,
 *  наверху крупный: так человек и находит свою скорость, и не крутит ползунок
 *  сотню раз.
 *
 *  Пальцем и мышью ползунок работает как раньше — перехвачены только действия
 *  скринридера. */
class RateSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    /** На сколько шагов ползунка двигать значение на одну подкачку скринридером
     *  (шаг ползунка — одно значение из списка `RateSteps`). */
    var a11yStep: Int = 1

    /** Свой шаг для каждого положения: получает текущий `progress` и возвращает,
     *  на сколько шагов двигать. Задан — [a11yStep] не смотрим. */
    var a11yStepOf: ((Int) -> Int)? = null

    /** Имя ползунка для журнала: берём у самого ползунка («Скорость чтения»),
     *  иначе просто «ползунок». */
    private val where: String
        get() = contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: "ползунок"

    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        val forward = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val backward = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (forward || backward) {
            // КРАЙ ШКАЛЫ НА ОЩУПЬ (09.10.2026). У самого края значение уже не
            // меняется, и человек не слышит НИЧЕГО: непонятно, дальше некуда или
            // свайп не дошёл. Отвечаем тем же языком, что край списка.
            if (forward && progress >= max) {
                A11y.scaleEdge(this, where, atBottom = true)
                return true
            }
            if (backward && progress <= 0) {
                A11y.scaleEdge(this, where, atBottom = false)
                return true
            }
            val step = (a11yStepOf?.invoke(progress) ?: a11yStep).coerceAtLeast(1)
            moveBy(if (forward) step else -step)
            return true
        }
        return super.performAccessibilityAction(action, args)
    }

    /** Пальцем довели до края — тот же толчок (09.10.2026): кто крутит ползунок
     *  рукой, слышит то же, что и свайпами диктора. Толчок только на ПЕРЕХОДЕ в
     *  край, а не на каждом движении внутри него. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val before = progress
        val handled = super.onTouchEvent(event)
        if (progress != before) {
            if (progress >= max && before < max) {
                A11y.scaleEdge(this, where, atBottom = true)
            } else if (progress <= 0 && before > 0) {
                A11y.scaleEdge(this, where, atBottom = false)
            }
        }
        return handled
    }

    /** Двинуть значение и сказать об этом скринридеру: без события он молчит,
     *  и человек не слышит, какая скорость стала. */
    private fun moveBy(delta: Int) {
        val next = (progress + delta).coerceIn(0, max)
        if (next == progress) return
        progress = next
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    }
}
