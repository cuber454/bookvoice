package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Останавливать чтение, когда экран разблокировали (msg5895/5899, задача #85).
 *
 *  Сергей: «добавим галочку — остановить чтение при включении экрана… по
 *  разблокировке, когда включаешь кнопку питания». Смысл для незрячего: взял
 *  телефон в руки и разблокировал — значит, собираешься в нём что-то делать, а
 *  не слушать; голос в этот момент перебивает TalkBack и мешает искать нужное.
 *
 *  Ловим ТОЛЬКО ACTION_USER_PRESENT — «экран включён И блокировка снята».
 *  Разблокировка — действие осознанное: взял телефон, снял замок, значит, идёшь
 *  в него работать.
 *
 *  Вариант «вставать на любое загорание экрана» (ACTION_SCREEN_ON, msg5903) был
 *  сделан и отменён самим Сергеем (msg5911/5915): отличить нажатие кнопки
 *  питания от вспышки на пришедшее уведомление программе нельзя, и чтение вставало
 *  зря посреди книги. Поэтому — только разблокировка; SCREEN_ON не слушаем.
 *
 *  Чтение НЕ прекращаем: [ReaderEngine.pausePlayback] встаёт там же, где его
 *  застали, карточка в шторке остаётся, продолжить можно одним касанием или
 *  кнопкой гарнитуры. Самовозобновления нет — иначе чтение заводилось бы снова
 *  само, пока телефон в руках.
 *
 *  Приёмник живёт ровно столько, сколько идёт чтение: [start]/[stop] зовёт
 *  [KeepAwake] рядом с блокировкой процессора (там же, где тихий поток).
 *  Регистрация поднимает вес процесса, а на паузе он нам не нужен. Галочка
 *  выключена (по умолчанию) — не регистрируем ничего.
 */
object ScreenOnPause {

    /** Идёт ли чтение. Нужно для [sync]: галочку могли переключить на ходу,
     *  когда приёмник ещё не встал. */
    @Volatile
    private var reading = false

    private var ctx: Context? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            // Не читаем — не наше дело: пауза и так стоит, карточка живёт своей
            // жизнью. Заодно отсекает разблокировку после конца книги.
            if (!ReaderEngine.playing) return
            Diag.log(c, "power", "экран разблокирован — чтение встало (галочка)")
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

    /** Перечитать галочку — зовёт экран настроек сразу после переключения: включили
     *  на ходу, во время чтения, — приёмник встаёт, не дожидаясь следующего старта. */
    fun sync() = apply()

    private fun apply() {
        val c = ctx ?: return
        val want = reading && pref(c)
        if (want == registered) return
        if (want) {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    c.registerReceiver(receiver, filter)
                }
                registered = true
            }
        } else {
            // Отписка из собственного onReceive (пауза зовёт KeepAwake.release)
            // безопасна: объект приёмника жив, снимается только регистрация.
            runCatching { c.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Включена ли галочка. Читаем prefs сами: экран настроек пишет туда же, а
     *  движку знать об этой настройке незачем (как у тихого потока). */
    private fun pref(c: Context): Boolean = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_PAUSE_ON_UNLOCK, false)
    }.getOrDefault(false)
}
