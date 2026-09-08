package com.cuber.bookvoice

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
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

    private val ACCENT = 0xFF8AB4F8.toInt()
    private val INK = 0xFFE8EAED.toInt()
    private val CLEAR = 0x00000000.toInt()

    /** Озвучка роли «вкладка»: «Название, вкладка[, выбрана]». Для вкладок-
     *  фильтров библиотеки (они по роли те же вкладки, что были внизу). */
    fun tabDesc(label: String, selected: Boolean): String =
        label + if (selected) ", вкладка, выбрана" else ", вкладка"

    /** Оформить текст вкладки и полоску-индикатор под состояние [selected].
     *  Активная — акцентным цветом и жирным, с полоской снизу. Для вкладок-
     *  фильтров библиотеки. */
    fun styleTab(label: TextView, ind: View?, selected: Boolean) {
        label.setTextColor(if (selected) ACCENT else INK)
        label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        ind?.setBackgroundColor(if (selected) ACCENT else CLEAR)
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

    /** Переместить фокус ридера на элемент [v] — тем способом, за которым
     *  скринридеры реально следуют (JieshuO, TalkBack). msg1506/1509:
     *  requestFocus двигает только системный (клавиатурный) фокус, ридеры его
     *  игнорируют и после перерисовки сами роняют фокус. Ридер переводим
     *  событием TYPE_VIEW_ACCESSIBILITY_FOCUSED — его ридеры слушают как сигнал
     *  «фокус перенесён»; requestFocus — запасной для режимов, где ридер всё же
     *  следует за системным фокусом.
     *
     *  НЕ трогаем node.performAction(ACTION_ACCESSIBILITY_FOCUS): нода из
     *  createAccessibilityNodeInfo приходит не sealed, и performAction роняет
     *  IllegalStateException (краш 0.3.57 — лог в пасте Сергея). */
    fun a11yFocus(v: View) {
        if (!v.isShown) {
            Diag.log(v.context, "focus", "пропуск переноса: цель не видна (${describe(v)})")
            return
        }
        if (v.isAccessibilityFocused()) {
            Diag.log(v.context, "focus", "цель уже сфокусирована — событие не шлю (${describe(v)})")
            scheduleSettleLog(v)
            return
        }
        // msg1629/1647: шлём РОВНО ОДНО событие, без повторов. Раньше после
        // отправки сверяли isAccessibilityFocused() и при false слали повторно
        // (до 3 раз): TalkBack объявляет КАЖДОЕ app-событие переноса, но флаг
        // реального фокуса при этом не выставляет синхронно — проверка всегда
        // считала перенос неудавшимся, и элемент озвучивался по разу на событие
        // («повторил четыре раза» → после 0.3.66 «три раза»). Однократная
        // отправка = одно объявление.
        sendFocus(v)
        // Ничего не переносим повторно; через паузу только пишем в лог, куда
        // ридер в итоге поставил фокус (наблюдение без вмешательства).
        scheduleSettleLog(v)
    }

    /** Одна попытка переноса: событие accessibility-фокуса + запасной requestFocus.
     *  НЕ трогаем node.performAction(ACTION_ACCESSIBILITY_FOCUS): нода из
     *  createAccessibilityNodeInfo приходит не sealed, и performAction роняет
     *  IllegalStateException (краш 0.3.57). True — ридер уже смотрит на [v]. */
    private fun sendFocus(v: View): Boolean {
        Diag.log(
            v.context, "focus",
            "перенос на ${describe(v)} (shown=${v.isShown}, focusable=${v.isFocusable}, " +
                "a11yFocused=${v.isAccessibilityFocused()})",
        )
        v.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        if (v.isFocusable) v.requestFocus()
        return v.isAccessibilityFocused()
    }

    /** Через паузу после попыток записать, где в ИТОГЕ сидит фокус ридера — на
     *  нашей цели или ридер всё же утащил его. По этому логу видно, игнорирует
     *  ли Jieshuo перенос или перекрывает его своим падением. */
    private fun scheduleSettleLog(v: View) {
        v.postDelayed({
            if (v.isShown) {
                val f = a11yFocusedDesc(v.rootView)
                Diag.log(
                    v.context, "focus",
                    "ИТОГ: фокус на ${f ?: "нигде/неизвестно"} (цель была ${describe(v)})",
                )
            }
        }, 250)
    }

    /** Наблюдение без вмешательства (эксперимент L1, msg1553): записать, где через
     *  паузу окажется accessibility-фокус, ничего не перенося. */
    fun observeFocusSettle(v: View) {
        if (v.isShown) scheduleSettleLog(v)
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
            if (v.isShown) focusHeaderAttempt(v, 0)
        }, delayMs)
    }

    /** Перенос фокуса на заголовок с подтверждением. Однократная отправка в
     *  [a11yFocus] могла теряться в гонке с авто-выбором ридера (msg2254: событие
     *  ушло, «ИТОГ: фокус на btnMore»). Здесь после паузы сверяем, что фокус
     *  реально на [v]; нет — повторяем (до 3 попыток). Повтор не даёт дубля
     *  озвучки: если фокус уже встал, a11yFocus сам не шлёт событие. */
    private fun focusHeaderAttempt(v: View, attempt: Int) {
        a11yFocus(v)
        v.postDelayed({
            if (v.isShown && !v.isAccessibilityFocused() && attempt < 2) {
                Diag.log(v.context, "focus", "заголовок не подтверждён — повтор ${attempt + 2}")
                focusHeaderAttempt(v, attempt + 1)
            }
        }, 400)
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
