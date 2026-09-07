package com.cuber.bookvoice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** «Доступ ко всем файлам» (msg1170/1172): железная запись в любую папку.
 *
 *  Проблема: часть сторонних проводников (MixPlorer и т.п.) отдают SAF-tree,
 *  но их provider не умеет createDocument — любая запись через
 *  DocumentsContract падает (Invalid URI / «нет прав на запись»). Читать из
 *  такой папки можно, писать нельзя.
 *
 *  Решение: если у приложения включён «Доступ ко всем файлам»
 *  (MANAGE_EXTERNAL_STORAGE), выбранную SAF-папку можно сопоставить с реальным
 *  путём на диске (/storage/…) и писать туда обычными File — как пишет сам
 *  проводник. Это запасной путь: сначала всегда пробуем штатный SAF, и только
 *  когда он не вышел — реальный путь, а за ним внутренняя память приложения.
 */
object AllFiles {

    /** Есть ли полный доступ к файлам. До Android 11 scoped storage нет —
     *  считаем, что доступ есть (SAF там и так пишет в любое место). */
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    /** Открыть системный экран «Доступ ко всем файлам» для нашего приложения. */
    fun openSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Сопоставить SAF-tree с реальным каталогом на диске. Tree внешнего
     *  хранилища имеет вид content://…/tree/<том>:<путь>; том «primary» — это
     *  /storage/emulated/0, остальные тома Android монтирует в /storage/<том>
     *  (так выглядит и карта MixPlorer: 0A99-3BD7:Books → /storage/0A99-3BD7/Books).
     *  Не можем сопоставить (чужой sandbox-провайдер) → null, остаёмся на SAF. */
    fun resolveDir(tree: Uri): File? {
        return try {
            val doc = DocumentsContract.getTreeDocumentId(tree)
            val colon = doc.indexOf(':')
            if (colon <= 0) return null
            val volume = doc.substring(0, colon)
            var rel = doc.substring(colon + 1)
            val root = when {
                volume == "primary" -> Environment.getExternalStorageDirectory()
                else -> File("/storage/$volume")
            }
            if (!root.isDirectory) return null
            if (rel.contains("%")) rel = android.net.Uri.decode(rel)
            if (rel.isEmpty()) root else File(root, rel)
        } catch (_: Exception) {
            null
        }
    }

    /** Записать файл реальным путём в папку выбранного дерева. Вернёт File или
     *  null (нет доступа / не сопоставилось / запись не удалась). */
    fun writeViaRealPath(context: Context, tree: Uri, name: String, bytes: ByteArray): File? {
        if (!granted(context)) return null
        val dir = resolveDir(tree) ?: return null
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            f
        } catch (_: Exception) {
            null
        }
    }

    /** Проверка реальным путём: можем ли создать и удалить файл в папке дерева. */
    fun probeViaRealPath(context: Context, tree: Uri): Boolean {
        if (!granted(context)) return false
        val dir = resolveDir(tree) ?: return false
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            val f = File(dir, "BookVoice_probe")
            f.writeBytes(byteArrayOf(1))
            val ok = f.exists()
            f.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Спросить владельца: включить «Доступ ко всем файлам» — выбранная папка
     *  не даёт писать обычным SAF-способом, но заработает реальным путём. */
    fun ask(activity: android.app.Activity, explain: String) {
        if (granted(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.all_files_title)
            .setMessage(explain)
            .setPositiveButton(R.string.all_files_open) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.all_files_later, null)
            .show()
    }
}
