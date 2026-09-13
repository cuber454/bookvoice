package com.cuber.bookvoice

import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import com.cuber.bookvoice.databinding.ActivitySleepBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Настройки таймера» (msg4993) — отдельным окном поверх таймера (msg4981).
 *
 * Почему отдельное окно. Всё в одно не влезало: куски времени — это выбор
 * «сейчас», а жесты, чувствительность, сигнал и проверки — настройка «вообще».
 * Смешав их в один список, незрячий вынужден слушать всё подряд, чтобы дойти до
 * нужной строки. Здесь два уровня: таймер (сколько) и его настройки (как).
 *
 * Порядок строк — от главного к проверке. Сначала то, что человек включает и
 * слышит каждый раз (ждать жест, переворот, продление), потом тонкая настройка
 * (чувствительность, сила вибрации), потом каналы (сигнал, голос), и только
 * в конце — проверки. Проверки специально последними: это редкое действие, а
 * короткий список ценнее.
 *
 * Проверки без ожидания (msg4985/4987). Ждать пять минут, чтобы понять, ловит ли
 * телефон встряску, нельзя. Полная проверка укладывается в полминуты: двадцать
 * секунд чтения, сигнал и десять секунд ожидания. Отдельная проверка жеста —
 * сразу десять секунд слушания, без чтения и сигнала: её и гоняют, подбирая
 * ступень чувствительности. Книга при обеих проверках НЕ останавливается.
 *
 * Каждая подсказка написана простыми словами (msg4989): что произойдёт и что
 * делать, если не сработало, — без «порогов ускорения» и «сенсоров».
 */
class SleepTimerSettingsActivity : RowsActivity() {

    private lateinit var binding: ActivitySleepBinding

    override val contentRoot: ViewGroup get() = binding.content

    /** Ответ последней проверки — первой строкой содержимого. */
    private var lastResult: String? = null
    private var resultRow: TextView? = null

    /** Раскрыто ли объяснение «Как это работает». */
    private var howOpen = false

    override fun buildSection(intent: Intent?) {
        setTitle(getString(R.string.sleep_settings_title))
        binding = ActivitySleepBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.tvTitle.text = getString(R.string.sleep_settings_title)
        binding.btnBack.setOnClickListener { rootBack() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        // Результат проверки приходит из движка (проверка идёт таймером, в том
        // числе когда человек уже ушёл из окна в книгу). Строку обновляем на
        // месте, без пересборки: фокус человека при этом не дёргается. И
        // проговариваем сами — это окно сейчас активно, а окно книги под ним
        // система может не озвучить.
        ReaderEngine.sleepTestListener = { text ->
            lastResult = text
            resultRow?.let {
                it.text = withHint(text, null)
                it.announceForAccessibility(text)
            }
        }
        buildContent()
    }

    override fun disposeSection() {
        // Окно закрылось — проверки больше никуда не докладывают.
        ReaderEngine.sleepTestListener = null
    }

    override fun resumeSection(arrival: Boolean) {
        if (arrival) {
            TabNav.focusHeader(binding.tvTitle, 550)
            return
        }
        buildContent()
        TabNav.refocusAfterRebuild(contentRoot)
    }

    override fun onSectionBackKey(): Boolean {
        stopRunningTest()
        rootBack()
        return true
    }

    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно «Настройки таймера» уходит на полку — авто-открытие погашено")
        super.rootBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Проверку не бросаем «висеть»: ушли из окна — она больше не нужна.
        stopRunningTest()
        // Слушателя снимаем и здесь: [disposeSection] зовётся только при
        // isFinishing, а при смене конфигурации движок держал бы ссылку на
        // погибшее окно.
        ReaderEngine.sleepTestListener = null
        if (isFinishing) LibraryActivity.suppressNextAutoOpen = true
    }

    private fun showMoreMenu() {
        MaterialAlertDialogBuilder(this)
            .setItems(
                arrayOf(
                    getString(R.string.settings_program),
                    getString(R.string.app_exit),
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SettingsWindowActivity::class.java))
                    1 -> TabNav.exitApp(this)
                }
            }
            .show()
    }

    // ---------------- Сборка содержимого ----------------

    private fun buildContent() {
        contentRoot.removeAllViews()
        resultRow = null

        addHint(getString(R.string.sleep_settings_intro))

        resultRow = addRow(
            lastResult ?: getString(R.string.sleep_check_none),
            if (lastResult == null) getString(R.string.sleep_check_none_hint) else null,
            strong = true,
            tag = TAG_RESULT,
        )

        // ——— Как ведёт себя таймер ———
        addCheck(
            getString(R.string.sleep_wait_title),
            getString(R.string.sleep_wait_hint),
            SleepTimerPrefs.KEY_WAIT,
            SleepTimerPrefs.DEF_WAIT,
            tag = TAG_WAIT,
        ) { on ->
            Diag.log(this, "sleep", "настройки таймера: ожидание жеста ${if (on) "включено" else "выключено"}")
            rebuild(TAG_WAIT)
        }
        addCheck(
            getString(R.string.sleep_flip_title),
            getString(R.string.sleep_flip_hint),
            SleepTimerPrefs.KEY_FLIP,
            SleepTimerPrefs.DEF_FLIP,
            tag = TAG_FLIP,
        ) { on ->
            Diag.log(this, "sleep", "настройки таймера: переворот ${if (on) "включён" else "выключен"}")
            rebuild(TAG_FLIP)
        }
        addRow(
            getString(
                R.string.sleep_extend_title,
                getString(R.string.sleep_min, SleepTimerPrefs.extendMinutes(this)),
            ),
            getString(R.string.sleep_extend_hint),
            tag = TAG_EXTEND,
        ) { cycleExtend() }

        // ——— Тонкая настройка ———
        addRow(
            getString(R.string.sleep_sense_title, getString(senseWord())),
            getString(R.string.sleep_sense_hint),
            tag = TAG_SENSE,
        ) { cycleSense() }
        addRow(
            getString(R.string.sleep_vibra_title, getString(vibraWord())),
            getString(R.string.sleep_vibra_hint),
            tag = TAG_VIBRA,
        ) { cycleVibra() }

        // ——— Каналы сигнала ———
        addCheck(
            getString(R.string.sleep_signal_title),
            getString(R.string.sleep_signal_hint),
            SleepTimerPrefs.KEY_SIGNAL,
            SleepTimerPrefs.DEF_SIGNAL,
            tag = TAG_SIGNAL,
        ) { on ->
            Diag.log(this, "sleep", "настройки таймера: сигнал ${if (on) "включён" else "выключен"}")
            rebuild(TAG_SIGNAL)
        }
        addCheck(
            getString(R.string.sleep_voice_title),
            getString(R.string.sleep_voice_hint),
            SleepTimerPrefs.KEY_VOICE,
            SleepTimerPrefs.DEF_VOICE,
            tag = TAG_VOICE,
        ) { on ->
            Diag.log(this, "sleep", "настройки таймера: объявление голосом ${if (on) "включено" else "выключено"}")
            rebuild(TAG_VOICE)
        }

        // ——— Проверки ———
        addRow(
            getString(R.string.sleep_check_all_title),
            getString(R.string.sleep_check_all_hint),
            tag = TAG_CHECK_ALL,
        ) { checkAll() }
        addRow(
            getString(R.string.sleep_check_gesture_title),
            getString(R.string.sleep_check_gesture_hint),
            tag = TAG_CHECK_GESTURE,
        ) { checkGesture() }
        addRow(
            getString(R.string.sleep_check_signal_title),
            getString(R.string.sleep_check_signal_hint),
            tag = TAG_CHECK_SIGNAL,
        ) { checkSignal() }
        addRow(
            getString(R.string.sleep_check_vibra_title),
            getString(R.string.sleep_check_vibra_hint),
            tag = TAG_CHECK_VIBRA,
        ) { checkVibra() }

        // ——— Объяснение ———
        addRow(
            getString(
                if (howOpen) R.string.sleep_how_title_open else R.string.sleep_how_title
            ),
            getString(
                if (howOpen) R.string.sleep_how_hint_open else R.string.sleep_how_hint
            ),
            tag = TAG_HOW,
        ) { toggleHow() }
        if (howOpen) addHint(getString(R.string.sleep_how_text))
    }

    /** Пересобрать содержимое и вернуть фокус на строку [tag]: строка пересоздана,
     *  прежняя ссылка мертва, а человек на ней стоял. */
    private fun rebuild(tag: String) {
        buildContent()
        focusTag(tag)
    }

    // ---------------- Слова ступеней ----------------

    private fun senseWord(): Int = when (SleepTimerPrefs.sense(this)) {
        0 -> R.string.sleep_sense_low
        2 -> R.string.sleep_sense_high
        else -> R.string.sleep_sense_mid
    }

    private fun vibraWord(): Int = when (SleepTimerPrefs.vibraLevel(this)) {
        0 -> R.string.sleep_vibra_low
        2 -> R.string.sleep_vibra_high
        else -> R.string.sleep_vibra_mid
    }

    // ---------------- Правка значений ----------------

    private fun cycleExtend() {
        val list = SleepTimerPrefs.EXTEND_CHOICES
        val cur = SleepTimerPrefs.extendMinutes(this)
        val next = list[(list.indexOf(cur) + 1) % list.size]
        SleepTimerPrefs.prefs(this).edit().putInt(SleepTimerPrefs.KEY_EXTEND, next).apply()
        Diag.log(this, "sleep", "настройки таймера: продление $next минут")
        rebuild(TAG_EXTEND)
    }

    private fun cycleSense() {
        val next = (SleepTimerPrefs.sense(this) + 1) % 3
        SleepTimerPrefs.prefs(this).edit().putInt(SleepTimerPrefs.KEY_SENSE, next).apply()
        Diag.log(this, "sleep", "настройки таймера: чувствительность $next")
        rebuild(TAG_SENSE)
    }

    private fun cycleVibra() {
        val next = (SleepTimerPrefs.vibraLevel(this) + 1) % 3
        SleepTimerPrefs.prefs(this).edit().putInt(SleepTimerPrefs.KEY_VIBRA, next).apply()
        Diag.log(this, "sleep", "настройки таймера: сила вибрации $next")
        // Сразу даём послушать выбранную силу: подбирать её на слух удобнее,
        // чем по названию ступени (та же логика, что у «Прослушать» для голоса).
        ReaderEngine.sleepTestVibra()
        rebuild(TAG_VIBRA)
    }

    private fun toggleHow() {
        howOpen = !howOpen
        Diag.log(this, "sleep", "настройки таймера: объяснение ${if (howOpen) "раскрыто" else "свёрнуто"}")
        rebuild(TAG_HOW)
    }

    // ---------------- Проверки ----------------

    /** Полная проверка: чтение идёт, через двадцать секунд сигнал, дальше десять
     *  секунд ждём жест. Результат приходит из движка в строку «Проверка…». */
    private fun checkAll() {
        ReaderEngine.startSleepTimerTest()
    }

    /** Проверка жеста отдельно — без чтения и сигнала: удобно подбирать ступень. */
    private fun checkGesture() {
        ReaderEngine.startSleepSenseTest()
    }

    private fun checkSignal() {
        ReaderEngine.sleepTestSignal()
        showResult(getString(R.string.sleep_check_signal_done))
    }

    private fun checkVibra() {
        ReaderEngine.sleepTestVibra()
        showResult(getString(R.string.sleep_check_vibra_done))
    }

    /** Результат мгновенной проверки виден и слышен сразу: обновляем строку и
     *  переводим на неё фокус — скринридер её прочитает. */
    private fun showResult(text: String) {
        lastResult = text
        val row = resultRow ?: return
        row.text = withHint(text, null)
        TabNav.a11yFocus(row)
    }

    /** Уйти из окна, не оставив проверку идти своим ходом: полная проверка потом
     *  сказала бы «жеста не было» уже в книге, без всякого повода. */
    private fun stopRunningTest() {
        ReaderEngine.stopSleepTest()
    }

    private companion object {
        const val TAG_RESULT = "sleep_result"
        const val TAG_WAIT = "sleep_wait"
        const val TAG_FLIP = "sleep_flip"
        const val TAG_EXTEND = "sleep_extend"
        const val TAG_SENSE = "sleep_sense"
        const val TAG_VIBRA = "sleep_vibra"
        const val TAG_SIGNAL = "sleep_signal"
        const val TAG_VOICE = "sleep_voice"
        const val TAG_CHECK_ALL = "sleep_check_all"
        const val TAG_CHECK_GESTURE = "sleep_check_gesture"
        const val TAG_CHECK_SIGNAL = "sleep_check_signal"
        const val TAG_CHECK_VIBRA = "sleep_check_vibra"
        const val TAG_HOW = "sleep_how"
    }
}
