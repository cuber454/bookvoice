package com.cuber.bookvoice

import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import com.cuber.bookvoice.databinding.ActivitySleepBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Не засыпать» (msg4705/4709) — помощник для прошивок, которые морозят
 * чтение своим сторожем энергии (MIUI на Poco, ColorOS на realme).
 *
 *  Почему окно, а не одна кнопка. Разрешение «не ограничивать батарею» — только
 *  одна из трёх дверей: на MIUI/ColorOS отдельно живут «Автозапуск» и «Разрешить
 *  фоновую активность», и отдельно — ограничение батареи у САМОГО движка речи
 *  (у тестера на Poco затыки ушли после снятия ограничения с RHVoice, а не с
 *  BookVoice, msg4550). Слепому пользователю важно слышать, что уже разрешено и
 *  куда идти дальше, а не угадывать по одной кнопке.
 *
 *  Как читается. Сверху — вступление, ниже состояние (озвучивается строкой:
 *  «Оптимизация батареи: ограничена»), дальше кнопки-действия и подсказки
 *  словами. Каждое действие возвращает ответ строкой-результатом вверху: после
 *  возврата из системного окна фокус встаёт на неё, и скринридер её читает —
 *  тост для незрячего слишком легко пропустить.
 */
class SleepWindowActivity : RowsActivity() {

    private lateinit var binding: ActivitySleepBinding

    /** Строки окна строятся в [ActivitySleepBinding.content] — общий каркас
     *  строк живёт в [RowsActivity]. */
    override val contentRoot: ViewGroup get() = binding.content

    /** Ответ последнего действия — строкой вверху, чтобы после возврата из
     *  системного окна было слышно, что произошло и что делать дальше. */
    private var lastResult: String? = null

    /** Первая строка состояния — на неё встаёт фокус при возврате из системного
     *  окна: состояние пересобрано, и его надо услышать (msg4709). */
    private var statusRow: TextView? = null

    private var resultRow: TextView? = null

    override fun buildSection(intent: Intent?) {
        setTitle(getString(R.string.sleep_title))
        binding = ActivitySleepBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.tvTitle.text = getString(R.string.sleep_title)
        binding.btnBack.setOnClickListener { rootBack() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        buildContent()
    }

    override fun resumeSection(arrival: Boolean) {
        if (arrival) {
            // Свежий вход — фокус на заголовок окна (msg1666): сразу ясно, куда зашёл.
            TabNav.focusHeader(binding.tvTitle, 550)
            return
        }
        // Возврат из системного окна: запреты могли поменяться — пересобираем
        // состояние и озвучиваем его строкой (её и слушает пользователь).
        buildContent()
        val target = resultRow ?: statusRow
        if (target != null) TabNav.refocusAfterRebuild(binding.content, target)
    }

    override fun onSectionBackKey(): Boolean {
        rootBack()
        return true
    }

    /** Уход на полку-дом: окно закрывается, полка внизу не должна прыгать в
     *  последнюю книгу (тот же фикс, что у окон Каталога и «О программе»). */
    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно «Не засыпать» уходит на полку — авто-открытие погашено")
        super.rootBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) LibraryActivity.suppressNextAutoOpen = true
    }

    /** «⋮» окна: пункты как у остальных окон, «Выход из приложения» — последним. */
    private fun showMoreMenu() {
        MaterialAlertDialogBuilder(this)
            .setItems(arrayOf(
                getString(R.string.settings_program),
                getString(R.string.app_exit),
            )) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, SettingsWindowActivity::class.java))
                    1 -> TabNav.exitApp(this)
                }
            }
            .show()
    }

    // ---------------- Сборка содержимого ----------------

    private fun buildContent() {
        val root = binding.content
        root.removeAllViews()
        statusRow = null
        resultRow = null

        addHint(getString(R.string.sleep_intro))

        // Ответ последнего действия — ПЕРВОЙ строкой после вступления: возврат из
        // системного окна начинается с него.
        lastResult?.let {
            resultRow = addRow(it, null, strong = true)
        }

        // Состояние: две независимые двери, обе проверяются у системы на месте
        // (состояние меняется в её окне, а не у нас), поэтому строки собираются
        // заново при каждом показе.
        val battery = SleepGuard.ignoringBattery(this)
        statusRow = addRow(
            getString(if (battery) R.string.sleep_battery_on else R.string.sleep_battery_off),
            null,
            onClick = null,
        )
        val restricted = SleepGuard.backgroundRestricted(this)
        addRow(
            getString(if (restricted) R.string.sleep_bg_off else R.string.sleep_bg_on),
            null,
            onClick = null,
        )

        addRow(
            getString(R.string.sleep_ask_title),
            getString(R.string.sleep_ask_hint),
        ) { askBattery() }
        addRow(
            getString(R.string.sleep_autostart_title),
            getString(R.string.sleep_autostart_hint),
        ) { openAutostart() }
        addRow(
            getString(R.string.sleep_app_title),
            getString(R.string.sleep_app_hint),
        ) { openAppDetails() }
        addRow(
            getString(R.string.sleep_engine_title),
            getString(R.string.sleep_engine_hint),
        ) { openEngineSettings() }

        // msg4721: галочка тихого звукового потока жила здесь — последней из
        // «рабочих» строк. msg5067: переехала в «Настройки → Чтение», к звуку
        // чтения, где её и искал Сергей. Здесь остаётся только след — иначе
        // человек, помнящий её в этом окне, решит, что её убрали.
        addHint(getString(R.string.sleep_silent_moved))

        addHint(getString(R.string.sleep_lock_hint))
        addHint(getString(R.string.sleep_log_hint))
    }

    // ---------------- Действия ----------------

    /** Системный запрос «не ограничивать батарею». Уже разрешено — говорим об
     *  этом и никуда не уходим: окна запроса система в этом случае не покажет, и
     *  нажатие выглядело бы немым. */
    private fun askBattery() {
        if (SleepGuard.ignoringBattery(this)) {
            lastResult = getString(R.string.settings_bg_already)
            Diag.log(this, "power", "«Не засыпать»: работа в фоне уже разрешена")
        } else {
            val asked = SleepGuard.requestIgnoreBattery(this)
            lastResult = getString(
                if (asked) R.string.sleep_ask_result else R.string.sleep_ask_result_list
            )
            Diag.log(
                this, "power",
                "«Не засыпать»: запрошено разрешение работать в фоне " +
                    "(${if (asked) "окно запроса" else "общий список"})"
            )
        }
        buildContent()
        resultRow?.let { TabNav.a11yFocus(it) }
    }

    /** Прошивочный экран «Автозапуск». Экрана может не быть — это не поломка:
     *  честно говорим словами и открываем настройки приложения, где на многих
     *  прошивках те же переключатели лежат внутри. */
    private fun openAutostart() {
        val vendor = SleepGuard.openAutostart(this)
        if (vendor != null) {
            lastResult = getString(R.string.sleep_autostart_result, vendor)
            Diag.log(this, "power", "«Не засыпать»: открыт экран автозапуска прошивки ($vendor)")
        } else if (SleepGuard.openAppDetails(this)) {
            lastResult = getString(R.string.sleep_autostart_none)
            Diag.log(this, "power", "«Не засыпать»: экрана автозапуска нет, открыты настройки приложения")
        } else {
            lastResult = getString(R.string.sleep_app_failed)
            Diag.log(this, "power", "«Не засыпать»: ни автозапуска, ни настроек приложения открыть не удалось")
        }
        buildContent()
        resultRow?.let { TabNav.a11yFocus(it) }
    }

    private fun openAppDetails() {
        val ok = SleepGuard.openAppDetails(this)
        lastResult = getString(if (ok) R.string.sleep_app_result else R.string.sleep_app_failed)
        Diag.log(this, "power", "«Не засыпать»: настройки приложения ${if (ok) "открыты" else "не открылись"}")
        buildContent()
        resultRow?.let { TabNav.a11yFocus(it) }
    }

    /** Настройки движка речи: там же снимается ограничение батареи с синтезатора —
     *  то, что реально помогло тестеру на Poco (msg4550). */
    private fun openEngineSettings() {
        val ok = SleepGuard.openTtsSettings(this)
        lastResult = getString(if (ok) R.string.sleep_engine_result else R.string.sleep_engine_failed)
        Diag.log(this, "power", "«Не засыпать»: настройки синтеза речи ${if (ok) "открыты" else "не открылись"}")
        buildContent()
        resultRow?.let { TabNav.a11yFocus(it) }
    }

    // ---------------- Строки экрана ----------------
    // addHint / addRow / addCheck / withHint / dp живут в RowsActivity — окна
    // «Не засыпать», «Таймер сна» и «Настройки таймера» рисуют строки одинаково.
    // logSilence переехал вместе с галочкой в SettingsActivity (msg5067).
}
