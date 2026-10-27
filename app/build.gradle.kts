import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Upload-nøglen til Google Play læses fra keystore.properties (lokalt)
// eller fra miljøvariabler (GitHub Actions). Uden nøgle bygges kun debug.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("SIGNING_STORE_FILE")?.let { setProperty("storeFile", it) }
    System.getenv("SIGNING_STORE_PASSWORD")?.let { setProperty("storePassword", it) }
    System.getenv("SIGNING_KEY_ALIAS")?.let { setProperty("keyAlias", it) }
    System.getenv("SIGNING_KEY_PASSWORD")?.let { setProperty("keyPassword", it) }
}
val hasSigning = signing.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "dk.thorbjorn.drlyd"
    compileSdk = 36

    defaultConfig {
        applicationId = "dk.thorbjorn.drlyd"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("VERSION_CODE") ?: "0")
    }

    signingConfigs {
        if (hasSigning) {
            create("upload") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
}
