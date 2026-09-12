package com.cuber.bookvoice

import android.content.Context
import android.media.MediaPlayer

/**
 * Короткие звуки-подсказки (msg2849). Вибрация (Vibra) чувствуется всегда,
 * но не говорит ЧТО случилось — «готово» и «ошибка» у неё различимы, а вот
 * «загрузилось» от «ещё грузится» на ощупь не отличить. Звуковой сигнал —
 * второй канал: играет в момент, когда книга после долгой загрузки реально
 * готова. На беззвучном (вибрация вместо звука) канал сам собой замолкает —
 * Vibra остаётся запасным.
 */
object SoundFx {
    /** «Книга готова»: короткий мелодичный сигнал после долгой загрузки. */
    fun ready(ctx: Context) {
        play(ctx, R.raw.sound_ready)
    }

    /** «Говори»: сигнал голосового поиска (msg4755). Подаётся ровно в тот
     *  момент, когда микрофон уже слушает, — по нему владелец и начинает
     *  говорить. Без него приходилось угадывать паузу и начало фразы терялось. */
    fun listen(ctx: Context) {
        play(ctx, R.raw.sound_listen)
    }

    private fun play(ctx: Context, res: Int) {
        try {
            val mp = MediaPlayer.create(ctx, res) ?: return
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        } catch (_: Exception) {
        }
    }
}
