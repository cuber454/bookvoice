package com.cuber.bookvoice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cuber.bookvoice.databinding.ActivityAboutBinding
import android.view.ViewGroup

/** Окно «О программе» (msg2879-2934): короткая справка для всех — концепция,
 *  фишки, неочевидные нажатия, контакты автора, версия. Открывается ПОВЕРХ
 *  полки из меню «⋮» Библиотеки, как Каталог/Настройки; «назад»/кнопка в
 *  шапке закрывают окно на полку.
 *
 *  Текст живёт в about_strings.xml как секции (заголовок + абзацы). Каждый
 *  абзац на экране режется на предложения ([TextSplit.splitToSentences]), и
 *  каждое предложение становится ОТДЕЛЬНЫМ доступным элементом (item_sentence,
 *  как в читалке): скринридер ходит по справке свайпами от предложения к
 *  предложению — тот же жест, что по тексту книги. Заголовки секций — элементы
 *  с ролью «заголовок» (навигация по заголовкам).
 */
class AboutWindowActivity : SectionActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun buildSection(intent: Intent?) {
        // msg1756-паттерн: имя окна = «О программе», фокус при появлении ляжет
        // на заголовок окна ниже (resumeSection), как у Каталога/Настроек.
        setTitle(getString(R.string.about_title))
        binding = ActivityAboutBinding.inflate(layoutInflater)
        container.addView(
            binding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        binding.tvTitle.text = getString(R.string.about_title)
        binding.btnBack.setOnClickListener { rootBack() }
        buildContent(binding.content)
    }

    override fun resumeSection(arrival: Boolean) {
        // Свежий вход — фокус на заголовок окна (msg1666). Повторные показы
        // (возврат из фона) фокус не трогают (msg1465) — справка не хранит
        // позицию, незачем и дёргать курсор.
        if (arrival) TabNav.focusHeader(binding.tvTitle, 550)
    }

    override fun onSectionBackKey(): Boolean {
        rootBack()
        return true
    }

    /** Уход на полку-дом — окно закрывается, полка внизу не должна прыгать в
     *  последнюю книгу (тот же фикс, что у окна Каталога, msg1695). */
    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно «О программе» уходит на полку — авто-открытие погашено")
        super.rootBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) LibraryActivity.suppressNextAutoOpen = true
    }

    // ---------------- Сборка справки ----------------

    /** Секция справки: заголовок + абзацы/пункты (каждый — строковый ресурс). */
    private class Section(val titleRes: Int, val bodies: List<Int>)

    private fun buildContent(root: LinearLayout) {
        val sections = listOf(
            Section(R.string.about_what_title, listOf(R.string.about_what_body)),
            Section(
                R.string.about_features_title,
                listOf(
                    R.string.about_feature_walk,
                    R.string.about_feature_quotes,
                    R.string.about_feature_voice,
                    R.string.about_feature_buttons,
                ),
            ),
            Section(
                R.string.about_press_title,
                listOf(
                    R.string.about_press_intro,
                    R.string.about_press_play,
                    R.string.about_press_bookmark,
                    R.string.about_press_search,
                    R.string.about_press_recent,
                    R.string.about_press_voice_search,
                    R.string.about_press_tab,
                    R.string.about_press_book,
                ),
            ),
            Section(R.string.about_contact_title, listOf(R.string.about_contact_body)),
        )
        for (section in sections) addSection(root, section)

        // Кликабельный контакт (msg3008): под секцией «Связаться с автором» —
        // кнопка открывает чат автора в Telegram. Отдельная кнопка, а не ссылка
        // в тексте: для скринридера это явная цель с двойным тапом, как любая
        // кнопка окна.
        addContactButton(root)

        // Версия — спокойной строкой в самом низу. Не клик и не элемент
        // навигации: это подпись, а не содержимое.
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrElse { "?" }
        val tv = TextView(this)
        tv.text = getString(R.string.about_version, version)
        tv.setTextColor(0xFF9AA0A6.toInt())
        tv.textSize = 14f
        tv.setPadding(4.dp(), 16.dp(), 4.dp(), 4.dp())
        // Не цель навигации свайпами, но озвучивается тапом (importantForAccessibility
        // по умолчанию = yes). Это подпись внизу, а не содержимое справки.
        tv.isFocusable = false
        root.addView(tv)
    }

    private fun addSection(root: LinearLayout, section: Section) {
        val header = layoutInflater.inflate(R.layout.item_about_header, root, false) as TextView
        header.text = getString(section.titleRes)
        root.addView(header)
        for (bodyRes in section.bodies) {
            val sentences = TextSplit.splitToSentences(getString(bodyRes))
            for ((idx, s) in sentences.withIndex()) {
                val item = layoutInflater.inflate(R.layout.item_sentence, root, false) as TextView
                item.text = s
                // Первое предложение абзаца/пункта — воздух сверху, чтобы списки
                // визуально отделялись и для зрячего (в книжке — то же отделение
                // абзацев, SentenceAdapter.paragraphStart). item_sentence кладёт
                // собственные паддинги внутрь, внешний margin выставляем здесь.
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = (if (idx == 0) 8 else 0).dp()
                item.layoutParams = lp
                root.addView(item)
            }
        }
    }

    /** Кнопка «Написать автору в Telegram» — из собственного layout, чтобы текст
     *  кнопки жил в ресурсах (about_contact_button), а не в коде. */
    private fun addContactButton(root: LinearLayout) {
        val btn = layoutInflater
            .inflate(R.layout.item_about_contact_button, root, false) as Button
        btn.setOnClickListener { openAuthorTelegram() }
        root.addView(btn)
    }

    /** Открыть чат автора @Cuber456. Сначала ссылка tg:// — уходит сразу в
     *  Telegram; если его нет (ActivityNotFound), страховка на https://t.me —
     *  откроется в браузере. Обе попытки ловим: окно «О программе» не должно
     *  упасть из-за отсутствия обработчика. */
    private fun openAuthorTelegram() {
        val direct = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=Cuber456"))
        if (!launchSafely(direct)) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Cuber456"))
            launchSafely(web)
        }
    }

    private fun launchSafely(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
