package com.cuber.bookvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Разделы настроек (0.3.32): главный экран — список групп, внутри — свой короткий список. */
private enum class Group(@StringRes val titleRes: Int, @StringRes val hintRes: Int) {
    VOICE(R.string.settings_group_voice, R.string.settings_group_voice_hint),
    READING(R.string.settings_group_reading, R.string.settings_group_reading_hint),
    READER(R.string.settings_group_reader, R.string.settings_group_reader_hint),
    START(R.string.settings_group_start, R.string.settings_group_start_hint),
    LIBRARY(R.string.settings_group_library, R.string.settings_group_library_hint),
    DIAG(R.string.settings_group_diag, R.string.settings_group_diag_hint),
}

/**
 * Экран «Настройки» (0.3.17) — с msg1676+ страница живёт В СВОЁМ окне-секции
 * (редизайн: Настройки открываются окном ПОВЕРХ полки, как книга). Ридер и
 * библиотека читают общие prefs "reader" при старте/возобновлении, поэтому
 * изменения применяются при следующем входе в книгу.
 */
class SettingsActivity(private val act: SectionActivity) {

    // Страница живёт В СВОЁМ окне-секции SectionActivity (редизайн msg1676+): у
    // страницы нет своего Activity — все службы (контекст, prefs, пикеры,
    // диалоги, запуск окон) идут через окно [act]. Каждое открытие окна строит
    // страницу заново — вход всегда начинается с корневого меню разделов.
    private lateinit var binding: ActivitySettingsBinding

    private val contentResolver get() = act.contentResolver
    private val packageName get() = act.packageName
    private val packageManager get() = act.packageManager
    private val filesDir get() = act.filesDir

    private fun getString(resId: Int, vararg formatArgs: Any): String =
        act.getString(resId, *formatArgs)

    private fun runOnUiThread(block: () -> Unit) = act.runOnUiThread(block)
    private fun startActivity(intent: Intent) = act.startActivity(intent)
    private fun checkSelfPermission(permission: String): Int = act.checkSelfPermission(permission)

    private val prefs by lazy { act.getSharedPreferences("reader", Context.MODE_PRIVATE) }


    // Строки-резюме, обновляются после выбора (стартовый экран, шаг, папка, сортировка).
    private var startRow: Button? = null
    private var exitRow: Button? = null
    private var stepRow: Button? = null
    private var chNavRow: Button? = null   // «Кнопки глав шагают» (0.3.37)
    private var folderRow: Button? = null
    private var dlFolderRow: Button? = null
    private var dlFormatRow: Button? = null
    // Строки-резюме назначенных жестов (#77).
    private var gestureRightRow: Button? = null
    private var gestureLeftRow: Button? = null
    // Строки-резюме блока «После звонка» (#98).
    private var afterCallRow: Button? = null
    private var afterCallRewindRow: Button? = null
    // Строки-кнопки вкладок Библиотеки (#97): id режима → кнопка «<Имя>: показана/скрыта».
    private val tabRowButtons = ArrayList<Pair<Int, Button>>()
    private var resetTabsRow: Button? = null
    // Резервная копия (#100): папка и частота (строка-резюме).
    private var backupDirRow: Button? = null
    private var backupAutoRow: Button? = null

    // Открытый раздел (null = экран списка групп).
    private var group: Group? = null

    // Фокус на контент уже поставлен после первого показа (вход по нижней полосе
    // или из читалки). Дальше возвраты из пикеров фокус не трогают (msg1652).
    private var contentFocusedOnce = false

    // Пункты конструктора экрана чтения (#58) — порядок показа в разделе.
    private val readerUi: List<Pair<Int, String>> = listOf(
        R.string.ui_sent_title to MainActivity.KEY_UI_SENT,
        R.string.ui_play_title to MainActivity.KEY_UI_PLAY,
        R.string.ui_chapters_title to MainActivity.KEY_UI_CHAPTERS,
        R.string.ui_slider_title to MainActivity.KEY_UI_SLIDER,
        R.string.ui_position_title to MainActivity.KEY_UI_POSITION,
        R.string.ui_stats_title to MainActivity.KEY_UI_STATS,
        R.string.ui_toc_title to MainActivity.KEY_UI_TOC,
        R.string.ui_bookmark_title to MainActivity.KEY_UI_BOOKMARK,
        R.string.ui_voice_title to MainActivity.KEY_UI_VOICE,
        R.string.ui_status_title to MainActivity.KEY_UI_STATUS,
        R.string.ui_speed_title to MainActivity.KEY_UI_SPEED,
        R.string.ui_search_title to MainActivity.KEY_UI_SEARCH,
        // #101: «⋮ Ещё» в читалке — меню «Действия» (вернуться на предыдущее место).
        R.string.ui_more_title to MainActivity.KEY_UI_MORE,
    )

    /** Смена папки с книгами — SAF-дерево выбирает хост (у страницы нет своего
     *  Activity): зовём act.openTreePicker. */
    private fun openFolderPicker() {
        act.openTreePicker { uri -> if (uri != null) onFolderPicked(uri) }
    }

    // Папка скачиваний из каталога (#44) — нужен доступ на запись, отдельный пикер.
    private fun openDlFolderPicker() {
        act.openTreePicker { uri -> if (uri != null) onDlFolderPicked(uri) }
    }

    // Резервная копия (#100): папка для автокопий и системный выбор файла копии.
    private fun openBackupDirPicker() {
        act.openTreePicker { uri -> if (uri != null) onBackupDirPicked(uri) }
    }
    private fun openBackupFilePicker() {
        act.openDocPicker(arrayOf("*/*")) { uri -> if (uri != null) onBackupFilePicked(uri) }
    }

    /** Разрешение «Телефон» (#98): READ_PHONE_STATE, чтобы узнавать о конце
     *  настоящего звонка. Просим только при выборе «Продолжить чтение». Результат
     *  разбирает колбэк act.requestPermission. */
    private fun requestPhonePerm() {
        act.requestPermission(Manifest.permission.READ_PHONE_STATE) { granted ->
            if (granted) {
                prefs.edit().putString(
                    MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_CONTINUE,
                ).apply()
                toast(getString(R.string.after_call_continue))
            } else {
                toast(getString(R.string.after_call_perm_denied))
            }
            rebuildCurrentGroup()
        }
    }

    /** Построить страницу настроек в контейнере [container] хоста. Зовётся один
     *  раз при первом показе вкладки; открытый раздел живёт в странице всё время
     *  (переживает переключения). [intent] — интент, с которым запущен хост
     *  (страница его не использует). */
    fun build(container: ViewGroup, intent: Intent?) {
        binding = ActivitySettingsBinding.inflate(act.layoutInflater)
        container.addView(binding.root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        binding.btnMore.setOnClickListener { showSettingsMoreMenu() }

        // msg1712/1718: шапка как в FBReader — кнопка «Назад» слева от заголовка
        // (в Настройках — по желанию Сергея, как в Каталоге). Работает как
        // системный «назад»: внутри раздела — на уровень выше (список групп),
        // в корне — закрывает окно на полку (suppress авто-открытия ставит
        // rootBack/onDestroy окна, фикс 0.3.75).
        binding.btnBack.setOnClickListener { onBackKey() }
        buildContent()
    }

    /** Страница показана. [byTab]=true — первый показ окна (свежий вход из меню
     *  «⋮» полки или из читалки): фокус встаёт на ЗАГОЛОВОК окна
     *  ([binding.tvTitle]) — TalkBack читает название экрана, и сразу ясно, куда
     *  зашёл (msg1666). Курсор никуда дальше не тащим и отдельных фраз не шлём:
     *  сам факт фокуса на заголовке и есть подтверждение. Заголовок — стабильный
     *  TextView вверху, не в списке: в отличие от «первой настройки» (msg1652)
     *  перенос держится. Возвраты из пикеров (byTab=false, окно уже показывалось)
     *  фокус не трогают — экран и позицию ведут сами пикеры (msg1465). */
    fun resume(byTab: Boolean) {
        val firstShow = !contentFocusedOnce
        contentFocusedOnce = true
        if (byTab || firstShow) {
            // byTab (первый показ): фокус на заголовок после паузы — окно
            // показалось, TalkBack отпустил нажатый элемент. Первый показ не по
            // навигации (первый вход вообще) — пауза короче (окно только что
            // открылось). build контент не дёргает (noFocus), чтобы не было
            // конкурирующего переноса.
            TabNav.focusHeader(binding.tvTitle, if (byTab) 550 else 350)
        }
    }

    /** «Назад» на странице настроек: внутри раздела — список групп; в корне —
     *  закрывает окно — возврат на полку/в читалку (редизайн msg1676+: Настройки
     *  — окно поверх, не вкладка). Свежий вход всегда открывает корневое меню
     *  разделов (раздел прошлого раза не держим). */
    fun onBackKey(): Boolean {
        if (group != null) {
            val from = group
            group = null
            showMenu(from)
        } else {
            act.rootBack()
        }
        return true
    }

    // ---------------- Меню «⋮» в шапке (msg1474) ----------------

    /** «⋮ Ещё» окна Настроек: «Библиотека» (прыжок на полку-дом, редизайн
     *  msg1676+) и «Выход из приложения» (глобальный, из любого места —
     *  msg1278; выход — всегда последним). */
    private fun showSettingsMoreMenu() {
        MaterialAlertDialogBuilder(act)
            .setItems(arrayOf(
                getString(R.string.go_library),  // домой: закрыть окно до полки.
                getString(R.string.app_exit),
            )) { _, which ->
                when (which) {
                    0 -> act.rootBack()  // полка под окном раскроется; suppress авто-открытия ставит onDestroy окна.
                    1 -> TabNav.exitApp(act)
                }
            }
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    // ---------------- Построение экрана ----------------

    private fun buildContent() {
        // msg1652 (0.3.69): первый показ фокус из build НЕ дёргает — фокус
        // ставит resume() по факту показа. Раньше здесь (showMenu → focusFirst,
        // +350 мс) и в arrive (requestFocus, +550 мс) срабатывали два
        // конкурирующих переноса, и TalkBack «читал первую настройку → молча
        // возвращался на вкладку».
        showMenu(noFocus = true)
    }

    /** Главный экран настроек: список разделов (0.3.32). [focusGroup] — раздел,
     *  из которого вернулись «назад»: фокус встаёт на его строку (msg1468);
     *  null — обычный вход. [noFocus] — контент строится без переноса фокуса
     *  (первый показ: фокус ставит resume(), msg1652). */
    private fun showMenu(focusGroup: Group? = null, noFocus: Boolean = false) {
        binding.tvTitle.text = getString(R.string.settings_title)
        content().removeAllViews()
        Group.values().forEach { g -> addGroupButton(g) }
        scrollTop()
        if (focusGroup != null) {
            focusGroupButton(focusGroup)
        } else if (!noFocus) {
            focusFirst()
        }
    }

    /** Открыть раздел: заголовок сверху, ниже — только его настройки. */
    private fun openGroup(g: Group) {
        group = g
        binding.tvTitle.text = getString(g.titleRes)
        content().removeAllViews()
        when (g) {
            Group.VOICE -> buildVoiceGroup()
            Group.READING -> buildReadingGroup()
            Group.READER -> buildReaderGroup()
            Group.START -> buildStartGroup()
            Group.LIBRARY -> buildLibraryGroup()
            Group.DIAG -> buildDiagGroup()
        }
        refreshRows()
        scrollTop()
        focusFirst()
    }

    private fun buildVoiceGroup() {
        // «Голос» (msg721 + msg1102): только параметры звука. Тон — сюда же
        // (msg1096: «пропал ползунок тона»), движок и голос — кнопкой ниже,
        // тем же выбором, что и в читалке.
        addRateSlider(MainActivity.KEY_SPEED, R.string.speed_value, "Скорость") {
            MainActivity.active?.player?.speed = it
        }
        addRateSlider(MainActivity.KEY_PITCH, R.string.tone_value, "Тон") {
            MainActivity.active?.player?.pitch = it
        }
        // Громкость чтения (0..100%) — общий KEY_VOLUME (тот же, что был в окне «Голос и речь»).
        addVolumeSlider()

        addButton(getString(R.string.voice_engine_title)) { openVoiceDialog() }
    }

    /** «Чтение» (msg721): когда начинать чтение и что озвучивать. Переехало из «Голоса». */
    private fun buildReadingGroup() {
        addCheck(R.string.chapter_start_title, MainActivity.KEY_SAY_CHAPTER_START, true)
        addCheck(R.string.auto_start_title, MainActivity.KEY_AUTO_START, true)
        addCheck(R.string.auto_resume_title, MainActivity.KEY_AUTO_RESUME, true)
        addCheck(R.string.tap_to_play_title, MainActivity.KEY_TAP_TO_PLAY, true)
        addCheck(R.string.toc_play_title, MainActivity.KEY_TOC_PLAY, true)
        addCheck(R.string.bm_play_title, MainActivity.KEY_BM_PLAY, true)
        // Найденное по поиску слово — читать ли с него сразу (как с главы/закладки).
        addCheck(R.string.search_play_title, MainActivity.KEY_SEARCH_PLAY, true)
        // «Показывать титульный лист» (#553): выключено = книга (FB2) начинается
        // сразу с первой главы, титульный/копирайт-блок в начале пропускается.
        addCheck(R.string.title_page_title, MainActivity.KEY_SHOW_TITLE_PAGE, false)

        // #98 «После звонка»: ряд-резюме + при «Продолжить» — ряд отката.
        // Оба про поведение при внешнем прерывании, поэтому рядом с #99 ниже.
        afterCallRow = addValueButton { pickAfterCallMode() }
        afterCallRewindRow = if (afterCallMode() == MainActivity.AFTER_CALL_CONTINUE) {
            addValueButton { pickAfterCallRewind() }
        } else {
            null
        }
        // #99: останавливать чтение, когда отключаются наушники.
        addCheck(R.string.headphones_pause_title, MainActivity.KEY_PAUSE_HEADSET, true)
    }

    /** Ползунок скорости/тона: шаг 0.1 в диапазоне 0.5..2.0, чтобы свайп
     *  TalkBack менял значение на одну десятую (0.6 → 0.7 → 0.8), а не на
     *  сотые (1.06). Подпись-значение обновляется над ползунком, [apply]
     *  применяет значение к плееру сразу. */
    private fun addRateSlider(key: String, labelRes: Int, cd: String, apply: (Float) -> Unit) {
        val min = 0.5f
        val step = 0.1f
        val ticks = Math.round((2.0f - min) / step).toInt() // 0.5..2.0, шаг 0.1 → 15
        fun valueOf(p: Int): Float = min + p * step
        fun progressOf(v: Float): Int = Math.round((v - min) / step).coerceIn(0, ticks)

        val cur = prefs.getFloat(key, 1f)
        val startP = progressOf(cur)
        val startV = valueOf(startP)
        val label = TextView(act).apply {
            text = getString(labelRes, rateLabel(startV))
            textSize = 17f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(0, 0, 0, dp(2))
        }
        content().addView(label)

        val seek = SeekBar(act).apply {
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
                prefs.edit().putFloat(key, v).apply()
                apply(v)
                announce(v)
                label.text = getString(labelRes, rateLabel(v))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        content().addView(seek, lp().apply { bottomMargin = dp(6) })
    }

    /** Ползунок громкости чтения (0..100%). Значение хранится как Float 0..1
     *  в том же KEY_VOLUME, что громкость в старом окне «Голос и речь»
     *  (переехала сюда в #1102) — единый источник для чтения. */
    private fun addVolumeSlider() {
        val startP = Math.round(prefs.getFloat(MainActivity.KEY_VOLUME, 1f) * 100).coerceIn(0, 100)
        val label = TextView(act).apply {
            text = getString(R.string.volume_value, startP)
            textSize = 17f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(0, 0, 0, dp(2))
        }
        content().addView(label)

        val cd = getString(R.string.volume_cd)
        val seek = SeekBar(act).apply {
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
                prefs.edit().putFloat(MainActivity.KEY_VOLUME, v).apply()
                MainActivity.active?.player?.volume = v
                announce(progress)
                label.text = getString(R.string.volume_value, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        content().addView(seek, lp().apply { bottomMargin = dp(6) })
    }

    private fun buildReaderGroup() {
        // Подсказка сверху: эти флажки убирают/возвращают элементы экрана чтения.
        addHint(getString(R.string.reader_group_hint))

        // Конструктор экрана чтения (#58): какие элементы читалки показывать.
        // Применяется в MainActivity.onStart (applyReaderUi) при возврате в книгу.
        readerUi.forEach { (res, key) -> addCheck(res, key, true) }
        // Автопрокрутка текста (#318) — переехала из «Управления» (msg721): тоже
        // про поведение экрана чтения, не про звук или запуск.
        addCheck(R.string.scroll_title, MainActivity.KEY_SCROLL, true)

        // Вкладки Библиотеки (#97): отдельный блок «Интерфейса» — порядок и
        // видимость верхних фильтров «Читаю/Новые/Прочитанные/Все». «Все»
        // скрыть нельзя; активную и последнюю видимую тоже.
        addHint(getString(R.string.lib_tabs_hint))
        tabRowButtons.clear()
        for (mode in LibraryActivity.tabsOrder(prefs)) {
            val row = addValueButton { tabActions(mode) }
            row.tag = mode
            tabRowButtons.add(mode to row)
        }
        resetTabsRow = addButton(getString(R.string.lib_tabs_reset)) { resetTabs() }
    }

    /** Строки свайпов вправо/влево (#77). Раньше были отдельным разделом «Жесты»,
     *  по просьбе перенесены в «Старт и кнопки». Палитра действий общая с ридером
     *  (MainActivity.GESTURE_ACTIONS): выбранный id хранится в prefs и читается
     *  диспетчером жестов при свайпе. */
    private fun addGestureRows() {
        content().addView(TextView(act).apply {
            text = getString(R.string.gesture_hint)
            textSize = 14f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        gestureRightRow = addValueButton {
            pickGesture(MainActivity.KEY_GESTURE_RIGHT, MainActivity.G_NEXT_CH)
        }
        gestureLeftRow = addValueButton {
            pickGesture(MainActivity.KEY_GESTURE_LEFT, MainActivity.G_PREV_CH)
        }
    }

    /** Диалог выбора действия для одного свайпа (общая палитра жестов). */
    private fun pickGesture(key: String, def: String) {
        val labels = MainActivity.GESTURE_ACTIONS.map { getString(it.second) }.toTypedArray()
        val cur = prefs.getString(key, def) ?: def
        val idx = MainActivity.GESTURE_ACTIONS.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.gesture_choose_title)
            .setSingleChoiceItems(labels, idx) { d, which ->
                prefs.edit().putString(key, MainActivity.GESTURE_ACTIONS[which].first).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Имя действия по его id из палитры жестов (для строк-резюме и подписи раздела). */
    private fun gestureLabel(id: String?): String {
        val res = MainActivity.GESTURE_ACTIONS.firstOrNull { it.first == id }?.second ?: return ""
        return getString(res)
    }

    private fun buildStartGroup() {
        // msg1254: тактильное подтверждение действий (книга удалена/скачана, копия,
        // закладка/цитата и т.д.). Один общий рубильник — снял галочку, вибраций нет.
        addCheck(R.string.vibrate_title, Vibra.KEY_VIBRATE, true)
        startRow = addValueButton {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.start_screen_dialog)
                .setSingleChoiceItems(
                    arrayOf(
                        getString(R.string.start_screen_library),
                        getString(R.string.start_screen_last),
                    ),
                    startIndex(),
                ) { d, which ->
                    prefs.edit().putString(
                        MainActivity.KEY_START,
                        if (which == 0) MainActivity.START_LIBRARY else MainActivity.START_LAST,
                    ).apply()
                    d.dismiss()
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
        // #43: куда уходить кнопкой «назад» из открытой книги.
        exitRow = addValueButton {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.exit_dialog)
                .setSingleChoiceItems(
                    arrayOf(
                        getString(R.string.exit_library),
                        getString(R.string.exit_desktop),
                    ),
                    if (prefs.getString(MainActivity.KEY_EXIT, MainActivity.EXIT_LIBRARY) == MainActivity.EXIT_LIBRARY) 0 else 1,
                ) { d, which ->
                    prefs.edit().putString(
                        MainActivity.KEY_EXIT,
                        if (which == 0) MainActivity.EXIT_LIBRARY else MainActivity.EXIT_DESKTOP,
                    ).apply()
                    d.dismiss()
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
        stepRow = addValueButton {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.step_dialog)
                .setSingleChoiceItems(
                    arrayOf(getString(R.string.step_sentence), getString(R.string.step_chapter)),
                    if (prefs.getString(MainActivity.KEY_STEP, MainActivity.STEP_SENTENCE) == MainActivity.STEP_CHAPTER) 1 else 0,
                ) { d, which ->
                    prefs.edit().putString(
                        MainActivity.KEY_STEP,
                        if (which == 0) MainActivity.STEP_SENTENCE else MainActivity.STEP_CHAPTER,
                    ).apply()
                    d.dismiss()
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
        // «Кнопки глав шагают» (0.3.37): по каким уровням FB2 переходят
        // «Предыдущая/Следующая глава» — по крупным разделам / по главам /
        // по всем заголовкам. Для TXT/EPUB иерархии нет, режим не влияет.
        chNavRow = addValueButton { pickChapterNav() }
        addGestureRows()
    }

    private fun buildLibraryGroup() {
        folderRow = addValueButton {
            if (treeUri() == null) {
                openFolderPicker()
            } else {
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.scan_folder)
                    .setItems(arrayOf(getString(R.string.folder_change))) { _, _ ->
                        openFolderPicker()
                    }
                    .setNegativeButton(R.string.toc_close, null)
                    .show()
            }
        }
        // #44: куда скачивать книги из каталога.
        dlFolderRow = addValueButton {
            if (dlTreeUri() == null) {
                openDlFolderPicker()
            } else {
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.dl_folder_title)
                    .setItems(arrayOf(getString(R.string.dl_folder_change))) { _, _ ->
                        openDlFolderPicker()
                    }
                    .setNegativeButton(R.string.toc_close, null)
                    .show()
            }
        }
        // #45: какой формат качать из каталога по умолчанию.
        dlFormatRow = addValueButton {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.dl_format_title)
                .setSingleChoiceItems(
                    OPDS_READABLE_FORMATS.map { it.second }.toTypedArray(),
                    dlFormatIndex(),
                ) { d, which ->
                    prefs.edit().putString(
                        OpdsPrefs.KEY_DL_FMT,
                        OPDS_READABLE_FORMATS[which].first,
                    ).apply()
                    d.dismiss()
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }

        // Резервная копия и восстановление (#100): создание → системный Share
        // (в Telegram «Избранное»), восстановление — выбор файла копии. Ниже —
        // папка автокопий и их частота.
        addButton(getString(R.string.backup_create)) { createBackupDialog() }
        addButton(getString(R.string.backup_restore)) {
            openBackupFilePicker()
        }
        backupDirRow = addValueButton { pickBackupDirAction() }
        backupAutoRow = addValueButton { pickBackupAuto() }
    }

    private fun buildDiagGroup() {
        addButton(getString(R.string.diag_send)) { sendDiagLog() }
        addButton(getString(R.string.diag_clear)) {
            Diag.clear(act)
            toast(getString(R.string.log_cleared))
        }
    }

    private fun content(): LinearLayout = binding.content

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Экран раздела начинается сверху (ScrollView мог остаться прокрученным). */
    private fun scrollTop() {
        (content().parent as? ScrollView)?.scrollTo(0, 0)
    }

    /** После смены экрана фокус TalkBack — на первом элементе, а не в пустоте.
     *  С паузой 350 мс (TabNav.refocusAfterRebuild): без неё TalkBack после
     *  перерисовки роняет фокус на нижние вкладки (0.3.55). */
    private fun focusFirst() {
        TabNav.refocusAfterRebuild(binding.content)
    }

    /** msg1468: возврат из раздела — фокус на строку этого раздела, а не на
     *  первый «Голос». Строки-разделы лежат в content в порядке Group.values(),
     *  поэтому раздел = дочерний элемент с его индексом. Пауза — как в focusFirst. */
    private fun focusGroupButton(g: Group) {
        val idx = Group.values().indexOf(g)
        val row = if (idx in 0 until content().childCount) content().getChildAt(idx) else null
        TabNav.refocusAfterRebuild(content(), row)
    }

    /** Подсказка-пояснение в начале раздела. Обычный текст без роли кнопки:
     *  TalkBack читает его целиком, когда фокус встаёт на первый элемент. */
    private fun addHint(text: String) {
        content().addView(TextView(act).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(2), 0, dp(10))
        })
    }

    private fun addCheck(titleRes: Int, key: String, def: Boolean) {
        content().addView(CheckBox(act).apply {
            text = getString(titleRes)
            textSize = 17f
            isChecked = prefs.getBoolean(key, def)
            isClickable = true
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        })
    }

    /** Кнопка-строка с резюме значения справа-снизу. Клик открывает диалог. */
    private fun addValueButton(onClick: () -> Unit): Button =
        addButton("") { onClick() }

    private fun addButton(label: String, onClick: () -> Unit): Button {
        val b = Button(act).apply {
            text = label
            textSize = 17f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }
        asPlainText(b)
        content().addView(b, lp().apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        return b
    }

    /** Строка-раздел главного экрана (msg721): название + статичная подсказка второй
     *  строкой, приглушённым серым. Подсказка — постоянный текст, не резюме состояния,
     *  поэтому msg528 (убрать резюме «что настроено») не нарушен. */
    private fun addGroupButton(g: Group) {
        val title = getString(g.titleRes)
        val hint = getString(g.hintRes)
        val b = Button(act).apply {
            text = SpannableStringBuilder().apply {
                append(title)
                append("\n")
                val hintStart = length
                append(hint)
                setSpan(RelativeSizeSpan(0.76f), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(0xFF9AA0A6.toInt()), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 17f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { openGroup(g) }
        }
        asPlainText(b)
        content().addView(b, lp().apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
    }

    /**
     * Строка настроек не должна озвучиваться как «…, кнопка»: здесь каждый пункт
     * и так очевидно нажимаемый, а слово-роль TalkBack добавляет по классу узла
     * (Button). Сообщаем класс TextView — как у нижних вкладок (#60): текст и
     * подпись читаются, роль «кнопка» не произносится, двойной тап и фокус
     * сохраняются.
     */
    private fun asPlainText(b: Button) {
        b.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.TextView"
            }
        }
    }

    /** Диалог: по каким уровням разметки FB2 шагают «Предыдущая/Следующая глава»
     *  (0.3.37). Три режима — см. MainActivity.chapterStopIndexes. */
    private fun pickChapterNav() {
        val values = arrayOf(
            MainActivity.CH_NAV_MAJOR,
            MainActivity.CH_NAV_CHAPTERS,
            MainActivity.CH_NAV_ALL,
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.ch_nav_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.ch_nav_major),
                    getString(R.string.ch_nav_chapters),
                    getString(R.string.ch_nav_all),
                ),
                values.indexOf(prefs.getString(MainActivity.KEY_CH_NAV, MainActivity.CH_NAV_ALL)).coerceAtLeast(0),
            ) { d, which ->
                prefs.edit().putString(MainActivity.KEY_CH_NAV, values[which]).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Название выбранного шага глав — для строки-резюме. */
    private fun chNavLabel(): String = getString(
        when (prefs.getString(MainActivity.KEY_CH_NAV, MainActivity.CH_NAV_ALL)) {
            MainActivity.CH_NAV_MAJOR -> R.string.ch_nav_major
            MainActivity.CH_NAV_CHAPTERS -> R.string.ch_nav_chapters
            else -> R.string.ch_nav_all
        }
    )

    /** Обновить тексты строк-резюме (стартовый экран, шаг, папка, сортировка). */
    private fun refreshRows() {
        val startLast = prefs.getString(MainActivity.KEY_START, MainActivity.START_LAST) == MainActivity.START_LAST
        startRow?.text = getString(R.string.start_screen_title) + ": " +
            if (startLast) getString(R.string.start_screen_last) else getString(R.string.start_screen_library)

        val exitLibrary = prefs.getString(MainActivity.KEY_EXIT, MainActivity.EXIT_LIBRARY) == MainActivity.EXIT_LIBRARY
        exitRow?.text = getString(R.string.exit_title) + ": " +
            if (exitLibrary) getString(R.string.exit_library) else getString(R.string.exit_desktop)

        val byChapter = prefs.getString(MainActivity.KEY_STEP, MainActivity.STEP_SENTENCE) == MainActivity.STEP_CHAPTER
        stepRow?.text = getString(R.string.step_title) + " " +
            if (byChapter) getString(R.string.step_chapter_value) else getString(R.string.step_sentence_value)

        chNavRow?.text = getString(R.string.ch_nav_title) + ": " + chNavLabel()

        val tree = treeUri()
        folderRow?.text = getString(R.string.folder_title) + ": " +
            if (tree == null) getString(R.string.folder_none) else folderLabel(Uri.parse(tree))

        val dlTree = dlTreeUri()
        dlFolderRow?.text = getString(R.string.dl_folder_title) + ": " +
            if (dlTree == null) getString(R.string.dl_folder_none) else folderLabel(Uri.parse(dlTree))

        dlFormatRow?.text = getString(R.string.dl_format_title) + ": " +
            OPDS_READABLE_FORMATS.firstOrNull { it.first == dlFormatKey() }?.second ?: "FB2"

        gestureRightRow?.text = getString(R.string.gesture_right_title) + ": " +
            gestureLabel(prefs.getString(MainActivity.KEY_GESTURE_RIGHT, MainActivity.G_NEXT_CH))
        gestureLeftRow?.text = getString(R.string.gesture_left_title) + ": " +
            gestureLabel(prefs.getString(MainActivity.KEY_GESTURE_LEFT, MainActivity.G_PREV_CH))

        afterCallRow?.text = getString(R.string.after_call_title) + ": " + afterCallLabel()
        afterCallRewindRow?.text = getString(R.string.after_call_rewind_title) + ": " + rewindLabel()

        val hiddenTabs = LibraryActivity.tabsHidden(prefs)
        for ((mode, b) in tabRowButtons) {
            b.text = getString(LibraryActivity.tabLabelRes(mode)) + ": " +
                getString(if (mode in hiddenTabs) R.string.lib_tabs_hidden else R.string.lib_tabs_visible)
        }

        val dir = BackupStore.dirUri(act)
        backupDirRow?.text = getString(R.string.backup_dir_title) + ": " +
            if (dir == null) getString(R.string.backup_dir_none) else folderLabel(dir)
        backupAutoRow?.text = getString(R.string.backup_auto_title) + ": " + backupAutoLabel()

    }

    // ---------------- «После звонка» (#98) и пауза при наушниках (#99) ----------------

    private fun afterCallMode(): String =
        prefs.getString(MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_STOP)
            ?: MainActivity.AFTER_CALL_STOP

    private fun afterCallLabel(): String = getString(
        if (afterCallMode() == MainActivity.AFTER_CALL_CONTINUE)
            R.string.after_call_continue_value else R.string.after_call_stop_value
    )

    private fun rewindLabel(): String = getString(when (
        prefs.getString(MainActivity.KEY_AFTER_CALL_REWIND, MainActivity.AFTER_CALL_REWIND_5)
            ?: MainActivity.AFTER_CALL_REWIND_5
    ) {
        MainActivity.AFTER_CALL_REWIND_NONE -> R.string.after_call_rewind_none
        MainActivity.AFTER_CALL_REWIND_2 -> R.string.after_call_rewind_2
        MainActivity.AFTER_CALL_REWIND_10 -> R.string.after_call_rewind_10
        else -> R.string.after_call_rewind_5
    })

    private fun hasPhonePerm(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Выбор режима «После звонка». «Продолжить чтение» требует разрешения
     *  «Телефон» (READ_PHONE_STATE) — просим его здесь, один раз. */
    private fun pickAfterCallMode() {
        val cur = if (afterCallMode() == MainActivity.AFTER_CALL_CONTINUE) 1 else 0
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.after_call_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.after_call_stop),
                    getString(R.string.after_call_continue),
                ),
                cur,
            ) { d, which ->
                if (which == 1 && !hasPhonePerm()) {
                    d.dismiss()
                    requestPhonePerm()
                    return@setSingleChoiceItems
                }
                prefs.edit().putString(
                    MainActivity.KEY_AFTER_CALL,
                    if (which == 0) MainActivity.AFTER_CALL_STOP else MainActivity.AFTER_CALL_CONTINUE,
                ).apply()
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Откат после звонка (виден только при «Продолжить чтение»). */
    private fun pickAfterCallRewind() {
        val values = arrayOf(
            MainActivity.AFTER_CALL_REWIND_NONE,
            MainActivity.AFTER_CALL_REWIND_2,
            MainActivity.AFTER_CALL_REWIND_5,
            MainActivity.AFTER_CALL_REWIND_10,
        )
        val cur = values.indexOf(
            prefs.getString(MainActivity.KEY_AFTER_CALL_REWIND, MainActivity.AFTER_CALL_REWIND_5)
        ).coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.after_call_rewind_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.after_call_rewind_none),
                    getString(R.string.after_call_rewind_2),
                    getString(R.string.after_call_rewind_5),
                    getString(R.string.after_call_rewind_10),
                ),
                cur,
            ) { d, which ->
                prefs.edit().putString(MainActivity.KEY_AFTER_CALL_REWIND, values[which]).apply()
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    // ---------------- Вкладки Библиотеки (#97) ----------------

    /** Диалог одной вкладки: показать/скрыть, передвинуть выше/ниже. */
    private fun tabActions(mode: Int) {
        val items = ArrayList<String>()
        val acts = ArrayList<Int>() // 0 — выше, 1 — ниже, 2 — показать/скрыть
        val order = LibraryActivity.tabsOrder(prefs)
        val hidden = mode in LibraryActivity.tabsHidden(prefs)
        if (hidden) {
            items.add(getString(R.string.lib_tabs_show))
            acts.add(2)
        } else {
            val pos = order.indexOf(mode)
            if (pos > 0) {
                items.add(getString(R.string.lib_tabs_up))
                acts.add(0)
            }
            if (pos >= 0 && pos < order.size - 1) {
                items.add(getString(R.string.lib_tabs_down))
                acts.add(1)
            }
            items.add(getString(R.string.lib_tabs_hide))
            acts.add(2)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(getString(R.string.lib_tabs_dialog, getString(LibraryActivity.tabLabelRes(mode))))
            .setItems(items.toTypedArray()) { d, which ->
                when (acts[which]) {
                    0 -> moveTab(mode, -1)
                    1 -> moveTab(mode, 1)
                    else -> toggleTabVisible(mode)
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun toggleTabVisible(mode: Int) {
        val order = LibraryActivity.tabsOrder(prefs)
        val hidden = LibraryActivity.tabsHidden(prefs).toMutableSet()
        if (mode in hidden) {
            hidden.remove(mode)
        } else {
            if (mode == LibraryActivity.FILTER_ALL) {
                toast(getString(R.string.lib_tabs_cannot_all))
                return
            }
            if (mode == LibraryActivity.activeFilterMode) {
                toast(getString(R.string.lib_tabs_cannot_active))
                return
            }
            val visibleNonAll = order.filter { it !in hidden && it != LibraryActivity.FILTER_ALL }
            if (visibleNonAll.size <= 1) {
                toast(getString(R.string.lib_tabs_cannot_last))
                return
            }
            hidden.add(mode)
        }
        LibraryActivity.saveTabs(prefs, order, hidden)
        rebuildCurrentGroup()
    }

    private fun moveTab(mode: Int, dir: Int) {
        val order = LibraryActivity.tabsOrder(prefs).toMutableList()
        val i = order.indexOf(mode)
        val j = i + dir
        if (i < 0 || j < 0 || j >= order.size) return
        val t = order[i]
        order[i] = order[j]
        order[j] = t
        LibraryActivity.saveTabs(prefs, order, LibraryActivity.tabsHidden(prefs))
        rebuildCurrentGroup()
    }

    private fun resetTabs() {
        LibraryActivity.saveTabs(prefs, LibraryActivity.TAB_MODES.toList(), emptySet())
        toast(getString(R.string.lib_tabs_reset_done))
        rebuildCurrentGroup()
    }

    /** Пересобрать открытый сейчас раздел (после смены значения внутри него). */
    private fun rebuildCurrentGroup() {
        val g = group ?: return
        openGroup(g)
    }

    // ---------------- Резервная копия и восстановление (#100) ----------------

    /** Ручное создание: два блока галочками, файл уходит системным Share. */
    private fun createBackupDialog() {
        val items = arrayOf(
            getString(R.string.backup_block_settings),
            getString(R.string.backup_block_books),
        )
        val checked = booleanArrayOf(true, true)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.backup_blocks_create)
            .setMultiChoiceItems(items, checked) { _, _, _ -> }
            .setPositiveButton(R.string.backup_create_go) { _, _ ->
                if (!checked[0] && !checked[1]) return@setPositiveButton
                runBackupCreate(checked[0], checked[1])
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun runBackupCreate(includeSettings: Boolean, includeBooks: Boolean) {
        Thread {
            val ctx = act.applicationContext
            val bytes = BackupStore.build(ctx, includeSettings, includeBooks)
            val f = writeBackupShareFile(ctx, bytes)
            // 0.3.44 (msg1088): ручная копия тоже ложится в выбранную папку копий,
            // если она выбрана и разрешает запись. Отмена меню «Поделиться» не
            // теряет копию — она уже в папке. writeAutoFile полностью в runCatching.
            val folderWasOn = BackupStore.dirUri(ctx) != null
            val folderDoc = if (folderWasOn) BackupStore.writeAutoFile(ctx, bytes) else null
            runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (folderDoc != null) {
                    toast(getString(R.string.backup_folder_copied))
                    Vibra.confirm(act)
                } else if (folderWasOn) {
                    // папка выбрана, но запись не удалась (напр. MixPlorer) —
                    // молча не бросаем: подсказываем и всё равно даём отправить.
                    toast(getString(R.string.backup_folder_copy_fail))
                }
                if (f == null) toast(getString(R.string.backup_create_fail))
                else shareBackupFile(f)
            }
        }.start()
    }

    /** Временный файл копии во внутреннем хранилище (FileProvider отдаёт его
     *  наружу; путь files-path покрывает filesDir). Старые темпы подчищаем. */
    private fun writeBackupShareFile(ctx: Context, bytes: ByteArray): File? {
        return try {
            val dir = ctx.filesDir
            dir.listFiles()?.forEach {
                if (it.name.startsWith("BookVoice_") && it.name.endsWith(".bvbak")) {
                    it.delete()
                }
            }
            val f = File(dir, BackupStore.filename(System.currentTimeMillis()))
            f.writeBytes(bytes)
            f
        } catch (_: Exception) {
            null
        }
    }

    private fun shareBackupFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(act, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.backup_create)))
        } catch (e: Exception) {
            Diag.log(act, "app", "ошибка отправки копии: ${e.message}")
            toast(getString(R.string.backup_create_fail))
        }
    }

    /** Выбор папки автокопий (SAF-tree, один раз) / смена. */
    private fun pickBackupDirAction() {
        if (BackupStore.dirUri(act) == null) {
            openBackupDirPicker()
        } else {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.backup_dir_title)
                .setItems(arrayOf(getString(R.string.backup_dir_change))) { _, _ ->
                    openBackupDirPicker()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
    }

    private fun onBackupDirPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(BackupStore.KEY_DIR, tree.toString()).apply()
        toast(getString(R.string.backup_dir_saved))
        refreshRows()
        probeBackupDir(tree)
    }

    /** Проверка, что в выбранную папку реально можно писать автоматически
     *  (0.3.43, msg1075/1077). Некоторые сторонние проводники (MixPlorer)
     *  отдают tree-uri, но не умеют createDocument — автобэкап тогда молча не
     *  работает, а раньше ещё и ронял приложение. Проверяем сразу созданием и
     *  удалением пробника и предупреждаем, если папка не подходит. */
    private fun probeBackupDir(tree: Uri) {
        Thread {
            val ok = runCatching {
                val probe = DocumentsContract.createDocument(
                    contentResolver, tree, "application/json", "BookVoice_probe"
                ) ?: return@runCatching false
                DocumentsContract.deleteDocument(contentResolver, probe)
                true
            }.getOrDefault(false)
            runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (ok) return@runOnUiThread
                // SAF-запись не вышла (MixPlorer и т.п. не умеют createDocument).
                // Если папке можно сопоставить реальный путь — она заработает
                // через «Доступ ко всем файлам» (AllFiles), папку не трогаем.
                val dir = AllFiles.resolveDir(tree)
                if (dir == null) {
                    toast(getString(R.string.backup_dir_unwritable))
                    return@runOnUiThread
                }
                if (AllFiles.granted(act)) {
                    // Доступ есть: проверяем реальным путём — если не пишет,
                    // значит папка правда не подходит.
                    if (!AllFiles.probeViaRealPath(act, tree)) {
                        toast(getString(R.string.backup_dir_unwritable))
                    }
                } else {
                    // Папка рабочая только с реальным путём — просим доступ.
                    AllFiles.ask(act, getString(R.string.all_files_explain))
                }
            }
        }.start()
    }

    /** Восстановление: разбор файла → что внутри → блоки галочками → MERGE. */
    private fun onBackupFilePicked(uri: Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            toast(getString(R.string.backup_restore_fail, "файл не читается"))
            return
        }
        val info = BackupStore.parse(act, bytes)
        if (info == null) {
            toast(getString(R.string.backup_bad_file))
            return
        }
        var sIdx = -1
        var bIdx = -1
        val items = ArrayList<String>()
        if (info.hasSettings) {
            sIdx = items.size
            items.add(getString(R.string.backup_block_settings))
        }
        if (info.hasBooks) {
            bIdx = items.size
            items.add(getString(R.string.backup_block_books))
        }
        if (items.isEmpty()) {
            toast(getString(R.string.backup_bad_file))
            return
        }
        val checked = BooleanArray(items.size) { true }
        val msg = StringBuilder(
            getString(R.string.backup_preview_title, backupDateStr(info.createdAt))
        )
        if (info.hasSettings) msg.append("\n").append(getString(R.string.backup_block_settings))
        if (info.hasBooks) {
            msg.append("\n").append(
                getString(R.string.backup_line_books, info.bookCount, info.bookmarkCount, info.quoteCount)
            )
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.backup_blocks_restore)
            .setMessage(msg)
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, _, _ -> }
            .setPositiveButton(R.string.backup_restore_go) { _, _ ->
                val doSettings = sIdx >= 0 && checked[sIdx]
                val doBooks = bIdx >= 0 && checked[bIdx]
                if (!doSettings && !doBooks) return@setPositiveButton
                val res = BackupStore.restore(act, bytes, doSettings, doBooks)
                if (res == null) {
                    toast(getString(R.string.backup_restore_fail, "файл повреждён"))
                } else {
                    toast(getString(
                        R.string.backup_restore_result,
                        res.booksAdded, res.booksUpdated, res.quotesAdded,
                    ))
                }
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun backupDateStr(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(ts))
    }

    private fun pickBackupAuto() {
        val values = arrayOf(
            BackupStore.AUTO_OFF, "day", BackupStore.AUTO_DEFAULT, "month",
        )
        val cur = values.indexOf(
            prefs.getString(BackupStore.KEY_AUTO, BackupStore.AUTO_DEFAULT)
        ).coerceAtLeast(2)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.backup_auto_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.backup_auto_off),
                    getString(R.string.backup_auto_day),
                    getString(R.string.backup_auto_week),
                    getString(R.string.backup_auto_month),
                ),
                cur,
            ) { d, which ->
                prefs.edit().putString(BackupStore.KEY_AUTO, values[which]).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun backupAutoLabel(): String = getString(
        when (prefs.getString(BackupStore.KEY_AUTO, BackupStore.AUTO_DEFAULT)
            ?: BackupStore.AUTO_DEFAULT) {
            BackupStore.AUTO_OFF -> R.string.backup_auto_off
            "day" -> R.string.backup_auto_day
            "month" -> R.string.backup_auto_month
            else -> R.string.backup_auto_week
        }
    )

    private fun startIndex(): Int =
        if (prefs.getString(MainActivity.KEY_START, MainActivity.START_LAST) == MainActivity.START_LAST) 1 else 0

    /** Значение ползунка скорости/тона для подписи и озвучки («0.8», «1.2»). */
    private fun rateLabel(rate: Float): String =
        String.format(java.util.Locale.ROOT, "%.1f", rate)

    // ---------------- Папка с книгами ----------------

    private fun treeUri(): String? = prefs.getString(LibraryActivity.KEY_TREE, null)

    private fun onFolderPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(LibraryActivity.KEY_TREE, tree.toString()).apply()
        toast(getString(R.string.folder_saved) + ". " + getString(R.string.folder_hint))
        refreshRows()
    }

    /** Человеческое имя папки из tree-uri (например "primary:Books" -> "Books"). */
    private fun folderLabel(tree: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
        val raw = if (docId.contains(':')) docId.substringAfter(':') else docId
        return raw.substringAfterLast('/').ifBlank { raw }
    }

    // ---------------- Папка и формат скачивания из каталога ----------------

    private fun dlTreeUri(): String? = prefs.getString(OpdsPrefs.KEY_DL_DIR, null)

    private fun onDlFolderPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(OpdsPrefs.KEY_DL_DIR, tree.toString()).apply()
        toast(getString(R.string.dl_folder_saved))
        refreshRows()
        probeDlDir(tree)
    }

    /** Проверка папки скачиваний сразу после выбора. Сторонние проводники
     *  (MixPlorer) отдают tree-uri, но не умеют createDocument — запись через
     *  SAF падает. Папка при этом всё равно рабочая, если ей можно сопоставить
     *  реальный путь и включён «Доступ ко всем файлам»: качаем туда обычными
     *  File (msg1172/1192). Сбрасываем папку только если она не пишет ни SAF,
     *  ни реальным путём — тогда скачивание уйдёт во внутреннюю память. */
    private fun probeDlDir(tree: Uri) {
        Thread {
            val ok = runCatching {
                val probe = DocumentsContract.createDocument(
                    contentResolver, tree, "application/json", "BookVoice_probe"
                ) ?: return@runCatching false
                DocumentsContract.deleteDocument(contentResolver, probe)
                true
            }.getOrDefault(false)
            runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (ok) return@runOnUiThread
                val dir = AllFiles.resolveDir(tree)
                if (dir == null) {
                    prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                    refreshRows()
                    toast(getString(R.string.dl_folder_unwritable))
                    return@runOnUiThread
                }
                if (AllFiles.granted(act)) {
                    if (!AllFiles.probeViaRealPath(act, tree)) {
                        prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                        refreshRows()
                        toast(getString(R.string.dl_folder_unwritable))
                    }
                    // доступ есть и реальный путь пишет — папка рабочая, молчим
                } else {
                    AllFiles.ask(act, getString(R.string.all_files_explain))
                }
            }
        }.start()
    }

    private fun dlFormatKey(): String {
        val key = prefs.getString(OpdsPrefs.KEY_DL_FMT, OpdsPrefs.DEFAULT_DL_FMT)
            ?: OpdsPrefs.DEFAULT_DL_FMT
        return OPDS_READABLE_FORMATS.firstOrNull { it.first == key }?.first
            ?: OpdsPrefs.DEFAULT_DL_FMT
    }

    private fun dlFormatIndex(): Int =
        OPDS_READABLE_FORMATS.indexOfFirst { it.first == dlFormatKey() }.coerceAtLeast(0)

    // ---------------- Диалог «Голос и движок» ----------------

    /** Движок/голос меняем через живой плеер открытой книги (если она есть).
     *  Из библиотеки (без книги) предложить открыть книгу — голос выбирать
     *  не на чем. */
    private fun openVoiceDialog() {
        val live = MainActivity.active?.player
        if (live != null && live.isReady) {
            showVoicePicker(live, releaseOnClose = false)
            return
        }
        // Открытой книги нет (Настройки открыты сами по себе) — движок и голос всё
        // равно можно выбрать: на время диалога поднимаем временный движок, а выбор
        // сохраняем в настройки (применится при следующем открытии книги, MainActivity
        // читает KEY_ENGINE/KEY_VOICE в openBook). Раньше тут был только тост
        // «сначала открой книгу» — Сергей упёрся в него в 0.3.40 (msg766).
        val tmp = SpeechPlayer(act.applicationContext)
        val saved = prefs.getString(MainActivity.KEY_ENGINE, null)
        val loading = MaterialAlertDialogBuilder(act)
            .setTitle(R.string.voice_engine_value)
            .setMessage(R.string.engine_loading)
            .setNegativeButton(R.string.toc_close, null)
            .show()
        tmp.setEngine(saved) { ok ->
            if (!ok) {
                prefs.edit().remove(MainActivity.KEY_ENGINE).apply()
                runCatching { loading.dismiss() }
                tmp.shutdown()
                toast("Не удалось запустить голосовой движок")
                return@setEngine
            }
            if (!loading.isShowing) {
                // Пользователь закрыл окно, пока движок инициализировался.
                tmp.shutdown()
                return@setEngine
            }
            runCatching { loading.dismiss() }
            showVoicePicker(tmp, releaseOnClose = true)
        }
    }

    /** Диалог «выбор движка (шаг 1) → выбор голоса (шаг 2)». Работает как на живом
     *  плеере открытой книги ([releaseOnClose]=false — смена применяется сразу),
     *  так и на временном движке из [openVoiceDialog] (после закрытия временный
     *  движок гасится, выбор остаётся в настройках). */
    private fun showVoicePicker(p: SpeechPlayer, releaseOnClose: Boolean) {
        val scroll = android.widget.ScrollView(act)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        scroll.addView(body)
        val handler = Handler(Looper.getMainLooper())

        val btnBack = Button(act).apply {
            text = getString(R.string.engine_back)
            visibility = View.GONE
        }
        val engineTitle = TextView(act).apply {
            text = getString(R.string.engine_step)
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        val engineGroup = RadioGroup(act)

        val voiceBlock = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        voiceBlock.addView(TextView(act).apply {
            text = getString(R.string.voice_step)
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        })
        val voiceContainer = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        voiceBlock.addView(voiceContainer)

        fun showStep(step: Int) {
            val second = step == 2
            btnBack.visibility = if (second) View.VISIBLE else View.GONE
            engineTitle.visibility = if (second) View.GONE else View.VISIBLE
            engineGroup.visibility = if (second) View.GONE else View.VISIBLE
            voiceBlock.visibility = if (second) View.VISIBLE else View.GONE
        }

        fun revealVoices() {
            populateVoiceList(voiceContainer, p)
            showStep(2)
        }

        fun onEngineChosen(pkg: String) {
            if (pkg == p.enginePackage) {
                revealVoices()
                return
            }
            prefs.edit().putString(MainActivity.KEY_ENGINE, pkg).apply()
            voiceContainer.removeAllViews()
            voiceContainer.addView(TextView(act).apply {
                text = getString(R.string.engine_loading)
                textSize = 15f
            })
            p.setEngine(pkg) { ok ->
                handler.post {
                    if (ok) {
                        revealVoices()
                    } else {
                        prefs.edit().remove(MainActivity.KEY_ENGINE).apply()
                        p.setEngine(null) { _ -> revealVoices() }
                        toast("Движок не запустился, вернул системный")
                    }
                }
            }
        }

        body.addView(btnBack)
        body.addView(engineTitle)
        val engines = p.engines
        val defaultEngine = p.defaultEngine
        val currentEngine = p.enginePackage ?: defaultEngine
        if (engines.isEmpty()) {
            body.addView(TextView(act).apply {
                text = getString(R.string.no_engines)
                textSize = 15f
            })
        } else {
            for ((pkg, engineLabel) in engines) {
                val rb = RadioButton(act)
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

        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle(R.string.voice_engine_value)
            .setView(scroll)
            .setNegativeButton(R.string.toc_close, null)
            .show()
        if (releaseOnClose) dlg.setOnDismissListener { p.shutdown() }
    }

    private fun populateVoiceList(container: LinearLayout, p: SpeechPlayer) {
        container.removeAllViews()
        val voices = p.voices
        if (voices.isEmpty()) {
            container.addView(TextView(act).apply {
                text = getString(R.string.no_voices)
                textSize = 15f
            })
            return
        }
        val group = RadioGroup(act)
        container.addView(group)
        val current = prefs.getString(MainActivity.KEY_VOICE, null)
        for (v in voices) {
            val shown = if (v.isNetworkConnectionRequired) "${v.name} (сеть)" else v.name
            val rb = RadioButton(act)
            rb.text = shown
            rb.tag = v.name
            rb.isChecked = v.name == current
            rb.setOnClickListener {
                val sel = rb.tag as String
                prefs.edit().putString(MainActivity.KEY_VOICE, sel).apply()
                p.selectVoice(sel)
            }
            group.addView(rb)
        }
    }

    // ---------------- Диагностика ----------------

    private fun sendDiagLog() {
        Diag.log(act, "app", "=== ПОЛЬЗОВАТЕЛЬ НАЖАЛ «ОТПРАВИТЬ ЛОГ» (из настроек) ===")
        try {
            val f = Diag.fileUri(act)
            if (!f.exists() || f.length() == 0L) {
                toast("Лог пока пуст — сначала повтори сценарий")
                return
            }
            val uri = FileProvider.getUriForFile(act, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BookVoice diag.log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.log_sent_hint)))
        } catch (e: Exception) {
            Diag.log(act, "app", "ошибка отправки лога: ${e.message}")
            toast("Не удалось отправить лог: ${e.message}")
        }
    }

    private fun dp(v: Float): Int = (v * act.resources.displayMetrics.density).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
}
