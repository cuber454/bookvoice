package com.cuber.bookvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Окно-секция — один из главных экранов в СОБСТВЕННОМ окне (редизайн msg1676+).
 *
 *  Раньше Библиотека/Каталоги/Настройки жили страницами общего хоста TabsActivity
 *  с нижней полосой вкладок. Полоса была постоянным якорем TalkBack: любой
 *  программный перенос фокуса с неё спорил, и интерфейс ощущался «абы как»
 *  (msg1670). Теперь нижней полосы нет: Библиотека — корневое окно задачи,
 *  Каталоги и Настройки открываются окнами поверх неё «как книга» из меню «⋮»
 *  полки. Механика окна книги проверена и стабильна — экраны получают её же.
 *
 *  База несёт то, что для страниц держал хост TabsActivity: контейнер
 *  [container], в который страница надувает ВЕСЬ свой экран (как в pageHost), и
 *  пикеры файлов/папок/результата/разрешений — у страницы нет своего Activity,
 *  все лаунчеры регистрирует окно. Конкретное окно строит свою страницу
 *  (buildSection) и ведёт её жизненный цикл: onResume → resumeSection(первый ли
 *  показ), «назад» → onSectionBackKey, закрытие → disposeSection.
 *
 *  [resumeSection] с [arrival]=true — первый показ окна после создания: свежий
 *  вход «навигацией», страница подтверждает и ставит фокус (заголовок). false —
 *  возврат из пикера/под-окна/рабочего стола: фокус не трогаем (msg1465).
 */
abstract class SectionActivity : AppCompatActivity() {

    /** Контейнер окна: страница надувается в него целиком. */
    protected lateinit var container: FrameLayout

    /** Первый ли сейчас показ окна (после создания). Дальше показы — возвраты,
     *  фокус не трогают. */
    private var shownOnce = false

    // Пикеры файлов/папок у страницы нет своего Activity — регистрирует окно и
    // раздаёт результат колбэком (как держал хост TabsActivity).
    private var docCb: ((Uri?) -> Unit)? = null
    private var treeCb: ((Uri?) -> Unit)? = null
    private var resCb: ((ActivityResult) -> Unit)? = null
    private var permCb: ((Boolean) -> Unit)? = null
    private val pickDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { docCb?.invoke(it) }
    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeCb?.invoke(it) }
    private val pickForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resCb?.invoke(it) }
    private val requestPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permCb?.invoke(it) }

    /** Пикер одного файла (SAF, вечный доступ) — для страницы окна. */
    fun openDocPicker(types: Array<String>, onResult: (Uri?) -> Unit) {
        docCb = onResult
        pickDoc.launch(types)
    }

    /** Пикер папки-дерева (SAF) — для страницы окна. */
    fun openTreePicker(onResult: (Uri?) -> Unit) {
        treeCb = onResult
        pickTree.launch(null)
    }

    /** Запустить [intent] и отдать результат (голосовой поиск каталога и т.п.) —
     *  у страницы нет своего Activity, лаунчер держит окно. */
    fun launchForResult(intent: Intent, onResult: (ActivityResult) -> Unit) {
        resCb = onResult
        pickForResult.launch(intent)
    }

    /** Запросить рантайм-разрешение (READ_PHONE_STATE в Настройках и т.п.) —
     *  у страницы нет своего Activity, лаунчер держит окно. */
    fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        permCb = onResult
        requestPerm.launch(permission)
    }

    /** Размер текста (msg5730) — окна-секции получают его здесь, до создания
     *  разметки: дальше и надутые из XML экраны, и строки, построенные кодом,
     *  читают размер из уже поднятой конфигурации. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(TextScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // msg5730: тема окна (обычная или контрастная) выбирается до создания
        // разметки — и разметка, и построенные кодом строки берут цвета у неё.
        Palette.applyTo(this)
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
        // SDK 36: окно edge-to-edge — прижимаем корень к безопасной зоне, иначе
        // шапка окна уедет под статус-бар, а низ — под навигационную полосу.
        edgeToEdge(container)
        buildSection(intent)

        // ТОЛЧОК У КРАЯ СПИСКА — всем окнам-секциям (09.10.2026). Окно собрано,
        // ищем в нём то, что прокручивается, и вешаем наблюдателя. Имя для
        // журнала берём у самого окна ([setTitle]): «Словарь», «Не засыпать»,
        // «О программе». Короткий список промолчит сам — у него нет края.
        if (edgeTickAuto) {
            A11y.edgeVibrateAuto(
                container,
                title?.toString() ?: "окно",
                watch = { w -> watchTouches(w) },
            )
        }

        // msg2233/2239: на Android 16 (targetSdk 36) системный «назад» уходит в
        // predictive-back диспетчер, и без явного callback системный default
        // МОЖЕТ закрыть окно сам, минуя onSectionBackKey (замечено в diag.log
        // 0.4.1: окно Настроек исчезло, а наших веток «назад» в логе нет — как
        // раз «то на полку, то в книгу»). Регистрируем ЯВНЫЙ OnBackPressedCallback:
        // каждый back приходит сюда → onSectionBackKey (в разделе — уровень вверх,
        // в корне — rootBack с подавлением авто-открытия книги). deprecated
        // onBackPressed() в predictive-режиме может не зваться, поэтому здесь
        // единственный источник правды (см. манифест enableOnBackInvokedCallback).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!onSectionBackKey()) {
                    // Страница «назад» не взяла — отдать системному default (finish).
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val arrival = !shownOnce
        shownOnce = true
        resumeSection(arrival)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) disposeSection()
    }

    /** «Назад» в корне окна-секции. Окно-дом (Библиотека) сворачивает задачу на
     *  рабочий стол; окно поверх (Каталог/Настройки) закрывается — под ним
     *  раскрывается то, откуда пришли. */
    open fun rootBack() {
        finish()
    }

    /** Собрать страницу окна в [container] при создании. [intent] — интент, с
     *  которым запущено окно (полка его использует для «Открыть с помощью»). */
    abstract fun buildSection(intent: Intent?)

    /** Страница показана. [arrival]=true — первый показ окна (свежий вход). */
    abstract fun resumeSection(arrival: Boolean)

    /**
     * Имя панели окна (28.09.2026, по документации Android). Все наши окна
     * называют себя через setTitle — и при входе, и при смене раздела; отсюда же
     * берём имя панели, и диктор объявляет его сам, когда панель появляется или
     * меняется. Так и должно быть: где встать фокусом, решает служба доступности,
     * а имя экрана даёт само окно. Раньше ради имени мы уводили фокус на заголовок.
     */
    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        if (::container.isInitialized) TabNav.namePane(container, title)
    }

    /** «Назад» на странице. True — обработано (окно дальше не пускает). */
    abstract fun onSectionBackKey(): Boolean

    /** Вешаем ли толчок у края АВТОМАТИЧЕСКИ, по собранному окну (09.10.2026).
     *  Окна, чья страница вешает его САМА (полка, каталог, настройки — им нужен
     *  ещё и `handOff` догрузки страниц каталога), отвечают здесь «нет»: иначе
     *  наш слушатель касаний перекрыл бы их собственный. */
    protected open val edgeTickAuto: Boolean get() = true

    /** Кто смотрит ВСЕ касания окна (09.10.2026). У касания ОДИН адресат: жест,
     *  начавшийся на шапке окна или на нижних кнопках, до слушателя списка не
     *  доходит вовсе — а Серж спрашивал ровно про это («с заголовка на первую
     *  книгу не вибрирует», «и на нижних также сделал элементах?»). Поэтому окно
     *  отдаёт свои касания тому, кто просит ([watchTouches]), а смотрим их в
     *  [dispatchTouchEvent]: он проходит через ЛЮБУЮ ветку дерева и никому
     *  события не отбирает. */
    private var touchWatchers: Array<(MotionEvent) -> Unit> = emptyArray()

    fun watchTouches(watcher: (MotionEvent) -> Unit) {
        touchWatchers += watcher
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Снимок ссылки: подписка во время жеста не сломает обход.
        val ws = touchWatchers
        for (i in ws.indices) ws[i](ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Окно закрывается (полный выход из приложения) — страница снимает свои
     *  защиты. По умолчанию нечего снимать; полка и каталог переопределяют. */
    open fun disposeSection() {}
}
