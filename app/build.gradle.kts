import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// CloudStream's official plugin API ("library" module; resolves as library-android on JitPack).
// v4.8.0 needs compileSdk 36, AGP 8.9+ and Kotlin 2.2.x - see the root build.gradle.kts.
val cloudstreamVersion = "v4.8.0"

android {
    namespace = "com.streamly"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.streamly"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Your own free TMDB v3 key goes in local.properties as TMDB_KEY=... (CI writes it from a repo secret).
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("TMDB_KEY", "")}\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // The CloudStream library pulls okhttp/jspecify/etc. whose META-INF files collide when merged.
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

// Newer plugins call kotlinx.coroutines functions that only exist from 1.11.0 (e.g. BuildersKt.runBlockingK) and
// would fail to load against the 1.10.x the library resolves to. 1.11.0 is a backward-compatible superset.
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    }
}

dependencies {
    implementation("com.github.recloudstream.cloudstream:library:$cloudstreamVersion")
    // Jackson, NiceHttp (the `app` HTTP global) and jsoup already come in with the CloudStream library at runtime.
    // compileOnly only lets our DataStore stand-in see Jackson's JsonMapper without bundling a second copy.
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Plugins' extractors parse URLs with Ktor; without it they throw NoClassDefFoundError when resolving links.
    runtimeOnly("io.ktor:ktor-http:3.2.3")

    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Plugin settings UIs are AppCompat + Material (bottom sheets, dialogs); PluginSettingsActivity hosts them.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
