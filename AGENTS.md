# BookVoice

Читалка книг для незрячих пользователей, управление голосом TalkBack. Kotlin без
Compose: обычные Activity, XML-разметка, viewBinding.

## Параметры
- applicationId и namespace `com.cuber.bookvoice`, compileSdk и targetSdk 36,
  minSdk 24, JVM target 17.
- AGP 9.0.1 со встроенной поддержкой Kotlin: отдельный плагин kotlin-android не
  подключается, jvmTarget берётся из compileOptions.
- gradle-wrapper.properties в репозитории указывает на Gradle 8.9, для AGP 9 это
  мало. Локально собирать системным Gradle 9.7.1 (scoop), а не `.\gradlew.bat`.
  На CI ставится Gradle 9.1 и вызывается напрямую.
- Зависимости: okhttp 4.12.0, appcompat 1.7.0, material 1.12.0, androidx.media 1.7.0,
  androidx.activity 1.9.3, pdfbox-android 2.0.27.0.
- Версия 0.4.87, versionCode 147. Тег релиза = "v" + versionName.

## Сборка
- Переменные: `GRADLE_USER_HOME=D:\Android\android-hello\.gradle-home`,
  `ANDROID_HOME=C:\AndroidSDK`.
- Команда из корня проекта:
  `gradle --no-daemon --console=plain :app:assembleDebug`
- Результат: `app\build\outputs\apk\debug\app-debug.apk`.
- Собранный файл сразу отправлять в Телеграм этим же чатом (sendDocument), а не
  только оставлять на диске: Серж проверяет сборки с телефона.
- Путь к SDK лежит в local.properties (в git не хранится).
- В песочнице DSH сборка проходит только с расширенным доступом: в обычном режиме
  не стартует процесс сборки Gradle.

## Git и подпись
- Репозиторий клонирован с повышенными правами, поэтому git зовём с
  `-c safe.directory='*'`, иначе он ругается на dubious ownership.
- Локальный APK подписан debug-ключом машины, релизный APK с GitHub — ключом из
  секретов репозитория. Подписи разные: APK не встают друг поверх друга, перед
  сменой варианта приложение надо удалять.

## Ориентиры
- План работ и фазы: ROADMAP.md.
- Согласовано, но пока не делаем — перевод чужого текста и правила чтения:
  docs/BACKLOG.md.
- CI: .github/workflows/build-apk.yml собирает APK при публикации релиза и
  прикрепляет его к релизу; приложение само предлагает обновление (Updater,
  UpdateFlow).
