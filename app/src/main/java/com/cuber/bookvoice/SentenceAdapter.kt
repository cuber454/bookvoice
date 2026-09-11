package com.cuber.bookvoice

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Список всей книги одной лентой («портянка», msg4292/4308). Раньше здесь была
 * одна глава и кнопка «Следующая глава» в конце — из-за этого на границе главы
 * список упирался в тупик. Теперь главы идут подряд, между ними — строка с
 * названием главы (когда оно есть в книге).
 *
 * Читалка продолжает думать в терминах «глава + предложение»: в этой системе
 * живут закладки, история переходов, поиск, выделение и само чтение. Поэтому
 * адаптер держит перевод между двумя мирами: [flatOf] — из «глава, предложение»
 * в строку ленты, [placeOf] — обратно.
 *
 * Прокрутка — обычная, родная для RecyclerView: рука и чтение двигают ОДИН
 * список, поэтому назад из текста в титульную страницу и начало книги теперь
 * можно вернуться свободно.
 */
class SentenceAdapter(
    private val onSentenceClick: (Int) -> Unit,
    private val onSentenceLongClick: (Int) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Строка ленты: заголовок главы ([sentence] == null) или предложение.
     *  [index] — номер предложения внутри главы, у заголовка -1. */
    private class Row(val chapter: Int, val index: Int, val title: String?, val sentence: Sentence?)

    private val rows = ArrayList<Row>()

    /** С какой строки ленты начинается глава; размер — число глав. */
    private var rowStart = IntArray(0)

    private var current = -1

    // #105: выделение фрагмента. anchor — начало (подсвечивается), selFrom/selTo —
    // границы отмеченного куска. Здесь это строки ленты, а не номера в главе:
    // фрагмент может пересекать границу глав, и подсветка это покажет.
    private var anchor = -1
    private var selFrom = -1
    private var selTo = -1

    val isEmpty: Boolean get() = rows.isEmpty()

    /** Собрать ленту по всей книге. Заголовок главы отдельной строкой появляется
     *  только если он в книге есть и не повторяет первое предложение главы
     *  (движок речи уже пропускает такой дубль — см. ReaderEngine.spokenText). */
    fun submitBook(chapters: List<Chapter>) {
        rows.clear()
        rowStart = IntArray(chapters.size)
        for (ch in chapters.indices) {
            rowStart[ch] = rows.size
            val c = chapters[ch]
            val title = c.title?.trim().orEmpty()
            val first = c.sentences.firstOrNull()?.text?.trim()
            if (title.isNotEmpty() && title != first) rows.add(Row(ch, -1, title, null))
            for (s in c.sentences.indices) {
                rows.add(Row(ch, s, null, c.sentences[s]))
            }
        }
        current = -1
        anchor = -1
        selFrom = -1
        selTo = -1
        notifyDataSetChanged()
    }

    fun clear() {
        if (rows.isEmpty()) return
        rows.clear()
        rowStart = IntArray(0)
        current = -1
        anchor = -1
        selFrom = -1
        selTo = -1
        notifyDataSetChanged()
    }

    /** Сколько предложений в главе (уже посчитано при сборке ленты). */
    private fun sentenceCount(chapter: Int): Int {
        val end = if (chapter + 1 < rowStart.size) rowStart[chapter + 1] else rows.size
        var n = end - rowStart[chapter]
        if (n > 0 && rows[rowStart[chapter]].sentence == null) n--
        return n.coerceAtLeast(0)
    }

    /** Строка ленты для места чтения. -1 — главы нет или в ней нет предложений. */
    fun flatOf(chapter: Int, sentence: Int): Int {
        if (chapter !in rowStart.indices) return -1
        val n = sentenceCount(chapter)
        if (n <= 0) return -1
        val head = if (rows[rowStart[chapter]].sentence == null) 1 else 0
        return rowStart[chapter] + head + sentence.coerceIn(0, n - 1)
    }

    /** Обратный перевод: строка ленты → «глава + предложение». Нажатие на
     *  заголовок ведёт в начало главы. */
    fun placeOf(row: Int): Pair<Int, Int>? {
        if (row !in rows.indices) return null
        val r = rows[row]
        return r.chapter to if (r.sentence == null) 0 else r.index
    }

    fun setSelectionAnchor(row: Int?) {
        val p = row ?: -1
        if (anchor == p) return
        val old = anchor
        anchor = p
        if (old in rows.indices) notifyItemChanged(old)
        if (anchor in rows.indices) notifyItemChanged(anchor)
    }

    fun setSelectionRange(from: Int?, to: Int?) {
        val f = from ?: -1
        val t = to ?: -1
        if (selFrom == f && selTo == t) return
        selFrom = f
        selTo = t
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (anchor == -1 && selFrom == -1 && selTo == -1) return
        anchor = -1
        selFrom = -1
        selTo = -1
        notifyDataSetChanged()
    }

    fun setCurrent(row: Int) {
        if (row == current) return
        val old = current
        current = row
        if (old in rows.indices) notifyItemChanged(old)
        if (current in rows.indices) notifyItemChanged(current)
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (rows[position].sentence == null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            HeadVH(inflater.inflate(R.layout.item_chapter_header, parent, false) as TextView)
        } else {
            SentenceVH(inflater.inflate(R.layout.item_sentence, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeadVH -> {
                holder.tv.text = row.title
                // Тап по заголовку — переход в начало главы.
                holder.tv.setOnClickListener { onSentenceClick(position) }
            }
            is SentenceVH -> {
                val s = row.sentence ?: return
                holder.tv.text = s.text
                val lp = holder.tv.layoutParams as ViewGroup.MarginLayoutParams
                val top = if (s.paragraphStart) dp(holder.tv, 8f) else 0
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    holder.tv.layoutParams = lp
                }
                val isCurrent = position == current
                val inSel = selFrom in 0..position && position in selFrom..selTo
                val isAnchor = position == anchor
                holder.tv.setBackgroundColor(
                    when {
                        isCurrent -> 0xFF33404C.toInt()
                        isAnchor -> 0xFF4B3A66.toInt()
                        inSel -> 0xFF37415C.toInt()
                        else -> android.graphics.Color.TRANSPARENT
                    }
                )
                holder.tv.setOnClickListener { onSentenceClick(position) }
                holder.tv.setOnLongClickListener {
                    onSentenceLongClick(position)
                    true
                }
            }
        }
    }

    class SentenceVH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    class HeadVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private fun dp(view: android.view.View, v: Float): Int =
        (v * view.resources.displayMetrics.density).toInt()
}
