package com.cuber.bookvoice

import android.content.Intent
import android.view.ViewGroup
import android.widget.Toast
import com.cuber.bookvoice.databinding.ActivitySleepBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Таймер сна» (msg4993).
 *
 * Что здесь. Куски времени (5, 10, 15, 20, 30, 45, 60), «До конца главы» и
 * строка «Настройки таймера…» — в отдельное окно поверх (msg4981: в одно окно
 * всё не влезало, а длинный список настроек в меню читать неудобно).
 *
 * Почему окно, а не диалог выбора. Диалог закрывался сам и ничего не объяснял:
 * ни что выбранный таймер умеет (сигнал за минуту, ожидание встряски), ни куда
 * идти, если жест не отзывается. Окно живёт «как книга» — в нём работает
 * скринридер, возврат на книгу и «⋮» с настройками, как в остальных окнах.
 *
 * Что видно про текущий таймер. Активный кусок помечен подсказкой «Уже стоит»,
 * а строка «Выключить таймер» показывает, сколько осталось, — незрячему важно
 * услышать состояние, а не вспоминать, что он ставил вчера.
 */
class SleepTimerWindowActivity : RowsActivity() {

    private lateinit var binding: ActivitySleepBinding

    override val contentRoot: ViewGroup get() = binding.content

    override fun buildSection(intent: Intent?) {
        setTitle(getString(R.string.sleep_timer_title))
        binding = ActivitySleepBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.tvTitle.text = getString(R.string.sleep_timer_title)
        binding.btnBack.setOnClickListener { rootBack() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        buildContent()
    }

    override fun resumeSection(arrival: Boolean) {
        if (arrival) {
            // Свежий вход — фокус на заголовок окна: сразу ясно, куда зашёл.
            TabNav.focusHeader(binding.tvTitle, 550)
            return
        }
        // Возврат из окна настроек таймера: там могли снять таймер или сменить
        // режим — состояние пересобираем, фокус оставляем где был.
        buildContent()
        TabNav.refocusAfterRebuild(contentRoot)
    }

    override fun onSectionBackKey(): Boolean {
        rootBack()
        return true
    }

    /** Уход на полку-дом: окно закрывается, полка внизу не должна прыгать в
     *  последнюю книгу (тот же фикс, что у окон Каталога и «О программе»). */
    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно «Таймер сна» уходит на полку — авто-открытие погашено")
        super.rootBack()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        addHint(getString(R.string.sleep_timer_intro))

        if (ReaderEngine.sleepTimerActive) {
            addRow(
                getString(R.string.sleep_off),
                sleepLeftHint(),
                tag = TAG_OFF,
            ) { cancelTimer() }
        }

        for (m in SleepTimerPrefs.MINUTE_CHOICES) {
            val active = ReaderEngine.sleepMode == ReaderEngine.SLEEP_MINUTES &&
                ReaderEngine.sleepMinutes == m
            addRow(
                getString(R.string.sleep_min, m),
                if (active) getString(R.string.sleep_timer_now) else null,
                tag = tagMinutes(m),
            ) { pickMinutes(m) }
        }

        val chapterActive = ReaderEngine.sleepMode == ReaderEngine.SLEEP_CHAPTER
        addRow(
            getString(R.string.sleep_chapter),
            if (chapterActive) getString(R.string.sleep_timer_now) else null,
            tag = TAG_CHAPTER,
        ) { pickChapter() }

        addRow(
            getString(R.string.sleep_settings_row),
            getString(R.string.sleep_settings_hint),
            tag = TAG_SETTINGS,
        ) { startActivity(Intent(this, SleepTimerSettingsActivity::class.java)) }

        addHint(getString(R.string.sleep_timer_hint))
    }

    /** Сколько осталось в активном таймере — словами, без опоры на «N минут»
     *  как на название куска: «осталось 7 мин» честнее, чем «10 минут». */
    private fun sleepLeftHint(): String? = when (ReaderEngine.sleepMode) {
        ReaderEngine.SLEEP_CHAPTER -> getString(R.string.sleep_timer_left_chapter)
        ReaderEngine.SLEEP_MINUTES -> {
            val left = ReaderEngine.sleepRemainingMinutes()
            if (left > 0) getString(R.string.sleep_timer_left, left) else null
        }
        else -> null
    }

    // ---------------- Действия ----------------

    private fun pickMinutes(minutes: Int) {
        ReaderEngine.setSleepTimerMinutes(minutes)
        Diag.log(this, "sleep", "окно таймера: выбрано $minutes минут")
        done(getString(R.string.sleep_menu_active, getString(R.string.sleep_min, minutes)))
    }

    private fun pickChapter() {
        ReaderEngine.setSleepTimerChapterEnd()
        Diag.log(this, "sleep", "окно таймера: выбран режим «до конца главы»")
        done(getString(R.string.sleep_menu_chapter_active))
    }

    private fun cancelTimer() {
        ReaderEngine.cancelSleepTimer()
        Diag.log(this, "sleep", "окно таймера: таймер снят")
        done(getString(R.string.sleep_off_done))
    }

    /** Выбор сделан — говорим вслух и уходим назад к книге: таймер работает в
     *  движке, окно ему больше не нужно. Тост, а не строка в окне: читалка под
     *  нами уже видна, и подтверждение должно прозвучать поверх неё. */
    private fun done(message: String) {
        Vibra.confirm(this)
        // LENGTH_LONG, как везде в приложении: короткий тост скринридер может
        // не успеть прочитать целиком.
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val TAG_OFF = "sleep_off"
        const val TAG_CHAPTER = "sleep_chapter"
        const val TAG_SETTINGS = "sleep_settings"

        fun tagMinutes(m: Int) = "sleep_min_$m"
    }
}
