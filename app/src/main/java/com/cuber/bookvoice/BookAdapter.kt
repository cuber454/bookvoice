package com.cuber.bookvoice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Полка книг: каждая книга — один доступный скринридеру элемент; текст строки
 * собирает библиотека ([rowText] для списка, [cardText] для карточки-сетки).
 *
 * Два вида (msg649): «список» — полная строка «название + мета»; «сетка» —
 * текстовая карточка с названием и автором (обложек нет). Вид живёт в
 * [viewMode] и отдаётся как itemViewType, поэтому RecyclerView не смешивает
 * холдеры разных видов и при переключении пересоздаёт их начисто.
 */
class BookAdapter(
    private val rowText: (BookRecord) -> String,
    private val onBookClick: (BookRecord) -> Unit,
    private val onBookLongClick: (BookRecord) -> Unit = {},
    private val cardText: (BookRecord) -> String = rowText,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_LIST = 0
        const val VIEW_GRID = 1
    }

    /** Текущий вид полки. Смена вида немедленно пересоздаёт холдеры. */
    var viewMode: Int = VIEW_LIST
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val items = ArrayList<BookRecord>()

    fun submit(list: List<BookRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Обновить записи ПО МЕСТУ: тот же порядок, те же позиции, только свежие
     *  данные в строках, которые изменились. msg4356: после возврата из книги
     *  полку не пересобираем (L1b — чтобы не сдвинуть фокус), но процент в
     *  строке обязан догнать книгу, иначе на полке висит старое «17%». */
    fun updateInPlace(fresh: Map<String, BookRecord>) {
        for (i in items.indices) {
            val now = fresh[items[i].uri] ?: continue
            if (now == items[i]) continue
            items[i] = now
            notifyItemChanged(i)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = viewMode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_GRID) {
            // Карточка-сетка: корень — LinearLayout с двумя TextView. Дети не
            // важны для доступности (importantForAccessibility="no" в layout);
            // единый узел — карточка, текст для TalkBack ставим в onBind.
            val card = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book_grid, parent, false)
            object : RecyclerView.ViewHolder(card) {}
        } else {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_book, parent, false) as TextView
            object : RecyclerView.ViewHolder(tv) {}
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val rec = items[position]
        val v = holder.itemView
        if (viewMode == VIEW_GRID) {
            v.findViewById<TextView>(R.id.tvCardTitle).text = rec.displayTitle
            val author = rec.author?.takeIf { it.isNotBlank() }
            val tvAuthor = v.findViewById<TextView>(R.id.tvCardAuthor)
            // Без автора строку скрываем, чтобы карточка не «дырявила» пустым
            // местом — высоту карточкам задаёт minHeight в layout.
            tvAuthor.visibility = if (author == null) View.GONE else View.VISIBLE
            tvAuthor.text = author ?: ""
            v.contentDescription = cardText(rec)
        } else {
            (v as TextView).text = rowText(rec)
        }
        v.setOnClickListener { onBookClick(rec) }
        v.setOnLongClickListener { onBookLongClick(rec); true }
    }
}
