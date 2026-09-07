package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent

/**
 * Приёмник медиа-кнопок (Bluetooth-гарнитура, проводные наушники,
 * «волшебное касание» TalkBack).
 *
 * Система шлёт сюда ACTION_MEDIA_BUTTON. [MainActivity] при создании
 * сессии кладёт её в [sessionRef], здесь мы достаём KeyEvent и передаём
 * его в активную сессию через dispatchMediaButtonEvent — она уже сама
 * превращает кнопку в play/pause/next/prev (см. колбэк сессии).
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
        val session = sessionRef
        Diag.log(
            context, "receiver",
            "получено ACTION_MEDIA_BUTTON: ${key?.action?.let { if (it == KeyEvent.ACTION_DOWN) "DOWN" else "UP" } ?: "нет ключа"} " +
                "keyCode=${key?.keyCode ?: "?"} (${key?.let { keyLabel(it.keyCode) } ?: "?"}), " +
                "сессия ${if (session != null) "есть" else "НЕТ"}"
        )
        // Кнопка гарнитуры транслируется в сессию — та решает: play/pause,
        // next/prev. KEYCODE_HEADSETHOOK = центральная кнопка наушников.
        if (key != null && session != null) {
            session.controller.dispatchMediaButtonEvent(key)
        }
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

    companion object {
        /** Сессия, которую выставил MainActivity при старте. */
        @Volatile
        var sessionRef: MediaSessionCompat? = null
    }
}
