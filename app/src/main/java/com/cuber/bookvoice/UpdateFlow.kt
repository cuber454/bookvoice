package com.cuber.bookvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * UI автообновления (#35). Логика сети и файлов — в [Updater]; здесь — общие
 * диалоги «проверить / скачать / установить», которыми пользуются и кнопка в
 * Настройках (ручная проверка), и полка (тихая авто-проверка). Всё идёт через
 * окно-секцию [act] и показывает диалоги только пока окно живо.
 */
object UpdateFlow {

    private const val PREFS = "reader"
    private const val KEY_OFFERED = "update_offered_version"

    /** Режим авто-проверки (0.3.91, msg1918): строка-резюме в «Разном» над кнопкой
     *  «Проверить обновление». MODE_AUTO — тихая проверка при открытии не чаще раза
     *  в день; MODE_MANUAL — только кнопка, GitHub зря не дёргаем. */
    const val KEY_MODE = "update_mode"
    const val MODE_AUTO = "auto"
    const val MODE_MANUAL = "manual"
    private const val KEY_LAST_DAY = "update_auto_last_day"

    /** Ровно одна авто-проверка за процесс: первый «тихий» показ полки или
     *  возврат из книги её запускает, дальше GitHub до перезапуска не дёргаем. */
    @Volatile
    private var autoCheckedRun = false

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            .format(java.util.Date())

    private val main = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun str(act: SectionActivity, res: Int, vararg args: Any): String =
        act.getString(res, *args)

    private fun toast(act: SectionActivity, msg: String) =
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

    /** Результат — окном, которое висит (msg1942): тост и announceForAccessibility
     *  скринридер может оборвать или не успеть дочитать целиком. Модальный диалог
     *  с текстом ответа и кнопкой «Закрыть» остаётся на экране, пока юзер сам его
     *  не закроет, — ответ можно спокойно прочитать и переслушать пальцем. */
    private fun resultDialog(act: SectionActivity, msg: String) {
        if (act.isFinishing || act.isDestroyed) return
        MaterialAlertDialogBuilder(act)
            .setMessage(msg)
            .setPositiveButton(str(act, R.string.toc_close), null)
            .show()
    }

    /** Ручная проверка (кнопка в Настройках): всегда спрашивает GitHub и
     *  показывает результат — «последняя версия» либо предложение скачать. */
    fun manual(act: SectionActivity) {
        if (act.isFinishing || act.isDestroyed) return
        val loading = MaterialAlertDialogBuilder(act)
            .setMessage(str(act, R.string.update_checking))
            .setNegativeButton(str(act, R.string.toc_close), null)
            .show()
        thread {
            val release = Updater.latestRelease()
            val cur = Updater.currentVersion(act)
            val newer = release != null && Updater.isNewer(release.version, cur)
            act.runOnUiThread {
                runCatching { loading.dismiss() }
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                when {
                    release == null -> resultDialog(act, str(act, R.string.update_fail_net))
                    !newer -> resultDialog(act, str(act, R.string.update_latest, cur))
                    else -> offer(act, release)
                }
            }
        }
    }

    /** Тихая авто-проверка (полка). Режим «Вручную» (#37) её отключает: GitHub зря
     *  не дёргаем. Иначе — не чаще раза в день (KEY_LAST_DAY) и только если эту
     *  версию ещё не предлагали («Позже»). Диалог — с паузой, чтобы не накрыть
     *  озвучку имени окна, на которую полка только что вернулась. */
    fun auto(act: SectionActivity) {
        if (autoCheckedRun) return
        autoCheckedRun = true
        if (prefs(act).getString(KEY_MODE, MODE_AUTO) == MODE_MANUAL) return
        thread {
            // Сегодня уже сверялись — повторный запрос до завтра не нужен.
            if (prefs(act).getString(KEY_LAST_DAY, null) == today()) return@thread
            val release = Updater.latestRelease() ?: return@thread
            // GitHub ответил — запомнили день; если сети не было, сегодня ещё попробуем.
            prefs(act).edit().putString(KEY_LAST_DAY, today()).apply()
            val cur = Updater.currentVersion(act)
            if (!Updater.isNewer(release.version, cur)) return@thread
            if (prefs(act).getString(KEY_OFFERED, null) == release.version) return@thread
            main.postDelayed({
                if (act.isFinishing || act.isDestroyed) return@postDelayed
                // За паузу могла открыться книга — диалог поверх озвучки не
                // показываем (живой ридер = читает или вот-вот начнёт). В этот
                // запуск уже не повторим — предложим при следующем праздном показе.
                if (MainActivity.active != null) return@postDelayed
                offer(act, release)
            }, 1500)
        }
    }

    private fun offer(act: SectionActivity, release: Updater.Release) {
        if (act.isFinishing || act.isDestroyed) return
        MaterialAlertDialogBuilder(act)
            .setTitle(str(act, R.string.update_available_title))
            .setMessage(str(act, R.string.update_available_msg, release.version))
            .setPositiveButton(str(act, R.string.update_download)) { _, _ ->
                downloadAndInstall(act, release)
            }
            .setNegativeButton(str(act, R.string.update_later)) { _, _ ->
                // «Позже»: эту версию больше не навязываем — ни в этом запуске,
                // ни в следующих, пока не выйдет ещё новее.
                prefs(act).edit().putString(KEY_OFFERED, release.version).apply()
            }
            .show()
    }

    private fun downloadAndInstall(act: SectionActivity, release: Updater.Release) {
        if (act.isFinishing || act.isDestroyed) return
        // Android 8+: установка APK из «своего» источника требует разрешения.
        // Просим раньше, чем качать зря: качать 10 МБ и потом упереться в
        // запрет — плохо.
        if (!Updater.canInstallPackages(act)) {
            MaterialAlertDialogBuilder(act)
                .setTitle(str(act, R.string.update_available_title))
                .setMessage(str(act, R.string.update_perm_need))
                .setPositiveButton(str(act, R.string.update_perm_open)) { _, _ ->
                    Updater.openInstallSourceSettings(act)
                }
                .setNegativeButton(str(act, R.string.update_later), null)
                .show()
            return
        }
        val dlg = MaterialAlertDialogBuilder(act)
            .setMessage(str(act, R.string.update_downloading_wait))
            .setNegativeButton(str(act, R.string.toc_close), null)
            .setCancelable(false)
            .show()
        thread {
            var lastPct = -1
            val file = Updater.downloadApk(act, release.apkUrl) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                if (pct != lastPct) {
                    lastPct = pct
                    act.runOnUiThread {
                        if (!dlg.isShowing) return@runOnUiThread
                        dlg.setMessage(
                            if (pct >= 0) str(act, R.string.update_downloading, pct)
                            else str(act, R.string.update_downloading_wait)
                        )
                    }
                }
            }
            act.runOnUiThread {
                runCatching { dlg.dismiss() }
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (file == null) {
                    resultDialog(act, str(act, R.string.update_fail_dl))
                    return@runOnUiThread
                }
                Vibra.confirm(act)
                toast(act, str(act, R.string.update_download_ok))
                if (!Updater.launchInstaller(act)) {
                    resultDialog(act, str(act, R.string.update_fail_install))
                }
            }
        }
    }
}
