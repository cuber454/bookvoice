package com.cuber.bookvoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.speech.RecognizerIntent
import android.text.Html
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AlertDialog
import com.cuber.bookvoice.databinding.ActivityCatalogBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.URLEncoder

/**
 * Онлайн-каталоги книг по протоколу OPDS (v0.3.26).
 *
 * Экран «Каталоги»: список источников + управление ими. Внутри источника —
 * список-каталог как в обычных читалках: каждая строка «название + вторая
 * строка-пояснение» (её присылает сам каталог: «837 новых книг», «Новые
 * поступления за неделю»…). Раздел открывается углублением, книга — своей
 * страницей с описанием и большой кнопкой «Скачать»; у каждой книги в списке
 * есть и быстрая кнопка «Скачать» в один тап.
 *
 * Длинные списки не дробятся на экраны-страницы: когда пользователь долистывает
 * до конца, следующая страница каталога (rel="next") подгружается сама и
 * дописывается вниз — так до самого конца списка. Срабатывание — и по
 * прокрутке, и по accessibility-скроллу TalkBack (когда он упирается в конец).
 *
 * Вся работа с сетью — в фоновых потоках ([Thread]), UI дёргаем через
 * runOnUiThread.
 */
class CatalogActivity(private val act: SectionActivity) {

    companion object {
        /** Флаг в интенте окна (msg2723/2730): полка попросила открыть каталог сразу
         *  в голосовом поиске (долгое нажатие «Каталоги»). */
        const val EXTRA_VOICE_SEARCH = "extra_voice_search"
    }

    // Страница живёт В СВОЁМ окне-секции SectionActivity (редизайн msg1676+:
    // Каталоги открываются окном ПОВЕРХ полки, как книга). У страницы нет своего
    // Activity — все службы (контекст, пикеры, запуск окон, диалоги) идут через
    // окно [act]. Навигация каталога (nav/sessions) и состояние лент живут в
    // странице, пока окно открыто; с закрытием окна состояние сбрасывается.
    private lateinit var binding: ActivityCatalogBinding

    private val contentResolver get() = act.contentResolver
    private val packageName get() = act.packageName
    private val packageManager get() = act.packageManager
    private val filesDir get() = act.filesDir

    private fun getString(resId: Int, vararg formatArgs: Any): String =
        act.getString(resId, *formatArgs)

    private fun runOnUiThread(block: () -> Unit) = act.runOnUiThread(block)
    private fun startActivity(intent: Intent) = act.startActivity(intent)

    /** Текущий путь. Пуст — список каталогов; [Nv.Feed] — лента; [Nv.Book] — книга. */
    private val nav = ArrayList<Nv>()

    /** Состояние открытых лент по ключу = url первой страницы (первой в списке). */
    private val sessions = HashMap<String, FeedSession>()

    /** Возврат из ленты в корень (список каталогов): url каталога, на строку
     *  которого вернуть accessibility-фокус (msg1468). Снимается при отрисовке. */
    private var rootFocusUrl: String? = null

    private var downloading = false

    /** 0.3.47: выбранная SAF-папка не дала записать файл (MixPlorer и т.п.) —
     *  книгу пришлось сохранить во внутреннюю память. Для тоста-предупреждения. */
    private var dlFallback = false

    /** msg1264: прогресс скачивания на кнопке «Скачать». [dlButton] — эта кнопка
     *  текущей страницы книги (ставится в renderBookPage, сбрасывается там же);
     *  [dlBookUrl] — url качаемой книги; [dlPct]: -1 = сервер размер не прислал
     *  (подпись без процента), иначе 0..100; [dlAnnounced] — озвученная веха ×25. */
    private var dlButton: Button? = null
    private var dlBookUrl: String? = null
    private var dlPct = -1
    private var dlAnnounced = 0

    /** Служебная нижняя строка ленты («Догружаю…» / кнопка повтора) — её убираем. */
    private var pendingRow: View? = null

    private val currentButtons = ArrayList<Button>()

    /** msg2723/2730: окно открыли долгим нажатием «Каталоги» — ждём, когда верхняя
     *  лента станет поисковой (searchTemplate), и сами стартуем голосовой поиск.
     *  Сбрасывается, как только флаг отработан или искать стало негде. */
    private var pendingVoiceSearch = false

    /** msg3052/3055/3057: это окно открыто долгим нажатием «Каталоги» С ПОЛКИ —
     *  транзит ради голосового поиска. Живёт весь срок окна (в отличие от
     *  pendingVoiceSearch): пока окно не закрылось, «назад» из ЛЮБОГО уровня
     *  ведёт сразу на полку, а не на уровень выше внутри каталога. Обычный вход
     *  в Каталог (кнопка/⋮, без EXTRA_VOICE_SEARCH) этого флага не получает —
     *  там «назад» работает как раньше. */
    private var transitFromShelf = false

    private val prefs by lazy { act.getSharedPreferences("reader", Context.MODE_PRIVATE) }

    /** Смена папки скачиваний (кнопка в «Настройках скачивания»). Пикер дерева
     *  регистрирует хост (у страницы нет Activity) — зовём act.openTreePicker. */
    private fun openDlDirPicker() {
        act.openTreePicker { uri -> if (uri != null) onDlDirPicked(uri) }
    }

    private fun onDlDirPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(OpdsPrefs.KEY_DL_DIR, uri.toString()).apply()
        toast(getString(R.string.dl_folder_saved))
        probeDlDir(uri)
    }

    /** Результат системного распознавания речи (см. startVoiceSearch, msg1421):
     *  распознанная непустая фраза уходит в поиск по каталогу (тот же путь, что
     *  у текстового окна). */
    private fun handleVoiceResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val q = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.takeIf { it.isNotBlank() }
        if (q == null) {
            binding.root.announceForAccessibility(getString(R.string.search_voice_retry))
            return
        }
        val s = currentFeedSession()
        if (s == null) return
        // Сергей не видел введённой фразы — озвучиваем, что ищем.
        binding.root.announceForAccessibility(getString(R.string.catalog_search_voice_announce, q))
        runSearch(s, q)
    }

    /** Проверка папки скачиваний сразу после выбора (0.3.47, msg1165/1167) —
     *  копия SettingsActivity.probeDlDir: MixPlorer и другие сторонние проводники
     *  отдают tree-uri, но не умеют createDocument. Нерабочую папку сбрасываем
     *  (скачивания идут во внутреннюю память) и предупреждаем. */
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
                // SAF-запись не вышла (MixPlorer и т.п. не умеют createDocument).
                // Если папке можно сопоставить реальный путь — она заработает
                // через «Доступ ко всем файлам», сбрасывать её рано.
                val dir = AllFiles.resolveDir(tree)
                if (dir == null) {
                    prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                    toast(getString(R.string.dl_folder_unwritable))
                    return@runOnUiThread
                }
                if (AllFiles.granted(act)) {
                    if (!AllFiles.probeViaRealPath(act, tree)) {
                        prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                        toast(getString(R.string.dl_folder_unwritable))
                    }
                    // доступ есть и реальный путь пишет — папка рабочая, молчим
                } else {
                    AllFiles.ask(act, getString(R.string.all_files_explain))
                }
            }
        }.start()
    }
    private var dlDialog: AlertDialog? = null

    private val acceptFeed = "application/atom+xml, application/xml;q=0.9, */*;q=0.8"

    private sealed class Nv {
        data class Feed(val url: String, val title: String) : Nv()
        data class Book(val item: OpdsItem.Book, val sourceTitle: String) : Nv()
    }

    private class FeedSession(val url: String, title: String) {
        var title: String = title
        var searchTemplate: String? = null
        val items = ArrayList<OpdsItem>()
        var nextUrl: String? = null
        var loadingFirst = false
        var loadedOnce = false
        var loadingMore = false
        var failMsg: String? = null
        var foldersShown = false
        var booksShown = false
        var scrollY = 0
        /** Адрес строки (книги/папки), с которой ушли вниз — после возврата
         *  «Назад» на неё возвращается accessibility-фокус (msg1220). */
        var focusUrl: String? = null
        /** Жест-подсказка (долгое нажатие — скачать) озвучивается один раз. */
        var hintAnnounced = false
    }

    /** Построить страницу каталогов в контейнере [container] хоста. Зовётся один
     *  раз при первом показе вкладки; страница остаётся живой всё время (навигация
     *  nav/sessions и достроенные ленты переживают переключения). [intent] —
     *  интент, с которым запущен хост (каталог его не использует). */
    fun build(container: ViewGroup, intent: Intent?) {
        binding = ActivityCatalogBinding.inflate(act.layoutInflater)
        container.addView(binding.root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        OpdsPrefs.ensureDefaults(act)
        Diag.log(act, "opds", "экран каталогов, версия $versionName")
        binding.btnSearch.setOnClickListener { toggleSearchPanel() }
        // msg1421: долгий тап (под TalkBack — двойной тап-удержание) — голосовой поиск.
        binding.btnSearch.setOnLongClickListener {
            val s = currentFeedSession()?.takeIf { it.searchTemplate != null }
            if (s == null) false else { startVoiceSearch(s); true }
        }
        // msg3214: панель поиска под шапкой — поле в фокусе с клавиатурой, рядом
        // кнопки голосового ввода и «Найти».
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchFromField()
                true
            } else {
                false
            }
        }
        binding.btnSearchMic.setOnClickListener {
            val s = currentFeedSession()?.takeIf { it.searchTemplate != null }
            if (s != null) startVoiceSearch(s)
        }
        binding.btnSearchGo.setOnClickListener { searchFromField() }
        binding.btnMore.setOnClickListener { showCatalogMoreMenu() }
        // msg1712: шапка как в FBReader — «Назад» слева от заголовка. Работает как
        // системный «назад»: внутри каталога — уровень выше (goBack), в корне
        // «Мои каталоги» — rootBack окна на полку (suppress авто-открытия ставит
        // onDestroy, фикс 0.3.75).
        binding.btnBack.setOnClickListener { onBackKey() }
        // msg2359/2363/2371: нижняя панель каталога — «Открыть книгу: <последняя>»
        // и «Библиотека» (одно касание до полки с любого уровня). Панель на ВСЕХ
        // уровнях, включая корень «Мои каталоги».
        binding.btnCatContinue.setOnClickListener { openLastBook() }
        // msg2723/2738: долгое нажатие «Открыть книгу» — список недавних книг
        // (тот же жест, что у «Продолжить» на полке).
        binding.btnCatContinue.setOnLongClickListener {
            Vibra.confirm(act)
            showRecentBooks()
            true
        }
        // msg2445: «Библиотека» запоминает место в каталоге, чтобы вернуться в него.
        binding.btnCatLibrary.setOnClickListener { leaveToLibrary() }

        setupAutoLoad()
        // msg2445: вернулись в Каталог — показываем запомненное место (не корень),
        // если уходили на полку из глубины каталога/со страницы книги.
        restorePosition()
        renderTop()
        // msg2723/2730: долгое нажатие «Каталоги» на полке — старт голосового
        // поиска, когда верхняя лента готова (или обычное открытие, если искать
        // негде — в корне «Мои каталоги»).
        transitFromShelf = intent?.getBooleanExtra(EXTRA_VOICE_SEARCH, false) == true
        pendingVoiceSearch = transitFromShelf
        if (pendingVoiceSearch) binding.root.post { maybeVoiceSearchAfterEntry() }
    }

    /** Окно закрывается — прячем диалог, если открыт.
     *
     *  Место в каталоге (msg2445) здесь НЕ стираем (msg4247). Окно закрывается не
     *  только при полном выходе, но и при обычном уходе на полку (кнопка
     *  «Библиотека», «назад» из корня) — и именно тогда запомненное место должно
     *  пережить закрытие. Раньше память чистилась здесь, поэтому сохранённая
     *  позиция умирала в тот же миг, и следующий вход в «Сетевые библиотеки»
     *  начинался с корня, хотя место было записано.
     *
     *  Полного выхода это не касается: место живёт в памяти процесса, а «Выход из
     *  приложения» процесс убивает (TabNav.exitApp) — начинать чистить нечего.
     *  Холодный запуск по этой же причине открывает каталог с корня (msg4266). */
    fun dispose() {
        dlDialog?.dismiss()
        dlDialog = null
    }

    // ---------------- Память места в каталоге (msg2445) ----------------

    /** Уход на полку («Библиотека» нижней панели, «назад» из корня, пункт ⋮):
     *  запоминаем, где стояли, чтобы вернуться сюда при следующем входе в Каталог. */
    private fun capturePosition() {
        val feeds = ArrayList<Pair<String, String>>()
        var book: OpdsItem.Book? = null
        var src = ""
        for (n in nav) when (n) {
            is Nv.Feed -> feeds.add(n.url to n.title)
            is Nv.Book -> {
                book = n.item
                src = n.sourceTitle
            }
        }
        CatalogMemory.feeds = feeds
        CatalogMemory.topBook = book
        CatalogMemory.topBookSource = src
    }

    /** Выход на полку (кнопка «Библиотека» и равнозначные): сначала запомнить место. */
    private fun leaveToLibrary() {
        // Покидаем окно — отложенный голосовой поиск (msg2723) больше не нужен.
        pendingVoiceSearch = false
        capturePosition()
        act.rootBack()
    }

    /** Свежее открытие окна Каталога: если запомнили место (msg2445) — выкладываем
     *  в nav ленту-стек, а поверх, если стояли на странице книги, — книгу. Нижние
     *  ленты сами не грузим: поднимутся при «назад» (openFeed дотянет). Верхнюю
     *  ленту или страницу книги показывает renderTop. Память не чистим — место
     *  хранится, пока его не перезапишет следующий выход с другого уровня. */
    private fun restorePosition() {
        if (CatalogMemory.feeds.isEmpty() && CatalogMemory.topBook == null) return
        nav.clear()
        for ((url, title) in CatalogMemory.feeds) nav.add(Nv.Feed(url, title))
        CatalogMemory.topBook?.let { nav.add(Nv.Book(it, CatalogMemory.topBookSource)) }
    }

    /** Страница показана. [byTab]=true — первый показ окна (свежий вход из меню
     *  «⋮» полки): фокус встаёт на ЗАГОЛОВОК окна ([binding.tvTitle]) — TalkBack
     *  читает, где мы: корень каталога или открытая лента/жанр (msg1666). Курсор
     *  в контент не тащим и отдельных фраз не шлём: сам фокус на заголовке и есть
     *  подтверждение. Заголовок — стабильный TextView вверху, не в списке:
     *  перенос держится, в отличие от прыжков на первый пункт ленты
     *  (msg1652/1662). Обычные показы (возврат из читалки, из пикера) флаг не
     *  получают — фокус не трогаем. */
    fun resume(byTab: Boolean) {
        if (byTab) {
            TabNav.focusHeader(binding.tvTitle)
        }
        // Возврат из читалки/пикера: обновить подпись последней книги на нижней
        // панели (место чтения/статус могли измениться).
        updateBottomBar()
    }

    /** «Назад» на странице каталогов: внутри (список/жанр/книга открыты) выходит
     *  на уровень выше через goBack(); в корне закрывает окно — возврат на полку
     *  (редизайн msg1676+: Каталоги — окно поверх, не вкладка). True — обработано
     *  (окно дальше не пускает).
     *
     *  msg3052/3055/3057/3073: если окно открыто долгим нажатием «Каталоги» с
     *  полки (транзит ради голосового поиска), «назад» с ЛЕНТЫ (список поиска /
     *  любая лента / корень) ведёт СРАЗУ на полку — не на уровень выше каталога.
     *  А «назад» со СТРАНИЦЫ КНИГИ — обычный возврат на ленту результатов, из
     *  которой её открыли (msg3073: из книги не выкидываем на полку, только со
     *  списка поиска). Место каталога (msg2445) при этом не трогаем (rootBack без
     *  capturePosition): поисковая сессия не должна перезаписывать обычную
     *  позицию, к которой человек вернётся следующим обычным входом в Каталог. */
    fun onBackKey(): Boolean {
        // msg3214: открыта панель поиска — «назад» сначала закрывает её (как в
        // читалке), на уровень выше выходим повторным «назад».
        if (searchPanelOpen()) {
            closeSearchPanel()
            return true
        }
        // msg2233: «назад» из каталога якобы выкидывает на полку/в книгу. Пишем
        // глубину: 0 = корень окна (rootBack на полку), >0 = выход на уровень выше.
        Diag.log(act, "nav", "Каталог: «назад», глубина = ${nav.size}")
        if (transitFromShelf) {
            if (nav.lastOrNull() is Nv.Book) {
                // Открыта страница книги — из неё «назад» на ленту результатов
                // (goBack сам гасит pendingVoiceSearch и перерисовывает ленту).
                Diag.log(act, "nav", "Каталог: транзит — из книги на ленту результатов")
                goBack()
            } else {
                Diag.log(act, "nav", "Каталог: транзит с полки — «назад» с ленты/корня сразу на полку")
                pendingVoiceSearch = false
                act.rootBack()
            }
            return true
        }
        if (!goBack()) {
            Diag.log(act, "nav", "Каталог: «назад» в корне — закрываю окно на полку")
            leaveToLibrary()
        }
        return true
    }

    /** True — обработано внутри (вышел на уровень выше), false — закрыть экран. */
    private fun goBack(): Boolean {
        // Пользователь сам пошёл по каталогу — отложенный голосовой поиск (msg2723)
        // гасим, чтобы не выскочил микрофон позже, когда его уже не ждут.
        pendingVoiceSearch = false
        if (nav.isEmpty()) return false
        stashScroll()
        // msg1468: уходим из ленты источника в корень — после перерисовки вернуть
        // фокус на строку этого каталога (не терять и не падать на первую).
        if (nav.size == 1 && nav.last() is Nv.Feed) {
            rootFocusUrl = (nav.last() as Nv.Feed).url
        }
        nav.removeAt(nav.size - 1)
        renderTop()
        return true
    }

    // ---------------- Меню «⋮» в шапке (msg1474) ----------------

    /** «⋮ Ещё» окна Каталога: в корне «Мои каталоги» первым идёт «Добавить
     *  каталог» (msg2371: вход в добавление переехал сюда из убранного FAB), затем
     *  «Библиотека» (прыжок на полку-дом из глубины, редизайн msg1676+),
     *  «Настройки программы» (msg4657), «Настройки скачивания»,
     *  «Выход из приложения». На глубине пункт добавления
     *  не показываем (каталог уже открыт). Бывшую шестерёнку ⚙ из шапки убрали
     *  (msg1474): у настроек скачивания один вход — здесь, на странице книги —
     *  своя кнопка в потоке контента (#301). Выход — всегда последним (msg1278). */
    private fun showCatalogMoreMenu() {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (nav.isEmpty()) {  // корень «Мои каталоги»: добавить новый каталог.
            actions.add(getString(R.string.catalog_add) to { showAddDialog() })
        }
        actions.add(getString(R.string.go_library) to {  // домой: до полки.
            leaveToLibrary()  // msg2445: место каталога запоминаем перед уходом.
        })
        // msg4657: «Настройки программы» — как в «⋮» полки и читалки, чтобы из
        // сетевых библиотек не приходилось идти на полку ради настроек. Окном
        // поверх каталога: закрытие возвращает в каталог. Ярлык отличается от
        // соседних «Настройки скачивания» (catalog_dl_settings_title) — на слух
        // два пункта «Настройки…» подряд путали бы.
        actions.add(getString(R.string.settings_program) to {
            act.startActivity(Intent(act, SettingsWindowActivity::class.java))
        })
        actions.add(getString(R.string.catalog_dl_settings_title) to { openDlSettings() })
        actions.add(getString(R.string.app_exit) to { TabNav.exitApp(act) })  // msg1278: последним.
        MaterialAlertDialogBuilder(act)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    // ---------------- Низкоуровневые помощники ----------------

    private fun content(): LinearLayout = binding.content

    private fun lp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    private val versionName: String get() = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** Помнить, куда долистал текущую ленту (для возврата назад). */
    private fun stashScroll() {
        (nav.lastOrNull() as? Nv.Feed)?.let {
            sessions[it.url]?.scrollY = binding.scroll.scrollY
        }
    }

    private fun finishScrollTo(y: Int) {
        binding.scroll.post { binding.scroll.scrollTo(0, y) }
    }

    private fun currentFeedSession(): FeedSession? {
        val cur = nav.lastOrNull() as? Nv.Feed ?: return null
        return sessions[cur.url]
    }

    /** msg2723/2730: окно открыли долгим нажатием «Каталоги» — пробуем стартовать
     *  голосовой поиск. Верхняя лента ещё грузится — ждём её (сработает в
     *  fetchFirst по готовности); корень «Мои каталоги» — сами открываем верхний
     *  каталог списка (msg2779); страница книги или пустой список каталогов —
     *  искать негде, открываем как обычно и флаг гасим. */
    private fun maybeVoiceSearchAfterEntry() {
        if (!pendingVoiceSearch) return
        val cur = nav.lastOrNull()
        if (cur is Nv.Feed) {
            val s = sessions[cur.url]
            // Лента впервые грузится — searchTemplate узнаем только после ответа
            // каталога; старт возьмёт на себя fetchFirst (лента всё ещё верхняя).
            if (s == null || !s.loadedOnce) return
            pendingVoiceSearch = false
            if (s.searchTemplate != null) delayedVoiceSearch(s)
            return
        }
        if (cur == null) {
            // msg2779/2783: окно открыли долгим нажатием «Каталоги», а место не
            // запомнено — верх корень «Мои каталоги», искать негде. Сами открываем
            // верхний каталог списка; голосовой поиск стартанёт, когда его лента
            // загрузится и окажется поисковой (см. fetchFirst). Каталогов нет —
            // остаёмся на пустом корне, флаг гасим.
            val first = OpdsPrefs.sources(act).firstOrNull()
            if (first != null) {
                openSource(first)
                return
            }
        }
        pendingVoiceSearch = false
    }

    /** Старт голосового поиска с короткой задержкой (после озвучки ленты), только
     *  если лента [s] всё ещё верхняя — за задержку пользователь мог уйти «назад». */
    private fun delayedVoiceSearch(s: FeedSession) {
        binding.root.postDelayed({
            if (currentFeedSession() === s) startVoiceSearch(s)
        }, 500)
    }

    private fun isTopFeed(url: String): Boolean =
        (nav.lastOrNull() as? Nv.Feed)?.url == url

    private fun hasMore(s: FeedSession?): Boolean =
        s != null && s.loadedOnce && s.nextUrl != null && s.failMsg == null && !s.loadingMore

    private fun removePending() {
        pendingRow?.let { content().removeView(it) }
        pendingRow = null
    }

    private fun applyEnabled() {
        val en = !downloading
        for (b in currentButtons) b.isEnabled = en
    }

    // ---------------- msg1264: прогресс скачивания ----------------

    /** Подпись кнопки во время загрузки (без размера от сервера — просто
     *  «Скачивание…»). Зовётся из [publishProgress] и [renderBookPage], где [dlPct]
     *  уже выставлен. */
    private fun dlProgressText(): String =
        if (dlPct >= 0) getString(R.string.catalog_dl_progress_pct, dlPct)
        else getString(R.string.catalog_dl_progress)

    /** Апдейт подписи кнопки из фонового потока: каждый новый процент (pct=-1 —
     *  размер неизвестен, только подпись). Дедуп по [dlPct]. Голосом объявляем
     *  редкие вехи 25/50/75 — не каждый процент, чтобы не шуметь. */
    private fun publishProgress(pct: Int) {
        runOnUiThread {
            if (act.isFinishing || act.isDestroyed) return@runOnUiThread
            if (dlPct == pct) return@runOnUiThread
            dlPct = pct
            // Страница всё ещё показывает качаемую книгу — меняем подпись кнопки.
            val cur = nav.lastOrNull()
            if (cur is Nv.Book && cur.item.url == dlBookUrl) {
                val btn = dlButton
                val label = dlProgressText()
                if (btn != null) {
                    btn.text = label
                    btn.contentDescription = label
                }
                if (pct in 1..99) {
                    val q = pct / 25
                    if (q > dlAnnounced) {
                        dlAnnounced = q
                        announceDl(getString(R.string.catalog_dl_announce, q * 25))
                    }
                }
            }
        }
    }

    /** Громкая веха прогресса. Шлём со скролла, чтобы пробиться поверх очереди
     *  TalkBack даже если фокус не на кнопке. */
    private fun announceDl(text: String) {
        binding.scroll.announceForAccessibility(text)
    }

    /** Сброс состояния прогресса после завершения загрузки (успех или провал). */
    private fun resetDlState() {
        dlBookUrl = null
        dlPct = -1
        dlAnnounced = 0
    }

    // ---------------- Отрисовка верхнего уровня ----------------

    private fun renderTop() {
        val cur = nav.lastOrNull()
        val v = content()
        v.removeAllViews()
        currentButtons.clear()
        removePending()
        // msg2371: нижняя панель «Открыть книгу/Библиотека» — на ВСЕХ уровнях,
        // включая корень «Мои каталоги» (FAB «Добавить каталог» убран — вход в
        // добавление живёт в ⋮-меню корня).
        updateBottomBar()
        when (cur) {
            null -> {
                // msg1700: корень каталога — одна строка сверху «Мои каталоги».
                // Заголовок окна и внутренняя строчка дублировали друг друга;
                // внутреннюю убрали (renderSources), имя экрана живёт в tvTitle.
                binding.tvTitle.setText(R.string.catalog_sources_title)
                showSearch(false)
                renderSources()
                finishScrollTo(0)
            }
            is Nv.Feed -> {
                openFeed(cur)
            }
            is Nv.Book -> {
                renderBookPage(cur)
            }
        }
    }

    private fun showSearch(visible: Boolean) {
        binding.btnSearch.visibility = if (visible) View.VISIBLE else View.GONE
        // Лента/книга не поисковые — панель поиска тоже прячем (msg3214): она
        // жила под шапкой ради поисковой ленты, на другой странице не нужна.
        if (!visible) closeSearchPanel()
    }

    // ---------------- Нижняя панель каталога (msg2359/2363/2371) ----------------

    /** Нижний ряд быстрых действий каталога, как на полке: слева — умная кнопка
     *  последней книги «Открыть книгу: <название>» (текст как в
     *  LibraryActivity.updateContinueButton, msg2108), правее — «Библиотека»:
     *  одно касание до полки с любого уровня (rootBack окна, минуя «назад» по
     *  шагам). Панель видна на ВСЕХ уровнях, включая корень «Мои каталоги»
     *  (msg2371): везде один и тот же ряд, как и в остальном приложении. Текст и
     *  цель — общий с полкой BookStore.continueTarget (msg3202/msg3206): кнопка
     *  не исчезает, при удалённой последней книге ведёт в самую свежую из живых
     *  открывавшихся, а если открытых не осталось — остаётся видимой и активной,
     *  «Нет открытых книг» (пустой тап — тост, openLastBook). Не выключаем:
     *  неактивную кнопку скринридер свайпом пропускает (см. LibraryActivity). */
    private fun updateBottomBar() {
        binding.bottomBar.visibility = View.VISIBLE
        val target = BookStore.continueTarget(act, prefs.getString(MainActivity.KEY_URI, null))
        binding.btnCatContinue.visibility = View.VISIBLE
        binding.btnCatContinue.isEnabled = true
        binding.btnCatContinue.text = if (target == null) getString(R.string.continue_last_none)
        else getString(R.string.continue_last_with, target.displayTitle)
    }

    /** «Открыть книгу: <последняя>» с нижней панели каталога. Тот же выбор цели,
     *  что у текста кнопки (updateBottomBar / LibraryActivity.openLastBook):
     *  последняя живая книга, не та, что показывалась до удаления файла.
     *  Читалка открывается как из страницы книги каталога (EXTRA_FROM_CATALOG):
     *  «назад» из неё вернёт в каталог, а не на полку. */
    private fun openLastBook() {
        val target = BookStore.continueTarget(act, prefs.getString(MainActivity.KEY_URI, null))
        if (target == null) {
            toast(getString(R.string.continue_last_none))
            return
        }
        openInReader(target)
    }

    /** msg2723/2738: долгое нажатие «Открыть книгу» нижней панели — список недавних
     *  книг (кроме текущей последней). Выбор ведёт в книгу тем же путём, что
     *  открытие из каталога (EXTRA_FROM_CATALOG — «назад» вернёт сюда). */
    private fun showRecentBooks() {
        val exclude = prefs.getString(MainActivity.KEY_URI, null)
        val recent = BookStore.recent(act, exclude, 5)
        if (recent.isEmpty()) {
            toast(getString(R.string.recent_books_empty))
            return
        }
        val names = recent.map { r ->
            val author = r.author?.takeIf { it.isNotBlank() }
            if (author == null) r.displayTitle else r.displayTitle + " — " + author
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.recent_books_title)
            .setItems(names.toTypedArray()) { _, which -> openInReader(recent[which]) }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    // ---------------- Список каталогов (корень) ----------------

    private fun renderSources() {
        // msg1700: заголовок «Мои каталоги» — строка окна (tvTitle в renderTop),
        // внутренняя строчка дублировала его и убрана: список идёт сразу.
        val sources = OpdsPrefs.sources(act)
        if (sources.isEmpty()) {
            hint(getString(R.string.catalog_empty))
        }
        for (s in sources) {
            // Строка каталога без роли «кнопка» и с короткой озвучкой — только имя
            // (как строки книг в списках): «Флибуста», а не «Открыть каталог… кнопка».
            val row = addRowText(
                s.name + "\n" + s.url,
                cd = s.name,
            ) { openSource(s) }
            // Метка-адрес: по ней после возврата из ленты находим строку каталога
            // и возвращаем на неё фокус (msg1468).
            row.tag = s.url
            // Короткий тап открывает каталог; долгий — меню (изменить/удалить).
            row.setOnLongClickListener {
                vibrate(60)
                showSourceMenu(s)
                true
            }
        }
        focusRootIfReturning()
    }

    /** msg1468: вернуть accessibility-фокус на строку каталога, из ленты которого
     *  вышли «назад» в корень. Строки уже нет (каталог удалён) — падаем на первый
     *  доступный элемент списка, чтобы фокус не потерялся вовсе. Пауза 350 мс —
     *  как в focusFeedFirst: мгновенный requestFocus после перерисовки TalkBack
     *  роняет на нижние вкладки (0.3.55).
     *
     *  L1-каталог (msg1573): ПЕРЕНОС не шлём — Jieshuo игнорирует наши события
     *  (0.3.60). Вместо этого объявляем голосом, куда вернулись, и наблюдаем, где
     *  ридер осядет сам. */
    private fun focusRootIfReturning() {
        val url = rootFocusUrl ?: return
        rootFocusUrl = null
        binding.content.postDelayed({
            val v = content()
            var target: View? = null
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c.tag == url && c.isFocusable && c.isShown) {
                    target = c
                    break
                }
            }
            if (target == null) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c.isFocusable && c.visibility == View.VISIBLE) {
                        target = c
                        break
                    }
                }
            }
            if (target != null) {
                target.announceForAccessibility(target.contentDescription ?: "")
            }
            TabNav.observeFocusAfter(binding.root, "возврат в корень")
        }, 350)
    }

    /** Меню долгого нажатия на каталог в списке (#53): открыть, изменить,
     *  проверить, передвинуть, удалить. «Выше/ниже» показываем, только если
     *  каталог можно сдвинуть в эту сторону. «Войти/Выйти» (#20) — только там,
     *  где вход есть или где библиотека сама его попросила. */
    private fun showSourceMenu(s: OpdsPrefs.Source) {
        val sources = OpdsPrefs.sources(act)
        val idx = sources.indexOfFirst { it.url == s.url }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.catalog_open_short) to { openSource(s) })
        actions.add(getString(R.string.catalog_edit) to { showEditDialog(s) })
        actions.add(getString(R.string.catalog_check) to { checkSource(s) })
        if (OpdsAuth.hasFor(act, s.url)) {
            actions.add(
                getString(R.string.catalog_logout_menu, OpdsAuth.login(act)) to { logoutSource(s) },
            )
        } else if (OpdsAuth.askedBefore(act, s.url)) {
            actions.add(getString(R.string.catalog_login_menu) to { askLogin(s.url) })
        }
        if (idx > 0) {
            actions.add(getString(R.string.catalog_move_up) to { moveSource(s, -1) })
        }
        if (idx in 0 until sources.lastIndex) {
            actions.add(getString(R.string.catalog_move_down) to { moveSource(s, +1) })
        }
        actions.add(getString(R.string.catalog_remove) to { confirmRemoveSource(s) })
        MaterialAlertDialogBuilder(act)
            .setTitle(s.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun moveSource(s: OpdsPrefs.Source, delta: Int) {
        OpdsPrefs.move(act, s.url, delta)
        renderTop()
        toast(getString(R.string.catalog_moved))
    }

    /** Проверка, отвечает ли адрес каталога; результат озвучиваем. */
    private fun checkSource(s: OpdsPrefs.Source) {
        toast(getString(R.string.catalog_checking, s.name))
        Thread {
            val err = OpdsNet.check(act, s.url)
            runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.catalog_check_ok, s.name))
                } else {
                    toast(getString(R.string.catalog_check_fail, s.name, err))
                }
            }
        }.start()
    }

    // ---------------- Вход в библиотеку (#20, msg4240) ----------------

    /** Окошко входа: имя пользователя и пароль. Зовём, когда сервер ответил 401
     *  («Моя полка» flibusta) или когда человек сам выбрал «Войти» в меню.
     *
     *  Пароль уходит библиотеке заголовком Basic и остаётся в приватном хранилище
     *  приложения — в diag.log и в Telegram он не попадает. После входа повторяем
     *  ровно то действие, которое упёрлось в 401: [onDone] — обычно повторная
     *  загрузка той же страницы каталога. Если человек закрыл окошко, не входя
     *  (кнопка «Отмена» или «назад»), зовём [onCancel]. */
    private fun askLogin(
        url: String,
        wrong: Boolean = false,
        onDone: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        // Нажал «Войти» — считаем, что человек в окошке разобрался: даже если
        // логин пустой, «отменой» это не считаем.
        var handled = false
        val host = OpdsPrefs.hostOf(url)
        OpdsAuth.noteAsked(act, url)
        val user = EditText(act).apply {
            hint = getString(R.string.catalog_login_user)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(OpdsAuth.login(act))
        }
        val pass = EditText(act).apply {
            hint = getString(R.string.catalog_login_pass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // Незрячему полезно уметь вернуться в поле и перечитать набранное:
        // у скрытого пароля скринридер читает «точка», у показанного — буквы.
        val show = CheckBox(act).apply {
            setText(R.string.catalog_login_show)
            setOnCheckedChangeListener { _, on ->
                val variation = if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_PASSWORD
                pass.inputType = InputType.TYPE_CLASS_TEXT or variation
                pass.setSelection(pass.text?.length ?: 0)
            }
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(user, lp().apply { bottomMargin = dp(6) })
            addView(pass, lp().apply { bottomMargin = dp(6) })
            addView(show)
        }
        val builder = MaterialAlertDialogBuilder(act)
            .setTitle(getString(R.string.catalog_login_title, host))
            .setView(box)
            .setPositiveButton(R.string.catalog_login_ok) { _, _ ->
                handled = true
                val u = user.text?.toString()?.trim().orEmpty()
                if (u.isEmpty()) {
                    toast(getString(R.string.catalog_login_empty))
                    return@setPositiveButton
                }
                val p = pass.text?.toString().orEmpty()
                OpdsAuth.save(act, host, u, p)
                // Ни логин, ни тем более пароль в лог не пишем (#20): журнал
                // уходит тестерам, там хватает хоста.
                Diag.log(act, "opds", "вход сохранён для $host")
                toast(getString(R.string.catalog_login_saved))
                onDone()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
        val msg = ArrayList<String>()
        if (wrong) msg.add(getString(R.string.catalog_login_wrong))
        val insecure = OpdsPrefs.sources(act).any { src ->
            src.url.startsWith("http://") &&
                OpdsPrefs.hostOf(src.url).equals(host, ignoreCase = true)
        }
        if (insecure) msg.add(getString(R.string.catalog_login_insecure))
        if (msg.isNotEmpty()) builder.setMessage(msg.joinToString("\n\n"))
        val dlg = builder.create()
        dlg.setOnDismissListener { if (!handled) onCancel() }
        dlg.show()
    }

    /** Выход из библиотеки: забываем логин и пароль. */
    private fun logoutSource(s: OpdsPrefs.Source) {
        OpdsAuth.clear(act)
        Diag.log(act, "opds", "вход забыт: ${OpdsPrefs.hostOf(s.url)}")
        toast(getString(R.string.catalog_logout_done))
        renderTop()
    }

    /** Редактирование каталога: имя и адрес (#53). */
    private fun showEditDialog(s: OpdsPrefs.Source) {
        val name = EditText(act).apply {
            setText(s.name)
            hint = getString(R.string.catalog_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val url = EditText(act).apply {
            setText(s.url)
            hint = getString(R.string.catalog_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(name, lp().apply { bottomMargin = dp(6) })
            addView(url)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.catalog_edit)
            .setView(box)
            .setPositiveButton(R.string.catalog_edit_ok) { _, _ ->
                var u = url.text?.toString()?.trim().orEmpty()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                if (u == "https://" || !u.contains(".") || u.startsWith("https://.")) {
                    toast(getString(R.string.catalog_bad_url))
                    return@setPositiveButton
                }
                val nm = name.text?.toString()?.trim().orEmpty()
                OpdsPrefs.update(act, s.url, nm, u)
                toast(getString(R.string.catalog_edit_saved, nm.ifBlank { OpdsPrefs.hostOf(u) }))
                renderTop()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Подтверждение удаления конкретного каталога (из меню долгого нажатия). */
    private fun confirmRemoveSource(s: OpdsPrefs.Source) {
        MaterialAlertDialogBuilder(act)
            .setMessage(getString(R.string.catalog_remove_ask, s.name))
            .setPositiveButton(R.string.catalog_remove_ok) { _, _ ->
                OpdsPrefs.remove(act, s.url)
                renderTop()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun openSource(s: OpdsPrefs.Source) {
        nav.add(Nv.Feed(s.url, s.name))
        renderTop()
    }

    // ---------------- Лента: открытие, первая страница ----------------

    private fun openFeed(cur: Nv.Feed) {
        val s = sessions.getOrPut(cur.url) { FeedSession(cur.url, cur.title) }
        if (s.loadedOnce) {
            renderFeedContent(s)
            // msg1468: вернулись «назад» из книги/папки — фокус на строку, с
            // которой ушли (focusAfterBack по s.focusUrl); свежий вход в уже
            // открытую ленту — сводка + первая строка (как при первой загрузке).
            if (!focusAfterBack(s)) {
                announceFeed(s)
                focusFeedFirst()
            }
            return
        }
        binding.tvTitle.text = cur.title
        showSearch(false)
        hint(getString(R.string.catalog_loading))
        if (!s.loadingFirst) fetchFirst(s)
    }

    private fun fetchFirst(s: FeedSession) {
        s.loadingFirst = true
        Diag.log(act, "opds", "открываю ленту: ${s.url}")
        Thread {
            val r = runCatching {
                val f = OpdsNet.get(act, s.url, acceptFeed)
                OpdsParser.parse(f.finalUrl, f.bytes)
            }
            runOnUiThread {
                s.loadingFirst = false
                r.onSuccess { feed ->
                    s.title = feed.title
                    s.searchTemplate = feed.searchTemplate
                    s.items.addAll(feed.items)
                    s.nextUrl = feed.nextUrl
                    s.loadedOnce = true
                    Diag.log(
                        act, "opds",
                        "лента ок: \"${feed.title}\", записей ${feed.items.size}" +
                            ", поиск ${feed.searchTemplate != null}" +
                            ", дальше ${feed.nextUrl != null}",
                    )
                    if (isTopFeed(s.url)) {
                        renderFeedContent(s)
                        announceFeed(s)
                        focusFeedFirst()
                        // msg2723/2730: окно открыли долгим нажатием «Каталоги» — ждали
                        // именно эту ленту. Она всё ещё верхняя и поисковая — стартуем
                        // голосовой поиск сами.
                        if (pendingVoiceSearch) {
                            pendingVoiceSearch = false
                            if (s.searchTemplate != null) delayedVoiceSearch(s)
                        }
                    }
                }.onFailure { e ->
                    // 401 (#20): не «ошибка», а просьба войти — спрашиваем логин
                    // и повторяем ту же ленту. Навигация не рвётся: человек
                    // остаётся в том разделе, куда шёл («Моя полка»).
                    if (e is OpdsNeedLogin) {
                        Diag.log(act, "opds", "лента просит вход: ${s.url}")
                        askLogin(
                            e.url,
                            wrong = OpdsAuth.hasFor(act, e.url),
                            onDone = { fetchFirst(s) },
                            onCancel = { failFirst(s, e) },
                        )
                        return@onFailure
                    }
                    failFirst(s, e)
                }
            }
        }.start()
    }

    /** Первая страница ленты не открылась: забываем неудачную сессию и уходим из
     *  раздела (если всё ещё стоим в нём), сказав об этом вслух. Сюда же попадает
     *  отмена входа в библиотеку (#20) — это не «ошибка сети», и текст другой. */
    private fun failFirst(s: FeedSession, e: Throwable?) {
        sessions.remove(s.url)
        pendingVoiceSearch = false
        Diag.log(act, "opds", "лента не открылась: ${s.url} — ${e?.message}")
        if (isTopFeed(s.url)) {
            // Корневая лента не открылась — возврат в корень тоже возвращает
            // фокус на её каталог (msg1468).
            if (nav.size == 1) rootFocusUrl = s.url
            nav.removeAt(nav.size - 1)
            renderTop()
            if (e is OpdsNeedLogin) {
                toast(getString(R.string.catalog_login_cancel))
            } else {
                val msg = (e as? OpdsException)?.message
                    ?: (e?.message ?: getString(R.string.catalog_err_unknown))
                toast(getString(R.string.catalog_load_fail, msg))
            }
        }
    }

    private fun announceFeed(s: FeedSession) {
        val nf = s.items.count { it is OpdsItem.Folder }
        val nb = s.items.count { it is OpdsItem.Book }
        binding.tvTitle.announceForAccessibility(
            getString(R.string.catalog_announce, s.title, nf, nb),
        )
    }

    /** Перерисовать ленту целиком (первый вход или возврат «Назад»). */
    private fun renderFeedContent(s: FeedSession) {
        binding.tvTitle.text = s.title
        showSearch(s.searchTemplate != null)
        val v = content()
        v.removeAllViews()
        currentButtons.clear()
        removePending()
        s.foldersShown = false
        s.booksShown = false

        val folders = s.items.filterIsInstance<OpdsItem.Folder>()
        val books = s.items.filterIsInstance<OpdsItem.Book>()
        if (folders.isNotEmpty()) {
            header(getString(R.string.catalog_sections))
            s.foldersShown = true
            for (f in folders) addFolderRow(f)
        }
        if (books.isNotEmpty()) {
            header(getString(R.string.catalog_books))
            s.booksShown = true
            for (b in books) addBookRow(b)
        }
        if (folders.isEmpty() && books.isEmpty()) {
            hint(getString(R.string.catalog_empty_feed))
        }
        maybeAnnounceBookHint(s)
        applyEnabled()
        finishScrollTo(s.scrollY)
        if (s.nextUrl != null) binding.scroll.post { prefillIfShort(s) }
        // Фокус после перерисовки ставят зовущие: focusAfterBack (возврат из
        // книги/папки) или focusFeedFirst (первая загрузка / свежий вход в кэш).
    }

    /** Вернуть accessibility-фокус на строку (книгу/папку), с которой ушли вниз
     *  и вернулись «Назад» (msg1220). scrollY уже вернул ленту на место; без
     *  явного фокуса TalkBack прыгнул бы к первой строке списка. Пауза 350 мс —
     *  как в focusFeedFirst: мгновенный requestFocus после перерисовки TalkBack
     *  роняет на нижние вкладки (0.3.55). True — якорь был, фокус запланирован;
     *  false — якоря нет, зовущий ставит фокус сам.
     *
     *  L1-каталог (msg1573): ПЕРЕНОС не шлём. Строку-якорь озвучиваем голосом,
     *  а где фокус осядет сам — фиксируем в лог. True — якорь был (озвучен),
     *  false — якоря нет, зовущий озвучивает сводку ленты. */
    private fun focusAfterBack(s: FeedSession): Boolean {
        val url = s.focusUrl ?: return false
        s.focusUrl = null
        TabNav.observeFocusAfter(binding.root, "возврат в ленту ${s.title}")
        binding.scroll.postDelayed({
            for (i in 0 until content().childCount) {
                val v = content().getChildAt(i)
                if (v.tag == url && v.isFocusable && v.isShown) {
                    v.announceForAccessibility(v.contentDescription ?: "")
                    return@postDelayed
                }
            }
            // Строки нет (лента перестроилась) — сводку озвучит зовущий (announceFeed).
        }, 350)
        return true
    }

    /** msg1434/1438 (вариант А): после первой загрузки ленты ставим
     *  accessibility-фокус на ПЕРВУЮ строку списка. Без этого TalkBack, когда
     *  строка-папка, на которой стояли, исчезает при перерисовке контента, сам
     *  роняет фокус куда попадёт — часто на нижние вкладки («Библиотека,
     *  вкладка»). Пауза 350 мс — чтобы сводка announceFeed успела прозвучать
     *  целиком, фокус уходит следом, не перебивая.
     *
     *  L1-каталог (msg1573): ПЕРЕНОС не шлём — сводку ленты уже озвучил зовущий
     *  (announceFeed), здесь только наблюдаем, где Jieshuo осядет сам. */
    private fun focusFeedFirst() {
        TabNav.observeFocusAfter(binding.root, "вход в ленту")
    }

    /** Подсказку о жестах (двойной тап / долгое нажатие) озвучиваем один раз. */
    private fun maybeAnnounceBookHint(s: FeedSession) {
        if (s.hintAnnounced) return
        if (s.items.none { it is OpdsItem.Book }) return
        s.hintAnnounced = true
        binding.tvTitle.announceForAccessibility(getString(R.string.catalog_gesture_hint))
    }

    // ---------------- Строки каталога ----------------

    private fun addFolderRow(f: OpdsItem.Folder) {
        val label = if (f.note.isNullOrBlank()) f.title else f.title + "\n" + f.note
        val tv = addRowText(label) {
            stashScroll()
            currentFeedSession()?.focusUrl = f.url
            nav.add(Nv.Feed(f.url, f.title))
            renderTop()
        }
        // Метка-адрес: по ней после возврата находим строку и возвращаем фокус.
        tv.tag = f.url
    }

    /** Строка книги в списке — это НЕ кнопка (скринридер не говорит «кнопка»):
     *  двойной тап открывает страницу книги, долгое нажатие скачивает формат по
     *  умолчанию и подтверждается короткой вибрацией. */
    private fun addBookRow(b: OpdsItem.Book) {
        val authorsLine = b.authors.filter { it.isNotBlank() }.joinToString(", ")
        val label = if (authorsLine.isEmpty()) b.title else b.title + "\n" + authorsLine
        val tv = addRowText(label) {
            openBook(b, currentFeedSession())
        }
        // Метка-адрес: по ней после возврата из книги находим строку и возвращаем
        // на неё фокус (msg1220).
        tv.tag = b.url
        tv.setOnLongClickListener {
            vibrate(70)
            downloadDefault(b)
            true
        }
    }

    /** Строка-элемент без роли «кнопки»: клик — основное действие. Возвращает
     *  TextView, чтобы вызывающий мог навесить долгое нажатие.
     *
     *  msg4173: снизу строки — линия-разделитель, вертикальные отступы 8dp → 11dp.
     *  Так строки списка (книги, папки, сами каталоги) не сливаются в одно полотно.
     *  Это просьба слабовидящего читателя из отзыва, поэтому и линия светлее обычных
     *  линий приложения, и воздух между строками больше прежнего. */
    private fun addRowText(
        label: String,
        cd: String? = null,
        onClick: () -> Unit,
    ): TextView {
        val tv = TextView(act).apply {
            text = label
            contentDescription = cd
            textSize = 17f
            setTextColor(0xFFE8EAED.toInt())
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setBackgroundResource(R.drawable.list_row_divider)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        // У TextView нет роли «кнопка» — TalkBack просто прочитает текст (или cd).
        content().addView(tv, lp().apply { topMargin = dp(2); bottomMargin = dp(2) })
        return tv
    }

    // ---------------- Автодогрузка следующих страниц ----------------

    private fun setupAutoLoad() {
        val sv = binding.scroll
        sv.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            currentFeedSession()?.scrollY = scrollY
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            if (sv.scrollY + sv.height >= child.height - dp(160)) maybeLoadMore()
        }
        sv.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Пока впереди есть страницы — TalkBack не должен говорить «конец списка».
                val s = currentFeedSession()
                if (s != null && s.loadedOnce && (s.nextUrl != null || s.loadingMore)) {
                    info.isScrollable = true
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val handled = super.performAccessibilityAction(host, action, args)
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && !host.canScrollVertically(1)) {
                    val s = currentFeedSession()
                    if (s != null && s.loadedOnce && (s.nextUrl != null || s.loadingMore)) {
                        maybeLoadMore()
                        return true
                    }
                }
                return handled
            }
        }
    }

    private fun maybeLoadMore() {
        val s = currentFeedSession() ?: return
        if (!s.loadedOnce || s.nextUrl == null || s.loadingMore || s.failMsg != null) return
        loadNext(s)
    }

    /** Если страница-первая короткая и каталог ещё не заполнил экран — догружаем
     *  вперёд, чтобы под самым концом не было пауз. */
    private fun prefillIfShort(s: FeedSession) {
        val sv = binding.scroll
        val child = sv.getChildAt(0) ?: return
        if (child.height <= sv.height + dp(40) && s.nextUrl != null && s.failMsg == null) {
            maybeLoadMore()
        }
    }

    private fun loadNext(s: FeedSession) {
        val url = s.nextUrl ?: return
        s.loadingMore = true
        removePending()
        pendingRow = addHintRow(getString(R.string.catalog_more_loading))
        Diag.log(act, "opds", "догружаю страницу: $url")
        Thread {
            val r = runCatching {
                val f = OpdsNet.get(act, url, acceptFeed)
                OpdsParser.parse(f.finalUrl, f.bytes)
            }
            runOnUiThread {
                s.loadingMore = false
                removePending()
                r.onSuccess { feed ->
                    val known = s.items.mapNotNull { item ->
                        (item as? OpdsItem.Folder)?.url ?: (item as? OpdsItem.Book)?.url
                    }.toHashSet()
                    val fresh = feed.items.filter { item ->
                        val u = (item as? OpdsItem.Folder)?.url ?: (item as? OpdsItem.Book)?.url
                        u == null || !known.contains(u)
                    }
                    s.nextUrl = feed.nextUrl
                    if (feed.searchTemplate != null) s.searchTemplate = feed.searchTemplate
                    s.items.addAll(fresh)
                    appendLoaded(s, fresh)
                    Diag.log(act, "opds", "догружено: ${fresh.size}, дальше ${feed.nextUrl != null}")
                    if (s.nextUrl == null) {
                        val nf = s.items.count { it is OpdsItem.Folder }
                        val nb = s.items.count { it is OpdsItem.Book }
                        binding.tvTitle.announceForAccessibility(
                            getString(R.string.catalog_end, nf, nb),
                        )
                    } else {
                        binding.scroll.post { prefillIfShort(s) }
                    }
                }.onFailure { e ->
                    // Вход кончился посреди списка (#20) — спрашиваем и догружаем
                    // ту же страницу, строку «повторить» не показываем.
                    if (e is OpdsNeedLogin) {
                        Diag.log(act, "opds", "страница просит вход: $url")
                        askLogin(e.url, wrong = OpdsAuth.hasFor(act, e.url)) { loadNext(s) }
                        return@onFailure
                    }
                    s.failMsg = (e as? OpdsException)?.message
                        ?: (e.message ?: getString(R.string.catalog_err_unknown))
                    Diag.log(act, "opds", "страница не догрузилась: $url — ${s.failMsg}")
                    addRetryRow(s)
                }
            }
        }.start()
    }

    /** Дописать пришедшую страницу в конец уже открытой ленты. */
    private fun appendLoaded(s: FeedSession, fresh: List<OpdsItem>) {
        val folders = fresh.filterIsInstance<OpdsItem.Folder>()
        val books = fresh.filterIsInstance<OpdsItem.Book>()
        if (folders.isNotEmpty()) {
            if (!s.foldersShown) {
                header(getString(R.string.catalog_sections))
                s.foldersShown = true
            }
            for (f in folders) addFolderRow(f)
        }
        if (books.isNotEmpty()) {
            if (!s.booksShown) {
                header(getString(R.string.catalog_books))
                s.booksShown = true
            }
            for (b in books) addBookRow(b)
        }
        maybeAnnounceBookHint(s)
        applyEnabled()
    }

    private fun addRetryRow(s: FeedSession) {
        val b = Button(act).apply {
            text = getString(R.string.catalog_more_fail, s.failMsg.orEmpty()) +
                "\n" + getString(R.string.catalog_more_retry)
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                s.failMsg = null
                loadNext(s)
            }
        }
        content().addView(b, lp().apply { topMargin = dp(2); bottomMargin = dp(2) })
        currentButtons.add(b)
        pendingRow = b
    }

    // ---------------- Страница книги ----------------

    private fun openBook(b: OpdsItem.Book, s: FeedSession?) {
        stashScroll()
        // Запомнить, с какой строки ушли, чтобы вернуться на неё после «Назад».
        s?.focusUrl = b.url
        nav.add(Nv.Book(b, s?.title ?: ""))
        renderTop()
    }

    /** [announce]=false — для молчаливой перерисовки после скачивания: страница
     *  та же, озвучка уже была (тост «Готово»). */
    private fun renderBookPage(cur: Nv.Book, announce: Boolean = true) {
        showSearch(false)
        binding.tvTitle.text = cur.item.title
        val v = content()
        v.removeAllViews()
        currentButtons.clear()
        removePending()
        // msg1264: на новой странице ссылка на кнопку прогресса сбрасывается —
        // заново ставится ниже, если страница показывает кнопку «Скачать».
        dlButton = null

        val b = cur.item
        val authorsLine = b.authors.filter { it.isNotBlank() }.joinToString(", ")
        val record = downloadedRecord(b)

        // Уже скачана — кнопка «Открыть в читалке» вместо «Скачать» (#41).
        if (record != null) {
            addButton(
                getString(R.string.catalog_book_open),
                getString(R.string.catalog_book_open_cd),
            ) {
                // msg1245: запись перечитываем в момент нажатия, а не в момент
                // отрисовки страницы. Страница после возврата из читалки НЕ
                // перерисовывается и держит запись с первого показа (до чтения,
                // глава 0) — повторный вход по ней прыгал бы в начало.
                val fresh = downloadedRecord(b)
                if (fresh != null) openInReader(fresh) else renderTop()
            }
        } else {
            // Большая кнопка скачивания (формат из настроек), на всю ширину — единственная
            // на странице (msg1996: маленький выбор формата убран, живёт в ⋮-меню окна).
            val dlKey = dlFmtKey()
            // msg4665: у книги ровно один формат — выбирать не из чего, берём его.
            // Иначе книга с одним PDF предлагала бы «Скачать FB2» и гнала в список.
            val fmt = b.downloads.firstOrNull { it.ext == ".$dlKey" } ?: b.downloads.singleOrNull()
            val fmtLabel = fmt?.label ?: opdsFormatLabel(dlKey)
            if (b.downloads.isEmpty()) {
                addReadableText(getString(R.string.catalog_no_formats))
            } else {
                val primary = Button(act).apply {
                    text = getString(R.string.catalog_dl_with_fmt, fmtLabel)
                    contentDescription = getString(R.string.catalog_dl_primary_cd)
                    textSize = 17f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        if (fmt != null) {
                            downloadFormat(b, fmt)
                        } else {
                            // Формата по умолчанию нет — сообщаем и показываем список (#45).
                            vibrate(60)
                            val avail = b.downloads.map { it.label }.joinToString(", ")
                            toast(getString(R.string.catalog_no_fmt, fmtLabel, avail))
                            showFormatDialog(b, getString(R.string.catalog_pick_fmt_dialog))
                        }
                    }
                }
                content().addView(primary, lp().apply { topMargin = dp(2); bottomMargin = dp(2) })
                currentButtons.add(primary)
                // msg1264: запоминаем большую кнопку — на неё пойдёт живой прогресс.
                // Если книга уже качается (вошёл на страницу посреди загрузки) —
                // сразу показываем текущую подпись вместо «Скачать».
                dlButton = primary
                if (downloading && b.url == dlBookUrl) {
                    primary.text = dlProgressText()
                    primary.contentDescription = primary.text
                }
            }
        }
        // msg1990/1993/1996: со страницы книги убраны маленькая кнопка выбора
        // формата и отдельная «Настройки скачивания» — Сергею на виду нужна одна
        // большая кнопка «Скачать», остальное живёт в ⋮-меню окна (там «Настройки
        // скачивания» уже есть, формат меняется внутри них). Формат по умолчанию
        // недоступен — большая кнопка сама покажет список доступных (#45).

        if (authorsLine.isNotEmpty()) {
            addReadableText(getString(R.string.catalog_info_authors, authorsLine))
        }
        val genresLine = b.genres.filter { it.isNotBlank() }.joinToString(", ")
        if (genresLine.isNotEmpty()) {
            addReadableText(getString(R.string.catalog_info_genres, genresLine))
        }
        if (cur.sourceTitle.isNotBlank()) {
            addReadableText(getString(R.string.catalog_info_source, cur.sourceTitle))
        }
        if (!b.annotation.isNullOrBlank() || !b.annotationHtml.isNullOrBlank()) {
            addAnnotation(b.annotationHtml, b.annotation)
        }
        addBottomLinks(b)
        applyEnabled()
        finishScrollTo(0)
        if (announce) {
            binding.tvTitle.announceForAccessibility(
                getString(R.string.catalog_book_announce, b.title, authorsLine),
            )
        }
    }

    /** Текстовый блок на странице книги: фокусируемый, чтобы TalkBack его читал. */
    private fun addReadableText(text: String) {
        content().addView(TextView(act).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(0f, 1.15f)
            isFocusable = true
            setPadding(dp(4), dp(4), dp(4), dp(10))
        }, lp())
    }

    /** Аннотация книги. Если каталог отдал HTML со ссылками — оставляем ссылки
     *  кликабельными: TalkBack выделяет их как отдельные элементы, двойной тап
     *  открывает браузер (#40). Без ссылок — обычный читаемый текст. */
    private fun addAnnotation(html: String?, plain: String?) {
        header(getString(R.string.catalog_annotation_title))
        val h = html?.takeIf { it.isNotBlank() }
        if (h != null) {
            val spanned = runCatching {
                Html.fromHtml(h, Html.FROM_HTML_MODE_LEGACY)
            }.getOrNull()
            if (spanned != null && spanned.isNotBlank()) {
                content().addView(TextView(act).apply {
                    text = spanned
                    textSize = 16f
                    setTextColor(0xFFE8EAED.toInt())
                    setLineSpacing(0f, 1.2f)
                    movementMethod = LinkMovementMethod.getInstance()
                    isFocusable = true
                    setPadding(dp(4), dp(4), dp(4), dp(10))
                }, lp())
                return
            }
        }
        plain?.takeIf { it.isNotBlank() }?.let { addReadableText(it) }
    }

    /** Блок ссылок внизу страницы книги (#42): «Все книги автора», «Все книги
     *  серии», «Книга на сайте». Первые два — разделы каталога, третий — браузер. */
    private fun addBottomLinks(b: OpdsItem.Book) {
        val links = ArrayList<Pair<String, () -> Unit>>()
        b.authorFeed?.let { r ->
            val label = r.label.ifBlank { getString(R.string.catalog_author_link) }
            links.add(label to {
                nav.add(Nv.Feed(r.url, label))
                renderTop()
            })
        }
        b.seriesFeed?.let { r ->
            val label = r.label.ifBlank { getString(R.string.catalog_series_link) }
            links.add(label to {
                nav.add(Nv.Feed(r.url, label))
                renderTop()
            })
        }
        b.siteUrl?.takeIf { it.isNotBlank() }?.let { url ->
            links.add(getString(R.string.catalog_site_link) to { openBrowser(url) })
        }
        if (links.isEmpty()) return
        header(getString(R.string.catalog_links_title))
        for ((label, onClick) in links) {
            addButton(label, null, onClick)
        }
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast(getString(R.string.catalog_link_fail, url))
        }
    }

    // ---------------- Панель поиска (msg3214) ----------------

    /** msg3214: вместо диалога по центру — панель под шапкой, как в читалке.
     *  Показывается кнопкой 🔍 шапки, когда верхняя лента поисковая. */
    private fun searchPanelOpen(): Boolean =
        binding.searchPanel.visibility == View.VISIBLE

    /** Открыть панель поиска. focusField — сразу поставить фокус в поле и поднять
     *  клавиатуру (обычный вход по 🔍); false — панель без клавиатуры. */
    private fun openSearchPanel(focusField: Boolean) {
        binding.searchPanel.visibility = View.VISIBLE
        if (focusField) {
            binding.etSearch.requestFocus()
            showSoftKeyboard()
        }
    }

    private fun closeSearchPanel() {
        binding.searchPanel.visibility = View.GONE
        hideKeyboard()
    }

    /** 🔍 в шапке: открыть панель (с полем в фокусе) или закрыть открытую. */
    private fun toggleSearchPanel() {
        val s = currentFeedSession()?.takeIf { it.searchTemplate != null } ?: return
        if (searchPanelOpen()) closeSearchPanel()
        else {
            binding.etSearch.setText("")
            openSearchPanel(focusField = true)
        }
    }

    private fun showSoftKeyboard() {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearch, 0)
    }

    private fun hideKeyboard() {
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    /** «Найти» по тексту из поля. Пусто — подсказка, поиск не начинаем. */
    private fun searchFromField() {
        val s = currentFeedSession()?.takeIf { it.searchTemplate != null } ?: return
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            binding.etSearch.announceForAccessibility(getString(R.string.catalog_search_empty))
            binding.etSearch.requestFocus()
            return
        }
        runSearch(s, q)
    }

    /** Прогнать запрос [q] по шаблону поиска ленты [s] — общий путь для
     *  панели поиска и голосового ввода. Панель перед переходом прячем. */
    private fun runSearch(s: FeedSession, q: String) {
        val template = s.searchTemplate ?: return
        closeSearchPanel()
        val url = template.replace("{searchTerms}", URLEncoder.encode(q, "UTF-8"))
        stashScroll()
        nav.add(Nv.Feed(url, getString(R.string.catalog_search) + ": " + q))
        renderTop()
    }

    /** Голосовой поиск (msg1421): системное распознавание речи, как в читалке
     *  (MainActivity.startVoiceSearch). Перед распознаванием тихо ставим
     *  фоновое чтение на паузу — TTS в микрофоне испортил бы распознавание. */
    private fun startVoiceSearch(s: FeedSession) {
        MediaSessionService.pause()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.catalog_search_voice_prompt))
        }
        try {
            act.launchForResult(intent) { r -> handleVoiceResult(r) }
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.search_voice_unavailable))
        }
    }

    // ---------------- Общие блоки контента ----------------

    private fun header(text: String) {
        content().addView(TextView(act).apply {
            this.text = text
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF8AB4F8.toInt())
            setPadding(dp(4), dp(16), dp(4), dp(4))
        })
    }

    private fun hint(text: String) {
        content().addView(TextView(act).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(10))
        })
    }

    private fun addHintRow(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF9AA0A6.toInt())
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }.also { content().addView(it, lp()) }

    private fun addButton(label: String, cd: String? = null, onClick: () -> Unit): Button {
        val b = Button(act).apply {
            text = label
            contentDescription = cd
            textSize = 17f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
        content().addView(b, lp().apply {
            topMargin = dp(2)
            bottomMargin = dp(2)
        })
        currentButtons.add(b)
        return b
    }

    // ---------------- Диалоги управления каталогами ----------------

    private fun showAddDialog() {
        val input = EditText(act).apply {
            hint = getString(R.string.catalog_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.catalog_url_dialog)
            .setView(box)
            .setPositiveButton(R.string.catalog_add_ok) { _, _ ->
                var url = input.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                if (url == "https://" || !url.contains(".") || url.startsWith("https://.")) {
                    toast(getString(R.string.catalog_bad_url))
                    return@setPositiveButton
                }
                val name = OpdsPrefs.hostOf(url)
                OpdsPrefs.add(act, name, url)
                toast(getString(R.string.catalog_added, name))
                renderTop()
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    // ---------------- Настройки скачивания (кнопка ⚙) ----------------

    /** Диалог: формат по умолчанию и папка, куда качать. Открывается из шапки
     *  каталога, чтобы не уходить ради этого в общие настройки. */
    private fun openDlSettings() {
        dlDialog?.dismiss()
        val fmtBtn = Button(act).apply {
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                dlDialog?.dismiss()
                openDlFormatDialog()
            }
        }
        val dirBtn = Button(act).apply {
            textSize = 16f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                dlDialog?.dismiss()
                openDlDirDialog()
            }
        }
        fun refresh() {
            fmtBtn.text = getString(R.string.dl_format_title) + ": " + opdsFormatLabel(dlFmtKey())
            val tree = dlTree()
            dirBtn.text = getString(R.string.dl_folder_title) + ": " +
                if (tree == null) getString(R.string.dl_folder_none) else treeLabel(Uri.parse(tree))
        }
        refresh()
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(fmtBtn, lp().apply { bottomMargin = dp(4) })
            addView(dirBtn)
        }
        dlDialog = MaterialAlertDialogBuilder(act)
            .setTitle(R.string.catalog_dl_settings_title)
            .setView(body)
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openDlFormatDialog() {
        dlDialog = MaterialAlertDialogBuilder(act)
            .setTitle(R.string.dl_format_title)
            .setSingleChoiceItems(
                OPDS_READABLE_FORMATS.map { it.second }.toTypedArray(),
                OPDS_READABLE_FORMATS.indexOfFirst { it.first == dlFmtKey() }.coerceAtLeast(0),
            ) { d, which ->
                prefs.edit().putString(
                    OpdsPrefs.KEY_DL_FMT,
                    OPDS_READABLE_FORMATS[which].first,
                ).apply()
                d.dismiss()
                // На странице книги большая кнопка показывает формат — обновляем её.
                val cur = nav.lastOrNull()
                if (cur is Nv.Book) renderBookPage(cur, announce = false)
                openDlSettings()
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openDlDirDialog() {
        if (dlTree() == null) {
            openDlDirPicker()
            return
        }
        dlDialog = MaterialAlertDialogBuilder(act)
            .setTitle(R.string.dl_folder_title)
            .setItems(
                arrayOf(
                    getString(R.string.dl_folder_change),
                    getString(R.string.dl_folder_to_app),
                ),
            ) { _, which ->
                if (which == 0) {
                    openDlDirPicker()
                } else {
                    prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                    toast(getString(R.string.dl_folder_internal))
                    openDlSettings()
                }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    /** Человеческое имя папки из tree-uri (например "primary:Books" -> "Books"). */
    private fun treeLabel(tree: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
        val raw = if (docId.contains(':')) docId.substringAfter(':') else docId
        return raw.substringAfterLast('/').ifBlank { raw }
    }

    // ---------------- Скачивание книги ----------------

    /** Формат по умолчанию из настроек ("fb2"/"epub"/"txt"/"pdf"). */
    private fun dlFmtKey(): String {
        val key = prefs.getString(OpdsPrefs.KEY_DL_FMT, OpdsPrefs.DEFAULT_DL_FMT)
            ?: OpdsPrefs.DEFAULT_DL_FMT
        return key.lowercase().takeIf { k -> OPDS_READABLE_FORMATS.any { it.first == k } }
            ?: OpdsPrefs.DEFAULT_DL_FMT
    }

    /** Куда качать (из настроек): tree-uri папки или null = во внутреннюю папку. */
    private fun dlTree(): String? = prefs.getString(OpdsPrefs.KEY_DL_DIR, null)

    /** Скачанные из каталога записи: лежат во внутренней папке или в выбранной. */
    private fun downloadedRecords(): List<BookRecord> {
        val dir = Uri.fromFile(File(filesDir, "books")).toString()
        val tree = dlTree()
        // Куда могла лечь скачанная книга (0.3.48): внутренняя папка приложения,
        // SAF-tree, document-пространство дерева или реальный путь папки через
        // All-files (MixPlorer не умеет createDocument — файл пишется File-путем).
        // Без реального пути кнопка «Скачать» не менялась бы на «Открыть» (msg1224).
        val prefixes = ArrayList<String>()
        prefixes.add(dir)
        if (tree != null) {
            prefixes.add(tree + "/document/")
            prefixes.add(tree.replaceFirst("/tree/", "/document/"))
            if (AllFiles.granted(act)) {
                AllFiles.resolveDir(Uri.parse(tree))?.let { d ->
                    prefixes.add(Uri.fromFile(d).toString() + "/")
                }
            }
        }
        return BookStore.all(act).filter { rec ->
            prefixes.any { rec.uri.startsWith(it) }
        }
    }

    /** Скачана ли уже эта книга (для кнопки «Открыть в читалке» #41).
     *  msg1333/1336: раньше искали по названию+автору среди записей папки
     *  скачивания. Но при первом открытии в читалке MainActivity переписывает
     *  запись файловыми названием/автором (registerOpen) — и карточка после
     *  возврата переставала узнавать скачанное: кнопка снова звала «Скачать»,
     *  а повторное скачивание плодило копии «… (1)». Теперь первым делом ищем
     *  по прямой ссылке sourceUrl (её запись не теряет) по всей библиотеке —
     *  кнопка остаётся «Открыть в читалке» после любого открытия книги. */
    private fun downloadedRecord(b: OpdsItem.Book): BookRecord? {
        // Все адреса скачивания книги из ленты: запись каталога хранит тот из
        // них, по которому реально качали (sourceUrl, ставится при скачивании
        // #100). Совпадение по ссылке не зависит от переписанных названия/автора.
        val feedUrls = ArrayList<String>()
        if (b.url.isNotBlank()) feedUrls.add(b.url)
        b.downloads.forEach { if (it.url.isNotBlank()) feedUrls.add(it.url) }
        val bySource = BookStore.all(act).firstOrNull { rec ->
            rec.sourceUrl != null && feedUrls.contains(rec.sourceUrl)
        }
        if (bySource != null) return bySource
        // Запасной путь для записей без sourceUrl (докачано до #100): название
        // и автор по папке скачивания, как раньше.
        return downloadedRecords().firstOrNull { rec ->
            rec.title != null && rec.title == b.title &&
                (b.author == null || rec.author == null || rec.author == b.author)
        }
    }

    /** Долгое нажатие на строку списка: если книга скачана — открываем, иначе
     *  скачиваем формат по умолчанию (вибрацию уже сделал вызывающий). */
    private fun downloadDefault(b: OpdsItem.Book) {
        val rec = downloadedRecord(b)
        if (rec != null) {
            toast(getString(R.string.catalog_open_existing, b.title))
            openInReader(rec)
            return
        }
        val key = dlFmtKey()
        // msg4665: единственный формат книги берём как есть, без выбора.
        val fmt = b.downloads.firstOrNull { it.ext == ".$key" } ?: b.downloads.singleOrNull()
        if (fmt != null) {
            downloadFormat(b, fmt)
        } else {
            val avail = b.downloads.map { it.label }.joinToString(", ")
            toast(getString(R.string.catalog_no_fmt, opdsFormatLabel(key), avail))
            showFormatDialog(b, getString(R.string.catalog_pick_fmt_dialog))
        }
    }

    private fun showFormatDialog(b: OpdsItem.Book, title: String?) {
        val list = b.downloads
        if (list.isEmpty()) {
            toast(getString(R.string.catalog_no_formats))
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(title ?: getString(R.string.catalog_fmt_dialog))
            .setItems(list.map { it.label }.toTypedArray()) { _, which ->
                vibrate(50)
                downloadFormat(b, list[which])
            }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    private fun downloadFormat(b: OpdsItem.Book, fmt: OpdsFormat) {
        if (downloading) {
            toast(getString(R.string.catalog_dl_busy))
            return
        }
        val rec = downloadedRecord(b)
        if (rec != null) {
            toast(getString(R.string.catalog_already, b.title))
            openInReader(rec)
            return
        }
        downloading = true
        dlBookUrl = b.url
        dlPct = -1
        dlAnnounced = 0
        applyEnabled()
        // msg1264: подпись «Скачивание…» сразу, не дожидаясь первого байта.
        val curNow = nav.lastOrNull()
        if (curNow is Nv.Book && curNow.item.url == b.url) {
            dlButton?.let { btn ->
                btn.text = getString(R.string.catalog_dl_progress)
                btn.contentDescription = btn.text
            }
        }
        toast(getString(R.string.catalog_dl_start, b.title))
        Diag.log(act, "opds", "скачиваю \"${b.title}\" формат ${fmt.label}: ${fmt.url}")
        Thread {
            dlFallback = false
            var lastPct = -2
            val res = runCatching {
                // msg1264: тело читаем кусками и шлём прогресс. Дедуп по проценту
                // на фоне: publishProgress уходит в UI только когда процент сменился.
                val fetch = OpdsNet.fetchProgress(act, fmt.url, "*/*") { read, total ->
                    val pct = if (total > 0) {
                        (read * 100 / total).toInt().coerceIn(0, 100)
                    } else {
                        -1 // сервер размер не прислал — остаётся «Скачивание…»
                    }
                    if (pct != lastPct) {
                        lastPct = pct
                        publishProgress(pct)
                    }
                }
                writeDownloaded(b, fmt, fetch.bytes)
            }
            val uriStr = res.getOrNull()?.first
            val name = res.getOrNull()?.second
            val err = res.exceptionOrNull()
            runOnUiThread {
                downloading = false
                applyEnabled()
                if (uriStr != null && name != null) {
                    BookStore.upsert(act, BookRecord(
                        uri = uriStr,
                        name = name,
                        title = b.title,
                        author = b.author,
                        // Прямая ссылка скачивания: по ней при восстановлении из
                        // резервной копии можно доскачать потерянный файл (#100).
                        sourceUrl = fmt.url,
                        addedAt = System.currentTimeMillis(),
                    ))
                    Diag.log(act, "opds", "книга скачана: \"${b.title}\" → $uriStr")
                    // 0.3.47: папка оказалась нерабочей — предупреждаем, что файл
                    // ушёл во внутреннюю память, а не в выбранную папку.
                    if (dlFallback) toast(getString(R.string.dl_folder_fallback))
                    else toast(getString(R.string.catalog_dl_done, b.title))
                    Vibra.confirm(act)
                    // Кнопка «Скачать» сразу меняется на «Открыть в читалке», если
                    // мы всё ещё на странице этой книги (#41). Перерисовка молчит —
                    // озвучка страницы уже была, тост «Готово» прозвучал.
                    val cur = nav.lastOrNull()
                    if (cur is Nv.Book && cur.item.url == b.url) {
                        renderBookPage(cur, announce = false)
                    }
                } else if (err is OpdsNeedLogin) {
                    // Книга отдаётся только после входа (#20): спрашиваем логин и
                    // повторяем это же скачивание — человек уже нажал «Скачать».
                    Diag.log(act, "opds", "книга просит вход: \"${b.title}\"")
                    askLogin(err.url, wrong = OpdsAuth.hasFor(act, err.url)) {
                        downloadFormat(b, fmt)
                    }
                } else {
                    val msg = (err as? OpdsException)?.message
                        ?: err?.message
                        ?: getString(R.string.catalog_err_unknown)
                    Diag.log(act, "opds", "книга не скачалась: \"${b.title}\" — $msg")
                    toast(getString(R.string.catalog_dl_fail, msg))
                    Vibra.error(act)
                    // msg1264: страница не перерисовывается (сохраняем прокрутку и
                    // фокус) — возвращаем кнопке обычную подпись «Скачать %формат%».
                    val cur = nav.lastOrNull()
                    if (cur is Nv.Book && cur.item.url == b.url) {
                        dlButton?.let { btn ->
                            val key = dlFmtKey()
                            val label = opdsFormatLabel(key)
                            btn.text = getString(R.string.catalog_dl_with_fmt, label)
                            btn.contentDescription = getString(
                                R.string.catalog_dl_primary_cd
                            )
                        }
                    }
                }
                resetDlState()
            }
        }.start()
    }

    /** Записать файл книги: во внутреннюю папку или в выбранную SAF-папку (#44).
     *  Возвращает (uri записи, имя файла). */
    private fun writeDownloaded(
        b: OpdsItem.Book,
        fmt: OpdsFormat,
        bytes: ByteArray,
    ): Pair<String, String> {
        val tree = dlTree()
        val base = safeName(b.title)
        return if (tree == null) {
            writeInternal(base, fmt.ext, bytes)
        } else {
            try {
                writeToTree(Uri.parse(tree), base, fmt.ext, bytes)
            } catch (e: Exception) {
                // 0.3.47 (msg1165/1167): выбранная SAF-папка не умеет
                // createDocument (сторонние проводники вроде MixPlorer). Раньше
                // каждая загрузка сюда падала с непонятным «книга не скачалась».
                // 0.3.48 (msg1170/1172): сначала пробуем записать реальным путём
                // через «Доступ ко всем файлам» — папка MixPlorer лежит на диске,
                // и сам проводник пишет туда именно File-операцией.
                Diag.log(act, "opds", "SAF-папка не пишет (createDocument): ${e.message}")
                val real = runCatching {
                    val t = Uri.parse(tree)
                    if (!AllFiles.granted(act)) return@runCatching null
                    val dir = AllFiles.resolveDir(t) ?: return@runCatching null
                    if (!dir.isDirectory) dir.mkdirs()
                    val f = uniqueFile(dir, base, fmt.ext)
                    f.writeBytes(bytes)
                    f
                }.getOrNull()
                if (real != null) {
                    Diag.log(act, "opds", "записал реальным путём: ${real.absolutePath}")
                    return Uri.fromFile(real).toString() to real.name
                }
                // Реальный путь не вышел — внутренняя память (как 0.3.47).
                // Сбрасываем нерабочую папку и предупреждаем пользователя.
                Diag.log(act, "opds", "папка не пишет файлы, качаю во внутреннюю: ${e.message}")
                prefs.edit().remove(OpdsPrefs.KEY_DL_DIR).apply()
                dlFallback = true
                writeInternal(base, fmt.ext, bytes)
            }
        }
    }

    /** Внутренняя папка приложения (всегда работает). */
    private fun writeInternal(base: String, ext: String, bytes: ByteArray): Pair<String, String> {
        val dir = File(filesDir, "books").apply { mkdirs() }
        val file = uniqueFile(dir, base, ext)
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString() to file.name
    }

    private fun writeToTree(
        tree: Uri,
        base: String,
        ext: String,
        bytes: ByteArray,
    ): Pair<String, String> {
        val doc = DocumentsContract.createDocument(
            contentResolver, tree, mimeForExt(ext), base + ext,
        ) ?: throw OpdsException("не удалось создать файл в выбранной папке")
        val out = contentResolver.openOutputStream(doc)
            ?: throw OpdsException("не удалось записать файл в выбранную папку")
        out.use { it.write(bytes) }
        return doc.toString() to (queryDisplayName(doc) ?: base + ext)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        ".epub" -> "application/epub+zip"
        ".fb2" -> "application/x-fictionbook+xml"
        ".txt" -> "text/plain"
        ".pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, base + ext)
        var n = 1
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    private fun safeName(s: String): String {
        val clean = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().trim('.').take(90)
        return clean.ifBlank { "book" }
    }

    /** Открыть уже скачанную книгу в читалке (как из библиотеки). */
    private fun openInReader(rec: BookRecord) {
        if (rec.status == BookRecord.STATUS_NEW) {
            BookStore.upsert(act, rec.copy(
                status = BookRecord.STATUS_READING,
                lastOpenedAt = System.currentTimeMillis(),
            ))
        }
        val i = Intent(act, MainActivity::class.java)
        i.putExtra(MainActivity.EXTRA_URI, rec.uri)
        i.putExtra(MainActivity.EXTRA_CHAPTER, rec.chapter)
        i.putExtra(MainActivity.EXTRA_SENTENCE, rec.sentence)
        // «Назад» из книги вернёт сюда, на этот экран каталога (#52).
        i.putExtra(MainActivity.EXTRA_FROM_CATALOG, true)
        startActivity(i)
    }

    /** Короткая вибрация — подтверждение долгого нажатия / выбора формата. */
    private fun vibrate(ms: Long) {
        val v = act.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }
}

/** msg2445: запомнить последнее место в каталоге — чтобы повторный вход с полки
 *  (кнопка «Каталоги», ⋮ полки) открывал то же место, где ты был, а не корень
 *  «Мои каталоги». Храним ленту-стек (url + заголовок по порядку) и, если стояли
 *  на странице книги, саму книгу с подписью источника. Живёт в памяти процесса:
 *  перезапуск приложения начинает каталог с корня, что и ожидаемо. */
object CatalogMemory {
    var feeds = ArrayList<Pair<String, String>>()
    var topBook: OpdsItem.Book? = null
    var topBookSource = ""

    // На диск место НЕ пишем (msg4266): Сергей решил, что история ходит только
    // внутри текущего сеанса, а холодный запуск открывает каталог с корня.
}
