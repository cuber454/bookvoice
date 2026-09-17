package com.cuber.bookvoice

import android.content.Context
import android.view.accessibility.AccessibilityManager

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
