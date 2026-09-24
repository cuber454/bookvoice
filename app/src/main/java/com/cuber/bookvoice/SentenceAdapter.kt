package com.cuber.bookvoice

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.view.LayoutInflater
import android.view.View
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
 * Строка ленты — АБЗАЦ, а не предложение (msg6322). Пока строкой было
 * предложение, зрячий читатель видел список строк: каждое предложение всегда
 * начиналось с новой строки, а перенос через границу предложения был
 * невозможен. Теперь абзац собирается в один кусок текста и выглядит как
 * книжная страница, а предложения внутри него отданы диктору виртуальными
 * узлами ([ParagraphView]): свайп по предложениям работает как раньше.
 *
 * Прокрутка — обычная, родная для RecyclerView: рука и чтение двигают ОДИН
 * список, поэтому назад из текста в титульную страницу и начало книги теперь
 * можно вернуться свободно.
 */
class SentenceAdapter(
    private val onSentenceClick: (Int, Int) -> Unit,
    private val onSentenceLongClick: (Int, Int) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Строка ленты: заголовок главы ([first] == -1) или абзац.
     *  [first] — номер первого предложения абзаца внутри главы, [text] — текст
     *  строки, [starts]/[ends] — границы предложений в нём (смещения в
     *  символах). По ним диктор ходит по предложениям, а читалка подсвечивает
     *  читаемое. [indent] — красная строка (не у первого абзаца главы). */
    private class Row(
        val chapter: Int,
        val first: Int,
        val title: String?,
        val text: String,
        val starts: IntArray,
        val ends: IntArray,
        val indent: Boolean,
    )

    private val rows = ArrayList<Row>()

    /** С какой строки ленты начинается глава; размер — число глав. */
    private var rowStart = IntArray(0)

    // Место чтения и выделение — в терминах «глава + предложение», как и всё
    // остальное в читалке. Строка ленты из них выводится через [flatOf].
    private var curChapter = -1
    private var curSentence = -1

    // #105: выделение фрагмента. anchor — начало (подсвечивается), from/to —
    // границы отмеченного куска. Фрагмент может пересекать границу глав, и
    // подсветка это покажет.
    private var anchorChapter = -1
    private var anchorSentence = -1
    private var selFrom: Pair<Int, Int>? = null
    private var selTo: Pair<Int, Int>? = null

    val isEmpty: Boolean get() = rows.isEmpty()

    /** Собрать ленту по всей книге. Заголовок главы отдельной строкой появляется
     *  только если он в книге есть и не повторяет первое предложение главы
     *  (движок речи уже пропускает такой дубль — см. ReaderEngine.spokenText).
     *
     *  Абзац — предложения от [Sentence.paragraphStart] до следующего начала
     *  абзаца: границы расставил парсер (TextSplit.fromParagraphs). */
    fun submitBook(chapters: List<Chapter>) {
        rows.clear()
        rowStart = IntArray(chapters.size)
        for (ch in chapters.indices) {
            rowStart[ch] = rows.size
            val c = chapters[ch]
            val title = c.title?.trim().orEmpty()
            val first = c.sentences.firstOrNull()?.text?.trim()
            if (title.isNotEmpty() && title != first) {
                rows.add(Row(ch, -1, title, title, IntArray(0), IntArray(0), indent = false))
            }
            var s = 0
            var firstParagraph = true
            while (s < c.sentences.size) {
                var e = s + 1
                while (e < c.sentences.size && !c.sentences[e].paragraphStart) e++
                rows.add(
                    paragraphRow(ch, s, c.sentences.subList(s, e), indent = !firstParagraph)
                )
                firstParagraph = false
                s = e
            }
        }
        resetState()
        notifyDataSetChanged()
    }

    /** Абзац: предложения сшиваются в один текст, их границы запоминаем. */
    private fun paragraphRow(
        chapter: Int,
        from: Int,
        items: List<Sentence>,
        indent: Boolean,
    ): Row {
        val sb = StringBuilder()
        val starts = IntArray(items.size)
        val ends = IntArray(items.size)
        for (i in items.indices) {
            // Вид книжного текста — одна строка без переводов и двойных пробелов;
            // для чтения вслух текст не меняется (движок берёт предложения как есть).
            val t = items[i].text.replace(WS, " ").trim()
            if (i > 0 && ends[i - 1] > starts[i - 1]) sb.append(' ')
            starts[i] = sb.length
            sb.append(t)
            ends[i] = sb.length
        }
        return Row(chapter, from, null, sb.toString(), starts, ends, indent)
    }

    fun clear() {
        if (rows.isEmpty()) return
        rows.clear()
        rowStart = IntArray(0)
        resetState()
        notifyDataSetChanged()
    }

    /** Сколько глав и строк знает лента — для журнала (0.4.79). Нужно, чтобы
     *  отличить «строки нет, потому что книга такая» от «лента разошлась с
     *  книгой»: жалоба «текст стоит, а чтение уходит». */
    val chapterCount: Int get() = rowStart.size
    val rowCount: Int get() = rows.size

    private fun resetState() {
        curChapter = -1
        curSentence = -1
        anchorChapter = -1
        anchorSentence = -1
        selFrom = null
        selTo = null
    }

    /** Строка ленты с этим предложением (у заголовка главы предложений нет,
     *  поэтому заголовок не вернётся). -1 — главы или предложения нет. */
    fun flatOf(chapter: Int, sentence: Int): Int {
        val from = rowStart.getOrNull(chapter) ?: return -1
        val to = if (chapter + 1 < rowStart.size) rowStart[chapter + 1] else rows.size
        val s = sentence.coerceAtLeast(0)
        var lo = from
        var hi = to - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (rows[mid].first in 0..s) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /** Первое предложение строки ленты: по нему читалка считает, какое
     *  предложение какого абзаца подсвечено/доводится прокруткой. -1 — строки
     *  нет или это заголовок главы. */
    fun firstInRow(row: Int): Int = rows.getOrNull(row)?.first ?: -1

    /** Обратный перевод: строка ленты → «глава + предложение». У абзаца это его
     *  первое предложение, у заголовка — начало главы. */
    fun placeOf(row: Int): Pair<Int, Int>? {
        if (row !in rows.indices) return null
        val r = rows[row]
        return r.chapter to r.first.coerceAtLeast(0)
    }

    /** Подсветить начало выделения. */
    fun setSelectionAnchor(chapter: Int?, sentence: Int?) {
        val c = chapter ?: -1
        val s = if (c < 0) -1 else (sentence ?: 0)
        if (c == anchorChapter && s == anchorSentence) return
        val old = anchorRow()
        anchorChapter = c
        anchorSentence = s
        val new = anchorRow()
        if (old >= 0 && old != new) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    /** Подсветить отмеченный кусок целиком (границы — включительно). */
    fun setSelectionRange(from: Pair<Int, Int>?, to: Pair<Int, Int>?) {
        if (selFrom == from && selTo == to) return
        selFrom = from
        selTo = to
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (anchorChapter == -1 && selFrom == null && selTo == null) return
        anchorChapter = -1
        anchorSentence = -1
        selFrom = null
        selTo = null
        notifyDataSetChanged()
    }

    /** Отметить читаемое предложение. */
    fun setCurrent(chapter: Int, sentence: Int) {
        if (chapter == curChapter && sentence == curSentence) return
        val old = currentRow()
        curChapter = chapter
        curSentence = sentence
        val new = currentRow()
        if (old >= 0 && old != new) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    fun clearCurrent() = setCurrent(-1, -1)

    private fun currentRow(): Int = flatOf(curChapter, curSentence)

    private fun anchorRow(): Int = if (anchorChapter < 0) -1 else flatOf(anchorChapter, anchorSentence)

    /** Попадает ли предложение в отмеченный кусок. Главы и предложения внутри
     *  них идут по порядку, поэтому сравниваем парами. */
    private fun inSelection(chapter: Int, sentence: Int): Boolean {
        val f = selFrom ?: return false
        val t = selTo ?: return false
        return compare(chapter, sentence, f.first, f.second) >= 0 &&
            compare(chapter, sentence, t.first, t.second) <= 0
    }

    private fun compare(c1: Int, s1: Int, c2: Int, s2: Int): Int =
        if (c1 != c2) c1.compareTo(c2) else s1.compareTo(s2)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = if (rows[position].title != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            HeadVH(inflater.inflate(R.layout.item_chapter_header, parent, false) as TextView)
        } else {
            ParagraphVH(inflater.inflate(R.layout.item_paragraph, parent, false) as ParagraphView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeadVH -> {
                holder.tv.text = row.title
                // Тап по заголовку — переход в начало главы.
                holder.tv.setOnClickListener {
                    onSentenceClick(row.chapter, 0)
                }
            }
            is ParagraphVH -> {
                val v = holder.tv
                v.text = highlight(row, v)
                v.bindSentences(row.starts, row.ends)
                v.onSentenceClick = { i -> onSentenceClick(row.chapter, row.first + i) }
                v.onSentenceLongClick = { i -> onSentenceLongClick(row.chapter, row.first + i) }
            }
        }
    }

    /** Текст абзаца с подсветкой: читаемое предложение, начало выделения и сам
     *  отмеченный кусок — как раньше красились строки, только теперь красится
     *  именно предложение внутри абзаца. */
    private fun highlight(row: Row, view: View): CharSequence {
        val text = SpannableString(row.text)
        for (i in row.starts.indices) {
            val from = row.starts[i]
            val to = row.ends[i]
            if (to <= from) continue
            val chapter = row.chapter
            val sentence = row.first + i
            val color = when {
                chapter == curChapter && sentence == curSentence -> Palette.SEL_CURRENT
                chapter == anchorChapter && sentence == anchorSentence -> Palette.SEL_ANCHOR
                inSelection(chapter, sentence) -> Palette.SEL_RANGE
                else -> 0
            }
            if (color != 0) {
                text.setSpan(
                    BackgroundColorSpan(color), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        // Красная строка абзаца.
        if (row.indent && text.isNotEmpty()) {
            text.setSpan(
                LeadingMarginSpan.Standard(dp(view), 0), 0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return text
    }

    private fun dp(view: View): Int = (INDENT_DP * view.resources.displayMetrics.density).toInt()

    class ParagraphVH(val tv: ParagraphView) : RecyclerView.ViewHolder(tv)
    class HeadVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private companion object {
        const val INDENT_DP = 24f

        /** Пробелы и переводы строки внутри предложения — к одному пробелу:
         *  абзац должен выглядеть одной строкой, как в книге. */
        val WS = Regex("\\s+")
    }
}
