package com.cuber.bookvoice

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Вход в Яндекс и папка приложения на Яндекс.Диске (msg6098…6114).
 *
 * Зачем: у владельца нет ни Google Диска, ни Яндекс.Диска на телефоне, а второй
 * способ синхронизации (выбранная папка облака, [SyncStore]) требует, чтобы
 * облачное приложение уже стояло и папка была выбрана вручную. Здесь программа
 * сама просит у Яндекса свою папку: владелец один раз входит своим аккаунтом, а
 * дальше файл `app:/BookVoice_sync.json` лежит в служебной папке приложения
 * «Приложения/BookVoice» и едет между устройствами без всякого выбора папок.
 *
 * Своего сервера у нас нет — и не нужно: программа ходит в Яндекс напрямую, а
 * токен лежит только в памяти приложения на телефоне владельца. ClientID ниже не
 * секрет (он внутри каждой копии программы). Секрет приложения нам не нужен
 * вовсе: обмен кода на токен доказывается PKCE ([code_verifier], SHA-256).
 *
 * Кто угодно, кому дан APK, входит СВОИМ яндекс-аккаунтом и получает СВОЮ
 * папку приложения — данные разных людей не пересекаются.
 */
object YandexDisk {

    /** ClientID приложения BookVoice, заведённого владельцем 2026-09-17
     *  (oauth.yandex.ru, платформа «Веб-сервисы», доступ «папка приложения»). */
    private const val CLIENT_ID = "3de6b02cb475475491f5f3aead6de1c6"

    /** Адрес возврата. Должен совпадать с анкетой приложения ПОСИМВОЛЬНО —
     *  иначе Яндекс не отдаст код. Ловит его [YandexAuthActivity]. */
    const val REDIRECT = "bookvoice://oauth"

    private const val AUTH_URL = "https://oauth.yandex.ru/authorize"
    private const val TOKEN_URL = "https://oauth.yandex.ru/token"
    private const val API = "https://cloud-api.yandex.net/v1/disk/resources"
    private const val SCOPE = "cloud_api:disk.app_folder"

    /** Тот же файл, что и в облачной папке: одно содержимое, два способа добраться. */
    private val PATH = "app:/" + SyncStore.FILE_NAME

    /** Папка книг внутри папки приложения (msg6130). Диск сам родительскую папку
     *  не создаёт — [ensureBooks] вызывается перед первой заливкой. */
    private const val BOOKS_DIR = "app:/books"

    private const val KEY_TOKEN = "yandex_token"
    private const val KEY_USER = "yandex_user"
    private const val KEY_VERIFIER = "yandex_verifier"
    private const val KEY_STATE = "yandex_state"

    private val JSON = "application/json".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun token(c: Context): String? = prefs(c).getString(KEY_TOKEN, null)

    /** Вход выполнен: синхронизация идёт через папку приложения на Диске. */
    fun connected(c: Context): Boolean = !token(c).isNullOrBlank()

    /** Логин владельца — показываем его в строке, чтобы было видно, чей вход. */
    fun user(c: Context): String? = prefs(c).getString(KEY_USER, null)

    fun reset(c: Context) {
        prefs(c).edit().remove(KEY_TOKEN).remove(KEY_USER)
            .remove(KEY_VERIFIER).remove(KEY_STATE).apply()
    }

    /** Адрес страницы согласия. Открываем его системным браузером: своего экрана
     *  входа у нас нет (и пароль владельца через нас не проходит). */
    fun loginUrl(c: Context): String {
        val verifier = randomToken(32)
        val state = randomToken(16)
        prefs(c).edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /** Меняем код из адреса на токен. null — получилось; иначе текст для владельца.
     *  Код и токен в журнал не пишем никогда: журнал владелец отправляет нам. */
    fun exchange(c: Context, code: String, state: String?): String? {
        val p = prefs(c)
        val verifier = p.getString(KEY_VERIFIER, null)
            ?: return c.getString(R.string.yandex_login_fail)
        val expect = p.getString(KEY_STATE, null)
        if (expect != null && state != null && expect != state) {
            Diag.log(c, "sync", "вход в Яндекс: чужой state")
            return c.getString(R.string.yandex_login_fail)
        }
        return try {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", verifier)
                .add("redirect_uri", REDIRECT)
                .build()
            val req = Request.Builder().url(TOKEN_URL).post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val o = runCatching { JSONObject(body) }.getOrNull()
                val token = o?.optString("access_token")?.takeIf { it.isNotBlank() }
                if (token == null) {
                    val why = o?.optString("error_description")?.ifBlank { null }
                        ?: o?.optString("error")?.ifBlank { null }
                        ?: "HTTP ${resp.code}"
                    Diag.log(c, "sync", "вход в Яндекс: отказ ($why)")
                    return c.getString(R.string.yandex_login_fail)
                }
                p.edit().putString(KEY_TOKEN, token)
                    .remove(KEY_VERIFIER).remove(KEY_STATE).apply()
                whoami(token)?.let { p.edit().putString(KEY_USER, it).apply() }
                Diag.log(c, "sync", "вход в Яндекс выполнен")
                null
            }
        } catch (e: Exception) {
            Diag.log(c, "sync", "вход в Яндекс: ${e.message}")
            c.getString(R.string.yandex_login_fail)
        }
    }

    /** Ответ Диска: [body] — содержимое файла (null — файла ещё нет),
     *  [error] — причина, которую показываем владельцу. */
    class Text(val body: String? = null, val error: String? = null)

    /** Читаем файл синхронизации из папки приложения. */
    fun read(c: Context): Text {
        val t = token(c) ?: return Text(error = c.getString(R.string.yandex_no_token))
        return try {
            val link = fileLink(c, t, "download", PATH, missingOk = true)
            when {
                link.error != null -> Text(error = link.error)
                link.body == null -> Text()
                else -> client.newCall(Request.Builder().url(link.body).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Text(error = c.getString(R.string.yandex_err, "HTTP ${resp.code}"))
                    } else {
                        Text(body = resp.body?.string())
                    }
                }
            }
        } catch (e: Exception) {
            Text(error = c.getString(R.string.yandex_err, e.message ?: ""))
        }
    }

    /** Кладём файл синхронизации в папку приложения. null — получилось. */
    fun write(c: Context, text: String): String? {
        val t = token(c) ?: return c.getString(R.string.yandex_no_token)
        return try {
            val link = fileLink(c, t, "upload", PATH, missingOk = false)
            if (link.error != null) return link.error
            val href = link.body ?: return c.getString(R.string.yandex_err, "нет ссылки на загрузку")
            val put = Request.Builder().url(href).put(text.toRequestBody(JSON)).build()
            client.newCall(put).execute().use { resp ->
                if (!resp.isSuccessful) return c.getString(R.string.yandex_err, "HTTP ${resp.code}")
            }
            null
        } catch (e: Exception) {
            c.getString(R.string.yandex_err, e.message ?: "")
        }
    }

    // ---------------- Книги в папке приложения (msg6130) ----------------
    //
    // Книги едут тем же путём, что и файл синхронизации: кладём их в папку
    // `app:/books` внутри папки приложения. Сопоставление — по ИМЕНИ файла, как
    // и у мест чтения: на каждом устройстве книга лежит по своему адресу, а имя
    // у неё одно и то же.

    /** Одна книга в папке приложения. */
    class Entry(val name: String, val size: Long)

    /** Содержимое папки книг. Пустой список — «книг там ещё нет»: это не ошибка,
     *  папка появляется с первой заливкой. [error] — причина для владельца. */
    class Items(val list: List<Entry> = emptyList(), val error: String? = null)

    /** Что лежит в папке книг. Только имена и размеры — за самими файлами идём
     *  отдельно и по одному. */
    fun listBooks(c: Context): Items {
        val t = token(c) ?: return Items(error = c.getString(R.string.yandex_no_token))
        return try {
            // limit по умолчанию — 20, для полки этого мало; 1000 — потолок Диска.
            // fields сужает ответ до имени и размера: полный список тянет ещё и
            // ссылки на каждый файл, а они нам здесь не нужны.
            val url = Uri.parse(API).buildUpon()
                .appendQueryParameter("path", BOOKS_DIR)
                .appendQueryParameter("limit", "1000")
                .appendQueryParameter("fields", "_embedded.items.name,_embedded.items.size")
                .build()
                .toString()
            val req = Request.Builder().url(url).header("Authorization", "OAuth $t").build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 -> Items(error = c.getString(R.string.yandex_need_login))
                    // Папки ещё нет (первый прогон) — книг на Диске ноль.
                    resp.code == 404 || resp.code == 409 -> Items()
                    !resp.isSuccessful -> Items(error = c.getString(R.string.yandex_err, reason(body, resp.code)))
                    else -> {
                        val arr = JSONObject(body).optJSONObject("_embedded")?.optJSONArray("items")
                        val out = ArrayList<Entry>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                val name = o.optString("name")
                                if (name.isNotBlank()) out.add(Entry(name, o.optLong("size")))
                            }
                        }
                        Items(list = out)
                    }
                }
            }
        } catch (e: Exception) {
            Items(error = c.getString(R.string.yandex_err, e.message ?: ""))
        }
    }

    /** Завести папку книг. Диск не создаёт родительскую папку сам, а заливка в
     *  несуществующую папку падает. null — папка есть. */
    fun ensureBooks(c: Context): String? {
        val t = token(c) ?: return c.getString(R.string.yandex_no_token)
        return try {
            val url = Uri.parse(API).buildUpon()
                .appendQueryParameter("path", BOOKS_DIR)
                .build()
                .toString()
            val req = Request.Builder().url(url)
                .method("PUT", ByteArray(0).toRequestBody(null))
                .header("Authorization", "OAuth $t")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> null
                    resp.code == 409 -> null // уже есть — это не ошибка
                    resp.code == 401 -> c.getString(R.string.yandex_need_login)
                    else -> c.getString(R.string.yandex_err, reason(body, resp.code))
                }
            }
        } catch (e: Exception) {
            c.getString(R.string.yandex_err, e.message ?: "")
        }
    }

    /** Залить одну книгу. null — получилось. */
    fun uploadBook(c: Context, name: String, src: File): String? {
        val t = token(c) ?: return c.getString(R.string.yandex_no_token)
        return try {
            val link = fileLink(c, t, "upload", "$BOOKS_DIR/$name", missingOk = false)
            if (link.error != null) return link.error
            val href = link.body ?: return c.getString(R.string.yandex_err, "нет ссылки на загрузку")
            // Тело берём прямо из файла: книга бывает и в сотни мегабайт, целиком
            // в памяти ей делать нечего, а disk-запрос читает её потоком.
            val put = Request.Builder().url(href).put(src.asRequestBody(OCTET)).build()
            client.newCall(put).execute().use { resp ->
                if (!resp.isSuccessful) return c.getString(R.string.yandex_err, "HTTP ${resp.code}")
            }
            null
        } catch (e: Exception) {
            c.getString(R.string.yandex_err, e.message ?: "")
        }
    }

    /** Забрать одну книгу в [dest]. null — получилось. Пишем потоком, по той же
     *  причине, что и заливаем. */
    fun downloadBook(c: Context, name: String, dest: File): String? {
        val t = token(c) ?: return c.getString(R.string.yandex_no_token)
        return try {
            val link = fileLink(c, t, "download", "$BOOKS_DIR/$name", missingOk = false)
            if (link.error != null) return link.error
            val href = link.body ?: return c.getString(R.string.yandex_err, "нет ссылки на скачивание")
            client.newCall(Request.Builder().url(href).build()).execute().use { resp ->
                if (!resp.isSuccessful) return c.getString(R.string.yandex_err, "HTTP ${resp.code}")
                val body = resp.body ?: return c.getString(R.string.yandex_err, "пустой ответ")
                dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            null
        } catch (e: Exception) {
            // Недокачанный файл на полке хуже отсутствующего: его не отличить от
            // целого, а откроется он огрызком.
            runCatching { dest.delete() }
            c.getString(R.string.yandex_err, e.message ?: "")
        }
    }

    /** Ссылка на файл Диска: [op] — «download» или «upload». [missingOk] —
     *  «файла ещё нет» (404) считаем пустым ответом, а не ошибкой: у файла
     *  синхронизации это первый прогон, файл создаётся только сейчас. */
    private fun fileLink(c: Context, token: String, op: String, path: String, missingOk: Boolean): Text {
        val url = Uri.parse("$API/$op").buildUpon()
            .appendQueryParameter("path", path)
            .apply { if (op == "upload") appendQueryParameter("overwrite", "true") }
            .build()
            .toString()
        val req = Request.Builder().url(url).header("Authorization", "OAuth $token").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return when {
                resp.code == 404 && missingOk -> Text()
                resp.code == 401 -> Text(error = c.getString(R.string.yandex_need_login))
                !resp.isSuccessful -> Text(error = c.getString(R.string.yandex_err, reason(body, resp.code)))
                else -> {
                    val href = runCatching { JSONObject(body).optString("href") }.getOrNull()
                    if (href.isNullOrBlank()) Text(error = c.getString(R.string.yandex_err, "пустой ответ"))
                    else Text(body = href)
                }
            }
        }
    }

    /** Чей это вход — логин владельца для строки на экране. Необязательная
     *  подробность: не ответил Диск, значит и строки хватит без имени. */
    private fun whoami(token: String): String? = runCatching {
        val req = Request.Builder().url("https://cloud-api.yandex.net/v1/disk/")
            .header("Authorization", "OAuth $token").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else JSONObject(resp.body?.string().orEmpty()).optJSONObject("user")?.optString("login")
        }
    }.getOrNull()

    /** Человеческая причина отказа: Диск кладёт её в тело ответа. */
    private fun reason(body: String, code: Int): String {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return "HTTP $code"
        return o.optString("description").ifBlank { o.optString("message") }
            .ifBlank { "HTTP $code" }
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return base64Url(buf)
    }

    /** base64url без выравнивания — так PKCE и требует (RFC 7636). */
    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
