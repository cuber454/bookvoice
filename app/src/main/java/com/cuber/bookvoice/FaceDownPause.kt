package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock

/**
 * Пауза, когда телефон лежит экраном вниз (23.09.2026, просьба Сергея).
 *
 * Как это для человека: экран погас и телефон лёг экраном вниз — чтение встаёт
 * на паузу; перевернул экраном вверх — чтение продолжается само. Удобно, когда
 * слушаешь в темноте: положил на грудь или на стол экраном вниз — тишина,
 * поднял — читает дальше, искать кнопку не надо.
 *
 * Галочка в «Чтении» выключена по умолчанию: у тех, кто читает под погашенным
 * экраном, поведение не должно меняться само.
 *
 * **Не пересекается с таймером сна.** У таймера есть свой жест — «положить
 * экраном вниз», которым чтение продлевают ([SleepSense], окно ожидания). Пока
 * это окно открыто ([ReaderEngine.sleepWaiting]), переворот принадлежит
 * таймеру, и мы молчим: ни паузы, ни продолжения. Всё остальное время жеста у
 * таймера нет, и переворот наш.
 *
 * Почему акселерометр, а не датчик приближения: приближение срабатывает не на
 * всякой поверхности (на кровати часто нет), и «экран вверх» им не отличить.
 * Гравитация одинакова на всех моделях: экран вниз — z уходит в минус, вверх —
 * в плюс. Числа те же, что у таймера ([FLIP_Z], [FLIP_HOLD_MS]), чтобы жест не
 * различался в двух местах.
 *
 * Слушаем только пока экран ПОГАШЕН и идёт чтение: при горящем экране есть своя
 * галочка («останавливать при включении экрана»), а датчик на всё чтение — это
 * лишняя батарея. Приёмник и датчик живут ровно столько, сколько идёт чтение:
 * [start]/[stop] зовёт [KeepAwake] рядом с блокировкой процессора.
 */
object FaceDownPause {

    /** Экран вниз: гравитация уходит в минус по z (как в SleepSense). */
    private const val FLIP_Z = 7.0f

    /** Держим переворот заметное время: мимолётный наклон при перекладывании
     *  паузой не считается. */
    private const val FLIP_HOLD_MS = 400L

    /** Идёт ли чтение. */
    @Volatile
    private var reading = false

    /** Экран погашен. */
    @Volatile
    private var screenOff = false

    /** Паузу поставили МЫ: только её мы и снимаем. Пауза человека (кнопкой,
     *  гарнитурой) остаётся паузой, сколько бы раз телефон ни переворачивали. */
    @Volatile
    private var pausedByUs = false

    private var ctx: Context? = null

    private var registered = false
    private var listening = false

    private var manager: SensorManager? = null
    private var sensor: Sensor? = null

    /** С какого момента телефон лежит экраном вниз (0 — не лежит). */
    private var faceDownSince = 0L

    /** С какого момента экран вверх (0 — не вверх). */
    private var faceUpSince = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    faceDownSince = 0L
                    faceUpSince = 0L
                    // Экран погас: дальше следим за положением — вдруг телефон
                    // уже лежит экраном вниз или его положат.
                    startSensor()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    stopSensor()
                    // Экран зажгли — пауза «по перевороту» теряет смысл и
                    // становится обычной паузой: дальше человек сам решает.
                    if (pausedByUs) {
                        pausedByUs = false
                        Diag.log(c, "power", "экран загорелся — пауза по перевороту больше не наша")
                    }
                    // Сторож больше не нужен: чтение стоит чужой паузой, ждать
                    // переворота вверх нечего. Если чтение идёт — apply оставит
                    // регистрацию (следующий SCREEN_OFF снова включит датчик).
                    apply()
                }
                else -> return
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val c = ctx ?: return
            val z = e.values[2]
            val now = SystemClock.elapsedRealtime()

            // Окно таймера сна: переворот сейчас принадлежит ему (продление).
            // Молчим и обнуляем отсчёты, чтобы после окна не сработало старое.
            if (ReaderEngine.sleepWaiting) {
                faceDownSince = 0L
                faceUpSince = 0L
                return
            }

            if (z < -FLIP_Z) {
                faceUpSince = 0L
                if (faceDownSince == 0L) faceDownSince = now
                if (now - faceDownSince >= FLIP_HOLD_MS && !pausedByUs) {
                    if (ReaderEngine.playing) {
                        pausedByUs = true
                        Diag.log(c, "power", "экран вниз, экран погашен — чтение встало")
                        ReaderEngine.pausePlayback(keepFocus = true, byFaceDown = true)
                    }
                }
                return
            }

            faceDownSince = 0L
            if (z > FLIP_Z) {
                if (faceUpSince == 0L) faceUpSince = now
                if (now - faceUpSince >= FLIP_HOLD_MS && pausedByUs) {
                    pausedByUs = false
                    ReaderEngine.resumeIfPausedByFaceDown()
                }
            } else {
                // Телефон на боку — не считаем ни тем, ни другим.
                faceUpSince = 0L
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Чтение началось. */
    fun start(c: Context) {
        ctx = c.applicationContext
        reading = true
        // Чтение пошло — ждать переворота вверх больше нечего: продолжение уже
        // случилось (или человек нажал «читать» сам).
        pausedByUs = false
        apply()
    }

    /** Чтение встало — пауза, конец книги, закрытие читалки.
     *
     *  ВАЖНО: сюда приходит и НАША пауза по перевороту (движок отпускает
     *  процессор через [KeepAwake.release]). Тогда сторожа выключать нельзя:
     *  продолжение придёт именно от него — перевернут экраном вверх. Поэтому
     *  отличаем нашу паузу от чужой по флагу движка: чужая (кнопкой, гарнитурой)
     *  снимает сторожа, наша — остаётся ждать. */
    fun stop(c: Context) {
        ctx = c.applicationContext
        reading = false
        if (!ReaderEngine.pausedByFaceDown) pausedByUs = false
        apply()
    }

    /** Перечитать настройку — зовёт экран настроек сразу после переключения:
     *  включили на ходу, во время чтения, — сторож встаёт, не дожидаясь
     *  следующего старта чтения, а сняли — уходит. */
    fun sync() = apply()

    private fun apply() {
        val c = ctx ?: return
        // Сторож нужен и когда чтение уже стоит НАШЕЙ паузой: ждём переворота
        // вверх, чтобы продолжить. Чужая пауза (кнопкой, концом книги) сюда не
        // попадает — там pausedByUs снят в [stop].
        val want = (reading || pausedByUs) && enabled(c)
        if (want == registered) return
        if (!want) {
            // Отписка из собственного onReceive безопасна: объект приёмника жив,
            // снимается только регистрация.
            runCatching { c.unregisterReceiver(receiver) }
            registered = false
            screenOff = false
            stopSensor()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                c.registerReceiver(receiver, filter)
            }
            registered = true
            // Чтение могли начать уже с погашенным экраном — тогда SCREEN_OFF мы
            // не увидим, а следить надо.
            screenOff = !screenOn(c)
            if (screenOff) startSensor()
        }
    }

    private fun screenOn(c: Context): Boolean = runCatching {
        val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isInteractive ?: true
    }.getOrDefault(true)

    private fun startSensor() {
        val c = ctx ?: return
        if (listening || !screenOff) return
        if (manager == null) {
            manager = c.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val m = manager
        val s = sensor
        if (m == null || s == null) {
            Diag.log(c, "power", "акселерометра нет — пауза по перевороту недоступна")
            return
        }
        faceDownSince = 0L
        faceUpSince = 0L
        listening = m.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_NORMAL)
        if (listening) Diag.log(c, "power", "переворот: экран погашен, слежу за положением")
    }

    private fun stopSensor() {
        if (!listening) return
        listening = false
        runCatching { manager?.unregisterListener(sensorListener) }
    }

    /** Что стоит в настройках. Читаем prefs сами: экран настроек пишет туда же,
     *  а движку про эту галочку знать незачем (как у сторожа экрана). */
    private fun enabled(c: Context): Boolean = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_PAUSE_FACE_DOWN, false)
    }.getOrDefault(false)
}
