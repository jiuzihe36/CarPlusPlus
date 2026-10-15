import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream
import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
}

// =====================================================================
// :app 现在是薄壳：源码、资源、JNI 全部在 :carpp-sdk，协议实现在 :sdk-iccoa。
// 这里只保留打包相关的东西 —— 应用 ID、渠道、签名、输出命名。
//
// ICCOA CarLink SDK 以标准 jar 依赖（libs/icar-sdk.jar）接入：由 Gradle/AGP
// 正常完成 d8 → dex → 对齐 → 签名，不再做任何 dex 注入/重签的后处理。
// 这是「原生」集成方式 —— 出 bug 时栈帧、映射、增量编译都正常可用。
// =====================================================================
android {
    compileSdk = 36
    namespace = "com.carpp.app"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Car++ installs as its own package, so it can sit alongside other receiver apps on the same head unit.
        applicationId = "com.carpp.app"
        minSdk = 16
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        setProperty("archivesBaseName", "${applicationId}_${versionName}")
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            minSdk = 21
        }
        create("github") {
            dimension = "distribution"
            // Default minSdk 16 from defaultConfig is used
        }
    }

    signingConfigs {
        create("release") {
            val defaultStoreFile = when {
                rootProject.file("headunit-release-key.jks").exists() -> rootProject.file("headunit-release-key.jks")
                file("../headunit-release-key.jks").exists() -> file("../headunit-release-key.jks")
                else -> null
            }
            if (defaultStoreFile != null) {
                storeFile = defaultStoreFile
            }
            keyAlias = "headunit-revived"

            val keyfile = rootProject.file("key.properties")
            val signingPropsFile = rootProject.file("secrets.properties")

            if (keyfile.exists()) {
                val keyprops = Properties()
                keyprops.load(FileInputStream(keyfile))

                // key.properties lives at the repo root, so resolve a relative storeFile
                // against the root project too — `file(...)` would resolve it against the
                // `app` module directory and silently produce an UNSIGNED release APK.
                if (keyprops.containsKey("storeFile")) {
                    val declared = keyprops.getProperty("storeFile")
                    val candidates = listOf(rootProject.file(declared), file(declared))
                    storeFile = candidates.firstOrNull { it.exists() } ?: candidates.first()
                }
                if (keyprops.containsKey("storePassword")) storePassword = keyprops.getProperty("storePassword")
                if (keyprops.containsKey("keyAlias")) keyAlias = keyprops.getProperty("keyAlias")
                if (keyprops.containsKey("keyPassword")) keyPassword = keyprops.getProperty("keyPassword")
            } else if (signingPropsFile.exists()) {
                val props = Properties()
                props.load(FileInputStream(signingPropsFile))

                storePassword = props.getProperty("HEADUNIT_KEYSTORE_PASSWORD")
                keyPassword = props.getProperty("HEADUNIT_KEY_PASSWORD")
            } else {
                val envStorePass = System.getenv("HEADUNIT_KEYSTORE_PASSWORD") ?: (project.findProperty("HEADUNIT_KEYSTORE_PASSWORD") as? String)
                val envKeyPass = System.getenv("HEADUNIT_KEY_PASSWORD") ?: (project.findProperty("HEADUNIT_KEY_PASSWORD") as? String)
                if (envStorePass != null) storePassword = envStorePass
                if (envKeyPass != null) keyPassword = envKeyPass
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                // 裁剪规则随 SDK 走（:carpp-sdk 也把它作为 consumer 规则对外发布），
                // 这里直接引用同一份，避免两份规则各自漂移。
                "../carpp-sdk/proguard-project.txt"
            )

            val relConfig = signingConfigs.getByName("release")
            if (relConfig.storeFile != null && relConfig.storeFile!!.exists()) {
                signingConfig = relConfig
            }
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
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

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "${variant.applicationId}_${variant.versionName}_debug.apk"
                if(variant.buildType.name == "release") {
                    outputFileName = "${variant.applicationId}_${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
                output.outputFileName = outputFileName
            }
    }
}

// Static launcher shortcuts carry android:targetPackage as a *literal* string: manifest
// placeholders are not substituted outside AndroidManifest.xml, and @string/... cannot be
// used either because ShortcutParser resolves that attribute against the system Resources.
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "debug") return@onVariants
        val shortcuts = file("src/debug/res/xml/shortcuts.xml")
        if (!shortcuts.exists()) return@onVariants
        val expected = variant.applicationId.get()
        val mismatched = Regex("android:targetPackage=\\\"([^\\\"]+)\\\"")
            .findAll(shortcuts.readText())
            .map { it.groupValues[1] }
            .filter { it != expected }
            .toSet()
        if (mismatched.isNotEmpty()) {
            throw GradleException(
                "src/debug/res/xml/shortcuts.xml targets $mismatched but the " +
                    "${variant.name} applicationId is \"$expected\". Static shortcuts " +
                    "would launch nothing; update the android:targetPackage literals."
            )
        }
    }
}

dependencies {
    implementation(project(":carpp-sdk"))
    // ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar）协议实现。
    // 换成别的协议时，把这一行换成你自己的 PhoneLink 实现模块即可。
    implementation(project(":sdk-iccoa"))
    implementation(project(":contract"))

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // ★ ICCOA CarLink SDK（闭源，约 5.3 MB，仓库不分发）：作为标准 jar 依赖接入，
    //   AGP 会用 d8 正常编译进 APK（multidex 自动处理）。
    //   编译期签名由 :sdk-iccoa 的 stubs/icar-sdk-stubs.jar 提供。
    //   详见 app/libs/README.md。
    implementation(files("libs/icar-sdk.jar"))
}
