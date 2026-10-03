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
        // msg5895 (#85): сторож разблокировки встаёт ровно на время чтения —
        // тот же интервал, что и у блокировки (как тихий поток ниже).
        ScreenOnPause.start(c)
        // 23.09.2026: пауза по перевороту экраном вниз — там же, где сторож
        // экрана: сторожу положено жить ровно пока идёт чтение.
        FaceDownPause.start(c)
        // msg5931: «идёт чтение» — тот же момент, что у блокировки; в этом
        // состоянии прячутся системные кнопки (см. ReaderBars).
        ReaderBars.readingStarted()
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
            // 30.09.2026: отметка «читали» — чтобы после убийства процесса окно
            // «Не засыпать» могло сказать, что нас оборвали на чтении.
            setAlive(c, true)
            // msg4709: с этой минуты — отметки «бьюсь» раз в минуту (см. beat).
            beats = 0
            main.removeCallbacks(beat)
            main.postDelayed(beat, BEAT_MS)
            // msg4721: тихий поток живёт ровно там же, где блокировка, — пока идёт
            // чтение. Галочка выключена (по умолчанию) — не включаем ничего.
            if (silentPref(c)) SilentKeepAlive.start(c)
        }
    }

    /** Отпустить — пауза, конец книги, закрытие читалки. */
    fun release() {
        // #85: чтение встало — сторож разблокировки тоже (даже если блокировку
        // процессора уже отпустили раньше: чтение и есть его условие).
        appCtx?.let { ScreenOnPause.stop(it) }
        // 23.09.2026: сторож переворота живёт там же — чтение встало, следить
        // за положением телефона больше не для чего.
        appCtx?.let { FaceDownPause.stop(it) }
        // msg5931: чтение встало — системные кнопки возвращаются (режим
        // «скрывать во время чтения» отпускает их именно здесь).
        ReaderBars.readingStopped()
        // 30.09.2026: чтение встало по-хорошему — след «нас оборвали на чтении»
        // снимаем ЗДЕСЬ, до выхода по отсутствию блокировки: иначе след прошлого
        // запуска остался бы висеть после первой же паузы в новом.
        appCtx?.let { setAlive(it, false) }
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
        SilentKeepAlive.stop(c)
    }

    /** Перечитать галочку тихого потока — зовёт экран «Не засыпать» сразу после
     *  переключения: включили на ходу — поток встаёт, сняли — гаснет. Состояние
     *  «идёт ли чтение» знает только блокировка: она и есть признак чтения. */
    fun syncSilence() {
        val c = appCtx ?: return
        if (lock?.isHeld == true && silentPref(c)) SilentKeepAlive.start(c)
        else SilentKeepAlive.stop(c)
    }

    /** Включить тихий поток сами, без галочки: чтение пошло способом, в котором
     *  звук играет движок, а не наш плеер (альтернативный способ либо сетевой
     *  движок, не отдающий файлы) — там система перестаёт видеть нас играющими и
     *  кнопки на наушниках уходят чужому плееру.
     *
     *  Раньше это делала только галочка альтернативного способа в настройках
     *  (msg4721). Но прямой режим включается и сам, по опыту: сетевой движок
     *  дважды не отдал файл — читаем напрямую ([SpeechPlayer.netDirect]). Тогда
     *  галочка оставалась снятой, и человек терял кнопки, ничего не меняя.
     *  30.09.2026, по образцу @Voice Aloud Reader: у них тихий звук — обычная
     *  настройка, а нам важно, чтобы он вставал в прямом режиме всегда.
     *
     *  Снимаем галочку по-прежнему только руками: закончился прямой режим — поток
     *  остаётся (обратно его никто не гасит, галочка видна в «Чтении»). */
    fun enableSilenceAuto(c: Context, reason: String) {
        val p = runCatching { c.getSharedPreferences("reader", Context.MODE_PRIVATE) }.getOrNull()
            ?: return
        if (p.getBoolean(MainActivity.KEY_SILENT_KEEPALIVE, false)) return
        p.edit().putBoolean(MainActivity.KEY_SILENT_KEEPALIVE, true).apply()
        Diag.log(c, "power", "тихий поток включён сам: $reason (галочка в «Чтении» → «Звук и стыки»)")
        syncSilence()
    }

    /** Включён ли тихий поток в настройках. Читаем сами prefs: экран «Не засыпать»
     *  пишет туда же, а движку знать об этой настройке незачем. */
    private fun silentPref(c: Context): Boolean = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_SILENT_KEEPALIVE, false)
    }.getOrDefault(false)

    // ---------------- «Читали ли, когда нас оборвали» (30.09.2026) ----------------

    /** Признак «чтение в ходу». Живёт в настройках, а не в памяти, именно потому,
     *  что при убийстве процесса `release()` не вызывается: оставшийся флаг и есть
     *  след «нас оборвали на чтении». Читает его окно «Не засыпать» вместе с
     *  причиной последнего выхода процесса ([SleepGuard.lastExit]) — вдвоём они
     *  отвечают, сама ли система остановила приложение и было ли оно занято
     *  чтением. Ключ внутренний: в настройках его не видно. */
    private const val KEY_READING_ALIVE = "reading_in_progress"

    private fun setAlive(c: Context, on: Boolean) {
        runCatching {
            c.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_READING_ALIVE, on).apply()
        }
    }

    /** Шло ли чтение, когда приложение закончилось в прошлый раз. */
    fun wasReadingAtLastExit(c: Context): Boolean = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getBoolean(KEY_READING_ALIVE, false)
    }.getOrDefault(false)
}
