package com.cuber.bookvoice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Встряска и переворот для таймера сна (msg4941/4977/4981).
 *
 * Как это работает для человека: таймер подходит к концу, BookVoice подаёт
 * сигнал и ЖДЁТ. Тряхнул телефон или положил его экраном вниз — чтение
 * продолжилось. Не тронул — чтение встало на паузу. Никаких настроек «порог
 * ускорения» в интерфейсе нет: ступеней три, и они названы словами (низкая,
 * средняя, высокая), потому что незрячему нужен не график, а проверка на слух
 * («Проверить встряску» в окне настроек таймера).
 *
 * Почему два жеста. Встряска привычнее, но телефон в чехле на столе или в
 * руке под одеялом трясти неудобно, а переворот экраном вниз однозначен:
 * гравитация, одинаково на всех моделях, во сне почти невозможен. Оба жеста
 * независимы — каждый включается своей галочкой.
 *
 * Слушаем ТОЛЬКО в окне ожидания (последняя минута перед остановкой), а не
 * всё время чтения: акселерометр постоянно — это батарея и ложные срабатывания
 * от кошки, края кровати и поездки в транспорте.
 *
 * Сенсор не требует разрешений (TYPE_ACCELEROMETER — обычный, не body-сенсор).
 * Нет акселерометра на телефоне — [available] вернёт false, и окно таймера
 * честно скажет словами, что на этом телефоне жест не поддерживается, вместо
 * немой строки.
 */
internal class SleepSense(ctx: Context) : SensorEventListener {

    enum class Gesture { SHAKE, FLIP }

    /** Контекст приложения: слушатель может пережить окно (окно ожидания идёт и
     *  при погашенном экране), держать Activity нельзя. */
    private val appCtx = ctx.applicationContext
    private val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Кого звать, когда жест пойман. Вызывается один раз — до [start]. */
    var onGesture: ((Gesture) -> Unit)? = null

    var shakeEnabled = true
    var flipEnabled = true

    /** Ступень чувствительности: 0 — низкая, 1 — средняя, 2 — высокая. */
    var sense = 1

    private var running = false

    /** Сглаженная гравитация (низкие частоты). Из неё вычитаем текущее
     *  ускорение — остаётся «толчок»: то, что человек делает рукой, а не
     *  наклон и не тряска автобуса. */
    private var gravity = 0f

    /** Время последнего толчка и был ли толчок в прошлом кадре — нужно, чтобы
     *  считать именно РАЗНЫЕ толчки, а не длинный подъём одного. */
    private var lastJoltAt = 0L
    private var joltFrame = false

    /** С какого момента телефон лежит экраном вниз. */
    private var faceDownSince = 0L

    private var fired = false

    val available: Boolean get() = sensor != null

    fun start() {
        val m = manager ?: return
        val s = sensor ?: return
        if (running) return
        gravity = 0f
        lastJoltAt = 0L
        joltFrame = false
        faceDownSince = 0L
        fired = false
        running = m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        Diag.log(
            appCtx, "sleep",
            "жесты: слушаю (встряска ${if (shakeEnabled) "да" else "нет"}, " +
                "переворот ${if (flipEnabled) "да" else "нет"}, " +
                "ступень $sense)"
        )
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.unregisterListener(this) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!running || fired) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        gravity = if (gravity == 0f) mag else gravity * 0.85f + mag * 0.15f
        val now = SystemClock.elapsedRealtime()

        // Переворот: телефон ЛЕЖИТ экраном вниз — держим жест заметное время,
        // чтобы мимолётный наклон при перекладывании не продлевал таймер.
        if (flipEnabled && z < -FLIP_Z) {
            if (faceDownSince == 0L) faceDownSince = now
            if (now - faceDownSince >= FLIP_HOLD_MS) {
                fire(Gesture.FLIP, "переворот")
                return
            }
        } else {
            faceDownSince = 0L
        }

        // Встряска: ДВА толчка подряд. Одиночный слишком легко спутать с шагом,
        // перекладыванием телефона или рукой во сне.
        if (shakeEnabled) {
            val jolt = abs(mag - gravity) > shakeThreshold()
            if (jolt && !joltFrame) {
                if (lastJoltAt != 0L && now - lastJoltAt <= JOLT_GAP_MS) {
                    fire(Gesture.SHAKE, "встряска")
                    return
                }
                lastJoltAt = now
            }
            joltFrame = jolt
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun fire(g: Gesture, why: String) {
        fired = true
        stop()
        Diag.log(appCtx, "sleep", "жест: $why")
        onGesture?.invoke(g)
    }

    /** Порог толчка по ступени: выше ступень — меньший толчок уже считается
     *  (высокая чувствительность ловит самую слабую тряску). Числа подобраны по
     *  типичным пикам ручной тряски 10–30 м/с²; проверяются на слух кнопкой
     *  «Проверить встряску», а не подгоняются вслепую. */
    private fun shakeThreshold(): Float = when (sense) {
        2 -> 2.2f
        0 -> 5.0f
        else -> 3.5f
    }

    private companion object {
        /** Экран вниз: гравитация уходит в минус по z. */
        const val FLIP_Z = 7.0f
        const val FLIP_HOLD_MS = 400L

        /** Между двумя толчками одной встряски — не больше 0,7 с. Взмахи
         *  рукой при ходьбе реже, поэтому пара толчков и есть признак «тряхнул». */
        const val JOLT_GAP_MS = 700L
    }
}
