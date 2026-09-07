package com.cuber.bookvoice

/** Окно «Библиотека» — дом приложения (редизайн msg1676+). Корень задачи:
 *  launcher и «Открыть с помощью» (msg748), как раньше хост TabsActivity.
 *
 *  Нижней полосы вкладок больше нет — поверх этого окна живут только окна:
 *  Каталог и Настройки (из меню «⋮» полки), читалка MainActivity, цитаты
 *  QuotesActivity. Возврат из любого из них — обычный onResume окна: полка
 *  перестраивается и подхватывает изменения (скачанная книга, смена сортировки).
 */
class LibraryWindowActivity : SectionActivity() {

    private val library = LibraryActivity(this)

    override fun buildSection(intent: android.content.Intent?) {
        // msg1739: имя окна-дома = «Библиотека», а не label приложения. При
        // холодном старте/возврате окно полки объявляет свой заголовок при
        // появлении — он не должен звучать как «BookVoice».
        setTitle(getString(R.string.go_library))
        library.build(container, intent)
    }

    override fun resumeSection(arrival: Boolean) {
        // Полка показывается всегда «снизу» (холодный старт либо возврат из окна
        // поверх): явного входа «по вкладке» больше нет.
        library.resume()
    }

    override fun onSectionBackKey(): Boolean = library.onBackKey()

    override fun disposeSection() {
        library.dispose()
    }

    /** Корень задачи: «назад» на полке сворачивает приложение на рабочий стол. */
    override fun rootBack() {
        moveTaskToBack(true)
    }
}
