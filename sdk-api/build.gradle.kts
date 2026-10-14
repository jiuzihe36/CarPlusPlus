import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = 36
    namespace = "com.carpp.sdk.sdk"

    defaultConfig {
        minSdk = 16
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).jvmTarget = "1.8"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    // 契约层用 StateFlow 暴露状态，因此协程是 SDK 的公共 API 的一部分。
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
