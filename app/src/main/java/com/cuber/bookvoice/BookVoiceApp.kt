package com.cuber.bookvoice

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.PrintWriter
import java.io.StringWriter

/** Глобальный сторож падений. Любое не пойманное исключение — в любой
 *  Activity, Service или фоновом потоке — сначала дописывается в diag.log
 *  (стек вызовов + короткий снимок состояния читалки), и только потом
 *  приложение умирает штатно, с системным «приложение остановлено».
 *
 *  Класс прописан в манифесте как android:name=".BookVoiceApp", поэтому его
 *  onCreate отрабатывает до первой Activity/Service — ловит краши из любого
 *  экрана, включая Настройки поверх читающей в фоне книги.
 *
 *  Зачем: падение в Настройках (0.3.29) Сергей «даже не понял, из-за чего» —
 *  без стека причина не видна. Теперь после падения достаточно нажать
 *  Настройки → «Отправить лог в Telegram»: в файле будет точное место краха.
 */
class BookVoiceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // PDFBox хранит свои ресурсы (glyphlist, afm, шрифты) в assets библиотеки
        // и читает их через AssetManager. Без init() при первом же разборе PDF он
        // падает в GlyphList.<clinit> «glyphlist.txt not found» (краш 0.3.39,
        // msg758). init() один раз при старте приложения чинит это для всех вызовов.
        PDFBoxResourceLoader.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                Diag.log(
                    this, "CRASH",
                    "падение в потоке «${thread.name}»; ${MainActivity.liveSnapshot()}:\n$sw",
                )
            } catch (_: Throwable) {
                // Лог не должен мешать умереть приложению как положено.
            }
            prev?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
