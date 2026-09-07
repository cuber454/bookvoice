package com.cuber.bookvoice

import android.content.Context
import android.text.Html
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.StringReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Онлайн-каталоги книг в формате OPDS (v0.3.20).
 *
 * Каталог — это лента Atom: внутри «папки» (записи со ссылкой на другую
 * OPDS-ленту — type application/atom+xml) и «книги» (записи со ссылкой
 * acquisition на файл — type application/epub+zip и т.п.). Книгу качаем
 * файлом в filesDir и добавляем в библиотеку как обычную запись.
 *
 * Какие источники хранятся — [OpdsPrefs]; скачивание/парсинг — [OpdsNet] и
 * [OpdsParser]; экран — [CatalogActivity].
 */
class OpdsException(message: String) : Exception(message)

/** Формат файла книги, который каталог отдаёт на скачивание. BookVoice умеет
 *  читать FB2/EPUB/TXT — остальные (rtf/mobi/html) в список не попадают. */
data class OpdsFormat(val label: String, val ext: String, val url: String)

/** Переход на другой OPDS-раздел: «Все книги автора…» / «Все книги серии…». */
data class OpdsRelated(val label: String, val url: String)

/** Читаемые форматы каталога: ключ (он же хранится в настройке формата) — название. */
val OPDS_READABLE_FORMATS = listOf("fb2" to "FB2", "epub" to "EPUB", "txt" to "TXT")

fun opdsFormatLabel(key: String): String =
    OPDS_READABLE_FORMATS.firstOrNull { it.first == key }?.second ?: key.uppercase()

sealed class OpdsItem {
    abstract val title: String

    /** Папка каталога: ведёт на другую OPDS-ленту. [note] — строка-пояснение,
     *  которую каталог прикладывает к разделу («837 новых книг», «Новые
     *  поступления за неделю»…) — показываем её второй строкой, как в читалках. */
    data class Folder(
        override val title: String,
        val url: String,
        val note: String? = null,
    ) : OpdsItem()

    /** Книга. [downloads] — все читаемые форматы, что отдаёт каталог; [url]/[ext] —
     *  первый из них (запасной адрес). [annotation], [genres], [authors] приходят
     *  в ленте целиком — для страницы книги. */
    data class Book(
        override val title: String,
        val authors: List<String> = emptyList(),
        val url: String = "",
        val ext: String = "",
        val genres: List<String> = emptyList(),
        val annotation: String? = null,
        /** Аннотация с сохранёнными тегами (<a href> и т.п.) — для кликабельных
         *  ссылок на странице книги ([annotation] — тот же текст без тегов). */
        val annotationHtml: String? = null,
        val downloads: List<OpdsFormat> = emptyList(),
        /** Раздел «Все книги автора…», если каталог его отдаёт. */
        val authorFeed: OpdsRelated? = null,
        /** Раздел «Все книги серии…», если книга в серии и каталог его отдаёт. */
        val seriesFeed: OpdsRelated? = null,
        /** Веб-страница книги в каталоге («Книга на сайте»). */
        val siteUrl: String? = null,
    ) : OpdsItem() {
        /** Первый автор — для строки списка (вся страница книги хранит [authors]). */
        val author: String? get() = authors.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}

data class OpdsFeed(
    val title: String,
    val items: List<OpdsItem>,
    val nextUrl: String?,
    /** Шаблон поиска вида /search?q={searchTerms}, если каталог его отдаёт. */
    val searchTemplate: String?,
)

/** Настройки: сохранённые каталоги (имя + адрес). Живут в prefs "reader". */
object OpdsPrefs {
    const val KEY_SOURCES = "opds_sources"
    val DEFAULT_URL = "https://flibusta.is/opds"

    /** Куда качать книги из каталога: tree-uri (SAF) или null = в приложение. */
    const val KEY_DL_DIR = "opds_dl_dir"

    /** Формат для большой кнопки «Скачать»: fb2/epub/txt (#45). */
    const val KEY_DL_FMT = "opds_dl_fmt"
    const val DEFAULT_DL_FMT = "fb2"

    /** Мёртвое зеркало (msg1776): flibusta.site не работает. Не сеем при чистой
     *  установке и вычищаем у тех, у кого запись успела появиться от старых версий. */
    private const val DEAD_URL = "https://flibusta.site/opds"

    /** Стартовый набор: живое зеркало. Запасное (flibusta.site) не работает —
     *  убрано (msg1776), чтобы не плодить мёртвые записи при чистой установке. */
    private val DEFAULT_SOURCES = listOf(
        "Flibusta" to DEFAULT_URL,
    )

    data class Source(val name: String, val url: String)

    fun sources(context: Context): List<Source> {
        val raw = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getString(KEY_SOURCES, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").ifBlank { return@mapNotNull null }
                Source(o.optString("name").ifBlank { url }, url)
            }
        }.getOrElse { emptyList() }
    }

    /** При первом запуске добавляет стартовый набор каталогов. Мёртвое зеркало
     *  flibusta.site (msg1776) у тех, у кого оно осталось от старых версий,
     *  вычищаем: раньше ensureDefaults докладывал его снова при каждом открытии
     *  Каталога, и удалённая вручную запись возвращалась. */
    fun ensureDefaults(context: Context) {
        val cleaned = sources(context).filterNot { it.url == DEAD_URL }
        val list = cleaned.toMutableList()
        for ((name, url) in DEFAULT_SOURCES) {
            if (cleaned.none { it.url == url }) list.add(Source(name, url))
        }
        if (list != cleaned) save(context, list)
    }

    fun add(context: Context, name: String, url: String) {
        val list = sources(context).filterNot { it.url == url }.toMutableList()
        list.add(Source(name.ifBlank { hostOf(url) }, url.trim()))
        save(context, list)
    }

    fun remove(context: Context, url: String) {
        save(context, sources(context).filterNot { it.url == url })
    }

    /** Изменить каталог на месте (сохранить позицию в списке): новое имя и/или
     *  адрес. Если адрес сменился на уже существующий у другого каталога —
     *  дубликат убираем, чтобы не было двух записей с одним адресом. */
    fun update(context: Context, oldUrl: String, name: String, newUrl: String) {
        val list = sources(context).toMutableList()
        val idx = list.indexOfFirst { it.url == oldUrl }
        if (idx < 0) return
        list.removeAt(idx)
        val finalUrl = newUrl.trim()
        list.removeAll { it.url == finalUrl }
        list.add(idx, Source(name.ifBlank { hostOf(finalUrl) }, finalUrl))
        save(context, list)
    }

    /** Переместить каталог в списке (delta = -1 выше, +1 ниже). Сохраняет
     *  порядок: первое зеркало всегда сверху и легко достаётся. */
    fun move(context: Context, url: String, delta: Int) {
        val list = sources(context).toMutableList()
        val idx = list.indexOfFirst { it.url == url }
        if (idx < 0) return
        val to = (idx + delta).coerceIn(0, list.lastIndex)
        if (to == idx) return
        val item = list.removeAt(idx)
        list.add(to, item)
        save(context, list)
    }

    private fun save(context: Context, list: List<Source>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        context.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrElse { url }
}

/**
 * Тонкий GET: скачивает страницу/файл, редиректы разворачивает OkHttp.
 * Имена резолвит системный DNS — как любое другое приложение на телефоне.
 * (DNS-обход через DoH убран: случай «каталог не открывался» был вызван
 * выключенным доступом приложения к интернету, а не сетью.)
 */
object OpdsNet {
    private const val UA = "Mozilla/5.0 (Linux; Android) BookVoice/0.3.26 OPDS"

    /** Клиент каталога: обычный системный DNS. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Для «Проверить каталог»: таймауты короче, чтобы не держать незрячего
     *  у зависшего зеркала. */
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    data class Fetch(val bytes: ByteArray, val finalUrl: String)

    fun get(url: String, accept: String): Fetch {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", accept)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OpdsException("сервер ответил: HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                // url последнего запроса — после редиректов; по нему резолвим ссылки фида.
                return Fetch(bytes, resp.request.url.toString())
            }
        } catch (e: OpdsException) {
            throw e
        } catch (e: Exception) {
            throw OpdsException(e.message ?: "нет связи с сервером")
        }
    }

    /** Загрузка файла с прогрессом (msg1264). Тело читаем кусками, после каждого
     *  зовём [onProgress](прочитано, всего). «Всего» — из заголовка Content-Length;
     *  если сервер размер не прислал (total = -1), точный процент невозможен. */
    fun fetchProgress(
        url: String,
        accept: String,
        onProgress: (read: Long, total: Long) -> Unit,
    ): Fetch {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", accept)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OpdsException("сервер ответил: HTTP ${resp.code}")
                val body = resp.body ?: throw OpdsException("сервер не прислал тело")
                val total = body.contentLength()
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(16 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
                return Fetch(out.toByteArray(), resp.request.url.toString())
            }
        } catch (e: OpdsException) {
            throw e
        } catch (e: Exception) {
            throw OpdsException(e.message ?: "нет связи с сервером")
        }
    }

    /** Проверка доступности адреса (меню долгого нажатия): null — отвечает,
     *  иначе — текст ошибки. Тело не сохраняем, таймауты короткие. */
    fun check(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return try {
            pingClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null else "сервер ответил: HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            e.message ?: "нет связи с сервером"
        }
    }

}

/**
 * Разбор OPDS/Atom-ленты. Лента обычно в UTF-8; названия и ссылки
 * извлекаем без тяжёлого DOM — одним проходом XmlPullParser.
 */
object OpdsParser {

    private data class Link(
        val href: String,
        val rel: String?,
        val type: String?,
        val title: String? = null,
    )

    fun parse(finalUrl: String, bytes: ByteArray): OpdsFeed {
        val text = stripBom(String(bytes, Charsets.UTF_8))
        val p = Xml.newPullParser()
        p.setInput(StringReader(text))

        var feedTitle = ""
        var searchTemplate: String? = null
        var nextUrl: String? = null
        val items = ArrayList<OpdsItem>()

        // Текущая запись <entry>.
        var inEntry = false
        var eTitle = ""
        val eAuthors = ArrayList<String>()
        val eGenres = ArrayList<String>()
        var eContent: String? = null
        var eSummary: String? = null
        val eLinks = ArrayList<Link>()

        fun resolve(href: String): String = URL(URL(finalUrl), href).toString()

        /** Аннотация книги: длинное пояснение в <content> (или краткое в <summary>). */
        fun annotationOf(): String? = listOfNotNull(eContent, eSummary)
            .firstOrNull { it.isNotBlank() }
            ?.let(::cleanText)
            ?.takeIf { it.isNotBlank() }

        /** HTML аннотации с сохранёнными тегами — ссылки делаем кликабельными. */
        fun annotationHtmlOf(): String? = listOfNotNull(eContent, eSummary)
            .firstOrNull { it.isNotBlank() }
            ?.let(::lightClean)
            ?.takeIf { it.isNotBlank() }

        /** Подпись раздела: короткое <content type="text"> («837 новых книг»…). */
        fun noteOf(): String? = eContent
            ?.let(::cleanText)
            ?.takeIf { it.isNotBlank() }

        /** Связанный раздел среди related-ссылок: «Все книги автора…» / серии… */
        fun relatedFeed(kw: String): OpdsRelated? =
            eLinks.asSequence()
                .filter { it.rel == "related" && it.type?.contains("atom+xml") == true }
                .firstOrNull { it.title?.contains(kw, ignoreCase = true) == true }
                ?.let { OpdsRelated(it.title ?: "", resolve(it.href)) }

        /** Веб-страница книги в каталоге (rel=alternate, text/html) — «Книга на сайте». */
        fun siteHref(): String? {
            val alts = eLinks.filter { it.rel == "alternate" && it.type?.contains("html") == true }
            if (alts.isEmpty()) return null
            val hit = alts.firstOrNull { it.title?.contains("сайте", ignoreCase = true) == true }
                ?: alts.firstOrNull { it.href.contains("/b/", ignoreCase = true) }
                ?: alts.first()
            return resolve(hit.href)
        }

        fun flushEntry() {
            if (eTitle.isBlank()) return
            // Acquisition-ссылки (файлы) делают запись «книгой». Читаем форматы в
            // предпочтительном порядке, остальное (related/сайт) собираем отдельно.
            val acq = eLinks.filter {
                it.rel?.startsWith("http://opds-spec.org/acquisition") == true
            }
            if (acq.isNotEmpty()) {
                val fmts = LinkedHashMap<String, OpdsFormat>()
                for (l in acq) {
                    val key = formatKey(l.type) ?: continue
                    if (!fmts.containsKey(key)) {
                        fmts[key] = OpdsFormat(formatLabel(key), ".$key", resolve(l.href))
                    }
                }
                val ordered = READABLE_FORMATS.mapNotNull { fmts[it] }
                val first = acq[0]
                items.add(OpdsItem.Book(
                    title = eTitle,
                    authors = eAuthors,
                    url = ordered.firstOrNull()?.url ?: resolve(first.href),
                    ext = ordered.firstOrNull()?.ext ?: "",
                    genres = eGenres,
                    annotation = annotationOf(),
                    annotationHtml = annotationHtmlOf(),
                    downloads = ordered,
                    authorFeed = relatedFeed("автор"),
                    seriesFeed = relatedFeed("сери"),
                    siteUrl = siteHref(),
                ))
                return
            }
            val folder = eLinks.firstOrNull { isCatalogLink(it) }
            if (folder != null) {
                items.add(OpdsItem.Folder(eTitle, resolve(folder.href), noteOf()))
            }
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (val name = p.name) {
                        "entry" -> {
                            inEntry = true
                            eTitle = ""
                            eAuthors.clear()
                            eGenres.clear()
                            eContent = null
                            eSummary = null
                            eLinks.clear()
                        }
                        "link" -> {
                            val href = p.getAttributeValue(null, "href") ?: ""
                            if (href.isNotBlank()) {
                                if (inEntry) {
                                    eLinks.add(Link(
                                        href,
                                        p.getAttributeValue(null, "rel"),
                                        p.getAttributeValue(null, "type"),
                                        p.getAttributeValue(null, "title"),
                                    ))
                                } else {
                                    val rel = p.getAttributeValue(null, "rel")
                                    val type = p.getAttributeValue(null, "type")
                                    when {
                                        rel == "next" -> nextUrl = resolve(href)
                                        rel == "search" && type?.contains("atom+xml") == true &&
                                            href.contains("{searchTerms}") ->
                                            searchTemplate = resolve(href)
                                    }
                                }
                            }
                        }
                        "category" -> {
                            if (inEntry) {
                                val term = p.getAttributeValue(null, "term")
                                val label = p.getAttributeValue(null, "label")
                                val g = label?.takeIf { it.isNotBlank() } ?: term
                                if (!g.isNullOrBlank()) eGenres.add(g)
                            }
                        }
                        "title" -> {
                            val t = readElementText(p).trim()
                            if (inEntry) { if (eTitle.isEmpty()) eTitle = t }
                            else if (feedTitle.isEmpty()) feedTitle = t
                        }
                        "name" -> {
                            if (inEntry) {
                                val n = readElementText(p).trim()
                                if (n.isNotEmpty()) eAuthors.add(n)
                            }
                        }
                        "content" -> {
                            if (inEntry && eContent == null) {
                                eContent = readElementText(p).trim()
                            }
                        }
                        "summary" -> {
                            if (inEntry && eSummary == null) {
                                eSummary = readElementText(p).trim()
                            }
                        }
                        // Всё остальное пропускаем по событию.
                        else -> Unit
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "entry") {
                        flushEntry()
                        inEntry = false
                    }
                }
                else -> Unit
            }
            event = p.next()
        }

        val finalItems = items.distinctBy { (it as? OpdsItem.Folder)?.url ?: (it as? OpdsItem.Book)?.url }
        return OpdsFeed(
            title = feedTitle.ifBlank { OpdsPrefs.hostOf(finalUrl) },
            items = finalItems,
            nextUrl = nextUrl,
            searchTemplate = searchTemplate,
        )
    }

    private fun stripBom(s: String): String =
        if (s.isNotEmpty() && s[0] == '﻿') s.substring(1) else s

    /** Дочитать элемент до его закрывающего тега и вернуть текст. Считает
     *  вложенные теги (в <content> может лежать HTML), поэтому не обрывается
     *  на первом же закрывающем теге. */
    private fun readElementText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** HTML/текст из <content> в читабельный текст для озвучки: теги выкидываем,
     *  сущности раскрываем, двойные пробелы и пустые строки схлопываем. */
    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        val text = Html.fromHtml(raw.replace(" ", " "), Html.FROM_HTML_MODE_LEGACY)
            .toString()
        return text
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Похоже на [cleanText], но теги (<a href>, <p>, …) сохраняет — нужны для
     *  кликабельных ссылок в аннотации. */
    private fun lightClean(raw: String): String =
        raw.replace(" ", " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Ссылка-каталог: ведёт на другую OPDS-ленту. */
    private fun isCatalogLink(l: Link): Boolean =
        l.type?.contains("atom+xml") == true

    /** Форматы, которые BookVoice умеет читать; порядок = предпочтения списка. */
    private val READABLE_FORMATS = listOf("fb2", "epub", "txt")

    /** Ключ читаемого формата по MIME acquisition-ссылки, либо null. */
    private fun formatKey(type: String?): String? = when {
        type?.contains("fb2") == true -> "fb2"
        type?.contains("epub") == true -> "epub"
        type?.contains("txt") == true -> "txt"
        else -> null
    }

    private fun formatLabel(key: String): String = when (key) {
        "fb2" -> "FB2"
        "epub" -> "EPUB"
        "txt" -> "TXT"
        else -> key.uppercase()
    }
}
