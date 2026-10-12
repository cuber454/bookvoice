package com.cuber.bookvoice

import android.content.Context
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Скрывать системные кнопки («назад/домой/недавние»), пока идёт чтение или пока
 * открыта книга (msg5923/5931).
 *
 *  Смысл для незрячего: панель лежит ровно там, куда приходит палец, когда он
 *  водит по экрану и листает по кнопкам читалки в поисках нужной. Случайное
 *  «назад» выбрасывает из книги посреди чтения. Убираем панель, пока она мешает.
 *
 *  Выбор в настройках, три состояния ([MainActivity.KEY_READER_BARS]):
 *  «показывать» (по умолчанию) — не трогаем ничего; «скрывать во время чтения» —
 *  панель уходит на старте чтения и САМА возвращается на паузе; «скрывать, пока
 *  открыта книга» — панели нет всё время, пока открыта книга.
 *
 *  Почему это выбор, а не одно поведение. Обе крайности кому-то мешают: «во
 *  время чтения» оставляет панель на паузе, «пока открыта книга» убирает её
 *  и там, где человек как раз собрался выходить. Второе состояние опаснее —
 *  панель возвращает только смахивание от нижнего края, и человек, который
 *  этого не знает, выйти обычной «Назад» не сможет. Поэтому по умолчанию не
 *  скрываем ничего, а на выборе «пока открыта книга» экран настроек говорит
 *  голосом, как её вернуть. Выход из книги при скрытой панели есть и без неё:
 *  «Выход» в медиа-карточке в шторе (см. [MediaSessionService]) — им же
 *  пользуются при чтении с погашенным экраном.
 *
 *  Прячем ТОЛЬКО навигационную полосу, статус-бар не трогаем: под ним живёт
 *  штора с медиа-карточкой, а это наш способ управлять чтением без окна.
 *
 *  Окно читалки — [MainActivity], то есть «книга открыта» здесь означает «окно
 *  с книгой на экране». Настройки и полка — другие окна (свои секции), у них
 *  свои полосы; уходя туда, мы ничего не прячем.
 */
object ReaderBars {

    /** Открыта ли книга в окне читалки. */
    private var bookOpen = false

    /** Идёт ли чтение — тот же признак, что у блокировки процессора и сторожа
     *  разблокировки (см. [KeepAwake]): они встают и снимаются в один момент. */
    private var reading = false

    /** Что уже применено к окну (`null` — окна ещё не видели). Держим, чтобы не
     *  дёргать контроллер инсетов на каждый чих: каждый вызов — пересчёт разметки. */
    private var hidden: Boolean? = null

    /** Книга открылась. */
    fun bookOpened() {
        bookOpen = true
        apply()
    }

    /** Книга закрылась (окно ушло, книга не открылась, «Выход»). */
    fun bookClosed() {
        bookOpen = false
        apply()
    }

    /** Чтение началось. */
    fun readingStarted() {
        reading = true
        apply()
    }

    /** Чтение встало — пауза, конец книги, закрытие читалки. */
    fun readingStopped() {
        reading = false
        apply()
    }

    /** Перечитать настройку — зовёт экран настроек сразу после переключения:
     *  выбрали на ходу — применяется, не дожидаясь следующего старта чтения. */
    fun sync() = apply()

    /** Окно вернулось в фокус. Система вправе вернуть полосу саму (уход в фон и
     *  возврат, показ шторы), поэтому забываем прежнее решение и применяем заново. */
    fun reapply() {
        hidden = null
        apply()
    }

    private fun apply() {
        val act = MainActivity.active ?: return
        val want = wantHide(act)
        // 07.10.2026, жалоба Сержа: «стоит „скрывать, пока открыта книга“, а
        // кнопки всё равно показываются». Своей памяти тут мало: система
        // возвращает полосу САМА (смахивание от края, работа диктора, переходы
        // окна), и если решать по одному лишь прошлому запросу, мы больше её не
        // спрячем. Сверяемся с фактическим положением дел в окне.
        if (hidden == want && actualHidden(act) == want) return
        runCatching {
            val w = act.window
            val c = WindowCompat.getInsetsController(w, w.decorView)
            if (want) {
                // Липкое погружение: смахивание от нижнего края показывает полосу
                // поверх на время, дальше она уходит сама.
                c.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                c.show(WindowInsetsCompat.Type.navigationBars())
            }
            hidden = want
            // Пишем в журнал КАЖДУЮ перемену: по жалобе «не скрывается» иначе не
            // понять, просили мы вообще или режим не тот (журнал Серж не читает,
            // он для разбора).
            Diag.log(
                act, "a11y",
                "системные кнопки: ${if (want) "прячу" else "показываю"} " +
                    "(режим ${mode(act)}, книга открыта=$bookOpen, чтение=$reading)"
            )
        }
    }

    /** Видна ли навигационная полоса ФАКТИЧЕСКИ (null — окно ещё не отвечало
     *  инсетами, тогда судим по своей памяти). */
    private fun actualHidden(act: MainActivity): Boolean? = runCatching {
        val insets = ViewCompat.getRootWindowInsets(act.window.decorView) ?: return null
        !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    }.getOrNull()

    /** Прятать ли полосу в этом режиме. «Пока открыта книга» включает в себя и
     *  время чтения: книга открыта тогда, когда её читают, тоже. */
    private fun wantHide(c: Context): Boolean = when (mode(c)) {
        MainActivity.BARS_HIDE_BOOK -> bookOpen
        MainActivity.BARS_HIDE_READING -> reading
        else -> false
    }

    private fun mode(c: Context): Int = runCatching {
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_READER_BARS, MainActivity.BARS_SHOW)
    }.getOrDefault(MainActivity.BARS_SHOW)
}
