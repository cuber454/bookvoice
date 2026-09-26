package com.cuber.bookvoice

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Что нового» после обновления (#18, msg4113-4141).
 *
 *  Зачем. Человек обновился — и не знает, что изменилось: читалку он глазами не
 *  просматривает, а новое чаще всего спрятано в жестах и формулировках. Окошко
 *  на полке называет 3-4 изменения фразами (их читают вслух) и уходит.
 *
 *  Когда показывается (условия согласованы, msg4125):
 *  1. только на полке и только в запуск — первый показ полки в этом окне;
 *  2. перед авто-открытием последней книги (вариант A): книга ждёт «Понятно»;
 *  3. если книга всё-таки успела открыться — окошко не показываем И НЕ помечаем
 *     показанным, оно доедет до следующего запуска.
 *
 *  Как отличаем обновление от первого запуска: `whats_new_seen_code` в prefs
 *  хранит versionCode, на котором человек видел окошко. Запись меньше текущего
 *  кода — обновились: показываем ВСЕ выпуски новее виденного (человек мог
 *  пропустить несколько). Равна — молчим. Больше (откатились на старую версию) —
 *  молча перезаписываем. Совсем нет записи — обновились с выпуска, где засечки
 *  ещё не было (0.4.25 и раньше): это обновление, показываем. А «самая первая
 *  установка» ловится отдельно и раньше — [markFirstRunIfFresh], из
 *  Application.onCreate: если у человека не осталось ни одного следа работы
 *  приложения, обновляться ему не с чего и окошко показывать не за что.
 *
 *  Тексты — строки в ресурсах `whats_new_<versionCode>`, по одной на пункт
 *  (см. whats_new_strings.xml). Новый выпуск с новостями = поднять versionCode
 *  в build.gradle.kts и дописать строки сюда, в [NOTES]. Ключ именно код, а не
 *  имя версии: имя меняется от рекламных соображений, код — никогда.
 *
 *  Переслушать список можно из «О программе» (msg4133): с одной кнопкой в
 *  окошке текст иначе пропадал бы навсегда.
 */
object WhatsNew {

    private const val PREFS = "reader"
    private const val KEY_SEEN = "whats_new_seen_code"

    /** Новости по выпускам: versionCode → пункты (строковые ресурсы). Порядок
     *  вставки — порядок показа. Выпуск без записи здесь просто молчит. */
    private val NOTES: Map<Int, List<Int>> = linkedMapOf(
        102 to listOf(
            R.string.whats_new_102_1,
            R.string.whats_new_102_2,
            R.string.whats_new_102_3,
            R.string.whats_new_102_4,
            R.string.whats_new_102_5,
            R.string.whats_new_102_6,
            R.string.whats_new_102_7,
            R.string.whats_new_102_8,
            R.string.whats_new_102_9,
            R.string.whats_new_102_10,
        ),
        // 0.4.56: блок 103 убран целиком — 0.4.43 публичным релизом не вышла,
        // а её пункт про «Останавливать чтение» объявлял удалённую настройку.
        // Всё накопленное с 0.4.42 идёт одним блоком, тексты утверждены
        // Сергеем (msg6455, «да нормально так» msg6457).
        116 to listOf(
            R.string.whats_new_116_1,
            R.string.whats_new_116_2,
            R.string.whats_new_116_3,
            R.string.whats_new_116_4,
            R.string.whats_new_116_5,
            R.string.whats_new_116_6,
            R.string.whats_new_116_7,
            R.string.whats_new_116_8,
        ),
        // 0.4.67: правило «только крупное» здесь сознательно нарушено по просьбе
        // Сергея (23.09.2026): «по одной строчке буквально про каждое изменение,
        // чтобы тестеры поняли, что изменилось». Выпуск почти целиком про сроки
        // ожидания и очередь фраз — то есть ровно про то, что человек слышит как
        // заминку и паузы, и молчать об этом в окне новостей было бы странно.
        127 to listOf(
            R.string.whats_new_127_1,
            R.string.whats_new_127_2,
            R.string.whats_new_127_3,
            R.string.whats_new_127_4,
            R.string.whats_new_127_5,
            R.string.whats_new_127_6,
            R.string.whats_new_127_7,
        ),
        // 0.4.68: тот же порядок — по строке на изменение (просьба Сергея
        // 24.09.2026 остаётся в силе: тестеры должны понимать, что поменялось).
        // Здесь всё про задержку перед звуком и про темп — то, что слышно с
        // первого нажатия.
        128 to listOf(
            R.string.whats_new_128_1,
            R.string.whats_new_128_2,
            R.string.whats_new_128_3,
            R.string.whats_new_128_4,
        ),
        // 0.4.69: по строке на изменение (вопрос Сергея 24.09.2026: «говорит,
        // что скачивает FB2, а скачивает в zip»).
        129 to listOf(
            R.string.whats_new_129_1,
            R.string.whats_new_129_2,
            R.string.whats_new_129_3,
        ),
        // 0.4.70: по строке на изменение (архив «Жизнь в поместье» от Сергея
        // 24.09.2026 — внутри книга в PDF и служебный файл описания).
        130 to listOf(
            R.string.whats_new_130_1,
            R.string.whats_new_130_2,
        ),
        // 0.4.71: по строке на изменение (книга «Жизнь в поместье»: чистка
        // колонтитулов, номеров и буквиц в текстовом слое скана).
        131 to listOf(
            R.string.whats_new_131_1,
            R.string.whats_new_131_2,
            R.string.whats_new_131_3,
        ),
        // 0.4.72: доводка чистки скана по учебнику «Человек и мир. 2 класс».
        132 to listOf(
            R.string.whats_new_132_1,
            R.string.whats_new_132_2,
            R.string.whats_new_132_3,
        ),
        // 0.4.73: переход по главам отменялся чужой целью прокрутки.
        133 to listOf(
            R.string.whats_new_133_1,
            R.string.whats_new_133_2,
        ),
        // 0.4.74: шаги по книге на паузе начинают читать (просьба Сергея).
        134 to listOf(
            R.string.whats_new_134_1,
            R.string.whats_new_134_2,
        ),
        // 0.4.76: переключение синтезатора откатывалось на системный движок.
        136 to listOf(
            R.string.whats_new_136_1,
        ),
        // 0.4.77: «волшебное касание» TalkBack снова ставит паузу.
        137 to listOf(
            R.string.whats_new_137_1,
        ),
        // 0.4.79: лента списка, разошедшаяся с книгой, пересобирается сама.
        139 to listOf(
            R.string.whats_new_139_1,
        ),
        // 0.4.80: кэш текста не теряет признак «название придумали мы».
        140 to listOf(
            R.string.whats_new_140_1,
        ),
        // 0.4.81: настоящая причина рассинхрона — ошибка поиска строки ленты.
        141 to listOf(
            R.string.whats_new_141_1,
        ),
        // 0.4.82: вторая причина «переключается на системный» — гасили движок
        // во время его запуска.
        142 to listOf(
            R.string.whats_new_142_1,
        ),
        // 0.4.87: сводный блок вместо 143, 144 и 146 — те выпуски публичным
        // релизом не выходили, тестеры оставались на 0.4.82 (142). По просьбе
        // Сергея 26.09.2026 в окне оставлено только самое основное: строка про
        // исправления и три вещи, которые видно сразу, — фоновая загрузка,
        // действия строки в меню TalkBack, пункт «Действия», и порядок в
        // синхронизации. Блоки 143,
        // 144 и 146 убраны целиком, как когда-то блок 103. Поводом для 0.4.87
        // был журнал 25.09.2026: заливка двенадцати мегабайт каждые
        // тринадцать секунд.
        147 to listOf(
            R.string.whats_new_147_1,
            R.string.whats_new_147_2,
            R.string.whats_new_147_3,
            R.string.whats_new_147_4,
        ),
    )

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** versionCode установленного приложения: `longVersionCode` — с Android 9,
     *  у нас minSdk 24, поэтому поле `versionCode` под проверкой версии. */
    @Suppress("DEPRECATION")
    private fun currentCode(c: Context): Int = runCatching {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }.getOrDefault(0)

    /** Заведены ли новости для этой версии — по этому признаку вход «Что нового»
     *  появляется в «О программе». */
    fun hasNotes(c: Context): Boolean = !NOTES[currentCode(c)].isNullOrEmpty()

    /**
     * Первый запуск ПОСЛЕ УСТАНОВКИ: засечки нет и других следов работы
     * приложения в prefs тоже нет. Зовётся из Application.onCreate — раньше
     * любого экрана, поэтому «prefs пуст» честно значит «человек только что
     * поставил BookVoice и ещё ничего не делал». Такому окошко не нужно:
     * обновляться ему не с чего. Запоминаем версию и молчим — до следующего
     * обновления.
     */
    fun markFirstRunIfFresh(c: Context) {
        val p = prefs(c)
        if (p.contains(KEY_SEEN)) return
        if (p.all.isNotEmpty()) return // приложение уже работало — это обновление
        val code = currentCode(c)
        p.edit().putInt(KEY_SEEN, code).apply()
        Diag.log(c, "whatsnew", "первый запуск после установки: запомнил версию $code")
    }

    /** Пункты всех выпусков новее [seen] и не новее [cur]. */
    private fun notesBetween(seen: Int, cur: Int): List<Int> =
        NOTES.filterKeys { it > seen && it <= cur }.values.flatten()

    /**
     * Показать окошко на полке при запуске. Возвращает true, если окошко развернулось:
     * тогда [onClosed] позовут по его закрытию и скажут, продолжать ли запуск —
     * `false` приходит, когда человек ушёл по кнопке в Telegram (открывать книгу
     * вдогонку нельзя: чтение зазвучало бы за спиной, в чужом приложении; вернётся
     * на полку — запуск откроет книгу как обычно).
     */
    fun showOnLaunch(act: Activity, onClosed: (Boolean) -> Unit): Boolean {
        val cur = currentCode(act)
        val seen = prefs(act).getInt(KEY_SEEN, 0)
        if (cur == seen) return false
        if (cur < seen) {
            // Откат на более старую версию: молча перезаписываем, иначе после
            // возврата на новую окошко показалось бы второй раз.
            prefs(act).edit().putInt(KEY_SEEN, cur).apply()
            Diag.log(act, "whatsnew", "версия старше виденной ($cur < $seen) — молча перезаписал")
            return false
        }
        // seen == 0 — обновились с выпуска, где засечки не было (до 0.4.26).
        // Свежая установка сюда не доходит: её отметил markFirstRunIfFresh.
        // Условие 3 (msg4125): книга успела открыться первой — окошко не
        // показываем И НЕ помечаем показанным: доедет до следующего запуска.
        if (MainActivity.active != null) {
            Diag.log(act, "whatsnew", "книга уже открыта — окошко не показываю и не помечаю")
            return false
        }
        val texts = notesBetween(seen, cur).map { act.getString(it) }
        if (texts.isEmpty()) {
            // Выпуск без новостей — отмечаем, чтобы не проверять его каждый запуск.
            prefs(act).edit().putInt(KEY_SEEN, cur).apply()
            Diag.log(act, "whatsnew", "новостей на $cur нет — просто отметил")
            return false
        }
        if (act.isFinishing || act.isDestroyed) return false
        // Помечаем ДО показа: окошко одноразовое, а показанное дважды хуже
        // пропущенного (человек не поймёт, почему оно вернулось).
        prefs(act).edit().putInt(KEY_SEEN, cur).apply()
        Diag.log(act, "whatsnew", "показываю «Что нового» ($seen → $cur), пунктов ${texts.size}")
        show(act, texts, onClosed)
        return true
    }

    /** Переслушать новости текущей версии — вход из «О программе» (msg4133). */
    fun showForAbout(act: Activity) {
        val res = NOTES[currentCode(act)] ?: return
        show(act, res.map { act.getString(it) }, null)
    }

    private fun show(act: Activity, notes: List<String>, onClosed: ((Boolean) -> Unit)?) {
        if (act.isFinishing || act.isDestroyed) return
        var wentToGroup = false
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle(act.getString(R.string.whats_new_title))
            // Пункты разделяем пустой строкой: скринридер делает паузу, глаз
            // видит отдельные строки. Одна простыня читается и звучит хуже.
            .setMessage(notes.joinToString("\n\n"))
            .setPositiveButton(act.getString(R.string.whats_new_ok), null)
            .setNeutralButton(act.getString(R.string.whats_new_group)) { _, _ ->
                wentToGroup = true
                AuthorContact.open(act)
            }
            .create()
        // Закрытие — любое: кнопкой, «назад» или уходом в Telegram. Запуск
        // продолжается только если человек остался в приложении.
        dlg.setOnDismissListener { onClosed?.invoke(!wentToGroup) }
        dlg.show()
    }
}
