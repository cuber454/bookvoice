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
    }

    private fun initPlayer() {
        val sp = SpeechPlayer(ctx)
        sp.onDone = { main.post { if (playing) onUtteranceDone() } }
        sp.onNeedNext = { peekNextText() }
        sp.speed = prefs.getFloat(MainActivity.KEY_SPEED, 1f)
        sp.pitch = prefs.getFloat(MainActivity.KEY_PITCH, 1f)
        sp.volume = prefs.getFloat(MainActivity.KEY_VOLUME, 1f)
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
        MediaSessionService.start(ctx, bk.title ?: currentName, playing = playing)
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
        playing = true
        // msg4211 (#19): чтение начинается — держим процессор, чтобы телефон,
        // лежащий экраном вниз, не заснул между фразами (лог тестера: сторож
        // сработал через 42 с вместо 15 — таймеры не шли, процесс спал).
        // Отпускаем на паузе/конце книги/закрытии читалки, иначе батарея.
        KeepAwake.acquire(ctx)
        ensureMediaService()
        Diag.log(
            ctx, "activity",
            "старт чтения (глава $chapterIdx, предл. $sentenceIdx); движок=${p.enginePackage}, голос=$voiceName"
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
        val t = spokenText(chapterIdx, sentenceIdx) ?: return
        player?.speak(t)
    }

    /** Текст предложения, которое пойдёт следующим за текущим — без побочных
     *  эффектов (не двигает позицию). Для упреждающего синтеза плеера. */
    private fun peekNextText(): String? {
        val bk = book ?: return null
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: return null
        if (sentenceIdx + 1 < cur.size) return spokenText(chapterIdx, sentenceIdx + 1)
        if (chapterIdx + 1 < bk.chapters.size) return spokenText(chapterIdx + 1, 0)
        return null
    }

    private fun onUtteranceDone() {
        if (!playing) return
        if (!continuous) {
            stopAtEnd()
            return
        }
        if (!advanceOneUnit()) {
            stopAtEnd()
            return
        }
        startSpeakingCurrent()
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
        Diag.log(ctx, "activity", "сохранено место: глава ${p.chapter}, предл. ${p.sentence}")
        prefs.edit()
            .putInt(MainActivity.KEY_CHAPTER, p.chapter)
            .putInt(MainActivity.KEY_SENTENCE, p.sentence)
            .apply()
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
