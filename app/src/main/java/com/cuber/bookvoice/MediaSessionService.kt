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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Медиа-сервис читалки. Владеет MediaSessionCompat и работает В ПЕРЕДНЕМ
 * ПЛАНЕ (foreground service + медиа-уведомление), как настоящий плеер.
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

    private var session: MediaSessionCompat? = null
    private var bookTitle: String? = null
    private var playing = false

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
        session = MediaSessionCompat(this, "BookVoice").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Diag.log(this@MediaSessionService, "session", "onPlay, listener=${listener != null}")
                    post { listener?.onMediaPlay() }
                }

                override fun onPause() {
                    Diag.log(this@MediaSessionService, "session", "onPause, listener=${listener != null}")
                    post { listener?.onMediaPause() }
                }

                override fun onStop() {
                    Diag.log(this@MediaSessionService, "session", "onStop, listener=${listener != null}")
                    post { listener?.onMediaPause() }
                }

                override fun onSkipToNext() {
                    Diag.log(this@MediaSessionService, "session", "onSkipToNext (+1)")
                    post { listener?.onMediaSkip(1) }
                }

                override fun onSkipToPrevious() {
                    Diag.log(this@MediaSessionService, "session", "onSkipToPrevious (-1)")
                    post { listener?.onMediaSkip(-1) }
                }

                /** «Выход» пришёл из системной карточки (msg4578): на Android 13+
                 *  она строит кнопки не из addAction уведомления, а из состояния
                 *  сессии — см. pushPlaybackState(). */
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action != CUSTOM_EXIT) return
                    Diag.log(this@MediaSessionService, "service", "«выход» из карточки (слот сессии)")
                    post { listener?.onMediaExit() }
                    dropCardAndStop()
                }
            })
            setMediaButtonReceiver(
                mediaButtonPendingIntent(0)
            )
            isActive = true
            MediaButtonReceiver.sessionRef = this
        }
        Diag.log(this, "service", "onCreate: сессия активна, receiver установлен")
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
        // msg4629: касание карточки возвращает в книгу, которую держит движок.
        intent?.getStringExtra(MainActivity.EXTRA_URI)?.let {
            if (it != bookUri) {
                bookUri = it
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
     *  шторки в книгу было бы не вернуться. */
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
            PendingIntent.FLAG_IMMUTABLE,
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
            .setContentText(getString(if (playing) R.string.media_reading else R.string.media_paused))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(prevAction)
            .addAction(playAction)
            .addAction(nextAction)
            .addAction(exitAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
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
        if (MediaButtonReceiver.sessionRef === session) MediaButtonReceiver.sessionRef = null
        session?.release()
        session = null
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

        /** Команда «Выход» из карточки в шторке (msg4073). */
        private const val ACTION_EXIT = "com.cuber.bookvoice.EXIT_READING"

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
        ) {
            val intent = Intent(context, MediaSessionService::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_PLAYING, playing)
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

        /** Тихая пауза чтения из другого экрана (голосовой поиск каталога,
         *  msg1421): команда идёт через сессию → listener.onMediaPause →
         *  MainActivity.pausePlayback. No-op, если книга не открыта. */
        fun pause() {
            main.post { instance?.session?.controller?.transportControls?.pause() }
        }

        /** Карточка уже в шторке (сервис поднят в foreground). MainActivity
         *  проверяет перед стартом чтения: при ПЕРВОМ рождении карточки TalkBack
         *  озвучивает его («управление мультимедиа», msg1815) — речь стартует
         *  с паузой, чтобы фраза не легла на первое слово. Повторные старты
         *  (пауза → играть) карточку не пересоздают и задержки не требуют. */
        fun isUp(): Boolean = instance?.foregroundStarted == true
    }

    private fun applyBookTitle(title: String?) {
        Diag.log(this, "service", "книга: ${title ?: "без названия"}")
        bookTitle = title
        pushMetadata()
        notifyNow()
    }

    /** Метаданные сессии (название книги) — для системной медиа-карточки. */
    private fun pushMetadata() {
        session?.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, bookTitle ?: "BookVoice")
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, "BookVoice")
                .build()
        )
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

    /** Каждый раз заново подтверждаем, что сессия активна и хочет
     *  медиа-кнопки: некоторые прошивки «гасят» сессию на паузе, и тогда
     *  следующее нажатие «играть» уходит в никуда. */
    private fun pushPlaybackState() {
        val s = session ?: return
        s.isActive = true
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        // msg4578: «Выход» — не только четвёртым действием уведомления (см.
        // buildNotification), но и пользовательским действием СЕССИИ. На Android 13+
        // системная медиа-карточка берёт кнопки из состояния сессии: первые три
        // слота — play/предыдущая/следующая, четвёртый и пятый — пользовательские
        // действия (PlaybackStateCompat.CustomAction). Без этого «Выход» в шторке
        // не появлялся вовсе (msg4562: на карточке нет кнопки выхода).
        val exitCustom = PlaybackStateCompat.CustomAction.Builder(
            CUSTOM_EXIT,
            "Выход",
            android.R.drawable.ic_menu_close_clear_cancel,
        ).build()
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .addCustomAction(exitCustom)
                .setState(state, 0L, 1f)
                .build()
        )
    }
}
