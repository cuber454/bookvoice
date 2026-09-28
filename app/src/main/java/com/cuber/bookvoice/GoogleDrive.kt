package com.cuber.bookvoice

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Google Диск: вход и служебная папка приложения (28.09.2026).
 *
 * Зачем: второй облачный сервис рядом с Яндексом — у людей за границей Яндекс
 * закрыт, а аккаунт Google есть почти у всех.
 *
 * Почему не так, как с Яндексом. Google запрещает Android-приложениям вход через
 * браузер с возвратом по своей схеме: на запрос отвечает «Custom URI scheme is
 * not enabled for your Android client», и в документации сказано, что возврат на
 * localhost в мобильных приложениях тоже не поддерживается. Поэтому вход идёт
 * через библиотеку Google для Android (play-services-auth): она показывает выбор
 * аккаунта и запрос доступа, а приложение получает признак «доступ выдан».
 * Дальше токен берётся молча, по имени аккаунта ([GoogleAuthUtil.getToken]).
 *
 * Что это значит для людей: нужны сервисы Google Play и аккаунт Google в
 * телефоне. У кого их нет — тот пользуется Яндекс.Диском, папкой или WebDAV.
 *
 * Своего сервера у нас нет: приложение ходит в Google напрямую, токен живёт
 * только в памяти приложения, а данные лежат в служебной папке приложения
 * (`appDataFolder`), которой в интерфейсе Google Диска не видно.
 *
 * Где что лежит внутри служебной папки. Плоско, без вложенных папок и без косой
 * черты в именах — так меньше запросов и меньше поводов ошибиться:
 *   BookVoice_sync.json — файл синхронизации, как у Яндекса;
 *   <имя книги>         — книги, залитые с телефона;
 *   удалено - <имя>     — то, что убрано с полки: не стираем, а помечаем именем,
 *                         чтобы владелец всегда мог достать книгу обратно.
 */
object GoogleDrive {

    /** Доступ только к служебной папке приложения — ничего чужого не видно. */
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    /** Аккаунт, которым вошли. Им же подписан запрос токена. */
    private const val KEY_ACCOUNT = "google_account"
    private const val ACCOUNT_TYPE = "com.google"

    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    /** Куда Драйв кладёт файлы приложения, если родителя не назвали. */
    private const val FOLDER = "appDataFolder"

    /** Приставка у книг, убранных с полки. Косую черту в именах не используем:
     *  у Диска имя — это имя, а не путь, и лишний риск нам не нужен. */
    private const val TRASH_PREFIX = "удалено - "

    /** Имя файла синхронизации — то же, что в облачной папке и у Яндекса. */
    private const val SYNC_NAME = SyncStore.FILE_NAME

    private val JSON = "application/json".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    /** Ответ с телом или с причиной отказа — как [YandexDisk.Text].
     *  [needConsent] — Google просит показать свой экран согласия ещё раз:
     *  токен сам не появится, нужен человек. */
    data class Text(val body: String? = null, val error: String? = null, val needConsent: Boolean = false)

    /** Одна книга в служебной папке. Имя — без служебной приставки. */
    class Entry(val name: String, val size: Long)

    /** Содержимое папки книг. Пустой список — «книг там ещё нет». */
    class Items(val list: List<Entry> = emptyList(), val error: String? = null)

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun account(c: Context): String? = prefs(c).getString(KEY_ACCOUNT, null)

    fun connected(c: Context): Boolean = !account(c).isNullOrBlank()

    /** Логин владельца — показываем в строке, чтобы было видно, чей вход. */
    fun user(c: Context): String? = account(c)

    fun saveAccount(c: Context, email: String) {
        prefs(c).edit().putString(KEY_ACCOUNT, email).apply()
    }

    fun reset(c: Context) {
        prefs(c).edit().remove(KEY_ACCOUNT).apply()
    }

    /** Есть ли на телефоне библиотека Google. Без неё вход невозможен, и об этом
     *  надо сказать словами, а не молчащей строкой. */
    fun available(): Boolean = runCatching {
        Class.forName("com.google.android.gms.auth.api.identity.AuthorizationClient")
    }.isSuccess

    /** Токен доступа. Зовётся ТОЛЬКО из фонового потока: внутри сеть.
     *  Живёт около часа, поэтому берём перед каждым прогоном, а не храним. */
    fun token(c: Context): Text {
        val who = account(c) ?: return Text(error = c.getString(R.string.google_no_token))
        return runCatching {
            Text(body = GoogleAuthUtil.getToken(c, Account(who, ACCOUNT_TYPE), "oauth2:$SCOPE"))
        }.getOrElse { e ->
            Diag.log(c, "sync", "токен Google не получен: ${e.javaClass.simpleName} ${e.message ?: ""}")
            Text(error = c.getString(R.string.google_need_login), needConsent = e is UserRecoverableAuthException)
        }
    }

    /** Экран, который Google просит показать, когда доступ выдан не до конца
     *  («NeedRemoteConsent»): обещание доступа есть, а токена сервер ещё не даёт.
     *  Зовётся из фонового потока; null — показывать нечего. */
    fun consentIntent(c: Context): Intent? {
        val who = account(c) ?: return null
        return runCatching {
            GoogleAuthUtil.getToken(c, Account(who, ACCOUNT_TYPE), "oauth2:$SCOPE")
            null
        }.getOrElse { e -> (e as? UserRecoverableAuthException)?.intent }
    }

    /** Проверка входа: спрашиваем у Google служебную папку приложения.
     *  null — доступ есть; иначе текст для владельца. */
    fun check(c: Context): String? {
        val t = token(c)
        if (t.error != null) return t.error
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", FOLDER)
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name)")
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${t.body}").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val n = JSONObject(resp.body?.string().orEmpty())
                        .optJSONArray("files")?.length() ?: 0
                    Diag.log(c, "sync", "проверка Google Диска: доступ есть, файлов в папке $n")
                    null
                } else {
                    Diag.log(c, "sync", "проверка Google Диска: HTTP ${resp.code}")
                    c.getString(R.string.google_check_fail, "HTTP ${resp.code}")
                }
            }
        }.getOrElse { e ->
            Diag.log(c, "sync", "проверка Google Диска не прошла: ${e.message}")
            c.getString(R.string.google_check_fail, e.message ?: "")
        }
    }

    /** Проверка с терпением. Сразу после согласия Google отвечает
     *  «NeedRemoteConsent»: доступ человек выдал, а сервер токена ещё не в курсе.
     *  Даём ему несколько секунд и пробуем снова, а на экран согласия зовём только
     *  тогда, когда Google сам его просит. */
    fun checkPatiently(c: Context, tries: Int = 3, pauseMs: Long = 3000L): String? {
        var err = check(c)
        var left = tries
        while (err != null && left > 0 && token(c).needConsent) {
            runCatching { Thread.sleep(pauseMs) }
            err = check(c)
            left--
        }
        return err
    }

    // ---------------- Файл синхронизации и книги ----------------

    /** Файл синхронизации. [Text.body] == null — файла ещё нет (первый прогон),
     *  это не ошибка, как и у Яндекса. */
    fun read(c: Context): Text {
        val t = token(c)
        if (t.error != null) return t
        val tok = t.body ?: return Text(error = c.getString(R.string.google_no_token))
        val found = find(c, tok, SYNC_NAME)
        if (found.error != null) return Text(error = found.error, needConsent = found.needConsent)
        val id = found.id ?: return Text()
        val got = media(c, tok, id)
        return Text(body = got.body, error = got.error, needConsent = got.needConsent)
    }

    /** Кладём файл синхронизации в служебную папку. null — получилось. */
    fun write(c: Context, body: String): String? {
        val t = token(c)
        if (t.error != null) return t.error
        val tok = t.body ?: return c.getString(R.string.google_no_token)
        val found = find(c, tok, SYNC_NAME)
        if (found.error != null) return found.error
        val data = body.toByteArray(Charsets.UTF_8)
        return if (found.id == null) {
            create(c, tok, SYNC_NAME, data.toRequestBody(JSON))
        } else {
            update(c, tok, found.id, data.toRequestBody(JSON))
        }
    }

    /** Что лежит в папке книг. Только имена и размеры — за самими файлами идём
     *  отдельно и по одному. Книгами считаем всё, кроме файла синхронизации и
     *  того, что уже помечено как удалённое. */
    fun listBooks(c: Context): Items {
        val t = token(c)
        if (t.error != null) return Items(error = t.error)
        val tok = t.body ?: return Items(error = c.getString(R.string.google_no_token))
        val all = list(c, tok)
        if (all.error != null) return Items(error = all.error)
        val out = ArrayList<Entry>()
        for (f in all.list) {
            if (f.name == SYNC_NAME || f.name.startsWith(TRASH_PREFIX)) continue
            if (f.name.isNotBlank()) out.add(Entry(f.name, f.size))
        }
        return Items(list = out)
    }

    /** Заводить папку не надо: книги лежат плоско, приставкой к имени.
     *  Функция остаётся ради общего вида с Яндексом. */
    fun ensureBooks(c: Context): String? = null

    /** Как и [ensureBooks]: «удалённое» — та же папка, другое начало имени. */
    fun ensureTrash(c: Context): String? = null

    /** Залить одну книгу. null — получилось. */
    fun uploadBook(c: Context, name: String, src: File): String? {
        val t = token(c)
        if (t.error != null) return t.error
        val tok = t.body ?: return c.getString(R.string.google_no_token)
        val found = find(c, tok, name)
        if (found.error != null) return found.error
        // Тело читается из файла потоком: книга бывает и в сотни мегабайт,
        // целиком в памяти ей делать нечего.
        val body = src.asRequestBody(OCTET)
        return if (found.id == null) create(c, tok, name, body)
        else update(c, tok, found.id, body)
    }

    /** Забрать одну книгу в [dest]. null — получилось. Пишем потоком. */
    fun downloadBook(c: Context, name: String, dest: File): String? {
        val t = token(c)
        if (t.error != null) return t.error
        val tok = t.body ?: return c.getString(R.string.google_no_token)
        val found = find(c, tok, name)
        if (found.error != null) return found.error
        val id = found.id ?: return c.getString(R.string.google_err, "книги нет в служебной папке")
        return runCatching {
            val req = Request.Builder()
                .url("$API/files/$id?alt=media")
                .header("Authorization", "Bearer $tok")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return c.getString(R.string.google_err, "HTTP ${resp.code}")
                val data = resp.body ?: return c.getString(R.string.google_err, "пустой ответ")
                dest.outputStream().use { out -> data.byteStream().copyTo(out) }
            }
            null
        }.getOrElse { e ->
            // Недокачанный файл на полке хуже отсутствующего: его не отличить от
            // целого, а откроется он огрызком.
            runCatching { dest.delete() }
            c.getString(R.string.google_err, e.message ?: "")
        }
    }

    /** Убрать книгу в «удалённое»: не стираем, а помечаем имя приставкой.
     *  Книги нет — переносить нечего, это не ошибка. */
    fun moveToTrash(c: Context, name: String): String? {
        val t = token(c)
        if (t.error != null) return t.error
        val tok = t.body ?: return c.getString(R.string.google_no_token)
        val found = find(c, tok, name)
        if (found.error != null) return found.error
        val id = found.id ?: return null
        return runCatching {
            val meta = JSONObject().put("name", TRASH_PREFIX + name).toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("$API/files/$id?fields=id")
                .patch(meta)
                .header("Authorization", "Bearer $tok")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else c.getString(R.string.google_err, reason(resp.body?.string().orEmpty(), resp.code))
            }
        }.getOrElse { e -> c.getString(R.string.google_err, e.message ?: "") }
    }

    // ---------------- Запросы к Диску ----------------

    /** Что нашлось по имени. [id] == null — файла нет. */
    private class Found(val id: String? = null, val error: String? = null, val needConsent: Boolean = false)

    /** Один файл служебной папки, как его отдаёт Диск. */
    private class Remote(val id: String, val name: String, val size: Long)

    private class Listing(val list: List<Remote> = emptyList(), val error: String? = null)

    /** Всё, что лежит в служебной папке приложения. Файлов там немного: наш файл
     *  синхронизации, книги и удалённые книги. */
    private fun list(c: Context, tok: String): Listing {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", FOLDER)
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "files(id,name,size)")
            .addQueryParameter("q", "trashed=false")
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return Listing(error = c.getString(R.string.google_err, reason(body, resp.code)))
                val arr = JSONObject(body).optJSONArray("files") ?: JSONArray()
                val out = ArrayList<Remote>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    val name = o.optString("name")
                    if (id.isBlank() || name.isBlank()) continue
                    // Размер Диск отдаёт строкой — читаем её числом.
                    val size = o.optString("size").toLongOrNull() ?: o.optLong("size")
                    out.add(Remote(id, name, size))
                }
                Listing(list = out)
            }
        }.getOrElse { e -> Listing(error = c.getString(R.string.google_err, e.message ?: "")) }
    }

    /** Ищем файл по имени. Отдельным запросом, а не общим списком: список книг
     *  бывает и на сотню записей, а имя нужно точечно. */
    private fun find(c: Context, tok: String, name: String): Found {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", FOLDER)
            .addQueryParameter("pageSize", "10")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("q", "name='${name.replace("'", "\\'")}' and trashed=false")
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Found(error = c.getString(R.string.google_err, reason(body, resp.code)))
                }
                val files = JSONObject(body).optJSONArray("files")
                val id = if (files != null && files.length() > 0) files.getJSONObject(0).optString("id") else null
                Found(id = id?.takeIf { it.isNotBlank() })
            }
        }.getOrElse { e -> Found(error = c.getString(R.string.google_err, e.message ?: "")) }
    }

    /** Тело файла (alt=media) строкой: так читается файл синхронизации. */
    private fun media(c: Context, tok: String, id: String): Text {
        val req = Request.Builder()
            .url("$API/files/$id?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Text(body = resp.body?.string().orEmpty())
                else Text(error = c.getString(R.string.google_err, "HTTP ${resp.code}"))
            }
        }.getOrElse { e -> Text(error = c.getString(R.string.google_err, e.message ?: "")) }
    }

    /** Новый файл: описание и содержимое одним запросом (multipart/related) —
     *  Диск создаёт такой файл сразу с телом. */
    private fun create(c: Context, tok: String, name: String, data: RequestBody): String? {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(FOLDER))
            .toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta.toRequestBody(JSON))
            .addPart(data)
            .build()
        val url = "$UPLOAD/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        val req = Request.Builder().url(url).post(body).header("Authorization", "Bearer $tok").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else c.getString(R.string.google_err, reason(resp.body?.string().orEmpty(), resp.code))
            }
        }.getOrElse { e -> c.getString(R.string.google_err, e.message ?: "") }
    }

    /** Новое содержимое уже существующего файла. */
    private fun update(c: Context, tok: String, id: String, data: RequestBody): String? {
        val url = "$UPLOAD/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id")
            .build()
        val req = Request.Builder().url(url).patch(data).header("Authorization", "Bearer $tok").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else c.getString(R.string.google_err, reason(resp.body?.string().orEmpty(), resp.code))
            }
        }.getOrElse { e -> c.getString(R.string.google_err, e.message ?: "") }
    }

    /** Человеческая причина отказа: Google кладёт её в тело ответа. */
    private fun reason(body: String, code: Int): String {
        val o = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            ?: return "HTTP $code"
        return o.optString("message").ifBlank { "HTTP $code" }
    }
}
