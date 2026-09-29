package com.cuber.bookvoice

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Проигрывание готовых звуковых файлов через ExoPlayer (Media3).
 *
 *  28.09.2026, часть 1 поэтапного перехода; 29.09.2026 переезд закончен — этот
 *  класс ЕДИНСТВЕННЫЙ, кто играет звук: старый путь на нашем MediaPlayer с
 *  прицепкой встык снесён из [SpeechPlayer] вместе со сверками конвейера и
 *  сторожем позиции. Поэтому упоминания MediaPlayer ниже — это рассказ о том,
 *  ПОЧЕМУ мы перешли, а не о том, что где-то осталось.
 *
 *  Зачем переходим (разбор с Сержем 28.09.2026):
 *  1. У MediaPlayer впереди может стоять ТОЛЬКО ОДИН файл: остальные заготовки
 *     ждут в нашей очереди, и когда чтение просит другую фразу, прицепку
 *     приходится отпускать и заказывать заново — отсюда строки «встык пропущен»,
 *     «встык не принят», «встык: фраза не совпала» и редкие щелчки на стыках.
 *     У ExoPlayer это СПИСОК: элементы добавляются, убираются и заменяются на
 *     ходу, а переходы между ними библиотека делает бесшовно.
 *  2. Скорость задаётся PlaybackParameters и сохраняет высоту голоса, применяется
 *     на ходу, без повторной установки после паузы и без предела «от половины до
 *     двух раз», который есть у MediaPlayer.setPlaybackParams.
 *  3. Пауза и продолжение идут с точной позиции внутри файла, а не с его начала:
 *     наш «сохранённый звук фразы» (keptPhrase) станет не нужен.
 *  4. Файлы разных голосов (повествование и реплики) можно держать в одном списке
 *     и задавать каждому свою громкость — пауза на смене роли уйдёт.
 *
 *  Чего этот класс НЕ делает: не синтезирует, не режет текст на фразы, не ведёт
 *  очередь заготовок, не трогает словарь и не работает с движком напрямую. Всё
 *  это остаётся в [SpeechPlayer] и [ReaderEngine]: сюда приходят уже готовые файлы
 *  по порядку, отсюда уходят сигналы «фраза началась», «фраза доиграла», «всё
 *  кончилось».
 *
 *  Аудиофокус класс НЕ запрашивает ([ExoPlayer.setAudioAttributes] с
 *  handleAudioFocus = false): фокусом по-прежнему распоряжается движок чтения
 *  (ReaderEngine.requestAudioFocus) — иначе два хозяина фокуса мешали бы друг
 *  другу, а наша логика «пауза из-за чужого плеера» перестала бы работать.
 *
 *  Поток: ExoPlayer не потокобезопасен — создаём и трогаем его только на главном
 *  потоке, как и наш MediaPlayer.
 */
class FilePlayback(private val ctx: Context) {

    /** Фраза, отданная на проигрывание: [id] — заявка приложения (по ней ищем
     *  фразу в сигналах плеера), [text] — текст (для журнала и сверок с чтением),
     *  [file] — готовый звук. */
    class Item(val id: Long, val text: String, val file: File)

    companion object {
        /** Сколько знаков текста показываем в журнале. */
        private const val BRIEF = 40

        /** Первые знаки фразы для журнала: по ним видно, ЧТО именно звучало.
         *  Зачем (30.09.2026, жалоба Сержа «фраза два раза прочиталась»): в
         *  журнале были только имя файла и размер, а два разных файла одного
         *  размера не различить — и не понять, книга повторила строку или мы
         *  прочитали её дважды. Пробелы и переводы строк сжимаем, хвост режем. */
        fun brief(text: String): String {
            val one = text.replace(Regex("\\s+"), " ").trim()
            return if (one.length <= BRIEF) one else one.take(BRIEF) + "…"
        }
    }

    /** Что плеер сообщает хозяину. Все вызовы — на главном потоке. */
    interface Listener {
        /** Плеер начал играть фразу с позиции [index] в списке. */
        fun onPhraseStart(index: Int, item: Item)

        /** Фраза [index] доиграла (плеер ушёл на следующую или остановился). */
        fun onPhraseEnd(index: Int, item: Item)

        /** Список кончился: играть больше нечего. */
        fun onAllEnded()

        /** Плеер не смог проиграть файл. Чтение само решит, что делать. */
        fun onError(item: Item?, message: String)
    }

    private var exo: ExoPlayer? = null
    private var listener: Listener? = null

    /** Наш список: тот же порядок, что у ExoPlayer, но с текстами и заявками.
     *  Индексы в сигналах плеера сверяем с этим списком. */
    private val items = ArrayList<Item>()

    /** Что играет сейчас и что уже отыграло — по этим числам считаем сигналы. */
    private var playingIndex = -1
    private var endedReported = false

    fun setListener(l: Listener?) {
        listener = l
    }

    val isPlaying: Boolean get() = exo?.isPlaying == true

    val positionMs: Long get() = exo?.currentPosition ?: 0L

    val currentIndex: Int get() = exo?.currentMediaItemIndex ?: -1

    /** Сколько фраз сейчас в списке. */
    val size: Int get() = items.size

    /** Звук начинается с [startPositionMs] внутри первой фразы: так продолжается
     *  пауза, не переигрывая фразу с начала. */
    fun play(list: List<Item>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        if (list.isEmpty()) return
        items.clear()
        items.addAll(list)
        endedReported = false
        playingIndex = -1
        val p = player()
        p.setMediaItems(mediaItems(items), startIndex.coerceIn(0, items.size - 1), startPositionMs)
        p.prepare()
        p.play()
        Diag.log(
            ctx, "sound",
            "exo: играю список: фраз ${items.size}, с позиции " +
                "${items.getOrNull(startIndex)?.file?.name ?: "?"}" +
                if (startPositionMs > 0) " ($startPositionMs мс)" else "",
        )
    }

    /** Дописать фразы в конец — то, ради чего и берём ExoPlayer: список можно
     *  удлинять на ходу, не трогая звук. */
    fun append(list: List<Item>) {
        if (list.isEmpty()) return
        // 29.09.2026: ежели плеера ещё нет (фразу приготовили заранее, до нажатия
        // «читать»), создаём его МОЛЧА: playWhenReady = false. Иначе подготовка
        // первой фразы сама начинала чтение — жалоба Сержа «запускаю приложение,
        // а оно читает, хотя галочка автоматического чтения снята».
        val fresh = exo == null
        val p = player()
        // 29.09.2026 (журнал 18:43, «затормозил и вообще вставал»): плеер, доигравший
        // список до конца, сам с новых фраз НЕ заводится — он так и стоит в «кончил».
        // В журнале это видно без «играю фразу» и без «фраза доиграла» после
        // «список кончился — жду следующую фразу»: чтение немеет навсегда, и
        // спасает только новый запуск списка. Поэтому, дописывая фразы в кончивший
        // список, ставим плеер на первую из них и готовим его заново.
        val ended = !fresh && p.playbackState == Player.STATE_ENDED
        val firstNew = items.size
        items.addAll(list)
        p.addMediaItems(mediaItems(list))
        when {
            fresh -> {
                p.playWhenReady = false
                p.prepare()
            }
            ended -> {
                p.seekTo(firstNew, 0L)
                p.prepare()
                p.play()
            }
            p.playbackState == Player.STATE_IDLE -> p.prepare()
        }
        Diag.log(ctx, "sound", "exo: дописал фраз ${list.size}, всего ${items.size}")
    }

    /** Выбросить всё, что впереди: оставить звучащую фразу и ещё [keepAhead] фраз
     *  после неё. Неверные заготовки уходят, а звучащая продолжает играть.
     *
     *  29.09.2026 (жалоба Сержа «словил очередную глюк после кручения скорости»).
     *  Раньше сюда приходил АБСОЛЮТНЫЙ индекс списка, а список по мере игры НЕ
     *  укорачивается: отыгранные фразы в нём остаются. После двух десятков фраз
     *  звучащая стояла уже на 20-м месте, и «оставить одну» выбрасывало хвост
     *  начиная с ПЕРВОГО элемента — плеер терял и звучащую фразу, и очередь. В
     *  журнале это рядом: «играю фразу 20/22» и «дописал фраз 1, всего 22» при
     *  зеркале в три фразы. Теперь считаем от звучащей: её индекс берём у плеера. */
    fun dropAhead(keepAhead: Int) {
        val p = exo ?: return
        val from = p.currentMediaItemIndex.coerceAtLeast(0) + 1 + keepAhead
        if (from >= items.size) return
        p.removeMediaItems(from, items.size)
        while (items.size > from) items.removeAt(items.size - 1)
        Diag.log(ctx, "sound", "exo: впереди оставил $keepAhead фраз, в списке ${items.size}")
    }

    /** Множитель темпа и высоты голоса. У ExoPlayer они живут в одних
     *  [PlaybackParameters], поэтому держим оба и обновляем одним заходом — иначе
     *  смена одного затирала бы другой. */
    private var speedFactor = 1f
    private var pitchFactor = 1f

    /** Сменить скорость на ходу. Высота голоса не меняется. */
    fun setSpeed(speed: Float) {
        speedFactor = speed.coerceIn(0.25f, 4f)
        applyParams()
    }

    /** Сменить высоту голоса на ходу (29.09.2026, «кручу тон, а срабатывает с
     *  большущей задержкой»): файл записан движком на своём тоне, а плееру задаём
     *  отношение «нужный тон / тон файла». Темп при этом не трогаем. */
    fun setPitch(pitch: Float) {
        pitchFactor = pitch.coerceIn(0.25f, 4f)
        applyParams()
    }

    private fun applyParams() {
        exo?.playbackParameters = PlaybackParameters(speedFactor, pitchFactor)
    }

    /** Громкость 0..1 — как у нашего плеера. */
    fun setVolume(v: Float) {
        exo?.volume = v.coerceIn(0f, 1f)
    }

    fun pause() {
        exo?.pause()
    }

    fun resume() {
        val p = exo ?: return
        // 29.09.2026: «кончил список» — это не пауза, play() такой плеер не заводит.
        // Если после конца что-то дописали, встаём на следующую фразу и готовим
        // плеер заново; иначе чтение осталось бы в тишине навсегда.
        if (p.playbackState == Player.STATE_ENDED) {
            val next = p.currentMediaItemIndex + 1
            if (next < p.mediaItemCount) p.seekTo(next, 0L)
            p.prepare()
        }
        p.play()
    }

    fun stop() {
        val p = exo ?: return
        p.stop()
        p.clearMediaItems()
        items.clear()
        playingIndex = -1
        endedReported = false
    }

    fun release() {
        exo?.release()
        exo = null
        items.clear()
        playingIndex = -1
    }

    // ---------------- Внутреннее ----------------

    private fun player(): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext).build()
        // Аудиофокус держим сами (см. заголовок класса): handleAudioFocus = false.
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false,
        )
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                // Плеер сам перешёл к следующей фразе: предыдущая доиграла.
                closePlaying("встык")
                announceStarted()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) announceStarted()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    closePlaying("список кончился")
                    if (!endedReported) {
                        endedReported = true
                        listener?.onAllEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val i = p.currentMediaItemIndex
                val item = items.getOrNull(i)
                Diag.log(ctx, "sound", "exo: не смог проиграть ${item?.file?.name ?: "?"}: ${error.message}")
                listener?.onError(item, error.message ?: "ошибка плеера")
            }
        })
        exo = p
        Diag.log(ctx, "sound", "exo: плеер поднят (Media3 ExoPlayer)")
        return p
    }

    /** Фраза, которая играла, закончилась: сообщаем хозяину один раз. */
    private fun closePlaying(why: String) {
        val i = playingIndex
        if (i < 0) return
        playingIndex = -1
        items.getOrNull(i)?.let { item ->
            Diag.log(ctx, "sound", "exo: фраза доиграла ($why): ${item.file.name} «${brief(item.text)}»")
            listener?.onPhraseEnd(i, item)
        }
    }

    /** Плеер начал играть фразу — сообщаем один раз на фразу. Зовём и по «звук
     *  пошёл», и по переходу: порядок этих сигналов у ExoPlayer не задан, а нам
     *  важен ровно один «начал» на каждую фразу. */
    private fun announceStarted() {
        val p = exo ?: return
        val i = p.currentMediaItemIndex
        if (i == playingIndex) return
        val item = items.getOrNull(i) ?: return
        playingIndex = i
        Diag.log(
            ctx, "sound",
            "exo: играю фразу ${i + 1}/${items.size} (${item.file.name}) «${brief(item.text)}»",
        )
        listener?.onPhraseStart(i, item)
    }

    private fun mediaItems(list: List<Item>): List<MediaItem> = list.map { item ->
        MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setUri(Uri.fromFile(item.file))
            .build()
    }
}
