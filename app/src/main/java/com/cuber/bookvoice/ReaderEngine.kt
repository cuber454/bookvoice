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
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

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

/** Сколько знаков названия главы пускаем во вторую строку медиа-карточки
 *  (msg4725): в шторке длинный заголовок не читается ни глазами, ни на слух. */
private const val CARD_TITLE_LIMIT = 40

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
        /** Книга дочитана до конца — спросить владельца, удалять ли её (07.10.2026).
         *  У окна может не быть фокуса (экран погашен, чтение идёт без окна): тогда
         *  вопрос ждёт возвращения в читалку, а не пропадает. */
        fun onBookEndAsk() {}
    }

    @Volatile
    var host: Host? = null

    // ---------------- Контекст и каналы ----------------

    private lateinit var ctx: Context

    /** Версия приложения строкой — её пишем в журнал при каждом старте чтения.
     *  В шапке журнала [Diag.header] версия есть, но она одна на запуск и при
     *  длинном логе уезжает за обрезку: по присланному куску не видно, какая
     *  сборка тестируется (вопрос Сергея, msg4424). Имя ПРОБНОЙ сборки
     *  ([Diag.BUILD]) идёт рядом: versionCode у проб не меняется, и без него
     *  сборку не опознать. */
    private val versionLabel: String by lazy {
        val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
            .getOrDefault("?")
        "$name проба ${Diag.BUILD}"
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

    /** 19.10.2026 (просьба Сержа): «при выборе голоса этим голосом зачитать
     *  последнюю единицу текста». Взведён — звучит РОВНО одна фраза с текущего
     *  места, а дальше чтение встаёт на паузу ([stopAtEnd]), место в книге не
     *  двигается. Ставится и снимается только в [startSpeakingCurrent]; снимает
     *  его [onUtteranceDone], а пауза, стоп и прыжок — [resetPipeline] не нужен:
     *  любое начало чтения проходит через [startSpeakingCurrent] и сбросит флаг
     *  само. */
    private var previewUnit = false

    /** msg4629: владелец нажал «Выход» в карточке шторки. Пока флаг стоит, окно
     *  читалки, закрываясь на паузе, карточку НЕ держит — иначе вышел бы и снова
     *  получил её. Снимается при возврате окна в движок ([attach]). */
    private var cardDroppedByUser = false

    // msg4416: сколько предложений ушло в движок одной фразой при коротких
    // паузах (см. [chunkSpan]). Обычно 1; при склейке — до [TIGHT_CHUNK_MAX_UNITS].
    // Нужно, чтобы после озвученной фразы переставить позицию ровно на столько
    // же предложений вперёд.
    private var spokenUnits = 1

    /** 05.10.2026: следующую фразу читать одним предложением, без склейки —
     *  ставится на ручном переходе ([goTo]) и снимается в [speakCurrent].
     *  Зачем: после прыжка готового звука нет, а склеенная фраза пишется 3-4,5 с
     *  (журнал Сержа 18:43) — короткая доспевает в разы быстрее. */
    private var shortFirstPhrase = false

    /** Фраза, которая читается СЕЙЧАС, укорочена (одно предложение, см.
     *  [shortFirstPhrase]). Нужно [phraseStartAhead]: он считает, что читать
     *  дальше, и обязан взять первый шаг таким же, как у самой фразы. Иначе
     *  очередь заготовок считалась бы от позиции по обычной склейке и уезжала
     *  вперёд: в журнале 21:03 заготовка «Скоро вдали показался паровозный
     *  дымок…» прозвучала раньше своей очереди, и текст повторился. */
    private var firstPhraseShort = false

    /** Позиция той самой укороченной фразы ([firstPhraseShort]) — или -1.
     *  07.10.2026: нужна [nextPhrasePos]. Укороченная фраза одна на переход, и
     *  шаг от неё считается по её длине (одно предложение), а не по склейке:
     *  без этого плеер, проверяя порядок очереди, ждал бы после неё не ту фразу
     *  и держал бы готовый звук зря. */
    private var shortPhrasePos = -1L

    // 05.10.2026: одна фраза книги может состоять из нескольких кусков — когда
    // прямая речь со словами автора режется на реплики (второй голос) и сами
    // слова автора (основной голос). Куски читаются подряд, позиция книги при
    // этом двигается только после последнего. Здесь лежат ещё не прочитанные
    // куски текущей фразы и её позиция.
    private var pendingFragments: List<String> = emptyList()
    private var fragmentPos: Long = -1L

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

    /** Сколько минут человек ВЫСТАВИЛ таймеру в этот раз — для продления «столько
     *  же» (msg5009). Отдельное поле, потому что [sleepMinutes] переписывается
     *  каждым продлением и исходное число из него уже не достать. Ставится
     *  только выбором из окна таймера, не жестом (иначе «столько же» съезжало бы
     *  на предыдущее продление). */
    @Volatile
    private var sleepBaseMinutes = 0

    @Volatile
    private var sleepEndAt = 0L

    /** Идёт окно ожидания: последняя минута перед остановкой, слушаем жесты. */
    @Volatile
    var sleepWaiting = false
        private set

    /** Длина окна ожидания — последняя минута перед остановкой (msg4941). */
    private const val SLEEP_WINDOW_MS = 60_000L

    /** Проверка таймера без ожидания (msg4987): прочитать 20 секунд, затем
     *  сигнал и окно на 10 секунд — весь цикл укладывается в полминуты. */
    private const val TEST_READ_MS = 20_000L
    private const val TEST_WINDOW_MS = 10_000L

    private val sleepRunnable = Runnable { fireSleepTimer() }

    /** Сигнал за минуту до остановки: чтение идёт, мы только предупреждаем. */
    private val sleepWarnRunnable = Runnable { enterSleepWindow(test = false) }

    /** Идёт проверка таймера: книгу НЕ останавливаем, только рассказываем. */
    private var testMode = false

    /** Конец проверки (жеста не было) — отдельный Runnable: [sleepRunnable]
     *  в это время уже свободен от настоящего таймера. */
    private var testEndRunnable: Runnable? = null

    /** Номер текущей проверки: отложенные шаги сверяются с ним и молчат, если
     *  проверку уже сменили (человек нажал вторую проверку, не дождавшись). */
    private var testToken = 0

    /** Кому доложить результат проверки — окно «Настройки таймера» ставит сюда
     *  свой колбэк на время показа и снимает при закрытии. */
    @Volatile
    var sleepTestListener: ((String) -> Unit)? = null

    /** Ожидание жеста после дочитанной главы (режим «до конца главы» + галочка
     *  «подождать встряску»): чтение уже стоит на паузе, жест его возвращает. */
    private var chapterWait = false

    /** Слушатель жестов — живёт в движке, а не в окне: окно ожидания идёт и при
     *  погашенном экране, и «без окна». */
    private var sense: SleepSense? = null

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

    /** Чтение встало потому, что телефон положили экраном вниз (галочка
     *  «Останавливать чтение, когда телефон перевёрнут экраном вниз»,
     *  23.09.2026). Перевернули экраном вверх — продолжаем сами, и только если
     *  это была НАША пауза: пауза человека ею не снимается (см. [FaceDownPause]). */
    @Volatile
    var pausedByFaceDown = false
        private set

    /** Телефон перевернули экраном вверх. Продолжаем, только если паузу ставили
     *  мы и только если таймер сна сейчас не ждёт жест: там переворот его. */
    fun resumeIfPausedByFaceDown() {
        if (!pausedByFaceDown) return
        pausedByFaceDown = false
        if (playing || book == null || sleepWaiting) return
        Diag.log(ctx, "power", "экран вверх — продолжаю чтение")
        requestStart()
    }

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
                // 30.09.2026: настройки «продолжать после чужого плеера» больше
                // нет — возврат звука продолжает чтение всегда (Серж: «сделать
                // чтобы было включено и всё»).
                if (pausedByFocusLoss && book != null && !playing) {
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
        // msg4472: плеер держит очередь заготовок и спрашивает фразу не только
        // «следующую», но и через одну-две вперёд — отсюда параметр offset.
        // 23.09.2026: фраза доиграла — разбираем, чья она: служебная «книга
        // прочитана» (у неё нет места в тексте) или очередная фраза книги.
        sp.onDone = { main.post { onPlayerDone() } }
        // 08.10.2026: в журнал — каким порядком читаем. Прежнего пути больше нет
        // (выключатель убран, когда новый отстоялся), но строка остаётся меткой:
        // по ней видно, что сборка читает по единицам речи.
        Diag.log(ctx, "sound", "порядок чтения: по единицам речи (ключ = место в книге + номер куска)")
        // 05.10.2026 (журнал 22:43): движок перестал отвечать — движок плеера
        // сам встаёт и зовёт нас: останавливаем чтение, оставляем место там, где
        // кончился звук, и говорим об этом вслух (см. [onEngineSilent]).
        sp.onEngineSilent = { pos -> main.post { onEngineSilent(pos) } }
        sp.onNeedNext = { offset -> peekNextFragments(offset) }
        // 07.10.2026: плееру нужен и «следующий адрес» фразы по позиции — им он
        // проверяет порядок списка перед тем, как дописать заготовку (см.
        // [nextPhrasePos] и SpeechPlayer.exoTopUp).
        sp.onNeedAfter = { pos -> nextPhrasePos(pos) }
        sp.speed = prefs.getFloat(MainActivity.KEY_SPEED, 1f)
        sp.pitch = prefs.getFloat(MainActivity.KEY_PITCH, 1f)
        sp.volume = prefs.getFloat(MainActivity.KEY_VOLUME, 1f)
        // msg4402/msg5254: короткие паузы между предложениями (просьба тестера) —
        // галочки нет, склейка и обрезка работают всегда.
        // 24.10.2026: обычное чтение — голосом движка (как у @Voice). Прежняя
        // галочка «Альтернативный способ чтения» стала строкой «Читать файлами
        // (обходной способ)», поэтому прямой путь включается по её ВЫКЛЮЧЕННОМУ
        // состоянию ([MainActivity.KEY_READ_FILES]). Наш конвейер заготовок при
        // прямом пути молчит.
        sp.altDirect = !prefs.getBoolean(MainActivity.KEY_READ_FILES, false)
        // msg5604: сколько тишины оставлять на стыке фраз — строка «Пауза между
        // фразами» в настройках (по умолчанию как было: 50 мс + 83 мс).
        sp.pauseKeepMs = prefs.getInt(MainActivity.KEY_PAUSE_KEEP, MainActivity.PAUSE_KEEP_DEFAULT)
        // 24.10.2026: срок ожидания сетевого голоса из опытных настроек (0 — по
        // нашей таблице). Строка за дверью, по образцу «Network timeout» у @Voice.
        sp.netTimeout = prefs.getLong(MainActivity.KEY_NET_TIMEOUT, 0L)
        // 24.10.2026: сколько раз повторять запрос к сетевому голосу (1…10).
        sp.retries = prefs.getInt(MainActivity.KEY_NET_RETRIES, 1)
        // Чтение всегда непрерывное: кнопка «по одному предложению» убрана.
        continuous = true
        voiceName = prefs.getString(MainActivity.KEY_VOICE, null)
        player = sp
        applyReplySettings(sp)
    }

    /** Реплики другим голосом (и, если выбран, другим движком): настройки читаем
     *  из prefs и отдаём плееру. Зовётся при создании плеера и при каждом старте
     *  чтения — настройку могли поменять на ходу. */
    private fun applyReplySettings(p: SpeechPlayer) {
        p.setReplyVoice(
            enabled = prefs.getBoolean(MainActivity.KEY_REPLY_ON, false),
            engine = prefs.getString(MainActivity.KEY_REPLY_ENGINE, null),
            voice = prefs.getString(MainActivity.KEY_REPLY_VOICE, null),
        )
        // Своя скорость, свой тон и своя громкость у реплик (26.09.2026).
        p.replySpeed = prefs.getFloat(MainActivity.KEY_REPLY_SPEED, 1f)
        p.replyPitch = prefs.getFloat(MainActivity.KEY_REPLY_PITCH, 1f)
        p.replyVolume = prefs.getFloat(MainActivity.KEY_REPLY_VOLUME, 1f)
    }

    /** Окно читалки закрылось — чтение гаснет (как раньше onDestroy). Сессию и
     *  сервис гасим, чтобы не отбирать медиа-кнопки у других плееров. Фокус
     *  отдаём и авто-продолжение гасим: вернуться «по фокусу» уже некому. */
    @Synchronized
    fun close() {
        // Служебное «книга прочитана» может ждать своей секунды или звучать —
        // закрытие читалки гасит и её, вместе с её фокусом и будильником.
        cancelEndAnnounce()
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
        // 29.09.2026: движок пуст — значит и «какая книга открыта» пусто. Иначе
        // состояние врало бы: книгу отпустили, а uri прежней остался, и по нему
        // окно решает, переподключаться к живому чтению или открывать заново, и по
        // нему же пишется место. В журнале тестера это и вышло: окно называется
        // новой книгой, а сохраняется место прежней (глава 12, предл. 333).
        currentUri = null
        currentName = null
        // Книги нет — и вопроса про неё тоже.
        endAskUri = null
        // Книга закрыта — разбор возвращаем к общим знакам реплик (30.09.2026).
        ReplyMarks.apply(ctx, null)
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
    fun windowGoneWhilePlaying(window: Host? = null) {
        // 30.09.2026: уже другое окно у руля — чужое закрытие нам не указ (гонка
        // переоткрытия), ровно как в [windowGoneWhilePaused]. Без этой проверки
        // закрывающееся прежнее окно сбрасывало host у НОВОГО: звук продолжался, а
        // окно переставало за чтением следить.
        if (host != null && window != null && host !== window) return
        host = null
        Diag.log(ctx, "activity", "окно закрыто, чтение без окна продолжается")
    }

    /** Окно читалки закрылось, а книга стоит на паузе (msg4629: «пусть карточка
     *  висит, пока я не выйду сам»). Карточку в шторке НЕ гасим: служба остаётся
     *  в переднем плане, кнопка «играть» в ней поднимет чтение без окна
     *  ([startSpeakingCurrent] сам просит фокус и добивает службу). Движок держит
     *  книгу и плеер, но звука нет: будильник снимаем, место фиксируем. Гасит всё
     *  это только «Выход» в карточке ([onMediaExit]) — или [close], если книги уже
     *  нет.
     *
     *  msg5423: фокус НЕ отдаём. Раньше отдавали («стоим — чужой плеер приглушать
     *  не за чем»), но этим выход из окна отличался от паузы В окне: там фокус
     *  остаётся нарочно, чтобы следующий магик-тап попал к нам (см. [onMediaPause]).
     *  Волшебное касание Сергея после выхода из окна ловилось через раз — делаем
     *  оба случая одинаковыми. Заиграет чужой плеер — он заберёт фокус сам, наш
     *  слушатель это перенесёт (LOSS при паузе безвреден). */
    @Synchronized
    fun windowGoneWhilePaused(window: Host?) {
        // Уже другое окно у руля — чужое закрытие нам не указ (гонка переоткрытия).
        if (host != null && host !== window) return
        host = null
        KeepAwake.release()
        savePosition()
        Diag.log(
            ctx, "activity",
            "окно закрыто, книга на паузе — карточка остаётся, фокус держим" +
                " (haveAudioFocus=$haveAudioFocus)"
        )
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

        /** «Переключи» — «волшебное касание» TalkBack (два пальца, два касания
         *  в любом месте экрана) и центральная кнопка гарнитуры. Решаем по
         *  ФАКТИЧЕСКОМУ состоянию движка, а не по опубликованному в систему:
         *  опубликованное может отстать (чтение идёт, а в системе пауза), и тогда
         *  система превратила бы касание в «играть», а «играть» на играющем
         *  чтении ничего не делает — на слух это «волшебное касание перестало
         *  ставить паузу» (жалоба тестеров, 0.4.77). */
        override fun onMediaToggle() {
            main.post {
                if (book == null) return@post
                if (playing) {
                    Diag.log(ctx, "session", "волшебное касание: ставлю паузу")
                    pausePlayback(keepFocus = true)
                } else {
                    Diag.log(ctx, "session", "волшебное касание: начинаю чтение")
                    requestStart()
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
                // 19.10.2026: в журнал — какая кнопка пришла и какой шаг выбран в
                // настройке. Без этой строки жалобу «гарнитура не переключает» не
                // разобрать: не видно ни прихода клавиши, ни решения.
                Diag.log(
                    ctx, "session",
                    "гарнитура: «${if (delta < 0) "назад" else "вперёд"}», шаг «$step»" +
                        (if (step == MainActivity.HS_OFF) " — выключен в настройке" else "")
                )
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
        var use = step
        // Шаг НАЗАД считаем от прошлой цели, если нажатия идут подряд
        // ([backStepBase]): иначе выходит «встал на начало текущего предложения».
        // Журнал пробы 255, слова Сержа: «предложение назад — а оно как-то на
        // начало текущего предложения становится». В журнале: 199 → 198, чтение
        // дочитывало фразу вперёд (199…201), и следующее нажатие снова давало 198.
        // Та же беда была у кнопки «Пред.» в читалке 29.09.2026 — лечится так же.
        val backFrom = delta < 0 && (step == MainActivity.HS_SENTENCE || step == MainActivity.HS_SENT_N)
        val base = if (backFrom) backStepBase(bk) else Place(chapterIdx, sentenceIdx)
        var target = headsetTarget(bk, step, delta, base)
        // 19.10.2026, разбор журнала пробы 255 (Серж: «гарнитура по-прежнему
        // управляет только play-паузой, а не перематывает»). Клавиши ДОШЛИ —
        // в журнале «кнопка гарнитуры KEYCODE_MEDIA_NEXT → шаг 1», шаг был
        // «chapter», а книга оказалась ОДНОЙ главой («Глава 1 из 1 · 204 из
        // 8162»): шаг «глава» упирался сразу, и кнопка молчала. Теперь, если
        // выбранный шаг не сдвинул место, идём следующим, более МЕЛКИМ шагом
        // (глава → абзац → предложение). Молчания в ответ на нажатие не бывает:
        // не вышло совсем — объясняем словами.
        if (samePlace(target)) {
            val fallbacks = when (step) {
                MainActivity.HS_SENTENCE -> emptyList()
                MainActivity.HS_PARAGRAPH, MainActivity.HS_SENT_N -> listOf(MainActivity.HS_SENTENCE)
                else -> listOf(MainActivity.HS_PARAGRAPH, MainActivity.HS_SENTENCE)
            }
            for (fb in fallbacks) {
                val t = headsetTarget(bk, fb, delta, base)
                if (!samePlace(t)) {
                    target = t
                    use = fb
                    break
                }
            }
        }
        if (target == null || samePlace(target)) {
            Diag.log(
                ctx, "session",
                "гарнитура: шаг «$step» в эту сторону некуда (начало/конец книги) — " +
                    "говорю место и стою"
            )
            if (!playing) host?.onAnnounce(positionPhrase())
            return
        }
        if (use != step) {
            Diag.log(
                ctx, "session",
                "гарнитура: шаг «$step» не сдвинул место (в книге всего " +
                    "${bk.chapters.size} гл.) — иду шагом «$use»"
            )
            // Просьба Сержа 19.10.2026: о таком переходе сказать СЛОВАМИ, но не
            // каждый раз — книгу листают долго, а подсказка нужна один раз.
            if (headsetHintBook != currentUri) {
                headsetHintBook = currentUri
                hintSpeaking = false
                hintText = ctx.getString(
                    when {
                        step == MainActivity.HS_MAJOR || step == MainActivity.HS_HEADER ->
                            R.string.headset_step_from_headers

                        use == MainActivity.HS_SENTENCE -> R.string.headset_step_sentence
                        else -> R.string.headset_step_paragraph
                    }
                )
            }
        }
        goTo(target.chapter, target.sentence)
        // Цели шагов назад помним, чтобы подряд идущие нажатия вели себя как в
        // читалке: 203 → 202 → 201, а не «то же место снова». Вперёд память
        // сбрасываем: после чтения вперёд «назад» должно считаться от места.
        if (delta < 0) rememberBackStep(target.chapter, target.sentence) else forgetBackStep()
        // Подсказку говорим ПОСЛЕ перехода: на паузе — диктором (чтение молчит),
        // на ходу — голосом чтения, оборвав начатую фразу, и сразу продолжаем
        // чтение с нового места (см. [speakHint]).
        hintText?.let { speakHint(it) }
        if (!playing && !hintSpeaking) host?.onAnnounce(positionPhrase())
    }

    /** Текст подсказки, которую ещё не сказали (одна на книгу, см. [headsetHintBook]). */
    private var hintText: String? = null

    /** Книга, которой про «мелкий шаг» уже сказали. */
    private var headsetHintBook: String? = null

    /** Сейчас звучит служебная подсказка — по её концу продолжим чтение. */
    private var hintSpeaking = false

    /** Сказать подсказку про шаг. На паузе — диктором, на ходу — голосом чтения:
     *  фраза книги уже оборвана переходом ([goTo]), и после подсказки чтение
     *  продолжится с нового места (см. [onPlayerDone]). */
    private fun speakHint(text: String) {
        hintText = null
        val p = player
        if (!playing || p == null || !p.isReady) {
            Diag.log(ctx, "session", "подсказка диктором: $text")
            host?.onAnnounce(text)
            return
        }
        Diag.log(ctx, "session", "подсказка голосом чтения: $text")
        p.stop()
        hintSpeaking = true
        p.speak(text)
    }

    // ---------------- Шаг назад, который идёт назад (19.10.2026) ----------------
    // Память ОДНА на всё приложение: кнопки читалки («Пред.», свайпы, «Прыжок») и
    // кнопки гарнитуры. Раньше она жила только в окне, и гарнитура считала «назад»
    // всегда от текущего места ([backStepBase]).

    /** Куда встал последний шаг назад. */
    private var backStepAt: Place? = null

    /** Когда он был сделан (часы устройства; 0 — памяти нет). */
    private var backStepTime = 0L

    /** Сколько держим память шага назад: этого хватает, чтобы нажать несколько раз. */
    private val backStepMs = 20_000L

    /** Откуда считать шаг назад: от прошлой цели, если нажатия идут подряд. */
    fun backStepBase(bk: BookDocument): Place {
        val at = backStepAt
        val fresh = backStepTime != 0L &&
            android.os.SystemClock.uptimeMillis() - backStepTime < backStepMs
        val ok = fresh && at != null &&
            at.chapter in bk.chapters.indices &&
            at.sentence in bk.chapters[at.chapter].sentences.indices
        return if (ok) at!! else Place(chapterIdx, sentenceIdx)
    }

    fun rememberBackStep(ch: Int, s: Int) {
        backStepAt = Place(ch, s)
        backStepTime = android.os.SystemClock.uptimeMillis()
    }

    fun forgetBackStep() {
        backStepTime = 0L
    }

    /** Шаг гарнитуры словами настройки: место, куда ведёт шаг [step]. */
    private fun headsetTarget(bk: BookDocument, step: String, delta: Int, base: Place): Place? = when (step) {
        MainActivity.HS_SENTENCE -> headsetSentenceTarget(bk, delta, base)
        MainActivity.HS_PARAGRAPH -> headsetParagraphTarget(bk, delta)
        MainActivity.HS_SENT_N -> headsetJumpTarget(bk, delta, base) // msg5250: прыжок
        // msg5295: у «главы» три уровня — как у свайпов и кнопок читалки
        // (G_NEXT_CH/MAJOR/HEADER), а не одна настройка на все шаги.
        MainActivity.HS_MAJOR -> headsetChapterTarget(bk, delta, MainActivity.CH_NAV_MAJOR)
        MainActivity.HS_HEADER -> headsetChapterTarget(bk, delta, MainActivity.CH_NAV_ALL)
        else -> headsetChapterTarget(bk, delta, MainActivity.CH_NAV_CHAPTERS)
    }

    /** Цель шага совпадает с тем, где чтение стоит (или её нет) — сдвига не будет. */
    private fun samePlace(target: Place?): Boolean =
        target == null || (target.chapter == chapterIdx && target.sentence == sentenceIdx)

    /** Ближайшее предложение в сторону [delta] (граница главы переходит в соседнюю,
     *  как кнопки «Пред/След» в читалке). null — упёрлись в край книги. */
    private fun headsetSentenceTarget(
        bk: BookDocument,
        delta: Int,
        base: Place = Place(chapterIdx, sentenceIdx),
    ): Place? {
        var ch = base.chapter
        var s = base.sentence + delta
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

    /** Прыжок (msg5250): сразу N предложений в сторону [delta] — тем же правилом,
     *  что соседний шаг «предложение» (граница главы переходит в следующую). Число
     *  берётся из настроек прыжка: «вперёд» — своё, «назад» — своё. null — на
     *  этом краю книги двигаться некуда, шаг не состоялся. */
    private fun headsetJumpTarget(
        bk: BookDocument,
        delta: Int,
        base: Place = Place(chapterIdx, sentenceIdx),
    ): Place? {
        val count = MainActivity.jumpCount(prefs, delta > 0)
        var ch = base.chapter
        var s = base.sentence
        var left = count
        while (left > 0) {
            var nextCh = ch
            var nextS = s + delta
            if (nextS < 0) {
                if (nextCh <= 0) break
                nextCh--
                nextS = bk.chapters[nextCh].sentences.size - 1
            } else if (nextS >= bk.chapters[ch].sentences.size) {
                if (ch + 1 >= bk.chapters.size) break
                nextCh = ch + 1
                nextS = 0
            }
            ch = nextCh
            s = nextS
            left--
        }
        if (left == count) return null
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
     *  в читалке: [mode] выбирает уровень (CH_NAV_MAJOR — крупный раздел,
     *  CH_NAV_CHAPTERS — глава без подразделов, CH_NAV_ALL — любой заголовок),
     *  его несёт сам шаг кнопки, отдельной настройки нет (msg5295).
     *  Историю переходов (#101, pushPlace) не трогаем — это кнопка. */
    private fun headsetChapterTarget(bk: BookDocument, delta: Int, mode: String): Place? {
        val stops = chapterStopIndexes(mode)
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

    /** По каким «главам» ходит шаг [mode] кнопок гарнитуры — копия линейки окна
     *  (MainActivity.chapterStopIndexes): фильтр плоского списка глав по разметке
     *  FB2. Уровень приходит от самого шага (msg5295), из настроек ничего не
     *  читаем. Дублируем, чтобы гарнитура работала и без окна (движок —
     *  процессный синглтон); держать в синхроне с окном. */
    private fun chapterStopIndexes(mode: String): IntArray {
        val bk = book ?: return intArrayOf()
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

    /** Сменили голос или движок НА ХОДУ (msg5604).
     *
     *  Готовые заготовки сделаны прежним голосом: пока они не отыграют (до трёх
     *  в очереди плюс одна прицепленная встык), новый голос не слышен вовсе —
     *  Сергей и описал это как «переключаю, а он продолжает читать старым».
     *  Поэтому выбрасываем заготовки и перечитываем текущее предложение: новый
     *  голос слышен через доли секунды, ценой повтора первых слов фразы.
     *
     *  Ждём готовности движка и списка его голосов: после смены движка голоса
     *  приезжают не сразу (msg3550), и без ожидания первая фраза ушла бы
     *  голосом по умолчанию. Пауза/закрытие книги во время ожидания отменяют
     *  перезапуск — [playing] проверяем на каждом шаге.
     *
     *  07.10.2026, разбор журнала (жалоба Сержа «переключился на другой голос, а
     *  она ещё дочитывает фразу старым»). Здесь стоял ранний выход по
     *  `!playing`: на ПАУЗЕ заготовок и очереди уже нет, но остаётся звук
     *  паузной фразы (keptPhrase) и кэш трёх сыгранных — оба сделаны прежним
     *  голосом. Журнал: пауза 21:13:43 сохранила s_411.wav (kermen-ru-cis),
     *  голос сменили на kseniya-ru в 21:14:05, продолжение в 21:14:08 сыграло
     *  тот же файл и только через 6,6 с дошло до новой речи (21:14:15). ПРАВИЛО:
     *  любой путь смены голоса или движка обязан выбросить ВСЁ, что записано
     *  прежним голосом, — и когда чтение идёт, и когда оно стоит на паузе.
     *  Откат — вернуть ранний выход `if (!playing || book == null) return`.
     *
     *  19.10.2026, жалоба Сержа «остановил чтение, поменял голос, запустил снова —
     *  последняя единица звучит старым синтезом». Чистка стояла ТОЛЬКО внутри
     *  [whenVoicesReady]: пока движок отдаёт список голосов (после смены голоса
     *  он бывает занят), окно это от 0 до 3 с, и в нём успевала начаться читка —
     *  со старым звуком, потому что чистки ещё не было. Теперь чистка идёт
     *  СРАЗУ и на том же плеере, что выбирал голос ([dropVoiceSwitched]), а
     *  ожидание движка остаётся только для новой речи.
     *
     *  Здесь же — то, о чём Серж просил в том же письме: «при выборе голоса этим
     *  голосом зачитать последнюю единицу текста». На паузе выбор голоса звучит
     *  ПРОБОЙ: одна текущая фраза новым голосом, дальше тишина и пауза, место в
     *  книге не двигается ([previewUnit]). Поэтому список голосов закрывать не
     *  надо — он остаётся полезным: касание по голосу сразу слышно. */
    fun restartAfterSwitch(withPreview: Boolean = true) {
        if (book == null) return
        // Чистка — немедленно, до ожидания движка (см. вышеуказанную жалобу).
        val wasPreview = previewUnit
        dropVoiceSwitched()
        previewUnit = false
        whenVoicesReady {
            val p = player ?: return@whenVoicesReady
            if (book == null) return@whenVoicesReady
            // Повторяем чистку: пока ждали, могли доиграть заготовки прежнего
            // голоса. Стоит она дёшево, а промах — тот самый «старый звук».
            p.dropPrepared()
            if (!playing || wasPreview) {
                // Чтение стоит (или это была проба голоса). Пробу заказывает
                // только окно книги — в Настройках вместо неё звучит образец
                // фразы ([VoicePicker.speakSample]), и две речи разом там не
                // нужны.
                Diag.log(
                    ctx, "sound",
                    "смена голоса/движка: звук прежнего голоса выброшен" +
                        if (withPreview) ", пробую голос на текущей фразе" else " (проба не нужна)"
                )
                if (withPreview) startSpeakingCurrent(preview = true)
                return@whenVoicesReady
            }
            Diag.log(ctx, "sound", "смена голоса/движка на ходу — перечитываю предложение заново")
            startSpeakingCurrent()
        }
    }

    /** Выбросить всё, что записано прежним голосом, на том плеере, который
     *  выбирал голос. Синхронно и без условий: это первый шаг смены голоса или
     *  движка, а не последний (19.10.2026, см. [restartAfterSwitch]). */
    fun dropVoiceSwitched() {
        player?.dropPrepared()
        // Паузный звук и кэш сыгранных чистит сам dropPrepared; здесь снимаем
        // ещё и «место, где встал паузный файл»: прежним голосом играть его уже
        // нельзя, а новая фраза начнётся с начала.
        previewUnit = false
    }

    /** Сколько предложений занимает фраза, которая ЗВУЧИТ сейчас (0 — чтение
     *  стоит, 1 — обычная фраза из одного предложения).
     *
     *  05.10.2026, жалоба Сержа «фразы повторяются, когда листаю»: склейка берёт
     *  до [TIGHT_CHUNK_MAX_UNITS] предложений, а шаг вперёд был ровно на одно —
     *  человек вставал в СЕРЕДИНУ звучащей фразы и слышал уже слышанное (журнал
     *  18:53: «Тяжело! Тоска!» прозвучало дважды). Шаг вперёд обязан идти за конец
     *  звучащей фразы — сколько бы предложений в ней ни было. */
    fun playingPhraseUnits(): Int = if (playing) spokenUnits.coerceAtLeast(1) else 0

    /** Позвать [block], когда движок поднялся и отдал список своих голосов
     *  (msg5622). Список приезжает позже init, а публичного «список готов» в SDK
     *  нет (msg3550) — спрашиваем опросом. Не дождались за ~3 с — зовём как есть:
     *  молчание тут хуже, чем решение по неполным данным. */
    fun whenVoicesReady(attempt: Int = 0, on: SpeechPlayer? = player, block: () -> Unit) {
        val p = on ?: return
        if ((!p.isReady || p.voices.isEmpty()) && attempt < 15) {
            // 24.10.2026: список сам не приедет — просим его В СТОРОНЕ (см.
            // SpeechPlayer.refreshVoices). На главном потоке этот вопрос к движку
            // вешал окно выбора голоса: телефон показывал «закрыть или подождать».
            if (attempt % 4 == 0) p.refreshVoices()
            main.postDelayed({ whenVoicesReady(attempt + 1, on, block) }, 200)
            return
        }
        block()
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
        // msg4725: место в книге (глава) передаём сразу в extras — служба
        // поднимается асинхронно, и первое уведомление должно родиться уже с ним,
        // а не с «Пауза» до следующей публикации.
        cardLine = cardLineText()
        // msg4629: вместе с книгой передаём её uri — по нему карточка шторки
        // возвращает окно читалки к живой книге (касание карточки).
        MediaSessionService.start(
            ctx,
            bk.title ?: currentName,
            playing = playing,
            uri = currentUri,
            card = cardLine,
        )
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
        if (book == null) return false
        if (rp.chapter == 0 && rp.sentence == 0) return false
        // Место остановки держим «полом», ниже которого не пишем позицию, пока
        // чтение не дойдёт до него (анти-дрейф: повторные открытия не сползают).
        rewindFloor = rp
        val (ch, s) = stepBack(chapterIdx, sentenceIdx, n)
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
        pushCardLine()
        return true
    }

    /** Отступить на [n] предложений назад от места (ch0, s0) — то же движение,
     *  что делает откат при старте (msg2093). Чистая арифметика без побочных
     *  действий: ею же считает место [prewarmCurrent], чтобы приготовить звук
     *  ровно той фразы, с которой чтение начнётся. */
    private fun stepBack(ch0: Int, s0: Int, n: Int): Pair<Int, Int> {
        val bk = book ?: return ch0 to s0
        var ch = ch0
        var s = s0
        repeat(n) {
            if (s > 0) {
                s--
            } else if (ch > 0) {
                ch--
                s = bk.chapters[ch].sentences.size - 1
            }
        }
        return ch to s
    }

    /** Место, с которого реально начнётся чтение: с учётом отката при старте,
     *  если он вооружён и применится. Побочных действий не делает — в отличие
     *  от [applyStartRewindIfPending], которая двигает позицию. */
    private fun startPlace(): Pair<Int, Int> {
        val rp = restoredPlace
        val applies = startRewindPending > 0 && !userMoved && !playing &&
            rp != null && rp.chapter == chapterIdx && rp.sentence == sentenceIdx &&
            !(rp.chapter == 0 && rp.sentence == 0)
        return if (applies) stepBack(chapterIdx, sentenceIdx, startRewindPending)
        else chapterIdx to sentenceIdx
    }

    /** Приготовить звук первой фразы заранее — чтобы «читать» начиналось со
     *  звука, а не с синтеза (24.09.2026, просьба Сергея; замеры и риски —
     *  в [SpeechPlayer.prewarm]).
     *
     *  Зовём при открытии книги и при возврате окна к книге. Неподходящий
     *  момент ничего не ломает: движок не готов, голос книги ещё не применён,
     *  чтение идёт или включён альтернативный способ — просто ничего не
     *  готовим. Ждём готовности движка и списка голосов тем же способом, что и
     *  смена голоса (msg3550): прогреть фразу чужим голосом хуже, чем не
     *  прогревать вовсе. */
    fun prewarmCurrent() {
        if (playing || endAnnounceSpeaking || book == null) return
        whenVoicesReady { prewarmNow() }
    }

    private fun prewarmNow() {
        val p = player ?: return
        // Движок реплик поднимаем вместе с первой фразой (просьба Сержа
        // 27.09.2026): он поднимается за 0,19 с, и к первой реплике будет готов.
        // Иначе реплика ждала бы его или прозвучала голосом книги.
        p.prepareReplyEngine()
        if (playing || endAnnounceSpeaking || book == null) return
        if (!p.isReady || p.voices.isEmpty()) return
        // Голос книги мог ещё не примениться: спрашиваем тот, что для книги
        // выбран, и греем только когда движок уже на нём.
        val want = perBookVoice ?: prefs.getString(MainActivity.KEY_VOICE, null)
        if (want != null && want != voiceName) return
        val (ch, s) = startPlace()
        if (book?.chapters?.getOrNull(ch)?.sentences.isNullOrEmpty()) return
        val raw = chunkText(ch, s, spanAt(ch, s, first = true)) ?: return
        val first = Roles.splitSpeech(raw)?.firstOrNull() ?: raw
        p.prewarm(first, ch * 1_000_000L + s)
    }

    /** Начать чтение с текущей позиции (см. исходный startSpeakingCurrent в
     *  MainActivity). Держим открытым — окно зовёт его по жесту «повторить».
     *
     *  [userAsked] — чтение начала сама читалка по ходу книги (false) или фразу
     *  попросил человек (true: «повторить», шаг назад, прыжок, продолжение после
     *  паузы, смена голоса). Различие важно плееру: по ходу книги единица речи
     *  звучит ОДИН раз за проход, а по просьбе человека — повторяем, и звук для
     *  этого берём из кэша сыгранных (08.10.2026, см. [SpeechPlayer.speak]).
     *
     *  [preview] — «проба голоса» (19.10.2026): звучит ровно эта фраза, дальше
     *  чтение встаёт и место в книге не двигается (см. [previewUnit]). */
    fun startSpeakingCurrent(userAsked: Boolean = true, preview: Boolean = false) {
        val bk = book ?: return
        val p = player ?: return
        // Любое начало чтения снимает пробу, кроме той, что заказана пробой.
        previewUnit = preview
        // Плееру говорим то же самое: во время пробы он не заказывает фразы
        // впрок (см. [SpeechPlayer.previewOnly]).
        p.previewOnly = preview
        // Читатель снова взялся за книгу — незачем говорить «книга прочитана»
        // (и незачем договаривать её, если она уже звучит). Заодно снимаем
        // признак «пауза по перевороту»: дальше переворот экраном вверх уже
        // ничего не продолжает, читает человек.
        cancelEndAnnounce()
        pausedByFaceDown = false
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
        // 0.4.77: состояние публикуем СРАЗУ, до речи. Раньше оно публиковалось
        // только в самом конце функции, и если [speakCurrent] выходил раньше
        // (фраза из одних знаков, конец книги), система оставалась считать нас
        // на паузе — а по этому состоянию она решает, что пошлёт «волшебное
        // касание»: играть или пауза. Отсюда и жалоба «касание перестало
        // ставить паузу». Вторая публикация в конце остаётся: она обновляет
        // строку карточки.
        pushPlayState()
        Diag.log(
            ctx, "activity",
            "старт чтения (глава $chapterIdx, предл. $sentenceIdx); движок=${p.enginePackage}, " +
                "голос=$voiceName, версия $versionLabel"
        )
        requestAudioFocus()
        host?.onShowCurrent()
        applyReplySettings(p)
        p.prepareReplyEngine()
        logChapterStats()
        // 06.10.2026: принудительный способ чтения из опытных настроек
        // ([ExpertPrefs.forceMode]) читаем ЗДЕСЬ — на старте чтения. Ручку меняют
        // в другом окне, а плеер живёт дольше одной книги, поэтому решение надо
        // обновлять; на ходу путь не меняем — это обрыв звука (см.
        // [SpeechPlayer.applyForceMode]).
        p.applyForceMode()
        speakCurrent(userAsked)
        pushPlayState()
    }

    /** Счётчики реплик по главе — в журнал, один раз на главу (26.09.2026).
     *  Нужны, чтобы решать про голоса героев по числам, а не на слух: сколько
     *  предложений, сколько реплик и кто в них назван прямо. Считается по уже
     *  разобранной книге, то есть мгновенно. */
    private var statsChapter = -1

    private fun logChapterStats() {
        val bk = book ?: return
        if (chapterIdx == statsChapter) return
        val ch = bk.chapters.getOrNull(chapterIdx) ?: return
        statsChapter = chapterIdx
        Diag.log(
            ctx, "roles",
            "глава ${chapterIdx + 1}: ${Roles.statsLine(Roles.stats(ch.sentences))}"
        )
    }

    /** Текст, который будет реально озвучен на позиции (ch, s). На первом
     *  предложении главы спереди добавляется её название. Название, дословно
     *  совпадающее с первым предложением, не дублируем.
     *
     *  30.09.2026: галочки «Озвучивать название главы в начале» больше нет —
     *  Серж: «пускай всегда включена, а галочку убрать». Заголовок главы из
     *  текста вынимает разбор (в FB2 это <title> секции, в DOCX, ODT и HTML —
     *  абзац-заголовок), поэтому без этой приклейки на стыке глав в чтении не
     *  звучало бы ничего: движок молча переходил бы к первой фразе следующей
     *  главы. На экране название и так стоит строкой-заголовком, в оглавлении
     *  оно есть, а после прыжка его называет объявление места. */
    private fun spokenText(ch: Int, s: Int): String? {
        val bk = book ?: return null
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
        val text = cur.getOrNull(s)?.text ?: return null
        if (s != 0) return text
        // 0.4.71: название, которое придумали мы сами («Стр. N» у PDF без
        // закладок), вслух не читаем — иначе на каждой странице книги звучит
        // «страница один», «страница два». В оглавлении оно остаётся: по нему
        // переходят к нужной странице.
        if (bk.chapters[ch].autoTitle) return text
        val title = bk.chapters[ch].title?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        return if (title == text.trim()) text else "$title. $text"
    }

    private fun speakCurrent(userAsked: Boolean = true) {
        // 05.10.2026: первую фразу после ручного перехода (прыжка) читаем КОРОТКИМ
        // куском — одним предложением (флаг ставит [goTo]). Склейка берёт до
        // четырёх предложений и до 220 знаков, а движок пишет примерно 15 мс на
        // знак: длинная фраза после прыжка — это 3-4,5 с тишины. Одно предложение
        // доспевает в разы быстрее, звук идёт почти сразу, а следующие предложения
        // читаются дальше обычной склейкой.
        // 07.10.2026 (жалоба Сержа «фразы повторяются», разбор журнала): СНАЧАЛА
        // берём флаг, потом считаем длину фразы. Было наоборот — [spanAt] читал
        // СТАРОЕ значение поля, то есть флаг «укоротить» доставался не той фразе,
        // для которой его ставили, а следующей. И тут же расходились два счёта:
        // читалка считала фразу одной длины, а заготовка впрок ([phraseStartAhead],
        // он берёт флаг уже новым) — другой. В журнале 00:35-00:36 это видно как
        // «фраза собрана: предл. 10» (одно предложение) при файле s_180 на ТРИ
        // предложения: файл звучал целиком, чтение двигалось на одно — и то же
        // предложение позже читалось снова.
        firstPhraseShort = shortFirstPhrase
        shortFirstPhrase = false
        val span = spanAt(chapterIdx, sentenceIdx, first = true)
        // Запоминаем для [phraseStartAhead]: он считает, что читать дальше, и
        // первый шаг обязан совпасть с этой фразой (иначе очередь заготовок
        // уезжает вперёд, и текст повторяется — журнал 21:03).
        spokenUnits = span
        var frags = voicedFragments(chapterIdx, sentenceIdx, spokenUnits)
        // msg4426: фраза из одних знаков (в книге «‹…›» — маркер пропуска) — для
        // движка пустой звук: он отдаёт файл в 44 байта, а мы всё равно тратим
        // цикл плеера и слышим паузу в четверть секунды. Пропускаем такие
        // фразы, не отдавая их движку вовсе. Ограничитель — чтобы битый текст
        // или дыра в разборе не гоняли нас по кругу.
        var guard = 0
        while (frags.isEmpty() && guard++ < 8) {
            val atCh = chapterIdx
            val atS = sentenceIdx
            val raw = chunkText(atCh, atS, spokenUnits)
            if (!advanceUnits(spokenUnits.coerceAtLeast(1))) {
                // Та же остановка, что и в onUtteranceDone: если это настоящий
                // конец книги — скажем об этом, а не просто замолчим.
                val finished = atBookEnd()
                stopAtEnd()
                if (finished) scheduleBookEnd()
                return
            }
            spokenUnits = spanAt(chapterIdx, sentenceIdx)
            frags = voicedFragments(chapterIdx, sentenceIdx, spokenUnits)
            // 30.09.2026: пишем, ПОЧЕМУ фраза не прозвучала. Без этой строки
            // «убрал строку правилом — а она всё равно читается / пропала» не
            // разобрать: у словаря свой выключатель, у правила — своя книга.
            Diag.log(
                ctx, "dict",
                if (raw != null) {
                    "словарь убрал фразу: глава $atCh, предл. $atS «${raw.replace(Regex("\\s+"), " ").take(40)}»"
                } else {
                    "фразу пропускаю (одни знаки): глава $atCh, предл. $atS"
                },
            )
        }
        if (frags.isEmpty()) return
        // 07.10.2026: БЫСТРЫЙ СТАРТ. Первая фраза после перехода и так короткая
        // (одно предложение, см. [shortFirstPhrase]), но предложение бывает и в
        // двести знаков: движок пишет его секунды три-четыре, и всё это время
        // человек слышит тишину. Если по замеру движка (см.
        // [SpeechPlayer.estimateSynthMs]) первая фраза выйдет дольше
        // [FIRST_CUT_MS], режем её по первой подходящей запятой: голова звучит
        // почти сразу, а хвост движок пишет, пока она играет (его заказывает
        // [SpeechPlayer.prefetchRest] — как второй кусок реплики).
        frags = cutFirstForStart(frags)
        // 30.09.2026: в журнал — ДЛЯ КАКОГО предложения собрана фраза и с чего она
        // начинается. Без этой строки не разобрать жалобу «фраза прозвучала дважды»
        // и «похожие фразы не в том порядке»: у плеера видно имя файла и текст, а к
        // какому месту книги он относится — приходилось угадывать по времени.
        Diag.log(
            ctx, "sound",
            "фраза собрана: глава $chapterIdx, предл. $sentenceIdx" +
                (if (spokenUnits > 1) "…${sentenceIdx + spokenUnits - 1}" else "") +
                " «${frags.joinToString(" / ") { it.replace(Regex("\\s+"), " ").take(40) }}»"
        )
        pendingFragments = frags.drop(1)
        fragmentPos = chapterIdx * 1_000_000L + sentenceIdx
        // Укороченная фраза — эта (см. [shortPhrasePos]).
        shortPhrasePos = if (firstPhraseShort) fragmentPos else -1L
        // 23.10.2026: остальные куски отдаём ВМЕСТЕ с головой — четвёртым
        // параметром [SpeechPlayer.speak]. В файловом пути их заказывает
        // [SpeechPlayer.prefetchRest] (вызов ниже), а в прямом пути они обязаны
        // встать в очередь движка СРАЗУ за головой и до заказа следующих фраз:
        // иначе хвост фразы оказывается позади следующей фразы, и когда чтение
        // попросит его, читалка гасит очередь движка (`tts.stop()`) посреди уже
        // начавшейся чужой речи — на слух «фраза обрывается и начинается заново».
        player?.speak(frags.first(), fragmentPos, userAsked, pendingFragments)
        // 05.10.2026: остальные куски (реплики после слов автора) заказываем
        // сразу, а не когда первый кусок доиграет — иначе реплика синтезируется
        // поздно и встаёт в конец очереди (за уже заготовленными следующими
        // фразами), и прямая речь пропускается или звучит не на месте.
        // 23.10.2026: в прямом пути это уже сделал [SpeechPlayer.speak], здесь
        // вызов ничего не заказывает повторно (сверка по ключу единицы).
        if (pendingFragments.isNotEmpty()) {
            player?.prefetchRest(pendingFragments, fragmentPos)
        }
    }

    /** Озвучиваемые куски фразы (ch, s, n): после разрезания прямой речи на
     *  реплики и слова автора и после словаря произношения. Пусто — произносить
     *  нечего (одни знаки, или словарь убрал всё). */
    private fun voicedFragments(ch: Int, s: Int, n: Int): List<String> {
        val raw = chunkText(ch, s, n) ?: return emptyList()
        val parts = Roles.splitSpeech(raw) ?: listOf(raw)
        return parts.mapNotNull { dictText(it) }
    }

    /** БЫСТРЫЙ СТАРТ: разрезать первую фразу по запятой (07.10.2026, просьба
     *  Сержа — пятый пункт списка «что взять у MyTTS»; вопрос был «а оно будет
     *  быстрее стартовать?», ответ — да, но не всегда).
     *
     *  Когда это помогает. Первая фраза после перехода и так короткая — одно
     *  предложение ([shortFirstPhrase]), но предложение бывает и в двести знаков:
     *  у MyTTS это ~16 мс на знак, то есть три с половиной секунды тишины перед
     *  первым звуком; кусок до первой запятой доспевает за секунду. Считаем по
     *  замеру самого движка ([SpeechPlayer.estimateSynthMs]) и режем только если
     *  выходит дольше [FIRST_CUT_MS].
     *
     *  Когда не помогает и потому не делаем: у ХОЛОДНОГО движка (после запуска или
     *  смены голоса) тишину съедает его подготовка, а не длина; и у MyTTS резать
     *  там даже вредно — он готовит текст по фрагментам, и разрезанное предложение
     *  станет двумя фрагментами с двумя подготовками.
     *
     *  Голова и хвост — куски ОДНОЙ фразы ([ReaderEngine.pendingFragments]): хвост
     *  звучит сразу за головой, позиция книги двигается только после него, а
     *  заказывается он заранее (см. [SpeechPlayer.prefetchRest]). Хвосту
     *  возвращаем знак реплики, иначе он перестал бы быть репликой и зазвучал
     *  другим голосом (на этих граблях уже стояли в сборке 209). */
    private fun cutFirstForStart(frags: List<String>): List<String> {
        if (!firstPhraseShort) return frags
        val head = frags.firstOrNull() ?: return frags
        val p = player ?: return frags
        if (p.estimateSynthMs(head) <= FIRST_CUT_MS) return frags
        val at = commaForStart(head) ?: return frags
        val cut = head.substring(0, at + 1).trim()
        val tail = head.substring(at + 1).trim()
        if (cut.isEmpty() || tail.isEmpty()) return frags
        val mark = roleMarkOf(head)
        val tailText = if (mark.isNullOrEmpty()) tail else "$mark $tail"
        Diag.log(
            ctx, "sound",
            "быстрый старт: первая фраза разрезана по запятой (по замеру движка " +
                "${p.estimateSynthMs(head)} мс) — " +
                "«${cut.replace(Regex("\\s+"), " ").take(40)}» / " +
                "«${tailText.replace(Regex("\\s+"), " ").take(40)}»"
        )
        return listOf(cut, tailText) + frags.drop(1)
    }

    /** Где резать: первая запятая, при которой голова не короче
     *  [FIRST_CUT_HEAD_PERCENT] от хвоста. Иначе вышло бы хуже, чем было: голова
     *  доиграет за полсекунды, а хвост ещё пишется — та же тишина, только посреди
     *  фразы. null — подходящей запятой нет, фразу не трогаем. */
    private fun commaForStart(text: String): Int? {
        var from = 0
        while (true) {
            val at = text.indexOf(',', from)
            if (at < 0) return null
            val headLen = at + 1
            val tailLen = text.length - headLen
            if (tailLen <= 0) return null
            if (headLen * 100 >= tailLen * FIRST_CUT_HEAD_PERCENT) return at
            from = at + 1
        }
    }

    /** Знак реплики в начале текста (длинное тире или свой знак из настроек) —
     *  им помечаем хвост, чтобы он читался тем же голосом. */
    private fun roleMarkOf(text: String): String? {
        val t = text.trimStart()
        if (t.isEmpty()) return null
        if (t[0] == '—' || t[0] == '–' || t[0] == '−') return t.substring(0, 1)
        for (m in Roles.ownMarks) {
            if (m.isNotEmpty() && t.startsWith(m, ignoreCase = true)) return m
        }
        return null
    }

    /** Быстрый старт: с какого расчётного времени синтеза режем первую фразу по
     *  запятой (см. [cutFirstForStart]). 2,5 с — уже заметная тишина; короче
     *  резать незачем, целое предложение звучит ровнее. */
    private const val FIRST_CUT_MS = 2_500L

    /** Голова не короче этой доли от хвоста (в процентах): иначе хвост не успеет
     *  доспеть, пока голова играет (см. [commaForStart]). */
    private const val FIRST_CUT_HEAD_PERCENT = 35

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

    /** Сколько предложений берём в одну фразу на позиции (ch, s).
     *
     *  Одно предложение — в двух случаях: первая фраза после ручного перехода
     *  ([firstPhraseShort], см. [speakCurrent]) и ЛЮБАЯ фраза у медленного голоса
     *  (просьба Сержа 06.10.2026: «сделай чтобы по одному предложению, чтобы
     *  паузы были поменьше»). У сетевого голоса MultiTTS первая фраза в 166
     *  знаков зазвучала только через 15,3 с — это время его сборки; одно
     *  предложение собирается в разы быстрее, поэтому и пауза короче.
     *
     *  ВАЖНО: длину фразы считают ЧЕТЫРЕ места, и все они обязаны звать эту
     *  функцию, иначе очередь заготовок разойдётся с чтением и текст повторится
     *  (грабли сборок 240, 242 и 243): [speakCurrent], [phraseStartAhead],
     *  [speakablePhrase] и [peekNextFragments]. Первая фраза после перехода
     *  отличается от остальных, поэтому у неё отдельный флаг [first].
     *
     *  ОТКАТ (Серж, 06.10.2026: «пометишь, что мы сейчас фразы эти короткие, если
     *  будет плохо — тогда вернём»). Возврат к прежнему поведению — убрать
     *  строку `player?.readsSlowly == true -> 1`: у медленного голоса снова
     *  заработает обычная склейка до четырёх предложений, и всё остальное
     *  останется как есть. Больше ничего трогать не нужно. */
    private fun spanAt(ch: Int, s: Int, first: Boolean = false): Int = when {
        first && firstPhraseShort -> 1
        // 24.10.2026: в ГОЛОСОВОМ (обычном) чтении фраза — ОДНО предложение.
        // Так книга движется по предложениям, и пауза/откат повторяют только
        // текущее предложение, а не склейку из четырёх (решение Сержа: «Один
        // повтор текущей фразы допустим»). Склеивание осталось только в файловом
        // (обходном) пути, где паузы срезаем мы сами.
        player?.directNow == true -> 1
        player?.readsSlowly == true -> 1
        else -> chunkSpan(ch, s)
    }

    /** Сколько предложений с [s] уйдёт в движок одной фразой. Склейка коротких
     *  фраз включена всегда (msg5254: галочка убрана). Без побочных эффектов:
     *  позицию не двигает. */
    private fun chunkSpan(ch: Int, s: Int): Int {
        val cur = book?.chapters?.getOrNull(ch)?.sentences ?: return 1
        var n = 1
        var len = cur.getOrNull(s)?.text?.length ?: return 1
        // 27.09.2026 (жалоба Сержа: «в диалогах тот же автор звучит вторым
        // синтезатором»): голос выбирается один раз на всю фразу и по её
        // началу, поэтому слова автора, приклеенные к реплике в одном абзаце,
        // читались голосом реплик. В «Моем тюремном трипе» таких мест 31 в
        // одной главе. Пока реплики читает второй движок, склейку
        // останавливаем на смене голоса: у каждого куска будет свой.
        // Роли выключены (или второй движок отказал) — правило не работает
        // вовсе, и склейка остаётся ровно прежней.
        val splitRoles = player?.replyVoicesActive == true
        val headReplica = splitRoles && Roles.looksReplica(cur[s].text)
        while (n < TIGHT_CHUNK_MAX_UNITS && s + n < cur.size) {
            // Абзац — смысловая граница (в дневниках это новая запись, новая
            // дата): через неё не склеиваем. Так пауза на границе абзаца
            // остаётся настоящей и слышимой, а укорачивается только то, что
            // внутри абзаца.
            if (cur[s + n].paragraphStart) break
            // Смена голоса — такая же граница: реплика и слова автора не
            // должны звучать одним голосом.
            if (splitRoles && Roles.looksReplica(cur[s + n].text) != headReplica) break
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
     *  следующая). null — книга кончилась. Позицию не двигает.
     *
     *  29.09.2026: считаем ФРАЗЫ, КОТОРЫЕ ПРОЗВУЧАТ. Раньше в счёт попадали и
     *  те, что чтение пропустит, — а пропускает оно две породы: фразы из одних
     *  знаков (маркер пропуска «‹…›») и фразы, убранные словарём произношения
     *  (правило с пустой заменой). Из-за этого очередь заготовок спотыкалась:
     *  как только очередная фраза вперёд оказывалась пропускаемой, [peekNextFragments]
     *  отвечал null, [SpeechPlayer.requestPrefetch] ничего не заказывал, и
     *  очередь так и стояла на глубине 1 из 3. В журнале Сержа это видно
     *  строками «заготовка готова … в очереди 1/3» у каждой фразы, а с медленным
     *  сетевым синтезатором (MultiTTS, файл готовится ~3 с) это и есть паузы:
     *  держать впереди нечего. Теперь считаем так же, как считает само чтение
     *  ([speakCurrent] пропускает такие фразы и идёт дальше), — очередь снова
     *  наполняется до конца. */
    private fun phraseStartAhead(offset: Int): Pair<Int, Int>? {
        val bk = book ?: return null
        var ch = chapterIdx
        var s = sentenceIdx
        var left = offset.coerceAtLeast(1)
        // Ограничитель на случай битого разбора: лучше вернуть null, чем крутиться.
        var guard = 0
        // Первый шаг считаем так же, как сама читаемая фраза: после перехода она
        // бывает укороченной ([firstPhraseShort]) — одно предложение. Пересчёт от
        // позиции по обычной склейке увёл бы очередь заготовок вперёд, и текст
        // повторился бы (журнал 21:03: «Скоро вдали показался паровозный дымок…»).
        var first = true
        while (guard++ < 128) {
            val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
            if (cur.isEmpty()) return null
            s += spanAt(ch, s, first = first)
            first = false
            if (s >= cur.size) {
                ch++
                s = 0
                if (ch >= bk.chapters.size) return null
            }
            // Пропускаемую фразу в счёт не берём: её движку не отдадут.
            if (!speakablePhrase(ch, s)) continue
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

    /** Прозвучит ли фраза с позиции (ch, s): словарь её не убрал и в ней есть что
     *  произносить. То же правило, по которому [speakCurrent] пропускает фразу. */
    private fun speakablePhrase(ch: Int, s: Int): Boolean =
        dictText(chunkText(ch, s, spanAt(ch, s))) != null

    /** Все куски фразы через [offset] вперёд — без побочных эффектов (не двигает
     *  позицию). Для упреждающего синтеза плеера (msg4472: он держит очередь
     *  заготовок и спрашивает не только следующую фразу). 05.10.2026: отдаём
     *  ВСЕ куски фразы, а не только первый: прямая речь режется на реплики и
     *  слова автора, и реплика обязана быть готова и стоять в очереди сразу за
     *  своим авторским куском — иначе она заказывается поздно, когда авторский
     *  кусок уже доиграл, и уходит в конец очереди (жалоба «второй синтезатор
     *  не читает прямую речь, фраза пропускается»).
     *
     *  08.10.2026: третье значение — НОМЕР КУСКА (см. [Units.key]). Номер считаем
     *  по ПРОИЗНОСИМЫМ кускам, по порядку: ровно так же нумерует сама читалка,
     *  когда говорит фразу ([speakCurrent] и [SpeechPlayer.prefetchRest]). Если
     *  словарь убрал кусок, номера остальных сдвигаются — иначе ключ заготовки и
     *  ключ просьбы чтения разошлись бы, и файл не нашёл бы своё место. */
    private fun peekNextFragments(offset: Int): List<Triple<String, Long, Int>>? {
        val p = phraseStartAhead(offset) ?: return null
        val raw = chunkText(p.first, p.second, spanAt(p.first, p.second)) ?: return null
        val parts = Roles.splitSpeech(raw) ?: listOf(raw)
        val pos = p.first * 1_000_000L + p.second
        val out = ArrayList<Triple<String, Long, Int>>(parts.size)
        for (part in parts) {
            val t = dictText(part) ?: continue
            if (t.isBlank()) continue
            out.add(Triple(t, pos, out.size))
        }
        return if (out.isEmpty()) null else out
    }

    /** Стартовая позиция фразы, которая прозвучит сразу ПОСЛЕ позиции [pos] —
     *  или null, если книга кончилась (07.10.2026).
     *
     *  Зачем это плееру. Он держит список фраз у ExoPlayer и дописывает туда
     *  заготовки вперёд. Порядок в списке — это порядок звука, а не порядок
     *  готовности, поэтому список обязан идти по книге. Проверить это можно
     *  только здесь: длину фразы знает читалка (склейка коротких предложений,
     *  укороченная первая фраза после перехода, словарь, роли), и она же одна
     *  знает, что дальше. Жалоба Сержа 07.10.2026 «фразы повторяются» — как раз
     *  про файл, который встал в список через одну фразу: он звучал раньше
     *  очереди, чтение считало фразы по «доиграла» и уезжало, а потом та же
     *  фраза заказывалась и звучала второй раз. */
    fun nextPhrasePos(pos: Long): Long? {
        val bk = book ?: return null
        if (pos < 0) return null
        var ch = (pos / 1_000_000L).toInt()
        var s = (pos % 1_000_000L).toInt()
        if (bk.chapters.getOrNull(ch)?.sentences?.getOrNull(s) == null) return null
        // Первый шаг — такой же, как у самой фразы: укороченная после перехода
        // читается одним предложением ([shortPhrasePos]).
        var first = pos == shortPhrasePos
        var guard = 0
        while (guard++ < 128) {
            val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
            if (cur.isEmpty()) return null
            s += spanAt(ch, s, first = first)
            first = false
            if (s >= cur.size) {
                ch++
                s = 0
                if (ch >= bk.chapters.size) return null
            }
            if (!speakablePhrase(ch, s)) continue
            // За границу выделенного куска (#106) и за главу с таймером сна
            // «до конца главы» фраз впрок не готовят — и в список их не берут.
            val re = rangeEnd
            if (re != null && (ch > re.chapter || (ch == re.chapter && s >= re.sentence))) return null
            if (sleepMode == SLEEP_CHAPTER && ch != chapterIdx) return null
            return ch * 1_000_000L + s
        }
        return null
    }

    /** Книга для словаря: по ней он считает, сколько раз встречается искомое. */
    fun bookOrNull(): BookDocument? = book

    /** Текст фразы с применённым словарём произношения (27.09.2026). Делаем это
     *  ЗДЕСЬ, а не в плеере: тогда и очередь заготовок, и сверки текста, и сам
     *  синтез видят одно и то же — иначе конвейер ругался бы, что очередь
     *  разошлась с чтением (движку уходит «эс-вэ-о», а читалка просит «СВО»).
     *
     *  Роли словарь сломать не может, и это обеспечено с двух сторон. Тире в
     *  начале реплики выводим из-под замены: правило применяется к тексту ПОСЛЕ
     *  него, а само тире приклеивается обратно нетронутым. И наоборот: если
     *  правило вставило тире в начало фразы, которая тире не начиналась, такое
     *  тире срезаем — иначе фраза перескочила бы в реплики и зазвучала вторым
     *  голосом (просьба Сержа 27.09.2026). */
    private fun dictText(text: String?): String? {
        if (text == null || text.isEmpty()) return text
        if (!Dict.enabled(ctx)) return text
        val replyOn = prefs.getBoolean(MainActivity.KEY_REPLY_ON, false)
        val reply = replyOn && player?.replyVoicesActive == true && Roles.looksReplica(text)
        val engine = if (reply) prefs.getString(MainActivity.KEY_REPLY_ENGINE, null)
        else prefs.getString(MainActivity.KEY_ENGINE, null)
        // Книга нужна словарю, чтобы применить её собственные правила и не трогать
        // правила других книг (28.09.2026).
        val book = currentName
        // Знак реплики из озвучки убирает САМ плеер (SpeechPlayer → Roles.stripped):
        // здесь этого делать нельзя. Роль фразы решается по исходному тексту, и если
        // срезать знак тут, фраза перестаёт быть репликой — ровно это и случилось в
        // сборке 209 (30.09.2026): в книгах с дефисом после номера голоса не
        // переключались.
        val src = text
        val t = src.trimStart()
        if (t.isNotEmpty() && (t[0] == '—' || t[0] == '–' || t[0] == '−')) {
            val cut = src.length - t.length + 1
            val body = Dict.apply(ctx, src.substring(cut), engine, book)
            // 30.09.2026: словарь убрал САМУ реплику (книжное правило вида
            // «убрать такие строки») — тогда тире остаётся одно, и движок получал
            // фразу из одного знака: в журнале «фраза собрана … «—»». Речи в нём
            // нет: отдаём null, чтобы чтение пропустило фразу целиком. Нашлось на
            // живой проверке правила для строк «— Ред.».
            if (body.isBlank()) return null
            return (src.substring(0, cut) + body).ifBlank { null }
        }
        // Пустой результат означает, что фразу убрал словарь (например книжное
        // правило выкинуло строку-источник вроде «1995 (с лекций МГУ)»). Отдаём
        // null — чтение пропустит её и пойдёт дальше, а не замолчит на месте
        // (та же ветка, что у фраз из одних знаков).
        //
        // 06.10.2026: правки текста из списка RuVoice (ссылки, скобки, знаки
        // именем) написали и ОТЛОЖИЛИ — Серж: «давай отложим эти правки на потом».
        // Разбор и решение, где будет галочка, — docs/BACKLOG.md, раздел про RuVoice.
        return trimForeignDash(Dict.apply(ctx, src, engine, book)).ifBlank { null }
    }

    /** Срезать тире в начале фразы, которого в книге не было: его могло вставить
     *  правило словаря. Роль фразы решается по первому знаку, и лишнее тире
     *  перевело бы обычный текст в реплики. */
    private fun trimForeignDash(s: String): String {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length || !(s[i] == '—' || s[i] == '–' || s[i] == '−')) return s
        var j = i + 1
        while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
        return s.substring(0, i) + s.substring(j)
    }

    private fun onUtteranceDone() {
        if (!playing) return
        // Проба голоса (19.10.2026): звучит ОДНА фраза с текущего места — и
        // чтение встаёт, место в книге не двигается. Куски той же фразы (слова
        // автора, хвост реплики) дочитываем: голос слушают целиком.
        if (previewUnit) {
            if (pendingFragments.isNotEmpty()) {
                val next = pendingFragments.first()
                pendingFragments = pendingFragments.drop(1)
                player?.speak(next, fragmentPos, userAsked = true)
                return
            }
            previewUnit = false
            Diag.log(
                ctx, "sound",
                "проба голоса: фраза прозвучала — чтение остаётся на паузе, место не сдвинуто"
            )
            stopAtEnd()
            return
        }
        if (!continuous) {
            stopAtEnd()
            return
        }
        // 05.10.2026: у фразы могли остаться не прочитанные куски (слова автора
        // или продолжение реплики) — читаем следующий кусок той же позиции, не
        // двигая место в книге.
        if (pendingFragments.isNotEmpty()) {
            val next = pendingFragments.first()
            pendingFragments = pendingFragments.drop(1)
            // Куски одной фразы — это просьба самой читалки, а не человека:
            // повтор по ходу книги плеер гасит (см. [SpeechPlayer.speak]).
            player?.speak(next, fragmentPos, userAsked = false)
            return
        }
        if (!advanceUnits(spokenUnits)) {
            // Конец книги — не то же самое, что «чтение встало»: сюда же
            // приходят таймер сна «до конца главы» и конец выделенного куска
            // (#106), поэтому проверяем место, а не сам факт остановки.
            val finished = atBookEnd()
            stopAtEnd()
            if (finished) scheduleBookEnd()
            return
        }
        // Шаг по книге делает сама читалка — это не просьба человека.
        startSpeakingCurrent(userAsked = false)
    }

    /** Сколько раз подряд пришёл конец «чужой» единицы речи. Дальше трёх раз не
     *  упираемся: пусть лучше место двинется, чем чтение встанет насовсем. */
    private var foreignDone = 0

    /** Фраза доиграла: [onUtteranceDone] — про фразы книги, а служебное
     *  «книга прочитана» живёт своей жизнью и позиции в тексте не имеет.
     *
     *  08.10.2026 (жалоба Сержа «два раза какая-то фраза повторялась», журнал
     *  21:43 и 12:45). Плеер вместе с сигналом присылает, КАКАЯ единица речи
     *  доиграла: место в книге и номер куска. Раньше чтение двигало место на
     *  любом «доиграла», и конец файла, прозвучавшего не в свою очередь, уводил
     *  его вперёд: чтение просило фразу, которая уже прозвучала, кэш сыгранных
     *  отдавал её звук — повтор слышался мгновенно. Теперь место двигается
     *  только по СВОЕЙ единице, а если звук ушёл вперёд — чтение подтягивается к
     *  тому, что реально звучит.
     *
     *  Выключатель «Прежний порядок чтения» в опытных настройках возвращает
     *  прежнее поведение. */
    private fun onPlayerDone() {
        if (endAnnounceSpeaking) {
            finishEndAnnounce()
            return
        }
        // Служебная подсказка гарнитуры доиграла — продолжаем чтение с места, куда
        // только что перешли (см. [speakHint]). Место при этом НЕ двигаем: это была
        // не фраза книги.
        if (hintSpeaking) {
            hintSpeaking = false
            Diag.log(ctx, "session", "подсказка прозвучала — продолжаю чтение с нового места")
            if (playing) startSpeakingCurrent(userAsked = true)
            return
        }
        if (!playing) return
        val sp = player
        if (sp != null) {
            val donePos = sp.lastDonePos
            val doneLast = sp.lastDoneLast
            when {
                donePos < 0 -> foreignDone = 0
                donePos == fragmentPos -> {
                    if (!doneLast && pendingFragments.isEmpty()) {
                        // Кусок не последний, а других кусков чтение не ждёт:
                        // значит прозвучал файл из середины чужой фразы.
                        foreignDone++
                        Diag.log(
                            ctx, "sound",
                            "указатель: кусок ${posLabel(donePos)} не последний, своих кусков " +
                                "чтение не ждёт — место в книге не двигаю (раз $foreignDone)"
                        )
                        if (foreignDone < 3) return
                    } else {
                        foreignDone = 0
                    }
                }
                donePos < fragmentPos -> {
                    // Хвост прежней фразы или запоздавший кусок: двигать место
                    // нечем. Пишем один раз, чтобы не забивать журнал.
                    if (foreignDone == 0) {
                        Diag.log(
                            ctx, "sound",
                            "указатель: доиграла прежняя единица ${posLabel(donePos)}, " +
                                "ждём ${posLabel(fragmentPos)} — место не двигаю"
                        )
                    }
                    foreignDone++
                    return
                }
                else -> {
                    // ЗВУК УШЁЛ ВПЕРЁД нашего места. Раньше чтение двигалось на
                    // одно предложение и просило уже прозвучавшее (повтор). Теперь
                    // подтягиваем место к тому, что реально звучит.
                    foreignDone++
                    Diag.log(
                        ctx, "sound",
                        "указатель: звук ушёл вперёд — читалка стояла на ${posLabel(fragmentPos)}, " +
                            "доиграла ${posLabel(donePos)}; подтягиваю место к звуку"
                    )
                    catchUpTo(donePos)
                }
            }
        }
        onUtteranceDone()
    }

    /** Место в книге словами — для журнала. Номера те же, что в «старт чтения»
     *  («глава $chapterIdx»), — единицу НЕ прибавлять: иначе строки журнала
     *  расходятся между собой и разбор путается. */
    private fun posLabel(pos: Long): String =
        if (pos < 0) "без места" else "глава ${pos / 1_000_000L}, предл. ${pos % 1_000_000L}"

    /** Подтянуть чтение к тому месту, которое реально звучит: звук обогнал
     *  указатель (см. [onPlayerDone]). Место ставим на доигравшую единицу, а
     *  следующий шаг чтения уйдёт уже за неё. */
    private fun catchUpTo(pos: Long) {
        if (pos < 0) return
        val ch = (pos / 1_000_000L).toInt()
        val s = (pos % 1_000_000L).toInt()
        if (book?.chapters?.getOrNull(ch)?.sentences?.getOrNull(s) == null) return
        chapterIdx = ch
        sentenceIdx = s
        fragmentPos = pos
        // 23.10.2026: длину той фразы считаем ТЕМ ЖЕ правилом, каким её читали:
        // если это была укороченная первая фраза после перехода — одно предложение
        // ([shortPhrasePos]), а не склейка. Иначе [advanceUnits] уезжал на 2-4
        // предложения, и часть книги пропускалась. Разбор неверной длины фразы —
        // docs/HISTORY.md, 07.10.2026 (журнал 00:35).
        spokenUnits = spanAt(ch, s, first = pos == shortPhrasePos).coerceAtLeast(1)
        pendingFragments = emptyList()
        host?.onShowCurrent()
    }

    // ——— «Книга прочитана» (23.09.2026, просьба Сергея) ———

    /** Книга дочитана до конца: через [END_ANNOUNCE_DELAY_MS] скажем об этом
     *  голосом чтения. Говорим НАШИМ синтезатором, а не диктором: чтение живёт
     *  и с закрытым окном (host == null), и голос должен быть тот же, что читал
     *  книгу, — иначе фраза выпадает из чтения. */
    private var endAnnouncePending = false
    private var endAnnounceSpeaking = false
    private val endAnnounceRunnable = Runnable { speakBookEnd() }

    /** Секунда — просьба Сергея: чтобы фраза не наезжала на хвост последнего
     *  предложения и было слышно, что чтение действительно кончилось. */
    private const val END_ANNOUNCE_DELAY_MS = 1000L

    /** Позиция — последнее предложение последней главы с текстом. Хвостовые
     *  главы без предложений (одна разметка) за конец книги не считаем: иначе
     *  фраза звучала бы на середине. */
    private fun atBookEnd(): Boolean {
        val bk = book ?: return false
        val ch = chapterIdx
        if (ch < 0 || ch >= bk.chapters.size) return false
        val cur = bk.chapters[ch].sentences
        if (cur.isEmpty() || sentenceIdx < cur.size - 1) return false
        for (i in ch + 1 until bk.chapters.size) {
            if (bk.chapters[i].sentences.isNotEmpty()) return false
        }
        return true
    }

    private fun scheduleBookEnd() {
        // Сначала полка: дочитанная книга помечается прочитанной (просьба
        // Сергея 23.09.2026). Делаем это ДО фразы и до проверки «уже сказано»:
        // фразу читатель может отменить, а книга всё равно дочитана. Возврат на
        // полку обновит строку сам (refreshRowsInPlace, msg4356) — в строке
        // появится «дочитана» и сто процентов.
        markBookFinished()
        // 07.10.2026 (просьба Сержа): дочитанную книгу предлагаем удалить —
        // «книга прочитана, удалить вместе с файлом или нет». Вопрос задаём
        // ПОСЛЕ фразы «книга прочитана», иначе диктор и голос чтения говорили бы
        // разом. Здесь только вооружаем: показывать будет окно, когда оно на
        // экране (см. [endAskUri] и [Host.onBookEndAsk]).
        armEndAsk()
        if (endAnnouncePending || endAnnounceSpeaking) return
        endAnnouncePending = true
        Diag.log(
            ctx, "activity",
            "книга дочитана до конца — через секунду скажу «${ctx.getString(R.string.book_finished)}»",
        )
        main.postDelayed(endAnnounceRunnable, END_ANNOUNCE_DELAY_MS)
    }

    /** Полка: книга дочитана — статус «прочитана». Пишем в реестр, откуда полка
     *  берёт статус и процент; position и prefs тут ни при чём. */
    private fun markBookFinished() {
        val u = currentUri ?: return
        val ex = BookStore.byUri(ctx, u) ?: return
        if (ex.status == BookRecord.STATUS_FINISHED) return
        BookStore.upsert(ctx, ex.copy(status = BookRecord.STATUS_FINISHED, readPct = 100))
        Diag.log(ctx, "activity", "книга дочитана — отметил прочитанной в реестре книг")
    }

    private fun speakBookEnd() {
        endAnnouncePending = false
        val p = player
        if (p == null || !p.isReady) {
            Diag.log(ctx, "activity", "«книга прочитана»: движок не готов — молчу")
            askDeleteAtEnd()
            return
        }
        // Чтение к этому моменту уже отпустило фокус и процессор (stopAtEnd),
        // поэтому берём их на время фразы — и отпускаем, когда она доиграет.
        requestAudioFocus()
        KeepAwake.acquire(ctx)
        endAnnounceSpeaking = true
        Diag.log(ctx, "activity", "говорю «${ctx.getString(R.string.book_finished)}»")
        p.speak(ctx.getString(R.string.book_finished))
    }

    private fun finishEndAnnounce() {
        endAnnounceSpeaking = false
        KeepAwake.release()
        dropAudioFocus()
        // Фраза отзвучала целиком — только теперь спрашиваем про удаление: два
        // голоса разом (наш и диктор, читающий окно) сливаются в кашу.
        askDeleteAtEnd()
    }

    // ——— «Книга дочитана — удалить?» (07.10.2026, просьба Сержа) ———

    /** Адрес книги, о которой ждём ответа. Задаём вопрос только у книги с полки:
     *  у той, что открыта ссылкой из чужого проводника, удалять нечего.
     *
     *  Показываем вопрос в живом окне читалки: с погашенным экраном его читать
     *  некому, поэтому он ЖДЁТ возвращения окна (см. [Host.onBookEndAsk]), а не
     *  пропадает. Любой ответ снимает вопрос ([endAskDone]). */
    private var endAskUri: String? = null

    /** Галочка «спрашивать про удаление дочитанной книги» (07.10.2026, просьба
     *  Сержа: «надо, чтобы этот диалог включать и выключать»). Живёт в
     *  Настройках → «Библиотека», читается КАЖДЫЙ раз на конце книги: выключил —
     *  следующий конец книги уже молчит, перезапускать ничего не надо. */
    const val KEY_ASK_DELETE_END = "ask_delete_finished"
    const val ASK_DELETE_END_DEFAULT = true

    private fun armEndAsk() {
        if (!prefs.getBoolean(KEY_ASK_DELETE_END, ASK_DELETE_END_DEFAULT)) return
        val u = currentUri ?: return
        if (BookStore.byUri(ctx, u) == null) return
        endAskUri = u
        Diag.log(ctx, "activity", "книга дочитана — жду ответа: удалить её или оставить")
    }

    private fun askDeleteAtEnd() {
        if (endAskUri == null) return
        // Окна нет (чтение без окна) — вопрос останется ждать: следующее открытие
        // читалки спросит само (MainActivity.maybeShowEndAsk).
        host?.onBookEndAsk()
    }

    /** Книга, о которой надо спросить (null — вопроса нет). Заодно снимаем
     *  вопрос, если за это время открыли другую книгу: спрашивать про прошлую
     *  поверх новой — только путать. */
    fun endAskRecord(): BookRecord? {
        val u = endAskUri ?: return null
        if (u != currentUri) {
            endAskUri = null
            return null
        }
        return BookStore.byUri(ctx, u)
    }

    /** Ответ получен (удалили или оставили) — вопроса больше нет. */
    fun endAskDone() {
        endAskUri = null
    }

    /** Отменить несказанное (или оборвать звучащее): читатель снова взялся за
     *  книгу — нажал «читать», перешёл по главе, закрыл читалку, поставил паузу. */
    private fun cancelEndAnnounce() {
        // Читатель снова взялся за книгу — вопрос «книга прочитана, удалить?»
        // тоже снимаем: книгу он явно оставляет себе.
        endAskUri = null
        if (!endAnnouncePending && !endAnnounceSpeaking) return
        endAnnouncePending = false
        main.removeCallbacks(endAnnounceRunnable)
        if (endAnnounceSpeaking) {
            endAnnounceSpeaking = false
            player?.stop()
            KeepAwake.release()
            dropAudioFocus()
            Diag.log(ctx, "activity", "«книга прочитана» прервана читателем")
        }
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

    /** Чтение дошло до конца книги (или было одноразовым) — отпускаем фокус.
     *
     *  19.10.2026 (жалоба тестера: «выбрали голос — книга читается сама, а кнопка
     *  стоит „Начать чтение“», и рядом «экран включается, чтение продолжается,
     *  хотя в настройках стоит остановка»). Правило: ОСТАНОВКА ЧТЕНИЯ — ЭТО
     *  ТИШИНА, а не только признак `playing = false`. Раньше здесь никто не гасил
     *  плеер, и после пробы голоса (одна фраза) в списке ExoPlayer оставались
     *  стоять заказанные впрок файлы следующих фраз: они звучали дальше сами.
     *  Читалка считала чтение стоящим, пауза по экрану и по кнопке ничего не
     *  делали (`pausePlayback` выходит по `!playing`), а книга читалась «другим
     *  потоком». Теперь плеер гасим здесь, одной строкой на все выходы. */
    private fun stopAtEnd() {
        Diag.log(ctx, "activity", "чтение закончилось само (конец/остановка)")
        // Сначала звук: тишина должна наступить в тот же миг, а не после
        // сохранения позиции. Если что-то ещё звучало или стояло в очереди, это
        // видно в журнале — по такой строке жалоба разбирается без догадок.
        player?.let { sp ->
            if (sp.pendingSound) {
                Diag.log(ctx, "sound", "остановка чтения: звук ещё оставался — гашу плеер и очередь")
            }
            sp.stop()
        }
        // Подсказка гарнитуры оборвана остановкой — её «доиграла» уже не будет
        // (см. pausePlayback); признак не должен пережить чтение.
        hintSpeaking = false
        hintText = null
        // msg4993: глава была дочитана ПО ТАЙМЕРУ — режим надо запомнить до
        // cancelSleepTimer(), который его гасит. Дальше либо окно ожидания
        // жеста (галочка включена), либо тихая пауза, как в 0.4.25.
        val chapterSleep = sleepMode == SLEEP_CHAPTER
        cancelSleepTimer() // таймер сна дальше не нужен — дочитали или встали на границе
        playing = false
        KeepAwake.release() // #19: чтение кончилось — процессор отпускаем
        dropAudioFocus()
        // msg1119: дочитал до конца — фиксируем, чтобы повторно не начать с начала.
        savePosition()
        pushPlayState()
        if (chapterSleep) armChapterWait()
    }

    /** Глава дочитана по таймеру: даём минуту на жест, как и в конце таймера по
     *  времени. Чтение уже на паузе — жест его будит ([onSleepGesture]). */
    private fun armChapterWait() {
        if (!sleepWindowEnabled()) {
            Diag.log(ctx, "sleep", "глава дочитана по таймеру — ожидание выключено")
            return
        }
        chapterWait = true
        Diag.log(ctx, "sleep", "глава дочитана по таймеру — минута на жест")
        main.postDelayed(sleepRunnable, SLEEP_WINDOW_MS)
        enterSleepWindow(test = false)
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
            // обычным путём перейдёт в следующую главу. Режим таймера здесь НЕ
            // сбрасываем: по нему [stopAtEnd] решает, открыть ли окно ожидания
            // жеста (msg4993).
            if (sleepMode == SLEEP_CHAPTER) return false
            chapterIdx++
            sentenceIdx = 0
            host?.onChapterLoaded()
            pushCardLine()
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
     *  пользовательская пауза (фокус держим), byFocusLoss — прервал чужой плеер,
     *  byFaceDown — телефон перевернули экраном вниз ([FaceDownPause]). */
    fun pausePlayback(
        keepFocus: Boolean = false,
        byFocusLoss: Boolean = false,
        byFaceDown: Boolean = false,
    ) {
        // Служебная «книга прочитана» звучит при уже остановленном чтении
        // (playing = false), поэтому её гасим ДО раннего выхода: «пауза» значит
        // тишина, а не «фраза доскажет себя и умолкнет».
        cancelEndAnnounce()
        // Подсказка гарнитуры (проба 256): пауза её обрывает вместе со звуком, а
        // «доиграла» от оборванной фразы уже не придёт. Признак снимаем ОБЯЗАТЕЛЬНО,
        // иначе следующая настоящая фраза, доиграв, будет принята за конец подсказки
        // и чтение перечитает её с начала (на слух — тот самый повтор).
        if (hintSpeaking) {
            hintSpeaking = false
            hintText = null
            Diag.log(ctx, "session", "пауза прервала подсказку гарнитуры — признак снят")
        }
        if (!playing) return
        Diag.log(
            ctx, "activity",
            "пауза: " + when {
                byFocusLoss -> "прерван чужим плеером"
                byFaceDown -> "телефон перевёрнут экраном вниз"
                else -> "по команде пользователя"
            } + ", keepFocus=$keepFocus"
        )
        playing = false
        // 24.09.2026: пауза сохраняет звук звучащей фразы — продолжение
        // зазвучит сразу, без повторного синтеза (см. pauseKeepingPhrase).
        player?.pauseKeepingPhrase()
        // Признаки «чья это пауза» ставим ДО KeepAwake.release: он зовёт сторожей
        // (ScreenOnPause/FaceDownPause), а сторожу переворота надо знать, что
        // пауза наша, — иначе он снимется и переворота вверх уже не увидит.
        pausedByFocusLoss = byFocusLoss
        pausedByFaceDown = byFaceDown
        // #19: чтение встало — процессор больше не держим (в т.ч. на паузе из-за
        // звонка: дальше чтение возобновит [startSpeakingCurrent]).
        KeepAwake.release()
        // Пользовательская пауза/стоп снимает таймер сна: он ставился «уснуть
        // под чтение», а чтение уже прервали руками (msg2567). Прерывание чужим
        // плеером (звонок) и переворот экраном вниз — временные: таймер
        // продолжает тикать, иначе перевёрнутый телефон отменял бы его молча.
        if (!byFocusLoss && !byFaceDown) cancelSleepTimer()
        if (!keepFocus) dropAudioFocus()
        // msg1119: пауза — ключевой момент, позицию фиксируем сразу.
        savePosition()
        pushPlayState()
    }

    /** Движок перестал отвечать: чтение встало, но и вперёд не идёт.
     *
     *  05.10.2026, журнал 22:43 (жалоба Сержа: «книжка очень быстро побежала
     *  вперёд»). Заглючивший TeraTTS на КАЖДУЮ заявку отвечал «готово», файла не
     *  писал, а прямая речь отвечала отказом. Мы каждую такую фразу молча
     *  пропускали и шли к следующей: за 9 секунд чтение ушло с главы 6 на главу
     *  21 — тысячи фраз, 530 КБ журнала, ни звука и ни слова человеку. Теперь
     *  плеер считает такие фразы подряд (см. `SpeechPlayer.noteSilentPhrase`) и,
     *  если их подряд слишком много, зовёт нас с позицией, где звук кончился.
     *  Здесь мы возвращаем место чтения туда, останавливаемся и говорим. */
    /** Уже переключились на локальный голос из-за молчания (24.10.2026). Второй
     *  раз не переключаемся: если и локальный молчит, дело не в сети — тогда
     *  останавливаемся и говорим словами. Живёт до закрытия читалки. */
    private var autoVoiceSwitched = false

    /** Шаг лестницы «локальный голос того же движка» (24.10.2026).
     *
     *  Возвращает true, если переключились и чтение перезапущено: тогда
     *  останавливаться не надо. Молчим, только если менять не на что: голос уже
     *  локальный (значит дело не в сети), локальных голосов у движка нет или
     *  язык не совпадает — чужой язык читать нельзя.
     *
     *  ПАМЯТЬ: переключение запоминаем до закрытия читалки ([autoVoiceSwitched]).
     *  Выбор человека не переписываем — в настройках остаётся его голос, и после
     *  перезапуска приложения он снова главный (решение Сержа 24.10.2026).
     *
     *  СЛОВАМИ, А НЕ МОЛЧА: человек услышит не тот голос, что выбирал, и должен
     *  знать почему. */
    /** Объявили ли уже про «только по Wi-Fi» (24.10.2026). Один раз на читалку:
     *  иначе фраза звучала бы при каждом переходе. */
    private var wifiSwitchAnnounced = false

    /** 24.10.2026: «сетевые голоса только по Wi-Fi» (как у @Voice). Если правило
     *  включено, а сети Wi-Fi нет, сетевой голос не ставим: выбираем ЛОКАЛЬНЫЙ
     *  голос того же языка и говорим об этом словами. Локального нет — оставляем
     *  как выбрано и пишем в журнал: молча ломать выбор человека нельзя.
     *  Зовётся в тех местах, где голос применяется к чтению. */
    private fun voiceForNow(v: String?): String? {
        if (v == null) return null
        if (!prefs.getBoolean(MainActivity.KEY_NET_WIFI_ONLY, false)) return v
        val p = player ?: return v
        val list = runCatching { p.voices }.getOrNull().orEmpty()
        if (list.isEmpty()) return v
        val target = list.firstOrNull { it.name == v } ?: return v
        val net = runCatching { target.isNetworkConnectionRequired }.getOrDefault(false)
        if (!net) return v
        if (NetNow.onWifi(ctx)) return v
        val lang = runCatching { target.locale?.language }.getOrNull()
        val local = list.firstOrNull { cand ->
            runCatching { !cand.isNetworkConnectionRequired }.getOrDefault(false) &&
                (lang == null || runCatching { cand.locale?.language == lang }.getOrDefault(true))
        }
        if (local == null) {
            Diag.log(
                ctx, "voice",
                "только Wi-Fi: сети нет, а локального голоса на замену не нашлось — " +
                    "оставляю сетевой «$v»"
            )
            return v
        }
        Diag.log(
            ctx, "voice",
            "только Wi-Fi: сети нет — ставлю локальный голос «${local.name}» вместо «$v»"
        )
        if (!wifiSwitchAnnounced) {
            wifiSwitchAnnounced = true
            host?.onAnnounce("Сетевые голоса только по Wi-Fi. Читаю локальным голосом ${local.name}.")
        }
        return local.name
    }

    /** Шаг лестницы «голос другого движка» (24.10.2026, решение Сержа).
     *
     *  Возвращает true, если попытку ВЗЯЛИ и она идёт своим ходом: тогда чтение
     *  не останавливаем, а ждём ответа ([LocalVoiceFinder] поднимает чужие движки
     *  по очереди). Не нашли — останавливаемся и говорим, как раньше.
     *
     *  ПАМЯТЬ: попытку делаем один раз до закрытия читалки ([autoEngineSwitched]) —
     *  иначе на живом молчащем движке мы бы поднимали чужие по кругу.
     *  ВЫБОР ЧЕЛОВЕКА НЕ ПЕРЕПИСЫВАЕМ: в настройках остаётся его движок и голос. */
    private var autoEngineSwitched = false

    private fun switchToOtherEngineAfterSilence(): Boolean {
        if (autoEngineSwitched) return false
        val p = player ?: return false
        autoEngineSwitched = true
        val list = runCatching { p.voices }.getOrNull().orEmpty()
        val cur = list.firstOrNull { it.name == voiceName }
        val lang = runCatching { cur?.locale?.language }.getOrNull()
        val from = p.enginePackage
        Diag.log(
            ctx, "voice",
            "движок молчит: ищу локальный голос того же языка в другом движке " +
                "(нынешний $from, язык ${lang ?: "неизвестен"})"
        )
        LocalVoiceFinder.find(ctx, lang, from) { found ->
            if (found == null) {
                Diag.log(ctx, "voice", "другой движок не подошёл — останавливаю чтение")
                stopAfterSilentEngine()
                return@find
            }
            host?.onAnnounce(
                "Этот движок не отвечает. Пробую другой движок, голос ${found.voice}."
            )
            player?.setEngine(found.engine) { ok ->
                if (!ok) {
                    Diag.log(ctx, "voice", "другой движок ${found.engine} не поднялся — встаю")
                    stopAfterSilentEngine()
                    return@setEngine
                }
                voiceName = found.voice
                player?.selectVoice(found.voice)
                restartAfterSwitch(withPreview = false)
            }
        }
        return true
    }

    private fun switchToLocalVoiceAfterSilence(): Boolean {
        if (autoVoiceSwitched) return false
        val p = player ?: return false
        val list = runCatching { p.voices }.getOrNull().orEmpty()
        if (list.isEmpty()) return false
        val cur = list.firstOrNull { it.name == voiceName }
        val curNet = runCatching { cur?.isNetworkConnectionRequired == true }.getOrDefault(false)
        // Голос локальный (или неизвестный) — сеть тут ни при чём, менять нечего.
        if (cur != null && !curNet) return false
        val lang = cur?.locale?.language
        val local = list.firstOrNull { v ->
            v.name != voiceName &&
                runCatching { !v.isNetworkConnectionRequired }.getOrDefault(false) &&
                (lang == null || runCatching { v.locale?.language == lang }.getOrDefault(true))
        } ?: return false
        autoVoiceSwitched = true
        Diag.log(
            ctx, "voice",
            "движок замолчал: перехожу на локальный голос «${local.name}» " +
                "вместо «$voiceName» (сетевой не отвечает)"
        )
        voiceName = local.name
        p.selectVoice(local.name)
        host?.onAnnounce("Сетевой голос не отвечает. Читаю локальным голосом ${local.name}.")
        // Проба тут не нужна: чтение идёт, [restartAfterSwitch] сам перечитает
        // текущее предложение новым голосом (и выбрасывает звук прежнего).
        restartAfterSwitch(withPreview = false)
        return true
    }

    /** Остановка после того, как лестница отказов кончилась (24.10.2026).
     *  Прежний код onEngineSilent целиком: место возвращаем, чтение гасим и
     *  говорим словами. Зовётся и напрямую, и из шага «другой движок», когда
     *  замены не нашлось. */
    private fun stopAfterSilentEngine() {
        val bk = book
        val wasChapter = chapterIdx
        Diag.log(
            ctx, "activity",
            "движок не отвечает: чтение остановлено на главе ${chapterIdx + 1}, " +
                "предл. $sentenceIdx (место звука), playing=$playing"
        )
        cancelEndAnnounce()
        playing = false
        player?.stop()
        KeepAwake.release()
        cancelSleepTimer()
        dropAudioFocus()
        savePosition()
        pushPlayState()
        if (bk != null && chapterIdx != wasChapter) host?.onChapterLoaded() else host?.onShowCurrent()
        // СЛОВАМИ, А НЕ МОЛЧА (16.10.2026, сборка 264x): причина по [TtsHealth].
        host?.onAnnounce(TtsHealth.stopText(ctx))
    }

    private fun onEngineSilent(pos: Long) {
        val bk = book
        val wasChapter = chapterIdx
        if (bk != null && pos > 0L) {
            val ch = (pos / 1_000_000L).toInt().coerceIn(0, bk.chapters.lastIndex)
            val cur = bk.chapters[ch].sentences
            chapterIdx = ch
            sentenceIdx = (pos % 1_000_000L).toInt().coerceIn(0, cur.lastIndex.coerceAtLeast(0))
        }
        // 24.10.2026: ЛЕСТНИЦА ОТКАЗОВ (решение Сержа: «повтор → локальный голос
        // того же движка → голос другого движка», каждый шаг объявляем словами).
        // Первый шаг уже сделал плеер (перезапуск и перечитывание фразы), теперь
        // пробуем локальный голос: сетевой мог просто не получить сеть, а
        // локальный в том же движке молчит разве что вместе с движком.
        if (switchToLocalVoiceAfterSilence()) return
        if (switchToOtherEngineAfterSilence()) return
        stopAfterSilentEngine()
    }

    /** Сообщить UI и системе новое состояние play/pause. Зовётся на каждой фразе
     *  (см. startSpeakingCurrent), поэтому здесь же обновляем и место в книге —
     *  сравнение с прежней строкой внутри не даёт лишних публикаций. */
    private fun pushPlayState() {
        host?.onPlayStateChanged()
        MediaSessionService.setPlaying(playing)
        pushCardLine()
    }

    // ---------------- Вторая строка медиа-карточки (msg4725) ----------------

    /** Место в книге для карточки шторки — как у чужой читалки: «Глава 6 из 20 ·
     *  ПЛЕН». Название главы у нас может быть пустым (безымянные главы) — тогда
     *  только номер. По предложениям («K из S») карточку НАМЕРЕННО не дробим:
     *  строка менялась бы каждые несколько секунд, а TalkBack объявляет
     *  обновления карточки, которая у него в фокусе — слушать это невозможно. */
    private var cardLine: String? = null

    /** Отдать строку в сервис, если она изменилась. */
    private fun pushCardLine() {
        val line = cardLineText()
        if (line == cardLine) return
        cardLine = line
        MediaSessionService.setCardLine(line)
    }

    /** Сколько прочитано, в процентах, по месту среди предложений книги
     *  (24.10.2026). Книга тут целиком, поэтому считаем сами: у карточки в шторке
     *  нет ни полоски прогресса, ни длительности звука в голосовом чтении.
     *  -1 — считать не по чему (книги нет или в ней одно предложение). */
    private fun readPercentNow(): Int {
        val bk = book ?: return -1
        var before = 0
        var total = 0
        for ((i, ch) in bk.chapters.withIndex()) {
            val n = ch.sentences.size
            if (i < chapterIdx) before += n
            total += n
        }
        if (total <= 1) return -1
        val g = (before + sentenceIdx).coerceIn(0, total - 1)
        return g * 100 / (total - 1)
    }

    private fun cardLineText(): String? {
        val bk = book
        return if (bk == null) {
            null
        } else {
            val size = bk.chapters.size
            // 24.10.2026: в голосовом чтении у звука нет длины, и полоска прогресса
            // в карточке шторки пустая. Поэтому место называем СЛОВАМИ и числом —
            // «Глава N из M · 42%» (решение Сержа: «карточка показывает главу и
            // процент»). Процент считаем по предложениям книги — той же линейкой,
            // что полка и слайдер.
            val pct = readPercentNow()
            val base = if (size > 0) {
                val head = "Глава ${chapterIdx + 1} из $size"
                if (pct >= 0) "$head · $pct%" else head
            } else {
                ""
            }
            val title = bk.chapters.getOrNull(chapterIdx)?.title?.trim().orEmpty()
            // Длинный заголовок в шторке не поместится и на слух тянется долго.
            val short = if (title.length > CARD_TITLE_LIMIT) {
                title.take(CARD_TITLE_LIMIT).trimEnd() + "…"
            } else {
                title
            }
            when {
                short.isEmpty() -> base
                base.isEmpty() -> short
                else -> "$base · $short"
            }.ifEmpty { null }
        }
    }

    // ---------------- Таймер сна (msg2567 → переделан в msg4993) ----------------
    //
    // Как это устроено для человека. Таймер подходит к концу — BookVoice подаёт
    // сигнал (звук + вибрация) и ЖДЁТ минуту. Тряхнул телефон или положил его
    // экраном вниз — чтение продолжилось ещё на N минут. Не тронул — чтение
    // встаёт на паузу, как раньше. Минута ожидания и есть «окно».

    /** Поставить таймер «уснуть через [minutes] минут». Прежний режим снимается.
     *  [byGesture] — продление жестом: тогда [sleepBaseMinutes] (выставленное
     *  человеком число) не трогаем, иначе «продлевать столько же» съезжало бы. */
    fun setSleepTimerMinutes(minutes: Int, byGesture: Boolean = false) {
        removeSleepRunnable()
        if (minutes <= 0) {
            sleepMode = SLEEP_OFF
            sleepMinutes = 0
            return
        }
        sleepMode = SLEEP_MINUTES
        sleepMinutes = minutes
        if (!byGesture) sleepBaseMinutes = minutes
        val ms = minutes * 60_000L
        sleepEndAt = SystemClock.elapsedRealtime() + ms
        main.postDelayed(sleepRunnable, ms)
        // Сигнал за минуту до конца. Если окно ожидания выключено и сигнал тоже,
        // не будим человека зря — таймер просто тихо встанет на паузу, как раньше.
        if (minutes > 1 && (sleepWindowEnabled() || SleepTimerPrefs.signal(ctx))) {
            main.postDelayed(sleepWarnRunnable, ms - SLEEP_WINDOW_MS)
        }
        Diag.log(ctx, "sleep", "таймер сна: через $minutes минут")
    }

    /** Поставить таймер «уснуть, когда закончится глава»: дочитываем текущую
     *  главу и встаём на паузу на её последнем предложении (см. [advanceOneUnit]).
     *  Окно ожидания при этом открывает [stopAtEnd] — глава уже дочитана. */
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
        chapterWait = false
        endSleepWindow()
        stopSleepTest()
    }

    private fun removeSleepRunnable() {
        sleepEndAt = 0L
        main.removeCallbacks(sleepRunnable)
        main.removeCallbacks(sleepWarnRunnable)
    }

    /** Включено ли ожидание жеста вообще: либо встряска, либо переворот. */
    private fun sleepWindowEnabled(): Boolean =
        SleepTimerPrefs.wait(ctx) || SleepTimerPrefs.flip(ctx)

    /** Сколько минут осталось до остановки — для строки «Выключить таймер».
     *  В режиме «до конца главы» остатка в минутах нет. */
    fun sleepRemainingMinutes(): Int {
        if (sleepMode != SLEEP_MINUTES || sleepEndAt == 0L) return 0
        val left = sleepEndAt - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 59_999) / 60_000).toInt()
    }

    /** Последняя минута перед остановкой: сигнал, сенсор, объявление — и ждём.
     *  [test] — проверка из окна настроек: книгу не останавливаем и не глохнем,
     *  только рассказываем словами, что жест поймали (или что не поймали). */
    private fun enterSleepWindow(test: Boolean) {
        if (sleepWaiting) return
        sleepWaiting = true
        val signal = test || SleepTimerPrefs.signal(ctx)
        if (signal) {
            SoundFx.sleepWarning(ctx)
            Vibra.timerSignal(ctx, SleepTimerPrefs.vibraLevel(ctx))
        }
        startSense(test)
        if (test) {
            // Проверку слушают словами всегда: ради этого её и запускают.
            reportSleepTest("Проверка: " + sleepGesturePhrase(test = true) + " Десять секунд.")
        } else if (SleepTimerPrefs.voice(ctx)) {
            host?.onAnnounce(
                (if (chapterWait) "Глава дочитана. " else "Таймер: последняя минута. ") +
                    sleepGesturePhrase()
            )
        }
        Diag.log(
            ctx, "sleep",
            if (test) "проверка: окно ожидания открыто"
            else "таймер: последняя минута, жду жест"
        )
    }

    /** Что именно предложить человеку — по включённым галочкам. Оба жеста
     *  независимы, поэтому фраза собирается из того, что реально слушаем. В
     *  проверке ([test]) слушаются оба, и звать надо тоже оба. */
    private fun sleepGesturePhrase(test: Boolean = false): String {
        val shake = test || SleepTimerPrefs.wait(ctx)
        val flip = test || SleepTimerPrefs.flip(ctx)
        return when {
            shake && flip -> "Тряхните телефон или положите его экраном вниз."
            shake -> "Тряхните телефон."
            flip -> "Положите телефон экраном вниз."
            else -> ""
        }
    }

    /** Включить сенсор на время окна. В проверке слушаем ОБА жеста, независимо
     *  от галочек: иначе «Проверить переворот» проверяла бы выключенный жест. */
    private fun startSense(test: Boolean) {
        val s = sleepSense()
        if (!s.available) {
            Diag.log(ctx, "sleep", "акселерометра нет — жест недоступен")
            return
        }
        s.shakeEnabled = test || SleepTimerPrefs.wait(ctx)
        s.flipEnabled = test || SleepTimerPrefs.flip(ctx)
        s.sense = SleepTimerPrefs.sense(ctx)
        s.onGesture = {
            onSleepGesture(if (it == SleepSense.Gesture.FLIP) "переворот" else "встряска")
        }
        s.start()
    }

    /** Слушатель сенсора живёт в движке, а не в окне: окно ожидания идёт и при
     *  погашенном экране, и «без окна» (чтение из шторки). Держим один экземпляр
     *  и переиспользуем — регистрация слушателя дешёвая, а сборка мусора на
     *  ночном чтении ни к чему. */
    private fun sleepSense(): SleepSense = sense ?: SleepSense(ctx).also { sense = it }

    /** Закрыть окно ожидания: сенсор выключаем, признак ожидания снимаем. */
    private fun endSleepWindow() {
        sleepWaiting = false
        sense?.let {
            it.onGesture = null
            it.stop()
        }
    }

    /** Жест пойман — продлеваем чтение (или, в проверке, просто рассказываем). */
    private fun onSleepGesture(why: String) {
        val wasTest = testMode
        if (wasTest) {
            // Гасим и окно, и конец проверки разом: иначе через десять секунд
            // прилетело бы «жеста не было» уже после пойманного жеста.
            stopSleepTest()
        } else {
            endSleepWindow()
            main.removeCallbacks(sleepRunnable)
            main.removeCallbacks(sleepWarnRunnable)
        }
        Vibra.confirm(ctx)
        if (wasTest) {
            reportSleepTest("Проверка: $why поймана. Таймер продлил бы чтение.")
            return
        }
        val ext = SleepTimerPrefs.extendMinutes(ctx)
        val afterChapter = chapterWait
        chapterWait = false
        // «Столько же» — продлить тем, чем был выставлен таймер (msg5009). Для
        // режима «до конца главы» это значит дочитать СЛЕДУЮЩУЮ главу и снова
        // встать на её последнем предложении: числа минут у него нет, а
        // «столько же» для него — ровно та же остановка на конце главы.
        if (ext == SleepTimerPrefs.EXTEND_SAME && afterChapter) {
            Diag.log(ctx, "sleep", "жест «$why» — продлеваю до конца следующей главы")
            setSleepTimerChapterEnd()
            requestStart()
            if (SleepTimerPrefs.voice(ctx)) host?.onAnnounce("Продлил до конца главы")
            return
        }
        val minutes = if (ext == SleepTimerPrefs.EXTEND_SAME) {
            if (sleepBaseMinutes > 0) sleepBaseMinutes else SleepTimerPrefs.DEF_EXTEND
        } else {
            ext
        }
        Diag.log(ctx, "sleep", "жест «$why» — продлеваю на $minutes минут")
        // Ставим новый таймер: прежний уже не нужен, а окно ожидания откроется
        // заново за минуту до нового конца.
        setSleepTimerMinutes(minutes, byGesture = true)
        if (afterChapter) {
            // Глава была дочитана по таймеру и чтение стояло на паузе — будим.
            requestStart()
        }
        if (SleepTimerPrefs.voice(ctx)) host?.onAnnounce("Продлил на $minutes минут")
    }

    // ---------------- Проверка таймера без ожидания (msg4987) ----------------

    /** Полная проверка за полминуты: читаем 20 секунд, затем сигнал и 10 секунд
     *  окна ожидания. Книгу НЕ останавливаем — иначе проверка каждые пять минут
     *  сбивала бы место чтения. */
    fun startSleepTimerTest() {
        if (!playing) {
            reportSleepTest("Проверка таймера: сначала включите чтение.")
            return
        }
        val token = startSleepTest("проверка таймера: начал")
        reportSleepTest("Проверка таймера: читаю двадцать секунд, потом подам сигнал.")
        main.postDelayed({
            if (testMode && testToken == token) enterSleepWindow(test = true)
        }, TEST_READ_MS)
        val end = Runnable {
            if (testMode && testToken == token) finishSleepTest()
        }
        testEndRunnable = end
        main.postDelayed(end, TEST_READ_MS + TEST_WINDOW_MS)
    }

    /** Проверка одного жеста, без чтения и без сигнала: десять секунд слушаем оба
     *  жеста и говорим словами, поймали или нет. Нужна для подбора ступени
     *  чувствительности — гонять ради этого полминуты с ожиданием незачем. */
    fun startSleepSenseTest() {
        val token = startSleepTest("проверка жеста: начал")
        reportSleepTest("Проверка жеста: " + sleepGesturePhrase(test = true) + " Десять секунд.")
        startSense(test = true)
        val end = Runnable {
            if (testMode && testToken == token) {
                stopSleepTest()
                Vibra.error(ctx)
                reportSleepTest("Проверка: жеста не было — поднимите ступень чувствительности.")
            }
        }
        testEndRunnable = end
        main.postDelayed(end, TEST_WINDOW_MS)
    }

    /** Начать проверку: прежняя (если шла) снимается, метка [testToken] делает
     *  отложенные шаги прежней проверки недействительными — иначе проверки
     *  наложились бы друг на друга и сказали не то. */
    private fun startSleepTest(logLine: String): Int {
        stopSleepTest()
        testMode = true
        testToken++
        Diag.log(ctx, "sleep", logLine)
        return testToken
    }

    /** Проверку довели до конца, жеста не было — это тоже результат: говорим,
     *  что таймер поставил бы чтение на паузу. Двойной толчок «не получилось»,
     *  чтобы отличать на ощупь от пойманного жеста. */
    private fun finishSleepTest() {
        stopSleepTest()
        Vibra.error(ctx)
        reportSleepTest("Проверка: жеста не было — таймер поставил бы чтение на паузу.")
    }

    /** Снять проверку (окно настроек закрылось, началась другая проверка, таймер
     *  снят). Публично — окну: уйти из окна, не оставив проверку идти своим ходом. */
    fun stopSleepTest() {
        testMode = false
        testToken++
        testEndRunnable?.let { main.removeCallbacks(it) }
        testEndRunnable = null
        endSleepWindow()
    }

    /** Результат проверки — словами и в строку окна настроек.
     *
     *  Говорит ИМЕННО ОКНО, когда оно открыто, а не читалка: окно настроек
     *  сейчас активно, окно книги под ним система может и не озвучить —
     *  announceForAccessibility у невидимого окна пропадает. Слушателя нет
     *  (проверка без окна) — зовём читалку, другого канала нет. */
    private fun reportSleepTest(text: String) {
        Diag.log(ctx, "sleep", "проверка: $text")
        val listener = sleepTestListener
        if (listener != null) listener.invoke(text) else host?.onAnnounce(text)
    }

    /** Отдельные короткие проверки (сигнал, вибрация) из окна настроек — им
     *  движок не нужен, но пусть будут в одном месте с полной проверкой. */
    fun sleepTestSignal() {
        SoundFx.sleepWarning(ctx)
    }

    fun sleepTestVibra() {
        Vibra.timerSignal(ctx, SleepTimerPrefs.vibraLevel(ctx))
    }

    private fun fireSleepTimer() {
        sleepEndAt = 0L
        val byTime = sleepMode == SLEEP_MINUTES
        val wasChapterWait = chapterWait
        sleepMode = SLEEP_OFF
        sleepMinutes = 0
        chapterWait = false
        main.removeCallbacks(sleepWarnRunnable)
        endSleepWindow()
        if (testMode) {
            // Страховка: проверка не имеет права глушить чтение.
            stopSleepTest()
            return
        }
        // Играет — ставим на паузу (сохранит место и отдаст фокус). Уже стоит на
        // паузе (например, прервали звонком или глава дочитана) — просто
        // сбрасываем режим.
        if (byTime && playing) {
            pausePlayback(keepFocus = false)
            Diag.log(ctx, "sleep", "таймер сна сработал — чтение на паузе")
        } else if (wasChapterWait) {
            Diag.log(ctx, "sleep", "ожидание после главы: жеста не было, остаётся пауза")
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
        val pct = readPercentAt(p)
        BookStore.upsert(ctx, ex.copy(
            chapter = p.chapter,
            sentence = p.sentence,
            readPct = pct,
            lastOpenedAt = System.currentTimeMillis(),
            // 24.10.2026: ЗАПИСЬ ИДЁТ ЗА МЕСТОМ, А НЕ ЗА ПРОШЛЫМ СТАТУСОМ.
            // Прежде «дочитанная» книга навсегда оставалась дочитанной: человек
            // начинал её заново, а полка продолжала писать «100% прочитано» и
            // ставила её в конец вкладки «Непрочитанные» (жалоба Сержа 24.10:
            // «в библиотеке 100%, а внутри книжки ноль»). Теперь — как только
            // место ушло от конца книги, статус снова «читается».
            status = when {
                ex.status == BookRecord.STATUS_NEW -> BookRecord.STATUS_READING
                ex.status == BookRecord.STATUS_FINISHED && pct < 99 -> BookRecord.STATUS_READING
                else -> ex.status
            },
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
        // Знаки реплик этой книги (30.09.2026): у книги может быть свой набор,
        // и разбор обязан узнать его до первой прочитанной фразы.
        ReplyMarks.apply(ctx, currentName)
        val now = System.currentTimeMillis()
        val existing = BookStore.byUri(ctx, u)
        prefs.edit().putString(MainActivity.KEY_URI, u).apply()
        // 24.10.2026: статус «дочитана» держится только на конце книги. Книгу,
        // которую открыли заново с начала, полка снова считает читаемой — иначе
        // она оставалась со 100% и уезжала в конец вкладки «Непрочитанные».
        val status = when (existing?.status) {
            BookRecord.STATUS_FINISHED ->
                if (readPercent() < 99) BookRecord.STATUS_READING else BookRecord.STATUS_FINISHED
            else -> BookRecord.STATUS_READING
        }
        BookStore.upsert(ctx, BookRecord(
            uri = u,
            name = existing?.name ?: currentName ?: "book",
            // 09.10.2026: название из метаданных берём, только если оно похоже на
            // название, и прежнее («(=^..^=)» у «Звонка для родителя») тоже не
            // возвращаем: иначе мусор жил бы в записи вечно.
            title = doc.title?.takeIf { BookRecord.usableTitle(it) }
                ?: existing?.title?.takeIf { BookRecord.usableTitle(it) },
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
                        p.selectVoice(voiceForNow(gVoice) ?: gVoice)
                        voiceName = voiceForNow(gVoice) ?: gVoice
                    }
                    host?.onSpeedUiRefresh()
                    prewarmCurrent() // движок поднялся — готовим первую фразу
                }
            }
        } else {
            if (gVoice != null && gVoice != voiceName) {
                p.selectVoice(voiceForNow(gVoice) ?: gVoice)
                voiceName = voiceForNow(gVoice) ?: gVoice
            }
            host?.onSpeedUiRefresh()
            prewarmCurrent() // голос применён — можно готовить первую фразу
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
                            p.selectVoice(voiceForNow(vce) ?: vce)
                            voiceName = voiceForNow(vce) ?: vce
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
                p.selectVoice(voiceForNow(vce) ?: vce)
                voiceName = voiceForNow(vce) ?: vce
            }
            announceIfBookVoiceMissing(vce)
            host?.onSpeedUiRefresh()
            prewarmCurrent() // голос книги применён — готовим первую фразу заранее
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
    private val callListener: Any =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 и новее: PhoneStateListener объявлен устаревшим, слушаем
            // через TelephonyCallback. Логика та же — состояние уходит в handleCallState.
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    main.post { handleCallState(state) }
                }
            }
        } else {
            // Android 11 и старше: только прежний способ.
            object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    main.post { handleCallState(state) }
                }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm.registerTelephonyCallback(
                ContextCompat.getMainExecutor(ctx),
                callListener as TelephonyCallback,
            )
        } else {
            @Suppress("DEPRECATION")
            tm.listen(callListener as PhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun unregisterPhoneListener() {
        if (!phoneListening) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tm?.unregisterTelephonyCallback(callListener as TelephonyCallback)
        } else {
            @Suppress("DEPRECATION")
            tm?.listen(callListener as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
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
        // Переход — это тоже «взялся за книгу»: несказанное «книга прочитана»
        // отменяем, иначе фраза догнала бы читателя уже в другом месте.
        cancelEndAnnounce()
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
        // 05.10.2026: после ручного перехода первую фразу читаем коротким куском
        // (см. [shortFirstPhrase]) — так звук приходит почти сразу.
        shortFirstPhrase = true
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
        // Публикуем состояние только если чтение и правда встало. При переходе
        // «на ходу» (wasPlaying) пауза внутренняя, на один миг: публиковать её
        // нельзя — иначе система останется считать нас на паузе, если
        // продолжение не дойдёт до своей публикации (см. [startSpeakingCurrent],
        // а также жалобу «волшебное касание перестало ставить паузу»).
        if (!wasPlaying) pushPlayState()
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
