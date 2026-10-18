package com.cuber.bookvoice

import android.content.Context

/** Экспериментальные настройки: скрытый раздел для владельца и интересующихся
 *  (06.10.2026).
 *
 *  Зачем. Обычному человеку эти ручки не нужны, а в общих настройках они пухнут
 *  и пугают: у нас уже были жалобы «интерфейс перегружен». Поэтому секретная
 *  дверь: семь нажатий по строке версии в окне «О программе» (см.
 *  [AboutWindowActivity]) — и в конце списка настроек появляется раздел
 *  «Экспериментальные настройки» ([SettingsActivity.buildExperimentalGroup]).
 *  Слово «Экспериментальные» стоит только в названии папки и в заголовке раздела:
 *  07.10.2026 Серж пожаловался, что приставка звучала перед КАЖДОЙ строкой
 *  («синтезатор пишет „Экспериментальная такая-то, Экспериментальная такая-то“»),
 *  и строки внутри названы коротко. Столько же нажатий выключают режим
 *  обратно, плюс есть строка «Выключить экспериментальные настройки».
 *
 *  Почему семь нажатий, а не удержание: обычные нажатия шлют и TalkBack, и
 *  Jieshuo, и «Голосовой доступ», а «двойное касание с удержанием» у разных
 *  дикторов работает по-разному. Счёт сбрасывается паузой больше
 *  [AboutWindowActivity.TAP_WINDOW_MS], чтобы случайные касания ничего не
 *  открывали.
 *
 *  Что лежит за дверью (на 06.10.2026): глубина заготовок впрок, «отдавать
 *  движку по одной фразе» ([onePhrase]), сброс пометок движка («медленный»,
 *  «файлов не отдаёт») и проба движка ([EngineProbe] — применяет ли он нашу
 *  скорость). Список будет расти — сюда же пойдут сроки ожидания и опыт с
 *  двумя движками.
 *
 *  Флаг режима живёт в тех же настройках «reader», что пометки движка и прочее
 *  чтение, поэтому переживает обновление приложения. При старте он пишется в
 *  журнал (см. [MainActivity]): иначе при разборе жалобы не понять, почему у
 *  человека ведёт себя не как у всех.
 */
object ExpertPrefs {

    /** Файл настроек чтения — тот же, что у [SpeechPlayer.marks]. */
    private const val PREFS = "reader"
    private const val KEY_ON = "expert_mode"
    private const val KEY_PREFETCH = "expert_prefetch"
    private const val KEY_ONE_PHRASE = "expert_one_phrase"

    // Разбор PDF (09.10.2026, просьба Сержа): разбор стал умнее — склейка
    // разрядки по зазорам, чтение по колонкам, чистка колонтитулов, — и на каждую
    // правку своя галочка, чтобы можно было вернуть прежнее поведение. Ключи
    // открыты: строки настроек пишут по ним напрямую ([SettingsActivity.addCheck]).
    const val KEY_PDF_HEADERS = "expert_pdf_headers"
    const val KEY_PDF_SPACING = "expert_pdf_spacing"
    const val KEY_PDF_COLUMNS = "expert_pdf_columns"

    /** Главы PDF по КЕГЛЮ (16.10.2026, сборка 263x): у книги без закладок
     *  крупные абзацы становятся главами. Выключишь — книга снова делится по
     *  страницам («Стр. 1», «Стр. 2»…), как было до 263x. */
    const val KEY_PDF_HEADINGS = "expert_pdf_headings"

    /** Что делать при разборе PDF прямо сейчас (см. [PdfParser.Options]). */
    fun pdfOptions(c: Context): PdfParser.Options = PdfParser.Options(
        headers = prefs(c).getBoolean(KEY_PDF_HEADERS, true),
        spacing = prefs(c).getBoolean(KEY_PDF_SPACING, true),
        columns = prefs(c).getBoolean(KEY_PDF_COLUMNS, true),
        headings = prefs(c).getBoolean(KEY_PDF_HEADINGS, true),
    )

    /** Отпечаток набора чисток — для кэша разбора: сменились галочки, старый
     *  разбор книги не годится (её надо разобрать заново). */
    fun pdfStamp(c: Context): Long = pdfOptions(c).stamp

    /** Ключи пометок движка — как в [SpeechPlayer]: «медленный» и «файлов не
     *  отдаёт». Дублируем строки сознательно: они уже лежат на телефонах под
     *  этими именами, переименовывать нельзя. */
    private const val KEY_SLOW_SYNTH = "slow_synth_marks"
    private const val KEY_DIRECT_SYNTH = "direct_synth_marks"

    /** Сколько фраз держать готовыми вперёд, если человек не трогал ручку.
     *  Прежнее число из [SpeechPlayer] — поведение по умолчанию не менялось. */
    const val DEFAULT_PREFETCH = 3

    /** Границы ручки: меньше одной заготовки смысла нет, больше шести — только
     *  зря греть процессор у локального движка. */
    private const val MIN_PREFETCH = 1
    private const val MAX_PREFETCH = 6

    fun isOn(c: Context): Boolean = prefs(c).getBoolean(KEY_ON, false)

    fun setOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply()
    }

    fun prefetch(c: Context): Int =
        prefs(c).getInt(KEY_PREFETCH, DEFAULT_PREFETCH).coerceIn(MIN_PREFETCH, MAX_PREFETCH)

    fun setPrefetch(c: Context, value: Int) {
        prefs(c).edit().putInt(KEY_PREFETCH, value.coerceIn(MIN_PREFETCH, MAX_PREFETCH)).apply()
    }

    /** Следующее значение ручки по кругу — для строки-переключателя. */
    fun nextPrefetch(current: Int): Int = if (current >= MAX_PREFETCH) MIN_PREFETCH else current + 1

    /** Сколько ЗНАКОВ текста приходится на одну ступень «заготовок впрок»
     *  (07.10.2026, просьба Сержа: «запас вперёд считать по знакам, а не по числу
     *  фраз»). Раньше глубина считалась только фразами, а фраза бывает и в 20
     *  знаков, и в 220: в первом случае впереди четверть секунды звука, во втором —
     *  двадцать секунд, и у медленного движка стык на коротких фразах голодал.
     *  Теперь цель — [AHEAD_CHARS_PER_STEP] знаков на ступень (ступень 2 — около
     *  300 знаков), а число фраз ограничено сверху (см. `SpeechPlayer`), чтобы на
     *  книге из коротких предложений очередь не разрослась. Знаки здесь — это
     *  знаки ТЕКСТА фраз: 150 знаков читаются примерно десять секунд. */
    const val AHEAD_CHARS_PER_STEP = 150

    /** То же словами — для строки настроек и подписи диалога. */
    fun prefetchChars(value: Int): Int = value * AHEAD_CHARS_PER_STEP

    /** Положения ручки «отдавать движку по одной фразе» (06.10.2026).
     *
     *  Само (по умолчанию) — по пометке пары «движок + голос»: движку, который
     *  играет звук сам и файлов не отдаёт, отдаём одну фразу, обычному — запас
     *  вперёд ([SpeechPlayer.directAhead]). «Всегда» ставят, чтобы попробовать
     *  одну фразу на движке, который у нас числится обычным; «никогда» —
     *  наоборот, вернуть запас вперёд. */
    const val ONE_AUTO = 0
    const val ONE_ALWAYS = 1
    const val ONE_NEVER = 2

    fun onePhrase(c: Context): Int =
        prefs(c).getInt(KEY_ONE_PHRASE, ONE_AUTO).coerceIn(ONE_AUTO, ONE_NEVER)

    fun setOnePhrase(c: Context, value: Int) {
        prefs(c).edit().putInt(KEY_ONE_PHRASE, value.coerceIn(ONE_AUTO, ONE_NEVER)).apply()
    }

    fun nextOnePhrase(current: Int): Int = if (current >= ONE_NEVER) ONE_AUTO else current + 1

    // Прежний порядок чтения (выключатель `expert_legacy_order`) убран 08.10.2026:
    // новый порядок по единицам речи отстоялся на живом чтении (Серж читал час с
    // лишним, журнал чистый — ни повторов, ни заминок, ни сбоев движка), и
    // договорённость была такой: как только он себя оправдает, прежний путь и
    // строку настроек убрать совсем. Два порядка одновременно и были причиной,
    // по которой жалобы на порядок возвращались. Ключ в настройках оставлен как
    // есть — он больше не читается, но и мешать не может.

    // ——— Принудительный способ чтения (06.10.2026) ———

    /** Ручка «каким способом читать» — против всей нашей автоматики. ОДНА НА
     *  КАЖДЫЙ ДВИЖОК, а не общая на всех (решение Сержа 06.10.2026: «у нас будет
     *  крутилка для всех профилей и крутилка для каждого — может, это
     *  нагромождение, давай что-то одно»). У движка, которого не касались, способ
     *  «сам, по опыту» — то есть прежнее поведение, и ничего настраивать не надо.
     *
     *  Зачем вообще (просьба Сержа 06.10.2026: «дать синтезатору каким способом
     *  читать... и чтобы без всяких проверок, что там установлено в этих
     *  синтезаторах»). Обычно путь выбирает опыт: движок дважды не отдал файл —
     *  читаем его голосом ([SpeechPlayer.netDirect]). Это удобно в жизни и мешает
     *  в опыте: нельзя заставить движок идти файлами, если он уже помечен, и
     *  нельзя заставить читать его голосом, пока он файлы отдаёт.
     *
     *  Четыре положения на каждый движок:
     *  [MODE_AUTO] — как раньше: файлами, а если движок их не даёт, переходим на
     *  его голос по пометкам и опыту;
     *  [MODE_FILE] — только файлами: проб нет, пометки не читаем и не ставим;
     *  [MODE_DIRECT] — только движком: файл не заказываем вовсе;
     *  [MODE_NET_DIRECT] — сетевые голоса читает движок, локальные идут файлами
     *  (сетевой голос сам заявляет, что ему нужна сеть, — это и есть признак).
     *
     *  Живёт в настройках чтения, поэтому переживает перезапуск и обновление
     *  приложения. Меняется на живом чтении не сразу: [SpeechPlayer.applyForceMode]
     *  перечитывает ручку на старте чтения и при применении голоса — иначе смена
     *  пути обрывала бы звук на середине фразы. */
    const val MODE_AUTO = 0
    const val MODE_FILE = 1
    const val MODE_DIRECT = 2
    const val MODE_NET_DIRECT = 3

    /** Префикс ключа настройки: к нему приписывается имя движка. */
    private const val KEY_FORCE_MODE = "expert_force_"

    /** Ключ для движка, который у нас зовётся «системный»: пакета у него нет
     *  (читаем голосом движка по умолчанию). */
    private const val SYSTEM_ENGINE = "системный"

    fun forceMode(c: Context, engine: String?): Int =
        prefs(c).getInt(KEY_FORCE_MODE + engineKey(engine), MODE_AUTO)
            .coerceIn(MODE_AUTO, MODE_NET_DIRECT)

    fun setForceMode(c: Context, engine: String?, value: Int) {
        prefs(c).edit()
            .putInt(KEY_FORCE_MODE + engineKey(engine), value.coerceIn(MODE_AUTO, MODE_NET_DIRECT))
            .apply()
    }

    /** Имя движка в ключе настройки. `null` — движок по умолчанию. */
    fun engineKey(engine: String?): String =
        if (engine.isNullOrBlank()) SYSTEM_ENGINE else engine

    /** Пакет движка синтеза по умолчанию — из системных настроек.
     *
     *  Нужен затем, что свой движок выбран не всегда: пока человек его не выбрал
     *  у нас, читаем системным, и профиль способа чтения должен находиться и для
     *  него (в ключе он звался бы «системный», а строки в настройках — по имени
     *  движка). Публичного способа спросить это без своего экземпляра синтезатора
     *  нет, поэтому читаем настройку Android. */
    fun defaultEngine(c: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(c.contentResolver, "tts_default_synth")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Как называется положение ручки — одним местом на настройки, журнал и
     *  ответ вслух ([ReadingMode]). */
    fun forceModeLabel(v: Int): String = when (v) {
        MODE_FILE -> "только файлом (обычный способ)"
        MODE_DIRECT -> "только движком (альтернативный способ)"
        MODE_NET_DIRECT -> "сетевые — движком, локальные — файлом"
        else -> "сам, по опыту"
    }

    // ——— Есть ли у движка сетевые голоса (06.10.2026) ———

    /** У движка без сетевых голосов положение «сетевые — движком, локальные —
     *  файлом» пустое, и показывать его в папке «Движки» незачем. Заранее мы
     *  этого не знаем: список движков приходит от системы, а «сетевой или
     *  локальный» заявляет сам голос при применении. Поэтому запоминаем, что
     *  видели. */
    private const val KEY_NET_ENGINES = "expert_net_engines"
    private const val KEY_LOCAL_ENGINES = "expert_local_engines"

    /** Запомнить, что у движка есть сетевые голоса — или что их точно НЕТ.
     *
     *  Про «нет» пишем только когда голос у движка ОДИН ([single]): у MultiTTS
     *  сетевые и локальные соседствуют, и по одному локальному голосу приговаривать
     *  движок нельзя. Про «есть» пишем сразу: одного сетевого голоса достаточно. */
    fun noteEngineVoice(c: Context, engine: String?, network: Boolean, single: Boolean) {
        if (engine.isNullOrBlank()) return
        val p = prefs(c)
        val net = p.getStringSet(KEY_NET_ENGINES, emptySet()) ?: emptySet()
        val local = p.getStringSet(KEY_LOCAL_ENGINES, emptySet()) ?: emptySet()
        when {
            network -> p.edit()
                .putStringSet(KEY_NET_ENGINES, net + engine)
                .putStringSet(KEY_LOCAL_ENGINES, local - engine)
                .apply()
            single -> p.edit().putStringSet(KEY_LOCAL_ENGINES, local + engine).apply()
        }
    }

    /** Есть ли у движка сетевые голоса: `true` — видели, `false` — точно нет,
     *  `null` — ещё не знаем (тогда в списке показываем все положения). */
    fun engineHasNetwork(c: Context, engine: String?): Boolean? {
        if (engine.isNullOrBlank()) return null
        val p = prefs(c)
        if ((p.getStringSet(KEY_NET_ENGINES, emptySet()) ?: emptySet()).contains(engine)) return true
        if ((p.getStringSet(KEY_LOCAL_ENGINES, emptySet()) ?: emptySet()).contains(engine)) return false
        return null
    }

    /** Забыть пометки движка: после этого он снова обычный — ни «медленный», ни
     *  «файлов не отдаёт», ни «долго готовит первую фразу». Нужно, когда движок
     *  обновился или пометка легла зря (в 248 такие пометки снимаются и сами, но
     *  вручную — быстрее). */
    fun clearMarks(c: Context) {
        val p = prefs(c)
        val e = p.edit().remove(KEY_SLOW_SYNTH).remove(KEY_DIRECT_SYNTH)
        // Пометки о долгой подготовке первой фразы лежат отдельными ключами (в
        // них число, а не набор), поэтому чистим по началу имени.
        p.all.keys.filter { it.startsWith(FIRST_PREP_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }

    // ——— Сколько движок готовит ПЕРВУЮ фразу (07.10.2026) ———
    //
    // Разбор журнала 01:18: движок MyTTS (com.davnozdu.supertonic.tts.fork)
    // первую фразу после запуска или смены голоса готовил ДВАДЦАТЬ секунд —
    // грузил модель и разбирал текст (у него своя подготовка: ударения, ё, роли).
    // Наши окна терпения рассчитаны на восемь секунд, и после них шла лестница
    // мер: переспрос, перезапуск движка (дважды) и прямая речь. Перезапуск
    // пересоздаёт соединение и, похоже, сбрасывает подготовку — вместо двадцати
    // секунд выходило больше, а первая фраза звучала прямой речью.
    //
    // Поэтому запоминаем по паре «движок + голос», сколько он готовил первую
    // фразу, и окно терпения берём по этому числу (см. [SpeechPlayer.warmWindow]).
    // Число обновляется последним замером: движок починили — окно укоротится.

    private const val FIRST_PREP_PREFIX = "expert_first_prep_"

    /** Верхний предел правдоподобного: больше сорока пяти секунд ждать не станем
     *  (дальше лучше переспросить и перезапустить, чем сидеть в тишине). */
    const val FIRST_PREP_MAX_MS = 45_000L

    private fun firstPrepKey(engine: String?, voice: String?): String =
        FIRST_PREP_PREFIX + "${engine ?: "системный"}|${voice ?: ""}"

    /** Сколько движок с этим голосом готовит первую фразу (мс). 0 — не знаем. */
    fun firstPrepMs(c: Context, engine: String?, voice: String?): Long =
        runCatching { prefs(c).getLong(firstPrepKey(engine, voice), 0L) }.getOrDefault(0L)

    fun setFirstPrepMs(c: Context, engine: String?, voice: String?, ms: Long) {
        runCatching {
            prefs(c).edit()
                .putLong(firstPrepKey(engine, voice), ms.coerceIn(0L, FIRST_PREP_MAX_MS))
                .apply()
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
