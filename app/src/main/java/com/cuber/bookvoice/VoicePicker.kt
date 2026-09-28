package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Набор «Голос чтения» — ОДИН код на читалку и Настройки (msg5610/msg5614/
 *  msg5618). Сергей упёрся в то, что в читалке строки движка/языка/голоса
 *  объявлялись «кнопкой», а в Настройках — обычными строками, а «Прослушать»
 *  значило в двух местах разное. Поэтому порядок, подписи, элементы и списки
 *  живут здесь, а хост (читалка или Настройки) отвечает только за то, КОГДА
 *  выбор попадёт в настройки: читалка пишет при закрытии панели (там галочка
 *  «Запомнить для этой книги», #55/msg2669), Настройки — сразу.
 *
 *  Состав набора: «Читать/Пауза» (msg5632: образцов голоса нет, кнопка
 *  запускает чтение книги), три строки-значения, галочка «Запомнить для текущей
 *  книги». Ползунки (скорость/тон/громкость) — у хостов свои, они и раньше были
 *  одинаковы по подписям; набор ставится между ними одинаково в обоих местах. */
class VoicePicker(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val host: Host,
    /** Чей голос настраивает набор: основной или репликовый (26.09.2026).
     *  Строки, списки и подписи одни и те же — разница только в том, куда
     *  пишется выбор. Так и получается «один интерфейс», как просил Серж. */
    private val target: Target = Target.MAIN,
) {

    enum class Target { MAIN, REPLY }

    /** Ключи настроек: у реплик свои голос, движок и язык. */
    private val keyEngine: String
        get() = if (target == Target.REPLY) MainActivity.KEY_REPLY_ENGINE else MainActivity.KEY_ENGINE
    private val keyVoice: String
        get() = if (target == Target.REPLY) MainActivity.KEY_REPLY_VOICE else MainActivity.KEY_VOICE
    private val keyLang: String
        get() = if (target == Target.REPLY) MainActivity.KEY_REPLY_LANG else MainActivity.KEY_VOICE_LANG
    /** Что хост даёт набору и что получает от него. */
    interface Host {
        /** Плеер для списков. У читалки он всегда готов; в Настройках без книги
         *  поднимается временный и гасится после закрытия списка — поэтому
         *  запрос асинхронный, как прежний withVoiceEngine. */
        fun withPlayer(titleRes: Int, onReady: (SpeechPlayer, Boolean) -> Unit)

        /** Живой плеер, если он уже есть, — только для подписей строк. */
        fun playerOrNull(): SpeechPlayer?

        /** Книга открыта: есть что читать и за чем запоминать. */
        fun hasBook(): Boolean
        fun isPlaying(): Boolean
        fun togglePlay()

        /** Текущий голос: у книги он может быть свой, не из настроек. */
        fun currentVoice(): String?

        /** Галочка «Запомнить для текущей книги». */
        fun rememberChecked(): Boolean
        fun rememberChanged(checked: Boolean)

        /** Выбор принят — хост решает, куда и когда его записать. [lang] —
         *  язык, который был открыт в списке голосов (нужен, чтобы не затереть
         *  выбранный руками язык выбором голоса из запасного языка). */
        fun engineApplied(pkg: String)
        fun voiceApplied(v: Voice, lang: String?)
        fun langApplied(code: String)

        /** Перерисовать строки хоста (Настройки пересобирают раздел). */
        fun redraw()
        fun toast(msg: String)

        /** Открыть окно «Чтение по ролям» — одно на оба входа (26.09.2026).
         *  Строка «По ролям» в наборе только ведёт туда; сама настройка живёт
         *  в этом окне, чтобы не дублироваться. */
        fun openReplyWindow()

        /** Галочка «Чтение по ролям» переключена (29.09.2026, просьба Сержа:
         *  «свайпом зашёл на роли, следующим свайпом галочка — включено или
         *  выключено»). Хост отдаёт режим живому плееру и перечитывает текущее
         *  предложение, чтобы разница была слышна сразу. */
        fun replyToggled(on: Boolean)
    }

    val playRow: Button = actionRow()
    val engineRow: Button = valueRow()
    val langRow: Button = valueRow()
    val voiceRow: Button = valueRow()

    /** Реплики другим голосом (26.09.2026): одна строка-ПЕРЕХОД в своё окно
     *  «Чтение по ролям». Так решено Сержем 26.09.2026: настройка живёт в одном
     *  месте, а в общем списке голоса остаётся короткая строка со значением —
     *  иначе до книжных ползунков приходилось бы слушать шесть строк реплик. */
    val replyRow: Button = valueRow()

    /** Галочка «Чтение по ролям» (29.09.2026, просьба Сержа).
     *
     *  Зачем при живой строке выше. Строка «По ролям» — это ЗНАЧЕНИЕ и вход в
     *  окно настройки; чтобы переключить режим, приходилось заходить в окно и
     *  выбирать там «Режим». Теперь сразу под строкой стоит галочка: свайп со
     *  строки попадает на неё, и одно касание включает или выключает роли.
     *  Состояние диктор называет сам («отмечено» / «не отмечено») — своего
     *  «включено» мы не добавляем, иначе состояние звучало бы дважды.
     *
     *  Места в панели хватает: она прокручивается, а галочка встаёт сразу под
     *  строкой «По ролям», третьей строкой набора. */
    val replyCheck: CheckBox = CheckBox(ctx).apply {
        text = ctx.getString(R.string.voice_reply_check)
        textSize = 17f
        isChecked = prefs.getBoolean(MainActivity.KEY_REPLY_ON, false)
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
    }

    /** Язык, открытый в списке голосов. Это ФИЛЬТР списка, а не настройка:
     *  по умолчанию — язык, выбранный Сергеем руками (msg5622), иначе язык
     *  текущего голоса. */
    private var pickLang: String? = null

    /** Собрать набор в контейнер: кнопка чтения/образца, строка «По ролям» и
     *  три строки-значения. У репликовой цели строки свои, «По ролям» не нужна. */
    fun build(parent: LinearLayout) {
        attach()
        if (target == Target.MAIN) {
            // «Читать» и «По ролям» — одной строкой, двумя кнопками рядом
            // (26.09.2026, просьба Сержа: «каждая кнопка на своей строчке —
            // очень»). Для диктора это по-прежнему две кнопки, просто список
            // короче на строку.
            parent.addView(playRow)
        } else {
            parent.addView(playRow)
        }
        // Движок, язык и голос — тремя строками, как было (26.09.2026: пробовали
        // одной строкой тремя кнопками, Серж вернул строки — они переносятся
        // сами и не режутся на крупном шрифте).
        parent.addView(engineRow)
        parent.addView(langRow)
        parent.addView(voiceRow)
        resetLang()
        refresh()
    }

    /** Две кнопки в одну строку, каждая на половину ширины. */
    fun pairRow(a: View, b: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Строка ролей для НИЗА набора (29.09.2026, просьба Сержа: «строчка
     *  переключения по ролям и рядом в этой же строчке галочка», и поставить её
     *  вниз, а не под «Читать»).
     *
     *  Слева кнопка-значение «По ролям» (она же вход в окно настройки голоса и
     *  движка реплик), справа галочка включения. Диктор обходит их двумя
     *  остановками подряд: сначала значение, следом флажок с состоянием.
     *
     *  Ставит её ХОЗЯИН набора, а не [build]: место в наборе у читалки и у
     *  Настроек одно и то же — сразу под ползунками, перед строкой словаря. */
    fun replyBlock(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(replyRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            replyCheck,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** Развесить слушателей. Отдельно от [build]: окно реплик ставит те же
     *  строки в своём порядке (между ними встаёт переключатель режима). */
    fun attach() {
        engineRow.setOnClickListener { pickEngine() }
        langRow.setOnClickListener { pickLangList() }
        voiceRow.setOnClickListener { pickVoice() }
        replyRow.setOnClickListener { host.openReplyWindow() }
        if (target == Target.MAIN) {
            replyCheck.setOnCheckedChangeListener { _, checked ->
                // Тихо, без своего объявления: состояние назовёт сам флажок
                // («отмечено» / «не отмечено»), как у правил словаря.
                if (replyCheckSync) return@setOnCheckedChangeListener
                prefs.edit().putBoolean(MainActivity.KEY_REPLY_ON, checked).apply()
                refresh()
                host.replyToggled(checked)
            }
        }
    }

    /** Обновляем флажок из настроек: режим могли переключить и в окне «Реплики».
     *  [replyCheckSync] гасит слушателя на время нашей же записи — иначе
     *  переключение из окна выглядело бы новым выбором и дёргало чтение. */
    private var replyCheckSync = false

    fun syncReplyCheck() {
        if (target != Target.MAIN) return
        replyCheckSync = true
        replyCheck.isChecked = prefs.getBoolean(MainActivity.KEY_REPLY_ON, false)
        replyCheckSync = false
    }

    /** Галочка «Запомнить для текущей книги» с подсказкой под ней. Книги нет —
     *  галочке не за что держаться, и она не появляется вовсе. */
    fun addRemember(parent: LinearLayout) {
        if (!host.hasBook()) return
        val cb = CheckBox(ctx).apply {
            text = ctx.getString(R.string.voice_remember_book)
            contentDescription = ctx.getString(R.string.voice_remember_book_cd)
            textSize = 16f
            isChecked = host.rememberChecked()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        row.addView(cb)
        parent.addView(row)
        // #55 (msg2669): статичная подсказка под галочкой — что будет с выбранным
        // голосом при закрытии панели. Одна фраза на обе стороны, чтобы незрячему
        // не приходилось угадывать по названию галочки (msg2659/msg2671).
        parent.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.voice_remember_hint)
            textSize = 14f
            setTextColor(Palette.DIM)
            setPadding(dp(4f), 0, dp(4f), dp(8f))
        })
        cb.setOnCheckedChangeListener { _, checked -> host.rememberChanged(checked) }
    }

    /** Перерисовать подписи строк и кнопки чтения из текущего состояния. */
    fun refresh() {
        // Строка словаря стоит внизу, и её состояние живёт в своём окне: без
        // этого она говорила бы «выключен» и после включения.
        dictRowView?.text = withHintText(ctx.getString(R.string.dict_row), dictValue())
        val p = host.playerOrNull()
        val voices = p?.voices ?: emptyList()
        // Движок: у реплик он свой и берётся из настроек реплик — живого плеера
        // у чужого движка может и не быть.
        val pkg = if (target == Target.REPLY) {
            prefs.getString(keyEngine, null)
        } else {
            p?.enginePackage ?: p?.defaultEngine ?: prefs.getString(keyEngine, null)
        }
        val engineName = p?.engines?.firstOrNull { it.first == pkg }?.second
            ?: pkg?.let { appLabel(it) }
            ?: ctx.getString(R.string.voice_engine_system)

        // Голос не выбран (только что сменили движок) — открываем русский, а если
        // движок его не знает, первый по алфавиту: список голосов не должен
        // показывать все языки сразу. Заодно тут оседает язык, выбранный руками,
        // если движок его знает (msg5622) — строка «Язык» не должна показывать
        // язык, голосов которого у движка нет.
        if (voices.isNotEmpty()) {
            pickLang = VoicePick.pickLangFor(voices, pickLang, host.currentVoice())
        }
        val langText = if (voices.isEmpty()) {
            // Плеера ещё нет (Настройки без книги) — показываем просто язык.
            pickLang?.let { VoicePick.langLabel(it) } ?: ctx.getString(R.string.voice_lang_unset)
        } else {
            pickLang?.let { VoicePick.langTitle(ctx, it, VoicePick.voicesOf(voices, it).size) }
                ?: ctx.getString(R.string.no_voices)
        }
        val voiceText = voiceText(voices, pkg, host.currentVoice())

        engineRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_engine_row), engineName)
        langRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_lang_row), langText)
        voiceRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_voice_row), voiceText)
        if (target == Target.MAIN) {
            // На экране подпись короткая — строка делится с «Читать»; всё
            // состояние уходит в описание для диктора, и он читает его целиком.
            replyRow.text = ctx.getString(R.string.voice_reply_row)
            replyRow.contentDescription = ctx.getString(
                R.string.voice_row_value,
                ctx.getString(R.string.voice_reply_row),
                replyValueText(),
            )
            // Галочка переключается и из окна «Реплики» — состояние берём из
            // настроек, а не из нажатия.
            syncReplyCheck()
        }
        refreshPlayLabel()
    }

    /** Значение строки-перехода: режим и, если он включён, каким голосом читают
     *  реплики. Строка — это запись, а не догадка: показываем ровно то, что
     *  выбрано, даже если движка сейчас рядом нет. */
    private fun replyValueText(): String {
        if (!prefs.getBoolean(MainActivity.KEY_REPLY_ON, false)) {
            return ctx.getString(R.string.voice_reply_off)
        }
        val voice = prefs.getString(MainActivity.KEY_REPLY_VOICE, null)
        return if (voice == null) {
            ctx.getString(R.string.voice_reply_other)
        } else {
            ctx.getString(R.string.voice_reply_other) + ", " + voice
        }
    }

    /** Что писать в строке «Голос» (msg5726). Показываем только то, что этот
     *  движок и правда прочтёт: выбранный голос, если он его знает, иначе голос,
     *  запомненный за этим движком (он и зазвучит), иначе честное «читает сам
     *  движок». Имени чужого голоса в строке нет: движок его не знает, и подпись
     *  врала бы — Сергей видел ровно это (msg5722: движок VIKTORIUS, а в строке
     *  голос прошлого движка, microsoft_ru-RU-DariyaNeural). */
    private fun voiceText(voices: List<Voice>, pkg: String?, chosen: String?): String {
        // Реплики: голос показываем из СВОИХ настроек. За движком его не
        // запоминаем — у реплик одна запись, и она видна как есть.
        if (target == Target.REPLY) {
            if (voices.isEmpty()) return chosen ?: ctx.getString(R.string.voice_engine_reads)
            val v = voices.firstOrNull { it.name == chosen }
            return v?.let { VoicePick.voiceLabel(it) } ?: ctx.getString(R.string.voice_engine_reads)
        }
        // Список голосов не приехал — «ещё не знаю», а не «голоса нет» (msg3550):
        // судить не о чем, показываем записанное как есть.
        if (voices.isEmpty()) {
            // Движка не знаем вовсе (в настройках он ещё не выбран) — показываем
            // выбор как есть: приписать его чужому движку нечем.
            if (pkg == null) return chosen ?: ctx.getString(R.string.voice_engine_reads)
            // 0.4.86 (msg7027): движок знаем, а список его голосов ещё не приехал.
            // Раньше сюда попадал общий выбор — голос ПРЕЖНЕГО движка, и Сергей
            // видел ровно это: сменил движок, а строка «Голос» осталась прежней.
            // Показываем голос, запомненный за ЭТИМ движком (он и зазвучит), а нет
            // его — честное «читает сам движок».
            return MainActivity.voiceForEngine(prefs, pkg)
                ?: ctx.getString(R.string.voice_engine_reads)
        }
        val v = voices.firstOrNull { it.name == chosen }
            ?: voices.firstOrNull { it.name == MainActivity.voiceForEngine(prefs, pkg) }
            ?: return ctx.getString(R.string.voice_engine_reads)
        return if (VoicePick.codeOf(v) == pickLang) {
            VoicePick.voiceLabel(v)
        } else {
            // Голос выбран из другого языка, чем открыт список: показываем его
            // как есть и называем язык — выбор не пропадает молча.
            ctx.getString(
                R.string.voice_lang_other,
                VoicePick.voiceLabel(v),
                VoicePick.langLabel(VoicePick.codeOf(v)),
            )
        }
    }

    /** Подпись первой строки отдельно от остальных: читалке она нужна на каждый
     *  старт/паузу, а пересобирать из-за неё строки-значения незачем.
     *
     *  Она же и говорит, что строка сделает (msg5640): книга открыта —
     *  «Читать/Пауза», книги нет (Настройки) — «Прослушать», образец фразы.
     *  Одна строка в одном месте, а разное поведение названо вслух, а не
     *  угадывается. */
    fun refreshPlayLabel() {
        playRow.text = when {
            target == Target.REPLY -> ctx.getString(R.string.reply_read)
            host.hasBook() ->
                if (host.isPlaying()) ctx.getString(R.string.pause)
                else ctx.getString(R.string.voice_read_start)
            else -> ctx.getString(R.string.voice_preview)
        }
    }

    /** С какого языка открывать списки: выбранный руками (msg5622), а если
     *  движка рядом нет — просто из настроек, как есть. Пересчитывать его по
     *  пустому списку голосов нельзя: в Настройках без книги движка нет вовсе,
     *  и записанный язык подменялся бы запасным на ровном месте. */
    fun resetLang() {
        val p = host.playerOrNull()
        val want = prefs.getString(keyLang, null)
        pickLang = if (p == null || p.voices.isEmpty()) {
            want
        } else {
            VoicePick.pickLangFor(p.voices, want, host.currentVoice())
        }
    }

    // ---------------- Списки ----------------

    private fun pickEngine() {
        host.withPlayer(R.string.voice_engine_row) { p, temp ->
            val engines = p.engines
            if (engines.isEmpty()) {
                host.toast(ctx.getString(R.string.no_engines))
                if (temp) p.shutdown()
                return@withPlayer
            }
            val defaultEngine = p.defaultEngine
            val cur = if (target == Target.REPLY) {
                prefs.getString(keyEngine, null) ?: p.enginePackage ?: defaultEngine
            } else {
                p.enginePackage ?: defaultEngine
            }
            val labels = engines.map { (pkg, label) ->
                if (pkg == defaultEngine) "$label (системный)" else label
            }.toTypedArray()
            val idx = engines.indexOfFirst { it.first == cur }.coerceAtLeast(0)
            // 0.4.82: переключились ли в этом диалоге. Нужно, чтобы НЕ гасить
            // временный плеер, пока переключение идёт (см. ниже).
            var switched = false
            val dlg = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.voice_engine_row)
                .setSingleChoiceItems(labels, idx) { d, which ->
                    d.dismiss()
                    val pkg = engines[which].first
                    if (pkg == p.enginePackage) return@setSingleChoiceItems
                    switched = true
                    host.toast(ctx.getString(R.string.voice_engine_switch))
                    // msg5726: уходящий движок забирает свой голос с собой.
                    // Без этой записи выбор, сделанный до смены движка, потерялся
                    // бы: у нового движка свой голос, и он переписал бы общий
                    // KEY_VOICE — вернувшись, человек искал бы голос заново.
                    val keep = host.currentVoice()
                    if (target == Target.MAIN && keep != null && p.voices.any { it.name == keep }) {
                        MainActivity.rememberVoiceForEngine(prefs, p.enginePackage, keep)
                    }
                    // Куда писать движок, решает хост: в читалке — при закрытии
                    // панели (#55/msg2669: выбор «до галочки» не должен
                    // заражать общие настройки), в Настройках — сразу.
                    host.engineApplied(pkg)
                    p.setEngine(pkg) { ok ->
                        if (!ok) {
                            // 0.4.76: отказ движка виден в журнале, а не только
                            // тостом — по нему и разбирается «переключается на
                            // системный».
                            Diag.log(
                                ctx, "tts",
                                "движок ${pkg ?: "системный"} не запустился — возвращаю системный"
                            )
                            p.setEngine(null) { _ -> afterEngineChange(p, temp) }
                            host.toast("Движок не запустился, вернул системный")
                        } else {
                            // msg5604: движок сменили на ходу — перечитываем
                            // текущее предложение новым движком. Именно ЗДЕСЬ, а
                            // не в engineApplied: там движок ещё старый и готов,
                            // и перечитка ушла бы прежним голосом. Без книги
                            // restartAfterSwitch молчит. Реплик это не касается:
                            // смена движка РЕПЛИК книгу не перечитывает.
                            if (target == Target.MAIN) ReaderEngine.restartAfterSwitch()
                            afterEngineChange(p, temp)
                        }
                    }
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            // 0.4.82: временный плеер гасим только если переключения в этом
            // диалоге НЕ было. Найдено по журналу Сергея 24.09.2026: выбор пункта
            // списка закрывает диалог (`d.dismiss()`), а слушатель закрытия
            // срабатывает НЕ сразу — уже после того, как мы запустили новый
            // движок. `shutdown` гасил его прямо во время запуска: ответ движка
            // приходил с успехом, но `tts` был уже null — и мы объявляли движок
            // неисправным («org.nobody.multitts не запустился — возвращаю
            // системный»). Отсюда и «не переключается или переключается на
            // системный», и то, что у одного движка получалось, а у другого нет:
            // это гонка. После успешного переключения плеер гасит
            // [afterEngineChange], когда список голосов уже прочитан.
            if (temp) dlg.setOnDismissListener { if (!switched) p.shutdown() }
        }
    }

    /** Движок сменили: язык, выбранный руками, смену переживает (msg5622).
     *  Решаем это, когда движок отдал список голосов — список приезжает позже
     *  init (msg3550), а по пустому списку «у движка нет языка» было бы враньём.
     *  [temp] — плеер временный (без открытой книги): гасим его сами, когда
     *  работа сделана (см. [pickEngine]). */
    private fun afterEngineChange(p: SpeechPlayer, temp: Boolean) {
        ReaderEngine.whenVoicesReady(on = p) {
            val want = pickLang ?: prefs.getString(keyLang, null)
            pickLang = VoicePick.pickLangFor(p.voices, want, host.currentVoice())
            if (want != null && !VoicePick.hasLang(p.voices, want)) {
                host.toast(
                    ctx.getString(
                        R.string.voice_lang_lost,
                        VoicePick.langLabel(want),
                        VoicePick.langLabel(pickLang),
                    )
                )
            }
            applyEngineVoice(p)
            refresh()
            host.redraw()
            // 0.4.86 (msg7027): чужой движок отдаёт список голосов позже, чем мы
            // его ждали (whenVoicesReady сдаётся через три секунды). Тогда строка
            // «Голос» показывает честное «читает сам движок» — и осталась бы с ним
            // до пересборки раздела. Досматриваем живого плеера ещё раз: и выбор
            // доведём, и подпись. Временный (Настройки без книги) не трогаем —
            // его мы гасим сразу, и опрашивать нечего.
            if (!temp && p.voices.isEmpty()) {
                main.postDelayed({
                    applyEngineVoice(p)
                    refresh()
                    host.redraw()
                }, 2000)
            }
            if (temp) p.shutdown()
        }
    }

    /** Какой голос зазвучит у нового движка (msg5726). Годится то, что движок
     *  знает: сперва текущий выбор (он и был в силе), иначе голос, запомненный
     *  за ЭТИМ движком, — он и вернётся, когда движок вернётся. Если не годится
     *  ничего, читает сам движок; об этом говорим вслух, но только когда при
     *  себе был чужой голос — молчаливая подмена голоса слышна и без слов, а
     *  вот её причина незрячему не видна. */
    private fun applyEngineVoice(p: SpeechPlayer) {
        val cur = host.currentVoice()
        // Для реплик «голос, запомненный за движком» не годится: там одна
        // запись на всё, и подставлять чужой выбор книги нельзя.
        val saved = if (target == Target.REPLY) null else MainActivity.voiceForEngine(prefs, p.enginePackage)
        val v = p.voices.firstOrNull { it.name == cur }
            ?: p.voices.firstOrNull { it.name == saved }
        if (v != null) {
            if (v.name != cur) {
                p.selectVoice(v.name)
                host.voiceApplied(v, pickLang)
            }
            return
        }
        if (cur != null) host.toast(ctx.getString(R.string.voice_engine_reads_toast, cur))
    }

    private fun pickLangList() {
        host.withPlayer(R.string.voice_pick_lang) { p, temp ->
            val voices = p.voices
            if (voices.isEmpty()) {
                host.toast(ctx.getString(R.string.no_voices))
                if (temp) p.shutdown()
                return@withPlayer
            }
            val langs = VoicePick.langs(voices, pickLang)
            val labels = langs.map { VoicePick.langTitle(ctx, it.code, it.voices.size) }.toTypedArray()
            val idx = langs.indexOfFirst { it.code == pickLang }.coerceAtLeast(0)
            val dlg = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.voice_pick_lang)
                .setSingleChoiceItems(labels, idx) { d, which ->
                    d.dismiss()
                    pickLang = langs[which].code
                    host.langApplied(langs[which].code)
                    refresh()
                    host.redraw()
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            if (temp) dlg.setOnDismissListener { p.shutdown() }
        }
    }

    private fun pickVoice() {
        host.withPlayer(R.string.voice_pick_voice) { p, temp ->
            val voices = p.voices
            if (voices.isEmpty()) {
                host.toast(ctx.getString(R.string.no_voices))
                if (temp) p.shutdown()
                return@withPlayer
            }
            // Список — только выбранного языка: чужие языки не подмешиваются.
            // Язык сверяем с голосами ЭТОГО плеера: в Настройках без книги он
            // поднимается только сейчас, и записанный язык мог движку не достаться.
            pickLang = VoicePick.pickLangFor(voices, pickLang, host.currentVoice())
            val list = VoicePick.voicesOf(voices, pickLang)
            if (list.isEmpty()) {
                host.toast(ctx.getString(R.string.no_voices))
                if (temp) p.shutdown()
                return@withPlayer
            }
            val labels = list.map { VoicePick.voiceLabel(it) }.toTypedArray()
            val idx = list.indexOfFirst { it.name == host.currentVoice() }
            val dlg = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.voice_pick_voice)
                .setSingleChoiceItems(labels, idx) { _, which ->
                    // Список остаётся открытым: голос подбирается на слух, и
                    // закрывать окно после каждого касания — значит заново
                    // открывать строку «Голос» и листать до того же места.
                    // Закрывает список сам читатель, кнопкой «Закрыть».
                    val v = list[which]
                    p.selectVoice(v.name)
                    // msg5726: голос помним за движком, которому он выбран, —
                    // вернёшься на этот движок, и голос вернётся сам. Репликам
                    // эта память не нужна: у них своя запись голоса.
                    if (target == Target.MAIN) {
                        MainActivity.rememberVoiceForEngine(prefs, p.enginePackage, v.name)
                    }
                    host.voiceApplied(v, pickLang)
                    refresh()
                    host.redraw()
                    // Книги нет (Настройки) — сразу слушаем голос образцом фразы,
                    // не выходя из списка. С открытой книгой перечитку текущего
                    // предложения делает сам voiceApplied (msg5604), образец там
                    // не нужен.
                    if (!host.hasBook()) speakSample(p)
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            if (temp) dlg.setOnDismissListener { p.shutdown() }
        }
    }

    // ---------------- Строки ----------------

    /** Строка-значение: объявлена обычным текстом (TalkBack не говорит «кнопка»),
     *  как все выбиралки Настроек — сортировка, папка, таймер. */
    private fun valueRow(): Button = Button(ctx).apply {
        textSize = 16f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(12f), 0, dp(12f), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2f)
            bottomMargin = dp(2f)
        }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.TextView"
            }
        }
    }

    /** Строка словаря — держим её у себя: состояние меняется в отдельном окне, и
     *  после возврата строку надо переписать ([refresh]), иначе она продолжает
     *  говорить «выключен», когда словарь уже включён (жалоба Сержа 27.09.2026). */
    private var dictRowView: Button? = null

    /** Строка «Словарь произношения» — ОДНА И ТА ЖЕ и в Настройках, и в панели
     *  голоса из книги (просьба Сержа 27.09.2026: «мы же договорились, что
     *  параметры голоса одинаковые и из настроек, и из кнопки голоса»). Стоит
     *  внизу, после ползунков: и там, и тут её ставит хост, потому что ползунки
     *  рисует он. */
    fun dictRow(): Button = valueRow().apply {
        text = withHintText(ctx.getString(R.string.dict_row), dictValue())
        setOnClickListener {
            val i = android.content.Intent(ctx, DictActivity::class.java)
            if (ctx !is android.app.Activity) i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
        dictRowView = this
    }

    /** Состояние словаря словами: выключен или сколько в нём правил. */
    private fun dictValue(): String =
        if (!Dict.enabled(ctx)) ctx.getString(R.string.dict_row_off)
        else ctx.getString(R.string.dict_row_on, Dict.load(ctx).size)

    /** Название строки и приглушённое пояснение второй строкой — как у строк
     *  подразделов Настроек. */
    private fun withHintText(title: String, hint: String): CharSequence =
        android.text.SpannableStringBuilder().apply {
            append(title)
            append("\n")
            val start = length
            append(hint)
            setSpan(
                android.text.style.RelativeSizeSpan(0.76f),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.ForegroundColorSpan(Palette.DIM),
                start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

    /** Первая строка набора — действие, поэтому настоящая кнопка: слово
     *  «кнопка» здесь уместно и отличает её от строк-значений.
     *
     *  Книга открыта — читать/пауза; книги нет (Настройки) — прослушать образец
     *  фразы (msg5640). Строка одна и та же и стоит на одном и том же месте,
     *  меняется только её работа и подпись. */
    private fun actionRow(): Button = Button(ctx).apply {
        textSize = 16f
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(12f), 0, dp(12f), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(2f)
            bottomMargin = dp(6f)
        }
        setOnClickListener {
            if (target == Target.REPLY) {
                // В окне реплик эта строка — проба репликовым голосом, и читает
                // её хост своим экземпляром, чтобы не перебить чтение книги.
                host.togglePlay()
            } else if (host.hasBook()) {
                host.togglePlay()
            } else {
                playSample()
            }
        }
    }

    // ---------------- Образец фразы (книги нет) ----------------

    /** Плеер образца. Держим свой, а не просим у хоста: в Настройках хост
     *  поднимает временный и гасит его при закрытии списка — образец же должен
     *  звучать по нажатию и не поднимать движок заново на каждый раз. */
    private var samplePlayer: SpeechPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    private fun playSample() {
        // Два голоса разом звучать не должны: читалка точно молчит (книги нет),
        // но движок мог остаться в чужом окне.
        ReaderEngine.pausePlayback()
        val p = samplePlayer ?: SpeechPlayer(ctx.applicationContext).also { samplePlayer = it }
        if (p.isReady) {
            speakSample(p)
            return
        }
        p.setEngine(prefs.getString(MainActivity.KEY_ENGINE, null)) { ok ->
            main.post {
                if (ok) speakSample(p)
                else host.toast(ctx.getString(R.string.voice_preview_failed))
            }
        }
    }

    private fun speakSample(p: SpeechPlayer) {
        p.stop()
        p.speed = prefs.getFloat(MainActivity.KEY_SPEED, 1f)
        p.pitch = prefs.getFloat(MainActivity.KEY_PITCH, 1f)
        p.volume = prefs.getFloat(MainActivity.KEY_VOLUME, 1f)
        prefs.getString(MainActivity.KEY_VOICE, null)?.let { p.selectVoice(it) }
        p.speak(ctx.getString(R.string.voice_preview_sample))
    }

    /** Погасить плеер образца (уход из Настроек). */
    fun shutdownSample() {
        samplePlayer?.shutdown()
        samplePlayer = null
    }

    /** Подпись приложения-движка по пакету: движок ради подписи поднимать не надо. */
    private fun appLabel(pkg: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: pkg

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
