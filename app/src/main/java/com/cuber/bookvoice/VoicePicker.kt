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
) {
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
    }

    val playRow: Button = actionRow()
    val engineRow: Button = valueRow()
    val langRow: Button = valueRow()
    val voiceRow: Button = valueRow()

    /** Язык, открытый в списке голосов. Это ФИЛЬТР списка, а не настройка:
     *  по умолчанию — язык, выбранный Сергеем руками (msg5622), иначе язык
     *  текущего голоса. */
    private var pickLang: String? = null

    /** Собрать набор в контейнер: кнопка чтения/образца, три строки-значения. */
    fun build(parent: LinearLayout) {
        parent.addView(playRow)
        engineRow.setOnClickListener { pickEngine() }
        langRow.setOnClickListener { pickLangList() }
        voiceRow.setOnClickListener { pickVoice() }
        parent.addView(engineRow)
        parent.addView(langRow)
        parent.addView(voiceRow)
        resetLang()
        refresh()
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
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(dp(4f), 0, dp(4f), dp(8f))
        })
        cb.setOnCheckedChangeListener { _, checked -> host.rememberChanged(checked) }
    }

    /** Перерисовать подписи строк и кнопки чтения из текущего состояния. */
    fun refresh() {
        val p = host.playerOrNull()
        val voices = p?.voices ?: emptyList()
        val pkg = p?.enginePackage ?: p?.defaultEngine
            ?: prefs.getString(MainActivity.KEY_ENGINE, null)
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
        val voiceName = host.currentVoice()
        val voiceText = voiceName?.let { name ->
            val v = voices.firstOrNull { it.name == name }
            when {
                v == null -> name
                VoicePick.codeOf(v) == pickLang -> VoicePick.voiceLabel(v)
                // Голос выбран из другого языка, чем открыт список: показываем
                // его как есть и называем язык — выбор не пропадает молча.
                else -> ctx.getString(
                    R.string.voice_lang_other,
                    VoicePick.voiceLabel(v),
                    VoicePick.langLabel(VoicePick.codeOf(v)),
                )
            }
        } ?: ctx.getString(R.string.no_voices)

        engineRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_engine_row), engineName)
        langRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_lang_row), langText)
        voiceRow.text = ctx.getString(R.string.voice_row_value, ctx.getString(R.string.voice_voice_row), voiceText)
        refreshPlayLabel()
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
        val want = prefs.getString(MainActivity.KEY_VOICE_LANG, null)
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
            val cur = p.enginePackage ?: defaultEngine
            val labels = engines.map { (pkg, label) ->
                if (pkg == defaultEngine) "$label (системный)" else label
            }.toTypedArray()
            val idx = engines.indexOfFirst { it.first == cur }.coerceAtLeast(0)
            val dlg = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.voice_engine_row)
                .setSingleChoiceItems(labels, idx) { d, which ->
                    d.dismiss()
                    val pkg = engines[which].first
                    if (pkg == p.enginePackage) return@setSingleChoiceItems
                    host.toast(ctx.getString(R.string.voice_engine_switch))
                    // Куда писать движок, решает хост: в читалке — при закрытии
                    // панели (#55/msg2669: выбор «до галочки» не должен
                    // заражать общие настройки), в Настройках — сразу.
                    host.engineApplied(pkg)
                    p.setEngine(pkg) { ok ->
                        if (!ok) {
                            p.setEngine(null) { _ -> afterEngineChange(p) }
                            host.toast("Движок не запустился, вернул системный")
                        } else {
                            // msg5604: движок сменили на ходу — перечитываем
                            // текущее предложение новым движком. Именно ЗДЕСЬ, а
                            // не в engineApplied: там движок ещё старый и готов,
                            // и перечитка ушла бы прежним голосом. Без книги
                            // restartAfterSwitch молчит.
                            ReaderEngine.restartAfterSwitch()
                            afterEngineChange(p)
                        }
                    }
                }
                .setNegativeButton(R.string.toc_close, null)
                .show()
            if (temp) dlg.setOnDismissListener { p.shutdown() }
        }
    }

    /** Движок сменили: язык, выбранный руками, смену переживает (msg5622).
     *  Решаем это, когда движок отдал список голосов — список приезжает позже
     *  init (msg3550), а по пустому списку «у движка нет языка» было бы враньём. */
    private fun afterEngineChange(p: SpeechPlayer) {
        ReaderEngine.whenVoicesReady(on = p) {
            val want = pickLang ?: prefs.getString(MainActivity.KEY_VOICE_LANG, null)
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
            refresh()
            host.redraw()
        }
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
                .setSingleChoiceItems(labels, idx) { d, which ->
                    d.dismiss()
                    val v = list[which]
                    p.selectVoice(v.name)
                    host.voiceApplied(v, pickLang)
                    refresh()
                    host.redraw()
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
            if (host.hasBook()) host.togglePlay() else playSample()
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
