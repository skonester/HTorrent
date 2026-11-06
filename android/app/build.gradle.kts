plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds make one APK per processor type (see splits below); run them on their own, not together with debug.
val releaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

android {
    namespace = "com.htorrent.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.htorrent.mobile"
        minSdk = 29
        targetSdk = 35
        // Same version as the Mac and Windows apps (VERSION at the repository root), e.g. 0.6.7 -> code 607.
        val version = rootProject.file("../VERSION").readText().trim()
        val (major, minor, patch) = version.split(".").map { it.takeWhile(Char::isDigit).ifEmpty { "0" }.toInt() }
        versionCode = major * 10000 + minor * 100 + patch
        versionName = version
        // The video engine (Python, yt-dlp, ffmpeg) is native code. Debug builds: phones (ARM) plus x86_64 for emulators.
        // Release builds pick their processor types with the splits below (Gradle refuses both at once).
        if (!releaseBuild) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // The engine is about 50 MB per processor type, so the release is one APK per type: arm64 (almost every phone)
    // and 32-bit ARM (older phones). Debug builds stay a single APK for every type (emulators, screenshots).
    splits {
        abi {
            isEnable = releaseBuild
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Release signing comes from the environment (GitHub secrets); keys are never stored in the repository.
    val keystore = System.getenv("HTORRENT_ANDROID_KEYSTORE")
    signingConfigs {
        if (!keystore.isNullOrEmpty() && file(keystore).exists()) {
            create("release") {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("HTORRENT_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HTORRENT_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("HTORRENT_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The video engine runs its programs from the installed native libraries, so they must be unpacked.
        jniLibs { useLegacyPackaging = true }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Video engine: yt-dlp (with Python) and ffmpeg, the same engine as the Mac and Windows apps.
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
