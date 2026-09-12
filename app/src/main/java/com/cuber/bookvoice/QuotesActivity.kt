package com.cuber.bookvoice

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cuber.bookvoice.databinding.ActivityQuotesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Раздел «Цитаты» (#104). Вход — «⋮ Ещё» в библиотеке. Каждая цитата — строка:
 * короткое нажатие открывает цитату целиком (msg1111/1114: полный текст по
 * предложениям, читает скринридер; TTS-чтения в цитатах больше нет), долгое
 * нажатие — меню действий (открыть в книге / поделиться / убрать / экспорт одной).
 * Вверху — экспорт: все сразу или выбранные (режим выбора) в TXT в Downloads.
 */
class QuotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuotesBinding

    private var quotes: List<Quote> = emptyList()

    /** Режим выбора нескольких для экспорта. */
    private var selectMode = false
    private val selectedIds = HashSet<String>()

    /** msg1111/1114: открытая на просмотр цитата (полный текст, без TTS-чтения). */
    private var openQuote: Quote? = null

    // Экспорт в файл на Android 9-: системный диалог «Сохранить как».
    private var pendingFileText: String? = null
    private var pendingFileCount = 0
    private val createTxt = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingFileText
        if (uri != null && text != null) {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            toast(getString(
                if (ok) R.string.quotes_exported else R.string.quotes_export_fail,
                pendingFileCount
            ))
            if (ok) Vibra.confirm(this) else Vibra.error(this)
        }
        pendingFileText = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // msg1763: имя окна = «Цитаты» (window title), а не label приложения — при
        // появлении окно объявляет свой window title (тот же фикс, что окнам 0.3.81).
        setTitle(getString(R.string.quotes_title))
        binding = ActivityQuotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // SDK 36: окно edge-to-edge — прижимаем корень к безопасной зоне, иначе
        // шапка «Цитат» уедет под статус-бар, низ — под навигационную полосу.
        edgeToEdge(binding.root)

        // msg1111/1114: если открыта полная цитата — «Назад» возвращает к списку.
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnExport.setOnClickListener { onExportClick() }
        binding.btnMore.setOnClickListener { showMoreMenu() }

        quotes = QuoteStore.all(this)
        rebuild()

        // msg2233/2239: на Android 16 (targetSdk 36) системный «назад» уходит в
        // predictive-back диспетчер, и без явного callback системный default
        // может закрыть окно сам, минуя нашу логику (см. комментарий в
        // SectionActivity.onCreate — тот же фикс всем окнам). Явный callback:
        // открытая цитата → к списку; иначе → закрыть окно с подавлением
        // авто-открытия книги на полке.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (openQuote != null) {
                    openQuote = null
                    rebuild()
                } else {
                    exitToLauncher()
                }
            }
        })
    }

    /** «Назад» из шапки: как системная кнопка. */
    private fun goBack() {
        if (openQuote != null) {
            openQuote = null
            rebuild()
        } else {
            exitToLauncher()
        }
    }

    /** msg1767: цитаты — окно-поверх: открываются из «⋮» полки и из «⋮» читалки.
     *  При закрытии поверх полки она не должна авто-открывать последнюю книгу
     *  (как окна Каталога/Настроек, msg1695 — suppressNextAutoOpen гаснет в
     *  LibraryActivity.resume). Из читалки finish возвращает в книгу: она и так
     *  остаётся открытой снизу, окну полки флаг не мешает. */
    private fun exitToLauncher() {
        LibraryActivity.suppressNextAutoOpen = true
        finish()
    }

    // ---------------- Экран ----------------

    private fun rebuild() {
        val q = openQuote
        if (q != null) {
            showFullQuote(q)
            return
        }
        binding.btnExport.visibility = View.VISIBLE
        binding.tvTitle.text = getString(R.string.quotes_title)
        val content = binding.content
        content.removeAllViews()

        if (quotes.isEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.quotes_empty)
                textSize = 16f
                setTextColor(0xFF9AA0A6.toInt())
                setLineSpacing(0f, 1.2f)
                isFocusable = true
                setPadding(0, dp(16), 0, dp(4))
            })
            binding.btnExport.isEnabled = false
            return
        }

        // В режиме выбора — строки действий поверх списка.
        if (selectMode) {
            content.addView(ctxButton(getString(R.string.quotes_select_all)) {
                selectedIds.clear()
                selectedIds.addAll(quotes.map { it.id })
                rebuild()
            })
            content.addView(ctxButton(getString(R.string.quotes_exit_select)) {
                selectMode = false
                selectedIds.clear()
                rebuild()
            })
        } else {
            // Подсказка о жестах — первая строка под шапкой.
            content.addView(TextView(this).apply {
                text = getString(R.string.quotes_read_hint)
                textSize = 14f
                setTextColor(0xFF9AA0A6.toInt())
                isFocusable = true
                setPadding(0, dp(8), 0, dp(2))
            })
        }

        var idx = 0
        for (q in quotes) {
            content.addView(quoteRow(q, idx))
            idx++
        }
        updateExportButton()
    }

    /** Контекстная кнопка-строка режима выбора. */
    private fun ctxButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(0xFF8AB4F8.toInt())
        isFocusable = true
        isClickable = true
        background = selectableItemBackground()
        setPadding(0, dp(10), 0, dp(2))
        setOnClickListener { action() }
    }

    /** Строка-цитата. Нажатие: в режиме выбора переключает, иначе открывает
     *  полный текст цитаты (msg1111/1114); долгое нажатие — меню действий. */
    private fun quoteRow(q: Quote, index: Int): TextView {
        val book = q.bookTitle.ifBlank { "?" }
        val preview = truncate(q.text, 200)
        val tv = TextView(this).apply {
            text = "$book — «$preview»"
            textSize = 16f
            setTextColor(0xFFE8EAED.toInt())
            setLineSpacing(0f, 1.15f)
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = selectableItemBackground()
            setPadding(0, dp(10), 0, dp(6))
        }
        tv.setOnClickListener {
            if (selectMode) {
                if (selectedIds.contains(q.id)) selectedIds.remove(q.id) else selectedIds.add(q.id)
                // msg1108: сначала «выбрана/не выбрана», потом текст цитаты.
                tv.contentDescription = getString(
                    R.string.quotes_row_cd,
                    getString(if (q.id in selectedIds) R.string.quotes_row_selected
                              else R.string.quotes_row_not_selected),
                    truncate(q.text, 120)
                )
                tv.announceForAccessibility(tv.contentDescription)
                updateExportButton()
            } else {
                // msg1111/1114: короткое нажатие — открыть полный текст цитаты.
                openQuote = q
                rebuild()
            }
        }
        tv.setOnLongClickListener {
            if (selectMode) {
                tv.performClick()
            } else {
                quoteMenu(q)
            }
            true
        }
        // По умолчанию на строке тоже полезно знать, из какой книги цитата.
        if (selectMode) {
            // msg1108: сначала состояние, потом текст цитаты.
            tv.contentDescription = getString(
                R.string.quotes_row_cd,
                getString(R.string.quotes_row_not_selected),
                truncate(q.text, 120)
            )
        }
        return tv
    }

    private fun updateExportButton() {
        val n = selectedIds.size
        binding.btnExport.text = getString(
            if (selectMode) R.string.quotes_export_selected else R.string.quotes_expo,
            n
        )
        binding.btnExport.contentDescription = binding.btnExport.text
        binding.btnExport.isEnabled = !selectMode || n > 0
    }

    // ---------------- Действия с цитатой ----------------

    /** «⋮» окна «Цитаты» (msg4669): настроек программы здесь не было, а окно
     *  открывается из «⋮» полки и из «⋮» читалки — за настройками приходилось
     *  сначала его закрывать. Пункты как у остальных окон: «Настройки программы»
     *  окном поверх цитат (закрытие возвращает сюда) и «Выход из приложения» —
     *  глобальный, всегда последним (msg1278). */
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
            .show()  // msg1687: без «Закрыть» — меню гасит системный «назад».
    }

    private fun quoteMenu(q: Quote) {
        MaterialAlertDialogBuilder(this)
            .setTitle(q.bookTitle.ifBlank { "?" })
            .setItems(arrayOf(
                getString(R.string.quotes_open_book),
                getString(R.string.quotes_share),
                getString(R.string.quotes_export_this),
                getString(R.string.quotes_remove),
            )) { _, which ->
                when (which) {
                    0 -> openInBook(q)
                    1 -> shareQuote(q)
                    2 -> exportOne(q)
                    3 -> removeQuote(q)
                }
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    /** msg1111/1114: полный текст цитаты — каждое предложение своей строкой,
     *  чтобы скринридер читал по одному. Кнопки «Прочитать» нет — читает TalkBack. */
    private fun showFullQuote(q: Quote) {
        binding.btnExport.visibility = View.GONE
        binding.tvTitle.text = getString(R.string.quotes_view_title)
        val content = binding.content
        content.removeAllViews()

        // Строка-источник: книга — автор (единый узел для скринридера).
        val book = q.bookTitle.ifBlank { "?" }
        val author = q.author?.takeIf { it.isNotBlank() }
        content.addView(TextView(this).apply {
            text = if (author != null) "$book — $author" else book
            textSize = 15f
            setTextColor(0xFF9AA0A6.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isFocusable = true
            setPadding(0, dp(8), 0, dp(10))
        })

        // Полный текст цитаты по предложениям (как в читалке).
        val parts = TextSplit.splitToSentences(q.text).ifEmpty { listOf(q.text.trim()) }
        for (part in parts) {
            content.addView(TextView(this).apply {
                text = part
                textSize = 18f
                setTextColor(0xFFE8EAED.toInt())
                setLineSpacing(0f, 1.25f)
                isFocusable = true
                setPadding(0, dp(3), 0, dp(7))
            })
        }

        // Действия той же цитаты — внизу, как в меню долгого нажатия.
        content.addView(TextView(this).apply {
            text = getString(R.string.quotes_actions_hint)
            textSize = 13f
            setTextColor(0xFF9AA0A6.toInt())
            isFocusable = true
            setPadding(0, dp(16), 0, dp(4))
        })
        content.addView(ctxButton(getString(R.string.quotes_open_book)) { openInBook(q) })
        content.addView(ctxButton(getString(R.string.quotes_share)) { shareQuote(q) })
        content.addView(ctxButton(getString(R.string.quotes_export_this)) { exportOne(q) })
        content.addView(ctxButton(getString(R.string.quotes_remove)) { removeQuote(q) })

        // Фокус на первую строку текста — скринридер сразу начнёт читать цитату.
        content.post {
            if (content.childCount > 1) content.getChildAt(1).requestFocus()
        }
    }

    private fun openInBook(q: Quote) {
        val rec = BookStore.byUri(this, q.uri)
        val exists = rec != null && fileExists(q.uri)
        if (!exists) {
            toast(getString(R.string.quotes_no_book))
            Vibra.error(this)
            return
        }
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(MainActivity.EXTRA_URI, q.uri)
        i.putExtra(MainActivity.EXTRA_CHAPTER, q.chapter)
        i.putExtra(MainActivity.EXTRA_SENTENCE, q.sentence)
        // msg1245: цитата открывается в своём месте, а не с сохранённого места книги.
        i.putExtra(MainActivity.EXTRA_EXPLICIT_PLACE, true)
        startActivity(i)
    }

    private fun fileExists(u: String): Boolean {
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        return try {
            if (uri.scheme == "file") {
                uri.path?.let { java.io.File(it).exists() } ?: false
            } else {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { it.moveToFirst() } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun shareQuote(q: Quote) {
        val author = q.author?.takeIf { it.isNotBlank() }
        val sb = StringBuilder("“").append(q.text).append("”\n\n— ")
            .append(q.bookTitle.ifBlank { "?" })
        author?.let { sb.append("\n").append(it) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, getString(R.string.quotes_share)))
    }

    private fun removeQuote(q: Quote) {
        QuoteStore.remove(this, q.id)
        quotes = QuoteStore.all(this)
        if (selectedIds.contains(q.id)) selectedIds.remove(q.id)
        // Убрали открытую на просмотр цитату — вернуться к списку.
        if (openQuote?.id == q.id) openQuote = null
        rebuild()
        toast(getString(R.string.quotes_removed))
        Vibra.confirm(this)
    }

    // ---------------- Экспорт в TXT ----------------

    private fun onExportClick() {
        if (quotes.isEmpty()) return
        if (selectMode) {
            exportText(
                textAll(quotes.filter { it.id in selectedIds }),
                fileNameAll(), selectedIds.size
            )
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quotes_expo)
            .setItems(arrayOf(
                getString(R.string.quotes_menu_export_all),
                getString(R.string.quotes_menu_export_multi),
            )) { _, which ->
                when (which) {
                    0 -> exportText(textAll(quotes), fileNameAll(), quotes.size)
                    1 -> {
                        selectMode = true
                        rebuild()
                    }
                }
            }
            .setNegativeButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun exportOne(q: Quote) {
        exportText(textAll(listOf(q)), fileNameOne(q), 1)
    }

    private fun textAll(list: List<Quote>): String {
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("BookVoice — ").append(getString(R.string.quotes_title)).append("\n")
        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        sb.append(fmt.format(java.util.Date())).append("\n\n")
        var n = 0
        for (q in list) {
            n++
            val author = q.author?.takeIf { it.isNotBlank() }
            sb.append(n).append(". ")
            sb.append(q.bookTitle.ifBlank { "?" })
            author?.let { sb.append(" — ").append(it) }
            sb.append("\n\n").append(q.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun fileNameAll(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return getString(R.string.quotes_file_prefix) + "_" + f.format(java.util.Date()) + ".txt"
    }

    private fun fileNameOne(q: Quote): String =
        getString(R.string.quotes_one_prefix) + "_" +
            sanitize(q.bookTitle.ifBlank { q.id }) + ".txt"

    private fun sanitize(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit() || c == '-' || c == '_') sb.append(c)
            else if (c == ' ') sb.append('_')
        }
        return sb.toString().ifBlank { "quote" }
    }

    private fun exportText(text: String, fileName: String, count: Int) {
        if (text.isEmpty()) {
            toast(getString(R.string.quotes_export_fail))
            Vibra.error(this)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Thread {
                val ok = writeToDownloads(fileName, text)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast(getString(
                        if (ok) R.string.quotes_exported else R.string.quotes_export_fail, count
                    ))
                    if (ok) Vibra.confirm(this) else Vibra.error(this)
                }
            }.start()
        } else {
            pendingFileText = text
            pendingFileCount = count
            createTxt.launch(fileName)
        }
    }

    /** Android 10+: файл в Downloads/BookVoice через MediaStore, без диалога. */
    private fun writeToDownloads(fileName: String, text: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BookVoice")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        true
    }.getOrElse { false }

    // ---------------- Служебное ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max).trimEnd() + "…"

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val out = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            getDrawable(out.resourceId)
        } else null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
