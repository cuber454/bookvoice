package com.cuber.bookvoice

import android.content.Context
import android.content.Intent

/**
 * ЗДОРОВЬЕ СИНТЕЗАТОРА (16.10.2026, сборка 264x).
 *
 * Повод — журнал Сержа: книга не читалась, в журнале две ошибки синтеза с кодом
 * −3 и ТИШИНА. Серж проверил отдельно: «ТТС у меня не работает в системе, и
 * другая читалка им тоже не читает». То есть читалка молчала о том, что сломано
 * НЕ у неё, и человек не мог понять, почему книга не звучит.
 *
 * ПРАВИЛО: отказ синтезатора — это НЕ «фраза сыграла». О нём надо сказать словами
 * через диктор (сам синтезатор молчит, поэтому говорит скринридер через
 * `announceForAccessibility`) и предложить два выхода: попробовать снова или
 * открыть настройки синтезатора в системе. Системные настройки мы только
 * ПОКАЗЫВАЕМ, сами ничего не переключаем — это правило Сержа от 30.09.2026.
 *
 * Что проверяем ДО чтения (быстро, без запуска движка): есть ли в системе хоть
 * один движок озвучки и выбран ли он. Что понимаем ПО ХОДУ: движок не запустился,
 * у него нет голосов, выбранного голоса у него нет, и наконец «он уже показал, что
 * звука не даёт» ([engineUnusable]) — это ставит [SpeechPlayer], когда подряд
 * пришли отказы синтеза.
 */
object TtsHealth {

    /** Почему синтезатор не может читать. NONE — причин не нашли. */
    enum class Problem { NONE, NO_ENGINE, NO_DEFAULT, INIT_FAILED, NO_VOICES, VOICE_MISSING, SILENT_ENGINE }

    data class Answer(val problem: Problem, val detail: String = "")

    /** Движок уже отказал столько раз подряд, что звука от него не будет.
     *  Ставит [SpeechPlayer], читают UI и [ReaderEngine]. */
    @Volatile
    var engineUnusable = false

    /** Снять признак «движок не работает»: движок снова дал звук, ЛИБО человек
     *  сменил движок или голос (17.10.2026, журнал 00:47 — после выбора рабочего
     *  движка первое нажатие «Читать» открывало панель вместо чтения, потому что
     *  признак остался от прежнего, сломанного движка). */
    fun reset() {
        engineUnusable = false
    }

    /** Быстрая проверка СИСТЕМЫ: выбран ли движок озвучки. Ничего не запускает,
     *  не ждёт и ничего не меняет — читаем один системный ключ, поэтому её можно
     *  звать на каждое нажатие «Читать».
     *
     *  Почему ключом, а не `TextToSpeech.getEngines()`: в этой сборке stubs SDK
     *  отдают оба метода как обычные, а не статические, и вызов по имени класса
     *  не компилируется (проверено 16.10.2026). Ключ `tts_default_synth` — тот же,
     *  что показывает экран «Синтезатор речи», и его достаточно: пусто — движок
     *  не выбран, и никакой голос не зазвучит; не пусто — идём читать, а отказы
     *  движка ловит [SpeechPlayer]. */
    fun systemProblem(c: Context): Answer? {
        val def = runCatching {
            android.provider.Settings.Secure.getString(c.contentResolver, "tts_default_synth")
        }.getOrNull()
        if (def.isNullOrBlank()) return Answer(Problem.NO_DEFAULT)
        return null
    }

    /** Словами: что случилось и что делать. Без кодов и без английских слов. */
    fun text(c: Context, a: Answer?): String = when (a?.problem) {
        Problem.NO_ENGINE -> c.getString(R.string.tts_no_engine)
        Problem.NO_DEFAULT -> c.getString(R.string.tts_no_default)
        Problem.INIT_FAILED -> c.getString(R.string.tts_init_failed)
        Problem.NO_VOICES -> c.getString(R.string.tts_no_voices)
        Problem.VOICE_MISSING -> c.getString(R.string.tts_voice_missing)
        Problem.SILENT_ENGINE -> c.getString(R.string.tts_not_ready)
        else -> c.getString(R.string.tts_not_reading)
    }

    /** Слова для случая «чтение встало, потому что движок молчит». Если причина
     *  известна точно — называем её, иначе говорим самое честное общее. */
    fun stopText(c: Context): String = text(c, systemProblem(c) ?: Answer(Problem.SILENT_ENGINE))

    /** Экран настроек синтезатора в системе. НИЧЕГО не переключаем — только
     *  показываем человеку нужный экран. */
    fun openSettings(c: Context) {
        val tts = Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { c.startActivity(tts) }.isSuccess
        if (ok) return
        runCatching {
            c.startActivity(
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Diag.log(c, "tts", "не смог открыть настройки синтезатора")
    }
}
