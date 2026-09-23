package com.cuber.bookvoice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File

/**
 * Плеер озвучки, в котором звук играет САМО приложение, а не движок синтеза.
 *
 * Зачем. Пока голос произносил движок TextToSpeech напрямую, аудио числилось
 * за процессом движка — Android не считал BookVoice «плеером» и не отдавал
 * ему медиа-кнопки (гарнитура, «волшебное касание»): они уходили последнему
 * настоящему плееру (YouTube/Nekogram). Лог диагностики это подтвердил:
 * кнопка не доходила до приложения ни разу.
 *
 * Как теперь. Движок TTS только СИНТЕЗИРУЕТ предложение в wav-файл, а файл
 * проигрывает наш MediaPlayer. Раз звук физически играет из нашего процесса,
 * система видит BookVoice как настоящий плеер и отдаёт кнопки нам.
 *
 * Чтобы между предложениями не было пауз на синтез, фразы готовятся заранее:
 * пока играет текущая, читалка подсказывает через [onNeedNext] текст следующей,
 * и мы синтезируем её в файл. С msg4472 впрок держится не одна фраза, а
 * очередь из [prefetchDepth]: у тестеров на Poco и realme движок синтеза
 * замирал на 12–15 с (экран заблокирован), и с одной заготовкой это слышалось
 * как пауза, а с тремя — проходит незаметно.
 */
class SpeechPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var engineReadyCallback: ((Boolean) -> Unit)? = null

    /** Пакет текущего движка синтеза (null — системный по умолчанию). */
    var enginePackage: String? = null
        private set

    var speed: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            tts?.setSpeechRate(value)
            // 24.09.2026: заготовки сделаны прежней скоростью, и играть их
            // прежним темпом нельзя (см. [Prepared]). Выбрасываем сразу, а не
            // по одной на подходе: иначе три устаревшие фразы подряд собирались
            // бы заново без упреждения и чтение спотыкалось бы на каждой.
            dropStaleRate()
        }

    /** Высота голоса (тон). Норма = 1.0. Применяется к следующему синтезу. */
    var pitch: Float = 1f
        set(value) {
            field = value
            tts?.setPitch(value)
        }

    /** Короткие паузы между предложениями (msg4402, просьба тестера
     *  @Spartach72). Мы озвучиваем каждое предложение отдельным файлом, и по
     *  краям движок оставляет тишину — в чтении по предложениям она слышна как
     *  пауза; здесь обрезаем эту тишину, оставляя короткие хвосты.
     *  msg4416: вторая половина той же правки живёт в ReaderEngine — соседние
     *  короткие предложения уходят в движок одной фразой. Обрезка работает не
     *  на всех движках (у DariyaNeural тишины по краям нет вовсе), но файл не
     *  портит, поэтому не выключается — галочку убрали (msg5254).
     *  msg5254: галочка «Короткие паузы между предложениями» убрана из настроек
     *  Сергеем — склейка и обрезка работают всегда, как было по умолчанию. */
    /** Бесшовная передача звука встык (msg4598, ТЕСТОВАЯ настройка).
     *
     *  Зачем. Замер по журналу Сергея (msg4594): между «фраза доиграла» и
     *  «играю свой звук» следующей фразы проходит 86–177 мс, к ним добавляется
     *  ~190 мс запаздывания самого MediaPlayer (длительность фразы ровно на
     *  столько больше, чем размер файла / 48 000 байт/с) — на слух это пауза
     *  около 0,3 с на КАЖДОМ стыке предложений.
     *
     *  Что делает. Пока звучит текущая фраза, следующая готовая заготовка
     *  прицепляется к играющему плееру платформенным
     *  [MediaPlayer.setNextMediaPlayer]: систему не нужно просить «начни
     *  следующий файл» в момент окончания — она сама продолжает звук тем же
     *  аудиотрактом. Наш перезапуск и его задержки из стыка уходят.
     *
     *  По умолчанию ВЫКЛ: пока это тестовая настройка, обычное поведение
     *  остаётся прежним, и Сергей сравнивает оба варианта на слух одной
     *  галочкой. */
    var gapless: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // msg4611: галочку снимают на ходу, а заготовка уже прицеплена к
            // играющему плееру. Если оставить её, платформа начнёт звук сама на
            // ближайшем стыке, а старый путь ту же фразу посчитает ещё не
            // сыгранной и запустит второй раз. Отпускаем прицепку заранее.
            if (!value) unhookChain()
        }

    /** #57: альтернативный способ озвучки — фразу целиком отдаём движку
     *  ([fallbackDirect] как основной путь, проба для сравнения на слух,
     *  msg5401/5409). Обычно у нас наоборот: движок только готовит файл, а
     *  играет наш плеер.
     *
     *  Что при этом выключается: наши заготовки ([requestPrefetch]), передача
     *  встык ([gapless]) и свой MediaPlayer — их работу движок делает сам.
     *  Сторож молчания продолжает работать, но своей веткой ([armDirectWatch]):
     *  он надзирает за прямой речью, а не за синтезом в файл, поэтому лестница
     *  мер «переспрос → перезапуск движка» здесь не взводится.
     *
     *  По умолчанию ВЫКЛ — настройка тестовая, обычное чтение не меняется. */
    var altDirect: Boolean = false

    /** Текст фразы, которая звучит прямо сейчас. */
    private var playingText: String? = null

    /** Файл звучащей фразы и скорость, которой он сделан. Нужны паузе: файл при
     *  паузе никуда не удаляется, и продолжение можно начать с него же, не
     *  заставляя движок синтезировать ту же фразу второй раз (см.
     *  [pauseKeepingPhrase]). */
    private var playingFile: File? = null
    private var playingRate: Float = 1f

    /** Следующая фраза, прицепленная к играющему плееру встык (msg4598). */
    private var chainedPlayer: MediaPlayer? = null
    private var chainedText: String? = null
    private var chainedFile: File? = null

    /** Скорость, которой сделан прицепленный файл: если её успели сменить,
     *  возвращать заготовку в очередь нельзя (см. [unhookChain]). */
    private var chainedRate: Float = 1f

    /** Прицепка готовится: файл отдан MediaPlayer, ответа ещё нет. В счёт
     *  заготовок входит наравне с готовой прицепкой (см. [requestPrefetch]) —
     *  иначе на время подготовки очередь заказала бы ту же фразу второй раз. */
    private var chainInFlight = false

    /** Поколение прицепок: запоздавший ответ MediaPlayer после stop() не должен
     *  ничего прицеплять к уже отпущенному плееру. */
    private var chainSeq = 0L

    /** Текст, который вот-вот попросит движок после перехода встык (msg4598).
     *  Отличает «движок сдвинулся, фраза уже звучит» от жеста «повторить»:
     *  повтор приходит тем же путём (startSpeakingCurrent → speak), но это
     *  осознанная просьба сыграть фразу заново. */
    private var gaplessHopText: String? = null

    /** Сколько раз за сессию журналируем пустой звук от движка (не спамить). */
    private var emptySoundLogged = 0

    /** Сколько тишины оставлять на стыке фраз, мс (msg5604).
     *
     *  Обрезка ([trimSilence]) снимает тишину, которую движок дописал по краям
     *  файла: у сетевых голосов Google её 518–680 мс на фразу, и без обрезки
     *  чтение шло «с паузами». Раньше остаток был прибит гвоздями (50 мс в
     *  голове + 83 мс в хвосте) — теперь его задаёт строка «Пауза между
     *  фразами» в настройках: значение делится между хвостом текущей фразы и
     *  головой следующей. 0 — срезать всю тишину, что дописал движок.
     *
     *  Чего настройка НЕ может: если движок паузу не молчанием, а интонацией
     *  (тянет последний слог) — тишины по краям нет вовсе, обрезка отступает
     *  (trimBail 9), и значение ничего не меняет. */
    var pauseKeepMs: Int = 130

    /** Громкость собственного звука (0..1, 1 = полная, как у системы).
     *  Умножается на системную громкость медиа. Применяется сразу к играющему
     *  предложению и к каждому следующему при старте. */
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            runCatching { media?.setVolume(field, field) }
        }

    private var selectedVoiceName: String? = null

    /** Вызывается на главном потоке, когда предложение дочитано до конца. */
    var onDone: (() -> Unit)? = null

    /** Возвращает текст фразы, которая прозвучит через [offset] фраз вперёд
     *  (1 — следующая), или null, если книга кончилась. Зовётся на главном
     *  потоке, без побочных эффектов.
     *
     *  msg4472: параметр появился вместе с очередью заготовок. Раньше мы
     *  синтезировали ровно одну фразу вперёд, и каждая заминка движка (в логе
     *  тестера — 12–15 с раз в минуту-полторы, экран заблокирован) слышалась
     *  как пауза: играть было нечего. Теперь держим [PREFETCH_DEPTH] фраз
     *  готовыми, и заминка проходит незаметно — звук идёт из заготовок. */
    var onNeedNext: ((Int) -> String?)? = null

    val isReady: Boolean get() = ready

    val voices: List<Voice>
        get() = tts?.voices?.toList() ?: emptyList()

    /** Пакет системного движка по умолчанию (может быть null до инициализации). */
    val defaultEngine: String?
        get() = tts?.defaultEngine

    /** Все установленные движки: пары (пакет, название). */
    val engines: List<Pair<String, String>>
        get() {
            val merged = LinkedHashMap<String, String>()
            for ((pkg, label) in queryEngines(appContext)) merged.putIfAbsent(pkg, label)
            tts?.engines?.forEach { e ->
                val name = e.name ?: return@forEach
                val label = e.label?.takeIf { it.isNotBlank() } ?: name
                merged.putIfAbsent(name, label)
            }
            return merged.toList().sortedBy { it.second.lowercase() }
        }

    // ---------- Пайплайн «синтез в файл → играет MediaPlayer» ----------

    private val synthDir = File(appContext.cacheDir, "tts_speech").apply {
        if (!exists()) mkdirs()
    }
    private val audioSessionId =
        (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var media: MediaPlayer? = null
    /** Счётчик поколений проигрывания: защита от запоздавших callback'ов
     *  MediaPlayer после stop()/release(). */
    private var playGen = 0L

    /** Последний текст, который попросили говорить. */
    private var currentText: String? = null

    /** Текст, чей синтез ещё идёт, а по завершении его надо СРАЗУ играть
     *  (speak() попросил, готового файла не было). */
    private var awaitingPlayText: String? = null

    /** Текст, который синтезируется впрок (пока играет другое предложение). */
    private var prefetchInFlightText: String? = null

    /** Заранее подготовленная фраза (см. [prewarm]): она одна и только она —
     *  очередь впрок, пока чтение не идёт, не растёт. */
    private var prewarmOnly = false

    /** Фраза, сохранённая паузой для мгновенного продолжения: файл уже готов и
     *  движку не нужно синтезировать её второй раз (см. [pauseKeepingPhrase]). */
    private var keptPhrase: Prepared? = null

    /** Очередь уже синтезированных фраз — готовы к мгновенному старту, в том
     *  порядке, в каком прозвучат (msg4472). Раньше здесь лежала ровно одна
     *  заготовка: её хватало на паузу между фразами, но не на заминку движка,
     *  которая длится дольше самой фразы.
     *
     *  [rate] — скорость, которой сделан файл (24.09.2026). Раньше сверять её
     *  было негде, и после смены скорости до трёх фраз доигрывали прежним
     *  темпом; теперь такая заготовка выбрасывается, а не звучит. */
    private data class Prepared(val text: String, val file: File, val rate: Float)

    private val readyQueue = ArrayDeque<Prepared>()

    /** Сколько фраз держим готовыми. Три — это 8–15 с звука при фразе в 4–6 с,
     *  то есть ровно длина заминок из лога тестера (12–15 с). Больше не нужно:
     *  растёт только задержка реакции на прыжок по тексту. */
    private val prefetchDepth = 3

    private var reqSeq = 0L
    private val pending = HashMap<Long, Pending>()

    /** Очередь фраз, ОТДАННЫХ движку в альтернативном способе (прямая речь),
     *  в порядке звучания; здесь только заявки вида [Pending.Kind.DIRECT].
     *
     *  msg (23.09.2026, просьба Сергея: «если синтезатору посылаешь предложение
     *  за предложением, может, читает всё без пауз»). Раньше в этом режиме мы
     *  отдавали движку ровно одну фразу и ждали «закончил» — на стыке слышалась
     *  пауза, а если движок молчал, мы считали срок по длине фразы. Теперь
     *  держим у движка впереди [prefetchDepth] фраз: он берёт их подряд, пауз
     *  нет, а срок нужен только один — на всю очередь (см. [armDirectWatch]).
     *
     *  Место в книге от очереди не страдает: каждая фраза отдаётся со своим
     *  номером, движок сообщает «закончил» по каждой отдельно, и закладку
     *  читалки двигает тот же [onDone], что и раньше. */
    private val directQueue = ArrayDeque<Long>()

    // ---------- Сторож молчания (msg4077) ----------

    /** Движок или плеер могут не отозваться ВООБЩЕ: в логе пользователя
     *  (RMX3472, Android 14, v0.4.24) чтение встало на «старт чтения (предл. 131)»
     *  и дальше ни звука, ни ошибки — ни `onDone`, ни `onError`. Ждать вечно
     *  нельзя: читалка замолкает насовсем. Токены гасят только свой сторож —
     *  событие на одном этапе не отменяет надзор за другим. */
    private var synthWatchToken = 0L
    private var playWatchToken = 0L
    private var directWatchToken = 0L

    /** Перезапусков движка подряд из-за молчания (сбрасывается удачной речью). */
    private var retriesInRow = 0

    /** Текст, который надо переспросить, когда движок поднимется после перезапуска. */
    private var retryAfterRestart: String? = null

    /** Текст, заявку на который движок уже потерял один раз (msg4103). Первый обрыв
     *  лечим повторной заявкой без перезапуска движка; второй подряд — перезапуском.
     *  Сбрасывается доспевшим синтезом и перезапуском движка (start/resetPipeline).
     *  msg5309: пауза его НЕ сбрасывает — иначе лестница мер начиналась бы заново
     *  на каждом «продолжить», и до перезапуска движка дело бы не доходило. */
    private var softRetriedText: String? = null

    /** [rate] — скорость, с которой этот файл ЗАКАЗАН у движка (msg5979). Держим
     *  её при заявке, а не читаем текущую при завершении: смена скорости не
     *  выбрасывает уже готовые заготовки, и фраза из очереди могла быть
     *  синтезирована на прежней. */
    private class Pending(val kind: Kind, val text: String, val file: File?, val rate: Float = 1f) {
        enum class Kind { FILE, DIRECT }
        var cancelled = false
    }

    private val initListener = TextToSpeech.OnInitListener { status ->
        main.post {
            val ok = status == TextToSpeech.SUCCESS && tts != null
            ready = ok
            if (ok) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    // msg1818: засечка фактического старта произнесения движком —
                    // для сопоставления с фразой TalkBack «управление мультимедиа».
                    // 23.09.2026: этим же сигналом пользуется надзор за прямой
                    // речью — до него срок ждём один, после него считаем по длине
                    // фразы (см. directSpeechStarted).
                    override fun onStart(utteranceId: String?) {
                        Diag.log(appContext, "tts", "onStart: движок начал речь ($utteranceId)")
                        val id = utteranceId?.toLongOrNull()
                        main.post { directSpeechStarted(id) }
                    }

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId?.toLongOrNull() ?: return
                        main.post { synthFinished(id) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val id = utteranceId?.toLongOrNull() ?: return
                        main.post { synthFailed(id, -1) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val id = utteranceId?.toLongOrNull() ?: return
                        main.post { synthFailed(id, errorCode) }
                    }
                })
                // msg3550: список голосов движок отдаёт не в момент init, а
                // позже — выбранный голос (в т.ч. свой у книги) в этот момент
                // поставить нечем. Публичного уведомления «список готов» в SDK
                // нет (сборка 0.4.25: setOnVoicesChangedListener не резолвится),
                // поэтому спрашиваем сами несколько раз.
                applySpeedAndVoice()
                if (tts?.voices.isNullOrEmpty()) waitForVoices(voiceWaitSteps.size)
                // Сторож (msg4077): движок перезапущен после молчания — переспросить
                // фразу, которую он не досказал. Скорость и голос уже применены выше.
                // Состояние «эту фразу ждут» восстанавливаем руками: start() его
                // обнулил (resetPipeline), а synthFinished играет только то, что ждут.
                retryAfterRestart?.let { t ->
                    retryAfterRestart = null
                    currentText = t
                    awaitingPlayText = t
                    synthToFile(t)
                }
            } else {
                retryAfterRestart?.let {
                    retryAfterRestart = null
                    Diag.log(appContext, "tts", "движок не поднялся после перезапуска (status=$status)")
                }
            }
            engineReadyCallback?.invoke(ok)
            engineReadyCallback = null
        }
    }

    init {
        start(null)
        // Старые временные файлы от прошлых запусков — почистим при старте.
        runCatching { synthDir.listFiles()?.forEach { it.delete() } }
    }

    /** Переключиться на движок [pkg] (null — системный по умолчанию). */
    fun setEngine(pkg: String?, onResult: ((Boolean) -> Unit)? = null) {
        if (pkg == enginePackage && ready) {
            onResult?.invoke(true)
            return
        }
        engineReadyCallback = onResult
        start(pkg)
    }

    private fun start(pkg: String?) {
        enginePackage = pkg
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        pending.clear()
        resetPipeline()
        tts = runCatching {
            if (pkg != null) TextToSpeech(appContext, initListener, pkg)
            else TextToSpeech(appContext, initListener)
        }.getOrNull()
    }

    /** Начать озвучивать одно предложение. Вызывается с главного потока. */
    fun speak(text: String) {
        val t = tts
        if (t == null || !ready || text.isBlank()) return
        currentText = text
        // Читалка просит фразу — упреждающая подготовка ([prewarm]) своё дело
        // сделала, дальше очередь наполняется обычным порядком.
        prewarmOnly = false

        // #57: альтернативный способ — наш конвейер не участвует вовсе.
        // Фразу целиком отдаём движку (он и синтезирует, и играет), поэтому
        // здесь же отпускаем всё своё: играющий плеер, прицепку встык и
        // заготовки. Ветка стоит до логики встыка намеренно: хоп «движок
        // сдвинулся, звук уже играет» — это про наш MediaPlayer, которого в
        // этом режиме нет.
        if (altDirect) {
            releaseMedia()
            clearPrefetch()
            prefetchInFlightText = null
            awaitingPlayText = null
            Diag.log(
                appContext, "sound",
                "альтернативный способ: отдаю фразу движку (${text.length} знаков)"
            )
            speakDirectQueued(text)
            return
        }

        // Переход встык (msg4598): движок сдвинулся на следующую фразу, а звук
        // её уже играет — его начал не мы, а платформа в момент окончания
        // предыдущей (см. onPhraseCompleted). Здесь нельзя ни releaseMedia()
        // (оборвал бы звук на середине), ни playFile (сыграл бы фразу второй раз).
        // Метка ставится ровно перед вызовом onDone, поэтому под неё попадает
        // только продолжение движка, а не жест «повторить предложение».
        // Проверку на играющий media здесь не ставим: фраза могла оказаться
        // пустой (движок изредка отдаёт файл из одного заголовка) и доиграть
        // раньше, чем движок попросит её же, — тогда без метки мы синтезировали
        // бы её второй раз и услышали повтор.
        val hop = gaplessHopText
        if (gapless && hop != null && text == hop) {
            gaplessHopText = null
            requestPrefetch() // место в очереди освободилось — просим следующую
            return
        }
        if (gapless && hop != null) {
            // msg4643: движок попросил фразу, а прицеплена была другая — так
            // выглядит рассинхрон заготовки. Сторож в [chainNext] такое не
            // пропускает, поэтому строка редкая и её видно в журнале: по ней
            // понятно, что сверка не сработала и фразу пришлось играть заново.
            Diag.log(
                appContext, "sound",
                "встык: фраза не совпала с прицепленной (${hop.length} → ${text.length} знаков) — играю заново"
            )
        }
        gaplessHopText = null
        releaseMedia()

        // Пауза сохранила звук ровно этой фразы — продолжаем с него, синтез не
        // нужен (см. [pauseKeepingPhrase]). Скорость сверяем: пауза могла
        // пережить её смену.
        takeKeptPhrase(text)?.let { kept ->
            playFile(kept.file, kept.rate)
            return
        }

        // Готовая заранее заготовка этой же фразы — играем без задержки.
        // ВАЖНО: забираем файл из очереди, НЕ вызывая clearPrefetch() — она
        // удаляет файлы, и playFile падает с ENOENT (каждая вторая фраза уходила
        // в запасной путь движкового произнесения). Файл удалит сам playFile по
        // завершении. Очередь читаем с головы: заготовки копятся в том порядке,
        // в каком прозвучат, и голова — как раз следующая фраза.
        val head = readyQueue.firstOrNull()
        if (head != null && head.text == text) {
            if (head.rate == speed) {
                readyQueue.removeFirst()
                awaitingPlayText = null
                playFile(head.file, head.rate)
                return
            }
            // Заготовка сделана до смены скорости. Играть её прежним темпом
            // нельзя — выбрасываем и собираем фразу заново текущей скоростью.
            Diag.log(
                appContext, "sound",
                "заготовка сделана на скорости ${RateSteps.label(head.rate)}, " +
                    "сейчас ${RateSteps.label(speed)} — синтезирую фразу заново"
            )
            readyQueue.removeFirst()
            head.file.delete()
        }

        // Синтез этого текста уже идёт впрок — просто ждём его и сыграем.
        if (prefetchInFlightText == text) {
            awaitingPlayText = text
            return
        }

        // Старые заготовки не совпадают — выбрасываем и синтезируем заново.
        clearPrefetch()
        prefetchInFlightText = null
        awaitingPlayText = text
        synthToFile(text)
    }

    /** Остановить озвучку: тишина сразу, onDone не вызывается. */
    fun stop() {
        currentText = null
        awaitingPlayText = null
        prewarmOnly = false
        // Сохранённую паузой фразу здесь не трогаем: её ставит
        // [pauseKeepingPhrase] уже ПОСЛЕ остановки. Настоящий стоп (прыжок,
        // закрытие книги) её выбрасывает — продолжать с неё больше нечего.
        keptPhrase?.let { runCatching { it.file.delete() } }
        keptPhrase = null
        // msg4103: заявка, которая была «в полёте», здесь же и отменяется —
        // tts.stop() её гасит, а pending ниже чистится. Метку «синтез идёт»
        // снимаем вместе с ней: иначе speak() того же предложения (пауза →
        // продолжить) ждал бы ответа по заявке, которой уже нет, и чтение
        // замолкало бы навсегда. Именно это и видно в логе пользователя
        // (ivona.tts): pause → «старт чтения (предл. 145)» → тишина.
        prefetchInFlightText = null
        // msg5309: а вот след лестницы мер (softRetriedText) пауза НЕ стирает.
        // Раньше стирала — и у тестера Ивана это съедало всю эскалацию: он жмёт
        // паузу через 5–12 с, сторож не успевает досчитать, а после продолжения
        // фраза получала чистый лист и снова только переспрос, до перезапуска
        // движка дело не доходило ни разу. Теперь фразу, которую движок уже
        // терял, после продолжения стерегут коротким запалом и до перезапуска
        // доходят сразу. Чужой фразе след не мешает: сравнение идёт по тексту,
        // а снимают его доспевший синтез и перезапуск движка (resetPipeline).
        // Сторож молчания (msg4077): тишина попрошена — надзор снимаем, чтобы
        // он не «оживил» чтение, которое владелец только что остановил.
        synthWatchToken++
        playWatchToken++
        directWatchToken++
        retryAfterRestart = null
        clearPrefetch()
        releaseMedia()
        // Останавливаем и сам движок: иначе запасное прямое произнесение
        // (fallbackDirect) пауза не прерывает — фраза «дочитывалась» до конца,
        // а следующая глава накладывалась на её хвост.
        runCatching { tts?.stop() }
        // Незавершённые синтезы больше никому не нужны: отменяем и удаляем их
        // файлы, чтобы после tts.stop() они не осиротели в pending.
        pending.values.forEach { it.cancelled = true; it.file?.delete() }
        pending.clear()
        directQueue.clear()
    }

    fun selectVoice(name: String) {
        selectedVoiceName = name
        applySpeedAndVoice()
    }

    private fun applySpeedAndVoice() {
        val t = tts ?: return
        if (!ready) return
        t.setSpeechRate(speed)
        t.setPitch(pitch)
        val name = selectedVoiceName
        if (name != null) {
            val v = t.voices?.firstOrNull { it.name == name }
            if (v != null) t.voice = v
        }
    }

    /** Задержки повторных вопросов «а голоса уже приехали?» после init (msg3550).
     *  Каждая следующая длиннее: первый ответ чаще всего приходит почти сразу,
     *  а медленный движок отзывается через секунду-полторы. */
    private val voiceWaitSteps = longArrayOf(300, 700, 1500)

    /** Голоса движка приезжают не в момент init ([applySpeedAndVoice] тогда не
     *  находит выбранный голос и оставляет как есть — настройки не портятся).
     *  Публичного уведомления о готовности списка в SDK нет, поэтому спрашиваем
     *  сами: до [voiceWaitSteps].size раз с растущей паузой. */
    private fun waitForVoices(stepsLeft: Int) {
        if (stepsLeft <= 0) return
        val delay = voiceWaitSteps[voiceWaitSteps.size - stepsLeft]
        main.postDelayed({
            if (!ready) return@postDelayed
            if (tts?.voices.isNullOrEmpty()) {
                waitForVoices(stepsLeft - 1)
            } else {
                applySpeedAndVoice()
                Diag.log(appContext, "tts", "голоса движка приехали не сразу (через $delay мс)")
            }
        }, delay)
    }

    // ---------- Сторож молчания (msg4077) ----------

    /** Взвести надзор за синтезом [text]: не отозвался за [synthFuseMs] —
     *  считаем движок замолчавшим. Если эту фразу движок уже терял однажды
     *  ([softRetriedText] — переспрос не помог), ждём коротко
     *  ([SYNTH_REASK_STALL_MS]) и идём к следующей мере, а не отсчитываем
     *  полный срок заново (msg5309).
     *
     *  Засекаем и МОМЕНТ взведения надзора (msg4211): в логе тестера сторож
     *  сработал через 42 с вместо номинала — таймеры `postDelayed` не шли,
     *  процесс спал. Часы elapsedRealtime идут и во сне, поэтому разница честно
     *  показывает, сколько устройство простояло замороженным. Засечка — на
     *  каждом взведении: взводят его только по настоящему событию (заявка на
     *  синтез или отсрочка, пока играет предыдущая фраза), и если бы момент
     *  не обновлялся, повтор той же фразы через полчаса выдал бы «спал 1800 с». */
    private fun armSynthWatch(text: String, shortFuse: Boolean = softRetriedText == text) {
        watchFromMs = SystemClock.elapsedRealtime()
        val fuse = if (shortFuse) SYNTH_REASK_STALL_MS else synthFuseMs(text)
        val token = ++synthWatchToken
        main.postDelayed({ if (token == synthWatchToken) onSynthWatchdog(text) }, fuse)
    }

    /** Когда взведён текущий надзор за синтезом (для честной цифры в логе). */
    private var watchFromMs = 0L

    /** Синтез молчит дольше разумного. Лестница мер (msg4103): переспросить фразу
     *  без перезапуска движка → перезапустить движок (до [MAX_RESTARTS] раз) →
     *  страховка ([onSynthLost]: прямая речь, а то и пропуск фразы). Чтение
     *  продолжается в любом случае — молчания навсегда быть не должно. */
    private fun onSynthWatchdog(text: String) {
        // Заготовка могла доспеть, пока сторож спал, — тогда сторожить нечего.
        val stuck = pending.filter {
            it.value.kind == Pending.Kind.FILE && it.value.text == text && !it.value.cancelled
        }
        if (stuck.isEmpty()) return
        if (media != null) {
            // Играет предыдущая фраза: движок сейчас не трогаем, иначе оборвём звук.
            // Ждём полный срок: с коротким запалом этот надзор просто крутился бы
            // вхолостую каждую секунду, пока звучит фраза.
            armSynthWatch(text, shortFuse = false)
            return
        }
        // Реальное время молчания, а не номинал сторожа: если часы «убежали»
        // далеко за срок запала — значит процесс спал (msg4211, #19).
        val nominalSec = if (softRetriedText == text) SYNTH_REASK_STALL_MS else synthFuseMs(text)
        val waited = ((SystemClock.elapsedRealtime() - watchFromMs) / 1000).toInt()
            .coerceAtLeast((nominalSec / 1000).toInt())
        stuck.forEach { (id, p) ->
            p.cancelled = true
            p.file?.delete()
            pending.remove(id)
        }
        if (prefetchInFlightText == text) prefetchInFlightText = null
        val wanted = text == awaitingPlayText
        // msg4103: первый обрыв — переспрашиваем фразу заново, НЕ трогая движок.
        // Лог пользователя (ivona.tts, RMX3472): движок терял одну-единственную
        // заявку, а следующие обслуживал исправно — предл. 146 зазвучал через
        // 1.1 с после начала синтеза. Перезапуск движка — тяжёлая мера (заново
        // init, голос, скорость), берегём её для второго обрыва подряд.
        if (softRetriedText != text && ready) {
            softRetriedText = text
            Diag.log(appContext, "tts",
                "синтез молчит $waited с (${text.take(40)}…) — переспрашиваю фразу заново")
            if (wanted) synthToFile(text)
            return
        }
        if (retriesInRow < MAX_RESTARTS) {
            retriesInRow++
            Diag.log(
                appContext, "tts",
                "движок молчит $waited с (${text.take(40)}…) — перезапускаю движок, попытка $retriesInRow"
            )
            if (wanted) retryAfterRestart = text
            start(enginePackage)
            return
        }
        Diag.log(appContext, "tts", "движок молчит $waited с и перезапуски не помогли — фразу пропускаю")
        retriesInRow = 0
        if (wanted) onSynthLost(text)
    }

    /** Взвести надзор за подготовкой файла: локальный wav готовится доли секунды. */
    private fun armPlayWatch(text: String?) {
        val token = ++playWatchToken
        main.postDelayed({ if (token == playWatchToken) onPlayWatchdog(text) }, PLAY_STALL_MS)
    }

    private fun onPlayWatchdog(text: String?) {
        Diag.log(appContext, "sound", "свой звук не подготовился за ${PLAY_STALL_MS / 1000} с — говорю напрямую")
        releaseMedia()
        fallbackDirect(text)
    }

    // ---------- Надзор за нашим плеером: звук идёт или стоит (23.09.2026) ----------

    /** Токен надзора: растёт, когда надзор больше не нужен (фраза закрыта, плеер
     *  отпущен, началась другая). */
    private var posWatchToken = 0L

    /** За каким плеером следим и что играем: по смене плеера надзор взводится
     *  заново (в т.ч. после перехода встык, когда играющим становится
     *  прицепленный плеер). */
    private var posWatchPlayer: MediaPlayer? = null
    private var posWatchFile: File? = null
    private var posWatchGen = 0L

    /** Последняя виденная позиция и когда она была такой. */
    private var posLast = -1
    private var posLastAt = 0L

    /** Видели ли мы хоть раз, что позиция РАСТЁТ. Пока не видели — судить
     *  нельзя: на отдельной прошивке `currentPosition` может всегда отдавать
     *  ноль, и тогда мы бы обрывали каждую фразу на ровном месте. */
    private var posSawProgress = false
    private var posWarnedNoProgress = false

    /** Чей конец фразы уже обработан: плеер живёт ровно одну фразу, поэтому
     *  хватает одной ссылки. Нужен, чтобы поздний `onCompletion` от платформы не
     *  прошёл второй раз по уже закрытой фразе (иначе чтение перескочит
     *  предложение). */
    private var completedPlayer: MediaPlayer? = null

    /** Взять играющий плеер под надзор: раз в [POSITION_CHECK_MS] сверяем
     *  позицию. Файл отдан, звука нет — увидим это сами, не спрашивая движок:
     *  он как раз и бывает тем, кто молчит. */
    private fun armPositionWatch(mp: MediaPlayer, f: File?, gen: Long) {
        if (f == null) return
        val token = ++posWatchToken
        posWatchPlayer = mp
        posWatchFile = f
        posWatchGen = gen
        posLast = -1
        posLastAt = SystemClock.elapsedRealtime()
        posSawProgress = false
        posWarnedNoProgress = false
        main.postDelayed({ if (token == posWatchToken) checkPosition(token) }, POSITION_CHECK_MS)
    }

    /** Снять надзор: фраза закрылась, плеер отпущен или звук больше не наш. */
    private fun dropPositionWatch() {
        posWatchToken++
        posWatchPlayer = null
        posWatchFile = null
    }

    private fun checkPosition(token: Long) {
        if (token != posWatchToken) return
        val mp = posWatchPlayer ?: return
        // Плеер уже не тот, что мы сторожим: фраза сменилась, чтение встало или
        // звук перешёл встык к следующему плееру (за него взведён свой надзор).
        if (media !== mp) return
        val pos = runCatching { mp.currentPosition }.getOrDefault(-1)
        val now = SystemClock.elapsedRealtime()
        if (pos > posLast) {
            posLast = pos
            posLastAt = now
            posSawProgress = true
        } else if (!posSawProgress) {
            // Позиция не растёт с самого начала: либо файл только-только отдан
            // (ждать нечего, время идёт), либо прошивка врёт про позицию. Второй
            // случай в журнале виден строкой ниже — по ней и разберёмся, если
            // такая прошивка найдётся.
            if (!posWarnedNoProgress && now - posLastAt >= POSITION_STALL_MS) {
                posWarnedNoProgress = true
                Diag.log(
                    appContext, "sound",
                    "позиция плеера не растёт с начала фразы (${pos}) — по времени не сужу",
                )
            }
        } else if (now - posLastAt >= POSITION_STALL_MS) {
            val dur = runCatching { mp.duration }.getOrDefault(-1)
            val still = (now - posLastAt) / 1000
            Diag.log(
                appContext, "sound",
                "позиция плеера стоит $still с ($pos из $dur) — звука нет, " +
                    "считаю фразу доигранной и иду дальше",
            )
            val f = posWatchFile
            dropPositionWatch()
            if (f != null) onPhraseCompleted(mp, f, posWatchGen)
            return
        }
        main.postDelayed({ if (token == posWatchToken) checkPosition(token) }, POSITION_CHECK_MS)
    }

    /** Взвести надзор за прямой речью движка: `speak()` мог отчитаться успехом и
     *  потом промолчать — тогда `onDone` не придёт и чтение встанет.
     *
     *  23.09.2026: срок считаем, а не берём одним числом (было 180 с — три минуты
     *  тишины). Пока движок не подал сигнал «начал говорить», ждём
     *  [DIRECT_START_MS] и пропускаем фразу: Сергей выбрал самую короткую тишину
     *  («чем можно пренебречь»), поэтому переспроса и перезапуска движка в этом
     *  режиме нет — они стоили бы ещё четыре секунды. Цена: если движок просто
     *  потерял одну заявку, фраза теряется. Вернуть переспрос — правка на пять
     *  строк, записано в docs/TODO.md. Пришёл сигнал «начал говорить» — считаем по
     *  длине фразы ([directFuseMs]).
     *
     *  С очередью ([directQueue]) срок на СТАРТ взводится только у первой фразы:
     *  у той, что стоит в очереди за другими, свой срок начинается не сейчас, а
     *  после них, и короткий запал сработал бы вхолостую. Дальше надзор ведёт
     *  сигнал «начал говорить» — он приходит на каждую фразу очереди. */
    private fun armDirectWatch(id: Long, started: Boolean = false) {
        val token = ++directWatchToken
        val text = pending[id]?.text
        val fuse = if (started) directFuseMs(text) else DIRECT_START_MS
        if (started) {
            Diag.log(
                appContext, "tts",
                "прямая речь началась: стерегу ${fuse / 1000} с " +
                    "(фраза ${text?.length ?: 0} знаков, скорость $speed)",
            )
        }
        main.postDelayed({
            if (token != directWatchToken) return@postDelayed
            if (pending.remove(id) == null) return@postDelayed
            // Движок стоит на этой фразе, а следующие уже за ней у него в
            // очереди: без очистки они прозвучат после молчания, и наша закладка
            // разъедется со звуком. Гасим очередь и просим заново со следующей
            // фразы — её пришлёт читалка по onDone (см. [speakDirectQueued]).
            flushDirectQueue("фраза не зазвучала")
            Diag.log(
                appContext, "tts",
                "прямая речь молчит ${fuse / 1000} с — пропускаю фразу, чтение идёт дальше"
            )
            retriesInRow = 0
            onDone?.invoke()
        }, fuse)
    }

    /** Фраза прямой речи от читалки. Обычный случай — она уже стоит в очереди
     *  движка (мы наполнили её заранее, [topUpDirectQueue]): тогда не трогаем
     *  ничего, движок сам её скажет, и паузы на стыке не будет вовсе. Другой
     *  текст (прыжок по тексту, повтор предложения, пауза → продолжить) — старая
     *  очередь движку больше не нужна, выбрасываем и начинаем с этой фразы. */
    private fun speakDirectQueued(text: String) {
        if (isDirectQueued(text)) {
            Diag.log(
                appContext, "sound",
                "альтернативный способ: фраза уже в очереди движка (${text.length} знаков)"
            )
            topUpDirectQueue()
            return
        }
        flushDirectQueue("пришла другая фраза")
        if (!queueDirect(text)) {
            onDone?.invoke()
            return
        }
        topUpDirectQueue()
    }

    /** Стоит ли [text] в очереди движка (сравниваем по тексту: номера заявок
     *  читалке неизвестны). Повтор одинаковых предложений подряд различению не
     *  мешает — движок читает очередь по порядку, а закладку двигает «закончил»
     *  по каждой фразе. */
    private fun isDirectQueued(text: String): Boolean =
        directQueue.any { pending[it]?.text == text }

    /** Отдать движку ещё одну фразу В КОНЕЦ очереди. false — движок отказался. */
    private fun queueDirect(text: String): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        val idle = directQueue.isEmpty()
        val id = reqSeq++
        pending[id] = Pending(Pending.Kind.DIRECT, text, null)
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_ADD, null, id.toString()) }
            .getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        directQueue.addLast(id)
        if (idle) armDirectWatch(id)
        return true
    }

    /** Держать у движка [prefetchDepth] фраз впереди — тогда он берёт их подряд
     *  без пауз. Сколько именно, спрашиваем у читалки тем же [onNeedNext], каким
     *  в обычном режиме заказываем заготовки: она единственный источник правды о
     *  том, что звучит следующим, и сама обрывает список на конце главы, конце
     *  книги и на границе выделенного куска. */
    private fun topUpDirectQueue() {
        while (directQueue.size < prefetchDepth) {
            val next = onNeedNext?.invoke(directQueue.size) ?: return
            if (next.isBlank()) return
            if (isDirectQueued(next)) return
            if (!queueDirect(next)) return
            Diag.log(
                appContext, "sound",
                "альтернативный способ: в очереди движка ${directQueue.size}/$prefetchDepth фраз"
            )
        }
    }

    /** Выбросить всё, что отдано движку в альтернативном способе, вместе с его
     *  собственной очередью. Движок при этом останавливаем — иначе он дочитает
     *  то, что мы уже не считаем своим (прыжок по тексту, пауза, пропуск
     *  застрявшей фразы).
     *
     *  Разовую страховку обычного режима ([fallbackDirect]) это не задевает: там
     *  очередь пуста, и первым же условием мы выходим — гасить движок нельзя, у
     *  него могут быть заявки на синтез в файл. */
    private fun flushDirectQueue(reason: String) {
        if (directQueue.isEmpty()) return
        directQueue.clear()
        pending.entries.removeAll { it.value.kind == Pending.Kind.DIRECT }
        directWatchToken++
        runCatching { tts?.stop() }
        Diag.log(appContext, "tts", "прямая речь: очередь движка выброшена ($reason)")
    }

    /** Сигнал движка «начал говорить» пришёл — переводим надзор на срок по длине
     *  фразы. В обычном режиме тот же сигнал приходит про синтез в файл: там свой
     *  надзор, и трогать его не надо. */
    private fun directSpeechStarted(id: Long?) {
        val real = id ?: return
        val p = pending[real] ?: return
        if (p.kind != Pending.Kind.DIRECT) return
        armDirectWatch(real, started = true)
    }

    /** Сколько ждать прямую речь по длине фразы: сколько ей звучать при текущей
     *  скорости, запас 1,4 и [DIRECT_TAIL_MS] сверху, в границах
     *  [DIRECT_MIN_MS]…[DIRECT_MAX_MS]. [CHARS_PER_SEC] — наша оценка темпа на
     *  скорости 1.0; её видно в журнале рядом со сроком, и если движок говорит
     *  заметно медленнее, число правится по факту, а не на глаз. */
    private fun directFuseMs(text: String?): Long {
        val chars = text?.length ?: 0
        val rate = speed.coerceAtLeast(0.5f)
        val expected = chars / (CHARS_PER_SEC * rate) * 1000f
        return (expected * 1.4f + DIRECT_TAIL_MS).toLong().coerceIn(DIRECT_MIN_MS, DIRECT_MAX_MS)
    }

    /** Сколько ждём движок на синтез [text] в файл: четверть того времени, что
     *  фраза будет звучать, в границах [SYNTH_MIN_MS]…[SYNTH_MAX_MS]. Почему
     *  четверть: наш замер — 13-секундная фраза собралась за 0,95 с, то есть
     *  синтез идёт примерно в десять раз быстрее речи, и четверть даёт запас
     *  в два с половиной раза на медленный движок или занятый процессор.
     *  Срок виден в журнале, и если движок у Сергея окажется медленнее, число
     *  правится по факту, а не на глаз. Короткая фраза теперь переспрашивается
     *  через 2,5 с вместо прежних 6 — это и есть выигранная тишина. */
    private fun synthFuseMs(text: String?): Long {
        val chars = text?.length ?: 0
        val rate = speed.coerceAtLeast(0.5f)
        val speechMs = chars / (CHARS_PER_SEC * rate) * 1000f
        return (speechMs / 4f).toLong().coerceIn(SYNTH_MIN_MS, SYNTH_MAX_MS)
    }

    // ---------- Синтез ----------

    /** Запустить синтез [text] в файл. По завершении судьба файла решается в
     *  [synthFinished]: текст ждёт [awaitingPlayText] — играем, иначе копим впрок. */
    private fun synthToFile(text: String) {
        val t = tts
        if (t == null || !ready) return
        val file = File(synthDir, "s_${reqSeq}.wav")
        val id = reqSeq++
        pending[id] = Pending(Pending.Kind.FILE, text, file, speed)
        armSynthWatch(text)
        val r = runCatching {
            t.synthesizeToFile(text, null, file, id.toString())
        }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)?.let { it.file?.delete() }
            onSynthLost(text)
        }
    }
    /** Завершился синтез (или прямое произнесение) с [id]. На главном потоке. */
    private fun synthFinished(id: Long) {
        directWatchToken++ // речь движка отозвалась — надзор за ней больше не нужен
        val p = pending.remove(id) ?: return
        if (p.cancelled) {
            p.file?.delete()
            return
        }
        when (p.kind) {
            Pending.Kind.DIRECT -> {
                // Звучащая фраза прямой речи (или разовая страховка) закончилась.
                retriesInRow = 0 // движок жив — счётчик перезапусков обнуляем
                if (directQueue.firstOrNull() == id) directQueue.removeFirst()
                else directQueue.remove(id)
                // Движок сейчас возьмёт следующую фразу очереди: переводим надзор
                // на её длину. Сигнал «начал говорить» придёт и сам, но срок,
                // отсчитанный отсюда, спасает движки, которые его не шлют.
                directQueue.firstOrNull()?.let { armDirectWatch(it, started = true) }
                onDone?.invoke()
            }
            Pending.Kind.FILE -> {
                val f = p.file
                if (f == null || !f.exists() || f.length() == 0L) {
                    f?.delete()
                    onSynthLost(p.text)
                    return
                }
                softRetriedText = null // синтез доспел — движок отзывается, обрывов нет
                // msg4416: движок иногда отдаёт файл из одного заголовка (44 байта,
                // ни одного отсчёта) — фраза звучит молча. Пишем в журнал её текст:
                // надо понять, теряется ли настоящий текст или это пустые обрывки
                // разбора (в логе Сергея такие файлы идут парами подряд).
                if (f.length() < 128 && emptySoundLogged < 6) {
                    emptySoundLogged++
                    Diag.log(
                        appContext, "sound",
                        "движок отдал пустой звук (${f.length()} байт): «${p.text.take(60)}»"
                    )
                }
                // msg5979: заказанная скорость и «сырой» размер файла. По ним
                // видно, насколько движок ускорился НА САМОМ ДЕЛЕ: при 24 кГц,
                // 16 бит моно это 48 000 байт в секунду звучания, значит одна и
                // та же фраза на 2.0 обязана быть вдвое короче, чем на 1.0.
                // Пишем ДО trimSilence: обрезка краёв отнимает постоянный кусок
                // и сравнение смазала бы.
                Diag.log(
                    appContext, "sound",
                    "синтез готов: ${f.name} (${f.length()} байт), скорость ${RateSteps.label(p.rate)}"
                )
                trimSilence(f)
                if (p.text == awaitingPlayText && p.rate != speed) {
                    // Скорость сменили, пока фраза синтезировалась: файл сделан
                    // прежним темпом. Не играем его — заказываем заново текущим
                    // (задержка тут только у той фразы, что была в работе).
                    awaitingPlayText = null
                    if (prefetchInFlightText == p.text) prefetchInFlightText = null
                    f.delete()
                    Diag.log(
                        appContext, "sound",
                        "скорость сменилась, пока фраза синтезировалась — собираю заново"
                    )
                    synthToFile(p.text)
                } else if (p.text == awaitingPlayText) {
                    // Это тот текст, который уже попросили говорить.
                    awaitingPlayText = null
                    if (prefetchInFlightText == p.text) prefetchInFlightText = null
                    playFile(f, p.rate)
                } else if (p.text == prefetchInFlightText) {
                    // Упреждающий синтез завершился, пока его ещё не просили.
                    // msg4077: засечка в лог — без неё по логу не видно, доспела
                    // заготовка или синтез молча повис.
                    prefetchInFlightText = null
                    if (readyQueue.size >= prefetchDepth) {
                        // Очередь переполнена (прыжок назад или смена позиции) —
                        // лишнее не копим.
                        f.delete()
                    } else {
                        readyQueue.addLast(Prepared(p.text, f, p.rate))
                        Diag.log(
                            appContext, "sound",
                            "заготовка готова: ${f.name} (${f.length()} байт), в очереди ${readyQueue.size}/$prefetchDepth"
                        )
                        // msg4472: заготовка доспела — сразу просим следующую,
                        // пока играет текущая. Так очередь наполняется сама.
                        // Кроме упреждающей подготовки ([prewarm]): там нужна
                        // ровно одна фраза — та, с которой начнётся чтение.
                        if (prewarmOnly) prewarmOnly = false else requestPrefetch()
                        // msg4598: прицепляем её встык, если звук уже идёт. Без
                        // этого стык бесшовным становился бы только у тех фраз,
                        // чья следующая была готова к моменту старта.
                        media?.let { m -> chainNext(m, playGen) }
                    }
                } else {
                    // Осиротевший синтез (устаревшая заготовка) — просто удаляем.
                    f.delete()
                }
            }
        }
    }

    /** Синтез с [id] сорвался — разбираемся, был ли это запрошенный текст. */
    private fun synthFailed(id: Long, errorCode: Int) {
        directWatchToken++
        val p = pending.remove(id) ?: return
        p.file?.delete()
        if (p.cancelled) return
        if (p.kind == Pending.Kind.DIRECT) {
            // Движок отказался от фразы прямой речи. Раньше такие ошибки молчали
            // вовсе: в этом режиме надзирало только молчание, а ошибка — не
            // молчание, и о ней никто не узнавал.
            val head = directQueue.firstOrNull() == id
            directQueue.remove(id)
            Diag.log(
                appContext, "tts",
                "прямая речь: движок отказался от фразы (код $errorCode)"
            )
            // Отказ по фразе ИЗ ОЧЕРЕДИ закладку не двигает: читалка попросит её
            // снова, когда дойдёт до неё (см. [speakDirectQueued]), и текст не
            // потеряется. А отказ по звучащей фразе — это пропуск, как по сторожу.
            if (!head) return
            flushDirectQueue("движок вернул ошибку")
            retriesInRow = 0
            onDone?.invoke()
            return
        }
        Diag.log(appContext, "tts", "синтез в файл не удался (код $errorCode)")
        onSynthLost(p.text)
    }

    /** Синтеза в файл не вышло. Если текст кто-то ждёт (speak()) — пытаемся
     *  проговорить напрямую движком: тишина хуже, чем разовое возвращение к
     *  движковому звуку (на время этой фразы media-кнопки будут не наши — это
     *  редкая страховка). Упавшую заготовку просто отбрасываем. */
    private fun onSynthLost(text: String) {
        if (prefetchInFlightText == text) prefetchInFlightText = null
        val wanted = text == awaitingPlayText
        awaitingPlayText = null
        val t = tts
        if (t == null || !ready || !wanted) return
        Diag.log(appContext, "tts", "страховка: движок говорит напрямую (media-кнопки на это время не наши)")
        val id = reqSeq++
        pending[id] = Pending(Pending.Kind.DIRECT, text, null)
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) }
            .getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            // Совсем ничего не вышло — пропускаем предложение, чтобы чтение не встало.
            onDone?.invoke()
        } else {
            armDirectWatch(id)
        }
    }

    // ---------- Проигрывание ----------

    /** Младшие байты WAV: 16-битное число с прямым порядком (little endian). */
    private fun leShort(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** 32-битное число WAV (little endian). */
    private fun leInt(b: ByteArray, i: Int): Int =
        leShort(b, i) or (leShort(b, i + 2) shl 16)

    /** Коды причин, по которым обрезка файл не тронула. Пишем в журнал по
     *  одному разу на код за сессию: если галочка «ничего не меняет», по этой
     *  строке видно, ЧТО именно ей помешало (msg4409 — Сергей: «на мой слух
     *  эта галочка ничего не меняет», а причина молчала).
     *  1 — это не WAV; 2 — формат не PCM; 3 — не тот звук (каналы/биты/частота);
     *  4 — нет блока data; 5 — файл подозрительного размера; 7 — сплошная
     *  тишина; 8 — после обрезки осталась бы десятая часть; 9 — тишины по краям
     *  нет вовсе (значит пауза не в тишине, а в интонации синтезатора). */
    private val trimBailLogged = HashSet<Int>()

    private fun trimBail(code: Int, what: String, f: File) {
        if (!trimBailLogged.add(code)) return
        Diag.log(appContext, "sound", "паузы: ${f.name} не тронул — $what (причина $code; дальше не повторяю)")
    }

    /** Обрезать тишину по краям синтезированного файла (msg4402). Разбираем
     *  только обычный 16-битный PCM-WAV (в том числе в «расширенном» заголовке) —
     *  если внутри что-то другое или файл не разобрался, оставляем как есть:
     *  пауза останется прежней, но чтение не сломается, а причина уйдёт в
     *  журнал через [trimBail]. Голову подрезаем сильнее хвоста — в начале
     *  тишина почти всегда «пустая», в конце в неё уходит затухание последнего
     *  слова. */
    private fun trimSilence(f: File) {
        val b = runCatching { f.readBytes() }.getOrNull() ?: return
        val n = b.size
        if (n < 128 || n > 8_000_000) { trimBail(5, "размер не тот ($n байт)", f); return }
        if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) { trimBail(1, "это не WAV", f); return }

        var off = 12
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= n) {
            val id = String(b, off, 4, Charsets.US_ASCII)
            val sz = leInt(b, off + 4)
            if (sz < 0 || off + 8 + sz > n) {
                if (id == "data") { dataOff = off + 8; dataLen = n - dataOff }
                break
            }
            if (id == "fmt " && sz >= 16) {
                // msg4409: движки пишут и «расширенный» заголовок
                // (WAVE_FORMAT_EXTENSIBLE, тег 0xFFFE): подформат там лежит
                // GUID'ом, и для обычного PCM он тоже начинается единицей.
                // Раньше принимали только тег 1 — на таком файле обрезка молча
                // ничего не делала.
                val tag = leShort(b, off + 8)
                val pcm = tag == 1 ||
                    (tag == 0xFFFE && sz >= 40 && leInt(b, off + 32) == 1)
                if (!pcm) { trimBail(2, "формат не PCM (тег $tag)", f); return }
                channels = leShort(b, off + 10)
                sampleRate = leInt(b, off + 12)
                bits = leShort(b, off + 22)
            } else if (id == "data") {
                dataOff = off + 8
                dataLen = minOf(sz, n - dataOff)
            }
            off += 8 + sz + (sz and 1)
        }
        if (channels !in 1..2 || bits != 16 || sampleRate !in 8000..48000) {
            trimBail(3, "не тот звук (каналов $channels, бит $bits, Гц $sampleRate)", f)
            return
        }
        if (dataOff < 0 || dataLen < 3200) { trimBail(4, "нет блока data", f); return }

        val frame = channels * 2
        val frames = dataLen / frame
        if (frames < 400) { trimBail(5, "слишком короткий ($frames кадров)", f); return }

        fun ampOf(frameIdx: Int): Int {
            var m = 0
            var i = dataOff + frameIdx * frame
            repeat(channels) {
                val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > m) m = a
                i += 2
            }
            return m
        }

        val thresh = 300 // ≈ −40 dBFS: тише этого считаем тишиной
        val scanCap = minOf(frames, sampleRate * 2)
        var first = -1
        var i = 0
        while (i < scanCap) {
            if (ampOf(i) > thresh) { first = i; break }
            i++
        }
        if (first < 0) { trimBail(7, "сплошная тишина", f); return }
        var last = -1
        var j = frames - 1
        while (j > first) {
            if (ampOf(j) > thresh) { last = j; break }
            j--
        }
        if (last < 0) { trimBail(7, "сплошная тишина", f); return }

        // msg5604: остаток тишины на стыке задаёт настройка — две пятых в
        // голове фразы, три пятых в хвосте (при 130 мс это прежние 50 и 83 мс).
        val keep = pauseKeepMs.coerceIn(0, 1000)
        val headKeep = sampleRate * (keep * 2 / 5) / 1000
        val tailKeep = sampleRate * (keep * 3 / 5) / 1000
        val startF = (first - headKeep).coerceAtLeast(0)
        val endF = (last + 1 + tailKeep).coerceAtMost(frames)
        if (endF - startF < frames / 10) {
            trimBail(8, "после обрезки осталась бы десятая часть", f)
            return
        }

        val cutHead = startF * 1000 / sampleRate
        val cutTail = (frames - endF) * 1000 / sampleRate
        if (cutHead < 40 && cutTail < 60) {
            // Самое важное для жалобы на паузы: движок тишины по краям не
            // дописывает — значит пауза не в тишине, а в интонации синтезатора,
            // и обрезкой её не взять.
            trimBail(9, "тишины по краям нет (голова $cutHead мс, хвост $cutTail мс)", f)
            return
        }

        val outLen = (endF - startF) * frame
        val out = ByteArray(44 + outLen)
        System.arraycopy("RIFF".toByteArray(Charsets.US_ASCII), 0, out, 0, 4)
        System.arraycopy("WAVE".toByteArray(Charsets.US_ASCII), 0, out, 8, 4)
        System.arraycopy("fmt ".toByteArray(Charsets.US_ASCII), 0, out, 12, 4)
        System.arraycopy("data".toByteArray(Charsets.US_ASCII), 0, out, 36, 4)
        val putInt = { at: Int, v: Int ->
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
            out[at + 2] = ((v shr 16) and 0xFF).toByte()
            out[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        val putShort = { at: Int, v: Int ->
            out[at] = (v and 0xFF).toByte()
            out[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putInt(4, 36 + outLen)
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, sampleRate * frame)
        putShort(32, frame)
        putShort(34, 16)
        putInt(40, outLen)
        System.arraycopy(b, dataOff + startF * frame, out, 44, outLen)

        if (!runCatching { f.writeBytes(out) }.isSuccess) return
        // В журнал — сколько тишины движок дописал ВСЕГО и сколько из неё
        // срезано: по этой строке видно, из чего состояла пауза между
        // предложениями (msg4409: «на слух ничего не меняет» — вот цифры).
        val silHead = first * 1000 / sampleRate
        val silTail = (frames - 1 - last) * 1000 / sampleRate
        Diag.log(
            appContext, "sound",
            "паузы: у ${f.name} тишины было ${silHead} мс в начале и ${silTail} мс в конце; " +
                "срезано ${cutHead} и ${cutTail} мс"
        )
    }

    /** Сыграть готовый файл. [rate] — скорость, которой он сделан: её храним
     *  вместе с играющим файлом, чтобы пауза не выдала прежний темп за текущий
     *  (см. [pauseKeepingPhrase]). */
    private fun playFile(f: File, rate: Float = speed) {
        val gen = ++playGen
        val text = currentText
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(audioAttrs)
            mp.setAudioSessionId(audioSessionId)
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener {
                playWatchToken++ // файл готов — сторож подготовки больше не нужен
                if (gen != playGen || media !== null) {
                    // Успели остановить, пока файл готовился, — не играем.
                    runCatching { it.release() }
                    f.delete()
                    return@setOnPreparedListener
                }
                media = it
                playingText = text
                playingFile = f
                playingRate = rate
                it.setVolume(volume, volume)
                it.start()
                retriesInRow = 0 // звук пошёл — движок и плеер живы
                Diag.log(appContext, "sound", "играю свой звук: ${f.name} (${f.length()} байт)")
                // 23.09.2026: взяли звук под надзор — «играю» или «позиция стоит».
                // С этого момента видно то, чего не видит движок: файл отдан, а
                // звука нет (в т.ч. когда файл доиграл, а onCompletion потерялся —
                // раньше это было молчание навсегда).
                armPositionWatch(it, f, gen)
                // msg4598: следующую заготовку прицепляем СРАЗУ, до requestPrefetch —
                // она уходит из очереди и обязана считаться занятой в её глубине.
                chainNext(it, gen)
                // Предложение начало звучать — готовим следующее заранее.
                requestPrefetch()
            }
            mp.setOnCompletionListener { onPhraseCompleted(it, f, gen) }
            mp.setOnErrorListener { p, what, extra ->
                playWatchToken++
                runCatching { p.release() }
                if (media === p) {
                    media = null
                    playingText = null
                    playingFile = null
                }
                f.delete()
                if (gen != playGen) return@setOnErrorListener true
                dropChain() // прицепленная фраза относится к упавшему плееру — её отпускаем
                Diag.log(appContext, "sound", "свой звук не заиграл (what=$what extra=$extra) — говорю напрямую")
                fallbackDirect(text)
                true
            }
            // msg4077: засечка «файл отдан плееру» — по логу видно, где встало:
            // синтез не доспел или плеер не подготовил файл.
            Diag.log(appContext, "sound", "готовлю свой звук: ${f.name} (${f.length()} байт)")
            armPlayWatch(text)
            mp.prepareAsync()
        } catch (e: Exception) {
            playWatchToken++
            runCatching { mp.release() }
            f.delete()
            if (gen == playGen) {
                Diag.log(appContext, "sound", "исключение при старте своего звука: ${e.message}")
                fallbackDirect(text)
            }
        }
    }

    /** Фраза доиграла — конец звука или переход встык (msg4598).
     *
     *  Обычный путь: отпускаем плеер, удаляем файл и говорим движку «дальше»
     *  ([onDone] у него отложен). Если к плееру была прицеплена следующая
     *  заготовка, платформа уже начала её сама — тогда эстафета передаётся без
     *  нашей задержки: текущим становится прицепленный плеер, а движку мы
     *  сообщаем о сдвиге на фразу так же, как в обычном пути. */
    private fun onPhraseCompleted(mp: MediaPlayer, f: File, gen: Long) {
        // Фразу могли уже закрыть надзором за позицией, а платформенный
        // onCompletion способен прийти вдогонку. Второй проход по той же фразе
        // дёрнул бы onDone ещё раз, и чтение перескочило бы предложение —
        // поэтому каждый плеер закрываем ровно один раз (плеер живёт одну фразу).
        if (mp === completedPlayer) return
        completedPlayer = mp
        // msg4402 (диагностика пауз): конец фразы. Разница отметок «фраза
        // доиграла» → следующее «играю свой звук» — это и есть пауза между
        // предложениями; по ней видно, помогла ли обрезка. В режиме встык вместо
        // «играю свой звук» идёт «встык сработал» — звук не перезапускался.
        Diag.log(appContext, "sound", "фраза доиграла: ${f.name}")
        val next = chainedPlayer
        if (gapless && next != null && media === mp && gen == playGen) {
            val nextText = chainedText
            val nextFile = chainedFile
            val nextRate = chainedRate
            chainedPlayer = null
            chainedText = null
            chainedFile = null
            runCatching { mp.release() }
            f.delete()
            media = next
            playingText = nextText
            playingFile = nextFile
            playingRate = nextRate
            playWatchToken++ // сторож надзора за «подготовкой» к этому звуку не относится
            Diag.log(appContext, "sound", "встык сработал: ${nextFile?.name ?: "?"}")
            // Играющим стал прицепленный плеер — надзор за позицией переезжает
            // на него: старый уже отпущен, и следить за ним нечего.
            armPositionWatch(next, nextFile, gen)
            // Движок сдвинется на эту же фразу (onDone отложен в главный поток):
            // его speak() узнает текст по метке и не станет играть его заново.
            gaplessHopText = nextText
            onDone?.invoke()
            // Тянем следующую заготовку в цепочку, пока звучит эта. requestPrefetch
            // здесь не зовём: движок ещё не сдвинул позицию, и он ответил бы про
            // фразу, которая уже звучит, — заготовку попросит его же speak().
            chainNext(next, gen)
            return
        }
        runCatching { mp.release() }
        if (media === mp) {
            media = null
            playingText = null
            playingFile = null
        }
        f.delete()
        onDone?.invoke()
    }

    /** Прицепить следующую готовую заготовку к играющему плееру (msg4598).
     *
     *  Платформа начинает прицепленный файл сама в момент окончания текущего —
     *  тем же аудиотрактом, без нашей задержки на запуск. Берём только голову
     *  очереди: заготовки лежат в том порядке, в каком прозвучат. */
    private fun chainNext(cur: MediaPlayer, gen: Long) {
        if (!gapless || chainedPlayer != null || chainInFlight) return
        if (gen != playGen) return
        val head = readyQueue.firstOrNull() ?: return
        if (head.text == playingText) return
        // 24.09.2026: заготовка прежней скорости в цепочку не годится — стык
        // прозвучал бы старым темпом. Выбрасываем и заказываем следующую: на
        // подходе эта фраза всё равно была бы собрана заново, но тогда стык
        // откатился бы на перезапуск и это было бы слышно паузой.
        if (head.rate != speed) {
            readyQueue.removeFirst()
            head.file.delete()
            Diag.log(appContext, "sound", "встык: заготовка прежней скорости — заказываю заново")
            requestPrefetch()
            return
        }
        // msg4643 (тестер: «пропускаются куски текста»): прицепляем заготовку
        // только если читалка ПРЯМО СЕЙЧАС называет её следующей фразой.
        // Тексты для очереди и для живого чтения считает один и тот же код, но
        // если позиция успела уехать (или заготовка заказана на другой глубине),
        // голова очереди оказывается НЕ следующей фразой: платформа заиграет
        // чужой кусок, а движок на следующем шаге оборвёт его ради правильного —
        // на слух это и есть пропуск. Не уверены — не прицепляем вовсе: обычный
        // стык ничего не перебивает, он просто на 0,3 с длиннее.
        // msg5596 (журнал Сергея): сразу после перехода встык читалка ещё не
        // сдвинула позицию — её «следующая» (сдвиг 1) это ровно та фраза, что
        // уже звучит в цепочке. Сверялись с ней и всегда отказывали: в журнале
        // «встык пропущен» после КАЖДОГО перехода, а следующая фраза
        // прицеплялась только позже, когда доспеет новая заготовка. На медленном
        // движке (сетевые голоса Google) заготовка может не успеть — стык
        // откатывается на перезапуск, и это слышно паузой. Узнали в ответе ту,
        // что звучит, — спрашиваем следующую за ней.
        var wanted = onNeedNext?.invoke(1)
        if (wanted != null && wanted == playingText) wanted = onNeedNext?.invoke(2)
        if (wanted == null || wanted != head.text) {
            Diag.log(
                appContext, "sound",
                "встык пропущен: заготовка не от следующей фразы " +
                    "(в очереди ${head.text.length} знаков, ждём ${wanted?.length ?: 0})"
            )
            return
        }
        val text = head.text
        val file = head.file
        // Заготовка уходит из очереди в цепочку сразу: и очередь, и счётчик
        // глубины в requestPrefetch обязаны видеть её занятой.
        readyQueue.removeFirst()
        chainInFlight = true
        val seq = ++chainSeq
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(audioAttrs)
            mp.setAudioSessionId(audioSessionId)
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { next ->
                if (seq != chainSeq || gen != playGen || media !== cur) {
                    // Прицепку отменили (остановка, смена фразы), пока файл готовился.
                    chainInFlight = false
                    runCatching { next.release() }
                    file.delete()
                    return@setOnPreparedListener
                }
                val ok = runCatching { cur.setNextMediaPlayer(next) }.isSuccess
                if (!ok) {
                    // Прошивка перехода не приняла — играем стык как раньше.
                    chainInFlight = false
                    runCatching { next.release() }
                    file.delete()
                    Diag.log(appContext, "sound", "встык не принят — стык как раньше: ${file.name}")
                    return@setOnPreparedListener
                }
                chainInFlight = false
                chainedPlayer = next
                chainedText = text
                chainedFile = file
                chainedRate = head.rate
                next.setVolume(volume, volume)
                next.setOnCompletionListener { onPhraseCompleted(next, file, gen) }
                Diag.log(appContext, "sound", "встык прицеплено: ${file.name} (${file.length()} байт)")
            }
            mp.setOnErrorListener { p, _, _ ->
                chainInFlight = false
                runCatching { p.release() }
                file.delete()
                Diag.log(appContext, "sound", "встык не подготовился: ${file.name}")
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            chainInFlight = false
            runCatching { mp.release() }
            file.delete()
            Diag.log(appContext, "sound", "встык не подготовился: ${e.message}")
        }
    }

    /** Отпустить прицепленную заготовку, не играя её (остановка, ошибка плеера,
     *  смена фразы). Файл удаляем: очередь заготовок наполнится сама. */
    private fun dropChain() {
        chainSeq++ // запоздавший ответ готовящейся прицепки больше не наш
        chainInFlight = false
        chainedPlayer?.let { runCatching { it.release() } }
        chainedPlayer = null
        chainedText = null
        chainedFile?.delete()
        chainedFile = null
        chainedRate = 1f
    }

    /** Выбросить всё, что заготовлено впрок (msg5604). Зовётся, когда на ходу
     *  сменили голос или движок: заготовки сделаны ПРЕЖНИМ голосом, и пока они
     *  не отыграют (до трёх в очереди и одна прицепленная), новый голос не
     *  слышен вовсе. Заодно снимаем метку «синтез идёт»: файл, который доспеет
     *  после смены, уже осиротеет и будет удалён — он тоже прежним голосом.
     *  Текущий звук не трогаем: его перечитает читалка ([ReaderEngine]
     *  restartAfterSwitch). */
    fun dropPrepared() {
        prefetchInFlightText = null
        awaitingPlayText = null
        prewarmOnly = false
        // Голос сменился — сохранённый паузой звук сделан прежним.
        keptPhrase?.let { runCatching { it.file.delete() } }
        keptPhrase = null
        // Метку перехода тоже снимаем: если смена голоса пришлась ровно на
        // момент перехода встык, следующий speak() должен фразу СЫГРАТЬ (новым
        // голосом), а не решить «она и так звучит».
        gaplessHopText = null
        clearPrefetch()
        dropChain()
    }

    /** Снять прицепку, вернув заготовку в очередь (галочка встык снята на ходу,
     *  msg4611). Файл ещё не звучал — он пригодится следующей фразе, поэтому
     *  его не удаляем, а кладём обратно в голову очереди. */
    private fun unhookChain() {
        chainSeq++
        chainInFlight = false
        val p = chainedPlayer ?: return
        val text = chainedText
        val file = chainedFile
        chainedPlayer = null
        chainedText = null
        chainedFile = null
        runCatching { p.release() }
        if (text != null && file != null && file.exists() && chainedRate == speed)
            readyQueue.addFirst(Prepared(text, file, chainedRate))
        else file?.delete()
    }

    /** Собственный аудиоплеер не смог сыграть файл (редкий случай) — чтобы
     *  чтение не замолчало, произносим текст напрямую движком. На время этой
     *  фразы media-кнопки будут не наши; по её окончании чтение продолжается. */
    private fun fallbackDirect(text: String?) {
        if (text.isNullOrBlank()) return
        val t = tts
        if (t == null || !ready) {
            // Совсем ничего не вышло — пропускаем предложение, чтение не встаёт.
            onDone?.invoke()
            return
        }
        val id = reqSeq++
        pending[id] = Pending(Pending.Kind.DIRECT, text, null)
        val r = runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) }
            .getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            onDone?.invoke()
        } else {
            armDirectWatch(id)
        }
    }

    /** Отменить текущее проигрывание. onDone НЕ вызывается. */
    private fun releaseMedia() {
        playGen++
        dropChain() // прицепленная фраза играет тем же трактом — её тоже отпускаем
        // Надзор за позицией снимаем вместе со звуком: сторожить нечего, а его
        // таймер иначе дождался бы чужого плеера.
        dropPositionWatch()
        playingText = null
        playingFile = null
        gaplessHopText = null
        media?.let { m ->
            runCatching { m.stop() }
            runCatching { m.release() }
        }
        media = null
    }

    /** Где конвейер разошёлся с читалкой (msg4685, вариант B), или null, если
     *  расхождений нет.
     *
     *  Читалка — единственный источник правды о том, какая фраза идёт следующей;
     *  мы спрашиваем её тем же [onNeedNext], каким заказываем заготовки. Список
     *  фраз ВПЕРЕДИ — по порядку звучания: прицепленная встык, потом очередь.
     *  Каждая обязана совпасть с ответом читалки на своём месте. Не совпала —
     *  конвейер разъехался: заготовки заказаны «на фразу вперёд», а стык между
     *  ними пустой. Так и выглядит журнал тестера (msg4635): прицеплена фраза 11,
     *  а движок просит 10 — платформа играет чужой кусок, движок через 0,6 с
     *  обрывает его ради правильного, на слух это пропуск текста.
     *
     *  Проверка консервативна: читалка ответила null (конец книги, движок гасят) —
     *  сверять нечем, расхождения не объявляем. */
    private fun firstMisalignedAhead(): Int? {
        val ask = onNeedNext ?: return null
        // Прицепка готовится: её фраза уже ушла из очереди, а текста в полях
        // ещё нет — список впереди стоящих был бы неполным, и сверка «нашла» бы
        // расхождение там, где его нет. Пропускаем круг: следующая проверка
        // (после прицепки или её отмены) снова увидит весь конвейер.
        if (chainInFlight) return null
        val chainedNow = chainedPlayer != null && chainedText != null
        val ahead = ArrayList<String>(readyQueue.size + 1)
        if (chainedNow) ahead.add(chainedText!!)
        for (p in readyQueue) ahead.add(p.text)
        for (i in ahead.indices) {
            val word = ask(i + 1) ?: return null
            if (word != ahead[i]) return i
        }
        return null
    }

    /** Выбросить из конвейера всё от позиции [from] и дальше: файлы удаляем,
     *  очередь укорачиваем. Впереди стоящее и совпавшее с читалкой (до [from])
     *  не трогаем — оно ещё пригодится. */
    private fun dropAheadFrom(from: Int) {
        val chainedNow = chainedPlayer != null && chainedText != null
        if (chainedNow && from <= 0) dropChain()
        val keep = if (chainedNow) (from - 1).coerceAtLeast(0) else from.coerceAtLeast(0)
        while (readyQueue.size > keep) {
            val p = readyQueue.removeLast()
            runCatching { p.file.delete() }
        }
    }

    /** Пока играет текущее — спросить читалку, какая фраза будет следующей, и
     *  синтезировать её заранее, чтобы между фразами не было паузы.
     *
     *  msg4472: держим [prefetchDepth] фраз готовыми. Синтез по-прежнему идёт
     *  ПО ОДНОЙ заявке за раз — так сторож молчания продолжает надзирать за
     *  каждой фразой ровно как раньше, а очередь наполняется цепочкой: доспела
     *  заготовка — просим следующую (см. [synthFinished]). */
    private fun requestPrefetch() {
        if (altDirect) return // #57: в альтернативном способе заготовки не нужны
        if (awaitingPlayText != null) return // текущая фраза ещё не сыграна
        // msg4685: сверяем конвейер с читалкой ДО заказа. Разъехавшийся хвост
        // выбрасываем — тогда offset ниже снова считает от верного места, и
        // очередь зарастает правильно (иначе дыра пережила бы все заготовки).
        firstMisalignedAhead()?.let { bad ->
            Diag.log(
                appContext, "sound",
                "конвейер разошёлся с чтением на позиции ${bad + 1} — выбрасываю хвост и заказываю заново"
            )
            dropAheadFrom(bad)
        }
        val inFlight = if (prefetchInFlightText != null) 1 else 0
        // msg4598: прицепленная встык фраза уже не в очереди, но она впереди —
        // считаем её наравне с остальными, иначе заказали бы её же второй раз.
        val chained = if (chainedPlayer != null || chainInFlight) 1 else 0
        if (readyQueue.size + inFlight + chained >= prefetchDepth) return
        val offset = readyQueue.size + inFlight + chained + 1
        val next = onNeedNext?.invoke(offset) ?: return
        if (next.isBlank() || next == currentText) return
        if (readyQueue.any { it.text == next }) return
        prefetchInFlightText = next
        synthToFile(next)
    }

    /** Полностью обнулить состояние пайплайна (без остановки TTS). */
    private fun resetPipeline() {
        synthWatchToken++
        playWatchToken++
        directWatchToken++
        awaitingPlayText = null
        prefetchInFlightText = null
        prewarmOnly = false
        softRetriedText = null
        directQueue.clear()
        clearPrefetch()
        releaseMedia()
        currentText = null
    }

    private fun clearPrefetch() {
        readyQueue.forEach { runCatching { it.file.delete() } }
        readyQueue.clear()
    }

    // ---------- Первая фраза заранее и пауза без повторного синтеза ----------
    // (24.09.2026, просьба Сергея: «чтение начиналось бы быстрее»)

    /** Приготовить звук фразы, с которой начнётся чтение, ДО нажатия «читать».
     *
     *  Зачем. Единственная задержка перед первым звуком — синтез первой фразы:
     *  по журналу Сергея это 0,54 с на фразу в 10,6 с речи (движок берёт
     *  40–50 мс на секунду звука), плюс 0,03 с на запуск нашего плеера. Отсюда
     *  и 0,58 с между «читать» и звуком; убрать их можно только тем, что файл
     *  готов заранее — окно читалки открывается на нужном месте за секунды до
     *  нажатия. Это ровно та же заготовка, что и во время чтения, только
     *  заказанная раньше.
     *
     *  Готовим ОДНУ фразу ([prewarmOnly]) и только когда ничего не читается:
     *  очередь впрок без звука — это лишняя работа процессора. Не пригодилась
     *  (прыжок, другая книга, чтение так и не начали) — файл осиротеет и будет
     *  удалён, как любая незапрошенная заготовка. */
    fun prewarm(text: String) {
        if (altDirect || text.isBlank()) return
        val t = tts
        if (t == null || !ready) return
        if (currentText != null || awaitingPlayText != null || media != null) return
        if (prefetchInFlightText == text) return
        if (readyQueue.any { it.text == text && it.rate == speed }) return
        if (keptPhrase?.let { it.text == text && it.rate == speed } == true) return
        clearPrefetch()
        prefetchInFlightText = text
        prewarmOnly = true
        Diag.log(appContext, "sound", "готовлю первую фразу заранее (${text.length} знаков)")
        synthToFile(text)
    }

    /** Пауза с сохранением звука звучащей фразы: продолжение зазвучит сразу.
     *
     *  В паузе файл звучащей фразы никуда не девается — его удаляют только конец
     *  фразы или ошибка. А продолжение начинается с ТОЙ ЖЕ фразы (позицию
     *  читалка держит по предложениям и на паузе сохраняет её начало), поэтому
     *  его можно сыграть из того же файла, не заказывая синтез заново: по
     *  журналу это те же 0,5 с на фразу в десять секунд речи.
     *
     *  Скорость держим рядом с файлом: сменили её на паузе — файл прежнего
     *  темпа не играем (см. [takeKeptPhrase]).
     *
     *  Порядок важен: сначала [stop] (он чистит очередь, надзоры и заявки),
     *  потом ставим сохранённую фразу — иначе stop() снёс бы её как старую. */
    fun pauseKeepingPhrase() {
        val text = playingText
        val file = playingFile
        val rate = playingRate
        stop()
        if (text != null && file != null && file.exists() && rate == speed) {
            keptPhrase = Prepared(text, file, rate)
            Diag.log(
                appContext, "sound",
                "пауза: звук фразы сохранён (${file.name}, ${file.length()} байт) — продолжение зазвучит сразу"
            )
        } else {
            file?.delete() // продолжать не с чего — файл не копим
        }
    }

    /** Взять фразу, сохранённую паузой. Пригодилась — отдаём файл, играть его
     *  будет наш плеер. Не та фраза, сменившаяся скорость или пропавший файл —
     *  выбрасываем и читаем как обычно. */
    private fun takeKeptPhrase(text: String): Prepared? {
        val kept = keptPhrase ?: return null
        keptPhrase = null
        if (kept.text == text && kept.rate == speed && kept.file.exists()) {
            Diag.log(appContext, "sound", "продолжаю с сохранённого звука (${kept.file.name})")
            return kept
        }
        if (kept.text == text) {
            Diag.log(
                appContext, "sound",
                "сохранённый звук сделан на скорости ${RateSteps.label(kept.rate)}, " +
                    "сейчас ${RateSteps.label(speed)} — синтезирую заново"
            )
        }
        kept.file.delete()
        return null
    }

    /** Выбросить заготовки, сделанные прежней скоростью ([speed]). Текущий звук
     *  не трогаем: он уже звучит. Фразу в работе ([awaitingPlayText]) тоже не
     *  снимаем — её судьбу решит [synthFinished] по скорости самого файла. */
    private fun dropStaleRate() {
        prefetchInFlightText = null
        prewarmOnly = false
        clearPrefetch()
        dropChain()
        keptPhrase?.let { runCatching { it.file.delete() } }
        keptPhrase = null
    }

    fun shutdown() {
        onDone = null
        onNeedNext = null
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        runCatching { synthDir.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        /** Сторож молчания (msg4077): сколько ждём движок на синтез одной фразы.
         *  msg5309: срок сокращён с 15 с до 6. 23.09.2026: и 6 с оказалось много
         *  (просьба Сергея «давай укорачиваем») — срок больше не одно число, а
         *  расчёт по длине фразы, см. [synthFuseMs]. Границы: [SYNTH_MIN_MS]…
         *  [SYNTH_MAX_MS]. Обычный синтез в логе занимает доли секунды
         *  (13-секундная фраза собралась за 0,95 с, проба голоса — за 0,2–0,39 с),
         *  а лишнее ожидание и есть то самое молчание, на которое жалуются. */
        private const val SYNTH_MIN_MS = 2_500L

        /** Потолок того же срока: длиннее шести секунд ждать нечего даже для
         *  самой длинной нашей фразы в 220 знаков. */
        private const val SYNTH_MAX_MS = 6_000L

        /** Тот же сторож после переспроса (msg5309): фразу у движка уже
         *  переспросили, и если он молчит ещё секунду — перезапускаем движок.
         *  Короткий срок и есть смысл второго шага: не отсчитывать срок заново,
         *  а идти к следующей мере. Обе цифры вместе дают при обычной скорости
         *  3,5–4,7 с до перезапуска вместо прежних 7 с (на самой медленной скорости
         *  потолок в [SYNTH_MAX_MS] возвращает те же 7 с — но там и речь вдвое
         *  длиннее). */
        private const val SYNTH_REASK_STALL_MS = 1_000L

        /** Сколько ждём MediaPlayer на подготовку локального wav-файла.
         *  23.09.2026: было 12 с. Файл лежит на диске и готовится десятые доли
         *  секунды; если за пять секунд не подготовился — он не подготовится,
         *  и выгоднее сразу перейти к прямой речи, чем держать тишину. */
        private const val PLAY_STALL_MS = 5_000L

        /** Как часто сверяем позицию играющего плеера (23.09.2026). */
        private const val POSITION_CHECK_MS = 2_000L

        /** Сколько позиция должна стоять на месте, чтобы считать, что звука нет.
         *  Позиция у локального файла идёт по времени, а не по громкости, и файл
         *  уже подготовлен: стоять четыре секунды на живой речи ей не с чего.
         *  23.09.2026: было 6 с — укорочено вместе с остальными сроками. */
        private const val POSITION_STALL_MS = 4_000L

        /** Прямая речь: срок на СТАРТ. Движок подаёт сигнал «начал говорить» за
         *  доли секунды — наши замеры: синтез 13-секундной фразы 0,95 с, проба
         *  голоса 0,2…0,4 с. Поэтому ждём совсем немного (две секунды, просьба
         *  Сергея 23.09.2026: «6 секунд — это очень много»); не начал — сначала
         *  ПЕРЕСПРАШИВАЕМ фразу (см. [armDirectWatch]), и только если молчит и
         *  после переспроса — пропускаем. Короткий срок при переспросе фразу не
         *  теряет. Было 180 с на всё — три минуты тишины. */
        private const val DIRECT_START_MS = 2_000L

        /** Оценка темпа речи на скорости 1.0: знаков в секунду. */
        private const val CHARS_PER_SEC = 15f

        /** Запас сверх расчётного времени фразы: движок может говорить медленнее,
         *  чем мы считаем, а оборвать живую речь хуже, чем подождать лишнее. */
        private const val DIRECT_TAIL_MS = 4_000L

        /** Границы срока по длине фразы: меньше шести секунд ждать нечего, а
         *  больше сорока пяти — уже ни к чему (наша фраза ≤220 знаков). */
        private const val DIRECT_MIN_MS = 6_000L
        private const val DIRECT_MAX_MS = 45_000L

        /** Перезапусков движка подряд, после которых фразу просто пропускаем. */
        private const val MAX_RESTARTS = 2

        /** Резервный способ перечислить движки через PackageManager, если getEngines пуст. */
        private fun queryEngines(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services = runCatching {
                pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }.getOrElse { emptyList() }
            return services.mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm).toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
                pkg to label
            }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
    }
}
