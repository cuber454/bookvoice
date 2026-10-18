package com.cuber.bookvoice

/** Окно «Каталоги» (редизайн msg1676+): открывается ПОВЕРХ полки из меню «⋮»
 *  Библиотеки, как книга. Нижней полосы вкладок нет — вернуться на полку =
 *  закрыть окно («назад» в корне каталога), как закрыть книгу.
 *
 *  Страница каталога строится при каждом открытии окна и живёт, пока окно на
 *  экране: навигация по лентам и достроенные списки переживают возврат из
 *  читалки (она ложится поверх), но с закрытием окна состояние сбрасывается —
 *  следующее открытие начинается с корня каталога.
 */
class CatalogWindowActivity : SectionActivity() {

    private val catalog = CatalogActivity(this)

    override fun buildSection(intent: android.content.Intent?) {
        // msg1756: имя окна = «Каталоги», а не label приложения. При появлении окно
        // объявляет свой window title; перенос фокуса на заголовок Jieshuo на свежем
        // окне роняет — имя должно жить в самом окне (тот же фикс, что полке 0.3.80).
        setTitle(getString(R.string.catalog_title))
        catalog.build(container, intent)
    }

    override fun resumeSection(arrival: Boolean) {
        // Первый показ окна — свежий вход: фокус на заголовок окна (msg1666).
        // Возвраты из читалки/пикера флаг не получают — фокус не трогаем.
        catalog.resume(byTab = arrival)
    }

    override fun onSectionBackKey(): Boolean = catalog.onBackKey()

    override fun disposeSection() {
        catalog.dispose()
    }

    /** Уход на полку-дом — «назад» в корне каталога либо «⋮ → Библиотека». Окно
     *  закрывается, и полка внизу не должна прыгать в последнюю книгу (msg1695:
     *  вернулся из каталога «назад» — а тебя перекинуло в открытую книгу). Флаг
     *  гасим ЗДЕСЬ, до finish(): подавление в onDestroy полагается на порядок
     *  жизненного цикла, а он не гарантирован — onResume полки может случиться
     *  раньше onDestroy окна, и тогда она успеет прочитать флаг неустановленным. */
    override fun rootBack() {
        LibraryActivity.suppressNextAutoOpen = true
        Diag.log(this, "nav", "окно Каталога уходит на полку — авто-открытие погашено")
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
