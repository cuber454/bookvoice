package com.cuber.bookvoice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Полка книг: каждая книга — один доступный скринридеру элемент; текст строки
 * собирает библиотека ([rowText] для списка, [cardText] для карточки-сетки).
 *
 * Два вида (msg649): «список» — полная строка «название + мета»; «сетка» —
 * карточка с обложкой, названием и автором. Вид живёт в [viewMode] и отдаётся
 * как itemViewType, поэтому RecyclerView не смешивает холдеры разных видов и
 * при переключении пересоздаёт их начисто.
 *
 * Обложка (23.09.2026) приходит из [covers] и для диктора не существует: узел
 * по-прежнему один — карточка, её текст задаёт [cardText]. Пока настоящая
 * картинка едет из файла, в карточке стоит нарисованная из названия.
 */
class BookAdapter(
    private val rowText: (BookRecord) -> String,
    private val onBookClick: (BookRecord) -> Unit,
    private val onBookLongClick: (BookRecord) -> Unit = {},
    private val cardText: (BookRecord) -> String = rowText,
    private val covers: CoverSource? = null,
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

    /** Показывать ли обложки в карточках-сетки. Галочка живёт в настройках
     *  («Библиотека и скачанные»), по умолчанию включена. Выключенная —
     *  карточка выглядит как раньше, только название и автор, и книгу ради
     *  картинки мы вообще не открываем: выключенное не должно стоить работы. */
    var coversEnabled: Boolean = true
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
            // Карточка-сетка: корень — LinearLayout с обложкой и двумя TextView.
            // Дети не важны для доступности (importantForAccessibility="no" в
            // layout); единый узел — карточка, текст для TalkBack ставим в onBind.
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
            // Автора на карточке с обложкой нет (23.09.2026): освободившуюся
            // строку отдали названию — у него четыре строки вместо трёх. А вот
            // БЕЗ обложки карточка снова текстовая, и там автор нужен: узнавать
            // книгу больше не по чему. Поэтому строку показываем ровно тогда,
            // когда картинки нет — снята галочка или взять её неоткуда.
            // Диктор автор слышит всегда: он приходит текстом карточки.
            val tvAuthor = v.findViewById<TextView>(R.id.tvCardAuthor)
            val author = rec.author?.takeIf { it.isNotBlank() }
            val showAuthor = author != null && !coversShown()
            tvAuthor.visibility = if (showAuthor) View.VISIBLE else View.GONE
            tvAuthor.text = if (showAuthor) author else ""
            v.contentDescription = cardText(rec)
            bindCover(v, rec)
        } else {
            (v as TextView).text = rowText(rec)
        }
        v.setOnClickListener { onBookClick(rec) }
        v.setOnLongClickListener { onBookLongClick(rec); true }
    }

    /** Показывается ли на карточке обложка. От этого зависит и автор: нет
     *  картинки — карточка текстовая, и автор в ней нужен. Условие ровно то же,
     *  что у [bindCover]: галочка включена и источник обложек есть. */
    private fun coversShown(): Boolean = coversEnabled && covers != null

    /** Обложка карточки-сетки. Диктор её не видит (в разметке помечена как
     *  неважная), поэтому на обход и на озвучку она не влияет. Пришедшую из
     *  файла картинку ставим, только если холдер всё ещё показывает ту же
     *  книгу: RecyclerView отдаёт вьюхи по кругу, и обложка соседней книги
     *  иначе оказалась бы не на своём месте. */
    private fun bindCover(v: View, rec: BookRecord) {
        val iv = v.findViewById<ImageView>(R.id.ivCardCover) ?: return
        val source = covers
        if (!coversEnabled || source == null) {
            // Обложки выключены галочкой (или их неоткуда взять): карточка
            // возвращается к прежнему виду — название и автор, без картинки.
            iv.tag = null
            iv.visibility = View.GONE
            iv.setImageDrawable(null)
            return
        }
        iv.visibility = View.VISIBLE
        iv.tag = rec.uri
        val ready = source.cached(rec)
        if (ready != null) {
            iv.setImageBitmap(ready)
            return
        }
        // Ставим заглушку ВСЕГДА, даже если нарисовать её не вышло: холдер
        // приходит из переработки, и в нём могла остаться чужая картинка.
        iv.setImageBitmap(source.placeholder(rec))
        source.request(rec) { bmp ->
            if (iv.tag == rec.uri) iv.setImageBitmap(bmp)
        }
    }
}
