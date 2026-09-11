package com.cuber.bookvoice

import android.content.Context
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
        }
    }
}
