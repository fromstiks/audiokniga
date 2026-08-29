plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ua.starky.audiokniga"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.starky.audiokniga"
        minSdk = 24
        targetSdk = 35
        // versionCode намеренно не растёт: иначе на телефон нельзя было бы поставить
        // сборку старее установленной, а именно так и откатываются с неудачной.
        versionCode = 1
        // Короткий хеш коммита видно в системных настройках приложения — по нему
        // сразу понятно, обновилась ли установленная сборка.
        versionName = System.getenv("GITHUB_SHA")?.take(7)?.let { "0.1.0-$it" } ?: "0.1.0"
    }

    /**
     * Ключ подписи лежит в репозитории намеренно.
     *
     * Android разрешает обновить установленное приложение только APK с той же подписью.
     * Сборщик на CI каждый раз создавал себе новый временный debug-ключ, поэтому каждую
     * сборку приходилось ставить с удалением предыдущей — вместе с базой и прогрессом.
     * Общий ключ решает это: подпись одна и та же во всех сборках.
     *
     * Пароль здесь не секрет и секретом быть не может: ключ подписывает только отладочные
     * сборки с отдельным applicationId. Для публикации в магазине нужен свой, закрытый.
     */
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/audiokniga-debug.jks")
            storePassword = "audiokniga"
            keyAlias = "audiokniga"
            keyPassword = "audiokniga"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            // Файла может не быть в чужой копии репозитория — тогда остаётся ключ по умолчанию.
            if (rootProject.file("keystore/audiokniga-debug.jks").exists()) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.jsoup)
    implementation(libs.androidx.documentfile)
}
