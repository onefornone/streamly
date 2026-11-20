plugins {
    // Versions match what CloudStream library v4.8.0 needs (it requires compileSdk 36 -> AGP 8.9+ and a Kotlin 2.2 compiler).
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
