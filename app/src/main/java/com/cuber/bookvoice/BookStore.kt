package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Запись библиотеки: одна книга. [uri] — content-uri (SAF), по нему же
 * хранится позиция чтения и статус. [name] — имя файла, [title]/[author] —
 * разобранные из FB2/EPUB, если доступны. [annotation] — текст из
 * <description><annotation> (FB2), если он есть; null = файл ради аннотации
 * ещё не читался, "" (пустая строка) = читался, аннотации в файле нет.
 * [sourceUrl] — прямая ссылка скачивания из каталога (для доскачивания, #100).
 * [voiceEngine]/[voice]/[voiceSpeed] — свой голос книги (#102): пишутся только
 * когда для книги включено «Запомнить для этой книги»; null = своего нет.
 */
data class BookRecord(
    val uri: String,
    val name: String,
    val title: String? = null,
    val author: String? = null,
    val annotation: String? = null,
    val sourceUrl: String? = null,
    val voiceEngine: String? = null,
    val voice: String? = null,
    val voiceSpeed: Float? = null,
    val addedAt: Long,
    val lastOpenedAt: Long = 0L,
    val status: Int = BookRecord.STATUS_NEW,
    val chapter: Int = 0,
    val sentence: Int = 0,
    // Сколько прочитано в процентах — по месту среди предложений книги, той же
    // «линейкой», что слайдер читалки (msg1380). Считает и сохраняет MainActivity
    // при открытии/паузе — библиотека только читает готовое значение и озвучивает
    // его, не открывая файл.
    val readPct: Int = 0,
    // «Избранное» (msg2555): пользовательская пометка, НЕ статус — книга может
    // стоять в любой статусной вкладке и одновременно в «Избранном». Вкладка
    // «Избранное» фильтрует по этому флагу; ставится/снимается из меню книги.
    val favorite: Boolean = false,
    // Ручное «название для полки» (msg2559): задаёт владелец в «Переименовать».
    // Показывается вместо [title]/[name] (см. [displayTitle]); файл и его имя
    // на диске не трогаются. null = своё название не задано.
    val customTitle: String? = null,
) {
    // Своё название важнее метаданных из файла (title), те — важнее имени
    // файла. У ручного названия приоритет и оно не перезаписывается при чтении
    // файла: пишется в отдельное поле, title живёт своей жизнью.
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: name

    companion object {
        const val STATUS_NEW = 0
        const val STATUS_READING = 1
        const val STATUS_FINISHED = 2

        fun fromJson(o: JSONObject) = BookRecord(
            uri = o.getString("uri"),
            name = o.getString("name"),
            title = o.optString("title").ifBlank { null },
            author = o.optString("author").ifBlank { null },
            annotation = if (o.has("annotation")) o.optString("annotation") else null,
            sourceUrl = o.optString("sourceUrl").ifBlank { null },
            voiceEngine = o.optString("voiceEngine").ifBlank { null },
            voice = o.optString("voice").ifBlank { null },
            voiceSpeed = if (o.has("voiceSpeed"))
                o.optDouble("voiceSpeed", 0.0).toFloat().takeIf { it > 0f } else null,
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
            status = o.optInt("status", STATUS_NEW),
            chapter = o.optInt("chapter"),
            sentence = o.optInt("sentence"),
            readPct = o.optInt("readPct"),
            favorite = o.optBoolean("favorite"),
            customTitle = o.optString("customTitle").ifBlank { null },
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("name", name)
        title?.let { put("title", it) }
        author?.let { put("author", it) }
        annotation?.let { put("annotation", it) }
        sourceUrl?.let { put("sourceUrl", it) }
        voiceEngine?.let { put("voiceEngine", it) }
        voice?.let { put("voice", it) }
        voiceSpeed?.let { put("voiceSpeed", it) }
        put("addedAt", addedAt)
        put("lastOpenedAt", lastOpenedAt)
        put("status", status)
        put("chapter", chapter)
        put("sentence", sentence)
        put("readPct", readPct)
        put("favorite", favorite)
        customTitle?.let { put("customTitle", it) }
    }
}

/**
 * Реестр книг. Хранится в filesDir/library.json, держит кэш в памяти.
 * Все мутации — через [upsert]/[remove]; потокобезопасно через @Synchronized.
 */
object BookStore {

    @Volatile
    private var cache: List<BookRecord>? = null

    fun all(context: Context): List<BookRecord> {
        cache?.let { return it }
        val f = file(context)
        val list = if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { BookRecord.fromJson(arr.getJSONObject(it)) }
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        cache = list
        return list
    }

    fun byUri(context: Context, uri: String): BookRecord? =
        all(context).firstOrNull { it.uri == uri }

    /** Последние открывавшиеся книги (долгое нажатие «Продолжить»): с записью
     *  времени чтения, кроме [excludeUri] (её открывает обычный тап кнопки), по
     *  убыванию [BookRecord.lastOpenedAt], не больше [limit]. Служат быстрым
     *  доступом к другим читаемым книгам, не прячась в списке полки. */
    fun recent(context: Context, excludeUri: String?, limit: Int): List<BookRecord> =
        all(context)
            .filter { it.lastOpenedAt > 0L && it.uri != excludeUri }
            .sortedByDescending { it.lastOpenedAt }
            .take(limit)

    /** Жив ли файл книги по uri. file:// — путь существует на диске; content:// —
     *  документ доступен (query DISPLAY_NAME что-то вернул). Запись в реестре
     *  может пережить свой файл: msg3202 — удалили одну из сдвоенных копий,
     *  файл стёрся, вторая запись осталась, но книгу открыть нельзя. Кнопка
     *  «Открыть книгу…» и авто-старт должны на такую запись не вести. */
    fun openable(context: Context, uri: String): Boolean {
        val u = Uri.parse(uri)
        return try {
            if (u.scheme == "file") {
                val p = u.path
                p != null && File(p).exists()
            } else {
                context.contentResolver.query(
                    u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { it.moveToFirst() } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** msg3206: книга, в которую ведёт кнопка «Открыть книгу…» (полка и каталог).
     *  Сначала [keyUri] — последняя открывавшаяся, пока её файл жив. Файл стёрли
     *  (удалили сдвоенную копию, msg3202) — самая свежая из остальных
     *  открывавшихся книг, чей файл ещё жив. Ни одной открывавшейся с живым
     *  файлом не осталось — null: кнопка остаётся видимой, но неактивной, с
     *  текстом «Нет открытых книг» (не исчезает). */
    fun continueTarget(context: Context, keyUri: String?): BookRecord? {
        keyUri?.let { u ->
            if (openable(context, u)) {
                return byUri(context, u) ?: BookRecord(
                    uri = u,
                    name = fileName(context, u),
                    addedAt = 0L,
                )
            }
        }
        // Открывавшиеся по убыванию свежести; первая с живым файлом. Проверка
        // файла у content:// — query к провайдеру, не дешёвая: firstOrNull
        // останавливается, как только нашлась живая, не гоняя по всей полке.
        return all(context)
            .asSequence()
            .filter { it.lastOpenedAt > 0L && it.uri != keyUri }
            .sortedByDescending { it.lastOpenedAt }
            .firstOrNull { openable(context, it.uri) }
    }

    /** Имя файла по uri для записи без реестра (keyUri жив, записи уже нет):
     *  у content:// это DISPLAY_NAME, у file:// — последний сегмент пути. */
    private fun fileName(context: Context, uri: String): String {
        val u = Uri.parse(uri)
        val fromResolver = if (u.scheme == "content") {
            try {
                context.contentResolver.query(
                    u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return fromResolver ?: u.lastPathSegment ?: uri
    }

    @Synchronized
    fun upsert(context: Context, rec: BookRecord) {
        val list = all(context).filterNot { it.uri == rec.uri }.toMutableList()
        list.add(rec)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, uri: String) {
        save(context, all(context).filterNot { it.uri == uri })
    }

    /** Склейка дублей скачанных книг (msg1333/1336). Записи с одинаковым
     *  [BookRecord.sourceUrl] — это одна и та же книга из каталога, попавшая
     *  в библиотеку дважды: раньше кнопка «Скачать» после первого открытия
     *  возвращалась (название/автор переписывались файловыми), повторное
     *  скачивание через [com.cuber.bookvoice.CatalogActivity] плодило копии
     *  «… (n)». Оставляем [betterForKeep]'шую запись, остальные убираем из
     *  реестра. Возвращает (чистый список, uri удалённых дублей — файлы на
     *  диске чистит вызывающий). */
    @Synchronized
    fun mergeSourceDuplicates(context: Context): Pair<List<BookRecord>, List<String>> {
        val list = all(context)
        val byUrl = HashMap<String, MutableList<BookRecord>>()
        list.forEach { r ->
            val u = r.sourceUrl
            if (!u.isNullOrBlank()) byUrl.getOrPut(u) { ArrayList() }.add(r)
        }
        val removed = ArrayList<String>()
        for (g in byUrl.values) {
            if (g.size < 2) continue
            var best = g[0]
            for (r in g) best = betterForKeep(best, r)
            for (r in g) if (r.uri != best.uri) removed.add(r.uri)
        }
        if (removed.isNotEmpty()) {
            val drop = removed.toSet()
            save(context, list.filterNot { it.uri in drop })
        }
        return all(context) to removed
    }

    /** Какая из двух записей-дублей одной книги ценнее для сохранения.
     *  Сначала прогресс чтения (статус, проценты, позиция — см. [dupRank]);
     *  затем избранное (msg2555): пометку «в избранном» пользователь ставил
     *  руками, дубль её потерять не должен; при равенстве — запись, связанная
     *  с каталогом ([sourceUrl] есть: каталог узнаёт книгу как скачанную, #100);
     *  дальше — открывавшуюся позже; и лишь потом — первую добавленную
     *  (исходный файл без « (n)»). */
    fun betterForKeep(a: BookRecord, b: BookRecord): BookRecord {
        val pa = dupRank(a)
        val pb = dupRank(b)
        if (pa != pb) return if (pa > pb) a else b
        if (a.favorite != b.favorite) return if (a.favorite) a else b
        // Ручное название задано руками (msg2559) — дубль его не должен терять.
        val ta = !a.customTitle.isNullOrBlank()
        val tb = !b.customTitle.isNullOrBlank()
        if (ta != tb) return if (ta) a else b
        val sa = !a.sourceUrl.isNullOrBlank()
        val sb = !b.sourceUrl.isNullOrBlank()
        if (sa != sb) return if (sa) a else b
        if (a.lastOpenedAt != b.lastOpenedAt) {
            return if (a.lastOpenedAt > b.lastOpenedAt) a else b
        }
        return if (a.addedAt <= b.addedAt) a else b
    }

    /** Насколько запись «ценнее» для сохранения из пары дублей. Выше — лучше:
     *  статус (дочитанная/читаемая ценнее новой), затем процент прочитанного,
     *  затем позиция внутри книги. */
    private fun dupRank(r: BookRecord): Long =
        r.status * 100_000_000L +
            r.readPct.coerceIn(0, 100) * 1_000_000L +
            r.chapter.coerceIn(0, 999_999) * 1_000L +
            r.sentence.coerceIn(0, 999)

    private fun save(context: Context, list: List<BookRecord>) {
        cache = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(context).writeText(arr.toString()) }
    }

    private fun file(context: Context): File = File(context.filesDir, "library.json")
}
