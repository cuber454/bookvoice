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

    /** Ровно одна авто-проверка за процесс: первый «тихий» показ полки или
     *  возврат из книги её запускает, дальше GitHub до перезапуска не дёргаем. */
    @Volatile
    private var autoCheckedRun = false

    private val main = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun str(act: SectionActivity, res: Int, vararg args: Any): String =
        act.getString(res, *args)

    private fun toast(act: SectionActivity, msg: String) =
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()

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
                    release == null -> toast(act, str(act, R.string.update_fail_net))
                    !newer -> toast(act, str(act, R.string.update_latest, cur))
                    else -> offer(act, release)
                }
            }
        }
    }

    /** Тихая авто-проверка (полка): один раз за процесс, только если эту версию
     *  ещё не предлагали (пользователь не сказал «Позже»). Диалог — с паузой,
     *  чтобы не накрыть озвучку имени окна, на которую полка только что вернулась. */
    fun auto(act: SectionActivity) {
        if (autoCheckedRun) return
        autoCheckedRun = true
        thread {
            val release = Updater.latestRelease() ?: return@thread
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
                    toast(act, str(act, R.string.update_fail_dl))
                    return@runOnUiThread
                }
                Vibra.confirm(act)
                toast(act, str(act, R.string.update_download_ok))
                if (!Updater.launchInstaller(act)) {
                    toast(act, str(act, R.string.update_fail_install))
                }
            }
        }
    }
}
