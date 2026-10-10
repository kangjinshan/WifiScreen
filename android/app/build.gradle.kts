import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val releaseProperties = Properties().apply {
    rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    namespace = "com.kanayama.wifiscreen"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kanayama.wifiscreen"
        minSdk = 21
        targetSdk = 34
        versionCode = 18
        versionName = "0.3.2"
    }
    buildFeatures { buildConfig = true }
    if (releaseProperties.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseProperties.getProperty("storeFile"))
                storePassword = file(releaseProperties.getProperty("storePasswordFile")).readText().trim()
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = storePassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation("junit:junit:4.13.2")
}
