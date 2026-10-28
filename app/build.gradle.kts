plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mobagent.app"
    compileSdk = 36
    // Google Play: 16 KB sahifa talabi — NDK r28+ native kutubxonalarni 16 KB hizalanishda quradi.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mobagent.app"
        minSdk = 26
        // Google Play: yangi ilova/yangilanishlar 2026-08-31 dan API 36 ni talab qiladi.
        targetSdk = 36
        // Google Play har yuklashda versionCode oshishini talab qiladi: CI da VERSION_CODE beriladi.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // 16 KB xotira sahifasi (Android 15+ / Google Play talabi)
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH",
                    // MUHIM TUZATISH: bu bo'lmasa, CMake native (llama.cpp)
                    // kodni HECH QANDAY optimizatsiya bayrog'isiz (-O3 yo'q)
                    // quradi — bu og'ir matritsa hisoblarini (LLM inference)
                    // o'nlab baravar sekinlashtirib, kichik promptlarda ham
                    // "osilib qolganday" tuyulgan uzoq kutishga sabab bo'ladi.
                    // Bu ilova Debug APK sifatida qurilsa ham, native tomon
                    // doim Release (optimallashtirilgan) bo'lishi kerak.
                    "-DCMAKE_BUILD_TYPE=Release",
                    // TEZLIK: CPU variantlari (dotprod/i8mm/...) CMakeLists.txt'da
                    // GGML_CPU_ALL_VARIANTS bilan quriladi va ish vaqtida tanlanadi;
                    // statik GGML_CPU_ARM_ARCH bu yerdan olib tashlandi.
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_LLAMAFILE=ON"
                    // GGML_VULKAN ni olib tashlandi - CMakeLists.txt'da o'rnatiladi
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // MUHIM: GitHub Actions har build'da YANGI debug kalit yaratadi, shuning uchun
    // SHA-1 har safar o'zgarib, Google bilan kirish ishlamay qolardi.
    // Repo ichidagi qat'iy debug.keystore ishlatiladi — SHA-1 doim bir xil.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release kaliti GitHub Secrets orqali (KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) beriladi.
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Imzo kalitlari bo'lmasa (masalan lokal build) release imzosiz qoladi, debug esa ishlayveradi.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Ubuntu rootfs (assets/linux/*.tar.xz|gz) allaqachon siqilgan: APK uni qayta siqmasin.
    androidResources {
        noCompress += listOf("xz", "gz")
    }

    packaging {
        // proot/loader/talloc diskka haqiqiy fayl sifatida chiqarilishi SHART
        // (nativeLibraryDir'dan ishga tushiriladi) — AndroidManifest'dagi extractNativeLibs bilan mos.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin 2.2+ da android { kotlinOptions { } } XATO beradi — o'rniga shu blok.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // Downloadable Fonts (Space Grotesk / Newsreader / JetBrains Mono) uchun —
    // Type.kt'ga qarang.
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Linux muhiti: Ubuntu rootfs (.tar.gz) ni symlink/hardlink bilan to'g'ri ochish uchun
    implementation("org.apache.commons:commons-compress:1.26.2")
    // .tar.xz (eng ixcham rootfs) ni ochish uchun
    implementation("org.tukaani:xz:1.9")

    // Online rejim uchun HTTP klient
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Firebase Authentication (Google + Email/Parol)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    // Onlayn suhbatlar tarixini hisobga bog'lab saqlash (qurilmalar orasida sinxron)
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-analytics")
    // Admin paneldagi e'lonlarni bildirishnoma (push) sifatida olish
    implementation("com.google.firebase:firebase-messaging")
    // Task.await() uchun
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Google bilan kirish (Credential Manager)
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Google Play obunalari (pullik tarif)
    implementation("com.android.billingclient:billing:8.0.0")
}
