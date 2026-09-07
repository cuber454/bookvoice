package com.cuber.bookvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Одна цитата (#104): выделенный фрагмент книги. Живёт в отдельном файле
 * quotes.json, а не в записи книги, чтобы переживать её удаление. Текст
 * [text] дублируется осознанно — цитаты озвучиваются и экспортируются без
 * файла книги. [uri]/[bookTitle]/[author] — откуда цитата (для раздела
 * «Цитаты» и «Открыть в книге», когда файл на месте). [chapter]/[sentence] —
 * место начала фрагмента.
 */
data class Quote(
    val id: String,
    val uri: String,
    val bookTitle: String,
    val author: String?,
    val chapter: Int,
    val sentence: Int,
    val text: String,
    val addedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uri", uri)
        put("bookTitle", bookTitle)
        author?.let { put("author", it) }
        put("chapter", chapter)
        put("sentence", sentence)
        put("text", text)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Quote(
            id = o.getString("id"),
            uri = o.getString("uri"),
            bookTitle = o.getString("bookTitle"),
            author = o.optString("author").ifBlank { null },
            chapter = o.optInt("chapter"),
            sentence = o.optInt("sentence"),
            text = o.getString("text"),
            addedAt = o.optLong("addedAt"),
        )
    }
}

/**
 * Реестр цитат: filesDir/quotes.json, кэш в памяти, атомарные записи как у
 * BookStore. Все мутации через [upsert]/[remove]; потокобезопасно.
 */
object QuoteStore {

    @Volatile
    private var cache: List<Quote>? = null

    fun all(context: Context): List<Quote> {
        cache?.let { return it }
        val f = file(context)
        val list = if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { Quote.fromJson(arr.getJSONObject(it)) }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        cache = list
        return list
    }

    /** Цитаты книги (для «Открыть в книге» из раздела Цитаты). */
    fun byUri(context: Context, uri: String): List<Quote> =
        all(context).filter { it.uri == uri }

    @Synchronized
    fun upsert(context: Context, q: Quote) {
        val list = all(context).filterNot { it.id == q.id }.toMutableList()
        list.add(q)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, all(context).filterNot { it.id == id })
    }

    /** Полная замена списка (восстановление из бэкапа, #100). */
    @Synchronized
    fun replaceAll(context: Context, list: List<Quote>) {
        save(context, list)
    }

    private fun save(context: Context, list: List<Quote>) {
        cache = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(context).writeText(arr.toString()) }
    }

    private fun file(context: Context): File = File(context.filesDir, "quotes.json")
}
