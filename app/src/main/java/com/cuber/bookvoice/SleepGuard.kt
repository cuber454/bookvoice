package com.cuber.bookvoice

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
