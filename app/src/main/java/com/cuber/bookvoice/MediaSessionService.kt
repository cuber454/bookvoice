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

    /** Первое уведомление уже опубликовано: повторные start() (пауза → снова
     *  читать) не должны пересоздавать его (msg1636+, 0.3.67). */
    private var foregroundStarted = false

    /** Получатель медиа-команд — сейчас это открытый ридер. */
    interface Listener {
        fun onMediaPlay()
        fun onMediaPause()
        fun onMediaSkip(delta: Int)
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
        // Карточка рождается в момент реального старта чтения (startSpeakingCurrent
        // зовёт start(playing=true), msg1779): раньше она появлялась при ОТКРЫТИИ
        // книги, и TalkBack озвучивал рождение при каждом открытии молчащей книги.
        // Состояние из интента применяем при КАЖДОМ старте, а не только при
        // первом: состояние обязано отражать реальность, а не первое попавшееся.
        var changed = false
        intent?.getStringExtra(EXTRA_TITLE)?.let { bookTitle = it }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(bookTitle ?: "BookVoice")
            .setContentText(getString(if (playing) R.string.media_reading else R.string.media_paused))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(playAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()
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
        listener = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "reader_playback"
        private const val NOTIF_ID = 1
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PLAYING = "playing"

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MediaSessionService? = null

        @Volatile
        var listener: Listener? = null

        /** Запустить/поднять сервис в переднем плане (идемпотентно).
         *  [title]/[playing] — состояние для ПЕРВОГО уведомления (msg1636+,
         *  0.3.67). Вызывать с UI-потока, пока приложение на экране. */
        fun start(context: Context, title: String? = null, playing: Boolean = false) {
            val intent = Intent(context, MediaSessionService::class.java)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_PLAYING, playing)
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
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, 0L, 1f)
                .build()
        )
    }
}
