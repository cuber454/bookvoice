package com.cuber.bookvoice

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
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

    /** Именованные действия узла предложения (msg6352). Диктор показывает их
     *  словами в меню «Действия» — на случай, когда жест «двойной тап с
     *  удержанием» не срабатывает или неудобен. */
    private val actRead = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
        ACTION_ID_READ, context.getString(R.string.sel_action_read),
    )
    private val actMark = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
        ACTION_ID_MARK, context.getString(R.string.sel_action_mark),
    )

    /** Нажатие на предложение: индекс предложения внутри абзаца. */
    var onSentenceClick: ((Int) -> Unit)? = null

    /** Долгое нажатие на предложении: индекс предложения внутри абзаца. */
    var onSentenceLongClick: ((Int) -> Unit)? = null

    private val helper = object : ExploreByTouchHelper(this) {

        /** Узел под точкой касания. Мимо предложений — сам хост. */
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val i = sentenceAtPoint(x, y)
            // msg6338: диктор спрашивает узел под точкой — видно в diag.log.
            seenAt++
            logSparse("касание", seenAt) { "y=${y.toInt()}, предложение $i" }
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
            // msg6348: без этого диктор не считает узел удерживаемым, и жест
            // «двойной тап с удержанием» не начинал выделение фрагмента —
            // раньше это свойство выставлял сам TextView со слушателем.
            node.isLongClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            // Именованные действия (msg6352): диктор показывает их словами в
            // своём меню «Действия», и тогда выделение доступно не только
            // жестом «двойной тап с удержанием».
            // В меню «Действия» — только то, что про МЕСТО в книге (0.4.86,
            // msg7021): «Читать отсюда» и «Выделить фрагмент отсюда». Пункт
            // «продолжить/пауза» отсюда убран: Сергей сказал, что рядом с
            // «Читать отсюда» он звучал похоже и только мешал.
            node.addAction(actRead)
            node.addAction(actMark)
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
            // msg6352/6354: видно в diag.log, доходит ли до нас действие диктора
            // (двойной тап по тексту не срабатывал).
            seenAct++
            logSparse("действие", seenAct) { "предложение $i, action=$action" }
            if (i !in starts.indices) return false
            // msg6364: жест и действие на одно предложение — один и тот же
            // поступок, второй раз не повторяем.
            if (handledRecently(i)) return true
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> {
                    markHandled(i)
                    onSentenceClick?.invoke(i)
                    true
                }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> {
                    markHandled(i)
                    onSentenceLongClick?.invoke(i)
                    true
                }
                // Именованные действия из меню «Действия» (msg6352).
                ACTION_ID_READ -> {
                    markHandled(i)
                    onSentenceClick?.invoke(i)
                    true
                }
                ACTION_ID_MARK -> {
                    markHandled(i)
                    onSentenceLongClick?.invoke(i)
                    true
                }
                else -> false
            }
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
        // Касание по тексту попадает в предложение под пальцем — как раньше
        // попадало в строку-предложение (msg6348).
        //
        // msg6364: обработчики ставятся ВСЕГДА, в том числе при включённом
        // обходе касанием. Лог показал, что настоящее касание до текста всё
        // равно доходит (`палец #1: обход касанием: true`), а диктор при этом
        // своих ACTION_CLICK/ACTION_LONG_CLICK узлу не шлёт вовсе — за всю
        // сессию пришло одно действие, и то служебное (ACTION_SHOW_ON_SCREEN,
        // 16908342: диктор подводит узел к экрану). То есть прежняя защита
        // «при дикторе обработчики не ставим, там жестом владеет диктор»
        // оставляла такого пользователя вообще без жестов: палец доходил,
        // слушателей не было. Двойного срабатывания тут не будет: если диктор
        // когда-нибудь и позовёт ACTION_CLICK сам, его отсекает [handledRecently]
        // — жест и действие на одно предложение считаются одним.
        setOnClickListener {
            val i = sentenceAtPoint(downX, downY)
            if (i >= 0 && !handledRecently(i)) {
                logSparse("нажатие", ++seenTap) { "предложение $i (жест)" }
                onSentenceClick?.invoke(i)
            }
        }
        setOnLongClickListener {
            val i = sentenceAtPoint(downX, downY)
            if (i >= 0 && !handledRecently(i)) {
                logSparse("удержание", ++seenHold) { "предложение $i (жест)" }
                onSentenceLongClick?.invoke(i)
            }
            true
        }
    }

    /** Какое предложение и когда мы в последний раз обработали — чтобы жест и
     *  действие диктора на одно и то же предложение не сработали дважды. */
    private var lastIndex = -1
    private var lastAt = 0L

    private fun handledRecently(index: Int): Boolean =
        index == lastIndex && SystemClock.uptimeMillis() - lastAt < DOUBLE_MS

    private fun markHandled(index: Int) {
        lastIndex = index
        lastAt = SystemClock.uptimeMillis()
    }

    /** Точка последнего касания: по ней видно, какое предложение нажали. */
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            // msg6354: видно в diag.log, доходит ли до текста настоящее касание
            // (и включён ли обход касанием в этот момент).
            seenTouch++
            logSparse("палец", seenTouch) { "обход касанием: ${touchExploration()}" }
        }
        return super.onTouchEvent(event)
    }

    /** Включён ли обход касанием (диктор ведёт палец по экрану). */
    private fun touchExploration(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return am?.isTouchExplorationEnabled == true
    }

    private companion object {
        // Свои действия узла нумеруются с 0x01000000 — как в платформе
        // (AccessibilityNodeInfo.ACTION_ID_FIRST_CUSTOM_ACTION).
        const val ACTION_ID_READ = 0x01000001
        const val ACTION_ID_MARK = 0x01000002

        /** Окно, в котором жест и действие диктора считаются одним поступком. */
        const val DOUBLE_MS = 400L
    }

    /** Предложение под точкой (координаты представления); -1 — мимо.
     *  Счёт общий: им пользуется и диктор ([ExploreByTouchHelper.getVirtualViewAt]),
     *  и обычное нажатие без диктора. */
    fun sentenceAtPoint(x: Float, y: Float): Int {
        val l = layout ?: return -1
        val ly = y - totalPaddingTop
        if (ly < 0f || ly > l.height.toFloat()) return -1
        val line = l.getLineForVertical(ly.toInt())
        val offset = l.getOffsetForHorizontal(line, x - totalPaddingLeft)
        return sentenceAt(offset)
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
    private var seenAct = 0
    private var seenTouch = 0
    private var seenTap = 0
    private var seenHold = 0

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
