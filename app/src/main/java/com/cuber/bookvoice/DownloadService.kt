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
        private const val ACTION_CANCEL = "com.cuber.bookvoice.action.DOWNLOAD_CANCEL"

        private const val EXTRA_TITLE = "book_title"
        private const val EXTRA_AUTHOR = "book_author"
        private const val EXTRA_URL = "book_url"
        private const val EXTRA_LABEL = "fmt_label"
        private const val EXTRA_EXT = "fmt_ext"
        private const val EXTRA_FMT_URL = "fmt_url"

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

        val running: Boolean get() = runningState
        val progress: Int get() = progressState
        val bookUrl: String? get() = bookUrlState

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
    }

    @Volatile
    private var cancelled = false

    private var currentTitle: String? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

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
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val fmtUrl = intent?.getStringExtra(EXTRA_FMT_URL)
        if (title.isNullOrBlank() || fmtUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val author = intent.getStringExtra(EXTRA_AUTHOR)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        cancelled = false
        runningState = true
        progressState = -1
        bookUrlState = url
        currentTitle = title
        // Уведомление поднимаем сразу: у службы переднего плана на это есть
        // несколько секунд, а первое объявление о старте читает владельцу тост.
        startForegroundCompat(buildRunning(title, -1))
        toast(getString(R.string.catalog_dl_start, title))
        Diag.log(this, "opds", "скачиваю \"$title\" формат $label: $fmtUrl")
        Thread { download(title, author, url, label, ext, fmtUrl) }.start()
        return START_NOT_STICKY
    }

    private fun download(
        title: String,
        author: String?,
        bookUrl: String,
        label: String,
        ext: String,
        fmtUrl: String,
    ) {
        var lastPct = -2
        val res = runCatching {
            // msg1264: тело читаем кусками и шлём прогресс. Дедуп по проценту:
            // уведомление обновляем только когда процент сменился.
            val fetch = OpdsNet.fetchProgress(
                this, fmtUrl, "*/*",
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
            DownloadStore.write(this, title, OpdsFormat(label, ext, fmtUrl), fetch.bytes)
        }
        val placed = res.getOrNull()
        val err = res.exceptionOrNull()

        main.post {
            runningState = false
            when {
                placed != null -> {
                    BookStore.upsert(this, BookRecord(
                        uri = placed.uri,
                        name = placed.name,
                        title = title,
                        author = author,
                        // Прямая ссылка скачивания: по ней при восстановлении из
                        // резервной копии можно доскачать потерянный файл (#100).
                        sourceUrl = fmtUrl,
                        addedAt = System.currentTimeMillis(),
                    ))
                    Diag.log(this, "opds", "книга скачана: \"$title\" → ${placed.uri}")
                    // 0.3.47: папка оказалась нерабочей — предупреждаем, что файл
                    // ушёл во внутреннюю память, а не в выбранную папку.
                    toast(
                        if (placed.fallback) getString(R.string.dl_folder_fallback)
                        else getString(R.string.catalog_dl_done, title)
                    )
                    Vibra.confirm(this)
                    watcher?.onFinished(title, placed.uri, placed.fallback)
                    // Итог оставляем в шторке карточкой (не висячей): владелец
                    // увидит, что загрузка кончилась, даже если ушёл из каталога.
                    nm.notify(NOTIF_ID, buildDone(title))
                    stopForegroundCompat(keepNotification = true)
                }
                err is DownloadCancelled -> {
                    Diag.log(this, "opds", "скачивание отменено: \"$title\"")
                    toast(getString(R.string.dl_cancelled))
                    watcher?.onCancelled()
                    stopForegroundCompat(keepNotification = false)
                }
                err is OpdsNeedLogin -> {
                    // Книга отдаётся только после входа (#20): спрашиваем логин —
                    // это умеет окно каталога. Окна нет — говорим, что делать.
                    Diag.log(this, "opds", "книга просит вход: \"$title\"")
                    val w = watcher
                    if (w != null) w.onNeedLogin(err.url) else toast(getString(R.string.dl_need_login))
                    stopForegroundCompat(keepNotification = false)
                }
                else -> {
                    // 0.4.83 (msg7001): вслух — «что случилось и что делать», без
                    // кода ответа и без английского текста исключения.
                    Diag.log(this, "opds", "книга не скачалась: \"$title\" — ${err?.message}")
                    toast(netText(this, err, book = true))
                    Vibra.error(this)
                    watcher?.onFailed(err)
                    stopForegroundCompat(keepNotification = false)
                }
            }
            progressState = -1
            bookUrlState = null
            currentTitle = null
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
            .setContentText(
                if (pct >= 0) getString(R.string.catalog_dl_progress_pct, pct)
                else getString(R.string.catalog_dl_progress)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, if (pct >= 0) pct else 0, pct < 0)
            .addAction(0, getString(R.string.dl_cancel), cancel)
            .build()
    }

    private fun buildDone(title: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(getString(R.string.dl_notif_done))
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(true)
            .build()

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

    private fun startForegroundCompat(n: Notification) {
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
        super.onDestroy()
    }
}
