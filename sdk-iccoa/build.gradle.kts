import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

plugins {
    id("com.android.library")
    kotlin("android")
}

// =====================================================================
// :sdk-iccoa — ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）
// 协议插件。它只是 :sdk-api 的 PhoneLink 的一种实现，可以整体替换成别的协议，
// :carpp-sdk 不认识它，只认识 PhoneLinkRegistry 里注册进来的实现。
//
// 协议本体是闭源 dex（icar-sdk.dex，约 7 MB），仓库里不分发：
//   · 编译期：stubs/icar-sdk-stubs.jar（纯签名存根，compileOnly，不进产物）
//   · 运行期：由 :app 的 injectIccoaDex 任务把 dex 追加进 APK 的 classesN.dex
// 详见 sdk-iccoa/README.md 与 docs/ICCOA_SDK_INTEGRATION.md。
// =====================================================================
android {
    compileSdk = 36
    namespace = "com.carpp.iccoa"

    defaultConfig {
        // 跟 :carpp-sdk 保持一致（minSdk 21，Compose 要求），否则 manifest 合并会冲突。
        minSdk = 21
        consumerProguardFiles("proguard-rules.pro")
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            minSdk = 21
        }
        create("github") {
            dimension = "distribution"
            // Default minSdk 21 from defaultConfig is used
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
            it.jvmTarget = "1.8"
        }
    }
}

dependencies {
    api(project(":sdk-api"))
    // 投影 Activity 复用 SDK 的 App 容器与主题资源。
    implementation(project(":carpp-sdk"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // ★ 编译期签名存根：只保证本模块能编译，绝不进 APK。
    compileOnly(files("stubs/icar-sdk-stubs.jar"))

    // ★ ICCOA SDK 运行期依赖：SDK 内部用 Room 维护「信任设备(Peer)」表与跨进程失效。
    //   已用 dex 解析器确认注入的 icar-sdk.dex 里 androidx/room/* 的【定义数 = 0】（只有引用），
    //   缺这个依赖会在 SDK 首次访问 Peer 库时抛 NoClassDefFoundError。
    implementation("androidx.room:room-runtime:2.4.3")
}
