package com.cuber.bookvoice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Короткие звуки-подсказки (msg2849). Вибрация (Vibra) чувствуется всегда,
 * но не говорит ЧТО случилось — «готово» и «ошибка» у неё различимы, а вот
 * «загрузилось» от «ещё грузится» на ощупь не отличить. Звуковой сигнал —
 * второй канал: играет в момент, когда книга после долгой загрузки реально
 * готова. На беззвучном (вибрация вместо звука) канал сам собой замолкает —
 * Vibra остаётся запасным.
 *
 * 0.4.83 (msg7006): сигналы играет [SoundPool], а не MediaPlayer. MediaPlayer
 * поднимает на каждый сигнал свой проигрыватель: это десятки миллисекунд, и
 * подсказка «книга готова» опаздывала — звучала уже после того, как владелец
 * начал слушать текст. SoundPool держит короткие сэмплы готовыми в памяти и
 * стартует сразу; его же просит список требований по доступности.
 *
 * Первый сигнал после запуска приложения может прозвучать с задержкой: сэмпл
 * ещё грузится. Такой звук не теряется — он играет по готовности (см. [play]),
 * поэтому «проглоченного» первого сигнала не бывает.
 */
object SoundFx {

    /** Одновременно больше двух сигналов не нужно: они короткие и не наложатся. */
    private const val MAX_STREAMS = 2

    private var pool: SoundPool? = null

    /** resId → номер сэмпла в пуле. */
    private val samples = HashMap<Int, Int>()

    /** Сэмплы, которые просили сыграть до того, как они догрузились. */
    private val waiting = HashSet<Int>()

    /** «Книга готова»: короткий мелодичный сигнал после долгой загрузки. */
    fun ready(ctx: Context) {
        play(ctx, R.raw.sound_ready)
    }

    /** «Говори»: сигнал голосового поиска (msg4755). Подавался ровно в тот
     *  момент, когда микрофон уже слушает, — по нему владелец и начинал
     *  говорить. Без него приходилось угадывать паузу и начало фразы терялось.
     *
     *  msg6404: сейчас НЕ зовётся. Свой микрофон мы включаем через
     *  `SpeechRecognizer`, а тот подаёт собственный сигнал готовности — вместе
     *  они звучали как два сигнала подряд. Оставлен на случай устройства, где
     *  системного сигнала не слышно: тогда это первое, что надо вернуть. */
    fun listen(ctx: Context) {
        play(ctx, R.raw.sound_listen)
    }

    /** «Таймер входит в последнюю минуту» (msg4979). Свой звук, а не «готово»:
     *  по нему владелец понимает, что сейчас надо тряхнуть телефон, и путать
     *  его с сигналом готовности книги нельзя. Два коротких тона — выше и ниже,
     *  слышно и в наушниках поверх чтения. */
    fun sleepWarning(ctx: Context) {
        play(ctx, R.raw.sound_sleep)
    }

    /** Пул один на приложение: сэмплы грузятся в память один раз. */
    private fun pool(ctx: Context): SoundPool {
        pool?.let { return it }
        val attrs = AudioAttributes.Builder()
            // Подсказка, а не музыка: систему просим играть её как служебный
            // сигнал, а не как воспроизведение.
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attrs)
            .build()
        p.setOnLoadCompleteListener { sp, sampleId, status ->
            if (status == 0 && waiting.remove(sampleId)) {
                runCatching { sp.play(sampleId, 1f, 1f, 1, 0, 1f) }
            }
        }
        pool = p
        return p
    }

    private fun play(ctx: Context, res: Int) {
        try {
            val app = ctx.applicationContext
            val p = pool(app)
            val id = samples.getOrPut(res) { p.load(app, res, 1) }
            if (id == 0) return
            // play() вернул 0 — сэмпл ещё не готов. Не бросаем его: сыграем по
            // готовности (слушатель выше), иначе первый сигнал после запуска
            // приложения пропадал бы молча.
            if (p.play(id, 1f, 1f, 1, 0, 1f) == 0) waiting.add(id)
        } catch (_: Exception) {
        }
    }
}
