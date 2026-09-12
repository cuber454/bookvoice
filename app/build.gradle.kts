plugins {
    id("com.android.application")
}

android {
    namespace = "com.cuber.bookvoice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cuber.bookvoice"
        minSdk = 24
        targetSdk = 36
        // Ветка portyanka: сплошная прокрутка книги (портянка, msg4308).
        // Номер выше 0.4.26, чтобы в «О программе» было видно, какая сборка
        // стоит на телефоне. Соберём релизом — только по слову Сергея.
        // msg4416: 0.4.27 успела разойтись несколькими тестовыми сборками, и в
        // «О программе» их было не различить — склейка предложений помечена 0.4.28.
        // 0.4.31: убрана галочка «Прыгать на ходу, не дожидаясь остановки»
        // (msg4450/4452) — прыжок остался только по отпусканию прокрутки.
        // 0.4.32: пункт «Сетевые библиотеки» в меню открытой книги (msg4464).
        versionCode = 92
        versionName = "0.4.32"
    }

    signingConfigs {
        // CI (#35): APK автообновления обязан быть подписан тем же ключом, что и
        // установленная версия, иначе апдейт не встанет поверх. На GitHub-раннере
        // AGP ищет debug-keystore не в $HOME/.android, а где-то ещё, и молча
        // генерирует новый — поэтому на CI путь к ключу задаём явно через env.
        // Локально env нет — остаётся стандартный ~/.android/debug.keystore.
        val ciKs = System.getenv("BV_DEBUG_KEYSTORE")
        if (ciKs != null) {
            create("ciDebug") {
                storeFile = file(ciKs)
                storePassword = System.getenv("BV_DEBUG_KEYSTORE_PW") ?: "android"
                keyAlias = "androiddebugkey"
                keyPassword = System.getenv("BV_DEBUG_KEY_PW") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("BV_DEBUG_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 built-in Kotlin: jvmTarget берётся из compileOptions.targetCompatibility.
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // enableEdgeToEdge для edge-to-edge (SDK 36): appcompat тянет activity 1.7.0,
    // где этой функции ещё нет — поднимаем до версии с ней.
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media:media:1.7.0")
    // PDF: извлечение текстового слоя + дерево закладок (для PDF-инструкций).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
