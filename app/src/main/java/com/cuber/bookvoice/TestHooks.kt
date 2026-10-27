package com.cuber.bookvoice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * КНОПКИ ДЛЯ САМОПРОВЕРКИ (24.10.2026).
 *
 * Зачем. Журнал программы лежит в приватной папке приложения, а `run-as` на
 * телефоне Сержа не работает (SELinux: `targetSdkVersion=36:fromRunAs`), поэтому
 * журнал обычно видит только сам Серж. Из-за этого любую проверку приходилось
 * делать его руками: включи галочку, нажми читать, пришли журнал. А он просил
 * («Давай тогда делай сам») проверять самому, где не нужно его ухо.
 *
 * Что делает. Принимает широковещательные команды через adb и отвечает строкой в
 * logcat с тегом [Diag.LOGCAT_TAG]:
 *
 * ```
 * adb shell am broadcast -a com.cuber.bookvoice.TEST.mirror --ez on true
 * adb shell am broadcast -a com.cuber.bookvoice.TEST.broken --ez on true
 * adb shell am broadcast -a com.cuber.bookvoice.TEST.read
 * adb shell am broadcast -a com.cuber.bookvoice.TEST.pause
 * adb shell am broadcast -a com.cuber.bookvoice.TEST.state
 * ```
 *
 * ВАЖНО, ЧЕГО ЗДЕСЬ НЕТ И НЕ ДОЛЖНО БЫТЬ: команд, которые чистят библиотеку,
 * меняют настройки чтения, голос или файлы. Только две ручки (зеркало журнала и
 * проверка «сломанный движок»), чтение/пауза и ответ о состоянии. Ошибка в
 * команде не должна стоить человеку книги.
 */
class TestHooks : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_MIRROR -> {
                Diag.mirrorToLogcat = intent.getBooleanExtra(EXTRA_ON, true)
                // Сначала включаем зеркало, потом пишем — иначе эта строка в
                // logcat не попадёт.
                Diag.log(app, "test", "зеркало журнала в logcat: ${if (Diag.mirrorToLogcat) "включено" else "выключено"}")
                if (!Diag.mirrorToLogcat) {
                    Log.i(Diag.LOGCAT_TAG, "test: зеркало выключено")
                }
            }

            ACTION_BROKEN -> {
                val on = intent.getBooleanExtra(EXTRA_ON, true)
                // Вид поломки: `--es kind refuse` — движок отказывается принять
                // заявку, иначе — мгновенное «готово» без звука (см. [queueDirect]).
                val refuse = intent.getStringExtra("kind") == "refuse"
                val key = if (refuse) ExpertPrefs.KEY_BROKEN_REFUSE else ExpertPrefs.KEY_BROKEN_ENGINE
                val p = app.getSharedPreferences("reader", Context.MODE_PRIVATE)
                p.edit().putBoolean(key, on).apply()
                Diag.log(
                    app, "test",
                    "проверка ${if (refuse) "«движок отказывается»" else "«сломанный движок»"}: " +
                        if (on) "включена" else "выключена",
                )
            }

            ACTION_READ -> {
                val l = MediaSessionService.listener
                if (l == null) {
                    Diag.log(app, "test", "команда «читать»: читалка не подключена (книга не открыта)")
                } else {
                    Diag.log(app, "test", "команда «читать» из проверки")
                    l.onMediaPlay()
                }
            }

            ACTION_PAUSE -> {
                val l = MediaSessionService.listener
                Diag.log(app, "test", "команда «пауза» из проверки")
                l?.onMediaPause()
            }

            ACTION_LOG -> {
                // ВЫГРУЗИТЬ ЖУРНАЛ В LOGCAT. Журнал лежит в приватной папке
                // приложения: без этого его видит только Серж, а вопрос «что было
                // в тот момент» решается секундами.
                //
                // `--ei lines N` — сколько строк, `--ei skip M` — сколько строк
                // ПРОПУСТИТЬ от конца (чтобы листать журнал назад). Выдаём пачками
                // по 40 строк с паузой: logd режет бурст (из 300 строк разом
                // доходило 95 — проверено 24.10.2026).
                Diag.mirrorToLogcat = true
                val want = intent.getIntExtra("lines", 120).coerceIn(20, 4000)
                val skip = intent.getIntExtra("skip", 0).coerceAtLeast(0)
                val f = java.io.File(app.filesDir, "diag.log")
                val all = runCatching { f.readLines() }.getOrDefault(emptyList())
                val end = (all.size - skip).coerceAtLeast(0)
                val start = (end - want).coerceAtLeast(0)
                val slice = if (end > start) all.subList(start, end) else emptyList()
                Log.i(
                    Diag.LOGCAT_TAG,
                    "test: журнал, строки $start…$end из ${all.size} (skip=$skip)",
                )
                Thread {
                    for ((idx, line) in slice.withIndex()) {
                        Log.i(Diag.LOGCAT_TAG, line)
                        if (idx % 40 == 39) runCatching { Thread.sleep(300) }
                    }
                }.start()
            }

            ACTION_GOTO -> {
                // Перейти к месту в открытой книге: `--ei ch 0 --ei s 0` — начало.
                // Нужно, чтобы проверять чтение там, где впереди много фраз (после
                // «убегания» книга стоит на последней странице).
                val ch = intent.getIntExtra("ch", 0)
                val s = intent.getIntExtra("s", 0)
                Diag.log(app, "test", "команда «перейти»: глава $ch, предложение $s")
                ReaderEngine.goTo(ch, s)
            }

            ACTION_STATE -> {
                val p = ReaderEngine.player
                Diag.log(
                    app, "test",
                    "состояние: читалка ${if (ReaderEngine.book != null) "с книгой" else "без книги"}; " +
                        "карточка ${if (MediaSessionService.isUp()) "поднята" else "не поднята"}; " +
                        "движок ${p?.enginePackage ?: "системный"}; " +
                        "играет ${if (ReaderEngine.playing) "да" else "нет"}; " +
                        "сломанный движок ${if (ExpertPrefs.brokenEngine(app)) "включён" else "выключен"}; " +
                        "зеркало ${if (Diag.mirrorToLogcat) "включено" else "выключено"}",
                )
            }

            // ПРОВЕРКА ЭКРАНА «ДОСТУП КО ВСЕМ ФАЙЛАМ» (24.10.2026). Нужна потому,
            // что Серж жалуется: приложение говорит, что доступа нет, а само
            // никуда не заходит. Своими руками это не проверить (на телефоне доступ
            // может быть уже выдан), а командой — видно и строку журнала, и то, какой
            // именно из трёх экранов открылся (см. [AllFiles.openSettings]).
            ACTION_ALLFILES -> {
                Diag.log(
                    app, "test",
                    "проверка экрана доступа: сейчас доступ " +
                        if (AllFiles.granted(app)) "ЕСТЬ" else "НЕ ДАН",
                )
                AllFiles.openSettings(app)
            }

            else -> Diag.log(app, "test", "неизвестная команда: ${intent.action}")
        }
    }

    companion object {
        private const val EXTRA_ON = "on"

        const val ACTION_MIRROR = "com.cuber.bookvoice.TEST.mirror"
        const val ACTION_BROKEN = "com.cuber.bookvoice.TEST.broken"
        const val ACTION_READ = "com.cuber.bookvoice.TEST.read"
        const val ACTION_PAUSE = "com.cuber.bookvoice.TEST.pause"
        const val ACTION_STATE = "com.cuber.bookvoice.TEST.state"
        const val ACTION_LOG = "com.cuber.bookvoice.TEST.log"
        const val ACTION_GOTO = "com.cuber.bookvoice.TEST.goto"
        const val ACTION_ALLFILES = "com.cuber.bookvoice.TEST.allfiles"
    }
}
