package com.cuber.bookvoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

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
 *
 *  30.09.2026 (настройка по образцу @Voice Aloud Reader). Галочка переехала
 *  в «Чтение» → «Звук и стыки» отдельной строкой. Рядом строка «Громкость тихого
 *  потока» — на случай гарнитуры, которая и этот поток считает тишиной.
 *
 *  24.10.2026, ПРАВИЛО СЕРЖА: «пускай по умолчанию будет выключена». Прежде поток
 *  поднимался САМ, как только звук начинал произносить движок (и в альтернативном
 *  способе, и когда сетевой движок не отдаёт файлы). Теперь — только галочкой;
 *  автоматика осталась строкой в журнале ([KeepAwake.noteDirectNeedsSilence]),
 *  потому что без потока кнопки на наушниках могут уйти чужому плееру.
 *
 *  26.10.2026, ПОПРАВКА К ПРАВИЛУ (Серж: «давай»). В голосовом чтении поток снова
 *  встаёт САМ и подновляется после каждой фразы (см. блок «ПЕРВОЕ МЕСТО В ОЧЕРЕДИ
 *  МЕДИА-КЛАВИШ»): иначе волшебное касание скринридера не доходит до читалки —
 *  медиа-клавишу система отдаёт движку, который играет звук. Без своего звука
 *  этого не исправить никак. Галочка осталась: она включает поток и в других
 *  случаях и задаёт уровень.
 */
object SilentKeepAlive {

    private const val SAMPLE_RATE = 48_000

    /** Буфер в отсчётах: около 43 мс при 48 кГц (период узора 2 отсчёта делит его
     *  нацело — стык петли без щелчка). Мало — тракту достаточно. */
    private const val WANT_FRAMES = 2048

    /** Уровни тихого звука — амплитуда в отсчётах. Первый (по умолчанию) — младший
     *  разряд 16-битного отсчёта, около −90 дБ: ухо не слышит, но и цифровой
     *  тишиной поток не считается. Выше — только для гарнитур, которые и такой
     *  поток принимают за тишину и всё равно засыпают (30.09.2026, настройка
     *  «Громкость тихого потока»: у @Voice Aloud Reader такая строка есть давно).
     *  Частота узора при любой амплитуде одна и та же (24 кГц), поэтому слышимого
     *  тона не появляется; на верхних уровнях в наушниках возможен еле слышный шум. */
    private val LEVELS = intArrayOf(1, 8, 32, 128, 512)

    private var track: AudioTrack? = null

    /** Включён ли поток сейчас — для лога и для [KeepAwake.syncSilence]. */
    val isOn: Boolean get() = track != null

    /** Уровень из настроек: 1..5 (см. [LEVELS]). Читаем сами prefs — экран настроек
     *  пишет туда же, а движку об этой настройке знать незачем. */
    private fun level(c: Context): Int = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_SILENT_LEVEL, MainActivity.SILENT_LEVEL_DEFAULT)
    }.getOrDefault(MainActivity.SILENT_LEVEL_DEFAULT).coerceIn(1, LEVELS.size)

    /** Запустить тихий поток. Повторный вызов безвреден.
     *
     *  Неудача не должна выглядеть как успех: если звуковой тракт построить не
     *  удалось, в журнале остаётся строка — иначе в отчёте «галочка стоит, а толку
     *  нет» не отличить отказ трека от засыпания гарнитуры. */
    fun start(c: Context) {
        if (track != null) return
        appCtx = c.applicationContext
        val lvl = level(c)
        val amp = LEVELS[lvl - 1]
        val res = runCatching { build(amp) }
        val t = res.getOrNull()
        if (t == null) {
            Diag.log(c, "power", "тихий поток НЕ построился: ${res.exceptionOrNull()}")
            return
        }
        track = t
        Diag.log(c, "power", "тихий поток включён (уровень $lvl, амплитуда $amp): звуковой канал не засыпает")
    }

    /** Уровень поменяли на ходу (строка «Громкость тихого потока»): поток уже
     *  играет — пересобираем его с новой амплитудой. Чтение при этом не трогаем. */
    fun levelChanged(c: Context) {
        if (track == null) {
            Diag.log(c, "power", "уровень тихого потока запомнен (уровень ${level(c)}), поток сейчас не идёт")
            return
        }
        stop(c)
        start(c)
    }

    fun stop(c: Context) {
        main.removeCallbacks(nudgeTask)
        nudgePending = false
        nudges = 0
        val t = track ?: return
        track = null
        runCatching {
            if (t.playState != AudioTrack.PLAYSTATE_STOPPED) t.stop()
            t.release()
        }
        Diag.log(c, "power", "тихий поток выключен")
    }

    // ---------------- ПЕРВОЕ МЕСТО В ОЧЕРЕДИ МЕДИА-КЛАВИШ (26.10.2026) ----------------
    //
    // Что выяснено на телефоне. Волшебное касание скринридера (два пальца, два
    // касания) посылает системе обычную медиа-клавишу play/pause, а система отдаёт
    // её не окну, а тому, кто ИГРАЛ ЗВУК ПОСЛЕДНИМ (MediaSessionStack:
    // «We send the media button events to the lastly played app»). В голосовом
    // способе звук даёт движок, и клавиша уходит ЕМУ — в dumpsys media_session так
    // и было: «Media button session is ru.tolyos.teratts/SupertonicMediaSession/3»,
    // и касание до читалки не доходило вовсе.
    //
    // Всплеск на 120 мс (24.10.2026) не спасал: первое место система отдаёт тому,
    // кто начал играть ПОЗЖЕ, а движок начинает новую фразу каждые несколько
    // секунд и каждый раз отбирал место обратно. Журнал Сержа 26.10.2026: поток
    // включён в 00:52:01, касание сработало в 00:52:07 (мы были первыми), а дальше
    // каждая новая фраза движка возвращала первое место ему.
    //
    // Поэтому теперь в голосовом чтении поток держится САМ и после начала каждой
    // фразы ПОДНОВЛЯЕТСЯ: короткая пауза и снова играть. Для системы это новое
    // начало звука, то есть мы опять «играли последними». Тракт при этом занят всё
    // чтение и Bluetooth не засыпает — это и есть та цена, на которую Серж сказал
    // «давай».

    /** Задержка подновления после начала фразы. Движок стартует первым, мы
     *  подновляемся следом — иначе система видит последним снова его. */
    private const val NUDGE_MS = 350L

    private val main = Handler(Looper.getMainLooper())

    /** Звук в этом чтении даёт движок — поток держим сами (см. блок выше). */
    @Volatile private var voiceHold = false

    /** Контекст для строк журнала: подновления идут по таймеру, без аргументов. */
    private var appCtx: Context? = null

    private var nudgePending = false
    private var nudges = 0

    /** Держится ли поток ради медиа-клавиш (а не из-за галочки). */
    fun isVoiceHold(): Boolean = voiceHold

    /** Голосовое чтение: движок начал фразу. Держим поток и подновляем первое место.
     *  Зовётся из [SpeechPlayer.directSpeechStarted] — только в прямом способе
     *  (в файловом звук играет наш плеер, там ничего не нужно). */
    fun directPhraseStarted(c: Context) {
        voiceHold = true
        appCtx = c.applicationContext
        if (track == null) start(c)
        scheduleNudge()
    }

    /** Чтение встало: занимать тракт больше не для чего. */
    fun forgetVoiceHold() {
        voiceHold = false
    }

    private fun scheduleNudge() {
        if (nudgePending) return
        nudgePending = true
        main.postDelayed(nudgeTask, NUDGE_MS)
    }

    private val nudgeTask = object : Runnable {
        override fun run() {
            nudgePending = false
            reassert()
        }
    }

    /** «Я играю заново»: пауза и снова играть на том же треке. Ни файлов, ни новых
     *  объектов — только переключение состояния, поэтому стоит почти ничего. */
    private fun reassert() {
        val t = track ?: return
        val res = runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
            t.play()
        }
        nudges++
        if (nudges <= 3 || nudges % 25 == 0) {
            val text = if (res.isSuccess)
                "тихий поток подновлён ($nudges-й раз за чтение): первое место за нами"
            else
                "тихий поток НЕ подновился: ${res.exceptionOrNull()}"
            appCtx?.let { Diag.log(it, "power", text) }
        }
    }

    private fun build(amp: Int): AudioTrack {
        // Буфер не меньше системного минимума: на части устройств статический
        // трек короче минимума просто не запускается.
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val frames = maxOf(WANT_FRAMES, ((minBytes + 1) / 2)).let { if (it % 2 == 0) it else it + 1 }
        val buf = ShortArray(frames) { (if (it % 2 == 0) amp else -amp).toShort() }

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
