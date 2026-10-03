package com.cuber.bookvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.appcompat.widget.SwitchCompat
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Разделы настроек (0.3.32): главный экран — список групп, внутри — свой короткий список. */
private enum class Group(@param:StringRes val titleRes: Int, @param:StringRes val hintRes: Int) {
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
    private var playLongRow: Button? = null  // msg2695/2699: долгое нажатие «▶»
    private var jumpFwdRow: Button? = null   // msg5234: прыжок — число вперёд
    private var jumpBackRow: Button? = null  // msg5234: прыжок — число назад
    private var folderRow: Button? = null
    private var dlFolderRow: Button? = null
    private var dlFormatRow: Button? = null
    // Строки-резюме назначенных жестов (#77).
    private var gestureRightRow: Button? = null
    private var gestureLeftRow: Button? = null
    // msg5266: восемь строк действий кнопок читалки — по две на кнопку (короткое
    // и долгое нажатие). Подпись постоянная («нижняя левая: долгое нажатие»),
    // меняется только значение, поэтому храним её рядом с кнопкой.
    // msg5707: галочки «кнопка видна» в этот список не входят — у них подпись
    // постоянная (по месту кнопки), обновлять в refreshRows нечего.
    private val buttonActionRows = ArrayList<ButtonActionRow>()

    /** Строка-резюме одного действия кнопки читалки (msg5266): ключ настройки,
     *  значение по умолчанию и постоянная подпись строки. [uiKey] — ключ
     *  «показывать кнопку»: по нему строка говорит, что кнопка спрятана
     *  (0.4.86, msg7026), — иначе, убрав галочку из этого подраздела, человек
     *  потерял бы связь между кнопкой и её действиями. */
    private class ButtonActionRow(
        val key: String,
        val def: String,
        val title: String,
        val button: Button,
        val uiKey: String,
    )
    // 0.4.86 (msg7026): строка «Что показывать в книге» в «Экране книги» —
    // открывает подраздел со всеми галочками видимости.
    private var showGroupRow: Button? = null
    // Строки-резюме кнопок гарнитуры „назад/вперёд“ (msg2136).
    private var headsetPrevRow: Button? = null
    private var headsetNextRow: Button? = null
    // msg5685/5699: «Кнопки и жесты» — подраздел «Управления». В списке раздела
    // остаётся строка-переход. Раньше так жили только кнопки гарнитуры
    // (controlsGroupRow), теперь туда же уехали свайпы, действия четырёх кнопок
    // читалки и прыжок — из «Экрана книги» и из общего списка «Управления».
    private var controlsGroupRow: Button? = null
    // Строки-резюме блока «После звонка» (#98).
    private var afterCallRow: Button? = null
    // Строка-резюме «Отступать назад при старте» (msg2093, раздел «Чтение»).
    private var startRewindRow: Button? = null
    // 30.09.2026: строки-выборы раздела «Чтение» вместо пачек галочек —
    // «читать сразу после перехода» (jumpPlayRow). Строки «что останавливает
    // чтение» и «что прокрутка делает с местом и голосом» в тот же вечер стали
    // тремя галочками и одной галочкой: окна у них больше нет.
    private var jumpPlayRow: Button? = null
    // Галочка «Читать с того места, куда прокрутил»: показывается только при
    // включённой прокрутке, поэтому ссылка на неё нужна (раздел «Чтение»).
    private var scrollPlaceBox: SwitchCompat? = null
    // msg5604: строка-значение «Пауза между фразами» — сколько тишины движка
    // оставлять на стыке предложений (раздел «Чтение»).
    private var pauseKeepRow: Button? = null
    // 30.09.2026: строка «Громкость тихого потока» — уровень того самого
    // неслышного звука, который держит звуковой тракт открытым (раздел «Чтение»).
    private var silentLevelRow: Button? = null
    // msg5931: строка «Системные кнопки» в разделе «Экран книги» — показывать /
    // скрывать во время чтения / скрывать, пока открыта книга.
    private var readerBarsRow: Button? = null
    // Строки-кнопки вкладок Библиотеки (#97): id режима → кнопка «<Имя>: показана/скрыта».
    private val tabRowButtons = ArrayList<Pair<Int, Button>>()
    private var resetTabsRow: Button? = null
    // Резервная копия (#100): папка и частота (строка-резюме).
    private var backupDirRow: Button? = null
    // 0.4.86 (msg7020): строка «Резервные копии» в «Библиотеке» — открывает
    // подраздел; туда же возвращает фокус «назад» из подраздела.
    private var backupGroupRow: Button? = null
    // msg6046…6078: синхронизация чтения между устройствами. 28.09.2026 место
    // синхронизации выбирает владелец: строка «Куда синхронизировать», строка
    // выбранного места со входом и строка состояния — куда файл идёт сейчас.
    private var syncPlaceRow: Button? = null
    private var syncAccountRow: Button? = null
    private var syncStateRow: TextView? = null
    private var syncLastRow: TextView? = null
    // 0.4.86 (msg7020): строка «Синхронизация» в «Библиотеке» — открывает
    // подраздел (весь блок переехал туда).
    private var syncGroupRow: Button? = null
    private var booksBox: SwitchCompat? = null
    private var booksRow: Button? = null
    // #57: галочка «Бесшовная передача» — при альтернативном способе озвучки
    // становится недоступной (она в этом режиме не работает).
    // #70 (msg5503/5511): движок, язык и голос — три строки-значения вместо одной
    // кнопки «Голос и движок» с визардом и плоским списком всех голосов движка.
    // #75 (msg5618): строки больше не свои — их держит общий набор VoicePicker,
    // тот же самый, что в панели читалки: один код, один вид, одни списки.
    private var voicePicker: VoicePicker? = null
    private var backupAutoRow: Button? = null
    // msg4895: строка «Разобранный текст» — занято из потолка (BookCache).
    private var cacheRow: Button? = null
    // msg5730: строка «Размер текста» — общая ручка размера для всего приложения.
    private var textScaleRow: Button? = null
    // 30.09.2026: списка галочек «что видно на экране книги» (readerUiBoxes) и
    // набора «что оставить на простом экране» (SIMPLE_KEEP) больше нет — вместе
    // со строками «простой экран» они стали не нужны: набор собирается галочками
    // в подразделе «Что показывать в книге».

    // Открытый раздел (null = экран списка групп).
    private var group: Group? = null

    // msg2527: открыт подраздел внутри раздела (навигация: корень → раздел →
    // подраздел). Пока true, «назад» ведёт в список «Управления», а не в корень.
    // msg5685/5699: подраздел теперь один — «Кнопки и жесты»; прежний «Настройки
    // кнопок гарнитуры» убран, его две строки переехали внутрь нового.
    private var controlsSubOpen = false

    // 0.4.86 (msg7020): подразделы «Резервные копии» и «Синхронизация» внутри
    // раздела «Библиотека». «Назад» из них возвращает в «Библиотеку» и ставит
    // фокус на ту строку, из которой вошли.
    private var backupSubOpen = false
    private var syncSubOpen = false
    // 0.4.86 (msg7026): подраздел «Что показывать в книге» внутри «Экрана книги».
    private var showSubOpen = false

    // Фокус на контент уже поставлен после первого показа (вход по нижней полосе
    // или из читалки). Дальше возвраты из пикеров фокус не трогают (msg1652).
    private var contentFocusedOnce = false

    // Пункты конструктора экрана чтения (#58) — порядок показа в разделе.
    private val readerUi: List<Pair<Int, String>> = listOf(
        R.string.ui_play_title to MainActivity.KEY_UI_PLAY,
        R.string.ui_voice_title to MainActivity.KEY_UI_VOICE,
        R.string.ui_slider_title to MainActivity.KEY_UI_SLIDER,
        R.string.ui_position_title to MainActivity.KEY_UI_POSITION,
        R.string.ui_stats_title to MainActivity.KEY_UI_STATS,
        R.string.ui_toc_title to MainActivity.KEY_UI_TOC,
        R.string.ui_bookmark_title to MainActivity.KEY_UI_BOOKMARK,
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
    /** Откат, выбранный вместе с «Продолжить», пока ждём разрешение «Телефон»
     *  (30.09.2026): список строки «После звонка» — один, и без этого память о
     *  выборе терялась бы на время системного запроса. */
    private var pendingCallRewind: String? = null

    private fun requestPhonePerm() {
        act.requestPermission(Manifest.permission.READ_PHONE_STATE) { granted ->
            if (granted) {
                val e = prefs.edit().putString(
                    MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_CONTINUE,
                )
                pendingCallRewind?.let { e.putString(MainActivity.KEY_AFTER_CALL_REWIND, it) }
                e.apply()
                pendingCallRewind = null
                toast(getString(R.string.after_call_continue))
            } else {
                pendingCallRewind = null
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
        // Возврат из браузера после входа в Яндекс (msg6114): строка «Яндекс.Диск»
        // обязана показать новое состояние сразу, а не после перезахода в раздел.
        if (group == Group.LIBRARY) refreshRows()
        // 27.09.2026: возврат из окна словаря произношения — строку «Словарь
        // произношения» надо переписать: состояние меняется там, а строка живёт
        // здесь (жалоба Сержа: галочка стоит, а строка говорит «выключен»).
        voicePicker?.refresh()
        if (byTab || firstShow) {
            // 28.09.2026, по документации Android: имя экрана даёт имя панели —
            // его ставит setTitle (см. SectionActivity), и диктор объявляет его
            // сам, когда панель появляется. Свой перенос фокуса на заголовок убран:
            // где встать в новом окне, решает служба доступности. build контент не
            // дёргает (noFocus), конкурирующего переноса нет.
        }
    }

    /** «Назад» на странице настроек: внутри раздела — список групп; в корне —
     *  закрывает окно — возврат на полку/в читалку (редизайн msg1676+: Настройки
     *  — окно поверх, не вкладка). Свежий вход всегда открывает корневое меню
     *  разделов (раздел прошлого раза не держим). */
    fun onBackKey(): Boolean {
        // msg2233/2239: «назад» из раздела якобы выкидывает сразу на полку. По коду
        // внутри раздела (group != null) он обязан вернуть список групп; на полку
        // уводит только rootBack из КОРНЯ. Здесь пишем точную ветку + время, чтобы
        // по логу понять: был ли group==null (состояние сбито) или пришло ДВА
        // back-события (две строки подряд с интервалом <0.5с).
        // msg2527: третий уровень — подраздел «Кнопки и жесты» внутри «Управления»:
        // «назад» из него возвращает в список «Управления» (group снова START), а не в корень.
        // 0.4.86 (msg7020): такие же подразделы «Резервные копии» и «Синхронизация»
        // внутри «Библиотеки» — «назад» из них возвращает в «Библиотеку».
        Diag.log(act, "nav", "Настройки: «назад», " + when {
            controlsSubOpen -> "в подразделе «Кнопки и жесты» (Управление)"
            backupSubOpen -> "в подразделе «Резервные копии» (Библиотека)"
            syncSubOpen -> "в подразделе «Синхронизация» (Библиотека)"
            showSubOpen -> "в подразделе «Что показывать в книге» (Экран книги)"
            group == null -> "В КОРНЕ (список разделов)"
            else -> "в разделе ${group!!.name}"
        })
        if (controlsSubOpen) {
            controlsSubOpen = false
            // msg2527: вернуться из подраздела в список «Управления». Перестроить
            // раздел и поставить фокус на строку «Кнопки и жесты»
            // (как focusGroupButton после «назад» из раздела — msg1468). openGroup
            // здесь не зовём: он объявляет заголовок «Управление» ещё раз.
            screenTitle(getString(Group.START.titleRes))
            content().removeAllViews()
            buildStartGroup()
            refreshRows()
            scrollTop()
            controlsGroupRow?.let { TabNav.refocusAfterRebuild(binding.content, it) }
        } else if (backupSubOpen || syncSubOpen) {
            // 0.4.86 (msg7020): из подраздела — назад в «Библиотеку», фокусом на
            // ту строку, из которой вошли. openGroup здесь не зовём: он объявил бы
            // заголовок «Библиотека» ещё раз.
            val wasBackup = backupSubOpen
            backupSubOpen = false
            syncSubOpen = false
            forgetSubRows()
            screenTitle(getString(Group.LIBRARY.titleRes))
            content().removeAllViews()
            buildLibraryGroup()
            refreshRows()
            scrollTop()
            val row = if (wasBackup) backupGroupRow else syncGroupRow
            row?.let { TabNav.refocusAfterRebuild(binding.content, it) }
        } else if (showSubOpen) {
            // 0.4.86 (msg7026): назад из «Что показывать» — в «Экран книги»,
            // фокусом на строку подраздела.
            showSubOpen = false
            forgetSubRows()
            screenTitle(getString(Group.READER.titleRes))
            content().removeAllViews()
            buildReaderGroup()
            refreshRows()
            scrollTop()
            showGroupRow?.let { TabNav.refocusAfterRebuild(binding.content, it) }
        } else if (group != null) {
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

    /** Имя экрана настроек: заголовок в шапке плюс имя окна и панели для диктора
     *  (28.09.2026, по документации Android). Диктор объявляет имя панели сам,
     *  когда панель меняется, — поэтому имя обязаны менять ВСЕ переходы между
     *  экранами настроек. Раньше менял только вход в раздел, и обратный выход
     *  («назад» из раздела или подраздела) звучал молча — жалоба Сержа. */
    private fun screenTitle(text: String) {
        binding.tvTitle.text = text
        act.setTitle(text)
    }

    /** Главный экран настроек: список разделов (0.3.32). [focusGroup] — раздел,
     *  из которого вернулись «назад»: фокус встаёт на его строку (msg1468);
     *  null — обычный вход. [noFocus] — контент строится без переноса фокуса
     *  (первый показ: фокус ставит resume(), msg1652). */
    private fun showMenu(focusGroup: Group? = null, noFocus: Boolean = false) {
        screenTitle(getString(R.string.settings_title))
        content().removeAllViews()
        Group.values().forEach { g -> addGroupButton(g) }
        // msg4476: энергетические запреты — первой из «не разделов». Тестеры на
        // Poco и realme жаловались, что с заблокированным экраном чтение
        // замирает: система душит приложение, а разрешение «не ограничивать
        // батарею» вынимает его из Doze. Строка стоит в корне, а не в разделе:
        // это не настройка чтения, а разговор с системой.
        // msg4709: раньше строка сразу звала системный запрос, но на прошивке
        // дверей несколько (автозапуск, фоновая активность, ограничение у
        // движка речи) — открываем окно «Не засыпать»: там состояние и все
        // дороги. Подсказкой остаётся тот же статус: он виден, не заходя.
        addMenuRow(getString(R.string.sleep_title), bgHint()) {
            startActivity(Intent(act, SleepWindowActivity::class.java))
        }
        // msg3921: группа обсуждения — отдельной строкой корня, в один шаг от
        // входа в настройки. Раньше вход был кнопкой внутри «О программы»
        // (msg3903); вынесен сюда по просьбе Сергея — до группы надо было
        // долистать всю справку. Строка с подсказкой, как разделы, чтобы
        // скринридер читал корень одним ритмом.
        addMenuRow(getString(R.string.settings_chat), getString(R.string.settings_chat_hint)) {
            AuthorContact.open(act)
        }
        // msg3907: «О программе» — тоже последней строкой корня, а не в «Разном»
        // (msg3061): справке место рядом с разделами.
        addMenuRow(getString(R.string.about_title), getString(R.string.settings_about_hint)) {
            startActivity(Intent(act, AboutWindowActivity::class.java))
        }
        scrollTop()
        if (focusGroup != null) {
            focusGroupButton(focusGroup)
        } else if (!noFocus) {
            focusFirst()
        }
    }

    /** Открыть раздел: заголовок сверху, ниже — только его настройки. После
     *  открытия Сергей должен СЛЫШАТЬ название раздела (msg2314): раньше здесь
     *  стоял focusFirst — перенос на первый пункт (флажок), и TalkBack озвучивал
     *  его, а заголовок «Чтение» молчал, хотя реальный фокус садился на него
     *  (лог 0.4.7: «перенос на Озвучивать …» → «ИТОГ: tvTitle|Чтение»). Перенос
     *  на первый пункт убран; заголовок объявляем голосом — как сводку ленты в
     *  каталоге (announceFeed, msg1573). Фокус не тащим: он и так на заголовке. */
    private fun openGroup(g: Group) {
        // Свежий вход в раздел всегда показывает его список, а не подраздел
        // (msg2527): состояние подраздела живёт только между openControlsSub()
        // и возвратом «назад» внутри «Управления». 0.4.86 (msg7020): то же для
        // подразделов «Резервные копии» и «Синхронизация».
        controlsSubOpen = false
        backupSubOpen = false
        syncSubOpen = false
        showSubOpen = false
        forgetSubRows()
        group = g
        // Имя панели — имя раздела: диктор объявляет его, когда панель меняется
        // (28.09.2026, по документации). Свой announce ниже оставлен как запасной:
        // если диктор панельные имена не читает, название раздела всё равно звучит.
        screenTitle(getString(g.titleRes))
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
        binding.tvTitle.announceForAccessibility(getString(g.titleRes))
    }

    private fun buildVoiceGroup() {
        // «Голос» (msg721 + msg1102): только параметры звука. Тон — сюда же
        // (msg1096: «пропал ползунок тона»).
        //
        // #75 (msg5610/5614/5618): движок / язык / голос — общий с читалкой
        // набор VoicePicker. Сергей упёрся в то, что в двух местах это выглядело
        // по-разному: в панели читалки строки объявлялись «кнопкой», тут —
        // обычным текстом, а «Прослушать» значило разное (запуск чтения против
        // пробы голоса). Теперь это буквально один код: строки-значения читаются
        // как текст и там, и тут, списки и подписи одни и те же.
        // msg5632/msg5640: первая строка набора одна на оба места, а работа у
        // неё разная. С книгой — «Читать/Пауза»; без книги (здесь) — она же
        // «Прослушать»: говорит образец фразы выбранным голосом, скоростью и
        // тоном. Иначе, крутя голос в Настройках, не слышно, что выбрал.
        val picker = VoicePicker(act, prefs, pickerHost)
        voicePicker = picker
        picker.build(content())

        addRateSlider(MainActivity.KEY_SPEED, R.string.speed_value, "Скорость", RateSteps.SPEED) {
            MainActivity.active?.player?.speed = it
        }
        addRateSlider(MainActivity.KEY_PITCH, R.string.tone_value, "Тон", RateSteps.PITCH) {
            MainActivity.active?.player?.pitch = it
        }
        // Громкость чтения (0..100%) — общий KEY_VOLUME (тот же, что был в окне «Голос и речь»).
        addVolumeSlider()
        // «По ролям» с галочкой рядом — там же, где у панели читалки: внизу
        // набора, перед строкой словаря (просьба Сержа 29.09.2026).
        content().addView(picker.replyBlock())

        // Словарь произношения (27.09.2026) — строкой внизу звукового раздела, и
        // та же строка стоит внизу панели «Голос» из книги (VoicePicker.dictRow):
        // настройка одна, дверей две, и обе выглядят одинаково.
        content().addView(picker.dictRow())
    }

    /** Что набор спрашивает у Настроек (msg5618). Здесь всё пишется в общие
     *  настройки сразу, в момент выбора: книги, за которой можно было бы
     *  запомнить голос отдельно, в этом окне нет. */
    private val pickerHost = object : VoicePicker.Host {
        override fun withPlayer(titleRes: Int, onReady: (SpeechPlayer, Boolean) -> Unit) =
            withVoiceEngine(titleRes, onReady)

        /** Окно «Чтение по ролям» — то же, что открывает читалка: настройка
         *  одна, дверей две (26.09.2026). */
        override fun openReplyWindow() {
            act.startActivity(Intent(act, ReplyVoiceActivity::class.java))
        }

        /** Галочка «Чтение по ролям» в наборе голоса (29.09.2026). Настройка
         *  одна на читалку и Настройки, поэтому сразу отдаём её живому плееру,
         *  если книга открыта. */
        override fun replyToggled(on: Boolean) {
            MainActivity.active?.player?.setReplyVoice(
                on,
                prefs.getString(MainActivity.KEY_REPLY_ENGINE, null),
                prefs.getString(MainActivity.KEY_REPLY_VOICE, null),
            )
            ReaderEngine.restartAfterSwitch()
        }

        override fun playerOrNull(): SpeechPlayer? = MainActivity.active?.player

        /** Книги тут нет: «Читать/Пауза» и «Запомнить для этой книги» — не про
         *  это окно. Живую книгу настраивают в панели читалки, там же и галочка. */
        override fun hasBook(): Boolean = false
        override fun isPlaying(): Boolean = false
        override fun togglePlay() = Unit
        override fun rememberChecked(): Boolean = false
        override fun rememberChanged(checked: Boolean) = Unit

        override fun currentVoice(): String? = prefs.getString(MainActivity.KEY_VOICE, null)

        override fun engineApplied(pkg: String) {
            prefs.edit().putString(MainActivity.KEY_ENGINE, pkg).apply()
            // Перечитку текущего предложения (msg5604) набор делает сам — уже
            // после того, как движок действительно сменился.
        }

        override fun voiceApplied(v: Voice, lang: String?) {
            val e = prefs.edit().putString(MainActivity.KEY_VOICE, v.name)
            // msg5622: язык голоса пишем, только если он не запасной — иначе
            // движок, не знающий выбранного руками языка, стёр бы этот выбор
            // выбором голоса из чужого языка. Запасной язык вернётся сам, когда
            // попадётся движок, который его знает.
            val want = prefs.getString(MainActivity.KEY_VOICE_LANG, null)
            if (want == null || lang == want) {
                e.putString(MainActivity.KEY_VOICE_LANG, VoicePick.codeOf(v))
            }
            e.apply()
            if (MainActivity.active?.player?.isReady == true) {
                MainActivity.active?.player?.selectVoice(v.name)
            }
            ReaderEngine.restartAfterSwitch()
        }

        override fun langApplied(code: String) {
            prefs.edit().putString(MainActivity.KEY_VOICE_LANG, code).apply()
        }

        override fun redraw() = refreshRows()
        override fun toast(msg: String) = this@SettingsActivity.toast(msg)
    }

    /** Уходим из Настроек — гасим плеер образца фразы (msg5640). Движок за
     *  собой держать незачем: следующий заход поднимет его снова. */
    fun shutdownSample() {
        voicePicker?.shutdownSample()
    }

    /** «Чтение» (msg721): когда начинать чтение и что озвучивать. Переехало из «Голоса».
     *  msg5711: 19 строк — простыня, поэтому раздел размечен заголовками по смыслу,
     *  как «Кнопки и жесты».
     *
     *  30.09.2026 (просьба Сержа: «сделать покомпактнее, чтобы было не так много
     *  всего»): было 22 строки, пять заголовков и три подсказки отдельными
     *  остановками — стало 12 строк и четыре заголовка. Что именно сделано:
     *  — пять галочек «читать сразу при переходе» собраны в одну строку-выбор с
     *    окном, а пункт «Настроить по отдельности» разворачивает их под строкой;
     *  — три галочки «останавливать чтение» (наушники, экран, экран вниз) — одна
     *    строка с окном из трёх галочек;
     *  — две галочки прокрутки — одна строка «что прокрутка делает», и видна она
     *    только когда прокрутка включена;
     *  — «Громкость тихого потока» видна только при включённом тихом потоке;
     *  — убраны галочки, которые ничего не решали: название главы теперь звучит
     *    всегда, о долгом открытии книга говорит всегда (Серж: «а зачем эта
     *    галочка вообще нужна, пускай всегда включена»);
     *  — подсказки альтернативного способа и тихого потока стали второй строкой
     *    своих галочек: раньше каждая была отдельной остановкой в списке;
     *  — «Продолжать чтение после прерывания другим плеером» переехала в
     *    «Прерывания»: она про вторжение извне, а не про начало книги.
     *  Порядок строк прежний, кроме «Паузы между фразами»: она ушла из «Начала
     *  книги» в звуковую группу — она про стык, а не про старт.
     *
     *  30.09.2026, дальше в тот же вечер: заголовки внутри раздела убраны
     *  (Серж: «дурацкая формулировка, вообще убирай», потом «заголовки убираем
     *  отсюда») — строки идут одним списком: старт, переходы, звук, прерывания,
     *  прокрутка. Заголовков в разделе нет вовсе. */
    private fun buildReadingGroup() {
        // msg2685: о долгом открытии книга говорит сама (30.09.2026 — всегда),
        // поэтому это сказано подсказкой в самой галочке, а не отдельной строкой.
        addCheck(
            R.string.auto_start_title, MainActivity.KEY_AUTO_START, true,
            hintRes = R.string.auto_start_hint,
        )
        // msg2093: «Отступать назад при старте» — начать на N предложений раньше
        // места остановки, чтобы вспомнить, что было. Выключено по умолчанию.
        startRewindRow = addValueButton { pickStartRewind() }
        // 29.09.2026 (просьба Сержа): одно слово о структуре текста — «Абзац» и
        // «Разделитель». Стоит здесь, а не в «Переходах»: это про то, что
        // слышно, когда идёшь по книге.
        addCheck(
            R.string.line_names_title, MainActivity.KEY_LINE_NAMES, true,
            hintRes = R.string.line_names_hint,
        )
        // Переходы (тап, оглавление, закладка, поиск, кнопки) — один выбор вместо
        // пяти галочек (30.09.2026).
        jumpPlayRow = addValueButton { pickJumpPlay() }
        if (jumpPlayDetail()) {
            // «Настроить по отдельности»: те же пять галочек, что были до
            // 30.09.2026, с прежними значениями по умолчанию. Резюме строки
            // обновляем на месте, чтобы человек слышал, что у него получилось.
            addCheck(R.string.tap_to_play_title, MainActivity.KEY_TAP_TO_PLAY, true) {
                refreshJumpPlayRow()
            }
            addCheck(R.string.toc_play_title, MainActivity.KEY_TOC_PLAY, true) {
                refreshJumpPlayRow()
            }
            addCheck(R.string.bm_play_title, MainActivity.KEY_BM_PLAY, true) {
                refreshJumpPlayRow()
            }
            addCheck(R.string.search_play_title, MainActivity.KEY_SEARCH_PLAY, true) {
                refreshJumpPlayRow()
            }
            // 0.4.74: то же правило для шагов по книге кнопками и свайпами.
            addCheck(R.string.nav_play_title, MainActivity.KEY_NAV_PLAY, true) {
                refreshJumpPlayRow()
            }
        }

        // 30.09.2026 (Серж: «„Звук и стыки“ — дурацкая формулировка, вообще
        // убирай»): заголовок убран, строки про звук идут сразу за строками
        // старта. msg5711: «Пауза между фразами» переехала сюда из «Начала
        // книги» — она про стык фраз, а не про старт.
        // msg5604: «Пауза между фразами» — сколько тишины, которую движок
        // дописывает по краям фразы, оставлять на стыке. У сетевых голосов
        // Google её 0,5–0,7 с на фразу, и это слышно как пауза в чтении.
        pauseKeepRow = addValueButton { pickPauseKeep() }
        // msg5254: галочки «Короткие паузы между предложениями» здесь больше нет
        // (Сергей: «давай уберём эту настройку») — склейка коротких фраз и
        // обрезка тишины по краям работают всегда, как раньше по умолчанию.
        // #57: альтернативный способ озвучки — звук целиком отдаём движку.
        // Тестовая галочка, по умолчанию выкл; применяется на месте, не
        // дожидаясь перезапуска.
        // 29.09.2026, просьба Сержа: тихий поток включается ВМЕСТЕ с этой
        // галочкой. Зачем: в альтернативном способе звук играет движок, а не
        // читалка, поэтому система перестаёт видеть нас играющими и кнопки на
        // наушниках (волшебное касание) уходят чужому плееру — тихий поток
        // возвращает нас в список играющих. Галочку переключаем тем же CheckBox,
        // что и руками: он сам запишет настройку, применит её и напишет строку в
        // журнал. Снять руками можно — обратно встанет при следующем включении
        // прямого режима (30.09.2026: следит уже сам движок —
        // SpeechPlayer.altDirect и netDirect зовут KeepAwake.enableSilenceAuto,
        // поэтому способ, включённый не этой галочкой, тоже не теряет кнопки).
        // 30.09.2026: подсказки у галочки нет, а название Серж вернул к
        // привычному «Альтернативный способ чтения» («надо исправить на
        // альтернативный способ чтения всё-таки») — под этим именем он её и
        // ищет. Про тихий поток сказано в его собственной строке ниже.
        var silentBox: SwitchCompat? = null
        addCheck(
            R.string.alt_voice_title, MainActivity.KEY_ALT_VOICE, false,
        ) { on ->
            ReaderEngine.player?.altDirect = on
            silentBox?.isChecked = on
        }
        // msg5067: тихий звуковой поток переехал сюда из окна «Не засыпать»
        // (Сергей искал его как звуковую настройку и не нашёл: окно про батарею).
        // Он тоже про звук на стыках: держит звуковой тракт открытым, чтобы он
        // не закрывался между фразами. (Бывшая пара — «Бесшовная передача» —
        // убрана в msg5955: про стык предложений галочек больше нет.) Ниже по
        // разделу уже другое: прокрутка, она про место, а не про голос.
        // 30.09.2026: подсказки у галочки нет — всё сказано в одной строке
        // (Серж: «можно всё в одной строчке, просто коротко»).
        silentBox = addCheck(
            R.string.sleep_silent_title, MainActivity.KEY_SILENT_KEEPALIVE, false,
        ) { on ->
            KeepAwake.syncSilence()
            logSilence(on)
            // 30.09.2026: строка громкости относится только к включённому потоку —
            // включать её незачем, а раздел от неё только длиннее.
            silentLevelRow?.visibility = if (on) View.VISIBLE else View.GONE
        }
        // 30.09.2026: уровень тихого звука — по образцу @Voice Aloud Reader, где
        // у этой же настройки есть поле громкости. Нужен на случай гарнитуры,
        // которая и наш поток считает тишиной: человек поднимет сам, вместо того
        // чтобы гадать, почему галочка стоит, а начало фраз всё равно теряется.
        // Строка появляется только при включённом потоке: создаём её всегда, а
        // прячем невидимой — так галочка выше может показать её на месте, не
        // пересобирая раздел (пересборка сбросила бы фокус с этой же галочки).
        silentLevelRow = addValueButton { pickSilentLevel() }
        silentLevelRow?.visibility =
            if (prefs.getBoolean(MainActivity.KEY_SILENT_KEEPALIVE, false)) View.VISIBLE else View.GONE

        // msg5711: строки про прерывания — что делать, когда в чтение влезло
        // что-то извне: звонок или снятые наушники.
        // #98 «После звонка» (30.09.2026): одна строка со всеми случаями —
        // «Остановиться» и четыре «Продолжить…» с разным откатом. Прежде было
        // две строки (режим и отдельно откат), и человек читал про откат, даже
        // когда стояло «Остановиться».
        afterCallRow = addValueButton { pickAfterCallMode() }
        // 30.09.2026: строку «Продолжать чтение после прерывания другим плеером»
        // убрали (Серж: «сделать чтобы было включено и всё»): возврат звука после
        // чужого плеера работает всегда, настройки у него больше нет.
        // 30.09.2026: три галочки «останавливать чтение» — вместо одной строки с
        // окном. В названии каждой сказано, что она делает, и окно с длинным
        // сообщением не нужно. Сторожа экрана и переворота читают настройку на
        // месте, поэтому включённое начинает работать сразу, без перезапуска
        // чтения (наушники движок перечитывает при следующем старте чтения).
        addCheck(
            R.string.headphones_pause_title, MainActivity.KEY_PAUSE_HEADSET, true,
        )
        addCheck(
            R.string.pause_on_screen_title, MainActivity.KEY_PAUSE_ON_SCREEN, false,
        ) {
            ScreenOnPause.sync()
        }
        addCheck(
            R.string.pause_face_down_title, MainActivity.KEY_PAUSE_FACE_DOWN, false,
            hintRes = R.string.pause_face_down_hint,
        ) {
            FaceDownPause.sync()
        }

        // msg5711: строки про прокрутку — как экран книги связан с голосом.
        // 30.09.2026: Серж просил убрать их совсем, но потом уточнил, что сказал
        // не то, — строки остаются, с согласованными названиями (см. строки
        // scroll_title и scroll_place_title).
        addCheck(R.string.scroll_title, MainActivity.KEY_SCROLL, true) { on ->
            // Прокрутку выключили — строка про место больше ни о чём, и её прячем
            // на месте (раздел не пересобираем: пересборка сбросила бы фокус с
            // этой же галочки).
            scrollPlaceBox?.visibility = if (on) View.VISIBLE else View.GONE
        }
        // 30.09.2026: вместо строки-выбора «Что прокрутка делает с местом и
        // голосом» (четыре значения) — одна галочка. В коде эти два ключа и
        // правда ходят парой: после ручной прокрутки верхняя строка становится
        // местом чтения, а голос переезжает туда (MainActivity читает
        // KEY_SCROLL_PLACE и KEY_SCROLL_FOLLOW вместе). Старое состояние из
        // четырёх значений («только место», «только голос») сводим к одному:
        // галочка либо стоит, либо нет.
        val scrollPlaceOn = prefs.getBoolean(MainActivity.KEY_SCROLL_PLACE, true) &&
            prefs.getBoolean(MainActivity.KEY_SCROLL_FOLLOW, true)
        if (!scrollPlaceOn && (prefs.getBoolean(MainActivity.KEY_SCROLL_PLACE, true) ||
                prefs.getBoolean(MainActivity.KEY_SCROLL_FOLLOW, true))
        ) {
            prefs.edit()
                .putBoolean(MainActivity.KEY_SCROLL_PLACE, false)
                .putBoolean(MainActivity.KEY_SCROLL_FOLLOW, false)
                .apply()
            Diag.log(act, "reading", "«Чтение»: прокрутка — старое значение сведено к «выключено»")
        }
        scrollPlaceBox = addCheck(
            R.string.scroll_place_title, MainActivity.KEY_SCROLL_PLACE, true,
        ) { on ->
            prefs.edit().putBoolean(MainActivity.KEY_SCROLL_FOLLOW, on).apply()
        }
        scrollPlaceBox?.visibility =
            if (prefs.getBoolean(MainActivity.KEY_SCROLL, true)) View.VISIBLE else View.GONE
    }

    // ---------------- «Чтение»: строки-выборы (30.09.2026) ----------------

    /** Состояние «показывать галочки переходов врозь»: либо человек сам выбрал
     *  «Настроить по отдельности», либо у него стоит набор, которого нет среди
     *  готовых (например, достался от прежних сборок с пятью галочками) —
     *  тогда галочки показываем сразу, чтобы его набор было видно и можно было
     *  поправить. Это состояние самого раздела, а не настройка чтения: живёт в
     *  prefs, чтобы список не схлопывался при каждом заходе в «Чтение». */
    private fun jumpPlayDetail(): Boolean =
        jumpPlayPreset() < 0 || prefs.getBoolean("reading_jump_play_detail", false)

    /** Пять переходов, у каждого до 30.09.2026 была своя галочка «читать сразу».
     *  Порядок тот же, что был у галочек в разделе. */
    private val jumpPlayKeys = listOf(
        MainActivity.KEY_TAP_TO_PLAY,
        MainActivity.KEY_TOC_PLAY,
        MainActivity.KEY_BM_PLAY,
        MainActivity.KEY_SEARCH_PLAY,
        MainActivity.KEY_NAV_PLAY,
    )

    /** Номер готового набора: 0 — везде, 1 — только тап и оглавление, 2 — нигде.
     *  Минус единица — набор свой, его одним словом не назвать. */
    private fun jumpPlayPreset(): Int {
        val on = jumpPlayKeys.map { prefs.getBoolean(it, true) }
        return when {
            on.all { it } -> 0
            on.none { it } -> 2
            on == listOf(true, true, false, false, false) -> 1
            else -> -1
        }
    }

    /** Резюме строки «Читать сразу после перехода»: готовый набор называем по
     *  имени, свой — «По отдельности» (тогда галочки и разворачиваются). */
    private fun jumpPlayLabel(): String = getString(
        when (jumpPlayPreset()) {
            0 -> R.string.jump_play_all
            1 -> R.string.jump_play_tap_toc
            2 -> R.string.jump_play_none
            else -> R.string.jump_play_custom
        }
    )

    private fun refreshJumpPlayRow() {
        jumpPlayRow?.text = valueLine(getString(R.string.jump_play_title), jumpPlayLabel())
    }

    /** Окно выбора: три готовых набора и «настроить по отдельности». */
    private fun pickJumpPlay() {
        val items = arrayOf(
            getString(R.string.jump_play_all),
            getString(R.string.jump_play_tap_toc),
            getString(R.string.jump_play_none),
            getString(R.string.jump_play_detail),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.jump_play_dialog)
            // Ничего не отмечено нарочно: наборы — это не «одно из», а выбор
            // действия, и подсвечивать нечего, если стоит свой набор.
            .setSingleChoiceItems(items, -1) { d, which ->
                val e = prefs.edit()
                when (which) {
                    0 -> {
                        for (k in jumpPlayKeys) e.putBoolean(k, true)
                        e.putBoolean("reading_jump_play_detail", false)
                    }
                    1 -> {
                        e.putBoolean(MainActivity.KEY_TAP_TO_PLAY, true)
                        e.putBoolean(MainActivity.KEY_TOC_PLAY, true)
                        e.putBoolean(MainActivity.KEY_BM_PLAY, false)
                        e.putBoolean(MainActivity.KEY_SEARCH_PLAY, false)
                        e.putBoolean(MainActivity.KEY_NAV_PLAY, false)
                        e.putBoolean("reading_jump_play_detail", false)
                    }
                    2 -> {
                        for (k in jumpPlayKeys) e.putBoolean(k, false)
                        e.putBoolean("reading_jump_play_detail", false)
                    }
                    else -> e.putBoolean("reading_jump_play_detail", true)
                }
                e.apply()
                Diag.log(act, "reading", "«Чтение»: читать сразу после перехода — ${jumpPlayLabel()}")
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    // 30.09.2026: окна «что останавливает чтение» и «что прокрутка делает с
    // местом и голосом» убраны — вместо них галочки прямо в разделе (см.
    // buildReadingGroup). Вместе с окнами ушли pauseStopKeys, pauseStopLabel,
    // refreshPauseStopRow, pickPauseStop, scrollWhatLabel и pickScrollWhat.

    /** Ползунок скорости/тона по списку значений [values] (RateSteps): свайп
     *  TalkBack двигает ровно на одну десятую (0.6 → 0.7 → 0.8), а не на сотые
     *  (1.06). У скорости список длиннее — до 4.0 (msg3933), у тона прежний, до
     *  2.0. Подпись-значение обновляется над ползунком, [apply] применяет
     *  значение к плееру сразу. */
    private fun addRateSlider(
        key: String,
        labelRes: Int,
        cd: String,
        values: List<Float>,
        apply: (Float) -> Unit,
    ) {
        val ticks = values.lastIndex
        fun valueOf(p: Int): Float = values[p.coerceIn(0, ticks)]
        fun progressOf(v: Float): Int = RateSteps.indexOf(values, v)

        val cur = prefs.getFloat(key, 1f)
        val startP = progressOf(cur)
        val startV = valueOf(startP)
        val label = TextView(act).apply {
            text = getString(labelRes, rateLabel(startV))
            textSize = 17f
            setTextColor(Palette.INK)
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
    private fun addVolumeSlider(
        key: String = MainActivity.KEY_VOLUME,
        labelRes: Int = R.string.volume_value,
        apply: (Float) -> Unit = { MainActivity.active?.player?.volume = it },
    ) {
        val startP = Math.round(prefs.getFloat(key, 1f) * 100).coerceIn(0, 200)
        val label = TextView(act).apply {
            text = getString(labelRes, startP)
            textSize = 17f
            setTextColor(Palette.INK)
            setPadding(0, 0, 0, dp(2))
        }
        content().addView(label)

        val cd = getString(R.string.volume_cd)
        val seek = SeekBar(act).apply {
            max = 200
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
                prefs.edit().putFloat(key, v).apply()
                apply(v)
                announce(progress)
                label.text = getString(labelRes, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        content().addView(seek, lp().apply { bottomMargin = dp(6) })
    }

    private fun buildReaderGroup() {
        // msg5730: размер текста — первой строкой раздела. Он про то, КАК экран
        // выглядит, а не про то, что на нём есть; и он один на всё приложение,
        // не только на читалку. Ставим его выше галочек, чтобы до самой нужной
        // слабовидящему настройки было меньше ходов.
        addTextScaleRow()

        // msg5730: высокая контрастность — тоже про то, КАК экран выглядит. Тема
        // выбирается при создании окна, поэтому галочка сразу пересоздаёт окно
        // (как и размер текста), а раздел возвращается по имени.
        addCheck(R.string.contrast_title, Palette.KEY, false) {
            Diag.log(act, "ui", "высокая контрастность: $it — окно пересоздаётся")
            act.recreate()
        }

        // 30.09.2026: подсказки здесь больше нет — галочки живут в подразделе
        // «Что показывать в книге», и там же сказано, что значит галочка. Здесь
        // строка говорит состояние числом, и этого довольно.
        // Конструктор экрана чтения (#58): какие элементы читалки показывать.
        // Применяется в MainActivity.onStart (applyReaderUi) при возврате в книгу.
        showGroupRow = addValueButton { openShowSub() }

        // 30.09.2026: строк «простой экран» («Оставить только самое нужное» и
        // «Вернуть все кнопки») здесь больше нет — Серж попросил их убрать.
        // Набор галочек человек собирает сам в подразделе «Что показывать в книге».

        // msg5923/5931: системные кнопки («назад/домой/недавние») — тоже про то,
        // что видно на экране книги. Строка последняя в разделе.
        readerBarsRow = addValueButton { cycleReaderBars() }
    }

    // 30.09.2026: строки «простой экран» (msg5730) убраны (Серж: «в меню книги
    // убери две настройки — показывать только нужное и показывать все кнопки»).
    // Работали они так: разом проставляли галочки из подраздела «Что показывать
    // в книге» — либо самое нужное (кнопка чтения, ползунок, место и «⋮»), либо
    // всё подряд. Теперь набор человек собирает сам теми же галочками; строки
    // `reader_simple_*` и `reader_all_*` удалены вместе с кодом, как и константа
    // SIMPLE_KEEP со списком «что оставить».

    /** Строка «Размер текста» (msg5730): общий размер для всего приложения, а не
     *  только для книги. Меняется сразу и целиком: размер окно берёт при своём
     *  создании ([TextScale.wrap] в attachBaseContext), поэтому после выбора окно
     *  пересоздаётся, а открытый раздел возвращается по имени ([restoreGroup]) —
     *  человек остаётся там же, где выбирал.
     *
     *  Выбор из трёх готовых строк, а не ползунок: крайние значения надо слышать
     *  словами, а середина между «крупным» и «очень крупным» ничего не решает. */
    private fun addTextScaleRow() {
        textScaleRow = addValueButton {
            val cur = TextScale.value(act)
            val labels = TextScale.VALUES.map { getString(TextScale.labelRes(it)) }.toTypedArray()
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.text_scale_title)
                .setSingleChoiceItems(labels, TextScale.VALUES.indexOf(cur)) { d, which ->
                    val v = TextScale.VALUES[which]
                    d.dismiss()
                    // Тот же размер — окно не трогаем: пересоздание стоит перехода
                    // фокуса, а ничего не меняет.
                    if (v == cur) return@setSingleChoiceItems
                    TextScale.set(act, v)
                    Diag.log(act, "ui", "размер текста: $v — окно пересоздаётся")
                    act.recreate()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
    }

    /** Имя открытого раздела (null — корень). Окно берёт его перед пересозданием
     *  (msg5730, смена размера текста), чтобы вернуть человека в тот же раздел. */
    fun groupName(): String? = group?.name

    /** Вернуть открытый раздел после пересоздания окна. Незнакомое имя — корень. */
    fun restoreGroup(name: String?) {
        val g = Group.values().firstOrNull { it.name == name } ?: return
        openGroup(g)
    }

    /** Диалог выбора числа для прыжка (msg5234): готовый ряд чисел, а не ввод с
     *  клавиатуры — незрячему выбор из списка дешевле, а нужны круглые значения.
     *  [forward] — какое из двух чисел правим; в названиях пунктов слово
     *  «предложение» стоит в правильной форме (sentencesPhrase). */
    private fun pickJumpSteps(key: String, forward: Boolean) {
        val cur = prefs.getInt(key, MainActivity.JUMP_DEFAULT)
        val checked = MainActivity.JUMP_STEPS.indexOf(cur).coerceAtLeast(0)
        val labels = MainActivity.JUMP_STEPS
            .map { MainActivity.sentencesPhrase(act, it) }
            .toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle(if (forward) R.string.jump_fwd_dialog else R.string.jump_back_dialog)
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs.edit().putInt(key, MainActivity.JUMP_STEPS[which]).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Строка прыжка: «Прыжок вперёд: 15 предложений» (msg5234). */
    private fun jumpRowText(titleRes: Int, key: String): CharSequence =
        valueLine(
            getString(titleRes),
            MainActivity.sentencesPhrase(act, prefs.getInt(key, MainActivity.JUMP_DEFAULT))
        )

    /** Строки свайпов вправо/влево (#77). Раньше были отдельным разделом «Жесты»,
     *  по просьбе перенесены в «Управление». Палитра действий общая с ридером
     *  (MainActivity.GESTURE_ACTIONS): выбранный id хранится в prefs и читается
     *  диспетчером жестов при свайпе. msg2455: серая подсказка над строками убрана —
     *  в разделе остаются только сами строки выбора. */
    private fun addGestureRows() {
        gestureRightRow = addValueButton {
            // msg5220: по умолчанию «по всем заголовкам» — как свайп ходил раньше.
            pickGesture(MainActivity.KEY_GESTURE_RIGHT, MainActivity.G_NEXT_HEADER, R.string.gesture_choose_right)
        }
        gestureLeftRow = addValueButton {
            pickGesture(MainActivity.KEY_GESTURE_LEFT, MainActivity.G_PREV_HEADER, R.string.gesture_choose_left)
        }
    }

    /** Четыре кнопки читалки по три строки (msg5266/msg5707): видна ли кнопка,
     *  что делает по короткому нажатию, что по долгому. Подписи постоянные и
     *  называют место кнопки на экране («нижняя левая: долгое нажатие»); значение
     *  — выбранное действие. Идём по порядку [MainActivity.READER_BUTTONS]
     *  (верхние главы, нижние предложения), поэтому тройки идут подряд и
     *  читаются как список кнопок. */
    private fun addReaderButtonRows() {
        // Раздел перестраивается на каждый вход (и на возврате из подраздела) —
        // список строк надо очистить, иначе старые кнопки остаются в нём навсегда
        // и refreshRows() пишет текст в мёртвые вьюхи.
        buttonActionRows.clear()
        for (b in MainActivity.READER_BUTTONS) {
            val pos = getString(b.posRes)
            // 0.4.86 (msg7026): галочки «видна» здесь больше нет — все «что видно»
            // собраны в одном подразделе «Что показывать в книге» («Экран книги»).
            // Связь с кнопкой не потерялась: строка действия сама говорит, что
            // кнопка спрятана (см. [actionRowText]), и только у спрятанных.
            buttonActionRows.add(
                addActionRow(
                    b.key, b.def,
                    getString(R.string.btn_action_short_title, pos),
                    b.uiKey,
                )
            )
            buttonActionRows.add(
                addActionRow(
                    b.longKey, b.longDef,
                    getString(R.string.btn_action_long_title, pos),
                    b.uiKey,
                )
            )
        }
    }

    /** Строка-значение одного действия кнопки читалки: тап открывает общую
     *  палитру действий (pickAction), текст обновляется в [refreshRows].
     *  [uiKey] — ключ «показывать кнопку»: по нему строка сообщает, что кнопка
     *  спрятана. */
    private fun addActionRow(
        key: String,
        def: String,
        title: String,
        uiKey: String,
    ): ButtonActionRow {
        val btn = addValueButton { pickAction(key, def, title) }
        return ButtonActionRow(key, def, title, btn, uiKey)
    }

    /** Текст строки действия: «нижняя левая: долгое нажатие: Ничего не делать».
     *  Кнопка спрятана — говорим и это (0.4.86, msg7026): галочка «видна» теперь
     *  в другом подразделе, и без пометки связь потерялась бы. Пометка есть
     *  только у спрятанных строк. */
    private fun actionRowText(r: ButtonActionRow): CharSequence {
        val hidden = !prefs.getBoolean(r.uiKey, true)
        val who = if (hidden) getString(R.string.btn_action_hidden, r.title) else r.title
        return valueLine(who, gestureLabel(prefs.getString(r.key, r.def) ?: r.def))
    }

    /** Строки кнопок гарнитуры „назад/вперёд“ (msg2136). msg2527: жили в отдельном
     *  подразделе (openHeadsetSub); msg5685/5699 подраздел убран — строки стоят
     *  прямо в «Кнопках и жестах» (openControlsSub), в общей группе кнопок.
     *  У каждой кнопки свой выбор шага: „Выключено“ /
     *  предложение / абзац / глава. msg2455: серая подсказка над строками убрана —
     *  на экране остаются только сами строки выбора. */
    private fun addHeadsetRows() {
        headsetPrevRow = addValueButton {
            pickHeadset(MainActivity.KEY_HS_PREV)
        }
        headsetNextRow = addValueButton {
            pickHeadset(MainActivity.KEY_HS_NEXT)
        }
        // msg5295: отдельной строки «Шаг „главы“ на гарнитуре» больше нет — уровень
        // живёт в самом шаге (три пункта «Глава» в списке кнопки), как у свайпов и
        // кнопок читалки. Строка msg5230 убрана, ключ CH_NAV остался только
        // источником разового переноса (MainActivity.migrateHeadsetStep).
    }

    /** Диалог выбора шага для одной кнопки гарнитуры. */
    private fun pickHeadset(key: String) {
        val values = arrayOf(
            MainActivity.HS_OFF, MainActivity.HS_SENTENCE,
            MainActivity.HS_PARAGRAPH, MainActivity.HS_CHAPTER,
            MainActivity.HS_MAJOR, MainActivity.HS_HEADER,
            MainActivity.HS_SENT_N,
        )
        val cur = values.indexOf(
            prefs.getString(key, MainActivity.HS_SENTENCE) ?: MainActivity.HS_SENTENCE
        ).coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.gesture_choose_title)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.headset_off),
                    getString(R.string.headset_sentence),
                    getString(R.string.headset_paragraph),
                    getString(R.string.headset_chapter),
                    // msg5295: три уровня «главы» отдельными пунктами — ровно те,
                    // что уже есть у свайпов и кнопок читалки.
                    getString(R.string.headset_major),
                    getString(R.string.headset_header),
                    // msg5250: прыжок — направление даёт сама кнопка, поэтому в
                    // пункте стоит число ИМЕННО этой кнопки («вперёд» или «назад»).
                    MainActivity.sentencesPhrase(act, headsetJumpCount(key)).let {
                        getString(R.string.headset_sent_n, it)
                    },
                ),
                cur,
            ) { d, which ->
                prefs.edit().putString(key, values[which]).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Сколько предложений мотает кнопка гарнитуры [key] (msg5250): у «вперёд» —
     *  число из настройки «прыжок вперёд», у «назад» — из «прыжок назад», как и
     *  на экране чтения. */
    private fun headsetJumpCount(key: String): Int =
        MainActivity.jumpCount(prefs, forward = key == MainActivity.KEY_HS_NEXT)

    /** Название выбранного шага кнопки гарнитуры — для строки-резюме. */
    private fun headsetLabel(id: String?, key: String): String = when (id) {
        MainActivity.HS_SENTENCE -> getString(R.string.headset_sentence)
        MainActivity.HS_PARAGRAPH -> getString(R.string.headset_paragraph)
        MainActivity.HS_CHAPTER -> getString(R.string.headset_chapter)
        MainActivity.HS_MAJOR -> getString(R.string.headset_major)
        MainActivity.HS_HEADER -> getString(R.string.headset_header)
        // msg5250: у прыжка в строке видно, на сколько она мотает именно здесь.
        MainActivity.HS_SENT_N -> getString(
            R.string.headset_sent_n,
            MainActivity.sentencesPhrase(act, headsetJumpCount(key)),
        )
        else -> getString(R.string.headset_off)
    }

    /** Диалог выбора действия для одного свайпа (общая палитра жестов). Заголовок
     *  называет направление свайпа (msg2547): «Действие свайпа вправо»/«…влево». */
    private fun pickGesture(key: String, def: String, titleRes: Int) {
        pickAction(key, def, getString(titleRes))
    }

    /** Тот же список действий, но заголовок — готовая строка (msg5266): кнопкам
     *  читалки он собирается из места кнопки и вида нажатия, а не лежит
     *  отдельным ресурсом на каждую из восьми строк. */
    private fun pickAction(key: String, def: String, title: CharSequence) {
        // msg5234: название действия собирается общим помощником — у прыжка в нём
        // стоит число из настроек, поэтому список показываем в момент открытия.
        val labels = MainActivity.GESTURE_ACTIONS
            .map { MainActivity.gestureActionLabel(act, prefs, it.first) }
            .toTypedArray()
        val cur = prefs.getString(key, def) ?: def
        val idx = MainActivity.GESTURE_ACTIONS.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { d, which ->
                prefs.edit().putString(key, MainActivity.GESTURE_ACTIONS[which].first).apply()
                d.dismiss()
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Имя действия по его id из палитры жестов (для строк-резюме и подписи раздела). */
    private fun gestureLabel(id: String?): String =
        MainActivity.gestureActionLabel(act, prefs, id ?: "")


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
        // msg5220/5224: строк «Кнопки назад и далее шагают по» и «Кнопки глав
        // шагают» здесь больше нет — шаг стал самим действием кнопки, и
        // назначается он долгим нажатием на самой кнопке в читалке.
        // msg2695/2699: долгое нажатие кнопки «▶». Опция живёт здесь же, где остальные
        // назначаемые кнопки. Значение — MainActivity.PLAY_LONG_*; по умолчанию таймер сна.
        val playLongOptions = listOf(
            getString(R.string.play_long_sleep) to MainActivity.PLAY_LONG_SLEEP,
            getString(R.string.play_long_off) to MainActivity.PLAY_LONG_OFF,
        )
        playLongRow = addValueButton {
            val cur = prefs.getString(MainActivity.KEY_PLAY_LONG, MainActivity.PLAY_LONG_SLEEP)
                ?: MainActivity.PLAY_LONG_SLEEP
            val checked = playLongOptions.indexOfFirst { it.second == cur }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.play_long_dialog)
                .setSingleChoiceItems(playLongOptions.map { it.first }.toTypedArray(), checked) { d, which ->
                    prefs.edit().putString(MainActivity.KEY_PLAY_LONG, playLongOptions[which].second).apply()
                    d.dismiss()
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
        }
        // msg5685/5699: свайпы, действия четырёх кнопок читалки, кнопки гарнитуры
        // и прыжок уехали в подраздел «Кнопки и жесты» — всё это про одно: чем
        // человек управляет чтением, кроме экрана. Раньше свайпы и действия кнопок
        // лежали здесь вперемешку со стартовым экраном, действия — ещё и в «Экране
        // книги», а гарнитура — на третьем уровне. В списке «Управления» остаётся
        // строка-переход (двухуровневая навигация, как из корня в раздел).
        controlsGroupRow = addMenuRow(
            getString(R.string.controls_group_title),
            getString(R.string.controls_group_hint),
        ) { openControlsSub() }
    }

    /** msg5685/5699: открыть подраздел «Кнопки и жесты» внутри «Управления».
     *  Заголовок окна — имя подраздела, контент — четыре группы строк с общим
     *  заголовком-картой перед каждой: свайпы, действия кнопок читалки, кнопки
     *  гарнитуры, прыжок. Возврат «назад» ведёт в список «Управления»
     *  (onBackKey), а не в корень. */
    private fun openControlsSub() {
        controlsSubOpen = true
        screenTitle(getString(R.string.controls_group_title))
        content().removeAllViews()
        // 30.09.2026: подсказок в подразделе нет вовсе (Серж: «в разделе жесты и
        // кнопки убери строки с подсказками»). Убраны три отдельные остановки:
        // карта прыжков (`controls_jumps_hint`), объяснение про четыре кнопки
        // читалки (`reader_actions_hint`) и подсказка про прыжок (`jump_hint`).
        // Заголовки групп (свайпы, кнопки на экране, кнопки гарнитуры, прыжок)
        // остаются: по ним слышно, где что.
        addHeading(getString(R.string.controls_section_swipes))
        addGestureRows()
        // msg5266: у каждой из четырёх кнопок читалки два действия — короткое и
        // долгое нажатие; оба выбираются здесь из той же палитры, что у свайпов.
        // Список по долгому нажатию на самой кнопке убран.
        // msg5707: сюда же переехала галочка «видна» — три строки на кнопку.
        addHeading(getString(R.string.controls_section_buttons))
        addReaderButtonRows()
        // msg2527: кнопки гарнитуры жили отдельным подразделом — теперь стоят
        // строками здесь, третьего уровня в настройках не осталось.
        addHeading(getString(R.string.controls_section_headset))
        addHeadsetRows()
        // msg5234: прыжок — шаг сразу на несколько предложений, число выбирается
        // здесь (своё для «вперёд» и «назад»), а сам прыжок появляется пунктом в
        // общем списке действий — на кнопке читалки, на свайпе и на кнопке
        // гарнитуры. Подсказки под заголовком больше нет.
        addHeading(getString(R.string.controls_section_jump))
        jumpFwdRow = addValueButton { pickJumpSteps(MainActivity.KEY_JUMP_FWD, forward = true) }
        jumpBackRow = addValueButton { pickJumpSteps(MainActivity.KEY_JUMP_BACK, forward = false) }
        refreshRows()
        scrollTop()
        binding.tvTitle.announceForAccessibility(getString(R.string.controls_group_title))
    }

    private fun buildLibraryGroup() {
        // 30.09.2026: без «Доступа ко всем файлам» часть книг на полке просто не
        // открывается (Android прячет чужие файлы), и человек об этом не знает —
        // доступ просили только при записи. Показываем это первой строкой раздела,
        // пока доступа нет: касание открывает системный экран.
        if (!AllFiles.granted(act)) {
            addMenuRow(
                getString(R.string.all_files_missing_row),
                getString(R.string.all_files_missing_hint),
            ) { AllFiles.ask(act, getString(R.string.all_files_explain)) }
        }
        // Обложки карточек-сетки (23.09.2026): владелец просил и обложки, и
        // галочку, чтобы их выключать. Стоит первой в разделе — новое ищут
        // сверху. Работает только в виде «Сетка»; список остаётся строками,
        // а озвучка карточек от галочки не зависит вовсе. Применяется сразу
        // при возврате на полку: она перечитывает галочку в resume().
        addCheck(
            R.string.lib_covers_title,
            LibraryActivity.KEY_LIB_COVERS,
            LibraryActivity.LIB_COVERS_DEFAULT,
        )
        // 30.09.2026: подсказки у этой галочки больше нет — из названия всё
        // понятно (Серж: «некоторые пункты вообще очевидные»).
        // Вкладки Библиотеки (#97, переехали из «Интерфейса» msg5317): порядок и
        // видимость верхних фильтров «Читаю/Новые/Прочитанные/Все» — это про
        // полку, а не про экран книги, поэтому и живут в «Библиотеке». «Все»
        // скрыть нельзя; активную и последнюю видимую тоже.
        addHint(getString(R.string.lib_tabs_hint))
        tabRowButtons.clear()
        for (mode in LibraryActivity.tabsOrder(prefs)) {
            val row = addValueButton { tabActions(mode) }
            row.tag = mode
            tabRowButtons.add(mode to row)
        }
        resetTabsRow = addButton(getString(R.string.lib_tabs_reset)) { resetTabs() }

        folderRow = addValueButton {
            // 30.09.2026, просьба Сержа: без промежуточного окошка. Касание строки
            // сразу открывает системный выбор папки — и когда папки ещё нет, и когда
            // её меняют. Окошко с одной строкой «Сменить папку» и кнопкой «Закрыть»
            // вслепую только путало: строка списка читается как текст, а не как
            // кнопка, и казалось, что менять нечем.
            openFolderPicker()
        }
        // 30.09.2026: подсказки «Файлы не копируются.» здесь больше нет (Серж:
        // «убери из библиотеки эту подсказку»). Что она значила: книги остаются
        // лежать в выбранной папке, приложение их не копирует к себе — полка
        // просто показывает файлы по их адресу. Человеку это знать не обязательно,
        // а остановка в списке была лишней.
        // #44: куда скачивать книги из каталога.
        dlFolderRow = addValueButton {
            // Так же без окошка (см. folderRow выше).
            openDlFolderPicker()
        }
        // 30.09.2026: подсказки у этой строки больше нет — сказанное в ней и так
        // видно из названия и значения (Серж: «некоторые пункты вообще очевидные,
        // давай по минимуму уберём»).
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

        // msg4895: «Разобранный текст» — сколько места занял кэш разбора (BookCache)
        // и кнопка очистки. Системная «Очистить кэш» до него не дотягивается:
        // он лежит в filesDir, то есть в данных приложения, а не в кэше.
        cacheRow = addValueButton { showCacheDialog() }

        // Резервная копия и синхронизация — каждый своим подразделом (0.4.86,
        // msg7020). Раньше обе жили прямо здесь: четыре строки копий и девять
        // строк синхронизации подряд, семнадцать остановок, и слушать их каждый
        // раз, когда зашёл поправить одну галочку, было тяжело. Снаружи теперь
        // по одной строке со статичной подсказкой; состояние — внутри, там же,
        // где сами настройки.
        backupGroupRow = addMenuRow(
            getString(R.string.backup_group_title),
            getString(R.string.backup_group_hint),
        ) { openBackupSub() }
        syncGroupRow = addMenuRow(
            getString(R.string.sync_title),
            getString(R.string.sync_group_hint),
        ) { openSyncSub() }
    }

    /** Подраздел «Что показывать в книге» (0.4.86, msg7026) — все галочки
     *  видимости в одном месте: десять панелей и кнопок из списка плюс четыре
     *  кнопки-стрелки. Раньше четыре из них жили в «Кнопках и жестах» у своих
     *  кнопок: связь с действиями там была, а одного места для «что видно» не
     *  было вовсе. Теперь наоборот: видно — здесь, что делает — там, а связь
     *  держит пометка «скрыта» в строках действий (см. [actionRowText]).
     *  30.09.2026: заголовки «Панели и кнопки» и «Четыре кнопки внизу» убраны
     *  (Серж: «убрать оба заголовка, как в „Чтении“»); вдобавок второй врал — из
     *  четырёх кнопок две сверху. */
    private fun openShowSub() {
        showSubOpen = true
        screenTitle(getString(R.string.show_group_title))
        content().removeAllViews()
        addHint(getString(R.string.reader_group_hint))
        readerUi.forEach { (res, key) -> addCheck(res, key, true) }
        // Четыре кнопки читалки: своё имя у каждой — «нижняя левая кнопка: видна».
        for (b in MainActivity.READER_BUTTONS) {
            addCheckText(
                getString(R.string.btn_visible_title, getString(b.posRes)),
                b.uiKey,
                true,
            )
        }
        scrollTop()
        binding.tvTitle.announceForAccessibility(getString(R.string.show_group_title))
    }

    /** Сколько элементов экрана книги спрятано (0.4.86, msg7026). Число говорит
     *  строка подраздела: по ней слышно, что что-то убрано, не заходя внутрь. */
    private fun hiddenReaderUiCount(): Int {
        var n = 0
        for ((_, key) in readerUi) if (!prefs.getBoolean(key, true)) n++
        for (b in MainActivity.READER_BUTTONS) if (!prefs.getBoolean(b.uiKey, true)) n++
        return n
    }

    /** Подраздел «Резервные копии» (0.4.86, msg7020). Внутри — ровно то, что
     *  было в «Библиотеке», плюс [forgetSubRows] следит, чтобы ссылки на строки
     *  не пережили этот подраздел. Порядок по делу: сначала два действия (ради
     *  них сюда и заходят), потом расписание и папка. */
    private fun openBackupSub() {
        backupSubOpen = true
        screenTitle(getString(R.string.backup_group_title))
        content().removeAllViews()
        // Чем копия отличается от синхронизации — говорим прямо: обе умеют
        // «отправить файл», и без этого человек путает два разных файла.
        addHint(getString(R.string.backup_diff_hint))
        // 30.09.2026: окна «Что включить в копию» больше нет — копия всегда
        // полная (настройки и книги). Копия для восстановления нужна целиком:
        // одни настройки без книг ничего не вернут, а окно с двумя галочками
        // стоило человеку лишнего шага при каждом создании.
        addButton(getString(R.string.backup_create)) {
            runBackupCreate(includeSettings = true, includeBooks = true)
        }
        addButton(getString(R.string.backup_restore)) {
            openBackupFilePicker()
        }
        backupAutoRow = addValueButton { pickBackupAuto() }
        // Папка нужна только расписанию: копию можно создать кнопкой и отправить
        // в Telegram. Показываем её строкой ниже расписания и только когда оно
        // включено (см. refreshRows).
        backupDirRow = addValueButton { pickBackupDirAction() }
        refreshRows()
        scrollTop()
        binding.tvTitle.announceForAccessibility(getString(R.string.backup_group_title))
    }

    /** Подраздел «Синхронизация» (0.4.86, msg7020). Порядок — по частоте: сперва
     *  включение и выбор места, потом прогон вручную, потом книги, в конце —
     *  ручной обмен файлом и след последнего прогона.
     *
     *  28.09.2026: место синхронизации выбирает владелец, а не приложение. Раньше
     *  здесь стояли две строки входа (Яндекс и Google) и отдельная строка папки, и
     *  человек читал подсказки про Яндекс, когда файл уезжал в Google. */
    private fun openSyncSub() {
        syncSubOpen = true
        screenTitle(getString(R.string.sync_title))
        content().removeAllViews()
        // 28.09.2026, просьба Сержа: подраздел сжат — длинной подсказки сверху нет,
        // строки говорят сами за себя. Выключатель стоит первым и вплотную к строке,
        // где выбрано место: «включить» и «через кого» — два первых решения, и они
        // рядом.
        // 0.4.86: галочка называлась так же, как заголовок окна, — диктор читал
        // «Синхронизация» дважды подряд. Теперь она про действие.
        addCheck(R.string.sync_enable_title, SyncStore.KEY_ON, false) { refreshRows() }
        syncPlaceRow = addValueButton { syncPlaceAction() }
        syncAccountRow = addValueButton { syncAccountAction() }
        // Строка состояния показывается ТОЛЬКО когда что-то не так (место не
        // выбрано или вход потерян): когда всё в порядке, она повторяла бы строки
        // выше — а именно этого Серж и не хотел.
        syncStateRow = addValueText().apply { visibility = android.view.View.GONE }
        addButton(getString(R.string.sync_now)) { runSyncNow() }
        booksBox = addCheck(R.string.sync_books_title, SyncStore.KEY_BOOKS, false) { on ->
            onBooksToggle(on)
        }
        booksRow = addValueButton { pullBooksAction() }
        // Вручную файлом — одной строкой вместо двух кнопок и подсказки: выбор из
        // «отправить» и «забрать» открывается нажатием.
        addMenuRow(getString(R.string.sync_manual_row), getString(R.string.sync_manual_row_hint)) {
            syncManualAction()
        }
        syncLastRow = addValueText()
        refreshRows()
        scrollTop()
        binding.tvTitle.announceForAccessibility(getString(R.string.sync_title))
    }

    /** Обмен файлом вручную — для тех, у кого облака нет. */
    private fun syncManualAction() {
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.sync_manual_row)
            .setItems(arrayOf(getString(R.string.sync_send), getString(R.string.sync_get))) { _, which ->
                if (which == 0) sendSyncFile() else openSyncFilePicker()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Забыть строки подразделов (0.4.86, msg7020): пока «Копии» или
     *  «Синхронизация» закрыты, ссылки на их строки мертвы, а [refreshRows]
     *  зовётся и из других мест — писать в мёртвые вьюхи нельзя. */
    private fun forgetSubRows() {
        backupDirRow = null
        backupAutoRow = null
        syncPlaceRow = null
        syncAccountRow = null
        syncStateRow = null
        syncLastRow = null
        booksRow = null
        booksBox = null
        // 0.4.86 (msg7026): галочки «что показывать» живут в подразделе — вне его
        // ссылки на них мертвы. Списка галочек для «простого экрана» больше нет:
        // сами строки «простой экран» убраны 30.09.2026.
    }

    /** Строка состояния без действия (msg6046…6078): «Последняя синхронизация: …».
     *  Кнопкой её делать нельзя — нажать нечего, а TalkBack добавил бы «кнопка». */
    private fun addValueText(): TextView {
        val tv = TextView(act).apply {
            textSize = 17f
            setTextColor(Palette.INK)
            setLineSpacing(0f, 1.1f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content().addView(tv)
        return tv
    }

    /** msg4895: сколько занял разобранный текст и его очистка. */
    private fun showCacheDialog() {
        MaterialAlertDialogBuilder(act)
            .setTitle(cacheText())
            .setMessage(getString(R.string.cache_clear_hint, BookCache.count(act)))
            .setPositiveButton(R.string.cache_clear) { _, _ ->
                BookCache.clearAll(act)
                toast(getString(R.string.cache_cleared))
                refreshRows()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun sizeText(bytes: Long): String {
        val mb = bytes / (1024L * 1024L)
        return if (mb >= 1) getString(R.string.cache_mb, mb) else getString(R.string.cache_less_mb)
    }

    /** msg5065: «Кэш: всего 300 МБ, занято 1 МБ». Одна формулировка на строку и
     *  на заголовок окна очистки — иначе они разъедутся при первой же правке слов. */
    private fun cacheText(): String = getString(
        R.string.cache_row,
        getString(R.string.cache_title),
        sizeText(BookCache.budgetBytes()),
        sizeText(BookCache.usedBytes(act)),
    )

    private fun buildDiagGroup() {
        // Автопроверку обновлений держим флажком «включено / не включено»: у
        // незрячего одна галочка понятнее двух слов «Автоматически / Вручную»,
        // которые ещё надо различать на слух. Кнопка ручной проверки ниже
        // работает всегда, независимо от флажка.
        addCheck(R.string.update_auto_check, UpdateFlow.KEY_AUTO, UpdateFlow.isAuto(act))
        addButton(getString(R.string.update_check)) { UpdateFlow.manual(act) }
        addButton(getString(R.string.diag_send)) { sendDiagLog() }
        addButton(getString(R.string.diag_clear)) {
            Diag.clear(act)
            toast(getString(R.string.log_cleared))
        }
        // msg3907: «О программе» отсюда убрано — теперь это строка корня
        // настроек (showMenu). msg3903: отдельный пункт «Связаться с
        // разработчиком» тоже убран — вход в группу один, кнопкой внутри
        // «О программе», ссылка живёт в AuthorContact.
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

    // ---------------- Работа в фоне (msg4476, окно «Не засыпать» — msg4709) ----------------

    /** Подсказка строки корня: состояние на момент открытия настроек. Спрашиваем
     *  систему каждый раз — состояние меняется в её окне, а не у нас.
     *
     *  Разрешение батареи и системные окна живут в [SleepGuard]: строка корня и
     *  окно «Не засыпать» должны спрашивать одно и то же одним кодом. */
    private fun bgHint(): String = getString(
        if (SleepGuard.ignoringBattery(act)) R.string.settings_bg_hint_on
        else R.string.settings_bg_hint_off
    )

    /** Подсказка-пояснение в начале раздела. Обычный текст без роли кнопки:
     *  TalkBack читает его целиком, когда фокус встаёт на первый элемент. */
    private fun addHint(text: String) {
        content().addView(TextView(act).apply {
            this.text = text
            textSize = 15f
            setTextColor(Palette.DIM)
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(2), 0, dp(10))
        })
    }

    /** Заголовок группы строк внутри длинного раздела (msg5685). От подсказки
     *  отличается на слух и на вид: подсказка серая и мелкая, заголовок — крупнее,
     *  светлее и жирный. Роль кнопки не даём (это не действие), поэтому TalkBack
     *  читает его как текст и он служит картой: слышно, где кончилась одна группа
     *  строк и началась другая.
     *
     *  0.4.83 (msg7007): помечаем настоящим заголовком для диктора. Тогда
     *  TalkBack умеет перескакивать по заголовкам, и раздел «Чтение» с шестью
     *  группами читается прыжками, а не свайпами через все строки. Фокусируемым
     *  его делаем нарочно (как заголовки в разметке): без этого остановки на нём
     *  при обычном обходе не было. */
    private fun addHeading(text: String) {
        val tv = TextView(act).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.INK)
            setPadding(0, dp(12), 0, dp(4))
            isFocusable = true
            ViewCompat.setScreenReaderFocusable(this, true)
        }
        ViewCompat.setAccessibilityHeading(tv, true)
        content().addView(tv)
    }

    /** Галочка с готовым текстом — нужна конструктору читалки (msg5220): строка
     *  называется действием кнопки, а оно меняется долгим нажатием в читалке,
     *  поэтому текст приходит строкой и обновляется в [refreshRows]. */
    private fun addCheckText(text: CharSequence, key: String, def: Boolean): SwitchCompat {
        val cb = SwitchCompat(act).apply {
            this.text = text
            textSize = 17f
            isChecked = prefs.getBoolean(key, def)
            isClickable = true
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        content().addView(cb)
        return cb
    }

    /** [onChange] — для галочек, которые надо не только запомнить, но и применить
     *  сразу: тихий поток живёт в движке, а не в prefs (msg5067). Параметр
     *  последний, поэтому трейлинг-лямбда попадает в него, а не в [def]. */
    private fun addCheck(
        titleRes: Int,
        key: String,
        def: Boolean,
        hintRes: Int = 0,
        onChange: ((Boolean) -> Unit)? = null,
    ): SwitchCompat {
        val box = SwitchCompat(act).apply {
            // Подсказка идёт второй строкой того же пункта (как у строк-разделов,
            // см. addMenuRow): диктор читает её там же, где название, и лишней
            // остановки в списке не появляется (29.09.2026, «Названия строк»).
            text = if (hintRes == 0) getString(titleRes) else SpannableStringBuilder().apply {
                append(getString(titleRes))
                append("\n")
                val hintStart = length
                append(getString(hintRes))
                setSpan(RelativeSizeSpan(0.76f), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Palette.DIM), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 17f
            isChecked = prefs.getBoolean(key, def)
            isClickable = true
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange?.invoke(checked)
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        content().addView(box)
        return box
    }

    /** В журнал — и что выбрано, и что вышло на самом деле: если чтение сейчас не
     *  идёт, поток остаётся выключенным до его начала, и это видно из строки.
     *  (Переехало из окна «Не засыпать» вместе с галочкой, msg5067.) */
    private fun logSilence(on: Boolean) {
        Diag.log(
            act, "power",
            "«Чтение»: тихий поток ${if (on) "включён" else "выключен"} галочкой; " +
                "сейчас ${if (SilentKeepAlive.isOn) "идёт" else "не идёт"} " +
                "(не идёт — значит чтение стоит)"
        )
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
            ripple(this)
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
        addMenuRow(getString(g.titleRes), getString(g.hintRes)) { openGroup(g) }
    }

    /** Та же строка, но для пункта, у которого нет раздела-`Group` (msg3907):
     *  «О программе» открывает отдельное окно, а не список настроек, но в корне
     *  должно выглядеть как остальные строки — с подсказкой.
     *
     *  [onClick] получает саму строку (msg5730): действие, меняющее соседние
     *  галочки, проговаривает результат на своей строке — она не пересобирается
     *  и фокус с неё не сходит. Кому строка не нужна, просто не берет параметр. */
    private fun addMenuRow(title: String, hint: String, onClick: (Button) -> Unit): Button {
        val b = Button(act).apply {
            text = SpannableStringBuilder().apply {
                append(title)
                append("\n")
                val hintStart = length
                append(hint)
                setSpan(RelativeSizeSpan(0.76f), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Palette.DIM), hintStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 17f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick(this) }
            ripple(this)
        }
        asPlainText(b)
        content().addView(b, lp().apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        return b
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

    /** Рябь при касании на строке-кнопке (единый вид). */
    private fun ripple(v: View) {
        val tv = android.util.TypedValue()
        if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId)
        }
    }

    /** Значение настройки второй строкой: название обычным, значение мельче и серым. */
    private fun valueLine(title: String, value: String): CharSequence =
        SpannableStringBuilder().apply {
            append(title)
            append("\n")
            val start = length
            append(value)
            setSpan(RelativeSizeSpan(0.76f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Palette.DIM), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** Обновить тексты строк-резюме (стартовый экран, шаг, папка, сортировка). */
    private fun refreshRows() {
        // msg5730: резюме строки размера текста — выбранное значение. Строки
        // может и не быть в этом разделе (тогда ссылка с прошлой сборки мертва,
        // это безвредно).
        textScaleRow?.text = valueLine(
            getString(R.string.text_scale_title),
            getString(TextScale.labelRes(TextScale.value(act)))
        )

        val startLast = prefs.getString(MainActivity.KEY_START, MainActivity.START_LAST) == MainActivity.START_LAST
        startRow?.text = valueLine(
            getString(R.string.start_screen_title),
            if (startLast) getString(R.string.start_screen_last) else getString(R.string.start_screen_library)
        )

        val exitLibrary = prefs.getString(MainActivity.KEY_EXIT, MainActivity.EXIT_LIBRARY) == MainActivity.EXIT_LIBRARY
        exitRow?.text = valueLine(
            getString(R.string.exit_title),
            if (exitLibrary) getString(R.string.exit_library) else getString(R.string.exit_desktop)
        )

        playLongRow?.text = valueLine(
            getString(R.string.play_long_title),
            getString(
                if (prefs.getString(MainActivity.KEY_PLAY_LONG, MainActivity.PLAY_LONG_SLEEP) == MainActivity.PLAY_LONG_OFF)
                    R.string.play_long_off else R.string.play_long_sleep
            )
        )

        val tree = treeUri()
        folderRow?.text = valueLine(
            getString(R.string.folder_title),
            if (tree == null) getString(R.string.folder_none) else folderLabel(Uri.parse(tree))
        )

        val dlTree = dlTreeUri()
        dlFolderRow?.text = valueLine(
            getString(R.string.dl_folder_title),
            if (dlTree == null) getString(R.string.dl_folder_none) else folderLabel(Uri.parse(dlTree))
        )

        dlFormatRow?.text = valueLine(
            getString(R.string.dl_format_title),
            OPDS_READABLE_FORMATS.firstOrNull { it.first == dlFormatKey() }?.second ?: "FB2"
        )

        gestureRightRow?.text = valueLine(
            getString(R.string.gesture_right_title),
            gestureLabel(prefs.getString(MainActivity.KEY_GESTURE_RIGHT, MainActivity.G_NEXT_HEADER))
        )
        gestureLeftRow?.text = valueLine(
            getString(R.string.gesture_left_title),
            gestureLabel(prefs.getString(MainActivity.KEY_GESTURE_LEFT, MainActivity.G_PREV_HEADER))
        )
        // msg5266: восемь строк действий кнопок читалки — значение следует выбору.
        for (r in buttonActionRows) r.button.text = actionRowText(r)
        // msg5234: прыжок — числа показываем словами («15 предложений»).
        jumpFwdRow?.text = jumpRowText(R.string.jump_fwd_title, MainActivity.KEY_JUMP_FWD)
        jumpBackRow?.text = jumpRowText(R.string.jump_back_title, MainActivity.KEY_JUMP_BACK)

        headsetPrevRow?.text = valueLine(
            getString(R.string.headset_prev_title),
            headsetLabel(
                prefs.getString(MainActivity.KEY_HS_PREV, MainActivity.HS_SENTENCE),
                MainActivity.KEY_HS_PREV,
            )
        )
        headsetNextRow?.text = valueLine(
            getString(R.string.headset_next_title),
            headsetLabel(
                prefs.getString(MainActivity.KEY_HS_NEXT, MainActivity.HS_SENTENCE),
                MainActivity.KEY_HS_NEXT,
            )
        )
        // #75: движок, язык и голос — строки общего набора (тот же код, что в
        // панели читалки). Раздела «Голос» на экране нет — набору нечего
        // перерисовывать, но вызов дешёвый и без ветвлений.
        voicePicker?.refresh()
        afterCallRow?.text = valueLine(getString(R.string.after_call_title), afterCallLabel())
        startRewindRow?.text = valueLine(getString(R.string.start_rewind_title), startRewindLabel())
        // 30.09.2026: строка-выбор раздела «Чтение». Галочки «останавливать
        // чтение» и «долистал сам» состояние держат сами — им резюме не нужно.
        jumpPlayRow?.text = valueLine(getString(R.string.jump_play_title), jumpPlayLabel())
        // msg5604: «Пауза между предложениями: <значение>».
        pauseKeepRow?.text = valueLine(getString(R.string.pause_keep_title), pauseKeepLabel())
        // 30.09.2026: «Уровень этого звука: <значение>».
        silentLevelRow?.text = valueLine(getString(R.string.silent_level_title), silentLevelLabel())
        readerBarsRow?.text = valueLine(getString(R.string.reader_bars_title), readerBarsLabel())

        val hiddenTabs = LibraryActivity.tabsHidden(prefs)
        for ((mode, b) in tabRowButtons) {
            b.text = valueLine(
                getString(LibraryActivity.tabLabelRes(mode)),
                getString(if (mode in hiddenTabs) R.string.lib_tabs_hidden else R.string.lib_tabs_visible)
            )
        }

        // 0.4.86 (msg7026): строка «Что показывать в книге» — состояние числом:
        // по ней слышно, что часть элементов убрана, не заходя внутрь.
        val hiddenUi = hiddenReaderUiCount()
        showGroupRow?.text = valueLine(
            getString(R.string.show_group_title),
            if (hiddenUi == 0) getString(R.string.show_group_all)
            else getString(R.string.show_group_hidden, hiddenUi)
        )

        val dir = BackupStore.dirUri(act)
        backupDirRow?.text = valueLine(
            getString(R.string.backup_dir_title),
            if (dir == null) getString(R.string.backup_dir_none) else folderLabel(dir)
        )
        backupAutoRow?.text = backupAutoText()
        // 0.4.86 (msg7020): папка нужна только расписанию — копию можно создать
        // кнопкой и отправить в Telegram. Выключено расписание — строку прячем.
        backupDirRow?.visibility =
            if (backupAutoOff()) android.view.View.GONE else android.view.View.VISIBLE
        // msg6046…6078: место синхронизации и след последнего прогона.
        // 28.09.2026: строки про Яндекс и Google больше не висят обе — показываем
        // выбранное место, а в состоянии честно называем, куда идёт файл.
        syncPlaceRow?.text = syncPlaceLabel()
        syncAccountRow?.text = syncAccountLabel()
        // Состояние говорим только когда есть о чём: место не выбрано или вход
        // потерян. Иначе строки выше уже назвали и место, и вход.
        syncStateRow?.let { row ->
            val trouble = SyncStore.trouble(act)
            if (trouble == null) {
                row.visibility = android.view.View.GONE
            } else {
                row.visibility = android.view.View.VISIBLE
                row.text = trouble
            }
        }
        booksRow?.text = booksLabel()
        syncLastRow?.text = valueLine(
            getString(R.string.sync_last_title),
            SyncStore.lastResult(act) ?: getString(R.string.sync_last_never)
        )

        cacheRow?.text = cacheText()

    }

    // ---------------- «После звонка» (#98) и пауза при наушниках (#99) ----------------

    private fun afterCallMode(): String =
        prefs.getString(MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_STOP)
            ?: MainActivity.AFTER_CALL_STOP

    /** Значение строки «После звонка»: либо «Остановиться», либо «Продолжить…» —
     *  с какого места. Общая на оба прежних случая (30.09.2026). */
    private fun afterCallLabel(): String {
        if (afterCallMode() != MainActivity.AFTER_CALL_CONTINUE) {
            return getString(R.string.after_call_stop)
        }
        return getString(when (
            prefs.getString(MainActivity.KEY_AFTER_CALL_REWIND, MainActivity.AFTER_CALL_REWIND_5)
                ?: MainActivity.AFTER_CALL_REWIND_5
        ) {
            MainActivity.AFTER_CALL_REWIND_NONE -> R.string.after_call_go_same
            MainActivity.AFTER_CALL_REWIND_2 -> R.string.after_call_go_2
            MainActivity.AFTER_CALL_REWIND_10 -> R.string.after_call_go_10
            else -> R.string.after_call_go_5
        })
    }

    private fun hasPhonePerm(): Boolean =
        checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Выбор «После звонка» (30.09.2026): один список — остановиться или
     *  продолжить, и если продолжить, то с какого места. Прежде это были две
     *  строки: режим и отдельно откат, — и человек читал про откат даже тогда,
     *  когда стояло «Остановиться» (Серж: «что если всё это в одной строчке
     *  повыбирать»). Любой вариант с «Продолжить» требует разрешения «Телефон»
     *  (READ_PHONE_STATE) — просим его здесь, один раз. */
    private fun pickAfterCallMode() {
        val rewinds = arrayOf(
            MainActivity.AFTER_CALL_REWIND_NONE,
            MainActivity.AFTER_CALL_REWIND_2,
            MainActivity.AFTER_CALL_REWIND_5,
            MainActivity.AFTER_CALL_REWIND_10,
        )
        val cur = if (afterCallMode() != MainActivity.AFTER_CALL_CONTINUE) {
            0
        } else {
            val saved = prefs.getString(
                MainActivity.KEY_AFTER_CALL_REWIND, MainActivity.AFTER_CALL_REWIND_5
            )
            (rewinds.indexOf(saved).takeIf { it >= 0 } ?: 2) + 1
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.after_call_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.after_call_stop),
                    getString(R.string.after_call_go_same),
                    getString(R.string.after_call_go_2),
                    getString(R.string.after_call_go_5),
                    getString(R.string.after_call_go_10),
                ),
                cur,
            ) { d, which ->
                if (which > 0 && !hasPhonePerm()) {
                    pendingCallRewind = rewinds[which - 1]
                    d.dismiss()
                    requestPhonePerm()
                    return@setSingleChoiceItems
                }
                val e = prefs.edit()
                if (which == 0) {
                    e.putString(MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_STOP)
                } else {
                    e.putString(MainActivity.KEY_AFTER_CALL, MainActivity.AFTER_CALL_CONTINUE)
                    e.putString(MainActivity.KEY_AFTER_CALL_REWIND, rewinds[which - 1])
                }
                e.apply()
                Diag.log(act, "reading", "«Чтение»: после звонка — ${afterCallLabel()}")
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    // ---------------- «Отступать назад при старте» (msg2093) ----------------

    /** Выбор отката при старте: выключено / 2 / 5 предложений. */
    private fun pickStartRewind() {
        val values = arrayOf(
            MainActivity.START_REWIND_NONE,
            MainActivity.START_REWIND_2,
            MainActivity.START_REWIND_5,
        )
        val cur = values.indexOf(
            prefs.getString(MainActivity.KEY_START_REWIND, MainActivity.START_REWIND_NONE)
        ).coerceAtLeast(0)
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.start_rewind_dialog)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.start_rewind_none),
                    getString(R.string.start_rewind_2),
                    getString(R.string.start_rewind_5),
                ),
                cur,
            ) { d, which ->
                prefs.edit().putString(MainActivity.KEY_START_REWIND, values[which]).apply()
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Название выбранного отката при старте — для строки-резюме. */
    private fun startRewindLabel(): String = getString(when (
        prefs.getString(MainActivity.KEY_START_REWIND, MainActivity.START_REWIND_NONE)
            ?: MainActivity.START_REWIND_NONE
    ) {
        MainActivity.START_REWIND_NONE -> R.string.start_rewind_none
        MainActivity.START_REWIND_2 -> R.string.start_rewind_2
        else -> R.string.start_rewind_5
    })

    /** Значения «Паузы между фразами» (msg5604): мс тишины, которые оставляем
     *  на стыке. 130 — как было до этой строки (50 мс в голове + 83 мс в
     *  хвосте), 0 — срезать всё, что движок дописал по краям фразы. */
    private val pauseKeepValues = intArrayOf(0, 130, 300, 600)

    private fun pickPauseKeep() {
        val cur = prefs.getInt(MainActivity.KEY_PAUSE_KEEP, MainActivity.PAUSE_KEEP_DEFAULT)
            .let { v -> pauseKeepValues.indexOf(v).takeIf { it >= 0 } ?: 1 }
        val labels = arrayOf(
            getString(R.string.pause_keep_0),
            getString(R.string.pause_keep_130),
            getString(R.string.pause_keep_300),
            getString(R.string.pause_keep_600),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.pause_keep_title)
            .setSingleChoiceItems(labels, cur) { d, which ->
                val keep = pauseKeepValues[which]
                prefs.edit().putInt(MainActivity.KEY_PAUSE_KEEP, keep).apply()
                // Живой плеер открытой книги (как галочка «Бесшовная»): значение
                // работает для фраз, которые ещё не синтезированы.
                MainActivity.active?.player?.pauseKeepMs = keep
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Название выбранной паузы — для строки-резюме. */
    private fun pauseKeepLabel(): String = getString(
        when (prefs.getInt(MainActivity.KEY_PAUSE_KEEP, MainActivity.PAUSE_KEEP_DEFAULT)) {
            0 -> R.string.pause_keep_0
            300 -> R.string.pause_keep_300
            600 -> R.string.pause_keep_600
            else -> R.string.pause_keep_130
        }
    )

    // ---------------- Тихий поток: громкость (30.09.2026) ----------------

    /** Порядок уровней тихого потока: 1 — младший разряд (ухо не слышит),
     *  дальше громче (см. SilentKeepAlive.LEVELS). */
    private val silentLevels = intArrayOf(1, 2, 3, 4, 5)

    private fun pickSilentLevel() {
        val cur = prefs.getInt(
            MainActivity.KEY_SILENT_LEVEL, MainActivity.SILENT_LEVEL_DEFAULT
        ).let { v -> silentLevels.indexOf(v).takeIf { it >= 0 } ?: 0 }
        val labels = arrayOf(
            getString(R.string.silent_level_1),
            getString(R.string.silent_level_2),
            getString(R.string.silent_level_3),
            getString(R.string.silent_level_4),
            getString(R.string.silent_level_5),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.silent_level_title)
            .setSingleChoiceItems(labels, cur) { d, which ->
                val lvl = silentLevels[which]
                prefs.edit().putInt(MainActivity.KEY_SILENT_LEVEL, lvl).apply()
                // Поток уже идёт — пересобираем его с новой амплитудой на ходу:
                // человек слушает гарнитуру и сразу слышит, помогло ли.
                SilentKeepAlive.levelChanged(act)
                d.dismiss()
                rebuildCurrentGroup()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Название выбранного уровня — для строки-резюме. */
    private fun silentLevelLabel(): String = getString(
        when (prefs.getInt(MainActivity.KEY_SILENT_LEVEL, MainActivity.SILENT_LEVEL_DEFAULT)) {
            2 -> R.string.silent_level_2
            3 -> R.string.silent_level_3
            4 -> R.string.silent_level_4
            5 -> R.string.silent_level_5
            else -> R.string.silent_level_1
        }
    )

    // ---------------- «Системные кнопки» (msg5931) ----------------
    // Та же механика: касание переводит на следующее состояние по кругу, текст
    // правится НА МЕСТЕ (msg5005). Дополнительно проговариваем подсказку о том,
    // как панель вернуть: для скрытых состояний это не мелочь, а единственный
    // способ узнать про смахивание от нижнего края, если человек его не знает.

    /** Порядок перебора: показывать → во время чтения → пока открыта книга. */
    private val readerBarsValues = intArrayOf(
        MainActivity.BARS_SHOW,
        MainActivity.BARS_HIDE_READING,
        MainActivity.BARS_HIDE_BOOK,
    )

    private fun readerBarsMode(): Int = prefs.getInt(
        MainActivity.KEY_READER_BARS, MainActivity.BARS_SHOW
    ).takeIf { it in readerBarsValues } ?: MainActivity.BARS_SHOW

    private fun readerBarsLabel(): String = getString(
        when (readerBarsMode()) {
            MainActivity.BARS_HIDE_READING -> R.string.reader_bars_reading
            MainActivity.BARS_HIDE_BOOK -> R.string.reader_bars_book
            else -> R.string.reader_bars_show
        }
    )

    /** Подсказка о возврате панели — своя на каждое скрытое состояние (для
     *  «показывать» её нет: возвращать нечего). */
    private fun readerBarsHint(): String? = when (readerBarsMode()) {
        MainActivity.BARS_HIDE_READING -> getString(R.string.reader_bars_hint_reading)
        MainActivity.BARS_HIDE_BOOK -> getString(R.string.reader_bars_hint_book)
        else -> null
    }

    private fun cycleReaderBars() {
        val next = readerBarsValues[
            (readerBarsValues.indexOf(readerBarsMode()) + 1) % readerBarsValues.size
        ]
        prefs.edit().putInt(MainActivity.KEY_READER_BARS, next).apply()
        // Применяем сразу: выбрали на ходу — панель уходит или возвращается, не
        // дожидаясь следующего старта чтения.
        ReaderBars.sync()
        Diag.log(act, "ui", "системные кнопки: ${readerBarsLabel()}")
        val row = readerBarsRow ?: return
        val text: CharSequence = valueLine(getString(R.string.reader_bars_title), readerBarsLabel())
        row.text = text
        row.announceForAccessibility(
            listOfNotNull(text, readerBarsHint()).joinToString(". ")
        )
    }

    // 30.09.2026: отдельного окна «На сколько отмотать после звонка» больше нет —
    // откат выбирается в общем списке строки «После звонка» (pickAfterCallMode).

    // ---------------- Вкладки Библиотеки (#97) ----------------

    /** Диалог одной вкладки: показать/скрыть, передвинуть выше/ниже.
     *
     *  30.09.2026 (вопрос Сержа: «есть опция убрать вкладку „Все“ — её убрать
     *  нельзя, тогда для чего там кнопка убрать?»): пункт «Скрыть» показываем
     *  ТОЛЬКО когда скрыть и правда можно. Прежде он был в меню всегда, а после
     *  нажатия отвечал отказом — «вкладку „Все“ скрыть нельзя», «эта вкладка
     *  сейчас открыта», «нельзя скрыть последнюю видимую». Предлагать то, чего
     *  сделать нельзя, — хуже, чем не предлагать: человек тратит касание и
     *  слышит отказ. Правила остались те же, просто теперь о них не узнаёшь
     *  нажатием:
     *   - «Все» не скрывается никогда (её нет в списке скрываемых);
     *   - открытую сейчас вкладку прячем только после перехода на другую;
     *   - последнюю видимую (кроме «Все») тоже не прячем — иначе полка
     *     осталась бы без единого фильтра. */
    private fun tabActions(mode: Int) {
        val items = ArrayList<String>()
        val acts = ArrayList<Int>() // 0 — выше, 1 — ниже, 2 — показать/скрыть
        val order = LibraryActivity.tabsOrder(prefs)
        val hiddenSet = LibraryActivity.tabsHidden(prefs)
        val hidden = mode in hiddenSet
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
            val visibleNonAll = order.count { it !in hiddenSet && it != LibraryActivity.FILTER_ALL }
            val canHide = mode != LibraryActivity.FILTER_ALL &&
                mode != LibraryActivity.activeFilterMode &&
                visibleNonAll > 1
            if (canHide) {
                items.add(getString(R.string.lib_tabs_hide))
                acts.add(2)
            }
        }
        // Менять нечего (например, в порядке всего одна вкладка): молчание хуже
        // короткой фразы — человек нажал и ждёт ответа.
        if (items.isEmpty()) {
            toast(getString(R.string.lib_tabs_nothing))
            return
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
    // 30.09.2026: окно «Что включить в копию» убрано — createBackupDialog()
    // заменён прямым вызовом runBackupCreate(true, true) в openBackupSub().

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

    /** Выбор папки автокопий (SAF-tree, один раз) / смена. Без промежуточного
     *  окошка: касание строки сразу открывает системный выбор (30.09.2026). */
    private fun pickBackupDirAction() {
        openBackupDirPicker()
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

    // ---------------- Синхронизация (msg6046…6114) ----------------

    /** Строка «Куда синхронизировать»: выбранное место словами. */
    private fun syncPlaceLabel(): String {
        val where = when (SyncStore.kind(act)) {
            SyncStore.CLOUD_GOOGLE -> getString(R.string.google_title)
            SyncStore.CLOUD_YANDEX -> getString(R.string.yandex_title)
            else -> getString(R.string.sync_place_folder)
        }
        return getString(R.string.sync_place_title) + ": " + where
    }

    /** Строка выбранного места: вход, проверка доступа или смена папки. */
    private fun syncAccountLabel(): String = when (SyncStore.kind(act)) {
        SyncStore.CLOUD_GOOGLE -> googleLabel()
        SyncStore.CLOUD_YANDEX -> yandexLabel()
        else -> getString(R.string.sync_dir_title) + ": " + syncDirLabel()
    }

    /** Выбор места синхронизации: Google, Яндекс или папка. Одного, не всех сразу:
     *  два облака разом — это две разные правды о месте чтения на одном телефоне. */
    private fun syncPlaceAction() {
        val now = SyncStore.kind(act)
        val mark = getString(R.string.sync_place_mark)
        val items = arrayOf(
            getString(R.string.google_title) + if (now == SyncStore.CLOUD_GOOGLE) mark else "",
            getString(R.string.yandex_title) + if (now == SyncStore.CLOUD_YANDEX) mark else "",
            getString(R.string.sync_place_folder) + if (now == SyncStore.CLOUD_FOLDER) mark else "",
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.sync_place_title)
            .setItems(items) { _, which ->
                val kind = when (which) {
                    0 -> SyncStore.CLOUD_GOOGLE
                    1 -> SyncStore.CLOUD_YANDEX
                    else -> SyncStore.CLOUD_FOLDER
                }
                SyncStore.setChosen(act, kind)
                Diag.log(act, "sync", "место синхронизации выбрано: $kind")
                refreshRows()
                // Выбрал облако, а входа нет — сразу предлагаем войти: иначе
                // человек нажмёт «Синхронизировать сейчас» и получит отказ.
                when {
                    kind == SyncStore.CLOUD_GOOGLE && !GoogleDrive.connected(act) -> googleAction()
                    kind == SyncStore.CLOUD_YANDEX && !YandexDisk.connected(act) -> yandexAction()
                    kind == SyncStore.CLOUD_FOLDER && SyncStore.folderFor(act) == null -> pickSyncDir()
                }
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Действие строки выбранного места: вход или выход, проверка доступа, папка. */
    private fun syncAccountAction() {
        when (SyncStore.kind(act)) {
            SyncStore.CLOUD_GOOGLE -> googleAction()
            SyncStore.CLOUD_YANDEX -> yandexAction()
            else -> pickSyncDir()
        }
    }

    /** Строка «Яндекс.Диск» на экране: кто вошёл или предложение войти. */
    private fun yandexLabel(): String {
        val who = YandexDisk.user(act)
        val state = when {
            who != null -> getString(R.string.yandex_on, who)
            YandexDisk.connected(act) -> getString(R.string.yandex_on_plain)
            else -> getString(R.string.yandex_off_state)
        }
        return getString(R.string.yandex_title) + ": " + state
    }

    /** Вход в Яндекс или отказ от него. Пароль владелец вводит на странице
     *  Яндекса в своём браузере — через нас он не проходит и у нас не хранится;
     *  у нас остаётся только токен доступа. */
    private fun yandexAction() {
        if (YandexDisk.connected(act)) {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.yandex_title)
                .setItems(arrayOf(getString(R.string.yandex_off))) { _, _ ->
                    YandexDisk.reset(act)
                    toast(getString(R.string.yandex_off_done))
                    refreshRows()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            return
        }
        toast(getString(R.string.yandex_opening))
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YandexDisk.loginUrl(act))))
        }.onFailure {
            Diag.log(act, "sync", "не открылся браузер для входа в Яндекс: ${it.message}")
            toast(getString(R.string.yandex_no_browser))
        }
    }

    /** Строка «Google Диск»: кто вошёл или предложение войти. */
    private fun googleLabel(): String {
        val who = GoogleDrive.user(act)
        val state = if (who != null) getString(R.string.google_on, who)
        else getString(R.string.google_off_state)
        return getString(R.string.google_title) + ": " + state
    }

    /** Вход в Google или отказ от него (28.09.2026). Пароль владелец вводит в
     *  окне Google; у нас остаётся только имя аккаунта, а токен берётся молча и
     *  нигде не хранится. Там, где сервисов Google нет, так и говорим. */
    private fun googleAction() {
        if (!GoogleDrive.available()) {
            toast(getString(R.string.google_no_play))
            return
        }
        if (GoogleDrive.connected(act)) {
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.google_title)
                .setItems(
                    arrayOf(
                        getString(R.string.google_off),
                        getString(R.string.google_check),
                    )
                ) { _, which ->
                    if (which == 0) {
                        GoogleDrive.reset(act)
                        toast(getString(R.string.google_off_done))
                        refreshRows()
                    } else {
                        checkGoogle()
                    }
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            return
        }
        toast(getString(R.string.google_opening))
        runCatching { startActivity(Intent(act, GoogleAuthActivity::class.java)) }
            .onFailure {
                Diag.log(act, "sync", "не открылось окно входа в Google: ${it.message}")
                toast(getString(R.string.google_login_fail))
            }
    }

    /** Проверка доступа к служебной папке: спрашиваем у Google список файлов.
     *  В сеть — из фонового потока, ответ говорим словами. */
    private fun checkGoogle() {
        toast(getString(R.string.google_check))
        Thread {
            val err = GoogleDrive.check(act)
            runOnUiThread {
                if (err == null) toast(getString(R.string.google_check_ok))
                else toast(getString(R.string.google_check_fail, err))
            }
        }.start()
    }

    // ---------------- Перенос книг (msg6130) ----------------
    /** Строка «Книги на Диске»: сколько там книг, которых у нас нет. Число берём
     *  из памяти прошлой проверки — лезть в сеть на каждой перерисовке экрана
     *  нельзя. Ещё не считали — так и говорим: «нажми, чтобы проверить». */
    private fun booksLabel(): String {
        val n = SyncStore.diskNew(act)
        return when {
            n < 0 -> getString(R.string.sync_books_check)
            n == 0 -> getString(R.string.sync_books_none)
            else -> getString(R.string.sync_books_get, n)
        }
    }

    /** Включение переноса книг. Сначала считаем объём и спрашиваем: молча вывалить
     *  на Диск сотни мегабайт нельзя. Без входа в Яндекс переносить некуда —
     *  галочку возвращаем назад и говорим почему. */
    private fun onBooksToggle(on: Boolean) {
        if (!on) return
        val trouble = SyncStore.booksTrouble(act)
        if (trouble != null) {
            toast(trouble)
            booksBox?.isChecked = false // вернёт и галочку, и память
            return
        }
        toast(getString(R.string.sync_books_counting))
        Thread {
            val plan = SyncStore.planBooks(act)
            runOnUiThread {
                val err = plan.error
                when {
                    err != null -> {
                        toast(getString(R.string.sync_books_list_fail, err))
                        booksBox?.isChecked = false
                    }
                    plan.up.isEmpty() -> {
                        toast(getString(R.string.sync_books_all_there))
                        refreshRows()
                    }
                    else -> askBooksPush(plan)
                }
            }
        }.start()
    }

    /** Подтверждение заливки: сколько книг и сколько это мегабайт. */
    private fun askBooksPush(plan: SyncStore.BookPlan) {
        val what = getString(R.string.sync_books_what, qty(R.plurals.sync_books_n, plan.up.size), booksSize(plan.upBytes))
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.sync_books_title)
            .setMessage(what + "\n\n" + getString(R.string.sync_books_ask))
            .setPositiveButton(R.string.sync_books_go) { _, _ -> startBooksPush(plan) }
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> booksBox?.isChecked = false }
            .setOnCancelListener { booksBox?.isChecked = false }
            .show()
    }

    private fun startBooksPush(plan: SyncStore.BookPlan) {
        toast(getString(R.string.sync_books_pushing, plan.up.size))
        Thread {
            val res = SyncStore.pushBooks(act, plan)
            runOnUiThread {
                toast(
                    when {
                        res.error != null -> getString(R.string.sync_books_push_fail, res.error)
                        res.failed > 0 -> getString(R.string.sync_books_pushed_part, res.done, res.failed)
                        else -> getString(R.string.sync_books_pushed, res.done)
                    }
                )
                refreshRows()
            }
        }.start()
    }

    /** Забор книг с Диска. Всегда рукой владельца: это десятки мегабайт из
     *  мобильной сети, и решать про них должен он, а не фоновая синхронизация. */
    private fun pullBooksAction() {
        val trouble = SyncStore.booksTrouble(act)
        if (trouble != null) {
            toast(trouble)
            return
        }
        toast(getString(R.string.sync_books_checking))
        Thread {
            val plan = SyncStore.planBooks(act)
            runOnUiThread {
                val err = plan.error
                when {
                    err != null -> toast(getString(R.string.sync_books_list_fail, err))
                    plan.down.isEmpty() -> {
                        toast(getString(R.string.sync_books_none_toast))
                        refreshRows()
                    }
                    else -> askBooksPull(plan)
                }
            }
        }.start()
    }

    private fun askBooksPull(plan: SyncStore.BookPlan) {
        val what = getString(R.string.sync_books_what, qty(R.plurals.sync_books_n, plan.down.size), booksSize(plan.downBytes))
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.sync_books_title)
            .setMessage(getString(R.string.sync_books_pull_ask, what))
            .setPositiveButton(R.string.sync_books_pull_go) { _, _ -> startBooksPull(plan) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startBooksPull(plan: SyncStore.BookPlan) {
        toast(getString(R.string.sync_books_pulling, plan.down.size))
        Thread {
            val (n, err) = SyncStore.pullBooks(act, plan)
            // Забрали — сразу и синхронизация: в файле уже лежат места чтения этих
            // книг, их надо применить (и заодно залить новое, если галочка стоит).
            if (err == null) SyncStore.sync(act)
            val fresh = SyncStore.planBooks(act) // сколько осталось — для строки
            runOnUiThread {
                toast(
                    if (err != null) getString(R.string.sync_books_pull_fail, err)
                    else getString(R.string.sync_books_pulled, n)
                )
                if (fresh.error == null) refreshRows()
            }
        }.start()
    }

    /** Склонённое число книг: «23 книги», «21 книга». */
    private fun qty(resId: Int, n: Int): String = act.resources.getQuantityString(resId, n, n)

    /** Размер для диалога. Слова те же, что у строки кэша ([sizeText]): «480 МБ» —
     *  одно написание мегабайт на весь экран. Ноль здесь значит «размер узнать
     *  не вышло» (книга из чужой папки может не отдавать столбец размера), и
     *  тогда честнее сказать это словами, чем показать «меньше 1 МБ». */
    private fun booksSize(bytes: Long): String =
        if (bytes <= 0L) getString(R.string.sync_books_size_unknown) else sizeText(bytes)

    /** Смена папки синхронизации. Папка нужна ровно одна на оба устройства, и
     *  её выбор обойти нельзя: Android не даёт писать в чужое облако без
     *  разрешения. Но если папка уже есть (своя, папка книг в облаке или папка
     *  копий) — молчим и пользуемся ею, лишний выбор не навязываем. */
    private fun pickSyncDir() {
        // Касание строки сразу открывает системный выбор папки (30.09.2026, просьба
        // Сержа): окошко с одной строкой «Сменить папку» и кнопкой «Закрыть» вслепую
        // только путало. Если папка уже есть (своя, папка книг в облаке или папка
        // копий) — выбор по-прежнему не навязываем, но и не прячем за окном.
        openSyncDirPicker()
    }

    private fun openSyncDirPicker() {
        act.openTreePicker { uri -> if (uri != null) onSyncDirPicked(uri) }
    }

    private fun onSyncDirPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        SyncStore.setDir(act, tree)
        // Выбрал папку — значит синхронизация идёт через папку: запоминаем место.
        SyncStore.setChosen(act, SyncStore.CLOUD_FOLDER)
        toast(getString(R.string.sync_dir_saved))
        refreshRows()
    }

    /** Ручной прогон. Работает и при выключенной галочке: галочка отвечает за
     *  фоновую синхронизацию на полке и в читалке, а не за право нажать кнопку. */
    private fun runSyncNow() {
        val trouble = SyncStore.trouble(act)
        if (trouble != null) {
            toast(trouble)
            // Папки нет — сразу предлагаем выбрать: без неё прогон не пойдёт.
            if (SyncStore.kind(act) == SyncStore.CLOUD_FOLDER) pickSyncDir()
            return
        }
        toast(getString(R.string.sync_now))
        Thread {
            SyncStore.sync(act)
            act.runOnUiThread {
                // Текст отчёта берём из SyncStore — тот же, что ложится в строку
                // «Последняя синхронизация»: одно место, один текст.
                toast(SyncStore.lastResult(act) ?: getString(R.string.sync_same))
                refreshRows()
            }
        }.start()
    }

    /** Ручной путь: унести файл синхронизации самому (msg6086) — «Поделиться»
     *  тем же способом, что и резервная копия. Нужен, когда облака нет вовсе. */
    private fun sendSyncFile() {
        val f = SyncStore.exportFile(act)
        if (f == null) {
            toast(getString(R.string.sync_share_fail))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(act, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.sync_send)))
        } catch (e: Exception) {
            Diag.log(act, "sync", "ошибка отправки файла синхронизации: ${e.message}")
            toast(getString(R.string.sync_share_fail))
        }
    }

    /** Ручной путь: принять файл, принесённый из Telegram или из проводника. */
    private fun openSyncFilePicker() {
        act.openDocPicker(arrayOf("application/json", "text/plain", "*/*")) { uri ->
            if (uri != null) importSyncFile(uri)
        }
    }

    private fun importSyncFile(uri: Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            toast(getString(R.string.sync_pick_fail))
            return
        }
        toast(getString(R.string.sync_now))
        Thread {
            SyncStore.importBytes(act, bytes)
            act.runOnUiThread {
                toast(SyncStore.lastResult(act) ?: getString(R.string.sync_same))
                refreshRows()
            }
        }.start()
    }

    /** Что показываем в строке папки: имя и — обязательно — облако это или
     *  память телефона. Папка на телефоне синхронизацией не является, и человек
     *  должен узнать это из строки, а не из тишины. */
    private fun syncDirLabel(): String {
        val tree = SyncStore.folderFor(act) ?: return getString(R.string.sync_dir_none)
        val name = folderLabel(tree).ifBlank { tree.lastPathSegment ?: "" }
        return name + ", " + getString(
            if (SyncStore.isCloud(tree)) R.string.sync_dir_cloud else R.string.sync_dir_local
        )
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
        if (!info.hasSettings && !info.hasBooks) {
            toast(getString(R.string.backup_bad_file))
            return
        }
        // 30.09.2026: галочек «что брать» больше нет — восстанавливаем всё, что
        // есть в файле. Окно остаётся подтверждением: по нему слышно, копия от
        // какого числа и что в ней лежит.
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
            .setPositiveButton(R.string.backup_restore_go) { _, _ ->
                val res = BackupStore.restore(act, bytes, info.hasSettings, info.hasBooks)
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

    /** Расписание копий выключено (или не выбиралось). */
    private fun backupAutoOff(): Boolean =
        (prefs.getString(BackupStore.KEY_AUTO, BackupStore.AUTO_DEFAULT)
            ?: BackupStore.AUTO_DEFAULT) == BackupStore.AUTO_OFF

    /** Строка расписания с датой последней копии (0.4.86, msg7020): раньше даты
     *  в списке не было вовсе, и по строке нельзя было понять, работает ли
     *  расписание. Информация добавляется, остановка — нет. */
    private fun backupAutoText(): CharSequence {
        val base = valueLine(getString(R.string.backup_auto_title), backupAutoLabel())
        if (backupAutoOff()) return base
        val last = BackupStore.lastBackupAt(act)
        if (last <= 0L) return base
        return SpannableStringBuilder(base).append(", ")
            .append(getString(R.string.backup_auto_last, backupDateStr(last)))
    }

    // ---------------- Диагностика обновлений ----------------
    // Выбор «Автоматически / Вручную» убран: автопроверка теперь флажок в
    // «Разном» (UpdateFlow.KEY_AUTO), ручная — кнопка «Проверить обновление».

    private fun startIndex(): Int =
        if (prefs.getString(MainActivity.KEY_START, MainActivity.START_LAST) == MainActivity.START_LAST) 1 else 0

    /** Значение ползунка скорости/тона для подписи и озвучки («0.8», «1.2»). */
    private fun rateLabel(rate: Float): String = RateSteps.label(rate)

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

    // ---------------- Плеер для списков голоса ----------------

    /** #70: выбор движка, языка и голоса работает и без открытой книги. У живой
     *  книги это её плеер (выбор слышен сразу), без книги на время списка
     *  поднимаем временный движок и гасим его, когда список закрыли: выбор всё
     *  равно уходит в настройки и подхватится при следующем открытии книги
     *  (MainActivity читает KEY_ENGINE / KEY_VOICE / KEY_VOICE_LANG). Раньше на
     *  это место был тост «сначала открой книгу» — Сергей упёрся в него в 0.3.40
     *  (msg766). [onReady] получает плеер и признак «временный»: временный надо
     *  погасить самому, когда диалог закрылся.
     *
     *  #75 (msg5618): это же и `VoicePicker.Host.withPlayer` — списки движка,
     *  языка и голоса открывает общий набор. */
    private fun withVoiceEngine(titleRes: Int, onReady: (SpeechPlayer, Boolean) -> Unit) {
        val live = MainActivity.active?.player
        if (live != null && live.isReady) {
            onReady(live, false)
            return
        }
        val tmp = SpeechPlayer(act.applicationContext)
        val loading = MaterialAlertDialogBuilder(act)
            .setTitle(titleRes)
            .setMessage(R.string.engine_loading)
            .setNegativeButton(R.string.toc_close, null)
            .show()
        tmp.setEngine(prefs.getString(MainActivity.KEY_ENGINE, null)) { ok ->
            if (!ok) {
                // 0.4.76: стирание выбранного движка — событие, а не мелочь:
                // после него приложение везде читает системным. Пишем в журнал
                // (раньше это было видно только по отсутствию строк).
                Diag.log(
                    act, "tts",
                    "выбранный движок ${prefs.getString(MainActivity.KEY_ENGINE, null) ?: "системный"} " +
                        "не запустился — забываю его, беру системный"
                )
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
            onReady(tmp, true)
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
