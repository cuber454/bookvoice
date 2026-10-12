package com.cuber.bookvoice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileInputStream

/**
 * Проба движка: отвечает ли, даёт ли файлы, применяет ли НАШУ скорость и успевает
 * ли он писать фразы быстрее, чем они звучат (опытные настройки, 06.10.2026;
 * расширено 07.10.2026 по просьбе Сержа: «сделай контрольными фразами и словами
 * вслух: отвечает ли, даёт ли файлы, применил ли скорость, нет ли разрыва на
 * стыке»; идея — экран диагностики у MyTTS).
 *
 *  Зачем. Половина наших разборов упиралась в одно и то же: движок «выглядит
 *  рабочим», а на деле не отдаёт файлы (прослойка MultiTTSLexx — 44 байта),
 *  игнорирует наш темп (MultiTTS с «Приоритетом конфигурации») или пишет фразу
 *  дольше, чем она звучит (и тогда стык рвётся паузой). Понимать это по журналу
 *  Серж не может — журнал он не читает. Проба отвечает механически и говорит
 *  вывод СЛОВАМИ.
 *
 *  Что делает по шагам:
 *  1. просит движок сделать ФАЙЛ на обычном темпе — по размеру видно, даёт ли он
 *     файлы вообще, по времени видно, сколько он пишет, а по заголовку WAV —
 *     сколько эта фраза звучит;
 *  2. просит тот же текст на двойном темпе — если файл почти не укоротился, наш
 *     темп движок игнорирует;
 *  3. если файлов нет вовсе — говорит ту же фразу дважды (обычный и двойной
 *     темп) и считает знаки в секунду;
 *  4. сводит всё в короткий вывод: отвечает, файлы, скорость, стык.
 *
 *  Почему отдельный экземпляр синтезатора. Проба идёт из окна настроек, когда
 *  чтение стоит (иначе она мешала бы чтению — см. [SpeechPlayer.sounding]).
 *  Свой экземпляр не трогает ни голос, ни очередь живого плеера: после пробы он
 *  гасится. Голос не выбираем: у MultiTTS голос выбирается внутри самого движка,
 *  и его же выберет проба, — значит вывод относится ровно к тому голосу, которым
 *  человек и читает.
 *
 *  Про звук вслух: движок-прослойка на просьбу «сделай файл» читает сам, поэтому
 *  проба может заговорить. Это не ошибка — об этом и говорим перед началом. */
class EngineProbe(private val ctx: Context, private val pkg: String?) {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var onResult: ((String) -> Unit)? = null
    private var finished = false

    /** Шаг пробы: 0 и 1 — файлы на 1.0 и 2.0, 2 и 3 — речь на 1.0 и 2.0. */
    private var step = 0
    private var startedAt = 0L
    private var timeout: Runnable? = null

    /** Замеры: файл на обычном темпе, файл на двойном, время письма и звучания. */
    private var file1Bytes = -1L
    private var file2Bytes = -1L
    private var synthMs = -1L
    private var audioMs = -1L

    /** Знаки в секунду, если файлов движок не даёт (мерим речью). */
    private var speakCps1 = 0f
    private var speakCps2 = 0f

    private val tempFiles = ArrayList<File>()

    fun start(onResult: (String) -> Unit) {
        this.onResult = onResult
        Diag.log(
            ctx, "tts",
            "проба движка ${pkg ?: "системный"}: файл и речь на ${RATES.joinToString(" и ")}, " +
                "контрольная фраза ${TEXT.length} знаков"
        )
        val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(status) } }
        tts = runCatching {
            if (pkg != null) TextToSpeech(ctx, listener, pkg) else TextToSpeech(ctx, listener)
        }.getOrNull()
        if (tts == null) {
            finish("Проба: движок ${pkg ?: "системный"} не создался вовсе.")
        }
    }

    private fun onInit(status: Int) {
        if (finished) return
        if (status != TextToSpeech.SUCCESS) {
            finish("Проба: движок ${pkg ?: "системный"} не поднялся (ответ $status).")
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { startedAt = SystemClock.elapsedRealtime() }
            }

            override fun onDone(utteranceId: String?) {
                main.post { onStepDone(utteranceId) }
            }

            override fun onError(utteranceId: String?) {
                main.post { onStepError(utteranceId, 0) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { onStepError(utteranceId, errorCode) }
            }
        })
        step = 0
        nextStep()
    }

    private fun nextStep() {
        if (finished) return
        when (step) {
            0 -> synthFile(RATES[0], ID_FILE_1)
            1 -> synthFile(RATES[1], ID_FILE_2)
            2 -> speakPhrase(RATES[0], ID_SPEAK_1)
            3 -> speakPhrase(RATES[1], ID_SPEAK_2)
            else -> finish(verdict())
        }
    }

    /** Попросить движок сделать файл контрольной фразы. */
    private fun synthFile(rate: Float, id: String) {
        val t = tts ?: return
        val f = runCatching { File.createTempFile("probe", ".wav", ctx.cacheDir) }.getOrNull()
        if (f == null) {
            finish("Проба: не смог завести временный файл.")
            return
        }
        tempFiles.add(f)
        startedAt = SystemClock.elapsedRealtime()
        runCatching { t.setSpeechRate(rate) }
        val r = runCatching { t.synthesizeToFile(TEXT, null, f, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            // Движок отказался делать файл вовсе: это и есть ответ «файлов не
            // даёт» — переходим к проверке речью.
            Diag.log(ctx, "tts", "проба: $id — движок отказался делать файл (ответ $r)")
            file1Bytes = 0L
            step = 2
            nextStep()
            return
        }
        armTimeout()
    }

    /** Сказать контрольную фразу (для движков без файлов). */
    private fun speakPhrase(rate: Float, id: String) {
        val t = tts ?: return
        startedAt = SystemClock.elapsedRealtime()
        runCatching { t.setSpeechRate(rate) }
        val r = runCatching { t.speak(TEXT, TextToSpeech.QUEUE_FLUSH, null, id) }
            .getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            finish("Проба: движок отказался говорить (ответ $r).")
            return
        }
        armTimeout()
    }

    private fun onStepDone(utteranceId: String?) {
        if (finished) return
        clearTimeout()
        val ms = SystemClock.elapsedRealtime() - startedAt
        when (utteranceId) {
            ID_FILE_1 -> {
                val f = tempFiles.lastOrNull()
                file1Bytes = f?.length() ?: 0L
                synthMs = ms
                audioMs = f?.let { wavDurationMs(it) } ?: 0L
                Diag.log(
                    ctx, "tts",
                    "проба: файл на ${RATES[0]} — ${file1Bytes} байт за $ms мс, звучит $audioMs мс"
                )
                step = 1
            }
            ID_FILE_2 -> {
                file2Bytes = tempFiles.lastOrNull()?.length() ?: 0L
                Diag.log(ctx, "tts", "проба: файл на ${RATES[1]} — $file2Bytes байт за $ms мс")
                step = 2
            }
            ID_SPEAK_1 -> {
                speakCps1 = cps(ms)
                Diag.log(ctx, "tts", "проба: речь на ${RATES[0]} — ${fmt(speakCps1)} знаков в секунду")
                step = 3
            }
            ID_SPEAK_2 -> {
                speakCps2 = cps(ms)
                Diag.log(ctx, "tts", "проба: речь на ${RATES[1]} — ${fmt(speakCps2)} знаков в секунду")
                step = 4
            }
        }
        nextStep()
    }

    private fun onStepError(utteranceId: String?, code: Int) {
        if (finished) return
        clearTimeout()
        // Ошибка на файле значит «файлов не даёт» (у прослойки бывает и так);
        // ошибка на речи — движок не говорит вовсе.
        if (utteranceId == ID_FILE_1 || utteranceId == ID_FILE_2) {
            Diag.log(ctx, "tts", "проба: $utteranceId — ошибка файла ($code)")
            if (file1Bytes < 0L) file1Bytes = 0L
            step = 2
            nextStep()
            return
        }
        finish("Проба: движок не смог сказать контрольную фразу (ошибка $code).")
    }

    private fun armTimeout() {
        clearTimeout()
        val r = Runnable {
            if (finished) return@Runnable
            Diag.log(ctx, "tts", "проба: шаг $step не ответил за ${TIMEOUT_MS / 1000} с")
            // Что успели измерить — уже ответ; совсем пусто — так и скажем.
            if (file1Bytes >= 0L || speakCps1 > 0f) finish(verdict())
            else finish("Проба: движок не отозвался — ни файла, ни речи.")
        }
        timeout = r
        main.postDelayed(r, TIMEOUT_MS)
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    /** Знаков в секунду по замеру речи. */
    private fun cps(ms: Long): Float = if (ms > 200) TEXT.length * 1000f / ms else 0f

    /** Вывод словами: отвечает, файлы, скорость, стык. Короткими фразами —
     *  это читает диктор, длинную простыню слушать нельзя. */
    private fun verdict(): String {
        clearTimeout()
        val who = pkg ?: "системный"
        val files = file1Bytes >= MIN_FILE_BYTES
        val parts = ArrayList<String>()
        parts.add("Проба движка $who.")
        if (files) {
            parts.add("Файлы даёт: контрольная фраза ${file1Bytes / 1024} килобайт.")
        } else {
            parts.add(
                "Файлов не даёт — читает сам (в файле ${if (file1Bytes < 0) 0 else file1Bytes} байт). " +
                    "Для нас это альтернативный способ чтения."
            )
        }
        // 07.10.2026: заодно узнаём и запоминаем, сколько движок готовит ПЕРВУЮ
        // фразу. Проба поднимает свой синтезатор, поэтому её первый замер — это
        // и есть холодная подготовка (у MyTTS в журнале Сержа — 20 секунд).
        // Число уходит в настройки: окно терпения при старте чтения берётся по
        // нему (см. [ExpertPrefs.firstPrepMs] и [SpeechPlayer.warmWindow]).
        firstPrepVerdict()?.let { parts.add(it) }
        parts.add(speedVerdict(files))
        seamVerdict(files)?.let { parts.add(it) }
        Diag.log(ctx, "tts", "проба движка $who: " + parts.joinToString(" "))
        return parts.joinToString(" ")
    }

    /** Сколько движок готовил первую фразу — и запись этого числа в пометки. */
    private fun firstPrepVerdict(): String? {
        if (synthMs <= 0L) return null
        val voice = runCatching {
            ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_VOICE, null)
        }.getOrNull()
        ExpertPrefs.setFirstPrepMs(ctx, pkg, voice, synthMs)
        val sec = "%.1f".format(synthMs / 1000f)
        return if (synthMs >= MIN_SLOW_FIRST_MS) {
            "Первую фразу движок готовил $sec секунды — столько я и буду ждать перед " +
                "первым звуком, движок при этом не трогаю."
        } else {
            "Первую фразу движок взялся писать сразу ($sec секунды)."
        }
    }

    /** Применяет ли движок нашу скорость. У движков с файлами это видно по
     *  размеру: на двойном темпе файл обязан стать заметно короче. Без файлов
     *  считаем знаки в секунду (как проба 249). */
    private fun speedVerdict(files: Boolean): String {
        val one = RATES[0]
        val two = RATES[1]
        if (files && file1Bytes > 0 && file2Bytes > 0) {
            val shrink = 1f - file2Bytes.toFloat() / file1Bytes.toFloat()
            return if (shrink >= SHRINK_ENOUGH) {
                "Нашу скорость применяет: файл на двойном темпе короче на ${(shrink * 100).toInt()} процентов."
            } else {
                "Нашу скорость НЕ применяет: файлы на темпе $one и $two почти одного размера " +
                    "(${file1Bytes / 1024} и ${file2Bytes / 1024} килобайт). Если это MultiTTS — " +
                    "проверь в нём настройку «Приоритет конфигурации»."
            }
        }
        if (speakCps1 <= 0f || speakCps2 <= 0f) {
            return "Скорость проверить не вышло: замер речи не получился."
        }
        val n1 = fmt(speakCps1)
        val n2 = fmt(speakCps2)
        return if (speakCps2 >= speakCps1 * RATIO_ENOUGH) {
            "Нашу скорость применяет: на обычном темпе $n1 знаков в секунду, на двойном $n2."
        } else {
            "Нашу скорость НЕ применяет: на обычном темпе $n1 знаков в секунду, на двойном $n2 " +
                "(должно было быть заметно больше). Если это MultiTTS — проверь в нём настройку " +
                "«Приоритет конфигурации»."
        }
    }

    /** Успевает ли движок писать фразы быстрее, чем они звучат. Если нет —
     *  на длинных фразах будет пауза на стыке. */
    private fun seamVerdict(files: Boolean): String? {
        if (!files || synthMs <= 0L || audioMs <= 0L) return null
        val per100synth = synthMs * 100.0 / TEXT.length
        val per100audio = audioMs * 100.0 / TEXT.length
        val synthS = "%.1f".format(per100synth / 1000f)
        val audioS = "%.1f".format(per100audio / 1000f)
        return if (per100synth <= per100audio) {
            "Стык успевает: сто знаков движок пишет за $synthS секунды, а звучат они $audioS."
        } else {
            "Стык НЕ успевает: сто знаков движок пишет за $synthS секунды, а звучат они $audioS — " +
                "на длинных фразах будет пауза. Помогает больше заготовок впрок."
        }
    }

    /** Сколько звучит файл с речью: берём из заголовка WAV (байт в секунду).
     *  Не WAV или заголовок битый — нуля хватит, стык просто не оценим. */
    private fun wavDurationMs(f: File): Long = runCatching {
        val head = ByteArray(44)
        FileInputStream(f).use { if (it.read(head) < 44) return@runCatching 0L }
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF") return@runCatching 0L
        fun le32(at: Int): Int =
            (head[at].toInt() and 0xFF) or
                ((head[at + 1].toInt() and 0xFF) shl 8) or
                ((head[at + 2].toInt() and 0xFF) shl 16) or
                ((head[at + 3].toInt() and 0xFF) shl 24)
        val byteRate = le32(28)
        if (byteRate <= 0) return@runCatching 0L
        val data = (f.length() - 44).coerceAtLeast(0L)
        data * 1000L / byteRate
    }.getOrDefault(0L)

    private fun fmt(v: Float): String = "%.1f".format(v)

    private fun finish(text: String) {
        if (finished) return
        finished = true
        clearTimeout()
        Diag.log(ctx, "tts", "проба движка: $text")
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
        val cb = onResult
        onResult = null
        cb?.invoke(text)
    }

    private companion object {
        /** Контрольная фраза: около сотни знаков и есть вопрос — заодно видно,
         *  что движок не давится знаком вопроса. Без цифр: разбор чисел — дело
         *  движка, и на нём проба сбилась бы. */
        const val TEXT =
            "Это проверка движка чтения. Слышно ли каждое слово? Да, слышно ясно и ровно."

        /** Какие темпы сравниваем. Вдвое — потому что разницу в два раза слышно и
         *  она не зависит от того, как движок трактует наши десятые. */
        val RATES = listOf(1.0f, 2.0f)

        const val ID_FILE_1 = "probe_file_1"
        const val ID_FILE_2 = "probe_file_2"
        const val ID_SPEAK_1 = "probe_speak_1"
        const val ID_SPEAK_2 = "probe_speak_2"

        /** Меньше этого файл — не звук, а один заголовок WAV (у движка-прослойки
         *  ровно 44 байта). Считаем, что файлов движок не даёт. */
        const val MIN_FILE_BYTES = 4096L

        /** С какого времени подготовку первой фразы считаем долгой и говорим об
         *  этом вслух (07.10.2026, см. [firstPrepVerdict]). */
        const val MIN_SLOW_FIRST_MS = 2_000L

        /** Насколько файл на двойном темпе обязан укоротиться, чтобы считать, что
         *  движок нас послушался. Не строго вдвое: у сетевых голосов шаг темпа
         *  грубее, и молчание по краям файла тоже даёт разницу. */
        const val SHRINK_ENOUGH = 0.25f

        /** Во сколько раз должен вырасти темп речи (когда файлов нет). */
        const val RATIO_ENOUGH = 1.3f

        const val TIMEOUT_MS = 25_000L
    }
}
