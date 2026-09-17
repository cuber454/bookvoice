package com.cuber.bookvoice

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Возврат из браузера после входа в Яндекс (msg6114).
 *
 * Ни своей кнопки, ни своего экрана у этого окна нет: владелец входит на
 * странице Яндекса, а система по адресу `bookvoice://oauth` открывает это окно —
 * код приходит в адресе. Меняем код на токен ([YandexDisk.exchange]) и сразу
 * закрываемся: владелец возвращается в Настройки, где строка «Яндекс.Диск»
 * покажет, что вход выполнен.
 *
 * Код и токен в журнал не пишем: журнал владелец отправляет нам в Telegram.
 */
class YandexAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            // В адресе может стоять причина отказа — её писать можно, а вот сам
            // адрес целиком нет: в нём бывает код.
            Diag.log(this, "sync", "вход в Яндекс: кода нет, error=" +
                (data?.getQueryParameter("error") ?: "не указана"))
            toast(getString(R.string.yandex_login_fail))
            finish()
            return
        }
        val state = data.getQueryParameter("state")
        Thread {
            val fail = YandexDisk.exchange(applicationContext, code, state)
            runOnUiThread {
                toast(getString(if (fail == null) R.string.yandex_login_ok else R.string.yandex_login_fail))
                finish()
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
}
