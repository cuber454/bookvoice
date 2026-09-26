package com.cuber.bookvoice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.view.View
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
class NoItemCountLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onInitializeAccessibilityNodeInfoForItem(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        host: View,
        info: AccessibilityNodeInfoCompat,
    ) {
        super.onInitializeAccessibilityNodeInfoForItem(recycler, state, host, info)
        info.unwrap().collectionItemInfo = null
    }
}
