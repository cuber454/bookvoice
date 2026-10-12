package com.cuber.bookvoice

import android.content.Context

/** Каким способом книга читается ПРЯМО СЕЙЧАС — словами (06.10.2026).
 *
 *  Зачем. Галочка «Альтернативный способ чтения» отвечает только за выбор
 *  человека, а прямой режим включается и САМ: движок не отдал файлы — читаем его
 *  голосом ([SpeechPlayer.netDirect], [SpeechPlayer.markNetDirect]). Тогда галочка
 *  снята, а читает всё равно движок, и по настройкам этого не видно. Просьба
 *  Сержа 06.10.2026: «у меня сейчас сетевой синтезатор, и я не могу определять по
 *  галочке... как сделать, чтобы я понимал».
 *
 *  Отвечаем из двух источников: живой плеер (он точно знает, чем читает, —
 *  [SpeechPlayer.directWhy]) и настройки с пометками движка ([ExpertPrefs]).
 *  Живой ответ главнее, но только после того, как пара «движок + голос» уже
 *  применена (чтение начиналось): до этого [SpeechPlayer.directWhy] пуст, и
 *  помеченная пара выглядела бы обычной. Поэтому при пустом живом ответе смотрим
 *  пометки. Второй источник слабее: он не знает, заявляет ли голос сеть.
 *
 *  Где видно: строка «Каким способом читаю» в разделе «Экспериментальные настройки»
 *  (её касание читает [long] вслух) и вторая строка у самой галочки альтернативного
 *  способа в разделе «Чтение» ([SettingsActivity.refreshRows]).
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
        return if (a.direct) "альтернативным: ${a.why}" else "обычным: ${a.why}"
    }

    /** Подробный ответ — его читаем вслух по касанию строки: чем читаю, почему и
     *  что с этим делать. */
    fun long(c: Context): String {
        val a = answer(c)
        val sb = StringBuilder()
        sb.append(
            if (a.direct) {
                "Читаю альтернативным способом: фразу целиком играет сам движок, " +
                    "нам он файла не отдаёт. "
            } else {
                "Читаю обычным способом: движок готовит файл, а играет наша читалка. " +
                    "Скорость, паузы и стык фраз работают как обычно. "
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
        // Галочка снята, а способ всё равно прямой: объясняем, что винить её не в
        // чем, и как вернуть файловый путь. При принудительном способе советы не
        // нужны: там причина — сама ручка опытных настроек.
        if (a.direct && !a.forced && !galochka(c)) {
            sb.append(" Галочка «Альтернативный способ чтения» здесь ни при чём — ")
            sb.append("способ включился сам, по опыту с этим движком. ")
            sb.append("Забыть пометки движка можно строкой «Забыть пометки движка»; ")
            sb.append("обычным способом движок станет со следующего запуска чтения.")
        }
        if (a.forced) {
            sb.append(" Сменить способ можно там же — строкой «Движки»; ")
            sb.append("он применится, когда начнёшь читать заново.")
        }
        return sb.toString()
    }

    /** Ответ и то, откуда он: `live` — от живого плеера (значит, верен для той
     *  пары, что выбрана сейчас), иначе — по настройкам и пометкам; `forced` —
     *  способ задан принудительно (см. [ExpertPrefs.forceMode]), и тогда советы
     *  «забудь пометки» неуместны. */
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

    /** Почему читаем обычным способом, словами: тот же разбор пометок, что и для
     *  прямого (движок файлы отдаёт, пометок на паре нет). */
    private fun ordinaryWhy(c: Context): String = fromMarks(c).why

    /** Движок, чей профиль способа чтения смотрим: живой плеер знает его точно,
     *  иначе — выбранный в настройках, иначе — движок по умолчанию системы. */
    private fun modeEngineFor(c: Context): String? {
        ReaderEngine.player?.enginePackage?.let { return it }
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        p?.getString(MainActivity.KEY_ENGINE, null)?.let { return it }
        return ExpertPrefs.defaultEngine(c)
    }

    /** Прямой ли способ у пары «движок + голос» из настроек, и почему, словами.
     *  Пометки читаем прямо из настроек, а не у плеера: так ответ верен и до
     *  начала чтения. */
    private data class Marks(val direct: Boolean, val why: String)

    private fun fromMarks(c: Context): Marks {
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
            ?: return Marks(false, "настройки чтения недоступны")
        if (p.getBoolean(MainActivity.KEY_ALT_VOICE, false)) {
            return Marks(true, "так выбрано галочкой «Альтернативный способ чтения»")
        }
        val engine = p.getString(MainActivity.KEY_ENGINE, null) ?: "системный"
        val voice = p.getString(MainActivity.KEY_VOICE, null) ?: ""
        val marks = runCatching { p.getStringSet(KEY_DIRECT_SYNTH, emptySet()) }
            .getOrDefault(emptySet()) ?: emptySet()
        if (marks.contains("$engine|$voice")) {
            return Marks(true, "этому движку с этим голосом файлы не заказываю: помечено в прошлый раз")
        }
        val fails = marks.count { it.substringBefore('|') == engine }
        val tail = if (fails > 0) {
            ", но без файлов у него уже $fails голосов: сетевой голос он тоже не отдаст"
        } else {
            ""
        }
        return Marks(false, "движок $engine файлы отдаёт$tail")
    }

    /** Движок и голос, выбранные в настройках, — для ответа, когда плеера нет. */
    private fun pairFromPrefs(c: Context): String {
        val p = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
        val engine = p?.getString(MainActivity.KEY_ENGINE, null) ?: "системный"
        val voice = p?.getString(MainActivity.KEY_VOICE, null) ?: "не назван"
        return "движок $engine, голос $voice"
    }

    private fun galochka(c: Context): Boolean = runCatching {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_ALT_VOICE, false)
    }.getOrDefault(false)
}
