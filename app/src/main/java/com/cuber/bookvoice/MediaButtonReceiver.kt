package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent

/**
 * Приёмник медиа-кнопок (Bluetooth-гарнитура, проводные наушники,
 * «волшебное касание» TalkBack) и кнопок нашего уведомления.
 *
 * Система и наши кнопки в шторке шлют сюда ACTION_MEDIA_BUTTON. Здесь мы достаём
 * KeyEvent и отдаём его службе ([MediaSessionService.onMediaButton]) — она решает
 * по фактическому состоянию движка, что делать: переключить чтение, листать или
 * пауза. Железные кнопки, кроме того, приходят прямо в колбэк медиа-сессии
 * (Media3), и от двойного срабатывания спасает засечка в службе.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        Diag.log(
            context, "receiver",
            "получено ACTION_MEDIA_BUTTON: ${key?.action?.let { if (it == KeyEvent.ACTION_DOWN) "DOWN" else "UP" } ?: "нет ключа"} " +
                "keyCode=${key?.keyCode ?: "?"} (${key?.let { keyLabel(it.keyCode) } ?: "?"})"
        )
        // Под Media3-сессией транслировать в неё нечего: у неё нет
        // dispatchMediaButtonEvent, а железные кнопки она получает сама, прямо в
        // колбэке. Поэтому приёмник раскладывает клавишу через службу — она отдаёт
        // её движку по тому же правилу, что и раньше (30.09.2026).
        if (key != null) MediaSessionService.onMediaButton(key)
    }

    private fun keyLabel(code: Int): String = when (code) {
        KeyEvent.KEYCODE_HEADSETHOOK -> "play/pause"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play/pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "stop"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
        else -> "код $code"
    }
}
