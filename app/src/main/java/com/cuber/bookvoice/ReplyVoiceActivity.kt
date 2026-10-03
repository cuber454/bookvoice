package com.cuber.bookvoice

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Прямая речь» (26.09.2026; до 30.09.2026 — «Чтение по ролям») —
 * настройка реплик.
 *
 *  Зачем отдельным окном. Репликам нужен и голос, и своя скорость, и свой тон,
 *  и своя громкость: в общем списке «Голос» это ещё шесть строк, и до книжных
 *  ползунков приходилось бы слушать их все. В разделе осталась одна строка
 *  «Прямая речь» со значением (сразу под кнопкой чтения), а настройка — здесь.
 *
 *  Интерфейс — ТОТ ЖЕ, что у основного голоса (просьба Сержа 26.09.2026):
 *  строки движка, языка и голоса рисует тот же набор [VoicePicker] с целью
 *  [VoicePicker.Target.REPLY], списки и подписи буквально одни и те же. Сверху
 *  стоит кнопка «Читать» — проба голосом прямой речи, чтобы услышать выбор, не
 *  трогая чтение книги. Ниже — переключатель режима и ползунки реплик.
 *
 *  Окно ОДНО на оба входа: его открывает и Настройки, и панель «Голос» из книги.
 *  «Назад» возвращает туда, откуда пришли: в Настройки или в книгу.
 *
 *  Настройка опытная: второй синтезатор может ошибаться голосом, задерживаться
 *  или не подняться вовсе — тогда реплики читает основной голос, чтение не
 *  встаёт. Выключено по умолчанию: включает только человек.
 */
class ReplyVoiceActivity : RowsActivity() {

    companion object {
        /** Имя файла настроек чтения — то же, что у читалки и у строк настроек
         *  (RowsActivity держит его у себя приватно). */
        private const val PREFS_READER = "reader"

        /** Части окна (30.09.2026): знаки реплик и список начал фраз. */
        private const val SECTION_HOW = "how"
        private const val SECTION_HEADS = "heads"
    }

    private lateinit var binding: ActivitySettingsBinding

    override val contentRoot: ViewGroup get() = binding.content

    /** Пробный синтезатор: свой экземпляр на движке реплик. Нужен и для списков
     *  голосов чужого движка, и для пробы по кнопке «Читать». Книгу он не
     *  трогает — чтение продолжается своим плеером. */
    private var probe: SpeechPlayer? = null

    private var picker: VoicePicker? = null

    override fun buildSection(intent: Intent?) {
        setTitle(getString(R.string.reply_window_title))
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.tvTitle.text = getString(R.string.reply_window_title)
        binding.btnBack.setOnClickListener { rootBack() }
        // Меню «⋮» этому окну не нужно: всё — строками ниже.
        binding.btnMore.visibility = View.GONE
        buildRows()
    }

    override fun resumeSection(arrival: Boolean) {
        // Возврат из списков: состояние могло смениться, строки пересобираем.
        buildRows()
        if (arrival) binding.tvTitle.announceForAccessibility(binding.tvTitle.text)
    }

    /** «Назад» внутри части возвращает на шаг назад, а не закрывает окно: из
     *  списка начал фраз — в знаки реплик, из знаков — в список ролей. */
    override fun onSectionBackKey(): Boolean {
        if (section != null) {
            section = if (section == SECTION_HEADS) SECTION_HOW else null
            buildRows()
            binding.tvTitle.announceForAccessibility(binding.tvTitle.text)
            return true
        }
        return false
    }

    override fun disposeSection() {
        probe?.shutdown()
        probe = null
    }

    private fun prefs() = getSharedPreferences(PREFS_READER, MODE_PRIVATE)

    private fun buildRows() {
        contentRoot.removeAllViews()
        when (section) {
            SECTION_HOW -> {
                binding.tvTitle.text = getString(R.string.reply_marks_title)
                buildHow()
            }
            SECTION_HEADS -> {
                binding.tvTitle.text = getString(R.string.reply_heads_title)
                buildHeads()
            }
            else -> {
                binding.tvTitle.text = getString(R.string.reply_window_title)
                buildMain()
            }
        }
    }

    /** Открыть часть окна: заголовок окна — имя части, диктор его объявляет. */
    private fun openSection(id: String) {
        section = id
        buildRows()
        binding.tvTitle.announceForAccessibility(binding.tvTitle.text)
    }

    private fun buildMain() {
        // Ползунки реплик живут в своём контейнере: стоят они внизу набора, но
        // собраны отдельно — так их видно и править можно, не пересобирая окно
        // (пересборка убила бы View под фокусом диктора, см. RowsActivity.updateRow).
        //
        // 29.09.2026: показываем их ВСЕГДА, а не только при включённых ролях.
        // Жалоба Сержа: «куда-то пропали ползунки из настройки для реплик, там где
        // голос настраивается для реплик, ползунки громкости и тона». Раньше они
        // появлялись лишь после включения режима (в настройках у него роли были
        // выключены), и настройка выглядела пропавшей. Искать то, чего в окне нет
        // ровно потому, что режим выключен, — хуже, чем видеть её всегда: значения
        // просто записываются и зазвучат, как только роли включат.
        val sliders = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 29.09.2026, просьба Сержа: «захожу в чтение по ролям, потом ещё в чтение
        // по ролям, в этом окошке нужно ещё зайти, чтобы включить реплики».
        // Переключатель — ПЕРВОЙ строкой и флажком: одно касание включает, второе
        // выключает, без окна со списком из двух пунктов (раньше на это уходило
        // два касания в отдельном окне поверх окна). Состояние называет сам флажок
        // («отмечено» / «не отмечено»), своего «включено» мы не добавляем.
        addCheck(
            getString(R.string.voice_reply_check),
            null,
            MainActivity.KEY_REPLY_ON,
            false,
        ) { checked ->
            Diag.log(
                this, "tts",
                "прямая речь: ${if (checked) "включена" else "выключена"} галочкой в окне «Прямая речь»",
            )
            applyReplyToPlayer()
        }

        // Подсказка про второй движок — сразу под переключателем: её читают до
        // того, как включат.
        addHint(getString(R.string.voice_reply_hint))

        // Как приложение понимает, что фраза — прямая речь (30.09.2026).
        // Отдельной частью окна, а не строками здесь: настроек разметки три, и на
        // одном экране с голосом и ползунками получилась бы свалка.
        addRow(
            getString(R.string.reply_marks_row),
            marksStateLine(),
            strong = true,
            tag = "marks",
        ) { openSection(SECTION_HOW) }

        // 26.09.2026 — 30.09.2026: здесь висело предупреждение «в альтернативном
        // способе озвучки реплики другим голосом не работают». Оно устарело:
        // реплики в этом способе читает второй движок (26.09), а с 30.09 работает
        // и случай «оба голоса в одном движке» — основной экземпляр берётся в
        // долг под реплику (SpeechPlayer.queueDirect, как в файловом способе).
        // Предупреждать больше не о чем, поэтому строки нет.

        // Тот же набор строк, что у основного голоса, но с репликовой целью.
        val pick = VoicePicker(this, prefs(), replyHost, VoicePicker.Target.REPLY)
        picker = pick
        pick.attach()

        // «Читать» — образец репликовым голосом: слушаем выбор, не трогая книгу.
        contentRoot.addView(pick.playRow)

        // Движок, язык и голос реплик — тремя строками, как в основном наборе.
        contentRoot.addView(pick.engineRow)
        contentRoot.addView(pick.langRow)
        contentRoot.addView(pick.voiceRow)
        pick.resetLang()
        pick.refresh()

        // Ползунки реплик — ВНИЗУ набора, как в Настройках → «Голос». Просьба Сержа
        // 29.09.2026: «поменяй, чтобы ползунки были внизу, как и в основном окне
        // настройки синтезатора». Порядок оттуда же: скорость, тон, громкость.
        contentRoot.addView(sliders)
        addRateSlider(
            MainActivity.KEY_REPLY_SPEED,
            R.string.reply_speed_value,
            getString(R.string.reply_speed_cd),
            RateSteps.SPEED,
            sliders,
        ) { v -> applyToLive { it.replySpeed = v } }
        addRateSlider(
            MainActivity.KEY_REPLY_PITCH,
            R.string.reply_tone_value,
            getString(R.string.reply_tone_cd),
            RateSteps.PITCH,
            sliders,
        ) { v -> applyToLive { it.replyPitch = v } }
        addVolumeSlider(sliders)

        ensureProbe()
    }

    /** Отдать настройку живому плееру книги, если она открыта. */
    private fun applyToLive(block: (SpeechPlayer) -> Unit) {
        MainActivity.active?.player?.let(block)
    }

    // ---------------- Как узнавать реплики (30.09.2026) ----------------

    /** Открытая часть окна: null — сам список ролей, [SECTION_HOW] — знаки
     *  реплик, [SECTION_HEADS] — чем начинаются фразы в этой главе. */
    private var section: String? = null

    /** Имя открытой книги: у неё может быть свой набор знаков. */
    private fun bookName(): String? = ReaderEngine.currentName

    /** Строка состояния для входа: чем реплики узнаются сейчас и где этот набор. */
    private fun marksStateLine(): String {
        val m = ReplyMarks.of(this, bookName())
        val where = getString(
            if (m.fromBook) R.string.reply_marks_for_book else R.string.reply_marks_common
        )
        return getString(R.string.reply_marks_state, m.words(), where)
    }

    /** Фразы открытой главы: по ним считаем реплики и начала фраз. */
    private fun chapterSentences(): List<Sentence>? =
        ReaderEngine.bookOrNull()?.chapters?.getOrNull(ReaderEngine.chapterIdx)?.sentences

    /** Часть «Как узнавать реплики»: галочка про номер, свои знаки, начала фраз,
     *  счёт реплик по главе и «запомнить для этой книги». */
    private fun buildHow() {
        val book = bookName()
        // Разбор обязан смотреть теми же знаками, что видит владелец в этом окне.
        ReplyMarks.apply(this, book)
        val marks = ReplyMarks.of(this, book)

        localCheck(
            getString(R.string.reply_marks_number),
            null,
            marks.number,
            "num",
        ) { on ->
            ReplyMarks.save(this, book, marks.list, on, marks.known)
            Diag.log(this, "roles", "знаки прямой речи: после номера ${if (on) "да" else "нет"}")
            buildRows()
            focusTagRetry("num")
        }

        // Свои знаки — по галочке на каждый: включён — действует, выключен — стоит
        // в списке, но не участвует. Добавляются касанием в «Чем начинаются фразы».
        // Долгое нажатие (и пункт «Действия» диктора) — изменить или удалить знак.
        for (sign in marks.known) {
            val active = marks.list.any { it.equals(sign, ignoreCase = true) }
            val box = localCheck(
                Roles.markName(sign),
                null,
                active,
                "sign:$sign",
            ) { on ->
                val list = if (on) marks.list + sign
                else marks.list.filterNot { it.equals(sign, ignoreCase = true) }
                ReplyMarks.save(this, book, list, marks.number, marks.known)
                buildRows()
                focusTagRetry("sign:$sign")
            }
            box.setOnLongClickListener {
                Vibra.confirm(this)
                showSignActions(sign)
                true
            }
            A11y.replaceLongPress(box, getString(R.string.reply_sign_actions)) {
                showSignActions(sign)
            }
        }

        val stats = chapterSentences()?.let { Roles.stats(it) }
        if (stats != null) {
            addRow(
                getString(R.string.reply_count_row, stats.replies, stats.sentences),
                null,
                tag = "count",
            )
        }

        addRow(
            getString(R.string.reply_heads_row),
            null,
            strong = true,
            tag = "heads",
        ) { openSection(SECTION_HEADS) }

        if (book.isNullOrBlank()) {
            addHint(getString(R.string.reply_marks_nobook))
        } else {
            localCheck(
                getString(R.string.reply_marks_remember),
                null,
                ReplyMarks.remembered(this, book),
                "remember",
            ) { on ->
                ReplyMarks.setRemember(this, book, on)
                buildRows()
                focusTagRetry("remember")
            }
        }
    }

    /** Часть «Чем начинаются фразы»: знаки этой главы и счёт фраз. Касание ставит
     *  знак в набор реплик, второе касание снимает. */
    private fun buildHeads() {
        val book = bookName()
        ReplyMarks.apply(this, book)
        val heads = chapterSentences()?.let { Roles.heads(it) } ?: emptyList()
        if (heads.isEmpty()) {
            addHint(getString(R.string.reply_heads_empty))
            return
        }
        addHint(getString(R.string.reply_heads_hint))
        val cur = ReplyMarks.of(this, book)
        for (h in heads) {
            val inSet = cur.list.any { it.equals(h.mark, ignoreCase = true) }
            addRow(
                getString(R.string.reply_head_row, Roles.markName(h.mark), h.count),
                getString(if (inSet) R.string.reply_head_in else R.string.reply_head_out),
                strong = inSet,
                tag = "head${h.mark}",
            ) { toggleMark(h.mark, cur) }
        }
    }

    /** Поставить или снять знак из набора. Набор один — общий или книжный, — и
     *  правка идёт в тот, который сейчас действует. Снятый знак остаётся в списке
     *  известных (галочка выключена), чтобы его можно было вернуть. */
    private fun toggleMark(mark: String, cur: ReplyMarks.Marks) {
        val have = cur.list.any { it.equals(mark, ignoreCase = true) }
        val list = if (have) cur.list.filterNot { it.equals(mark, ignoreCase = true) }
        else cur.list + mark
        val known = if (have) cur.known else cur.known + mark
        ReplyMarks.save(this, bookName(), list, cur.number, known)
        toastText(
            getString(
                if (have) R.string.reply_head_removed else R.string.reply_head_added,
                Roles.markName(mark),
            )
        )
        buildRows()
        focusTagRetry("head$mark")
    }

    /** Долгое нажатие на знак: изменить или удалить его. */
    private fun showSignActions(sign: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(Roles.markName(sign))
            .setItems(
                arrayOf(
                    getString(R.string.reply_sign_edit),
                    getString(R.string.reply_sign_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> editSign(sign)
                    1 -> confirmDeleteSign(sign)
                }
            }
            .show()
    }

    /** Изменить знак: поле с прежним знаком, сохраняем новым. */
    private fun editSign(sign: String) {
        val field = EditText(this).apply {
            setText(sign)
            isSingleLine = true
            textSize = 17f
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reply_sign_edit)
            .setView(box)
            .setPositiveButton(R.string.dict_save) { _, _ ->
                val next = field.text.toString().trim()
                if (next.isEmpty()) {
                    toastText(getString(R.string.reply_sign_empty))
                    return@setPositiveButton
                }
                if (next != sign) {
                    ReplyMarks.renameMark(this, bookName(), sign, next)
                    buildRows()
                    focusTagRetry("sign:$next")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Удалить знак из списка совсем — с подтверждением. */
    private fun confirmDeleteSign(sign: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reply_sign_delete)
            .setMessage(getString(R.string.reply_sign_delete_msg, Roles.markName(sign)))
            .setPositiveButton(R.string.reply_sign_delete) { _, _ ->
                ReplyMarks.removeMark(this, bookName(), sign)
                toastText(getString(R.string.reply_head_removed, Roles.markName(sign)))
                buildRows()
                focusTagRetry("num")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Флажок с состоянием НЕ из настроек напрямую: у знаков реплик значение
     *  зависит от книги, поэтому addCheck из RowsActivity (он пишет по одному
     *  ключу) тут не годится. Возвращает сам флажок, чтобы можно было повесить
     *  долгое нажатие. */
    private fun localCheck(
        title: String,
        hint: String?,
        checked: Boolean,
        tag: String,
        onChange: (Boolean) -> Unit,
    ): SwitchCompat {
        val box = SwitchCompat(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            isChecked = checked
            isFocusable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            this.tag = tag
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        contentRoot.addView(
            box,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )
        return box
    }

    /** Перенести фокус на строку с меткой: только что пересобранный список ещё не
     *  разложен, а невидимую цель диктор пропускает ([TabNav.a11yFocus]). Ищем по
     *  метке как обычный [View], а не как строку [rowWithTag]: метки здесь стоят и
     *  на галочках, и каст к TextView на них уронил бы окно. */
    private fun focusTagRetry(tag: String, retry: Boolean = true) {
        val v = contentRoot.findViewWithTag<View>(tag) ?: return
        if (!v.isShown && retry) {
            v.postDelayed({ focusTagRetry(tag, retry = false) }, 120)
            return
        }
        TabNav.a11yFocus(v)
    }

    // ---------------- Плееры: список и проба ----------------

    /** Плеер ТОЛЬКО для чтения подписей и списков голосов. Живой годится, лишь
     *  когда он и есть движок реплик; иначе пробный. */
    private fun labelPlayer(): SpeechPlayer? {
        val engine = prefs().getString(MainActivity.KEY_REPLY_ENGINE, null)
        val p = probe
        if (p != null && p.isReady && p.enginePackage == engine) return p
        val live = MainActivity.active?.player
        if (live != null && live.isReady && (live.enginePackage ?: live.defaultEngine) == engine) {
            return live
        }
        return p
    }

    /** Пробный плеер на движке реплик — для списков и для пробы «Читать».
     *  Поднимаем молча: окно открывается без «Загрузка движка», строки просто
     *  сначала показывают записанное, а как движок отзовётся — уточняются. */
    private fun ensureProbe() {
        val engine = prefs().getString(MainActivity.KEY_REPLY_ENGINE, null)
        val live = MainActivity.active?.player
        if (live != null && live.isReady && (live.enginePackage ?: live.defaultEngine) == engine) {
            return // списки возьмём у живого плеера, проба поднимется по нажатию
        }
        val p = probe ?: SpeechPlayer(applicationContext).also { probe = it }
        if (p.isReady && p.enginePackage == engine) return
        p.setEngine(engine) { ok ->
            if (!ok) {
                Diag.log(this, "tts", "движок прямой речи ${engine ?: "системный"} не поднялся (окно «Прямая речь»)")
                return@setEngine
            }
            picker?.refresh()
        }
    }

    /** Проба репликовым голосом: «Читать» в этом окне читает образец, а не книгу.
     *  Всегда своим экземпляром — даже если движок тот же, что у книги: проба не
     *  должна перебивать текущую фразу чтения. */
    private fun speakReplySample() {
        val engine = prefs().getString(MainActivity.KEY_REPLY_ENGINE, null)
        val p = probe ?: SpeechPlayer(applicationContext).also { probe = it }
        fun apply() {
            val s = prefs()
            p.speed = s.getFloat(MainActivity.KEY_REPLY_SPEED, 1f)
            p.pitch = s.getFloat(MainActivity.KEY_REPLY_PITCH, 1f)
            p.volume = s.getFloat(MainActivity.KEY_REPLY_VOLUME, 1f)
            s.getString(MainActivity.KEY_REPLY_VOICE, null)?.let { p.selectVoice(it) }
            // 30.09.2026: свой образец — общий говорил про «чтение», и в окне
            // прямой речи человек не понимал, чей голос слушает.
            p.speak(getString(R.string.reply_preview_sample))
        }
        if (p.isReady && p.enginePackage == engine) {
            apply()
        } else {
            p.setEngine(engine) { ok ->
                if (!ok) {
                    toastText(getString(R.string.voice_reply_engine_fail))
                    return@setEngine
                }
                apply()
            }
        }
    }

    /** Хост набора для реплик: тот же интерфейс, но выбор пишется в настройки
     *  реплик, а «Читать» читает образец. */
    private val replyHost = object : VoicePicker.Host {
        override fun withPlayer(titleRes: Int, onReady: (SpeechPlayer, Boolean) -> Unit) {
            // ВСЕГДА свой экземпляр. Набор при выборе движка зовёт p.setEngine,
            // и живой плеер книги тут отдавать нельзя: смена движка РЕПЛИК
            // переключала бы движок книги. Так и случилось 26.09.2026: Серж
            // выбрал репликам Google, а книга замолчала — её движок стал Google,
            // а голос остался от multiTTS, которого Google не знает.
            val engine = prefs().getString(MainActivity.KEY_REPLY_ENGINE, null)
            val tmp = probe ?: SpeechPlayer(applicationContext).also { probe = it }
            if (tmp.isReady && tmp.enginePackage == engine) {
                onReady(tmp, false)
                return
            }
            val loading = MaterialAlertDialogBuilder(this@ReplyVoiceActivity)
                .setTitle(titleRes)
                .setMessage(R.string.engine_loading)
                .setNegativeButton(R.string.toc_close, null)
                .show()
            tmp.setEngine(engine) { ok ->
                if (!loading.isShowing) return@setEngine
                runCatching { loading.dismiss() }
                if (!ok) {
                    toastText(getString(R.string.voice_reply_engine_fail))
                    return@setEngine
                }
                // Не «временный»: экземпляр живёт, пока открыто это окно.
                onReady(tmp, false)
            }
        }

        override fun playerOrNull(): SpeechPlayer? = labelPlayer()

        override fun hasBook(): Boolean = false

        override fun isPlaying(): Boolean = false

        override fun togglePlay() = speakReplySample()

        override fun currentVoice(): String? =
            prefs().getString(MainActivity.KEY_REPLY_VOICE, null)

        override fun rememberChecked(): Boolean = false

        override fun rememberChanged(checked: Boolean) {}

        override fun engineApplied(pkg: String) {
            val e = prefs().edit().putString(MainActivity.KEY_REPLY_ENGINE, pkg)
            // Голос прежнего движка новому не указ: забываем его. Иначе движок
            // молча читает своим голосом, а строка показывает имя чужого — это
            // и выглядит как «выбрал другой голос, а читает прежний».
            e.remove(MainActivity.KEY_REPLY_VOICE)
            e.apply()
            applyReplyToPlayer()
        }

        override fun voiceApplied(v: android.speech.tts.Voice, lang: String?) {
            val e = prefs().edit().putString(MainActivity.KEY_REPLY_VOICE, v.name)
            if (lang != null) e.putString(MainActivity.KEY_REPLY_LANG, lang)
            e.apply()
            Diag.log(
                this@ReplyVoiceActivity, "tts",
                "прямая речь: выбран голос ${v.name} (движок " +
                    "${prefs().getString(MainActivity.KEY_REPLY_ENGINE, null) ?: "системный"})"
            )
            applyReplyToPlayer()
        }

        override fun langApplied(code: String) {
            prefs().edit().putString(MainActivity.KEY_REPLY_LANG, code).apply()
        }

        override fun redraw() {
            picker?.refresh()
        }

        override fun toast(msg: String) = toastText(msg)

        /** Мы уже в этом окне — строка-переход здесь ни к чему. */
        override fun openReplyWindow() {}

        /** Галочка «Чтение по ролям» — она живёт в наборе голоса читалки и
         *  Настроек; в этом окне режим переключает строка «Реплики». Метод
         *  интерфейса всё равно нужен, и он делает то же самое. */
        override fun replyToggled(on: Boolean) = applyReplyToPlayer()
    }

    // ---------------- Режим ----------------

    /** Отдать живому плееру все настройки реплик разом. */
    private fun applyReplyToPlayer() {
        val p = MainActivity.active?.player ?: return
        val s = prefs()
        p.setReplyVoice(
            s.getBoolean(MainActivity.KEY_REPLY_ON, false),
            s.getString(MainActivity.KEY_REPLY_ENGINE, null),
            s.getString(MainActivity.KEY_REPLY_VOICE, null),
        )
        p.replySpeed = s.getFloat(MainActivity.KEY_REPLY_SPEED, 1f)
        p.replyPitch = s.getFloat(MainActivity.KEY_REPLY_PITCH, 1f)
        p.replyVolume = s.getFloat(MainActivity.KEY_REPLY_VOLUME, 1f)
    }

    private fun toastText(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- Ползунки ----------------

    /** Ползунок скорости и тона: тот же вид и те же ступени, что у книжных.
     *  Кладём в [into], а не прямо в окно: ползунки реплик прячутся и
     *  показываются вместе с режимом, не пересобирая содержимое. */
    private fun addRateSlider(
        key: String,
        labelRes: Int,
        cd: String,
        values: List<Float>,
        into: LinearLayout,
        apply: (Float) -> Unit,
    ) {
        val ticks = values.lastIndex
        fun valueOf(pos: Int): Float = values[pos.coerceIn(0, ticks)]
        val cur = prefs().getFloat(key, 1f)
        val startP = RateSteps.indexOf(values, cur)
        val startV = valueOf(startP)

        val label = TextView(this).apply {
            text = getString(labelRes, RateSteps.label(startV))
            textSize = 17f
            setTextColor(Palette.INK)
            setPadding(0, 0, 0, dp(2))
        }
        into.addView(label)

        val seek = SeekBar(this).apply {
            max = ticks
            progress = startP
            contentDescription = cd
        }
        fun announce(v: Float) {
            val txt = RateSteps.label(v)
            if (android.os.Build.VERSION.SDK_INT >= 30) seek.stateDescription = txt
            else seek.contentDescription = "$cd, $txt"
        }
        announce(startV)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = valueOf(progress)
                prefs().edit().putFloat(key, v).apply()
                apply(v)
                announce(v)
                label.text = getString(labelRes, RateSteps.label(v))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        into.addView(seek, sliderLp())
    }

    private fun addVolumeSlider(into: LinearLayout) {
        val key = MainActivity.KEY_REPLY_VOLUME
        val startP = Math.round(prefs().getFloat(key, 1f) * 100).coerceIn(0, 200)
        val label = TextView(this).apply {
            text = getString(R.string.reply_volume_value, startP)
            textSize = 17f
            setTextColor(Palette.INK)
            setPadding(0, 0, 0, dp(2))
        }
        into.addView(label)

        val cd = getString(R.string.reply_volume_cd)
        val seek = SeekBar(this).apply {
            max = 200
            progress = startP
            contentDescription = cd
        }
        fun announce(p: Int) {
            val txt = "$p%"
            if (android.os.Build.VERSION.SDK_INT >= 30) seek.stateDescription = txt
            else seek.contentDescription = "$cd, $txt"
        }
        announce(startP)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs().edit().putFloat(key, v).apply()
                applyToLive { it.replyVolume = v }
                announce(progress)
                label.text = getString(R.string.reply_volume_value, progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        into.addView(seek, sliderLp())
    }

    private fun sliderLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
}
