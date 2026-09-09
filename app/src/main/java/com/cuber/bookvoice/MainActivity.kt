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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cuber.bookvoice.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), ReaderEngine.Host {

    private lateinit var binding: ActivityMainBinding

    /** Плеер живёт в движке (#38). Окно и экран настроек берут его через этот
     *  геттер — голос/скорость меняются на живой книге сразу. */
    internal val player: SpeechPlayer
        get() = ReaderEngine.player
            ?: throw IllegalStateException("ReaderEngine не подключён (attach в onCreate)")

    private lateinit var layoutManager: LinearLayoutManager

    // Состояние чтения живёт в движке (ReaderEngine, #38 шаг 1) — окно только
    // читает/пишет его через эти обёртки. Пока окно открыто, движок подключён
    // (attach в onCreate), поэтому обёртки всегда валидны.
    private var book: BookDocument?
        get() = ReaderEngine.book
        set(v) { ReaderEngine.book = v }
    private var chapterIdx: Int
        get() = ReaderEngine.chapterIdx
        set(v) { ReaderEngine.chapterIdx = v }
    private var sentenceIdx: Int
        get() = ReaderEngine.sentenceIdx
        set(v) { ReaderEngine.sentenceIdx = v }
    private var playing: Boolean
        get() = ReaderEngine.playing
        set(v) { ReaderEngine.playing = v }

    // Прогресс по всей книге — для слайдера перемотки и оценки времени чтения.
    // chapterStart[c] — номер первого предложения главы c в глобальной нумерации,
    // cumWords[i] — сколько слов в предложениях с номерами < i.
    private var chapterStart: IntArray
        get() = ReaderEngine.chapterStart
        set(v) { ReaderEngine.chapterStart = v }
    private var cumWords: LongArray
        get() = ReaderEngine.cumWords
        set(v) { ReaderEngine.cumWords = v }
    private var scrubbing = false
    private var voiceName: String?
        get() = ReaderEngine.voiceName
        set(v) { ReaderEngine.voiceName = v }

    // #102: свой голос/скорость книги — зеркало полей BookRecord открытой книги
    // (движок тоже держит их для применения при открытии).
    private var perBookEngine: String?
        get() = ReaderEngine.perBookEngine
        set(v) { ReaderEngine.perBookEngine = v }
    private var perBookVoice: String?
        get() = ReaderEngine.perBookVoice
        set(v) { ReaderEngine.perBookVoice = v }
    private var perBookSpeed: Float?
        get() = ReaderEngine.perBookSpeed
        set(v) { ReaderEngine.perBookSpeed = v }
    private var voiceMissingAnnounced: Boolean
        get() = ReaderEngine.voiceMissingAnnounced
        set(v) { ReaderEngine.voiceMissingAnnounced = v }

    // #101: история явных переходов для «Вернуться на предыдущее место».
    // Тип Place объявлен в ReaderEngine.kt — общий с движком.
    private val backStack = java.util.ArrayDeque<Place>()

    // #105/#106: выделение фрагмента. selAnchor — начало (первое удержание);
    // второе удержание открывает окно действий. rangeEnd — конец куска при
    // «Прочитать выделенное»: движок проверяет его в автопродолжении.
    private var selAnchor: Place? = null
    private var rangeEnd: Place?
        get() = ReaderEngine.rangeEnd
        set(v) { ReaderEngine.rangeEnd = v }

    // msg1137/1139: восстановленное место и флаги навигации — в движке
    // (guardResetToStart читает их и при чтении без окна).
    private var restoredPlace: Place?
        get() = ReaderEngine.restoredPlace
        set(v) { ReaderEngine.restoredPlace = v }
    private var userMoved: Boolean
        get() = ReaderEngine.userMoved
        set(v) { ReaderEngine.userMoved = v }
    private var openingWindow: Boolean
        get() = ReaderEngine.openingWindow
        set(v) { ReaderEngine.openingWindow = v }
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
    // #38: окно — экран движка. Что движок решает нарисовать (текущее
    // предложение, смена главы, кнопка play/pause, тост, озвучка) — приходит
    // сюда через [ReaderEngine.Host]. Медиа-команды с гарнитуры идут движку
    // напрямую (MediaSessionService.listener ставит attach), окну они не нужны.

    override fun onShowCurrent() = showCurrent()

    override fun onChapterLoaded() = loadChapter()

    override fun onPlayStateChanged() = updatePlayButton()

    override fun onMovedInChapter(s: Int) {
        adapter.setCurrent(s)
        scrollToSentence(s)
        updatePosition()
    }

    override fun onToast(msg: String) = toast(msg)

    override fun onAnnounce(text: String) {
        binding.sentenceList.announceForAccessibility(text)
    }

    override fun onSpeedUiRefresh() {
        refreshSpeedValue()
        updateStats()
    }

    private var currentUri: String?
        get() = ReaderEngine.currentUri
        set(v) { ReaderEngine.currentUri = v }
    private var currentName: String?
        get() = ReaderEngine.currentName
        set(v) { ReaderEngine.currentName = v }

    // Аудиофокус (#98/#99), состояние звонков и наушников целиком переехали в
    // движок (ReaderEngine): там живут haveAudioFocus, pausedByFocusLoss,
    // audioFocusListener, noisyReceiver и их регистрация.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.header(this)
        // Живой инстанс ридера: экран настроек достаёт через него плеер,
        // чтобы скорость/голос менялись сразу, без перезапуска книги.
        active = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // SDK 36: окно edge-to-edge — прижимаем корень к безопасной зоне, иначе
        // шапка читалки уедет под статус-бар, нижние кнопки — под жестовую зону.
        edgeToEdge(binding.root)

        layoutManager = LinearLayoutManager(this)
        binding.sentenceList.layoutManager = layoutManager
        binding.sentenceList.adapter = adapter
        binding.sentenceList.setHasFixedSize(true)

        installSwipe()

        // msg2403/2415: «Голос чтения» в нижнем ряду и «Свернуть» в шторке.
        // Открытая шторка держит нижние кнопки видимыми — скорость слышна на лету.
        binding.btnVoice.setOnClickListener { toggleVoicePanel() }
        binding.btnVoiceClose.setOnClickListener { hideVoicePanel() }
        // #54 (msg2643): кнопка ▶ в полноэкранной панели голоса — послушать
        // выбранный голос/скорость на тексте книги сразу, ничего не сворачивая.
        binding.btnVoiceTest.setOnClickListener { togglePlay() }
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
        // msg2695/2699: долгое нажатие «▶» — действие из настройки play_long.
        // Возвращаем true всегда (съедаем событие): даже в режиме «Выключено»
        // долгое нажатие не должно случайно запустить чтение.
        binding.btnPlayPause.setOnLongClickListener {
            onPlayPauseLongClick()
            true
        }
        binding.btnPrevSentence.setOnClickListener { stepMove(-1) }
        binding.btnNextSentence.setOnClickListener { stepMove(+1) }
        binding.btnPrevChapter.setOnClickListener { chapterNavMove(-1) }
        binding.btnNextChapter.setOnClickListener { chapterNavMove(+1) }
        // Кнопки скорости (#58): шаг ±0.1, меняют темп на лету.
        binding.btnSpeedDown.setOnClickListener { nudgeSpeed(-0.1f) }
        binding.btnSpeedUp.setOnClickListener { nudgeSpeed(+0.1f) }
        // msg3081+: кнопки-ячейки панели быстрого доступа. Клик — переключиться
        // на книгу ячейки; долгий клик — закрепить/снять закрепление книги.
        installQuickPanelHandlers()
        // msg2375: кнопка «Отметить дочитанной» с экрана убрана — действие живёт в
        // ⋮-меню читалки (showMoreDialog).

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

        // #38 шаг 2: если книга уже читается БЕЗ окна (мы вышли из неё, а голос
        // продолжил — см. ReaderEngine.windowGoneWhilePlaying), новое окно не
        // переразбирает файл и не сбрасывает позицию: оно рисуется вокруг живого
        // чтения движка и попадает туда, где книга сейчас звучит. Открывают
        // ДРУГУЮ книгу — гасим живую (close) и открываем как обычно.
        val liveUri = ReaderEngine.liveWindowlessUri()
        val requested = intent.getStringExtra(EXTRA_URI)
        if (liveUri != null && (requested == null || requested == liveUri)) {
            rejoinLiveReading()
        } else {
            // #38: «сердце» чтения — плеер, состояние, цикл, аудиофокус — живёт в
            // движке ReaderEngine. Окно лишь подключается к нему и рисует по его
            // событиям (см. ReaderEngine.Host). attach создаёт плеер и ставит
            // MediaSessionService.listener на команды движка.
            if (liveUri != null) {
                // Живую книгу бросаем ради другой: сначала фиксируем её место в
                // записи (вдали от окна позиция шла вперёд только в prefs).
                ReaderEngine.savePosition()
                ReaderEngine.close()
            }
            ReaderEngine.attach(this)
            ReaderEngine.host = this
            // Название книги из записи в шапку — до показа окна (msg1725/1736).
            restoreEngineAndVoice()
            restoreSession()
        }
        // Android 13+: медиа-уведомление в шторке требует разрешения.
        requestNotificationPermission()

        // msg2233/2239: на Android 16 (targetSdk 36) «назад» гонится в
        // predictive-back диспетчер; без явного callback системный default
        // может закрыть читалку сам, минуя onBackPressed (и тогда полка снизу
        // не получит suppress — авто-открытие вернёт «в книгу»). Тот же фикс,
        // что окнам-секциям (SectionActivity): явный OnBackPressedCallback.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Открыта шторка голоса — «назад» сначала закрывает её.
                if (voicePanelOpen()) {
                    hideVoicePanel()
                    return
                }
                // Открыта панель поиска — «назад» сначала закрывает её, а не
                // выходит из книги (системная кнопка и жест работают одинаково).
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
                    // «В список книг»: хост под читалкой показывает Библиотеку;
                    // suppress гасит авто-открытие последней книги.
                    startLibrary()
                } else {
                    // «На рабочий стол»: сворачиваем задачу (полка под читалкой).
                    moveTaskToBack(true)
                }
            }
        })
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
        // Там же могли сменить шаг кнопок «Пред.»/«След.» — переименовываем
        // их для скринридера под выбранный шаг (msg2471).
        updateSentenceButtonNames()
        // Там же могли сменить режим «Кнопки глав шагают» (по главам/разделам/
        // заголовкам) — имя «◀ Глава/Глава ▶» для скринридера следует режиму (msg2535).
        updateChapterButtonNames()
        refreshSpeedValue()
        // Карточка рождается только когда книга реально начала читаться
        // (startSpeakingCurrent) — msg1779: при просто открытой, но молчащей
        // книге её появление TalkBack озвучивает как «мусор». При возврате в
        // ридер добиваем сервис до актуального состояния, только если мы играли
        // (процесс мог быть пересоздан). Слушатель медиа-сессии уже стоит
        // движком (attach в onCreate).
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
        // #98/#99: слушатели живут, пока ридер на экране (вешает движок).
        ReaderEngine.onWindowStart()
    }

    override fun onStop() {
        super.onStop()
        ReaderEngine.onWindowStop()
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
        // #38 шаг 2: чтение без окна пишет место в prefs на КАЖДОМ прочитанном
        // предложении, а в запись книги — только при паузе/выходе/конце. Поэтому
        // для книги, которая открывалась/слушалась последней (KEY_URI совпадает),
        // место из prefs всегда свежее записи: у новой книги запись так и стоит
        // на (0,0), и перезапуск вернул бы её в начало. Для любой ДРУГОЙ книги
        // место по-прежнему из записи (msg1245).
        val sameAsLast = u == prefs.getString(KEY_URI, null)
        val ch: Int
        val s: Int
        if (explicit) {
            ch = intent.getIntExtra(EXTRA_CHAPTER, 0)
            s = intent.getIntExtra(EXTRA_SENTENCE, 0)
        } else if (sameAsLast) {
            ch = prefs.getInt(KEY_CHAPTER, 0)
            s = prefs.getInt(KEY_SENTENCE, 0)
            Diag.log(this, "activity", "место из prefs (последняя книга): глава $ch, предл. $s")
        } else if (rec != null) {
            ch = rec.chapter
            s = rec.sentence
            Diag.log(this, "activity", "место из записи: глава $ch, предл. $s")
        } else {
            ch = prefs.getInt(KEY_CHAPTER, 0)
            s = prefs.getInt(KEY_SENTENCE, 0)
        }
        // msg2093: книга открывается на сохранённом месте (не по явному переходу
        // из цитаты) — вооружаем откат при старте. Внутри openBook, после разбора.
        openBook(Uri.parse(u), ch, s, rewindOnOpen = !explicit)
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
        // displayTitle (msg2559): своё название из «Переименовать» важнее
        // метаданных, метаданные — важнее имени файла. Для записи оно есть
        // всегда, так что окно не остаётся с label приложения даже у книг без
        // встроенного названия (например PDF).
        val recAuthor = rec?.author
        if (rec != null) {
            val line = if (recAuthor.isNullOrBlank()) rec.displayTitle
            else "${rec.displayTitle} — $recAuthor"
            binding.tvHeader.text = line
            // msg1736/1739: название книги = window title окна читалки. Ставим ДО
            // показа окна — при появлении объявится книга, а не label приложения.
            setTitle(line)
            headerAnnouncedAtOpen = true
            Diag.log(this, "a11y", "имя окна читалки (setTitle) = «$line»")
        }
    }

    /** #38 шаг 2: книга уже читается в движке БЕЗ окна (мы вышли из неё, голос
     *  продолжил, см. windowGoneWhilePlaying). Новое окно рисуется вокруг живого
     *  состояния: файл не переразбираем, чтение не перезапускаем — голос звучит
     *  как звучал, а окно показывает место, где книга сейчас. Заголовок в шапке
     *  и имя окна — название живой книги, с первого кадра (msg1725/1736). */
    private fun rejoinLiveReading() {
        ReaderEngine.attach(this)
        ReaderEngine.host = this
        headerAnnouncedAtOpen = true
        val explicit = intent.getBooleanExtra(EXTRA_EXPLICIT_PLACE, false)
        val eCh = intent.getIntExtra(EXTRA_CHAPTER, 0)
        val eS = intent.getIntExtra(EXTRA_SENTENCE, 0)
        // Пока новое окно рисуется вокруг живой книги, любой goTo(0,0) — тот же
        // авто-сброс, что при холодном открытии (0.3.46, msg2064): перерисовка
        // шлёт переход к началу, и без guard он затирает живое место и гонит
        // чтение с первой главы. Держим guard на время отрисовки, как openBook.
        openingWindow = true
        refreshChrome()
        loadChapter()
        openingWindow = false
        // msg3081+: окно вернулось к живой книге — пересобираем ячейки панели,
        // как при обычном открытии (состав недавних мог устареть).
        rebuildQuickPanel()
        // Цитата (EXTRA_EXPLICIT_PLACE): намеренный переход на указанное место
        // живой книги — уже после guard, чтобы переход к (0,0) из цитаты прошёл.
        // goTo сам остановит/продолжит чтение по состоянию.
        if (explicit) goTo(eCh, eS)
        // Сервис мог прилечь, пока окна не было, — добиваем до живого состояния.
        if (playing) ensureMediaService(true)
        Diag.log(this, "activity", "rejoin: окно вернулось к живой книге «${book?.title}»")
    }

    private fun showEmpty() {
        adapter.submit(emptyList(), false)
        closeSearchPanel()
        refreshChrome()
    }

    /** Уйти на полку — дом приложения (редизайн msg1676+). Библиотека это
     *  КОРНЕВОЕ окно задачи; между ним и ридером может лежать окно Каталога
     *  (книга открыта из каталога) — уходим на корень CLEAR_TOP, чтобы снять
     *  всё, что поверх полки. Место сохранено (onPause). Если голос звучит —
     *  #38 шаг 2: он продолжает читать без окна, на полке (onDestroy зовёт
     *  windowGoneWhilePlaying); если молчит — чтение гаснет как раньше (close). */
    private fun startLibrary() {
        // Полка показывается не «с рабочего стола» — авто-открытие книги гасим.
        LibraryActivity.suppressNextAutoOpen = true
        startActivity(
            Intent(this, LibraryWindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    /** Уйти в «Настройки» — окно поверх (редизайн msg1676+). msg2264: ридер НЕ
     *  закрывается (раньше finish() снимал его, и под окном оказывалась полка —
     *  выход из Настроек вёл «в библиотеку»). Теперь книга остаётся под окном,
     *  как у «Цитат» из читалки (msg1767): закрытие Настроек возвращает в книгу.
     *  Полка под нами — только когда пришли из неё (там окно запускает полка,
     *  не этот метод). Смена скорости/голоса применяется к книге по возврату
     *  в onStart (applyReaderUi); на лету есть и «⋮ → Голос чтения». */
    private fun startSettingsTab() {
        startActivity(Intent(this, SettingsWindowActivity::class.java))
    }

    /** Книга открыта из каталога? Тогда «назад» возвращает в тот же экран
     *  каталога (он лежит под читалкой в стеке), а не в список библиотеки. */
    private val fromCatalog: Boolean
        get() = intent.getBooleanExtra(EXTRA_FROM_CATALOG, false)

    private fun openBook(uri: Uri, chapter: Int, sentence: Int, rewindOnOpen: Boolean = false) {
        // msg2679: новое открытие снимает неисполненное «Читать» прошлого раза.
        playWantedWhileLoading = false
        // msg2679: пока книга разбирается в фоне, кнопку «Читать» держим активной
        // (refreshChrome с book==null её бы выключил) — нажатие в это окно обязано
        // регистрироваться и вести к очереди старта, а не глотаться молча.
        bookLoading = true
        // msg2849: каждое новое открытие начинает с чистого флага долгой загрузки.
        longLoadFired = false
        binding.btnPlayPause.isEnabled = true
        // msg2685: книга открывается дольше ~2с — один раз сообщаем голосом и
        // лёгкой вибрацией, чтобы не казалось, что приложение зависло. Таймер
        // одноразовый: книга готова раньше — объявление не звучит (bookLoading
        // уже false); опцию можно выключить в Настройках → Чтение.
        if (prefs.getBoolean(KEY_LONG_LOAD_ANNOUNCE, true)) {
            handler.postDelayed({
                if (!isFinishing && bookLoading) {
                    Diag.log(this, "activity", "долгая загрузка (>2с) — объявляю (msg2685)")
                    binding.tvHeader.announceForAccessibility(getString(R.string.long_load_speech))
                    Vibra.confirm(this)
                    // msg2849: сработал блок «подождите» — загрузка точно была
                    // долгой; по готовности книги добавим звуковой «готово».
                    longLoadFired = true
                }
            }, 2000)
        }
        // msg1739: «Открываю…» убрано — скринридер читал тост при входе и перебивал
        // объявление названия книги (в FBReader при открытии нет «открытия», есть
        // название). Имя окна уже несёт название (restoreSession → setTitle).
        Thread {
            val t0 = System.currentTimeMillis()
            val doc = try {
                // msg3081+: тяжёлый разбор (большой PDF) кэшируем на диск. Первое
                // открытие — разбор, повторное — готовый текст из кэша (BookCache.get
                // сам проверяет, что файл книги не менялся).
                BookCache.get(this, uri) ?: readBook(uri)
            } catch (_: Exception) {
                null
            }
            // msg3081+: разбор занял долго и книга настоящая (есть текст) — кладём
            // разобранный текст в кэш, чтобы следующий раз открыть почти мгновенно.
            val elapsed = System.currentTimeMillis() - t0
            if (doc != null && BookCache.isSlow(elapsed) && doc.hasText) {
                runCatching { BookCache.put(this, uri, doc) }
            }
            // Глобальная нумерация предложений и накопительные слова считаем
            // в фоне — на больших книгах это не должно дёргать интерфейс.
            val prog = doc?.let { computeProgressIndexes(it) }
            handler.post {
                // msg2679: разбор кончился — состояние загрузки снято, дальше
                // refreshChrome сам решает по факту (есть книга или пусто).
                bookLoading = false
                // msg2787: окно закрыли, пока книга разбиралась (выход на полку и
                // т.п.) — движок уже отключён (player пуст), трогать его нельзя:
                // запоздавший результат разбора тихо игнорируем, иначе падение
                // «ReaderEngine не подключён» на плеере.
                if (isDestroyed || isFinishing || ReaderEngine.player == null) return@post
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
                        BookDocument.Unreadable.PDF_OUT_OF_MEMORY -> R.string.pdf_out_of_memory
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
                // prefs-место обязано стать местом ИМЕННО этой книги (KEY_URI уже
                // указывает на неё): иначе после перезапуска приложение подхватит
                // место предыдущей книги (restoreSession выбирает по KEY_URI).
                ReaderEngine.persistPosition()
                // Новая книга — история переходов (#101), выделение (#105) и
                // режим чтения куска (#106) больше недействительны.
                backStack.clear()
                finishSelection()
                rangeEnd = null
                voiceMissingAnnounced = false
                playing = false
                player.stop()
                refreshChrome()
                // msg3081+: книга открылась (или переключились на неё из панели) —
                // пересобираем ячейки быстрого доступа: только что открытая книга
                // становится самой свежей недавней.
                rebuildQuickPanel()
                // msg2691: книга открылась (разбор завершился) — лёгкая вибрация
                // как тактильный маркер готовности: после долгой загрузки понятно,
                // что открытие закончилось, даже если голос ещё молчит.
                Vibra.confirm(this)
                // msg2849: загрузка была долгой (>2с, сработал «подождите») и ничего
                // другого сейчас не заговорит — играем короткий мелодичный «готово».
                // Вибрация в конце долгого ожидания неотличима от вибрации «ещё
                // грузится», звук — отличим. Пропускаем, когда готовность и так
                // озвучится сама: чтение стартует (авто-старт или ждали «Читать» —
                // первое слово книги и есть сигнал), либо сейчас произнесут название
                // книги (внешний файл, headerAnnouncedAtOpen=false — озвучка через
                // 300мс ниже). Бип под свою же речь лезть не должен.
                if (longLoadFired && !playWantedWhileLoading
                    && !prefs.getBoolean(KEY_AUTO_START, true)
                    && headerAnnouncedAtOpen) {
                    Diag.log(this, "activity", "долгая загрузка закончилась — сигнал «готово» (msg2849)")
                    SoundFx.ready(this)
                }
                loadChapter()
                // msg2093: книга открылась на сохранённом месте (не из цитаты) —
                // вооружаем откат при старте. Первый реальный старт чтения начнётся
                // на N предложений раньше; движок сам не даст откату уехать в
                // сохранённое место, пока чтение не дочитает до него.
                if (rewindOnOpen) ReaderEngine.armStartRewind()
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
                // msg2679: «Читать» ждали, пока книга открывалась (кнопка была
                // disabled), а авто-старт не успел или выключен — стартуем сами.
                if (playWantedWhileLoading) {
                    playWantedWhileLoading = false
                    if (!isFinishing && !playing && book != null) requestStart()
                }
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

    /** Начать чтение (если движок ещё не готов — он сам подождёт и повторит). */
    private fun maybeAutoStart() = ReaderEngine.maybeAutoStart()

    /** Надёжный старт чтения: логика (готовность синтеза, повторы) в движке. */
    private fun requestStart() = ReaderEngine.requestStart()

    /** Поднять медиа-сервис. Карточка рождается при ОТКРЫТИИ книги, в состоянии
     *  паузы (0.3.88, msg1840); [playing] — состояние ПЕРВОГО уведомления.
     *  Логика в движке (он же держит сервис при чтении без окна). */
    private fun ensureMediaService(playing: Boolean = true) = ReaderEngine.ensureMediaService(playing)

    /** После успешного открытия — обновить запись книги в библиотеке. */
    private fun registerOpen(doc: BookDocument) = ReaderEngine.registerOpen(doc)

    private fun readBook(uri: Uri): BookDocument? {
        val name = if (uri.scheme == "file") {
            uri.lastPathSegment ?: "book"
        } else {
            queryDisplayName(uri) ?: uri.lastPathSegment ?: "book"
        }
        // PDF разбираем с диска (msg2619): читать большой файл в память целиком
        // нельзя — он разворачивался и валил приложение OOM ещё до разбора.
        if (name.lowercase(Locale.ROOT).endsWith(".pdf")) return readPdfDisk(uri)
        val bytes = openBytes(uri) ?: return null
        // «Показывать титульный лист» (#553): короткий блок в начале FB2 (титул,
        // копирайт) по умолчанию пропускается — книга начинается с первой главы.
        return BookParser.parse(name, bytes, prefs.getBoolean(KEY_SHOW_TITLE_PAGE, false))
    }

    /** PDF с диска (msg2619): файл не читаем в память целиком. Для content:// —
     *  копируем поток во временный файл приложения; pdfbox грузит документ так,
     *  что распакованные потоки пишутся во временные файлы (scratch в cacheDir),
     *  а не в кучу. Исходный файл на полке не трогаем; временные файлы чистим
     *  после разбора — текст к этому моменту уже в главах. */
    private fun readPdfDisk(uri: Uri): BookDocument? {
        val scratch = File(cacheDir, "pdf_scratch").apply { mkdirs() }
        val src: File? = when {
            uri.scheme == "file" -> uri.path?.let { File(it) }
            else -> {
                val tmp = File(scratch, "book.pdf")
                val copied = contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } != null
                if (copied) tmp else null
            }
        }
        if (src == null) return null
        return try {
            PdfParser.parse(src, scratch)
        } finally {
            // Оригинал (file://) не здесь — чистим только свои временные файлы.
            runCatching { scratch.listFiles()?.forEach { it.delete() } }
        }
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

    /** msg1119: полная запись позиции (prefs + запись книги). Логика в движке —
     *  ему сохранять место нужно и при чтении без окна. */
    private fun savePosition() = ReaderEngine.savePosition()

    /** Быстрая запись позиции в prefs (каждое предложение/переход). В движке. */
    private fun persistPosition() = ReaderEngine.persistPosition()

    /** Переключатель «читаю / дочитана». Действие живёт в ⋮-меню читалки
     *  (msg2375): экранной кнопки btnStatus больше нет. */
    private fun toggleStatus() {
        val u = currentUri ?: return
        val ex = BookStore.byUri(this, u) ?: return
        val next = if (ex.status == BookRecord.STATUS_FINISHED) {
            BookRecord.STATUS_READING
        } else {
            BookRecord.STATUS_FINISHED
        }
        BookStore.upsert(this, ex.copy(status = next))
        toast(getString(if (next == BookRecord.STATUS_FINISHED) R.string.status_finished else R.string.status_reading))
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onDestroy() {
        // Ридер закрыт. Если голос ЗВУЧИТ — #38 шаг 2: чтение остаётся жить без
        // окна (движок держит книгу, плеер и медиа-сервис; управление дальше —
        // гарнитура, «волшебное касание», кнопка в шторке). Если молчит — как
        // раньше: движок сам гасит сессию, отдаёт фокус и глушит
        // авто-продолжение (см. ReaderEngine.close): медиа-кнопки не должны
        // остаться у нас, а чтение не должно «ожить» без окна.
        if (active === this) active = null
        if (ReaderEngine.playing) ReaderEngine.windowGoneWhilePlaying()
        else ReaderEngine.close()
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
            G_PREV_PARA -> jumpAndAnnounce { moveByParagraph(-1) }
            G_NEXT_PARA -> jumpAndAnnounce { moveByParagraph(+1) }
            G_PREV_CH -> jumpAndAnnounce { chapterNavMove(-1) }
            G_NEXT_CH -> jumpAndAnnounce { chapterNavMove(+1) }
            // msg2547: свайп можно назначить на «Ничего не делать» — просто игнорируем.
            G_NONE -> {}
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

    /** Шаг «Абзац» (msg2471) для кнопок «Пред.»/«След.» — тем же правилом, что
     *  кнопки гарнитуры (ReaderEngine.headsetParagraphTarget): первое предложение
     *  абзаца помечено парсером (Sentence.paragraphStart) по структуре книги —
     *  в FB2/EPUB это абзацы вёрстки, в TXT — куски, отделённые пустой строкой.
     *  Вперёд — к началу следующего абзаца (в главе кончились — в первую абзац
     *  следующей главы). Назад — из середины абзаца в его начало, ещё раз назад —
     *  в начало предыдущего абзаца; в начале книги — стоп. */
    private fun moveByParagraph(delta: Int) {
        val bk = book ?: return
        val cur = bk.chapters[chapterIdx].sentences
        if (delta > 0) {
            for (i in sentenceIdx + 1 until cur.size) {
                if (cur[i].paragraphStart) { goTo(chapterIdx, i); return }
            }
            if (chapterIdx + 1 >= bk.chapters.size) return
            val next = bk.chapters[chapterIdx + 1].sentences
            val i = next.indexOfFirst { it.paragraphStart }
            goTo(chapterIdx + 1, if (i >= 0) i else 0)
            return
        }
        for (i in sentenceIdx - 1 downTo 0) {
            if (cur[i].paragraphStart) { goTo(chapterIdx, i); return }
        }
        if (chapterIdx == 0) {
            if (sentenceIdx != 0) goTo(0, 0)
            return
        }
        val prev = bk.chapters[chapterIdx - 1].sentences
        if (prev.isEmpty()) return
        val i = prev.indexOfLast { it.paragraphStart }
        goTo(chapterIdx - 1, if (i >= 0) i else 0)
    }

    /** По каким «главам» ходят «Предыдущая/Следующая глава» и свайп-глава
     *  (настройка «Кнопки глав», 0.3.37). Фильтрует плоский список глав по
     *  разметке FB2: [CH_NAV_MAJOR] — только начала крупных разделов (parts),
     *  [CH_NAV_CHAPTERS] — главы без вложенных подразделов, [CH_NAV_ALL] —
     *  по всем (по умолчанию, поведение прежнее). Мелкие режимы [CH_NAV_SENT]/
     *  [CH_NAV_PARAGRAPH] (msg2539) сюда не доходят — кнопки и свайп-глава
     *  перехватывает chapterNavMove; если кто-то всё же зовёт moveByChapter при
     *  мелком режиме (например шаг «Глава» у «Пред./След.»), ведём себя как
     *  [CH_NAV_ALL] — полный список. Для TXT/EPUB иерархии нет — все режимы
     *  дают полный список. */
    private fun chapterStopIndexes(): IntArray {
        val bk = book ?: return intArrayOf()
        val mode = prefs.getString(KEY_CH_NAV, CH_NAV_ALL)
        val size = bk.chapters.size
        if (mode == CH_NAV_ALL || mode == CH_NAV_SENT || mode == CH_NAV_PARAGRAPH) {
            return IntArray(size) { it }
        }
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

    /** Шаг «дальше/назад» (настройка в «Управлении»): предложение / абзац / глава. */
    private fun stepMode(): String =
        prefs.getString(KEY_STEP, STEP_SENTENCE) ?: STEP_SENTENCE

    /** Кнопки «Пред.»/«След.» листают шагом из настроек: по умолчанию — по
     *  предложениям; «Абзац» — переходят к началу абзаца (msg2471); «Глава» —
     *  по главам, как кнопки ⏮/⏭. */
    private fun stepMove(delta: Int) {
        when (stepMode()) {
            STEP_PARAGRAPH -> moveByParagraph(delta)
            STEP_CHAPTER -> moveByChapter(delta)
            else -> moveBySentence(delta)
        }
    }

    /** Режим «Кнопки глав шагают» — что листают кнопки «◀ Глава/Глава ▶»
     *  (настройка KEY_CH_NAV). Default CH_NAV_ALL — как раньше. */
    private fun chNavMode(): String =
        prefs.getString(KEY_CH_NAV, CH_NAV_ALL) ?: CH_NAV_ALL

    /** Кнопки «◀ Глава/Глава ▶» идут по режиму «Кнопки глав шагают» (msg2531/2539):
     *  выбрано «по предложениям»/«по абзацам» — листают мелким шагом, как
     *  «Пред./След.» в том же режиме (moveBySentence/moveByParagraph); остальные
     *  режимы — скачком по главам/разделам/заголовкам (moveByChapter). */
    private fun chapterNavMove(delta: Int) {
        when (chNavMode()) {
            CH_NAV_SENT -> moveBySentence(delta)
            CH_NAV_PARAGRAPH -> moveByParagraph(delta)
            else -> moveByChapter(delta)
        }
    }

    /** Имена кнопок «Пред.»/«След.» для скринридера следуют выбранному шагу
     *  (msg2471): предложение → «Предыдущее/Следующее предложение», абзац →
     *  «Предыдущий/Следующий абзац», глава → «Предыдущая/Следующая глава».
     *  Вызывается в onStart — при старте читалки и после возврата из Настроек
     *  (там менялся шаг). Видимые короткие подписи «Пред.»/«След.» не трогаем —
     *  их запрещено растягивать (msg2431). */
    private fun updateSentenceButtonNames() {
        val (prev, next) = when (stepMode()) {
            STEP_PARAGRAPH -> R.string.prev_paragraph to R.string.next_paragraph
            STEP_CHAPTER -> R.string.prev_chapter to R.string.next_chapter
            else -> R.string.prev_sentence to R.string.next_sentence
        }
        binding.btnPrevSentence.contentDescription = getString(prev)
        binding.btnNextSentence.contentDescription = getString(next)
    }

    /** Имена кнопок «◀ Глава/Глава ▶» для скринридера следуют режиму «Кнопки глав
     *  шагают» (msg2531/2535/2539): по предложениям → «…предложение», по абзацам →
     *  «…абзац», по главам → «Предыдущая/Следующая глава», по крупным разделам →
     *  «…раздел», по всем заголовкам → «…заголовок». Раньше имя всегда было
     *  «глава», хотя сами кнопки при режимах major/all прыгают по разделам и
     *  заголовкам, а при sent/paragraph листают по предложениям/абзацам —
     *  скринридер говорил не то, куда реально ведёт переход.
     *  Вызывается в onStart вместе с updateSentenceButtonNames. */
    private fun updateChapterButtonNames() {
        val (prev, next) = when (chNavMode()) {
            CH_NAV_SENT -> R.string.prev_sentence to R.string.next_sentence
            CH_NAV_PARAGRAPH -> R.string.prev_paragraph to R.string.next_paragraph
            CH_NAV_MAJOR -> R.string.prev_section to R.string.next_section
            CH_NAV_CHAPTERS -> R.string.prev_chapter to R.string.next_chapter
            else -> R.string.prev_header to R.string.next_header
        }
        binding.btnPrevChapter.contentDescription = getString(prev)
        binding.btnNextChapter.contentDescription = getString(next)
    }

    private fun goTo(ch: Int, s: Int) = ReaderEngine.goTo(ch, s)

    /** Двойной тап по предложению (при чтении TalkBack'ом — активация строки).
     *  Всегда переводит читаемую позицию на это предложение; если чтение ещё
     *  не шло и в настройках включено «двойной тап начинает чтение» — начинаем
     *  озвучивать отсюда (см. [KEY_TAP_TO_PLAY]). */
    private fun onSentenceTapped(pos: Int) {
        if (book == null) return
        goTo(chapterIdx, pos)
        if (!playing && prefs.getBoolean(KEY_TAP_TO_PLAY, true)) requestStart()
    }

    // ---------------- Воспроизведение ----------------

    private fun togglePlay() {
        // msg2679: «Читать» нажали, пока книга ещё открывается (тяжёлый PDF
        // разбирается в фоне) — нажатие не теряем: объявляем один раз и начнём
        // сами по готовности книги (см. openBook). Повторные нажатия в это окно
        // объявление не дублируют.
        if (book == null) {
            if (!bookLoading) return  // пустой экран (книги нет и не открывается)
            if (!playWantedWhileLoading) {
                playWantedWhileLoading = true
                Diag.log(this, "activity", "play: книга ещё не готова — старт по готовности (msg2679)")
                binding.btnPlayPause.announceForAccessibility(
                    "Книга ещё открывается, чтение начнётся само"
                )
            }
            return
        }
        // Пользовательская пауза кнопкой — фокус держим (см. pausePlayback).
        if (playing) {
            playWantedWhileLoading = false
            pausePlayback(keepFocus = true)
        } else {
            startSpeakingCurrent()
        }
    }

    /** Пауза чтения. Логика (фокус, запись места, кнопка) — в движке: паузу
     *  нужно уметь делать и при чтении без окна. */
    private fun pausePlayback(keepFocus: Boolean = false, byFocusLoss: Boolean = false) =
        ReaderEngine.pausePlayback(keepFocus, byFocusLoss)

    /** Старт чтения с текущей позиции. Логика (guard места, фокус, медиа-сервис,
     *  кнопка) — в движке, ему стартовать нужно и при чтении без окна. */
    private fun startSpeakingCurrent() = ReaderEngine.startSpeakingCurrent()

    // ---------------- UI ----------------

    /** displayTitle (msg2559) для окна ридера: ручное название из
     *  «Переименовать» > метаданные > имя файла. Всё это хранит запись
     *  библиотеки (BookRecord.displayTitle), а у открытого BookDocument только
     *  title/author — поэтому запись ищем по currentUri; без записи (книга ещё
     *  не в библиотеке) — title документа, затем имя файла. null — ни книги,
     *  ни имени. */
    private fun docDisplayTitle(): String? {
        val u = currentUri
        u?.let { BookStore.byUri(this, it)?.let { rec -> return rec.displayTitle } }
        return book?.title?.takeIf { it.isNotBlank() }
            ?: currentName?.takeIf { it.isNotBlank() }
    }

    private fun refreshChrome() {
        val bk = book
        // displayTitle (msg2559): ручное название из «Переименовать» > метаданные
        // > имя файла; для открытой книги непусто, no_book остаётся только без
        // книги и без имени.
        val t = docDisplayTitle()
        val author = bk?.author?.takeIf { it.isNotBlank() }
        val headerText = when {
            t == null -> getString(R.string.no_book)
            author == null -> t
            else -> "$t — $author"
        }
        binding.tvHeader.text = headerText
        // msg1736/1739: window title окна — по фактическому названию книги (для
        // файлов вне записи оно известно только после разбора).
        if (bk != null) setTitle(headerText)
        val enabled = bk != null
        // msg2679: во время загрузки книги (bookLoading) кнопку «Читать» держим
        // активной — togglePlay поставит старт в очередь, а не потеряет нажатие
        // на disabled-кнопке. Остальные кнопки требуют готовой книги.
        binding.btnPlayPause.isEnabled = enabled || bookLoading
        binding.btnPrevSentence.isEnabled = enabled
        binding.btnNextSentence.isEnabled = enabled
        binding.btnPrevChapter.isEnabled = enabled
        binding.btnNextChapter.isEnabled = enabled
        binding.btnSpeedDown.isEnabled = enabled
        binding.btnSpeedUp.isEnabled = enabled
        binding.btnToc.isEnabled = enabled
        binding.btnSearch.isEnabled = enabled
        binding.seekProgress.isEnabled = enabled
        updatePlayButton()
        updatePosition()
    }

    private fun updatePlayButton() {
        // msg1644/1646 (0.3.68): TalkBack озвучивал смену кнопки несколько раз
        // подряд — из-за двух источников (contentDescription и текст менялись
        // отдельными событиями) + повторов. Один источник озвучки = ТЕКСТ, без
        // значка ▶/⏸ в строке (msg1144: значок скринридер читал отдельным словом)
        // и без contentDescription: одно изменение = одно объявление.
        binding.btnPlayPause.text = if (playing) "Пауза" else "Читать"
        // #54: та же кнопка в полноэкранной панели голоса — «Проверить голос»/«Пауза».
        binding.btnVoiceTest.text = if (playing) "Пауза" else getString(R.string.voice_test_play)
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
        show(KEY_UI_SPEED, binding.speedRow)
        show(KEY_UI_VOICE, binding.btnVoice)
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
        // Поиск и шторка голоса одновременно не нужны.
        if (voicePanelOpen()) hideVoicePanel()
        if (searchPanelOpen()) closeSearchPanel()
        else openSearchPanel(focusField = true)
    }

    private fun onSearchLongPress() {
        if (book == null) return
        startVoiceSearch()
    }

    private fun searchPanelOpen(): Boolean =
        binding.searchPanel.visibility == View.VISIBLE

    private fun voicePanelOpen(): Boolean =
        binding.voicePanel.visibility == View.VISIBLE

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
                docDisplayTitle(),
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
        // msg2375: «Отметить дочитанной» ушло с экрана в это меню — пункт
        // переключается по текущему статусу книги (читаю/дочитана). Выход —
        // всегда последним (msg1278).
        val u = currentUri
        val finished = u != null &&
            BookStore.byUri(this, u)?.status == BookRecord.STATUS_FINISHED
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.go_library) to { startLibrary() })   // msg1176/1179: из шапки → «⋮»
        actions.add(getString(R.string.settings_btn) to { startSettingsTab() })  // msg1176: Настройки → «⋮»
        actions.add(getString(R.string.reader_action_back) to { goBackPlace() })
        actions.add(getString(
            if (finished) R.string.status_reading_toggle else R.string.status_finished_toggle
        ) to { toggleStatus() })
        actions.add(getString(R.string.quotes_title) to {  // 0.3.44 (msg1092): Цитаты из читалки
            startActivity(Intent(this, QuotesActivity::class.java))
        })
        // msg3081+: панель быстрого доступа (пять ячеек недавних/закреплённых книг)
        // и закрепление открытой книги. Показ/скрытие — переключатель; пункт пина
        // меняется по тому, закреплена ли текущая книга.
        actions.add(
            if (quickPanelVisible()) getString(R.string.quick_hide) else getString(R.string.quick_show)
        ) to { toggleQuickPanel() }
        if (u != null) {
            val pinnedIndex = quickPinnedIndex(u)
            if (pinnedIndex == null) {
                actions.add(getString(R.string.quick_pin_this) to { pinCurrentBook() })
            } else {
                actions.add(getString(R.string.quick_unpin_this) to {
                    setQuickPin(pinnedIndex, null)
                    rebuildQuickPanel()
                    binding.sentenceList.announceForAccessibility(getString(R.string.quick_unpinned))
                })
            }
        }
        // msg2567: таймер сна — пункт-переключатель. Подпись показывает режим,
        // клик открывает выбор времён/«выключить» (см. showSleepTimerDialog).
        actions.add(sleepTimerMenuLabel() to { showSleepTimerDialog() })
        // msg2762: «Голос чтения» убран из меню — дубль кнопки «Голос» нижнего ряда.
        actions.add(getString(R.string.app_exit) to { exitApp() })  // msg1278: последним.
        MaterialAlertDialogBuilder(this)
            // msg1687: заголовок «Действия» убран — звучал пунктом, но не нажимался.
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    // ---------------- msg3081+: панель быстрого доступа (пять кнопок-ячеек) ----------------

    /** Одна ячейка панели: uri книги в ней (null — свободна) и закреплена ли она.
     *  Свободные ячейки заняты недавними книгами из библиотеки — той же выборкой
     *  (BookStore.recent по lastOpenedAt), что меню долгого нажатия «Открыть книгу»
     *  в Библиотеке. */
    private data class QuickCell(val uri: String?, val title: String?, val pinned: Boolean)

    /** Последняя сборка панели — клики по кнопкам берут отсюда uri/закрепление. */
    private var quickCells: List<QuickCell> = emptyList()

    private val quickButtons: List<Button>
        get() = listOf(
            binding.btnQuick0, binding.btnQuick1, binding.btnQuick2,
            binding.btnQuick3, binding.btnQuick4,
        )

    private fun installQuickPanelHandlers() {
        val buttons = quickButtons
        for (i in buttons.indices) {
            buttons[i].setOnClickListener { onQuickClick(i) }
            buttons[i].setOnLongClickListener {
                onQuickLongClick(i)
                true
            }
        }
    }

    private fun quickPanelVisible(): Boolean = prefs.getBoolean(KEY_QUICK_PANEL, false)

    private fun setQuickPanelVisible(v: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_PANEL, v).apply()
    }

    private fun quickPinUri(i: Int): String? = prefs.getString(KEY_QUICK_PIN + i, null)

    private fun setQuickPin(i: Int, uri: String?) {
        prefs.edit().putString(KEY_QUICK_PIN + i, uri).apply()
    }

    /** На какой кнопке закреплена книга (или null — не закреплена). */
    private fun quickPinnedIndex(uri: String): Int? =
        (0..4).firstOrNull { quickPinUri(it) == uri }

    /** Пункт ⋮-меню «Показать панель книг» / «Скрыть панель книг». */
    private fun toggleQuickPanel() {
        val show = !quickPanelVisible()
        setQuickPanelVisible(show)
        if (show) {
            if (!rebuildQuickPanel()) {
                binding.sentenceList.announceForAccessibility(getString(R.string.quick_empty))
            } else {
                binding.sentenceList.announceForAccessibility(getString(R.string.quick_panel_cd))
            }
        } else {
            binding.quickPanel.visibility = View.GONE
            binding.sentenceList.announceForAccessibility(getString(R.string.quick_hide))
        }
    }

    /** Пересобрать ячейки панели и применить её видимость. true — панель показана
     *  (в ней есть хоть одна книга); false — панель пуста и скрыта. */
    private fun rebuildQuickPanel(): Boolean {
        if (!quickPanelVisible()) {
            binding.quickPanel.visibility = View.GONE
            return false
        }
        val cur = currentUri
        // Пины: закреплённые книги намертво на своих местах. Книга пропала из
        // библиотеки (удалена) — пин теряет смысл, снимаем сами.
        val pins = arrayOfNulls<String>(5)
        val pinnedUris = HashSet<String>()
        for (i in 0 until 5) {
            val pu = quickPinUri(i)
            if (pu == null) continue
            if (BookStore.byUri(this, pu) == null) {
                setQuickPin(i, null)
                continue
            }
            pins[i] = pu
            pinnedUris.add(pu)
        }
        // Недавние заполняют свободные ячейки слева направо по свежести.
        // Исключаем закреплённых (они на своих местах) и книгу, открытую сейчас.
        val recents = BookStore.recent(this, null, 20)
            .filter { it.uri != cur && it.uri !in pinnedUris }
        val cells = ArrayList<QuickCell>(5)
        val ri = recents.iterator()
        for (i in 0 until 5) {
            val pu = pins[i]
            if (pu != null) {
                val rec = BookStore.byUri(this, pu)
                cells.add(QuickCell(pu, rec?.displayTitle, true))
            } else {
                val r = if (ri.hasNext()) ri.next() else null
                cells.add(QuickCell(r?.uri, r?.displayTitle, false))
            }
        }
        quickCells = cells
        val buttons = quickButtons
        var any = false
        for (i in 0 until 5) {
            val cell = cells[i]
            val btn = buttons[i]
            if (cell.uri == null) {
                // Пустая ячейка: место держит, но фокус и клики не занимает.
                btn.visibility = View.INVISIBLE
                continue
            }
            any = true
            val title = cell.title ?: "?"
            btn.visibility = View.VISIBLE
            btn.text = (i + 1).toString()
            btn.contentDescription = if (cell.pinned) {
                getString(R.string.quick_pinned_cd, i + 1, title)
            } else {
                getString(R.string.quick_btn_cd, i + 1, title)
            }
        }
        binding.quickPanel.visibility = if (any) View.VISIBLE else View.GONE
        return any
    }

    /** Клик по ячейке — переключиться на её книгу, продолжив с сохранённого места. */
    private fun onQuickClick(index: Int) {
        if (index >= quickCells.size) return
        val cell = quickCells[index]
        val uri = cell.uri ?: return
        if (uri == currentUri) {
            binding.sentenceList.announceForAccessibility(getString(R.string.quick_already_open))
            return
        }
        if (book == null) return
        switchToBook(uri)
    }

    private fun switchToBook(uri: String) {
        // Фиксируем место уходящей книги в её записи — иначе движок без окна
        // держит его только в prefs, и после переключения место потеряется.
        if (book != null) ReaderEngine.savePosition()
        val rec = BookStore.byUri(this, uri)
        openBook(
            Uri.parse(uri),
            rec?.chapter ?: 0,
            rec?.sentence ?: 0,
            rewindOnOpen = (rec?.lastOpenedAt ?: 0L) > 0L,
        )
    }

    /** Долгий клик по ячейке: книга не закреплена — закрепить за этой кнопкой,
     *  закреплена — снять. Симметрично и предсказуемо (msg3114/3118). */
    private fun onQuickLongClick(index: Int) {
        if (index >= quickCells.size) return
        val cell = quickCells[index]
        val uri = cell.uri ?: return
        if (cell.pinned) {
            setQuickPin(index, null)
            rebuildQuickPanel()
            binding.sentenceList.announceForAccessibility(getString(R.string.quick_unpinned))
        } else {
            // Та же книга уже закреплена за другой кнопкой — снять старый пин,
            // чтобы книги не было в двух ячейках разом.
            quickPinnedIndex(uri)?.let { setQuickPin(it, null) }
            setQuickPin(index, uri)
            rebuildQuickPanel()
            binding.sentenceList.announceForAccessibility(getString(R.string.quick_pinned_to, index + 1))
        }
    }

    /** ⋮-меню: закрепить открытую книгу в первой свободной ячейке (слева). */
    private fun pinCurrentBook() {
        val u = currentUri ?: return
        if (quickPinnedIndex(u) != null) return
        val free = (0..4).firstOrNull { quickPinUri(it) == null }
        if (free == null) {
            toast(getString(R.string.quick_full))
            return
        }
        setQuickPin(free, u)
        rebuildQuickPanel()
        binding.sentenceList.announceForAccessibility(getString(R.string.quick_pinned_to, free + 1))
    }

    /** msg2567: подпись пункта «Таймер сна» в ⋮-меню читалки. Показывает
     *  активный режим: «Таймер сна: N минут» / «Таймер сна: до конца главы»;
     *  таймера нет — просто «Таймер сна». */
    private fun sleepTimerMenuLabel(): String = when (ReaderEngine.sleepMode) {
        ReaderEngine.SLEEP_MINUTES -> getString(
            R.string.sleep_menu_active,
            getString(R.string.sleep_min, ReaderEngine.sleepMinutes)
        )
        ReaderEngine.SLEEP_CHAPTER -> getString(R.string.sleep_menu_chapter_active)
        else -> getString(R.string.sleep_menu)
    }

    /** msg2695/2699: долгое нажатие «▶». Действие из настройки play_long
     *  (раздел «Управление»): по умолчанию — таймер сна. */
    private fun onPlayPauseLongClick() {
        when (prefs.getString(KEY_PLAY_LONG, PLAY_LONG_SLEEP)) {
            PLAY_LONG_SLEEP -> showSleepTimerDialog()
            // PLAY_LONG_OFF — ничего не делаем; событие уже съедено слушателем.
        }
    }

    /** msg2567/2575: выбор таймера сна. Таймер живёт в движке — работает и при
     *  погашенном экране, и «без окна» (см. ReaderEngine.setSleepTimerMinutes).
     *  Активный режим первым пунктом снимается; затем времена и «До конца главы». */
    private fun showSleepTimerDialog() {
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (ReaderEngine.sleepTimerActive) {
            opts.add(getString(R.string.sleep_off) to {
                ReaderEngine.cancelSleepTimer()
                toast(getString(R.string.sleep_off_done))
            })
        }
        for (m in SLEEP_TIMER_CHOICES) {
            opts.add(getString(R.string.sleep_min, m) to {
                ReaderEngine.setSleepTimerMinutes(m)
                Vibra.confirm(this)
                toast(getString(R.string.sleep_menu_active, getString(R.string.sleep_min, m)))
            })
        }
        opts.add(getString(R.string.sleep_chapter) to {
            ReaderEngine.setSleepTimerChapterEnd()
            Vibra.confirm(this)
            toast(getString(R.string.sleep_menu_chapter_active))
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sleep_dialog_title))
            .setItems(opts.map { it.first }.toTypedArray()) { _, which ->
                opts[which].second()
            }
            .show()
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
     *  Логика каскада и ожидания готовности синтеза — в движке. */
    private fun applyBookVoiceAtOpen() = ReaderEngine.applyBookVoiceAtOpen()

    /** Диалог «Голос и речь» закрыт с включённой галочкой — зафиксировать
     *  текущие движок/голос/скорость как свои для этой книги. */
    private fun persistPerBookProfile() {
        if (book == null) return
        if (currentBookRecord() == null) return
        val vce = voiceName?.takeIf { player.isReady && player.voices.any { v -> v.name == it } }
        savePerBook(player.enginePackage, vce, player.speed)
    }

    /** #55 (msg2669): панель голоса закрыта БЕЗ галочки и что-то меняли — текущие
     *  движок/голос/скорость плеера становятся глобальными (голос «для всех книг
     *  без своего голоса»). Зовётся при закрытии панели, а не в момент кликов:
     *  тогда настройка книги с галочкой не успевает заразить глобальные, в каком
     *  порядке ни выбирай. Если глобально движок не был выбран (null — системный),
     *  движок не «прилипает»: пишем только когда панель действительно что-то
     *  меняла (voicePanelDirty). */
    private fun persistGlobalProfile() {
        if (!player.isReady) return
        val e = prefs.edit()
        val eng = player.enginePackage
        if (eng != null) e.putString(KEY_ENGINE, eng) else e.remove(KEY_ENGINE)
        val vc = voiceName?.takeIf { player.voices.any { v -> v.name == it } }
        if (vc != null) e.putString(KEY_VOICE, vc) else e.remove(KEY_VOICE)
        e.putFloat(KEY_SPEED, player.speed)
        e.apply()
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
            docDisplayTitle(),
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
            bookTitle = docDisplayTitle() ?: getString(R.string.app_name),
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

    // Включена ли в открытой шторке галочка «Запомнить для этой книги» — нужна
    // при закрытии шторки, чтобы зафиксировать профиль книги.
    private var voiceRememberChecked = false

    // #55 (msg2669): в этой сессии панели голоса что-то меняли (движок/голос/
    // скорость)? Закрыли без галочки и без изменений — глобальные настройки не
    // перезаписываем, чтобы простое «открыл-закрыл» не прибивало системный движок.
    private var voicePanelDirty = false

    // msg2679: «Читать» нажали, пока книга ещё открывалась (тяжёлый PDF
    // разбирается в фоне секунды) — намерение не теряем: стартуем сами по
    // готовности книги (см. openBook).
    private var playWantedWhileLoading = false
    // msg2679: книга в процессе открытия (фоновый разбор). В это время кнопку
    // «Читать» НЕ отключаем (refreshChrome), чтобы нажатие регистрировалось и
    // вело к очереди старта, а не глоталось молча disabled-кнопкой.
    private var bookLoading = false
    // msg2849: загрузка реально была долгой — блок «подождите» (msg2685) успел
    // сработать через 2с. По этому флагу при готовности книги играет звуковой
    // сигнал: вибрацию в конце долгого ожидания можно перепутать с вибрацией
    // «ещё грузится», а короткий мелодичный «готово» не спутаешь.
    private var longLoadFired = false

    // #54 (msg2643): полноэкранный режим панели голоса. На время панели прячем
    // остальную читалку (всех соседей voiceArea в корне + список предложений),
    // чтобы панель со скроллом заняла весь экран; при закрытии всё возвращаем.
    private var voiceFullscreen = false
    private val voiceSavedViews = ArrayList<Pair<View, Int>>()

    /** «Голос чтения» (кнопка в нижнем ряду и пункт меню «⋮»): #54 (msg2643)
     *  открывает панель voicePanel НА ВЕСЬ ЭКРАН — читалка временно прячется
     *  (enterVoiceFullscreen). Содержимое прежнее — ползунки скорости/тона/
     *  громкости, галочка «Запомнить для этой книги» (#102), двухшаговый выбор
     *  движок → голоса. Кнопка «Проверить голос» (▶/⏸) в шапке слушает выбранный
     *  голос/скорость на тексте книги, не сворачивая панель. */
    private fun toggleVoicePanel() {
        if (binding.voicePanel.visibility == View.VISIBLE) {
            hideVoicePanel()
            return
        }
        if (!player.isReady) {
            toast("Движок синтеза речи ещё не готов, попробуйте через секунду")
            return
        }
        // Шторка и поиск одновременно не нужны — закрываем панель поиска.
        if (searchPanelOpen()) closeSearchPanel()
        // Перестраиваем шторку из текущих значений при каждом открытии.
        val body = binding.voiceBody
        body.removeAllViews()
        voicePanelDirty = false
        body.setPadding(dp2px(4f), dp2px(8f), dp2px(4f), dp2px(8f))

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
        voiceRememberChecked = rememberCb.isChecked

        // #54: кнопка «Проверить голос» в шапке панели — только когда открыта книга
        // (без книги читать нечего). Текст обновляет и updatePlayButton на лету.
        binding.btnVoiceTest.visibility = if (hasBook) View.VISIBLE else View.GONE
        binding.btnVoiceTest.text = if (playing) "Пауза" else getString(R.string.voice_test_play)

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
            populateVoiceList(voiceContainer)
            showStep(2)
        }

        // Выбор движка — как в Настройках: выбрал (даже текущий) → список голосов.
        fun onEngineChosen(pkg: String) {
            if (pkg == player.enginePackage) {
                revealVoices()
                return
            }
            // #55 (msg2669): движок в глобальные настройки в момент клика НЕ пишем —
            // иначе выбор «до галочки» заражал все книги без своего голоса (msg2671).
            // Куда писать решает hideVoicePanel при закрытии: галочка → запись книги,
            // без галочки → глобальные (persistGlobalProfile). Порядок «сначала голос,
            // потом галочка» перестаёт иметь значение.
            voicePanelDirty = true
            voiceContainer.removeAllViews()
            voiceContainer.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.engine_loading)
            })
            player.setEngine(pkg) { ok ->
                handler.post {
                    if (ok) {
                        revealVoices()
                    } else {
                        player.setEngine(null) { _ ->
                            handler.post { revealVoices() }
                        }
                        toast("Движок не запустился, вернул системный")
                    }
                }
            }
        }

        // Ползунки — как Настройки → «Голос»: скорость, тон, громкость.
        // Скорость: применяем к плееру сразу (новую слышно на лету), но в
        // глобальные НЕ пишем до закрытия панели — #55 (msg2669): выбор «до галочки»
        // не должен заражать общие настройки. Куда писать решает hideVoicePanel
        // (галочка → запись книги, без → глобальные). В отличие от тон/громкость,
        // которые всегда общие, скорость может быть своя у книги (#102).
        addRateSliderTo(body, R.string.speed_value, "Скорость", currentSpeed()) { v ->
            voicePanelDirty = true
            if (player.isReady) player.speed = v
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
        // #55 (msg2669): статичная подсказка под галочкой — что будет с выбранным
        // голосом при закрытии панели. Одна фраза на обе стороны, чтобы незрячему
        // не приходилось угадывать по названию галочки (msg2659/msg2671).
        body.addView(TextView(this).apply {
            text = getString(R.string.voice_remember_hint)
            textSize = 14f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(dp2px(4f), 0, dp2px(4f), dp2px(8f))
        })
        rememberCb.setOnCheckedChangeListener { _, checked ->
            voiceRememberChecked = checked
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

        // #54 (msg2643): панель голоса «на весь экран» — прячем остальную
        // читалку, а не показываем шторку над списком. Кнопка «Проверить голос»
        // (▶/⏸) в шапке слушает выбранный голос/скорость на тексте книги прямо
        // из панели. Скролл внутри занимает весь экран, поэтому галочка
        // «Запомнить для этой книги» (msg2639) больше не обрезается невидимым
        // краем шторки — до неё всегда можно доскроллить.
        enterVoiceFullscreen()
        binding.voicePanel.visibility = View.VISIBLE
        binding.voicePanel.post {
            binding.tvVoiceTitle.announceForAccessibility(getString(R.string.voice_settings))
        }
    }

    /** Закрыть шторку голоса (кнопка «Свернуть», повторное нажатие «Голос»).
     *  При включённой галочке «Запомнить для этой книги» фиксируем профиль книги
     *  (движок/голос/скорость) — как раньше при закрытии диалога: скорость мог
     *  изменить слайдер в шторке, поэтому обновляем подпись и статистику. */
    private fun hideVoicePanel() {
        if (binding.voicePanel.visibility != View.VISIBLE) return
        binding.voicePanel.visibility = View.GONE
        // Возвращаем читалку: соседи voiceArea и список предложений на место,
        // панели/скроллу — исходные параметры (следующее открытие заново
        // развернёт их через enterVoiceFullscreen).
        exitVoiceFullscreen()
        if (book != null && voiceRememberChecked) {
            persistPerBookProfile()
            refreshSpeedValue()
            updateStats()
        } else if (voicePanelDirty) {
            // #55 (msg2669): галочки нет (или книги нет) и что-то меняли — закрыли
            // панель, значит выбранные движок/голос/скорость становятся ГЛОБАЛЬНЫМИ
            // («для всех книг без своего голоса»). Запись здесь, а не в момент
            // клика, чинит порядок «выбрал голос, потом поставил галочку»: при
            // галочке белорусский выбор уже ушёл в книгу выше и глобальные не задел.
            persistGlobalProfile()
            refreshSpeedValue()
        }
        voiceRememberChecked = false
        voicePanelDirty = false
    }

    /** #54: развернуть панель голоса на весь экран. Прячем всех соседей voiceArea
     *  в корневом вертикальном ряду (шапку, иконки, позицию, слайдер, скорость,
     *  главы, статистику, нижний ряд) и список предложений; панели и её скроллу
     *  задаём вес 1, чтобы она заполнила экран и внутри реально скроллилась. */
    private fun enterVoiceFullscreen() {
        if (voiceFullscreen) return
        voiceFullscreen = true
        voiceSavedViews.clear()
        val root = binding.root as LinearLayout
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v !== binding.voiceArea) {
                voiceSavedViews.add(v to v.visibility)
                v.visibility = View.GONE
            }
        }
        voiceSavedViews.add(binding.sentenceList to binding.sentenceList.visibility)
        binding.sentenceList.visibility = View.GONE
        binding.voicePanel.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        binding.voiceScroll.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
    }

    /** #54: свернуть полноэкранную панель — вернуть все спрятанные view и
     *  исходные layout-параметры панели/скролла. */
    private fun exitVoiceFullscreen() {
        if (!voiceFullscreen) return
        voiceFullscreen = false
        for ((v, vis) in voiceSavedViews) v.visibility = vis
        voiceSavedViews.clear()
        binding.voicePanel.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        binding.voiceScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun populateVoiceList(container: LinearLayout) {
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
                // #55 (msg2669): голос в глобальные в момент выбора НЕ пишем — выбор
                // до установки галочки заражал все книги без своего голоса. Куда
                // писать решает hideVoicePanel при закрытии панели.
                voicePanelDirty = true
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

        // msg2567: набор времён таймера сна в диалоге выбора (минуты).
        internal val SLEEP_TIMER_CHOICES = intArrayOf(10, 20, 30, 45, 60)

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
        // msg2685: сообщать голосом, если книга открывается дольше ~2 секунд.
        internal const val KEY_LONG_LOAD_ANNOUNCE = "long_load_announce"
        internal const val KEY_AUTO_RESUME = "auto_resume"
        internal const val KEY_SAY_CHAPTER_START = "say_chapter_start"
        internal const val KEY_STEP = "step"
        internal const val STEP_SENTENCE = "sentence"
        internal const val STEP_PARAGRAPH = "paragraph"
        internal const val STEP_CHAPTER = "chapter"
        // «Кнопки глав шагают» (0.3.37): по чём ходит «Предыдущая/Следующая глава».
        // Default CH_NAV_ALL — как раньше. Режимы sent/paragraph (msg2531/2539) —
        // мелкий шаг: кнопки листают по предложениям/абзацам, как «Пред./След.»;
        // major/chapters/all — скачок по узлам разметки FB2 (см. chapterStopIndexes).
        internal const val KEY_CH_NAV = "ch_nav"
        internal const val CH_NAV_SENT = "sentence"
        internal const val CH_NAV_PARAGRAPH = "paragraph"
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
        internal const val KEY_UI_SPEED = "ui_speed_buttons"
        internal const val KEY_UI_SEARCH = "ui_search_button"
        // msg2766: кнопка «Голос» нижнего ряда читалки — скрывается конструктором.
        internal const val KEY_UI_VOICE = "ui_voice"

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

        // msg2093: «Отступать назад при старте» — при первом старте чтения после
        // открытия книги начать на N предложений раньше места остановки (вспомнить,
        // что там было). NONE — выключено (по умолчанию). Работает и для авто-старта,
        // и для ручного «Читать»; один раз за открытие.
        internal const val KEY_START_REWIND = "start_rewind"
        internal const val START_REWIND_NONE = "none"
        internal const val START_REWIND_2 = "2"
        internal const val START_REWIND_5 = "5"

        internal const val START_LIBRARY = "library"
        internal const val START_LAST = "last"

        // Куда «Назад» выходит из открытой книги (#43).
        internal const val KEY_EXIT = "exit_reader"
        internal const val EXIT_DESKTOP = "desktop"
        internal const val EXIT_LIBRARY = "library"

        // msg3081+: панель быстрого доступа (пять кнопок-ячеек) в читалке.
        // KEY_QUICK_PANEL — видимость панели («показана/скрыта», живёт между
        // входами в читалку); KEY_QUICK_PIN_<i> — закреплённая книга за ячейкой i
        // (uri строкой; null/нет ключа — ячейка свободна, в ней недавняя).
        internal const val KEY_QUICK_PANEL = "quick_panel"
        internal const val KEY_QUICK_PIN = "quick_pin_"

        // msg2136: кнопки гарнитуры „назад/вперёд“ — у каждой своя настройка шага
        // („Выключено“ / предложение / абзац / глава). Значение pref — одна из
        // HS_* констант. По умолчанию — „Предложение“ (msg2152): тройное постукивание
        // листает книгу. „Выключено“ — кнопка ничего не делает: паузу/продолжение
        // Сергей держит на двойном постукивании (отдельная команда play/pause), а не
        // на next/prev, поэтому прежнее „next = пауза“ больше не нужно.
        internal const val KEY_HS_PREV = "headset_prev"
        internal const val KEY_HS_NEXT = "headset_next"
        internal const val HS_OFF = "off"
        internal const val HS_SENTENCE = "sentence"
        internal const val HS_PARAGRAPH = "paragraph"
        internal const val HS_CHAPTER = "chapter"

        // msg2695/2699: долгое нажатие кнопки «▶» (играть/пауза). Значение pref — одна
        // из PLAY_LONG_*. По умолчанию PLAY_LONG_SLEEP — долгое нажатие открывает
        // диалог «Таймер сна» (сэкономленная кнопка). Настройка живёт в разделе
        // «Управление» (там, где остальные назначаемые кнопки).
        internal const val KEY_PLAY_LONG = "play_long"
        internal const val PLAY_LONG_OFF = "off"
        internal const val PLAY_LONG_SLEEP = "sleep"

        // Назначаемые свайпы влево/вправо (#77). Значение pref — id действия;
        // палитра [GESTURE_ACTIONS] общая для диспетчера в ридере и экрана
        // «Жесты» в настройках.
        internal const val KEY_GESTURE_LEFT = "gesture_left"
        internal const val KEY_GESTURE_RIGHT = "gesture_right"
        internal const val G_PREV_SENT = "prev_sent"
        internal const val G_NEXT_SENT = "next_sent"
        internal const val G_PREV_PARA = "prev_para"
        internal const val G_NEXT_PARA = "next_para"
        internal const val G_PREV_CH = "prev_ch"
        internal const val G_NEXT_CH = "next_ch"
        internal const val G_NONE = "none"
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
            G_NEXT_PARA to R.string.g_action_next_para,
            G_PREV_PARA to R.string.g_action_prev_para,
            G_PLAY to R.string.g_action_play,
            G_PAUSE to R.string.g_action_pause,
            G_REPEAT to R.string.g_action_repeat,
            G_POSITION to R.string.g_action_position,
            G_SPEED_UP to R.string.g_action_speed_up,
            G_SPEED_DOWN to R.string.g_action_speed_down,
            G_TOC to R.string.g_action_toc,
            G_BOOKMARKS to R.string.g_action_bookmarks,
            G_ADD_BM to R.string.g_action_add_bm,
            G_NONE to R.string.g_action_none,
        )
    }
}
