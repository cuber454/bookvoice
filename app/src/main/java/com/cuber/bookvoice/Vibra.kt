package com.cuber.bookvoice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Вибрация-подтверждение действий (msg1254). Тактильный дубль речевого тоста:
 * для незрячего пользователя толчок чувствуется всегда, даже когда TalkBack
 * занят чтением. Один общий рубильник — KEY_VIBRATE («Управление» в
 * Настройках): выключил, и все толчки ушли. confirm() — короткий одиночный
 * «готово», error() — двойной «не получилось», чтобы отличать без вслушивания.
 * Обе сами проверяют настройку, вызывающему проверять не нужно.
 */
object Vibra {
    /** Общий ключ: включена ли вибрация действий (по умолчанию — да). */
    const val KEY_VIBRATE = "vibrate_actions"

    /** Кто-то уже читает предпочтение из своей prefs-ссылки на «reader». */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATE, true)

    /** «Готово»: действие завершилось. Короткий одиночный толчок. */
    fun confirm(ctx: Context) = pulse(ctx, longArrayOf(0, 70))

    /** «Не получилось»: ошибка. Двойной толчок — не спутать с «готово». */
    fun error(ctx: Context) = pulse(ctx, longArrayOf(0, 120, 90, 120))

    /** Сигнал таймера сна (msg4979): двойной толчок выбранной силы — 0 слабая,
     *  1 средняя, 2 сильная. Идёт МИМО рубильника «Вибрация действий»: там
     *  отклик на нажатие, а здесь будильник — глушить его тем же выключателем
     *  значило бы отнять у незрячего единственный канал, когда экран погашен.
     *  У сигнала свой выключатель — «Сигнал за минуту» в настройках таймера. */
    fun timerSignal(ctx: Context, level: Int) {
        val amp = when (level) {
            0 -> 90
            2 -> 255
            else -> 170
        }
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 150, 130, 150)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(pattern, intArrayOf(0, amp, 0, amp), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun pulse(ctx: Context, pattern: LongArray) {
        if (!enabled(ctx)) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }
}
