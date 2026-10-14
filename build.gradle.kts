plugins {
    // AGP 8.13: Android 16 (API 36) uchun compileSdk 36 ni qo'llaydi; Gradle 8.13 talab qiladi.
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    // Kotlin 2.x da Compose kompilyatori alohida plagin (composeOptions o'rniga)
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    // Firebase (Google login + Email/Parol)
    id("com.google.gms.google-services") version "4.5.0" apply false
}
