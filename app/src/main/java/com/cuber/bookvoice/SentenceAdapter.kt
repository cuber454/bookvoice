package com.cuber.bookvoice

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Список предложений текущей главы. Каждое предложение — отдельный элемент,
 * доступный Screen Reader'у (свайп переводит его на следующее предложение).
 * Текущее предложение подсвечивается. В конце — элемент перехода к следующей главе.
 */
class SentenceAdapter(
    private val onSentenceClick: (Int) -> Unit,
    private val onNextChapterClick: () -> Unit,
    private val onSentenceLongClick: (Int) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sentences = ArrayList<Sentence>()
    private var hasNext = false
    private var current = -1

    // #105: выделение фрагмента. anchor — начало (подсвечивается), selFrom/selTo —
    // границы отмеченного куска в предложениях ТЕКУЩЕЙ главы (-1 = нет).
    private var anchor = -1
    private var selFrom = -1
    private var selTo = -1

    fun setSelectionAnchor(pos: Int?) {
        val p = pos ?: -1
        if (anchor == p) return
        val old = anchor
        anchor = p
        if (old in sentences.indices) notifyItemChanged(old)
        if (anchor in sentences.indices) notifyItemChanged(anchor)
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

    val sentenceCount: Int get() = sentences.size

    fun submit(list: List<Sentence>, showNext: Boolean) {
        sentences.clear()
        sentences.addAll(list)
        hasNext = showNext
        current = -1
        notifyDataSetChanged()
    }

    fun setCurrent(pos: Int) {
        if (pos == current) return
        val old = current
        current = pos
        if (old in sentences.indices) notifyItemChanged(old)
        if (current in sentences.indices) notifyItemChanged(current)
    }

    override fun getItemCount(): Int = sentences.size + if (hasNext) 1 else 0

    override fun getItemViewType(position: Int): Int = if (isFooter(position)) 1 else 0

    private fun isFooter(position: Int): Boolean = hasNext && position == sentences.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            FootVH(inflater.inflate(R.layout.item_next_chapter, parent, false) as TextView)
        } else {
            SentenceVH(inflater.inflate(R.layout.item_sentence, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FootVH -> holder.tv.setOnClickListener { onNextChapterClick() }
            is SentenceVH -> {
                val s = sentences[position]
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
    class FootVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private fun dp(view: android.view.View, v: Float): Int =
        (v * view.resources.displayMetrics.density).toInt()
}
