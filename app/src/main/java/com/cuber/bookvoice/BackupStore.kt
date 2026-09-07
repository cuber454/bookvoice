package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Резервная копия и восстановление (#100). Файл .bvbak — JSON из двух блоков,
 * каждый выбирается галочками и при создании, и при восстановлении:
 *  - «settings» — все настройки программы (prefs "reader", кроме закладок);
 *  - «library» + «bookmarks» + «quotes» — книги с прогрессом, закладки, цитаты.
 * Файлы книг в копию НЕ входят — после восстановления книги сопоставляются с
 * файлами по имени при сканировании/открытии; прямой адрес скачивания
 * (sourceUrl) для новых скачиваний копится в записи книги и попадает в блок 2.
 * Автобэкап (#100, msg996/999): по расписанию при открытии приложения пишет
 * копию в выбранную один раз папку (SAF-tree), хранит последние ~3 файла.
 */
object BackupStore {

    const val KEY_DIR = "backup_dir"
    const val KEY_AUTO = "backup_auto"          // off / day / week / month
    const val KEY_LAST = "backup_last_at"       // epoch-millis последней копии
    const val AUTO_DEFAULT = "week"
    const val AUTO_OFF = "off"

    private const val FILE_PREFIX = "BookVoice"

    /** Частота → миллисекунды периода. */
    fun autoPeriodMs(mode: String): Long? = when (mode) {
        "day" -> 24L * 3600_000
        "week" -> 7L * 24L * 3600_000
        "month" -> 30L * 24L * 3600_000
        else -> null
    }

    fun lastBackupAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST, 0L)

    fun recordBackupTime(context: Context) {
        prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    // ——— Создание ———

    /** Собирает байты .bvbak по включённым блокам. */
    fun build(context: Context, includeSettings: Boolean, includeBooks: Boolean): ByteArray {
        val root = JSONObject()
        root.put("app", "BookVoice")
        root.put("kind", "bvbak")
        root.put("createdAt", System.currentTimeMillis())
        if (includeSettings) root.put("settings", settingsEntries(context))
        if (includeBooks) {
            val lib = JSONArray()
            BookStore.all(context).forEach { lib.put(it.toJson()) }
            root.put("library", lib)
            root.put("bookmarks", bookmarkBlock(context))
            val q = JSONArray()
            QuoteStore.all(context).forEach { q.put(it.toJson()) }
            root.put("quotes", q)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Настройки списком записей {k, type, v} — тип сохраняется, чтобы
     *  восстановление вызвало правильный putString/putInt/… */
    private fun settingsEntries(context: Context): JSONArray {
        val arr = JSONArray()
        prefs(context).all.forEach { (k, v) ->
            if (!k.startsWith("bookmarks_")) {
                val e = JSONObject().put("k", k)
                when (v) {
                    is String -> e.put("type", "string").put("v", v)
                    is Int -> e.put("type", "int").put("v", v)
                    is Long -> e.put("type", "long").put("v", v)
                    is Float -> e.put("type", "float").put("v", v.toDouble())
                    is Boolean -> e.put("type", "bool").put("v", v)
                    else -> return@forEach
                }
                arr.put(e)
            }
        }
        return arr
    }

    /** Блок закладок: ключ «bookmarks_<uri>» → JSON-массив закладок. */
    private fun bookmarkBlock(context: Context): JSONObject {
        val out = JSONObject()
        prefs(context).all.forEach { (k, v) ->
            if (k.startsWith("bookmarks_") && v is String) {
                runCatching { out.put(k, JSONArray(v)) }
            }
        }
        return out
    }

    // ——— Разбор и восстановление ———

    /** Разобранная копия (для диалога «что внутри»). */
    data class BackupFile(
        val createdAt: Long,
        val hasSettings: Boolean,
        val hasBooks: Boolean,
        val bookCount: Int,
        val bookmarkCount: Int,
        val quoteCount: Int,
    )

    data class RestoreResult(
        val booksAdded: Int,
        val booksUpdated: Int,
        val quotesAdded: Int,
    )

    fun parse(context: Context, bytes: ByteArray): BackupFile? = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optString("kind") != "bvbak") return null
        val bm = o.optJSONObject("bookmarks")
        val bmCount = bm?.let { b ->
            var n = 0
            b.keys().forEach { k -> n += (b.optJSONArray(k)?.length() ?: 0) }
            n
        } ?: 0
        BackupFile(
            createdAt = o.optLong("createdAt"),
            hasSettings = o.has("settings"),
            hasBooks = o.has("library"),
            bookCount = o.optJSONArray("library")?.length() ?: 0,
            bookmarkCount = bmCount,
            quoteCount = o.optJSONArray("quotes")?.length() ?: 0,
        )
    }.getOrNull()

    /** Применяет выбранные блоки. Настройки перезаписываются; книги/закладки/
     *  цитаты сливаются (MERGE): у тех же файлов прогресс обновляется, лишнее
     *  не стирается. Возвращает счётчики для голосового отчёта. */
    fun restore(context: Context, bytes: ByteArray,
                includeSettings: Boolean, includeBooks: Boolean): RestoreResult? = runCatching {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        if (o.optString("kind") != "bvbak") return null

        if (includeSettings) {
            val e = prefs(context).edit()
            val arr = o.optJSONArray("settings") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val en = arr.getJSONObject(i)
                val k = en.getString("k")
                if (k.startsWith("bookmarks_")) continue
                when (en.optString("type")) {
                    "string" -> e.putString(k, en.optString("v"))
                    "int" -> e.putInt(k, en.optInt("v"))
                    "long" -> e.putLong(k, en.optLong("v"))
                    "float" -> e.putFloat(k, en.optDouble("v").toFloat())
                    "bool" -> e.putBoolean(k, en.optBoolean("v"))
                }
            }
            e.apply()
        }

        var added = 0
        var updated = 0
        var quotesAdded = 0

        if (includeBooks) {
            // oldUri → uri, под которым книга реально живёт после merge.
            val uriMap = HashMap<String, String>()

            val lib = o.optJSONArray("library")
            if (lib != null) {
                for (i in 0 until lib.length()) {
                    val brec = BookRecord.fromJson(lib.getJSONObject(i))
                    val sameUri = BookStore.byUri(context, brec.uri)
                    if (sameUri != null) {
                        uriMap[brec.uri] = brec.uri
                    } else {
                        // Книга из копии с тем же именем файла и НЕ открытая локально
                        // (напр. после переустановки) — принять её прогресс на свою запись.
                        val nameMatch = BookStore.all(context)
                            .firstOrNull { it.name == brec.name && it.lastOpenedAt == 0L }
                        if (nameMatch != null) {
                            BookStore.upsert(context, nameMatch.copy(
                                title = brec.title ?: nameMatch.title,
                                author = brec.author ?: nameMatch.author,
                                annotation = brec.annotation ?: nameMatch.annotation,
                                sourceUrl = brec.sourceUrl ?: nameMatch.sourceUrl,
                                status = brec.status,
                                chapter = brec.chapter,
                                sentence = brec.sentence,
                                readPct = brec.readPct,
                                voiceEngine = brec.voiceEngine,
                                voice = brec.voice,
                                voiceSpeed = brec.voiceSpeed,
                            ))
                            uriMap[brec.uri] = nameMatch.uri
                            updated++
                        } else {
                            BookStore.upsert(context, brec)
                            uriMap[brec.uri] = brec.uri
                            added++
                        }
                    }
                }
            }

            // Закладки: переносим на реальные uri и сливаем по месту (ch/s).
            val bmBlock = o.optJSONObject("bookmarks")
            if (bmBlock != null) {
                val e = prefs(context).edit()
                bmBlock.keys().forEach { oldKey ->
                    val srcArr = bmBlock.optJSONArray(oldKey) ?: return@forEach
                    val oldUri = oldKey.removePrefix("bookmarks_")
                    val newUri = uriMap[oldUri] ?: oldUri
                    val newKey = "bookmarks_$newUri"
                    val dst = runCatching {
                        val raw = prefs(context).getString(newKey, null)
                        if (raw.isNullOrEmpty() || raw == "[]") null
                        else JSONArray(raw)
                    }.getOrNull() ?: JSONArray()
                    for (i in 0 until srcArr.length()) {
                        val bo = srcArr.getJSONObject(i)
                        val ch = bo.optInt("ch")
                        val s = bo.optInt("s")
                        var dup = false
                        for (j in 0 until dst.length()) {
                            val d = dst.getJSONObject(j)
                            if (d.optInt("ch") == ch && d.optInt("s") == s) { dup = true; break }
                        }
                        if (!dup) dst.put(bo)
                    }
                    e.putString(newKey, dst.toString())
                }
                e.apply()
            }

            // Цитаты: объединение по id + перенос uri сопоставленных книг.
            val qArr = o.optJSONArray("quotes")
            if (qArr != null) {
                val existing = QuoteStore.all(context).map { it.id }.toHashSet()
                val toAdd = ArrayList<Quote>()
                for (i in 0 until qArr.length()) {
                    val q = Quote.fromJson(qArr.getJSONObject(i))
                    if (q.id in existing) continue
                    existing.add(q.id)
                    toAdd.add(q.copy(uri = uriMap[q.uri] ?: q.uri))
                    quotesAdded++
                }
                if (toAdd.isNotEmpty()) QuoteStore.replaceAll(context, QuoteStore.all(context) + toAdd)
            }
        }

        RestoreResult(added, updated, quotesAdded)
    }.getOrNull()

    // ——— Папка для копий (SAF-tree), автобэкап ———

    fun dirUri(context: Context): Uri? =
        prefs(context).getString(KEY_DIR, null)?.let { Uri.parse(it) }

    /** Создаёт в выбранной папке файл копии и возвращает его content-uri
     *  (им можно шериться). null — папка не выбрана или запись не удалась.
     *  Сначала — штатный SAF через provider папки. Если он не умеет
     *  createDocument (MixPlorer и др., msg1170/1172) — пишем реальным путём
     *  через «Доступ ко всем файлам» (AllFiles), как сам проводник. */
    fun writeAutoFile(context: Context, bytes: ByteArray): Uri? {
        val tree = dirUri(context) ?: return null
        val name = filename(System.currentTimeMillis())
        // 1) SAF-запись через provider (работает для обычных папок).
        val saf = runCatching {
            // createDocument в runCatching: если у приложения нет живого права на
            // запись в выбранную папку (отозвано, provider отказал), он кидает
            // SecurityException/IllegalArgumentException — автобэкап не должен
            // из-за этого ронять приложение.
            val doc = DocumentsContract.createDocument(
                context.contentResolver, tree, "application/json", name
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }
            pruneOld(context, keep = 3)
            doc
        }.getOrNull()
        if (saf != null) return saf
        // 2) Реальный путь (All-files): MixPlorer и т.п. не умеют createDocument,
        //    но выбранная папка лежит на диске — пишем File, как сам проводник.
        return runCatching {
            val f = AllFiles.writeViaRealPath(context, tree, name, bytes)
                ?: return@runCatching null
            pruneReal(context, keep = 3)
            Uri.fromFile(f)
        }.getOrNull()
    }

    /** Имя файла копии: BookVoice_2026-09-05_12-33.bvbak */
    fun filename(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US)
        return "${FILE_PREFIX}_${fmt.format(java.util.Date(ts))}.bvbak"
    }

    /** Оставляет последние [keep] файлов BookVoice_* в папке, остальные удаляет. */
    private fun pruneOld(context: Context, keep: Int) {
        val tree = dirUri(context) ?: return
        val list = listFiles(context, tree) ?: return
        if (list.size <= keep) return
        list.sortedByDescending { it.lastModified }
            .drop(keep)
            .forEach { deleteDoc(context, it.uri) }
    }

    /** Подчистка старых копий реальным путём — для папок, где SAF не умеет даже
     *  список/удаление (MixPlorer и т.п.); зовётся после записи реальным путём. */
    private fun pruneReal(context: Context, keep: Int) {
        val tree = dirUri(context) ?: return
        val dir = AllFiles.resolveDir(tree) ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    private data class DocEntry(val uri: Uri, val name: String, val lastModified: Long)

    private fun listFiles(context: Context, tree: Uri): List<DocEntry>? = runCatching {
        // getTreeDocumentId тоже в runCatching: на URI не-дерева кидает
        // IllegalArgumentException, и он не должен валить фон автобэкапа.
        val treeDoc = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc)
        val out = ArrayList<DocEntry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                if (!name.startsWith(FILE_PREFIX)) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out.add(DocEntry(docUri, name, c.getLong(2)))
            }
        }
        out
    }.getOrNull()

    private fun deleteDoc(context: Context, doc: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, doc)
        }
    }
}
