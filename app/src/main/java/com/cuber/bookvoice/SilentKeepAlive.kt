package com.cuber.bookvoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Тихий поток на время чтения (msg4717/4721) — чтобы звуковой канал не закрывался.
 *
 *  Приём старый и живёт в читалках: приложение всё время чтения держит звуковой
 *  поток, которого не слышно. Даёт он две вещи разной природы:
 *  1. Звуковой тракт (в том числе Bluetooth) не закрывается, поэтому гарнитура не
 *     уходит в сон и не откусывает начало фразы, когда просыпается. Это механика,
 *     слышимая на слух — ровно то, что Сергей видел настройкой в чужой читалке
 *     (msg4717).
 *  2. Система всё это время видит активное воспроизведение, а не простаивающее
 *     приложение. Гипотеза против прошивочного сторожа энергии (MIUI/ColorOS):
 *     проверяется на телефоне, а не в коде.
 *
 *  Как сделано. MODE_STATIC: буфер записывается ОДИН раз и играет по кругу
 *  (setLoopPoints) — ни потока-писателя, ни работы процессора, только занятый
 *  звуковой тракт. В буфере не нули, а чередование ±1 (младший разряд 16-битного
 *  отсчёта, около −90 дБ): цифровая тишина на части гарнитур считается паузой, и
 *  они всё равно засыпают. Ухо ±1 не слышит.
 *
 *  Аудиофокус НЕ запрашиваем: поток ни на что не претендует и не глушит чужое.
 *  Цена — занятый тракт и поднятый Bluetooth-канал, поэтому галочка по умолчанию
 *  выключена (msg4721) и поток живёт только пока идёт чтение (см. [KeepAwake]).
 */
object SilentKeepAlive {

    private const val SAMPLE_RATE = 48_000

    /** Буфер в отсчётах: около 43 мс при 48 кГц (период узора 2 отсчёта делит его
     *  нацело — стык петли без щелчка). Мало — тракту достаточно. */
    private const val WANT_FRAMES = 2048

    private var track: AudioTrack? = null

    /** Включён ли поток сейчас — для лога и для [KeepAwake.syncSilence]. */
    val isOn: Boolean get() = track != null

    /** Запустить тихий поток. Повторный вызов безвреден.
     *
     *  Неудача не должна выглядеть как успех: если звуковой тракт построить не
     *  удалось, в журнале остаётся строка — иначе в отчёте «галочка стоит, а толку
     *  нет» не отличить отказ трека от засыпания гарнитуры. */
    fun start(c: Context) {
        if (track != null) return
        val res = runCatching { build() }
        val t = res.getOrNull()
        if (t == null) {
            Diag.log(c, "power", "тихий поток НЕ построился: ${res.exceptionOrNull()}")
            return
        }
        track = t
        Diag.log(c, "power", "тихий поток включён (звуковой канал не засыпает)")
    }

    fun stop(c: Context) {
        val t = track ?: return
        track = null
        runCatching {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.release()
        }
        Diag.log(c, "power", "тихий поток выключен")
    }

    private fun build(): AudioTrack {
        // Буфер не меньше системного минимума: на части устройств статический
        // трек короче минимума просто не запускается.
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val frames = maxOf(WANT_FRAMES, ((minBytes + 1) / 2)).let { if (it % 2 == 0) it else it + 1 }
        val buf = ShortArray(frames) { (if (it % 2 == 0) 1 else -1).toShort() }

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Тот же тракт, что у чтения: слышимая речь уходит и в динамик,
                    // и в гарнитуру — тихий поток должен идти туда же.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(buf, 0, buf.size)
        t.setLoopPoints(0, buf.size, -1) // бесконечная петля
        t.play()
        return t
    }
}
