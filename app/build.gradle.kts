import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CloudStream's official plugin API ("library" module). Coordinates confirmed from Zangetsu's build.gradle.kts.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val cloudstreamVersion = "v4.8.0"   // same tag Zangetsu ships with

android {
    namespace = "com.streamly"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.streamly"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Your own free TMDB v3 key goes in local.properties as TMDB_KEY=...
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("TMDB_KEY", "")}\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("com.github.recloudstream.cloudstream:library:$cloudstreamVersion")
    // Same helpers CloudStream plugins expect to find at runtime (plugins may use any of these).
    // NiceHttp (the `app` HTTP global plugins use) already comes in transitively with the CloudStream library.
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Plugin settings UIs are AppCompat + Material (bottom sheets, dialogs); PluginSettingsActivity hosts them.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
