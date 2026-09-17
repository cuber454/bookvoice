package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Синхронизация между устройствами (msg6046…6074): в папке облака лежит один
 * маленький файл, который читают и пишут оба устройства. Переносятся МЕСТА
 * чтения, закладки, цитаты и избранное. Файлы книг и настройки синтеза не
 * переносятся сознательно: голоса живут в движках конкретного устройства, и
 * перенесённый голос на другом телефоне не найдётся.
 *
 * Доступ к папке даёт владелец системным выбором папки — ни нашего сервера, ни
 * входа в чужой аккаунт не нужно. Папка должна быть ОДНА и та же на обоих
 * устройствах, иначе они друг друга не найдут; по умолчанию берём уже выбранную
 * папку книг (если она в облаке) или папку резервных копий, чтобы владельцу не
 * приходилось выбирать вторую.
 *
 * Сопоставление книг — по ИМЕНИ ФАЙЛА: книга на каждом устройстве лежит по
 * своему адресу (content://), а имя у неё одно и то же. Ограничение: две разные
 * книги с одинаковым именем файла сольются в одну запись — случай редкий, но он
 * есть, и молчать о нём нельзя.
 *
 * Файл — объединение, а не «наше состояние»: в него пишутся и чужие записи, даже
 * если локально такой книги нет. Иначе устройство без книги стирало бы чужое
 * место (а книга на втором устройстве может появиться позже).
 *
 * Свежесть записи — [BookRecord.lastOpenedAt]: книгу, которую читали, всегда
 * считаем свежее книги, которую только добавили в полку (иначе скан папки на
 * втором устройстве затирал бы место нулём).
 */
object SyncStore {

    const val KEY_ON = "sync_on"
    const val KEY_DIR = "sync_dir"

    /** Переносить и книги (msg6130). Отдельная галочка: заливка полки занимает
     *  место на Диске и время в сети, включать её вместе с местами чтения молча
     *  нельзя. По умолчанию выключена. */
    const val KEY_BOOKS = "sync_books"

    /** Сколько книг лежит на Диске, которых у нас нет (для строки на экране).
     *  -1 — ещё не считали; счёт обновляется при каждом плане и прогоне. */
    private const val KEY_DISK_N = "sync_disk_n"

    private const val KEY_LAST_AT = "sync_last_at"
    private const val KEY_LAST_RESULT = "sync_last_result"
    /** Что мы знаем о других устройствах (msg6086). Нужно ручному пути: файл
     *  собирается из своей полки, а чужая книга в полку не заводится — без этой
     *  памяти она потерялась бы при отправке файла обратно. */
    private const val KEY_CARRY = "sync_carry"

    /** Имя файла в облачной папке. Видно владельцу в проводнике/Диске — по нему
     *  он понимает, что синхронизация живая. */
    const val FILE_NAME = "BookVoice_sync.json"

    private const val KIND = "bvsync"
    private const val VERSION = 1

    /** Один прогон за раз: автосинхронизация может позваться и с полки, и из
     *  читалки почти одновременно. */
    private val running = AtomicBoolean(false)

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ON, false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply()
    }

    fun dirUri(c: Context): Uri? =
        prefs(c).getString(KEY_DIR, null)?.let { Uri.parse(it) }

    fun setDir(c: Context, tree: Uri) {
        prefs(c).edit().putString(KEY_DIR, tree.toString()).apply()
    }

    /** Переносим ли и книги, или только места чтения. */
    fun booksOn(c: Context): Boolean = prefs(c).getBoolean(KEY_BOOKS, false)

    /** Сколько книг на Диске нет у нас. -1 — ещё не считали. */
    fun diskNew(c: Context): Int = prefs(c).getInt(KEY_DISK_N, -1)

    private fun setDiskNew(c: Context, n: Int) {
        prefs(c).edit().putInt(KEY_DISK_N, n).apply()
    }

    fun lastAt(c: Context): Long = prefs(c).getLong(KEY_LAST_AT, 0L)

    fun lastResult(c: Context): String? = prefs(c).getString(KEY_LAST_RESULT, null)

    /** Папка, которой пользуемся: своя выбранная, иначе папка книг (если она в
     *  облаке), иначе папка резервных копий. null — выбирать придётся руками. */
    fun folderFor(c: Context): Uri? {
        dirUri(c)?.let { return it }
        prefs(c).getString(LibraryActivity.KEY_TREE, null)?.let {
            val u = Uri.parse(it)
            if (isCloud(u)) return u
        }
        return BackupStore.dirUri(c)
    }

    /** Папка в облаке или просто на телефоне. Провайдеры локальных мест
     *  (память телефона, SD-карта, «Загрузки») облаком не являются: файл в такой
     *  папке на второе устройство не поедет, и обещать обратное нельзя. */
    fun isCloud(tree: Uri): Boolean {
        val a = tree.authority ?: return false
        return a != "com.android.externalstorage.documents" &&
            a != "com.android.providers.downloads.documents" &&
            a != "com.android.providers.media.documents"
    }

    /** Фоновой прогон: не ждём результата, уходим сразу — синхронизация не должна
     *  держать ни открытие полки, ни выход из книги. */
    fun auto(c: Context) {
        if (!enabled(c)) return
        val app = c.applicationContext
        Thread { runCatching { sync(app) } }.start()
    }

    data class Result(
        /** Сколько книг обновилось у нас («забрал»). */
        val got: Int,
        /** Сколько книг с нашим местом уехало в файл («отправил»). */
        val sent: Int,
        /** Сколько цитат добавилось из файла. */
        val quotes: Int,
        /** null — получилось; иначе причина, которую показываем владельцу. */
        val error: String?,
        /** Сколько книг уехало на Диск в этом прогоне (msg6130). */
        val booksUp: Int = 0,
    )

    // ---------------- Прогон ----------------

    fun sync(c: Context): Result {
        if (!running.compareAndSet(false, true)) return Result(0, 0, 0, null)
        try {
            // Вход в Яндекс важнее выбранной папки (msg6114): папка приложения на
            // Диске не требует ни облачного приложения на телефоне, ни выбора —
            // владелец вошёл, и файл уже лежит где надо. Папка остаётся для тех,
            // у кого аккаунта нет.
            if (YandexDisk.connected(c)) return syncYandex(c)
            val tree = folderFor(c)
                ?: return finish(c, Result(0, 0, 0, c.getString(R.string.sync_err_no_dir)))
            val remote = readRemote(c, tree)?.let { parse(it) }
            val local = collect(c)
            val merged = merge(local, remote?.first ?: emptyList())
            val (got, sent) = apply(c, merged, remote?.first ?: emptyList())
            if (!writeRemote(c, tree, merged, remote?.second ?: emptyList())) {
                return finish(c, Result(0, 0, 0, c.getString(R.string.sync_err_write)))
            }
            // Цитаты — второй блок файла (первый — книги).
            return finish(c, Result(got, sent, remote?.second?.let { rq -> countNewQuotes(c, rq) } ?: 0, null))
        } catch (e: Exception) {
            return finish(c, Result(0, 0, 0, e.message ?: c.getString(R.string.sync_err_write)))
        } finally {
            running.set(false)
        }
    }

    /** Прогон через папку приложения на Яндекс.Диске (msg6114). Разбор, слияние и
     *  запись — те же самые; отличается только место, где лежит файл. */
    private fun syncYandex(c: Context): Result {
        val t = YandexDisk.read(c)
        if (t.error != null) return finish(c, Result(0, 0, 0, t.error))
        val remote = t.body?.let { parse(it) }
        val merged = merge(collect(c), remote?.first ?: emptyList())
        val (got, sent) = apply(c, merged, remote?.first ?: emptyList())
        val body = root(merged, unionQuotes(QuoteStore.all(c), remote?.second ?: emptyList()))
        YandexDisk.write(c, body.toString())?.let { return finish(c, Result(0, 0, 0, it)) }
        // Книги (msg6130): заливаем то, чего на Диске ещё нет. Забор книг сюда не
        // входит — он тянет десятки мегабайт и на чужом тарифе, и это решение
        // владельца, а не фоновая подробность: книги забираются кнопкой.
        var booksUp = 0
        if (booksOn(c)) {
            val plan = planBooks(c)
            val err = plan.error
            if (err != null) {
                Diag.log(c, "sync", "список книг на Диске не получен: $err")
            } else {
                val (n, upErr) = pushBooks(c, plan)
                booksUp = n
                if (upErr != null) Diag.log(c, "sync", "книга не залилась: $upErr")
            }
        }
        return finish(c, Result(got, sent, remote?.second?.let { rq -> countNewQuotes(c, rq) } ?: 0, null, booksUp))
    }

    // ---------------- Книги (msg6130) ----------------
    //
    // Места чтения уезжают файлом в несколько килобайт, книги — это сотни
    // мегабайт. Поэтому книги лежат отдельно, в папке `app:/books` на Диске, и
    // едут по своей воле: заливка — по галочке владельца (и то после того, как
    // он увидит объём), забор — только кнопкой. Сопоставление по имени файла,
    // как и у мест чтения: две разные книги с одинаковым именем сольются. Это
    // ограничение у нас уже объявлено вслух и остаётся тем же.

    /** Один перенос книг за раз: заливка и забор долгие, а запустить их могут
     *  и кнопка, и автосинхронизация. */
    private val moving = AtomicBoolean(false)

    /** Что поедет и что приедет. Считается ДО переноса: молча вывалить на Диск
     *  сотни мегабайт нельзя, владелец должен увидеть объём. */
    data class BookPlan(
        /** Книги, которых на Диске нет (или они там другого размера). */
        val up: List<BookRecord> = emptyList(),
        val upBytes: Long = 0L,
        /** Имена книг, которые лежат на Диске, а у нас их нет. */
        val down: List<String> = emptyList(),
        val downBytes: Long = 0L,
        val error: String? = null,
    )

    /** Считаем перенос: список Диска и размеры своих книг. Ничего не качаем. */
    fun planBooks(c: Context): BookPlan {
        val items = YandexDisk.listBooks(c)
        val listErr = items.error
        if (listErr != null) return BookPlan(error = listErr)
        val remote = items.list.associateBy { key(it.name) }
        val local = BookStore.all(c)
        val localKeys = local.map { key(it.name) }.toHashSet()
        val up = ArrayList<BookRecord>()
        var upBytes = 0L
        for (rec in local) {
            val size = sizeOf(c, rec.uri)
            val there = remote[key(rec.name)]
            // Размер сравниваем и на заливке: книга, не доехавшая в прошлый раз,
            // лежит на Диске огрызком, и её надо перезалить, а не считать готовой.
            if (there == null || (size > 0 && there.size > 0 && size != there.size)) {
                up.add(rec)
                if (size > 0) upBytes += size
            }
        }
        val down = items.list.filter { key(it.name) !in localKeys }
        var downBytes = 0L
        down.forEach { downBytes += it.size }
        setDiskNew(c, down.size)
        return BookPlan(up, upBytes, down.map { it.name }, downBytes)
    }

    /** Заливаем книги на Диск. Возвращает (сколько залито, причина отказа). */
    fun pushBooks(c: Context, plan: BookPlan): Pair<Int, String?> {
        if (plan.up.isEmpty()) return 0 to null
        if (!moving.compareAndSet(false, true)) return 0 to c.getString(R.string.sync_books_busy)
        try {
            YandexDisk.ensureBooks(c)?.let { return 0 to it }
            var done = 0
            for (rec in plan.up) {
                val src = uploadSource(c, rec)
                    ?: return done to c.getString(R.string.sync_books_no_read, rec.name)
                val err = YandexDisk.uploadBook(c, rec.name, src.file)
                src.temp?.delete()
                if (err != null) return done to c.getString(R.string.sync_books_up_fail, rec.name, err)
                done++
                Diag.log(c, "sync", "книга залита на Диск: ${rec.name}")
            }
            return done to null
        } finally {
            moving.set(false)
        }
    }

    /** Забираем с Диска книги, которых у нас нет. Кладём во внутреннюю папку
     *  приложения — туда же, куда качает каталог, чтобы книга сразу нашлась на
     *  полке. Места чтения и закладки подтянутся следующей синхронизацией: ключ
     *  тот же (имя файла), а записи в файле синхронизации уже лежат. */
    fun pullBooks(c: Context, plan: BookPlan): Pair<Int, String?> {
        if (plan.down.isEmpty()) return 0 to null
        if (!moving.compareAndSet(false, true)) return 0 to c.getString(R.string.sync_books_busy)
        try {
            val dir = File(c.filesDir, "books").apply { mkdirs() }
            var done = 0
            for (name in plan.down) {
                val dest = File(dir, name)
                // Файл уже лежит (забрали раньше, а запись потерялась) — заводим
                // запись и идём дальше: те же мегабайты второй раз не тянем.
                if (!dest.isFile || dest.length() == 0L) {
                    val err = YandexDisk.downloadBook(c, name, dest)
                    if (err != null) return done to c.getString(R.string.sync_books_down_fail, name, err)
                }
                BookStore.upsert(c, BookRecord(
                    uri = Uri.fromFile(dest).toString(),
                    name = dest.name,
                    addedAt = System.currentTimeMillis(),
                ))
                done++
                Diag.log(c, "sync", "книга забрана с Диска: $name")
            }
            return done to null
        } finally {
            moving.set(false)
        }
    }

    /** Откуда брать файл книги для заливки. Своя книга (file://) уходит прямо со
     *  своего места; чужая (content:// из выбранной папки) — копией в кэше: тело
     *  запроса Диска читается из файла, а из чужого потока — нельзя. */
    private class Src(val file: File, val temp: File?)

    private fun uploadSource(c: Context, rec: BookRecord): Src? {
        val uri = Uri.parse(rec.uri)
        if (rec.uri.startsWith("file:")) {
            val own = uri.path?.let { File(it) }
            if (own != null && own.isFile) return Src(own, null)
        }
        val tmp = File(c.cacheDir, "sync_up.tmp")
        val ok = runCatching {
            c.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!ok || !tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            return null
        }
        return Src(tmp, tmp)
    }

    /** Размер книги на телефоне: у своей — длина файла, у чужой — столбец SIZE.
     *  Не узнали — 0: тогда размер не сравниваем, решает имя. */
    private fun sizeOf(c: Context, uri: String): Long = runCatching {
        if (uri.startsWith("file:")) {
            File(Uri.parse(uri).path ?: "").length()
        } else {
            c.contentResolver.query(
                Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null,
            )?.use { cur -> if (cur.moveToFirst()) cur.getLong(0) else 0L } ?: 0L
        }
    }.getOrDefault(0L)

    // ---------------- Ручной путь: файл уносим сами (msg6086) ----------------
    //
    // Для случая, когда облака на телефоне нет вовсе: файл синхронизации
    // отправляется вручную (Telegram «Избранное» или любым другим способом), а на
    // втором устройстве забирается кнопкой. Те же разбор и слияние, что и в
    // обычном прогоне, — отличается только то, откуда взялись байты.

    /** Собирает файл синхронизации во временный файл и отдаёт его наружу.
     *  null — не вышло записать. */
    fun exportFile(c: Context): File? = runCatching {
        val (carryBooks, carryQuotes) = carry(c)
        val books = merge(collect(c), carryBooks)
        val quotes = unionQuotes(QuoteStore.all(c), carryQuotes)
        setCarry(c, books, quotes)
        File(c.cacheDir, FILE_NAME).apply {
            writeText(root(books, quotes).toString(), Charsets.UTF_8)
        }
    }.getOrNull()

    /** Файл, принесённый вручную. */
    fun importBytes(c: Context, bytes: ByteArray): Result {
        if (!running.compareAndSet(false, true)) return Result(0, 0, 0, null)
        try {
            val remote = parse(String(bytes, Charsets.UTF_8))
                ?: return finish(c, Result(0, 0, 0, c.getString(R.string.sync_bad_file)))
            val merged = merge(collect(c), remote.first)
            val (got, sent) = apply(c, merged, remote.first)
            setCarry(c, merged, unionQuotes(QuoteStore.all(c), remote.second))
            return finish(c, Result(got, sent, countNewQuotes(c, remote.second), null))
        } catch (e: Exception) {
            return finish(c, Result(0, 0, 0, e.message ?: c.getString(R.string.sync_bad_file)))
        } finally {
            running.set(false)
        }
    }

    /** Чужие записи, которые мы помним с прошлого раза. */
    private fun carry(c: Context): Pair<List<SBook>, List<Quote>> {
        val raw = prefs(c).getString(KEY_CARRY, null)
            ?: return emptyList<SBook>() to emptyList()
        return parse(raw) ?: (emptyList<SBook>() to emptyList())
    }

    private fun setCarry(c: Context, books: List<SBook>, quotes: List<Quote>) {
        prefs(c).edit().putString(KEY_CARRY, root(books, quotes).toString()).apply()
    }

    private fun finish(c: Context, r: Result): Result {
        var text = when {
            r.error != null -> r.error
            r.got == 0 && r.sent == 0 && r.booksUp == 0 -> c.getString(R.string.sync_same)
            else -> c.getString(R.string.sync_counts, r.sent, r.got)
        }
        // Книги — отдельной строкой: «отправил 3, забрал 1» про места чтения, и
        // молчать о залитых книгах нельзя (владелец ждёт их появления на Диске).
        if (r.error == null && r.booksUp > 0) {
            text += " " + c.getString(R.string.sync_books_pushed, r.booksUp)
        }
        prefs(c).edit()
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, text)
            .apply()
        Diag.log(c, "sync", "синхронизация: $text")
        return r
    }

    // ---------------- Своё состояние ----------------

    /** Запись книги в файле синхронизации. От [BookRecord] берём только то, что
     *  едет между устройствами: место, статус, избранное, закладки. */
    private data class SBook(
        val key: String,
        val title: String? = null,
        val author: String? = null,
        val status: Int = BookRecord.STATUS_NEW,
        val chapter: Int = 0,
        val sentence: Int = 0,
        val readPct: Int = 0,
        val favorite: Boolean = false,
        /** Свежесть: когда книгу в последний раз читали на этом устройстве. */
        val at: Long = 0L,
        val bm: List<Bm> = emptyList(),
    )

    private data class Bm(val ch: Int, val s: Int, val t: String)

    private fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun collect(c: Context): List<SBook> = BookStore.all(c).map { rec ->
        SBook(
            key = key(rec.name),
            title = rec.title,
            author = rec.author,
            status = rec.status,
            chapter = rec.chapter,
            sentence = rec.sentence,
            readPct = rec.readPct,
            favorite = rec.favorite,
            at = rec.lastOpenedAt,
            bm = readBm(c, rec.uri),
        )
    }

    private fun readBm(c: Context, uri: String): List<Bm> {
        val raw = prefs(c).getString("bookmarks_$uri", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Bm(o.optInt("ch"), o.optInt("s"), o.optString("t"))
            }
        }.getOrDefault(emptyList())
    }

    /** Закладки — объединение по месту (глава + предложение): имя закладки у
     *  каждой стороны своё, терять ничьё не даём. */
    private fun unionBm(a: List<Bm>, b: List<Bm>): List<Bm> {
        val out = LinkedHashMap<Long, Bm>()
        (a + b).forEach { out.putIfAbsent(it.ch.toLong() * 1_000_000L + it.s, it) }
        return out.values.sortedWith(compareBy({ it.ch }, { it.s }))
    }

    /** Какая из двух записей свежее. Книга, которую читали (at > 0), всегда
     *  старше книги, которую только добавили в полку (at == 0): иначе скан папки
     *  на втором устройстве затирал бы настоящее место нулём. При равенстве
     *  оставляем своё — чужое всё равно уедет в файл без изменений. */
    private fun better(a: SBook, b: SBook): SBook {
        if (a.at > 0L && b.at == 0L) return a
        if (b.at > 0L && a.at == 0L) return b
        if (a.at != b.at) return if (a.at > b.at) a else b
        return a
    }

    private fun merge(local: List<SBook>, remote: List<SBook>): List<SBook> {
        val out = LinkedHashMap<String, SBook>()
        local.forEach { out[it.key] = it }
        remote.forEach { r ->
            val l = out[r.key]
            out[r.key] = if (l == null) r
            else better(l, r).copy(bm = unionBm(l.bm, r.bm))
        }
        return out.values.toList()
    }

    /** Применяем чужое поверх своего и считаем, что поехало в обе стороны.
     *  Книгу, которой у нас нет, не заводим: запись без файла на полке бесполезна
     *  (и мы таких уже лечили, msg3202). Место такой книги остаётся в файле и
     *  доедет, когда книга появится. */
    private fun apply(c: Context, merged: List<SBook>, remote: List<SBook>): Pair<Int, Int> {
        val remoteByKey = remote.associateBy { it.key }
        val byName = BookStore.all(c).associateBy { key(it.name) }
        var got = 0
        var sent = 0
        for (m in merged) {
            val rec = byName[m.key] ?: continue
            val r = remoteByKey[m.key]
            val ls = collectOne(c, rec)
            if (r == null) {
                sent++
                continue
            }
            val win = better(ls, r)
            if (win === r) {
                if (!samePlace(ls, r)) {
                    BookStore.upsert(c, rec.copy(
                        status = r.status, chapter = r.chapter,
                        sentence = r.sentence, readPct = r.readPct, favorite = r.favorite,
                    ))
                    got++
                }
            } else if (!samePlace(ls, r)) {
                sent++
            }
            // Закладки — всегда объединением: у каждой стороны свои.
            val union = unionBm(ls.bm, r.bm)
            if (union.size != ls.bm.size) writeBm(c, rec.uri, union)
        }
        return got to sent
    }

    private fun collectOne(c: Context, rec: BookRecord) = SBook(
        key = key(rec.name),
        title = rec.title,
        author = rec.author,
        status = rec.status,
        chapter = rec.chapter,
        sentence = rec.sentence,
        readPct = rec.readPct,
        favorite = rec.favorite,
        at = rec.lastOpenedAt,
        bm = readBm(c, rec.uri),
    )

    private fun samePlace(a: SBook, b: SBook): Boolean =
        a.chapter == b.chapter && a.sentence == b.sentence &&
            a.status == b.status && a.readPct == b.readPct && a.favorite == b.favorite

    private fun writeBm(c: Context, uri: String, list: List<Bm>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ch", it.ch).put("s", it.s).put("t", it.t)) }
        prefs(c).edit().putString("bookmarks_$uri", arr.toString()).apply()
    }

    private fun countNewQuotes(c: Context, remote: List<Quote>): Int {
        val have = QuoteStore.all(c).map { it.id }.toHashSet()
        return remote.count { it.id !in have }
    }

    // ---------------- Файл ----------------

    private fun parse(text: String): Pair<List<SBook>, List<Quote>>? = runCatching {
        val o = JSONObject(text)
        if (o.optString("kind") != KIND) return@runCatching null
        parseBooks(o.optJSONArray("books")) to parseQuotes(o.optJSONArray("quotes"))
    }.getOrNull()

    private fun parseBooks(arr: JSONArray?): List<SBook> {
        if (arr == null) return emptyList()
        val books = ArrayList<SBook>()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val bm = ArrayList<Bm>()
            b.optJSONArray("bm")?.let { ba ->
                for (j in 0 until ba.length()) {
                    val bo = ba.getJSONObject(j)
                    bm.add(Bm(bo.optInt("ch"), bo.optInt("s"), bo.optString("t")))
                }
            }
            val k = b.optString("key")
            if (k.isBlank()) continue
            books.add(SBook(
                key = k,
                title = b.optString("title").ifBlank { null },
                author = b.optString("author").ifBlank { null },
                status = b.optInt("status"),
                chapter = b.optInt("chapter"),
                sentence = b.optInt("sentence"),
                readPct = b.optInt("readPct"),
                favorite = b.optBoolean("favorite"),
                at = b.optLong("at"),
                bm = bm,
            ))
        }
        return books
    }

    private fun parseQuotes(arr: JSONArray?): List<Quote> {
        if (arr == null) return emptyList()
        val quotes = ArrayList<Quote>()
        for (i in 0 until arr.length()) {
            runCatching { quotes.add(Quote.fromJson(arr.getJSONObject(i))) }
        }
        return quotes
    }

    /** Цитаты — объединение по id: чужую не теряем, свою не отдаём дважды. */
    private fun unionQuotes(local: List<Quote>, remote: List<Quote>): List<Quote> {
        val byId = LinkedHashMap<String, Quote>()
        local.forEach { byId[it.id] = it }
        remote.forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.toList()
    }

    /** Пишем объединение: свои книги + чужие (в том числе те, которых у нас нет) +
     *  цитаты, объединённые по id. */
    private fun writeRemote(c: Context, tree: Uri, books: List<SBook>, quotes: List<Quote>): Boolean {
        val merged = root(books, unionQuotes(QuoteStore.all(c), quotes))
        return writeFile(c, tree, merged.toString().toByteArray(Charsets.UTF_8))
    }

    /** Содержимое файла синхронизации. Одно и то же и для облачной папки, и для
     *  файла, который уносят руками (msg6086). */
    private fun root(books: List<SBook>, quotes: List<Quote>): JSONObject = JSONObject().apply {
        put("app", "BookVoice")
        put("kind", KIND)
        put("version", VERSION)
        put("device", deviceName())
        put("updatedAt", System.currentTimeMillis())
        put("books", JSONArray().apply {
            books.forEach { b ->
                put(JSONObject().apply {
                    put("key", b.key)
                    b.title?.let { put("title", it) }
                    b.author?.let { put("author", it) }
                    put("status", b.status)
                    put("chapter", b.chapter)
                    put("sentence", b.sentence)
                    put("readPct", b.readPct)
                    put("favorite", b.favorite)
                    put("at", b.at)
                    put("bm", JSONArray().apply {
                        b.bm.forEach { m ->
                            put(JSONObject().put("ch", m.ch).put("s", m.s).put("t", m.t))
                        }
                    })
                })
            }
        })
        put("quotes", JSONArray().apply { quotes.forEach { put(it.toJson()) } })
    }

    private fun deviceName(): String =
        runCatching { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }.getOrDefault("Android")

    private fun readRemote(c: Context, tree: Uri): String? = runCatching {
        val doc = findFile(c, tree) ?: return@runCatching null
        c.contentResolver.openInputStream(doc)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun writeFile(c: Context, tree: Uri, bytes: ByteArray): Boolean = runCatching {
        val existing = findFile(c, tree)
        val doc = existing ?: DocumentsContract.createDocument(
            c.contentResolver, tree, "application/json", FILE_NAME,
        )
        doc ?: return@runCatching false
        // "wt" — запись с обнулением. Не всякий провайдер его понимает, поэтому
        // при отказе пробуем обычный "w"; без обнуления в файле остались бы
        // хвосты прежнего, более длинного содержимого.
        val ok = runCatching {
            c.contentResolver.openOutputStream(doc, "wt")?.use { it.write(bytes) }
        }.isSuccess
        if (!ok) {
            c.contentResolver.openOutputStream(doc, "w")?.use { it.write(bytes) }
        }
        true
    }.getOrDefault(false)

    private fun findFile(c: Context, tree: Uri): Uri? = runCatching {
        val treeDoc = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc)
        c.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getString(1) == FILE_NAME) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                }
            }
            null
        }
    }.getOrNull()
}
