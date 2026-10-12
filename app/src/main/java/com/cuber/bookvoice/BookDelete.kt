package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Удаление книги: стереть файл на диске и убрать запись с полки.
 *
 * 07.10.2026: код вынесен из `LibraryActivity` (там он и был написан, msg1351 и
 * msg5947), потому что тот же вопрос понадобился ЧИТАЛКЕ: книга дочитана до
 * конца — «удалить или оставить». Держать две копии удаления нельзя: разошлись
 * бы, и одна из них однажды стёрла бы файл, оставив запись-сироту (или наоборот
 * — книгу, которая вернётся на полку следующим сканом папки).
 *
 * Полка (`LibraryActivity`) осталась тонкой прослойкой: её методы
 * `removeBook`/`hideFromScan`/`treeResolver`/`diskPathOf` просто зовут отсюда.
 */
internal object BookDelete {

    /** Спрятанные от авто-скана адреса записей (ключи живут в настройках
     *  «reader», как и были). */
    const val KEY_HIDDEN = "hidden_uris"

    /** msg1347: спрятанные от скана файлы по реальному пути (удалены под
     *  file://-адресом, а скан встречает их как content://-документы). */
    const val KEY_HIDDEN_PATHS = "hidden_paths"

    private fun prefs(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Папка книг, сопоставленная с реальным каталогом на диске. */
    class TreePaths(val dir: File, val treeDocId: String)

    /** Ядро удаления (msg1351, msg5947): стереть файл, если [withFiles], и убрать
     *  запись с полки. Без тостов и озвучки — их зовёт вызывающий: одиночное
     *  удаление говорит про одну книгу, очистка категории — про пачку одним
     *  итогом. Возвращает «файл действительно стёрт».
     *
     *  [withFiles] = false — файл остаётся на диске, но книгу ВСЁ РАВНО прячем
     *  от скана (и запись, и путь): иначе автоскан папки вернёт её на полку
     *  следующей же строкой, и «очистка» окажется пустой. */
    fun book(context: Context, rec: BookRecord, withFiles: Boolean): Boolean {
        var deleted = false
        val uri = runCatching { Uri.parse(rec.uri) }.getOrNull()
        val tp = treeResolver(context)
        if (withFiles && uri != null && uri.scheme == "file") {
            deleted = try {
                uri.path?.let { File(it).delete() } ?: false
            } catch (_: Exception) {
                false
            }
        }
        if (withFiles && !deleted && uri != null && uri.scheme == "content") {
            if (tp != null) {
                val p = diskPathOf(rec.uri, tp)
                if (p != null) {
                    deleted = try { File(p).delete() } catch (_: Exception) { false }
                }
            }
            if (!deleted) {
                deleted = try {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } catch (_: Exception) {
                    false
                }
            }
        }
        val path = tp?.let { diskPathOf(rec.uri, it) }
        // Убираем саму запись и её близнеца-дубль (другой адрес того же файла),
        // чтобы после удаления файла не осталось записи-сироты.
        BookStore.remove(context, rec.uri)
        // Синхронизация (msg6142): «здесь книги нет» само по себе между
        // устройствами не едет — оставляем метку удаления, по ней книга уйдёт с
        // полки второго устройства. Синхронизация выключена — метка не ставится.
        SyncStore.recordDelete(context, rec.name)
        // msg3081+: разобранный кэш книги больше не нужен — книги на полке нет
        // (при withFiles = false файл остаётся, но запись тоже уходит).
        BookCache.remove(context, Uri.parse(rec.uri))
        if (path != null) {
            BookStore.all(context).forEach { r ->
                if (r.uri != rec.uri && diskPathOf(r.uri, tp) == path) {
                    BookStore.remove(context, r.uri)
                    hideFromScan(context, r.uri)
                }
            }
        }
        hideFromScan(context, rec.uri)
        // Файл остался на диске — не стёрся (нет прав/чужой провайдер) или его
        // и не трогали (withFiles = false): прячем и путь, чтобы автоскан не
        // вернул книгу под другим адресом.
        if ((!withFiles || !deleted) && path != null) hidePathFromScan(context, path)
        return deleted
    }

    /** Спрятать uri от авто-скана папки: запись удалена или склеена, но сам
     *  файл остаётся в папке — без скрытия скан вернул бы его строкой. */
    fun hideFromScan(context: Context, uriStr: String) {
        val p = prefs(context)
        val set = (p.getStringSet(KEY_HIDDEN, null) ?: emptySet()).toMutableSet()
        if (set.add(uriStr)) {
            p.edit().putStringSet(KEY_HIDDEN, set).apply()
        }
    }

    /** Спрятать от авто-скана сам файл (по реальному пути на диске): пригодится,
     *  когда запись могла быть под одним адресом, а скан встречает файл под
     *  другим (content://-документ вместо удалённой file://-записи). */
    private fun hidePathFromScan(context: Context, path: String) {
        val p = prefs(context)
        val set = (p.getStringSet(KEY_HIDDEN_PATHS, null) ?: emptySet()).toMutableSet()
        if (set.add(path)) {
            p.edit().putStringSet(KEY_HIDDEN_PATHS, set).apply()
        }
    }

    fun treeResolver(context: Context): TreePaths? {
        val treeStr = prefs(context).getString(LibraryActivity.KEY_TREE, null) ?: return null
        if (!AllFiles.granted(context)) return null
        val tree = runCatching { Uri.parse(treeStr) }.getOrNull() ?: return null
        val dir = AllFiles.resolveDir(tree) ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        return TreePaths(dir, docId)
    }

    /** Реальный путь файла на диске по uri записи, если файл лежит в выбранной
     *  папке: file:// — путь как есть; content://-документ дерева — дерево +
     *  относительный путь документа. Чужой uri (не наша папка, сторонний
     *  провайдер) — null, такой файл сравнить с другими нельзя. */
    fun diskPathOf(uriStr: String, tp: TreePaths): String? {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        return try {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).canonicalPath }
                "content" -> {
                    val doc = DocumentsContract.getDocumentId(uri) ?: return null
                    val prefix = tp.treeDocId + "/"
                    if (!doc.startsWith(prefix)) return null
                    var rel = doc.substring(prefix.length)
                    if (rel.contains("%")) rel = Uri.decode(rel)
                    File(tp.dir, rel).canonicalPath
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
