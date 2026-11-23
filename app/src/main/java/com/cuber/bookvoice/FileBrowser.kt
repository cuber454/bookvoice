package com.cuber.bookvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Файлы» — вкладка-менеджер библиотеки (просьба Сержа, 19.11.2026).
 *
 * Полка — это РЕЕСТР: у каждой строки запись со статусом, местом и отметками
 * («не возвращать при скане», склейки дублей). Из реестра файлы и «пропадают»:
 * жалоба тестера «папка указана, книг много, а на полке пять» разбирается
 * именно здесь. Менеджер показывает ДЕЙСТВИТЕЛЬНОЕ содержимое папок, минуя
 * реестр: дерево, как кнопка «Просмотр папок» в @Voice, — «родительская
 * папка», подпапки со счётчиками книжных файлов, файлы текущей папки. Нашёл
 * файл — касаешься: он открывается и, если надо, встаёт на полку; отметка
 * «не возвращать при скане» при этом снимается (менеджер и есть ответ на
 * «файл есть, а его не видно»).
 *
 * Ходит двумя путями, как решает доступ:
 *  - «Доступ ко всем файлам» есть — обычный обход диска от корня «Внутренняя
 *    память» (/storage/emulated/0); стартовая точка — папка книг, наверх можно
 *    подняться до корня;
 *  - доступа нет — ходим внутри выбранной папки книг по SAF-дереву; за её край
 *    менеджер не выпускает, а в меню «⋮» появляется «Смотреть весь телефон».
 *
 * Чтение ленивое: за вход читаем ТОЛЬКО содержимое текущей папки, поэтому
 * папка в тысячу файлов открывается мгновенно и лимит глубины полки (5
 * уровней) здесь не действует. Счётчики файлов внутри подпапок добираются
 * вторым фоном с бюджетом посещений — гигантское дерево не вешает вкладку.
 *
 * Фокус (просьба Сержа про «пятисотый файл»): при открытии вкладки и при входе
 * в папку, если последняя читаемая книга лежит здесь, список сам прокручивается
 * к ней и диктор ставит фокус на неё («500 из 1234» говорит сам скринридер —
 * RecyclerView отдаёт ему позиции). При выходе наверх фокус встаёт на ту
 * подпапку, из которой вышли. Кнопка «К текущей книге» повторяет прыжок в
 * любой момент, входя при необходимости в чужую папку.
 *
 * «Назад» на этой вкладке поднимает на уровень выше и НЕ сворачивает
 * приложение; на верхней границе (корень памяти или край папки книг) работает
 * обычное поведение полки.
 */
class FileBrowser(
    private val act: SectionActivity,
    private val lib: LibraryActivity,
    private val list: RecyclerView,
    private val emptyView: TextView,
) {

    private val prefs by lazy { act.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private val resolver get() = act.contentResolver

    companion object {
        /** Последняя открытая папка менеджера: путь на диске или document id
         *  дерева — форматы не пересекаются («/…» против «том:путь»). */
        private const val KEY_FOLDER = "files_folder"
        /** Сортировка внутри папки: 0 — по названию, 1 — свежие файлы выше,
         *  2 — старые файлы выше. Папки всегда первыми. */
        private const val KEY_SORT = "files_sort"
        private const val SORT_NAME = 0
        private const val SORT_NEW = 1
        private const val SORT_OLD = 2

        /** Бюджет обхода подпапок ради счётчиков (за показ папки) и потолок
         *  числа: дальше пишем «файлов много». */
        private const val COUNT_BUDGET = 3000
        private const val COUNT_CAP = 500
        private const val COUNT_DEPTH = 12

        private const val MODE_NONE = 0
        private const val MODE_DISK = 1
        private const val MODE_SAF = 2

        private const val KIND_PARENT = 0
        private const val KIND_HOME = 1
        private const val KIND_DIR = 2
        private const val KIND_FILE = 3

        /** Цель фокуса после показа списка. */
        private const val F_FIRST = 1
        private const val F_KEEP = 2
        private const val F_BOOK = 3          // последняя книга, если она здесь
        private const val F_BOOK_OR_FIRST = 4 // при входе в папку: книга, иначе — начало
        private const val F_DIR = 5           // подпапка, из которой вышли наверх
    }

    private class Row(
        val kind: Int,
        var title: String,
        var sub: String,
        /** Папка: ключ для входа (путь на диске или document id дерева). */
        var key: String? = null,
        /** Файл: адрес для записи книги (file:// или content:// дерева). */
        var uri: String? = null,
        var file: File? = null,
        var safDocId: String? = null,
        var size: Long = 0L,
        var date: Long = 0L,
        /** Файл уже на полке — запись, найденная при показе папки. */
        var rec: BookRecord? = null,
    )

    /** Вход, пришедший, пока папка читается: будет выполнен, когда показ
     *  закончится (тот же поток UI, гонки нет). */
    private class PendingEnter(
        val key: String?,
        val focus: Int,
        val announce: Boolean,
        val fDir: String?,
    )

    private var pendingRestart: PendingEnter? = null

    // ——— состояние ———

    private var mode = MODE_NONE
    private var rootKey: String? = null
    private var homeKey: String? = null   // папка книг (в режиме диска)
    private var tree: Uri? = null         // только SAF-режим
    private var currentKey: String? = null
    private var loaded = false
    private var busy = false
    /** Номер показа: фон, дочитывающий счётчики, понимает, что устарел. */
    private var gen = 0
    private var rows: List<Row> = emptyList()
    private var searchNeedle = ""
    private var searchFrom = -1
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)

    private fun sortMode(): Int = prefs.getInt(KEY_SORT, SORT_NAME)

    // ——— вход на вкладку и перерисовка ———

    /** Вкладку «Файлы» открыли переключением: если ещё не загружались — идём к
     *  последней папке; иначе перечитываем текущую. В обоих случаях стараемся
     *  встать на последнюю читаемую книгу, если она в этой папке. */
    fun onTabEnter() {
        if (!loaded || currentKey == null) start()
        else enter(currentKey, F_BOOK, announce = false)
    }

    /** refresh() полки пришёл, когда активна эта вкладка. [entering] — человек
     *  только что переключился на неё; иначе это фон (скан, сортировка) —
     *  фокус не трогаем. */
    fun refresh(entering: Boolean) {
        if (entering || !loaded || currentKey == null) onTabEnter()
        else enter(currentKey, F_KEEP, announce = false)
    }

    /** Первый показ за окно: определить доступ и войти в запомненную папку. */
    private fun start() {
        mode = when {
            AllFiles.granted(act) -> MODE_DISK
            lib.treeUri() != null -> MODE_SAF
            else -> MODE_NONE
        }
        if (mode == MODE_DISK) {
            val root = Environment.getExternalStorageDirectory()
            rootKey = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
            homeKey = lib.treeUri()?.let { s ->
                runCatching { AllFiles.resolveDir(Uri.parse(s))?.canonicalPath }.getOrNull()
            }
            val saved = prefs.getString(KEY_FOLDER, null)
            currentKey = when {
                saved != null && isUnderRoot(saved) && File(saved).isDirectory -> saved
                homeKey != null && File(homeKey!!).isDirectory -> homeKey!!
                else -> rootKey
            }
            Diag.log(act, "files", "вкладка «Файлы»: режим диска, входим в ${displayKey(currentKey)}")
        } else if (mode == MODE_SAF) {
            tree = lib.treeUri()?.let { runCatching { Uri.parse(it) }.getOrNull() }
            rootKey = tree?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
            homeKey = rootKey
            val saved = prefs.getString(KEY_FOLDER, null)
            val root = rootKey
            currentKey = if (saved != null && root != null &&
                (saved == root || saved.startsWith("$root/"))
            ) saved else root
            Diag.log(act, "files", "вкладка «Файлы»: режим SAF (нет доступа ко всем файлам), корень $rootKey")
        } else {
            currentKey = null
            loaded = true
            rows = emptyList()
            adapter.notifyDataSetChanged()
            emptyView.text = act.getString(R.string.files_none_mode)
            emptyView.visibility = View.VISIBLE
            Diag.log(act, "files", "вкладка «Файлы»: ни папки книг, ни доступа ко всем файлам — идти некуда")
            return
        }
        enter(currentKey, F_BOOK, announce = false)
    }

    private fun isUnderRoot(path: String): Boolean {
        val r = rootKey ?: return false
        return path == r || path.startsWith(r + File.separator)
    }

    // ——— чтение папки ———

    /** [fDir] — ключ подпапки для цели F_DIR (возврат наверх). */
    private fun enter(key: String?, focus: Int, announce: Boolean, fDir: String? = null) {
        if (key == null) return
        currentKey = key
        prefs.edit().putString(KEY_FOLDER, key).apply()
        if (busy) {
            // Показ уже читается. Не бросаем человека: как только поток
            // закончит, перезапустим чтение текущей папки (pendingRestart).
            gen++   // результат нынешнего чтения больше не нужен
            pendingRestart = PendingEnter(key, focus, announce, fDir)
            return
        }
        busy = true
        val myGen = ++gen
        val keyIsDisk = mode == MODE_DISK
        Thread {
            val out = ArrayList<Row>()
            var err: String? = null
            var subDirs = 0
            var filesN = 0
            var hiddenN = 0
            try {
                if (keyIsDisk) {
                    val dir = File(key)
                    val kids = dir.listFiles()
                    if (kids == null) {
                        err = "папка не читается: $key"
                    } else {
                        val tp = BookDelete.treeResolver(act)
                        val hidden = prefs.getStringSet(BookDelete.KEY_HIDDEN, null) ?: emptySet()
                        val hiddenPaths =
                            prefs.getStringSet(BookDelete.KEY_HIDDEN_PATHS, null) ?: emptySet()
                        val recs = BookStore.all(act)
                        val recByPath = HashMap<String, BookRecord>()
                        if (tp != null) {
                            recs.forEach { r ->
                                BookDelete.diskPathOf(r.uri, tp)?.let { recByPath[it] = r }
                            }
                        }
                        val dirs = ArrayList<File>()
                        val fs = ArrayList<File>()
                        kids.forEach { f ->
                            if (f.name.startsWith(".")) return@forEach
                            if (f.isDirectory) dirs.add(f)
                            else if (f.isFile && lib.supportedFileName(f.name)) fs.add(f)
                        }
                        subDirs = dirs.size
                        filesN = fs.size
                        dirs.sortWith(entryComparator({ it.name }, { it.lastModified() }))
                        fs.sortWith(entryComparator({ it.name }, { it.lastModified() }))
                        val root = rootKey
                        if (key != root) {
                            val parent = dir.parentFile
                            out.add(Row(KIND_PARENT,
                                title = act.getString(R.string.files_up_title),
                                sub = parent?.let {
                                    if (it.absolutePath == root) act.getString(R.string.files_root_name)
                                    else it.name
                                } ?: "",
                                key = runCatching { parent?.canonicalPath }.getOrNull()
                                    ?: parent?.absolutePath))
                        }
                        val home = homeKey
                        if (key == root && home != null && home != root) {
                            out.add(Row(KIND_HOME,
                                title = act.getString(R.string.files_home_title),
                                sub = File(home).name,
                                key = home))
                        }
                        dirs.forEach { d ->
                            out.add(Row(KIND_DIR, title = d.name, sub = "", key = d.absolutePath,
                                file = d, date = d.lastModified()))
                        }
                        fs.forEach { f ->
                            val u = Uri.fromFile(f).toString()
                            val cp = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
                            val rec = BookStore.byUri(act, u) ?: recByPath[cp]
                            if (u in hidden || cp in hiddenPaths) hiddenN++
                            out.add(Row(KIND_FILE,
                                title = f.name,
                                sub = fileSub(f.length(), f.lastModified(), rec),
                                uri = u, file = f, size = f.length(), date = f.lastModified(),
                                rec = rec))
                        }
                    }
                } else {
                    val t = tree
                    val kids = if (t == null) null else listSafChildren(t, key)
                    if (kids == null) {
                        err = "SAF-папка не открылась: $key"
                    } else {
                        val byUri = HashMap<String, BookRecord>()
                        BookStore.all(act).forEach { byUri[it.uri] = it }
                        subDirs = kids.count { it.first }
                        filesN = kids.size - subDirs
                        // Папки первыми всегда: kindOf возвращает «не папка»,
                        // и false (папка) сортируется раньше true (файл).
                        kids.sortWith(entryComparator({ it.second.name }, { it.second.date }, { !it.first }))
                        if (key != rootKey) {
                            val parent = safParent(key)
                            out.add(Row(KIND_PARENT,
                                title = act.getString(R.string.files_up_title),
                                sub = parent?.let { folderDisplayName(it) } ?: "",
                                key = parent))
                        }
                        kids.forEach { (isDir, info) ->
                            if (isDir) {
                                out.add(Row(KIND_DIR, title = info.name, sub = "", key = info.docId,
                                    date = info.date))
                            } else {
                                val u = DocumentsContract.buildDocumentUriUsingTree(t, info.docId)
                                    .toString()
                                val rec = byUri[u]
                                out.add(Row(KIND_FILE,
                                    title = info.name,
                                    sub = fileSub(info.size, info.date, rec),
                                    uri = u, safDocId = info.docId,
                                    size = info.size, date = info.date, rec = rec))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                err = "папка не прочитана: ${e.javaClass.simpleName} ${e.message}"
            }
            val shown = out
            val error = err
            val dirsN = subDirs
            val filesCnt = filesN
            val hiddenCnt = hiddenN
            act.runOnUiThread {
                busy = false
                pendingRestart?.let { pr ->
                    pendingRestart = null
                    if (lib.currentFilterMode() == LibraryActivity.FILTER_FILES) {
                        enter(pr.key, pr.focus, pr.announce, pr.fDir)
                    }
                    return@runOnUiThread
                }
                if (myGen != gen || lib.currentFilterMode() != LibraryActivity.FILTER_FILES) return@runOnUiThread
                if (error != null && currentKey != key) return@runOnUiThread
                rows = shown
                adapter.notifyDataSetChanged()
                loaded = true
                if (error != null) {
                    Diag.log(act, "files", "файлы: $error")
                    emptyView.text = act.getString(R.string.files_unreadable)
                    emptyView.visibility = View.VISIBLE
                    return@runOnUiThread
                }
                val contentRows = rows.count { it.kind == KIND_DIR || it.kind == KIND_FILE }
                emptyView.visibility =
                    if (contentRows == 0) View.VISIBLE else View.GONE
                emptyView.text = act.getString(R.string.files_empty_here)
                Diag.log(
                    act, "files",
                    "файлы: открыли «${displayKey(key)}»: подпапок $dirsN, " +
                        "книжных файлов $filesCnt, скрыто от автоскана $hiddenCnt",
                )
                if (announce) {
                    val name = folderDisplayName(key)
                    list.announceForAccessibility(
                        "Папка «$name», " +
                            act.resources.getQuantityString(R.plurals.files_subdirs, dirsN, dirsN) +
                            ", " +
                            act.resources.getQuantityString(R.plurals.files_n, filesCnt, filesCnt),
                    )
                }
                applyFocus(focus, fDir)
                // Счётчики подпапок добираются после показа списка.
                if (shown.any { it.kind == KIND_DIR }) countPass(myGen, shown)
            }
        }.start()
    }

    /** Общий порядок строк: папки первыми, внутри — выбранный режим. */
    private fun <T> entryComparator(
        nameOf: (T) -> String,
        dateOf: (T) -> Long,
        kindOf: (T) -> Boolean = { false },
    ): Comparator<T> = Comparator { a, b ->
        val byKind = kindOf(a).compareTo(kindOf(b))
        if (byKind != 0) return@Comparator byKind
        when (sortMode()) {
            SORT_NEW -> {
                val c = dateOf(b).compareTo(dateOf(a))
                if (c != 0) c else nameKey(nameOf(a)).compareTo(nameKey(nameOf(b)))
            }
            SORT_OLD -> {
                val c = dateOf(a).compareTo(dateOf(b))
                if (c != 0) c else nameKey(nameOf(a)).compareTo(nameKey(nameOf(b)))
            }
            else -> nameKey(nameOf(a)).compareTo(nameKey(nameOf(b)))
        }
    }

    private fun nameKey(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')

    /** Вторым проходом считаем книжные файлы внутри подпапок и обновляем
     *  подписи строк. Общий бюджет посещений на весь проход. */
    private fun countPass(myGen: Int, shown: List<Row>) {
        val dirRows = shown.filter { it.kind == KIND_DIR }
        Thread {
            var budget = COUNT_BUDGET
            val updates = ArrayList<Pair<Int, String>>()
            for (row in dirRows) {
                val idx = shown.indexOf(row)
                if (budget <= 0) break
                val res = if (mode == MODE_DISK) {
                    val f = row.file ?: continue
                    countDisk(f, budget)
                } else {
                    val k = row.key ?: continue
                    countSaf(k, budget)
                }
                budget -= res.visited
                val text = if (res.over || res.count > COUNT_CAP) act.getString(R.string.files_many)
                else act.resources.getQuantityString(R.plurals.files_n, res.count, res.count)
                updates.add(idx to text)
            }
            act.runOnUiThread {
                if (myGen != gen || updates.isEmpty()) return@runOnUiThread
                updates.forEach { (idx, text) ->
                    if (idx < rows.size && rows[idx].kind == KIND_DIR && rows[idx].sub != text) {
                        rows[idx].sub = text
                        adapter.notifyItemChanged(idx)
                    }
                }
            }
        }.start()
    }

    private class Counted(val count: Int, val visited: Int, val over: Boolean)

    /** Рекурсивный счёт книжных файлов на диске. Скрытые от скана файлы тоже
     *  считаются: менеджер показывает реальность, а не реестр. */
    private fun countDisk(dir: File, budget: Int): Counted {
        var visited = 0
        var count = 0
        var over = false
        fun walk(d: File, depth: Int) {
            if (over) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (f.name.startsWith(".")) continue
                visited++
                if (visited > budget || depth > COUNT_DEPTH) { over = true; return }
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.isFile && lib.supportedFileName(f.name)) count++
            }
        }
        walk(dir, 0)
        return Counted(count, visited, over)
    }

    private fun countSaf(docId: String, budget: Int): Counted {
        val t = tree ?: return Counted(0, 0, false)
        var visited = 0
        var count = 0
        var over = false
        fun walk(id: String, depth: Int) {
            if (over) return
            val kids = listSafChildren(t, id) ?: return
            for ((isDir, info) in kids) {
                if (info.name.startsWith(".")) continue
                visited++
                if (visited > budget || depth > COUNT_DEPTH) { over = true; return }
                if (isDir) walk(info.docId, depth + 1)
                else if (lib.supportedFileName(info.name)) count++
            }
        }
        walk(docId, 0)
        return Counted(count, visited, over)
    }

    private class SafInfo(val docId: String, val name: String, val size: Long, val date: Long)

    /** Листинг ОДНОГО уровня SAF-папки: пары (каталог?, данные). null — папка
     *  не открылась (грант отозван, провайдер умер). */
    private fun listSafChildren(t: Uri, docId: String): MutableList<Pair<Boolean, SafInfo>>? {
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, docId)
            val out = ArrayList<Pair<Boolean, SafInfo>>()
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val date = if (c.isNull(4)) 0L else c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        out.add(true to SafInfo(id, name, size, date))
                    } else if (lib.supportedFileName(name)) {
                        out.add(false to SafInfo(id, name, size, date))
                    }
                }
            }
            out
        } catch (e: Exception) {
            Diag.log(act, "files", "SAF-листинг не вышел ($docId): ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun safParent(key: String): String? {
        val root = rootKey ?: return null
        if (key == root) return null
        val cut = key.lastIndexOf('/')
        if (cut <= 0) return root
        return key.substring(0, cut)
    }

    // ——— фокус и прыжки ———

    /** uri книги, на которую встанем при следующем показе папки (возврат из
     *  читалки мог прийти, пока она ещё читается). */
    private var bookUriOverride: String? = null

    /** Возврат из читалки на вкладке «Файлы» (зовёт полка): перечитать текущую
     *  папку и встать на строку книги, если она здесь. */
    fun returnToBook(uri: String?) {
        if (uri != null) bookUriOverride = uri
        enter(currentKey, F_BOOK, announce = false)
    }

    private fun applyFocus(focus: Int, fDir: String?) {
        if (rows.isEmpty()) { bookUriOverride = null; return }
        var idx = -1
        when (focus) {
            F_FIRST -> idx = 0
            // Фоновая перерисовка (досканили, сменили меню): список на месте и
            // фокус на месте — НЕ двигаем ни то, ни другое. Прокрутку держит сам
            // LinearLayoutManager (notifyDataSetChanged при том же содержимом
            // якорь не сбрасывает).
            F_KEEP -> return
            F_BOOK, F_BOOK_OR_FIRST -> {
                idx = findBookRow()
                if (idx < 0 && focus == F_BOOK_OR_FIRST) idx = 0
                if (idx < 0) {
                    Diag.log(act, "files", "показ папки: последней книги здесь нет — фокус не двигаю")
                }
            }
            F_DIR -> {
                idx = rows.indexOfFirst { it.kind == KIND_DIR && it.key == fDir }
                if (idx < 0) idx = 0
            }
        }
        bookUriOverride = null
        if (idx >= 0) scrollAndFocusRow(idx)
    }

    private fun scrollAndFocusRow(idx: Int) {
        val lm = list.layoutManager ?: return
        // findViewByPosition умеет ЛЮБОЙ LayoutManager — и линейный, и сетку
        // (23.11.2026: раньше здесь был.cast к LinearLayoutManager, и в сетке
        // прыжок фокуса молча пропадал).
        val seen = lm.findViewByPosition(idx)
        if (seen != null && seen.isShown) {
            TabNav.a11yFocusBook(seen)
        } else {
            list.scrollToPosition(idx)
            list.postDelayed({
                val v = lm.findViewByPosition(idx)
                if (v != null && v.isShown) TabNav.a11yFocusBook(v)
            }, 250)
        }
    }

    /** Строка последней читаемой книги в текущем списке, если она здесь. */
    private fun findBookRow(): Int {
        val uri = bookUriOverride ?: prefs.getString(MainActivity.KEY_URI, null) ?: return -1
        return findRowFor(uri)
    }

    /** Индекс строки файла по uri записи книги: сверяем и адрес, и реальный
     *  путь (тот же файл на полке может жить под content://, а мы его видим
     *  file://, и наоборот). */
    private fun findRowFor(bookUri: String): Int {
        for (i in rows.indices) {
            val r = rows[i]
            if (r.kind != KIND_FILE) continue
            if (r.uri == bookUri) return i
            r.rec?.let { if (it.uri == bookUri) return i }
        }
        val tp = if (mode == MODE_DISK) BookDelete.treeResolver(act) else null
        val path = tp?.let { BookDelete.diskPathOf(bookUri, it) }
        if (path != null) {
            for (i in rows.indices) {
                val f = rows[i].file ?: continue
                val cp = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
                if (cp == path) return i
            }
        }
        // Имя — самая запасная сверка: тот же файл мог попасть в реестр третьим
        // адресом; совпадение имени внутри ЭТОЙ папки почти всегда настоящая книга.
        val name = uriTailName(bookUri)
        if (name != null) {
            for (i in rows.indices) {
                val r = rows[i]
                if (r.kind == KIND_FILE && r.title.equals(name, ignoreCase = true)) return i
            }
        }
        return -1
    }

    private fun uriTailName(uriStr: String): String? {
        val u = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        val p = u.path?.substringAfterLast('/') ?: return null
        val clean = Uri.decode(p)
        return clean.takeIf { it.isNotBlank() && it.contains('.') }
    }

    /** «К текущей книге»: войти в папку последней читаемой книги и встать на неё. */
    fun goToCurrentBook() {
        val uri = prefs.getString(MainActivity.KEY_URI, null)
        val rec = uri?.let { BookStore.byUri(act, it) }
            ?: BookStore.continueTarget(act, uri)
        if (rec == null) {
            toast(act.getString(R.string.files_no_book))
            Diag.log(act, "files", "к текущей книге: открытой книги нет")
            return
        }
        val folderKey = folderKeyOfRecord(rec)
        if (folderKey == null) {
            toast(act.getString(R.string.files_book_outside))
            Diag.log(act, "files", "к текущей книге: папку записи не вижу (${rec.uri})")
            return
        }
        Diag.log(act, "files", "к текущей книге: «${displayKey(folderKey)}» (${rec.name})")
        enter(folderKey, F_BOOK, announce = false)
    }

    /** Ключ папки, где лежит файл записи, в терминах текущего режима обхода. */
    private fun folderKeyOfRecord(rec: BookRecord): String? {
        val u = runCatching { Uri.parse(rec.uri) }.getOrNull() ?: return null
        if (mode == MODE_DISK) {
            val path = when (u.scheme) {
                "file" -> u.path
                "content" -> BookDelete.treeResolver(act)?.let { BookDelete.diskPathOf(rec.uri, it) }
                else -> null
            } ?: return null
            val parentDir = File(path).parentFile ?: return null
            val canonical = runCatching { parentDir.canonicalPath }.getOrNull()
                ?: parentDir.absolutePath
            return if (isUnderRoot(canonical)) canonical else null
        }
        if (mode == MODE_SAF && u.scheme == "content") {
            val docId = runCatching { DocumentsContract.getDocumentId(u) }.getOrNull() ?: return null
            val root = rootKey ?: return null
            if (!(docId == root || docId.startsWith("$root/"))) return null
            return if (docId.contains('/')) docId.substringBeforeLast('/') else root
        }
        return null
    }

    /** «Назад» на вкладке «Файлы»: true — поднялись на уровень выше, окно
     *  ничего больше не делает. */
    fun onBack(): Boolean {
        if (mode == MODE_NONE || !loaded || currentKey == null) return false
        val root = rootKey ?: return false
        val key = currentKey!!
        if (key == root) return false
        val parent = if (mode == MODE_DISK) {
            runCatching { File(key).parentFile?.canonicalPath }.getOrNull() ?: return false
        } else {
            safParent(key) ?: return false
        }
        Diag.log(act, "files", "назад: из «${displayKey(key)}» в «${displayKey(parent)}»")
        enter(parent, F_DIR, announce = false, fDir = key)
        return true
    }

    // ——— поиск по началу слова ———

    fun showSearch() {
        val field = EditText(act).apply {
            hint = act.getString(R.string.files_search_hint)
            // 22.11.2026 (найдено на эмуляторе): поле помнило прошлое слово, и
            // диктованное сверху приклеивалось к нему («Проверка»+прежнее
            // «afb2» = «afb2Проверка», поиск по такому никогда не находит).
            // Прежний поиск помнит searchNeedle — показывать его нечем.
            setText("")
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.files_search_title)
            .setView(field)
            .setPositiveButton(R.string.files_search_go) { _, _ ->
                val raw = field.text.toString()
                val needle = SearchMatch.normalizeNeedle(raw)
                if (needle.isEmpty()) {
                    toast(act.getString(R.string.files_search_empty))
                } else {
                    // Тем же словом, что искали, — следующий круг; новое слово — с начала.
                    val fromStart = raw != searchNeedle || searchFrom < 0
                    searchNeedle = raw
                    jumpMatch(needle, fromStart)
                }
            }
            .setNegativeButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    private fun jumpMatch(needle: String, fromStart: Boolean) {
        val from = if (fromStart) 0 else (searchFrom + 1).coerceAtLeast(0)
        var idx = -1
        if (rows.isNotEmpty()) {
            for (step in rows.indices) {
                val i = (from + step) % rows.size
                val r = rows[i]
                if (r.kind != KIND_DIR && r.kind != KIND_FILE) continue
                if (SearchMatch.contains(r.title, needle)) { idx = i; break }
            }
        }
        if (idx < 0) {
            searchFrom = -1
            Diag.log(act, "files", "поиск «$needle»: не нашёл в этой папке")
            toast(act.getString(R.string.files_not_found, needle))
            return
        }
        searchFrom = idx
        Diag.log(act, "files", "поиск «$needle»: строка ${idx + 1} из ${rows.size}")
        scrollAndFocusRow(idx)
    }

    // ——— открытие, удаление, сведения ———

    private fun onRowClick(r: Row) {
        when (r.kind) {
            KIND_PARENT -> {
                val prev = currentKey
                val parent = r.key ?: return
                enter(parent, F_DIR, announce = false, fDir = prev)
            }
            KIND_HOME, KIND_DIR -> enter(r.key, F_BOOK_OR_FIRST, announce = true)
            KIND_FILE -> openFile(r)
        }
    }

    /** Касание файла: снять метку «не возвращать при скане» (она молчала, пока
     *  человек не пришёл сюда руками) и открыть. Нового файла на полке нет —
     *  обычным путём полки заведём запись и откроем. */
    private fun openFile(r: Row) {
        val uriStr = r.uri ?: return
        BookDelete.unhideFromScan(act, uriStr)
        if (mode == MODE_DISK) {
            val f = r.file
            if (f != null) {
                val cp = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
                BookDelete.unhidePathFromScan(act, cp)
            }
        }
        val rec = r.rec ?: BookStore.byUri(act, uriStr)
        if (rec != null) {
            Diag.log(act, "files", "открыли файл с полки: ${r.title}")
            lib.openReaderViaBrowser(rec)
            return
        }
        Diag.log(act, "files", "файла на полке не было, заводим запись: ${r.title}")
        lib.openNewFileViaBrowser(Uri.parse(uriStr), r.title)
    }

    private fun onRowLongClick(r: Row): Boolean {
        when (r.kind) {
            KIND_FILE -> {
                val rec = r.rec ?: BookStore.byUri(act, r.uri ?: return false)
                // Файл с полки: то же меню книги, но менеджер дописывает
                // последний пункт «Удалить файл» (23.11.2026, просьба Сержа) —
                // действие ровно то, что у файла вне полки: файл с диска,
                // запись уходит вместе с ним.
                if (rec != null) lib.bookMenuViaBrowser(rec, fileDeleteAction(r)) else fileMenu(r)
                return true
            }
            KIND_DIR, KIND_HOME, KIND_PARENT -> {
                if (r.key == null) return false
                folderMenu(r)
                return true
            }
            else -> return false
        }
    }

    /** Пункт «Удалить файл» для меню файла в менеджере — общий для полок
     *  полки и вне её (23.11.2026). */
    private fun fileDeleteAction(r: Row) =
        act.getString(R.string.files_menu_delete) to { confirmDeleteFile(r) }

    /** Меню папки (20.11.2026, просьба Сержа): «добавить всё в библиотеку» и
     *  «сделать папкой книг». Массовое добавление метки «не возвращать при
     *  скане» НЕ снимает: касание одного файла — человек хотел эту книгу, а
     *  пачка не имеет права трогать то, что пряталось осознанно; число скрытых
     *  говорим в отчёте, возвращать — по одному.
     *  23.11.2026 (просьба Сержа: «на файле и на папке добавился пункт
     *  удалить»): третий пункт — «Удалить папку» с переспросом, опасное
     *  последним; на самой папке книг и на служебных строках («Наверх»,
     *  «В папку книг») его НЕТ — эти папки менеджеру стереть не во что. */
    private fun folderMenu(r: Row) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, action: () -> Unit) {
            labels.add(label); actions.add(action)
        }
        item(act.getString(R.string.files_folder_add_all)) { addFolderToShelf(r.key!!) }
        // «Сделать папкой книг» бессмысленно на самой папке книг.
        if (r.key != homeKey) {
            item(act.getString(R.string.files_folder_make_books)) { confirmMakeBooksFolder(r.key!!) }
        }
        // Стереть можно только настоящую подпапку (KIND_DIR): служебные строки
        // ведут в папки, которые мы показываем снаружи («Наверх» — родитель,
        // «В папку книг» — корень полки).
        if (r.kind == KIND_DIR && r.key != homeKey) {
            item(act.getString(R.string.files_menu_delete_dir)) { confirmDeleteFolder(r) }
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(r.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun confirmDeleteFolder(r: Row) {
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.files_delete_dir_title)
            .setMessage(act.getString(R.string.files_delete_dir_msg, r.title))
            .setPositiveButton(act.getString(R.string.files_menu_delete_dir)) { _, _ -> deleteFolder(r) }
            .setNegativeButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    /** Удалить папку со всем её содержимым с диска (23.11.2026). Записи
     *  полки отсюда НЕ трогаем: файлы честно исчезнут, а мёртвые строки
     *  уберёт скан («запись без файла убрана с полки») — тот же путь, что
     *  работает для сторонних менеджеров. В SAF deleteDocument на непустой
     *  папке у внешнего носителя удаляет рекурсией; если провайдер
     *  откажет — говорим правду, файлы останутся на месте. */
    private fun deleteFolder(r: Row) {
        val key = r.key ?: return
        val modeIsDisk = mode == MODE_DISK
        val t = tree
        Thread {
            var ok = false
            var err: String? = null
            var booksN = 0
            try {
                if (modeIsDisk) {
                    val d = File(key)
                    if (!d.isDirectory) {
                        err = "папка не найдена: $key"
                    } else {
                        booksN = countDisk(d, COUNT_BUDGET).count
                        ok = runCatching { d.deleteRecursively() }.getOrDefault(false)
                        if (!ok && err == null) err = "диск ответил «не удалось»"
                    }
                } else {
                    if (t == null) {
                        err = "дерево SAF недоступно"
                    } else {
                        booksN = countSaf(key, COUNT_BUDGET).count
                        val u = DocumentsContract.buildDocumentUriUsingTree(t, key)
                        ok = runCatching { DocumentsContract.deleteDocument(resolver, u) }
                            .getOrDefault(false)
                        if (!ok && err == null) err = "проводник ответил «не удалось»"
                    }
                }
            } catch (e: Exception) {
                err = "${e.javaClass.simpleName} ${e.message}"
            }
            val done = ok
            val why = err
            val n = booksN
            act.runOnUiThread {
                if (done) {
                    toast(act.getString(R.string.files_dir_deleted, r.title))
                    Diag.log(act, "files", "папка удалена: «${displayKey(key)}», " +
                        "книжных файлов в ней было $n — записи уберёт скан")
                    enter(currentKey, F_KEEP, announce = false)
                } else {
                    toast(act.getString(R.string.files_dir_delete_failed))
                    Diag.log(act, "files", "папку стереть не удалось: «${displayKey(key)}» — $why")
                }
            }
        }.start()
    }

    // ——— массовое добавление папки в библиотеку ———

    /** Сколько новых файлов молча добавляем без переспроса. Больше — спрашиваем:
     *  «добавить всё» в папке на три тысячи раздач превратило бы полку в другую
     *  библиотеку за одно долгое касание. */
    private val ADD_CONFIRM_ABOVE = 50
    /** Потолок обхода: файлов и посещенных папок. Дерево больше этого — говорим
     *  про потолок, а не вешаем телефон. */
    private val ADD_WALK_LIMIT = 20000

    /** Список файлов папки со всем её нутром: (uri, имя, путь на диске или null). */
    private class FoundFile(val uriStr: String, val name: String, val path: String?)

    private fun addFolderToShelf(key: String) {
        val busyNow = busy
        if (busyNow) {
            toast(act.getString(R.string.files_wait_folder))
            return
        }
        Diag.log(act, "files", "добавляем папку в библиотеку: ${displayKey(key)}")
        toast(act.getString(R.string.files_add_busy))
        val myMode = mode
        val t = tree
        Thread {
            val found = ArrayList<FoundFile>()
            var over = false
            try {
                if (myMode == MODE_DISK) {
                    var visited = 0
                    fun walk(d: File, depth: Int) {
                        if (over) return
                        val kids = d.listFiles() ?: return
                        for (f in kids) {
                            if (f.name.startsWith(".")) continue
                            visited++
                            if (visited > ADD_WALK_LIMIT || depth > 15) { over = true; return }
                            if (f.isDirectory) walk(f, depth + 1)
                            else if (f.isFile && lib.supportedFileName(f.name)) {
                                found.add(FoundFile(
                                    Uri.fromFile(f).toString(), f.name,
                                    runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath,
                                ))
                            }
                        }
                    }
                    walk(File(key), 0)
                } else if (t != null) {
                    var visited = 0
                    fun walk(id: String, depth: Int) {
                        if (over) return
                        val kids = listSafChildren(t, id) ?: return
                        for ((isDir, info) in kids) {
                            visited++
                            if (visited > ADD_WALK_LIMIT || depth > 15) { over = true; return }
                            if (isDir) walk(info.docId, depth + 1)
                            else if (lib.supportedFileName(info.name)) {
                                found.add(FoundFile(
                                    DocumentsContract.buildDocumentUriUsingTree(t, info.docId)
                                        .toString(), info.name, null,
                                ))
                            }
                        }
                    }
                    walk(key, 0)
                }
            } catch (e: Exception) {
                Diag.log(act, "files", "обход при добавлении не вышел: ${e.javaClass.simpleName} ${e.message}")
            }
            // Кому уже есть место на полке, кого прячет метка.
            val hiddenU = prefs.getStringSet(BookDelete.KEY_HIDDEN, null) ?: emptySet()
            val hiddenP = prefs.getStringSet(BookDelete.KEY_HIDDEN_PATHS, null) ?: emptySet()
            val have = HashSet(BookStore.all(act).map { it.uri })
            val tp = BookDelete.treeResolver(act)
            val havePaths = if (tp != null) {
                HashSet(BookStore.all(act).mapNotNull { BookDelete.diskPathOf(it.uri, tp) })
            } else emptySet()
            val fresh = ArrayList<FoundFile>()
            var already = 0
            var hidden = 0
            for (f in found) {
                if (f.uriStr in have || (f.path != null && f.path in havePaths)) {
                    already++
                } else if (f.uriStr in hiddenU || (f.path != null && f.path in hiddenP)) {
                    hidden++
                } else {
                    fresh.add(f)
                }
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (fresh.isEmpty()) {
                    val sb = StringBuilder(act.getString(R.string.files_add_none))
                    if (already > 0) sb.append(" ").append(
                        act.getString(R.string.files_add_already_were, already))
                    if (hidden > 0) sb.append(" ").append(
                        act.getString(R.string.files_add_hidden_were, hidden))
                    toast(sb.toString())
                    Diag.log(act, "files", "добавили «${displayKey(key)}»: новых нет (были $already, скрыты $hidden)")
                    return@runOnUiThread
                }
                if (fresh.size > ADD_CONFIRM_ABOVE) {
                    MaterialAlertDialogBuilder(act)
                        .setTitle(act.getString(R.string.files_add_confirm_title, fresh.size))
                        .setMessage(act.getString(R.string.files_add_confirm_msg, folderDisplayName(key)))
                        .setPositiveButton(R.string.files_add_go) { _, _ -> runAdd(key, fresh, already, hidden, over) }
                        .setNegativeButton(act.getString(R.string.dialog_close), null)
                        .show()
                } else {
                    runAdd(key, fresh, already, hidden, over)
                }
            }
        }.start()
    }

    private fun runAdd(
        key: String,
        fresh: List<FoundFile>,
        already: Int,
        hidden: Int,
        over: Boolean,
    ) {
        toast(act.getString(R.string.files_add_busy))
        Thread {
            var added = 0
            for (i in fresh.indices) {
                if (i > 0 && i % 200 == 0) {
                    // Отчёт о ходе: тысячи файлов читают метаданные не мгновенно,
                    // и по журналу должно быть видно, что добавление живёт, а
                    // не зависло.
                    Diag.log(act, "files", "добавляем «${displayKey(key)}»: $i из ${fresh.size}")
                }
                if (lib.addFoundFileToShelf(fresh[i].uriStr, fresh[i].name)) added++
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val sb = StringBuilder(act.getString(R.string.files_add_done_head))
                    .append(' ')
                    .append(act.resources.getQuantityString(R.plurals.files_added, added, added))
                if (already > 0) {
                    sb.append(". ").append(act.getString(R.string.files_add_already_were, already))
                }
                if (hidden > 0) {
                    sb.append(". ").append(act.getString(R.string.files_add_hidden_were, hidden))
                }
                if (over) sb.append(". ").append(act.getString(R.string.files_walk_limit))
                Diag.log(
                    act, "files",
                    "добавили «${displayKey(key)}»: новых $added, уже были $already, скрыты меткой $hidden",
                )
                toast(sb.toString())
                // Подписи «на полке» в списке устарели — перечитать папку без
                // переноса фокуса.
                enter(currentKey, F_KEEP, announce = false)
            }
        }.start()
    }

    /** «Сделать эту папку папкой книг»: переспрос, затем системный выбор,
     *  открытый сразу внутри папки. Тихо заменить папку книг нельзя — система
     *  требует подтверждения человека; мы лишь ведём его за руку. */
    private fun confirmMakeBooksFolder(key: String) {
        MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.files_make_title, folderDisplayName(key)))
            .setMessage(act.getString(R.string.files_make_msg))
            .setPositiveButton(R.string.files_make_go) { _, _ ->
                val initial = treeInitialUri(key)
                Diag.log(
                    act, "files",
                    "делаем «${displayKey(key)}» папкой книг: открываем выбор " +
                        (initial ?: "(стартовый адрес не собрать — откроется обычный)"),
                )
                lib.openBooksFolderPicker(initial)
            }
            .setNegativeButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    /** Стартовый адрес системного выбора папок для этой папки обхода: в
     *  SAF-режиме — document id как есть,authority берём у нашего дерева; в
     *  дисковом — «primary:путь» относительно корня внутренней памяти. */
    private fun treeInitialUri(key: String): Uri? {
        val authority = if (mode == MODE_SAF) {
            tree?.authority ?: "com.android.externalstorage.documents"
        } else "com.android.externalstorage.documents"
        val docId = if (mode == MODE_SAF) {
            key
        } else {
            val root = rootKey ?: return null
            if (key == root) {
                "primary:"
            } else if (key.startsWith(root + "/")) {
                "primary:" + key.removePrefix(root + "/")
            } else return null
        }
        return runCatching {
            Uri.parse("content://$authority/tree/" + Uri.encode(docId))
        }.getOrNull()
    }

    /** Меню файла, которого на полке НЕТ (23.11.2026, просьба Сержа): «Добавить
     *  в библиотеку» — молча завести запись, не открывая читалку (касанием —
     *  как раньше, добавить и читать). Скрытую метку «не возвращать при скане»
     *  добавление снимает: человек сам принёс книгу обратно. Если файл уже
     *  заведен (дубль по пути под другим адресом) — скажет общий ответ двери. */
    private fun fileMenu(r: Row) {
        val labels = arrayOf(
            act.getString(R.string.files_menu_add),
            act.getString(R.string.files_menu_info),
            act.getString(R.string.files_menu_delete),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(r.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> addFileToShelf(r)
                    1 -> showFileInfo(r)
                    2 -> confirmDeleteFile(r)
                }
            }
            .show()
    }

    private fun addFileToShelf(r: Row) {
        val uriStr = r.uri ?: return
        val added = lib.addFoundFileToShelf(uriStr, r.rec?.name ?: r.title)
        Diag.log(
            act, "files",
            if (added) "добавили в библиотеку: ${r.title}" else "уже в библиотеке: ${r.title}",
        )
        if (added) {
            BookDelete.unhideFromScan(act, uriStr)
            r.file?.let {
                val cp = runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath
                BookDelete.unhidePathFromScan(act, cp)
            }
        }
        toast(act.getString(
            if (added) R.string.files_add_done else R.string.files_add_was,
            r.title,
        ))
    }

    private fun showFileInfo(r: Row) {
        val sb = StringBuilder()
        sb.append(act.getString(R.string.files_info_path)).append(": ")
            .append(displayKey(currentKey)).append('\n')
        if (r.size > 0) sb.append(act.getString(R.string.files_info_size)).append(": ")
            .append(humanSize(r.size)).append('\n')
        if (r.date > 0) sb.append(act.getString(R.string.files_info_date)).append(": ")
            .append(dateFmt.format(Date(r.date))).append('\n')
        MaterialAlertDialogBuilder(act)
            .setTitle(r.title)
            .setMessage(sb.toString())
            .setPositiveButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    private fun confirmDeleteFile(r: Row) {
        // 23.11.2026: пункт «Удалить файл» есть и у файла на полке — переспрос
        // там честнее: без файла запись всё равно мертва, deleteFile убирает
        // её вместе с близнецами.
        val onShelf = r.rec != null ||
            (r.uri != null && BookStore.byUri(act, r.uri!!) != null)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.files_delete_title)
            .setMessage(act.getString(
                if (onShelf) R.string.files_delete_msg_shelf else R.string.files_delete_msg,
                r.title,
            ))
            .setPositiveButton(R.string.files_menu_delete) { _, _ -> deleteFile(r) }
            .setNegativeButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    private fun deleteFile(r: Row) {
        val ok = if (mode == MODE_DISK) {
            val f = r.file
            f != null && runCatching { f.delete() }.getOrDefault(false)
        } else {
            val doc = r.safDocId
            val t = tree
            if (doc == null || t == null) false
            else runCatching {
                DocumentsContract.deleteDocument(
                    resolver, DocumentsContract.buildDocumentUriUsingTree(t, doc),
                )
            }.getOrDefault(false)
        }
        Diag.log(act, "files", if (ok) "файл удалён: ${r.title}" else "файл НЕ удалён: ${r.title}")
        toast(act.getString(if (ok) R.string.files_delete_ok else R.string.files_delete_fail))
        if (!ok) return
        // Записи на полке, ссылающиеся на этот файл (в том числе под другим
        // адресом того же файла), умерли вместе с ним — убираем, чтобы не
        // висели мёртвыми строками.
        val tp = BookDelete.treeResolver(act)
        val uriStr = r.uri
        val path = r.file?.let { runCatching { it.canonicalPath }.getOrNull() ?: it.absolutePath }
            ?: if (uriStr != null && tp != null) BookDelete.diskPathOf(uriStr, tp) else null
        if (uriStr != null) BookStore.remove(act, uriStr)
        if (path != null && tp != null) {
            BookStore.all(act).forEach { other ->
                if (other.uri != uriStr && BookDelete.diskPathOf(other.uri, tp) == path) {
                    BookStore.remove(act, other.uri)
                    Diag.log(act, "files", "убрана и запись-близнец: ${other.name}")
                }
            }
        }
        enter(currentKey, F_FIRST, announce = false)
    }

    // ——— меню «⋮» вкладки и сортировка ———

    fun showMenu() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, action: () -> Unit) {
            labels.add(label); actions.add(action)
        }
        item(act.getString(R.string.files_menu_refresh)) { enter(currentKey, F_KEEP, announce = false) }
        // 23.11.2026 (Серж: вид «список/сетка» — одинаковый для всех вкладок):
        // тот же общий диалог, что на полке, прямо отсюда — не сходить же
        // ради переключения с папок. 23.11 (просьба Сержа «чтобы пункт меню
        // назывался одинаково»): подпись пункта — ОДНА строка `lib_menu_view`
        // и здесь, и на полке («Вид: список или сетка»); `lib_view_title`
        // остаётся заголовком самого диалога.
        item(act.getString(R.string.lib_menu_view)) { lib.showShelfViewDialog() }
        item(act.getString(R.string.files_menu_system)) { lib.openSystemFileViaBrowser() }
        if (mode == MODE_SAF) item(act.getString(R.string.files_menu_all)) {
            AllFiles.ask(act, act.getString(R.string.files_all_explain))
        }
        if (mode == MODE_DISK && homeKey != null) item(act.getString(R.string.files_go_home)) {
            enter(homeKey, F_BOOK_OR_FIRST, announce = true)
        }
        // msg4693: «Настройки» — прямо перед «Выходом», в конце списка её и ищут.
        item(act.getString(R.string.settings_title)) {
            act.startActivity(Intent(act, SettingsWindowActivity::class.java))
        }
        item(act.getString(R.string.app_exit)) { TabNav.exitApp(act) }
        MaterialAlertDialogBuilder(act)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    fun showSortDialog() {
        val opts = arrayOf(
            act.getString(R.string.files_sort_name),
            act.getString(R.string.files_sort_new),
            act.getString(R.string.files_sort_old),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.files_sort_title)
            .setSingleChoiceItems(opts, sortMode()) { d, which ->
                prefs.edit().putInt(KEY_SORT, which).apply()
                d.dismiss()
                enter(currentKey, F_KEEP, announce = false)
            }
            .setNegativeButton(act.getString(R.string.dialog_close), null)
            .show()
    }

    // ——— подписи строк ———

    private fun fileSub(size: Long, date: Long, rec: BookRecord?): String {
        val parts = ArrayList<String>()
        if (size > 0) parts.add(humanSize(size))
        if (date > 0) parts.add(dateFmt.format(Date(date)))
        if (rec != null) parts.add(act.getString(R.string.files_on_shelf))
        return parts.joinToString(" · ")
    }

    private fun humanSize(b: Long): String {
        if (b < 1024) return "$b Б"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f МБ", mb)
        return String.format(Locale.ROOT, "%.2f ГБ", mb / 1024.0)
    }

    /** Имя папки для озвучки, заголовков и журнала: корень диска — «Внутренняя
     *  память», SAF-корень — том и путь после «двоеточия». */
    private fun folderDisplayName(key: String?): String {
        if (key == null) return ""
        if (mode == MODE_DISK) {
            if (key == rootKey) return act.getString(R.string.files_root_name)
            return File(key).name
        }
        val root = rootKey
        if (key == root) return key.substringAfterLast(':')
        return key.substringAfterLast('/')
    }

    private fun displayKey(key: String?): String = folderDisplayName(key)

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    // ——— адаптер ———

    private val adapter = RowsAdapter()

    /** Отдать список менеджера окну (вкладка стала активной).
     *  23.11.2026 (Серж: «вид — одинаково для всех вкладок»): раскладку
     *  (строки или сетка карточек) здесь НЕ выбираем — общим RecyclerView'ом
     *  ведёт LibraryActivity.applyViewMode, он же скажет нам о смене вида. */
    fun attach() {
        if (list.adapter !== adapter) list.adapter = adapter
    }

    /** Общий вид полки сменился («список» ↔ «сетка»): холдеры карточек
     *  другие, пересоздаём начисто. */
    fun onViewModeChanged() {
        adapter.notifyDataSetChanged()
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        /** 23.11.2026 (Серж: «делай одинаково для всех вкладок»): вид общий с
         *  полкой — 0 строки, 1 сетка карточек (тот же layout item_book_grid). */
        override fun getItemViewType(position: Int): Int = if (lib.shelfViewGrid()) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 1) {
                // Карточка: корень — не TextView; дети не важны для доступности,
                // узел один, текст ему ставим в onBind (как на полке).
                object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_book_grid, parent, false)) {}
            } else {
                val tv = inf.inflate(R.layout.item_book, parent, false) as TextView
                object : RecyclerView.ViewHolder(tv) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val v = holder.itemView
            if (v is TextView) {
                v.text = if (r.sub.isEmpty()) r.title else r.title + "\n" + r.sub
            } else {
                val tvTitle = v.findViewById<TextView>(R.id.tvCardTitle)
                val tvAuthor = v.findViewById<TextView>(R.id.tvCardAuthor)
                val iv = v.findViewById<android.widget.ImageView>(R.id.ivCardCover)
                val badge = v.findViewById<TextView>(R.id.tvCardProgress)
                badge.visibility = View.GONE
                badge.tag = null
                // Обложку просим только у файлов; папки («Наверх», подпапки)
                // остаются текстовыми карточками — картинки у папки нет.
                val source = if (r.kind == KIND_FILE) lib.fileCardCovers() else null
                val uriKey = r.uri
                if (source != null && uriKey != null) {
                    tvTitle.visibility = View.GONE
                    tvTitle.text = ""
                    tvAuthor.visibility = View.GONE
                    tvAuthor.text = ""
                    iv.visibility = View.VISIBLE
                    iv.tag = uriKey
                    val rec = r.rec ?: BookRecord(uri = uriKey, name = r.title, addedAt = r.date)
                    val ready = source.cached(rec)
                    if (ready != null) {
                        iv.setImageBitmap(ready)
                    } else {
                        // Заглушка ВСЕГДА: холдер из переработки мог принести
                        // чужую картинку (то же правило, что на полке).
                        iv.setImageBitmap(source.placeholder(rec))
                        source.request(rec) { bmp -> if (iv.tag == uriKey) iv.setImageBitmap(bmp) }
                    }
                } else {
                    iv.visibility = View.GONE
                    iv.setImageDrawable(null)
                    iv.tag = null
                    tvTitle.visibility = View.VISIBLE
                    tvTitle.text = r.title
                    tvAuthor.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
                    tvAuthor.text = r.sub
                }
                v.contentDescription =
                    if (r.sub.isEmpty()) r.title else r.title + ", " + r.sub
            }
            v.setOnClickListener { onRowClick(r) }
            v.setOnLongClickListener { onRowLongClick(r) }
            // Те же действия, что по долгому касанию, — в меню TalkBack
            // («Действия»), как у строк полки (msg7003).
            val actions = ArrayList<Pair<String, () -> Unit>>()
            when (r.kind) {
                KIND_FILE -> {
                    val rec = r.rec
                    if (rec != null) {
                        actions.addAll(lib.bookRowActionsViaBrowser(rec))
                        // и свой последний пункт, как при долгом (23.11.2026):
                        actions.add(fileDeleteAction(r))
                    } else {
                        actions.add(act.getString(R.string.files_menu_add) to { addFileToShelf(r) })
                        actions.add(act.getString(R.string.files_menu_delete) to { confirmDeleteFile(r) })
                    }
                }
                KIND_DIR, KIND_HOME, KIND_PARENT -> {
                    val key = r.key
                    if (key != null) {
                        actions.add(act.getString(R.string.files_folder_add_all) to { addFolderToShelf(key) })
                        if (key != homeKey) {
                            actions.add(
                                act.getString(R.string.files_folder_make_books) to
                                    { confirmMakeBooksFolder(key) },
                            )
                        }
                        if (r.kind == KIND_DIR && key != homeKey) {
                            actions.add(
                                act.getString(R.string.files_menu_delete_dir) to
                                    { confirmDeleteFolder(r) },
                            )
                        }
                    }
                }
            }
            if (actions.isEmpty()) {
                // Ряд приходит из переработки: на нём мог остаться делегат
                // прежней строки-файла с её действиями. Снимаем, у папок и
                // служебных строк действий нет.
                v.accessibilityDelegate = null
            } else {
                v.setA11yActions(*actions.toTypedArray())
            }
        }
    }
}
