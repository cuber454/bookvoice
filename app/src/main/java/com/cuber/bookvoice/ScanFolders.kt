package com.cuber.bookvoice

import android.content.Context
import java.io.File

/**
 * Дополнительные папки для сканирования (20.11.2026, просьба Сержа).
 *
 * Полка умеет жить по РЕЕСТРУ записей, а книги в него приносит автоскан ОДНОЙ
 * выбранной папки. Если книги лежат в двух-трёх постоянных местах, человек не
 * должен каждый раз лазить в менеджер: он один раз добавляет папку в список, и
 * скан при каждом открытии полки проходит по всем.
 *
 * Дополнительные папки — ОБЫЧНЫЕ ПУТИ НА ДИСКЕ, а не SAF-деревья: их читаем
 * напрямую, ровно так же, как «скан по диску» главной папки. Цена — нужен
 * «Доступ ко всем файлам» (главной папке он не обязателен, у неё есть SAF);
 * без доступа строка в настройках честно говорит об этом и ведёт включывать.
 *
 * Хранится множество канонических путей в prefs «reader», ключ [KEY]. Порядок
 * показа — по имени папки: путь пути рознь только именем и показать человеку
 * «вторую» и «третью» в сколь-нибудь устойчивом порядке важнее, чем ручной
 * порядок, за который никто не просил.
 *
 * Удаление папки из списка НИЧЕГО не удаляет с полки: записи её книг живут,
 * место чтения и закладки при них — просто скан перестает туда заглядывать.
 * (Просьба Сержа от того же дня: «старые книги с полки не убираем».)
 */
object ScanFolders {

    const val KEY = "scan_folders"

    private fun prefs(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Отсортированные по имени папки: так список стабилен между показами. */
    fun list(c: Context): List<String> {
        val raw = prefs(c).getStringSet(KEY, null) ?: return emptyList()
        return raw.sortedWith(compareBy({ name(it).lowercase() }, { it.lowercase() }))
    }

    fun name(path: String): String = File(path).name.ifBlank { path }

    fun has(c: Context, path: String): Boolean =
        (prefs(c).getStringSet(KEY, null) ?: emptySet()).contains(path)

    fun add(c: Context, path: String): Boolean {
        val p = prefs(c)
        val set = (p.getStringSet(KEY, null) ?: emptySet()).toMutableSet()
        if (!set.add(path)) return false
        p.edit().putStringSet(KEY, set).apply()
        Diag.log(c, "shelf", "папка для сканирования добавлена: $path")
        return true
    }

    fun remove(c: Context, path: String): Boolean {
        val p = prefs(c)
        val set = (p.getStringSet(KEY, null) ?: emptySet()).toMutableSet()
        if (!set.remove(path)) return false
        p.edit().putStringSet(KEY, set).apply()
        Diag.log(c, "shelf", "папка для сканирования убрана: $path")
        return true
    }
}
