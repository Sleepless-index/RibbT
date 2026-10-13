plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "app.mediadl"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.mediadl"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "mediadl-local"
            keyAlias = "mediadl"
            keyPassword = "mediadl-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES,LICENSE*,NOTICE*,INDEX.LIST}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("flask>=3.0")
            install("requests>=2.31")
            install("yt-dlp")
            install("certifi")
            install("websockets")
            install("mutagen")
        }
    }
}

val syncServer by tasks.registering(Sync::class) {
    val serverRoot = File(rootProject.projectDir, "server")
    from(File(serverRoot, "mediadl")) {
        exclude("**/__pycache__/**", "**/*.pyc")
    }
    from(File(serverRoot, "config/settings.json")) {
        into("defaults")
    }
    into(layout.projectDirectory.dir("src/main/python/mediadl"))
}
tasks.matching { it.name != "syncServer" && it.name.contains("Python") }.configureEach {
    dependsOn(syncServer)
}
tasks.named("preBuild") {
    dependsOn(syncServer)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:common:0.18.1")
}
