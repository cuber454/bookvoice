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

    /** Правка правила: окно правила сообщает, что именно сохранило, — по этому
     *  ответу мы после возврата встаём диктором на это правило (29.09.2026,
     *  просьба Сержа: новое правило уезжает в конец списка, искать его вслепую
     *  приходится долго). */
    private val editRule = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        pendingFind = res.data?.getStringExtra(DictRuleActivity.RESULT_FIND)
        pendingRepl = res.data?.getStringExtra(DictRuleActivity.RESULT_REPL).orEmpty()
        pendingBook = res.data?.getStringExtra(DictRuleActivity.RESULT_BOOK).orEmpty()
    }

    /** Что сохранило окно правила: ищем по этим трём строкам — они и есть правило. */
    private var pendingFind: String? = null
    private var pendingRepl: String = ""
    private var pendingBook: String = ""

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
        val find = pendingFind
        if (find != null) {
            pendingFind = null
            focusRule(find, pendingRepl, pendingBook)
        }
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
        // Тот же порядок, что и в частях словаря: новые правила сверху.
        for ((i, r) in rules.withIndex().reversed()) {
            val book = r.book.orEmpty()
            // Правило из книжной части без привязки к книге (его завели вручную в
            // «Правилах из книг» и не ограничили книгой) работает везде — так и
            // называем, а не «книги нет на полке».
            val where = if (book.isBlank()) getString(R.string.dict_books_all)
            else if (Dict.key(book) in onShelf) book
            else getString(R.string.dict_books_orphan, book)
            addRuleCheck(i, r, bookRule = true, prefix = where)
        }
    }

    private fun buildRoot(rules: List<Dict.Rule>) {
        addHint(getString(R.string.dict_hint))
        // Включение словаря — первая строка окна и ЕДИНСТВЕННЫЙ его выключатель
        // (29.09.2026, разбор Сержа «галочка включается в двух местах»: галочка
        // всегда была одна, а вторая строка в «Голосе» — это вход в это окно).
        // Подсказка — только счёт правил: «отмечено» или «не отмечено» диктор и так
        // скажет сам, а прежний текст занимал две строки на экране.
        addCheck(
            getString(R.string.dict_on),
            null,
            Dict.KEY_ON,
            false,
            tag = "on",
            hintOf = { getString(R.string.dict_on_hint, rules.size) },
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
            // Состояние части — счётом, как и у флажка «Отметить всё»: по нему видно,
            // работает ли часть целиком или правила повыключены по одному.
            val onCount = rules.count { !it.off && it.engines.contains(pkg) }
            val state = when {
                !labels.containsKey(pkg) -> getString(R.string.dict_group_no_engine)
                else -> getString(R.string.dict_group_count, onCount, n)
            }
            addRow(
                getString(R.string.dict_group_engine, name, n),
                state,
                strong = true,
                tag = "g_" + pkg,
            ) { openGroup(pkg) }
        }

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

        // Переключателя части здесь больше нет (29.09.2026, решение Сержа): его
        // работу делает флажок «Отметить всё» ниже, а два выключателя в одной части
        // только путали. Часть собирается по признаку движка у самого правила, и
        // этот отбор никуда не делся.
        if (g.isNotEmpty() && !Dict.installedEngines(this).containsKey(g)) {
            addHint(getString(R.string.dict_group_no_engine_hint))
        }

        if (picked.isEmpty()) {
            addHint(getString(R.string.dict_empty))
        } else {
            // 28.09.2026, просьба Сержа: переключать правило — самое частое
            // действие, поэтому у каждого правила стоит свой флажок, а правка
            // ушла на долгое нажатие. Подсказка одна на всю часть, а не строка под
            // каждым правилом: иначе список читается вдвое дольше.
            addHint(getString(R.string.dict_rule_toggle_hint))
            // Один флажок «Отметить всё» вместо двух строк «включить все правила
            // части» и «выключить все правила части» (просьба Сержа 29.09.2026).
            addAllCheck(g, picked.count { !it.value.off }, picked.size)
            // Новые правила показываем СВЕРХУ (29.09.2026, просьба Сержа): правило,
            // которое только что завёл, иначе лежит в самом конце сотни строк.
            // Номер правила при этом не меняется — он указывает место в словаре,
            // а не место в списке.
            for ((i, r) in picked.reversed()) addRuleCheck(i, r)
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
                    // Удаление — прямо из списка (29.09.2026, вопрос Сержа «как
                    // удалить правило»): раньше убрать правило можно было только
                    // внутри самого правила, и найти это вслепую было негде.
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_DELETE_RULE,
                            getString(R.string.dict_rule_delete_action),
                        )
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == ACTION_OPEN_RULE) {
                        openEditor(index, "", bookRule)
                        return true
                    }
                    if (action == ACTION_DELETE_RULE) {
                        confirmDeleteRule(host.tag as? Dict.Rule ?: r, bookRule)
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

    /** Флажок «Отметить всё» в части словаря: отметил — работают все правила части,
     *  снял — ни одно (просьба Сержа 29.09.2026: «просто сделай чекбокс отметить
     *  всё»; до этого тут стояли две строки-действия «включить/выключить все
     *  правила части», и это было лишним).
     *
     *  Состояние флажка в настройках НЕ храним: оно и так видно по самим правилам —
     *  отмечен, когда включены все, снят, когда выключено хоть одно. Второй строкой
     *  держим счёт «включено N из M»: по нему слышно, что вышло после касания, и
     *  видно, что часть отмечена не полностью. */
    private fun addAllCheck(g: String, onCount: Int, total: Int): CheckBox {
        val title = getString(R.string.dict_all_mark)
        val box = CheckBox(this).apply {
            text = withHint(title, getString(R.string.dict_all_mark_hint, onCount, total))
            textSize = 17f
            isChecked = onCount == total
            isFocusable = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            tag = ALL_TAG
            ViewCompat.setScreenReaderFocusable(this, true)
            setOnCheckedChangeListener { _, checked ->
                val n = Dict.setGroupOff(this@DictActivity, g, off = !checked)
                Diag.log(
                    this@DictActivity, "dict",
                    "часть «${groupTitle(g)}»: " + (if (checked) "включено" else "выключено") +
                        " правил $n (флажок «отметить всё»)"
                )
                toast(
                    getString(
                        if (checked) R.string.dict_all_on_done else R.string.dict_all_off_done,
                        n,
                    )
                )
                // Правила перерисовались — их галочки изменились вместе с этим.
                buildRows()
                focusAllCheck()
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
        return box
    }

    /** После пересборки вернуть диктора на флажок «Отметить всё»: он стоял на нём,
     *  когда его касался, а пересборка строку убивает. Невидимую цель перенос
     *  фокуса пропускает ([TabNav.a11yFocus]), поэтому один повтор — как в
     *  [focusRule]. */
    private fun focusAllCheck(retry: Boolean = true) {
        val v = contentRoot.findViewWithTag<View>(ALL_TAG) ?: return
        if (!v.isShown && retry) {
            v.postDelayed({ focusAllCheck(retry = false) }, 120)
            return
        }
        TabNav.a11yFocus(v)
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
        editRule.launch(
            Intent(this, DictRuleActivity::class.java)
                .putExtra(DictRuleActivity.EXTRA_INDEX, index)
                .putExtra(DictRuleActivity.EXTRA_ENGINE, engine)
                .putExtra(DictRuleActivity.EXTRA_BOOK, book)
        )
    }

    /** Встать диктором на сохранённое правило. Ищем строку по самому правилу:
     *  искатель по номеру не годится — правило могло переехать в другую часть
     *  (сменили движки) или в книжные правила, и тогда его тут просто нет. */
    private fun focusRule(find: String, repl: String, book: String, retry: Boolean = true) {
        for (i in 0 until contentRoot.childCount) {
            val v = contentRoot.getChildAt(i) ?: continue
            val r = v.tag as? Dict.Rule ?: continue
            if (r.find != find || r.repl != repl || r.book.orEmpty() != book) continue
            if (!v.isShown && retry) {
                // Список пересобран только что: до первой раскладки строка ещё не
                // видна, а перенос фокуса невидимую цель пропускает ([TabNav.a11yFocus]).
                v.postDelayed({ focusRule(find, repl, book, retry = false) }, 120)
                return
            }
            Diag.log(this, "dict", "встаю на сохранённое правило: «$find» → «$repl»")
            TabNav.a11yFocus(v)
            return
        }
    }

    /** Удалить правило из списка, не заходя в него. Сообщение диалога — само
     *  правило: на слух «Удалить» одинаково у всех строк, а вслепую надо знать,
     *  что именно уходит. */
    private fun confirmDeleteRule(r: Dict.Rule, bookRule: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_rule_delete_action)
            .setMessage(ruleText(r, bookRule))
            .setPositiveButton(R.string.dict_rule_delete) { _, _ ->
                if (bookRule) Dict.removeBookRule(this, r) else Dict.remove(this, r)
                Diag.log(
                    this, "dict",
                    (if (bookRule) "книжное правило удалено: «" else "правило удалено: «") +
                        "${r.find}» (замена была «${r.repl}»)"
                )
                toast(getString(R.string.dict_rule_deleted))
                buildRows()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

        /** Второе наше действие там же: «Удалить правило» (29.09.2026). */
        const val ACTION_DELETE_RULE = 0x01000011

        /** Значение «открытой части» для правил из книг: пакет движка так
         *  выглядеть не может, поэтому восклицательный знак безопасен. */
        const val GROUP_BOOKS = "!books"

        /** Метка флажка «Отметить всё»: по ней окно возвращает на него фокус
         *  после пересборки списка. */
        const val ALL_TAG = "all_mark"
    }
}
