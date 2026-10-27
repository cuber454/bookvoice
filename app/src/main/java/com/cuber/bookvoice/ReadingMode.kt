package com.cuber.bookvoice

import android.content.Context

/** Каким способом книга читается ПРЯМО СЕЙЧАС — словами (06.10.2026, переписано
 *  24.10.2026).
 *
 *  24.10.2026 способы поменялись местами: обычный — ГОЛОСОМ ДВИЖКА (как у @Voice
 *  Aloud Reader), файловый путь стал обходным и включается строкой «Читать файлами
 *  (обходной способ)» ([MainActivity.KEY_READ_FILES], по умолчанию выключена).
 *  Прежняя галочка «Альтернативный способ чтения» больше не главная: её значение
 *  перенесено в новый ключ один раз ([PrefsMigration]).
 *
 *  Зачем объект. По настройкам не видно, что происходит: прямой путь включается и
 *  САМ (движок не отдал файлы → [SpeechPlayer.netDirect]), а файловый — только по
 *  просьбе человека. Просьба Сержа 06.10.2026: «я не могу определять по галочке,
 *  как сделать, чтобы я понимал». Отсюда строка «Каким способом читаю» в опытных
 *  настройках ([SettingsActivity.buildExperimentalGroup]): диктор читает вторую
 *  строку при переходе, а касание читает вслух весь разбор ([long]).
 *
 *  24.10.2026, вечер: строку вернули за дверь опытных настроек — Серж: «эту
 *  настройку надо занести в скрытые эту строчку». Обычному человеку разбор в
 *  «Чтении» мешал: способ он понимает по галочке «Читать файлами», а «почему так»
 *  нужно владельцу. Вторая строка у галочки — снова обычная подсказка.
 *
 *  Отвечаем из двух источников: живой плеер (он точно знает, чем читает, —
 *  [SpeechPlayer.directWhy]) и настройки с пометками движка ([ExpertPrefs]).
 *  Живой ответ главнее, но только после того, как пара «движок + голос» уже
 *  применена (чтение начиналось): до этого [SpeechPlayer.directWhy] пуст, и
 *  помеченная пара выглядела бы обычной.
 */
object ReadingMode {

    /** Файл настроек чтения — как у [SpeechPlayer] и [ExpertPrefs]. */
    private const val PREFS = "reader"

    /** Ключ пометок «этому движку с этим голосом файлы не заказывать» — как в
     *  `SpeechPlayer.KEY_DIRECT_SYNTH`. */
    private const val KEY_DIRECT_SYNTH = "direct_synth_marks"

    /** Короткий ответ: он стоит второй строкой в строке настроек и озвучивается
     *  диктором при переходе по списку, без касания. */
    fun short(c: Context): String {
        val a = answer(c)
        return if (a.direct) "голосом движка: ${a.why}" else "файлами: ${a.why}"
    }

    /** Подробный ответ — его читаем вслух по касанию строки: чем читаю, почему и
     *  что с этим делать. */
    fun long(c: Context): String {
        val a = answer(c)
        val sb = StringBuilder()
        sb.append(
            if (a.direct) {
                "Читаю голосом движка: фразу целиком произносит синтезатор, а " +
                    "читалка только подаёт ему текст. Так читает и @Voice. "
            } else {
                "Читаю файлами, обходным способом: движок пишет звук в файл, а " +
                    "играет наша читалка — так срезаются паузы по краям фраз. "
            }
        )
        sb.append("Причина: ").append(a.why).append(". ")
        if (!a.live) {
            sb.append(
                "Отвечаю по настройкам и пометкам: эта пара «движок + голос» в чтении " +
                    "ещё не работала. "
            )
        }
        sb.append(if (a.live) ReaderEngine.player?.pairLabel else pairFromPrefs(c)).append('.')
        if (a.direct) {
            sb.append(" Хотите файлами — включите строку «Читать файлами (обходной ")
            sb.append("способ)» в подразделе «Альтернативное чтение».")
        }
        if (a.forced) {
            sb.append(" Сменить способ можно там же — строкой «Движки»; ")
            sb.append("он применится, когда начнёшь читать заново.")
        }
        return sb.toString()
    }

    /** Ответ и то, откуда он: `live` — от живого плеера (значит, верен для той
     *  пары, что выбрана сейчас), иначе — по настройкам и пометкам; `forced` —
     *  способ задан принудительно (см. [ExpertPrefs.forceMode]). */
    private class Answer(
        val direct: Boolean,
        val why: String,
        val live: Boolean,
        val forced: Boolean = false,
    )

    private fun answer(c: Context): Answer {
        // Профиль способа чтения у КАЖДОГО движка свой (см. [ExpertPrefs.forceMode])
        // и сильнее всего остального: он отменяет и пометки, и опыт, и ответ про
        // них читать нельзя — человек специально выбрал способ.
        when (val forced = ExpertPrefs.forceMode(c, modeEngineFor(c))) {
            ExpertPrefs.MODE_FILE -> return Answer(
                false,
                "так выбрано для этого движка в опытных настройках: " +
                    "«${ExpertPrefs.forceModeLabel(forced)}» — пометки движка не смотрю",
                true,
                forced = true,
            )
            ExpertPrefs.MODE_DIRECT -> return Answer(
                true,
                "так выбрано для этого движка в опытных настройках: " +
                    "«${ExpertPrefs.forceModeLabel(forced)}»",
                true,
                forced = true,
            )
        }
        val player = ReaderEngine.player
        // Живой плеер главнее: он знает, чем читает НА САМОМ ДЕЛЕ. Но только
        // если он говорит о той же паре, что выбрана в настройках: после смены
        // движка или голоса он ещё держит ответ о прежней паре.
        if (player != null && samePairAsPrefs(player, c)) {
            val why = player.directWhy
            return Answer(why != null, why ?: (player.fileWhy ?: ordinaryWhy(c)), true)
        }
        val marks = fromMarks(c)
        return Answer(marks.direct, marks.why, false)
    }

    /** Та же ли пара «движок + голос» у плеера, что выбрана в настройках. */
    private fun samePairAsPrefs(player: SpeechPlayer, c: Context): Boolean {
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        val engine = p?.getString(MainActivity.KEY_ENGINE, null) ?: "системный"
        val voice = p?.getString(MainActivity.KEY_VOICE, null) ?: ""
        return (player.enginePackage ?: "системный") == engine && (player.pairVoice ?: "") == voice
    }

    /** Почему читаем голосом, словами: тот же разбор пометок, что и для файлового
     *  пути (пометки на паре нет — значит путь свободен). */
    private fun ordinaryWhy(c: Context): String = fromMarks(c).why

    /** Движок, чей профиль способа чтения смотрим: живой плеер знает его точно,
     *  иначе — выбранный в настройках, иначе — движок по умолчанию системы. */
    private fun modeEngineFor(c: Context): String? {
        ReaderEngine.player?.enginePackage?.let { return it }
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        p?.getString(MainActivity.KEY_ENGINE, null)?.let { return it }
        return ExpertPrefs.defaultEngine(c)
    }

    /** Читаем ли файлами по настройкам, и почему, словами. Пометки читаем прямо из
     *  настроек, а не у плеера: так ответ верен и до начала чтения. */
    private data class Marks(val direct: Boolean, val why: String)

    private fun fromMarks(c: Context): Marks {
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
            ?: return Marks(true, "настройки чтения недоступны, читаю голосом")
        // 24.10.2026: строка «Читать файлами» — единственный вход в файловый путь;
        // по умолчанию она выключена, и тогда обычное чтение — голосом движка.
        if (!PrefsMigration.readFiles(c)) {
            return Marks(true, "обычный способ: так у нас по умолчанию, как у @Voice")
        }
        val engine = p.getString(MainActivity.KEY_ENGINE, null) ?: "системный"
        val voice = p.getString(MainActivity.KEY_VOICE, null) ?: ""
        val marks = runCatching { p.getStringSet(KEY_DIRECT_SYNTH, emptySet()) }
            .getOrDefault(emptySet()) ?: emptySet()
        if (marks.contains("$engine|$voice")) {
            return Marks(
                true,
                "так выбрано строкой «Читать файлами», но этому голосу файлы не " +
                    "заказываю: помечено в прошлый раз — прочту голосом",
            )
        }
        val fails = marks.count { it.substringBefore('|') == engine }
        val tail = if (fails > 0) {
            ", но без файлов у него уже $fails голосов: сетевой голос он тоже не отдаст"
        } else {
            ""
        }
        return Marks(false, "так выбрано строкой «Читать файлами»; движок $engine файлы отдаёт$tail")
    }

    /** Движок и голос, выбранные в настройках, — для ответа, когда плеера нет. */
    private fun pairFromPrefs(c: Context): String {
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        val engine = p?.getString(MainActivity.KEY_ENGINE, null) ?: "системный"
        val voice = p?.getString(MainActivity.KEY_VOICE, null) ?: "не назван"
        return "движок $engine, голос $voice"
    }
}
