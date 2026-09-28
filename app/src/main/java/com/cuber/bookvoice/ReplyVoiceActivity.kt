package com.cuber.bookvoice

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Чтение по ролям» (26.09.2026) — настройка реплик.
 *
 *  Зачем отдельным окном. Репликам нужен и голос, и своя скорость, и свой тон,
 *  и своя громкость: в общем списке «Голос» это ещё шесть строк, и до книжных
 *  ползунков приходилось бы слушать их все. В разделе осталась одна строка
 *  «По ролям» со значением (сразу под кнопкой чтения), а настройка — здесь.
 *
 *  Интерфейс — ТОТ ЖЕ, что у основного голоса (просьба Сержа 26.09.2026):
 *  строки движка, языка и голоса рисует тот же набор [VoicePicker] с целью
 *  [VoicePicker.Target.REPLY], списки и подписи буквально одни и те же. Сверху
 *  стоит кнопка «Читать» — проба репликовым голосом, чтобы услышать выбор, не
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
        if (arrival) binding.tvTitle.announceForAccessibility(getString(R.string.reply_window_title))
    }

    /** «Назад» обрабатывает база: окно закрывается, под ним — то, откуда пришли. */
    override fun onSectionBackKey(): Boolean = false

    override fun disposeSection() {
        probe?.shutdown()
        probe = null
    }

    private fun prefs() = getSharedPreferences(PREFS_READER, MODE_PRIVATE)

    private fun buildRows() {
        contentRoot.removeAllViews()

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
                "роли: ${if (checked) "включены" else "выключены"} галочкой в окне «Чтение по ролям»",
            )
            applyReplyToPlayer()
        }

        // Предупреждение — сразу под переключателем: его читают до того, как
        // включат.
        addHint(getString(R.string.voice_reply_hint))

        // 26.09.2026: в альтернативном способе озвучки фразу целиком читает
        // движок, наш конвейер файлов не участвует, а репликам без него не
        // жить. Говорим об этом прямо, а не молчим выключенным переключателем.
        if (prefs().getBoolean(MainActivity.KEY_ALT_VOICE, false)) {
            addHint(getString(R.string.reply_alt_voice_hint))
            Diag.log(this, "tts", "реплики: открыто окно ролей, но включён альтернативный способ озвучки")
        }

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
                Diag.log(this, "tts", "движок реплик ${engine ?: "системный"} не поднялся (окно ролей)")
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
            p.speak(getString(R.string.voice_preview_sample))
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
                "реплики: выбран голос ${v.name} (движок " +
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
