plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.enhance.comic"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.enhance.comic"
        minSdk = 26
        targetSdk = 36
        // versionCode 只会增加；versionName 也只会增加，绝不允许回退，
        // 否则国产 ROM 的安装器会判定为降级并拒绝覆盖安装。
        versionCode = 20
        versionName = "1.9.9"
    }

    // 固定签名：密钥随项目走，任何机器、任何次构建都是同一把 key，
    // 这样新版本可以直接覆盖安装，不需要先卸载（数据也不会丢）。
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("key/enhancecomic.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = "2026.10.00"
    implementation(platform("androidx.compose:compose-bom-beta:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // RAR / CBR: pure Java, no native code, works on all ABIs.
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.slf4j:slf4j-api:1.7.36")
    // 7z / CB7 / TAR / TAR.GZ / TAR.BZ2 / TAR.XZ: pure Java as well.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
}
