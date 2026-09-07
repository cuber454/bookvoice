plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cuber.bookvoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cuber.bookvoice"
        minSdk = 24
        targetSdk = 34
        versionCode = 50
        versionName = "0.3.90"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.media:media:1.7.0")
    // PDF: извлечение текстового слоя + дерево закладок (для PDF-инструкций).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
