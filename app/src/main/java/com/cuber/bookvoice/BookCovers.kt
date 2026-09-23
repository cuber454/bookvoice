package com.cuber.bookvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Обложки для карточек-сетки на полке (23.09.2026, просьба Сергея: «хочу, чтобы
 * показывалось как в FBReader»).
 *
 * Правила, ради которых это написано именно так:
 *
 * 1. **Обложка — украшение, а не элемент.** Для диктора карточка остаётся одним
 *    узлом с названием, автором и процентом (см. BookAdapter.cardText); картинка
 *    помечена как неважная для доступности. Иначе на каждой книге диктор
 *    говорил бы «картинка», и свайпов стало бы вдвое больше.
 * 2. **Чего нет — дорисовываем.** Настоящую обложку можно достать не у всякой
 *    книги: в TXT, DOCX и ODT её нет вовсе. Вместо серой дыры рисуем обложку из
 *    названия (как делает FBReader на скриншоте Сергея): цветной фон,
 *    название словами. Вёрстка от этого не прыгает — размер всегда один.
 * 3. **Файл книги — не наш, читаем бережно.** Книга лежит в чужом дереве
 *    (SAF), открывается потоком. Поэтому FB2 разбираем потоково (XmlPullParser),
 *    а EPUB — несколькими проходами по архиву, каждый до первой нужной записи.
 *    Целиком в память книгу не тянем: она бывает в десятки мегабайт.
 * 4. **Считаем один раз.** Готовые байты картинки кладём на диск
 *    (`filesDir/covers`), декодированный битмап — в память. Каталог подрезаем
 *    по объёму и по числу файлов, чтобы он не рос бесконечно; в настройках
 *    обложки входят в ОБЩУЮ строку «Кэш» — отдельной настройки у них нет по
 *    просьбе Сергея (23.09.2026), поэтому и размер, и очистка общие с
 *    разобранным текстом ([BookCache]).
 *
 * Обложек нет в кэше книги — она достаётся в фоне одной ниткой: полка из
 * трёхсот книг не должна думать вместо того, чтобы открываться.
 */

/** Откуда карточка-сетка берёт картинку. Реализация — [BookCovers]. */
interface CoverSource {
    /** Готовая обложка из памяти; null — ещё не достали (или её нет). */
    fun cached(rec: BookRecord): Bitmap?

    /** Что показать, пока настоящая обложка не готова: нарисованная из названия. */
    fun placeholder(rec: BookRecord): Bitmap?

    /** Достать настоящую обложку в фоне. [onReady] — в главном потоке. */
    fun request(rec: BookRecord, onReady: (Bitmap) -> Unit)
}

internal class BookCovers(
    private val appContext: Context,
    /** Размер, под который декодируем и рисуем: карточка сетки. */
    private val widthPx: Int,
    private val heightPx: Int,
) : CoverSource {

    internal companion object {
        /** Потолок на картинку обложки: больше в книгах не бывает, а память конечна. */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** Потолок на служебный XML (контейнер EPUB, опись, страница обложки). */
        const val MAX_XML_BYTES = 1024 * 1024

        /** Потолок на книгу внутри архива: fb2 в zip бывает большим. */
        const val MAX_ZIP_BOOK_BYTES = 32 * 1024 * 1024

        /** С какого времени рисование заглушки считаем заметным и пишем в журнал.
         *  Кадр при 60 Гц — 16 мс; берём половину, чтобы видеть приближение. */
        const val SLOW_DRAW_MS = 8L

        /** С какого времени чтение обложки из кэша считаем медленным (обычно
         *  единицы миллисекунд; всё, что дольше, — повод посмотреть в журнал). */
        const val SLOW_LOAD_MS = 50L

        /** Сколько файлов обложек держим на диске. */
        const val MAX_CACHE_FILES = 300

        /** Потолок кэша обложек по объёму (23.09.2026): в строке настроек
         *  «Кэш» показан ОБЩИЙ объём вместе с разобранным текстом, и своей
         *  отдельной строки у обложек нет — Сергей просил оставить одну
         *  настройку, как было. Поэтому обложки подрезаются по байтам, а не
         *  по числу файлов: одна обложка весит 50 КБ, другая полмегабайта. */
        const val MAX_CACHE_BYTES = 32L * 1024L * 1024L

        /** Цвета для нарисованных обложек: приглушённые, чтобы не рябило. */
        val PALETTE = intArrayOf(
            0xFF6D4C41.toInt(), // коричневый
            0xFF455A64.toInt(), // синий серый
            0xFF5D4037.toInt(), // тёмно-коричневый
            0xFF37474F.toInt(), // графит
            0xFF4E342E.toInt(), // кофе
            0xFF33691E.toInt(), // тёмно-зелёный
            0xFF4A148C.toInt(), // фиолетовый
            0xFF880E4F.toInt(), // винный
            0xFF01579B.toInt(), // синий
            0xFF3E2723.toInt(), // почти чёрный
        )

        /** Общее на всё приложение: окно библиотеки открывается заново, а
         *  кэш обложек переезжать вместе с ним не должен. */
        private val main = Handler(Looper.getMainLooper())

        /** Одна нитка: распаковка архива — работа тяжёлая, а обложек нужно немного. */
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "book-covers").apply { isDaemon = true }
        }

        private val memory = object : LruCache<String, Bitmap>(memoryBudget()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

        /** Половина доступной приложению памяти под обложки: на полке их видно
         *  единицы, но при быстрой прокрутке кэш растёт. */
        private fun memoryBudget(): Int {
            val max = Runtime.getRuntime().maxMemory() / 1024
            return (max / 8).toInt().coerceAtLeast(4 * 1024)
        }

        private var coverDir: File? = null

        /** Книги, у которых обложки нет: второй раз файл не открываем. */
        private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

        /** Книги, обложка которых уже достаётся: повторно в очередь не ставим. */
        private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

        /** Каталог кэша обложек одним местом: и у окна полки, и у общей
         *  очистки кэша в настройках он должен быть один и тот же. */
        private fun dirOf(context: Context): File =
            coverDir ?: File(context.filesDir, "covers").also { coverDir = it }

        /** Файл обложки книги: имя из хэша адреса, внутри — байты картинки. */
        private fun diskFileOf(context: Context, key: String): File =
            File(dirOf(context), "${diskNameOf(key)}.img")

        /** Рядом — размер файла книги на момент доставания обложки. По нему
         *  видно, что книгу заменили по тому же адресу (тот же приём, что в
         *  BookCache.signature): кэш обложки тогда снимается и берётся заново. */
        private fun sizeFileOf(context: Context, key: String): File =
            File(dirOf(context), "${diskNameOf(key)}.size")

        private fun diskNameOf(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        /** Подрезать кэш обложек: сначала по объёму ([MAX_CACHE_BYTES]), вторым
         *  предохранителем — по числу файлов ([MAX_CACHE_FILES]). Сносим самые
         *  давно не использованные (дату обращения обновляем при чтении).
         *  Считаем по факту, как BookCache.usedBytes: тогда в цифру попадают и
         *  файлы-сироты прошлых версий. */
        private fun prune(d: File) {
            val files = d.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".img") }
                ?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            var index = 0
            while (index < files.size &&
                (total > MAX_CACHE_BYTES || files.size - index > MAX_CACHE_FILES)
            ) {
                val f = files[index++]
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    // Рядом лежал размер оригинала — уходит вместе с картинкой.
                    runCatching {
                        File(f.parentFile, f.name.removeSuffix(".img") + ".size").delete()
                    }
                }
            }
        }

        /** Сколько места заняли обложки: цифра складывается с разобранным текстом
         *  в ОДНОЙ строке «Кэш» в настройках — отдельной настройки у обложек
         *  нет по просьбе Сергея (23.09.2026). */
        fun usedBytes(context: Context): Long {
            var total = 0L
            dirOf(context).listFiles()?.forEach { if (it.isFile) total += it.length() }
            return total
        }

        /** Очистка кэша обложек — та же кнопка, что чистит разобранный текст. */
        fun clearAll(context: Context) {
            var n = 0
            dirOf(context).listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
            memory.evictAll()
            missing.clear()
            inFlight.clear()
            Diag.log(context, "cache", "обложки очищены вручную: файлов $n")
        }

        /** Книга ушла из библиотеки (удаление, очистка категории, склейка
         *  дублей) — убираем и её обложку, как убираем разобранный текст.
         *  Иначе картинки удалённых книг лежат в кэше сиротами. */
        fun remove(context: Context, uri: Uri) {
            val key = uri.toString()
            runCatching { diskFileOf(context, key).delete() }
            runCatching { sizeFileOf(context, key).delete() }
            memory.remove(key)
            missing.remove(key)
        }
    }

    override fun cached(rec: BookRecord): Bitmap? = memory.get(cacheKey(rec))

    override fun placeholder(rec: BookRecord): Bitmap? {
        val title = rec.displayTitle.trim().ifEmpty { return null }
        val key = "ph|$widthPx x $heightPx|$title"
        memory.get(key)?.let { return it }
        // Заглушка рисуется в ГЛАВНОМ потоке — единственная работа по обложкам,
        // которая вообще может подёргать прокрутку. Поэтому её время пишем в
        // журнал (начиная с заметного порога): после проверки на телефоне
        // видно, стоит ли это оптимизировать, а не гадать.
        val started = System.nanoTime()
        val drawn = runCatching { drawPlaceholder(title) }.getOrNull() ?: return null
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms >= SLOW_DRAW_MS) {
            Diag.log(appContext, "covers", "заглушка «$title»: $ms мс (рисуется в главном потоке)")
        }
        memory.put(key, drawn)
        return drawn
    }

    override fun request(rec: BookRecord, onReady: (Bitmap) -> Unit) {
        val key = cacheKey(rec)
        memory.get(key)?.let { onReady(it); return }
        if (missing.contains(key)) return
        if (!inFlight.add(key)) return
        worker.execute {
            val started = System.nanoTime()
            val ms = { (System.nanoTime() - started) / 1_000_000 }
            try {
                // Размер оригинала спрашиваем один раз на попытку: по нему
                // понимаем, не заменили ли книгу под тем же адресом.
                val sourceBytes = runCatching { Uri.parse(rec.uri) }.getOrNull()
                    ?.let { sourceSize(it) } ?: -1L
                val fromDisk = loadFromDisk(key, sourceBytes)
                if (fromDisk != null && ms() >= SLOW_LOAD_MS) {
                    // Обложка нашлась в кэше, но диск ответил медленно — это
                    // видно в журнале (медленная карта памяти, занятый файл).
                    Diag.log(appContext, "covers", "обложка ${rec.name}: из кэша, ${ms()} мс")
                }
                val bmp = if (fromDisk != null) {
                    fromDisk
                } else {
                    val bytes = extract(rec)
                    if (bytes == null) {
                        // Так выглядит и «в книге обложки нет» (TXT, DOCX, ODT),
                        // и битый файл. В журнале это одна строка на книгу — по
                        // ней видно, у каких книг картинка не досталась.
                        Diag.log(appContext, "covers", "обложки нет: ${rec.name} (${ms()} мс)")
                        missing.add(key)
                        null
                    } else {
                        saveToDisk(key, bytes, sourceBytes)
                        val decoded = decode(bytes)
                        Diag.log(
                            appContext, "covers",
                            "обложка ${rec.name}: ${bytes.size} байт, ${ms()} мс, " +
                                if (decoded == null) "картинка не разобралась" else "показана",
                        )
                        decoded
                    }
                }
                if (bmp == null) {
                    // Второй раз за этой книгой не ходим: распаковка стоит времени.
                    missing.add(key)
                    return@execute
                }
                memory.put(key, bmp)
                main.post { onReady(bmp) }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** Ключ кэша — адрес книги: он же признак «та же книга» для адаптера. */
    private fun cacheKey(rec: BookRecord): String = rec.uri

    // ---------------- Диск ----------------

    /** Каталог кэша обложек. Лежит в filesDir приложения: обложки — наши
     *  производные данные, в папке книг им делать нечего. */
    private fun dir(): File = dirOf(appContext)

    private fun diskFile(key: String): File = diskFileOf(appContext, key)

    private fun sizeFile(key: String): File = sizeFileOf(appContext, key)

    /** Размер файла книги: по нему понимаем, что книгу заменили. Для обычного
     *  пути берём с диска, для системного адреса (SAF) — запросом, как это
     *  делает BookCache.signature. Недоступно — -1, тогда просто не проверяем. */
    private fun sourceSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            val path = uri.path ?: return -1L
            val f = File(path)
            return if (f.isFile) f.length() else -1L
        }
        return runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
        }.getOrElse { -1L }
    }

    private fun loadFromDisk(key: String, sourceBytes: Long): Bitmap? = runCatching {
        val f = diskFile(key)
        if (!f.isFile) return null
        val recorded = sizeFile(key).takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toLongOrNull() }.getOrNull() }
        if (recorded != null && sourceBytes >= 0 && recorded != sourceBytes) {
            Diag.log(
                appContext, "covers",
                "обложка ${f.name}: файл книги изменился ($recorded → $sourceBytes), достаю заново",
            )
            f.delete()
            sizeFile(key).delete()
            return null
        }
        // Обращение к кэшу — свежая дата использования: подрезка сносит самые
        // давно ненужные, а не самые старые по появлению.
        f.setLastModified(System.currentTimeMillis())
        decode(f.readBytes())
    }.getOrNull()

    private fun saveToDisk(key: String, bytes: ByteArray, sourceBytes: Long) {
        runCatching {
            val d = dir()
            d.mkdirs()
            diskFile(key).writeBytes(bytes)
            if (sourceBytes >= 0) sizeFile(key).writeText(sourceBytes.toString())
            prune(d)
        }
    }

    // ---------------- Достать обложку из книги ----------------

    private fun extract(rec: BookRecord): ByteArray? {
        val name = rec.name.lowercase(Locale.ROOT)
        val uri = runCatching { Uri.parse(rec.uri) }.getOrNull() ?: return null
        return runCatching {
            when {
                name.endsWith(".fb2") || name.endsWith(".xml") -> open(uri)?.use { fb2Cover(it) }
                name.endsWith(".epub") -> epubCover { open(uri) }
                // Книгу кладут в архив и целиком (fb2.zip, fbz) — Сергей держит
                // библиотеку как раз так. Внутри ищем ту же книгу, что откроет
                // читалка: сначала FB2, потом EPUB. Порядок важен, потому что
                // разбор книги (BookParser.parseZip) тоже предпочитает FB2.
                name.endsWith(".zip") || name.endsWith(".fb2.zip") || name.endsWith(".fbz") ->
                    zipCover(uri)
                else -> null
            }
        }.onFailure {
            // Сбой разбора не должен ронять полку, но обязан быть виден в журнале:
            // иначе «у книги нет обложки» и «мы не смогли её достать» не различить.
            Diag.log(appContext, "covers", "обложка ${rec.name}: сбой разбора (${it.message})")
        }.getOrNull()
    }

    /** Обложка книги из архива. Открываем архив столько раз, сколько нужно
     *  проходов: поток из SAF не перематывается, зато каждый проход дешёвый —
     *  до первой подходящей записи. Вложенные архивы (zip в zip) не разбираем:
     *  там окажется нарисованная заглушка с названием. */
    private fun zipCover(uri: Uri): ByteArray? {
        val again = { open(uri) }
        // FB2 — обычный случай такого архива.
        val fb2 = zipEntry(again, { it.endsWith(".fb2") }, MAX_ZIP_BOOK_BYTES)
            // .xml берём, но не служебный из чужого архива: у epub внутри zip
            // первым .xml идёт META-INF/container.xml, и он бы всё испортил.
            ?: zipEntry(
                again,
                { it.endsWith(".xml") && !it.startsWith("meta-inf/") },
                MAX_ZIP_BOOK_BYTES,
            )
        if (fb2 != null) return fb2Cover(ByteArrayInputStream(fb2))
        val epub = zipEntry(again, { it.endsWith(".epub") }, MAX_ZIP_BOOK_BYTES) ?: return null
        return epubCover { ByteArrayInputStream(epub) }
    }

    private fun open(uri: Uri): InputStream? =
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()

    /** Прочитать запись архива по имени (без учёта регистра). Каждый вызов —
     *  свой проход: поток из SAF не перематывается, поэтому архив открывается
     *  заново ([again] даёт свежий поток). */
    private fun zipEntry(again: () -> InputStream?, want: (String) -> Boolean, cap: Int): ByteArray? {
        again()?.use { raw ->
            ZipInputStream(raw).use { zis ->
                while (true) {
                    val entry = try {
                        zis.nextEntry
                    } catch (_: Exception) {
                        null // обрыв архива — что успели, то наше (как в BookParser)
                    } ?: return null
                    if (entry.isDirectory) continue
                    val entryName = entry.name.lowercase(Locale.ROOT)
                    if (want(entryName)) return readCapped(zis, cap)
                }
            }
        }
        return null
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (total < cap) {
            val n = try {
                input.read(buf, 0, minOf(buf.size, cap - total))
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ---------------- FB2 ----------------

    /** Обложка FB2: в `<coverpage>` лежит ссылка на картинку, сама картинка —
     *  в `<binary>` (base64) обычно в конце файла. Идём по потоку один раз,
     *  держим в памяти не больше двух картинок: первую встреченную (запасной
     *  вариант) и ту, на которую сослалась обложка. */
    private fun fb2Cover(input: InputStream): ByteArray? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var coverId: String? = null
        var inCoverPage = false
        var binaryWanted = false
        var binaryId: String? = null
        val text = StringBuilder()
        var matched: ByteArray? = null
        var first: ByteArray? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfterLast(':').lowercase(Locale.ROOT)) {
                        "coverpage" -> inCoverPage = true
                        "image" -> if (inCoverPage && coverId == null) {
                            coverId = attributeByLocalName(parser, "href")
                                ?.substringAfterLast('#')?.trim()?.takeIf { it.isNotEmpty() }
                        }
                        "binary" -> {
                            binaryId = attributeByLocalName(parser, "id")?.trim()
                            // Забираем только нужную картинку и, если ссылки нет,
                            // самую первую — она в fb2 и есть обложка.
                            binaryWanted = matched == null && (binaryId == coverId || (coverId == null && first == null))
                            if (binaryWanted) text.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (binaryWanted && text.length < MAX_IMAGE_BYTES * 2) {
                    text.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfterLast(':').lowercase(Locale.ROOT)) {
                    "coverpage" -> inCoverPage = false
                    "binary" -> if (binaryWanted) {
                        binaryWanted = false
                        val bytes = decodeBase64(text.toString())
                        if (bytes != null) {
                            if (binaryId != null && binaryId == coverId) matched = bytes
                            else if (first == null) first = bytes
                        }
                        text.setLength(0)
                    }
                }
            }
            event = try {
                parser.next()
            } catch (_: Exception) {
                XmlPullParser.END_DOCUMENT
            }
        }
        return matched ?: first
    }

    private fun attributeByLocalName(parser: XmlPullParser, local: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).substringAfterLast(':').equals(local, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun decodeBase64(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val bytes = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        return bytes
    }

    // ---------------- EPUB ----------------

    /** Обложка EPUB: сначала `META-INF/container.xml` говорит, где опись (OPF),
     *  в описи картинка обложки помечена свойством `cover-image` (новые книги)
     *  или ссылкой `<meta name="cover">` (старые), а иногда обложка — не
     *  картинка, а отдельная страница XHTML со ссылкой на картинку. Проходим
     *  архив столько раз, сколько нужно, каждый раз до нужной записи. */
    private fun epubCover(again: () -> InputStream?): ByteArray? {
        val container = zipEntry(again, { it == "meta-inf/container.xml" }, MAX_XML_BYTES) ?: return null
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(String(container, Charsets.UTF_8))?.groupValues?.get(1)?.let { decodeHref(it) }
            ?: return null
        val opf = zipEntry(again, { it == opfPath.lowercase(Locale.ROOT) }, MAX_XML_BYTES) ?: return null
        val opfText = String(opf, Charsets.UTF_8)
        val opfDir = opfPath.substringBeforeLast('/', "")

        val items = parseManifest(opfText)
        if (items.isEmpty()) return null

        // 1. явная пометка обложки в свойствах предмета (EPUB 3);
        // 2. старая ссылка <meta name="cover" content="..."> (EPUB 2);
        // 3. предмет, чей id или имя файла сами говорят «cover».
        val byProperty = items.firstOrNull { it.properties.contains("cover-image", ignoreCase = true) }
        val coverMetaId = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(opfText)
            .map { it.value }
            .firstOrNull { attr(it, "name").equals("cover", ignoreCase = true) }
            ?.let { attr(it, "content") }
        val byMeta = items.firstOrNull { it.id.equals(coverMetaId, ignoreCase = true) }
        val byName = items.firstOrNull {
            it.mediaType.startsWith("image/") &&
                (it.id.contains("cover", ignoreCase = true) || it.href.contains("cover", ignoreCase = true))
        }
        val cover = byProperty ?: byMeta ?: byName ?: return null

        val href = cover.href
        val hrefDir = href.substringBeforeLast('/', "")
        var imagePath = joinPath(opfDir, href)

        if (!cover.mediaType.startsWith("image/")) {
            // Обложка-страница: ищем в ней <img src="...">.
            val page = zipEntry(again, { it == imagePath.lowercase(Locale.ROOT) }, MAX_XML_BYTES) ?: return null
            val src = Regex("<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(String(page, Charsets.UTF_8))?.groupValues?.get(1)?.let { decodeHref(it) }
                ?: return null
            imagePath = joinPath(joinPath(opfDir, hrefDir), src)
        }

        return zipEntry(again, { it == imagePath.lowercase(Locale.ROOT) }, MAX_IMAGE_BYTES)
    }

    private class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    private fun parseManifest(opf: String): List<ManifestItem> =
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).mapNotNull { m ->
            val tag = m.value
            val href = attr(tag, "href") ?: return@mapNotNull null
            ManifestItem(
                id = attr(tag, "id").orEmpty(),
                href = decodeHref(href),
                mediaType = attr(tag, "media-type").orEmpty(),
                properties = attr(tag, "properties").orEmpty(),
            )
        }.toList()

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    /** Ссылка внутри книги: снимаем якорь и раскодируем проценты (в epub
     *  пробелы в именах файлов пишут как %20). */
    private fun decodeHref(href: String): String =
        Uri.decode(href.substringBefore('#').trim())

    /** Путь записи архива из каталога и относительной ссылки. */
    private fun joinPath(dir: String, href: String): String {
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (piece in href.split('/')) {
            when (piece) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(piece)
            }
        }
        return parts.joinToString("/")
    }

    // ---------------- Кодирование и рисование ----------------

    /** Декодировать картинку под размер карточки: полноразмерные обложки
     *  (бывает 2000 точек по длинной стороне) в карточку не нужны. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx && bounds.outHeight / (sample * 2) >= heightPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    /** Нарисованная обложка: цветной фон и название словами. Так книга без
     *  картинки выглядит книгой, а не дырой в полке. */
    private fun drawPlaceholder(title: String): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = PALETTE[Math.floorMod(title.hashCode(), PALETTE.size)]
        canvas.drawColor(bg)

        val dark = luminance(bg) > 0.6f
        val ink = if (dark) Color.BLACK else Color.WHITE

        // Корешок книги: узкая полоса слева.
        val spine = Paint().apply { color = ink; alpha = 60 }
        canvas.drawRect(0f, 0f, widthPx * 0.035f, heightPx.toFloat(), spine)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = (widthPx * 0.13f).coerceAtLeast(18f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val maxWidth = widthPx * 0.78f
        val lines = wrap(title, paint, maxWidth, maxLines = 6)
        val lineHeight = paint.fontSpacing
        var y = (heightPx - lineHeight * lines.size) / 2f - paint.fontMetrics.top
        for (line in lines) {
            val x = (widthPx - paint.measureText(line)) / 2f
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return bmp
    }

    /** Разбить название по словам под ширину; длинное слово режем по буквам. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current.setLength(0)
                current.append(candidate)
                continue
            }
            if (current.isNotEmpty()) {
                lines.add(current.toString())
                current.setLength(0)
                if (lines.size == maxLines) break
            }
            var rest = word
            while (paint.measureText(rest) > maxWidth && rest.length > 1) {
                var cut = rest.length - 1
                while (cut > 1 && paint.measureText(rest.substring(0, cut)) > maxWidth) cut--
                lines.add(rest.substring(0, cut))
                rest = rest.substring(cut)
                if (lines.size == maxLines) break
            }
            if (lines.size == maxLines) break
            current.append(rest)
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines.add(current.toString())
        return if (lines.isEmpty()) listOf(text) else lines
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}
