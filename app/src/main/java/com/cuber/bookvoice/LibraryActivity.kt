package com.cuber.bookvoice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.cuber.bookvoice.databinding.ActivityLibraryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

/**
 * Библиотека — стартовый экран приложения.
 *
 * Книги попадают сюда тремя путями:
 *  1. «＋ Книга» — системный пикер одного файла (SAF, вечный доступ);
 *  2. «📁 Выбрать папку» — пользователь указывает папку (SAF-дерево),
 *     она запоминается и сканируется автоматически при каждом открытии;
 *  3. «Открыть с помощью» из файлового менеджера (msg748): системный VIEW/SEND
 *     с поддерживаемым файлом — как если бы файл выбрали через «＋ Книга».
 *
 * Позиция, статус и сортировки хранятся в [BookStore].
 */
class LibraryActivity(private val act: SectionActivity) {

    private lateinit var binding: ActivityLibraryBinding

    // Страница живёт В СВОЁМ окне-секции SectionActivity (редизайн msg1676+:
    // нижних вкладок нет — Библиотека это дом, корневое окно задачи). У
    // страницы нет своего Activity — все службы (контекст, запуск окон,
    // пикеры файлов, диалоги) идут через окно [act]. Открытие Каталога и
    // Настроек поверх полки не пересоздаёт страницу: состояние полки (скролл,
    // фильтр) живёт здесь всё время жизни окна-дома.
    private val contentResolver get() = act.contentResolver
    private val packageName get() = act.packageName
    private val theme get() = act.theme

    private fun getString(resId: Int, vararg formatArgs: Any): String =
        act.getString(resId, *formatArgs)

    private fun getDrawable(id: Int): Drawable? = act.getDrawable(id)
    private fun runOnUiThread(block: () -> Unit) = act.runOnUiThread(block)
    private fun startActivity(intent: Intent) = act.startActivity(intent)

    private val prefs by lazy { act.getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private var sortMode = SORT_TITLE

    // msg748: запуск «Открыть с помощью» (файл прислан снаружи). В этом случае
    // авто-открытие последней книги при старте не срабатывает — открываем файл.
    private var launchWasExternal = false

    // Фильтр списка по статусу (msg643): 0..2 — конкретный статус из
    // BookRecord, FILTER_ALL — показывать всё. Выбранный фильтр помним в prefs.
    private var filterMode = FILTER_ALL

    /** Текущий список полки из последнего refresh() — по нему ищем строку книги
     *  для возврата фокуса (msg1468). */
    private var shownRecords: List<BookRecord> = emptyList()

    /** Собрана ли полка хоть раз на ЭТОМ экземпляре страницы. L1b (msg1565)
     *  «не трогай полку под ридером» корректен только когда полка уже построена:
     *  после холодного старта с авто-открытием книги полка могла ещё не собраться
     *  (msg1585: «библиотека пустая») — refresh() пропускать нельзя. */
    private var shelfReady = false
    // msg1739: холодный старт — авто-открытие отложено до показа полки; защита от
    // повторного планирования, если resume() придёт в это окно ещё раз.
    private var coldAutoOpenPending = false

    // Вид полки (msg646/649): список (полные строки) или сетка (текстовые
    // карточки, без обложек). Помним выбор между запусками.
    private var viewMode = BookAdapter.VIEW_LIST

    // Построенные вкладки-фильтры: подписи и полоски-индикаторы (стиль общий
    // с нижними вкладками — TabNav.styleTab). Набор, порядок и видимость
    // читаются из prefs (#97): их задаёт экран «Вкладки библиотеки» в Настройках.
    private val filterLabels = ArrayList<TextView>()
    private val filterInds = ArrayList<View>()
    private var currentTabModes: List<Int> = emptyList()

    private val adapter = BookAdapter(
        rowText = { rec -> rowText(rec) },
        onBookClick = { rec -> openReader(rec) },
        onBookLongClick = { rec -> showBookMenu(rec) },
        // Карточка-сетка: говорим название и автора — то, что видно на карточке.
        cardText = { rec -> cardText(rec) },
    )

    /** Построить полку в контейнере [container] хоста. Зовётся один раз при
     *  создании хоста; страница остаётся живой всё время, переключение вкладок
     *  её не пересоздаёт. [intent] — интент, с которым запущен хост (для msg748
     *  «Открыть с помощью» из файлового менеджера). */
    fun build(container: ViewGroup, intent: Intent?) {
        binding = ActivityLibraryBinding.inflate(act.layoutInflater)
        container.addView(binding.root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // msg748: запуск по файлу из файлового менеджера («Открыть с помощью»).
        // Обрабатываем до сборки экрана — файл сразу уходит в библиотеку/читалку.
        handleExternalOpen(intent)

        sortMode = loadSort()

        viewMode = if (prefs.getInt(KEY_LIB_VIEW, BookAdapter.VIEW_LIST) == BookAdapter.VIEW_GRID) {
            BookAdapter.VIEW_GRID
        } else {
            BookAdapter.VIEW_LIST
        }
        binding.bookList.setHasFixedSize(true)
        binding.bookList.adapter = adapter
        applyViewMode()

        // msg646/649: в шапке остались «Продолжить» (2/3 ряда) и «⋮» — «Ещё».
        // Все остальные действия (добавить/сканировать/сортировать/вид) живут
        // в меню «Ещё» — короткий ряд не теснится и не перегружен.
        binding.btnLast.setOnClickListener { openLastBook() }
        // msg2351/2355: кнопка «Каталоги» правее от «Открыть книгу» — быстрый
        // вход в окно каталогов поверх полки (тот же переход, что пункт «⋮»).
        binding.btnCatalogs.setOnClickListener {
            startActivity(Intent(act, CatalogWindowActivity::class.java))
        }
        binding.btnMore.contentDescription = getString(R.string.lib_more)
        binding.btnMore.setOnClickListener { showMoreMenu() }

        // Фильтры по статусу (msg643): верхние вкладки «Читаю / Новые / Дочитано /
        // Все». Выбранный фильтр помним и возвращаемся к нему. Дефолт — «Все»,
        // без умного «Читаю»: авто-скан папки при каждом открытии добавляет книги
        // со статусом «новая», и если бы полка по умолчанию прятала их под «Читаю»,
        // свежие файлы пропадали бы из виду — будто скан не работает.
        filterMode = prefs.getInt(KEY_LIB_FILTER, FILTER_ALL)
        buildFilterTabs()

        // msg559: если доступ в интернет у BookVoice отключён (realme/Oppo —
        // отдельный переключатель «Интернет»), один раз за запуск подсказываем,
        // как вернуть. Системного диалога для интернета нет, поэтому — кнопка
        // на страницу приложения в настройках.
        PermissionNudge.ensureInternet(act)
    }

    /** Полка показана. Окно-дом зовёт это при каждом своём onResume: холодный
     *  старт, возврат из читалки/Каталога/Настроек/пикера. Внутри — бывший
     *  onResume Activity; переключение вкладок больше не поднимает окно, и явного
     *  входа «по вкладке» нет (msg1676+) — сюда всегда возвращаются «снизу». */
    fun resume() {
        // Сортировку можно поменять на экране настроек — перечитываем её при
        // каждом возврате сюда (в т.ч. из настроек).
        sortMode = loadSort()
        // msg1565/1567 (эксперимент L1b): возврат из ридера. Обычный путь ниже
        // делает refresh() — он пересортирует полку (SORT_LASTREAD поднимает
        // только что читанную книгу наверх) и переиспользует ряды: View, на
        // которой сидел Jieshuo, переезжает или получает другую книгу, и ридер
        // паркуется на вкладку или молчит (0.3.61: «нигде»/соседняя строка).
        // Librera полку под ридером не двигает — и фокус сам возвращается на
        // строку открывавшейся книги. Здесь на возврате из ридера полку НЕ
        // трогаем вовсе (ни refresh, ни скролл, ни перенос фокуса) и наблюдаем,
        // где Jieshuo осядет сам. Снимки двумя задержками: парковка бывает позже
        // 0.6 с (ранний снимок 0.3.61 показывал «нигде» ложно).
        val returnFromReader = pendingBookFocusUri != null
        // L1b применяем только если полка на этом экземпляре уже собрана
        // (shelfReady). Свежий экран после авто-открытия книги при холодном
        // старте ещё пуст — там пропуск refresh() = «библиотека пустая» (msg1585).
        if (returnFromReader && shelfReady) {
            pendingBookFocusUri = null
            Diag.log(act, "focus", "L1b: возврат из ридера — полку не трогаю")
            binding.bookList.postDelayed({
                val f = TabNav.focusedNow(binding.root)
                Diag.log(act, "focus", "L1b ИТОГ(+0.6с): фокус на ${f ?: "нигде/неизвестно"}")
            }, 600)
            binding.bookList.postDelayed({
                val f = TabNav.focusedNow(binding.root)
                Diag.log(act, "focus", "L1b ИТОГ(+1.5с): фокус на ${f ?: "нигде/неизвестно"}")
            }, 1500)
            // msg1690: сам по себе возврат оставляет фокус на окне, и TalkBack
            // озвучивает имя приложения, а не экран. Полку (список) не трогаем —
            // L1b живёт, но имя окна даём: фокус на стабильный заголовок
            // «Библиотека» (как вход в Каталог/Настройки, msg1666) — он вне
            // списка, озвучка держится.
            TabNav.focusHeader(binding.tvTitle)
            return
        }
        if (returnFromReader) {
            Diag.log(act, "focus", "L1b: возврат из ридера, полка не собрана — строю полку")
        }
        // Авто-скан выбранной папки — в фоне, чтобы новые книги добавлялись
        // даже когда сразу переходим в последнюю книгу.
        if (treeUri() != null) {
            scanInBackground { refresh() }
        }
        // Автобэкап по расписанию (#100): проверяем дешёвым гейтом (частота +
        // когда была последняя копия), тяжёлую запись делаем в фоне.
        maybeAutoBackup()
        // Возврат к последней читаемой книге при показе полки «сверху» (холодный
        // старт, возврат с рабочего стола — хост всегда корень задачи). Возврат
        // из ридера сюда полку не перехватывает: MainActivity ставит suppress
        // перед finish(). Хост не finish'ится — вкладки живут под читалкой (L1b).
        val suppress = suppressNextAutoOpen
        suppressNextAutoOpen = false
        Diag.log(act, "focus", "полка показана: авто-открытие ${if (suppress) "подавлено (окно-поверх/ридер закрылись)" else "не подавлено — возможен уход в книгу"}")

        // Запуск «Открыть с помощью» (msg748): внешний файл уже открыт в build(),
        // авто-возврат к последней книге сейчас не нужен — остальное (скан, полка)
        // выполняется как обычно.
        val startPref = prefs.getString(MainActivity.KEY_START, MainActivity.START_LAST)
        if (!suppress && !launchWasExternal && startPref == MainActivity.START_LAST) {
            val lastUri = prefs.getString(MainActivity.KEY_URI, null)
            if (lastUri != null && lastBookReadable(lastUri)) {
                if (shelfReady) {
                    // Возврат с рабочего стола: полка уже показана и объявлена —
                    // книгу открываем сразу.
                    openLastBook()
                    return
                }
                // msg1739: холодный старт — полка ещё не построена и не объявлена.
                // Если открыть книгу сразу, окно полки объявится позже label-ом
                // приложения («BookVoice») и перебьёт название книги (у Сергея:
                // «кусочек названия, потом BookVoice»). Даём полке появиться и
                // объявить своё имя (в окне — setTitle «Библиотека»), потом
                // открываем книгу — оба объявления звучат целиком.
                if (!coldAutoOpenPending) {
                    coldAutoOpenPending = true
                    // msg1853 (0.3.89): «управление мультимедиа» TalkBack объявляет
                    // один раз за процесс — в момент, когда BookVoice впервые рождает
                    // media-сессию. В 0.3.88 карточка рождалась при ОТКРЫТИИ книги —
                    // фраза падала сразу после названия книги. Чтобы система успела
                    // «познакомиться» с сессией ДО названия, рождаем карточку здесь,
                    // на старте: полка только что объявила «Библиотека», книга
                    // откроется через ~0.6с — разовое объявление уходит в стартовую
                    // паузу, а название книги и первое слово приходят чистыми.
                    // (playing=false: книга ещё не читается, карточка — в паузе;
                    // старт чтения переведёт её в PLAYING, не рождая заново.)
                    if (!MediaSessionService.isUp()) {
                        val rec = BookStore.byUri(act, lastUri)
                        MediaSessionService.start(act, rec?.title, playing = false)
                    }
                    Diag.log(act, "focus", "холодный старт: жду имя окна полки, авто-открытие через ~0.6с")
                    // msg1807/1811: на время холодного старта полка — промежуточный
                    // кадр перед книгой: озвучить должно успеться только имя окна
                    // «Библиотека», контент (вкладки, кнопки, список) — нет. Раньше
                    // TalkBack цеплял вкладку («…, вкладка, выбрана»), после их
                    // скрытия — наполненный фоновым сканом список («список, названия
                    // книги»). Прячем от скринридера ВЕСЬ контент полки на время
                    // авто-открытия; пауза 0.6с (была 1.1с) — полка не успевает
                    // озвучиться, имя окна — успевает. Вернём перед открытием книги.
                    suppressShelfA11y(true)
                    binding.root.postDelayed({
                        coldAutoOpenPending = false
                        suppressShelfA11y(false)
                        if (!act.isFinishing) openLastBook()
                    }, 600)
                }
                return
            }
        }
        // msg1807-страховка: сюда приходят только НЕ-холодные показы полки
        // (без отложенного авто-открытия), поэтому скрытие на время холодного
        // старта здесь гарантированно снимается, даже если книга не открылась.
        suppressShelfA11y(false)
        // Вкладки могли изменить в Настройках (#97) — пересобираем при каждом
        // возврате на полку. Если активный фильтр скрыли, buildFilterTabs сам
        // откатится на «Все»/первую видимую.
        buildFilterTabs()
        refresh()
        // msg1468: вернулись на полку — фокус на строку книги, которую открывали
        // (не на первую книгу и не в пустоту). Раньше здесь же был вход по
        // вкладке «Библиотека» с подтверждением и фокусом на первую книгу —
        // вкладок больше нет (msg1676+), остался только возврат «снизу».
        val focusUri = pendingBookFocusUri
        if (focusUri != null) {
            pendingBookFocusUri = null
            focusBookOnShelf(focusUri)
        } else if (shelfReady) {
            // msg1702: вернулись на полку без книги-цели — окно-поверх (Каталог/
            // Настройки/Цитаты) закрылось «назад», pendingBookFocusUri пуст.
            // Полка уже была показана (shelfReady), значит это возврат «сверху»,
            // а не холодный старт, — но TalkBack называет имя приложения, а не
            // экран. Даём имя окна-дома заголовком «Библиотека» (вне списка,
            // как в L1b-ветке возврата из ридера, msg1690), чтобы было слышно,
            // куда вышел. Свежий экран (shelfReady=false) не трогаем.
            TabNav.focusHeader(binding.tvTitle)
            // Автообновление (#35): полка только что показана «сверху» (окно
            // Настроек/Каталога/Цитат закрылось) — момент праздный, ридер
            // закрыт, озвучке не мешаем. Раз в процесс тихо спрашиваем GitHub
            // о новой версии; предложение (если есть) появится с паузой.
            UpdateFlow.auto(act)
        }
    }

    /** msg1453: перенос accessibility-фокуса на первую книгу полки. Полка могла
     *  быть прокручена — сдвигаемся вверх и ждём перерисовку, затем фокусируем
     *  первый видимый ряд (книгу). Раньше звалась при входе на вкладку
     *  «Библиотека»; теперь — запасной путь, когда книга, к которой хотели
     *  вернуться, не нашлась в списке. Пустая полка — падаем на «Продолжить»
     *  (если он доступен), иначе на «⋮ Ещё». */
    private fun focusFirstBookArrival() {
        binding.bookList.scrollToPosition(0)
        binding.bookList.postDelayed({
            for (i in 0 until binding.bookList.childCount) {
                val c = binding.bookList.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.isFocusable) {
                    TabNav.a11yFocus(c)
                    return@postDelayed
                }
            }
            val btn = when {
                binding.btnLast.isEnabled -> binding.btnLast
                else -> binding.btnMore
            }
            TabNav.a11yFocus(btn)
        }, 350)
    }

    /** msg1468/1553 (эксперимент L1): вернуть фокус на строку книги, из которой
     *  вышли из читалки — БЕЗ принудительного переноса. refresh() уже перерисовал
     *  полку; лог 0.3.60 показал: Jieshuo игнорирует наши a11yFocus-события (7 из 8
     *  мимо) и паркуется сам. Если ряд открывавшейся книги уцелел после refresh,
     *  ридер держит на нём фокус сам — наш перенос только сбивает. Здесь скроллим
     *  к ряду (если его нет на экране) и наблюдаем, где фокус осядет, не вмешиваясь.
     *  Книги в списке нет (другой фильтр/сортировка её спрятал) — первая книга,
     *  как при приходе на вкладку. */
    private fun focusBookOnShelf(uri: String) {
        val idx = shownRecords.indexOfFirst { it.uri == uri }
        if (idx < 0) {
            Diag.log(act, "focus", "L1: книга не в списке — естественный возврат")
            focusFirstBookArrival()
            return
        }
        binding.bookList.scrollToPosition(idx)
        binding.bookList.postDelayed({
            val v = binding.bookList.layoutManager?.findViewByPosition(idx)
            if (v != null && v.visibility == View.VISIBLE && v.isFocusable) {
                Diag.log(act, "focus", "L1: ряд книги на месте, перенос НЕ шлю")
                TabNav.observeFocusSettle(v)
            } else {
                // msg1523: ряд мог быть не раскатан (полка ещё перестраивалась) —
                // повторяем поиск, прежде чем падать на первую книгу.
                binding.bookList.postDelayed({
                    val v2 = binding.bookList.layoutManager?.findViewByPosition(idx)
                    if (v2 != null && v2.visibility == View.VISIBLE && v2.isFocusable) {
                        Diag.log(act, "focus", "L1: ряд появился позже, перенос НЕ шлю")
                        TabNav.observeFocusSettle(v2)
                    } else {
                        Diag.log(act, "focus", "L1: ряд так и не появился — первая книга")
                        focusFirstBookArrival()
                    }
                }, 400)
            }
        }, 350)
    }

    /** Хост закрывается (полный выход из приложения) — защита «активную вкладку
     *  нельзя скрыть» в Настройках отключается вместе с полкой. */
    fun dispose() {
        if (act.isFinishing) activeFilterMode = FILTER_ALL
    }

    /** msg1367: «назад» в корне вкладки «Библиотека» сворачивает приложение на
     *  рабочий стол, а не закрывает экран. Вкладки живут в одной задаче — обычный
     *  finish() вернул бы к предыдущей вкладке из стека, а не на рабочий стол.
     *  Полное закрытие — «⋮ → Выход из приложения». moveTaskToBack не гасит
     *  чтение: книга (если звучит) продолжает играть в фоне. */
    /** msg1367: «назад» на полке (окно-дом) сворачивает приложение на рабочий
     *  стол, а не закрывает экран. moveTaskToBack не гасит чтение: книга (если
     *  звучит) продолжает играть в фоне. Полное закрытие —
     *  «⋮ → Выход из приложения». Окно-дом решает, как свернуть, само
     *  (LibraryWindowActivity.rootBack); у окна поверх было бы finish(). */
    fun onBackKey(): Boolean {
        act.rootBack()
        return true
    }

    /** Последняя книга открывается автоматически, только если файл ещё на
     *  месте — иначе приложение каждый запуск прыгало бы в ридер с ошибкой. */
    private fun lastBookReadable(u: String): Boolean {
        val uri = Uri.parse(u)
        return try {
            if (uri.scheme == "file") {
                val p = uri.path
                p != null && java.io.File(p).exists()
            } else {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { it.moveToFirst() } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------------- Фильтры по статусу ----------------

    /** Видимые вкладки в порядке из prefs (#97): весь набор [LibraryActivity.TAB_MODES],
     *  отсортированный по сохранённому порядку, без скрытых. Вкладку «Все»
     *  скрыть нельзя, поэтому она всегда в результате. */
    private fun tabModesInOrder(): List<Int> {
        val order = LibraryActivity.tabsOrder(prefs)
        val hidden = LibraryActivity.tabsHidden(prefs)
        val visible = order.filter { it !in hidden }
        return if (FILTER_ALL in visible) visible else visible + FILTER_ALL
    }

    /** Собрать верхние вкладки-фильтры. Каждая — как нижняя вкладка: колонка с
     *  текстом (вес 1) и полоской-индикатором снизу; клик переключает фильтр.
     *  Активный фильтр могли скрыть в Настройках — тогда откатываемся на «Все»
     *  (или первую видимую), чтобы полка не открывалась на несуществующей вкладке. */
    private fun buildFilterTabs() {
        binding.filterBar.removeAllViews()
        filterLabels.clear()
        filterInds.clear()
        currentTabModes = tabModesInOrder()
        if (filterMode !in currentTabModes) {
            filterMode = if (FILTER_ALL in currentTabModes) FILTER_ALL else currentTabModes.first()
            prefs.edit().putInt(KEY_LIB_FILTER, filterMode).apply()
        }
        // Что реально показывает полка — для защиты в Настройках (#97).
        activeFilterMode = filterMode
        for (mode in currentTabModes) {
            val label = TextView(act).apply {
                text = getString(LibraryActivity.tabLabelRes(mode))
                textSize = 16f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = selectableItemBackground()
            }
            val ind = View(act)
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(label, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
                addView(ind, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
                ))
            }
            label.setOnClickListener {
                filterMode = mode
                prefs.edit().putInt(KEY_LIB_FILTER, mode).apply()
                activeFilterMode = mode
                applyFilterTabs()
                label.announceForAccessibility(
                    TabNav.tabDesc(getString(LibraryActivity.tabLabelRes(mode)), true)
                )
                refresh()
            }
            binding.filterBar.addView(col, LinearLayout.LayoutParams(
                0, dp(48), 1f
            ))
            filterLabels.add(label)
            filterInds.add(ind)
        }
        applyFilterTabs()
    }

    /** Подсветить активную вкладку-фильтр: текст акцентным цветом, полоска снизу,
     *  озвучка «…, вкладка, выбрана» — тот же стиль, что у нижних вкладок. */
    private fun applyFilterTabs() {
        for (i in currentTabModes.indices) {
            val mode = currentTabModes[i]
            val selected = filterMode == mode
            TabNav.styleTab(filterLabels[i], filterInds[i], selected)
            filterLabels[i].contentDescription = TabNav.tabDesc(
                getString(LibraryActivity.tabLabelRes(mode)), selected
            )
        }
    }

    /** msg1807/1811: на время холодного авто-открытия полка - лишь промежуточный
     *  кадр перед книгой; озвучиться должен только заголовок окна «Библиотека».
     *  Скринридер цеплялся и за вкладки-фильтры («…, вкладка, выбрана», msg1807),
     *  и за наполненный фоновым сканом список книг («названия книги», msg1811).
     *  В [hidden] прячем от a11y-дерева ВЕСЬ контент полки (вкладки, кнопки,
     *  список, подсказку) - у окна не остаётся фокусируемых элементов, а имя окна
     *  объявляет само окно, не контент. В обычном состоянии возвращаем AUTO.
     *  Визуально ничего не меняется. */
    private fun suppressShelfA11y(hidden: Boolean) {
        val mode = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        binding.filterBar.importantForAccessibility = mode
        binding.btnLast.importantForAccessibility = mode
        binding.btnCatalogs.importantForAccessibility = mode
        binding.btnMore.importantForAccessibility = mode
        binding.bookList.importantForAccessibility = mode
        binding.tvEmpty.importantForAccessibility = mode
    }

    /** Рipple-подложка под клик (attr), как у обычных кнопок/вкладок. */
    private fun selectableItemBackground(): Drawable? {
        val out = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            getDrawable(out.resourceId)
        } else null
    }

    // ---------------- Список ----------------

    private fun refresh() {
        // msg1333/1336: при каждом показе полки склеиваем старые дубли одной
        // скачанной книги (две записи с одним sourceUrl — оригинал и копия
        // «… (1)»). BookStore уже убрал проигравшие записи, здесь чистим их
        // файлы на диске, чтобы авто-скан папки не вернул копию отдельной
        // строкой. Новые дубли не появятся: каталог узнаёт скачанное по sourceUrl.
        val (all0, dupUris) = BookStore.mergeSourceDuplicates(act)
        if (dupUris.isNotEmpty()) {
            dupUris.forEach { removeDupFile(it) }
            Diag.log(act, "lib", "склеены дубли скачанных книг: ${dupUris.size}")
        }
        // msg1345/1346: sourceUrl-склейка не видит пару «скачано в папку (file://)
        // + авто-скан той же папки (content://)» — у скана sourceUrl нет. Если
        // папку удалось сопоставить с реальным путём, склеиваем и такие: один
        // файл на диске = одна запись, сохраняем с прогрессом чтения.
        val resolver = treeResolver()
        val all = if (resolver != null) mergeSameFileCopies(resolver) else all0
        // Фильтр по статусу (msg643): «Все» — без фильтра, конкретный статус —
        // только книги с ним. Сортировка применяется уже к отфильтрованному списку.
        val shown = if (filterMode == FILTER_ALL) {
            all
        } else {
            all.filter { it.status == filterMode }
        }
        // Сортировка «недочитанные сверху» убрана (msg646/649): статус теперь
        // разводят вкладки-фильтры сверху, а не сортировка. Остались по названию,
        // по дате добавления, по автору и по последнему чтению.
        val sorted = when (sortMode) {
            SORT_DATE -> shown.sortedByDescending { it.addedAt }
            SORT_AUTHOR -> shown.sortedBy { (it.author ?: it.displayTitle).lowercase(Locale.ROOT) }
            SORT_LASTREAD -> shown.sortedByDescending { it.lastOpenedAt }
            else -> shown.sortedBy { it.displayTitle.lowercase(Locale.ROOT) }
        }
        adapter.submit(sorted)
        // Полка построена на этом экземпляре — L1b может не трогать её при
        // возврате из ридера (msg1585: свежий экран без refresh остался бы пустым).
        shelfReady = true
        // Текущий список — по нему возврат фокуса на открывавшуюся книгу (msg1468).
        shownRecords = sorted
        val empty = sorted.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.bookList.visibility = if (empty) View.GONE else View.VISIBLE
        // Библиотека пуста и фильтр пуст — это разные сообщения: в первом случае
        // зовём добавить книгу, во втором подсказываем сменить вкладку-фильтр.
        if (empty) {
            binding.tvEmpty.text = getString(
                if (all.isEmpty()) R.string.books_empty else R.string.lib_filter_empty
            )
        }
        updateContinueButton()
    }

    /** msg1938/msg2108: умная кнопка быстрого входа в последнюю книгу — текст с
     *  её названием, чтобы было слышно, куда ведёт («Открыть книгу: Война и мир»).
     *  Нет доступной последней книги — кнопки нет вовсе, на полке ничего не висит
     *  впустую. Название берём из каталога (displayTitle); запись удалили — пробуем
     *  имя файла напрямую (как openLastBook), файл тоже пропал — прячем. */
    private fun updateContinueButton() {
        val u = prefs.getString(MainActivity.KEY_URI, null)
        val title = u?.let {
            BookStore.byUri(act, it)?.displayTitle ?: queryDisplayName(Uri.parse(it))
        }
        binding.btnLast.visibility = if (title == null) View.GONE else View.VISIBLE
        binding.btnLast.isEnabled = title != null
        binding.btnLast.text = if (title == null) getString(R.string.continue_last)
        else getString(R.string.continue_last_with, title)
    }

    private fun rowText(rec: BookRecord): String {
        val meta = ArrayList<String>()
        rec.author?.takeIf { it.isNotBlank() }?.let { meta.add(it) }
        // Статус — только в «Все»: в отфильтрованном списке он одинаков у каждой
        // строки и только засоряет озвучку. Прогресс оставляем везде.
        if (filterMode == FILTER_ALL) meta.add(statusLabel(rec.status))
        if (rec.lastOpenedAt > 0L) progressText(rec)?.let { meta.add(it) }
        return if (meta.isEmpty()) {
            rec.displayTitle
        } else {
            rec.displayTitle + "\n" + meta.joinToString(" · ")
        }
    }

    /** «прочитано N%» (msg656): дочитанная книга — 100%, у остальных — процент,
     *  который ридер сохранил в запись при открытии/паузе. У старых записей
     *  (открыты до появления поля процента) число неизвестно — возвращаем null,
     *  строку прогресса не показываем; она появится после первого же открытия
     *  книги, когда ридер пересчитает позицию. */
    private fun progressText(rec: BookRecord): String? {
        val pct = when {
            rec.status == BookRecord.STATUS_FINISHED -> 100
            rec.readPct > 0 -> rec.readPct.coerceIn(0, 100)
            rec.chapter > 0 || rec.sentence > 0 -> return null
            else -> 0
        }
        return getString(R.string.lib_progress_pct, pct)
    }

    private fun statusLabel(status: Int): String = getString(
        when (status) {
            BookRecord.STATUS_FINISHED -> R.string.status_finished
            BookRecord.STATUS_READING -> R.string.status_reading
            else -> R.string.status_new
        }
    )

    private fun dp(v: Int): Int = (act.resources.displayMetrics.density * v).toInt()

    /** Сортировка из prefs. Старое значение 2 («сначала недочитанные») в новом
     *  наборе режимов не существует — откатываемся на «по названию»; остальные
     *  id валидны и сохраняются как есть. */
    private fun loadSort(): Int {
        val v = prefs.getInt(KEY_SORT, SORT_TITLE)
        return if (v in SORT_CHOICES) v else SORT_TITLE
    }

    private fun showSortDialog() {
        // Варианты общие с экраном настроек (SORT_CHOICES). id режима не обязан
        // совпадать с позицией в списке, поэтому пишем в prefs id, а выбранный
        // пункт диалога ищем по indexOf.
        val opts = SORT_CHOICES.map { getString(sortLabelRes(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.sort_dialog)
            .setSingleChoiceItems(
                opts,
                SORT_CHOICES.indexOf(sortMode).coerceAtLeast(0),
            ) { d, which ->
                sortMode = SORT_CHOICES[which]
                prefs.edit().putInt(KEY_SORT, sortMode).apply()
                d.dismiss()
                refresh()
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    // ---------------- Меню «Ещё» (⋮) и вид полки (msg646/649) ----------------

    /** Меню «⋮» в шапке вместо четырёх кнопок: открыть файл (бывш. «Добавить»),
     *  сканировать/выбрать папку, сортировка, вид «список/сетка» и раздел
     *  «Цитаты» (#104). Если папка ещё не выбрана, пункт про неё предлагает
     *  её выбрать. */
    private fun showMoreMenu() {
        val tree = treeUri()
        val qCount = QuoteStore.all(act).size
        val items = arrayOf(
            getString(R.string.catalog_title),   // msg1676+: «Каталоги» окном поверх.
            getString(R.string.settings_title),  // msg1676+: «Настройки» окном поверх.
            getString(R.string.lib_menu_open_file),
            getString(if (tree == null) R.string.choose_folder else R.string.lib_menu_scan),
            getString(R.string.lib_menu_sort),
            getString(R.string.lib_menu_view),
            getString(R.string.quotes_section, qCount),
            getString(R.string.app_exit),  // msg1278: Выход из приложения — последним.
        )
        MaterialAlertDialogBuilder(act)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(act, CatalogWindowActivity::class.java))
                    1 -> startActivity(Intent(act, SettingsWindowActivity::class.java))
                    2 -> act.openDocPicker(arrayOf("*/*")) { uri -> if (uri != null) onBookPicked(uri) }
                    3 -> onFolderButton()
                    4 -> showSortDialog()
                    5 -> showViewDialog()
                    6 -> startActivity(Intent(act, QuotesActivity::class.java))
                    7 -> exitApp()
                }
            }
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    /** msg1278: «Выход из приложения» из библиотеки. Общий для вкладок выход —
     *  TabNav.exitApp: гасит службу чтения и закрывает всю задачу (читалка
     *  до-finish'ается своим onDestroy, где сервис и player гаснут штатно). */
    private fun exitApp() = TabNav.exitApp(act)

    /** Выбор вида полки: список (полные строки) или сетка (текстовые карточки). */
    private fun showViewDialog() {
        val opts = arrayOf(
            getString(R.string.lib_view_list),
            getString(R.string.lib_view_grid),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.lib_view_title)
            .setSingleChoiceItems(opts, viewMode) { d, which ->
                viewMode = which
                prefs.edit().putInt(KEY_LIB_VIEW, viewMode).apply()
                d.dismiss()
                applyViewMode()
                refresh()
                toast(getString(R.string.lib_view_changed, getString(
                    if (viewMode == BookAdapter.VIEW_GRID) R.string.lib_view_grid
                    else R.string.lib_view_list
                )))
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    /** Применить вид к полке: сетка — две колонки карточек, список — строки.
     *  Зовётся при первом показе и при смене в меню «Ещё». */
    private fun applyViewMode() {
        adapter.viewMode = viewMode
        binding.bookList.layoutManager = if (viewMode == BookAdapter.VIEW_GRID) {
            GridLayoutManager(act, GRID_COLUMNS)
        } else {
            LinearLayoutManager(act)
        }
    }

    /** Озвучка карточки-сетки: то, что видно на карточке (название, автор), и
     *  прогресс чтения — чтобы в сетке тоже было слышно, сколько прочитано
     *  (msg656). Статус, как и раньше, остаётся в полном списке. */
    private fun cardText(rec: BookRecord): String {
        val author = rec.author?.takeIf { it.isNotBlank() }
        val base = if (author == null) rec.displayTitle else rec.displayTitle + "\n" + author
        if (rec.lastOpenedAt > 0L) {
            progressText(rec)?.let { return base + "\n" + it }
        }
        return base
    }

    // ---------------- Автобэкап (#100) ----------------

    /** Автобэкап по расписанию: дешёвый гейт на главном (папка выбрана, частота
     *  включена, срок подошёл), сборка и запись — в фоне, чтобы не задерживать
     *  открытие. Озвучиваем результат, только когда копия реально создалась. */
    private fun maybeAutoBackup() {
        val mode = prefs.getString(BackupStore.KEY_AUTO, BackupStore.AUTO_DEFAULT)
            ?: BackupStore.AUTO_DEFAULT
        if (mode == BackupStore.AUTO_OFF) return
        val period = BackupStore.autoPeriodMs(mode) ?: return
        val last = BackupStore.lastBackupAt(act)
        if (last > 0L && System.currentTimeMillis() - last < period) return
        if (BackupStore.dirUri(act) == null) return
        Thread {
            try {
                val bytes = BackupStore.build(act, includeSettings = true, includeBooks = true)
                val ok = BackupStore.writeAutoFile(act, bytes) != null
                if (ok) BackupStore.recordBackupTime(act)
                runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (ok) toast(getString(R.string.backup_created))
                }
            } catch (t: Throwable) {
                // Фоновая гигиена (0.3.43, msg1075): автобэкап НЕ должен ронять
                // приложение. Любой сбой пишем в diag.log и спокойно выходим.
                Diag.log(act, "backup", "автобэкап не удался: ${t}")
            }
        }.start()
    }

    // ---------------- Папка с книгами ----------------

    private fun treeUri(): String? = prefs.getString(KEY_TREE, null)

    private fun onFolderButton() {
        if (treeUri() == null) {
            act.openTreePicker { uri -> if (uri != null) onFolderPicked(uri) }
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.scan_folder)
            .setItems(arrayOf(getString(R.string.rescan_now), getString(R.string.other_folder))) { _, which ->
                when (which) {
                    0 -> rescanFolder()
                    1 -> act.openTreePicker { uri -> if (uri != null) onFolderPicked(uri) }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun onFolderPicked(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
        toast(getString(R.string.folder_added))
        rescanFolder()
    }

    private fun rescanFolder() {
        toast(getString(R.string.scanning_start))
        scanInBackground { n ->
            refresh()
            toast(getString(R.string.folder_scan_result, n))
        }
    }

    /** Скан в фоне — не вешаем UI, названия из файлов читаем отдельно. */
    private fun scanInBackground(done: (Int) -> Unit) {
        Thread {
            val added = scanFolderWithMeta()
            runOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) done(added)
            }
        }.start()
    }

    /** Рекурсивно пройти дерево; новые книги добавить, у «безымянных»
     *  (добавлены прошлой версией по имени файла) дозаполнить настоящее
     *  название и автора из содержимого, битые кодировки заголовков — починить. */
    private fun scanFolderWithMeta(): Int {
        val treeStr = treeUri() ?: return 0
        val tree = Uri.parse(treeStr)
        val found = ArrayList<Pair<String, String>>() // (documentId, name)
        collectDocuments(tree, DocumentsContract.getTreeDocumentId(tree), found, 0)
        val hidden = prefs.getStringSet(KEY_HIDDEN, null) ?: emptySet()
        val hiddenPaths = prefs.getStringSet(KEY_HIDDEN_PATHS, null) ?: emptySet()
        var added = 0
        for ((docId, name) in found) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val u = uri.toString()
            val existing = BookStore.byUri(act, u)
            if (existing != null) {
                // 0.3.26: заголовки из файлов могли сохраниться с битой кодировкой
                // (кракозябры «РєР°С‡…»). Чиним запись без перечитывания файла;
                // попутно дозаполняем метаданные у записей прошлых версий.
                var upd = existing
                val fixedTitle = existing.title?.let { BookParser.unmojibake(it) }
                val fixedAuthor = existing.author?.let { BookParser.unmojibake(it) }
                if (fixedTitle != null) upd = upd.copy(title = fixedTitle)
                if (fixedAuthor != null) upd = upd.copy(author = fixedAuthor)
                if (upd.title.isNullOrBlank() && upd.author.isNullOrBlank() && needsBookMeta(name)) {
                    val meta = readMeta(uri, name)
                    if (meta != null) {
                        upd = upd.copy(
                            title = meta.title ?: upd.title,
                            author = meta.author ?: upd.author,
                            // Файл прочитан — фиксируем и аннотацию: "" = прочитан,
                            // аннотации в нём нет (не путать с null «не проверялось»).
                            annotation = if (canHaveFileAnnotation(name)) {
                                meta.annotation ?: ""
                            } else {
                                upd.annotation
                            },
                        )
                    }
                }
                if (upd != existing) BookStore.upsert(act, upd)
                continue
            }
            // Файлы, которые владелец удалил из библиотеки, авто-скан не
            // возвращает: по точному uri и по реальному пути файла (запись могли
            // удалить под другим адресом — msg1347).
            if (docHiddenFromScan(u, hidden, hiddenPaths)) continue
            // msg1345/1346: файл уже есть в библиотеке по file://-адресу (скачан
            // из каталога в эту же папку) — content://-копия скана не нужна.
            // Прячем документ, чтобы скан больше его не встречал, и не добавляем.
            if (skipScannedTwin(u, name)) continue
            val meta = if (needsBookMeta(name)) readMeta(uri, name) else null
            BookStore.upsert(act, BookRecord(
                uri = u,
                name = name,
                title = meta?.title,
                author = meta?.author,
                // Файл уже прочитан ради названия — заодно сохраняем аннотацию.
                // У fb2/архивов "" означает «прочитан, аннотации нет».
                annotation = if (meta != null && canHaveFileAnnotation(name)) {
                    meta.annotation ?: ""
                } else {
                    null
                },
                addedAt = System.currentTimeMillis(),
            ))
            added++
        }
        return added
    }

    /** Название ищем в файле только там, где оно есть: fb2, xml, архив, epub.
     *  У голого txt метаданных нет — оставляем имя файла. */
    private fun needsBookMeta(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".fb2") || lower.endsWith(".xml") ||
            lower.endsWith(".zip") || lower.endsWith(".epub")
    }

    /** Аннотацию из файла умеем доставать только у FB2-подобных форматов:
     *  сам fb2/xml и архив (внутри которого FB2). У epub аннотация сидит в
     *  OPF-разметке и здесь не разбирается. */
    private fun canHaveFileAnnotation(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".fb2") || lower.endsWith(".xml") || lower.endsWith(".zip")
    }

    /** Быстро вытащить название/автора прямо из файла (архива или fb2). */
    private fun readMeta(uri: Uri, name: String): BookParser.BookMeta? {
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        } catch (_: Exception) {
            return null
        }
        return BookParser.peekMeta(name, bytes)
    }

    private fun collectDocuments(
        tree: Uri,
        docId: String,
        out: MutableList<Pair<String, String>>,
        depth: Int,
    ) {
        if (depth > MAX_SCAN_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir) {
                        collectDocuments(tree, id, out, depth + 1)
                    } else if (supportedFileName(name)) {
                        out.add(id to name)
                    }
                }
            }
        }
    }

    private fun supportedFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".fb2") || lower.endsWith(".txt") ||
            lower.endsWith(".zip") || lower.endsWith(".xml") || lower.endsWith(".epub") ||
            lower.endsWith(".pdf")
    }

    // ---------------- Открытие книги ----------------

    private fun onBookPicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "book"
        if (!supportedFileName(name)) {
            toast(getString(R.string.unsupported_format))
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
        val u = uri.toString()
        val existing = BookStore.byUri(act, u)
        if (existing != null) {
            toast(getString(R.string.already_in_library))
            openReader(existing)
            return
        }
        val rec = BookRecord(uri = u, name = name, addedAt = System.currentTimeMillis())
        BookStore.upsert(act, rec)
        toast(getString(R.string.book_added))
        Vibra.confirm(act)
        openReader(rec)
    }

    private fun openReader(rec: BookRecord) {
        // Первое открытие новой книги помечает её «читаю».
        if (rec.status == BookRecord.STATUS_NEW) {
            BookStore.upsert(act, rec.copy(
                status = BookRecord.STATUS_READING,
                lastOpenedAt = System.currentTimeMillis(),
            ))
        }
        // msg1468: вернулись из читалки — фокус на строку этой книги.
        pendingBookFocusUri = rec.uri
        val i = Intent(act, MainActivity::class.java)
        i.putExtra(MainActivity.EXTRA_URI, rec.uri)
        i.putExtra(MainActivity.EXTRA_CHAPTER, rec.chapter)
        i.putExtra(MainActivity.EXTRA_SENTENCE, rec.sentence)
        startActivity(i)
    }

    private fun openLastBook() {
        val u = prefs.getString(MainActivity.KEY_URI, null)
        if (u == null) {
            toast(getString(R.string.no_last_book))
            return
        }
        val existing = BookStore.byUri(act, u)
        if (existing != null) {
            openReader(existing)
            return
        }
        // Запись могли удалить — открываем как есть, с позицией из prefs.
        openReader(BookRecord(
            uri = u,
            name = queryDisplayName(Uri.parse(u)) ?: "book",
            addedAt = 0L,
            chapter = prefs.getInt(MainActivity.KEY_CHAPTER, 0),
            sentence = prefs.getInt(MainActivity.KEY_SENTENCE, 0),
        ))
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    // ---------------- Открытие файла снаружи (msg748) ----------------

    /** msg748: приложение в списке «Открыть с помощью» — пользователь нажал на
     *  поддерживаемый файл (PDF/EPUB/ZIP/TXT/FB2) в файловом менеджере, и система
     *  запустила нас с ACTION_VIEW (или ACTION_SEND из «Поделиться»). Принятый
     *  файл ведём тем же путём, что и выбор через «＋ Книга»: в библиотеку и в
     *  читалку. Неподдерживаемое расширение отсекает onBookPicked со своей
     *  подсказкой. */
    private fun handleExternalOpen(intent: Intent?) {
        val action = intent?.action ?: return
        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            // «Поделиться файлом»: тип реального файла (не сообщения-текста)
            // определяем по наличию EXTRA_STREAM — без него это просто текст,
            // не файл, игнорируем.
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> return
        }
        if (uri == null) return
        launchWasExternal = true
        onBookPicked(uri)
    }

    // ---------------- Долгое нажатие на книгу (меню) ----------------

    /** Меню книги: поделиться, статус, информация, удалить. Короткий тап —
     *  открывает книгу, длинный — это меню. */
    private fun showBookMenu(rec: BookRecord) {
        val finished = rec.status == BookRecord.STATUS_FINISHED
        val opts = arrayOf(
            getString(R.string.library_menu_share),
            getString(if (finished) R.string.library_menu_unfinish else R.string.library_menu_finish),
            getString(R.string.library_menu_info),
            getString(R.string.library_menu_delete),
        )
        MaterialAlertDialogBuilder(act)
            .setTitle(rec.displayTitle)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> shareBook(rec)
                    1 -> toggleFinished(rec, finished)
                    2 -> showBookInfo(rec)
                    3 -> confirmDelete(rec)
                }
            }
            // msg1977: «Закрыть» убрана — жест «назад»/тап мимо и так закрывает
            // меню, лишняя кнопка только плодит шум в озвучке.
            .show()
    }

    /** Файл наружу: content:// из SAF отдаём как есть с грантом на чтение,
     *  file:// (наши скачанные) — через FileProvider. */
    private fun shareBook(rec: BookRecord) {
        val uri = runCatching { Uri.parse(rec.uri) }.getOrNull() ?: return
        val shareUri = if (uri.scheme == "content") {
            uri
        } else {
            val f = uri.path?.let { java.io.File(it) }
            if (f == null || !f.exists()) {
                toast(getString(R.string.book_share_missing))
                return
            }
            androidx.core.content.FileProvider.getUriForFile(
                act, "$packageName.fileprovider", f
            )
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(rec.name)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.library_menu_share)))
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".epub", true) -> "application/epub+zip"
        name.endsWith(".fb2", true) -> "application/x-fictionbook+xml"
        name.endsWith(".txt", true) -> "text/plain"
        name.endsWith(".xml", true) -> "text/xml"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun toggleFinished(rec: BookRecord, finished: Boolean) {
        BookStore.upsert(act, rec.copy(
            status = if (finished) BookRecord.STATUS_READING else BookRecord.STATUS_FINISHED,
        ))
        toast(getString(if (finished) R.string.status_reading else R.string.status_finished))
        refresh()
    }

    /** Окно «Информация о книге»: автор, файл, статус и внизу — аннотация
     *  (msg568). Текст аннотации живёт в записи; у старых книг (добавленных до
     *  этой версии) файл ради неё ещё не читался — догружаем в фоне, чтобы
     *  диалог не ждал чтения файла и открывался сразу.
     *
     *  Значения [BookRecord.annotation]: null = не проверялось, "" = файл
     *  прочитан, аннотации в нём нет, иначе — сам текст. */
    private fun showBookInfo(rec0: BookRecord) {
        var rec = rec0
        val scroll = ScrollView(act)
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(0))
        }
        scroll.addView(content)

        fun makeRow(text: String, muted: Boolean = false): TextView = TextView(act).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (muted) 0xFF9AA0A6.toInt() else 0xFFE8EAED.toInt())
            setLineSpacing(0f, 1.15f)
            isFocusable = true
            setPadding(0, dp(8), 0, dp(4))
        }

        fun makeHeader(): TextView = TextView(act).apply {
            text = getString(R.string.book_info_annotation_title)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF8AB4F8.toInt())
            isFocusable = true
            setPadding(0, dp(14), 0, dp(4))
        }

        /** Отрисовать низ: аннотация из записи — блоком с заголовком; пусто —
         *  приглушённой строкой «нет». Поиск аннотации в интернете убран
         *  (msg1215): внешние сервисы не дают стабильного результата. */
        fun renderAnnotationArea() {
            val stored = rec.annotation
            if (!stored.isNullOrBlank()) {
                content.addView(makeHeader())
                content.addView(makeRow(stored))
            } else {
                content.addView(makeRow(getString(R.string.book_info_annotation_none), muted = true))
            }
        }

        rec.author?.takeIf { it.isNotBlank() }
            ?.let { content.addView(makeRow(getString(R.string.book_info_author, it))) }
        content.addView(makeRow(getString(R.string.book_info_file, rec.name)))
        content.addView(makeRow(getString(R.string.book_info_status, statusLabel(rec.status))))

        // У epub/txt/pdf аннотации в файле не бывает — рисуем сразу; у FB2-подобных
        // с null файл читаем в фоне (иначе диалог ждал бы чтения файла).
        val canFile = canHaveFileAnnotation(rec.name)
        val fileCheckPending = rec.annotation == null && canFile
        if (!fileCheckPending) renderAnnotationArea()

        val dialog = MaterialAlertDialogBuilder(act)
            .setTitle(rec.displayTitle)
            .setView(scroll)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .create()
        dialog.show()

        if (fileCheckPending) {
            val loading = makeRow(getString(R.string.book_info_annotation_loading), muted = true)
            loading.setPadding(0, dp(14), 0, dp(4))
            content.addView(loading)
            val uri = runCatching { Uri.parse(rec.uri) }.getOrNull()
            Thread {
                val meta = if (uri != null) readMeta(uri, rec.name) else null
                val found = meta?.annotation?.takeIf { it.isNotBlank() }
                runOnUiThread {
                    if (act.isFinishing || act.isDestroyed || !dialog.isShowing) return@runOnUiThread
                    val idx = content.indexOfChild(loading)
                    content.removeView(loading)
                    rec = rec.copy(annotation = found ?: "")
                    BookStore.upsert(act, rec)
                    // Строку «читаю…» заменяем: нашли — аннотацией, нет — строкой «нет».
                    if (found != null) {
                        content.addView(makeHeader(), idx)
                        content.addView(makeRow(found), idx + 1)
                    } else {
                        content.addView(
                            makeRow(getString(R.string.book_info_annotation_none), muted = true), idx
                        )
                    }
                }
            }.start()
        }
    }

    private fun confirmDelete(rec: BookRecord) {
        MaterialAlertDialogBuilder(act)
            .setTitle(R.string.book_delete_title)
            .setMessage(getString(R.string.book_delete_msg, rec.displayTitle))
            .setPositiveButton(R.string.book_delete_yes) { _, _ -> deleteBook(rec) }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    /** Удаление из библиотеки (msg1351 — по решению владельца стираем и файл).
     *  Сначала пробуем стереть физический файл: file:// — напрямую (AllFiles);
     *  content:// из сканируемой папки — по реальному пути, если папка
     *  сопоставлена, иначе через SAF. Затем убираем запись. Файл стереть не
     *  вышло (нет прав/чужой провайдер) — прячем его от скана, чтобы книга не
     *  вернулась следующей строкой. */
    private fun deleteBook(rec: BookRecord) {
        var deleted = false
        val uri = runCatching { Uri.parse(rec.uri) }.getOrNull()
        val tp = treeResolver()
        if (uri != null && uri.scheme == "file") {
            deleted = try {
                uri.path?.let { File(it).delete() } ?: false
            } catch (_: Exception) {
                false
            }
        }
        if (!deleted && uri != null && uri.scheme == "content") {
            if (tp != null) {
                val p = diskPathOf(rec.uri, tp)
                if (p != null) {
                    deleted = try { File(p).delete() } catch (_: Exception) { false }
                }
            }
            if (!deleted) {
                deleted = try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                    false
                }
            }
        }
        val path = tp?.let { diskPathOf(rec.uri, it) }
        // Убираем саму запись и её близнеца-дубль (другой адрес того же файла),
        // чтобы после удаления файла не осталось записи-сироты.
        BookStore.remove(act, rec.uri)
        if (path != null) {
            BookStore.all(act).forEach { r ->
                if (r.uri != rec.uri && diskPathOf(r.uri, tp) == path) {
                    BookStore.remove(act, r.uri)
                    hideFromScan(r.uri)
                }
            }
        }
        hideFromScan(rec.uri)
        // Файл не стёрся (нет прав/чужой провайдер) — прячем от скана, чтобы
        // книга не вернулась следующей строкой.
        if (!deleted && path != null) hidePathFromScan(path)
        Diag.log(act, "lib", "удалена книга ${rec.name}: файл ${if (deleted) "стёрт" else "не стёрт — скрыт от скана"}")
        toast(getString(R.string.book_deleted))
        Vibra.confirm(act)
        refresh()
    }

    /** Проигравший дубль (msg1333/1336): файл-копию убираем с диска, а uri
     *  прячем в скрытый список — если файл ещё подхватил авто-скан папки, запись
     *  он не вернёт. Удалить не вышло — прячем всё равно: дубль исчезает с полки,
     *  копия файла остаётся на диске до ручной уборки. */
    private fun removeDupFile(uriStr: String) {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        val deleted = try {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).delete() } ?: false
                "content" -> DocumentsContract.deleteDocument(contentResolver, uri)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
        hideFromScan(uriStr)
        Diag.log(act, "lib", if (deleted) "файл дубля удалён: $uriStr"
            else "файл дубля не удалось убрать, скрыт от скана: $uriStr")
    }

    // ——— Дубли «один файл — два адреса» (msg1345/1346) ———

    /** Сопоставление выбранной папки-дерева с реальным каталогом на диске.
     *  Нужно, чтобы распознать одну книгу под двумя адресами: file:// реального
     *  пути (запасная запись скачивания AllFiles) и content://-документа дерева
     *  (авто-скан той же папки). Работает только с включённым «Доступом ко всем
     *  файлам» и сопоставимой папкой; иначе null — склейку не делаем. */
    private class TreePaths(val dir: File, val treeDocId: String)

    private fun treeResolver(): TreePaths? {
        val treeStr = treeUri() ?: return null
        if (!AllFiles.granted(act)) return null
        val tree = runCatching { Uri.parse(treeStr) }.getOrNull() ?: return null
        val dir = AllFiles.resolveDir(tree) ?: return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        return TreePaths(dir, docId)
    }

    /** Реальный путь файла на диске по uri записи, если файл лежит в выбранной
     *  папке: file:// — путь как есть; content://-документ дерева — дерево +
     *  относительный путь документа. Чужой uri (не наша папка, сторонний
     *  провайдер) — null, такой файл сравнить с другими нельзя. */
    private fun diskPathOf(uriStr: String, tp: TreePaths): String? {
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
        return try {
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it).canonicalPath }
                "content" -> {
                    val doc = DocumentsContract.getDocumentId(uri) ?: return null
                    val prefix = tp.treeDocId + "/"
                    if (!doc.startsWith(prefix)) return null
                    var rel = doc.substring(prefix.length)
                    if (rel.contains("%")) rel = Uri.decode(rel)
                    File(tp.dir, rel).canonicalPath
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Спрятать uri от авто-скана папки: запись удалена или склеена, но сам
     *  файл остаётся в папке — без скрытия скан вернул бы его строкой. */
    private fun hideFromScan(uriStr: String) {
        val set = (prefs.getStringSet(KEY_HIDDEN, null) ?: emptySet()).toMutableSet()
        if (set.add(uriStr)) {
            prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
        }
    }

    /** Спрятать от авто-скана сам файл (по реальному пути на диске): пригодится,
     *  когда запись могла быть под одним адресом, а скан встречает файл под
     *  другим (content://-документ вместо удалённой file://-записи). */
    private fun hidePathFromScan(path: String) {
        val set = (prefs.getStringSet(KEY_HIDDEN_PATHS, null) ?: emptySet()).toMutableSet()
        if (set.add(path)) {
            prefs.edit().putStringSet(KEY_HIDDEN_PATHS, set).apply()
        }
    }

    /** Пропустить ли документ при скане: uri уже в скрытом списке, либо реальный
     *  путь файла спрятан (книгу удаляли под другим адресом). */
    private fun docHiddenFromScan(u: String, hiddenUris: Set<String>, hiddenPaths: Set<String>): Boolean {
        if (u in hiddenUris) return true
        if (hiddenPaths.isEmpty()) return false
        val tp = treeResolver() ?: return false
        val p = diskPathOf(u, tp) ?: return false
        return p in hiddenPaths
    }

    /** Склейка записей, за которыми один и тот же файл на диске. Группируем по
     *  реальному пути, оставляем самую «ценную» запись ([BookStore.betterForKeep]
     *  — ту, что с прогрессом чтения и sourceUrl), остальные убираем из реестра и
     *  прячем от скана. Файл один — с диска ничего не удаляем. Возвращает
     *  актуальный список записей. */
    private fun mergeSameFileCopies(tp: TreePaths): List<BookRecord> {
        val byPath = HashMap<String, MutableList<BookRecord>>()
        BookStore.all(act).forEach { r ->
            val p = diskPathOf(r.uri, tp)
            if (p != null) byPath.getOrPut(p) { ArrayList() }.add(r)
        }
        var removed = 0
        for (g in byPath.values) {
            if (g.size < 2) continue
            var best = g[0]
            for (r in g) best = BookStore.betterForKeep(best, r)
            for (r in g) if (r.uri != best.uri) {
                BookStore.remove(act, r.uri)
                hideFromScan(r.uri)
                removed++
            }
        }
        if (removed > 0) {
            Diag.log(act, "lib", "склеены дубли одной книги (тот же файл): $removed")
        }
        return BookStore.all(act)
    }

    /** При скане: файл уже в библиотеке по другому адресу (file:// скачивания) —
     *  content://-документ не добавляем, а прячем, чтобы скан не встречал его
     *  снова. Возвращает true, если документ надо пропустить. */
    private fun skipScannedTwin(contentUri: String, name: String): Boolean {
        val tp = treeResolver() ?: return false
        val contentPath = diskPathOf(contentUri, tp) ?: return false
        val hasTwin = BookStore.all(act).any { r ->
            r.uri != contentUri && diskPathOf(r.uri, tp) == contentPath
        }
        if (!hasTwin) return false
        hideFromScan(contentUri)
        Diag.log(act, "lib", "книга уже есть по file://-адресу, скан пропустил: $name")
        return true
    }

    companion object {
        // internal — экран настроек читает/пишет сортировку и папку в те же prefs.
        internal const val KEY_SORT = "sort"
        internal const val KEY_TREE = "tree_uri"
        internal const val SORT_TITLE = 0
        internal const val SORT_DATE = 1
        // 2 был «сначала недочитанные» — убран (msg646/649): статусы разводят
        // вкладки-фильтры, режим больше не существует, старые сохранённые 2
        // loadSort() откатывает на «по названию».
        internal const val SORT_AUTHOR = 3
        internal const val SORT_LASTREAD = 4

        /** Варианты сортировки (порядок в диалогах полки и настроек). */
        internal val SORT_CHOICES = intArrayOf(
            SORT_TITLE, SORT_DATE, SORT_AUTHOR, SORT_LASTREAD,
        )

        /** Подпись варианта сортировки по его id (не по позиции в списке). */
        internal fun sortLabelRes(mode: Int): Int = when (mode) {
            SORT_DATE -> R.string.sort_date
            SORT_AUTHOR -> R.string.sort_author
            SORT_LASTREAD -> R.string.sort_lastread
            else -> R.string.sort_title
        }

        // ——— Вкладки-фильтры Библиотеки (#97) ———
        // Порядок и видимость верхних вкладок задаёт экран «Вкладки библиотеки»
        // в Настройках; читает/пишет те же prefs-функции, что и полка.
        // internal: экран настроек сравнивает с ней (FILTER_ALL неснимаема) и
        // хранит активный фильтр живой Библиотеки.
        internal const val FILTER_ALL = 3

        internal const val KEY_TABS_ORDER = "lib_tabs_order"
        internal const val KEY_TABS_HIDDEN = "lib_tabs_hidden"

        /** Все вкладки в порядке по умолчанию. «Все» (FILTER_ALL) — неснимаема. */
        internal val TAB_MODES = intArrayOf(
            BookRecord.STATUS_READING, BookRecord.STATUS_NEW,
            BookRecord.STATUS_FINISHED, FILTER_ALL,
        )

        /** Подпись вкладки-фильтра по id режима (не по позиции в списке). */
        internal fun tabLabelRes(mode: Int): Int = when (mode) {
            BookRecord.STATUS_READING -> R.string.lib_filter_reading
            BookRecord.STATUS_NEW -> R.string.lib_filter_new
            BookRecord.STATUS_FINISHED -> R.string.lib_filter_finished
            else -> R.string.lib_filter_all
        }

        /** Сохранённый порядок вкладок; мусор/неполный набор — порядок по умолчанию. */
        internal fun tabsOrder(prefs: SharedPreferences): List<Int> {
            val raw = prefs.getString(KEY_TABS_ORDER, null) ?: return TAB_MODES.toList()
            val parsed = raw.split(',').mapNotNull { it.toIntOrNull() }
            if (parsed.size != TAB_MODES.size) return TAB_MODES.toList()
            if (parsed.toSet() != TAB_MODES.toSet()) return TAB_MODES.toList()
            return parsed
        }

        /** Скрытые вкладки (номера режимов). «Все» скрыть нельзя — выпадает сама. */
        internal fun tabsHidden(prefs: SharedPreferences): Set<Int> {
            val set = prefs.getStringSet(KEY_TABS_HIDDEN, null) ?: return emptySet()
            return set.mapNotNull { it.toIntOrNull() }.filter { it != FILTER_ALL }.toSet()
        }

        /** Записать порядок и скрытые вкладки (экран «Вкладки библиотеки»). */
        internal fun saveTabs(prefs: SharedPreferences, order: List<Int>, hidden: Set<Int>) {
            prefs.edit()
                .putString(KEY_TABS_ORDER, order.joinToString(","))
                .putStringSet(KEY_TABS_HIDDEN, hidden.map { it.toString() }.toSet())
                .apply()
        }

        private const val MAX_SCAN_DEPTH = 5
        private const val KEY_HIDDEN = "hidden_uris"
        // msg1347: спрятанные от скана файлы по реальному пути (удалены под
        // file://-адресом, а скан встречает их как content://-документы).
        private const val KEY_HIDDEN_PATHS = "hidden_paths"
        private const val KEY_LIB_FILTER = "lib_filter"
        private const val KEY_LIB_VIEW = "lib_view"
        private const val GRID_COLUMNS = 2

        // Ставит MainActivity перед «назад в библиотеку»: полка не должна
        // перехватывать onResume как «запуск с рабочего стола» и прыгать
        // обратно в книгу.
        @Volatile
        internal var suppressNextAutoOpen = false

        // msg1468: книга, открытая в читалку. Возврат на полку ставит фокус на
        // её строку (refresh перерисовывает список и иначе фокус теряется).
        // Статик, потому что при холодном старте с авто-открытием последней
        // книги первая Библиотека finish()'ится — instance-поле не переживёт.
        @Volatile
        internal var pendingBookFocusUri: String? = null

        // Какой фильтр-вкладка сейчас открыт в живой Библиотеке (#97). Экран
        // «Вкладки библиотеки» в Настройках не даёт скрыть активную вкладку;
        // без Библиотеки на экране (значение не обновлялось) — «Все».
        @Volatile
        internal var activeFilterMode: Int = FILTER_ALL
    }
}
