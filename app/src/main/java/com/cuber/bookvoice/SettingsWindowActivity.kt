package com.cuber.bookvoice

/** Окно «Настройки» (редизайн msg1676+): открывается ПОВЕРХ полки из меню «⋮»
 *  Библиотеки, как книга (и поверх читалки из её «⋮» — читалка закрывается,
 *  место книги сохранено, как было в модели вкладок). Вернуться = закрыть окно.
 *
 *  Страница настроек строится при каждом открытии окна: вход всегда начинается
 *  с корневого меню разделов (открытый в прошлый раз раздел не держим).
 */
class SettingsWindowActivity : SectionActivity() {

    private val settings = SettingsActivity(this)

    override fun buildSection(intent: android.content.Intent?) {
        // msg1756: имя окна = «Настройки», а не label приложения. При появлении окно
        // объявляет свой window title; перенос фокуса на заголовок Jieshuo на свежем
        // окне роняет — имя должно жить в самом окне (тот же фикс, что полке 0.3.80).
        setTitle(getString(R.string.settings_title))
        settings.build(container, intent)
    }

    override fun resumeSection(arrival: Boolean) {
        // Первый показ окна — свежий вход: фокус на заголовок окна (msg1666).
        // Возвраты из пикеров флаг не получают — фокус не трогаем (msg1465).
        settings.resume(byTab = arrival)
    }

    override fun onSectionBackKey(): Boolean = settings.onBackKey()

    /** Уход на полку-дом — «назад» в корне настроек либо «⋮ → Библиотека». Окно
     *  закрывается, и полка внизу не должна прыгать в последнюю книгу (msg1695,
     *  тот же класс, что у Каталога). Флаг гасим ЗДЕСЬ, до finish(): подавление
     *  в onDestroy полагается на порядок жизненного цикла, а он не гарантирован —
     *  onResume полки может случиться раньше onDestroy окна. */
    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно Настроек уходит на полку — авто-открытие погашено")
        super.rootBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Возврат с этого окна на полку не должен прыгать в последнюю книгу:
        // полка покажется «снизу», и её resume это воспринял бы как старт.
        // Страховка поверх rootBack (там флаг ставится раньше, до finish()).
        if (isFinishing) LibraryActivity.suppressNextAutoOpen = true
    }
}
