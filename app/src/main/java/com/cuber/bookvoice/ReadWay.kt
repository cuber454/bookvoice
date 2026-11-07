package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences

/**
 * Каким способом читать — РУЧНОЙ выбор человека на пару «движок + голос»
 * (29.10.2026, просьба Сержа).
 *
 * Зачем своя настройка. Серж: «хочу убрать эту автоматизацию, чтобы я для движка
 * сам мог настроить и запомнил навсегда: этот синтезатор, этот голос читает вот
 * так. Если не нужно — отменил». Автоматика (пометки «движок не отдаёт файлы»,
 * переключение на локальный голос, пересчёты на ходу) знает пару сама, но
 * ошибается и «перерабатывает криво» — здесь её выбор железный: что поставил, тем
 * и читает.
 *
 * Два положения (29.10.2026): «голосом движка» и «файлами». Третьего, «решает
 * читалка», больше НЕТ — это оно и было тем самым «то работает, то нет»
 * (просьба Сержа: «может, вообще убрать автоматический режим — оставить только по
 * умолчанию и файлами»).
 *
 * Ключ — ПАРА, потому что у одного движка локальные голоса файлы отдают, а
 * сетевые нет: именно на этом автоматика и путалась.
 */
object ReadWay {

    const val AUTO = ExpertPrefs.MODE_AUTO
    const val FILE = ExpertPrefs.MODE_FILE
    const val DIRECT = ExpertPrefs.MODE_DIRECT

    /** Значение по умолчанию — ГОЛОСОМ ДВИЖКА (решение Сержа 29.10.2026:
     *  «пускай по умолчанию читает просто обычным синтезатором, чтобы никто не
     *  заморачивался, что там крутить»).
     *
     *  Это же и прежнее поведение: галочка «Читать файлами» по умолчанию была
     *  выключена, то есть обычный способ и означал «голосом движка». Так что
     *  перехода для человека не происходит — но теперь это записано явно, а не
     *  выводится автоматикой. */
    const val DEFAULT = DIRECT

    private const val PREFS = "reader"
    private const val KEY_PREFIX = "read_way_"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Ключ пары. Голос может ещё не быть выбран — тогда пустая строка: настройка
     *  «на весь движок» и есть положение [AUTO]. */
    private fun key(engine: String?, voice: String?): String =
        KEY_PREFIX + (engine.orEmpty()) + "|" + (voice.orEmpty())

    /** Что выбрано для этой пары. [engine] null — системный движок. */
    fun of(c: Context, engine: String?, voice: String?): Int =
        prefs(c).getInt(key(engine, voice), DEFAULT).coerceIn(AUTO, DIRECT)

    fun set(c: Context, engine: String?, voice: String?, value: Int) {
        prefs(c).edit().putInt(key(engine, voice), value.coerceIn(AUTO, DIRECT)).apply()
        Diag.log(
            c, "tts",
            "способ чтения выбран вручную: ${label(value)} — движок ${engine ?: "системный"}, " +
                "голос ${voice ?: "не назван"}"
        )
    }

    fun label(value: Int): String = when (value) {
        FILE -> "файлами"
        DIRECT -> "голосом движка"
        else -> "решает читалка"
    }

    /** Подпись строки в панели «Голос» — вместе с названием строки. */
    fun fullLabel(value: Int): String = "Как читать: " + label(value)
}
