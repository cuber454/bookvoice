package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Останавливать чтение, когда зажигается экран (задача #85, msg5895/5899).
 *
 *  Смысл для незрячего: взял телефон в руки, зажёг экран — значит, собираешься
 *  что-то в нём делать, а не слушать: голос в этот момент перебивает экранного
 *  диктора и мешает искать нужное.
 *
 *  Слушаем ACTION_SCREEN_ON и ACTION_USER_PRESENT. Разблокировку ловим отдельно
 *  не для красоты: при снятии замка отпечатком на уже горящем экране отдельного
 *  SCREEN_ON может не прийти (msg5919).
 *
 *  Загорание от уведомления мы не отличить от нажатия кнопки питания не можем —
 *  оба дают ACTION_SCREEN_ON (кнопку Power приложению не отдают вовсе, Android
 *  9+). Сергей решил (22.09.2026): пусть будет так, а вспышки на уведомления он
 *  гасит в системных настройках сам.
 *
 *  История настройки: сперва галочка «при разблокировке» (8b441c9), потом
 *  строка-переключатель в три состояния (c0c1b2b, msg5919), потом всё убрано
 *  целиком (1e6306c, msg6022). 22.09.2026 Сергей попросил вернуть галочку, но с
 *  поведением «при включении экрана».
 *
 *  Чтение НЕ прекращаем: [ReaderEngine.pausePlayback] встаёт там же, где его
 *  застали, карточка в шторке остаётся, продолжить можно касанием или кнопкой
 *  гарнитуры. Самовозобновления нет — иначе чтение заводилось бы снова само,
 *  пока телефон в руках.
 *
 *  Приёмник живёт ровно столько, сколько идёт чтение: [start]/[stop] зовёт
 *  [KeepAwake] рядом с блокировкой процессора (там же, где тихий поток).
 *  Регистрация поднимает вес процесса, а на паузе он нам не нужен. Галочка
 *  снята (по умолчанию) — не регистрируем ничего.
 */
object ScreenOnPause {

    /** Идёт ли чтение. Нужно для [sync]: галочку могли включить на ходу, когда
     *  приёмник ещё не встал. */
    @Volatile
    private var reading = false

    private var ctx: Context? = null

    /** Стоит ли приёмник сейчас. */
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val what = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> "экран разблокирован"
                Intent.ACTION_SCREEN_ON -> "экран загорелся"
                else -> return
            }
            // Не читаем — не наше дело: пауза и так стоит, карточка живёт своей
            // жизнью. Заодно отсекает разблокировку после конца книги.
            if (!ReaderEngine.playing) return
            Diag.log(c, "power", "$what — чтение встало")
            // keepFocus = true — как пауза из окна: фокус держим, волшебное
            // касание гарнитуры продолжит чтение без окна.
            ReaderEngine.pausePlayback(keepFocus = true)
        }
    }

    /** Чтение началось. */
    fun start(c: Context) {
        ctx = c.applicationContext
        reading = true
        apply()
    }

    /** Чтение встало — пауза, конец книги, закрытие читалки. */
    fun stop(c: Context) {
        ctx = c.applicationContext
        reading = false
        apply()
    }

    /** Перечитать настройку — зовёт экран настроек сразу после переключения:
     *  включили на ходу, во время чтения, — приёмник встаёт, не дожидаясь
     *  следующего старта чтения, а сняли — уходит. */
    fun sync() = apply()

    private fun apply() {
        val c = ctx ?: return
        val want = reading && enabled(c)
        if (want == registered) return
        if (!want) {
            // Отписка из собственного onReceive (пауза зовёт KeepAwake.release)
            // безопасна: объект приёмника жив, снимается только регистрация.
            runCatching { c.unregisterReceiver(receiver) }
            registered = false
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                c.registerReceiver(receiver, filter)
            }
            registered = true
        }
    }

    /** Что стоит в настройках: останавливать чтение при включении экрана или нет.
     *  Читаем prefs сами: экран настроек пишет туда же, а движку знать об этой
     *  галочке незачем (как у тихого потока).
     *
     *  Ключ именно `pause_on_screen` (Boolean), а не старый `pause_when_screen`:
     *  в том лежало ЧИСЛО от прежнего переключателя в три состояния, и читать его
     *  как галочку нельзя — упадёт на разборе типа. У кого старое значение
     *  осталось, у того галочка просто окажется снятой. */
    private fun enabled(c: Context): Boolean = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_PAUSE_ON_SCREEN, false)
    }.getOrDefault(false)
}
