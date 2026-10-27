package com.cuber.bookvoice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Поиск ЛОКАЛЬНОГО голоса нужного языка в ДРУГОМ движке (24.10.2026).
 *
 *  Зачем. Лестница отказов (решение Сержа): повтор → локальный голос того же
 *  движка → голос другого движка. Первые два шага читалка делает сама, а третий
 *  требует знать, есть ли у ДРУГОГО движка локальный голос нашего языка: ставить
 *  движок наугад нельзя — он может заговорить по-английски или не иметь голосов
 *  вовсе.
 *
 *  Как. По очереди поднимаем каждый установленный движок (кроме того, что уже
 *  молчит), просим у него наш язык и смотрим список голосов. Первый, у кого
 *  найдётся ЛОКАЛЬНЫЙ голос этого языка, и есть ответ. Каждый пробный экземпляр
 *  сразу гасим ([TextToSpeech.shutdown]) — иначе на телефоне остались бы висеть
 *  подключения к чужим движкам.
 *
 *  Осторожно: это долгий путь (сотни миллисекунд на движок), поэтому зовём его
 *  только когда чтение уже встало, а не на каждой фразе. */
object LocalVoiceFinder {

    /** Что нашли: пакет движка и имя голоса в нём. */
    data class Found(val engine: String, val voice: String)

    /** Максимум ждём инициализацию одного движка — дальше пробуем следующий. */
    private const val INIT_WAIT_MS = 1500L

    /** Найти локальный голос языка [lang] (код вида «rus») в движке, отличном от
     *  [excludeEngine]. Ответ приходит в главном потоке, один раз: null — не нашли. */
    fun find(
        ctx: Context,
        lang: String?,
        excludeEngine: String?,
        onDone: (Found?) -> Unit,
    ) {
        val app = ctx.applicationContext
        val engines = runCatching { Dict.installedEngines(app) }.getOrDefault(linkedMapOf())
        val queue = engines.keys.filter { it != excludeEngine }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        if (queue.isEmpty()) {
            Diag.log(app, "voice", "другой движок для замены: установленных движков больше нет")
            main.post { onDone(null) }
            return
        }
        step(app, queue, 0, lang, main, onDone)
    }

    private fun step(
        app: Context,
        queue: List<String>,
        index: Int,
        lang: String?,
        main: android.os.Handler,
        onDone: (Found?) -> Unit,
    ) {
        if (index >= queue.size) {
            Diag.log(app, "voice", "другой движок для замены: локального голоса не нашлось ни в одном")
            main.post { onDone(null) }
            return
        }
        val pkg = queue[index]
        var finished = false
        var tts: TextToSpeech? = null
        // Один движок мог не ответить вовсе — тогда идём к следующему, но только
        // один раз (finished защищает от двойного ответа: init и таймаут).
        fun next(reason: String?) {
            if (finished) return
            finished = true
            runCatching { tts?.shutdown() }
            if (reason != null) Diag.log(app, "voice", "движок $pkg не подошёл: $reason")
            step(app, queue, index + 1, lang, main, onDone)
        }
        tts = runCatching {
            TextToSpeech(app) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    next("не поднялся (статус $status)")
                    return@TextToSpeech
                }
                val t = runCatching { tts }.getOrNull()
                if (t == null) {
                    next("экземпляр потерян")
                    return@TextToSpeech
                }
                // 24.10.2026: язык и список голосов спрашиваем В СТОРОНЕ. Это тоже
                // запрос к чужому движку по binder'у, и на главном потоке он вешал
                // приложение на секунды (журнал зависаний 24.10: главный поток стоял
                // в TextToSpeech.getVoices). Ответ возвращаем в главный поток.
                Thread {
                    val voice = runCatching { localVoiceOf(t, lang) }.getOrNull()
                    main.post {
                        if (finished) return@post
                        if (voice == null) {
                            next("нет локального голоса нужного языка")
                            return@post
                        }
                        finished = true
                        runCatching { t.shutdown() }
                        Diag.log(app, "voice", "другой движок для замены: $pkg, голос «$voice»")
                        onDone(Found(pkg, voice))
                    }
                }.start()
            }
        }.getOrNull()
        if (tts == null) {
            next("не создался")
            return
        }
        // Движок не ответил вовсе — не ждём вечно, пробуем следующий.
        main.postDelayed({ next("молчит дольше ${INIT_WAIT_MS / 1000} с") }, INIT_WAIT_MS)
    }

    /** Локальный (не сетевой) голос языка [lang] у движка [t]; null — нет такого. */
    private fun localVoiceOf(t: TextToSpeech, lang: String?): String? {
        val locale = lang?.let { Locale(it) }
        if (locale != null) runCatching { t.setLanguage(locale) }
        val voices = runCatching { t.voices }.getOrNull().orEmpty()
        val local = voices.filter { v ->
            runCatching { !v.isNetworkConnectionRequired }.getOrDefault(false)
        }
        if (local.isEmpty()) return null
        if (lang == null) return local.firstOrNull()?.name
        return local.firstOrNull { v ->
            runCatching { v.locale?.language == lang }.getOrDefault(false)
        }?.name
    }
}
