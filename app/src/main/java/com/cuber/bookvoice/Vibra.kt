package com.cuber.bookvoice

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
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

    /** КОНЕЦ СПИСКА (08.10.2026, просьба Сержа). Свайп вниз, а дальше некуда —
     *  отвечаем одним коротким толчком. Чувствуется как «готово» ([confirm]) и
     *  означает то же самое: дальше пути нет.
     *
     *  09.10.2026: играем СТАНДАРТНЫЙ системный эффект (`EFFECT_TICK`), а не свой
     *  шаблон. Так делают приложения: предопределённые эффекты одинаковы во всех
     *  программах и подогнаны под конкретный телефон системой — «custom tailored
     *  to the device hardware... better experience than you could otherwise build
     *  using the generic building blocks» (документация `VibrationEffect`).
     *  Наш самодельный шаблон остался только запасным путём для Android 9 и
     *  старше, где предопределённых эффектов ещё нет. */
    fun edgeBottom(ctx: Context) =
        haptic(ctx, "конец списка", VibrationEffect.EFFECT_TICK, longArrayOf(0, 90))

    /** НАЧАЛО СПИСКА. Свайп вверх, а выше некуда — двойной толчок: на ощупь
     *  отличается от конца без вслушивания (как [error] от [confirm]).
     *
     *  09.10.2026: системный `EFFECT_DOUBLE_CLICK` — стандартный двойной отклик,
     *  тоже подобранный под телефон. */
    fun edgeTop(ctx: Context) =
        haptic(ctx, "начало списка", VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 60, 70, 60))

    /** Играем СТАНДАРТНЫЙ эффект системы, если он есть ([effectId]), иначе — свой
     *  шаблон ([pattern]) на Android 9 и старше.
     *
     *  Что это даёт: эффект играет сама система по своим таблицам для этого
     *  телефона, и нам не надо угадывать длину и силу вспышки. Канал остаётся
     *  наш, «специальные возможности» (см. [pulse]): толчок у края — подсказка
     *  незрячему, а не отклик на палец. */
    private fun haptic(ctx: Context, what: String, effectId: Int, pattern: LongArray) {
        if (!enabled(ctx)) {
            Diag.log(ctx, "vibra", "толчок ($what) пропущен: «Вибрация действий» выключена")
            return
        }
        val v = ctx.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) {
            Diag.log(ctx, "vibra", "толчок ($what) пропущен: системе нечем вибрировать")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = runCatching { VibrationEffect.createPredefined(effectId) }.getOrNull()
            if (effect != null) {
                Diag.log(ctx, "vibra", "толчок ($what): системный эффект $effectId")
                try {
                    vibrate(v, effect)
                } catch (e: Exception) {
                    Diag.log(ctx, "vibra", "толчок ($what) не вышел: ${e.message}")
                }
                return
            }
        }
        pulse(ctx, pattern)
    }

    /** Отправить готовый эффект вибратору: на Android 13+ с каналом «специальные
     *  возможности» ([VibrationAttributes.USAGE_ACCESSIBILITY]), ниже — как
     *  получится.
     *
     *  Почему канал: у ColorOS у канала касаний своя сила и свой выключатель, и
     *  толчок без свойств пропадает там молча. */
    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
            v.vibrate(effect, attrs)
        } else {
            v.vibrate(effect)
        }
    }

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
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
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

    /** Толчок. Пишем в журнал КАЖДЫЙ исход (08.10.2026): без этого по жалобе
     *  «толчки не вибрируют» нельзя отличить «до нас не дошло» от «дошло, но
     *  рубильник выключен» или «системе нечем вибрировать» — в журнале не
     *  оставалось ни следа. */
    private fun pulse(ctx: Context, pattern: LongArray) {
        if (!enabled(ctx)) {
            Diag.log(ctx, "vibra", "толчок пропущен: «Вибрация действий» выключена")
            return
        }
        val v = ctx.getSystemService(Vibrator::class.java)
        if (v == null || !v.hasVibrator()) {
            Diag.log(ctx, "vibra", "толчок пропущен: системе нечем вибрировать")
            return
        }
        Diag.log(ctx, "vibra", "толчок: вспышек ${pattern.size}, шаблон ${pattern.joinToString(",")}")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Канал «специальные возможности» — внутри [vibrate].
                vibrate(v, VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Diag.log(ctx, "vibra", "толчок не вышел: ${e.message}")
        }
    }
}
