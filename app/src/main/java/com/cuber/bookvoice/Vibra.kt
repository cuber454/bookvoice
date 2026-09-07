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
