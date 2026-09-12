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
 * Чтобы между предложениями не было пауз на синтез, следующее предложение
 * синтезируется заранее: пока играет текущее, MainActivity подсказывает
 * текст следующего через [onNeedNext], и мы готовим его впрок.
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
            field = value
            tts?.setSpeechRate(value)
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
     *  пауза. Включено — обрезаем эту тишину, оставляя короткие хвосты.
     *  msg4416: вторая половина той же галочки живёт в ReaderEngine — соседние
     *  короткие предложения уходят в движок одной фразой. Здесь же остаётся
     *  только обрезка краёв: она работает не на всех движках (у DariyaNeural
     *  тишины по краям нет вовсе), но файл не портит. */
    var tightPauses: Boolean = false

    /** Сколько раз за сессию журналируем пустой звук от движка (не спамить). */
    private var emptySoundLogged = 0

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

    /** Возвращает текст следующего предложения для предзагрузки (или null —
     *  книга кончилась). Зовётся на главном потоке, без побочных эффектов. */
    var onNeedNext: (() -> String?)? = null

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

    /** Уже синтезированный файл следующего предложения — готов к мгновенному старту. */
    private var prefetchText: String? = null
    private var prefetchFile: File? = null

    private var reqSeq = 0L
    private val pending = HashMap<Long, Pending>()

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
     *  Сбрасывается любым доспевшим синтезом и на остановке дыхания (stop/start). */
    private var softRetriedText: String? = null

    private class Pending(val kind: Kind, val text: String, val file: File?) {
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
                    override fun onStart(utteranceId: String?) {
                        Diag.log(appContext, "tts", "onStart: движок начал речь ($utteranceId)")
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
        releaseMedia()

        // Готовый заранее файл этого же предложения — играем без задержки.
        // ВАЖНО: забираем файл, НЕ вызывая clearPrefetch() — она удаляет его,
        // и playFile падает с ENOENT (каждая вторая фраза уходила в запасной
        // путь движкового произнесения). Файл удалит сам playFile по завершении.
        if (prefetchText == text && prefetchFile != null) {
            val f = prefetchFile!!
            prefetchFile = null
            prefetchText = null
            awaitingPlayText = null
            playFile(f)
            return
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
        // msg4103: заявка, которая была «в полёте», здесь же и отменяется —
        // tts.stop() её гасит, а pending ниже чистится. Метку «синтез идёт»
        // снимаем вместе с ней: иначе speak() того же предложения (пауза →
        // продолжить) ждал бы ответа по заявке, которой уже нет, и чтение
        // замолкало бы навсегда. Именно это и видно в логе пользователя
        // (ivona.tts): pause → «старт чтения (предл. 145)» → тишина.
        prefetchInFlightText = null
        softRetriedText = null
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

    /** Взвести надзор за синтезом [text]: не отозвался за [SYNTH_STALL_MS] —
     *  считаем движок замолчавшим.
     *
     *  Засекаем и МОМЕНТ взведения надзора (msg4211): в логе тестера сторож
     *  сработал через 42 с вместо 15 — таймеры `postDelayed` не шли, процесс
     *  спал. Часы elapsedRealtime идут и во сне, поэтому разница честно
     *  показывает, сколько устройство простояло замороженным. Засечка — на
     *  каждом взведении: взводят его только по настоящему событию (заявка на
     *  синтез или отсрочка, пока играет предыдущая фраза), и если бы момент
     *  не обновлялся, повтор той же фразы через полчаса выдал бы «спал 1800 с». */
    private fun armSynthWatch(text: String) {
        watchFromMs = SystemClock.elapsedRealtime()
        val token = ++synthWatchToken
        main.postDelayed({ if (token == synthWatchToken) onSynthWatchdog(text) }, SYNTH_STALL_MS)
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
            armSynthWatch(text)
            return
        }
        // Реальное время молчания, а не номинал сторожа: если часы «убежали»
        // далеко за 15 с — значит процесс спал (msg4211, #19).
        val waited = ((SystemClock.elapsedRealtime() - watchFromMs) / 1000).toInt()
            .coerceAtLeast((SYNTH_STALL_MS / 1000).toInt())
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

    /** Взвести надзор за прямой речью движка: `speak()` мог отчитаться успехом и
     *  потом промолчать — тогда `onDone` не придёт и чтение встанет. */
    private fun armDirectWatch(id: Long) {
        val token = ++directWatchToken
        main.postDelayed({
            if (token != directWatchToken) return@postDelayed
            if (pending.remove(id) == null) return@postDelayed
            Diag.log(
                appContext, "tts",
                "прямая речь молчит ${DIRECT_STALL_MS / 1000} с — пропускаю фразу, чтение идёт дальше"
            )
            retriesInRow = 0
            onDone?.invoke()
        }, DIRECT_STALL_MS)
    }

    // ---------- Синтез ----------

    /** Запустить синтез [text] в файл. По завершении судьба файла решается в
     *  [synthFinished]: текст ждёт [awaitingPlayText] — играем, иначе копим впрок. */
    private fun synthToFile(text: String) {
        val t = tts
        if (t == null || !ready) return
        val file = File(synthDir, "s_${reqSeq}.wav")
        val id = reqSeq++
        pending[id] = Pending(Pending.Kind.FILE, text, file)
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
                // Страховочное произнесение движком закончилось само.
                retriesInRow = 0 // движок жив — счётчик перезапусков обнуляем
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
                if (tightPauses) trimSilence(f)
                if (p.text == awaitingPlayText) {
                    // Это тот текст, который уже попросили говорить.
                    awaitingPlayText = null
                    if (prefetchInFlightText == p.text) prefetchInFlightText = null
                    playFile(f)
                } else if (p.text == prefetchInFlightText) {
                    // Упреждающий синтез завершился, пока его ещё не просили.
                    // msg4077: засечка в лог — без неё по логу не видно, доспела
                    // заготовка или синтез молча повис.
                    Diag.log(appContext, "sound", "заготовка готова: ${f.name} (${f.length()} байт)")
                    prefetchInFlightText = null
                    prefetchText = p.text
                    prefetchFile = f
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
        if (p.cancelled || p.kind != Pending.Kind.FILE) return
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

        val headKeep = sampleRate / 20 // 50 мс
        val tailKeep = sampleRate / 12 // ~83 мс
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

    private fun playFile(f: File) {
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
                it.setVolume(volume, volume)
                it.start()
                retriesInRow = 0 // звук пошёл — движок и плеер живы
                Diag.log(appContext, "sound", "играю свой звук: ${f.name} (${f.length()} байт)")
                // Предложение начало звучать — готовим следующее заранее.
                requestPrefetch()
            }
            mp.setOnCompletionListener {
                // msg4402 (диагностика пауз): конец фразы. Разница отметок
                // «фраза доиграла» → следующее «играю свой звук» — это и есть
                // пауза между предложениями; по ней видно, помогла ли обрезка.
                Diag.log(appContext, "sound", "фраза доиграла: ${f.name}")
                runCatching { it.release() }
                if (media === it) media = null
                f.delete()
                onDone?.invoke()
            }
            mp.setOnErrorListener { p, what, extra ->
                playWatchToken++
                runCatching { p.release() }
                if (media === p) media = null
                f.delete()
                if (gen != playGen) return@setOnErrorListener true
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
        media?.let { m ->
            runCatching { m.stop() }
            runCatching { m.release() }
        }
        media = null
    }

    /** Пока играет текущее — спросить MainActivity, какое предложение следующее,
     *  и синтезировать его заранее, чтобы между предложениями не было паузы. */
    private fun requestPrefetch() {
        if (awaitingPlayText != null) return // текущее предложение ещё не сыграно
        val next = onNeedNext?.invoke() ?: return
        if (next.isBlank() || next == currentText) return
        if (next == prefetchText && prefetchFile != null) return
        if (next == prefetchInFlightText) return
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
        softRetriedText = null
        clearPrefetch()
        releaseMedia()
        currentText = null
    }

    private fun clearPrefetch() {
        prefetchFile?.let { runCatching { it.delete() } }
        prefetchText = null
        prefetchFile = null
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
         *  Обычный синтез в логе занимает доли секунды — 15 с это запас в десятки раз. */
        private const val SYNTH_STALL_MS = 15_000L

        /** Сколько ждём MediaPlayer на подготовку локального wav-файла. */
        private const val PLAY_STALL_MS = 12_000L

        /** Прямая речь движка: медленный темп и длинная фраза могут звучать долго,
         *  поэтому срок щедрый — тишина дольше трёх минут уже точно сбой. */
        private const val DIRECT_STALL_MS = 180_000L

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
