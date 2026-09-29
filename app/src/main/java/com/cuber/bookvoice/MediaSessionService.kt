package com.cuber.bookvoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Медиа-сервис читалки. Держит сессию Media3 и работает В ПЕРЕДНЕМ
 * ПЛАНЕ (foreground service + медиа-уведомление), как настоящий плеер.
 * Медиа-карточку собирает сам Media3 (`MediaStyleNotificationHelper`): он
 * кладёт в уведомление ключ сессии Media3 напрямую, поэтому старая библиотека
 * `androidx.media` и её `MediaSessionCompat` больше не нужны (30.09.2026,
 * сборка 220).
 *
 * Почему именно foreground: Android отдаёт медиа-кнопки (Bluetooth-гарнитура,
 * «волшебное касание» TalkBack) тому приложению, которое сейчас «плеер».
 * Пока сессия жила в Activity или в обычном фоновом сервисе, систему
 * перехватывал другой запущенный плеер, и нажатия уходили ему. Медиа-сервис
 * переднего плана с уведомлением и метаданными — единственный надёжный
 * способ заявить системе: медиа-кнопки сейчас наши.
 *
 * Озвучка (TTS) остаётся в [MainActivity]; сюда приходят только команды,
 * которые сервис передаёт через [listener].
 */
class MediaSessionService : Service() {

    /** Сессия Media3 (29.09.2026, часть 5): система сама спрашивает состояние у
     *  [ReaderPlayer] и сама раздаёт медиа-команды — вручную публиковать ничего
     *  не надо. Прежний путь на MediaSessionCompat снесён 30.09.2026: сессия
     *  проверена на телефоне (журнал 23:26-23:28: карточка, «волшебное касание»,
     *  состояние), и держать два пути стало незачем. */
    private var session: MediaSession? = null

    private var player: ReaderPlayer? = null

    /** Засечка последнего «переключи»: одна и та же кнопка может прийти двумя
     *  путями (наш приёмник и колбэк сессии), а двойное переключение — это
     *  «пауза, потом сразу играть», то есть на слух «ничего не произошло». */
    private var toggleAt = 0L

    private var bookTitle: String? = null
    private var playing = false

    /** Место в книге — вторая строка карточки (msg4725), приходит от движка. */
    private var cardLine: String? = null

    /** Книга, которую держит движок: цель касания карточки (msg4629). Карточка
     *  на паузе живёт без окна — без этого из шторки в книгу было бы не вернуться. */
    private var bookUri: String? = null

    /** Первое уведомление уже опубликовано: повторные start() (пауза → снова
     *  читать) не должны пересоздавать его (msg1636+, 0.3.67). */
    private var foregroundStarted = false

    /** Получатель медиа-команд — сейчас это открытый ридер. */
    interface Listener {
        fun onMediaPlay()
        fun onMediaPause()

        /** «Переключи» — «волшебное касание» TalkBack (два пальца, два касания в
         *  любом месте экрана) и центральная кнопка гарнитуры. Решение принимает
         *  ДВИЖОК по своему фактическому состоянию, а не система по тому, что мы
         *  ей опубликовали (0.4.77, см. [MediaSessionService] — иначе касание
         *  перестаёт ставить паузу). */
        fun onMediaToggle()

        fun onMediaSkip(delta: Int)

        /** «Выход» из карточки в шторке (msg4073): владелец гасит чтение, не
         *  открывая окно. Движок останавливает речь, отпускает аудиофокус и
         *  сохраняет место; служба убирает карточку. */
        fun onMediaExit()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        createMedia3Session()
    }

    /**
     * Сессия на Media3 (29.09.2026, часть 5). Состояние система берёт у
     * [ReaderPlayer] — публиковать его руками больше не нужно, — а разбор
     * «переключи» и «Выход» остаются у нас: первое потому, что систему нельзя
     * пускать решать play/pause по состоянию, которое может отстать (0.4.77),
     * второе уезжает в системную карточку Android 13+ пользовательской командой
     * (прежде это было PlaybackStateCompat.CustomAction).
     */
    private fun createMedia3Session() {
        val p = ReaderPlayer(
            main.looper,
            titleOf = { bookTitle },
            playingOf = { playing },
            onPlay = { post { listener?.onMediaPlay() } },
            onPause = { post { listener?.onMediaPause() } },
            onSkip = { delta -> post { listener?.onMediaSkip(delta) } },
            log = { Diag.log(this, "session", it) },
        )
        player = p
        val exitCommand = SessionCommand(CUSTOM_EXIT, Bundle.EMPTY)
        session = MediaSession.Builder(this, p)
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    intent: Intent,
                ): Boolean {
                    val key = keyEventOf(intent) ?: return false
                    val toggle = key.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                        key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    if (!toggle) return false
                    if (key.action == KeyEvent.ACTION_DOWN) {
                        Diag.log(
                            this@MediaSessionService, "session",
                            "переключение с кнопки/касания (медиа-сессия): решает движок, " +
                                "listener=${listener != null}"
                        )
                        post { toggleFromMedia() }
                    }
                    return true
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    command: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (command.customAction == CUSTOM_EXIT) {
                        Diag.log(this@MediaSessionService, "service", "«выход» из карточки (медиа-сессия)")
                        post { listener?.onMediaExit() }
                        dropCardAndStop()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setCustomLayout(
                listOf(
                    CommandButton.Builder(android.R.drawable.ic_menu_close_clear_cancel)
                        .setDisplayName("Выход")
                        .setSessionCommand(exitCommand)
                        .build()
                )
            )
            .build()
        p.setBook(bookUri)
        applySessionActivity()
        Diag.log(this, "service", "onCreate: сессия Media3 поднята")
    }


    /** «Переключи» из любого пути: решение принимает движок по своему
     *  фактическому состоянию. Повтор в пределах [TOGGLE_MS] гасим — одна и та же
     *  кнопка может прийти и от нашего приёмника, и от колбэка сессии. */
    private fun toggleFromMedia() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - toggleAt < TOGGLE_MS) {
            Diag.log(this, "session", "повторное «переключи» через ${now - toggleAt} мс — пропускаю")
            return
        }
        toggleAt = now
        listener?.onMediaToggle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // «Выход» из карточки (msg4073): сначала просим движок встать — он
        // отпустит фокус и сохранит место, — потом убираем карточку и гасим
        // службу. Порядок важен: гашение службы само по себе фокус не отдаёт.
        if (intent?.action == ACTION_EXIT) {
            Diag.log(this, "service", "«выход» из карточки: гашу чтение и карточку")
            post { listener?.onMediaExit() }
            dropCardAndStop()
            return START_NOT_STICKY
        }
        // Карточка рождается в момент реального старта чтения (startSpeakingCurrent
        // зовёт start(playing=true), msg1779): раньше она появлялась при ОТКРЫТИИ
        // книги, и TalkBack озвучивал рождение при каждом открытии молчащей книги.
        // Состояние из интента применяем при КАЖДОМ старте, а не только при
        // первом: состояние обязано отражать реальность, а не первое попавшееся.
        var changed = false
        intent?.getStringExtra(EXTRA_TITLE)?.let { bookTitle = it }
        // msg4725: место в книге тем же путём, что и название, — в extras. Через
        // setCardLine() его передать нельзя: служба поднимается асинхронно, и
        // первый пост пришёл бы в ещё не созданный instance (карточка родилась бы
        // с «Пауза» вместо главы).
        intent?.getStringExtra(EXTRA_CARD)?.let {
            if (it != cardLine) {
                cardLine = it
                changed = true
            }
        }
        // msg4629: касание карточки возвращает в книгу, которую держит движок.
        intent?.getStringExtra(MainActivity.EXTRA_URI)?.let {
            if (it != bookUri) {
                bookUri = it
                player?.setBook(bookUri)
                applySessionActivity()
                changed = true
            }
        }
        if (intent?.hasExtra(EXTRA_PLAYING) == true) {
            val wantPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
            changed = wantPlaying != playing
            playing = wantPlaying
        }
        pushMetadata()
        pushPlaybackState()
        if (!foregroundStarted) {
            foregroundStarted = true
            // msg1818: засечка публикации карточки — по таймингу в diag.log
            // сопоставляем, от карточки ли фраза TalkBack «управление мультимедиа»
            // или от первого реального звука (MediaPlayer/SpeechPlayer).
            Diag.log(this, "service", "карточка публикуется (playing=$playing)")
            startForegroundCompat(buildNotification())
        } else if (changed) {
            notifyNow()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Чтение книг",
                NotificationManager.IMPORTANCE_LOW,
            )
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun mediaButtonPendingIntent(code: Int, keyCode: Int = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE): PendingIntent {
        val key = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        return PendingIntent.getBroadcast(
            this,
            code,
            Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(this, MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, key),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Ссылка на «Выход» в карточке: идёт в саму службу тем же путём, что и
     *  старт чтения (getService), — карточка живёт, пока служба работает. */
    private fun exitPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            4,
            Intent(this, MediaSessionService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** Намерение «вернуться в книгу» — цель касания карточки и активность сессии
     *  (msg4629). Ведёт в окно читалки к той же книге: движок узнаёт её по uri и
     *  переподключает окно к живому состоянию ([MainActivity] → rejoinLiveReading).
     *  Нужно именно потому, что карточка теперь висит и без окна — иначе из
     *  шторки в книгу было бы не вернуться.
     *
     *  FLAG_UPDATE_CURRENT обязателен (msg5867): система узнаёт PendingIntent по
     *  (код запроса, компонент), а extras в опознание НЕ входят. Без флага
     *  возвращался тот же самый объект, собранный когда-то с ПЕРВОЙ книгой, и
     *  подмены «книга в extra» не происходило: касание карточки в шторке вело
     *  в старую книгу. В diag.log Сергея (02:14:45) оно бросило живую «Томас
     *  Тредд. Мёртвый ход» и полезло открывать давно забытый файл из /Books,
     *  которого уже нет, — «формат не поддерживается или файл повреждён». */
    private fun bookOpenIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            5,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_URI, bookUri)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Активность сессии: система использует её для «вернуться к плееру» (в т.ч.
     *  из карточки шторки). Переставляем, когда движок открыл другую книгу. */
    private fun applySessionActivity() {
        if (bookUri == null) return
        runCatching { session?.setSessionActivity(bookOpenIntent()) }
    }

    /** Убрать карточку и погасить службу — без убийства процесса: место чтения
     *  и настройки дописываются на диск асинхронно (prefs.apply). */
    private fun dropCardAndStop() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val playAction: NotificationCompat.Action
        if (playing) {
            playAction = NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Пауза",
                mediaButtonPendingIntent(1),
            )
        } else {
            playAction = NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Играть",
                mediaButtonPendingIntent(1),
            )
        }
        // msg2182: «назад»/«вперёд» в карточке шторки — те же media-button команды,
        // что на гарнитуре (KEYCODE_MEDIA_PREVIOUS/NEXT): идут в MediaButtonReceiver →
        // session callback onSkipToPrevious/Next → движок, листают по настройкам
        // «Кнопка назад/вперёд». Один путь для всех устройств ввода — не разойдутся.
        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Назад",
            mediaButtonPendingIntent(2, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        )
        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Вперёд",
            mediaButtonPendingIntent(3, KeyEvent.KEYCODE_MEDIA_NEXT),
        )
        // msg4073: «Выход» — как у чужой читалки (Сергей сравнил карточки):
        // гасит чтение прямо из шторки, не открывая окно. На Android 13+ этот
        // набор действий система игнорирует (карточка строится из состояния
        // сессии, см. pushPlaybackState) — он остаётся ради Android 12 и ниже,
        // где кнопки берутся именно отсюда.
        val exitAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Выход",
            exitPendingIntent(),
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(bookTitle ?: "BookVoice")
            .setContentText(cardText())
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(prevAction)
            .addAction(playAction)
            .addAction(nextAction)
            .addAction(exitAction)
        // Стиль медиа-карточки берём у Media3: его MediaStyleNotificationHelper
        // кладёт в уведомление ключ сессии Media3 напрямую, без моста через
        // MediaSessionCompat, поэтому старая androidx.media уходит совсем
        // (30.09.2026, сборка 220). Сессии ещё нет — публикуем карточку без
        // стиля: она всё равно не медиа, а следующая публикация будет со стилем.
        val live = session
        if (live != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(live)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        // msg4629: касание карточки возвращает в книгу — путь знает движок,
        // он и передал uri (см. ReaderEngine.ensureMediaService).
        if (bookUri != null) builder.setContentIntent(bookOpenIntent())
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notifyNow() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    /** Сервис стартует только когда открыта книга — активно всё время жизни. */
    private fun post(block: () -> Unit) {
        main.post { block() }
    }

    override fun onDestroy() {
        Diag.log(this, "service", "onDestroy")
        runCatching { session?.release() }
        session = null
        player = null
        // listener НЕ обнуляем (было здесь раньше): он — мост медиа-команд к
        // движку ReaderEngine (ставится в attach). Движок — синглтон процесса,
        // поэтому listener живёт, пока жив процесс. Гонка при переходе между
        // книгами: close(А) → stopService → новый attach(Б) → асинхронный
        // onDestroy старого сервиса приходит ПОСЛЕ attach и обнулял listener —
        // пересозданный сервис книги Б оставался без слушателя, и «волшебное
        // касание»/гарнитура не останавливали чтение (listener=false, msg2078).
        // mediaCommands движка безопасен и при закрытой книге (guards внутри).
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIF_ID = 1
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        /** Место в книге для второй строки карточки (msg4725). */
        private const val EXTRA_CARD = "card"

        /** Команда «Выход» из карточки в шторке (msg4073). */
        private const val ACTION_EXIT = "com.cuber.bookvoice.EXIT_READING"

        /** Окно, в котором повторное «переключи» считаем тем же самым нажатием:
         *  одна кнопка может прийти и от нашего приёмника, и от колбэка сессии. */
        private const val TOGGLE_MS = 250L

        /** Кнопка из нашего уведомления (её шлёт [MediaButtonReceiver]).
         *  Под Media3 сессия больше не принимает `dispatchMediaButtonEvent`,
         *  поэтому раскладываем клавишу здесь — ровно так же, как это делала
         *  сессия: переключатель решает движок по фактическому состоянию. */
        fun onMediaButton(key: KeyEvent) {
            if (key.action != KeyEvent.ACTION_DOWN) return
            main.post {
                when (key.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                        instance?.toggleFromMedia() ?: listener?.onMediaToggle()

                    KeyEvent.KEYCODE_MEDIA_PLAY -> listener?.onMediaPlay()
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_STOP -> listener?.onMediaPause()

                    KeyEvent.KEYCODE_MEDIA_NEXT -> listener?.onMediaSkip(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> listener?.onMediaSkip(-1)
                }
            }
        }

        /** «Выход» как пользовательское действие сессии (msg4578): по этой
         *  строке система узнаёт кнопку в четвёртом слоте карточки Android 13+. */
        private const val CUSTOM_EXIT = "com.cuber.bookvoice.CUSTOM_EXIT"

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MediaSessionService? = null

        @Volatile
        var listener: Listener? = null

        /** Запустить/поднять сервис в переднем плане (идемпотентно).
         *  [title]/[playing] — состояние для ПЕРВОГО уведомления (msg1636+,
         *  0.3.67). Вызывать с UI-потока, пока приложение на экране. */
        fun start(
            context: Context,
            title: String? = null,
            playing: Boolean = false,
            uri: String? = null,
            card: String? = null,
        ) {
            val intent = Intent(context, MediaSessionService::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_PLAYING, playing)
            intent.putExtra(EXTRA_CARD, card)
            // тот же ключ, что у окна читалки: MainActivity.EXTRA_URI
            intent.putExtra(MainActivity.EXTRA_URI, uri)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MediaSessionService::class.java)) }
        }

        /** Название книги — для уведомления и метаданных сессии. */
        fun setBookTitle(title: String?) {
            main.post { instance?.applyBookTitle(title) }
        }

        /** Держать системное состояние в актуальности: от него зависит,
         *  что пошлёт кнопка — play или pause. */
        fun setPlaying(playing: Boolean) {
            main.post { instance?.applyPlaying(playing) }
        }

        /** Вторая строка карточки — место в книге (msg4725). Приходит от движка:
         *  только он знает главу. null — вернуть обычное «Читает…»/«Пауза». */
        fun setCardLine(line: String?) {
            main.post { instance?.applyCardLine(line) }
        }

        /** Тихая пауза чтения из другого экрана (голосовой поиск каталога,
         *  msg1421): команда идёт через сессию → listener.onMediaPause →
         *  MainActivity.pausePlayback. No-op, если книга не открыта. */
        fun pause() {
            main.post { instance?.session?.player?.pause() }
        }

        /** Карточка уже в шторке (сервис поднят в foreground). MainActivity
         *  проверяет перед стартом чтения: при ПЕРВОМ рождении карточки TalkBack
         *  озвучивает его («управление мультимедиа», msg1815) — речь стартует
         *  с паузой, чтобы фраза не легла на первое слово. Повторные старты
         *  (пауза → играть) карточку не пересоздают и задержки не требуют. */
        fun isUp(): Boolean = instance?.foregroundStarted == true
    }

    /** Клавиша из интента медиа-кнопки. На Android 13+ нужен типизированный
     *  вызов, иначе система отдаёт null. */
    private fun keyEventOf(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

    private fun applyBookTitle(title: String?) {
        Diag.log(this, "service", "книга: ${title ?: "без названия"}")
        bookTitle = title
        pushMetadata()
        notifyNow()
    }

    /** Метаданные сессии (название книги) — для системной медиа-карточки.
     *  Название Media3 берёт из состояния плеера-переводчика
     *  ([ReaderPlayer.getState]); отдельно публиковать его нечем. */
    private fun pushMetadata() {
        player?.refresh()
    }

    private fun applyPlaying(p: Boolean) {
        val changed = playing != p
        if (changed) {
            Diag.log(this, "service", "состояние плеера → ${if (p) "ИГРАЕТ" else "ПАУЗА"}")
        }
        playing = p
        pushPlaybackState()
        // msg1636+ (0.3.67): уведомление пересобираем только когда реально
        // меняется его вид (пауза ↔ чтение). Раньше оно публиковалось на каждый
        // вызов setPlaying — при открытии книги до 4 раз подряд, и TalkBack
        // озвучивал лишние публикации.
        // msg1644 (0.3.68): если первый foreground ещё не встал (гонка: setPlaying
        // сработал раньше onStartCommand) — уведомление НЕ дублируем; начальное
        // опубликует onStartCommand по extras, и оно уже будет правильным.
        if (changed && foregroundStarted) notifyNow()
    }

    /** Место в книге для второй строки карточки (msg4725). Пересобираем
     *  уведомление только когда строка реально сменилась — движок зовёт это на
     *  каждой фразе, а публикация стоит денег: TalkBack объявляет обновления
     *  карточки, которая у него в фокусе (тем же правилом живёт applyPlaying). */
    private fun applyCardLine(line: String?) {
        if (line == cardLine) return
        cardLine = line
        Diag.log(this, "service", "карточка: место «${line ?: "—"}»")
        if (foregroundStarted) notifyNow()
    }

    /** Вторая строка уведомления. Есть место в книге — показываем его (как у
     *  чужой читалки: «Глава 6 ПЛЕН»); пауза остаётся словом впереди, чтобы
     *  состояние читалось и по тексту, а не только по кнопке. Места нет
     *  (карточка родилась раньше книги) — прежнее «Читает…»/«Пауза». */
    private fun cardText(): String {
        val pos = cardLine
        val state = getString(if (playing) R.string.media_reading else R.string.media_paused)
        return when {
            pos.isNullOrEmpty() -> state
            playing -> pos
            else -> "$state · $pos"
        }
    }

    /** Состояние читалки для системы. Отдельно публиковать его больше не нужно:
     *  система спрашивает состояние у [ReaderPlayer], а он берёт его у движка
     *  (playing). Осталась одна строка — сказать плееру, что состояние изменилось. */
    private fun pushPlaybackState() {
        player?.refresh()
    }
}
