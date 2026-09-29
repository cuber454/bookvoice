package com.cuber.bookvoice

import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.cuber.bookvoice.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Окно правила словаря произношения (27.09.2026): что искать, на что менять,
 * регулярное выражение, каким движкам адресовано.
 *
 *  Главное здесь — не поля, а подбор написания. Вслепую ни знак ударения не
 *  поставить, ни «ё» не вписать, поэтому приложение само составляет варианты
 *  («Подобрать произношение») и проговаривает их по номерам; человек выбирает
 *  тот, что звучит правильно, а выбранное тут же произносится движком. Порядок
 *  вариантов зависит от движка: тому, который знака ударения не понимает
 *  (multiTTS на Microsoft-голосах рвёт слово на знаке), варианты со знаком не
 *  предлагаются вовсе.
 *
 *  «По буквам» — отдельная кнопка для сокращений: «СВО» → «эс-вэ-о». Проверено
 *  на слух 27.09.2026: через пробел движок сливает буквы в одно слово, через
 *  дефис читает верно.
 *
 *  Из книги приходим по действию «Добавить в словарь» (29.09.2026): предложение
 *  сразу стоит в поле «Что искать» целой строкой — чаще всего правило и нужно на
 *  всю строку (разделители вида «* * *»). Если строку надо сузить, рядом есть
 *  «Слова из предложения»: слова отмечаются флажками, а кнопка «Всё предложение»
 *  возвращает целую строку.
 */
class DictRuleActivity : RowsActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_ENGINE = "engine"

        /** Текст предложения, из которого пришли («Добавить в словарь» из книги):
         *  по нему приложение предлагает слова флажками, чтобы не диктовать. */
        const val EXTRA_SENTENCE = "sentence"

        /** Правят КНИЖНОЕ правило (28.09.2026): такие правила лежат отдельным
         *  файлом, поэтому и читать, и писать их надо оттуда, а не из словаря. */
        const val EXTRA_BOOK = "book_rule"

        /** Метка строки «Кому годится правило». */
        private const val SCOPE_TAG = "scope"

        /** Ответ списку правил: что именно сохранили (29.09.2026). По нему список
         *  после возврата встаёт диктором на это правило. Ищем по трём строкам
         *  самого правила: номера строк после сохранения сдвигаются, а правило
         *  могло ещё и переехать в другую часть. */
        const val RESULT_FIND = "saved_find"
        const val RESULT_REPL = "saved_repl"
        const val RESULT_BOOK = "saved_book"
    }

    private lateinit var binding: ActivitySettingsBinding

    override val contentRoot: ViewGroup get() = binding.content

    private var index = -1
    private var engines: List<String> = emptyList()
    private var regex = false

    /** Правят книжное правило: списки и запись — из книжного файла. */
    private var bookRule = false

    /** Кому годится правило: null — всем книгам, иначе имя файла книги. Правится
     *  строкой «Кому» (29.09.2026, вопрос Сержа: как сделать правило только для
     *  одной его книги). */
    private var bindBook: String? = null

    /** Имя книги, которой правило можно ограничить: у книжного правила — его
     *  книга, у нового — последняя открытая. Пусто — ограничивать нечем, строки
     *  выбора в окне не будет. */
    private var bookName: String? = null

    /** Строка «Кому»: после переключения меняем текст на месте, чтобы диктор
     *  сказал новое значение (пересборка строки его теряет). */
    private var scopeRow: TextView? = null

    private var findField: EditText? = null
    private var replField: EditText? = null
    private var regexCheck: CheckBox? = null

    /** Пробный синтезатор — только для проговаривания, книгу не трогает. */
    private var probe: SpeechPlayer? = null

    /** Предложение, из которого пришли («Добавить в словарь» из книги). Пусто —
     *  правило заводится вручную. */
    private var sentence = ""

    /** Отмеченные слова предложения (по номерам) — из них собирается «что искать». */
    private val pickedWords = LinkedHashSet<Int>()

    /** Идёт откат галочки: слушатель переставляется программно, реагировать не надо. */
    private var reverting = false

    override fun buildSection(intent: Intent?) {
        index = intent?.getIntExtra(EXTRA_INDEX, -1) ?: -1
        sentence = intent?.getStringExtra(EXTRA_SENTENCE).orEmpty()
        bookRule = intent?.getBooleanExtra(EXTRA_BOOK, false) ?: false
        val fromGroup = intent?.getStringExtra(EXTRA_ENGINE).orEmpty()
        val rules = if (bookRule) Dict.loadBooks(this) else Dict.load(this)
        val rule = if (index in rules.indices) rules[index] else null
        if (rule != null) {
            regex = rule.regex
            engines = rule.engines
        } else if (fromGroup.isNotEmpty()) {
            engines = listOf(fromGroup)
        }
        // Кому правило годится: у правки — как было, у нового — пока всем книгам.
        // Имя книги берём у читалки, а если процесс уже перезапускался — из
        // настроек (туда его кладёт MainActivity при открытии книги).
        bindBook = rule?.book
        bookName = rule?.book ?: lastBookName()
        setTitle(getString(if (rule == null) R.string.dict_rule_new else R.string.dict_rule_title))
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
        buildRows(rule)
    }

    override fun resumeSection(arrival: Boolean) = Unit

    override fun onSectionBackKey(): Boolean = false

    override fun disposeSection() {
        probe?.shutdown()
        probe = null
    }

    private fun buildRows(rule: Dict.Rule?) {
        contentRoot.removeAllViews()
        scopeRow = null
        addHint(getString(R.string.dict_rule_hint))

        // Из книги приходим с готовым предложением, и «что искать» сразу заполнено
        // целой строкой (просьба Сержа 29.09.2026: «чтобы целая строка уже стояла в
        // поле поиска»). Нужно сузить — рядом строка «Слова из предложения», там
        // слова отмечаются флажками. Тире в начале реплики срезаем: движок получает
        // текст УЖЕ без него (см. ReaderEngine.dictText), и правило с тире не
        // нашлось бы никогда.
        val initialFind = rule?.find ?: sentenceForFind()
        findField = addField(getString(R.string.dict_rule_find), initialFind, null)
        replField = addField(getString(R.string.dict_rule_repl), rule?.repl.orEmpty(), null)

        // Пришли из книги — рядом со словами предложения: отмечаешь нужные
        // флажками, и «что искать» собирается само, буква в букву как в книге.
        if (sentence.isNotEmpty() && rule == null) {
            addRow(
                getString(R.string.dict_from_sentence),
                getString(R.string.dict_from_sentence_hint, sentenceForFind()),
                strong = true,
            ) { pickFromSentence() }
        }

        regexCheck = addLocalCheck(
            getString(R.string.dict_rule_regex),
            getString(R.string.dict_rule_regex_hint),
            regex,
        ) { regex = it }

        addRow(getString(R.string.dict_rule_engines, enginesText()), null, strong = true) { pickEngines() }
        addScopeRow()
        addRow(getString(R.string.dict_rule_letters), getString(R.string.dict_rule_letters_hint)) { makeLetters() }
        addRow(getString(R.string.dict_rule_pick), getString(R.string.dict_rule_pick_hint)) { pickPronunciation() }
        addRow(getString(R.string.dict_rule_check), getString(R.string.dict_rule_check_hint)) { check() }
        addRow(getString(R.string.dict_save), null, strong = true) { save(rule) }
        if (rule != null) {
            addRow(getString(R.string.dict_rule_delete), null, strong = true) { confirmDelete(rule) }
        }
    }

    // ——— Поля ———

    private fun addField(label: String, value: String, hint: String?): EditText {
        val tv = TextView(this).apply {
            text = if (hint == null) label else withHint(label, hint)
            textSize = 15f
            setTextColor(Palette.DIM)
            setPadding(dp(12), dp(6), dp(12), dp(2))
        }
        contentRoot.addView(tv)
        val et = EditText(this).apply {
            this.setText(value)
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT
            contentDescription = label
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        contentRoot.addView(
            et,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) },
        )
        return et
    }

    /** Галочка этого окна: она не настройка, а часть правила, поэтому в prefs не
     *  пишется — в отличие от [addCheck]. */
    private fun addLocalCheck(
        title: String,
        hint: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): CheckBox {
        val cb = CheckBox(this).apply {
            text = withHint(title, hint)
            textSize = 17f
            isChecked = checked
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        contentRoot.addView(cb)
        return cb
    }

    // ——— Кому годится правило ———

    /** Строка «Кому годится правило»: всем книгам или только одной. Её нет, когда
     *  книгу нечем назвать (книга ещё не открывалась): предлагать «только для
     *  книги» без имени книги — обман. */
    private fun addScopeRow() {
        if (bookName == null) return
        scopeRow = addRow(scopeTitle(), scopeHint(), strong = true, tag = SCOPE_TAG) { toggleScope() }
    }

    private fun scopeTitle(): String =
        if (bindBook == null) getString(R.string.dict_scope_all)
        else getString(R.string.dict_scope_book, bindBook)

    private fun scopeHint(): String =
        if (bindBook == null) getString(R.string.dict_scope_all_hint, bookName.orEmpty())
        else getString(R.string.dict_scope_book_hint)

    /** Касание переносит правило между «всем книгам» и «только этой». Пометку
     *  показываем словами и проговариваем сами: строка остаётся на месте, фокус
     *  с неё не уходит. */
    private fun toggleScope() {
        val name = bookName ?: return
        bindBook = if (bindBook == null) name else null
        scopeRow?.let { updateRow(it, scopeTitle(), scopeHint()) }
        Diag.log(
            this, "dict",
            "правило: кому — " + (if (bindBook == null) "все книги" else "только книга «$bindBook»")
        )
    }

    /** Имя последней открытой книги: сперва у читалки (процесс жив), иначе из
     *  настроек — туда его кладёт MainActivity при открытии книги. */
    private fun lastBookName(): String? =
        ReaderEngine.currentName?.takeIf { it.isNotBlank() }
            ?: prefs().getString(MainActivity.KEY_LAST_BOOK_NAME, null)?.takeIf { it.isNotBlank() }

    // ——— Слова из предложения («Добавить в словарь» из книги) ———

    /** Список слов предложения флажками. Отмечать можно только соседние:
     *  правило ищет текст как он написан, и «Вася … домой» через пропущенное
     *  слово не найдёт в книге ничего. Про пропуск говорим прямо, иначе
     *  человек будет думать, что починил, а голос не изменится. */
    private fun pickFromSentence() {
        val words = Dict.words(sentenceForFind())
        if (words.isEmpty()) {
            toastText(getString(R.string.dict_need_word))
            return
        }
        // Флажки начинаем с пустого набора: человек отмечает ровно те слова,
        // которые хочет оставить в правиле (целая строка уже стоит в поле).
        pickedWords.clear()
        val boxes = ArrayList<CheckBox>(words.size)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, w) in words.withIndex()) {
            val cb = CheckBox(this).apply {
                text = w
                textSize = 17f
                setPadding(dp(16), dp(8), dp(16), dp(8))
            }
            cb.setOnCheckedChangeListener { _, on ->
                if (reverting) return@setOnCheckedChangeListener
                if (on) {
                    pickedWords.add(i)
                    if (!inARow()) {
                        pickedWords.remove(i)
                        reverting = true
                        cb.isChecked = false
                        reverting = false
                        toastText(getString(R.string.dict_not_in_row))
                        return@setOnCheckedChangeListener
                    }
                } else {
                    pickedWords.remove(i)
                }
                val text = pickedText(words)
                findField?.setText(text)
                speak(text)
            }
            boxes.add(cb)
            col.addView(cb)
        }
        val scroll = ScrollView(this).apply { addView(col) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_from_sentence)
            .setView(scroll)
            .setPositiveButton(R.string.dict_save) { _, _ ->
                findField?.setText(pickedText(words))
            }
            // Вернуть целую строку одним касанием: сузил галочками и передумал —
            // иначе предложение пришлось бы набирать заново (оно длинное, вслепую
            // это мучение).
            .setNeutralButton(R.string.dict_from_sentence_all) { _, _ ->
                pickedWords.clear()
                reverting = true
                boxes.forEach { it.isChecked = false }
                reverting = false
                val whole = sentenceForFind()
                findField?.setText(whole)
                speak(whole)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Что писать в «что искать» по отмеченным словам: ничего не отмечено —
     *  оставляем целую строку. */
    private fun pickedText(words: List<String>): String =
        if (pickedWords.isEmpty()) sentenceForFind() else assembled(words)

    /** Предложение для поля «что искать»: без ведущего тире и лишних пробелов.
     *  Тире срезаем потому, что движок получает текст уже без него
     *  (см. ReaderEngine.dictText), и правило с тире в книге не нашлось бы. */
    private fun sentenceForFind(): String {
        var t = sentence.trim()
        while (t.isNotEmpty() && (t[0] == '—' || t[0] == '–' || t[0] == '−')) {
            t = t.substring(1).trimStart()
        }
        return t
    }

    /** Отмеченные слова идут подряд, без пропусков. */
    private fun inARow(): Boolean {
        if (pickedWords.size <= 1) return true
        val sorted = pickedWords.sorted()
        for (k in 1 until sorted.size) if (sorted[k] != sorted[k - 1] + 1) return false
        return true
    }

    private fun assembled(words: List<String>): String =
        pickedWords.sorted().mapNotNull { words.getOrNull(it) }.joinToString(" ")

    // ——— Движки правила ———

    private fun enginesText(): String {
        if (engines.isEmpty()) return getString(R.string.dict_engines_all)
        val labels = Dict.installedEngines(this)
        return engines.joinToString(", ") { labels[it] ?: it }
    }

    private fun engineChoices(): List<String> {
        val labels = Dict.installedEngines(this)
        val out = LinkedHashSet<String>()
        out.addAll(labels.keys)
        out.addAll(engines)
        return out.toList()
    }

    private fun pickEngines() {
        val choices = engineChoices()
        if (choices.isEmpty()) {
            toastText(getString(R.string.dict_engines_none))
            return
        }
        val labels = Dict.installedEngines(this)
        val names = choices.map { labels[it] ?: it }.toTypedArray()
        val checked = BooleanArray(choices.size) { engines.contains(choices[it]) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_rule_engines_dialog)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.dict_save) { _, _ ->
                engines = choices.filterIndexed { i, _ -> checked[i] }
                buildRows(currentRule())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ——— Подбор написания ———

    private fun makeLetters() {
        val find = findField?.text?.toString().orEmpty().trim()
        if (find.isEmpty()) {
            toastText(getString(R.string.dict_need_find))
            return
        }
        val letters = Dict.letters(find)
        replField?.setText(letters)
        speak(letters)
    }

    private fun pickPronunciation() {
        val repl = replField?.text?.toString().orEmpty().trim()
        val find = findField?.text?.toString().orEmpty().trim()
        val words = Dict.words(if (repl.isNotEmpty()) repl else find)
        when {
            words.isEmpty() -> toastText(getString(R.string.dict_need_word))
            words.size == 1 -> showCandidates(words[0], repl.isNotEmpty())
            else -> pickWord(words) { w -> showCandidates(w, repl.isNotEmpty()) }
        }
    }

    /** Слово во фразе выбираем списком: замену можно править и в целом выражении. */
    private fun pickWord(words: List<String>, onPick: (String) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_pick_word)
            .setItems(words.toTypedArray()) { _, which -> onPick(words[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Список вариантов: нажатие проговаривает вариант движком и ставит его в
     *  поле; окно не закрывается — так их удобно перебирать подряд. */
    private fun showCandidates(word: String, fromRepl: Boolean) {
        val engine = targetEngine()
        val list = Dict.candidates(word, engine)
        if (list.isEmpty()) return

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dialogRow(getString(R.string.dict_pick_all)) { speakAll(list) })
        list.forEachIndexed { i, variant ->
            col.addView(dialogRow(getString(R.string.dict_variant, i + 1, list.size, variant)) {
                val cur = replField?.text?.toString().orEmpty()
                val next = if (fromRepl && cur.contains(word)) cur.replaceFirst(word, variant) else variant
                replField?.setText(next)
                speak(next)
            })
        }
        val scroll = ScrollView(this).apply { addView(col) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dict_pick_title, list.size))
            .setView(scroll)
            .setPositiveButton(R.string.toc_close, null)
            .show()
    }

    /** Строка списка вариантов: читается диктором как текст, а нажатие звучит. */
    private fun dialogRow(label: String, onClick: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Palette.INK)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isClickable = true
            ViewCompat.setScreenReaderFocusable(this, true)
            setOnClickListener { onClick() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.TextView"
                }
            }
        }
        return v
    }

    private fun speakAll(list: List<String>) {
        val sb = StringBuilder()
        list.forEachIndexed { i, v ->
            sb.append(getString(R.string.dict_variant_spoken, i + 1)).append(". ").append(v).append(". ")
        }
        speak(sb.toString())
    }

    // ——— Проверка ———

    private fun check() {
        val find = findField?.text?.toString().orEmpty()
        val repl = replField?.text?.toString().orEmpty()
        if (find.isBlank()) {
            toastText(getString(R.string.dict_need_find))
            return
        }
        val rule = Dict.Rule(find, repl, regexCheck?.isChecked ?: regex, off = false, engines = engines)
        val result = Dict.applyRule(rule, find)
        val n = Dict.countInBook(find, rule.regex)
        val head = if (n < 0) getString(R.string.dict_check_nobook)
        else getString(R.string.dict_check_count, n)
        toastText(head)
        speak(result)
    }

    // ——— Сохранение и удаление ———

    private fun currentRule(): Dict.Rule? {
        val rules = if (bookRule) Dict.loadBooks(this) else Dict.load(this)
        return if (index in rules.indices) rules[index] else null
    }

    private fun save(rule: Dict.Rule?) {
        val find = findField?.text?.toString().orEmpty().trim()
        val repl = replField?.text?.toString().orEmpty()
        if (find.isEmpty()) {
            toastText(getString(R.string.dict_need_find))
            return
        }
        val bind = bindBook
        val fresh = Dict.Rule(
            find, repl, regexCheck?.isChecked ?: regex, rule?.off ?: false, engines,
            book = bind,
        )
        // Правило, привязанное к книге, живёт в книжном файле (29.09.2026): там
        // его видно рядом с прочими правилами книг, и оно уходит вместе с книгой.
        // Снял привязку у общего правила — оно возвращается в общий словарь.
        val toBooks = bookRule || bind != null
        if (toBooks) {
            if (!bookRule && rule != null) Dict.remove(this, rule)
            if (rule == null || !bookRule) Dict.addBookRule(this, fresh)
            else Dict.replaceBookRule(this, rule, fresh)
        } else {
            if (bookRule && rule != null) Dict.removeBookRule(this, rule)
            if (rule == null) Dict.add(this, fresh) else Dict.replace(this, rule, fresh)
        }
        Diag.log(
            this, "dict",
            (if (toBooks) "книжное правило сохранено: " else "правило сохранено: ") +
                "«$find» → «$repl», движки " + (if (engines.isEmpty()) "все" else engines.joinToString(",")) +
                ", кому: " + (bind?.let { "книга «$it»" } ?: "все книги")
        )
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_FIND, find)
                .putExtra(RESULT_REPL, repl)
                .putExtra(RESULT_BOOK, bind.orEmpty()),
        )
        finish()
    }

    private fun confirmDelete(rule: Dict.Rule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dict_rule_delete)
            .setMessage(rule.line())
            .setPositiveButton(R.string.dict_rule_delete) { _, _ ->
                if (bookRule) Dict.removeBookRule(this, rule) else Dict.remove(this, rule)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ——— Голос ———

    /** Каким движком проверять: тем, который сейчас читает книгу, иначе тем, что
     *  указан у правила, иначе системным. */
    private fun targetEngine(): String? {
        val book = prefs().getString(MainActivity.KEY_ENGINE, null)
        if (book != null) return book
        return engines.firstOrNull()
    }

    private fun prefs() = getSharedPreferences("reader", MODE_PRIVATE)

    private fun speak(text: String) {
        if (text.isBlank()) return
        val engine = targetEngine()
        val p = probe ?: SpeechPlayer(applicationContext).also { probe = it }
        fun go() {
            p.volume = 1f
            p.speak(text)
        }
        if (p.isReady && p.enginePackage == engine) {
            go()
        } else {
            p.setEngine(engine) { ok ->
                if (!ok) toastText(getString(R.string.dict_speak_fail)) else go()
            }
        }
    }

    private fun toastText(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
