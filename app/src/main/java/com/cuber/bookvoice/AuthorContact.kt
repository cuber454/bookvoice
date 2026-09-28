package com.cuber.bookvoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Переход в группу обсуждения BookVoice (msg3008, msg3502, msg3903).
 *
 *  Группа приватная, вход по ссылке-приглашению. Порядок попыток важен и
 *  однажды уже был причиной жалобы (26.09.2026): сначала идём по схеме
 *  tg://join — её регистрирует само приложение Telegram, и браузер на неё не
 *  претендует, поэтому группа открывается прямо в Telegram, без интернета.
 *  Запасная — обычная https-ссылка t.me/+хеш: она нужна только там, где
 *  Telegram не установлен, и её открывает браузер.
 *
 *  Раньше было наоборот, и это молча ломалось: https-ссылку «умеет» открывать
 *  браузер по умолчанию, поэтому попытка считалась удачной, до схемы tg://
 *  дело не доходило, а сам t.me без VPN в России недоступен — владелец видел
 *  пустую страницу. Теперь веб-ссылка остаётся лишь запасным путём.
 *
 *  Обе попытки ловим: экран не должен падать из-за отсутствия обработчика.
 *  Если у группы когда-нибудь появится публичное имя, хватит одной строки:
 *  INVITE_URL = "https://t.me/имя", схема tg://resolve?domain=имя.
 */
object AuthorContact {

    /** Ссылка-приглашение в группу. */
    private const val INVITE_URL = "https://t.me/+GgrlS9Rl_lU3Njk6"

    /** Хеш после «+» — вторая половина deep link tg://join. */
    private const val INVITE_HASH = "GgrlS9Rl_lU3Njk6"

    fun open(act: Activity) {
        if (!launch(act, Intent(Intent.ACTION_VIEW, Uri.parse("tg://join?invite=$INVITE_HASH")))) {
            launch(act, Intent(Intent.ACTION_VIEW, Uri.parse(INVITE_URL)))
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
