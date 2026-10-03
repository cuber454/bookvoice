package com.cuber.bookvoice

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * «Чтение замолкало» — след для окна «Не засыпать» (30.09.2026).
 *
 *  Зачем отдельная память. Самая частая жалоба тестеров — «читал, и встало».
 *  Причин три, и на слух они неразличимы: прошивка заморозила процесс, система
 *  убила его ради памяти, или замолчал движок речи. Про первые две знает система
 *  (см. [SleepGuard.lastExit]), третью видим только мы: её ловят наши сторожа
 *  звука ([SpeechPlayer.armSoundWatch], [SpeechPlayer.armExoStartWatch],
 *  [SpeechPlayer.armDirectWatch]).
 *
 *  Пишем не только в журнал, но и в настройки. Журнал обрезается по размеру, а
 *  ответ на вопрос «часто ли это вообще бывает» должен пережить и обрезку, и
 *  перезапуск приложения: счётчик копится с установки, время последней заминки
 *  хранится отдельно, вместе с короткой причиной.
 */
object StallLog {

    private const val KEY_COUNT = "stall_count"
    private const val KEY_LAST = "stall_last_ms"
    private const val KEY_NOTE = "stall_last_note"

    private val fmtTime = SimpleDateFormat("HH:mm", Locale.US)
    private val fmtDay = SimpleDateFormat("dd.MM в HH:mm", Locale.US)

    private fun prefs(c: Context) = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
    }.getOrNull()

    /** Заминка: звук не пошёл, хотя чтение его ждало. Зовут сторожа звука —
     *  каждый такой случай считается один раз, поэтому счётчик честный. */
    fun record(c: Context, note: String) {
        val p = prefs(c) ?: return
        val count = p.getInt(KEY_COUNT, 0) + 1
        runCatching {
            p.edit()
                .putInt(KEY_COUNT, count)
                .putLong(KEY_LAST, System.currentTimeMillis())
                .putString(KEY_NOTE, note)
                .apply()
        }
    }

    /** Строка для окна: «Замолкало: 3 раза, последний раз сегодня в 16:12 (прямая
     *  речь молчала 20 с)». null — заминок не было: тогда и строки нет. */
    fun summary(c: Context): String? {
        val p = prefs(c) ?: return null
        val count = p.getInt(KEY_COUNT, 0)
        if (count <= 0) return null
        val at = p.getLong(KEY_LAST, 0L)
        val note = p.getString(KEY_NOTE, null)
        val whenText = if (at <= 0L) "" else {
            val same = isToday(at)
            ", последний раз " + (if (same) "сегодня в " else "") +
                (if (same) fmtTime.format(Date(at)) else fmtDay.format(Date(at)))
        }
        val tail = if (note.isNullOrBlank()) "" else " ($note)"
        return "Замолкало: ${plural(count)} с установки$whenText$tail"
    }

    /** «1 раз», «2 раза», «5 раз» — по-русски, а не «2 раз». */
    private fun plural(n: Int): String {
        val tail100 = n % 100
        val tail10 = n % 10
        return when {
            tail100 in 11..14 -> "$n раз"
            tail10 == 1 -> "$n раз"
            tail10 in 2..4 -> "$n раза"
            else -> "$n раз"
        }
    }

    /** Забыть заминки — вместе с «Очистить журнал»: человек ждёт, что очистка
     *  стирает и этот след (см. [Diag.clear]). */
    fun clear(c: Context) {
        runCatching {
            prefs(c)?.edit()
                ?.remove(KEY_COUNT)?.remove(KEY_LAST)?.remove(KEY_NOTE)?.apply()
        }
    }

    private fun isToday(ms: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
}
