package com.cuber.bookvoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cuber.bookvoice.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Плеер ридера — доступен экрану настроек через [active], чтобы голос и
     *  скорость менялись на живой книге сразу. */
    internal lateinit var player: SpeechPlayer

    private lateinit var layoutManager: LinearLayoutManager

    private var book: BookDocument? = null
    private var chapterIdx = 0
    private var sentenceIdx = 0
    private var playing = false
    private var continuous = false

    // Прогресс по всей книге — для слайдера перемотки и оценки времени чтения.
    // chapterStart[c] — номер первого предложения главы c в глобальной нумерации,
    // cumWords[i] — сколько слов в предложениях с номерами < i.
    private var chapterStart = intArrayOf()
    private var cumWords = longArrayOf()
    private var scrubbing = false
    private var voiceName: String? = null

    // #102: свой голос/скорость книги — зеркало полей BookRecord открытой книги.
    private var perBookEngine: String? = null
    private var perBookVoice: String? = null
    private var perBookSpeed: Float? = null
    private var voiceMissingAnnounced = false

    // #98: авто-продолжение после настоящего звонка.
    private var inCall = false
    private var wasReadingAtCallStart = false
    private var lastTransientPauseAt = 0L
    private var phoneListening = false
    private var phoneListener: PhoneStateListener? = null

    // #99: пауза при отключении наушников (ACTION_AUDIO_BECOMING_NOISY).
    private var noisyRegistered = false

    // #101: история явных переходов для «Вернуться на предыдущее место».
    private data class Place(val chapter: Int, val sentence: Int)
    private val backStack = java.util.ArrayDeque<Place>()

    // #105/#106: выделение фрагмента. selAnchor — начало (первое удержание);
    // второе удержание открывает окно действий. rangeEnd — конец куска при
    // «Прочитать выделенное»: чтение останавливается ровно на его границе.
    private var selAnchor: Place? = null
    private var rangeEnd: Place? = null

    // msg1137/1139: книга восстановилась на (restoredPlace); userMoved — была ли
    // после открытия ручная навигация. Отличаем настоящий выбор «начать с начала»
    // от тихого сброса позиции к (0,0) без действий читателя (слайдер/жест/гонка).
    private var restoredPlace: Place? = null
    private var userMoved = false
    // msg1137/1139/0.3.46: пока идёт открытие книги (весь синхронный хвост
    // openBook), никакого настоящего действия читателя быть не может — главный
    // поток занят. Любой goTo(0,0) в это окно — авто-сброс, игнорируем.
    private var openingWindow = false
    // msg1725: название книги попало в шапку ещё ДО разбора файла (из записи) —
    // заголовок уже объявлен при показе окна, дублирующая озвучка по готовности
    // не нужна.
    private var headerAnnouncedAtOpen = false
    // msg1736/1739: «BookVoice» при открытии — это имя окна (Activity label),
    // объявляемое в момент появления. Перенос фокуса на tvHeader Jieshuo роняет
    // (лог 0.3.77), инъекция текста в window-state через делегат события не
    // приходят (лог 0.3.78). Лечим источник: window title окна (setTitle) =
    // название книги, ставим ДО показа окна — при появлении объявится книга.

    private val handler = Handler(Looper.getMainLooper())

    // Состояние поиска по книге. Совпадения храним по всей книге (глава,
    // предложение), searchCurrent — номер текущего совпадения в этом списке.
    private var searchMatches: List<Pair<Int, Int>> = emptyList()
    private var searchCurrent = -1
    private var searchGen = 0

    private val adapter = SentenceAdapter(
        onSentenceClick = { pos -> onSentenceTapped(pos) },
        onNextChapterClick = { goTo(chapterIdx + 1, 0) },
        onSentenceLongClick = { pos -> onSentenceLongPressed(pos) },
    )

    private val prefs by lazy { getSharedPreferences("reader", MODE_PRIVATE) }

    /** Команды с гарнитуры/TalkBack, пришедшие из [MediaSessionService].
     *  Диагностические тосты и вибрация убраны (0.3.14): кнопки уже доходят
     *  до нас, озвучивать каждое нажатие не нужно. */
    private val mediaCommands = object : MediaSessionService.Listener {
        override fun onMediaPlay() {
            handler.post {
                if (book == null) return@post
                if (!playing) requestStart()
            }
        }

        override fun onMediaPause() {
            handler.post {
                if (playing) {
                    // Пользовательская пауза — фокус держим, чтобы следующий
                    // магик-тап снова попал к нам.
                    pausePlayback(keepFocus = true)
                }
            }
        }

        override fun onMediaSkip(delta: Int) {
            // Кнопки «дальше/назад» гарнитуры тоже делаем паузой/продолжением:
            // у разных наушников двойное нажатие шлёт play/pause ИЛИ next —
            // Сергею нужен именно стоп/продолжение, а не перемотка.
            handler.post {
                if (book == null) return@post
                if (playing) pausePlayback(keepFocus = true) else requestStart()
            }
        }
    }

    private var currentUri: String? = null
    private var currentName: String? = null

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var haveAudioFocus = false
    private var focusRetried = false
    private var audioFocusReq: android.media.AudioFocusRequest? = null

    /** Чтение было прервано тем, что заиграл ДРУГОЙ плеер (а не остановлено
     *  пользователем). Когда тот плеер замолчит и фокус вернётся к нам —
     *  читалка продолжит сама: так двойное касание «пауза → снова играть»
     *  работает, даже если кнопка физически ушла чужому приложению. */
    private var pausedByFocusLoss = false

    /** #99: наушники отключились (звук ушёл бы в динамик) — ставим чтение на
     *  паузу. Сами не продолжаем: пользователь сам решает, когда вернуться. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (playing) pausePlayback(keepFocus = true)
        }
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
            this, "focus",
            "событие фокуса: ${focusLabel(change)}, playing=$playing, pausedByFocusLoss=$pausedByFocusLoss"
        )
        when (change) {
            // Фокус забрали насовсем — заиграл другой плеер (например,
            // сработало «волшебное касание», а кнопка ушла YouTube/Nekogram).
            // Ставим на паузу, но наш запрос фокуса НЕ отзываем и запоминаем:
            // когда чужой плеер остановят, фокус вернётся к нам и чтение
            // продолжится само (см. AUDIOFOCUS_GAIN ниже). haveAudioFocus=false
            // помечаем честно: теперь фокус не наш, и повторное нажатие Play
            // должно сделать НАСТОЯЩИЙ перезапрос и отобрать фокус обратно.
            AudioManager.AUDIOFOCUS_LOSS -> handler.post {
                haveAudioFocus = false
                if (playing) pausePlayback(keepFocus = true, byFocusLoss = true)
            }

            // Короткая потеря (звонок, уведомление, TalkBack озвучил экран).
            // Паузу ставим, фокус держим, но «продолжение само» не включаем —
            // иначе читалка оживала бы после каждого слова TalkBack.
            // #98: запоминаем момент прерывания — если следом начнётся НАСТОЯЩИЙ
            // звонок (событие CALL_STATE может прийти чуть позже потери фокуса),
            // по его концу продолжим читать (см. handleCallState).
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> handler.post {
                if (playing) lastTransientPauseAt = SystemClock.elapsedRealtime()
                pausePlayback(keepFocus = true)
            }

            // Фокус вернулся к нам (чужой плеер остановили) — а мы были
            // прерваны им, а не пользователем: продолжаем чтение, если в
            // настройках разрешено автопродолжение после прерывания.
            AudioManager.AUDIOFOCUS_GAIN -> handler.post {
                haveAudioFocus = true
                if (pausedByFocusLoss && book != null && !playing &&
                    prefs.getBoolean(KEY_AUTO_RESUME, true)
                ) {
                    pausedByFocusLoss = false
                    Diag.log(this, "focus", "возврат фокуса — продолжаю чтение сам")
                    requestStart()
                }
            }

            // Можно говорить тише — читалку не трогаем.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.header(this)
        // Живой инстанс ридера: экран настроек достаёт через него плеер,
        // чтобы скорость/голос менялись сразу, без перезапуска книги.
        active = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        layoutManager = LinearLayoutManager(this)
        binding.sentenceList.layoutManager = layoutManager
        binding.sentenceList.adapter = adapter
        binding.sentenceList.setHasFixedSize(true)

        installSwipe()

        binding.btnVoice.setOnClickListener { showVoiceDialog() }
        binding.btnToc.setOnClickListener { showTocDialog() }
        // Поиск по книге: короткое нажатие — панель (поле, микрофон,
        // Найти/Назад/Далее/Закрыть); долгое — сразу голосовой ввод слова.
        binding.btnSearch.setOnClickListener { onSearchButtonTap() }
        binding.btnSearch.setOnLongClickListener {
            onSearchLongPress()
            true
        }
        binding.btnSearchGo.setOnClickListener { searchFromField() }
        binding.btnSearchMic.setOnClickListener { startVoiceSearch() }
        binding.btnSearchPrev.setOnClickListener { stepSearch(-1) }
        binding.btnSearchNext.setOnClickListener { stepSearch(+1) }
        binding.btnSearchClose.setOnClickListener { closeSearchPanel() }
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            searchFromField()
            true
        }
        // Закладка: короткое нажатие — список закладок книги (переход к месту,
        // удаление — там же, справа у каждой строки); долгое — сразу создать
        // закладку на текущем месте без диалога (если уже есть — подсказка).
        binding.btnBookmark.setOnClickListener { onBookmarkClick() }
        binding.btnBookmark.setOnLongClickListener {
            onBookmarkLongClick()
            true
        }
        // #101: «⋮ Ещё» — действия читалки (вернуться на прежнее место).
        binding.btnMore.setOnClickListener { showMoreDialog() }
        binding.btnPlayPause.setOnClickListener { togglePlay() }
        binding.btnPrevSentence.setOnClickListener { stepMove(-1) }
        binding.btnNextSentence.setOnClickListener { stepMove(+1) }
        binding.btnPrevChapter.setOnClickListener { moveByChapter(-1) }
        binding.btnNextChapter.setOnClickListener { moveByChapter(+1) }
        // Кнопки скорости (#58): шаг ±0.1, меняют темп на лету.
        binding.btnSpeedDown.setOnClickListener { nudgeSpeed(-0.1f) }
        binding.btnSpeedUp.setOnClickListener { nudgeSpeed(+0.1f) }
        binding.btnStatus.setOnClickListener { toggleStatus() }

        // Слайдер перемотки по всей книге: тянем — едем по предложениям,
        // отпустили — перепрыгиваем к выбранному месту.
        binding.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // Свайпы TalkBack меняют значение ползунка БЕЗ onStart/onStopTrackingTouch,
                // поэтому раньше позиция книги не применялась вовсе: бегунок уезжал,
                // а чтение стартовало со старого места. Прыгаем сразу, как только
                // значение на слайдере разошлось с текущей позицией. Реальный
                // пальцевой драг по-прежнему обрабатываем на отпускании (scrubbing).
                if (scrubbing) return
                if (progress != currentGlobal()) jumpToGlobal(progress, announce = false)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                scrubbing = true
                // Пока крутим слайдер, чтение не должно мешать — ставим паузу,
                // но аудиофокус держим, чтобы вернуться в чтение одной кнопкой.
                if (playing) pausePlayback(keepFocus = true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                scrubbing = false
                val p = sb?.progress ?: return
                jumpToGlobal(p)
            }
        })

        player = SpeechPlayer(this)
        player.onDone = { handler.post { if (playing) onUtteranceDone() } }
        // Пока играет предложение, плеер синтезирует следующее впрок (наш
        // собственный звук → система считает читалку плеером). peekNextText
        // ничего не меняет — только подсказывает, что будет дальше.
        player.onNeedNext = { peekNextText() }
        player.speed = prefs.getFloat(KEY_SPEED, 1f)
        player.pitch = prefs.getFloat(KEY_PITCH, 1f)
        player.volume = prefs.getFloat(KEY_VOLUME, 1f)
        // Чтение всегда непрерывное: кнопка «по одному предложению» убрана.
        continuous = true
        voiceName = prefs.getString(KEY_VOICE, null)

        // Медиа-сессия живёт в сервисе; команды с гарнитуры приходят сюда.
        MediaSessionService.listener = mediaCommands
        restoreEngineAndVoice()
        restoreSession()
        // Android 13+: медиа-уведомление в шторке требует разрешения.
        requestNotificationPermission()
    }

    /** На Android 13+ спрашиваем разрешение на уведомления (показывает
     *  медиа-карточку читалки в шторке). Один раз, молча, без повторных
     *  нытьев — если отклонит, управление всё равно работает через
     *  системные media-кнопки. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onStart() {
        super.onStart()
        // Возврат из экрана настроек: там могли поменять, какие элементы
        // читалки показывать, скорость и голос — применяем к живой книге.
        applyReaderUi()
        refreshSpeedValue()
        // Медиа-сессия живёт в сервисе. Карточка рождается только когда книга
        // реально начала читаться (startSpeakingCurrent) — msg1779: при просто
        // открытой, но молчащей книге её появление TalkBack озвучивает как
        // «мусор». При возврате в ридер добиваем сервис до актуального
        // состояния, только если мы играли (процесс мог быть пересоздан).
        MediaSessionService.listener = mediaCommands
        if (book != null && playing) ensureMediaService(true)
        // Голос «по умолчанию» трогаем, только если у книги нет своего голоса —
        // иначе затрём запомненный для книги (voiceName показывает активный).
        val own = currentBookRecord()?.voice
        if (own == null) voiceName = prefs.getString(KEY_VOICE, null)
        if (book != null) {
            player.speed = currentSpeed()
            player.pitch = prefs.getFloat(KEY_PITCH, 1f)
            player.volume = prefs.getFloat(KEY_VOLUME, 1f)
            updateStats() // возврат из настроек: скорость могла поменяться.
        }
        // #98/#99: слушатели живут, пока ридер на экране.
        registerNoisyReceiver()
        registerPhoneListener()
    }

    override fun onStop() {
        super.onStop()
        unregisterNoisyReceiver()
        unregisterPhoneListener()
    }

    // ---------------- Открытие и сохранение книги ----------------

    private fun restoreEngineAndVoice() {
        val savedEngine = prefs.getString(KEY_ENGINE, null)
        val defaultEngine = player.defaultEngine
        if (savedEngine != null && savedEngine != defaultEngine) {
            player.setEngine(savedEngine) { ok ->
                handler.post {
                    if (ok) {
                        voiceName?.let { player.selectVoice(it) }
                    } else {
                        prefs.edit().remove(KEY_ENGINE).apply()
                        player.setEngine(null) { _ -> voiceName?.let { player.selectVoice(it) } }
                    }
                }
            }
        } else {
            voiceName?.let { player.selectVoice(it) }
        }
    }

    // msg1739: момент появления окна читалки и какой window title в этот момент —
    // чтобы понять, успевает ли setTitle до объявления окна скринридером.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Diag.log(this, "a11y", "окно читалки в фокусе; window title = «${title}»")
    }

    private fun restoreSession() {
        val u = intent.getStringExtra(EXTRA_URI) ?: prefs.getString(KEY_URI, null)
        if (u == null) {
            showEmpty()
            return
        }
        currentUri = u
        currentName = queryDisplayName(Uri.parse(u))
        // msg1245/1246: открытие книги всегда стартует с места из её записи (или
        // из prefs), а не с того, что передал интентом экран-открыватель. Страница
        // каталога держит запись на момент ПЕРВОГО рендера (0/0), пересоздание
        // задачи — старый интент: пока читали, запись ушла вперёд, а повторный
        // вход нёс старое 0/0 и прыгал в начало. Из библиотеки запоминалось —
        // там список перечитывается при каждом возврате. Явный переход в место
        // (цитата из QuotesActivity: EXTRA_EXPLICIT_PLACE) открывает где указано.
        val explicit = intent.getBooleanExtra(EXTRA_EXPLICIT_PLACE, false)
        val rec = if (explicit) null else BookStore.byUri(this, u)
        val ch: Int
        val s: Int
        if (explicit) {
            ch = intent.getIntExtra(EXTRA_CHAPTER, 0)
            s = intent.getIntExtra(EXTRA_SENTENCE, 0)
        } else if (rec != null) {
            ch = rec.chapter
            s = rec.sentence
        } else {
            ch = prefs.getInt(KEY_CHAPTER, 0)
            s = prefs.getInt(KEY_SENTENCE, 0)
        }
        openBook(Uri.parse(u), ch, s)
        // msg1725/1721/1736: заголовок книги — в шапке с ПЕРВОГО кадра. Пока файл
        // разбирается, у окна нет своего имени, и при показе скринридер объявляет
        // имя приложения («BookVoice»); поздний фокус (после разбора) не перебивает,
        // а при открытии перенос фокуса Jieshuo и вовсе роняет (лог 0.3.77).
        // У FBReader верх — панель с названием книги, оно читается при входе.
        // Если название известно сразу (книга из записи) — ставим его в tvHeader
        // до показа окна и делаем именем окна (announceWindowTitle → текст события
        // TYPE_WINDOW_STATE_CHANGED). Когда название узнаётся только после разбора
        // файла (внешний файл без записи), озвучим его по готовности в openBook
        // (headerAnnouncedAtOpen=false).
        val recTitle = rec?.title
        val recAuthor = rec?.author
        if (rec != null && !recTitle.isNullOrBlank()) {
            val line = if (recAuthor.isNullOrBlank()) recTitle else "$recTitle — $recAuthor"
            binding.tvHeader.text = line
            // msg1736/1739: название книги = window title окна читалки. Ставим ДО
            // показа окна — при появлении объявится книга, а не label приложения.
            setTitle(line)
            headerAnnouncedAtOpen = true
            Diag.log(this, "a11y", "имя окна читалки (setTitle) = «$line»")
        }
    }

    private fun showEmpty() {
        adapter.submit(emptyList(), false)
        closeSearchPanel()
        refreshChrome()
    }

    /** Уйти на полку — дом приложения (редизайн msg1676+). Библиотека это
     *  КОРНЕВОЕ окно задачи; между ним и ридером может лежать окно Каталога
     *  (книга открыта из каталога) — уходим на корень CLEAR_TOP, чтобы снять
     *  всё, что поверх полки. Книга закрывается (onDestroy гасит чтение),
     *  место сохранено. */
    private fun startLibrary() {
        // Полка показывается не «с рабочего стола» — авто-открытие книги гасим.
        LibraryActivity.suppressNextAutoOpen = true
        startActivity(
            Intent(this, LibraryWindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /** Уйти в «Настройки» — окно поверх (редизайн msg1676+). Как [startLibrary]:
     *  ридер закрывается, окно настроек раскрывается над тем, что под читалкой
     *  (полка или каталог). С закрытием книги чтение останавливается (см.
     *  onDestroy), место сохранено; голос/скорость на лету — «⋮ → Голос чтения»
     *  прямо в книге. */
    private fun startSettingsTab() {
        startActivity(Intent(this, SettingsWindowActivity::class.java))
        finish()
    }

    /** Книга открыта из каталога? Тогда «назад» возвращает в тот же экран
     *  каталога (он лежит под читалкой в стеке), а не в список библиотеки. */
    private val fromCatalog: Boolean
        get() = intent.getBooleanExtra(EXTRA_FROM_CATALOG, false)

    /** Выход из книги: настройка — на рабочий стол или в список книг (#43).
     *  По умолчанию — в список (так раньше «Назад» выкидывал на рабочий стол,
     *  и читать дальше было неудобно). Книга из каталога всегда возвращает
     *  в каталог (#52) — «настройка выхода» к ней не применяется. */
    override fun onBackPressed() {
        // Открыта панель поиска — «назад» сначала закрывает её, а не выходит
        // из книги (системная кнопка и жест работают одинаково).
        if (book != null && searchPanelOpen()) {
            closeSearchPanel()
            return
        }
        if (fromCatalog) {
            finish()
            return
        }
        val exit = prefs.getString(KEY_EXIT, EXIT_LIBRARY)
        if (exit == EXIT_LIBRARY) {
            // «В список книг»: хост под читалкой показывает Библиотеку; suppress
            // гасит авто-открытие последней книги (полка и так будет видна).
            startLibrary()
        } else {
            // «На рабочий стол»: хост теперь всегда под читалкой, поэтому простой
            // finish() показал бы полку, а не рабочий стол — сворачиваем задачу.
            moveTaskToBack(true)
        }
    }

    private fun openBook(uri: Uri, chapter: Int, sentence: Int) {
        // msg1739: «Открываю…» убрано — скринридер читал тост при входе и перебивал
        // объявление названия книги (в FBReader при открытии нет «открытия», есть
        // название). Имя окна уже несёт название (restoreSession → setTitle).
        Thread {
            val doc = try {
                readBook(uri)
            } catch (_: Exception) {
                null
            }
            // Глобальная нумерация предложений и накопительные слова считаем
            // в фоне — на больших книгах это не должно дёргать интерфейс.
            val prog = doc?.let { computeProgressIndexes(it) }
            handler.post {
                if (doc == null) {
                    toast("Не удалось открыть: формат не поддерживается или файл повреждён")
                    Vibra.error(this)
                    showEmpty()
                    return@post
                }
                // PDF, который распознан, но текст не извлекается (скан/пароль) —
                // показываем конкретную причину, а не общую ошибку (msg727-752).
                val unreadableRes = doc.unreadable?.let {
                    when (it) {
                        BookDocument.Unreadable.PDF_NO_TEXT_LAYER -> R.string.pdf_no_text_layer
                        BookDocument.Unreadable.PDF_ENCRYPTED -> R.string.pdf_encrypted
                    }
                }
                if (unreadableRes != null) {
                    toast(getString(unreadableRes))
                    Vibra.error(this)
                    showEmpty()
                    return@post
                }
                if (!doc.hasText) {
                    toast("Не удалось открыть: формат не поддерживается или файл повреждён")
                    Vibra.error(this)
                    showEmpty()
                    return@post
                }
                currentUri = uri.toString()
                book = doc
                if (prog != null) {
                    chapterStart = prog.first
                    cumWords = prog.second
                }
                chapterIdx = chapter.coerceIn(0, doc.chapters.size - 1)
                sentenceIdx = if (chapterIdx == chapter && chapter in 0..doc.chapters.lastIndex) {
                    sentence.coerceIn(0, doc.chapters[chapterIdx].sentences.size - 1)
                } else 0
                // msg1139: запоминаем, откуда книга открылась, и что навигации ещё
                // не было — по этому отличаем сброс позиции к началу от выбора.
                restoredPlace = Place(chapterIdx, sentenceIdx)
                userMoved = false
                // 0.3.46: синхронный хвост открытия — никаких действий читателя ещё
                // нет, поэтому goTo(0,0) в это окно — авто-сброс, а не выбор.
                openingWindow = true
                // msg1119: фиксируем, КУДА реально открылась книга — видно в diag.log,
                // если сохранённое место всё же не совпадёт с восстановленным.
                Diag.log(this, "activity", "книга открылась: глава $chapterIdx, предл. $sentenceIdx")
                registerOpen(doc)
                // Новая книга — история переходов (#101), выделение (#105) и
                // режим чтения куска (#106) больше недействительны.
                backStack.clear()
                finishSelection()
                rangeEnd = null
                voiceMissingAnnounced = false
                playing = false
                player.stop()
                refreshChrome()
                loadChapter()
                // msg1840 (0.3.88): карточку рождаем УЖЕ при открытии книги, в
                // состоянии паузы. Причина: «управление мультимедиа» (msg1815)
                // TalkBack говорит, когда BookVoice рождает media-сессию «из
                // ниоткуда» ровно в момент первого звука. У настоящих плееров
                // (YouTube — наблюдение Сергея, msg1837) сессия живёт заранее,
                // поэтому старт звука не объявляется. Рождая карточку при
                // открытии, мы даём системе «познакомиться» с сессией до
                // нажатия «Читать» — старт чтения лишь оживляет знакомую.
                // #102: свой голос/скорость книги применяем до старта чтения.
                applyBookVoiceAtOpen()
                if (!MediaSessionService.isUp()) ensureMediaService(false)
                // Если включено «автоматически читать» — начинаем без нажатия.
                if (prefs.getBoolean(KEY_AUTO_START, true)) maybeAutoStart()
                // msg1725: если название книги было известно до разбора (запись в
                // библиотеке, restoreSession) — оно уже в шапке с первого кадра и
                // уже озвучено при показе окна, дубль по готовности не нужен.
                // Если название узнали только теперь (внешний файл без записи) —
                // озвучиваем заголовок по готовности книги.
                if (!headerAnnouncedAtOpen) {
                    binding.tvHeader.postDelayed({
                        if (book != null && !isFinishing) {
                            Diag.log(this, "focus", "книга готова (названия заранее не было) — озвучиваю заголовок")
                            TabNav.focusHeader(binding.tvHeader, 0)
                        }
                    }, 300)
                }
                // Окно открытия закрыто: дальше goTo — только настоящие действия.
                openingWindow = false
            }
        }.start()
    }

    /** Начать чтение, если движок ещё инициализируется — подождать и повторить. */
    private fun maybeAutoStart() {
        if (book == null || playing) return
        requestStart()
    }

    /** Надёжный старт чтения: если движок синтеза не готов, пробуем ещё
     *  несколько раз (движок грузится в фоне после открытия книги). */
    private fun requestStart() {
        if (book == null || playing) return
        if (!player.isReady) {
            retryStart(0)
            return
        }
        startSpeakingCurrent()
    }

    private fun retryStart(attempt: Int) {
        if (attempt > 10) return // ~2.5с — движок так и не поднялся, молчим
        handler.postDelayed({
            if (book == null || playing) return@postDelayed
            if (!player.isReady) retryStart(attempt + 1) else startSpeakingCurrent()
        }, 250)
    }

    /** Поднять медиа-сервис. Карточка рождается при ОТКРЫТИИ книги, в состоянии
     *  паузы (0.3.88, msg1840): система успевает «познакомиться» с сессией ДО
     *  первого звука — как у настоящих плееров (YouTube), у которых старт
     *  чтения не озвучивается как «управление мультимедиа». Старт чтения
     *  (startSpeakingCurrent → ensureMediaService(true)) лишь переводит уже
     *  существующую сессию в PLAYING, не рождая карточку заново. [playing] —
     *  состояние ПЕРВОГО уведомления. Повторные start() идемпотентны: if сервис
     *  уже поднят, onStartCommand ничего не пересоздаёт, состояние шлёт
     *  setPlaying. */
    private fun ensureMediaService(playing: Boolean = true) {
        if (book == null) return
        MediaSessionService.start(this, book?.title ?: currentName, playing = playing)
    }

    /** После успешного открытия — обновить запись книги в библиотеке. */
    private fun registerOpen(doc: BookDocument) {
        val u = currentUri ?: return
        val now = System.currentTimeMillis()
        val existing = BookStore.byUri(this, u)
        prefs.edit().putString(KEY_URI, u).apply()
        val status = when (existing?.status) {
            BookRecord.STATUS_FINISHED -> BookRecord.STATUS_FINISHED
            else -> BookRecord.STATUS_READING
        }
        BookStore.upsert(this, BookRecord(
            uri = u,
            name = existing?.name ?: currentName ?: "book",
            title = doc.title ?: existing?.title,
            author = doc.author ?: existing?.author,
            // Аннотацию в ридере не парсим — сохраняем уже найденную в записи,
            // чтобы дозаполненная в «Информации» не затиралась при чтении.
            annotation = existing?.annotation,
            // Ссылку скачивания и запомненный голос книги сохраняем — иначе
            // каждый «просто открыл книгу» стирал бы их из записи (#100/#102).
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
        ))
    }

    private fun readBook(uri: Uri): BookDocument? {
        val name = if (uri.scheme == "file") {
            uri.lastPathSegment ?: "book"
        } else {
            queryDisplayName(uri) ?: uri.lastPathSegment ?: "book"
        }
        val bytes = openBytes(uri) ?: return null
        // «Показывать титульный лист» (#553): короткий блок в начале FB2 (титул,
        // копирайт) по умолчанию пропускается — книга начинается с первой главы.
        return BookParser.parse(name, bytes, prefs.getBoolean(KEY_SHOW_TITLE_PAGE, false))
    }

    private fun openBytes(uri: Uri): ByteArray? {
        if (uri.scheme == "file") {
            val p = uri.path ?: return null
            return try {
                java.io.File(p).readBytes()
            } catch (_: Exception) {
                null
            }
        }
        return contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }

    /** msg1119: полная запись позиции (prefs + запись книги) в моменты паузы,
     *  конца чтения и выхода с экрана. Раньше звалась ТОЛЬКО в onPause — если
     *  процесс умирал без него (свайп из недавних, «закрыть все»), место терялось
     *  на момент последнего ухода с экрана. Теперь дублируется в pausePlayback и
     *  stopAtEnd, а по каждому предложению/переходу пишется persistPosition (prefs). */
    private fun savePosition() {
        if (book == null) return
        guardResetToStart()
        Diag.log(this, "activity", "сохранено место: глава $chapterIdx, предл. $sentenceIdx")
        persistPosition()
        val u = currentUri ?: return
        val ex = BookStore.byUri(this, u) ?: return
        BookStore.upsert(this, ex.copy(
            chapter = chapterIdx,
            sentence = sentenceIdx,
            readPct = readPercent(),
            lastOpenedAt = System.currentTimeMillis(),
            status = if (ex.status == BookRecord.STATUS_NEW) BookRecord.STATUS_READING else ex.status,
        ))
    }

    /** Быстрая запись позиции в prefs — зовётся часто (каждое предложение,
     *  каждый переход слайдером/главами), поэтому только prefs, без DB-записи. */
    private fun persistPosition() {
        if (book == null) return
        guardResetToStart()
        prefs.edit()
            .putInt(KEY_CHAPTER, chapterIdx)
            .putInt(KEY_SENTENCE, sentenceIdx)
            .apply()
    }

    /** msg1139: книга открылась с сохранённого места, а позиция без ручной
     *  навигации вдруг в (0,0) — так в начало не попасть (автопереход только
     *  вперёд, в начало ведут лишь явные переходы goTo, а они ставят userMoved).
     *  Значит это тихий сброс: не даём паузе/старту/выходу записать 0/0 поверх
     *  сохранённого места. Зовётся из savePosition/persistPosition/startSpeakingCurrent. */
    private fun guardResetToStart() {
        val rp = restoredPlace ?: return
        if (userMoved) return
        if (!(rp.chapter > 0 || rp.sentence > 0)) return
        if (chapterIdx != 0 || sentenceIdx != 0) return
        Diag.log(
            this, "activity",
            "сброс к началу без навигации — держу место: глава ${rp.chapter}, предл. ${rp.sentence}"
        )
        chapterIdx = rp.chapter
        sentenceIdx = rp.sentence
    }

    /** Переключатель «читаю / дочитана». */
    private fun toggleStatus() {
        val u = currentUri ?: return
        val ex = BookStore.byUri(this, u) ?: return
        val next = if (ex.status == BookRecord.STATUS_FINISHED) {
            BookRecord.STATUS_READING
        } else {
            BookRecord.STATUS_FINISHED
        }
        BookStore.upsert(this, ex.copy(status = next))
        updateStatusButton()
        toast(getString(if (next == BookRecord.STATUS_FINISHED) R.string.status_finished else R.string.status_reading))
    }

    private fun updateStatusButton() {
        val u = currentUri
        val finished = u != null &&
            BookStore.byUri(this, u)?.status == BookRecord.STATUS_FINISHED
        binding.btnStatus.text = getString(
            if (finished) R.string.status_reading_toggle else R.string.status_finished_toggle
        )
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        // Ридер закрыт — сессию и сервис гасим, чтобы не отбирать
        // медиа-кнопки у других плееров. Фокус отдаём и авто-продолжение
        // гасим: вернуться «по фокусу» уже некому.
        if (active === this) active = null
        pausedByFocusLoss = false
        dropAudioFocus()
        playing = false
        MediaSessionService.stop(this)
        player.shutdown()
        super.onDestroy()
    }

    // ---------------- Отображение текущей главы ----------------

    private fun loadChapter() {
        val bk = book ?: return
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: emptyList()
        if (cur.isEmpty()) {
            adapter.submit(emptyList(), false)
            updatePosition()
            return
        }
        sentenceIdx = sentenceIdx.coerceIn(0, cur.lastIndex)
        adapter.submit(cur, chapterIdx < bk.chapters.lastIndex)
        adapter.setCurrent(sentenceIdx)
        refreshSelectionMarkers()
        scrollToSentence(sentenceIdx)
        updatePosition()
    }

    private fun showCurrent() {
        val cur = book?.chapters?.getOrNull(chapterIdx)?.sentences ?: return
        if (cur.isEmpty()) return
        val pos = sentenceIdx.coerceIn(0, cur.lastIndex)
        adapter.setCurrent(pos)
        scrollToSentence(pos)
        updatePosition()
    }

    /** Прокрутить список к позиции, если в настройках включено
     *  «Прокручивать к читаемому предложению». */
    private fun scrollToSentence(pos: Int) {
        if (prefs.getBoolean(KEY_SCROLL, true)) layoutManager.scrollToPosition(pos)
    }

    /** Настраиваемые свайпы влево/вправо по тексту (#77). Без TalkBack жест —
     *  одним пальцем; с включённым TalkBack один палец он забирает себе, поэтому
     *  свайпать нужно двумя пальцами (их скринридер не трогает). И то, и другое
     *  приводит к ОДНОЙ назначенной функции на направление. Что делает свайп,
     *  выбирается в Настройках → «Жесты». Двухпальцевые вверх/вниз остаются
     *  прокруткой списка — их не перехватываем. */
    private fun installSwipe() {
        val vt = VelocityTracker.obtain()
        var downX = 0f
        var downY = 0f
        var multi = false
        binding.sentenceList.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    multi = false
                    vt.clear()
                    vt.addMovement(e)
                    false
                }
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    if (e.pointerCount >= 2) multi = true
                    vt.addMovement(e)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    vt.addMovement(e)
                    vt.computeCurrentVelocity(1000)
                    val vx = vt.xVelocity
                    val dx = e.x - downX
                    val dy = e.y - downY
                    vt.clear()
                    // Горизонтальный быстрый свайп. Одним пальцем — только когда
                    // экранный диктор выключен (включённый сам забирает одиночные
                    // свайпы, и до нас они не дойдут как полноценный жест).
                    val singleOk = !screenReaderOn()
                    val horiz = abs(dx) > 60 && abs(dx) > abs(dy) && abs(vx) > 300
                    if (horiz && (multi || singleOk)) {
                        fireSwipe(if (vx < 0) -1 else +1)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    vt.clear()
                    false
                }
                else -> false
            }
        }
    }

    /** Включён ли экранный диктор TalkBack: тогда одиночные свайпы — его, и
     *  нашим жестом считаем только двухпальцевые. */
    private fun screenReaderOn(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            ?: return false
        val talkback = "com.google.android.marvin.talkback"
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
        ).any { it.resolveInfo?.serviceInfo?.packageName == talkback }
    }

    /** Свайп влево (-1) / вправо (+1): вызывает действие, назначенное на это
     *  направление в Настройках → «Жесты». */
    private fun fireSwipe(dir: Int) {
        if (book == null) return
        val key = if (dir < 0) KEY_GESTURE_LEFT else KEY_GESTURE_RIGHT
        val def = if (dir < 0) G_PREV_CH else G_NEXT_CH
        runGestureAction(prefs.getString(key, def) ?: def)
    }

    /** Выполнить действие свайпа по его id (см. [GESTURE_ACTIONS]). */
    private fun runGestureAction(act: String) {
        if (book == null) return
        when (act) {
            G_PREV_SENT -> jumpAndAnnounce { moveBySentence(-1) }
            G_NEXT_SENT -> jumpAndAnnounce { moveBySentence(+1) }
            G_PREV_CH -> jumpAndAnnounce { moveByChapter(-1) }
            G_NEXT_CH -> jumpAndAnnounce { moveByChapter(+1) }
            G_PLAY -> togglePlay()
            G_PAUSE -> if (playing) pausePlayback(keepFocus = true)
            G_REPEAT -> startSpeakingCurrent()
            G_POSITION -> announcePosition()
            G_SPEED_UP -> nudgeSpeed(+0.1f)
            G_SPEED_DOWN -> nudgeSpeed(-0.1f)
            G_TOC -> showTocDialog()
            G_BOOKMARKS -> onBookmarkClick()
            G_ADD_BM -> onBookmarkLongClick()
        }
    }

    /** Переход по жесту: если чтение не шло, озвучить, куда встали (при паузе
     *  свайп иначе «молчит», и не видно, сработал ли он). Если чтение шло —
     *  [goTo] сам продолжит его с новой позиции. */
    private inline fun jumpAndAnnounce(move: () -> Unit) {
        val wasPlaying = playing
        move()
        if (!wasPlaying) announcePosition()
    }

    // ---------------- Навигация ----------------

    private fun moveBySentence(delta: Int) {
        val bk = book ?: return
        var ch = chapterIdx
        var s = sentenceIdx + delta
        if (s < 0) {
            if (ch <= 0) return
            ch--
            s = bk.chapters[ch].sentences.size - 1
        } else if (s >= bk.chapters[ch].sentences.size) {
            if (ch + 1 >= bk.chapters.size) return
            ch++
            s = 0
        }
        goTo(ch, s)
    }

    private fun moveByChapter(delta: Int) {
        val stops = chapterStopIndexes()
        if (stops.isEmpty()) return
        val cur = chapterIdx
        var target = -1
        if (delta > 0) {
            for (s in stops) if (s > cur) { target = s; break }
        } else {
            for (i in stops.indices.reversed()) if (stops[i] < cur) { target = stops[i]; break }
        }
        if (target < 0) return
        pushPlace() // #101: запомнить место, откуда ушли по главе
        goTo(target, 0)
    }

    /** По каким «главам» ходят «Предыдущая/Следующая глава» и свайп-глава
     *  (настройка «Кнопки глав», 0.3.37). Фильтрует плоский список глав по
     *  разметке FB2: [CH_NAV_MAJOR] — только начала крупных разделов (parts),
     *  [CH_NAV_CHAPTERS] — главы без вложенных подразделов, [CH_NAV_ALL] —
     *  по всем (по умолчанию, поведение прежнее). Для TXT/EPUB иерархии нет —
     *  все три режима дают полный список. */
    private fun chapterStopIndexes(): IntArray {
        val bk = book ?: return intArrayOf()
        val mode = prefs.getString(KEY_CH_NAV, CH_NAV_ALL)
        val size = bk.chapters.size
        if (mode == CH_NAV_ALL) return IntArray(size) { it }
        val out = ArrayList<Int>(size)
        for (i in 0 until size) {
            val ch = bk.chapters[i]
            if (mode == CH_NAV_MAJOR) {
                if (ch.major) out.add(i)
            } else if (!ch.nested) {   // CH_NAV_CHAPTERS
                out.add(i)
            }
        }
        return out.toIntArray()
    }

    /** Шаг «дальше/назад» — настройка: предложением (по умолчанию) или главой. */
    private fun stepIsChapter(): Boolean =
        prefs.getString(KEY_STEP, STEP_SENTENCE) == STEP_CHAPTER

    /** Кнопки «◀ Пред»/«След ▶» и свайп листают шагом из настроек: если там
     *  выбрано «главой», обычные кнопки переходят по главам, как ⏮/⏭. */
    private fun stepMove(delta: Int) {
        if (stepIsChapter()) moveByChapter(delta) else moveBySentence(delta)
    }

    private fun goTo(ch: Int, s: Int) {
        val bk = book ?: return
        // Любой ручной переход отменяет режим «прочитать выделенный кусок» (#106).
        rangeEnd = null
        val chClamped = ch.coerceIn(0, bk.chapters.lastIndex)
        val cur = bk.chapters[chClamped].sentences
        if (cur.isEmpty()) return
        val sClamped = s.coerceIn(0, cur.lastIndex)
        // 0.3.46: пока идёт открытие книги, настоящий переход к началу невозможен
        // (главный поток занят) — значит это авто-сброс позиции к (0,0), а не
        // выбор читателя. Молча блокируем, чтобы восстановленное место устояло.
        if (openingWindow && chClamped == 0 && sClamped == 0) {
            Diag.log(this, "activity", "переход к началу в момент открытия — игнорирую (авто-сброс)")
            return
        }
        val wasPlaying = playing
        if (wasPlaying) {
            playing = false
            player.stop()
        }
        val changedChapter = chClamped != chapterIdx
        chapterIdx = chClamped
        sentenceIdx = sClamped
        // msg1139: любой переход через goTo — ручная навигация (слайдер/тап/
        // главы/оглавление/жест). Пишем куда ушли в diag.log: если позиция
        // «сама» уезжает к началу, лог покажет, какой путь её сбросил.
        // 0.3.46: а если к (0,0) едет ПОСЛЕ открытия и без ручной навигации —
        // записываем цепочку вызовов, чтобы поймать источник авто-сброса.
        if (chClamped == 0 && sClamped == 0 && !userMoved) {
            val rp = restoredPlace
            if (rp != null && (rp.chapter > 0 || rp.sentence > 0)) {
                val callers = Thread.currentThread().stackTrace
                    .take(7).drop(2).joinToString(" <- ") { it.methodName }
                Diag.log(this, "activity", "goTo(0,0) без ручной навигации; цепочка: $callers")
            }
        }
        userMoved = true
        Diag.log(this, "activity", "переход: глава $chClamped, предл. $sClamped")
        // msg1119: переходы (слайдер/главы/оглавление/двойной тап) сразу пишут
        // позицию в prefs — перемотка на 30% не должна теряться при закрытии.
        persistPosition()
        if (changedChapter) {
            loadChapter()
        } else {
            adapter.setCurrent(sClamped)
            scrollToSentence(sClamped)
            updatePosition()
        }
        updatePlayButton()
        if (wasPlaying) startSpeakingCurrent()
    }

    /** Двойной тап по предложению (при чтении TalkBack'ом — активация строки).
     *  Всегда переводит читаемую позицию на это предложение; если чтение ещё
     *  не шло и в настройках включено «двойной тап начинает чтение» — начинаем
     *  озвучивать отсюда (см. [KEY_TAP_TO_PLAY]). */
    private fun onSentenceTapped(pos: Int) {
        if (book == null) return
        goTo(chapterIdx, pos)
        if (!playing && prefs.getBoolean(KEY_TAP_TO_PLAY, true)) requestStart()
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
        Diag.log(this, "activity", "чтение закончилось само (конец/остановка)")
        playing = false
        dropAudioFocus()
        // msg1119: дочитал до конца — фиксируем, чтобы повторно не начать с начала.
        savePosition()
        updatePlayButton()
    }

    /** Перейти на следующее предложение (или главу) — для автопродолжения. */
    private fun advanceOneUnit(): Boolean {
        val bk = book ?: return false
        val cur = bk.chapters[chapterIdx].sentences
        if (sentenceIdx + 1 < cur.size) {
            sentenceIdx++
        } else if (chapterIdx + 1 < bk.chapters.size) {
            chapterIdx++
            sentenceIdx = 0
            loadChapter()
        } else {
            return false
        }
        // #106: «Прочитать выделенное» — остановиться ровно на конце куска,
        // не продолжая читать дальше.
        val re = rangeEnd
        if (re != null &&
            (chapterIdx > re.chapter || (chapterIdx == re.chapter && sentenceIdx >= re.sentence))
        ) {
            rangeEnd = null
            return false
        }
        // msg1119: каждое прочитанное предложение — в prefs (дёшево), чтобы
        // при любом обрыве процесса место не откатывалось к последнему выходу.
        persistPosition()
        return true
    }

    // ---------------- Воспроизведение ----------------

    private fun togglePlay() {
        if (book == null) return
        // Пользовательская пауза кнопкой — фокус держим (см. pausePlayback).
        if (playing) pausePlayback(keepFocus = true) else startSpeakingCurrent()
    }

    /** Пауза чтения.
     *  keepFocus=true — когда паузу нажал сам пользователь (кнопка, наушники,
     *  слайдер): фокус держим, чтобы система продолжала отдавать медиа-кнопки
     *  нам, а не уводила их чужому плееру. keepFocus=false — когда фокус
     *  отобрала система (звонок) — тут спорить не с чем.
     *  byFocusLoss=true — чтение прервал ДРУГОЙ плеер, а не пользователь:
     *  запоминаем это, чтобы самим продолжиться, когда тот плеер замолчит
     *  (см. AUDIOFOCUS_GAIN). Любая явная пользовательская пауза это
     *  намерение сбрасывает. */
    private fun pausePlayback(keepFocus: Boolean = false, byFocusLoss: Boolean = false) {
        if (!playing) return
        Diag.log(
            this, "activity",
            "пауза: ${if (byFocusLoss) "прерван чужим плеером" else "по команде пользователя"}, " +
                "keepFocus=$keepFocus"
        )
        playing = false
        player.stop()
        pausedByFocusLoss = byFocusLoss
        if (!keepFocus) dropAudioFocus()
        // msg1119: пауза — ключевой момент, позицию фиксируем сразу, не дожидаясь
        // выхода с экрана (иначе «остановил → закрыл» теряло место).
        savePosition()
        updatePlayButton()
    }

    /** Забираем аудиофокус на время чтения. Читалка — «владелец» звука, пока
     *  идёт чтение: фокус держим и на пользовательской паузе, чтобы другой
     *  плеер не просыпался и не перехватывал кнопки наушника (см. onPause). */
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
                .setOnAudioFocusChangeListener(audioFocusListener, handler)
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
            this, "focus",
            "запрос фокуса → ${if (haveAudioFocus) "ДАНО" else "ОТКАЗАНО ($r)"}, SDK=" +
                Build.VERSION.SDK_INT
        )
        if (haveAudioFocus) focusRetried = false
        // Сразу не дали (например, фокус ещё не освободился) — пробуем ещё раз
        // один раз, если чтение продолжается.
        if (!haveAudioFocus && !focusRetried && Build.VERSION.SDK_INT >= 26) {
            focusRetried = true
            handler.postDelayed({
                Diag.log(this, "focus", "повторный запрос фокуса через 400мс")
                if (playing) requestAudioFocus()
            }, 400)
        }
    }

    private fun dropAudioFocus() {
        if (!haveAudioFocus) return
        Diag.log(this, "focus", "отдаю фокус")
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

    private fun startSpeakingCurrent() {
        val bk = book ?: return
        // msg1139: если позицию тихо сбросило к началу — стартуем не с (0,0),
        // а с места, на котором книга открылась.
        guardResetToStart()
        if (bk.chapters.getOrNull(chapterIdx)?.sentences.isNullOrEmpty()) return
        if (!player.isReady) {
            toast("Движок синтеза речи ещё не готов, попробуйте через секунду")
            return
        }
        // Пользователь запускает чтение явно — прерванность чужим плеером
        // больше не актуальна, сами не «оживём» не вовремя.
        pausedByFocusLoss = false
        playing = true
        ensureMediaService()
        Diag.log(this, "activity", "старт чтения (глава $chapterIdx, предл. $sentenceIdx)")
        requestAudioFocus()
        showCurrent()
        speakCurrent()
        updatePlayButton()
    }

    /** Текст, который будет реально озвучен на позиции (ch, s). На первом
     *  предложении главы спереди добавляется её название (если есть и в
     *  настройках включено «Озвучивать название главы в начале») — так Сергей
     *  слышит, в какую главу попал. Название, дословно совпадающее с первым
     *  предложением, не дублируем. */
    private fun spokenText(ch: Int, s: Int): String? {
        val bk = book ?: return null
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return null
        val text = cur.getOrNull(s)?.text ?: return null
        if (s != 0 || !prefs.getBoolean(KEY_SAY_CHAPTER_START, true)) return text
        val title = bk.chapters[ch].title?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        return if (title == text.trim()) text else "$title. $text"
    }

    private fun speakCurrent() {
        val t = spokenText(chapterIdx, sentenceIdx) ?: return
        player.speak(t)
    }

    /** Текст предложения, которое пойдёт следующим за текущим — без побочных
     *  эффектов (не двигает позицию). Используется плеером для упреждающего
     *  синтеза, чтобы между предложениями не было тишины. Содержит то же, что
     *  будет озвучено по-настоящему (с названием главы на её границе). */
    private fun peekNextText(): String? {
        val bk = book ?: return null
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: return null
        if (sentenceIdx + 1 < cur.size) return spokenText(chapterIdx, sentenceIdx + 1)
        if (chapterIdx + 1 < bk.chapters.size) return spokenText(chapterIdx + 1, 0)
        return null
    }

    // ---------------- UI ----------------

    private fun refreshChrome() {
        val bk = book
        val headerText = when {
            bk == null -> getString(R.string.no_book)
            bk.author.isNullOrBlank() -> bk.title ?: getString(R.string.app_name)
            else -> "${bk.title ?: getString(R.string.app_name)} — ${bk.author}"
        }
        binding.tvHeader.text = headerText
        // msg1736/1739: window title окна — по фактическому названию книги (для
        // файлов вне записи оно известно только после разбора).
        if (bk != null) setTitle(headerText)
        val enabled = bk != null
        binding.btnPlayPause.isEnabled = enabled
        binding.btnPrevSentence.isEnabled = enabled
        binding.btnNextSentence.isEnabled = enabled
        binding.btnPrevChapter.isEnabled = enabled
        binding.btnNextChapter.isEnabled = enabled
        binding.btnSpeedDown.isEnabled = enabled
        binding.btnSpeedUp.isEnabled = enabled
        binding.btnStatus.isEnabled = enabled
        binding.btnToc.isEnabled = enabled
        binding.btnSearch.isEnabled = enabled
        binding.seekProgress.isEnabled = enabled
        updatePlayButton()
        updateStatusButton()
        updatePosition()
    }

    private fun updatePlayButton() {
        // msg1644/1646 (0.3.68): TalkBack озвучивал смену кнопки несколько раз
        // подряд — из-за двух источников (contentDescription и текст менялись
        // отдельными событиями) + повторов. Один источник озвучки = ТЕКСТ, без
        // значка ▶/⏸ в строке (msg1144: значок скринридер читал отдельным словом)
        // и без contentDescription: одно изменение = одно объявление.
        binding.btnPlayPause.text = if (playing) "Пауза" else "Читать"
        MediaSessionService.setPlaying(playing)
    }

    /** Конструктор экрана (#58): по настройкам прячет или показывает элементы
     *  читалки. Вызывается при старте и при возврате из «Настроек» (onStart). */
    private fun applyReaderUi() {
        fun show(key: String, v: View) {
            v.visibility = if (prefs.getBoolean(key, true)) View.VISIBLE else View.GONE
        }
        // Стрелки предложений — пара, один ключ на обе.
        show(KEY_UI_SENT, binding.btnPrevSentence)
        show(KEY_UI_SENT, binding.btnNextSentence)
        show(KEY_UI_PLAY, binding.btnPlayPause)
        // Стрелки глав — пара, один ключ на обе.
        show(KEY_UI_CHAPTERS, binding.btnPrevChapter)
        show(KEY_UI_CHAPTERS, binding.btnNextChapter)
        show(KEY_UI_SLIDER, binding.seekProgress)
        show(KEY_UI_POSITION, binding.tvPosition)
        show(KEY_UI_STATS, binding.tvStats)
        show(KEY_UI_TOC, binding.btnToc)
        show(KEY_UI_BOOKMARK, binding.btnBookmark)
        show(KEY_UI_SEARCH, binding.btnSearch)
        // Кнопку поиска убрали в конструкторе экрана — прячем и открытую панель.
        if (!prefs.getBoolean(KEY_UI_SEARCH, true)) closeSearchPanel()
        show(KEY_UI_MORE, binding.btnMore)
        show(KEY_UI_VOICE, binding.btnVoice)
        show(KEY_UI_STATUS, binding.btnStatus)
        show(KEY_UI_SPEED, binding.speedRow)
    }

    /** Подпись текущей скорости над рядом кнопок «Медленнее/Быстрее». */
    private fun speedText(rate: Float): String =
        "Скорость: " + String.format(java.util.Locale.ROOT, "%.1f", rate)

    /** #102: какая скорость сейчас уместна — своя у книги (если запомнена) или
     *  глобальная из настроек. */
    private fun currentSpeed(): Float {
        if (book == null) return prefs.getFloat(KEY_SPEED, 1f)
        val bs = currentBookRecord()?.voiceSpeed
        return if (bs != null && bs > 0f) bs else prefs.getFloat(KEY_SPEED, 1f)
    }

    private fun refreshSpeedValue() {
        binding.tvSpeed.text = speedText(currentSpeed())
    }

    /** Кнопки «Медленнее/Быстрее» (#58): шаг 0.1, диапазон 0.5–2.0 — тот же,
     *  что у слайдера скорости в диалоге голоса. Если у книги запомнена своя
     *  скорость (#102) — меняем именно её, глобальную не трогаем.
     *  Если книга на паузе, новую скорость озвучиваем (во время чтения её
     *  слышно и так). */
    private fun nudgeSpeed(delta: Float) {
        val cur = currentSpeed()
        val rate = (cur + delta).coerceIn(0.5f, 2.0f)
        if (rate == cur) return
        if (currentBookRecord()?.voiceSpeed != null) {
            // Своя скорость книги: сохраняем в запись книги.
            savePerBook(perBookEngine, perBookVoice, rate)
        } else {
            prefs.edit().putFloat(KEY_SPEED, rate).apply()
        }
        if (player.isReady) player.speed = rate
        refreshSpeedValue()
        updateStats() // «Прочитано/осталось» пересчитывается под новую скорость.
        if (!playing) binding.tvSpeed.announceForAccessibility(speedText(rate))
    }

    private fun updatePosition() {
        val bk = book
        if (bk == null) {
            binding.tvPosition.text = ""
            binding.seekProgress.max = 0
            binding.seekProgress.progress = 0
            binding.tvStats.text = ""
            return
        }
        val curSize = bk.chapters.getOrNull(chapterIdx)?.sentences?.size ?: 0
        val pos = if (curSize > 0) {
            " · ${(sentenceIdx.coerceIn(0, curSize - 1)) + 1} из $curSize"
        } else ""
        binding.tvPosition.text = "Глава ${chapterIdx + 1} из ${bk.chapters.size}$pos"
        syncSlider()
        updateStats()
    }

    /** Глобальный номер текущего предложения по всей книге. */
    private fun currentGlobal(): Int =
        (chapterStart.getOrNull(chapterIdx) ?: 0) + sentenceIdx

    /** Синхронизировать бегунок слайдера с текущей позицией. */
    private fun syncSlider() {
        val total = chapterStart.lastOrNull() ?: 0
        if (scrubbing) return // пока палец на слайдере — недёргаем бегунок
        binding.seekProgress.max = (total - 1).coerceAtLeast(0)
        binding.seekProgress.progress = currentGlobal().coerceIn(0, (total - 1).coerceAtLeast(0))
    }

    /** Индексы для перемотки и статистики: начало каждой главы и слова по
     *  предложениям. Считается один раз при открытии книги, в фоне. */
    private fun computeProgressIndexes(doc: BookDocument): Pair<IntArray, LongArray> {
        val starts = IntArray(doc.chapters.size + 1)
        var g = 0
        for ((i, ch) in doc.chapters.withIndex()) {
            starts[i] = g
            g += ch.sentences.size
        }
        starts[doc.chapters.size] = g
        val cum = LongArray(g + 1)
        var words = 0L
        var idx = 0
        for (ch in doc.chapters) {
            for (s in ch.sentences) {
                words += countWords(s.text)
                cum[idx + 1] = words
                idx++
            }
        }
        return starts to cum
    }

    private fun countWords(t: String): Int {
        var n = 0
        var inWord = false
        for (c in t) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                n++
                inWord = true
            }
        }
        return n
    }

    /** Процент прочитанного на текущей позиции — по месту среди предложений
     *  книги, той же «линейкой», что слайдер перемотки (msg1380: слайдер
     *  считал по предложениям, а в запись писался процент по словам — главы
     *  разной плотности давали на одном месте разные числа, например 25% и
     *  35%). Теперь сохраняем в запись ([BookRecord.readPct]) и показываем
     *  в статистике ровно тот процент, что читает слайдер. */
    private fun readPercent(): Int {
        val total = chapterStart.lastOrNull() ?: 0
        if (total <= 1) return 0
        // Та же арифметика, что у слайдера: max = total−1, прогресс = currentGlobal.
        val g = currentGlobal().coerceIn(0, total - 1)
        return g * 100 / (total - 1)
    }

    /** Строка «прочитано/осталось» — грубая оценка по количеству слов и
     *  скорости (~150 слов/мин на скорости 1.0x), не хронометраж. */
    private fun updateStats() {
        val total = chapterStart.lastOrNull() ?: 0
        if (total <= 0 || cumWords.size < 2) {
            binding.tvStats.text = ""
            return
        }
        val g = currentGlobal().coerceIn(0, total)
        val wpm = (150f * currentSpeed()).coerceAtLeast(20f)
        val readWords = cumWords.getOrNull(g) ?: 0L
        val totalWords = cumWords.getOrNull(total) ?: readWords
        val remainWords = (totalWords - readWords).coerceAtLeast(0L)
        binding.tvStats.text =
            "Прочитано ~${fmtMin(readWords / wpm)}, осталось ~${fmtMin(remainWords / wpm)} (${readPercent()}%)"
    }

    /** Минуты → «12 мин» / «1 ч 5 мин». Доли меньше минуты показываем «<1 мин». */
    private fun fmtMin(mins: Float): String {
        val mm = mins.roundToInt()
        return when {
            mm <= 0 -> "<1 мин"
            mm < 60 -> "$mm мин"
            else -> {
                val h = mm / 60
                val r = mm % 60
                if (r == 0) "$h ч" else "$h ч $r мин"
            }
        }
    }

    // ---------------- Оглавление и слайдер ----------------

    /** Диалог «Оглавление»: список глав (и закладки книги, если есть),
     *  выбор прыгает к началу главы. */
    private fun showTocDialog() {
        val bk = book ?: return
        val bms = loadBookmarks()
        val bmHeader = if (bms.isEmpty()) null else getString(R.string.bm_toc_entry, bms.size)
        val titles = bk.chapters.mapIndexed { i, ch ->
            val title = ch.title?.takeIf { it.isNotBlank() } ?: "Глава ${i + 1}"
            if (i == chapterIdx) getString(R.string.toc_current, i + 1, title)
            else "${i + 1}. $title"
        }
        val items = if (bmHeader == null) titles.toTypedArray() else arrayOf(bmHeader) + titles
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toc_title)
            .setItems(items) { _, which ->
                if (bmHeader != null && which == 0) {
                    showBookmarksList(loadBookmarks())
                    return@setItems
                }
                pushPlace() // #101: глава по оглавлению — явный переход
                goTo(which - if (bmHeader == null) 0 else 1, 0)
                announcePosition()
                // Выбранная глава в оглавлении сразу начинает читаться (если
                // включено в настройках, KEY_TOC_PLAY): когда чтение шло, goTo
                // уже продолжает с неё; стояло на паузе — запускаем сами
                // (движок может быть не готов — requestStart сам подождёт).
                if (!playing && prefs.getBoolean(KEY_TOC_PLAY, true)) requestStart()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Перепрыгнуть на глобальное предложение (слайдер перемотки). [announce] —
     *  подтверждать голосом, куда прыгнули: включаем на отпускании пальцевого
     *  драга; на пошаговых свайпах TalkBack не дублируем — их значение он и
     *  так озвучивает сам. */
    private fun jumpToGlobal(gTarget: Int, announce: Boolean = true) {
        val bk = book ?: return
        val total = chapterStart.lastOrNull() ?: return
        if (total <= 0) return
        val g = gTarget.coerceIn(0, total - 1)
        var ch = 0
        while (ch + 1 < chapterStart.size && chapterStart[ch + 1] <= g) ch++
        val s = (g - chapterStart[ch]).coerceIn(0, bk.chapters[ch].sentences.size - 1)
        pushPlace() // #101: перемотка слайдером — явный переход
        goTo(ch, s)
        if (announce) announcePosition()
    }

    /** Проговорить, куда прыгнули (оглалвление/слайдер) — TalkBack читает
     *  строку позиции, даже если фокус остался на кнопке. */
    private fun announcePosition() {
        val txt = binding.tvPosition.text?.toString().orEmpty()
        if (txt.isNotBlank()) binding.sentenceList.announceForAccessibility(txt)
    }

    // ---------------- Поиск по книге ----------------

    /** Кнопка «Поиск»: панель уже открыта — прячем её, иначе показываем
     *  с фокусом в поле ввода. */
    private fun onSearchButtonTap() {
        if (book == null) return
        if (searchPanelOpen()) closeSearchPanel()
        else openSearchPanel(focusField = true)
    }

    private fun onSearchLongPress() {
        if (book == null) return
        startVoiceSearch()
    }

    private fun searchPanelOpen(): Boolean =
        binding.searchPanel.visibility == View.VISIBLE

    private fun openSearchPanel(focusField: Boolean) {
        if (book == null) return
        binding.searchPanel.visibility = View.VISIBLE
        if (focusField) {
            binding.etSearch.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.etSearch, 0)
        }
    }

    private fun closeSearchPanel() {
        binding.searchPanel.visibility = View.GONE
        searchGen++ // идущий в фоне поиск больше не нужен
        searchMatches = emptyList()
        searchCurrent = -1
        binding.tvSearchResult.text = ""
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    /** «Найти» по тексту из поля. */
    private fun searchFromField() {
        if (book == null) return
        hideKeyboard()
        val q = binding.etSearch.text?.toString().orEmpty().trim()
        if (q.isEmpty()) {
            binding.etSearch.announceForAccessibility(getString(R.string.search_empty_hint))
            binding.etSearch.requestFocus()
            return
        }
        runSearch(q)
    }

    /** Полный проход по книге в фоне: ищем слово без учёта регистра во всех
     *  главах. Результат — список (глава, предложение). searchGen отбрасывает
     *  устаревшие ответы (панель закрыли, нажали «Найти» повторно). */
    private fun runSearch(query: String) {
        val bk = book ?: return
        val gen = ++searchGen
        val needle = query.lowercase(Locale.ROOT)
        Thread {
            val found = ArrayList<Pair<Int, Int>>()
            for ((ci, ch) in bk.chapters.withIndex()) {
                for ((si, s) in ch.sentences.withIndex()) {
                    if (s.text.lowercase(Locale.ROOT).contains(needle)) found.add(ci to si)
                }
                if (gen != searchGen) return@Thread // панель закрыли — хватит искать
            }
            handler.post {
                if (gen != searchGen) return@post
                searchMatches = found
                if (found.isEmpty()) {
                    searchCurrent = -1
                    binding.tvSearchResult.text = getString(R.string.search_none)
                    binding.sentenceList.announceForAccessibility(getString(R.string.search_none))
                    return@post
                }
                // Первое совпадение — текущее или ближайшее следующее за ним.
                val g = currentGlobal()
                var idx = found.indexOfFirst { (c, s) -> (chapterStart.getOrNull(c) ?: 0) + s >= g }
                if (idx < 0) idx = 0
                searchCurrent = idx
                jumpToMatch()
            }
        }.start()
    }

    /** Переход к текущему совпадению. Если чтение шло — [goTo] сам продолжит его
     *  с нового места. Стояло на паузе — озвучиваем счётчик и, если в настройках
     *  включено «Читать сразу при переходе к найденному слову», через паузу
     *  начинаем читать найденное предложение (TalkBack успеет договорить). */
    private fun jumpToMatch() {
        val m = searchMatches.getOrNull(searchCurrent) ?: return
        val wasPlaying = playing
        pushPlace() // #101: переход к найденному — явный переход
        goTo(m.first, m.second)
        updateSearchCounter()
        if (wasPlaying) return
        binding.sentenceList.announceForAccessibility(searchCounterText())
        if (prefs.getBoolean(KEY_SEARCH_PLAY, true)) {
            handler.postDelayed({
                if (!playing && book != null) requestStart()
            }, 450)
        }
    }

    /** «Далее»/«Назад» по списку совпадений (по кругу в обе стороны). */
    private fun stepSearch(delta: Int) {
        if (searchMatches.isEmpty()) return
        val n = searchMatches.size
        searchCurrent = ((searchCurrent + delta) % n + n) % n
        jumpToMatch()
    }

    private fun updateSearchCounter() {
        binding.tvSearchResult.text = searchCounterText()
    }

    private fun searchCounterText(): String {
        val n = searchMatches.size
        if (n == 0) return getString(R.string.search_none)
        return getString(R.string.search_counter, searchCurrent + 1, n)
    }

    /** Голосовой ввод (системное распознавание речи) — из долгого нажатия на
     *  «Поиск» и с кнопки-микрофона в панели. Распознанное слово подставляется
     *  в поле и сразу запускает поиск (см. [onActivityResult]). */
    private fun startVoiceSearch() {
        if (book == null) return
        // Чтение мешало бы распознаванию — ставим на паузу, фокус держим.
        if (playing) pausePlayback(keepFocus = true)
        hideKeyboard()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice_prompt))
        }
        try {
            startActivityForResult(intent, REQ_VOICE_SEARCH)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.search_voice_unavailable))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE_SEARCH || resultCode != RESULT_OK || data == null) return
        val word = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }
        if (word == null) {
            binding.sentenceList.announceForAccessibility(getString(R.string.search_voice_retry))
            return
        }
        // Панель показываем с распознанным словом в поле; клавиатуру после
        // диктовки не открываем — слово уже введено.
        openSearchPanel(focusField = false)
        binding.etSearch.setText(word)
        binding.etSearch.setSelection(word.length)
        runSearch(word)
    }

    // ---------------- Закладки ----------------

    private data class Bm(val chapter: Int, val sentence: Int, val label: String)

    /** Храним закладки отдельно для каждой книги (по её uri), JSON-списком. */
    private fun bookmarksKey(): String {
        val u = currentUri ?: return ""
        return "bookmarks_" + u
    }

    private fun loadBookmarks(): MutableList<Bm> {
        val key = bookmarksKey()
        if (key.isEmpty()) return mutableListOf()
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Bm(o.getInt("ch"), o.getInt("s"), o.getString("t"))
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveBookmarks(list: List<Bm>) {
        val key = bookmarksKey()
        if (key.isEmpty()) return
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("ch", b.chapter).put("s", b.sentence).put("t", b.label))
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun findBookmark(list: List<Bm>, ch: Int, s: Int): Bm? =
        list.firstOrNull { it.chapter == ch && it.sentence == s }

    private fun currentSentenceText(): String {
        val bk = book ?: return ""
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: return ""
        if (cur.isEmpty()) return ""
        return cur[sentenceIdx.coerceIn(0, cur.lastIndex)].text.replace(Regex("\\s+"), " ").trim()
    }

    /** Имя новой закладки по умолчанию — слова предложения, где стоит курсор.
     *  Длинные обрезаем по границе слова, чтобы имя не превращалось в простыню. */
    private fun defaultBookmarkLabel(): String {
        val t = currentSentenceText()
        if (t.length <= 120) return t
        val cut = t.substring(0, 120)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > 40) cut.substring(0, sp) else cut) + "…"
    }

    /** Короткое нажатие «Закладки»: список закладок книги — выбор переходит к
     *  месту (и, если включено в настройках, сразу читает оттуда). */
    private fun onBookmarkClick() {
        showBookmarksList(loadBookmarks())
    }

    /** Долгое нажатие «Закладки»: сразу создать закладку на текущем месте с
     *  автоименем — без диалога. Если на этом месте закладка уже есть — ничего
     *  не создаём, только подсказка (удаление — в списке закладок). */
    private fun onBookmarkLongClick() {
        val bk = book ?: return
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: return
        if (cur.isEmpty()) return
        val s = sentenceIdx.coerceIn(0, cur.lastIndex)
        val list = loadBookmarks()
        if (findBookmark(list, chapterIdx, s) != null) {
            toast(getString(R.string.bm_existing_title))
            return
        }
        list.add(Bm(chapterIdx, s, defaultBookmarkLabel()))
        saveBookmarks(list)
        toast(getString(R.string.bm_created))
        Vibra.confirm(this)
    }

    /** Список закладок книги: у каждой строки слева — переход к месту (и, если
     *  включено в настройках, авто-старт чтения оттуда), справа — «Убрать».
     *  Долгое нажатие на строку — действия с закладкой (#103): поделиться или
     *  переименовать. */
    private fun showBookmarksList(initial: List<Bm>) {
        val list = initial.toMutableList()
        if (list.isEmpty()) {
            toast(getString(R.string.bm_none))
            return
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(4f), dp2px(8f), dp2px(4f), dp2px(8f))
        }
        scroll.addView(content)
        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun jump(bm: Bm) {
            pushPlace() // #101: переход по закладке — явный переход
            goTo(bm.chapter, bm.sentence)
            announcePosition()
            // Выбранная закладка сразу начинает читаться (если включено в
            // настройках, KEY_BM_PLAY) — как выбор главы в оглавлении.
            // Когда чтение шло, goTo уже продолжает с места закладки;
            // стояло на паузе — запускаем сами (requestStart подождёт
            // неготовый движок и начнёт, когда тот поднимется).
            if (!playing && prefs.getBoolean(KEY_BM_PLAY, true)) requestStart()
        }

        // Долгое нажатие на строку открывает «Действия с закладкой» (#103).
        // Объявлено хелдером до rebuild, сама функция — ниже (чтобы не было
        // перекрёстных ссылок на необъявленные локальные функции).
        var rowActions: (Bm) -> Unit = {}

        fun rebuild() {
            content.removeAllViews()
            list.forEach { bm ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val go = TextView(this).apply {
                    text = bm.label
                    textSize = 18f
                    setPadding(0, dp2px(10f), dp2px(8f), dp2px(10f))
                    isClickable = true
                    isFocusable = true
                    // Скринридеру добавляем подсказку о долгом нажатии — иначе
                    // жест «долгое нажатие» не очевиден (#103).
                    contentDescription = "${bm.label}. ${getString(R.string.bm_long_hint)}"
                    setOnClickListener {
                        jump(bm)
                        dialog?.dismiss()
                    }
                    setOnLongClickListener {
                        rowActions(bm)
                        true
                    }
                }
                row.addView(go, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val del = TextView(this).apply {
                    text = getString(R.string.bm_remove)
                    // Свой текст — короткое «Убрать», но скринридеру говорим, какую
                    // именно закладку уберёт действие, иначе в списке их не различить.
                    contentDescription = getString(R.string.bm_remove_cd, bm.label)
                    textSize = 16f
                    setTextColor(0xFFEF9A9A.toInt())
                    setPadding(dp2px(10f), dp2px(10f), dp2px(2f), dp2px(10f))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        list.removeAll { it.chapter == bm.chapter && it.sentence == bm.sentence }
                        saveBookmarks(list)
                        toast(getString(R.string.bm_removed))
                        Vibra.confirm(this@MainActivity)
                        if (list.isEmpty()) dialog?.dismiss()
                        else rebuild()
                    }
                }
                row.addView(del)
                content.addView(row)
            }
        }

        fun rename(bm: Bm) {
            val field = EditText(this).apply {
                setText(bm.label)
                setSelection(bm.label.length)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bm_edit_dialog)
                .setView(field)
                .setPositiveButton(R.string.bm_save) { _, _ ->
                    val name = field.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) return@setPositiveButton
                    val i = list.indexOfFirst { it.chapter == bm.chapter && it.sentence == bm.sentence }
                    if (i < 0) return@setPositiveButton
                    list[i] = list[i].copy(label = name)
                    saveBookmarks(list)
                    toast(getString(R.string.bm_updated))
                    rebuild()
                }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
        }

        fun share(bm: Bm) {
            val bk = book
            val t = sentenceTextAt(bm.chapter, bm.sentence)
            val bookName = listOfNotNull(
                bk?.title?.takeIf { it.isNotBlank() },
                bk?.author?.takeIf { it.isNotBlank() },
            ).joinToString(" — ")
            val sb = StringBuilder("“").append(if (t.isNotBlank()) t else bm.label).append("”")
            if (bookName.isNotBlank()) sb.append("\n\n").append(bookName)
            shareText(sb.toString())
        }

        fun actions(bm: Bm) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bm_actions_title)
                .setItems(arrayOf(getString(R.string.bm_share), getString(R.string.bm_edit))) { _, which ->
                    when (which) {
                        0 -> share(bm)
                        1 -> rename(bm)
                    }
                }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
        }
        rowActions = { actions(it) }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bm_list_title)
            .setView(scroll)
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        rebuild()
        dialog.show()
    }

    // ---------------- #98/#99: звонки и наушники ----------------

    private fun afterCallContinue(): Boolean =
        prefs.getString(KEY_AFTER_CALL, AFTER_CALL_STOP) == AFTER_CALL_CONTINUE

    private fun hasPhonePerm(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private val callListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handler.post { handleCallState(state) }
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!inCall) {
                    inCall = true
                    // Читало ли до звонка? Смотрим и текущий флаг, и недавний
                    // разрыв по потере фокуса (CALL_STATE может прийти чуть позже
                    // самой потери) — чтобы продолжить после НАСТОЯЩЕГО звонка,
                    // а не после уведомления/TalkBack.
                    val now = SystemClock.elapsedRealtime()
                    wasReadingAtCallStart = playing ||
                        (lastTransientPauseAt != 0L && now - lastTransientPauseAt < 3000)
                    Diag.log(this, "call", "звонок начался (читало=$wasReadingAtCallStart)")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall) {
                    inCall = false
                    Diag.log(this, "call", "звонок закончился")
                    afterCallEnded()
                }
            }
        }
    }

    /** Конец настоящего звонка (#98). Если до звонка шло чтение — отматываем
     *  назад на выбранное число предложений и продолжаем. Уведомления и
     *  озвучка TalkBack сюда не попадают: у них нет событий телефонии. */
    private fun afterCallEnded() {
        val resume = wasReadingAtCallStart
        wasReadingAtCallStart = false
        if (!resume || book == null || playing) return
        val n = when (prefs.getString(KEY_AFTER_CALL_REWIND, AFTER_CALL_REWIND_5)) {
            AFTER_CALL_REWIND_NONE -> 0
            AFTER_CALL_REWIND_2 -> 2
            AFTER_CALL_REWIND_5 -> 5
            AFTER_CALL_REWIND_10 -> 10
            else -> 5
        }
        if (n > 0) moveBySentence(-n)
        binding.sentenceList.announceForAccessibility(getString(R.string.after_call_continue_announce))
        requestStart()
    }

    private fun registerPhoneListener() {
        if (!afterCallContinue() || !hasPhonePerm()) return
        if (phoneListening) return
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        phoneListening = true
        @Suppress("DEPRECATION")
        tm.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterPhoneListener() {
        if (!phoneListening) return
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        tm?.listen(callListener, PhoneStateListener.LISTEN_NONE)
        phoneListening = false
        inCall = false
        wasReadingAtCallStart = false
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered || !prefs.getBoolean(KEY_PAUSE_HEADSET, true)) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(noisyReceiver, filter)
            }
            noisyRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    // ---------------- #101: «⋮ Ещё» — вернуться на прежнее место ----------------

    /** Запомнить текущее место перед явным переходом (глава по кнопке/жесту,
     *  оглавление, поиск, закладка, перемотка). На обычном последовательном
     *  чтении история не копится. Хранится ~10 последних, живёт только пока
     *  открыта эта книга. */
    private fun pushPlace() {
        if (!prefs.getBoolean(KEY_UI_MORE, true)) return
        if (book == null) return
        val top = backStack.peekLast()
        if (top != null && top.chapter == chapterIdx && top.sentence == sentenceIdx) return
        backStack.addLast(Place(chapterIdx, sentenceIdx))
        while (backStack.size > 10) backStack.removeFirst()
    }

    private fun showMoreDialog() {
        if (book == null) return
        MaterialAlertDialogBuilder(this)
            // msg1687: заголовок «Действия» убран — звучал пунктом, но не нажимался.
            .setItems(arrayOf(
                getString(R.string.go_library),      // msg1176/1179: Библиотека из шапки → «⋮»
                getString(R.string.settings_btn),    // msg1176: Настройки из шапки → «⋮»
                getString(R.string.reader_action_back),
                getString(R.string.quotes_title),   // 0.3.44 (msg1092): Цитаты из читалки
                getString(R.string.voice_settings), // 0.3.44 (msg1104): дубль «Голос чтения» — доступен,
                                                    // даже когда кнопку на экране скрыли конструктором.
                getString(R.string.app_exit),       // msg1278: Выход из приложения — последним.
            )) { _, which ->
                when (which) {
                    0 -> startLibrary()
                    1 -> startSettingsTab()
                    2 -> goBackPlace()
                    3 -> startActivity(Intent(this, QuotesActivity::class.java))
                    4 -> showVoiceDialog()
                    5 -> exitApp()
                }
            }
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    /** msg1278: «Выход из приложения». Если звучит чтение — гасим (сохраняет
     *  место и снимает фокус). finishAffinity закрывает и библиотеку под нами;
     *  в onDestroy уже штатно гасятся MediaSessionService и player. */
    private fun exitApp() {
        if (playing) pausePlayback(keepFocus = false)
        savePosition()
        // msg1770: TabNav.exitApp — finishAffinity + гасит процесс. Иначе процесс
        // жив, повторный запуск «возвращается» в живой сеанс, и холодный старт
        // в последней книге не наступает.
        TabNav.exitApp(this)
    }

    private fun goBackPlace() {
        if (book == null) return
        val p = backStack.pollLast()
        if (p == null) {
            binding.sentenceList.announceForAccessibility(getString(R.string.reader_back_none))
            return
        }
        goTo(p.chapter, p.sentence)
        val where = binding.tvPosition.text?.toString().orEmpty()
        binding.sentenceList.announceForAccessibility(
            getString(R.string.reader_back_done, if (where.isBlank()) "" else where)
        )
    }

    // ---------------- #102: свой голос и скорость для книги ----------------

    private fun currentBookRecord(): BookRecord? {
        val u = currentUri ?: return null
        return BookStore.byUri(this, u)
    }

    /** Сохранить запомненное для книги (движок/голос/скорость) в её запись и
     *  обновить локальные зеркала. null в поле — значит «своего нет». */
    private fun savePerBook(engine: String?, voice: String?, speed: Float?) {
        val rec = currentBookRecord() ?: return
        BookStore.upsert(this, rec.copy(voiceEngine = engine, voice = voice, voiceSpeed = speed))
        perBookEngine = engine
        perBookVoice = voice
        perBookSpeed = speed
    }

    /** При открытии книги: применить запомненный голос/скорость (#102).
     *  Каскад: своё у книги → глобальное → системное. Скорость — всегда
     *  числом, если запомнена. Голос/движок ждут готовности синтеза. */
    private fun applyBookVoiceAtOpen() {
        val rec = currentBookRecord() ?: return
        perBookEngine = rec.voiceEngine
        perBookVoice = rec.voice
        perBookSpeed = rec.voiceSpeed
        if (perBookSpeed != null && perBookSpeed!! > 0f) {
            player.speed = perBookSpeed!!
            refreshSpeedValue()
            updateStats()
        }
        if (perBookEngine != null || perBookVoice != null) waitApplyBookVoice(0)
    }

    private fun waitApplyBookVoice(attempt: Int) {
        val eng = perBookEngine
        val vce = perBookVoice
        if (book == null) return
        if (!player.isReady) {
            if (attempt > 40) return // ~8с — движок так и не поднялся
            handler.postDelayed({ waitApplyBookVoice(attempt + 1) }, 200)
            return
        }
        if (eng != null && eng != player.enginePackage) {
            player.setEngine(eng) { ok ->
                handler.post {
                    if (ok) {
                        if (vce != null) {
                            player.selectVoice(vce)
                            voiceName = vce
                        }
                        announceIfBookVoiceMissing(vce)
                        refreshSpeedValue()
                    } else {
                        // Движок книги недоступен — читаем обычным. Запись не
                        // удаляем: пользователь увидит/услышит, что голос пропал.
                        player.setEngine(null) { _ ->
                            handler.post {
                                if (vce != null) announceIfBookVoiceMissing(vce)
                                refreshSpeedValue()
                            }
                        }
                    }
                }
            }
        } else {
            if (vce != null && vce != voiceName) {
                player.selectVoice(vce)
                voiceName = vce
            }
            announceIfBookVoiceMissing(vce)
            refreshSpeedValue()
        }
    }

    /** Запомненный для книги голос не найден среди голосов движка — один раз
     *  сказать об этом. Запись не трогаем. */
    private fun announceIfBookVoiceMissing(vce: String?) {
        if (vce == null || voiceMissingAnnounced || !player.isReady) return
        if (player.voices.none { it.name == vce }) {
            voiceMissingAnnounced = true
            binding.sentenceList.announceForAccessibility(getString(R.string.voice_book_missing))
        }
    }

    /** Диалог «Голос и речь» закрыт с включённой галочкой — зафиксировать
     *  текущие движок/голос/скорость как свои для этой книги. */
    private fun persistPerBookProfile() {
        if (book == null) return
        if (currentBookRecord() == null) return
        val vce = voiceName?.takeIf { player.isReady && player.voices.any { v -> v.name == it } }
        savePerBook(player.enginePackage, vce, player.speed)
    }

    /** Галочку «Запомнить для этой книги» сняли — свой голос забываем и
     *  возвращаемся к глобальным настройкам (включая системный движок, если
     *  глобально движок не выбран). [onDone] зовётся, когда движок переключён. */
    private fun clearBookVoice(onDone: (() -> Unit)? = null) {
        savePerBook(null, null, null)
        player.speed = prefs.getFloat(KEY_SPEED, 1f)
        refreshSpeedValue()
        val gEngine = prefs.getString(KEY_ENGINE, null)
        val gVoice = prefs.getString(KEY_VOICE, null)
        if (player.enginePackage != gEngine) {
            player.setEngine(gEngine) { ok ->
                handler.post {
                    if (ok && gVoice != null) {
                        player.selectVoice(gVoice)
                        voiceName = gVoice
                    }
                    onDone?.invoke()
                }
            }
        } else {
            if (gVoice != null && gVoice != voiceName) {
                player.selectVoice(gVoice)
                voiceName = gVoice
            }
            onDone?.invoke()
        }
    }

    // ---------------- #105/#106: выделение фрагмента ----------------

    /** Подсветить начало выделения, если оно в текущей главе (список читалки
     *  показывает только одну главу). */
    private fun refreshSelectionMarkers() {
        val a = selAnchor
        if (a != null && a.chapter == chapterIdx) adapter.setSelectionAnchor(a.sentence)
        else adapter.setSelectionAnchor(null)
    }

    /** Долгое нажатие на предложении: первое удержание отмечает начало
     *  фрагмента, второе (в любом месте книги) — открывает окно действий. */
    private fun onSentenceLongPressed(pos: Int) {
        val bk = book ?: return
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: return
        if (cur.isEmpty()) return
        if (playing) pausePlayback(keepFocus = true)
        val here = Place(chapterIdx, pos.coerceIn(0, cur.lastIndex))
        val a = selAnchor
        if (a == null) {
            selAnchor = here
            adapter.setSelectionAnchor(pos.coerceIn(0, cur.lastIndex))
            binding.sentenceList.announceForAccessibility(getString(R.string.sel_anchor_start))
        } else {
            showSelectionWindow(a, here)
        }
    }

    private fun placeGlobal(p: Place): Int =
        (chapterStart.getOrNull(p.chapter) ?: 0) + p.sentence

    private fun finishSelection() {
        selAnchor = null
        adapter.clearSelection()
    }

    private fun buildSelectionText(start: Place, end: Place): String {
        val bk = book ?: return ""
        val sb = StringBuilder()
        for (ch in start.chapter..end.chapter) {
            val cur = bk.chapters.getOrNull(ch)?.sentences ?: continue
            if (cur.isEmpty()) continue
            val from = if (ch == start.chapter) start.sentence.coerceIn(0, cur.lastIndex) else 0
            val to = if (ch == end.chapter) end.sentence.coerceIn(0, cur.lastIndex) else cur.lastIndex
            for (s in from..to) {
                val t = cur[s].text.replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
        }
        return sb.toString()
    }

    private fun selectionCount(start: Place, end: Place): Int {
        val bk = book ?: return 0
        var n = 0
        for (ch in start.chapter..end.chapter) {
            val cur = bk.chapters.getOrNull(ch)?.sentences ?: continue
            if (cur.isEmpty()) continue
            val from = if (ch == start.chapter) start.sentence else 0
            val to = if (ch == end.chapter) end.sentence else cur.lastIndex
            n += (to - from + 1).coerceIn(0, cur.size)
        }
        return n
    }

    private fun showSelectionWindow(a: Place, b: Place) {
        val start: Place
        val end: Place
        if (placeGlobal(a) <= placeGlobal(b)) {
            start = a
            end = b
        } else {
            start = b
            end = a
        }
        val count = selectionCount(start, end)
        val text = buildSelectionText(start, end)
        if (count == 0 || text.isBlank()) {
            finishSelection()
            binding.sentenceList.announceForAccessibility(getString(R.string.sel_empty))
            return
        }
        // Подсветить диапазон, если он пересекается с видимой главой.
        if (chapterIdx in start.chapter..end.chapter) {
            val from = if (chapterIdx == start.chapter) start.sentence else 0
            val to = if (chapterIdx == end.chapter) end.sentence else Int.MAX_VALUE
            adapter.setSelectionRange(from, to)
        } else {
            adapter.setSelectionRange(null, null)
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sel_title, count))
            .setItems(arrayOf(
                getString(R.string.sel_read),
                getString(R.string.sel_copy),
                getString(R.string.sel_share),
                getString(R.string.sel_quote),
                getString(R.string.sel_cancel),
            )) { _, which ->
                when (which) {
                    0 -> startRangeRead(start, end)
                    1 -> copySelection(text)
                    2 -> shareSelection(text)
                    3 -> addSelectionToQuotes(start, text)
                    else -> {}
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        dlg.setOnDismissListener { finishSelection() }
        dlg.show()
    }

    /** #106: «Прочитать выделенное» — читаем ровно отмеченный кусок и на его
     *  конце останавливаемся (дальше книгу не продолжаем). */
    private fun startRangeRead(start: Place, end: Place) {
        if (book == null) return
        if (playing) pausePlayback(keepFocus = true)
        goTo(start.chapter, start.sentence) // goTo сбрасывает старый rangeEnd
        rangeEnd = end
        requestStart()
    }

    private fun copySelection(text: String) {
        runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bookvoice", text))
        }
        toast(getString(R.string.sel_copied))
    }

    private fun shareSelection(text: String) {
        val bk = book
        val bookName = listOfNotNull(
            bk?.title?.takeIf { it.isNotBlank() },
            bk?.author?.takeIf { it.isNotBlank() },
        ).joinToString(" — ")
        val sb = StringBuilder("“").append(text).append("”")
        if (bookName.isNotBlank()) sb.append("\n\n").append(bookName)
        shareText(sb.toString())
    }

    private fun addSelectionToQuotes(start: Place, text: String) {
        val bk = book ?: return
        val u = currentUri ?: return
        QuoteStore.upsert(this, Quote(
            id = UUID.randomUUID().toString(),
            uri = u,
            bookTitle = bk.title?.takeIf { it.isNotBlank() } ?: currentName ?: "?",
            author = bk.author?.takeIf { it.isNotBlank() },
            chapter = start.chapter,
            sentence = start.sentence,
            text = text.trim(),
            addedAt = System.currentTimeMillis(),
        ))
        toast(getString(R.string.sel_quote_saved))
        Vibra.confirm(this)
    }

    /** Общий шеринг текста (закладка #103, выделение #105). */
    private fun shareText(body: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.sel_share)))
        } catch (_: Exception) {
            toast("Поделиться не получилось")
        }
    }

    /** Текст предложения (ch, s), нормализованный (без лишних пробелов). */
    private fun sentenceTextAt(ch: Int, s: Int): String {
        val bk = book ?: return ""
        val cur = bk.chapters.getOrNull(ch)?.sentences ?: return ""
        val t = cur.getOrNull(s)?.text ?: return ""
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // ---------- Диалог «Голос чтения»: ползунки + движок → голоса (#1099/#1102/#1141) ----------

    /** Ползунок скорости/тона в диалогах ридера — тот же диапазон 0.5..2.0 с шагом
     *  0.1 и та же озвучка (stateDescription с SDK 30), что у ползунков
     *  Настройки → «Голос» (SettingsActivity.addRateSlider). Встраивается в переданный
     *  контейнер; [apply] сам решает, куда писать значение (плеер/настройки/запись
     *  книги) — для скорости вызывающий учитывает галочку «Запомнить для книги» (#102). */
    private fun addRateSliderTo(
        container: LinearLayout,
        labelRes: Int,
        cd: String,
        start: Float,
        apply: (Float) -> Unit,
    ) {
        val min = 0.5f
        val step = 0.1f
        val ticks = Math.round((2.0f - min) / step).toInt() // 0.5..2.0, шаг 0.1 → 15
        fun valueOf(p: Int): Float = min + p * step
        fun progressOf(v: Float): Int = Math.round((v - min) / step).coerceIn(0, ticks)
        fun rateLabel(v: Float): String = String.format(java.util.Locale.ROOT, "%.1f", v)

        val startP = progressOf(start)
        val startV = valueOf(startP)
        val label = TextView(this).apply {
            text = getString(labelRes, rateLabel(startV))
            textSize = 17f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(0, 0, 0, dp2px(2f))
        }
        container.addView(label)

        val seek = SeekBar(this).apply {
            max = ticks
            progress = startP
            contentDescription = cd
        }
        fun announce(v: Float) {
            val txt = rateLabel(v)
            if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = txt
            else seek.contentDescription = "$cd, $txt"
        }
        announce(startV)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueOf(progress)
                apply(v)
                announce(v)
                label.text = getString(labelRes, rateLabel(v))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(seek, sliderLp())
    }

    /** Ползунок громкости чтения (0..100%) в диалогах ридера. Хранится Float 0..1
     *  в общем KEY_VOLUME — единый с Настройками → «Голос» (SettingsActivity.addVolumeSlider). */
    private fun addVolumeSliderTo(container: LinearLayout, apply: (Float) -> Unit) {
        val startP = Math.round(prefs.getFloat(KEY_VOLUME, 1f) * 100).coerceIn(0, 100)
        val label = TextView(this).apply {
            text = getString(R.string.volume_value, startP)
            textSize = 17f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(0, 0, 0, dp2px(2f))
        }
        container.addView(label)

        val cd = getString(R.string.volume_cd)
        val seek = SeekBar(this).apply {
            max = 100
            progress = startP
            contentDescription = cd
        }
        fun announce(p: Int) {
            val txt = "$p%"
            if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = txt
            else seek.contentDescription = "$cd, $txt"
        }
        announce(startP)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                apply(v)
                announce(progress)
                label.text = getString(R.string.volume_value, progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(seek, sliderLp())
    }

    /** Отступ ползунка в диалоге — как bottomMargin у ползунков настроек. */
    private fun sliderLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp2px(6f) }

    /** «Голос чтения» в читалке (кнопка внизу и пункт меню «⋮», #1104): сначала
     *  ползунки скорости/тона/громкости — как Настройки → «Голос» (#1141: после
     *  #1102 их оставили только в настройках, вернул в окно читалки), затем галочка
     *  «Запомнить для этой книги» (#102), когда книга открыта, и внизу двухшаговый
     *  выбор движок → голоса, как кнопка «Голос и движок» в Настройках. */
    private fun showVoiceDialog() {
        if (!player.isReady) {
            toast("Движок синтеза речи ещё не готов, попробуйте через секунду")
            return
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(4f), dp2px(8f), dp2px(4f), dp2px(8f))
        }
        scroll.addView(body)

        // #102: «Запомнить для этой книги». Видна, когда открыта книга; включена —
        // если у книги уже есть запомненный голос/скорость.
        val hasBook = book != null
        val rememberCb = CheckBox(this).apply {
            text = getString(R.string.voice_remember_book)
            contentDescription = getString(R.string.voice_remember_book_cd)
            textSize = 16f
            visibility = if (hasBook) View.VISIBLE else View.GONE
            isChecked = hasBook && currentBookRecord()?.let {
                it.voiceEngine != null || it.voice != null || it.voiceSpeed != null
            } == true
        }

        // Шаг 2 → к шагу 1: «← К выбору движка».
        val btnBack = Button(this).apply {
            text = getString(R.string.engine_back)
            visibility = View.GONE
        }
        val engineTitle = TextView(this).apply {
            text = getString(R.string.engine_step)
            setPadding(0, 0, 0, dp2px(4f))
        }
        val engineGroup = RadioGroup(this)

        // Шаг 2: голоса выбранного движка.
        val voiceBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        voiceBlock.addView(TextView(this).apply {
            text = getString(R.string.voice_step)
            setPadding(0, 0, 0, dp2px(4f))
        })
        val voiceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voiceBlock.addView(voiceContainer)

        fun showStep(step: Int) {
            val second = step == 2
            btnBack.visibility = if (second) View.VISIBLE else View.GONE
            engineTitle.visibility = if (second) View.GONE else View.VISIBLE
            engineGroup.visibility = if (second) View.GONE else View.VISIBLE
            voiceBlock.visibility = if (second) View.VISIBLE else View.GONE
        }

        fun revealVoices() {
            populateVoiceList(voiceContainer, rememberCb)
            showStep(2)
        }

        // Выбор движка — как в Настройках: выбрал (даже текущий) → список голосов.
        fun onEngineChosen(pkg: String) {
            if (pkg == player.enginePackage) {
                revealVoices()
                return
            }
            // #102: с включённой галочкой глобальный движок не трогаем — выбранный
            // уйдёт в запись книги при закрытии окна (persistPerBookProfile).
            if (!rememberCb.isChecked) prefs.edit().putString(KEY_ENGINE, pkg).apply()
            voiceContainer.removeAllViews()
            voiceContainer.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.engine_loading)
            })
            player.setEngine(pkg) { ok ->
                handler.post {
                    if (ok) {
                        revealVoices()
                    } else {
                        if (!rememberCb.isChecked) prefs.edit().remove(KEY_ENGINE).apply()
                        player.setEngine(null) { _ ->
                            handler.post { revealVoices() }
                        }
                        toast("Движок не запустился, вернул системный")
                    }
                }
            }
        }

        // Ползунки — как Настройки → «Голос»: скорость, тон, громкость.
        // Скорость: при галочке «Запомнить для этой книги» глобальную не трогаем —
        // книге зафиксируем текущую при закрытии окна (persistPerBookProfile), чтобы
        // не писать library.json на каждый тик слайдера. Без галочки — как в настройках.
        addRateSliderTo(body, R.string.speed_value, "Скорость", currentSpeed()) { v ->
            if (hasBook && rememberCb.isChecked) {
                if (player.isReady) player.speed = v
            } else {
                prefs.edit().putFloat(KEY_SPEED, v).apply()
                if (player.isReady) player.speed = v
            }
            refreshSpeedValue()
            updateStats()
        }
        addRateSliderTo(body, R.string.tone_value, "Тон", prefs.getFloat(KEY_PITCH, 1f)) { v ->
            prefs.edit().putFloat(KEY_PITCH, v).apply()
            if (player.isReady) player.pitch = v
        }
        addVolumeSliderTo(body) { v ->
            prefs.edit().putFloat(KEY_VOLUME, v).apply()
            if (player.isReady) player.volume = v
        }

        // Галочка «Запомнить для этой книги».
        val rememberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp2px(4f))
        }
        rememberRow.addView(rememberCb)
        body.addView(rememberRow)
        rememberCb.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                persistPerBookProfile()
                toast(getString(R.string.voice_book_saved))
            } else {
                // Сняли галочку: свой голос книги забываем, движок вернётся на
                // глобальный — после переключения обновляем список голосов.
                clearBookVoice {
                    revealVoices()
                    toast(getString(R.string.voice_book_cleared))
                }
            }
        }

        body.addView(btnBack)
        body.addView(engineTitle)
        val engines = player.engines
        val defaultEngine = player.defaultEngine
        val currentEngine = player.enginePackage ?: defaultEngine
        if (engines.isEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.no_engines)
                setPadding(0, 0, 0, dp2px(4f))
            })
        } else {
            for ((pkg, engineLabel) in engines) {
                val rb = RadioButton(this)
                rb.text = if (pkg == defaultEngine) "$engineLabel (системный)" else engineLabel
                rb.tag = pkg
                rb.isChecked = pkg == currentEngine
                rb.setOnClickListener { onEngineChosen(rb.tag as String) }
                engineGroup.addView(rb)
            }
            body.addView(engineGroup)
        }
        body.addView(voiceBlock)

        btnBack.setOnClickListener { showStep(1) }
        showStep(1)

        val voiceDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.voice_engine_value)
            .setView(scroll)
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        // #102: закрыли окно с включённой галочкой — фиксируем профиль книги
        // (движок/голос/текущую скорость). Скорость мог изменить слайдер в этом
        // окне, поэтому обновляем подпись и статистику под новой скоростью книги.
        voiceDialog.setOnDismissListener {
            if (hasBook && rememberCb.isChecked) {
                persistPerBookProfile()
                refreshSpeedValue()
                updateStats()
            }
        }
        voiceDialog.show()
    }

    private fun populateVoiceList(container: LinearLayout, rememberCb: CheckBox? = null) {
        container.removeAllViews()
        val voices = player.voices
        if (voices.isEmpty()) {
            container.addView(TextView(this).apply { text = "В этом движке нет голосов" })
            return
        }
        val group = RadioGroup(this)
        container.addView(group)
        for (v in voices) {
            val name = v.name
            val shown = if (v.isNetworkConnectionRequired) "${v.name} (сеть)" else v.name
            val rb = RadioButton(this)
            rb.text = shown
            rb.tag = name
            rb.isChecked = name == voiceName
            rb.setOnClickListener {
                val sel = rb.tag as String
                voiceName = sel
                // #102: при галочке «Запомнить для этой книги» глобальный голос
                // не трогаем — выбранный уйдёт в запись книги при закрытии.
                if (rememberCb?.isChecked != true) {
                    prefs.edit().putString(KEY_VOICE, sel).apply()
                }
                player.selectVoice(sel)
            }
            group.addView(rb)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp2px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        // Живой ридер, если он открыт — экран настроек через него меняет
        // скорость/голос сразу, а не «на следующий запуск».
        @Volatile
        internal var active: MainActivity? = null

        /** Короткий снимок живой читалки для строки о падении (пишет
         *  BookVoiceApp в diag.log): какая книга открыта, читает ли, скорость.
         *  Если ридер закрыт или поля ещё не готовы — сокращаемся. */
        internal fun liveSnapshot(): String {
            val a = active ?: return "ридер закрыт"
            return runCatching {
                val t = a.book?.title?.takeIf { it.isNotBlank() } ?: "без названия"
                val state = if (a.playing) "читает" else "на паузе"
                "книга «$t», глава ${a.chapterIdx + 1}, $state, скорость ${a.player.speed}"
            }.getOrDefault("книга открыта")
        }

        internal const val EXTRA_URI = "uri"
        internal const val EXTRA_CHAPTER = "chapter"
        internal const val EXTRA_SENTENCE = "sentence"
        /** Книга открыта из каталога (CatalogActivity): «назад» возвращает в каталог. */
        internal const val EXTRA_FROM_CATALOG = "from_catalog"
        /** msg1245: интент несёт КОНКРЕТНОЕ место (цитата из QuotesActivity) —
         *  открыть там, а не с сохранённого места записи. */
        internal const val EXTRA_EXPLICIT_PLACE = "explicit_place"
        /** Запрос системного голосового ввода (поиск по книге). */
        internal const val REQ_VOICE_SEARCH = 2001

        internal const val KEY_URI = "uri"
        internal const val KEY_CHAPTER = "chapter"
        internal const val KEY_SENTENCE = "sentence"
        internal const val KEY_SPEED = "speed"
        internal const val KEY_PITCH = "pitch"
        internal const val KEY_VOLUME = "volume"
        internal const val KEY_VOICE = "voice"
        internal const val KEY_ENGINE = "engine"
        internal const val KEY_AUTO = "auto"
        internal const val KEY_AUTO_START = "auto_start"
        internal const val KEY_AUTO_RESUME = "auto_resume"
        internal const val KEY_SAY_CHAPTER_START = "say_chapter_start"
        internal const val KEY_STEP = "step"
        internal const val STEP_SENTENCE = "sentence"
        internal const val STEP_CHAPTER = "chapter"
        // «Кнопки глав шагают» (0.3.37): по каким уровням разметки FB2 ходит
        // «Предыдущая/Следующая глава». Default CH_NAV_ALL — как раньше.
        internal const val KEY_CH_NAV = "ch_nav"
        internal const val CH_NAV_MAJOR = "major"
        internal const val CH_NAV_CHAPTERS = "chapters"
        internal const val CH_NAV_ALL = "all"
        internal const val KEY_SCROLL = "scroll_to_current"
        internal const val KEY_TAP_TO_PLAY = "tap_to_play"
        internal const val KEY_TOC_PLAY = "toc_play"
        internal const val KEY_BM_PLAY = "bm_play"
        internal const val KEY_SEARCH_PLAY = "search_play"
        internal const val KEY_SHOW_TITLE_PAGE = "show_title_page"
        internal const val KEY_START = "start"

        // Конструктор экрана чтения (#58): какие элементы читалки показывать.
        internal const val KEY_UI_SENT = "ui_sentence_arrows"
        internal const val KEY_UI_PLAY = "ui_play_pause"
        internal const val KEY_UI_CHAPTERS = "ui_chapter_arrows"
        internal const val KEY_UI_SLIDER = "ui_slider"
        internal const val KEY_UI_POSITION = "ui_position"
        internal const val KEY_UI_STATS = "ui_stats"
        internal const val KEY_UI_TOC = "ui_toc"
        internal const val KEY_UI_BOOKMARK = "ui_bookmark"
        internal const val KEY_UI_VOICE = "ui_voice"
        internal const val KEY_UI_STATUS = "ui_status"
        internal const val KEY_UI_SPEED = "ui_speed_buttons"
        internal const val KEY_UI_SEARCH = "ui_search_button"

        // #101: кнопка «⋮ Ещё» в читалке — открывает меню «Действия». Входит
        // в конструктор ui_*: выключена — кнопки нет и история переходов не копится.
        internal const val KEY_UI_MORE = "ui_more"

        // #99: останавливать чтение, когда отключаются наушники
        // (ACTION_AUDIO_BECOMING_NOISY). Default ВКЛ — так сейчас себя ведёт
        // большинство плееров, и это осознанное изменение.
        internal const val KEY_PAUSE_HEADSET = "pause_when_headphones_out"

        // #98: что делать после настоящего звонка — «Остановиться» (как сейчас)
        // или «Продолжить чтение»; при продолжении — откат на N предложений.
        internal const val KEY_AFTER_CALL = "after_call"
        internal const val AFTER_CALL_STOP = "stop"
        internal const val AFTER_CALL_CONTINUE = "continue"
        internal const val KEY_AFTER_CALL_REWIND = "after_call_rewind"
        internal const val AFTER_CALL_REWIND_NONE = "none"
        internal const val AFTER_CALL_REWIND_2 = "2"
        internal const val AFTER_CALL_REWIND_5 = "5"
        internal const val AFTER_CALL_REWIND_10 = "10"

        internal const val START_LIBRARY = "library"
        internal const val START_LAST = "last"

        // Куда «Назад» выходит из открытой книги (#43).
        internal const val KEY_EXIT = "exit_reader"
        internal const val EXIT_DESKTOP = "desktop"
        internal const val EXIT_LIBRARY = "library"

        // Назначаемые свайпы влево/вправо (#77). Значение pref — id действия;
        // палитра [GESTURE_ACTIONS] общая для диспетчера в ридере и экрана
        // «Жесты» в настройках.
        internal const val KEY_GESTURE_LEFT = "gesture_left"
        internal const val KEY_GESTURE_RIGHT = "gesture_right"
        internal const val G_PREV_SENT = "prev_sent"
        internal const val G_NEXT_SENT = "next_sent"
        internal const val G_PREV_CH = "prev_ch"
        internal const val G_NEXT_CH = "next_ch"
        internal const val G_PLAY = "play"
        internal const val G_PAUSE = "pause"
        internal const val G_REPEAT = "repeat"
        internal const val G_POSITION = "position"
        internal const val G_SPEED_UP = "speed_up"
        internal const val G_SPEED_DOWN = "speed_down"
        internal const val G_TOC = "toc"
        internal const val G_BOOKMARKS = "bookmarks"
        internal const val G_ADD_BM = "add_bm"

        /** Палитра действий для свайпов: id → строка-название для экрана «Жесты». */
        internal val GESTURE_ACTIONS: List<Pair<String, Int>> = listOf(
            G_NEXT_CH to R.string.g_action_next_ch,
            G_PREV_CH to R.string.g_action_prev_ch,
            G_NEXT_SENT to R.string.g_action_next_sent,
            G_PREV_SENT to R.string.g_action_prev_sent,
            G_PLAY to R.string.g_action_play,
            G_PAUSE to R.string.g_action_pause,
            G_REPEAT to R.string.g_action_repeat,
            G_POSITION to R.string.g_action_position,
            G_SPEED_UP to R.string.g_action_speed_up,
            G_SPEED_DOWN to R.string.g_action_speed_down,
            G_TOC to R.string.g_action_toc,
            G_BOOKMARKS to R.string.g_action_bookmarks,
            G_ADD_BM to R.string.g_action_add_bm,
        )
    }
}
