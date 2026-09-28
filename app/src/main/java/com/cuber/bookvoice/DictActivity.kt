package com.cuber.bookvoice

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно «Словарь произношения» (27.09.2026) — правила подмены текста перед
 * синтезом. Вход из Настройки → «Голос», строкой внизу раздела.
 *
 *  Строки окна: главная галочка (по умолчанию словарь ВЫКЛЮЧЕН), затем части:
 *  «Общие правила» — они работают на любом движке, и по строке на каждый движок,
 *  который упомянут в правилах. Часть открывается, как подраздел: внутри её
 *  правила, добавление, правка и удаление. Выключатель есть и у части: одно
 *  касание снимает с работы полсотни правил, не трогая их собственные галочки.
 *
 *  Зачем части. Хитрое написание, подобранное на слух на одном движке, на другом
 *  звучит иначе — про это прямым текстом написано и в готовых словарях @Voice.
 *  Поэтому правило помнит, каким движкам оно адресовано, а правила для движка,
 *  которого на телефоне нет, просто лежат без дела.
 */
class DictActivity : RowsActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override val contentRoot: ViewGroup get() = binding.content

    /** Открытая часть: null — корень окна, "" — общие правила, [GROUP_BOOKS] —
     *  правила из книг, иначе пакет движка. */
    private var group: String? = null

    /** Импорт словаря: выбор любого файла с правилами. */
    private val pickDict = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> importPicked(uri) }

    /** Выгрузка словаря: создание файла в выбранном месте. */
    private val createDict = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> exportTo(uri) }

    override fun buildSection(intent: Intent?) {
        setTitle(getString(R.string.dict_title))
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.btnMore.visibility = View.GONE
        binding.btnBack.setOnClickListener { rootBack() }
        buildRows()
    }

    override fun resumeSection(arrival: Boolean) {
        // Возврат из правки правила: список мог измениться — пересобираем.
        buildRows()
        if (arrival) binding.tvTitle.announceForAccessibility(getString(R.string.dict_title))
    }

    /** «Назад» внутри части возвращает в корень окна, а не закрывает его. */
    override fun onSectionBackKey(): Boolean {
        if (group != null) {
            group = null
            buildRows()
            return true
        }
        return false
    }

    override fun disposeSection() = Unit

    private fun prefs() = getSharedPreferences("reader", MODE_PRIVATE)

    private fun buildRows() {
        contentRoot.removeAllViews()
        val g = group
        binding.tvTitle.text = if (g == null) getString(R.string.dict_title) else groupTitle(g)
        if (g == null) {
            buildRoot(Dict.load(this))
        } else if (g == GROUP_BOOKS) {
            buildBooks()
        } else {
            buildOne(Dict.load(this), g)
        }
    }

    /** Часть «Правила из книг»: список собранных по книгам правил и две команды —
     *  убрать правила книг, которых нет на полке, и убрать все сразу. */
    private fun buildBooks() {
        val rules = Dict.loadBooks(this)
        val orphans = Dict.orphans(this)
        if (rules.isEmpty()) {
            addHint(getString(R.string.dict_books_empty))
            return
        }
        addHint(getString(R.string.dict_books_hint))
        if (orphans.isNotEmpty()) {
            addRow(getString(R.string.dict_books_drop_orphans, orphans.size), null, tag = "orph") {
                val n = orphans.size
                for (r in orphans) Dict.removeBookRule(this, r)
                Diag.log(this, "dict", "убрал правила книг, которых нет на полке: $n")
                toast(getString(R.string.dict_books_dropped, n))
                buildRows()
            }
        }
        addRow(getString(R.string.dict_books_drop_all), null, tag = "dropall") {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dict_books_drop_all)
                .setMessage(getString(R.string.dict_books_drop_all_ask, rules.size))
                .setPositiveButton(R.string.dict_rule_delete) { _, _ ->
                    val n = Dict.dropAllBooks(this)
                    Diag.log(this, "dict", "убрал все книжные правила: $n")
                    toast(getString(R.string.dict_books_dropped, n))
                    buildRows()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        val onShelf = BookStore.all(this).map { Dict.key(it.name) }.toHashSet()
        for ((i, r) in rules.withIndex()) {
            val book = r.book.orEmpty()
            val where = if (Dict.key(book) in onShelf) book else getString(R.string.dict_books_orphan, book)
            addRuleCheck(i, r, bookRule = true, prefix = where)
        }
    }

    private fun buildRoot(rules: List<Dict.Rule>) {
        addHint(getString(R.string.dict_hint))
        // Подсказка зависит от состояния (28.09.2026): раньше тут стоял неподвижный
        // текст «пока выключен, правила не применяются вовсе», и после включения
        // Серж слышал то же самое — галка включена, а строка говорит «выключен».
        addCheck(
            getString(R.string.dict_on),
            null,
            Dict.KEY_ON,
            false,
            tag = "on",
            hintOf = { on ->
                getString(if (on) R.string.dict_on_hint_on else R.string.dict_on_hint_off)
            },
        ) { on ->
            Diag.log(this, "dict", "словарь " + (if (on) "включён" else "выключен") + ", правил ${Dict.load(this).size}")
        }

        val (common, byEngine) = Dict.counts(rules)
        addRow(
            getString(R.string.dict_group_common, common),
            getString(R.string.dict_group_common_hint),
            strong = true,
            tag = "g_common",
        ) { openGroup("") }

        val labels = Dict.installedEngines(this)
        for ((pkg, n) in byEngine) {
            val name = labels[pkg] ?: pkg
            val state = when {
                !labels.containsKey(pkg) -> getString(R.string.dict_group_no_engine)
                Dict.groupOn(this, pkg) -> getString(R.string.dict_group_on)
                else -> getString(R.string.dict_group_off)
            }
            addRow(
                getString(R.string.dict_group_engine, name, n),
                state,
                strong = true,
                tag = "g_" + pkg,
            ) { openGroup(pkg) }
        }

        val kb = (Dict.file(this).length() + 1023) / 1024
        addHint(getString(R.string.dict_total, rules.size, kb))
        // Книжные правила — отдельной частью (28.09.2026): они собраны по книгам,
        // лежат своим файлом и уходят вместе с книгой. Владельцу важно видеть их
        // число и уметь снести разом — «мало ли книга исчезла как-то некорректно».
        val books = Dict.loadBooks(this)
        if (books.isNotEmpty()) {
            addRow(
                getString(R.string.dict_books_group, books.size),
                getString(R.string.dict_books_group_hint),
                strong = true,
                tag = "g_books",
            ) { openGroup(GROUP_BOOKS) }
        }
        addRow(getString(R.string.dict_add), null, strong = true) { openEditor(-1, "") }
        // Импорт и выгрузка (28.09.2026): готовые словари @Voice/Librera читаются
        // тем же форматом, а свой словарь можно унести на другое устройство.
        addRow(getString(R.string.dict_import), getString(R.string.dict_import_hint)) { importDict() }
        addRow(getString(R.string.dict_export), getString(R.string.dict_export_hint)) { exportDict() }
    }

    /** Импорт: выбираем файл словаря и ДОБАВЛЯЕМ его правила к своим. Файл читаем
     *  в фоне (он бывает на сотни килобайт), разбор и слияние — там же. */
    private fun importDict() {
        runCatching {
            pickDict.launch(arrayOf("text/plain", "text/*", "application/octet-stream", "*/*"))
        }.onFailure {
            Diag.log(this, "dict", "не открылся выбор файла словаря: ${it.message}")
            toast(getString(R.string.dict_import_fail))
        }
    }

    private fun importPicked(uri: android.net.Uri?) {
        if (uri == null) return
        toast(getString(R.string.dict_import_reading))
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val rules = bytes?.let { Dict.parse(Dict.decode(it)) } ?: emptyList()
            val res = if (rules.isEmpty()) null else Dict.merge(this, rules)
            runOnUiThread {
                when {
                    bytes == null -> {
                        Diag.log(this, "dict", "файл словаря не прочитался: $uri")
                        toast(getString(R.string.dict_import_fail))
                    }
                    res == null -> {
                        Diag.log(this, "dict", "в файле словаря правил не нашлось: $uri (${bytes.size} байт)")
                        toast(getString(R.string.dict_import_empty))
                    }
                    else -> {
                        val (added, skipped) = res
                        Diag.log(
                            this, "dict",
                            "импорт словаря: добавлено $added, повторов $skipped, всего ${Dict.load(this).size}"
                        )
                        toast(getString(R.string.dict_import_done, added, skipped))
                        buildRows()
                    }
                }
            }
        }.start()
    }

    /** Выгрузка: наш словарь в файл, который владелец сохранит куда хочет —
     *  в «Загрузки», на Диск или отправит себе. */
    private fun exportDict() {
        runCatching { createDict.launch(Dict.EXPORT_NAME) }.onFailure {
            Diag.log(this, "dict", "не открылось сохранение словаря: ${it.message}")
            toast(getString(R.string.dict_export_fail))
        }
    }

    private fun exportTo(uri: android.net.Uri?) {
        if (uri == null) return
        val rules = Dict.load(this)
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(Dict.serialize(rules).toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        if (ok) {
            Diag.log(this, "dict", "словарь выгружен: правил ${rules.size} → $uri")
            toast(getString(R.string.dict_export_done, rules.size))
        } else {
            Diag.log(this, "dict", "словарь не выгрузился: $uri")
            toast(getString(R.string.dict_export_fail))
        }
    }

    private fun buildOne(rules: List<Dict.Rule>, g: String) {
        val picked = rules.withIndex().filter { (_, r) ->
            if (g.isEmpty()) r.engines.isEmpty() else r.engines.contains(g)
        }

        // Выключатель части: одно касание снимает с работы всю часть, а галочки
        // самих правил остаются как были.
        if (g.isNotEmpty()) {
            val labels = Dict.installedEngines(this)
            if (!labels.containsKey(g)) addHint(getString(R.string.dict_group_no_engine_hint))
            addCheck(
                getString(R.string.dict_group_switch, labels[g] ?: g),
                getString(R.string.dict_group_switch_hint),
                Dict.KEY_GROUP + g,
                true,
                tag = "gs",
            ) { on ->
                Diag.log(this, "dict", "часть " + (labels[g] ?: g) + ": " + (if (on) "включена" else "выключена"))
            }
        }

        if (picked.isEmpty()) {
            addHint(getString(R.string.dict_empty))
        } else {
            // 28.09.2026, просьба Сержа: переключать правило — самое частое
            // действие, поэтому у каждого правила стоит свой флажок, а правка
            // ушла на долгое нажатие. Подсказка одна на всю часть, а не строка под
            // каждым правилом: иначе список читается вдвое дольше.
            addHint(getString(R.string.dict_rule_toggle_hint))
            addRow(getString(R.string.dict_all_on), null, tag = "all_on") { setAll(g, false) }
            addRow(getString(R.string.dict_all_off), null, tag = "all_off") { setAll(g, true) }
            for ((i, r) in picked) addRuleCheck(i, r)
        }
        addRow(getString(R.string.dict_add), null, strong = true) { openEditor(-1, g) }
    }

    /**
     * Строка правила с флажком: и переключатель, и название правила — один
     * элемент. Двойной тап переключает, долгое нажатие открывает правило для
     * правки; в меню «Действия» диктора на этой же строке есть «Открыть правило».
     *
     * При переключении говорим ровно два слова: состояние и название правила
     * (просьба Сержа 28.09.2026). Поэтому класс узла подменяем на TextView —
     * иначе TalkBack добавит «флажок» и свой текст, — а состояние называем сами
     * в самом тексте строки: так оно слышно и когда просто встаёшь на правило.
     */
    private fun addRuleCheck(
        index: Int,
        r: Dict.Rule,
        bookRule: Boolean = false,
        prefix: String? = null,
    ) {
        val box = CheckBox(this).apply {
            val name = prefix?.let { "$it: " } ?: ""
            text = "${index + 1}. $name${ruleText(r, bookRule)}"
            textSize = 17f
            isChecked = !r.off
            isFocusable = true
            setPadding(dp(8), dp(6), dp(12), dp(6))
            tag = r
            ViewCompat.setScreenReaderFocusable(this, true)
            setOnCheckedChangeListener { _, on ->
                val cur = tag as? Dict.Rule ?: r
                val fresh = cur.copy(off = !on)
                if (bookRule) Dict.replaceBookRule(this@DictActivity, cur, fresh)
                else Dict.replace(this@DictActivity, cur, fresh)
                tag = fresh
                Diag.log(
                    this@DictActivity, "dict",
                    (if (bookRule) "книжное правило «" else "правило «") +
                        "${fresh.find}» " + (if (fresh.off) "выключено" else "включено")
                )
                // Сами НЕ объявляем: состояние флажка диктор называет своим словом
                // («отмечено» / «не отмечено»), и он же его произносит при
                // переключении. Раньше мы добавляли своё «Включено», и Серж слышал
                // состояние дважды (просьба 28.09.2026: «надо, чтобы читал только
                // „отмечено“»).
            }
            setOnLongClickListener {
                openEditor(index, "", bookRule)
                true
            }
            // Из объявления убираем только системное «долгое нажатие»: вместо него
            // действие «Открыть правило» лежит в меню «Действия» диктора. Ни класс
            // узла, ни состояние не подменяем — флажок остаётся флажком, и диктор
            // говорит про него своим словом.
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_OPEN_RULE,
                            getString(R.string.dict_rule_open),
                        )
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == ACTION_OPEN_RULE) {
                        openEditor(index, "", bookRule)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
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
    }

    /** Включить или выключить всю часть разом и сказать, сколько поменялось. */
    private fun setAll(g: String, off: Boolean) {
        val n = Dict.setGroupOff(this, g, off)
        Diag.log(this, "dict", "часть «${groupTitle(g)}»: " + (if (off) "выключено" else "включено") + " правил $n")
        if (n == 0) {
            toast(getString(R.string.dict_all_none))
            return
        }
        toast(getString(if (off) R.string.dict_all_off_done else R.string.dict_all_on_done, n))
        buildRows()
    }

    private fun groupTitle(g: String): String = when (g) {
        "" -> getString(R.string.dict_group_common_title)
        GROUP_BOOKS -> getString(R.string.dict_books_group_title)
        else -> getString(R.string.dict_group_engine_title, Dict.installedEngines(this)[g] ?: g)
    }

    private fun openGroup(g: String) {
        group = g
        buildRows()
        binding.tvTitle.announceForAccessibility(binding.tvTitle.text)
    }

    private fun openEditor(index: Int, engine: String, book: Boolean = false) {
        startActivity(
            Intent(this, DictRuleActivity::class.java)
                .putExtra(DictRuleActivity.EXTRA_INDEX, index)
                .putExtra(DictRuleActivity.EXTRA_ENGINE, engine)
                .putExtra(DictRuleActivity.EXTRA_BOOK, book)
        )
    }

    /** Строка правила для списка. Книжные правила — образцы для поиска, и читать
     *  их как регулярное выражение нельзя: показываем понятными словами. */
    private fun ruleText(r: Dict.Rule, bookRule: Boolean): String =
        if (bookRule && r.regex) DictSmart.humanLine(r.find) else r.line()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        /** Свой номер действия в меню «Действия» диктора: «Открыть правило».
         *  Нумерация как у [ParagraphView.ACTION_ID_DICT] — свои номера выше
         *  системных, чтобы не столкнуться с чужими. */
        const val ACTION_OPEN_RULE = 0x01000010

        /** Значение «открытой части» для правил из книг: пакет движка так
         *  выглядеть не может, поэтому восклицательный знак безопасен. */
        const val GROUP_BOOKS = "!books"
    }
}
