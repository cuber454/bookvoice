package com.cuber.bookvoice

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.Manifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Разрешение на интернет — «обычное»: Android выдаёт его при установке, и
 * системного диалога для него нет (в отличие от уведомлений или геолокации).
 * Но на оболочках realme/Oppo (и ряде других) доступ в сеть у конкретного
 * приложения можно выключить отдельным переключателем «Интернет» на странице
 * приложения — тогда сеть пропадает молча, без какого-либо запроса.
 *
 * Здесь: если доступ отключён — один раз за процесс показываем сообщение и
 * кнопку на страницу настроек приложения, где лежит переключатель.
 */
object PermissionNudge {

    private var warnedThisProcess = false

    /** Проверить доступ в интернет и, если он отключён, подсказать, как вернуть. */
    fun ensureInternet(activity: Activity) {
        if (warnedThisProcess) return
        val granted = activity.checkSelfPermission(Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        warnedThisProcess = true
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.net_permission_title)
            .setMessage(R.string.net_permission_message)
            .setPositiveButton(R.string.net_permission_open) { _, _ -> openAppSettings(activity) }
            .setNegativeButton(R.string.toc_close, null)
            .show()
    }

    /** Открыть системную страницу приложения (там переключатель «Интернет»). */
    fun openAppSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
            )
        } catch (_: ActivityNotFoundException) {
            // Экран настроек недоступен — ничего не поделать, молча выходим.
        }
    }
}
