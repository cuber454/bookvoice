package com.cuber.bookvoice

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
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
}

/**
 * Лента, которая для экранного диктора не «список» (msg6260).
 *
 * RecyclerView сам помечает себя коллекцией, а каждую строку — её элементом, и
 * диктор на каждой строке называет позицию: «12 из 340». На книге это шум:
 * номер не говорит, где ты в тексте, и перебивает чтение.
 *
 * Снимаем ровно две пометки — «я список» у ленты и «я элемент N из M» у
 * строки. Сначала зовём super, потом обнуляем, поэтому всё остальное
 * (прокрутка, действия доступности, порядок обхода) остаётся нетронутым: это
 * разметка для диктора, а не поведение. Ни вёрстка, ни место чтения не задеты.
 *
 * Обнуляем через framework-методы: у compat-сеттера в старых сборках core
 * параметр не помечен nullable, и передача null туда — лотерея.
 */
class NoListMarksLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onInitializeAccessibilityNodeInfo(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        info: AccessibilityNodeInfoCompat,
    ) {
        super.onInitializeAccessibilityNodeInfo(recycler, state, info)
        info.unwrap().collectionInfo = null
    }

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
