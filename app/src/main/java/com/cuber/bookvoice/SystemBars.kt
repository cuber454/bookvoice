package com.cuber.bookvoice

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Прижим окон к безопасной зоне под системными барами (миграция SDK 36, msg2202+).
 *
 * С targetSdk 35 Android рисует окно в edge-to-edge (под статус-баром и
 * навигационной полосой), а на Android 16 opt-out windowOptOutEdgeToEdgeEnforcement
 * больше не работает — принудительно. Без обработки инсетов шапки окон уедут под
 * часы/батарею, а нижние кнопки («Открыть книгу» на полке, ряды читалки) — под
 * жестовую зону.
 *
 * Включаем тот же режим единообразно на всех версиях (enableEdgeToEdge сам
 * разруливает старые API: цвета баров, иконки) и компенсируем системные бары
 * отступами корня окна: верхний = статус-бар, нижний = навигационная область.
 * Базовые padding корня (в XML у окон 6dp) сохраняются — инсеты кладутся поверх.
 */
fun ComponentActivity.edgeToEdge(root: View) {
    enableEdgeToEdge()
    var first = true
    var baseLeft = 0
    var baseTop = 0
    var baseRight = 0
    var baseBottom = 0
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        if (first) {
            baseLeft = v.paddingLeft
            baseTop = v.paddingTop
            baseRight = v.paddingRight
            baseBottom = v.paddingBottom
            first = false
        }
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(baseLeft, baseTop + bars.top, baseRight, baseBottom + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
