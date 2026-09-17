package com.cuber.bookvoice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.Voice
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
import androidx.recyclerview.widget.RecyclerView
import com.cuber.bookvoice.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
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

    /** Книга, для которой уже собрана лента списка (портянка, msg4308).
     *  Состояние ОКНА, а не движка: смена книги — пересобрать ленту, смена
     *  главы внутри той же книги — только перевести каретку. */
    private var adapterBook: BookDocument? = null

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
    /** msg4673: последний процент, проговорённый во время драга по бегунку, и
     *  время той проговаривки — чтобы не повторяться и не забивать очередь речи. */
    private var lastScrubPct = -1
    private var lastScrubAt = 0L
    /** Портянка (msg4330): пока применяем место с прокрутки, сами список не
     *  прокручиваем — рука уже там, а лишний scrollToPosition дёрнул бы ленту
     *  (и снёс бы с экрана заголовок главы, на котором остановился читатель). */
    private var suppressScroll = false
    /** Портянка (msg4372): куда читатель увёл ленту рукой. Держим отдельно от
     *  «верхней строки на остановке»: между «увёл» и «отпустил» лента успевает
     *  дёрнуться назад за голосом (авто-прокрутка к читаемому), и к остановке
     *  цель потерялась бы. Ставится, когда читаемое предложение уехало с экрана;
     *  снимается на остановке прокрутки (там её и разбирает [applyScrollPlace]). */
    private var pendingScrollTarget: Pair<Int, Int>? = null
    /** Последний процент, показанный на экране чтения (msg4377) — чтобы не
     *  писать в журнал одну и ту же строку на каждое обновление статистики. */
    private var lastLoggedPct = -1
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

    // msg4308: список — вся книга одной лентой. Позиция в ленте (row) — не то же
    // самое, что предложение в главе, поэтому на входе переводим её обратно в
    // «главу + предложение» (placeOf), а в движок уже уходит привычное место.
    private val adapter = SentenceAdapter(
        onSentenceClick = { row -> onSentenceTapped(row) },
        onSentenceLongClick = { row -> onSentenceLongPressed(row) },
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

    override fun onPlayStateChanged() {
        updatePlayButton()
        // Портянка (msg4338): чтение встало, а лента стоит не на читаемом —
        // читатель уехал рукой и смотрит в другое место; оно и становится
        // местом книги, с него продолжит ▶. Читаемое на экране (обычная пауза,
        // конец главы) — трогать нечего. Ключ «Прокручивать к читаемому»
        // выключен — лента за голосом не ходит, брать её верх нельзя.
        if (!playing &&
            prefs.getBoolean(KEY_SCROLL, true) &&
            prefs.getBoolean(KEY_SCROLL_PLACE, true)
        ) {
            binding.sentenceList.postDelayed({ applyScrollPlace() }, 300)
        }
    }

    override fun onMovedInChapter(s: Int) {
        val row = adapter.flatOf(chapterIdx, s)
        adapter.setCurrent(row)
        scrollToSentence(row)
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

    /** Размер текста (msg5730) — читалка получает его до создания разметки, как
     *  и окна-секции: поднимается и текст книги, и подписи кнопок. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TextScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // msg5730: тема окна (обычная или контрастная) — до создания разметки.
        Palette.applyTo(this)
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
        // Портянка (msg4330): лента теперь одна на всю книгу, и рука уезжает по
        // ней дальше, чем уезжала по одной главе. Остановился — верхняя строка
        // экрана становится местом книги: слайдер, «Глава N из M» и процент едут
        // за ней, и это место книга запоминает. Пока голос читает — не
        // вмешиваемся, иначе чтение дёргается под пальцем.
        binding.sentenceList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy == 0) return
                noteScrollTarget()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) applyScrollPlace()
            }
        })

        installSwipe()

        // msg2403/2415: «Голос чтения» в нижнем ряду и «Свернуть» в шторке.
        // Открытая шторка держит нижние кнопки видимыми — скорость слышна на лету.
        binding.btnVoice.setOnClickListener { toggleVoicePanel() }
        binding.btnVoiceClose.setOnClickListener { hideVoicePanel() }
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
        // msg5220/msg5266: у каждой из четырёх кнопок читалки два действия из
        // общей палитры — короткое и долгое; оба назначаются в «Управлении»,
        // диспетчер тот же, что у свайпов. Долгое подтверждаем вибрацией: списка
        // на нём больше нет, и без толчка незрячему не за что зацепиться — сработал
        // ли вообще долгий тап. Действие «Выключено» молчит: обещать толчком
        // нечего.
        val buttonViews: List<Pair<ReaderButton, android.view.View>> = listOf(
            READER_BUTTONS[0] to binding.btnPrevChapter,
            READER_BUTTONS[1] to binding.btnNextChapter,
            READER_BUTTONS[2] to binding.btnPrevSentence,
            READER_BUTTONS[3] to binding.btnNextSentence,
        )
        for ((b, view) in buttonViews) {
            view.setOnClickListener { runGestureAction(readerButtonAction(prefs, b)) }
            view.setOnLongClickListener {
                val long = readerButtonLongAction(prefs, b)
                if (long != G_NONE) {
                    Vibra.confirm(this)
                    runGestureAction(long)
                }
                true
            }
        }
        // Кнопки скорости (#58): шаг по списку скорости, меняют темп на лету.
        binding.btnSpeedDown.setOnClickListener { nudgeSpeed(up = false) }
        binding.btnSpeedUp.setOnClickListener { nudgeSpeed(up = true) }
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
                if (scrubbing) {
                    // msg4673: пока палец на бегунке, syncSlider молчит (позицию
                    // книги применяем на отпускании) — и о движении ползунок не
                    // сообщал ничего: Сергей слышал процент только после
                    // отпускания. Проговариваем процент сами, по ходу.
                    if (fromUser) announceScrubPercent(progress)
                    return
                }
                if (progress != currentGlobal()) jumpToGlobal(progress, announce = false)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                scrubbing = true
                // msg4673: новый драг — проговариваем с первого же процента.
                lastScrubPct = -1
                lastScrubAt = 0L
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
        // msg5220: первый запуск после обновления — старые настройки шага и
        // режима глав переезжают в действия кнопок (до первого чтения имён).
        migrateReaderButtons()
        // msg5295: следом уровень «главы» кнопки гарнитуры переезжает в сам шаг
        // (читает уже перенесённый migrateReaderButtons ключ CH_NAV).
        migrateHeadsetStep()
        // Возврат из экрана настроек: там могли поменять, какие элементы
        // читалки показывать, скорость и голос — применяем к живой книге.
        applyReaderUi()
        // msg5220: имена и короткие подписи четырёх кнопок читалки — по их
        // текущим действиям (вернулись из читалки после долгого нажатия —
        // переименовываем).
        updateButtonNames()
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

    /** Плеер, если движок ещё подключён к окну. null — читалка успела закрыться,
     *  и [ReaderEngine.player] уже обнулён ([ReaderEngine.close]). Отложенные
     *  хвосты переключения движка обязаны это проверять: msg5857 — книга не
     *  открылась, окно ушло на полку, а обработчик переключения ещё не сработал,
     *  и обращение к player уронило приложение. */
    private val livePlayer: SpeechPlayer?
        get() = ReaderEngine.player

    private fun restoreEngineAndVoice() {
        val savedEngine = prefs.getString(KEY_ENGINE, null)
        val defaultEngine = player.defaultEngine
        if (savedEngine != null && savedEngine != defaultEngine) {
            player.setEngine(savedEngine) { ok ->
                handler.post {
                    // Окно могло закрыться, пока движок переключался: голос
                    // выставится при следующем открытии книги.
                    val p = livePlayer ?: return@post
                    if (ok) {
                        voiceName?.let { p.selectVoice(it) }
                    } else {
                        prefs.edit().remove(KEY_ENGINE).apply()
                        p.setEngine(null) { _ -> livePlayer?.let { v -> voiceName?.let { v.selectVoice(it) } } }
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
        if (hasFocus) {
            Diag.log(this, "a11y", "окно читалки в фокусе; window title = «${title}»")
            // msg5931: система вправе вернуть системные кнопки сама (уход в фон и
            // возврат) — решение принимаем заново, а не помним с прошлого раза.
            ReaderBars.reapply()
        }
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
            // msg4322: к месту добавлен процент ИЗ ЗАПИСИ КНИГИ — та цифра, которую
            // показывает полка. Расхождение с местом сразу видно в логе.
            Diag.log(
                this, "activity",
                "место из prefs (последняя книга): глава $ch, предл. $s (в записи ${rec?.readPct ?: -1}%)"
            )
        } else if (rec != null) {
            ch = rec.chapter
            s = rec.sentence
            Diag.log(this, "activity", "место из записи: глава $ch, предл. $s (${rec.readPct}%)")
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
        if (rec != null) {
            // msg4809/4811: в шапке название книги — заголовком, автор — второй
            // строкой ОТДЕЛЬНЫМ элементом (свайп вправо от названия). Имя окна —
            // только название: при входе объявляется книга.
            val line = rec.displayTitle
            setReaderHeader(line, rec.author)
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

    /** msg5475/5499: файл не открылся — скан-PDF без текстового слоя, PDF под
     *  паролем, нечитаемый формат. Говорим причину и УХОДИМ назад (на полку, в
     *  каталог или в то приложение, откуда книгу открыли), а не оставляем пустое
     *  окно читалки: раньше тут был showEmpty(), и окно надо было закрывать
     *  руками. Сергей выбрал этот вариант — «сказал и вернулся» — вместо окна с
     *  кнопкой «Понятно» (msg5499). Тост гаснет сам, окно тоста живёт отдельно от
     *  активити, поэтому finish() его не снимает. */
    private fun failOpen(message: String) {
        // msg5857: адрес книги в строке отказа — иначе по логу не понять, ЧТО
        // именно не прочиталось (своя копия, ссылка чужого проводника, файл из
        // выбранной папки), а это первое, что нужно при разборе.
        Diag.log(this, "activity", "не открылось: $message — возвращаюсь назад; адрес: $currentUri")
        toast(message)
        Vibra.error(this)
        finish()
    }

    private fun showEmpty() {
        adapter.clear()
        adapterBook = null
        closeSearchPanel()
        refreshChrome()
    }

    /** Уйти на полку — дом приложения (редизайн msg1676+). Библиотека это
     *  КОРНЕВОЕ окно задачи; между ним и ридером может лежать окно Каталога
     *  (книга открыта из каталога) — уходим на корень CLEAR_TOP, чтобы снять
     *  всё, что поверх полки. Место сохранено (onPause). Если голос звучит —
     *  #38 шаг 2: он продолжает читать без окна, на полке (onDestroy зовёт
     *  windowGoneWhilePlaying); если книга на паузе — карточка в шторке остаётся
     *  и ждёт владельца (msg4629, windowGoneWhilePaused); гаснет всё как раньше
     *  (close) только когда книги нет или он сам вышел «Выходом». */
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

    /** msg4665: книга скачана из сетевой библиотеки в формате, который BookVoice
     *  не читает. Говорим об этом прямо: «формат не поддерживается или файл
     *  повреждён» читается как поломка, хотя файл цел — просто открыть его можно
     *  другой программой. null — формат обычный, отвечает общая фраза. */
    private fun unreadableFormatMessage(): String? {
        val name = currentName?.lowercase(Locale.ROOT) ?: return null
        val ext = UNREADABLE_EXTS.firstOrNull { name.endsWith(".$it") } ?: return null
        return getString(R.string.book_format_unreadable, ext.uppercase(Locale.ROOT))
    }

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
                    // msg3142: для PDF говорим правду о том, что происходит (извлечение
                    // текста); для остальных форматов — общая фраза.
                    val longLoadText =
                        if (looksLikePdf(uri)) getString(R.string.long_load_pdf_speech)
                        else getString(R.string.long_load_speech)
                    binding.tvHeader.announceForAccessibility(longLoadText)
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
            // msg6042: серия книги — дозаполняем запись полки, если её там ещё нет
            // (скан папки проходит не у всех). Читаем только начало файла и только
            // пока запись не проверена — см. [seriesFillFor].
            val seriesUpd = seriesFillFor(uri)
            handler.post {
                // Запись книги можно обновлять и до проверок ниже: это просто
                // метаданные полки, к движку и разбору они отношения не имеют.
                seriesUpd?.let { runCatching { BookStore.upsert(this, it) } }
                // msg2679: разбор кончился — состояние загрузки снято, дальше
                // refreshChrome сам решает по факту (есть книга или пусто).
                bookLoading = false
                // msg2787: окно закрыли, пока книга разбиралась (выход на полку и
                // т.п.) — движок уже отключён (player пуст), трогать его нельзя:
                // запоздавший результат разбора тихо игнорируем, иначе падение
                // «ReaderEngine не подключён» на плеере.
                if (isDestroyed || isFinishing || ReaderEngine.player == null) return@post
                if (doc == null) {
                    failOpen(unreadableFormatMessage()
                        ?: "Не удалось открыть: формат не поддерживается или файл повреждён")
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
                    failOpen(getString(unreadableRes))
                    return@post
                }
                if (!doc.hasText) {
                    failOpen(unreadableFormatMessage()
                        ?: "Не удалось открыть: формат не поддерживается или файл повреждён")
                    return@post
                }
                currentUri = uri.toString()
                book = doc
                // msg5931: книга открылась — в режиме «скрывать, пока открыта
                // книга» системная полоса уходит вместе с ней.
                ReaderBars.bookOpened()
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

    /** PDF ли открываем (по имени файла/дисплея) — для честной фразы долгой загрузки. */
    private fun looksLikePdf(uri: Uri): Boolean {
        val name = if (uri.scheme == "file") {
            uri.lastPathSegment ?: ""
        } else {
            queryDisplayName(uri) ?: uri.lastPathSegment ?: ""
        }
        return name.lowercase(Locale.ROOT).endsWith(".pdf")
    }

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
        // Короткий блок в начале FB2 (титул, копирайт) пропускается всегда:
        // книга начинается с первой главы. Настройка убрана (msg4653).
        return BookParser.parse(name, bytes)
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

    /** msg6042: дозаполнить серию книги в записи полки. null — делать нечего:
     *  записи нет, она уже проверена (в том числе «проверено, серии нет»), формат
     *  серию не несёт или файл не прочитался. Иначе — готовая запись для upsert.
     *
     *  Читаем только начало файла: книга бывает в десятки мегабайт, а серия лежит
     *  в первых килобайтах. Усечённый архив разобрать нельзя — у книг в архиве
     *  серия появится от скана папки (там файл читается целиком). */
    private fun seriesFillFor(uri: Uri): BookRecord? = runCatching {
        val rec = BookStore.byUri(this, uri.toString()) ?: return@runCatching null
        if (rec.series != null) return@runCatching null
        if (!BookParser.canCarrySeries(rec.name)) return@runCatching null
        val max = BookParser.META_PREFIX
        val head = contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = ins.read(buf, total, max - total)
                if (n < 0) break
                total += n
            }
            buf.copyOf(total)
        } ?: return@runCatching null
        val meta = BookParser.peekMeta(rec.name, head) ?: return@runCatching null
        rec.copy(series = meta.series ?: "", seriesNo = meta.seriesNo)
    }.getOrNull()

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
        // гарнитура, «волшебное касание», кнопка в шторке). Если книга на паузе —
        // msg4629: карточка в шторке остаётся (владелец слушает с неё: «пусть
        // висит, пока я не выйду сам»), движок держит книгу без звука. И только
        // если книги нет вовсе (или он сам вышел «Выходом») — как раньше: движок
        // гасит сессию, отдаёт фокус и глушит авто-продолжение (см.
        // ReaderEngine.close): медиа-кнопки не должны остаться у нас, а чтение не
        // должно «ожить» без окна.
        // msg5931: окно читалки уходит — «книга открыта» больше не верно (до
        // сброса active: применять решение можно только к живому окну).
        ReaderBars.bookClosed()
        if (active === this) active = null
        when {
            ReaderEngine.playing -> ReaderEngine.windowGoneWhilePlaying()
            ReaderEngine.keepCardWhenWindowGone() -> ReaderEngine.windowGoneWhilePaused(this)
            else -> ReaderEngine.close()
        }
        super.onDestroy()
    }

    // ---------------- Отображение текущей главы ----------------

    private fun loadChapter() {
        val bk = book ?: return
        // Лента собирается один раз на книгу: смена главы при чтении больше не
        // пересобирает список (раньше здесь submit() одной главы и убивал
        // прокрутку — начало книги было недостижимо, msg4288).
        if (adapterBook !== bk) {
            adapter.submitBook(bk.chapters)
            adapterBook = bk
        }
        val cur = bk.chapters.getOrNull(chapterIdx)?.sentences ?: emptyList()
        if (cur.isEmpty()) {
            adapter.setCurrent(-1)
            updatePosition()
            return
        }
        sentenceIdx = sentenceIdx.coerceIn(0, cur.lastIndex)
        refreshSelectionMarkers()
        showCurrent()
    }

    private fun showCurrent() {
        val cur = book?.chapters?.getOrNull(chapterIdx)?.sentences ?: return
        if (cur.isEmpty()) return
        val row = adapter.flatOf(chapterIdx, sentenceIdx.coerceIn(0, cur.lastIndex))
        adapter.setCurrent(row)
        scrollToSentence(row)
        updatePosition()
    }

    /** Прокрутить список к строке ленты, если в настройках включено
     *  «Прокручивать к читаемому предложению». */
    private fun scrollToSentence(row: Int) {
        if (row < 0) return
        if (suppressScroll) return
        // Портянка (msg4372): читатель увёл ленту и ещё не отпустил — за голосом
        // не подтягиваем, иначе лента вырывается у него из-под руки и уезжает
        // назад. Цель помним, на остановке прокрутки к ней вернёмся.
        if (pendingScrollTarget != null) return
        if (prefs.getBoolean(KEY_SCROLL, true)) layoutManager.scrollToPosition(row)
    }

    /** Портянка (msg4330): читатель доехал рукой до места — оно и есть место
     *  книги. Прокрутка списка раньше была «посмотреть»: слайдер, «Глава N из M»
     *  и процент стояли на месте чтения, а рука уезжала отдельно, и книга
     *  запоминала не то, что на экране. Теперь на остановке прокрутки верхняя
     *  строка ленты становится новым местом.
     *
     *  Три условия, чтобы не мешать: голос молчит (иначе чтение прыгало бы за
     *  пальцем), палец не на слайдере, и прежнее место уже уехало с экрана —
     *  докрутка вокруг читаемого предложения и наша же авто-прокрутка за
     *  голосом места не меняют. */
    private fun applyScrollPlace() {
        val bk = book ?: return
        // msg4377: смена места приходит с задержкой 300 мс после остановки
        // чтения — если читатель в этот момент уже вышел из книги, место
        // двигать поздно: сохранение при выходе прошло, и полка разошлась бы
        // с книгой.
        if (isFinishing || isDestroyed) return
        // Цель копится в onScrolled; её нет — лента стоит на читаемом, брать нечего.
        val target = pendingScrollTarget
        pendingScrollTarget = null
        if (target == null) return
        if (!prefs.getBoolean(KEY_SCROLL_PLACE, true)) return
        if (scrubbing) return
        if (bk.chapters.getOrNull(target.first)?.sentences.isNullOrEmpty()) return
        if (playing) {
            // Вариант А (msg4372): чтение идёт, читатель отпустил прокрутку —
            // голос переезжает на верхнюю строку и читает дальше с неё. Галочка
            // выключена — во время чтения лента остаётся только «посмотреть».
            if (!prefs.getBoolean(KEY_SCROLL_FOLLOW, true)) return
            goTo(target.first, target.second)
            Diag.log(
                this, "activity",
                "скачок чтения по прокрутке: глава ${target.first}, предл. ${target.second}"
            )
            return
        }
        val sameChapter = target.first == chapterIdx
        if (!ReaderEngine.placeFromScroll(target.first, target.second)) return
        suppressScroll = true
        if (sameChapter) onMovedInChapter(sentenceIdx) else onChapterLoaded()
        suppressScroll = false
        Diag.log(this, "activity", "место по прокрутке: глава $chapterIdx, предл. $sentenceIdx")
    }

    /** Пока читатель ведёт ленту рукой, помним верхнюю строку — она станет новым
     *  местом (msg4372). Наша авто-прокрутка за голосом цель не ставит: при ней
     *  читаемое предложение остаётся на экране, а условие — оно уехало вниз. */
    private fun noteScrollTarget() {
        if (book == null || scrubbing) return
        if (!prefs.getBoolean(KEY_SCROLL_PLACE, true)) return
        val top = layoutManager.findFirstVisibleItemPosition()
        if (top < 0) return
        val p = adapter.placeOf(top) ?: return
        val curRow = adapter.flatOf(chapterIdx, sentenceIdx)
        // Читаемое предложение снова на экране — читатель вернулся к своему
        // месту, менять нечего: прежнюю цель забываем.
        if (p.first == chapterIdx && p.second == sentenceIdx ||
            curRow >= top && curRow <= layoutManager.findLastVisibleItemPosition()
        ) {
            pendingScrollTarget = null
            return
        }
        pendingScrollTarget = p
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
        // msg5220: по умолчанию свайп ходит по всем заголовкам — как ходил
        // раньше (прежний режим по умолчанию был «по всем заголовкам»).
        val def = if (dir < 0) G_PREV_HEADER else G_NEXT_HEADER
        runGestureAction(prefs.getString(key, def) ?: def)
    }

    /** Выполнить действие свайпа по его id (см. [GESTURE_ACTIONS]). */
    private fun runGestureAction(act: String) {
        if (book == null) return
        when (act) {
            G_PREV_SENT -> jumpAndAnnounce { moveBySentence(-1) }
            G_NEXT_SENT -> jumpAndAnnounce { moveBySentence(+1) }
            // msg5234: прыжок — сразу N предложений в сторону; число у каждого
            // направления своё (две строки в «Управлении»).
            G_PREV_SENT_N -> jumpAndAnnounce {
                moveBySentences(-1, jumpCount(prefs, forward = false))
            }
            G_NEXT_SENT_N -> jumpAndAnnounce {
                moveBySentences(+1, jumpCount(prefs, forward = true))
            }
            G_PREV_PARA -> jumpAndAnnounce { moveByParagraph(-1) }
            G_NEXT_PARA -> jumpAndAnnounce { moveByParagraph(+1) }
            G_PREV_CH -> jumpAndAnnounce { moveByChapter(-1, CH_NAV_CHAPTERS) }
            G_NEXT_CH -> jumpAndAnnounce { moveByChapter(+1, CH_NAV_CHAPTERS) }
            G_PREV_MAJOR -> jumpAndAnnounce { moveByChapter(-1, CH_NAV_MAJOR) }
            G_NEXT_MAJOR -> jumpAndAnnounce { moveByChapter(+1, CH_NAV_MAJOR) }
            G_PREV_HEADER -> jumpAndAnnounce { moveByChapter(-1, CH_NAV_ALL) }
            G_NEXT_HEADER -> jumpAndAnnounce { moveByChapter(+1, CH_NAV_ALL) }
            // msg2547: свайп можно назначить на «Ничего не делать» — просто игнорируем.
            G_NONE -> {}
            G_PLAY -> togglePlay()
            G_PAUSE -> if (playing) pausePlayback(keepFocus = true)
            G_REPEAT -> startSpeakingCurrent()
            G_POSITION -> announcePosition()
            G_SPEED_UP -> nudgeSpeed(up = true)
            G_SPEED_DOWN -> nudgeSpeed(up = false)
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

    /** Прыжок (msg5234): шаг сразу на [count] предложений в сторону [delta] — по
     *  книге целиком, сквозь главы, ровно тем же правилом, что «След. предложение»
     *  (граница главы переходит в соседнюю). У края книги останавливаемся там, где
     *  он: доехать на меньшее число предложений лучше, чем не сдвинуться вовсе.
     *  Если не сдвинулись ни на шаг — позицию не трогаем (иначе озвучка «Где я»
     *  повторила бы то же место как результат перемотки). */
    private fun moveBySentences(delta: Int, count: Int) {
        val bk = book ?: return
        var ch = chapterIdx
        var s = sentenceIdx
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
        if (left == count) return
        goTo(ch, s)
    }

    /** Шаг по «главам» ([mode] — по какому уровню ходим: [CH_NAV_MAJOR] — крупные
     *  разделы, [CH_NAV_CHAPTERS] — главы без вложенных подразделов, [CH_NAV_ALL] —
     *  все заголовки). msg5220: уровень больше не настройка, а само действие
     *  кнопки/свайпа, поэтому приходит параметром. */
    private fun moveByChapter(delta: Int, mode: String = CH_NAV_ALL) {
        val stops = chapterStopIndexes(mode)
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
    private fun chapterStopIndexes(mode: String = CH_NAV_ALL): IntArray {
        val bk = book ?: return intArrayOf()
        val size = bk.chapters.size
        if (mode != CH_NAV_MAJOR && mode != CH_NAV_CHAPTERS) {
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

    /** Имена четырёх кнопок читалки для скринридера и короткие надписи на них
     *  (msg5220): кнопка называется тем действием, которое на ней висит. Раньше
     *  имя следовало шагу из настроек (msg2471/2531) — теперь шаг и есть
     *  назначенное действие, поэтому источник один: палитра действий. Короткие
     *  подписи держим той же длины, что прежние «Пред.»/«След.»/«◀ Глава» —
     *  растягивать их запрещено (msg2431). Вызывается в onStart. */
    private fun updateButtonNames() {
        val views = listOf(
            binding.btnPrevChapter, binding.btnNextChapter,
            binding.btnPrevSentence, binding.btnNextSentence,
        )
        for (i in READER_BUTTONS.indices) {
            val b = READER_BUTTONS[i]
            views[i].contentDescription = readerButtonName(this, prefs, b)
            views[i].text = actionShort(this, prefs, readerButtonAction(prefs, b))
        }
    }


    /** msg5220: разовый перенос старых настроек в действия кнопок. Шаг «Пред./
     *  След.» и режим «Кнопки глав» были отдельными настройками — переводим их
     *  в действие каждой кнопки (поведение сохраняем как было), свайпы, у которых
     *  стоял «глава по режиму», разворачиваем в тот же конкретный уровень, а
     *  парные галочки конструктора — в четыре одиночных. KEY_STEP/KEY_CH_NAV
     *  после этого никто не пишет; читает их только разовый перенос —
     *  KEY_CH_NAV, следом за нами, в migrateHeadsetStep (msg5295). */
    private fun migrateReaderButtons() {
        if (prefs.getBoolean(KEY_BTN_MIGRATED, false)) return
        val chMode = prefs.getString(KEY_CH_NAV, CH_NAV_ALL) ?: CH_NAV_ALL
        val step = prefs.getString(KEY_STEP, STEP_SENTENCE) ?: STEP_SENTENCE
        fun level(prev: Boolean): String = when (chMode) {
            CH_NAV_SENT -> if (prev) G_PREV_SENT else G_NEXT_SENT
            CH_NAV_PARAGRAPH -> if (prev) G_PREV_PARA else G_NEXT_PARA
            CH_NAV_MAJOR -> if (prev) G_PREV_MAJOR else G_NEXT_MAJOR
            CH_NAV_CHAPTERS -> if (prev) G_PREV_CH else G_NEXT_CH
            else -> if (prev) G_PREV_HEADER else G_NEXT_HEADER
        }
        fun byStep(prev: Boolean): String = when (step) {
            STEP_PARAGRAPH -> if (prev) G_PREV_PARA else G_NEXT_PARA
            STEP_CHAPTER -> level(prev)
            else -> if (prev) G_PREV_SENT else G_NEXT_SENT
        }
        val e = prefs.edit()
        e.putString(KEY_BTN_PREV_SENT, byStep(true))
        e.putString(KEY_BTN_NEXT_SENT, byStep(false))
        e.putString(KEY_BTN_PREV_CH, level(true))
        e.putString(KEY_BTN_NEXT_CH, level(false))
        // Свайп «глава» означал «по режиму» — разворачиваем в конкретный уровень,
        // иначе после обновления он бы молча сменил смысл.
        for (k in listOf(KEY_GESTURE_LEFT, KEY_GESTURE_RIGHT)) {
            when (prefs.getString(k, null)) {
                G_PREV_CH -> e.putString(k, level(true))
                G_NEXT_CH -> e.putString(k, level(false))
            }
        }
        // Конструктор экрана: пары → по одной кнопке.
        e.putBoolean(KEY_UI_PREV_SENT, prefs.getBoolean(KEY_UI_SENT, true))
        e.putBoolean(KEY_UI_NEXT_SENT, prefs.getBoolean(KEY_UI_SENT, true))
        e.putBoolean(KEY_UI_PREV_CH, prefs.getBoolean(KEY_UI_CHAPTERS, true))
        e.putBoolean(KEY_UI_NEXT_CH, prefs.getBoolean(KEY_UI_CHAPTERS, true))
        // msg5230: у «главы» на гарнитуре уровень жил в KEY_CH_NAV, пока шаг не
        // переехал в сам пункт списка (msg5295). Мелкие значения (предложение/
        // абзац) движок и раньше вёл как обычные главы — записываем это прямо,
        // чтобы перенос шага гарнитуры (migrateHeadsetStep) взял то, что человек
        // действительно слышит, а не выбор, которого у «главы» нет.
        if (chMode != CH_NAV_MAJOR && chMode != CH_NAV_CHAPTERS && chMode != CH_NAV_ALL) {
            e.putString(KEY_CH_NAV, CH_NAV_CHAPTERS)
        }
        e.putBoolean(KEY_BTN_MIGRATED, true)
        e.apply()
    }

    /** msg5295: уровень «главы» кнопки гарнитуры переехал из отдельной настройки
     *  («Шаг „главы“ на гарнитуре», ключ CH_NAV) в сам шаг кнопки — в списке шагов
     *  стало три главы-пункта вместо одного. Переносим по разу: у кого стояла
     *  «глава», получает именно тот уровень, по которому он и ходил. Свой
     *  рубильник, а не общий KEY_BTN_MIGRATED: тот у владельца уже поднят, и до
     *  его настроек гарнитуры мы бы не добрались. Значение по умолчанию у ключа
     *  берём CH_NAV_ALL — так его читал движок до этой правки
     *  (ReaderEngine.chapterStopIndexes), то есть ровно то, что человек слышал.
     *  Сам движок ключ больше не читает: уровень приходит от шага. */
    private fun migrateHeadsetStep() {
        if (prefs.getBoolean(KEY_HS_MIGRATED, false)) return
        val level = when (prefs.getString(KEY_CH_NAV, CH_NAV_ALL)) {
            CH_NAV_MAJOR -> HS_MAJOR
            CH_NAV_ALL -> HS_HEADER
            else -> HS_CHAPTER
        }
        val e = prefs.edit()
        for (key in listOf(KEY_HS_PREV, KEY_HS_NEXT)) {
            if ((prefs.getString(key, HS_SENTENCE) ?: HS_SENTENCE) == HS_CHAPTER) {
                e.putString(key, level)
            }
        }
        e.putBoolean(KEY_HS_MIGRATED, true)
        e.apply()
    }

    private fun goTo(ch: Int, s: Int) = ReaderEngine.goTo(ch, s)

    /** Двойной тап по предложению (при чтении TalkBack'ом — активация строки).
     *  Всегда переводит читаемую позицию на это предложение; если чтение ещё
     *  не шло и в настройках включено «двойной тап начинает чтение» — начинаем
     *  озвучивать отсюда (см. [KEY_TAP_TO_PLAY]). */
    private fun onSentenceTapped(row: Int) {
        if (book == null) return
        // Строка ленты → место в книге (у заголовка главы это её начало).
        val p = adapter.placeOf(row) ?: return
        goTo(p.first, p.second)
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

    /** msg4809/4811: шапка читалки — ДВА элемента: название (заголовок окна, первая
     *  остановка) и под ним автор (вторая строка, своя остановка — свайп вправо от
     *  названия). Автора нет — вторая строка прячется целиком, пустого элемента
     *  обход не получает. */
    private fun setReaderHeader(title: String, author: String?) {
        binding.tvHeader.text = title
        val a = author?.takeIf { it.isNotBlank() }
        binding.tvHeaderAuthor.text = a.orEmpty()
        binding.tvHeaderAuthor.visibility = if (a == null) View.GONE else View.VISIBLE
    }

    private fun refreshChrome() {
        val bk = book
        // displayTitle (msg2559): ручное название из «Переименовать» > метаданные
        // > имя файла; для открытой книги непусто, no_book остаётся только без
        // книги и без имени.
        val t = docDisplayTitle()
        // msg4809/4811: автор — второй строкой под названием, своим элементом.
        val headerText = t ?: getString(R.string.no_book)
        setReaderHeader(headerText, bk?.author)
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
        // msg5561: вместе с подписью меняем и значок (треугольник ↔ две палочки).
        // Вторым источником озвучки он не становится: значок TalkBack не
        // объявляет, объявление по-прежнему одно — от текста (msg1644 был про
        // значок В САМОЙ строке кнопки, и он читался отдельным словом).
        // Приведение нужно потому, что ViewBinding типизирует поле по тегу
        // <Button>, а в Material-теме объект на самом деле MaterialButton.
        (binding.btnPlayPause as? MaterialButton)?.setIconResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        // #75 (msg5632): «Читать/Пауза» в панели голоса — первая строка набора
        // VoicePicker; подпись меняем там же, где и всё остальное в наборе.
        voicePicker?.refreshPlayLabel()
        MediaSessionService.setPlaying(playing)
    }

    /** Конструктор экрана (#58): по настройкам прячет или показывает элементы
     *  читалки. Вызывается при старте и при возврате из «Настроек» (onStart). */
    private fun applyReaderUi() {
        fun show(key: String, v: View) {
            v.visibility = if (prefs.getBoolean(key, true)) View.VISIBLE else View.GONE
        }
        // msg5220: четыре кнопки прячутся по одной — после переназначения пара
        // врала бы (строка «главы», а за ней давно оглавление).
        show(KEY_UI_PREV_SENT, binding.btnPrevSentence)
        show(KEY_UI_NEXT_SENT, binding.btnNextSentence)
        show(KEY_UI_PLAY, binding.btnPlayPause)
        show(KEY_UI_PREV_CH, binding.btnPrevChapter)
        show(KEY_UI_NEXT_CH, binding.btnNextChapter)
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
        "Скорость: " + RateSteps.label(rate)

    /** #102: какая скорость сейчас уместна — своя у книги (если запомнена) или
     *  глобальная из настроек. */
    private fun currentSpeed(): Float {
        if (book == null) return prefs.getFloat(KEY_SPEED, 1f)
        val bs = currentBookRecord()?.voiceSpeed
        return if (bs != null && bs > 0f) bs else prefs.getFloat(KEY_SPEED, 1f)
    }

    private fun refreshSpeedValue() {
        binding.tvSpeed.text = speedText(currentSpeed())
        // #57: альтернативный способ озвучки — подхватываем на возврате из
        // «Настроек», не переоткрывая книгу.
        val alt = prefs.getBoolean(KEY_ALT_VOICE, false)
        ReaderEngine.player?.altDirect = alt
        // msg5955: бесшовная передача — уже не галочка, а поведение: включена
        // всегда, кроме альтернативной озвучки (там прицеплять нечего).
        ReaderEngine.player?.gapless = !alt
        // msg5604: строка «Пауза между фразами» — подхватываем так же, на возврате
        // из «Настроек» (движок уже играющие фразы не переигрывает, значение
        // работает для следующих заготовок).
        ReaderEngine.player?.pauseKeepMs =
            prefs.getInt(KEY_PAUSE_KEEP, PAUSE_KEEP_DEFAULT)
    }

    /** Кнопки «Медленнее/Быстрее» (#58): шаг 0.1 по всему диапазону 0.5–4.0
     *  (RateSteps.SPEED, msg3929/msg3933) — ровно как у слайдера в диалоге
     *  голоса. Если у книги запомнена своя
     *  скорость (#102) — меняем именно её, глобальную не трогаем.
     *  Если книга на паузе, новую скорость озвучиваем (во время чтения её
     *  слышно и так). */
    private fun nudgeSpeed(up: Boolean) {
        val cur = currentSpeed()
        val rate = RateSteps.neighbour(RateSteps.SPEED, cur, up)
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
        // msg4388: ползунок озвучивает TalkBack, и процент он считает сам —
        // делением progress/max с округлением, а строка статистики и запись
        // книги отбрасывают дробь (Сергей: «в книге на 1% меньше, чем на
        // полке»). Задаём ползунку наше число явно, чтобы он говорил ровно то
        // же, что видно в строке «(N%)» и что уезжает на полку. Приём тот же,
        // что у ползунков в диалогах ридера (addRateSliderTo/addVolumeSliderTo).
        if (Build.VERSION.SDK_INT >= 30) binding.seekProgress.stateDescription = "${readPercent()}%"
    }

    /** msg4673: процент перемотки во время пальцевого драга по бегунку — чтобы
     *  он звучал ПО ХОДУ движения, а не только после отпускания. [progress] —
     *  значение самого бегунка: во время драга оно опережает позицию книги (её
     *  применяем на отпускании, scrubbing), поэтому считаем процент от бегунка —
     *  той же арифметикой, что [readPercent] (позиция * 100 / максимум).
     *
     *  Говорим через [View.announceForAccessibility] (как [announcePosition] на
     *  отпускании) — это канал, который скринридер Сергея уже слышит. Значение
     *  молчим дважды: только когда меняется целый процент (иначе на каждый пиксель
     *  драга) и не чаще, чем раз в [SCRUB_ANNOUNCE_MS] — быстрый проезд по всей
     *  книге иначе забил бы очередь речи десятками чисел. */
    private fun announceScrubPercent(progress: Int) {
        val max = binding.seekProgress.max
        if (max <= 0) return
        val pct = progress * 100 / max
        if (pct == lastScrubPct) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScrubAt < SCRUB_ANNOUNCE_MS) return
        lastScrubPct = pct
        lastScrubAt = now
        binding.sentenceList.announceForAccessibility("$pct%")
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
        val pct = readPercent()
        binding.tvStats.text =
            "Прочитано ~${fmtMin(readWords / wpm)}, осталось ~${fmtMin(remainWords / wpm)} ($pct%)"
        // msg4377 (диагностика): Сергей сравнил «в полке 2%, а в книжке 3%».
        // В журнал пишем КАЖДУЮ смену процента на экране — тогда видно, что
        // именно показывала книга в тот момент, когда полка показывала другое.
        if (pct != lastLoggedPct) {
            lastLoggedPct = pct
            // msg4388: рядом с процентом на экране пишем процент ИЗ ЗАПИСИ книги —
            // то число, которое прямо сейчас показала бы полка. Если Сергей
            // видит «в книге на 1% меньше», здесь будет видно, расходятся ли
            // числа в один и тот же момент или дело в разном времени записи.
            val recPct = currentUri?.let { BookStore.byUri(this, it)?.readPct } ?: -1
            Diag.log(this, "activity",
                "на экране $pct% (глава $chapterIdx, предл. $sentenceIdx), в записи $recPct%")
        }
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
        // msg6171: оглавление открывается на текущей главе, а не с начала
        // списка. setItems всегда строит список с нулевой прокруткой, поэтому
        // диалог создаём сами и после показа ставим выбор на строку текущей
        // главы (первой строкой идут закладки, если они есть, — отсюда сдвиг).
        val dlg = MaterialAlertDialogBuilder(this)
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
            .create()
        dlg.setOnShowListener {
            val list = dlg.listView ?: return@setOnShowListener
            val row = chapterIdx + if (bmHeader == null) 0 else 1
            if (row in items.indices) list.setSelection(row)
        }
        dlg.show()
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
        // msg6179: как и в каталоге (msg6175) — сперва глушим диктора. Своё
        // объявление кнопки («Поиск», жест долгого нажатия) он читает в тот же
        // миг, и этот голос уходил в микрофон вместо слова владельца. Пауза
        // 400 мс — чтобы interrupt() попал в речь, а не до неё.
        A11y.hush(this)
        Diag.log(this, "voice", "долгое нажатие 🔍 в книге: глушу диктора, микрофон через 400 мс")
        binding.root.postDelayed({
            if (!isFinishing && book != null) startVoiceSearch()
        }, 400)
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
        // msg6179: любой вход в голосовой ввод начинается с тишины — диктор не
        // должен говорить в микрофон (кнопка-микрофон в панели, долгое нажатие 🔍).
        A11y.hush(this)
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
                    setTextColor(Palette.DANGER)
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
        // msg4464 (тестер @TifloMir через Сергея): из открытой книги хочется
        // сразу прыгнуть в сетевые библиотеки, без захода на полку. Пункт стоит
        // рядом с «Библиотека» — там же, где глаз ищет книжные места.
        actions.add(getString(R.string.catalog_title) to {
            startActivity(Intent(this, CatalogWindowActivity::class.java))
        })
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
            (if (quickPanelVisible()) getString(R.string.quick_hide) else getString(R.string.quick_show))
                to { toggleQuickPanel() }
        )
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
        // клик открывает окно таймера (см. openSleepTimerWindow).
        actions.add(sleepTimerMenuLabel() to { openSleepTimerWindow() })
        // msg2762: «Голос чтения» убран из меню — дубль кнопки «Голос» нижнего ряда.
        // msg4693: «Настройки» — прямо перед «Выходом», как в остальных меню.
        actions.add(getString(R.string.settings_btn) to { startSettingsTab() })
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
            // msg3146: озвучиваем ячейку названием книги — без служебного префикса
            // «Быстрый доступ, кнопка N» (скринридер и так сообщает, что это кнопка).
            // msg3170: но у закреплённой книги признак «закреплено» обязан остаться —
            // без него не понять, какая кнопка держит книгу намертво.
            btn.contentDescription = if (cell.pinned) {
                getString(R.string.quick_pinned_cd, title)
            } else {
                title
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
            PLAY_LONG_SLEEP -> openSleepTimerWindow()
            // PLAY_LONG_OFF — ничего не делаем; событие уже съедено слушателем.
        }
    }

    /** msg4993: выбор таймера сна — теперь ОКНОМ, а не диалогом (msg4981).
     *
     *  Диалог умел только «выбрать время»: ни активного режима с остатком, ни
     *  пути к настройкам жестов и сигнала он не показывал. В окне тот же выбор
     *  плюс строка «Настройки таймера» — жесты, чувствительность, сигнал и
     *  проверки без ожидания. Таймер по-прежнему живёт в движке и работает при
     *  погашенном экране (см. ReaderEngine.setSleepTimerMinutes). */
    private fun openSleepTimerWindow() {
        startActivity(Intent(this, SleepTimerWindowActivity::class.java))
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
        val rec = currentBookRecord() ?: return
        // msg3550: пустой список голосов — это «ещё не знаю», а не «голоса нет».
        // Раньше галочка, закрытая в такой момент, записывала книге голос null —
        // свой голос книги затирался насовсем.
        val list = player.voices
        val vce = voiceName?.takeIf { player.isReady && list.any { v -> v.name == it } }
            ?: rec.voice.takeIf { list.isEmpty() }
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
        val list = player.voices
        val vc = voiceName?.takeIf { list.any { v -> v.name == it } }
        if (vc != null) {
            e.putString(KEY_VOICE, vc)
            // #70: вместе с глобальным голосом помним и его язык — иначе строка
            // «Язык» в Настройках осталась бы от прежнего голоса (в панели читалки
            // язык — только фильтр списка и в prefs не идёт, а тут его надо
            // вывести из выбранного голоса).
            // msg5622: но НЕ затираем язык, выбранный руками, если движок его
            // сейчас не знает (в списке был запасной язык) — иначе выбор Сергея
            // пропадёт молча. Такой выбор ждёт движка, который язык узнает.
            val want = prefs.getString(KEY_VOICE_LANG, null)
            list.firstOrNull { it.name == vc }?.let { v ->
                if (want == null || VoicePick.codeOf(v) == want) {
                    e.putString(KEY_VOICE_LANG, VoicePick.codeOf(v))
                }
            }
        }
        // msg3550: по пустому списку настройку не стираем — «ещё не знаю» ≠
        // «голоса нет»; иначе закрытие панели в неудачный момент сносило
        // выбранный голос из настроек насовсем.
        else if (list.isNotEmpty()) e.remove(KEY_VOICE)
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
                    // Та же защита, что в restoreEngineAndVoice: окно могло
                    // закрыться, пока движок переключался.
                    val p = livePlayer ?: return@post
                    if (ok && gVoice != null) {
                        p.selectVoice(gVoice)
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

    /** Подсветить начало выделения. Список — вся книга одной лентой (msg4308),
     *  поэтому начало фрагмента может быть в любой главе, а не только в текущей. */
    private fun refreshSelectionMarkers() {
        val a = selAnchor
        val row = if (a != null) adapter.flatOf(a.chapter, a.sentence) else -1
        adapter.setSelectionAnchor(if (row >= 0) row else null)
    }

    /** Долгое нажатие на предложении: первое удержание отмечает начало
     *  фрагмента, второе (в любом месте книги) — открывает окно действий. */
    private fun onSentenceLongPressed(row: Int) {
        val bk = book ?: return
        val p = adapter.placeOf(row) ?: return
        val cur = bk.chapters.getOrNull(p.first)?.sentences ?: return
        if (cur.isEmpty()) return
        if (playing) pausePlayback(keepFocus = true)
        val here = Place(p.first, p.second.coerceIn(0, cur.lastIndex))
        val a = selAnchor
        if (a == null) {
            selAnchor = here
            adapter.setSelectionAnchor(row)
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
        // Подсветить весь диапазон — теперь он виден целиком, даже если кусок
        // пересекает границу глав (в ленте они идут подряд, msg4308).
        val from = adapter.flatOf(start.chapter, start.sentence)
        val to = adapter.flatOf(end.chapter, end.sentence)
        if (from >= 0 && to >= from) adapter.setSelectionRange(from, to)
        else adapter.setSelectionRange(null, null)
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

    /** Ползунок скорости/тона в диалогах ридера — те же значения (список [values]
     *  из RateSteps: у скорости 0.5..4.0, у тона 0.5..2.0) и та же озвучка
     *  (stateDescription с SDK 30), что у ползунков Настройки → «Голос»
     *  (SettingsActivity.addRateSlider). Встраивается в переданный контейнер;
     *  [apply] сам решает, куда писать значение (плеер/настройки/запись книги) —
     *  для скорости вызывающий учитывает галочку «Запомнить для книги» (#102). */
    private fun addRateSliderTo(
        container: LinearLayout,
        labelRes: Int,
        cd: String,
        start: Float,
        values: List<Float>,
        apply: (Float) -> Unit,
    ) {
        // msg3929: значения приходят списком (RateSteps) — у скорости он длиннее
        // (до 4.0), у тона прежний (до 2.0).
        val ticks = values.lastIndex
        fun valueOf(p: Int): Float = values[p.coerceIn(0, ticks)]
        fun progressOf(v: Float): Int = RateSteps.indexOf(values, v)
        fun rateLabel(v: Float): String = RateSteps.label(v)

        val startP = progressOf(start)
        val startV = valueOf(startP)
        val label = TextView(this).apply {
            text = getString(labelRes, rateLabel(startV))
            textSize = 17f
            setTextColor(Palette.INK)
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
            setTextColor(Palette.INK)
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

    /** Набор «Голос чтения» открытой панели: общий код с Настройками
     *  (VoicePicker.kt). Живёт, пока панель открыта. */
    private var voicePicker: VoicePicker? = null

    /** Хост набора для читалки (msg5618): набор спрашивает состояние и сообщает
     *  о выборе, а куда и когда писать — решает читалка: движок и голос слышны
     *  сразу, а в настройки они уходят при закрытии панели (#55, msg2669), когда
     *  уже известно, стоит ли галочка «Запомнить для этой книги». */
    private val pickerHost = object : VoicePicker.Host {
        override fun withPlayer(titleRes: Int, onReady: (SpeechPlayer, Boolean) -> Unit) {
            // У читалки плеер всегда под рукой — ждать и гасить нечего.
            onReady(player, false)
        }

        override fun playerOrNull(): SpeechPlayer? = player

        override fun hasBook(): Boolean = book != null

        override fun isPlaying(): Boolean = playing

        override fun togglePlay() = this@MainActivity.togglePlay()

        override fun currentVoice(): String? = voiceName

        override fun rememberChecked(): Boolean = currentBookRecord()?.let {
            it.voiceEngine != null || it.voice != null || it.voiceSpeed != null
        } == true

        override fun rememberChanged(checked: Boolean) {
            voiceRememberChecked = checked
            if (checked) {
                persistPerBookProfile()
                toast(getString(R.string.voice_book_saved))
            } else {
                // Сняли галочку: свой голос книги забываем, движок вернётся на
                // глобальный — после переключения обновляем список голосов.
                clearBookVoice {
                    voicePicker?.resetLang()
                    voicePicker?.refresh()
                    toast(getString(R.string.voice_book_cleared))
                }
            }
        }

        override fun engineApplied(pkg: String) {
            // #55 (msg2669): движок в глобальные настройки в момент клика НЕ
            // пишем — иначе выбор «до галочки» заражал все книги без своего
            // голоса (msg2671). Куда писать решает hideVoicePanel при закрытии:
            // галочка → запись книги, без галочки → глобальные.
            // Перечитку текущего предложения набор делает сам, уже после того,
            // как движок действительно сменился (msg5604).
            voicePanelDirty = true
        }

        override fun voiceApplied(v: Voice, lang: String?) {
            voiceName = v.name
            voicePanelDirty = true
            // msg5604: голос сменили на ходу — готовые заготовки сделаны прежним
            // голосом, перечитываем текущее предложение новым.
            ReaderEngine.restartAfterSwitch()
        }

        override fun langApplied(code: String) {
            // Язык в панели читалки — только фильтр списка голосов: в настройки
            // он уйдёт при закрытии панели вместе с выбранным голосом (#70).
        }

        override fun redraw() = Unit

        override fun toast(msg: String) = this@MainActivity.toast(msg)
    }

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
     *  (enterVoiceFullscreen). Содержимое — набор VoicePicker (msg5618): первая
     *  строка «Читать/Пауза» (msg5632 — образцов голоса нет, кнопка запускает
     *  чтение книги), строки движок/язык/голос, ползунки скорости/тона/громкости
     *  и галочка «Запомнить для этой книги» (#102). Тот же код, что в Настройках. */
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

        // #75 (msg5610/5614/5618): движок / язык / голос / «Читать» и галочка
        // «Запомнить для этой книги» — ТОТ ЖЕ код, что в Настройках
        // (VoicePicker.kt). Раньше это были две разные поделки: тут строки
        // объявлялись «кнопкой», в Настройках — обычным текстом, и «Прослушать»
        // в двух местах значило разное. Теперь порядок, подписи и элементы
        // совпадают буквально; отличается только КОГДА выбор уходит в настройки
        // (тут — при закрытии панели, #55/msg2669; в Настройках — сразу).
        val picker = VoicePicker(this, prefs, pickerHost)
        voicePicker = picker
        picker.build(body)
        // Галочка могла стоять ещё до открытия панели (у книги уже есть свой
        // голос) — тогда закрытие панели обязано писать в книгу, а не в общие
        // настройки. Ставим зеркало ДО addRemember: сама галочка уже в этом
        // состоянии, и её слушатель сработал бы зря.
        voiceRememberChecked = pickerHost.rememberChecked()

        // Ползунки — как Настройки → «Голос»: скорость, тон, громкость.
        // Скорость: применяем к плееру сразу (новую слышно на лету), но в
        // глобальные НЕ пишем до закрытия панели — #55 (msg2669): выбор «до галочки»
        // не должен заражать общие настройки. Куда писать решает hideVoicePanel
        // (галочка → запись книги, без → глобальные). В отличие от тон/громкость,
        // которые всегда общие, скорость может быть своя у книги (#102).
        addRateSliderTo(body, R.string.speed_value, "Скорость", currentSpeed(), RateSteps.SPEED) { v ->
            voicePanelDirty = true
            if (player.isReady) player.speed = v
            refreshSpeedValue()
            updateStats()
        }
        addRateSliderTo(body, R.string.tone_value, "Тон", prefs.getFloat(KEY_PITCH, 1f), RateSteps.PITCH) { v ->
            prefs.edit().putFloat(KEY_PITCH, v).apply()
            if (player.isReady) player.pitch = v
        }
        addVolumeSliderTo(body) { v ->
            prefs.edit().putFloat(KEY_VOLUME, v).apply()
            if (player.isReady) player.volume = v
        }

        // Галочка «Запомнить для этой книги» с подсказкой — тоже из набора.
        picker.addRemember(body)


        // #54 (msg2643): панель голоса «на весь экран» — прячем остальную
        // читалку, а не показываем шторку над списком. Скролл внутри занимает
        // весь экран, поэтому галочка «Запомнить для этой книги» (msg2639)
        // больше не обрезается невидимым краем шторки — до неё всегда можно
        // доскроллить.
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
        // Панель закрыта — набору больше нечего перерисовывать (updatePlayButton
        // дёргает его подпись; без панели строк на экране нет).
        voicePicker = null
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp2px(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** Кнопка читалки: где хранится её действие, что стоит по умолчанию,
     *  как называется её место (для различения дублей) и какой ключ отвечает
     *  за её показ на экране. Порядок в READER_BUTTONS — как в разметке:
     *  верхний ряд (крупные шаги), потом нижний (пред./след.).
     *
     *  Класс объявлен здесь, а не внутри companion: из другого файла он
     *  виден как MainActivity.ReaderButton, а вложенный в companion — нет
     *  (пришлось бы писать MainActivity.Companion.ReaderButton). */
    internal class ReaderButton(
        val key: String,
        val def: String,
        val longKey: String,
        val longDef: String,
        val posRes: Int,
        val uiKey: String,
    )

    companion object {
        // Живой ридер, если он открыт — экран настроек через него меняет
        // скорость/голос сразу, а не «на следующий запуск».
        @Volatile
        internal var active: MainActivity? = null

        /** msg4673: пауза между проговариванием процента при драге по бегунку.
         *  Процент меняется быстро — без паузы быстрый проезд по книге забил бы
         *  очередь речи скринридера десятками чисел. 150 мс на слух — «на ходу»,
         *  но не поток. */
        private const val SCRUB_ANNOUNCE_MS = 150L

        /** msg4665: расширения, которые BookVoice прочитать не может, но которые
         *  книга может получить из сетевой библиотеки (mobi/rtf/html/doc, а
         *  также .rar — это PDF внутри архива RAR). msg4853: тот же список
         *  нужен и при открытии файла снаружи — там он говорит правду про
         *  формат вместо общего «не поддерживается» (LibraryActivity). */
        internal val UNREADABLE_EXTS = listOf("mobi", "rtf", "doc", "rar", "djvu")

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

        /** #70 (msg5511): выбранный ЯЗЫК голоса. Список голосов движка режется по
         *  языку, и помнить его надо между запусками: иначе после перезапуска
         *  голос выбран, а язык в строке «Язык» — пусто. Код ISO 639-2 («rus»). */
        internal const val KEY_VOICE_LANG = "voice_lang"

        /** msg5726: голос, выбранный КОНКРЕТНОМУ движку (префикс ключа, дальше
         *  пакет движка). Раньше голос был один на всё приложение: сменил движок
         *  — строка «Голос» показывала имя голоса прошлого движка, которого у
         *  нового нет (Сергей, msg5722: «движок переключил, а на кнопке голоса
         *  остался голос от прошлого движка»). Тот же выбор, кстати, затирался
         *  при выборе голоса у нового движка — вернуться к прежнему движку
         *  означало выбирать голос заново. Теперь у каждого движка своя запись,
         *  и возврат движка возвращает его голос. */
        internal const val KEY_VOICE_BY_ENGINE = "voice_by_engine"

        /** Ключ записи «голос этого движка». null-пакет — системный движок. */
        private fun voiceEngineKey(pkg: String?): String =
            "$KEY_VOICE_BY_ENGINE:${pkg ?: "system"}"

        /** Голос, который человек подобрал этому движку, или null. */
        internal fun voiceForEngine(prefs: SharedPreferences, pkg: String?): String? =
            prefs.getString(voiceEngineKey(pkg), null)

        /** Запомнить голос за движком. По пустому списку голосов не зовётся —
         *  там «ещё не знаю», а не «голос такой». */
        internal fun rememberVoiceForEngine(prefs: SharedPreferences, pkg: String?, voice: String) {
            prefs.edit().putString(voiceEngineKey(pkg), voice).apply()
        }
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
        // msg5295: настройки больше нет — уровень несёт само действие (G_*_CH/
        // MAJOR/HEADER, HS_CHAPTER/MAJOR/HEADER), ключ оставлен под перенос.
        internal const val KEY_CH_NAV = "ch_nav"
        internal const val CH_NAV_SENT = "sentence"
        internal const val CH_NAV_PARAGRAPH = "paragraph"
        internal const val CH_NAV_MAJOR = "major"
        internal const val CH_NAV_CHAPTERS = "chapters"
        internal const val CH_NAV_ALL = "all"

        // msg5220: у каждой из четырёх кнопок читалки своё действие из общей
        // палитры (GESTURE_ACTIONS). msg5266: действий у кнопки стало два —
        // короткое нажатие (KEY_BTN_*) и долгое (KEY_BTN_*_LONG); оба выбираются
        // в «Управлении», список по долгому нажатию убран. KEY_STEP/KEY_CH_NAV
        // остались только источником разового переноса: KEY_STEP — в действия
        // кнопок (migrateReaderButtons), KEY_CH_NAV — в шаг «глава» кнопок
        // гарнитуры (migrateHeadsetStep, msg5295). Боевых читателей у ключей нет.
        internal const val KEY_BTN_PREV_SENT = "btn_prev_sent"
        internal const val KEY_BTN_NEXT_SENT = "btn_next_sent"
        internal const val KEY_BTN_PREV_CH = "btn_prev_ch"
        internal const val KEY_BTN_NEXT_CH = "btn_next_ch"
        internal const val KEY_BTN_PREV_SENT_LONG = "btn_prev_sent_long"
        internal const val KEY_BTN_NEXT_SENT_LONG = "btn_next_sent_long"
        internal const val KEY_BTN_PREV_CH_LONG = "btn_prev_ch_long"
        internal const val KEY_BTN_NEXT_CH_LONG = "btn_next_ch_long"
        internal const val KEY_BTN_MIGRATED = "btn_actions_migrated"
        // msg5295: свой рубильник у переноса шага гарнитуры (см. migrateHeadsetStep).
        internal const val KEY_HS_MIGRATED = "hs_step_migrated"
        // Конструктор экрана (msg5220): четыре кнопки прячутся по одной; старые
        // парные ключи KEY_UI_SENT/KEY_UI_CHAPTERS — только для переноса.
        internal const val KEY_UI_PREV_SENT = "ui_prev_sent"
        internal const val KEY_UI_NEXT_SENT = "ui_next_sent"
        internal const val KEY_UI_PREV_CH = "ui_prev_ch"
        internal const val KEY_UI_NEXT_CH = "ui_next_ch"
        internal const val KEY_SCROLL = "scroll_to_current"
        // Портянка (msg4338): прокрутка рукой становится местом чтения. Выкл —
        // прежнее поведение: место двигают только чтение, кнопки и ползунок.
        internal const val KEY_SCROLL_PLACE = "scroll_moves_place"
        // Портянка (msg4372, вариант А): во время чтения отпущенная прокрутка
        // перекидывает голос на верхнюю строку — он читает дальше с неё.
        internal const val KEY_SCROLL_FOLLOW = "scroll_follows_reading"
        // msg4598 → msg5955: бесшовная передача звука встык. Была тестовой
        // галочкой (ключ "gapless_handoff"); галочку убрали — механизм работает
        // всегда, выключается только при альтернативной озвучке. В prefs у
        // людей остался старый «выкл», поэтому значение больше не читаем.

        /** #57: альтернативный способ озвучки — фразу целиком отдаём движку
         *  (проба для сравнения на слух, msg5401/5409). По умолчанию выключено. */
        internal const val KEY_ALT_VOICE = "alt_direct_voice"

        /** msg5604: сколько тишины оставлять на стыке фраз, мс. Значение строки
         *  «Пауза между фразами»: движок дописывает по краям файла свою тишину
         *  (у сетевых голосов Google — 0,5–0,7 с на фразу), и раньше мы оставляли
         *  от неё жёстко 50 мс в голове и 83 мс в хвосте. 130 мс — ровно как
         *  было; 0 — срезать всё, что дописал движок. */
        internal const val KEY_PAUSE_KEEP = "pause_keep_ms"
        internal const val PAUSE_KEEP_DEFAULT = 130
        // msg4721: тихий поток на время чтения — не давать засыпать звуковому каналу
        // (Bluetooth-гарнитура не уходит в сон и не откусывает начало фразы). Эксперимент,
        // поэтому по умолчанию выкл; галочка живёт на экране «Не засыпать».
        internal const val KEY_SILENT_KEEPALIVE = "silent_keepalive"
        internal const val KEY_TAP_TO_PLAY = "tap_to_play"
        internal const val KEY_TOC_PLAY = "toc_play"
        internal const val KEY_BM_PLAY = "bm_play"
        internal const val KEY_SEARCH_PLAY = "search_play"
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

        // msg5923/5931: прятать системные кнопки («назад/домой/недавние») в окне
        // книги. Три состояния, как у соседней строки: показывать (default) /
        // скрывать во время чтения / скрывать, пока открыта книга. Default —
        // «показывать»: скрытая панель возвращается только смахиванием от нижнего
        // края, и человек, который этого не знает, вышел бы из книги только через
        // «Выход» в шторке (см. ReaderBars).
        internal const val KEY_READER_BARS = "reader_bars"

        /** Панель на месте — по умолчанию. */
        internal const val BARS_SHOW = 0

        /** Панель уходит на время чтения и сама возвращается на паузе. */
        internal const val BARS_HIDE_READING = 1

        /** Панели нет всё время, пока открыта книга. */
        internal const val BARS_HIDE_BOOK = 2

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
        // msg5295: уровень «главы» у кнопки гарнитуры — не отдельная настройка, а
        // три отдельных шага, как у свайпов и кнопок читалки (G_NEXT_CH/MAJOR/
        // HEADER): глава без вложенных подразделов / крупный раздел / любой
        // заголовок. Раньше уровень жил строкой «Шаг „главы“ на гарнитуре»
        // (ключ CH_NAV); разовый перенос — migrateHeadsetStep.
        internal const val HS_MAJOR = "major"
        internal const val HS_HEADER = "header"
        // msg5250: шаг гарнитуры «прыжок» — сразу N предложений в сторону кнопки
        // (сколько именно, берём из настроек прыжка: у «вперёд» своё число, у
        // «назад» своё). Направление задаёт сама кнопка, как у прочих шагов.
        internal const val HS_SENT_N = "sent_n"

        // msg5234: прыжок — шаг сразу на N предложений. Число задаётся человеком
        // (две строки в «Управлении»), поэтому в пункте палитры оно подставляется
        // в название, а на кнопке читалки стоит короткая подпись «+15»/«−15».
        internal const val KEY_JUMP_FWD = "jump_fwd"
        internal const val KEY_JUMP_BACK = "jump_back"
        internal const val JUMP_DEFAULT = 10
        /** Готовый ряд чисел прыжка: выбирать из списка незрячему дешевле, чем
         *  вводить число с клавиатуры, а нужны всё равно круглые значения. */
        internal val JUMP_STEPS = intArrayOf(1, 2, 3, 5, 10, 15, 20, 30, 50)

        /** Сколько предложений отматывать за раз: [forward] — «вперёд» или «назад». */
        internal fun jumpCount(prefs: SharedPreferences, forward: Boolean): Int =
            prefs.getInt(if (forward) KEY_JUMP_FWD else KEY_JUMP_BACK, JUMP_DEFAULT)

        /** «15 предложений» в правильной форме — общая для названий пункта палитры,
         *  строк настроек и пункта гарнитуры. */
        internal fun sentencesPhrase(ctx: Context, n: Int): String =
            ctx.resources.getQuantityString(R.plurals.sentences_n, n, n)

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
        // msg5220: три уровня шага «глава» — раздел (крупные части), глава (без
        // вложенных подразделов), заголовок (все, включая вложенные).
        internal const val G_PREV_MAJOR = "prev_major"
        internal const val G_NEXT_MAJOR = "next_major"
        internal const val G_PREV_HEADER = "prev_header"
        internal const val G_NEXT_HEADER = "next_header"
        // msg5234: прыжок — шаг сразу на N предложений (число из настроек).
        internal const val G_PREV_SENT_N = "prev_sent_n"
        internal const val G_NEXT_SENT_N = "next_sent_n"
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

        /** Палитра действий для свайпов и кнопок читалки (msg5220): id → строка.
         *  Имя кнопки для скринридера берётся отсюда же — «кнопка называется тем,
         *  что она делает», поэтому список — единственный источник слов. */
        internal val GESTURE_ACTIONS: List<Pair<String, Int>> = listOf(
            G_NEXT_CH to R.string.g_action_next_ch,
            G_PREV_CH to R.string.g_action_prev_ch,
            G_NEXT_MAJOR to R.string.g_action_next_major,
            G_PREV_MAJOR to R.string.g_action_prev_major,
            G_NEXT_HEADER to R.string.g_action_next_header,
            G_PREV_HEADER to R.string.g_action_prev_header,
            G_NEXT_SENT to R.string.g_action_next_sent,
            G_PREV_SENT to R.string.g_action_prev_sent,
            // msg5234: прыжок — рядом с соседями по смыслу (шаг по предложениям),
            // число в названии подставляется при показе списка (gestureActionLabel).
            G_NEXT_SENT_N to R.string.g_action_next_sent_n,
            G_PREV_SENT_N to R.string.g_action_prev_sent_n,
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

        /** Четыре кнопки читалки: короткое действие (msg5220) и долгое (msg5266).
         *  По умолчанию короткие — как было исстари (верхние шагают по заголовкам,
         *  нижние по предложениям), долгие выключены: пока человек сам не назначит,
         *  долгое нажатие ничего не делает и не удивляет. */
        internal val READER_BUTTONS: List<ReaderButton> = listOf(
            ReaderButton(
                KEY_BTN_PREV_CH, G_PREV_HEADER, KEY_BTN_PREV_CH_LONG, G_NONE,
                R.string.btn_pos_top_left, KEY_UI_PREV_CH,
            ),
            ReaderButton(
                KEY_BTN_NEXT_CH, G_NEXT_HEADER, KEY_BTN_NEXT_CH_LONG, G_NONE,
                R.string.btn_pos_top_right, KEY_UI_NEXT_CH,
            ),
            ReaderButton(
                KEY_BTN_PREV_SENT, G_PREV_SENT, KEY_BTN_PREV_SENT_LONG, G_NONE,
                R.string.btn_pos_bottom_left, KEY_UI_PREV_SENT,
            ),
            ReaderButton(
                KEY_BTN_NEXT_SENT, G_NEXT_SENT, KEY_BTN_NEXT_SENT_LONG, G_NONE,
                R.string.btn_pos_bottom_right, KEY_UI_NEXT_SENT,
            ),
        )

        /** Действие кнопки читалки на короткое нажатие (или значение по умолчанию). */
        internal fun readerButtonAction(prefs: SharedPreferences, b: ReaderButton): String =
            prefs.getString(b.key, b.def) ?: b.def

        /** Действие кнопки читалки на долгое нажатие (msg5266). */
        internal fun readerButtonLongAction(prefs: SharedPreferences, b: ReaderButton): String =
            prefs.getString(b.longKey, b.longDef) ?: b.longDef

        /** Имя кнопки читалки для скринридера (msg5220): строка действия, а если
         *  такое же действие стоит ещё на одной кнопке — с местом на конце
         *  («Оглавление, верхняя левая»). Иначе человек слышит два одинаковых
         *  имени и не понимает, где именно стоит. */
        internal fun readerButtonName(
            ctx: Context,
            prefs: SharedPreferences,
            b: ReaderButton,
        ): String {
            val act = readerButtonAction(prefs, b)
            val label = gestureActionLabel(ctx, prefs, act)
            val twin = READER_BUTTONS.any { it !== b && readerButtonAction(prefs, it) == act }
            return if (twin) ctx.getString(R.string.btn_name_with_pos, label, ctx.getString(b.posRes))
            else label
        }

        /** Название действия из палитры — то, что человек слышит в списке и на
         *  кнопке (msg5220). У прыжка (msg5234) в название подставляется число
         *  из настроек, поэтому это не голая строка ресурса, а функция: список
         *  показывается в момент открытия, и число в нём всегда актуальное. */
        internal fun gestureActionLabel(ctx: Context, prefs: SharedPreferences, act: String): String {
            val res = GESTURE_ACTIONS.firstOrNull { it.first == act }?.second ?: return ""
            return when (act) {
                G_NEXT_SENT_N -> ctx.getString(res, sentencesPhrase(ctx, jumpCount(prefs, true)))
                G_PREV_SENT_N -> ctx.getString(res, sentencesPhrase(ctx, jumpCount(prefs, false)))
                else -> ctx.getString(res)
            }
        }

        /** Короткая видимая надпись на кнопке — по её действию (msg5220). Прыжок
         *  подписывается числом («+15»/«−15», msg5234): длинное название растянуло
         *  бы кнопку за прежнюю ширину (msg2431). */
        internal fun actionShort(ctx: Context, prefs: SharedPreferences, act: String): String =
            when (act) {
                G_NEXT_SENT_N -> ctx.getString(
                    R.string.btn_short_jump_next, jumpCount(prefs, true),
                )
                G_PREV_SENT_N -> ctx.getString(
                    R.string.btn_short_jump_prev, jumpCount(prefs, false),
                )
                else -> ctx.getString(actionShortRes(act))
            }

        /** Ресурс короткой надписи для действий без подстановки (msg5220). */
        internal fun actionShortRes(act: String): Int = when (act) {
            G_PREV_SENT -> R.string.btn_short_prev_sent
            G_NEXT_SENT -> R.string.btn_short_next_sent
            G_PREV_PARA -> R.string.btn_short_prev_para
            G_NEXT_PARA -> R.string.btn_short_next_para
            G_PREV_CH -> R.string.btn_short_prev_ch
            G_NEXT_CH -> R.string.btn_short_next_ch
            G_PREV_MAJOR -> R.string.btn_short_prev_major
            G_NEXT_MAJOR -> R.string.btn_short_next_major
            G_PREV_HEADER -> R.string.btn_short_prev_header
            G_NEXT_HEADER -> R.string.btn_short_next_header
            G_PLAY -> R.string.btn_short_play
            G_PAUSE -> R.string.btn_short_pause
            G_REPEAT -> R.string.btn_short_repeat
            G_POSITION -> R.string.btn_short_position
            G_SPEED_UP -> R.string.btn_short_speed_up
            G_SPEED_DOWN -> R.string.btn_short_speed_down
            G_TOC -> R.string.btn_short_toc
            G_BOOKMARKS -> R.string.btn_short_bookmarks
            G_ADD_BM -> R.string.btn_short_add_bm
            else -> R.string.btn_short_none
        }
    }
}
