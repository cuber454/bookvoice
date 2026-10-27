# BookVoice: как устроено чтение книги и откуда берётся «фраза прочитана дважды»

Документ написан для ВНЕШНЕГО разбора: его читает нейросеть (или человек), которая
не видит репозиторий. Здесь всё, что нужно, чтобы судить о логике: как устроено
чтение, какие у него способы, какие правила (инварианты) обязаны держаться, и в
каких местах, по моему чтению кода, эти правила могут не держаться.

Просьба к рецензенту: искать ОШИБКИ ЛОГИКИ, из-за которых одна и та же фраза книги
может прозвучать два раза (или фраза обрывается и начинается заново — на слух это
тоже «повтор»). Стиль, именование и оформление не важны. Конкретные вопросы — в
конце, раздел 13.

---

## 0. Что за приложение и в какой сборке смотрим

BookVoice — читалка книг для незрячих (Android, Kotlin, без Compose, всё живёт на
главном потоке). Книга разбирается в главы → предложения. Читалка отдаёт движку
синтеза речи (TTS) фразы и играет получившийся звук.

Версия 0.7.0, versionCode 253, пробные сборки помечаются строкой `Diag.BUILD`
(сейчас «257»).

**Состояние кода на 23.10.2026.** Документ писался по коду до рефакторинга прямого
пути. Раздел 10 — это снимок «как было» (он объясняет причины), раздел 15 — «как
стало». Всё это уже в исходниках и 23.10.2026 собрано пробной сборкой 258
(`Diag.BUILD = "258"`), она отправлена Сержу: 13 725 241 байт, подпись та же
(81:B4…B9:4C).

Ключевые файлы:

| Файл | Что внутри |
|---|---|
| `Diag.kt` | журнал `files/diag.log`, метки `tts` / `sound` / `activity` |
| `Units.kt` | ключ единицы речи (позиция + номер куска) |
| `Roles.kt` | режет фразу на «слова автора» и «реплику» (`splitSpeech`), узнаёт реплику |
| `ReaderEngine.kt` | чтение: место в книге, склейка предложений, куски фразы, шаги, пауза |
| `SpeechPlayer.kt` | очередь заготовок, прямой путь, сторож по срокам, кэш сыгранных |
| `FilePlayback.kt` | ExoPlayer (Media3), играет готовые wav, отдаёт «начал / доиграл» |

Единицы времени: миллисекунды от `SystemClock.elapsedRealtime()`.

---

## 1. Короткая выжимка (если читать только одну страницу)

1. Чтение идёт по «единицам речи». Единица = ФРАЗА (1–4 предложения, склеенные) плюс
   НОМЕР КУСКА: фраза может быть разрезана на «слова автора» и «реплику», и куски
   читаются разными голосами. Ключ единицы — `Units.key(pos, part)`.
2. Звук бывает двумя способами:
   - **файловый**: движок синтезирует wav, играет НАШ ExoPlayer (список файлов,
     который играется как стоит). Мы держим впереди 1–6 готовых файлов;
   - **прямой**: движок сам синтезирует и сам говорит, у него СВОЯ очередь
     (`TextToSpeech.speak(..., QUEUE_ADD)`), а мы только отдаём фразы вперёд.
3. Место в книге двигает ТОЛЬКО сигнал «единица доиграла», и в нём приходит, КАКАЯ
   именно единица доиграла (`SpeechPlayer.reportDone(pos, part, last)`).
4. Повтор на слух получается, когда звук ушёл не в ту очередь, что думает чтение:
   файл/фраза звучит раньше очереди, чтение двигает место, возвращается на уже
   прозвучавшее и просит его снова (кэш сыгранных отдаёт звук мгновенно).
5. Поэтому в файловом пути список плеера наполняется ТОЛЬКО по порядку ключей
   (`expectedNextKey`), а прозвучавшая единица в этом проходе второй раз не звучит
   (`playedKeys`).
6. В прямом пути той же защиты НЕТ: там фразы отдаются движку текстом, номер
   позиции при заказе впрок теряется (`topUpDirectQueue` → `queueDirect(next)` без
   `pos/part`), и «доиграла» приходит без места. Это первое, что стоит проверить.
7. Есть ещё три источника звука, которые легко дают «повтор»: кэш сыгранных
   (`playedCache`, играет по просьбе человека), сохранённый паузы файл
   (`keptPhrase`) и «проба голоса» (`previewOnly`).
8. Ещё одна общая болячка — СЧЁТ «фраз» и «кусков» в одном числе: `directQueue`,
   `exoPending` и `readyQueue` считают КУСКИ, а запас вперёд читалка считает
   ФРАЗАМИ. У фразы с репликой кусков два, и любой счёт, где это не учтено,
   промахивается на одну фразу — а промах на фразу и есть механизм повтора.

---

## 2. Общая схема

```
            ┌──────────────┐   «какая фраза будет через N фраз?»
            │ ReaderEngine │◀──────────────────────────────────┐
            │  место книги │                                   │
            │  склейка,    │  speak(текст, pos, кусок)          │
            │  роли, словарь│──────────────┐                   │
            └──────────────┘              ▼                   │
                                    ┌─────────────┐   onNeedNext(offset)
                                    │ SpeechPlayer│───────────┘
                                    │ очередь,    │   onNeedAfter(pos)  → nextPhrasePos
                                    │ сроки, кэш  │
                                    └──────┬──────┘
                        файловый путь      │      прямой путь
                    ┌──────────────────────┴──────────────────────┐
                    ▼                                             ▼
            ┌───────────────┐                            ┌──────────────────┐
            │ FilePlayback  │  «начал / доиграл / кончил»│ движок TTS сам   │
            │ (ExoPlayer)   │───────────────────────────▶│ говорит (QUEUE_ADD)
            └───────────────┘                            └──────────────────┘
                    ▲                                             │
                    │ файл из synthFinished                       │ «готово» (onDone)
                    └────────────── движок синтеза ───────────────┘
```

Связь обратно в чтение — один колбэк: `SpeechPlayer.onDone` → `ReaderEngine.onPlayerDone`.
Перед вызовом плеер кладёт в поля `lastDonePos`, `lastDonePart`, `lastDoneLast`,
что именно доиграло.

Три вопроса, которыми чтение и плеер разговаривают:

- `onNeedNext(offset): List<Triple<текст, позиция, кусок>>?` — какие единицы
  прозвучат через `offset` ФРАЗ вперёд (для заказа впрок);
- `onNeedAfter(pos): Long?` — позиция фразы, которая по книге идёт сразу за `pos`
  (для проверки порядка списка плеера);
- `onDone` + `lastDone*` — единица доиграла.

---

## 3. Единица речи и ключ (`Units.kt`)

```kotlin
internal object Units {
    const val PARTS_MAX = 100
    fun key(pos: Long, part: Int): Long =
        if (pos < 0) -1L else pos * PARTS_MAX + part.coerceIn(0, PARTS_MAX - 1)
    fun label(pos: Long, part: Int): String =
        if (pos < 0) "без места в книге"
        else "глава ${pos / 1_000_000L}, предл. ${pos % 1_000_000L}, кусок $part"
}
```

- `pos` фразы = `глава * 1_000_000 + номер предложения` (адрес её первого
  предложения). Одна фраза = от 1 до 4 склеенных предложений (до 220 знаков).
- `part` = номер куска внутри фразы, считается по ПРОИЗНОСИМЫМ кускам, с нуля.
  Куски появляются, когда `Roles.splitSpeech` режет фразу на «слова автора» и
  «реплику» (два голоса) или когда первая фраза после перехода укорочена и
  разрезана по запятой («голова» и «хвост»).
- Ключ — единственное опознание единицы: им пользуются очередь заготовок, зеркало
  списка плеера, кэш сыгранных и сигнал «доиграла». ТЕКСТ опознанием быть не может:
  в книге полно одинаковых фраз («— Да.», «— Нет.»).
- Куски нумеруются в ТРЁХ местах, и все три обязаны совпасть:
  `ReaderEngine.speakCurrent` (говорит), `ReaderEngine.peekNextFragments` (заказывает
  следующую фразу впрок) и `SpeechPlayer.prefetchRest` (заказывает остальные куски
  ТЕКУЩЕЙ фразы). Если разойдутся — файл не найдёт своё место в очереди.

---

## 4. Состояние: кто что помнит

### 4.1. ReaderEngine (чтение)

| Поле | Смысл |
|---|---|
| `chapterIdx`, `sentenceIdx` | место чтения: с какого предложения читаем |
| `spokenUnits` | сколько предложений ушло в движок ОДНОЙ фразой (склейка) |
| `pendingFragments` | ещё не прочитанные куски текущей фразы (тексты) |
| `fragmentPos` | позиция текущей фразы (`pos`), общая для всех её кусков |
| `firstPhraseShort` / `shortFirstPhrase` | фраза укорочена до одного предложения (после ручного перехода) |
| `shortPhrasePos` | чья это была укороченная фраза |
| `previewUnit` | «проба голоса»: звучит одна фраза, место не двигается |
| `playing` | чтение идёт |

### 4.2. SpeechPlayer (очередь и звук)

| Поле | Смысл |
|---|---|
| `readyQueue: ArrayDeque<Prepared>` | готовые файлы, ещё не отданные плееру |
| `exoPending: ArrayDeque<Prepared>` | ЗЕРКАЛО списка ExoPlayer (первый — звучит) |
| `prefetchInFlight: LinkedHashMap<текст, pos>` | заявки «сделай файл», ответа ещё нет |
| `playedCache: ArrayDeque<Prepared>` | 3 последних СЫГРАННЫХ файла (повтор, шаг назад) |
| `keptPhrase` | файл звучащей фразы, сохранённый паузой |
| `phraseParts: HashMap<pos, Int>` | сколько кусков у фразы (для «последний ли кусок») |
| `lastAskedPos/lastAskedPart` | по ним нумеруются куски: тот же pos → part+1 |
| `playingKey/playingPart` | ключ того, что звучит сейчас |
| `playedKeys: HashSet<Long>` | ключи, прозвучавшие в ЭТОМ проходе |
| `lastPlayedPos/Part` | последняя доигравшая единица |
| `lastDonePos/Part/Last` | что сообщили чтению в последнем «доиграла» |
| `currentText/currentPos/currentPart` | что чтение попросило говорить последним |
| `awaitingPlayText/Pos/Part` | чей синтез ещё идёт, а по готовности играть сразу |
| `directQueue: ArrayDeque<Long>` | очередь заявок, ОТДАННЫХ движку (прямой путь) |
| `directSpeakingText` | текст фразы, которую движок говорит сейчас |
| `directAskedId` | заявка, которую чтение назвало последней |

Проход («тот же проход») — это отрезок чтения от действия человека до следующего
действия. `playedKeys` обнуляется на старте чтения (`speak(..., userAsked = true)`),
на `stop()`, `dropPrepared()`, `resetPipeline()`.

---

## 5. Способ A: файловый путь (движок делает файл, играем мы)

### 5.1. Вход: `SpeechPlayer.speak`

```kotlin
fun speak(text: String, pos: Long = -1L, userAsked: Boolean = true) {
    val t = tts
    if (t == null || !ready || text.isBlank()) return
    // Человек просит фразу — она начинается С НАЧАЛА, с нулевого куска
    if (userAsked) { lastAskedPos = -1L; lastAskedPart = -1 }
    val part = nextPartFor(pos)          // тот же pos → part+1, иначе 0
    currentText = text; currentPos = pos; currentPart = part
    if (userAsked) playedKeys.clear()    // новый проход
    prewarmOnly = false
    logReadingMode()

    if (directMode) {                    // способ B
        releaseMedia(); clearPrefetch(); awaitingPlayText = null
        speakDirectQueued(text, pos, part)
        return
    }
    speakExo(text, pos, part, userAsked)  // способ A
}
```

`userAsked = true` ставят: начало чтения, «повторить», шаг назад, прыжок,
продолжение после паузы, смена голоса. `false` — когда по книге идёт сама читалка
(`onUtteranceDone` → следующая фраза или следующий кусок).

### 5.2. `speakExo`: семь веток, строго по порядку

```kotlin
private fun speakExo(text: String, pos: Long, part: Int, userAsked: Boolean) {
    filePlayer.setListener(exoListener); armSoundWatch()
    val key = Units.key(pos, part)

    // 1) эта единица звучит прямо сейчас — очередь не трогаем
    if (sameSounding && exoFilePlayingSafe()) { requestPrefetch(); exoTopUp(); return }

    // 2) она уже впереди в списке плеера — просто ждём (и пускаем, если на паузе)
    if (ahead) { if (!playing) filePlayer.resume(); requestPrefetch(); exoTopUp(); return }

    // 2б) она уже ЗВУЧАЛА в этом проходе, а просит её само чтение (не человек):
    //     второй раз вперёд не читаем, сообщаем «доиграла» — чтение пойдёт дальше
    if (!userAsked && key >= 0 && playedKeys.contains(key)) {
        awaitingPlayText = null; awaitingPos = -1; awaitingPart = -1
        reportDone(pos, part, lastOfPhrase(pos, part)); return
    }

    exoDropTail()                        // хвост списка вон, звучащую не трогаем

    // 3а) пауза сохранила звук ровно этой фразы — продолжаем с середины файла
    takeKeptPhrase(text, pos, part)?.let { kept ->
        exoPlay(kept.text, kept.file, kept.rate, keptPositionMs, kept.pos, kept.part)
        requestPrefetch(); return
    }
    // 3а-2) фраза звучала недавно и её звук ждёт в кэше — ТОЛЬКО по просьбе человека
    if (userAsked) takePlayed(text, pos, part)?.let { done ->
        awaitingPlayText = null; awaitingPos = -1; awaitingPart = -1
        exoPlay(done.text, done.file, done.rate, 0L, done.pos, done.part)
        requestPrefetch(); return
    }
    // 3б) она в readyQueue, но не первая: всё перед ней чтение уже прошло
    if (deeper > 0) { … выбросить первые deeper … }
    if (head != null && sameUnit(head) && head.rate == rateFor(head.role)) {
        readyQueue.removeFirst(); awaiting… = null
        exoPlay(text, head.file, head.rate, 0L, head.pos, head.part)
        requestPrefetch(); return
    }
    // 3в) заявка уже в пути — ждём её (файл придёт в synthFinished)
    if (prefetchInFlight.containsKey(text)) {
        awaitingPlayText = text; awaitingPos = pos; awaitingPart = part
        if (waitingSilently()) armVoiceWarmup(…coldStartMs…) else armSynthWatch(text)
        return
    }
    // 4) ничего нет — заказываем синтез
    if (coldStart(text)) { coldPhrasePending = true; armVoiceWarmup(…) }
    awaitingPlayText = text; awaitingPos = pos; awaitingPart = part
    synthToFile(text, pos, part)
}
```

`sameSounding` — по ключу (а при `pos < 0` — по тексту). `ahead` — поиск по
`exoPending` по ключу. `deeper`/`head` — поиск по `readyQueue` по ключу.

### 5.3. Заказ впрок: `requestPrefetch`

```kotlin
private fun requestPrefetch() {
    if (directMode) return
    if (previewOnly) return                       // проба голоса: впрок не надо
    if (awaitingPlayText != null) return          // текущая фраза ещё не сыграна
    var issued = 0
    while (phrasesAhead() < aheadPhraseCap() && aheadCharsAhead() < aheadCharsTarget()) {
        if (issued >= aheadPhraseCap() + maxInFlight) break
        val offset = phrasesAhead() + 1           // считаем ФРАЗАМИ
        val frags = onNeedNext?.invoke(offset) ?: return
        if (frags.isEmpty()) return
        frags.map { it.second }.distinct().forEach { p ->
            registerParts(p, frags.count { it.second == p })   // сколько кусков у фразы
        }
        var progressed = false
        for ((nextText, nextPos, nextPart) in frags) {
            if (nextText.isBlank()) continue
            if (nextText == currentText) continue                  // ← сверка по ТЕКСТУ
            if (readyQueue.any { it.key == Units.key(nextPos, nextPart) }) continue
            if (exoPending.any { it.key == Units.key(nextPos, nextPart) }) continue
            if (prefetchInFlight.containsKey(nextText)) continue   // ← по ТЕКСТУ
            prefetchInFlight[nextText] = nextPos
            synthToFile(nextText, nextPos, nextPart)
            issued++; progressed = true
        }
        if (!progressed) return
    }
}
```

`phrasesAhead()` и `aheadCharsAhead()` считают «впереди» только позиции СТРОГО
дальше `currentPos` (куски самой читаемой фразы в счёт не идут — на этом был
разбор 07.10.2026, см. раздел 11).

### 5.4. Файл пришёл: `synthFinished` (ветка `FILE`)

```kotlin
val f = p.file
if (f == null || !f.exists() || f.length() == 0L) { onSynthLost(p.text, p.pos); return }
…
if (f.length() < 128) {                       // «пустой звук» (заголовок без звука)
    if (p.role == MAIN && looksLikeSpeech(p.text)) {
        emptySoundFails++
        if (give) { … markNetDirect(…) ; if (awaited) speakDirectNow(p.text, p.pos, …); return }
        f.delete(); prefetchInFlight.remove(p.text)
        if (awaited) synthToFile(p.text, p.pos, p.part)     // первый пустой — заказать заново
        return
    }
}
trimSilence(f, p.role)
if (isAwaited(p) && p.rate != rateFor(p.role)) {         // скорость сменилась, пока писался
    prefetchInFlight.remove(p.text); f.delete(); synthToFile(p.text, p.pos, p.part)
} else if (isAwaited(p)) {                              // этого и ждало чтение
    awaitingPlayText = null; awaitingPos = -1; awaitingPart = -1
    prefetchInFlight.remove(p.text)
    playFile(f, p.rate)                                 // ← играем
    requestPrefetch(); exoTopUp()
} else if (prefetchInFlight.remove(p.text) != null) {    // доспела заготовка
    if (readyQueue.size >= aheadPhraseCap() + maxInFlight) f.delete()
    else { addReadyInOrder(Prepared(p.text, f, p.rate, p.role, req = id, pos = p.pos, part = p.part))
           if (prewarmOnly) prewarmOnly = false else requestPrefetch()
           exoTopUp() }
} else {                                                 // осиротевший синтез
    val waiting = currentText != null || awaitingPlayText != null
    if (!soundPlaying() && waiting && p.rate == rateFor(p.role)) playFile(f, p.rate)
    else f.delete()
}
```

```kotlin
private fun isAwaited(p: Pending): Boolean =
    p.text == awaitingPlayText && p.pos == awaitingPos &&
        (awaitingPart < 0 || p.part == awaitingPart)
```

### 5.5. Список плеера: `exoAppend`, `exoTopUp`, порядок

ExoPlayer играет СПИСОК как он стоит. Поэтому заготовка, дописанная автоматически,
обязана быть ровно той единицей, что идёт следующей:

```kotlin
private fun exoAppend(text, f, rate, pos, part, automatic: Boolean): AppendResult {
    val key = Units.key(pos, part)
    val same = exoPending.firstOrNull { if (key >= 0) it.key == key else it.text == text && it.pos == pos }
    if (same != null && same.rate == rate) { f.delete(); return DROPPED }   // дубль в списке
    if (same != null) { … exoDropTail() }                                   // устаревшая скорость
    if (sameSounding) { f.delete(); return DROPPED }
    if (automatic) {
        val expect = expectedNextKey()
        if (expect != null && key >= 0 && key != expect) {
            if (unitInPipeline(expect)) return HELD             // ждёт своей очереди
            // единицы нет в конвейере — берём следующую
        }
    }
    exoPending.addLast(Prepared(…, pos = pos, part = part))
    filePlayer.append(listOf(Item(++exoSeq, text, f)))
    return ADDED
}
```

```kotlin
private fun expectedAfter(pos: Long, part: Int): Long? {
    if (pos < 0) return null
    if (part + 1 < partsOf(pos)) return Units.key(pos, part + 1)   // следующий кусок
    val next = onNeedAfter?.invoke(pos) ?: return null             // следующая фраза
    return Units.key(next, 0)
}
private fun expectedNextKey(): Long? {
    val last = exoPending.lastOrNull()
    if (last != null && last.pos >= 0) return expectedAfter(last.pos, last.part)
    if (lastPlayedPos >= 0) return expectedAfter(lastPlayedPos, lastPlayedPart)
    return null
}
```

`exoTopUp` перекладывает `readyQueue` → список плеера, пока впереди меньше
`prefetchDepth` фраз, и держит порядок ещё и по номеру заявки (`Prepared.req`),
чтобы реплика, готовая позже книжной фразы, не встала после неё.

### 5.6. «Доиграла» и указатель чтения

```kotlin
// SpeechPlayer.exoListener.onPhraseEnd
val done = exoPending.firstOrNull { it.file.name == item.file.name }
if (exoPending.isNotEmpty() && exoPending.first().text == item.text) exoPending.removeFirst()
val dPos = done?.pos ?: -1L
val dPart = done?.part ?: -1
val dLast = lastOfPhrase(dPos, dPart)
if (done != null) { rememberPlayed(…); noteUnitPlayed(done.pos, done.part) }
requestPrefetch(); exoTopUp(); reportDone(dPos, dPart, dLast)
```

```kotlin
// ReaderEngine.onPlayerDone — место двигается только по СВОЕЙ единице
val donePos = sp.lastDonePos
when {
    donePos < 0 -> foreignDone = 0                        // место неизвестно: как раньше
    donePos == fragmentPos -> {
        if (!doneLast && pendingFragments.isEmpty()) {     // кусок не последний, а своих кусков нет
            foreignDone++
            if (foreignDone < 3) return                    // чужой файл из середины чужой фразы
        }
    }
    donePos < fragmentPos -> { foreignDone++; return }     // хвост прежней фразы
    else -> catchUpTo(donePos)                             // звук УШЁЛ ВПЕРЁД — подтянуть место
}
onUtteranceDone()
```

```kotlin
private fun onUtteranceDone() {
    if (!playing) return
    if (previewUnit) { … дочитать куски, stopAtEnd(), место не двигать … }
    if (!continuous) { stopAtEnd(); return }
    if (pendingFragments.isNotEmpty()) {                    // ещё куски ЭТОЙ фразы
        val next = pendingFragments.first()
        pendingFragments = pendingFragments.drop(1)
        player?.speak(next, fragmentPos, userAsked = false)  // номер куска даст nextPartFor
        return
    }
    if (!advanceUnits(spokenUnits)) { stopAtEnd(); … ; return }   // +1..4 предложения
    startSpeakingCurrent(userAsked = false)
}
```

`advanceUnits(spokenUnits)` двигает `sentenceIdx` на столько предложений, сколько
было в озвученной фразе (склейка!). `advanceOneUnit` переходит в следующую главу и
учитывает конец выделенного куска и таймер сна «до конца главы».

---

## 6. Способ B: прямой путь (движок говорит сам)

Включается: галочкой «Альтернативный способ чтения», ручкой опытных настроек
«только движком», или САМ по опыту (`netDirect`): если движок дважды подряд не
отдал файл (или отдал пустой) — пара «движок + голос» помечается, и дальше читаем
её голосом.

### 6.1. `speakDirectQueued` — вход

```kotlin
private fun speakDirectQueued(text: String, pos: Long = -1L, part: Int = 0) {
    // фраза, которая ЗВУЧИТ, — не повод гасить движок
    if (directSpeakingText != null && text == directSpeakingText && directQueue.isNotEmpty()) return
    val front = directQueue.firstOrNull()
    if (front != null && pending[front]?.text == text) {      // она в голове очереди
        directAskedId = front; topUpDirectQueue(); return
    }
    flushDirectQueue("пришла другая фраза: …")                // tts.stop() + очистка очереди!
    if (!queueDirect(text, pos, part)) { … reportDone(); return }
    directAskedId = lastDirectQueuedId
    topUpDirectQueue()
}
```

Ключевое: сверка идёт по ГОЛОВЕ очереди и по ТЕКСТУ. Если пришла фраза не с головы
— очередь движка выбрасывается вместе с `tts.stop()` (это обрывает то, что движок
уже начал говорить).

### 6.2. `topUpDirectQueue` — заказ впрок

```kotlin
private fun topUpDirectQueue() {
    val limit = directAhead                       // 1 или prefetchDepth
    while (directQueue.size < limit) {
        val askedInQueue = directAskedId?.let { directQueue.contains(it) } == true
        val ahead = directQueue.size + if (askedInQueue) 0 else 1     // ← СЧЁТ В КУСКАХ
        val frags = onNeedNext?.invoke(ahead) ?: return               // ← а offset в ФРАЗАХ
        if (frags.isEmpty()) return
        var queuedAny = false
        for ((next, _) in frags) {
            if (next.isBlank()) continue
            if (directQueue.size >= limit) break
            if (!queueDirect(next)) { … return }                     // ← pos/part НЕ передаются
            queuedAny = true
        }
        if (!queuedAny) return
    }
}
```

### 6.3. Отчёт «готово» и придержка

```kotlin
// synthFinished, ветка DIRECT
directSpeakingText = null
if (directAskedId == id) directAskedId = null
val hold = directAckHoldMs(id, p.text)     // >0 придержать чтение, <0 «похоже, молчал»
noteSpokenRate(id, p.text.length)
directAskedAt.remove(id)
if (directQueue.firstOrNull() == id) directQueue.removeFirst() else directQueue.remove(id)
directQueue.firstOrNull()?.let { armDirectWatch(it, started = true) }
if (hold < 0L) {
    val pos = if (p.pos >= 0L) p.pos else currentPos
    if (ackLooksSilent(pos)) return
}
if (hold != 0L) main.postDelayed({ reportDone(p.pos, p.part, lastOfPhrase(p.pos, p.part)) }, wait)
else reportDone(p.pos, p.part, lastOfPhrase(p.pos, p.part))
```

`p.pos` здесь = позиция, с которой фразу заказали. Для фраз, заказанных впрок
(`topUpDirectQueue` → `queueDirect(next)` без позиции), `p.pos = -1`, значит
«доиграла» приходит БЕЗ МЕСТА и чтение двигается «как раньше», вслепую.

### 6.4. Лестница «движок молчит»

`noteSilentPhrase(pos)`: `silentRestartAt` подряд — перезапуск движка и
перечитывание текущей фразы (`retryAfterRestart = currentText`), `silentStopAt`
подряд — чтение встаёт и говорит об этом вслух.

---

## 7. Прочие пути, откуда берётся звук (и повторы)

1. **Кэш сыгранных** (`playedCache`, 3 файла). Отдаётся ТОЛЬКО при
   `userAsked = true` («повторить», шаг назад, возврат к прежнему месту). Звук
   играет мгновенно — если чтение по ошибке попросит уже прозвучавшую фразу как
   «просьбу человека», повтор будет слышен сразу и без паузы. `takePlayed` ищет по
   ключу единицы.
2. **Сохранённый паузой звук** (`keptPhrase`). `pauseKeepingPhrase()` запоминает
   текст, файл, скорость и `playingPos`, затем `stop()`. Продолжение играет файл с
   середины (`takeKeptPhrase`), сверяя текст + pos + скорость. Куски: в `Prepared`
   при сохранении передаётся только `pos`, а `part` остаётся нулём по умолчанию.
3. **Проба голоса** (`previewOnly`, `previewUnit`): звучит ровно одна фраза (и её
   куски), впрок не заказывается, место не двигается, в конце — `stopAtEnd()`.
4. **Служебные фразы**: «книга прочитана» (`endAnnounceSpeaking`), подсказка
   гарнитуры (`hintSpeaking`) — у них `pos = -1`, и `reportDone(-1)` означает «место
   неизвестно» (чтение ведёт себя как раньше), а не «ничего не произошло».
5. **Страховки**: `fallbackDirect` (файл не заиграл за 5 с → говорим движком через
   `QUEUE_FLUSH`), `speakDirectNow` (синтез не вышел, а чтение ждёт),
   `speakDirectAgain`/`rescueAgain` (сетевая пара: две попытки спасти фразу).
   Все три говорят фразу МИМО очереди и зовут `reportDone` сами.
6. **Смена скорости в прямом пути** (`restartDirectForRate`): `flushDirectQueue` и
   перечитывание текущей фразы новым темпом; у помеченной пары — пересоздание
   соединения с движком (`start(...)`).

---

## 8. Как переключаются способы

```kotlin
private val directMode: Boolean get() = when (forceMode) {
    ExpertPrefs.MODE_DIRECT -> true
    ExpertPrefs.MODE_FILE -> false
    else -> altDirect || netDirect
}
```

- `applyForceMode()` читается на СТАРТЕ чтения и при применении голоса (на ходу
  путь не меняем — это обрыв звука);
- `markNetDirect(reason)` ставит `netDirect = true` по опыту (2 провала файлов
  подряд: неотвеченная заявка или 2 пустых файла);
- `markedPairNoFiles` → `directAhead = 1` (по одной фразе) и пересоздание соединения
  при смене темпа;
- при включении прямого пути поднимается «тихий поток» (`KeepAwake.enableSilenceAuto`),
  иначе медиа-кнопки достаются чужому плееру.

---

## 9. Инварианты (что обязано быть верно всегда)

- **И1.** Ключ `Units.key(pos, part)` — единственное опознание единицы. Текст
  опознанием быть не может.
- **И2.** Куски нумеруются одинаково в трёх местах (`speakCurrent`,
  `peekNextFragments`, `prefetchRest`), по произносимым кускам, с нуля и по порядку.
- **И3.** Длина фразы (склейка) считается ОДНИМ правилом (`spanAt`) в четырёх
  местах: `speakCurrent`, `phraseStartAhead`, `speakablePhrase`, `peekNextFragments`.
  Флаг «укоротить первую фразу» берётся ДО расчёта длины.
- **И4.** Список ExoPlayer играется как стоит, поэтому автоматически (не по просьбе
  чтения) дописывается ТОЛЬКО единица, которая идёт следующей по книге
  (`expectedNextKey`); иначе — `HELD` в `readyQueue`.
- **И5.** Единица, прозвучавшая в этом проходе, вперёд не звучит второй раз; исключение
  — `userAsked = true` (тогда играем из кэша).
- **И6.** Место в книге двигает только «доиграла» СВОЕЙ единицы; чужой конец место не
  двигает, а звук, ушедший вперёд, подтягивает место (`catchUpTo`).
- **И7.** Каждая ветка, которая НАЧИНАЕТ играть фразу, сразу заказывает следующие
  (`requestPrefetch()`), иначе стык голодный.
- **И8.** `reportDone` — единственный путь сообщить «доиграла», и он несёт место,
  кусок и «последний ли это кусок».
- **И9.** `stop()`/пауза — это тишина: плеер гасится (`player.stop()`), а не только
  ставится признак.
- **И10.** Смена голоса/движка выбрасывает всё, что записано прежним (`dropPrepared`),
  и на ходу, и на паузе.
- **И11.** В прямом пути движение ползунка скорости перечитывает текущую фразу (движок
  помнит темп на момент заказа).
- **И12.** Кусок фразы обязан прозвучать раньше следующего предложения — даже если
  очередь заготовок забежала вперёд.

---

## 10. Места, которые я подозреваю (по моему чтению кода — проверь, пожалуйста)

Ниже не «найденные ошибки», а места, где инвариант держится не полностью. Каждое
может дать «фраза дважды» или «фраза обрывается и начинается заново».

**ВАЖНО (23.10.2026).** После написания этого раздела прямой путь переписан — см.
раздел 15: пункты П1, П2, П3, П4, П5, П6, П9, П11 закрыты в коде. Раздел 10 оставлен
как есть: он объясняет, ПОЧЕМУ код стал таким, каким стал, и по нему удобно
проверять, не вернулось ли старое.

### П1. Прямой путь: заказ впрок теряет позицию и кусок

`topUpDirectQueue` → `queueDirect(next)` — передаётся ТОЛЬКО текст:

```kotlin
for ((next, _) in frags) { … if (!queueDirect(next)) { … } … }
```

`frags` — это `Triple<текст, позиция, кусок>`, но позиция и кусок не передаются.
Следствие: у заготовленных фраз в прямом пути `Pending.pos = -1`, и «доиграла»
приходит без места (`reportDone(-1, 0, lastOfPhrase(-1, 0))` → «последний кусок» =
true). Значит в прямом пути:

- защита И6 не работает (чтение двигается «вслепую»);
- защита от повтора (`playedKeys`) не работает (ключа нет);
- `directSpeakingText` и сверка головы очереди идут по ТЕКСТУ — одинаковые реплики
  («— Да.») неразличимы.

Это ровно тот класс дефекта, который чинили в файловом пути 08.10.2026 (единицы
речи). В прямом пути он, по коду, остался.

### П2. Прямой путь: `ahead` считает КУСКИ, а `onNeedNext` ждёт ФРАЗЫ

```kotlin
val ahead = directQueue.size + if (askedInQueue) 0 else 1
val frags = onNeedNext?.invoke(ahead) ?: return
```

В очереди лежат КУСКИ (фраза с репликой = 2 заявки), а `offset` в чтении — ФРАЗЫ
(`phraseStartAhead`). У книги, где почти каждая фраза — реплика со словами автора,
`ahead` почти вдвое больше нужного: чтение называет фразу через одну, и фраза сразу
после текущей в очередь не попадает. Дальше `speakDirectQueued` на ней увидит, что
голова очереди — ДРУГАЯ фраза, вызовет `flushDirectQueue` (то есть `tts.stop()`), и
если движок уже начал эту фразу, начало прозвучит и оборвётся, а потом прозвучит
снова целиком. На слух — «фраза повторилась».

Важно: счёт `directQueue.size` расходится с `offset` ровно на «лишние куски». Если у
фраз в книге по одному куску (обычный текст без слов автора), счёт совпадает.

### П3. Прямой путь: куски текущей фразы не заказываются впрок

`prefetchRest` в прямом пути выходит сразу (`if (directMode) return`), поэтому
остальные куски ТЕКУЩЕЙ фразы заказываются только в `onUtteranceDone`, когда первый
кусок уже доиграл. В установившемся режиме это не беда: когда чтение стояло на
предыдущей фразе, `topUpDirectQueue` взял через `onNeedNext` СЛЕДУЮЩУЮ фразу целиком,
со всеми кусками, и они уже стоят в очереди. Беда в другом: у первой фразы после
старта, прыжка, паузы, смены скорости и ЛЮБОГО `flushDirectQueue` куски впрок не
заказаны — первый кусок доиграл, голова очереди это уже следующая фраза, значит
`speakDirectQueued(хвост)` попадёт в ветку «пришла другая фраза» и вызовет
`flushDirectQueue` + `tts.stop()`. А после этого `flush` очередь обнулил, и вместе с
П2 получается, что фраза сразу за текущей опять не заказана — то есть разрыв
повторяется на каждой следующей фразе. На слух: «движок начал следующую фразу,
оборвался, прозвучал хвост, а потом эта фраза началась снова».

### П4. Файловый путь: `playFile` теряет место файла

```kotlin
private fun playFile(f: File, rate: Float = speed) {
    val t = currentText ?: return
    …
    exoPlay(t, f, rate, 0L, currentPos, currentPart)
}
```

Файл, пришедший по заявке `p`, играется как «текущая фраза чтения» (`currentText`,
`currentPos`, `currentPart`), а не как `p.text/p.pos/p.part`. Если ответ пришёл на
фразу, которую чтение уже не ждёт (или на такую же по тексту, но из другого места
книги), в зеркало `exoPending` попадёт НЕВЕРНЫЙ ключ. Дальше `expectedNextKey`
посчитает ожидание от чужого места, правильно стоящая заготовка уйдёт в `HELD` (или
наоборот), и порядок может разъехаться. В ветке `isAwaited(p)` правильнее выглядит
`exoPlay(p.text, f, p.rate, 0L, p.pos, p.part)`.

### П5. Файловый путь: `prefetchInFlight` и `awaitingPlayText` — по ТЕКСТУ

`prefetchInFlight: LinkedHashMap<String, Long>` и сверки `prefetchInFlight.containsKey(text)`,
`nextText == currentText` — это опознание по тексту, вопреки И1:

- два одинаковых по тексту места книги не заказываются оба (`containsKey` съедает
  второе, а `prefetchRest` пропускает кусок, равный `currentText`);
- при ответе движка `synthFinished` снимает заявку `p.text` — при двух одинаковых
  текстах снимается «не та»;
- `isAwaited(p)` сверяет текст + pos + кусок, но `prefetchInFlight` уже мог быть
  перетёрт.

В книге с диалогами («— Да.») это реальный сценарий.

### П6. Файловый путь: зеркало списка сравнивается по тексту

```kotlin
if (exoPending.isNotEmpty() && exoPending.first().text == item.text) exoPending.removeFirst()
```

`item.file.name` уникально, а сверка идёт по тексту. Две одинаковые фразы подряд
(или в списке одновременно) — снимается не тот элемент зеркала, и все последующие
`pos/part` съезжают (см. П4). Надёжнее снимать по имени файла.

### П7. `expectedNextKey() == null` отключает проверку порядка

```kotlin
if (automatic) {
    val expect = expectedNextKey()
    if (expect != null && key >= 0 && key != expect) { … }
}
```

`expectedNextKey` возвращает null, когда зеркало пусто и `lastPlayedPos < 0`, а
также когда `onNeedAfter` вернул null (конец книги, граница выделенного куска,
таймер сна «до конца главы», книга не загружена). В этом случае автоматическая
заготовка дописывается БЕЗ проверки порядка — то есть ровно тот механизм, из-за
которого были повторы 07.10 (файл звучит раньше очереди, чтение уезжает, фраза
читается снова). Для «книга кончилась» это безобидно, а вот `onNeedAfter == null` по
другой причине — нет.

### П8. «Последний ли кусок» зависит от карты `partsOf`, которую заполняют по ходу

`lastOfPhrase(pos, part)` = `part + 1 >= partsOf(pos)`, а `partsOf` = 1, пока
`registerParts` не запишет больше. Регистрация идёт в трёх местах: `nextPartFor`
(когда запрошен кусок с номером > 0), `requestPrefetch` (сколько кусков вернуло
чтение для этой позиции) и `prefetchRest`. Хорошая новость: `speakCurrent` зовёт
`prefetchRest` СИНХРОННО сразу после `speak`, а сигналы плеера приходят через
`main.post`, то есть регистрация успевает. Плохая: карта `registerParts` берёт
МАКСИМУМ (`if (n > was)`), поэтому если число кусков на новом проходе стало
МЕНЬШЕ (словарь убрал кусок, роли выключили, реплика стала словами автора), фраза
останется «многокусковой», и `onPlayerDone` будет ждать несуществующий кусок: он
увидит `doneLast = false` при пустых `pendingFragments` и выйдет по ветке «чужой
кусок» (П11), а место в книге не сдвинется. Обратный случай (карта ЗАНИЖЕНА) даёт
`doneLast = true` и сдвиг места на середине фразы. Стоит проверить: чистятся ли
`partsOf` при смене ролей/словаря (сейчас — только `phraseParts.size > 4000`).

### П9. `reportDone` из отложенного `postDelayed` может переставить события

В прямом пути придержка (`hold > 0`) откладывает `reportDone` на N мс. За это время
может прийти другое «доиграла» (следующая фраза движка) и записать `lastDonePos`.
Тогда отложенный вызов перепишет его СТАРЫМ значением, и чтение получит не ту
единицу. Инвариант И8 («reportDone несёт место») формально держится, а «события идут
в порядке звука» — нет.

### П10. `catchUpTo` выбрасывает куски текущей фразы

```kotlin
pendingFragments = emptyList()
spokenUnits = spanAt(ch, s).coerceAtLeast(1)   // ← БЕЗ first = true
```

`catchUpTo` (звук ушёл вперёд) сбрасывает `pendingFragments` и пересчитывает
`spokenUnits = spanAt(ch, s)` — БЕЗ флага `first`. Если подтягиваемся к укороченной
первой фразе после перехода, `spokenUnits` будет посчитан обычной склейкой, и
`advanceUnits` уедет на 2–4 предложения вместо одного — то есть часть книги будет
пропущена. Обратная ошибка (не та длина) — это ровно механизм повторов из разбора
07.10 (00:35), только в другую сторону.

### П11. `foreignDone` — «дальше трёх раз не упираемся»

После трёх чужих «доиграла» подряд чтение двигает место, даже если конец был
действительно чужой. Это осознанный компромисс (лучше двинуть место, чем встать),
но он же и есть путь к повтору: место сдвинулось на фразу, которая уже звучала, а
кэш сыгранных отдаст её звук.

### П12. `onAllEnded` и «двойное» доигрывание

```kotlin
override fun onAllEnded() {
    if (playingText != null) { … reportDone() … }
}
```

Если `onPhraseEnd` для последней фразы почему-то не пришёл, `onAllEnded` двигает
чтение сам (`reportDone()` без места). Это страховка от вечного молчания, но она
может сработать ПОСЛЕ настоящего «доиграла» (если `playingText` не успели обнулить)
и дать лишний шаг вперёд. Стоит проверить, может ли `playingText` остаться непустым
после `onPhraseEnd`.

### П13. Три пути звука одновременно

`keptPhrase` (пауза), `playedCache` (сыгранные), `readyQueue`/`exoPending`
(заготовки) могут содержать ОДИН И ТОТ ЖЕ ключ единицы. Порядок веток в `speakExo`
(keptPhrase → playedCache → readyQueue/голова → inFlight) определяет, что сыграет;
но файл, взятый из одного места, продолжает числиться в другом, и повторная чистка
(`exoDropTail`, `clearPlayedCache`) может удалить файл, который уже отдан в плеер.
`takeKeptPhrase` при этом создаёт `Prepared(..., pos = playingPos)` без `part`.

### П14. `phraseParts` живёт дольше прохода и только растёт

`playedKeys.clear()` в `stop()` и `dropPrepared()` — правильно (новый проход). Но
`phraseParts` при этом НЕ чистится (только по размеру, 4000 записей), а
`registerParts` берёт максимум. После прыжка назад/вперёд по одной и той же позиции
число кусков может остаться от прежнего прохода — в частности, если словарь или
роли на новом проходе дают другое число кусков (включили/выключили голос реплик,
поправили правило словаря).

---

## 11. Что уже было найдено и исправлено (чтобы не искать заново)

Список подтверждённых причин повторов — по журналам и разборам. Все они закрыты,
но хорошо показывают, какие механизмы ломаются.

| Когда | Симптом | Причина | Чем закрыто |
|---|---|---|---|
| 29.09.2026 | «одна фраза прочиталась три раза» | читалка дважды по-разному ответила на заказ; сверка конвейера выбросила хвост и заказала заново, а копии уже дописанных в плеер фраз остались | `exoAppend` не берёт ту же фразу второй раз (`DROPPED`) |
| 29.09.2026 | «фраза повторилась» после паузы | `playingRate` выставлялся только в `exoPlay`, поэтому пауза считала сохранённый звук «прежней скорости» и выбрасывала файл → фраза синтезировалась и звучала С НАЧАЛА | `playingRate` ставится в `onPhraseStart` |
| 30.09.2026 | похожие фразы переставлены, потом повтор | фразы уходили в плеер в порядке ГОТОВНОСТИ (книга и реплики — разные движки) | `Prepared.req`, `earliestInFlight`, `addReadyInOrder` |
| 30.09.2026 | фраза 1175 дважды | придержанная фраза встала в очередь после своей соседки | порядок в `readyQueue` по номеру заявки |
| 05.10.2026 | повтор при листании | два счёта длины фразы (флаг «укоротить» брался после `spanAt`) | флаг берётся первым |
| 06.10.2026 | «фраза повторяется» в прямом пути | второй кусок приходил как «другая фраза» → `flush` (`tts.stop`) → фраза начиналась заново | `topUpDirectQueue` заказывает все куски фразы |
| 07.10.2026 | «фразы повторяются» (00:35) | `phrasesAhead()` считал куски читаемой фразы за «фразу впереди» → заказ через одну → файл звучал раньше очереди | `phrasesAhead` считает только позиции строго дальше `currentPos` |
| 07.10.2026 | то же | заготовка вставала в список «как пришла» | `exoAppend(automatic)` + `nextPhrasePos` (проверка «следующая ли это единица»), позже — `HELD` вместо выбрасывания |
| 08.10.2026 | «два раза какая-то фраза повторялась» (21:43, 12:45) | все куски фразы шли с ОДНОЙ позицией, опознать их было нечем: хвост вставал после следующего предложения, чтение двигало место на любом «доиграла» и просило прозвучавшее, кэш отдавал сразу | `Units.kt` (ключ = позиция + кусок), `playedKeys`, `reportDone(pos, part, last)`, `onPlayerDone` по своей единице, `HELD` |

---

## 12. Как повтор виден в журнале

Журнал: `files/diag.log` (метки `tts`, `sound`, `activity`). Нас интересуют строки:

```
фраза собрана: глава 20, предл. 10 «— Опыт не всегда… / Главное…»      ← какую фразу собрало чтение
заказал заготовок: 3 (в работе 2 из 3; впереди 214 знаков из 300…)
заготовка готова: s_180.wav (376016 байт), в очереди 1/3
синтез готов: s_180.wav (376016 байт), скорость 1.4 [книга]
exo: дописал фразу (s_180.wav), впереди 2
exo: играю фразу 21/24 (s_180.wav) «— Опыт не всегда самое главное…»
exo: фраза доиграла (встык): s_180.wav «— Опыт…»
фраза доиграла: s_180.wav «— Опыт…» [глава 20, предл. 10, кусок 0, последний кусок]
играю из кэша сыгранных (s_180.wav, 376016 байт) «— Опыт…»        ← ПОВТОР
порядок: заготовка … ждёт своей очереди (следующей должна звучать …)
порядок: … уже звучала в этом проходе — второй раз по ходу книги не читаю
указатель: звук ушёл вперёд — читалка стояла на …, доиграла …; подтягиваю место к звуку
указатель: доиграла прежняя единица …, ждём … — место не двигаю
альтернативный способ: отдаю фразу движку (80 знаков, скорость 1.0): …
прямая речь: очередь движка выброшена (пришла другая фраза: «…»)
```

Опознание повтора на слух и в журнале: одна и та же пара «фраза собрана» +
один и тот же размер файла у двух РАЗНЫХ файлов (движок писал фразу дважды), либо
строка «играю из кэша сыгранных» сразу после «фраза доиграла» той же позиции.

---

## 13. Что я прошу проверить (конкретные вопросы)

**Примечание 23.10.2026:** вопросы 2, 4, 7, 8, 9, 10, 14 относятся к коду ДО
рефакторинга — ответы на них даны самим рефакторингом (раздел 15). Актуальные
вопросы — в разделе 15.4; ниже они оставлены, чтобы было видно, из чего выросла
правка.

1. Держится ли И4 (порядок списка) при `expectedNextKey() == null`? Может ли
   `onNeedAfter` вернуть null не из-за конца книги, а из-за состояния чтения
   (граница куска, таймер сна, несовпадение `spanAt`)? См. П7.
2. Может ли `playFile(f, rate)` с `currentPos/currentPart` дать неверный ключ в
   зеркале, и что из этого следует для `expectedNextKey`? См. П4.
3. Согласованы ли номера кусков в трёх местах (И2) для фразы, в которой словарь
   убрал ОДИН из кусков (пустая замена)? `speakCurrent` пропускает кусок, и номера
   сдвигаются — одинаково ли сдвигаются `peekNextFragments` и `prefetchRest`?
4. Может ли `prefetchRest` пропустить кусок из-за `rest == currentText` (одинаковый
   текст у двух кусков одной фразы, например «— Да. — Да.»), и что тогда с ключом?
5. Может ли число кусков у позиции разойтись между проходами (`phraseParts` не
   чистится, `registerParts` берёт максимум, `lastOfPhrase` строится на нём)? См. П8
   и П14: что будет, если на новом проходе кусков стало меньше? А если больше?
6. Не может ли `hold`-придержка в прямом пути (П9) переписать `lastDonePos` старой
   единицей и сдвинуть/не сдвинуть место не туда?
7. Правда ли, что в прямом пути `topUpDirectQueue` считает куски вместо фраз (П2),
   и какая фраза в итоге не попадает в очередь?
8. Что происходит в прямом пути с кусками ТЕКУЩЕЙ фразы (П3): всегда ли это
   `flushDirectQueue` + `tts.stop()`? Если да — это и есть источник «фраза
   обрывается и начинается заново».
9. Должен ли `queueDirect` получать `pos/part` в `topUpDirectQueue` (П1)? Что ещё
   держится на том, что в прямом пути `p.pos = -1`?
10. `catchUpTo` сбрасывает `pendingFragments` и считает `spokenUnits` без флага
    `first` (П10) — верно ли это и не теряются ли куски?
11. Может ли `onAllEnded` дать лишний шаг вперёд (П12)? Может ли `playingText`
    остаться непустым после `onPhraseEnd`?
12. Согласованы ли `playedKeys`, `playedCache` и `keptPhrase` по одному и тому же
    ключу (П13): не может ли один и тот же файл числиться в двух местах и быть
    удалённым, пока он в плеере?
13. `phraseParts` не чистится на новом проходе (П14) — может ли число кусков
    разойтись между проходами (включили/выключили голос реплик или изменили словарь)?
14. Нет ли где-то ещё опознания по ТЕКСТУ вместо ключа? В коде есть:
    `prefetchInFlight` (ключ — текст), `awaitingPlayText` (текст + pos), сверки
    `p.text == awaitingPlayText`, `exoPending.first().text == item.text`,
    `directSpeakingText`, `directQueue` сверка головы по тексту, `isDirectQueued`.
    Для каждой такой сверки: что произойдёт, если в книге две одинаковые фразы
    подряд или две одинаковые фразы в очереди одновременно?
15. Есть ли место, где заказ вперёд может «прыгнуть через фразу» (счёт в кусках
    против счёта в фразах) — кроме `topUpDirectQueue` и `exoTopUp`
    (`exoPending.size - 1 < prefetchDepth - 1`)?

---

## 14. Приложение: как это читается по-человечески

Чтение и звук — это два независимых «прилавка»:

- чтение идёт по книге и говорит: «эту фразу — читай» (и заранее: «через одну будет
  вот эта»);
- звук отвечает: «эта единица доиграла».

Всё устройство защиты от повторов — это попытка сделать так, чтобы «доиграла»
всегда относилось к той единице, на которой стоит чтение, и чтобы звук никогда не
уходил вперёд очереди. Файловый путь это умеет (ключи, `HELD`, `playedKeys`,
`reportDone` с местом). Прямой путь до 23.10.2026 умел это только наполовину: ключи
там были у фраз, которые чтение называет само, и не было у заготовок (П1), а счёт
запаса путал куски с фразами (П2). Поэтому главный вопрос к рецензенту был: **что
именно ломается в прямом пути** — и не является ли вся пачка жалоб «фраза дважды»
жалобами на прямой путь (сетевые голоса и движки-прослойки, которые файлов не
отдают и потому переводятся на прямой путь автоматически).

Раздел 15 описывает, что в прямом пути уже переделано, — и вот к нему вопрос тот же,
только по новому коду.

---

## 15. Рефакторинг прямого пути (23.10.2026): что теперь в коде

Задача была одна: **прямой путь не должен быть «ущербным»**. Он теперь работает на
тех же ключах единиц речи и по тем же правилам безопасности, что файловый. Ниже —
что именно изменилось и что осталось открытым. Порядок пунктов — по разделам П1-П14.

### 15.1. Ключ вместо текста (П1, П5, П6, П9, П11)

Один и тот же механизм в обеих ветках — `Units.key(pos, part)`:

| было | стало |
|---|---|
| `queueDirect(next)` (только текст) | `queueDirect(next, pos, part)` во всех вызовах |
| `Pending` без места | `Pending(…, pos, part)`; `Pending.key` = `Units.key(pos, part)` |
| `pending` строк `val pos = -1` у заготовок | ключ есть у любой фразы книги |
| `reportDone(-1, 0, true)` из прямого пути | `reportDone(p.pos, p.part, lastOfPhrase(p.pos, p.part))` |
| `playedKeys` в прямом пути не работал | `noteUnitPlayed(p.pos, p.part)` в `synthFinished` (ветка DIRECT) |
| `prefetchInFlight` (ключ — ТЕКСТ) | нет такого поля; поиск заявки — `fileInFlight(key)` |
| `nextText == currentText` в `requestPrefetch` | `nextKey == Units.key(currentPos, currentPart)` |
| `prefetchInFlight.containsKey(text)` | `fileInFlight(nextKey) != null` |
| `exoPending.first().text == item.text` | снятие по имени ФАЙЛА (`indexOfFirst`/`removeAt`) |
| сверка головы очереди по тексту | `frontPending.key == key` |
| `isDirectQueued(text)` | удалено |

Текст остался опознанием РОВНО в одном месте и только для единиц без места в книге
(служебные фразы, подсказки, «книга прочитана»): `fileInFlightText` в
`fileInFlightAny`. Фразы книги туда не попадают никогда.

`speak()` теперь принимает четвёртым доводом `rest: List<String>` — остальные куски
ТОЙ ЖЕ фразы. Читалка (`ReaderEngine.speakCurrent`) отдаёт их сразу вместе с головой,
и прямой путь кладёт их в очередь движка **сразу за головой** (П3).

### 15.2. Что закрыто по пунктам

- **П1** (заказ впрок терял место): закрыт. `topUpDirectQueue` и `prefetchRest`
  передают `(текст, место, кусок)`; у заготовок есть ключ.
- **П2** (`ahead` считал куски, `onNeedNext` ждёт фразы): закрыт.
  `directPhrasesAhead()` считает РАЗНЫЕ места строго дальше `currentPos` (куски одной
  фразы — одна фраза). Смещение для `onNeedNext` — в фразах.
- **П3** (куски текущей фразы не заказывались впрок): закрыт двумя путями —
  `speak(..., rest)` и прямой веткой `prefetchRest`. В прямой путь добавлено правило
  «дописывать можно только в конец»: если за хвостом очереди уже стоят единицы
  ДАЛЬШЕ, кусок не дописывается (иначе он прозвучит не на месте).
- **П4** (`playFile` брал `currentText/currentPos/currentPart`): закрыт. `playFile(p)`
  играет ровно ту единицу, за которую пришёл файл (`p.text/p.pos/p.part`).
- **П5** (сверка по «чистому тексту»): закрыт, см. таблицу 15.1.
- **П6** (`exoPending.first().text == item.text`): закрыт — снятие по имени файла.
- **П9** (повторный заказ звучащей фразы): закрыт по ключу:
  `key == directSpeakingKey`, где `directSpeakingKey` = ключ заявки, о которой движок
  сообщил «начал говорить».
- **П11** (`reportDone` без места): закрыт — место и кусок есть у каждой заявки.
- **П8/П14** (`phraseParts` не чистится, `registerParts` берёт максимум): НЕ закрыт,
  вопрос остаётся открытым. Число кусков у позиции по-прежнему только растёт; новый
  проход с меньшим числом кусков увидит старое (большее) число, и `lastOfPhrase`
  может считать не-последний кусок последним. Практически это возможно при смене
  голоса реплик или словаря на ходу — то есть редко, но возможно.
- **П10** (`catchUpTo` считал длину фразы без флага `first`): закрыт —
  `spanAt(ch, s, first = pos == shortPhrasePos)`.
- **П12** (`onAllEnded` мог дать лишний шаг): НЕ закрыт намеренно: там по-прежнему
  `reportDone()` без места (место неизвестно, а «подтягивание» по последней
  прозвучавшей единице могло бы дать ложный `catchUpTo`).
- **П7, П13**: не трогались, вопрос открыт.

### 15.3. Как прямой путь теперь держит порядок

1. `topUpDirectQueue` держит у движка `directAhead - 1` ФРАЗ впереди (режим «по одной
   фразе» — ровно 0: только текущая фраза со всеми кусками). Уже заказанная фраза
   второй раз не заказывается (`directQueuedPhrase`).
2. Очередь делится по ДВИЖКАМ, а не по ролям: когда голос реплик живёт в том же
   движке, очередь у него одна, и голос ставится перед каждым заказом. Два РАЗНЫХ
   экземпляра по-прежнему не смешиваются (иначе звук внахлёст).
3. `speakDirectQueued` сверяется с головой очереди по ключу и НЕ гасит очередь, если
   эта единица уже звучит. `flushDirectQueue` (`tts.stop()`) остался только там, где
   пришла ДРУГАЯ единица, а очередь занята — прыжок, повтор, «продолжить».
4. Единица, прозвучавшая в этом проходе, вперёд второй раз не звучит
   (`playedKeys`, проверка в `speakDirectQueued` только для `userAsked = false`).
5. Смена скорости на ходу перечитывает ТУ единицу, что звучит (или голову очереди),
   вместе с её нерозданными кусками, и помнит её место и кусок
   (`retryDirectAfterRestart*`) — прежде место терялось.

### 15.4. Что в этом новом коде стоит проверить рецензенту

1. `directPhrasesAhead()` не считает единицы с `p.pos <= currentPos`. Может ли в
   очереди оказаться единица ДАЛЬШЕ, но заказанная РАНЬШЕ текущей (после прыжка), и
   не даст ли это ложного «фраз впереди 0», то есть бесконечный заказ? (Защита —
   `directQueuedPhrase` + счётчик шагов `guard`.)
2. Очередь движка теперь может содержать куски, а не только головы. Не может ли
   `directSpeechStarted`/`armDirectWatch` принять кусок за целую фразу и не перевести
   надзор на слишком короткий срок (кусок короче фразы)?
3. `reportDone(p.pos, p.part, …)` в прямом пути теперь может прийти для единицы,
   которая СТАРШЕ текущей позиции чтения (движок доигрывает отменённое). Чтение такой
   отчёт игнорирует (`donePos < fragmentPos`). Не может ли он, наоборот, «залипнуть»
   на единице, которая уже прозвучала и никогда не повторится?
4. `currentRest` — куски ТЕКУЩЕЙ фразы, они нужны только для перечитки при смене
   скорости. Переживёт ли он паузу, смену голоса, прыжок (везде `stop()` чистит)?
5. `playedKeys` теперь наполняется и в прямом пути. Не может ли «продолжение после
   паузы» попасть под запрет повтора? (`stop()` чистит `playedKeys`, `speak(...,
   userAsked = true)` тоже; проверь все входы.)
6. Заготовка по ключу ищется в `pending` через `fileInFlight(key)`. Что будет, если
   для одной единицы в `pending` окажутся ДВЕ заявки (старая и новая)? Кто из них
   выиграет и что со второй (см. ветку «файл пришёл без заявки»)?

