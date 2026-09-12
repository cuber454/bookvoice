package com.cuber.bookvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Не давать телефону заснуть, пока идёт чтение (msg4211/4212, задача #19).
 *
 *  Жалоба тестера (Poco X7, Android 16): телефон лежит — чтение встаёт, взял в
 *  руки — продолжается; в горизонтальной плоскости двигаешь — молчит. Лог
 *  v0.4.25 подтвердил механику: между «старт чтения» и сработкой сторожа
 *  прошло 42 с вместо 15 — таймеры `postDelayed` не шли, процесс спал. Между
 *  фразами ничего не звучит (каждая фраза — отдельный файл), и в эту щель
 *  система уводит устройство в глубокий сон: замирают и синтез, и сторож.
 *  Подъём телефона вертикально включает экран (жест «поднести к лицу»), процесс
 *  просыпается, сторож срабатывает, чтение оживает, — потому и кажется, что
 *  дело в акселераторе.
 *
 *  Лечение — PARTIAL_WAKE_LOCK на время чтения: экран гаснет как обычно, а
 *  процессор работает. Держим ровно от старта чтения до паузы/конца книги/
 *  закрытия окна, иначе садим батарею. Разрешение WAKE_LOCK — в манифесте.
 */
object KeepAwake {

    /** Имя блокировки видно в `adb shell dumpsys power` — по нему искать, кто держит. */
    private const val TAG = "BookVoice:read"

    private var lock: PowerManager.WakeLock? = null

    /** Контекст приложения — нужен, чтобы записать в лог и отпускание блокировки
     *  (в release контекста из аргументов уже нет). */
    private var appCtx: Context? = null

    // ---------------- «Сердечный ритм» (msg4709) ----------------

    /** Раз в минуту, пока идёт чтение, — строка-отметка в журнал. Смысл: отличить
     *  заморозку процесса от ошибки кода. Если прошивка усыпила приложение,
     *  отметки пропадают и в журнале остаётся ДЫРА во времени — ровно так был
     *  разобран случай на Poco (msg4211: между «старт чтения» и сработкой
     *  сторожа 42 с вместо 15). Молчащая дыра говорит «процесс спал», а не
     *  «программа сломалась». Планировщик — на главном цикле: спит процесс,
     *  спят и отметки, а нам именно это и надо увидеть. */
    private const val BEAT_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private var beats = 0
    private val beat = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            beats++
            Diag.log(c, "power", "бьюсь: чтение идёт $beats мин")
            main.postDelayed(this, BEAT_MS)
        }
    }

    /** Взять блокировку — зовётся на каждом старте чтения. Повторные вызовы
     *  безвредны: держим одну и ту же блокировку. */
    fun acquire(c: Context) {
        lock?.let { if (it.isHeld) return }
        appCtx = c.applicationContext
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = lock ?: runCatching {
            // setReferenceCounted(false): считаем блокировку одной на приложение,
            // иначе при рассинхроне acquire/release она осталась бы висеть навсегда.
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }
        }.getOrNull() ?: return
        lock = l
        runCatching {
            l.acquire()
            // Засечка в diag.log: по ней видно, что блокировка взята, — иначе в
            // следующем логе не отличить «не держали» от «держали, но не помогло».
            Diag.log(c, "power", "держу процессор (чтение идёт)")
            // msg4709: с этой минуты — отметки «бьюсь» раз в минуту (см. beat).
            beats = 0
            main.removeCallbacks(beat)
            main.postDelayed(beat, BEAT_MS)
        }
    }

    /** Отпустить — пауза, конец книги, закрытие читалки. */
    fun release() {
        val l = lock ?: return
        val c = appCtx ?: return
        runCatching {
            if (l.isHeld) {
                l.release()
                Diag.log(c, "power", "отпустил процессор (чтение встало)")
            }
            // Отметки — только пока чтение идёт: в паузе журналу молчать.
            main.removeCallbacks(beat)
        }
    }
}
