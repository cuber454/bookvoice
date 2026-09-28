package com.cuber.bookvoice

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Scope
import org.json.JSONObject

/**
 * Вход в Google (28.09.2026).
 *
 * Браузерный вход Google для Android-приложений запрещён, поэтому окно входа
 * показывает сама библиотека Google: она даёт выбрать аккаунт в телефоне и
 * спросить доступ к служебной папке приложения. Нам остаётся запомнить, каким
 * аккаунтом вошли, — по нему потом молча берётся токен ([GoogleDrive.token]).
 *
 * Своего экрана у окна нет: оно нужно только чтобы Google показал свой запрос.
 *
 * 28.09.2026, первая проверка на телефоне: Google отвечает УСПЕХом, но с
 * неразрешённым запросом — в ответе лежит [AuthorizationResult.hasResolution] и
 * `pendingIntent` с экраном согласия. Токена и имени аккаунта в таком ответе ещё
 * нет, поэтому раньше вход обрывался сразу («аккаунт в ответе не назван»).
 * Теперь мы сначала запускаем этот экран, а имя аккаунта берём из ответа уже
 * после согласия: сперва из библиотеки, затем из токена-идентификатора, а если
 * на телефоне всего один гугловый аккаунт — прямо из него.
 */
class GoogleAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GoogleDrive.available()) {
            Diag.log(this, "sync", "вход в Google: на телефоне нет библиотеки Google Play")
            toast(getString(R.string.google_no_play))
            finish()
            return
        }
        ask()
    }

    /** Запрос доступа. Если Google захочет показать свой экран (выбор аккаунта или
     *  согласие), он вернёт «разрешимую» ошибку — её и запускаем. */
    private fun ask() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope("openid"),
                    Scope("email"),
                    Scope(GoogleDrive.SCOPE),
                )
            )
            .build()
        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result -> onResult(result) }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    runCatching { e.startResolutionForResult(this, REQ_AUTH) }.onFailure {
                        Diag.log(this, "sync", "вход в Google: экран согласия не открылся")
                        fail()
                    }
                } else {
                    Diag.log(this, "sync", "вход в Google: ${e.javaClass.simpleName} " +
                        (e as? ApiException)?.statusCode.orEmptyStatus())
                    fail()
                }
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTH) return
        if (data == null) {
            Diag.log(this, "sync", "вход в Google: владелец закрыл экран согласия")
            fail()
            return
        }
        val result = runCatching {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
        }.getOrNull()
        if (result == null) {
            Diag.log(this, "sync", "вход в Google: ответ без результата")
            fail()
            return
        }
        onResult(result)
    }

    /** Ответ Google: либо «покажи человеку экран согласия», либо готовый доступ. */
    private fun onResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pending = result.pendingIntent
            if (pending == null) {
                Diag.log(this, "sync", "вход в Google: ответ просит согласие, а экрана нет")
                fail()
                return
            }
            runCatching {
                startIntentSenderForResult(pending.intentSender, REQ_AUTH, null, 0, 0, 0)
            }.onFailure {
                Diag.log(this, "sync", "вход в Google: экран согласия не открылся " +
                    "(${it.javaClass.simpleName})")
                fail()
            }
            return
        }
        finishWith(result)
    }

    /** Доступ выдан — запоминаем аккаунт. Почта нужна как имя аккаунта для
     *  тихого получения токена; без неё вход считать несостоявшимся. */
    private fun finishWith(result: AuthorizationResult) {
        val email = emailOf(result)
        if (email.isNullOrBlank()) {
            Diag.log(this, "sync", "вход в Google: аккаунт в ответе не назван (${describe(result)})")
            fail()
            return
        }
        GoogleDrive.saveAccount(applicationContext, email)
        Diag.log(this, "sync", "вход в Google: $email, доступов ${result.grantedScopes?.size ?: 0}")
        // Сразу проверяем, что доступ к папке приложения действительно есть.
        // Сразу после согласия Google бывает отвечает «NeedRemoteConsent»: доступ
        // выдан, а сервер токена ещё не в курсе. Поэтому проверка терпеливая, а
        // если Google всё-таки просит свой экран — показываем его.
        Thread {
            val err = GoogleDrive.checkPatiently(applicationContext)
            val consent = if (err != null) GoogleDrive.consentIntent(applicationContext) else null
            runOnUiThread {
                if (err == null) {
                    toast(getString(R.string.google_login_ok))
                } else {
                    Diag.log(applicationContext, "sync", "после входа: $err; экран согласия: ${consent != null}")
                    if (consent != null) {
                        toast(getString(R.string.google_consent_asking))
                        runCatching { startActivity(consent) }
                    } else {
                        toast(getString(R.string.google_login_fail))
                    }
                }
                finish()
            }
        }.start()
    }

    /** Чем вошли. Способы по порядку: как назвала библиотека, поле `email` в
     *  токене-идентификаторе и, наконец, единственный гугловый аккаунт телефона. */
    private fun emailOf(result: AuthorizationResult): String? {
        runCatching { result.toGoogleSignInAccount()?.email }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        emailFromToken(result.accessToken)?.let { return it }
        return googleAccounts().singleOrNull()
    }

    private fun googleAccounts(): List<String> = runCatching {
        AccountManager.get(this).getAccountsByType(ACCOUNT_TYPE).map { it.name }
    }.getOrDefault(emptyList())

    /** Токен-идентификатор (JWT) состоит из трёх частей через точку; во второй
     *  лежит полезная нагрузка, а в ней — почта владельца. */
    private fun emailFromToken(token: String?): String? {
        val parts = token?.split('.') ?: return null
        if (parts.size != 3) return null
        return runCatching {
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val json = String(Base64.decode(parts[1], flags), Charsets.UTF_8)
            JSONObject(json).optString("email").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Что именно пришло — для журнала, когда почта так и не нашлась. */
    private fun describe(result: AuthorizationResult): String {
        val token = result.accessToken
        val tokenKind = when {
            token.isNullOrBlank() -> "токена нет"
            token.count { it == '.' } == 2 -> "токен-идентификатор есть"
            else -> "токен не идентификатор"
        }
        return "$tokenKind, доступов ${result.grantedScopes?.size ?: 0}, " +
            "гугловых аккаунтов на телефоне ${googleAccounts().size}"
    }

    private fun fail() {
        toast(getString(R.string.google_login_fail))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun Int?.orEmptyStatus(): String = this?.let { "код $it" } ?: ""

    private companion object {
        const val REQ_AUTH = 4181
        const val ACCOUNT_TYPE = "com.google"
    }
}
