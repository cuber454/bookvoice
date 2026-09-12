package com.cuber.bookvoice

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
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
class SleepWindowActivity : SectionActivity() {

    private lateinit var binding: ActivitySleepBinding

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

        // msg4721: единственная галочка окна — тихий звуковой поток. Стоит последней
        // из «рабочих» строк: она не действие, а настройка, и включается на время чтения.
        addCheck(
            getString(R.string.sleep_silent_title),
            getString(R.string.sleep_silent_hint),
            MainActivity.KEY_SILENT_KEEPALIVE,
            def = false,
        ) { on -> KeepAwake.syncSilence(); logSilence(on) }

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

    /** Подсказка-пояснение: обычный текст без роли кнопки — скринридер читает его
     *  целиком, когда фокус встаёт на строку. */
    private fun addHint(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF9AA0A6.toInt())
            setLineSpacing(0f, 1.1f)
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        binding.content.addView(tv)
    }

    /** Строка экрана: название, второй строкой — подсказка или состояние. Как
     *  строки Настроек (title + приглушённый hint). [onClick] = null — строка
     *  только сообщает (состояние, ответ действия), нажимать её нечего.
     *
     *  Класс узла подменяем на TextView: роль «кнопка» на каждой строке не нужна,
     *  двойной тап и фокус при этом сохраняются (тот же приём в Настройках). */
    private fun addRow(
        title: String,
        hint: String?,
        strong: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): TextView {
        val v = TextView(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            if (strong) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isFocusable = true
            ViewCompat.setScreenReaderFocusable(this, true)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.TextView"
                }
            }
        }
        binding.content.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )
        return v
    }

    /** Галочка-настройка (msg4721). Флажок сам говорит скринридеру «включено» или
     *  «выключено» — для переключателя это роднее, чем переписывать состояние в
     *  текст строки. Значение ложится в те же prefs «reader», что и прочие
     *  настройки чтения, и применяется на месте ([KeepAwake.syncSilence]):
     *  включили во время чтения — поток встаёт сразу, без перезапуска книги. */
    private fun addCheck(
        title: String,
        hint: String,
        key: String,
        def: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val v = CheckBox(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            isChecked = prefs.getBoolean(key, def)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange(checked)
            }
        }
        binding.content.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            },
        )
    }

    /** Строка с подсказкой второй строкой: название обычным, пояснение — мельче и
     *  серым. Общее для строк-действий и галочки, поэтому вынесено сюда. */
    private fun withHint(title: String, hint: String?): CharSequence =
        SpannableStringBuilder().apply {
            append(title)
            if (hint != null) {
                append("\n")
                val start = length
                append(hint)
                setSpan(RelativeSizeSpan(0.76f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(0xFF9AA0A6.toInt()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    /** В журнал — и что выбрано, и что вышло на самом деле: если чтение сейчас не
     *  идёт, поток остаётся выключенным до его начала, и это видно из строки. */
    private fun logSilence(on: Boolean) {
        Diag.log(
            this, "power",
            "«Не засыпать»: тихий поток ${if (on) "включён" else "выключен"} галочкой; " +
                "сейчас ${if (SilentKeepAlive.isOn) "идёт" else "не идёт"} " +
                "(не идёт — значит чтение стоит)"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Те же настройки чтения, что и у читалки: `MainActivity.prefs` и
         *  [KeepAwake] читают этот же файл. */
        private const val PREFS = "reader"
    }
}
