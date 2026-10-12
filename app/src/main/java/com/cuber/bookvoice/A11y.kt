package com.cuber.bookvoice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ListView
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

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

    /**
     * КОНЕЦ СПИСКА НА ОЩУПЬ (08.10.2026, просьба Сержа: «сделай, чтобы это было и
     * в настройках, и в читалке, и в каталогах»).
     *
     * Зачем. Незрячий листает список свайпом вниз, список кончился — и в ответ
     * тишина: непонятно, то ли дальше правда пусто, то ли палец промахнулся и
     * жест не дошёл. Диктор на такой свайп шлёт окну действия
     * ACTION_SCROLL_FORWARD/BACKWARD; мы перехватываем их ровно в тот момент,
     * когда прокручивать уже некуда, и отвечаем толчком: низ — один
     * ([Vibra.edgeBottom]), верх — два ([Vibra.edgeTop]). Во всех остальных
     * случаях действие уходит дальше как обычно, прокрутка не тронута.
     *
     * Вешаем на любое прокручиваемое окно — список читалки, полку, каталог,
     * настройки. Прокручиваемость вью считается по факту, а не по числу строк в
     * разметке: на коротком окне, которое целиком влезло в экран, толчок будет с
     * обеих сторон — и это верно, листать там действительно нечего.
     *
     * ЧУЖОЙ ДЕЛЕГАТ НЕ ВЫБРАСЫВАЕМ. У окна каталога своя работа на том же
     * действии — автодогрузка следующей страницы (`CatalogActivity.setupAutoLoad`).
     * Поэтому сперва отдаём действие прежнему делегату и только если он его не
     * взял — считаем, что за краем ничего нет, и толкаем. Ставить этот вызов
     * надо ПОСЛЕ того, кто ставил делегата раньше, иначе он затрёт нас.
     *
     * ПОРЯДОК ПРОВЕРОК — ВАЖЕН (сборка 253 не толкала вовсе, журнал 09.10.2026
     * 01:06-01:08: Серж ходил по каталогу и не почувствовал ни одного толчка).
     * Сначала стояла отдача действия дальше и выход по `handled` — а платформа
     * на «прокрутить вперёд» у самого края отвечает «обработано» (прокрутка на
     * нулевую величину), и до толчка дело не доходило. Теперь КРАЙ ПРОВЕРЯЕМ
     * ПЕРВЫМ и в этом случае действие дальше не отдаём: спрашиваем только того,
     * у кого за краем есть своя работа ([handOff] — автодогрузка каталога), а
     * иначе толкаем.
     *
     * [mayVibrate] — необязательный вопрос «а сейчас-то толкать можно?». Каталогу
     * он нужен, чтобы молчать, пока идёт ПЕРВАЯ загрузка ленты: пустой список в
     * этот момент — не «конец списка», а «ещё не пришло», и о загрузке человеку
     * говорит строка состояния, а не толчок.
     *
     * [handOff] — необязательный вопрос «за этим краем есть кому работать?»:
     * у каталога там догрузка следующей страницы, и пока страницы есть, жест
     * обязан уйти ей, а не превращаться в толчок.
     *
     * ВТОРОЙ ПУТЬ — ФОКУС НА КРАЙНЕЙ СТРОКЕ (09.10.2026, вторая жалоба Сержа
     * «опять толчки не работают»). По журналу 01:39-01:40 у него в системе диктор
     * `com.nirenr.talkman`, и до нашего перехвата не дошло НИ ОДНОГО действия
     * прокрутки — при том что по строкам диктор ходит (в журнале видны переносы
     * фокуса). Значит, часть дикторов свайп в списке обрабатывает сама: либо
     * двигает фокус по строкам, либо прокручивает окно своим жестом, не посылая
     * ACTION_SCROLL_*. На такое наше действие не отреагирует никогда, поэтому
     * есть второе правило: как только фокус диктора ВСТАЁТ на ПЕРВУЮ или
     * ПОСЛЕДНЮЮ строку списка, отвечаем толчком (низ — один, верх — два).
     *
     * Осторожность, чтобы не жужжать зря: толкаем только на СМЕНЕ зоны (середина
     * → край) и не чаще, чем раз в [EDGE_BUZZ_MIN_GAP_MS]; при входе в окно фокус
     * встаёт на первую строку с «середины» — это не толчок, а обычный вход.
     * [rows] — контейнер строк (для RecyclerView — он сам, для ScrollView — его
     * внутренний LinearLayout). Без него второй путь выключен.
     *
     * ТРЕТИЙ ПУТЬ — САМО КАСАНИЕ (09.10.2026, слова Сержа: «я проверял и с
     * выключенным скринридером, никаких толчков не было — так что скринридер тут
     * ни при чём»). Он прав в том, что виноват не диктор, но проверка с
     * выключенным диктором по первому и второму пути и не могла ничего дать: оба
     * работают от ДИКТОРА (его действия и его фокус). Чтобы толчок был и без
     * диктора — а он у Сержа основной способ проверки, — слушаем само касание
     * ([EdgeGesture]).
     *
     * Прокрутку не перехватываем: слушатель касаний возвращает false, и жест
     * идёт дальше как обычно.
     *
     * Каждое решение пишется в журнал меткой «a11y» — по нему видно и то, что
     * диктор вообще шлёт прокрутку (иначе толчок искать негде), и в каком месте
     * списка это было.
     */
    fun edgeVibrate(
        v: View,
        where: String,
        mayVibrate: () -> Boolean = { true },
        handOff: () -> Boolean = { false },
        rows: View? = null,
        touch: Boolean = true,
        watch: (((MotionEvent) -> Unit) -> Unit)? = null,
    ) {
        val prev = v.accessibilityDelegate
        // -1 — в этом списке фокуса ещё не было (вход в окно), 0 — середина,
        // 1 — первая строка, 2 — последняя. Разница важна: при ВХОДЕ в окно
        // фокус встаёт на первую строку, и это не «дошли до начала» — толчка быть
        // не должно (жалоба Сержа 09.10.2026: «в настройках работает, но как-то
        // странно»).
        var zone = -1
        // ОТКУДА ПРИШЁЛ ПАЛЕЦ (09.10.2026, просьба Сержа: «когда иду на первый
        // элемент из заголовка, он по-прежнему не вибрирует — хочу знать, что он
        // первый»). Если жест начался ВНЕ списка — на шапке сверху или на кнопках
        // снизу, — приход фокуса на первую/последнюю строку это толчок, и он
        // нужен даже у короткого списка, который целиком влез в экран: там края
        // для прокрутки нет, но «это первая» человеку знать надо. 0 — изнутри
        // списка, 1 — сверху списка, 2 — снизу.
        var cameFrom = 0
        var cameAt = 0L
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                if (prev != null) {
                    prev.onInitializeAccessibilityNodeInfo(host, info)
                } else {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }

            override fun onRequestSendAccessibilityEvent(
                host: ViewGroup,
                child: View,
                event: AccessibilityEvent,
            ): Boolean {
                if (rows != null && event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    // КАКАЯ СТРОКА (09.10.2026, третья жалоба Сержа «в некоторых
                    // местах вообще не работает»). Событие фокуса приходит к нам
                    // не от строки, а от ПРЯМОГО ребёнка того вью, чей это делегат:
                    // у читалки и полки делегат стоит на самом списке, и ребёнок —
                    // строка; а у настроек, каталога и цитат делегат на ScrollView,
                    // и ребёнок — внутренний LinearLayout со всеми строками сразу.
                    // Прежнее сравнение `перваяСтрока === child` там не совпадало
                    // НИКОГДА: зона всегда выходила «середина», и толчка не было
                    // вовсе. Теперь строку ищем по фокусу внутри ребёнка
                    // ([rowOf]) — годится и для плоского списка, и для строки с
                    // вложенными вью.
                    val row = rowOf(rows, child)
                    val first = firstRowIn(rows)
                    val last = lastRowIn(rows)
                    val z = when {
                        row == null -> 0
                        last != null && row === last -> 2
                        first != null && row === first -> 1
                        else -> 0
                    }
                    if (z != zone) {
                        val was = zone
                        zone = z
                        val place = rowPlace(rows, row)
                        // Свежий ли приход СНАРУЖИ списка (с шапки или с нижних
                        // кнопок): по времени — палец мог уже уйти далеко.
                        val fresh = SystemClock.uptimeMillis() - cameAt < CAME_OUTSIDE_MS
                        val fromAbove = fresh && cameFrom == 1
                        val fromBelow = fresh && cameFrom == 2
                        val atTop = z == 1
                        val atBottom = z == 2
                        val scrollable = hasScroll(host)
                        // «Дошёл до края» — это приход из середины списка (и тогда
                        // список обязан прокручиваться) или приход СНАРУЖИ, с
                        // шапки/нижних кнопок: у короткого списка это единственный
                        // способ узнать, что строка первая или последняя.
                        val arrived = (atTop && (fromAbove || (was >= 0 && scrollable))) ||
                            (atBottom && (fromBelow || (was >= 0 && scrollable)))
                        when {
                            // СПИСОК, КОТОРЫЙ НЕ ПРОКРУЧИВАЕТСЯ, КРАЯ НЕ ИМЕЕТ
                            // (жалоба Сержа 09.10.2026: «ввёл одним пальцем снизу
                            // вверх, и вибрация срабатывала, хотя не должна»).
                            // Если список влез в экран целиком или ещё пуст, то
                            // «первая строка» и «последняя» — это весь список, и
                            // толчок говорил бы о конце там, где конца нет.
                            (atTop || atBottom) && !scrollable && !arrived ->
                                Diag.log(
                                    host.context, "a11y",
                                    "список ($where): фокус на краю ($place), но прокручивать нечего — молчу",
                                )
                            atBottom && arrived ->
                                buzzEdge(
                                    host, where,
                                    "фокус на последней строке ($place)" +
                                        if (fromBelow) ", пришёл снизу списка" else "",
                                    true, mayVibrate,
                                )
                            atTop && arrived ->
                                buzzEdge(
                                    host, where,
                                    "фокус на первой строке ($place)" +
                                        if (fromAbove) ", пришёл сверху списка" else "",
                                    false, mayVibrate,
                                )
                            atTop -> Diag.log(host.context, "a11y", "список ($where): вход — фокус на первой строке")
                            atBottom -> Diag.log(host.context, "a11y", "список ($where): вход — фокус на последней строке")
                            row == null -> Diag.log(host.context, "a11y", "список ($where): фокус не на строке списка")
                            else -> Diag.log(host.context, "a11y", "список ($where): фокус в середине списка ($place)")
                        }
                    }
                }
                // Событие не фильтруем: вернуть false значило бы отнять его у диктора.
                prev?.onRequestSendAccessibilityEvent(host, child, event)
                return true
            }

            override fun performAccessibilityAction(host: View, a: Int, args: Bundle?): Boolean {
                val forward = a == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                val backward = a == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (forward || backward) {
                    val atBottom = forward && !host.canScrollVertically(1)
                    val atTop = backward && !host.canScrollVertically(-1)
                    if (atBottom || atTop) {
                        val place = if (atBottom) "низ" else "верх"
                        if (handOff()) {
                            Diag.log(host.context, "a11y", "список ($where): $place — за краем есть работа, отдаю дальше")
                            return prev?.performAccessibilityAction(host, a, args) ?: false
                        }
                        buzzEdge(host, where, "жест диктора у края ($place)", atBottom, mayVibrate)
                        return true
                    }
                }
                val handled = if (prev != null) {
                    prev.performAccessibilityAction(host, a, args)
                } else {
                    super.performAccessibilityAction(host, a, args)
                }
                if (forward || backward) {
                    Diag.log(
                        host.context, "a11y",
                        "список ($where): прокрутка ${if (forward) "вперёд" else "назад"}, край не достигнут" +
                            " (сделано: $handled)",
                    )
                }
                return handled
            }
        }

        if (touch || watch != null) {
            // Третий путь: слушаем само касание. Слушатель ничего не перехватывает.
            //
            // СЛУШАТЬ НАДО ВСЁ ОКНО ([watch], 09.10.2026, вопросы Сержа: «спускаюсь
            // пальцем с заголовка на первую книгу — не вибрирует, это так
            // задумано?» и «и на нижних также сделал элементах?»). У касания ОДИН
            // адресат: жест, начавшийся на шапке окна или на нижних кнопках, до
            // слушателя списка не доходит вовсе, и намерение «хочу выше первой
            // книги / ниже последней» оставалось без ответа. Поэтому окно отдаёт
            // нам ВСЕ свои касания ([watch] — подписка окна), а «есть ли куда
            // крутить» по-прежнему решает СПИСОК: у обычной панели
            // `canScrollVertically` отвечает «нет», и толчок молчал бы всегда.
            val gesture = EdgeGesture(v, where, mayVibrate, handOff)
            val feed: (MotionEvent) -> Unit = { e ->
                gesture.onTouch(e)
                // Где начался жест ОТНОСИТЕЛЬНО списка — это и решает, считать ли
                // приход фокуса на первую строку толчком у короткого списка
                // (см. `cameFrom` выше). Координаты события — оконные, значит и
                // положение списка берём в окне.
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val loc = IntArray(2)
                    v.getLocationInWindow(loc)
                    cameFrom = when {
                        e.y < loc[1] -> 1
                        e.y > loc[1] + v.height -> 2
                        else -> 0
                    }
                    cameAt = SystemClock.uptimeMillis()
                }
            }
            if (watch != null) {
                watch(feed)
            } else if (touch) {
                v.setOnTouchListener { _, e ->
                    feed(e)
                    false
                }
            }
        }
    }

    /**
     * ТОЛЧОК У КРАЯ ДЛЯ ЛЮБОГО ОКНА (09.10.2026). Просьба Сержа: «предложи, где
     * ещё можно на длинных списках сделать вибрацию в разных окнах». Окна-секции
     * ([SectionActivity] и всё, что от неё) получают толчок, НИЧЕГО не зная о
     * своей разметке: мы сами ищем в собранном окне то, что прокручивается
     * ([scrollIn]), и вешаем на него [edgeVibrate].
     *
     * Список, который влез в экран, промолчит сам: у него нет края
     * ([hasScroll]). Поэтому звать можно смело — вреда от этого не будет.
     */
    fun edgeVibrateAuto(
        root: View,
        where: String,
        mayVibrate: () -> Boolean = { true },
        watch: (((MotionEvent) -> Unit) -> Unit)? = null,
    ) {
        val scroll = scrollIn(root) ?: return
        val rows = when (scroll) {
            is RecyclerView, is ListView -> scroll
            else -> (scroll as? ViewGroup)
                ?.takeIf { it.childCount == 1 }
                ?.getChildAt(0) as? ViewGroup
        }
        Diag.log(root.context, "a11y", "окно ($where): толчок у края повешен на ${scroll.javaClass.simpleName}")
        // Касания слушает ВСЁ окно ([watch]), а край меряем по списку ([scroll]).
        edgeVibrate(scroll, where, mayVibrate, rows = rows, watch = watch)
    }

    /**
     * ТОЛЧОК У КРАЯ СПИСКА В ДИАЛОГЕ (09.10.2026): «Оглавление», выбор голоса,
     * движка или языка, недавние книги — списки длиннее экрана, а дойти до
     * конца вслепую нечем.
     *
     * Звать ПОСЛЕ `show()`: до показа списка в диалоге ещё нет. `setOnShowListener`
     * не трогаем — у «Оглавления» он свой (ставит выбор на текущую главу).
     *
     * Длинный ТЕКСТ в диалоге («Что нового») — тоже случай этой функции: если
     * строк-списка нет, берём то, что прокручивается внутри окна диалога.
     */
    fun edgeVibrateDialog(
        dialog: AlertDialog,
        where: String,
        mayVibrate: () -> Boolean = { true },
    ) {
        val list = runCatching { dialog.listView }.getOrNull()
        if (list != null && list.adapter != null && list.adapter.count > 0) {
            edgeVibrate(list, where, mayVibrate, rows = list)
            return
        }
        val root = dialog.window?.decorView ?: return
        edgeVibrateAuto(root, where, mayVibrate)
    }

    /**
     * ТОЛЧОК У КРАЯ ШКАЛЫ — у ползунка (09.10.2026). Не край списка, а край
     * значения: скорость, громкость тихого потока, перемотка. У самого края
     * значение уже не меняется, и человек не слышит НИЧЕГО — непонятно, дальше
     * некуда или свайп не дошёл. Язык тот же, что у края списка: низ — один
     * толчок, верх — два ([Vibra.edgeBottom] / [Vibra.edgeTop]).
     *
     * Общая задержка ([EDGE_BUZZ_MIN_GAP_MS]) — как у списков: один и тот же
     * жест не жужжит дважды.
     */
    fun scaleEdge(view: View, where: String, atBottom: Boolean, mayVibrate: () -> Boolean = { true }): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeBuzzAt < EDGE_BUZZ_MIN_GAP_MS) return false
        if (!mayVibrate()) return false
        lastEdgeBuzzAt = now
        Diag.log(
            view.context,
            "a11y",
            "ползунок ($where): ${if (atBottom) "дальше некуда (максимум)" else "выше некуда (минимум)"} — толчок",
        )
        if (atBottom) Vibra.edgeBottom(view.context) else Vibra.edgeTop(view.context)
        return true
    }

    /** Первое, что прокручивается ВНИЗ-ВВЕРХ, в дереве окна: список
     *  ([RecyclerView], [ListView]) или скролл-контейнер ([ScrollView],
     *  [NestedScrollView]). Ищем ПО ТИПУ: до раскладки `canScrollVertically`
     *  ещё отвечает «нет» даже у длинного списка.
     *
     *  Горизонтальные карусели пропускаем: у них края по вертикали нет, а
     *  остановиться на первой попавшейся значило бы оставить вертикальный список
     *  окна без толчка. */
    private fun scrollIn(root: View): View? {
        when {
            root is RecyclerView -> {
                val lm = root.layoutManager
                if (lm is LinearLayoutManager && lm.orientation != LinearLayoutManager.VERTICAL) {
                    return null
                }
                return root
            }
            root is ListView || root is ScrollView || root is NestedScrollView -> return root
            root !is ViewGroup -> return null
        }
        for (i in 0 until root.childCount) {
            val found = scrollIn(root.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * ПАЛЕЦ ТЯНЕТ СПИСОК ЗА КРАЙ (третий путь, 09.10.2026).
     *
     * Так это сделано в прошивках: список доехал до конца, человек продолжает
     * тянуть — и получает короткий отклик ровно в этот момент, а не «иногда».
     * Поэтому толчок даём ПРЯМО В ДВИЖЕНИИ (ACTION_MOVE), как только палец ушёл
     * от места нажатия дальше [EDGE_TOUCH_MIN_DP], а список в эту сторону уже не
     * прокручивается. Один жест — один толчок: пока палец не оторвали, второй
     * раз не жужжим (флаг [fired]).
     *
     * ЖЕСТ ОПОЗНАЁМ ПО downTime, А НЕ ПО ACTION_DOWN (09.10.2026, четвёртая
     * жалоба Сержа: «в некоторых местах вообще не работает, в некоторых
     * срабатывает»). Причина: строки у нас почти везде сами обрабатывают
     * нажатие — книга в каталоге, строка настроек, предложение в читалке, — и
     * тогда ACTION_DOWN до контейнера НЕ доезжает: ребёнок забирает жест себе, а
     * контейнер начинает получать события только после перехвата прокрутки, то
     * есть с ACTION_MOVE. Прежний код ждал DOWN: место нажатия оставалось
     * нулевым (или от прошлого жеста), а флаг «уже жужжал» — поднятым навсегда,
     * потому что ACTION_UP тоже уходил ребёнку. Отсюда и «срабатывает
     * непонятно»: работало только там и только тогда, где палец попадал в
     * промежуток между строками.
     *
     * Теперь точкой отсчёта становится первое событие жеста, которое мы вообще
     * увидели (у всех событий одного касания общий `downTime`), а новый `downTime`
     * — это новый жест. Прокрутку по-прежнему не перехватываем.
     *
     * ДВУМЯ ПАЛЬЦАМИ — ТОЖЕ (09.10.2026, слова Сержа: «это должно работать и вовсе
     * без скринридера, а со скринридером просто добавляется второй палец»). Поэтому
     * считаем середину между всеми пальцами ([middle]) и пересчитываем точку
     * отсчёта, когда палец добавили или убрали.
     *
     * [handOff] — тот же вопрос, что и у жеста диктора: у каталога за краем
     * списка живёт догрузка следующей страницы, и пока страницы есть, толчок
     * молчит (иначе «конец списка» звучал бы там, где список ещё не кончился).
     */
    class EdgeGesture(
        private val view: View,
        private val where: String,
        private val mayVibrate: () -> Boolean = { true },
        private val handOff: () -> Boolean = { false },
    ) {
        /** Номер жеста платформы (`downTime`): у всех событий одного касания он
         *  общий, у нового касания — другой. По нему и понимаем, что жест новый. */
        private var gestureDownTime = -1L
        private var downY = 0f
        private var fired = false

        /** Середина между пальцами на прошлом событии — по ней видно, что набор
         *  пальцев изменился (см. [middle]). */
        private var lastY = 0f
        private var pointerCount = 0

        /** По этому жесту решение уже принято и записано — второй раз не пишем. */
        private var decided = false

        /** Где пальцы в среднем по вертикали (09.10.2026, слова Сержа: «это должно
         *  работать и вовсе без скринридера, а со скринридером просто добавляется
         *  второй палец»).
         *
         *  Считаем СЕРЕДИНУ между всеми пальцами, а не первый: диктор одиночные
         *  свайпы забирает себе, и человек листает двумя пальцами — тянуть может
         *  любой из них, а первый при этом стоять на месте. По первому пальцу
         *  жест выглядел бы как «палец не двигался», и толчка не было бы. */
        private fun middle(e: MotionEvent): Float {
            var sum = 0f
            for (i in 0 until e.pointerCount) sum += e.getY(i)
            return sum / e.pointerCount
        }

        fun onTouch(e: MotionEvent) {
            val y = middle(e)
            if (e.downTime != gestureDownTime) {
                gestureDownTime = e.downTime
                downY = y
                lastY = y
                pointerCount = e.pointerCount
                fired = false
                decided = false
            } else if (e.pointerCount != pointerCount) {
                // Палец добавили или убрали — середина прыгнула сама по себе.
                // Сдвигаем точку отсчёта на столько же, иначе счёт смещения врёт
                // и толчок приходит не там.
                downY += y - lastY
                pointerCount = e.pointerCount
            }
            lastY = y
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = y
                    fired = false
                    decided = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fired || decided) return
                    val density = view.resources.displayMetrics.density
                    val dy = y - downY
                    val min = density * EDGE_TOUCH_MIN_DP
                    if (abs(dy) < min) return
                    val canForward = view.canScrollVertically(1)
                    val canBack = view.canScrollVertically(-1)
                    // СПИСОК, КОТОРЫЙ НЕ ПРОКРУЧИВАЕТСЯ, КРАЯ НЕ ИМЕЕТ (жалоба
                    // Сержа 09.10.2026: «ввёл одним пальцем снизу вверх, и
                    // вибрация срабатывала, хотя не должна»). Прежде в коротком
                    // списке (влез в экран) или в ещё не наполненном списке
                    // `canScrollVertically` отвечает «нет» в ОБЕ стороны — и
                    // толчок звучал на любом движении пальца, хотя листать там
                    // было нечего. Молчим: толчок — про КОНЕЦ списка, который
                    // листали, а не про его отсутствие.
                    if (!canForward && !canBack) {
                        decided = true
                        Diag.log(
                            view.context, "a11y",
                            "список ($where): палец тянет ${if (dy < 0) "вперёд" else "назад"} " +
                                "(${(abs(dy) / density).toInt()}dp), прокручивать нечего — молчу",
                        )
                        return
                    }
                    val reason = "палец тянет за ${if (dy < 0) "нижний" else "верхний"} край " +
                        "(${(abs(dy) / density).toInt()}dp)"
                    if (dy <= -min && !canForward && !handOff()) {
                        if (buzzEdge(view, where, reason, true, mayVibrate)) fired = true
                    } else if (dy >= min && !canBack) {
                        if (buzzEdge(view, where, reason, false, mayVibrate)) fired = true
                    } else {
                        decided = true
                        Diag.log(
                            view.context, "a11y",
                            "список ($where): $reason — край не достигнут, молчу",
                        )
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> fired = false
            }
        }
    }

    /** Общий толчок у края для всех путей: одна задержка на всех, чтобы один и
     *  тот же жест не жужжал дважды, если о нём сказали два пути сразу. Задержка
     *  КОРОТКАЯ ([EDGE_BUZZ_MIN_GAP_MS]): она склеивает одновременные сообщения,
     *  а не глушит следующую попытку человека. В 253c стояло 1,2 с, и каждая
     *  вторая попытка в настройках пропадала («работает как-то странно»). */
    private var lastEdgeBuzzAt = 0L

    private fun buzzEdge(
        view: View,
        where: String,
        what: String,
        atBottom: Boolean,
        mayVibrate: () -> Boolean,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeBuzzAt < EDGE_BUZZ_MIN_GAP_MS) {
            Diag.log(view.context, "a11y", "список ($where): $what — толчок не повторяю, только что был")
            return false
        }
        if (!mayVibrate()) {
            Diag.log(view.context, "a11y", "список ($where): $what — толчок сейчас нельзя, молчу")
            return false
        }
        lastEdgeBuzzAt = now
        Diag.log(view.context, "a11y", "список ($where): $what — толчок")
        if (atBottom) Vibra.edgeBottom(view.context) else Vibra.edgeTop(view.context)
        return true
    }

    /** Есть ли в этом вью что прокручивать ХОТЬ КУДА-НИБУДЬ (09.10.2026).
     *
     *  Отличается от проверки одного края: список, который влез в экран целиком
     *  или ещё не наполнен, отвечает «нет» в обе стороны, и это не «конец
     *  списка», а «списка пока нет». По этой проверке толчок молчит — иначе
     *  вибрация звучала бы на любом движении пальца (жалоба Сержа). */
    private fun hasScroll(v: View): Boolean = v.canScrollVertically(1) || v.canScrollVertically(-1)

    /** Место строки для журнала: «строка 3 из 40». Нужно, чтобы по жалобе
     *  «толчок не там» было видно, какую строку мы сочли краем. Пусто — если
     *  строку опознать не удалось или контейнер не список. */
    private fun rowPlace(rows: View, row: View?): String {
        if (row == null || rows !is RecyclerView) return "?"
        val pos = rows.getChildAdapterPosition(row)
        val n = rows.adapter?.itemCount ?: 0
        return if (pos < 0) "?" else "строка ${pos + 1} из $n"
    }

    /** Первая строка списка, до которой может добраться диктор. Для
     *  RecyclerView — строка с позицией 0 (из уже разложенных), для обычного
     *  контейнера — первый видимый ребёнок, в котором есть на чём остановиться. */
    private fun firstRowIn(host: View): View? {
        if (host is RecyclerView) {
            val lm = host.layoutManager as? LinearLayoutManager ?: return null
            if ((host.adapter?.itemCount ?: 0) == 0) return null
            for (i in 0 until host.childCount) {
                val c = host.getChildAt(i)
                if (lm.getPosition(c) == 0) return c
            }
            return null
        }
        if (host !is ViewGroup) return null
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (isRow(c)) return c
        }
        return null
    }

    /** Последняя строка списка — так же, как [firstRowIn]. */
    private fun lastRowIn(host: View): View? {
        if (host is RecyclerView) {
            val lm = host.layoutManager as? LinearLayoutManager ?: return null
            val n = host.adapter?.itemCount ?: 0
            if (n == 0) return null
            for (i in host.childCount - 1 downTo 0) {
                val c = host.getChildAt(i)
                if (lm.getPosition(c) == n - 1) return c
            }
            return null
        }
        if (host !is ViewGroup) return null
        for (i in host.childCount - 1 downTo 0) {
            val c = host.getChildAt(i)
            if (isRow(c)) return c
        }
        return null
    }

    /** Может ли диктор остановиться в этом вью: либо оно само ловит фокус, либо
     *  фокус ловит что-то внутри него (у строк бывают вложенные вью). */
    private fun isRow(v: View): Boolean =
        v.isShown && (v.isFocusable || hasFocusableInside(v))

    private fun hasFocusableInside(v: View): Boolean {
        if (v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c.isShown && (c.isFocusable || hasFocusableInside(c))) return true
        }
        return false
    }

    /** Какая строка контейнера [rows] получила фокус (09.10.2026).
     *
     *  Ребёнок, пришедший с событием, — не всегда строка: у ScrollView-окон это
     *  внутренний LinearLayout со всеми строками сразу (см. разбор в
     *  [edgeVibrate]). Поэтому ищем внутри него того, на кого диктор встал
     *  ([focusedInside]), и поднимаемся до ПРЯМОГО ребёнка [rows] — это и есть
     *  строка. Не нашли — отдаём [child] как есть: пусть сравнение с краями
     *  честно не совпадёт, чем совпадёт случайно. */
    private fun rowOf(rows: View, child: View): View? {
        var cur: View = focusedInside(child) ?: child
        var p: ViewParent? = cur.parent
        while (p != null && p !== rows) {
            cur = p as? View ?: return null
            p = cur.parent
        }
        return if (p === rows) cur else null
    }

    /** Вью, на котором сейчас доступностный фокус, внутри [v] (включая сам [v]). */
    private fun focusedInside(v: View): View? {
        if (v.isAccessibilityFocused) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                focusedInside(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

}

/** Номер первого своего действия узла — как в платформе
 *  (`AccessibilityNodeInfo.ACTION_ID_FIRST_CUSTOM_ACTION`); эта константа в SDK
 *  скрыта, поэтому берём её значение. */
private const val A11Y_ACTION_FIRST = 0x01000000

/** Ближе этого срока два толчка у края списка не даём (09.10.2026): о крае могут
 *  сказать два пути сразу — жест диктора и палец. Задержка КОРОТКАЯ: она
 *  склеивает одновременные сообщения, а не глушит следующую попытку человека
 *  (при 1,2 с в 253c каждая вторая попытка в настройках пропадала). */
private const val EDGE_BUZZ_MIN_GAP_MS = 300L

/** Насколько палец должен пройти по вертикали, чтобы это считалось жестом
 *  «листаю дальше», а не касанием-нажатием (в dp). */
private const val EDGE_TOUCH_MIN_DP = 40f

/** Сколько помним, что жест начался ВНЕ списка — на шапке сверху или на нижних
 *  кнопках (09.10.2026). Нужно фокусному пути: приход на первую строку с шапки —
 *  это толчок, а тот же приход от автоматического фокуса при входе в окно — нет.
 *  1,5 с хватает: человек ведёт палец с шапки на первую книгу почти сразу. */
private const val CAME_OUTSIDE_MS = 1500L

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
