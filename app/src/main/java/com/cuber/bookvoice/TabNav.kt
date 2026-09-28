package com.cuber.bookvoice

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Набор стилей и a11y-приёмов главных экранов (редизайн навигации msg1676+).
 *
 *  Нижних вкладок больше нет: Библиотека — дом (корневое окно), Каталоги и
 *  Настройки — окна поверх. Этим экранам остались общие приёмы: вкладки-фильтры
 *  полки выглядят и звучат как вкладки (styleTab/tabDesc), перенос
 *  accessibility-фокуса (a11yFocus), фокус на заголовок окна (focusHeader),
 *  фокус после перерисовки (refocusAfterRebuild) и полный выход (exitApp).
 */
object TabNav {

    // msg5730: цвета берём у палитры на каждом обращении, а не один раз при
    // инициализации объекта — иначе окно, открытое после смены контраста,
    // осталось бы с прежними.
    private val CLEAR = 0x00000000.toInt()

    /** Озвучка роли «вкладка»: «Название, вкладка[, выбрана]». Для вкладок-
     *  фильтров библиотеки (они по роли те же вкладки, что были внизу). */
    fun tabDesc(label: String, selected: Boolean): String =
        label + if (selected) ", вкладка, выбрана" else ", вкладка"

    /** Оформить текст вкладки и полоску-индикатор под состояние [selected].
     *  Активная — акцентным цветом и жирным, с полоской снизу. Для вкладок-
     *  фильтров библиотеки. */
    fun styleTab(label: TextView, ind: View?, selected: Boolean) {
        label.setTextColor(if (selected) Palette.ACCENT else Palette.INK)
        label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        ind?.setBackgroundColor(if (selected) Palette.ACCENT else CLEAR)
    }

    /** Первый видимый кликабельный потомок [root] в порядке обхода сверху вниз
     *  (сам [root] не проверяем). Null — если кликабельных нет. */
    fun firstFocusable(root: ViewGroup): View? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            if (c.isFocusable) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    /**
     * Переместить фокус диктора на элемент [v] — способом, который нынешний
     * Android считает правильным (переписано 28.09.2026).
     *
     * Раньше слали событие TYPE_VIEW_ACCESSIBILITY_FOCUSED руками: публичного
     * вызова переноса мы не знали, а нода из createAccessibilityNodeInfo падала
     * (краш 0.3.57) — отсюда и запрет в прежнем комментарии. Правильный вызов —
     * у самого View: `performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS)`, он
     * есть с Android 4.1 и не падает. Им же диктор пользуется, когда сам
     * переносит фокус.
     *
     * Почему это важно: в журнале 28.09.2026 наши переносы по событию
     * записывались («перенос на Голос»), а диктор молчал — на событие он не
     * реагировал, а на вызов переноса реагирует (для строки книги он ответил
     * «true», и фокус встал).
     *
     * Событие осталось запасным путём: если вызов не обработан (диктор или вид
     * его не поддерживают), шлём то же событие, что и раньше. Перенос ровно один:
     * TalkBack объявляет каждое событие переноса, поэтому «проверил и повторил»
     * давало тройную озвучку (msg1629/1647).
     */
    fun a11yFocus(v: View) {
        if (!v.isShown) {
            Diag.log(v.context, "focus", "пропуск переноса: цель не видна (${describe(v)})")
            return
        }
        if (v.isAccessibilityFocused()) {
            Diag.log(v.context, "focus", "цель уже сфокусирована — перенос не шлю (${describe(v)})")
            scheduleSettleLog(v)
            return
        }
        interruptSpeech(v)
        Diag.log(
            v.context, "focus",
            "перенос на ${describe(v)} (shown=${v.isShown}, focusable=${v.isFocusable}, " +
                "a11yFocused=${v.isAccessibilityFocused()})",
        )
        val handled = v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        Diag.log(v.context, "focus", "перенос: вызов переноса ответил $handled")
        if (!handled) {
            // Запасной путь для дикторов, которые на вызов не отвечают: то же
            // событие, каким пользовались до 28.09.2026.
            v.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
            Diag.log(v.context, "focus", "перенос: вызов не обработан — послал событие")
        }
        scheduleSettleLog(v)
    }

    /** Через паузу после попыток записать, где в ИТОГЕ сидит фокус ридера — на
     *  нашей цели или ридер всё же утащил его. По этому логу видно, игнорирует
     *  ли Jieshuo перенос или перекрывает его своим падением. */
    private fun scheduleSettleLog(v: View) {
        v.postDelayed({
            if (v.isShown) {
                val all = a11yFocusedAll(v.rootView)
                // Кроме доступностного фокуса пишем и системный: приложение не может
                // спросить у службы доступности, куда она встала, а флаг на узле
                // диктор выставляет не всегда (отсюда «нигде/неизвестно»). Системный
                // фокус идёт за диктором в большинстве случаев, и по нему видно, куда
                // перенос всё-таки дошёл.
                val sys = runCatching { describe(v.rootView.findFocus() ?: v.rootView) }.getOrNull()
                Diag.log(
                    v.context, "focus",
                    "ИТОГ: фокус на ${all.joinToString(" | ").ifEmpty { "нигде/неизвестно" }} " +
                        "(цель была ${describe(v)}; системный фокус: ${sys ?: "?"})",
                )
            }
        }, 250)
    }

    /** ВСЕ узлы, которые сообщают о доступностном фокусе (28.09.2026).
     *
     *  Нужно, чтобы отличить настоящий уход фокуса от нашего же лога: диктор,
     *  переезжая, не всегда снимает флаг с прежнего узла, а поиск «первого
     *  попавшегося» в порядке дерева показывал бы старую строку. Если в строке
     *  журнала два имени — фокус ушёл по-настоящему; если одно — и это цель, значит
     *  всё в порядке. */
    private fun a11yFocusedAll(root: View): List<String> {
        val out = ArrayList<String>(2)
        fun walk(v: View) {
            if (v.isAccessibilityFocused()) out.add(describe(v))
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /** Наблюдение без вмешательства (эксперимент L1, msg1553): записать, где через
     *  паузу окажется accessibility-фокус, ничего не перенося. */
    fun observeFocusSettle(v: View) {
        if (v.isShown) scheduleSettleLog(v)
    }

    /**
     * Перенос фокуса на строку книги — способом, который нынешний Android считает
     * правильным (28.09.2026).
     *
     * Чем отличается от [a11yFocus]. Там мы шлём событие TYPE_VIEW_ACCESSIBILITY_FOCUSED
     * руками — так делали, потому что публичного вызова переноса мы не знали, а нода
     * из createAccessibilityNodeInfo падала (краш 0.3.57). Здесь три вещи:
     *   1) `View.performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS)` — публичный
     *      вызов самого View (есть с Android 4.1), его и просит документация;
     *   2) на Android 14 и новее строка помечается «начальным фокусом» окна
     *      (`AccessibilityNodeInfo.setRequestInitialAccessibilityFocus`, публичный с
     *      API 34): диктор встанет на неё сам, даже если вызов (1) он проигнорирует;
     *   3) обрыв текущей речи (`AccessibilityManager.interrupt()`), чтобы хвост
     *      фразы книги не накладывался на объявление полки.
     *
     * Событие шлём ровно одно: TalkBack объявляет каждое наше событие, а флаг
     * `isAccessibilityFocused()` при этом не выставляет (см. msg1629/1647), поэтому
     * «проверил и повторил» давало тройную озвучку одного и того же.
     */
    fun a11yFocusBook(v: View): Boolean {
        if (!v.isShown) {
            Diag.log(v.context, "focus", "перенос на книгу: цель не видна (${describe(v)})")
            return false
        }
        if (v.isAccessibilityFocused()) {
            Diag.log(v.context, "focus", "перенос на книгу: цель уже в фокусе (${describe(v)})")
            scheduleSettleLog(v)
            return true
        }
        interruptSpeech(v)
        Diag.log(
            v.context, "focus",
            "перенос на книгу ${describe(v)} (API ${Build.VERSION.SDK_INT}, " +
                "focusable=${v.isFocusable}, a11yFocused=${v.isAccessibilityFocused()})",
        )
        val handled = v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        Diag.log(v.context, "focus", "перенос на книгу: вызов переноса ответил $handled")
        // requestFocus здесь НЕ зовём: системный фокус в списке и доступностный — две
        // разные вещи, и вторая половина дня 28.09.2026 показала, что от лишнего
        // системного фокуса диктор через полторы секунды уезжает на соседнюю строку
        // (журнал: цель «Коллекция анекдотов», а через 1.5 с фокус на «Самые свежие
        // угарные анекдоты»). Вызова переноса диктору достаточно: он ответил true, и
        // фокус встал на книгу.
        scheduleSettleLog(v)
        return handled
    }

    /**
     * Пометка «начальный фокус» для [v] — Android 14 и новее.
     *
     * НЕ ИСПОЛЬЗУЕТСЯ с 28.09.2026, оставлено как объяснение: пометку снимали через
     * полторы секунды, а снятие — это изменение узла, на которое диктор реагирует
     * перечитыванием списка. В журнале ровно через полторы секунды после переноса
     * фокус оказывался на СОСЕДНЕЙ строке (цель «Коллекция анекдотов», а через 1.5 с
     * «Самые свежие угарные анекдоты»). Вызова переноса диктору хватает, поэтому
     * пометка убрана совсем — и лишнего события больше нет.
     */
    @Suppress("unused")
    private fun markInitialFocus(v: View) {
        if (Build.VERSION.SDK_INT < 34) return
        val d = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.setRequestInitialAccessibilityFocus(true)
            }
        }
        v.accessibilityDelegate = d
        v.postDelayed({
            if (v.accessibilityDelegate === d) v.accessibilityDelegate = null
        }, 1500)
    }

    /** Оборвать текущую речь: хвост фразы книги не должен звучать поверх
     *  объявления полки. Вызов есть с самых первых версий Android. */
    private fun interruptSpeech(v: View) {
        runCatching {
            val am = v.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.interrupt()
        }
    }

    /** Текущее место accessibility-фокуса в дереве [root] — для наблюдения без
     *  вмешательства (L1b, msg1565): записать, где Jieshuo осел сам. */
    fun focusedNow(root: View): String? = a11yFocusedDesc(root)

    /** Наблюдение без вмешательства по таймеру (эксперимент L1-каталог, msg1573):
     *  через [delayMs] записать, где осел accessibility-фокус. Ничего не переносим —
     *  только фиксируем результат. */
    fun observeFocusAfter(root: View, what: String, delayMs: Long = 1200) {
        root.postDelayed({
            val f = a11yFocusedDesc(root)
            Diag.log(root.context, "focus", "L1K ИТОГ($what): фокус на ${f ?: "нигде/неизвестно"}")
        }, delayMs)
    }

    private fun a11yFocusedDesc(root: View): String? {
        if (root.isAccessibilityFocused()) return describe(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                a11yFocusedDesc(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Короткое имя [v] для лога: id из ресурсов + текст + contentDescription. */
    private fun describe(v: View): String {
        val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
        val txt = (v as? TextView)?.text?.toString()
        val cd = v.contentDescription?.toString()
        val parts = listOfNotNull(id, txt, cd).map { it.trim() }.filter { it.isNotEmpty() }
        return (parts.joinToString("|") + "#" + Integer.toHexString(v.hashCode())).take(60)
    }

    /** Поставить accessibility-фокус ПОСЛЕ перерисовки экрана. [target] null —
     *  первый кликабельный в [root]; если [target] не показан/не фокусируем —
     *  тоже запасной первый.
     *
     *  msg1434/1438/1447: когда фокусная строка исчезает при перерисовке
     *  контента (removeAllViews + rebuild), TalkBack сам роняет фокус куда
     *  попадёт. Мгновенный requestFocus гонку проигрывает (это и был баг 0.3.55).
     *  Пауза 350 мс даёт ридеру осесть после перестройки дерева, затем фокус
     *  ставим явно через [a11yFocus] — и он держится. */
    fun refocusAfterRebuild(root: View, target: View? = null) {
        root.postDelayed({
            var v: View? = target
            if (v == null || !v.isShown || !v.isFocusable) {
                v = if (root is ViewGroup) firstFocusable(root) else null
            }
            if (v != null && v.isShown) a11yFocus(v)
        }, 350)
    }

    /**
     * Имя панели окна (28.09.2026, по документации Android).
     *
     * Диктор объявляет имя панели сам — когда панель появляется или меняется.
     * Это и есть штатный способ назвать экран; раньше мы ради имени окна уводили
     * фокус на заголовок (msg1666), а это лишний перенос: где встать в новом окне,
     * решает служба доступности. Теперь имя даёт само окно, а фокус остаётся там,
     * куда его поставила система.
     */
    fun namePane(root: View, title: CharSequence?) {
        if (title.isNullOrBlank()) return
        runCatching { ViewCompat.setAccessibilityPaneTitle(root, title) }
        // Пишем в журнал: по этой строке видно, ЧТО мы дали диктору как имя экрана.
        // Озвучку услышать мы не можем (её делает служба доступности), но проверить,
        // дали ли мы имя вовсе, надо — иначе не отличить «мы не сказали» от
        // «диктор панельные имена не читает».
        Diag.log(root.context, "a11y", "имя панели: $title")
    }

    /** msg1666: вход в окно-секцию — фокус ридера на ЗАГОЛОВОК окна. TalkBack
     *  читает название экрана, и сразу ясно, куда зашёл; где именно потом осядет
     *  курсор — неважно. Заголовок — стабильный TextView вверху страницы (не в
     *  списке), поэтому перенос держится, в отличие от борьбы за первый пункт
     *  контента, которая давала молчаливые прыжки фокуса (msg1652/1662).
     *  После паузы [delayMs] — чтобы окно показалось и TalkBack отпустил
     *  нажатый элемент. */
    fun focusHeader(v: View, delayMs: Long = 550) {
        if (!v.isFocusable) v.isFocusable = true
        // msg2296 (законы TalkBack): чтобы заголовок был КАНДИДАТОМ на фокус,
        // focusable мало — TalkBack по «правилу первенства» садится на первую
        // кнопку окна (⋮/Назад), а перенос на «пассивный» текст отклоняет.
        // screenReaderFocusable делает текст полноправной целью (в layout это же
        // стоит атрибутом с первого кадра; здесь — страховка на всех API).
        ViewCompat.setScreenReaderFocusable(v, true)
        v.postDelayed({
            if (v.isShown) a11yFocus(v)
        }, delayMs)
        // msg1629/1647 + лог 0.4.7 (msg2312): НЕ ретраить перенос. TalkBack
        // объявляет КАЖДОЕ app-событие переноса, но реальный accessibility-фокус
        // при этом НЕ двигает — флаг isAccessibilityFocused() у заголовка так и
        // остаётся false. Проверка «не подтверждён → повтор» (0.4.6) озвучивала
        // заголовок по разу на событие (2-3 дубля, msg2302); на подтверждении
        // ретрай так же не остановится — флаг не выставляется никогда. Поэтому
        // ровно ОДНО событие = ровно одно объявление, а где осядет курсор — решает
        // TalkBack сам (для окон это кнопка ⋮/«Назад», но заголовок озвучен — цели
        // msg1666 «сразу ясно, куда зашёл» достаточно).
    }

    /** Полный выход из приложения (msg1278): гасим службу чтения и закрываем
     *  всю задачу — нижележащие экраны до-finish'атся своими onDestroy. Общий
     *  для окон: «⋮ Ещё» Библиотеки, Каталога и Настроек.
     *
     *  msg1770: finishAffinity закрывает окна, но ПРОЦЕСС и задача остаются
     *  живыми — повторный запуск «возвращается» в живой сеанс (полку), и старт
     *  в последней книге (работает на холодном старте, msg1739) не наступает.
     *  Поэтому после закрытия окон гасим и процесс — с паузой, чтобы async-
     *  запись настроек/места чтения (prefs.apply) успела доехать до диска. */
    fun exitApp(from: Activity) {
        MediaSessionService.stop(from)
        from.finishAffinity()
        from.window?.decorView?.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 400)
    }
}
