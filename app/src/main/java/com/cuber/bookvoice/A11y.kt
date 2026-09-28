package com.cuber.bookvoice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Мелкие операции с экранным диктором (msg6175).
 *
 * [hush] обрывает текущую озвучку. Нужна там, где мы открываем микрофон:
 * диктор объявляет нажатую кнопку словами («Поиск. Долгое нажатие — голосовой
 * поиск»), и этот голос уходит в микрофон вместо фразы владельца.
 * interrupt() — штатный публичный API: гасит текущую озвучку, ничего не ломая.
 * Диктор, который его не слушает, просто продолжит говорить — хуже не станет.
 */
object A11y {
    fun hush(ctx: Context) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return
        runCatching { am.interrupt() }
    }

    /** Какие дикторы включены в системе (msg6338).
     *
     *  Нужно для разбора «TalkBack не видит текст, Jieshuo видит»: по логу видно,
     *  кого именно мы обслуживаем, и менялся ли набор между запусками. Пишем один
     *  раз при старте окна читалки — строка короткая, без личных данных. */
    fun logReaders(ctx: Context) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return
        val on = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
        Diag.log(
            ctx, "a11y",
            "дикторы в системе: ${if (on.isEmpty()) "нет" else on.joinToString()}",
        )
    }

    /** Кнопка, долгое нажатие которой диктор не объявляет второй раз (msg6288).
     *
     *  Долгое нажатие диктор объявляет сам — и не в момент жеста, а следом за
     *  ним: framework после нажатия рассылает событие TYPE_VIEW_LONG_CLICKED, и
     *  на него приходит второе объявление той же кнопки. Владелец слышит одну и
     *  ту же фразу дважды («долгое нажатие — голосовой поиск», затем снова), при
     *  этом второй раз — уже поверх открытого микрофона.
     *
     *  Объявление кнопки под фокусом нам нужно: из него владелец узнаёт о жесте
     *  (подсказка живёт в contentDescription). А повтор — нет. Гасим ровно это
     *  событие, все остальные пропускаем как есть.
     */
    fun muteLongClickAnnouncement(v: View) {
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
                super.sendAccessibilityEvent(host, eventType)
            }
        }
    }

    /** Наблюдение за доступностным фокусом окна (29.09.2026).
     *
     *  Жалоба Сержа: в книге свайпы по строкам иногда уводят фокус на кнопку
     *  «Ещё» или «Читать/Пауза» сами по себе. Спросить у службы доступности,
     *  куда она встала, приложение не может — поэтому пишем каждое событие
     *  переноса: по журналу видно и когда это случилось, и на что именно встал
     *  диктор (кнопка это, строка текста или служебный текст вроде «Стр. 3»).
     *
     *  Ставим на корень окна: событие ребёнка проходит через родителя. Своё
     *  поведение не меняем — только пишем. */
    fun watchFocus(root: ViewGroup, where: String, state: (() -> String)? = null) {
        // Изменения дерева пишем не чаще раза в [CONTENT_LOG_GAP_MS]: они сыплются
        // пачками, а нужны только как метка времени — «в этот момент окно
        // перестроилось». По ней видно, наш ли это пересбор ленты сбросил фокус
        // (жалоба Сержа 29.09.2026: при обходе вверх фокус ушёл на кнопки).
        var lastContentAt = 0L
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent,
            ): Boolean {
                val extra = state?.let { runCatching { it() }.getOrNull().orEmpty() }.orEmpty()
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> Diag.log(
                        root.context, "focus",
                        "$where: диктор встал на ${who(event)}" +
                            if (extra.isEmpty()) "" else " [$extra]"
                    )
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> Diag.log(
                        root.context, "focus",
                        "$where: диктор ушёл с ${who(event)}" +
                            if (extra.isEmpty()) "" else " [$extra]"
                    )
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastContentAt >= CONTENT_LOG_GAP_MS) {
                            lastContentAt = now
                            Diag.log(
                                root.context, "focus",
                                "$where: окно перестроилось (тип ${event.contentChangeTypes})" +
                                    if (extra.isEmpty()) "" else " [$extra]"
                            )
                        }
                    }
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
    }

    /** Как часто писать в журнал перестройку окна (мс). */
    private const val CONTENT_LOG_GAP_MS = 700L

    /** Кто именно получил фокус (29.09.2026). Спрашиваем у ИСТОЧНИКА события, а не
     *  у ребёнка, через которого оно прошло: `child` здесь — прямой ребёнок корня
     *  (у читалки это контейнер текста), по нему кнопку не узнать, а именно её и
     *  надо назвать — жалоба Сержа «фокус улетает на кнопку Ещё или Читать».
     *  Даём имя из разметки, класс и текст; у кнопки-значка текста нет, поэтому
     *  читаем ещё и описание. */
    private fun who(event: AccessibilityEvent): String {
        val src = runCatching { event.source }.getOrNull()
        val id = src?.viewIdResourceName?.substringAfterLast('/')
        val cls = src?.className?.toString()?.substringAfterLast('.')
        val text = event.text.joinToString(" ").trim().take(40)
        val desc = src?.contentDescription?.toString()?.trim()?.take(40).orEmpty()
        val name = when {
            text.isNotEmpty() -> "«$text»"
            desc.isNotEmpty() -> "«$desc»"
            else -> "(без текста)"
        }
        return "${id ?: "?"}|${cls ?: "?"} $name"
    }

    /**
     * Убрать из объявления кнопки само упоминание долгого нажатия, оставив само
     * действие (28.09.2026, просьба Сержа: «чтобы не говорил долгое нажатие,
     * вибрации достаточно»).
     *
     *  Почему так. Диктор объявляет «долгое нажатие» при каждом попадании на
     *  кнопку, у которой оно есть, — слова повторяются на каждой кнопке и на
     *  каждом обходе, а толку в них нет: о срабатывании говорит вибрация, она на
     *  этих кнопках уже есть ([Vibra.confirm]).
     *
     *  Действие при этом не теряется: вместо системного «долгое нажатие» кладём
     *  своё именованное действие в меню диктора (пункт «Действия») — тем же
     *  способом, что и действия строк (msg7003). Кто не может удержать палец,
     *  выберет его из меню, а не потеряет вовсе.
     */
    fun replaceLongPress(v: View, label: String, action: () -> Unit) {
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(A11Y_ACTION_FIRST + 40, label))
            }

            override fun performAccessibilityAction(host: View, a: Int, args: Bundle?): Boolean {
                if (a == A11Y_ACTION_FIRST + 40) {
                    action()
                    return true
                }
                return super.performAccessibilityAction(host, a, args)
            }
        }
    }

}

/** Номер первого своего действия узла — как в платформе
 *  (`AccessibilityNodeInfo.ACTION_ID_FIRST_CUSTOM_ACTION`); эта константа в SDK
 *  скрыта, поэтому берём её значение. */
private const val A11Y_ACTION_FIRST = 0x01000000

/**
 * Свои действия строки для меню TalkBack, пункт «Действия» (0.4.83, msg7003).
 *
 * Зачем. Действия строки — скачать, поделиться, удалить, в цитаты — жили только
 * на долгом нажатии: кто не может удержать палец (дрожат руки, протез, телефон в
 * держателе), до них не добирался вовсе. Именованные действия диктор показывает
 * списком в меню TalkBack, в пункте «Действия», и они делают ровно то же одним
 * выбором. Меню открывается тремя пальцами по экрану (в старых версиях TalkBack
 * это называлось «локальное контекстное меню», свайп вверх, затем влево).
 *
 * Почему не подпись долгого нажатия (ACTION_LONG_CLICK). Подпись диктор читает
 * прямо в объявлении строки: на каждой строке списка прибавилась бы фраза
 * «долгое нажатие — …», и полка превратилась бы в многословие. Именованные
 * действия в обычном обходе молчат — их слышно, только когда меню открыто
 * осознанно.
 *
 * Действия задаются парами «подпись — что сделать», порядок сохраняется. Звать
 * после того, как на вью повешены слушатели: подпись и поступок должны совпадать
 * — жест и действие делают одно и то же.
 */
fun View.setA11yActions(vararg actions: Pair<String, () -> Unit>) {
    if (actions.isEmpty()) return
    val list = actions.toList()
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            list.forEachIndexed { i, (label, _) ->
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(A11Y_ACTION_FIRST + i, label)
                )
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            val i = action - A11Y_ACTION_FIRST
            if (i in list.indices) {
                list[i].second()
                return true
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }
}

/**
 * Лента, у которой диктор не называет счёт строк: «12 из 340» (msg6300).
 *
 * RecyclerView сам помечает себя коллекцией, а каждую строку — её элементом, и
 * диктор на каждой строке называет позицию. На книге это шум: номер не говорит,
 * где ты в тексте, и перебивает чтение.
 *
 * Снимаем ТОЛЬКО пометку строки («я элемент N из M»), а ленте метку коллекции
 * оставляем намеренно. История: в 0.4.46 (msg6260) снимались обе пометки, и у
 * TalkBack перестала работать автоматическая прокрутка текста свайпами — фокус
 * вместо следующей строки прыгал на нижнюю (строку текущей главы). Диктору
 * метка списка нужна, чтобы понимать, что он прокручивает контейнер. Откат —
 * одна строка в MainActivity (`layoutManager = LinearLayoutManager(this)`),
 * см. [[project_bookvoice_nolist]].
 *
 * Сначала зовём super, потом обнуляем, поэтому всё остальное (прокрутка,
 * действия доступности, порядок обхода) остаётся нетронутым: это разметка для
 * диктора, а не поведение. Ни вёрстка, ни место чтения не задеты.
 *
 * Обнуляем через framework-метод: у compat-сеттера в старых сборках core
 * параметр не помечен nullable, и передача null туда — лотерея.
 */
class NoItemCountLayoutManager(private val ctx: Context) : LinearLayoutManager(ctx) {

    override fun onInitializeAccessibilityNodeInfoForItem(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        host: View,
        info: AccessibilityNodeInfoCompat,
    ) {
        super.onInitializeAccessibilityNodeInfoForItem(recycler, state, host, info)
        info.unwrap().collectionItemInfo = null
    }

    /**
     * Держать разложенными строки за краем экрана — по ТРИ экрана сверху и снизу
     * (29.09.2026).
     *
     * Зачем. Диктор ходит по предложениям, а предложение — ВИРТУАЛЬНЫЙ узел
     * внутри строки ленты (см. [ParagraphView]). Узел существует, только пока
     * строка разложена: у строки за краем экрана узлов нет вовсе, и свайп по
     * тексту упирается в пустоту — диктор уходит из ленты на кнопку («Ещё» и
     * кнопки шапки сверху, «Читать/Пауза» снизу). Это и есть жалоба Сержа
     * «фокус иногда улетает на кнопку сам по себе».
     *
     * Одного экрана оказалось мало (журнал 01:51): абзацы в книге высокие, в
     * запас попадало всего две-три строки, и обход всё равно выходил на кнопки.
     * Три экрана дают запас в 6–10 строк — обход уходит глубже в текст, а
     * показать строку на экране просит уже сам диктор (ACTION_SHOW_ON_SCREEN,
     * см. ParagraphView.onSentenceShow).
     *
     * Платим раскладкой лишнего текста — это доли того, что лента уже держит
     * целой книгой.
     */
    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        super.calculateExtraLayoutSpace(state, extraLayoutSpace)
        val screen = (if (height > 0) height else ctx.resources.displayMetrics.heightPixels) * EXTRA_SCREENS
        extraLayoutSpace[0] = screen
        extraLayoutSpace[1] = screen
    }

    private companion object {
        /** Сколько экранов текста держать разложенными за краем. */
        const val EXTRA_SCREENS = 3
    }
}
