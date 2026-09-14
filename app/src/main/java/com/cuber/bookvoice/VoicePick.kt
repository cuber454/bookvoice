package com.cuber.bookvoice

import android.content.Context
import android.speech.tts.Voice
import java.util.Locale

/**
 * msg5503/5511: голоса движка — по языкам, а не одной простыней.
 *
 * И панель читалки, и Настройки → «Голос» показывали ПЛОСКИЙ список голосов
 * выбранного движка: русские, английские, украинские вперемешку, да ещё сетевые
 * в общей куче. По имени голоса язык не угадать — у RHVoice «Aleksandr» есть и
 * русский, и английский. Сергей: «надо чтобы выкидывался выбор был какой язык
 * выбирать» (msg5503); схема — три строки-значения «Движок / Язык / Голос»
 * (msg5511), список голосов показывает только выбранный язык.
 *
 * Язык у нас уже есть: движок отдаёт его вместе с голосом ([Voice.getLocale],
 * код ISO 639-2 — «rus», «eng», «ukr»). Здесь только раскладка по языкам и
 * русские названия; состояние выбора (какой язык, какой голос) держат экраны.
 */
object VoicePick {

    /** Русская локаль — для названий языков. */
    private val RU = Locale("ru")

    /** Язык, которого движок не назвал (пустая локаль) — в списке идёт как «Другие». */
    const val OTHER_LANG = "und"

    /** Русские названия языков: движок отдаёт трёхбуквенный код ISO 639-2, а
     *  разворачивает ли его ICU на конкретной прошивке — лотерея. Своя таблица —
     *  страховка от «Russian» и «rus» на экране у незрячего. Собраны языки,
     *  которые реально встречаются у RHVoice, Google TTS и системных движков;
     *  незнакомый код показываем как есть. */
    private val LANG_RU = mapOf(
        "rus" to "Русский", "eng" to "Английский", "ukr" to "Украинский",
        "bel" to "Белорусский", "tat" to "Татарский", "kir" to "Киргизский",
        "kaz" to "Казахский", "uzb" to "Узбекский", "hye" to "Армянский",
        "kat" to "Грузинский", "aze" to "Азербайджанский", "deu" to "Немецкий",
        "fra" to "Французский", "spa" to "Испанский", "ita" to "Итальянский",
        "por" to "Португальский", "nld" to "Нидерландский", "pol" to "Польский",
        "ces" to "Чешский", "slk" to "Словацкий", "bul" to "Болгарский",
        "srp" to "Сербский", "bos" to "Боснийский", "ron" to "Румынский",
        "hun" to "Венгерский", "ell" to "Греческий", "tur" to "Турецкий",
        "heb" to "Иврит", "ara" to "Арабский", "fas" to "Персидский",
        "hin" to "Хинди", "zho" to "Китайский", "jpn" to "Японский",
        "kor" to "Корейский", "fin" to "Финский", "swe" to "Шведский",
        "nor" to "Норвежский", "dan" to "Датский", "lit" to "Литовский",
        "lav" to "Латышский", "est" to "Эстонский", "slv" to "Словенский",
        "hrv" to "Хорватский", "mkd" to "Македонский", "sqi" to "Албанский",
    )

    /** Язык голосов: код ISO 639-2 и его голоса, по алфавиту. */
    class Lang(val code: String, val voices: List<Voice>)

    /** Код языка голоса; пустая локаль — [OTHER_LANG]. */
    fun codeOf(v: Voice): String {
        val c = v.locale?.language?.lowercase(Locale.ROOT).orEmpty()
        return if (c.isEmpty() || c == "und" || c == "zxx") OTHER_LANG else c
    }

    /** Язык голоса по его имени — для строки «Язык: …» по текущему голосу. */
    fun langOf(voices: List<Voice>, voiceName: String?): String? =
        voiceName?.let { name -> voices.firstOrNull { it.name == name }?.let { codeOf(it) } }

    /** Языки, которые есть у движка: по алфавиту русского названия. [first] —
     *  код, который ставим первым (язык текущего голоса): незрячему важно не
     *  свайпать весь список до своего языка. */
    fun langs(voices: List<Voice>, first: String? = null): List<Lang> {
        val byCode = LinkedHashMap<String, MutableList<Voice>>()
        for (v in voices) byCode.getOrPut(codeOf(v)) { ArrayList() }.add(v)
        val sorted = byCode.map { (code, list) -> Lang(code, sortVoices(list)) }
            .sortedBy { langLabel(it.code) }
        val head = sorted.firstOrNull { it.code == first } ?: return sorted
        return listOf(head) + sorted.filter { it !== head }
    }

    /** Язык, который открываем, когда выбора ещё нет (движок только переключили,
     *  голос не выбран): русский, если движок его знает, иначе первый по алфавиту.
     *  Так список голосов не вываливается простынёй всех языков сразу. */
    fun defaultLang(voices: List<Voice>): String? =
        langs(voices).let { list -> (list.firstOrNull { it.code == "rus" } ?: list.firstOrNull())?.code }

    /** Голоса одного языка; [code] = null — все голоса (язык ещё не выбран). */
    fun voicesOf(voices: List<Voice>, code: String?): List<Voice> =
        if (code == null) sortVoices(voices) else sortVoices(voices.filter { codeOf(it) == code })

    /** Имя голоса для списка: как отдаёт движок, сетевые — с пометкой. */
    fun voiceLabel(v: Voice): String =
        if (v.isNetworkConnectionRequired) "${v.name} (сеть)" else v.name

    /** Название языка по-русски: сначала пробуем ICU (он знает все языки),
     *  потом свою таблицу, в крайнем случае — сам код. С большой буквы. */
    fun langLabel(code: String?): String {
        val c = code?.lowercase(Locale.ROOT).orEmpty()
        if (c.isEmpty() || c == OTHER_LANG) return "Другие"
        val icu = runCatching { Locale(c).getDisplayLanguage(RU) }.getOrNull()
        val name = if (!icu.isNullOrBlank() && !icu.equals(c, ignoreCase = true)) {
            icu
        } else {
            LANG_RU[c] ?: c.uppercase(Locale.ROOT)
        }
        return name.replaceFirstChar { it.titlecase(RU) }
    }

    /** «Русский, 8 голосов» — сколько голосов внутри, чтобы вслепую было понятно,
     *  что ждёт за строкой языка. */
    fun langTitle(ctx: Context, code: String, count: Int): String {
        val plural = ctx.resources.getQuantityString(R.plurals.voice_count, count, count)
        return "${langLabel(code)}, $plural"
    }

    private fun sortVoices(list: List<Voice>): List<Voice> =
        list.sortedBy { it.name.lowercase(Locale.ROOT) }
}
