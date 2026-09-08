package com.cuber.bookvoice

/** Одно предложение текста. [paragraphStart] — начинало ли оно новый абзац в исходнике. */
data class Sentence(val text: String, val paragraphStart: Boolean = false)

/** Глава книги: список предложений и заголовок (может отсутствовать).
 *  Флаги [major] и [nested] описывают место главы в иерархии FB2 и нужны
 *  настройке «Кнопки глав шагают» (0.3.37): [major] — глава открывает корневую
 *  секцию/часть (по ней ходит режим «по крупным разделам»); [nested] — глава
 *  лежит внутри другой главы, т.е. это подраздел (режим «по главам» его
 *  пропускает). Для TXT/EPUB обе false — иерархии нет, ходьба прежняя. */
data class Chapter(
    val title: String?,
    val sentences: List<Sentence>,
    val major: Boolean = false,
    val nested: Boolean = false,
)

/** Разобранная книга. */
data class BookDocument(
    val title: String?,
    val author: String?,
    val chapters: List<Chapter>,
    /** Формат распознан, но содержимое прочитать нельзя — текст не извлекается
     *  (скан), файл под паролем. Когда не null, [chapters] пуст, а ридер
     *  показывает причину вместо книги. */
    val unreadable: Unreadable? = null,
) {
    val hasText: Boolean
        get() = chapters.isNotEmpty() && chapters.any { it.sentences.isNotEmpty() }

    /** Почему файл не удалось превратить в книгу (PDF, msg727-752). */
    enum class Unreadable {
        /** Нет извлекаемого текстового слоя: скан или картинки. OCR не делаем. */
        PDF_NO_TEXT_LAYER,
        /** Файл защищён паролем. */
        PDF_ENCRYPTED,
        /** Разбор съел всю память (обычно большой скан-учебник). Ловим на
         *  OutOfMemoryError — это не повод ронять приложение (msg2587). */
        PDF_OUT_OF_MEMORY,
    }
}
