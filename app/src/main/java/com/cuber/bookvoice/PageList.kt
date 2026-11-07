package com.cuber.bookvoice

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import androidx.recyclerview.widget.RecyclerView

/**
 * Лента книги, которая листается СТРАНИЦАМИ (30.10.2026, просьба тестера).
 *
 * Зачем. Свободная прокрутка на книге неудобна: махнул коротко — сдвинулось на
 * полстроки, махнул длинно — улетело на полторы страницы. Тестер просил, чтобы
 * «независимо от того, какой свайп, длинный или короткий, текст перелистывался
 * экранами сразу целиком», как в бумажной книге.
 *
 * Правило простое: ЛЮБОЙ жест вверх-вниз — ровно одна страница. Тянуть середину
 * страницы нельзя. То же и для свайпа экранного диктора: платформа присылает
 * прокрутку как доступностное действие ([performAccessibilityAction]), и мы
 * отвечаем на него страницей, а не плавной прокруткой, — иначе у незрячего и у
 * зрячего листание вело бы себя по-разному.
 *
 * Сколько пикселей в странице и что делать после переворота, знает читалка: она
 * задаёт [pageHeight] и [onFlipped] (там место книги переходит на верхнюю строку
 * новой страницы, см. `MainActivity.onPageFlipped`).
 *
 * Тянуть пальцем сам список по-прежнему умеет — это нужно диктору и прокрутке
 * вокруг читаемого предложения; жест страницы перехватывает читалка
 * (`MainActivity.installSwipe`), потому что ей же надо отличать вертикальный жест
 * от горизонтального свайпа.
 */
class PageList @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    /** Высота страницы в пикселях (видимая часть без полей). Спрашивается в
     *  момент переворота: экран мог повернуться или полоса подсказки уехать. */
    var pageHeight: (() -> Int)? = null

    /** Страница перевернулась: [dir] = +1 вперёд, -1 назад. */
    var onFlipped: ((Int) -> Unit)? = null

    /** Перевернуть страницу. Дальше конца книга не едет — `scrollBy` сам
     *  обрежется, а если сдвига не вышло, читалке это знать безразлично. */
    fun flip(dir: Int): Boolean {
        val h = pageHeight?.invoke() ?: 0
        if (h <= 0) return false
        scrollBy(0, (if (dir >= 0) 1 else -1) * h)
        onFlipped?.invoke(if (dir >= 0) 1 else -1)
        return true
    }

    /** Свайп экранного диктора вверх/вниз — тоже страница. */
    override fun performAccessibilityAction(action: Int, args: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (flip(1)) return true
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (flip(-1)) return true
        }
        return super.performAccessibilityAction(action, args)
    }
}
