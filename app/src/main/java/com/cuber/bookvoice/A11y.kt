package com.cuber.bookvoice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Мелкие операции с экранным диктором (msg6175).
 *
 * [hush] обрывает текущую озвучку. Нужна там, где мы открываем микрофон:
 * диктор объявляет нажатую кнопку словами («Поиск. Долгое нажатие — голосовой
 * поиск»), и этот голос уходит в микрофон вместо фразы владельца.
 * interrupt() — штатный публичный API: гасит текущую озвучку, ничего не ломая.
 * Диктор, который его не слушает, просто продолжит говорить — хуже не станет.
 */
object A11y {
    fun hush(ctx: Context) {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return
        runCatching { am.interrupt() }
    }

    /** Кнопка, долгое нажатие которой диктор не объявляет второй раз (msg6288).
     *
     *  Долгое нажатие диктор объявляет сам — и не в момент жеста, а следом за
     *  ним: framework после нажатия рассылает событие TYPE_VIEW_LONG_CLICKED, и
     *  на него приходит второе объявление той же кнопки. Владелец слышит одну и
     *  ту же фразу дважды («долгое нажатие — голосовой поиск», затем снова), при
     *  этом второй раз — уже поверх открытого микрофона.
     *
     *  Объявление кнопки под фокусом нам нужно: из него владелец узнаёт о жесте
     *  (подсказка живёт в contentDescription). А повтор — нет. Гасим ровно это
     *  событие, все остальные пропускаем как есть.
     */
    fun muteLongClickAnnouncement(v: View) {
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun sendAccessibilityEvent(host: View, eventType: Int) {
                if (eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
                super.sendAccessibilityEvent(host, eventType)
            }
        }
    }

    /** Идёт ли в системе воспроизведение (речь диктора идёт потоком доступности).
     *  До API 26 списка воспроизведений нет — считаем, что тихо. */
    private fun speaking(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            am.activePlaybackConfigurations.any { cfg ->
                when (cfg.audioAttributes?.usage) {
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
                    AudioAttributes.USAGE_ASSISTANT,
                    AudioAttributes.USAGE_MEDIA,
                    -> true

                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    /** Отдать микрофон только в тишине (msg6288).
     *
     *  Диктор объявляет нажатую кнопку не в миг жеста, а следом за ним — при
     *  фиксированной паузе его голос приходил в уже открытый микрофон, и вместо
     *  слова владельца распознавалось объявление кнопки. Ждём, пока в системе
     *  смолкнет воспроизведение, и лишь потом открываем микрофон.
     *
     *  [floorMs] — пол: жест должен улечься, диктор начинает говорить не
     *  мгновенно. [capMs] — потолок: если диктор рассказывает что-то длинное,
     *  ждать до конца нельзя, владелец уже готов диктовать.
     */
    fun waitForQuiet(
        ctx: Context,
        floorMs: Long = 400,
        capMs: Long = 3500,
        ready: () -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.uptimeMillis()
        val probe = object : Runnable {
            override fun run() {
                val waited = SystemClock.uptimeMillis() - start
                if (waited < capMs && (waited < floorMs || speaking(ctx))) {
                    handler.postDelayed(this, 150)
                    return
                }
                // Только теперь — тишина: гасим хвост (мог начаться, пока
                // считали) и открываем микрофон.
                hush(ctx)
                ready()
            }
        }
        handler.postDelayed(probe, floorMs)
    }
}

/**
 * Лента, которая для экранного диктора не «список» (msg6260).
 *
 * НЕ ПОДКЛЮЧЁН (msg6278): в 0.4.46 читалка ставила его менеджером ленты, и у
 * TalkBack перестала работать автоматическая прокрутка текста свайпами — фокус
 * вместо следующей строки прыгал на нижнюю (строку текущей главы). Диктору
 * пометка списка нужна, чтобы понимать, что он прокручивает, — сняв её, мы
 * сломали прокрутку. В читалке снова обычный [androidx.recyclerview.widget.LinearLayoutManager].
 * Класс оставлен как запись этого опыта: если браться снова, снимать надо не
 * обе пометки, а только «N из M» у строки, оставив ленте метку коллекции.
 *
 * RecyclerView сам помечает себя коллекцией, а каждую строку — её элементом, и
 * диктор на каждой строке называет позицию: «12 из 340». На книге это шум:
 * номер не говорит, где ты в тексте, и перебивает чтение.
 *
 * Снимаем ровно две пометки — «я список» у ленты и «я элемент N из M» у
 * строки. Сначала зовём super, потом обнуляем, поэтому всё остальное
 * (прокрутка, действия доступности, порядок обхода) остаётся нетронутым: это
 * разметка для диктора, а не поведение. Ни вёрстка, ни место чтения не задеты.
 *
 * Обнуляем через framework-методы: у compat-сеттера в старых сборках core
 * параметр не помечен nullable, и передача null туда — лотерея.
 */
class NoListMarksLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onInitializeAccessibilityNodeInfo(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        info: AccessibilityNodeInfoCompat,
    ) {
        super.onInitializeAccessibilityNodeInfo(recycler, state, info)
        info.unwrap().collectionInfo = null
    }

    override fun onInitializeAccessibilityNodeInfoForItem(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        host: View,
        info: AccessibilityNodeInfoCompat,
    ) {
        super.onInitializeAccessibilityNodeInfoForItem(recycler, state, host, info)
        info.unwrap().collectionItemInfo = null
    }
}
