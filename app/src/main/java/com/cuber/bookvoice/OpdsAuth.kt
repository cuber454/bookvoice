package com.cuber.bookvoice

import android.content.Context
import android.util.Base64

/**
 * Вход в сетевую библиотеку (#20, msg4240).
 *
 * Механика выяснена вживую (curl по живому OPDS): «Моя полка» на flibusta
 * (`/opds/polka`) без входа отвечает `HTTP 401` и заголовком
 * `www-authenticate: Basic realm="Flibusta authentication"` — это стандартная
 * HTTP Basic-авторизация OPDS, ровно её и показывает FBReader. Значит для входа
 * достаточно приложить к запросу заголовок `Authorization: Basic base64(логин:пароль)`.
 * Куки и формы не нужны: другие разделы каталога публичные и работают как раньше.
 *
 * Где живёт пароль: в приватном хранилище приложения (файл настроек «reader», там
 * же остальные настройки). Шифрования нет — но файл недоступен другим приложениям
 * (песочница Android). В diag.log и в Telegram пароль не попадает никогда, пишем
 * только «вход сохранён» и имя пользователя.
 *
 * Кому уходит заголовок: ТОЛЬКО тому хосту, которому логин дали. В списке может
 * быть несколько библиотек, и отдавать пароль flibusta чужому серверу нельзя.
 */
object OpdsAuth {
    private const val KEY_HOST = "opds_auth_host"
    private const val KEY_LOGIN = "opds_auth_login"
    private const val KEY_PASS = "opds_auth_pass"

    /** Хосты, которые сами попросили вход (ответили 401). По ним в меню библиотеки
     *  показываем «Войти»: у публичных библиотек этого пункта не будет. */
    private const val KEY_ASKED = "opds_auth_asked"

    private fun prefs(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Хост сохранённого входа (пусто — входа нет). */
    fun host(c: Context): String = prefs(c).getString(KEY_HOST, "").orEmpty()

    /** Имя пользователя сохранённого входа — оно не секрет, показываем в меню. */
    fun login(c: Context): String = prefs(c).getString(KEY_LOGIN, "").orEmpty()

    fun has(c: Context): Boolean = host(c).isNotEmpty() && login(c).isNotEmpty()

    /** Вход сохранён именно для этого адреса? */
    fun hasFor(c: Context, url: String): Boolean =
        has(c) && sameHost(host(c), OpdsPrefs.hostOf(url))

    fun save(c: Context, host: String, login: String, pass: String) {
        prefs(c).edit()
            .putString(KEY_HOST, host)
            .putString(KEY_LOGIN, login)
            .putString(KEY_PASS, pass)
            .apply()
    }

    fun clear(c: Context) {
        prefs(c).edit().remove(KEY_HOST).remove(KEY_LOGIN).remove(KEY_PASS).apply()
    }

    /** Запомнить, что хост просил вход (для пункта меню «Войти»). */
    fun noteAsked(c: Context, url: String) {
        val host = OpdsPrefs.hostOf(url).lowercase()
        if (host.isBlank()) return
        val all = asked(c) + host
        prefs(c).edit().putString(KEY_ASKED, all.joinToString(",")).apply()
    }

    fun askedBefore(c: Context, url: String): Boolean =
        asked(c).contains(OpdsPrefs.hostOf(url).lowercase())

    private fun asked(c: Context): Set<String> =
        prefs(c).getString(KEY_ASKED, "").orEmpty()
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Заголовок Authorization для адреса — или null, если входить нечем или это
     *  чужой хост (пароль уходит только тому, кому его дали). */
    fun headerFor(c: Context, url: String): String? {
        if (!hasFor(c, url)) return null
        val raw = "${login(c)}:${prefs(c).getString(KEY_PASS, "").orEmpty()}"
        val b64 = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $b64"
    }

    private fun sameHost(a: String, b: String): Boolean =
        a.trim().lowercase().removePrefix("www.") == b.trim().lowercase().removePrefix("www.")
}
