package com.cuber.bookvoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Переход в группу обсуждения BookVoice (msg3008, msg3502, msg3903).
 *
 *  Группа приватная, вход по ссылке-приглашению. Первой идёт обычная
 *  https-ссылка t.me/+хеш: её принимает и приложение Telegram (открывает
 *  сразу группу), и браузер, если Telegram не установлен. Запасная —
 *  схема tg://join, она уходит в приложение напрямую. Раньше здесь была
 *  ссылка на личный чат автора; заменена по просьбе Сергея (msg3903).
 *  Обе попытки ловим: экран не должен падать из-за отсутствия обработчика.
 *
 *  Если у группы когда-нибудь появится публичное имя, хватит одной строки:
 *  INVITE_URL = "https://t.me/имя", схема tg://resolve?domain=имя.
 */
object AuthorContact {

    /** Ссылка-приглашение в группу. */
    private const val INVITE_URL = "https://t.me/+GgrlS9Rl_lU3Njk6"

    /** Хеш после «+» — вторая половина deep link tg://join. */
    private const val INVITE_HASH = "GgrlS9Rl_lU3Njk6"

    fun open(act: Activity) {
        if (!launch(act, Intent(Intent.ACTION_VIEW, Uri.parse(INVITE_URL)))) {
            launch(act, Intent(Intent.ACTION_VIEW, Uri.parse("tg://join?invite=$INVITE_HASH")))
        }
    }

    private fun launch(act: Activity, intent: Intent): Boolean = try {
        act.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}
