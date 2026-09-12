package com.cuber.bookvoice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Движок чтения BookVoice (#38, шаг 1).
 *
 * Зачем. Пока «сердце» чтения — состояние книги, плеер, цикл воспроизведения,
 * аудиофокус, обработка звонков и сохранение места — жило в полях MainActivity,
 * оно умирало вместе с окном читалки: закрыл книгу (ушёл на полку) → голос гаснет.
 * Чтобы потом можно было слушать книгу, гуляя по каталогу/полке, и сделать виджет,
 * сердце должно жить вне окна. Этот движок — процессный синглтон: владеет всем
 * состоянием чтения и циклом, а окно (MainActivity) остаётся просто экраном,
 * который подписан на события движка и рисует по ним.
 *
 * Шаг 1 (этот файл): поведение НЕ меняется. MainActivity по-прежнему создаёт
 * движок при открытии и гасит при закрытии окна ([close]); чтение так же
 * останавливается, когда читалка закрыта. Меняется только то, ГДЕ живёт
 * состояние. Следующими шагами уберём привязку к окну («слушать без окна») и
 * нарастим виджет.
 *
 * UI-слой подключается через [host]: движок зовёт окно только когда ему нужно
 * что-то нарисовать (прокрутить список, обновить кнопку, сказать тост). Если
 * окна нет — вызовы просто не доходят никуда, чтение продолжается.
 */

/** Место в книге: глава + предложение. Общий тип окна и движка — на нём
 *  история переходов (#101), выделение (#105), «прочитать кусок» (#106) и
 *  охрана места от тихого сброса (msg1139). */
internal data class Place(val chapter: Int, val sentence: Int)

internal object ReaderEngine {

    // ---------------- UI-хост (окно читалки) ----------------

    /** Что движку нужно от экрана. Реализует MainActivity; может отсутствовать
     *  (чтение без окна). Все методы — на главном потоке. */
    interface Host {
        /** Прокрутить список к текущему предложению и обновить позицию. */
        fun onShowCurrent()
        /** Перезагрузить список текущей главы (смена главы). */
        fun onChapterLoaded()
        /** Обновить кнопку «Читать/Пауза». */
        fun onPlayStateChanged()
        /** Нарисовать текущее предложение [s] (смена внутри главы). */
        fun onMovedInChapter(s: Int)
        /** Сказать тост. */
        fun onToast(msg: String)
        /** Озвучить текст (announceForAccessibility). */
        fun onAnnounce(text: String)
        /** Обновить подпись скорости и статистику (после смены голоса/скорости). */
        fun onSpeedUiRefresh()
    }

    @Volatile
    var host: Host? = null

    // ---------------- Контекст и каналы ----------------

    private lateinit var ctx: Context

    /** Версия приложения строкой — её пишем в журнал при каждом старте чтения.
     *  В шапке журнала [Diag.header] версия есть, но она одна на запуск и при
     *  длинном логе уезжает за обрезку: по присланному куску не видно, какая
     *  сборка тестируется (вопрос Сергея, msg4424). */
    private val versionLabel: String by lazy {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
            .getOrDefault("?")
    }
    private val main = Handler(Looper.getMainLooper())

    private val prefs get() = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val audioManager get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Плеер озвучки — живёт в движке, не в окне. */
    var player: SpeechPlayer? = null
        private set

    // ---------------- Состояние чтения ----------------

    var book: BookDocument? = null
    var chapterIdx = 0
    var sentenceIdx = 0
    var playing = false

    /** msg4629: владелец нажал «Выход» в карточке шторки. Пока флаг стоит, окно
     *  читалки, закрываясь на паузе, карточку НЕ держит — иначе вышел бы и снова
     *  получил её. Снимается при возврате окна в движок ([attach]). */
    private var cardDroppedByUser = false

    // msg4416: сколько предложений ушло в движок одной фразой при коротких
    // паузах (см. [chunkSpan]). Обычно 1; при склейке — до [TIGHT_CHUNK_MAX_UNITS].
    // Нужно, чтобы после озвученной фразы переставить позицию ровно на столько
    // же предложений вперёд.
    private var spokenUnits = 1

    // ——— Таймер сна (msg2567) ———
    // Живёт в движке, а не в окне: чтение «без окна» продолжается в фоне, и
    // таймер должен досчитать и поставить на паузу независимо от того, есть ли
    // на экране читалка. Отсчёт — по wall-clock (elapsedRealtime): «уснуть через
    // 20 минут» означает через 20 минут реального времени, как в плеерах.
    const val SLEEP_OFF = 0
    const val SLEEP_MINUTES = 1
    const val SLEEP_CHAPTER = 2

    @Volatile
    var sleepMode = SLEEP_OFF

    @Volatile
    var sleepMinutes = 0

    @Volatile
    private var sleepEndAt = 0L

    private val sleepRunnable = Runnable { fireSleepTimer() }

    val sleepTimerActive: Boolean get() = sleepMode != SLEEP_OFF
    var continuous = false

    // Прогресс по всей книге — для слайдера перемотки и оценки времени чтения.
    var chapterStart = intArrayOf()
    var cumWords = longArrayOf()

    // #102: свой голос/скорость книги — зеркало полей BookRecord открытой книги.
    var voiceName: String? = null
    var perBookEngine: String? = null
    var perBookVoice: String? = null
    var perBookSpeed: Float? = null
    var voiceMissingAnnounced = false

    // msg1137/1139: книга восстановилась на (restoredPlace); userMoved — была ли
    // после открытия ручная навигация.
    var restoredPlace: Place? = null
    var userMoved = false
    // msg1137/1139/0.3.46: пока идёт открытие книги, любой goTo(0,0) — авто-сброс.
    var openingWindow = false

    // msg2093: «Отступать назад при старте». startRewindPending — одноразовый
    // откат на N предложений при ПЕРВОМ старте чтения после открытия книги на
    // сохранённом месте (0 = выключено). rewindFloor — место остановки, НИЖЕ
    // которого не пишем позицию (prefs/запись), пока чтение не дойдёт до него:
    // так откат не «сползает» назад при повторных открытиях-закрытиях без чтения.
    var startRewindPending = 0
    private var rewindFloor: Place? = null

    // msg4377: когда запись книги (readPct для полки) писалась в последний раз,
    // и стоит ли уже отложенный досыл. Ползунок шлёт переход на каждый сдвиг —
    // писать JSON книги на каждое движение нельзя.
    private var lastRecordWriteAt = 0L
    private var recordSyncScheduled = false
    private const val RECORD_WRITE_MS = 1000L

    // msg2679: движок синтеза грузится (особенно при смене на голос книги) — чтобы
    // одно нажатие «Читать» не терялось, старт откладывается и повторяется сам.
    // startQueued не даёт наслоить несколько параллельных цепочек ожидания.
    private var startQueued = false

    // #106: конец куска при «Прочитать выделенное».
    var rangeEnd: Place? = null

    // #98: авто-продолжение после настоящего звонка.
    var inCall = false
    var wasReadingAtCallStart = false
    var lastTransientPauseAt = 0L

    // #99: пауза при отключении наушников (ACTION_AUDIO_BECOMING_NOISY).
    private var noisyRegistered = false

    private var phoneListening = false

    var currentUri: String? = null
    var currentName: String? = null

    // ---------------- Аудиофокус ----------------

    private var haveAudioFocus = false
    private var focusRetried = false
    private var audioFocusReq: android.media.AudioFocusRequest? = null

    /** Чтение было прервано тем, что заиграл ДРУГОЙ плеер (а не остановлено
     *  пользователем). Когда тот плеер замолчит и фокус вернётся к нам —
     *  читалка продолжит сама. */
    var pausedByFocusLoss = false

    private fun focusLabel(code: Int): String = when (code) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN (фокус вернулся)"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS (забрали насовсем)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT (короткая потеря)"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "CAN_DUCK (можно тише)"
        else -> "код $code"
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Diag.log(
            ctx, "focus",
            "событие фокуса: ${focusLabel(change)}, playing=$playing, pausedByFocusLoss=$pausedByFocusLoss"
        )
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> main.post {
                haveAudioFocus = false
                if (playing) pausePlayback(keepFocus = true, byFocusLoss = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> main.post {
                if (playing) lastTransientPauseAt = SystemClock.elapsedRealtime()
                pausePlayback(keepFocus = true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> main.post {
                haveAudioFocus = true
                if (pausedByFocusLoss && book != null && !playing &&
                    prefs.getBoolean(MainActivity.KEY_AUTO_RESUME, true)
                ) {
                    pausedByFocusLoss = false
                    Diag.log(ctx, "focus", "возврат фокуса — продолжаю чтение сам")
                    requestStart()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
        }
    }

    /** #99: наушники отключились (звук ушёл бы в динамик) — ставим чтение на
     *  паузу. Сами не продолжаем: пользователь сам решает, когда вернуться. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (playing) pausePlayback(keepFocus = true)
        }
    }

    // ---------------- Жизненный цикл движка ----------------

    /** Подключить движок к окну (MainActivity.onCreate). Если плеер ещё не создан
     *  (первое открытие или после [close]) — создаём и настраиваем. Окно
     *  регистрируется как [host], медиа-команды теперь идут движку. */
    @Synchronized
    fun attach(activityContext: Context) {
        val wasFresh = player == null
        ctx = activityContext.applicationContext
        if (wasFresh) initPlayer()
        MediaSessionService.listener = mediaCommands
        // msg4629: окно снова с нами — «выход» из карточки больше не в силе,
        // карточка (её поднимет ensureMediaService при старте) снова наша.
        cardDroppedByUser = false
    }

    private fun initPlayer() {
        val sp = SpeechPlayer(ctx)
        sp.onDone = { main.post { if (playing) onUtteranceDone() } }
        // msg4472: плеер держит очередь заготовок и спрашивает фразу не только
        // «следующую», но и через одну-две вперёд — отсюда параметр offset.
        sp.onNeedNext = { offset -> peekNextText(offset) }
        sp.speed = prefs.getFloat(MainActivity.KEY_SPEED, 1f)
        sp.pitch = prefs.getFloat(MainActivity.KEY_PITCH, 1f)
        sp.volume = prefs.getFloat(MainActivity.KEY_VOLUME, 1f)
        // msg4402: короткие паузы между предложениями (просьба тестера).
        sp.tightPauses = prefs.getBoolean(MainActivity.KEY_TIGHT_PAUSES, true)
        // msg4598: бесшовная передача звука встык — тестовая галочка, по
        // умолчанию выкл (сравнение двух стыков на слух).
        sp.gapless = prefs.getBoolean(MainActivity.KEY_GAPLESS, false)
        // Чтение всегда непрерывное: кнопка «по одному предложению» убрана.
        continuous = true
        voiceName = prefs.getString(MainActivity.KEY_VOICE, null)
        player = sp
    }

    /** Окно читалки закрылось — чтение гаснет (как раньше onDestroy). Сессию и
     *  сервис гасим, чтобы не отбирать медиа-кнопки у других плееров. Фокус
     *  отдаём и авто-продолжение гасим: вернуться «по фокусу» уже некому. */
    @Synchronized
    fun close() {
        unregisterNoisyReceiver()
        unregisterPhoneListener()
        host = null
        pausedByFocusLoss = false
        dropAudioFocus()
        playing = false
        KeepAwake.release() // #19: читалка закрылась — блокировку снимаем
        cancelSleepTimer()
        MediaSessionService.stop(ctx)
        player?.shutdown()
        player = null
        book = null
        // msg2093: с книгой гасим и откат при старте — новое открытие перевооружит.
        startRewindPending = 0
        rewindFloor = null
    }

    /** Окно читалки снова на экране (MainActivity.onStart): вешаем слушателей
     *  звонков/наушников. Сами по себе они не трогают чтение. */
    fun onWindowStart() {
        registerNoisyReceiver()
        registerPhoneListener()
    }

    /** Окно ушло с экрана (MainActivity.onStop). */
    fun onWindowStop() {
        unregisterNoisyReceiver()
        unregisterPhoneListener()
    }

    /** Окно читалки закрылось, а чтение звучит (#38, шаг 2) — продолжаем читать
     *  без окна. Движок остаётся жить целиком (книга, плеер, медиа-сервис,
     *  аудиофокус); гаснет только связь с UI. Управление дальше — гарнитура,
     *  «волшебное касание» TalkBack и кнопка в шторке. Следующее открытие той же
     *  книги (см. rejoinLiveReading) подхватит живое состояние. НЕ зовём [close]:
     *  он загасил бы звук, сессию и книгу. */
    @Synchronized
    fun windowGoneWhilePlaying() {
        host = null
        Diag.log(ctx, "activity", "окно закрыто, чтение без окна продолжается")
    }

    /** Окно читалки закрылось, а книга стоит на паузе (msg4629: «пусть карточка
     *  висит, пока я не выйду сам»). Карточку в шторке НЕ гасим: служба остаётся
     *  в переднем плане, кнопка «играть» в ней поднимет чтение без окна
     *  ([startSpeakingCurrent] сам просит фокус и добивает службу). Движок держит
     *  книгу и плеер, но звука нет и процессор не занят: фокус отдаём, будильник
     *  снимаем, место фиксируем. Гасит всё это только «Выход» в карточке
     *  ([onMediaExit]) — или [close], если книги уже нет. */
    @Synchronized
    fun windowGoneWhilePaused(window: Host?) {
        // Уже другое окно у руля — чужое закрытие нам не указ (гонка переоткрытия).
        if (host != null && host !== window) return
        host = null
        dropAudioFocus() // стоим — чужой плеер приглушать не за чем
        KeepAwake.release()
        savePosition()
        Diag.log(ctx, "activity", "окно закрыто, книга на паузе — карточка остаётся")
    }

    /** Держать ли карточку, когда окно закроется на паузе (msg4629). Не держим,
     *  если книги нет вовсе или владелец только что вышел «Выходом» из карточки. */
    fun keepCardWhenWindowGone(): Boolean = book != null && player != null && !cardDroppedByUser

    /** Живёт ли чтение без окна? Возвращает uri книги, которую движок держит,
     *  когда UI-хоста нет. Окно читалки решает по нему, переподключиться к живой
     *  книге или открывать холодно. null — окно было закрыто на паузе ([close])
     *  или чтения нет вовсе. */
    @Synchronized
    fun liveWindowlessUri(): String? =
        if (host == null && book != null && player != null) currentUri else null

    // ---------------- Команды с гарнитуры/TalkBack ----------------

    private val mediaCommands = object : MediaSessionService.Listener {
        override fun onMediaPlay() {
            main.post {
                if (book == null) return@post
                if (!playing) requestStart()
            }
        }

        override fun onMediaPause() {
            main.post {
                if (playing) {
                    // Пользовательская пауза — фокус держим, чтобы следующий
                    // магик-тап снова попал к нам.
                    pausePlayback(keepFocus = true)
                }
            }
        }

        /** «Выход» из карточки в шторке (msg4073). Владелец слушает без окна и
         *  хочет закончить: гасим чтение, отпускаем фокус (чтобы музыка из
         *  другого плеера не осталась приглушённой), сохраняем место. Карточку
         *  убирает служба — она же и прислала эту команду. Если чтение стояло
         *  на паузе, фокус всё равно отдаём: пауза его держит намеренно, но
         *  после «Выхода» возвращаться к нам уже некому. */
        override fun onMediaExit() {
            main.post {
                if (book == null) return@post
                // msg4629: вышел — карточку больше не держим (окно, если ещё
                // открыто, закрываясь на паузе, погасит всё как раньше).
                cardDroppedByUser = true
                Diag.log(ctx, "activity", "«выход» из карточки: чтение встало")
                if (playing) {
                    pausePlayback(keepFocus = false)
                } else {
                    dropAudioFocus()
                    savePosition()
                    pushPlayState()
                }
            }
        }

        override fun onMediaSkip(delta: Int) {
            // Кнопка «назад» (-1) / «вперёд» (+1) гарнитуры. Что она делает — своя
            // настройка на кнопку (msg2136). По умолчанию — «Предложение»: кнопка
            // листает книгу в свою сторону; шло чтение — продолжится с нового места
            // (как навигация в окне). «Выключено» — кнопка ничего не делает: пауза/
            // продолжение у Сергея приходят отдельной командой (двойное постукивание
            // = play/pause), поэтому вешать их на next/prev больше не нужно (msg2152).
            main.post {
                if (book == null) return@post
                val key = if (delta < 0) MainActivity.KEY_HS_PREV else MainActivity.KEY_HS_NEXT
                val step = prefs.getString(key, MainActivity.HS_SENTENCE)
                    ?: MainActivity.HS_SENTENCE
                if (step == MainActivity.HS_OFF) return@post
                navigateByHeadset(step, delta)
            }
        }
    }

    // ---------------- Кнопки гарнитуры: навигация по шагу (msg2136) ----------------

    /** Выполнить шаг кнопкой гарнитуры: [step] — предложение/абзац/глава, [delta] —
     *  направление (-1 «назад», +1 «вперёд»). Общий путь [goTo]: шло чтение — оно
     *  продолжится с нового места; стояло — позиция переезжает (окно, если есть,
     *  перерисуется). Если после шага на паузе и что-то сдвинулось — озвучим, куда
     *  встали, иначе кнопка «молчит» (как свайп по жесту — jumpAndAnnounce в окне). */
    private fun navigateByHeadset(step: String, delta: Int) {
        val bk = book ?: return
        val target = when (step) {
            MainActivity.HS_SENTENCE -> headsetSentenceTarget(bk, delta)
            MainActivity.HS_PARAGRAPH -> headsetParagraphTarget(bk, delta)
            else -> headsetChapterTarget(bk, delta) // HS_CHAPTER
        } ?: return
        if (target.chapter == chapterIdx && target.sentence == sentenceIdx) return
        goTo(target.chapter, target.sentence)
        if (!playing) host?.onAnnounce(positionPhrase())
    }

    /** Ближайшее предложение в сторону [delta] (граница главы переходит в соседнюю,
     *  как кнопки «Пред/След» в читалке). null — упёрлись в край книги. */
    private fun headsetSentenceTarget(bk: BookDocument, delta: Int): Place? {
        var ch = chapterIdx
        var s = sentenceIdx + delta
        if (s < 0) {
            if (ch <= 0) return null
            ch--
            s = bk.chapters[ch].sentences.size - 1
        } else if (s >= bk.chapters[ch].sentences.size) {
            if (ch + 1 >= bk.chapters.size) return null
            ch++
            s = 0
        }
        return Place(ch, s)
    }

    /** Абзац в сторону [delta]: ближайшее предложение с paragraphStart — абзацы
     *  размечены парсером (Model.Sentence), и по ним же визуально отделяются строки
     *  списка. Внутри главы — её абзацы; на краю главы — переход в соседнюю (её
     *  первый/последний абзац). Стоя посреди абзаца и листая «назад», попадаем в
     *  его начало; следующий «назад» — в начало предыдущего абзаца. */
    private fun headsetParagraphTarget(bk: BookDocument, delta: Int): Place? {
        val cur = bk.chapters[chapterIdx].sentences
        if (delta > 0) {
            for (i in sentenceIdx + 1 until cur.size) {
                if (cur[i].paragraphStart) return Place(chapterIdx, i)
            }
            // абзацев в главе больше нет — следующий раздел книги
            if (chapterIdx + 1 >= bk.chapters.size) return null
            return firstParagraphOf(bk, chapterIdx + 1)
        }
        for (i in sentenceIdx - 1 downTo 0) {
            if (cur[i].paragraphStart) return Place(chapterIdx, i)
        }
        // стоим в первом абзаце главы: в первой главе — начало книги, иначе —
        // последний абзац предыдущей главы
        if (chapterIdx == 0) return if (sentenceIdx == 0) null else Place(0, 0)
        val prev = bk.chapters[chapterIdx - 1].sentences
        if (prev.isEmpty()) return null
        var i = prev.indexOfLast { it.paragraphStart }
        if (i < 0) i = 0
        return Place(chapterIdx - 1, i)
    }

    /** Первое предложение первого абзаца главы [ch] — куда встаёт «следующий
     *  абзац» на границе глав. */
    private fun firstParagraphOf(bk: BookDocument, ch: Int): Place? {
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
        if (cur.isEmpty()) return null
        val i = cur.indexOfFirst { it.paragraphStart }
        return Place(ch, if (i >= 0) i else 0)
    }

    /** Глава в сторону [delta] — та же линейка, что «Предыдущая/Следующая глава»
     *  в читалке (chapterStopIndexes учитывает настройку «Кнопки глав шагают»,
     *  0.3.37). Историю переходов (#101, pushPlace) не трогаем — это кнопка. */
    private fun headsetChapterTarget(bk: BookDocument, delta: Int): Place? {
        val stops = chapterStopIndexes()
        if (stops.isEmpty()) return null
        val cur = chapterIdx
        var target = -1
        if (delta > 0) {
            for (s in stops) if (s > cur) { target = s; break }
        } else {
            for (i in stops.indices.reversed()) if (stops[i] < cur) { target = stops[i]; break }
        }
        if (target < 0) return null
        return Place(target, 0)
    }

    /** По каким «главам» ходит шаг «глава» кнопок гарнитуры — копия линейки окна
     *  (MainActivity.chapterStopIndexes): фильтр плоского списка глав по разметке
     *  FB2 и настройке CH_NAV. Дублируем, чтобы гарнитура работала и без окна
     *  (движок — процессный синглтон); держать в синхроне с окном. */
    private fun chapterStopIndexes(): IntArray {
        val bk = book ?: return intArrayOf()
        val mode = prefs.getString(MainActivity.KEY_CH_NAV, MainActivity.CH_NAV_ALL)
        val size = bk.chapters.size
        if (mode == MainActivity.CH_NAV_ALL) return IntArray(size) { it }
        val out = ArrayList<Int>(size)
        for (i in 0 until size) {
            val ch = bk.chapters[i]
            if (mode == MainActivity.CH_NAV_MAJOR) {
                if (ch.major) out.add(i)
            } else if (!ch.nested) { // CH_NAV_CHAPTERS
                out.add(i)
            }
        }
        return out.toIntArray()
    }

    /** Строка позиции для озвучки после шага кнопкой гарнитуры на паузе — та же,
     *  что окно показывает в строке позиции (updatePosition). */
    private fun positionPhrase(): String {
        val bk = book ?: return ""
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences
        val size = bk.chapters.size
        val pos = if (cur != null && cur.isNotEmpty()) {
            " · ${sentenceIdx.coerceIn(0, cur.size - 1) + 1} из ${cur.size}"
        } else ""
        return "Глава ${chapterIdx + 1} из $size$pos"
    }

    // ---------------- Запуск и остановка чтения ----------------

    /** Начать чтение, если движок ещё инициализируется — подождать и повторить. */
    fun maybeAutoStart() {
        if (book == null || playing) return
        requestStart()
    }

    /** msg2093: вооружить откат при старте — зовёт окно, когда книга открылась на
     *  сохранённом месте (восстановление, НЕ явный переход из цитаты). Применяется
     *  один раз при первом реальном старте чтения; для книги с самого начала
     *  (0,0) отступать некуда. Новое открытие книги перевооружает заново. */
    fun armStartRewind() {
        startRewindPending = 0
        rewindFloor = null
        if (book == null) return
        if (chapterIdx == 0 && sentenceIdx == 0) return
        val n = when (
            prefs.getString(MainActivity.KEY_START_REWIND, MainActivity.START_REWIND_NONE)
        ) {
            MainActivity.START_REWIND_2 -> 2
            MainActivity.START_REWIND_5 -> 5
            else -> 0
        }
        startRewindPending = n
        Diag.log(ctx, "activity", "откат при старте вооружён: на $n предл. с места глава $chapterIdx/$sentenceIdx")
    }

    /** Надёжный старт чтения: если движок синтеза не готов, пробуем ещё
     *  несколько раз (движок грузится в фоне после открытия книги). */
    fun requestStart() {
        val p = player ?: return
        if (book == null || playing) return
        if (!p.isReady) {
            if (!startQueued) {
                startQueued = true
                retryStart(0)
            }
            return
        }
        startSpeakingCurrent()
    }

    private fun retryStart(attempt: Int) {
        // msg2679: смена движка на голос книги может грузиться дольше прежних
        // 2.5с — даём ~5с попыток, потом молча сдаёмся.
        if (attempt > 20) {
            startQueued = false
            return
        }
        main.postDelayed({
            val p = player ?: return@postDelayed
            if (book == null || playing) {
                startQueued = false
                return@postDelayed
            }
            if (!p.isReady) {
                retryStart(attempt + 1)
            } else {
                startQueued = false
                startSpeakingCurrent()
            }
        }, 250)
    }

    /** Поднять медиа-сервис. Карточка рождается при ОТКРЫТИИ книги, в состоянии
     *  паузы (0.3.88, msg1840). [playing] — состояние ПЕРВОГО уведомления. */
    fun ensureMediaService(playing: Boolean = true) {
        val bk = book ?: return
        // msg4629: вместе с книгой передаём её uri — по нему карточка шторки
        // возвращает окно читалки к живой книге (касание карточки).
        MediaSessionService.start(ctx, bk.title ?: currentName, playing = playing, uri = currentUri)
    }

    /** msg2093: одноразовый откат при первом старте чтения. Срабатывает только
     *  если книга открылась на сохранённом месте, ручной навигации не было и это
     *  первый реальный старт с этой позиции (pending снимается в любом случае —
     *  дальше за это открытие не откатываем). Возвращает true, если позиция
     *  сдвинута назад на N предложений. */
    private fun applyStartRewindIfPending(): Boolean {
        if (startRewindPending <= 0) return false
        val n = startRewindPending
        startRewindPending = 0
        val rp = restoredPlace ?: return false
        if (userMoved) return false // читатель уже сам выбрал место — не вмешиваемся
        if (playing) return false
        if (chapterIdx != rp.chapter || sentenceIdx != rp.sentence) return false
        val bk = book ?: return false
        if (rp.chapter == 0 && rp.sentence == 0) return false
        // Место остановки держим «полом», ниже которого не пишем позицию, пока
        // чтение не дойдёт до него (анти-дрейф: повторные открытия не сползают).
        rewindFloor = rp
        var ch = chapterIdx
        var s = sentenceIdx
        repeat(n) {
            if (s > 0) {
                s--
            } else if (ch > 0) {
                ch--
                s = bk.chapters[ch].sentences.size - 1
            }
        }
        if (ch == chapterIdx && s == sentenceIdx) {
            rewindFloor = null // откатываться некуда — начало книги
            return false
        }
        Diag.log(
            ctx, "activity",
            "откат при старте: глава $chapterIdx/$sentenceIdx → $ch/$s (держу место ${rp.chapter}/${rp.sentence})"
        )
        chapterIdx = ch
        sentenceIdx = s
        if (ch != rp.chapter) host?.onChapterLoaded() else host?.onMovedInChapter(s)
        return true
    }

    /** Начать чтение с текущей позиции (см. исходный startSpeakingCurrent в
     *  MainActivity). Держим открытым — окно зовёт его по жесту «повторить». */
    fun startSpeakingCurrent() {
        val bk = book ?: return
        val p = player ?: return
        // msg1139: если позицию тихо сбросило к началу — стартуем не с (0,0),
        // а с места, на котором книга открылась.
        guardResetToStart()
        if (bk.chapters.getOrNull(chapterIdx)?.sentences.isNullOrEmpty()) return
        if (!p.isReady) {
            // msg2679: «Читать» нажали, а движок ещё грузится (смена на голос
            // книги) — не глотаем нажатие молча и не просим жать снова: объявляем
            // один раз и стартуем сами, как только движок готов (startQueued).
            if (!startQueued) {
                startQueued = true
                host?.onToast("Движок ещё готовится, чтение начнётся само")
                retryStart(0)
            }
            return
        }
        // msg2093: откат применяем в момент реального начала речи — если движок
        // ещё не готов, старт откладывается и откат применится при повторе.
        applyStartRewindIfPending()
        // Пользователь запускает чтение явно — прерванность чужим плеером
        // больше не актуальна, сами не «оживём» не вовремя.
        pausedByFocusLoss = false
        // msg4629: чтение снова пошло (окно, гарнитура) — «выход» из карточки
        // отменён: закрываясь на паузе, окно снова оставит карточку владельцу.
        cardDroppedByUser = false
        playing = true
        // msg4211 (#19): чтение начинается — держим процессор, чтобы телефон,
        // лежащий экраном вниз, не заснул между фразами (лог тестера: сторож
        // сработал через 42 с вместо 15 — таймеры не шли, процесс спал).
        // Отпускаем на паузе/конце книги/закрытии читалки, иначе батарея.
        KeepAwake.acquire(ctx)
        ensureMediaService()
        Diag.log(
            ctx, "activity",
            "старт чтения (глава $chapterIdx, предл. $sentenceIdx); движок=${p.enginePackage}, " +
                "голос=$voiceName, версия $versionLabel"
        )
        requestAudioFocus()
        host?.onShowCurrent()
        speakCurrent()
        pushPlayState()
    }

    /** Текст, который будет реально озвучен на позиции (ch, s). На первом
     *  предложении главы спереди добавляется её название (если есть и в
     *  настройках включено «Озвучивать название главы в начале»). Название,
     *  дословно совпадающее с первым предложением, не дублируем. */
    private fun spokenText(ch: Int, s: Int): String? {
        val bk = book ?: return null
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
        val text = cur.getOrNull(s)?.text ?: return null
        if (s != 0 || !prefs.getBoolean(MainActivity.KEY_SAY_CHAPTER_START, true)) return text
        val title = bk.chapters[ch].title?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        return if (title == text.trim()) text else "$title. $text"
    }

    private fun speakCurrent() {
        spokenUnits = chunkSpan(chapterIdx, sentenceIdx)
        var t = chunkText(chapterIdx, sentenceIdx, spokenUnits)
        // msg4426: фраза из одних знаков (в книге «‹…›» — маркер пропуска) — для
        // движка пустой звук: он отдаёт файл в 44 байта, а мы всё равно тратим
        // цикл плеера и слышим паузу в четверть секунды. Пропускаем такие
        // фразы, не отдавая их движку вовсе. Ограничитель — чтобы битый текст
        // или дыра в разборе не гоняли нас по кругу.
        var guard = 0
        while (t == null && guard++ < 8) {
            if (!advanceUnits(spokenUnits.coerceAtLeast(1))) {
                stopAtEnd()
                return
            }
            spokenUnits = chunkSpan(chapterIdx, sentenceIdx)
            t = chunkText(chapterIdx, sentenceIdx, spokenUnits)
        }
        if (t == null) return
        player?.speak(t)
    }

    // ——— Короткие паузы: склейка соседних предложений (msg4416) ———
    // Просьба тестера @Spartach72: паузы между предложениями. Обрезка тишины по
    // краям файла не помогла — движок отдаёт файл впритык (лог Сергея 08:59:32:
    // «тишины по краям нет (голова 23 мс, хвост 0 мс)»). Значит пауза сидит в
    // самой речи: синтезатор делает её в конце КАЖДОЙ фразы, потому что каждая
    // фраза для него — отдельный кусок текста. Отдаём соседние предложения одной
    // строкой: точка оказывается внутри фразы, а не в конце, и пауза на ней
    // короче — плюс пропадает наш перезапуск плеера (90–120 мс, измерено по тому
    // же логу).

    /** Потолок длины склеенной фразы в символах и в предложениях. Длинную фразу
     *  синтезатор читает хуже, а пауза и прыжок посреди неё откатывают её целиком. */
    private const val TIGHT_CHUNK_MAX = 220
    private const val TIGHT_CHUNK_MAX_UNITS = 4

    /** Сколько предложений с [s] уйдёт в движок одной фразой (1 — если склейка
     *  выключена). Без побочных эффектов: позицию не двигает. */
    private fun chunkSpan(ch: Int, s: Int): Int {
        if (player?.tightPauses != true) return 1
        val cur = book?.chapters?.getOrNull(ch)?.sentences ?: return 1
        var n = 1
        var len = cur.getOrNull(s)?.text?.length ?: return 1
        while (n < TIGHT_CHUNK_MAX_UNITS && s + n < cur.size) {
            // Абзац — смысловая граница (в дневниках это новая запись, новая
            // дата): через неё не склеиваем. Так пауза на границе абзаца
            // остаётся настоящей и слышимой, а укорачивается только то, что
            // внутри абзаца.
            if (cur[s + n].paragraphStart) break
            val next = cur[s + n].text.length
            if (len + next > TIGHT_CHUNK_MAX) break
            len += next
            n++
        }
        // #106: выделенный кусок не читаем дальше его конца — движок в
        // [advanceOneUnit] останавливается, дойдя до rangeEnd, значит дальше
        // него в фразу не заглядываем.
        val re = rangeEnd
        if (re != null && re.chapter == ch && re.sentence > s) n = minOf(n, re.sentence - s)
        return n.coerceAtLeast(1)
    }

    /** Текст, пригодный к озвучке: книжные маркеры пропуска «<…>» и «‹…›»
     *  движок читает словами — «меньше», «больше» (жалоба Сергея, msg4438).
     *  Убираем угловые скобки, многоточие оставляем: на месте пропуска остаётся
     *  пауза, а не пропадает весь кусок. */
    private fun voiceText(raw: String): String =
        raw.replace('<', ' ').replace('>', ' ').replace('‹', ' ').replace('›', ' ')
            .replace(Regex("\\s+"), " ").trim()

    /** Текст фразы из [n] предложений с позиции (ch, s). Название главы
     *  добавляет только первое предложение — как и в [spokenText]. null — если
     *  произносить нечего (одни знаки: маркеры пропуска, линейки и т.п.). */
    private fun chunkText(ch: Int, s: Int, n: Int): String? {
        val cur = book?.chapters?.getOrNull(ch)?.sentences ?: return null
        val head = voiceText(spokenText(ch, s) ?: return null)
        val sb = StringBuilder(head)
        for (i in 1 until n) {
            val t = cur.getOrNull(s + i)?.text?.trim() ?: break
            if (t.isEmpty()) continue
            sb.append(' ').append(voiceText(t))
        }
        val out = sb.toString().trim()
        return if (out.any { it.isLetterOrDigit() }) out else null
    }

    /** Начало фразы, которая прозвучит через [offset] фраз вперёд (1 —
     *  следующая). null — книга кончилась. Позицию не двигает. */
    private fun phraseStartAhead(offset: Int): Pair<Int, Int>? {
        val bk = book ?: return null
        var ch = chapterIdx
        var s = sentenceIdx
        var left = offset.coerceAtLeast(1)
        // Ограничитель на случай битого разбора: лучше вернуть null, чем крутиться.
        var guard = 0
        while (guard++ < 64) {
            val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
            if (cur.isEmpty()) return null
            s += chunkSpan(ch, s)
            if (s >= cur.size) {
                ch++
                s = 0
                if (ch >= bk.chapters.size) return null
            }
            if (--left <= 0) {
                // Куда идти дальше, решает чтение, а не заготовка: за границу
                // выделенного куска (#106) и за главу с таймером сна «до конца
                // главы» фразы впрок не готовим — их всё равно не сыграют.
                val re = rangeEnd
                if (re != null &&
                    (ch > re.chapter || (ch == re.chapter && s >= re.sentence))
                ) return null
                if (sleepMode == SLEEP_CHAPTER && ch != chapterIdx) return null
                return ch to s
            }
        }
        return null
    }

    /** Текст фразы через [offset] вперёд — без побочных эффектов (не двигает
     *  позицию). Для упреждающего синтеза плеера (msg4472: он держит очередь
     *  заготовок и спрашивает не только следующую фразу). */
    private fun peekNextText(offset: Int): String? {
        val p = phraseStartAhead(offset) ?: return null
        return chunkText(p.first, p.second, chunkSpan(p.first, p.second))
    }

    private fun onUtteranceDone() {
        if (!playing) return
        if (!continuous) {
            stopAtEnd()
            return
        }
        if (!advanceUnits(spokenUnits)) {
            stopAtEnd()
            return
        }
        startSpeakingCurrent()
    }

    /** Промотать вперёд ровно столько предложений, сколько было в озвученной
     *  фразе (при склейке — не одно). */
    private fun advanceUnits(n: Int): Boolean {
        var ok = true
        for (i in 0 until n.coerceAtLeast(1)) {
            ok = advanceOneUnit()
            if (!ok) break
        }
        return ok
    }

    /** Чтение дошло до конца книги (или было одноразовым) — отпускаем фокус. */
    private fun stopAtEnd() {
        Diag.log(ctx, "activity", "чтение закончилось само (конец/остановка)")
        cancelSleepTimer() // таймер сна дальше не нужен — дочитали или встали на границе
        playing = false
        KeepAwake.release() // #19: чтение кончилось — процессор отпускаем
        dropAudioFocus()
        // msg1119: дочитал до конца — фиксируем, чтобы повторно не начать с начала.
        savePosition()
        pushPlayState()
    }

    /** Перейти на следующее предложение (или главу) — для автопродолжения. */
    private fun advanceOneUnit(): Boolean {
        val bk = book ?: return false
        val cur = bk.chapters[chapterIdx].sentences
        if (sentenceIdx + 1 < cur.size) {
            sentenceIdx++
        } else if (chapterIdx + 1 < bk.chapters.size) {
            // msg2567: «уснуть до конца главы» — глава дочитана, дальше не идём.
            // Позиция остаётся на её последнем предложении; повторный старт
            // обычным путём перейдёт в следующую главу.
            if (sleepMode == SLEEP_CHAPTER) {
                sleepMode = SLEEP_OFF
                return false
            }
            chapterIdx++
            sentenceIdx = 0
            host?.onChapterLoaded()
        } else {
            return false
        }
        // #106: «Прочитать выделенное» — остановиться ровно на конце куска.
        val re = rangeEnd
        if (re != null &&
            (chapterIdx > re.chapter || (chapterIdx == re.chapter && sentenceIdx >= re.sentence))
        ) {
            rangeEnd = null
            return false
        }
        // msg1119: каждое прочитанное предложение — в prefs (дёшево).
        persistPosition()
        return true
    }

    /** Пауза чтения. См. исходную pausePlayback в MainActivity: keepFocus —
     *  пользовательская пауза (фокус держим), byFocusLoss — прервал чужой плеер. */
    fun pausePlayback(keepFocus: Boolean = false, byFocusLoss: Boolean = false) {
        if (!playing) return
        Diag.log(
            ctx, "activity",
            "пауза: ${if (byFocusLoss) "прерван чужим плеером" else "по команде пользователя"}, " +
                "keepFocus=$keepFocus"
        )
        playing = false
        player?.stop()
        // #19: чтение встало — процессор больше не держим (в т.ч. на паузе из-за
        // звонка: дальше чтение возобновит [startSpeakingCurrent]).
        KeepAwake.release()
        pausedByFocusLoss = byFocusLoss
        // Пользовательская пауза/стоп снимает таймер сна: он ставился «уснуть
        // под чтение», а чтение уже прервали руками (msg2567). Прерывание чужим
        // плеером (звонок) — временное, таймер продолжает тикать.
        if (!byFocusLoss) cancelSleepTimer()
        if (!keepFocus) dropAudioFocus()
        // msg1119: пауза — ключевой момент, позицию фиксируем сразу.
        savePosition()
        pushPlayState()
    }

    /** Сообщить UI и системе новое состояние play/pause. */
    private fun pushPlayState() {
        host?.onPlayStateChanged()
        MediaSessionService.setPlaying(playing)
    }

    // ---------------- Таймер сна (msg2567) ----------------

    /** Поставить таймер «уснуть через [minutes] минут». Прежний режим снимается. */
    fun setSleepTimerMinutes(minutes: Int) {
        removeSleepRunnable()
        if (minutes <= 0) {
            sleepMode = SLEEP_OFF
            sleepMinutes = 0
            return
        }
        sleepMode = SLEEP_MINUTES
        sleepMinutes = minutes
        sleepEndAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        main.postDelayed(sleepRunnable, minutes * 60_000L)
        Diag.log(ctx, "sleep", "таймер сна: через $minutes минут")
    }

    /** Поставить таймер «уснуть, когда закончится глава»: дочитываем текущую
     *  главу и встаём на паузу на её последнем предложении (см. [advanceOneUnit]). */
    fun setSleepTimerChapterEnd() {
        removeSleepRunnable()
        sleepMode = SLEEP_CHAPTER
        sleepMinutes = 0
        Diag.log(ctx, "sleep", "таймер сна: до конца главы")
    }

    /** Снять таймер. Зовётся при ручной паузе/остановке — сработавший ночью
     *  таймер не должен выстрелить утром, когда чтение продолжили. */
    fun cancelSleepTimer() {
        removeSleepRunnable()
        sleepMode = SLEEP_OFF
        sleepMinutes = 0
    }

    private fun removeSleepRunnable() {
        sleepEndAt = 0L
        main.removeCallbacks(sleepRunnable)
    }

    private fun fireSleepTimer() {
        sleepEndAt = 0L
        val byTime = sleepMode == SLEEP_MINUTES
        sleepMode = SLEEP_OFF
        sleepMinutes = 0
        // Играет — ставим на паузу (сохранит место и отдаст фокус). Уже стоит на
        // паузе (например, прервали звонком) — просто сбрасываем режим.
        if (byTime && playing) {
            pausePlayback(keepFocus = false)
            Diag.log(ctx, "sleep", "таймер сна сработал — чтение на паузе")
        }
    }

    // ---------------- Аудиофокус ----------------

    private fun requestAudioFocus() {
        if (haveAudioFocus) return
        val r: Int
        if (Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener, main)
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusReq = req
            r = audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            r = audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        haveAudioFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Diag.log(
            ctx, "focus",
            "запрос фокуса → ${if (haveAudioFocus) "ДАНО" else "ОТКАЗАНО ($r)"}, SDK=" +
                Build.VERSION.SDK_INT
        )
        if (haveAudioFocus) focusRetried = false
        // Сразу не дали — пробуем ещё раз один раз, если чтение продолжается.
        if (!haveAudioFocus && !focusRetried && Build.VERSION.SDK_INT >= 26) {
            focusRetried = true
            main.postDelayed({
                Diag.log(ctx, "focus", "повторный запрос фокуса через 400мс")
                if (playing) requestAudioFocus()
            }, 400)
        }
    }

    private fun dropAudioFocus() {
        if (!haveAudioFocus) return
        Diag.log(ctx, "focus", "отдаю фокус")
        haveAudioFocus = false
        focusRetried = false
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusReq?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioFocusReq = null
    }

    // ---------------- Сохранение места ----------------

    /** msg2093: позиция для записи. Пока действует rewindFloor (откат при старте
     *  ещё не «догнал» место остановки), пишем не ниже него — откат не должен
     *  уехать в сохранённое место. Дошли до места остановки — floor снимаем. */
    private fun guardedPlace(): Place {
        val f = rewindFloor ?: return Place(chapterIdx, sentenceIdx)
        if (chapterIdx > f.chapter || (chapterIdx == f.chapter && sentenceIdx >= f.sentence)) {
            rewindFloor = null
            return Place(chapterIdx, sentenceIdx)
        }
        return f
    }

    /** msg1119: полная запись позиции (prefs + запись книги). */
    fun savePosition() {
        if (book == null) return
        guardResetToStart()
        val p = guardedPlace()
        // msg4322: в строку добавлен процент — жалоба «дошёл ползунком до 20%,
        // вышел, а на полке 0%». Теперь видно, что именно записано в книгу.
        Diag.log(
            ctx, "activity",
            "сохранено место: глава ${p.chapter}, предл. ${p.sentence} (${readPercentAt(p)}%)"
        )
        prefs.edit()
            .putInt(MainActivity.KEY_CHAPTER, p.chapter)
            .putInt(MainActivity.KEY_SENTENCE, p.sentence)
            .apply()
        writePlaceToRecord(p)
    }

    /** Отложенная запись места в книгу (msg4377): не чаще раза в секунду, но
     *  последнее значение терять нельзя — если throttle съел бы последний сдвиг
     *  ползунка, ставим досыл на [RECORD_WRITE_MS]. */
    private fun syncRecordSoon() {
        val now = System.currentTimeMillis()
        if (now - lastRecordWriteAt >= RECORD_WRITE_MS) {
            writePlaceToRecord(Place(chapterIdx, sentenceIdx))
            return
        }
        if (recordSyncScheduled) return
        recordSyncScheduled = true
        main.postDelayed({
            recordSyncScheduled = false
            writePlaceToRecord(Place(chapterIdx, sentenceIdx))
        }, RECORD_WRITE_MS)
    }

    /** Полная запись места в книгу (реестр библиотеки): по ней полка показывает
     *  строку «прочитано N%». msg4377: раньше это делалось только в savePosition,
     *  а смена места прокруткой или ползунком писала одни prefs — книга
     *  открывалась на новом месте, а полка показывала старое число (Сергей:
     *  «в полке 2%, а в книжке 3%»). */
    private fun writePlaceToRecord(p: Place) {
        lastRecordWriteAt = System.currentTimeMillis()
        val u = currentUri ?: return
        val ex = BookStore.byUri(ctx, u) ?: return
        BookStore.upsert(ctx, ex.copy(
            chapter = p.chapter,
            sentence = p.sentence,
            readPct = readPercentAt(p),
            lastOpenedAt = System.currentTimeMillis(),
            status = if (ex.status == BookRecord.STATUS_NEW) BookRecord.STATUS_READING else ex.status,
        ))
    }

    /** Портянка (msg4330): место, до которого читатель доехал рукой по ленте.
     *  Это его выбор — как ручной переход, поэтому снимает «место остановки»
     *  (rewindFloor, msg2093) и не даёт авто-сбросу перебить себя. Пишем и prefs,
     *  и запись книги (msg4377): смена места прокруткой может случиться уже после
     *  сохранения при выходе, и тогда полка осталась бы со старым процентом.
     *  Голос и плеер не трогаем: чтение с нового места само не начинается, экран
     *  перерисовывает вызывающий. false — книгу/главу взять неоткуда. */
    fun placeFromScroll(ch: Int, s: Int): Boolean {
        val bk = book ?: return false
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return false
        if (cur.isEmpty()) return false
        rewindFloor = null
        userMoved = true
        chapterIdx = ch
        sentenceIdx = s.coerceIn(0, cur.lastIndex)
        persistPosition()
        val p = Place(chapterIdx, sentenceIdx)
        writePlaceToRecord(p)
        Diag.log(
            ctx, "activity",
            "место записано в книгу: глава ${p.chapter}, предл. ${p.sentence} (${readPercentAt(p)}%)"
        )
        return true
    }

    /** Быстрая запись позиции в prefs — зовётся часто, только prefs без DB. */
    fun persistPosition() {
        if (book == null) return
        guardResetToStart()
        val p = guardedPlace()
        prefs.edit()
            .putInt(MainActivity.KEY_CHAPTER, p.chapter)
            .putInt(MainActivity.KEY_SENTENCE, p.sentence)
            .apply()
    }

    /** msg1139: книга открылась с сохранённого места, а позиция без ручной
     *  навигации вдруг в (0,0) — тихий сброс, не даём записать 0/0 поверх. */
    private fun guardResetToStart() {
        val rp = restoredPlace ?: return
        if (userMoved) return
        if (!(rp.chapter > 0 || rp.sentence > 0)) return
        if (chapterIdx != 0 || sentenceIdx != 0) return
        Diag.log(
            ctx, "activity",
            "сброс к началу без навигации — держу место: глава ${rp.chapter}, предл. ${rp.sentence}"
        )
        chapterIdx = rp.chapter
        sentenceIdx = rp.sentence
    }

    /** Процент прочитанного на текущей позиции — той же «линейкой», что слайдер. */
    fun readPercent(): Int = readPercentAt(Place(chapterIdx, sentenceIdx))

    /** Процент прочитанного на конкретном месте — для записи, когда реальная
     *  позиция ещё в откате (msg2093) и пишем место остановки. */
    private fun readPercentAt(p: Place): Int {
        val total = chapterStart.lastOrNull() ?: 0
        if (total <= 1) return 0
        val g = ((chapterStart.getOrNull(p.chapter) ?: 0) + p.sentence).coerceIn(0, total - 1)
        return g * 100 / (total - 1)
    }

    private fun currentGlobal(): Int =
        (chapterStart.getOrNull(chapterIdx) ?: 0) + sentenceIdx

    /** После успешного открытия — обновить запись книги в библиотеке. */
    fun registerOpen(doc: BookDocument) {
        val u = currentUri ?: return
        val now = System.currentTimeMillis()
        val existing = BookStore.byUri(ctx, u)
        prefs.edit().putString(MainActivity.KEY_URI, u).apply()
        val status = when (existing?.status) {
            BookRecord.STATUS_FINISHED -> BookRecord.STATUS_FINISHED
            else -> BookRecord.STATUS_READING
        }
        BookStore.upsert(ctx, BookRecord(
            uri = u,
            name = existing?.name ?: currentName ?: "book",
            title = doc.title ?: existing?.title,
            author = doc.author ?: existing?.author,
            annotation = existing?.annotation,
            sourceUrl = existing?.sourceUrl,
            voiceEngine = existing?.voiceEngine,
            voice = existing?.voice,
            voiceSpeed = existing?.voiceSpeed,
            addedAt = existing?.addedAt ?: now,
            lastOpenedAt = now,
            status = status,
            chapter = chapterIdx,
            sentence = sentenceIdx,
            readPct = readPercent(),
            // msg2555: запись пересоздаётся (не .copy) — без этого «избранное»
            // и ручное название слетали бы при каждом сохранении прогресса.
            favorite = existing?.favorite ?: false,
            customTitle = existing?.customTitle,
        ))
    }

    // ---------------- #102: свой голос/скорость книги ----------------

    /** Применить запомненный для книги голос/скорость при открытии. Каскад:
     *  своё у книги → глобальное → системное. Своё применяем, только когда у
     *  записи оно есть; иначе движок/голос ЯВНО возвращаем на глобальные —
     *  без этого после книги со своим голосом следующая книга осталась бы на
     *  её синтезаторе (msg2647): переключённый движок «залипал» в плеере. */
    fun applyBookVoiceAtOpen() {
        val rec = currentBookRecord()
        perBookEngine = rec?.voiceEngine
        perBookVoice = rec?.voice
        perBookSpeed = rec?.voiceSpeed
        // Скорость: своя у книги → глобальная. Раньше без своего профиля
        // скорость не трогали, и после книги со своей скоростью следующая
        // читалась бы на ней (msg2647).
        val sp = perBookSpeed?.takeIf { it > 0f } ?: prefs.getFloat(MainActivity.KEY_SPEED, 1f)
        player?.speed = sp
        host?.onSpeedUiRefresh()
        if (perBookEngine != null || perBookVoice != null) {
            waitApplyBookVoice(0)
        } else {
            waitReturnGlobalVoice(0)
        }
    }

    /** У книги нет своего голоса — вернуть движок/голос на глобальные настройки
     *  (а если глобально движок не выбран — на системный). Аналог снятия
     *  галочки «Запомнить для этой книги» (MainActivity.clearBookVoice), но без
     *  стирания профиля — просто переключение плеера. */
    private fun waitReturnGlobalVoice(attempt: Int) {
        val p = player ?: return
        if (book == null) return
        if (!p.isReady) {
            if (attempt > 40) return // ~8с — движок так и не поднялся
            main.postDelayed({ waitReturnGlobalVoice(attempt + 1) }, 200)
            return
        }
        val gEngine = prefs.getString(MainActivity.KEY_ENGINE, null)
        val gVoice = prefs.getString(MainActivity.KEY_VOICE, null)
        if (p.enginePackage != gEngine) {
            p.setEngine(gEngine) { ok ->
                main.post {
                    if (ok && gVoice != null) {
                        p.selectVoice(gVoice)
                        voiceName = gVoice
                    }
                    host?.onSpeedUiRefresh()
                }
            }
        } else {
            if (gVoice != null && gVoice != voiceName) {
                p.selectVoice(gVoice)
                voiceName = gVoice
            }
            host?.onSpeedUiRefresh()
        }
    }

    private fun currentBookRecord(): BookRecord? {
        val u = currentUri ?: return null
        return BookStore.byUri(ctx, u)
    }

    /** Сколько раз (по 200 мс, ~4с) ждать список голосов движка (msg3550). */
    private val voiceListTries = 20

    private fun waitApplyBookVoice(attempt: Int, voiceWait: Int = 0) {
        val eng = perBookEngine
        val vce = perBookVoice
        val p = player ?: return
        if (book == null) return
        if (!p.isReady) {
            if (attempt > 40) return // ~8с — движок так и не поднялся
            main.postDelayed({ waitApplyBookVoice(attempt + 1) }, 200)
            return
        }
        // msg3550: «движок готов» и «список голосов пришёл» — разные события:
        // Android отдаёт голоса чуть позже инициализации. Пустой список — это
        // «ещё не знаю», а не «голоса нет»: раньше проверка заскакивала раньше
        // списка, объявляла «голос не найден» и голос книги не выставлялся —
        // книга уезжала на обычный голос. Ждём список, как ждём готовность.
        if (vce != null && p.voices.isEmpty() && voiceWait < voiceListTries) {
            main.postDelayed({ waitApplyBookVoice(attempt, voiceWait + 1) }, 200)
            return
        }
        if (eng != null && eng != p.enginePackage) {
            p.setEngine(eng) { ok ->
                main.post {
                    if (ok) {
                        if (vce != null) {
                            p.selectVoice(vce)
                            voiceName = vce
                        }
                        // Движок перезапустился — список голосов поедет заново
                        // (пустой). Проходим тем же циклом, а не проверяем сразу.
                        waitApplyBookVoice(0, 0)
                    } else {
                        p.setEngine(null) { _ ->
                            main.post {
                                if (vce != null) announceIfBookVoiceMissing(vce)
                                host?.onSpeedUiRefresh()
                            }
                        }
                    }
                }
            }
        } else {
            if (vce != null && vce != voiceName) {
                p.selectVoice(vce)
                voiceName = vce
            }
            announceIfBookVoiceMissing(vce)
            host?.onSpeedUiRefresh()
        }
    }

    /** Запомненный для книги голос не найден — один раз сказать об этом.
     *  По пустому списку голосов решение не принимаем (msg3550): это «ещё не
     *  знаю», а не «голоса нет» — иначе объявление было бы ложным. */
    private fun announceIfBookVoiceMissing(vce: String?) {
        val p = player ?: return
        if (vce == null || voiceMissingAnnounced || !p.isReady) return
        if (p.voices.isEmpty()) {
            Diag.log(ctx, "voice", "список голосов пуст — про голос книги «$vce» молчу")
            return
        }
        if (p.voices.none { it.name == vce }) {
            voiceMissingAnnounced = true
            Diag.log(ctx, "voice", "голос книги «$vce» не найден среди ${p.voices.size} голосов")
            host?.onAnnounce(ctx.getString(R.string.voice_book_missing))
        } else {
            Diag.log(ctx, "voice", "голос книги «$vce» на месте (${p.voices.size} голосов)")
        }
    }

    // ---------------- #98: звонки ----------------

    private fun afterCallContinue(): Boolean =
        prefs.getString(MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_STOP) ==
            MainActivity.AFTER_CALL_CONTINUE

    private fun hasPhonePerm(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private val callListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            main.post { handleCallState(state) }
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!inCall) {
                    inCall = true
                    val now = SystemClock.elapsedRealtime()
                    wasReadingAtCallStart = playing ||
                        (lastTransientPauseAt != 0L && now - lastTransientPauseAt < 3000)
                    Diag.log(ctx, "call", "звонок начался (читало=$wasReadingAtCallStart)")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall) {
                    inCall = false
                    Diag.log(ctx, "call", "звонок закончился")
                    afterCallEnded()
                }
            }
        }
    }

    /** Конец настоящего звонка (#98). Если до звонка шло чтение — отматываем
     *  назад на выбранное число предложений и продолжаем. */
    private fun afterCallEnded() {
        val resume = wasReadingAtCallStart
        wasReadingAtCallStart = false
        if (!resume || book == null || playing) return
        val n = when (prefs.getString(MainActivity.KEY_AFTER_CALL_REWIND, MainActivity.AFTER_CALL_REWIND_5)) {
            MainActivity.AFTER_CALL_REWIND_NONE -> 0
            MainActivity.AFTER_CALL_REWIND_2 -> 2
            MainActivity.AFTER_CALL_REWIND_5 -> 5
            MainActivity.AFTER_CALL_REWIND_10 -> 10
            else -> 5
        }
        if (n > 0) rewindSentences(n)
        host?.onAnnounce(ctx.getString(R.string.after_call_continue_announce))
        requestStart()
    }

    /** Откатить позицию на [n] предложений назад (после звонка) — через общий
     *  путь goTo, чтобы место сохранилось и экран (если есть) перерисовался. */
    private fun rewindSentences(n: Int) {
        val bk = book ?: return
        var ch = chapterIdx
        var s = sentenceIdx
        repeat(n) {
            if (s > 0) {
                s--
            } else if (ch > 0) {
                ch--
                s = bk.chapters[ch].sentences.size - 1
            }
        }
        goTo(ch, s)
    }

    private fun registerPhoneListener() {
        if (!afterCallContinue() || !hasPhonePerm()) return
        if (phoneListening) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        phoneListening = true
        @Suppress("DEPRECATION")
        tm.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterPhoneListener() {
        if (!phoneListening) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        tm?.listen(callListener, PhoneStateListener.LISTEN_NONE)
        phoneListening = false
        inCall = false
        wasReadingAtCallStart = false
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered || !prefs.getBoolean(MainActivity.KEY_PAUSE_HEADSET, true)) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(noisyReceiver, filter)
            }
            noisyRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { ctx.unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    // ---------------- Навигация (общий путь goTo) ----------------

    /** Общий переход к (ch, s). См. исходный goTo в MainActivity: сбрасывает
     *  режим чтения куска, останавливает плеер если играло, пишет позицию,
     *  перерисовывает экран и продолжает чтение с нового места. */
    fun goTo(ch: Int, s: Int) {
        val bk = book ?: return
        // Любой ручной переход отменяет режим «прочитать выделенный кусок» (#106).
        rangeEnd = null
        // Ручной переход — явный выбор читателя: держать место остановки
        // (rewindFloor, msg2093) больше не нужно.
        rewindFloor = null
        val chClamped = ch.coerceIn(0, bk.chapters.lastIndex)
        val cur = bk.chapters[chClamped].sentences
        if (cur.isEmpty()) return
        val sClamped = s.coerceIn(0, cur.lastIndex)
        // 0.3.46: пока идёт открытие книги, настоящий переход к началу невозможен
        // (главный поток занят) — значит это авто-сброс позиции к (0,0), а не
        // выбор читателя. Молча блокируем, чтобы восстановленное место устояло.
        if (openingWindow && chClamped == 0 && sClamped == 0) {
            Diag.log(ctx, "activity", "переход к началу в момент открытия — игнорирую (авто-сброс)")
            return
        }
        val wasPlaying = playing
        if (wasPlaying) {
            playing = false
            player?.stop()
        }
        val changedChapter = chClamped != chapterIdx
        chapterIdx = chClamped
        sentenceIdx = sClamped
        if (chClamped == 0 && sClamped == 0 && !userMoved) {
            val rp = restoredPlace
            if (rp != null && (rp.chapter > 0 || rp.sentence > 0)) {
                val callers = Thread.currentThread().stackTrace
                    .take(7).drop(2).joinToString(" <- ") { it.methodName }
                Diag.log(ctx, "activity", "goTo(0,0) без ручной навигации; цепочка: $callers")
            }
        }
        userMoved = true
        Diag.log(ctx, "activity", "переход: глава $chClamped, предл. $sClamped")
        persistPosition()
        // msg4377: ручной переход (ползунок, оглавление, закладка, поиск) — это
        // выбор читателя; пишем его и в запись книги, чтобы полка показывала то же
        // место, что книга, не дожидаясь выхода. Сергей: «в полке 2%, а в книжке 3%»
        // — как раз ползунок. Ползунок шлёт переход на каждый сдвиг, поэтому
        // запись отложенная и не чаще раза в секунду.
        syncRecordSoon()
        if (changedChapter) {
            host?.onChapterLoaded()
        } else {
            host?.onMovedInChapter(sClamped)
        }
        pushPlayState()
        if (wasPlaying) startSpeakingCurrent()
    }

    /** Текст предложения (ch, s), нормализованный (без лишних пробелов). */
    fun sentenceTextAt(ch: Int, s: Int): String {
        val bk = book ?: return ""
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return ""
        val t = cur.getOrNull(s)?.text ?: return ""
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
