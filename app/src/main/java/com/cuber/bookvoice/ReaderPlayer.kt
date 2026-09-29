package com.cuber.bookvoice

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * «Теневой» плеер для медиа-сессии Media3 (29.09.2026, часть 5 переезда).
 *
 * Звук у нас играет не плеер, а движок озвучки: он синтезирует фразы и отдаёт
 * готовые файлы в [FilePlayback]. Поэтому Media3-сессию нельзя привязать к
 * ExoPlayer: система решила бы, что «играет» — это когда звучит файл, а между
 * фразами (движок ещё собирает следующую) состояние мигало бы «пауза», и
 * медиа-кнопка в этот момент превращалась бы в «играть» вместо «паузы».
 *
 * Этот класс — переводчик: для системы он выглядит плеером, а на самом деле
 * только показывает состояние движка ([isPlaying], название книги) и передаёт
 * команды ему же. Ничего не проигрывает и позиции не считает: место в книге
 * система не показывает — оно у нас во второй строке своего уведомления.
 *
 * Команды приходят сюда от сессии (кнопки в системной карточке, гарнитура,
 * «волшебное касание»). Кнопку-переключатель сессия разбирает сама, в своём
 * колбэке, — по той же причине, что и раньше: система решает play/pause по
 * опубликованному состоянию, а оно может отстать.
 */
class ReaderPlayer(
    looper: Looper,
    private val titleOf: () -> String?,
    private val playingOf: () -> Boolean,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkip: (Int) -> Unit,
    private val log: (String) -> Unit,
) : SimpleBasePlayer(looper) {

    /** Книга, к которой ведёт касание карточки; id элемента — её uri. */
    private var bookId: String = DEFAULT_ID

    /** Состояние менялось: движок открыл книгу или встал на паузу. */
    fun refresh() = invalidateState()

    fun setBook(uri: String?) {
        val id = uri ?: DEFAULT_ID
        if (id == bookId) return
        bookId = id
        invalidateState()
    }

    override fun getState(): State {
        val meta = MediaMetadata.Builder()
            .setTitle(titleOf() ?: "BookVoice")
            .setArtist("BookVoice")
            .build()
        // Один элемент-«книга»: он нужен только ради названия в системной
        // карточке. Длительность неизвестна (читаем вслух и без конца) — C.TIME_UNSET
        // велит системе не показывать полосу и время.
        val item = MediaItemData.Builder(bookId)
            .setMediaMetadata(meta)
            .setDurationUs(C.TIME_UNSET)
            .setIsSeekable(false)
            .build()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0)
            .setPlaybackState(Player.STATE_READY)
            // Причина «по просьбе пользователя»: без неё система считает, что
            // воспроизведение подавлено (например, из-за потери аудиофокуса), и
            // карточка на паузе выглядит иначе, чем у обычного плеера.
            .setPlayWhenReady(playingOf(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        log("media3: команда сессии " + if (playWhenReady) "«играть»" else "«пауза»")
        if (playWhenReady) onPlay() else onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> onSkip(1)

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> onSkip(-1)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val DEFAULT_ID = "bookvoice:reading"
    }
}
