package com.cuber.bookvoice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * «Не засыпать» (msg4705/4709) — состояние энергетических запретов и дороги к
 * экранам прошивки, которые их снимают.
 *
 *  Зачем отдельный объект. На Poco (MIUI/HyperOS) и realme (ColorOS) чтение
 *  иногда замолкает с погасшим экраном — и иногда нет. Это не наша ошибка:
 *  прошивочный «сторож энергии» (MIUI power keeper, ColorOS Athena) морозит
 *  процесс по своим спискам, отдельно от общеандроидной оптимизации батареи.
 *  Морозить он может ДВА процесса: наш и движок речи — у тестера на Poco затыки
 *  ушли после снятия ограничения с RHVoice, а не с BookVoice (msg4550).
 *
 *  Что у приложения уже есть (см. KeepAwake, MediaSessionService, манифест):
 *  foreground-служба mediaPlayback, MediaSession со STATE_PLAYING,
 *  PARTIAL_WAKE_LOCK на время чтения, запрос «не ограничивать батарею».
 *  Здесь — то, что API не отдаёт: проверка запрета фоновой активности и
 *  попытки открыть прошивочные экраны автозапуска. Всё безопасно: любая
 *  функция возвращает результат, а не бросает.
 *
 *  30.09.2026 (по образцу окна @Voice Aloud Reader): сюда же добавлены проверки,
 *  которых окну не хватало, — экономия энергии, жёсткий режим ожидания, запрет
 *  фона для сети и причина последнего выхода процесса ([lastExit]). Вместе они
 *  отвечают на вопрос «почему чтение встало» без гадания, а [stateLine] уходит в
 *  журнал, чтобы присланный лог рассказывал это сам.
 */
object SleepGuard {

    /** Разрешено ли приложению игнорировать оптимизацию батареи (Doze).
     *  Спрашиваем систему каждый раз — состояние меняется в её окне, а не у нас. */
    fun ignoringBattery(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(c.packageName)
    }.getOrDefault(false)

    /** Запрещена ли приложению фоновая активность (API 28+). На MIUI/ColorOS это
     *  ОТДЕЛЬНЫЙ переключатель от «не ограничивать батарею» — приложение может
     *  быть вне Doze и всё равно не иметь права работать в фоне. */
    fun backgroundRestricted(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        }.getOrDefault(false)
    }

    // ---------------- Расширенная проверка фона (30.09.2026) ----------------
    // Взято у @Voice Aloud Reader: у них такое окно отвечает на вопрос «почему
    // чтение встало» и подсказывает дорогу по марке телефона. У нас окно «Не
    // засыпать» уже было — здесь добавлены три проверки, которых в нём не хватало,
    // и след последнего выхода процесса.

    /** Включена ли экономия энергии (Battery Saver): она режет и сеть, и таймеры. */
    fun powerSaveMode(c: Context): Boolean = runCatching {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    }.getOrDefault(false)

    /** Поддерживает ли прошивка жёсткий режим ожидания (Android 13+). */
    fun lowPowerStandbySupported(): Boolean = Build.VERSION.SDK_INT >= 33

    /** Включён ли жёсткий режим ожидания (Low Power Standby, Android 13+): экран
     *  гаснет — система глушит сеть и службы, и сетевое чтение встаёт. */
    fun lowPowerStandby(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isLowPowerStandbyEnabled
        }.getOrDefault(false)
    }

    /** Как обстоит дело с фоном для сети. Возвращаем одно из [DATA_OK],
     *  [DATA_BLOCKED], [DATA_WHITELISTED]: сетевым голосам нужен фон, а «экономия
     *  трафика» его запрещает — тогда без Wi-Fi они молчат, и это выглядит как
     *  «читалка сломалась». */
    fun dataSaver(c: Context): Int = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> DATA_BLOCKED
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> DATA_WHITELISTED
            else -> DATA_OK
        }
    }.getOrDefault(DATA_OK)

    const val DATA_OK = 0
    const val DATA_BLOCKED = 1
    const val DATA_WHITELISTED = 2

    /** Чем закончился прошлый запуск приложения — словами системы (Android 11+).
     *  Это единственный способ отличить «прошивка заморозила» от «системе не
     *  хватило памяти»: сам процесс об этом рассказать уже не может. */
    data class Exit(val timeMs: Long, val reason: String, val bySystem: Boolean)

    fun lastExit(c: Context): Exit? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(c.packageName, 0, 5).firstOrNull()
                ?: return null
            val (text, bySystem) = exitReasonText(info.reason)
            val desc = runCatching { info.description }.getOrNull()
            val full = if (desc.isNullOrBlank()) text else "$text ($desc)"
            Exit(info.timestamp, full, bySystem)
        }.getOrNull()
    }

    /** Причина выхода процесса по-русски и признак «это сделала система». */
    private fun exitReasonText(reason: Int): Pair<String, Boolean> = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "системе не хватило памяти, и она остановила приложение" to true
        ApplicationExitInfo.REASON_ANR ->
            "приложение зависло, и система его остановила" to true
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "приложение упало" to true
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "система сочла, что приложение слишком много расходует" to true
        14 -> // ApplicationExitInfo.REASON_FREEZER (Android 14, константа API 34)
            "система заморозила процесс" to true
        ApplicationExitInfo.REASON_SIGNALED ->
            "процесс убит сигналом системы" to true
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            "приложение закрыл пользователь" to false
        ApplicationExitInfo.REASON_USER_STOPPED ->
            "приложение остановлено пользователем (смахивание из недавних)" to false
        ApplicationExitInfo.REASON_EXIT_SELF ->
            "приложение завершилось само" to false
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
            "процесс не смог запуститься" to true
        else -> "система остановила процесс" to true
    }

    /** Одна строка со всеми проверками — её пишем в журнал при каждом открытии
     *  окна «Не засыпать» (и при каждом возврате в него). Смысл: присланный
     *  журнал тогда сам рассказывает, что было с батареей и сетью, и не приходится
     *  спрашивать человека про настройки, которых он не видит. */
    fun stateLine(c: Context): String {
        val sb = StringBuilder("проверка фона: ")
        sb.append(if (ignoringBattery(c)) "батарея не ограничена" else "БАТАРЕЯ ОГРАНИЧЕНА")
        sb.append(if (backgroundRestricted(c)) ", ФОН ЗАПРЕЩЁН" else ", фон разрешён")
        sb.append(if (powerSaveMode(c)) ", ЭКОНОМИЯ ЭНЕРГИИ ВКЛЮЧЕНА" else ", экономия энергии выключена")
        if (lowPowerStandbySupported()) {
            sb.append(
                if (lowPowerStandby(c)) ", ЖЁСТКИЙ РЕЖИМ ОЖИДАНИЯ ВКЛЮЧЁН"
                else ", жёсткий режим ожидания выключен"
            )
        }
        sb.append(
            when (dataSaver(c)) {
                DATA_BLOCKED -> ", ФОН ДЛЯ СЕТИ ЗАПРЕЩЁН (сетевые голоса без Wi-Fi молчат)"
                DATA_WHITELISTED -> ", фон для сети ограничен, но мы в исключениях"
                else -> ", фон для сети не ограничен"
            }
        )
        lastExit(c)?.let { e ->
            val t = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
                .format(java.util.Date(e.timeMs))
            sb.append(", прошлый выход: ${e.reason} ($t)")
        }
        return sb.toString()
    }

    /** Системный запрос «не ограничивать батарею». true — открылось окно
     *  запроса, false — открыли общий список «Оптимизация батареи» (часть
     *  прошивок окна запроса не поддерживает, там BookVoice надо найти руками). */
    fun requestIgnoreBattery(c: Context): Boolean {
        val pkg = Uri.parse("package:${c.packageName}")
        val asked = start(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        if (!asked) start(c, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return asked
    }

    /** Прошивочные экраны «Автозапуск». Порядок — от частого к редкому; первый,
     *  который система приняла, и открываем. */
    private val AUTOSTART_SCREENS = listOf(
        // MIUI / HyperOS — Poco, Xiaomi.
        "MIUI" to ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        // ColorOS — Oppo, realme, OnePlus: сборки постарше и сборки на oplus.
        "ColorOS" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        "ColorOS" to ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        "ColorOS" to ComponentName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        ),
        "ColorOS" to ComponentName(
            "com.oplus.safecenter",
            "com.oplus.safecenter.startupapp.StartupAppListActivity",
        ),
    )

    /** Открыть прошивочный экран «Автозапуск». Возвращает название прошивки,
     *  чей экран открылся, либо null — экрана нет (это честный ответ, а не
     *  поломка: пользователю остаётся ручная дорога через настройки приложения). */
    fun openAutostart(c: Context): String? {
        for ((vendor, cn) in AUTOSTART_SCREENS) {
            if (start(c, Intent().setComponent(cn))) return vendor
        }
        return null
    }

    /** Системный экран «О приложении»: на прошивках там же лежат «Автозапуск»,
     *  «Разрешить фоновую активность» и «Батарея». */
    fun openAppDetails(c: Context): Boolean =
        start(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))

    /** Системный экран «Синтез речи» — выбор движка и вход в его настройки.
     *  Снятие ограничения батареи с самого движка (RHVoice) — то, что реально
     *  помогло тестеру на Poco (msg4550). Действие не из публичного API
     *  Settings, поэтому пробуем строкой и не считаем провал ошибкой. */
    fun openTtsSettings(c: Context): Boolean = start(c, Intent(TTS_SETTINGS_ACTION))

    private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

    /** Запустить системный экран. Через try — на прошивке экрана может не быть
     *  (ActivityNotFoundException) или он окажется закрыт для чужих
     *  (SecurityException); и то и другое ловим и отдаём false. */
    private fun start(c: Context, i: Intent): Boolean = runCatching {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
