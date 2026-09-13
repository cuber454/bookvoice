package com.cuber.bookvoice

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * Окно со строками-настройками (msg4993) — общий каркас для окон, которые
 * строят содержимое кодом: «Не засыпать», «Таймер сна», «Настройки таймера».
 *
 * Зачем база. Три окна рисуют одни и те же строки: подсказка-пояснение, строка
 * с приглушённым пояснением второй строкой, галочка-настройка. Раньше helpers
 * жили приватно в «Не засыпать»; копия в новом окне разошлась бы с оригиналом
 * на первой же правке внешнего вида (а внешний вид тут — доступность: класс
 * узла, `screenReaderFocusable`, размер пояснения). Здесь одна реализация.
 *
 * Конкретное окно даёт только [contentRoot] — свой скролл-контейнер.
 */
abstract class RowsActivity : SectionActivity() {

    /** Куда складывать строки: контент конкретного окна (у всех трёх — `content`
     *  внутри ScrollView своего layout-а). */
    protected abstract val contentRoot: ViewGroup

    /** Подсказка-пояснение: обычный текст без роли кнопки — скринридер читает его
     *  целиком, когда фокус встаёт на строку. */
    protected fun addHint(text: String): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(HINT_COLOR)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        contentRoot.addView(tv)
        return tv
    }

    /** Строка экрана: название, второй строкой — подсказка или состояние. Как
     *  строки Настроек (title + приглушённый hint). [onClick] = null — строка
     *  только сообщает (состояние, ответ действия), нажимать её нечего.
     *
     *  Класс узла подменяем на TextView: роль «кнопка» на каждой строке не нужна,
     *  двойной тап и фокус при этом сохраняются (тот же приём в Настройках).
     *  [tag] — метка строки: по ней окно возвращает фокус после пересборки. */
    protected fun addRow(
        title: String,
        hint: String?,
        strong: Boolean = false,
        tag: String? = null,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val v = TextView(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            if (strong) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = true
            ViewCompat.setScreenReaderFocusable(this, true)
            if (tag != null) this.tag = tag
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.TextView"
                }
            }
        }
        contentRoot.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )
        return v
    }

    /** Галочка-настройка (msg4721). Флажок сам говорит скринридеру «включено» или
     *  «выключено» — для переключателя это роднее, чем переписывать состояние в
     *  текст строки. Значение ложится в те же prefs «reader», что и прочие
     *  настройки чтения, и применяется на месте ([onChange]). */
    protected fun addCheck(
        title: String,
        hint: String,
        key: String,
        def: Boolean,
        tag: String? = null,
        onChange: (Boolean) -> Unit,
    ) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val v = CheckBox(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            isChecked = prefs.getBoolean(key, def)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            if (tag != null) this.tag = tag
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange(checked)
            }
        }
        contentRoot.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )
    }

    /** Вернуть фокус строке с меткой [tag] после пересборки содержимого: строка
     *  пересоздана, прежний View мёртв, а человек стоит на ней. */
    protected fun focusTag(tag: String) {
        val v = contentRoot.findViewWithTag<View>(tag) ?: return
        TabNav.a11yFocus(v)
    }

    /** Строка с меткой [tag] — null, если её сейчас в окне нет. */
    protected fun rowWithTag(tag: String): TextView? = contentRoot.findViewWithTag(tag)

    /** Сменить текст строки НА МЕСТЕ и сказать новое значение словами.
     *
     *  Почему не пересборкой содержимого (msg5005). Строка, на которой стоит
     *  человек, при `removeAllViews` умирает; перенос фокуса на только что
     *  добавленный View ридер пропускает — у того ещё нет размеров. В итоге
     *  новое значение не звучит, пока строку не тронешь заново: надпись сменилась
     *  на «сильная», а голос промолчал. Обновление на месте оставляет и строку,
     *  и курсор живыми, а значение проговариваем сами — тем же приёмом, что
     *  скорость и позиция в читалке ([View.announceForAccessibility]). */
    protected fun updateRow(row: TextView, title: String, hint: String?) {
        row.text = withHint(title, hint)
        row.announceForAccessibility(title)
    }

    /** Строка с подсказкой второй строкой: название обычным, пояснение — мельче и
     *  серым. Общее для строк-действий и галочки, поэтому вынесено сюда. */
    protected fun withHint(title: String, hint: String?): CharSequence =
        SpannableStringBuilder().apply {
            append(title)
            if (hint != null) {
                append("\n")
                val start = length
                append(hint)
                setSpan(RelativeSizeSpan(0.76f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(HINT_COLOR), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Те же настройки чтения, что и у читалки: `MainActivity.prefs` и
         *  [KeepAwake] читают этот же файл. */
        private const val PREFS = "reader"

        /** Цвет пояснения второй строкой — тот же, что у строк Настроек
         *  (не `const`: литерал с `toInt()` константой не считается). */
        private val HINT_COLOR = 0xFF9AA0A6.toInt()
    }
}
