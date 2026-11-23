package com.cuber.bookvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Скачивание книги из сетевого каталога (0.4.83, msg7004).
 *
 * Зачем отдельная служба. Раньше загрузка жила потоком внутри окна каталога:
 * закрыл окно — и загрузку не видно и не остановить, а о ходе сообщалось
 * голосом прямо в окне, поверх чтения («Скачано 25 процентов»), потому что
 * другого канала не было. Теперь загрузка идёт в службе с переднего плана:
 * у неё своё тихое уведомление с полосой и кнопкой «Отменить», а в озвучке —
 * только начало и конец. Ход загрузки вслух не читается ни разу.
 *
 * Почему не «User-Initiated Data Transfer Job» из списка требований. Задание
 * системы качает файл вне нашего процесса и отдаёт готовое через дескриптор —
 * это переписывание всей записи книги (SAF-папка, «Доступ ко всем файлам»,
 * честное расширение архива) на другой механизм. Пользователю разница не видна:
 * загрузка не умирает с окном, уведомление есть, отмена есть. Оставляем службу
 * и говорим об этом прямо.
 *
 * Кто рисует прогресс в окне: [Watcher] — окно каталога, пока оно открыто.
 * Ссылку держим только пока идёт загрузка и окно живо; закрылось — работаем без
 * него, уведомление остаётся.
 */
class DownloadService : Service() {

    /** Тот же accept заголовка, что у окна каталога. */
    private val acceptFeed = "application/atom+xml, application/xml;q=0.9, */*;q=0.8"

    /** Окно каталога, пока оно открыто. Все зовы — в главном потоке. */
    interface Watcher {
        fun onProgress(pct: Int) {}
        fun onFinished(title: String, uri: String, fallback: Boolean) {}
        fun onFailed(e: Throwable?) {}
        fun onCancelled() {}
        fun onNeedLogin(url: String) {}
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 41

        /** Карточка «скачано» — ОТДЕЛЬНЫМ номером (26.09.2026). Карточка службы
         *  переднего плана на части прошивок не смахивается и висит даже после
         *  её конца (жалоба Сергея): снять её было нечем. У обычной карточки есть
         *  и кнопка «Убрать», и смахивание. */
        private const val NOTIF_DONE_ID = 42

        private const val ACTION_CANCEL = "com.cuber.bookvoice.action.DOWNLOAD_CANCEL"
        private const val ACTION_DISMISS = "com.cuber.bookvoice.action.DOWNLOAD_DISMISS"

        private const val EXTRA_TITLE = "book_title"
        private const val EXTRA_AUTHOR = "book_author"
        private const val EXTRA_URL = "book_url"
        private const val EXTRA_LABEL = "fmt_label"
        private const val EXTRA_EXT = "fmt_ext"
        private const val EXTRA_FMT_URL = "fmt_url"
        // ПАКЕТНОЕ скачивание (20.11.2026, просьба Сержа): «скачать всю серию /
        // всего автора» долгим на строке «Все книги…». Служба сама собирает
        // список по страницам ленты и качает подряд; отмена — кнопкой в шторке
        // или тем же долгим на той же строке.
        private const val EXTRA_BATCH = "batch_feed"
        private const val EXTRA_BATCH_NAME = "batch_name"
        /** Каким именем ложить в папки: "author" — одна папка автора,
         *  "series" — «Автор/Серия» (20.11.2026, слова Сержа: «переделай
         *  автор → серия»). */
        private const val EXTRA_BATCH_KIND = "batch_kind"
        /** Выше этого списка пакет не качаем: серия или автор на тысячи книг —
         *  это почти наверняка сбой навигации (залезли в корень каталога), а не
         *  намерение человека. */
        private const val BATCH_HARD_LIMIT = 5000

        /** Идёт ли загрузка прямо сейчас (окно гасит по ней кнопки). */
        @Volatile
        private var runningState = false

        /** Проценты: -1 — сервер размер не прислал, показываем «Скачивание…». */
        @Volatile
        private var progressState = -1

        /** Url книги, которая качается сейчас: по нему окно понимает, чью кнопку
         *  подписывать прогрессом. */
        @Volatile
        private var bookUrlState: String? = null

        /** Адрес ленты, из которой сейчас качают пачку (null — пачки нет): по нему
         *  долгое на той же строке понимает, что надо ОТМЕНЯТЬ, а не начинать. */
        @Volatile
        private var batchUrlState: String? = null

        /** Очередь пачек (23.11.2026, просьба Сержа: «несколько серий подряд
         *  набавлял, и оно добавлялось в очередь все»): долгое во время чужой
         *  пачки НЕ отменяет и НЕ игнорит — добавляет в конец. Отмена точная:
         *  долгое по строке качающейся пачки снимает ЕЁ, по строке ожидающей —
         *  убирает её из очереди; «Отменить» в шторке — только текущую. */
        private data class BatchJob(val feedUrl: String, val name: String, val kind: String)
        private val batchQueue = ArrayList<BatchJob>()
        /** Занята ли служба прямо сейчас (пачкой или одиночной книгой): пока
         *  занята, пачки копятся в batchQueue; по окончании очередь разбирается
         *  по одной. Одиночные книги НЕ очередь: они мгновенные, а очереди
         *  просили для пачек. */
        @Volatile
        private var busyState = false

        /** Сколько пачек ждут очереди (не считая качающейся). */
        val queuedBatches: Int get() = synchronized(batchQueue) { batchQueue.size }
        /** Пачка с этой ленты уже ЖДЁТ в очереди (не считая качающейся). */
        fun isQueued(feedUrl: String): Boolean =
            synchronized(batchQueue) { batchQueue.any { it.feedUrl == feedUrl } }
        /** Убрать пачку из очереди: true — была, убрана; false — её там нет. */
        fun dropQueued(feedUrl: String): Boolean = synchronized(batchQueue) {
            val i = batchQueue.indexOfFirst { it.feedUrl == feedUrl }
            if (i < 0) false else { batchQueue.removeAt(i); true }
        }

        /** «3 из 120» — для журнала и карточки; окно по ним ничего не рисует
         *  (просьба Сержа не шуметь числами вслух), это для разбора жалоб. */
        @Volatile
        private var batchDoneState = 0
        @Volatile
        private var batchTotalState = 0
        @Volatile
        private var batchLabelState: String? = null

        val running: Boolean get() = runningState
        val progress: Int get() = progressState
        val bookUrl: String? get() = bookUrlState
        val batchUrl: String? get() = batchUrlState
        val batchDone: Int get() = batchDoneState
        val batchTotal: Int get() = batchTotalState
        val batchLabel: String? get() = batchLabelState

        /** Кому рассказывать о ходе. Ставит окно каталога, снимает при закрытии. */
        @Volatile
        var watcher: Watcher? = null

        fun start(ctx: Context, book: OpdsItem.Book, fmt: OpdsFormat) {
            val i = Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_TITLE, book.title)
                .putExtra(EXTRA_AUTHOR, book.author)
                .putExtra(EXTRA_URL, book.url)
                .putExtra(EXTRA_LABEL, fmt.label)
                .putExtra(EXTRA_EXT, fmt.ext)
                .putExtra(EXTRA_FMT_URL, fmt.url)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Начать пачку: служба сама соберёт ленты по страницам и будет качать
         *  в подпапку (имя из ленты точнее — страница серии называется своим
         *  заголовком, его и берём, когда он есть). [kind] — "author" или
         *  "series": от него зависит форма папки («Автор» или «Автор/Серия»).
         *  23.11.2026: если служба занята — пачка встанет в очередь и дойдёт
         *  сама; окно по возвращаемому числу («перед ней столько же») говорит
         *  это вслух. */
        fun startBatch(ctx: Context, feedUrl: String, fallbackName: String, kind: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, DownloadService::class.java)
                    .putExtra(EXTRA_BATCH, feedUrl)
                    .putExtra(EXTRA_BATCH_NAME, fallbackName)
                    .putExtra(EXTRA_BATCH_KIND, kind),
            )
        }

        /** Отменить то, что идёт (одиночную книгу или пачку): то же, что кнопка
         *  «Отменить» в шторке. */
        fun cancelNow(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }
    }

    @Volatile
    private var cancelled = false

    private var currentTitle: String? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    /** Что качаем одну книгу: всё, что нужно для скачивания и записи. [sub] —
     *  папка внутри папки скачивания («Автор» или «Автор/Серия»), null — корень
     *  (20.11.2026, слова Сержа: «переделай: автор → серия»). */
    private data class Spec(
        val title: String,
        val author: String?,
        val pageUrl: String,
        val fmt: OpdsFormat,
        val sub: String? = null,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }
        // «Убрать» на карточке итога: снимаем её и больше ничего не делаем.
        if (intent?.action == ACTION_DISMISS) {
            nm.cancel(NOTIF_DONE_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        // Пакет: «скачать весь список» (20.11.2026). 23.11.2026 (Серж: «несколько
        // серий подряд — и все в очередь») — становится в конец очереди, если
        // служба занята, и берётся самой, когда дойдёт черёд.
        val batchFeed = intent?.getStringExtra(EXTRA_BATCH)
        if (batchFeed != null) {
            enqueueBatch(
                batchFeed,
                intent.getStringExtra(EXTRA_BATCH_NAME).orEmpty(),
                intent.getStringExtra(EXTRA_BATCH_KIND) ?: "author",
            )
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val fmtUrl = intent?.getStringExtra(EXTRA_FMT_URL)
        if (title.isNullOrBlank() || fmtUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (busyState) {
            // Одиночная книга во время чужой работы НЕ отменяет её и НЕ влезает
            // в очередь мгновенных: окно и так скажет «подожди» (downloadFormat),
            // а контракт переднего плана гасим повторной карточкой того, что идёт.
            startForegroundCompat(
                buildRunning(currentTitle ?: title, progressState),
                batch = batchUrlState != null,
            )
            toast(getString(R.string.catalog_dl_busy))
            return START_NOT_STICKY
        }
        val author = intent.getStringExtra(EXTRA_AUTHOR)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        cancelled = false
        runningState = true
        busyState = true
        progressState = -1
        bookUrlState = url
        currentTitle = title
        // Уведомление поднимаем сразу: у службы переднего плана на это есть
        // несколько секунд, а первое объявление о старте читает владельцу тост.
        // Заодно убираем карточку итога прошлой загрузки: она своё уже сказала,
        // а копиться им незачем.
        nm.cancel(NOTIF_DONE_ID)
        startForegroundCompat(buildRunning(title, -1))
        toast(getString(R.string.catalog_dl_start, title))
        Diag.log(this, "opds", "скачиваю \"$title\" формат $label: $fmtUrl")
        Thread { download(title, author, url, label, ext, fmtUrl) }.start()
        return START_NOT_STICKY
    }

    /** Поставить пачку в очередь и, если служба свободна, взять её же первой
     *  (23.11.2026, просьба Сержа). Та же лента дважды не встанет: повтор после
     *  окна входа ([CatalogActivity.retryLastDownload]) обязан быть холостым,
     *  иначе очередь набивалась бы близнецами. */
    private fun enqueueBatch(feedUrl: String, name: String, kind: String) {
        var begin: BatchJob? = null
        val queued = synchronized(batchQueue) {
            val dup = feedUrl == batchUrlState || batchQueue.any { it.feedUrl == feedUrl }
            when {
                dup -> false
                busyState -> { batchQueue.add(BatchJob(feedUrl, name, kind)); true }
                else -> {
                    busyState = true
                    runningState = true
                    begin = BatchJob(feedUrl, name, kind)
                    false
                }
            }
        }
        if (begin != null) beginBatch(begin!!) else {
            // Каждый startForegroundService обязан увидеть startForeground — даже
            // когда пачка просто встала в очередь (или это повтор). Служба уже
            // впереди, поэтому зовём её ТЕКУЩЕЙ карточкой: молча, та же.
            startForegroundCompat(
                buildRunning(
                    currentTitle ?: batchLabelState
                        ?: getString(R.string.catalog_batch_title),
                    progressState.coerceAtLeast(-1),
                ),
                batch = true,
            )
        }
    }

    /** Начать взятую из очереди пачку: карточка, журнал, рабочий поток. */
    private fun beginBatch(job: BatchJob) {
        cancelled = false
        runningState = true
        progressState = -1
        bookUrlState = null
        batchUrlState = job.feedUrl
        batchDoneState = 0
        batchTotalState = 0
        currentTitle = null
        nm.cancel(NOTIF_DONE_ID)
        startForegroundCompat(
            buildRunning(job.name.ifBlank { getString(R.string.catalog_batch_title) }, -1),
            batch = true,
        )
        val waitN = synchronized(batchQueue) { batchQueue.size }
        Diag.log(this, "opds", "пачка: собираю список ${job.feedUrl}" +
            (if (waitN > 0) " (в очереди ждут $waitN)" else ""))
        Thread { batch(job.feedUrl, job.name, job.kind) }.start()
    }

    /** Чья очередь: взяла следующую пачку — служба продолжает; очереди нет —
     *  busy снят, вызывающий заканчивает службу как раньше. */
    private fun nextBatch(): BatchJob? = synchronized(batchQueue) {
        if (batchQueue.isEmpty()) { busyState = false; null }
        else batchQueue.removeAt(0)
    }

    /** Общий конец ЛЮБОГО дела (пачки и одиночной книги): если за спиной ждут
     *  пачки — зовём следующую, иначе служба заканчивается. busyState при этом
     *  снимает nextBatch(), а вот runningState гасим здесь: finishWith для
     *  одиночной книги не зовётся, и без этого окно навсегда считало бы,
     *  что «загрузка идёт». */
    private fun jobEnded(): BatchJob? {
        val next = nextBatch()
        if (next != null) {
            // состояние закончившейся пачки не должно путать окно, пока
            // beginBatch ещё не выставил своё
            batchUrlState = null
            bookUrlState = null
            progressState = -1
            beginBatch(next)
        } else {
            runningState = false
        }
        return next
    }

    private fun download(
        title: String,
        author: String?,
        bookUrl: String,
        label: String,
        ext: String,
        fmtUrl: String,
    ) {
        val res = runCatching {
            // Одиночная книга (20.11.2026, слова Сержа «переделай: автор → серия»)
            // тоже ложится в папку автора, когда автор известен.
            runOne(Spec(title, author, bookUrl, OpdsFormat(label, ext, fmtUrl), authorSub(author)))
        }
        val placed = res.getOrNull()
        val err = res.exceptionOrNull()

        main.post {
            // Одиночная книга кончилась. Снимать передний план и убивать службу
            // будем только если очередь пачек пуста — иначе следующая возьмёт
            // карточку себе (23.11.2026).
            when {
                placed != null -> {
                    // Запись на полке делает runOne (общая дверь для одиночной
                    // книги и пачки) — здесь только итог владельцу.
                    Diag.log(this, "opds", "книга скачана: \"$title\" → ${placed.uri}")
                    // 0.3.47: папка оказалась нерабочей — предупреждаем, что файл
                    // ушёл во внутреннюю память, а не в выбранную папку.
                    toast(
                        if (placed.fallback) getString(R.string.dl_folder_fallback)
                        else getString(R.string.catalog_dl_done, title)
                    )
                    Vibra.confirm(this)
                    watcher?.onFinished(title, placed.uri, placed.fallback)
                    if (jobEnded() == null) {
                        // Карточку итога показываем ОТДЕЛЬНЫМ номером, а карточку
                        // службы убираем совсем: снять карточку переднего плана
                        // бывает нечем, а у обычной есть кнопка «Убрать», смахивание
                        // и касание на полку.
                        stopForegroundCompat(keepNotification = false)
                        nm.notify(NOTIF_DONE_ID, buildDone(title))
                    }
                }
                err is DownloadCancelled -> {
                    Diag.log(this, "opds", "скачивание отменено: \"$title\"")
                    toast(getString(R.string.dl_cancelled))
                    watcher?.onCancelled()
                    if (jobEnded() == null) stopForegroundCompat(keepNotification = false)
                }
                err is OpdsNeedLogin -> {
                    // Книга отдаётся только после входа (#20): спрашиваем логин —
                    // это умеет окно каталога. Окна нет — говорим, что делать.
                    Diag.log(this, "opds", "книга просит вход: \"$title\"")
                    val w = watcher
                    if (w != null) w.onNeedLogin(err.url) else toast(getString(R.string.dl_need_login))
                    if (jobEnded() == null) stopForegroundCompat(keepNotification = false)
                }
                else -> {
                    // 0.4.83 (msg7001): вслух — «что случилось и что делать», без
                    // кода ответа и без английского текста исключения.
                    Diag.log(this, "opds", "книга не скачалась: \"$title\" — ${err?.message}")
                    toast(netText(this, err, book = true))
                    Vibra.error(this)
                    watcher?.onFailed(err)
                    if (jobEnded() == null) stopForegroundCompat(keepNotification = false)
                }
            }
            // 23.11.2026: stopSelf — только когда очередь правда пуста. Пока за
            // спиной ждут пачки, служба нужная: jobEnded уже позвал следующую.
            // busyState при этом снимает nextBatch(), а вот runningState гасим
            // здесь — иначе окно навсегда считает, что «загрузка идёт».
            // Состояние НЕ чистим, если следующий уже взят: beginBatch выставил
            // своё, а эти строки стёрли бы его.
            if (!busyState) {
                progressState = -1
                bookUrlState = null
                currentTitle = null
                runningState = false
                stopSelf()
            }
        }
    }

    /** Скачать ОДНУ книгу и завести её на полку: кусковая выборка с прогрессом
     *  (msg1264, дедуп по проценту), честная распаковка архива (0.4.69), запись
     *  в папку скачивания ([Spec.sub] — «Автор» или «Автор/Серия»),
     *  запись в реестр с прямой ссылкой (sourceUrl — по ней окно и пачка узнают
     *  «уже скачано», #100 и msg1333). Исключения летят вызывающему: одиночная
     *  книга объявляет их словами, пачка пишет в журнал и идёт дальше. */
    private fun runOne(spec: Spec): DownloadStore.Placed {
        var lastPct = -2
        val fetch = OpdsNet.fetchProgress(
            this, spec.fmt.url, "*/*",
            onProgress = { read, total ->
                val pct = if (total > 0) {
                    (read * 100 / total).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                if (pct != lastPct) {
                    lastPct = pct
                    publishProgress(pct)
                }
            },
            isCancelled = { cancelled },
        )
        val placed = DownloadStore.write(this, spec.title, spec.fmt, fetch.bytes, spec.sub)
        BookStore.upsert(this, BookRecord(
            uri = placed.uri,
            name = placed.name,
            title = spec.title,
            author = spec.author,
            sourceUrl = spec.fmt.url,
            addedAt = System.currentTimeMillis(),
        ))
        return placed
    }

    /** Пакет, первый шаг — собрать список по всем страницам ленты. Заголовок
     *  самой ленты становится именем подпапки: страница серии называется своим
     *  заголовком — это точнее, чем подпись ссылки («Все книги серии»).
     *  Уже скачанное пропускаем по прямой ссылке (тот же критерий, что у кнопки
     *  окна). Обрыв ленты посередине — не беда: качаем собранное, причину пишем
     *  в журнал. */
    private fun batch(feedUrl: String, fallbackName: String, kind: String) {
        val specs = ArrayList<Spec>()
        var name = fallbackName
        var listFail: Throwable? = null
        var tooBig = false
        var pages = 0
        var noFile = 0
        try {
            val sources = HashSet<String>()
            for (r in BookStore.all(this)) r.sourceUrl?.let { sources.add(it) }
            val seen = HashSet<String>()
            val dlFmt = getSharedPreferences("reader", MODE_PRIVATE)
                .getString(OpdsPrefs.KEY_DL_FMT, OpdsPrefs.DEFAULT_DL_FMT)
                ?: OpdsPrefs.DEFAULT_DL_FMT
            var url: String? = feedUrl
            var hubTried = false
            var hubBest: String? = null
            var hubRank = 0
            while (url != null && pages < 200) {
                if (cancelled) break
                // Страницу просим ДВАЖДЫ (22.11.2026, разговор с Сержем про
                // «обход прервался: timeout»): медленное зеркало не всегда
                // укладывается в 45 секунд с первого захода, а сдавать весь
                // хвост списка из-за одной страницы — терять книги.
                var f: OpdsNet.Fetch? = null
                var pageErr: Throwable? = null
                for (attempt in 1..2) {
                    if (cancelled) break
                    try {
                        f = OpdsNet.get(this, url, acceptFeed)
                        pageErr = null
                        break
                    } catch (e: Throwable) {
                        pageErr = e
                        if (e is OpdsNeedLogin) break
                        Diag.log(this, "opds", "пачка: страница не ответила, попытка $attempt: ${e.message}")
                        if (attempt < 2) runCatching { Thread.sleep(3000) }
                    }
                }
                if (cancelled) break
                if (f == null) { listFail = pageErr; break }
                val feed = OpdsParser.parse(f.finalUrl, f.bytes)
                if (pages == 0 && feed.title.isNotBlank()) {
                    // Заголовок ленты — но флибуста подписывает страницы служебно:
                    // «Книги автора Имя», «Произведения серии «Название»». Имя
                    // папки должно быть чистым (найдено на эмуляторе 22.11.2026:
                    // папка «Книги автора Летта Ника» вместо «Летта Ника»).
                    // 22.11.2026 (урок пачки «Все новинки»): лента может
                    // называться ВРЕМЕНЕМ («Новинки с 15.11 по 22.11») — папка
                    // с датой на следующий день стала бы другой. Дату из
                    // имени вырезаем, остаётся устойчивое ядро: «Новинки».
                    val cleaned = cleanFeedTitle(feed.title)
                    name = if (looksLikeDateTitle(cleaned))
                        stripDateTitle(cleaned).ifBlank { name }
                    else cleaned
                }
                for (item in feed.items) {
                    if (item is OpdsItem.Folder) {
                        // Страница автора у флибусты — узел навигации: настоящие
                        // книги лежат в подленте «Книги по алфавиту» (найдено на
                        // эмуляторе 22.11.2026: пачка автора качала только
                        // «Об авторе»). Примечаем лучшую подленту — если книг на
                        // своих страницах не найдём, соберём из неё.
                        if (pages == 0) {
                            val rank = hubCandidate(item.title)
                            if (rank > 0 && (hubRank == 0 || rank < hubRank)) {
                                hubRank = rank
                                hubBest = item.url
                            }
                        }
                        continue
                    }
                    if (item !is OpdsItem.Book) continue
                    val fmt = item.downloads.firstOrNull { it.ext == ".$dlFmt" }
                        ?: item.downloads.firstOrNull()
                    if (fmt == null || fmt.url.isBlank()) {
                        // Каталог НЕ отдал ссылку на файл (найдено на Coollib
                        // 22.11.2026: у самиздата и компиляций стоит
                        // «Заблокировано! Скачивание невозможно» и в ленте нет
                        // acquisition). Такие книги не потеря — их нельзя скачать
                        // здесь в принципе, но молча их съедать нельзя:
                        // человек не должен гадать, почему «7 из 21».
                        noFile++
                        continue
                    }
                    if (!seen.add(fmt.url)) continue
                    if (fmt.url in sources || (item.url.isNotBlank() && item.url in sources)) {
                        continue
                    }
                    // Форма папки (20.11.2026, «переделай: автор → серия»):
                    // список автора — «Автор», список серии — «Автор/Серия».
                    // 23.11.2026: авторскую пачку ведём по автору САМОЙ КНИГИ,
                    // заголовок ленты — запасной: долгое в подленте автора
                    // («Книги по алфавиту») дало бы папку «Книги по алфавиту»,
                    // а не имя автора. У ленты «Книги автора X» авторы книг
                    // равны X — результат тот же.
                    val sub = if (kind == "series") seriesSub(item.author, name)
                    else authorSub(item.author) ?: DownloadStore.safeName(name)
                    specs.add(Spec(item.title, item.author, item.url, fmt, sub))
                    if (specs.size > BATCH_HARD_LIMIT) {
                        tooBig = true
                        break
                    }
                }
                if (tooBig || cancelled) break
                // Смешанная лента (урок Coollib 22.11.2026): навигационные папки
                // вперемешку с книгами — страница всё равно узел, книги в ней
                // единичны и случайны; полный список — в подленте.
                val mixedHub = hubRank > 0 && specs.size + noFile < 4
                url = if (mixedHub && !hubTried && hubBest != null) {
                    Diag.log(this, "opds", "пачка: страница — узел навигации, спускаюсь в подленту $hubBest")
                    hubTried = true
                    hubBest
                } else feed.nextUrl
                pages++
                if (url == null && !hubTried && specs.size <= 1 && hubBest != null) {
                    Diag.log(this, "opds", "пачка: страница — узел навигации, спускаюсь в подленту $hubBest")
                    url = hubBest
                    hubTried = true
                }
            }
        } catch (e: Throwable) {
            listFail = e
        }
        Diag.log(this, "opds", "пачка: собрано ${specs.size} книг со страниц $pages" +
            (if (noFile > 0) ", без файла $noFile" else "") +
            (if (listFail != null) ", обход прервался: ${listFail?.message}" else ""))
        if (tooBig) {
            finishWith(getString(R.string.catalog_batch_toobig, BATCH_HARD_LIMIT), failed = true)
            return
        }
        if (listFail is OpdsNeedLogin && specs.isEmpty()) {
            // Пачка упёрлась во вход: окно спросит логин само (человек тут же
            // повторит долгое), службы к тому моменту быть не должно.
            val e = listFail as OpdsNeedLogin
            val w = watcher
            if (w != null) {
                // Все зовы наблюдателя — в главном потоке (правило Watcher).
                main.post { w.onNeedLogin(e.url) }
                finishWith("вход", failed = true, quiet = true)
            } else {
                finishWith(getString(R.string.dl_need_login), failed = true)
            }
            return
        }
        if (cancelled) {
            finishWith(getString(R.string.dl_cancelled), failed = false)
            return
        }
        if (specs.isEmpty()) {
            val msg = if (listFail != null) netText(this, listFail, book = true)
            else if (noFile > 0) getString(R.string.catalog_batch_nofile)
            else getString(R.string.catalog_batch_none)
            finishWith(msg, failed = listFail != null)
            return
        }
        batchLabelState = name
        batchTotalState = specs.size
        batchDoneState = 0
        currentTitle = name
        Diag.log(this, "opds", "пачка «$name»: качаю ${specs.size} книг")
        val nStr = resources.getQuantityString(R.plurals.catalog_batch_n, specs.size, specs.size)
        // Тост — с главного потока: у рабочего нет Looper, Toast там падает.
        // Папку называем так, как она будет лежать: «Пелевин — Мир Тёмного».
        val disp = specs.firstOrNull()?.sub?.replace("/", " — ") ?: name
        val goMsg = getString(R.string.catalog_batch_go, nStr, disp) +
            // Coollib-уроки (22.11.2026): часть списка каталог не отдаёт вовсе —
            // человек слышит это сразу, а не гадает потом.
            if (noFile > 0) " " + getString(R.string.catalog_batch_nofile_more, noFile) else ""
        main.post { toast(goMsg) }
        downloadAll(name, specs)
    }

    /** Имя, пахнущее расписанием, а не собранием: даты, месяцы, годы внутри.
     *  «Новинки с 15.11.2026 по 22.11.2026», «Топ недели», «2024-176» — на
     *  следующий день такое имя уже другое, папка им руководить не должна.
     *  Правило общее: четыре цифры подряд, дата с точками или месяц по имени.
     *  «С днем рождения, сангвиник!» — это книга, не расписание: словесный
     *  хвост после цифры («2024 год») спасает от ложных срабатываний. */
    private fun looksLikeDateTitle(s: String): Boolean =
        Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}").containsMatchIn(s) ||
            Regex("(недел[ьяюе]|месяц[аеюы]|выпуск|топ )", RegexOption.IGNORE_CASE)
                .containsMatchIn(s)

    /** Из имени-расписания вырезаем даты и висячие предлоги: «Новинки с
     *  15.11.2026 по 22.11.2026» → «Новинки». Пусто — значит устойчивого ядра
     *  нет, caller решит, чем закрыть. */
    private fun stripDateTitle(s: String): String {
        var t = s
        t = Regex("\\d{1,2}\\.\\d{1,2}(\\.\\d{2,4})?").replace(t) { "" }
        t = Regex("\\b\\d{4}\\b").replace(t) { "" }
        t = t.replace(Regex("\\s+"), " ").trim()
        // хвост из предлогов, оставшийся после вырезки дат: «с по», «за», «из»
        t = Regex("(?i)(\\s+(с|по|из|от|за|к|в|на)\\s*)+$").replace(t) { " " }
        t = Regex("(?i)^(с|по|из|от|за|к|в|на)\\s+").replace(t) { "" }
        return t.trim()
    }

    /** Подлента-кандидат узла навигации: «Книги по алфавиту» — полный и самый
     *  устойчивый список; «по дате поступления» — запасной. 0 — не кандидат.
     *  22.11.2026 (урок Coollib): раньше спуск был только при пустой ленте, и
     *  автор с одной отдачей среди 60 заблокированных самиздат-книг скачивался
     *  «весь» одной книгой. Теперь узел виден по самому устройству ленты:
     *  навигационные папки вперемешку с книгами — это узел, книг в нём не
     *  считаем и спускаемся в подленту. */
    private fun hubCandidate(title: String): Int = when {
        title.contains("алфавит", ignoreCase = true) -> 1
        title.contains("дате поступления", ignoreCase = true) -> 2
        else -> 0
    }

    /** Служебные приставки заголовков лент флибусты, которые не должны попадать
     *  в имя папки. */
    private val FEED_TITLE_PREFIXES = arrayOf(
        "Книги автора ", "Все книги автора ", "Произведения автора ",
        "Книги серии ", "Все книги серии ", "Произведения серии ",
        "Книги в серии ", "Серия ",
    )

    /** Чистое имя папки из заголовка ленты: снять приставку и «ёлочки» вокруг
     *  имени серии («Произведения серии «Роза Мира»» → «Роза Мира»). */
    private fun cleanFeedTitle(raw: String): String {
        // Coollib-урок (22.11.2026): заголовок ленты приходит с разметкой
        // «Книги автора  <переносы и отступы> Thanaton» — имя вписано в шаблон
        // как есть. Все пробельные дыры сводим к одному пробелу до остального.
        var s = raw.replace(Regex("\\s+"), " ").trim()
        for (p in FEED_TITLE_PREFIXES) {
            if (s.startsWith(p, ignoreCase = true)) { s = s.substring(p.length).trim(); break }
        }
        if (s.length >= 2 && s.first() == '«' && s.last() == '»') {
            s = s.substring(1, s.length - 1).trim()
        }
        return s.ifBlank { raw.trim() }
    }

    /** Папка для одиночной книги и пачки автора: «Автор»; автора нет — корень
     *  папки скачивания (придумывать папку «book» хуже, чем положить в корень). */
    private fun authorSub(author: String?): String? {
        val a = author?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return DownloadStore.safeName(a).takeIf { it.isNotBlank() && it != "book" }
    }

    /** Папка для пачки серии: «Автор/Серия». Из заголовка ленты срезается
     *  приставка автора («Пелевин: Мир Тёмного» → «Мир Тёмного»), иначе в пути
     *  имя автора заглохло бы дважды. Автора нет — просто «Серия». */
    private fun seriesSub(bookAuthor: String?, listName: String): String {
        val a = bookAuthor?.trim().orEmpty()
        val an = authorSub(a.ifBlank { null })
        if (an == null) return DownloadStore.safeName(listName)
        var s = listName
        for (sep in arrayOf(": ", " — ", " – ", " - ", ". ")) {
            if (s.startsWith("$a$sep", ignoreCase = true)) {
                s = s.substring(("$a$sep").length).trim()
                break
            }
        }
        val sn = DownloadStore.safeName(s).takeIf { it.isNotBlank() && it != "book" } ?: return an
        return if (sn.equals(an, ignoreCase = true)) an else "$an/$sn"
    }

    /** Пакет, второй шаг: качать подряд. Одна упавшая книга пачку НЕ рвёт —
     *  пишем в журнал и идём дальше; отмена (кнопка в шторке или долгое на той
     *  же строке) и просьба войти — рвут: дальше тот же результат. */
    private fun downloadAll(name: String, specs: List<Spec>) {
        var done = 0
        var failed = 0
        var needLogin = false
        val disp = specs.firstOrNull()?.sub?.replace("/", " — ") ?: name
        for ((i, s) in specs.withIndex()) {
            if (cancelled) break
            batchDoneState = i + 1
            currentTitle = getString(R.string.catalog_batch_card, name, i + 1, specs.size)
            progressState = -1
            nm.notify(NOTIF_ID, buildRunning(currentTitle!!, -1))
            val r = runCatching { runOne(s) }
            if (r.isSuccess) {
                done++
                Diag.log(this, "opds", "пачка: $done из ${specs.size} — «${s.title}»")
            } else {
                val e = r.exceptionOrNull()
                when {
                    e is DownloadCancelled -> cancelled = true
                    e is OpdsNeedLogin -> needLogin = true
                    else -> {
                        failed++
                        Diag.log(this, "opds", "пачка: не отдалось «${s.title}» — ${e?.message}")
                    }
                }
                if (cancelled || needLogin) break
            }
            // БЕЗ ПАУЗ МЕЖДУ КНИГАМИ (слова Сержа 20.11.2026: «скачать без всяких
            // пауз, пускай наоборот быстро всё качается»). Книги и так идут
            // последовательно, одна за другой, быстрее канала всё равно не выйдет.
        }
        val head = StringBuilder(getString(R.string.catalog_batch_done, done, disp))
        if (failed > 0) head.append(". ").append(getString(R.string.catalog_batch_failed, failed))
        if (needLogin) {
            head.setLength(0)
            head.append(getString(R.string.catalog_batch_login, done))
        }
        if (cancelled) {
            head.setLength(0)
            head.append(getString(R.string.catalog_batch_cancelled, done, specs.size))
        }
        Diag.log(this, "opds", "пачка итог: $head")
        main.post { finishWith(head.toString(), failed = failed > 0 || needLogin, doneCard = true) }
    }

    /** Общий конец пачки: показать итог и либо позвать следующую из очереди,
     *  либо снять службу с переднего плана и убить себя. [doneCard] — карточка
     *  итога в шторке (пачка: её читают, когда окно уже закрыли); при
     *  продолжении очереди карточку НЕ вешаем — её же beginBatch снимет, а
     *  вслух итог уже сказан тостом. [quiet] — молча: окно сейчас само
     *  попросит вход. */
    private fun finishWith(
        msg: String,
        failed: Boolean,
        doneCard: Boolean = false,
        quiet: Boolean = false,
    ) {
        main.post {
            if (!quiet) {
                toast(msg)
                if (failed) Vibra.error(this) else Vibra.confirm(this)
            }
            if (jobEnded() != null) return@post
            runningState = false
            batchUrlState = null
            batchLabelState = null
            batchTotalState = 0
            batchDoneState = 0
            progressState = -1
            bookUrlState = null
            currentTitle = null
            if (doneCard && !quiet) {
                stopForegroundCompat(keepNotification = false)
                nm.notify(NOTIF_DONE_ID, buildDone(msg))
            } else {
                stopForegroundCompat(keepNotification = false)
            }
            if (failed) watcher?.onFailed(null) else watcher?.onCancelled()
            stopSelf()
        }
    }
    /** Ход загрузки: уведомление и окно. Вслух не читаем ничего — иначе вехи
     *  перебивают чтение книги (msg1264 их объявлял, 0.4.83 убрал). */
    private fun publishProgress(pct: Int) {
        progressState = pct
        val title = currentTitle ?: return
        nm.notify(NOTIF_ID, buildRunning(title, pct))
        watcher?.onProgress(pct)
    }

    private fun buildRunning(title: String, pct: Int): Notification {
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            // ПРОЦЕНТОВ В ТЕКСТЕ КАРТОЧКИ НЕТ (16.10.2026, просьба Сержа:
            // «чтобы когда скачивается книга проценты не проговаривались
            // скринридером»). Число менялось на каждом шаге, и карточка
            // перекладывалась заново — у части дикторов это читается вслух.
            // Ход видно полосой (она не текст и вслух не читается), а число
            // показывает окно каталога отдельной строкой.
            .setContentText(getString(R.string.catalog_dl_progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            .addAction(0, getString(R.string.dl_cancel), cancel)
            .build()
    }

    /** Карточка итога: обычная, смахиваемая, с кнопкой «Убрать» и переходом на
     *  полку по касанию. Живёт до тех пор, пока её не уберут: незрячему важно
     *  иметь след «скачано», если он ушёл из каталога и тост пропустил. */
    private fun buildDone(title: String): Notification {
        val dismiss = PendingIntent.getService(
            this, 2,
            Intent(this, DownloadService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openShelf = PendingIntent.getActivity(
            this, 3,
            Intent(this, LibraryWindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(getString(R.string.dl_notif_done))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(openShelf)
            .addAction(0, getString(R.string.dl_dismiss), dismiss)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Скачивание книг",
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat(n: Notification, batch: Boolean = false) {
        // Пачка — specialUse (Android 14+ ограничивает dataSync ~10 минутами в
        // фоне, серия из сотни книг не влезла бы); одиночная книга остаётся на
        // честном dataSync. Отказ specialUse (ROM без разрешения — так на
        // эмуляторе 22.11.2026 ловили) НЕ роняет службу: доедаем на dataSync,
        // хуже лимит, лучше живой пачки.
        if (Build.VERSION.SDK_INT >= 34 && batch) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (e: Exception) {
                Diag.log(this, "opds", "specialUse не пускают (${e.javaClass.simpleName}), качаю как dataSync")
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Снять службу с переднего плана. [keepNotification] — оставить итоговую
     *  карточку в шторке (загрузка удалась), иначе убрать совсем. */
    private fun stopForegroundCompat(keepNotification: Boolean) {
        stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        if (!keepNotification) runCatching { nm.cancel(NOTIF_ID) }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    /** Служба кончилась (stopSelf или система сняла) — состояние не должно
     *  врать: окно по нему гасит кнопки и подписывает прогресс, и если оно
     *  откроется уже после, «идёт загрузка» повисло бы навсегда. */
    override fun onDestroy() {
        runningState = false
        progressState = -1
        bookUrlState = null
        currentTitle = null
        batchUrlState = null
        batchLabelState = null
        batchTotalState = 0
        batchDoneState = 0
        // 23.11.2026: очередь живёт только в этой службе. Умерла служба —
        // умерли и ждушие пачки: при следующем запуске они бы качали по
        // часам-другим давности уже ненужные списки.
        synchronized(batchQueue) {
            batchQueue.clear()
            busyState = false
        }
        super.onDestroy()
    }
}
