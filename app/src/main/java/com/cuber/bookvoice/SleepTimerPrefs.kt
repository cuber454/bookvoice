package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences

/**
 * Настройки таймера сна (msg4977/4987) — один дом для ключей prefs.
 *
 * Зачем отдельный файл. Настройки читают ТРОЕ: движок (окно ожидания встряски
 * живёт в ReaderEngine — таймер обязан сработать и при погашенном экране, и
 * «без окна»), окно таймера и окно настроек таймера. Разбросанные по трём
 * файлам строковые ключи ломаются на первой же опечатке (ровно так в 0.4.25
 * упал CI с выдуманным `setOnVoicesChangedListener` — урок «не писать по
 * памяти»). Здесь они лежат рядом со значениями по умолчанию и читаются
 * функциями, а не россыпью `getBoolean`.
 *
 * Значения — ступени, а не абсолютные числа: чувствительность и сила вибрации
 * идут ступенями 0/1/2 (словами их называют строки, чтобы окно и подсказки
 * говорили одно и то же), продление — из [EXTEND_CHOICES].
 */
internal object SleepTimerPrefs {

    /** Файл настроек чтения — тот же, что у читалки и «Не засыпать». */
    const val PREFS = "reader"

    /** Ждать встряску в последнюю минуту, а не глохнуть сразу (msg4941). */
    const val KEY_WAIT = "sleep_wait_shake"

    /** Продлевать переворотом экраном вниз — второй жест (msg4981). */
    const val KEY_FLIP = "sleep_flip"

    /** На сколько минут продлевать после жеста. */
    const val KEY_EXTEND = "sleep_extend_min"

    /** Ступень чувствительности: 0 — низкая, 1 — средняя, 2 — высокая. */
    const val KEY_SENSE = "sleep_sense_level"

    /** Сила вибрации сигнала: 0 — слабая, 1 — средняя, 2 — сильная. */
    const val KEY_VIBRA = "sleep_vibra_level"

    /** Сигнал за минуту: звук + вибрация. */
    const val KEY_SIGNAL = "sleep_signal"

    /** Объявлять словами через скринридер. По умолчанию ВЫКЛ: речь поверх
     *  чтения мешает (msg4979) — сигнал и вибрация справляются сами. */
    const val KEY_VOICE = "sleep_voice"

    const val DEF_WAIT = true
    const val DEF_FLIP = true
    const val DEF_SIGNAL = true
    const val DEF_VOICE = false
    const val DEF_LEVEL = 1
    const val DEF_EXTEND = 10

    /** Продлевать «столько же, сколько был выставлен таймер» (msg5009). Ноль —
     *  не число минут, а именно этот выбор: таймер на 30 минут продлевается на
     *  30, на 15 — на 15. У режима «до конца главы» числа нет, и «столько же»
     *  для него значит дочитать следующую главу (см. ReaderEngine.onSleepGesture). */
    const val EXTEND_SAME = 0

    /** Во сколько продлевать — три ступени и «столько же», четвёртой «навсегда»
     *  нет намеренно: случайная встряска во сне не должна снимать таймер до
     *  утра (msg4941). */
    val EXTEND_CHOICES = intArrayOf(5, 10, 15, EXTEND_SAME)

    /** Куски времени в окне таймера: 5 и 15 добавлены к прежней шкале
     *  (10/20/30/45/60 из msg2567) — Сергей просил мелкий шаг в начале. */
    val MINUTE_CHOICES = intArrayOf(5, 10, 15, 20, 30, 45, 60)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wait(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WAIT, DEF_WAIT)

    fun flip(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLIP, DEF_FLIP)

    fun signal(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SIGNAL, DEF_SIGNAL)

    fun voice(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VOICE, DEF_VOICE)

    fun sense(ctx: Context): Int = prefs(ctx).getInt(KEY_SENSE, DEF_LEVEL).coerceIn(0, 2)

    fun vibraLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_VIBRA, DEF_LEVEL).coerceIn(0, 2)

    /** Продление: число минут или [EXTEND_SAME]. Значение из prefs может быть
     *  любым (старая сборка, правка файла руками) — вне шкалы берём умолчание. */
    fun extendMinutes(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_EXTEND, DEF_EXTEND)
        return if (v in EXTEND_CHOICES) v else DEF_EXTEND
    }
}
