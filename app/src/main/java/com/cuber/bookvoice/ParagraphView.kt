package com.cuber.bookvoice

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * Абзац книги: обычный текст с переносами по словам, но каждое предложение
 * внутри — отдельный узел для экранного диктора (msg6322).
 *
 * Зачем так. Диктор (TalkBack, Jieshuo) знает гранулярность «знак, слово,
 * строка, абзац» — предложения в ней нет. Пока лента рисовала каждое
 * предложение отдельной строкой списка, диктор ходил по предложениям просто
 * потому, что строка = предложение. А зрячему это давало вид списка: каждое
 * предложение всегда начиналось с новой строки, перенос через границу
 * предложения был невозможен.
 *
 * Теперь строкой ленты стал абзац (см. [SentenceAdapter]), а предложения
 * отданы диктору виртуальными узлами: [ExploreByTouchHelper] описывает их
 * внутри одного TextView. Диктор свайпает по предложениям, зрячий видит
 * нормальный книжный текст.
 *
 * Сам абзац как узел диктору не отдаём (текст хоста гасится,
 * [ExploreByTouchHelper.onPopulateNodeForHost]): иначе он прочитал бы абзац
 * целиком, а следом ещё раз по предложениям.
 *
 * Если предложения не заданы ([bindSentences] не звали — так эту разметку
 * переиспользует окно «О программе»), поведение обычное: текст читается
 * целиком, как у любого TextView.
 */
class ParagraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** Начала предложений в [text] (смещения в символах). */
    private var starts = IntArray(0)

    /** Концы предложений в [text], [ends]\[i] — за последним символом. */
    private var ends = IntArray(0)

    /** Нажатие на предложение: индекс предложения внутри абзаца. */
    var onSentenceClick: ((Int) -> Unit)? = null

    /** Долгое нажатие на предложении: индекс предложения внутри абзаца. */
    var onSentenceLongClick: ((Int) -> Unit)? = null

    private val helper = object : ExploreByTouchHelper(this) {

        /** Узел под точкой касания. Мимо предложений — сам хост. */
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val l = layout ?: return HOST_ID
            val ly = y - totalPaddingTop
            if (ly < 0f || ly > l.height.toFloat()) return HOST_ID
            val line = l.getLineForVertical(ly.toInt())
            val offset = l.getOffsetForHorizontal(line, x - totalPaddingLeft)
            val i = sentenceAt(offset)
            // msg6338: диктор спрашивает узел под точкой — видно в diag.log.
            seenAt++
            logSparse("касание", seenAt) { "y=${y.toInt()}, предложение ${if (i >= 0) i else -1}" }
            return if (i >= 0) i else HOST_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in starts.indices) {
                // Пустые предложения (в книге бывает) пропускаем: узла без текста
                // диктору не отдаём.
                if (ends[i] > starts[i]) virtualViewIds.add(i)
            }
            // msg6338: диктор обошёл абзац — видно в diag.log, кто именно.
            seenNodes++
            logSparse("абзац", seenNodes) { "у диктора: предложений ${virtualViewIds.size}" }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val i = virtualViewId
            if (i !in starts.indices) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            node.text = text.subSequence(starts[i], ends[i])
            node.className = "android.widget.TextView"
            node.isFocusable = true
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            node.setBoundsInParent(boundsOfSentence(i))
        }

        /** Абзац целиком диктору не читаем: его читают предложения. Узел хоста
         *  остаётся пустой ёмкостью, и диктор в него не встаёт. */
        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            if (starts.isEmpty()) return  // обычный TextView (окно «О программе»)
            node.text = null
            node.contentDescription = null
            node.isFocusable = false
            node.isClickable = false
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val i = virtualViewId
            if (i !in starts.indices) return false
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    onSentenceClick?.invoke(i)
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    onSentenceLongClick?.invoke(i)
                    true
                }
                else -> false
            }
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
    }

    // Штатная часть подключения ExploreByTouchHelper (так же — в образце Google
    // «custom view accessibility»), и её в 0.4.49 не было — отсюда msg6338:
    // TalkBack не читал абзацы вовсе, Jieshuo читал (он обходится деревом
    // узлов). Касание с обходом приходит в представление событием наведения, а
    // клавиатурный обход — нажатием клавиши; и то и другое обязано дойти до
    // помощника, иначе виртуальных узлов для диктора просто нет. Смену фокуса
    // помощнику сообщаем отдельно — по ней он синхронизирует свой узел.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        seenHover++
        logSparse("наведение", seenHover) { "action=${event.action}" }
        return helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        seenKeys++
        logSparse("клавиша", seenKeys) { "код ${event.keyCode}, action=${event.action}" }
        return helper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        helper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    // Счётчики диагностики (msg6338): пишем в diag.log только первые три
    // вызова каждого вида и дальше каждый сотый — иначе лог забьётся касаниями.
    private var seenNodes = 0
    private var seenAt = 0
    private var seenHover = 0
    private var seenKeys = 0

    private fun logSparse(tag: String, n: Int, msg: () -> String) {
        if (n > 3 && n % 100 != 0) return
        Diag.log(context, "a11y", "$tag #$n: ${msg()}")
    }

    /** Задать границы предложений внутри уже выставленного [text].
     *  Звать после `text = …` — смещения считаются по его длине. */
    fun bindSentences(starts: IntArray, ends: IntArray) {
        this.starts = starts
        this.ends = ends
        helper.invalidateRoot()
    }

    /** Верх предложения внутри абзаца (для доводки прокрутки); -1 — не знаем
     *  такого предложения. */
    fun sentenceTop(index: Int): Int {
        if (index !in starts.indices) return -1
        return boundsOfSentence(index).top
    }

    /** Предложение, стоящее на этой высоте абзаца (координаты представления);
     *  диктор берёт им узел под пальцем, читалка — место книги по верхнему краю
     *  экрана: абзац выше экрана, и «верхняя строка» вовсе не начало абзаца.
     *  -1 — не нашли. */
    fun sentenceAtHeight(y: Float): Int {
        val l = layout ?: return -1
        val ly = y - totalPaddingTop
        if (ly < 0f || ly > l.height.toFloat()) return -1
        val line = l.getLineForVertical(ly.toInt())
        return sentenceAt(l.getLineStart(line))
    }

    /** Предложение по смещению в тексте; -1 — не нашли. Пробел между
     *  предложениями отдаём следующему: попадание в него (строка начинается с
     *  пробела после переноса) не должно оставлять диктора и читалку без места. */
    private fun sentenceAt(offset: Int): Int {
        for (i in starts.indices) {
            if (ends[i] <= starts[i]) continue
            if (offset < ends[i]) return i
        }
        return -1
    }

    /** Рамка предложения в координатах представления (для диктора). Считаем по
     *  разметке текста: предложение может занимать несколько строк, а строка —
     *  кончаться на середине соседнего предложения. */
    private fun boundsOfSentence(i: Int): Rect {
        val l = layout ?: return Rect(0, 0, 1, 1)
        val len = l.text.length
        val s = starts[i].coerceIn(0, len)
        val e = ends[i].coerceIn(s, len)
        val firstLine = l.getLineForOffset(s)
        val lastLine = if (e > s) l.getLineForOffset(e - 1) else firstLine
        val left = l.getPrimaryHorizontal(s).toInt()
        val right = if (e < len && l.getLineForOffset(e) == lastLine) {
            l.getPrimaryHorizontal(e).toInt()
        } else {
            l.getLineRight(lastLine).toInt()
        }
        val top = l.getLineTop(firstLine)
        val bottom = l.getLineBottom(lastLine)
        val r = Rect(left, top, maxOf(right, left + 1), maxOf(bottom, top + 1))
        r.offset(totalPaddingLeft, totalPaddingTop)
        return r
    }
}
