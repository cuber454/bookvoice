package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Останавливать чтение, когда зажигается экран или его разблокируют
 * (msg5895/5899/5919, задача #85).
 *
 *  Смысл для незрячего: взял телефон в руки, зажёг экран — значит, собираешься
 *  в нём что-то делать, а не слушать; голос в этот момент перебивает TalkBack и
 *  мешает искать нужное.
 *
 *  Режим выбирается в настройках, три состояния (см. [MainActivity.KEY_PAUSE_ON_SCREEN]):
 *  «выключено» — не слушаем ничего; «при включении экрана» — встаём на
 *  ACTION_SCREEN_ON; «при разблокировке» — только на ACTION_USER_PRESENT
 *  («экран включён И замок снят»).
 *
 *  Почему это выбор, а не одно поведение. Отличить нажатие кнопки питания от
 *  вспышки на пришедшее уведомление программе нельзя — оба дают ACTION_SCREEN_ON.
 *  Сергей прошёл весь круг (msg5903 — «вставать и на загорание экрана», msg5911 —
 *  «убирай», msg5919 — «сделай переключателем»): режим «при включении экрана»
 *  останавливает чтение и от уведомления тоже, режим «при разблокировке» — только
 *  от осознанного действия. Кнопку Power как таковую поймать нельзя: с Android 9
 *  система её приложению не отдаёт.
 *
 *  Чтение НЕ прекращаем: [ReaderEngine.pausePlayback] встаёт там же, где его
 *  застали, карточка в шторке остаётся, продолжить можно одним касанием или
 *  кнопкой гарнитуры. Самовозобновления нет — иначе чтение заводилось бы снова
 *  само, пока телефон в руках.
 *
 *  Приёмник живёт ровно столько, сколько идёт чтение: [start]/[stop] зовёт
 *  [KeepAwake] рядом с блокировкой процессора (там же, где тихий поток).
 *  Регистрация поднимает вес процесса, а на паузе он нам не нужен. Режим
 *  «выключено» (по умолчанию) — не регистрируем ничего.
 */
object ScreenOnPause {

    /** Идёт ли чтение. Нужно для [sync]: режим могли переключить на ходу, когда
     *  приёмник ещё не встал (или стоял в другом режиме). */
    @Volatile
    private var reading = false

    private var ctx: Context? = null

    /** Режим, под который ПОДПИСАН приёмник (`PAUSE_SCREEN_OFF` — не подписан).
     *  Держим именно режим, а не флажок: от режима зависит набор событий в
     *  фильтре, и при переключении на ходу подписку надо перестроить. */
    private var registeredFor = MainActivity.PAUSE_SCREEN_OFF

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val what = when (intent.action) {
                Intent.ACTION_USER_PRESENT -> "экран разблокирован"
                Intent.ACTION_SCREEN_ON -> "экран загорелся"
                else -> return
            }
            // Режим мог переключиться, пока приёмник стоял (настройки зовут
            // [sync]) — сверяемся с ним, а не с тем, каким он был на старте.
            if (!stopsOn(intent.action, mode(c))) return
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

    /** Останавливает ли чтение это событие при режиме [mode]. Режим «при включении
     *  экрана» ловит и разблокировку: экран при ней уже зажжён, но отдельного
     *  SCREEN_ON могло не прийти (замок снят отпечатком на горящем экране). */
    private fun stopsOn(action: String?, mode: Int): Boolean = when (mode) {
        MainActivity.PAUSE_SCREEN_UNLOCK -> action == Intent.ACTION_USER_PRESENT
        MainActivity.PAUSE_SCREEN_ON -> action == Intent.ACTION_USER_PRESENT ||
            action == Intent.ACTION_SCREEN_ON
        else -> false
    }

    private fun apply() {
        val c = ctx ?: return
        // На паузе приёмник не нужен: чтение и так стоит.
        val want = if (reading) mode(c) else MainActivity.PAUSE_SCREEN_OFF
        if (want == registeredFor) return
        if (registeredFor != MainActivity.PAUSE_SCREEN_OFF) {
            // Отписка из собственного onReceive (пауза зовёт KeepAwake.release)
            // безопасна: объект приёмника жив, снимается только регистрация.
            runCatching { c.unregisterReceiver(receiver) }
            registeredFor = MainActivity.PAUSE_SCREEN_OFF
        }
        if (want == MainActivity.PAUSE_SCREEN_OFF) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            // «При включении экрана» — нужен и он; в остальных режимах впустую не
            // слушаем: ACTION_SCREEN_ON сыплется от любого уведомления.
            if (want == MainActivity.PAUSE_SCREEN_ON) addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                c.registerReceiver(receiver, filter)
            }
            registeredFor = want
        }
    }

    /** Что выбрано в настройках: выключено / при включении экрана / при
     *  разблокировке. Читаем prefs сами: экран настроек пишет туда же, а движку
     *  знать об этой настройке незачем (как у тихого потока). */
    private fun mode(c: Context): Int = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_PAUSE_ON_SCREEN, MainActivity.PAUSE_SCREEN_OFF)
    }.getOrDefault(MainActivity.PAUSE_SCREEN_OFF)
}
