import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
}

android {
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    namespace = "com.carpp.sdk"

    buildFeatures {
        buildConfig = true
        aidl = true // needed for shizuku
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val copyRootAssets = tasks.register<Copy>("copyRootAssets") {
        // CHANGELOG.md is the English default; the About screen picks a locale-specific
        // CHANGELOG-<lang>[-<COUNTRY>].md when one exists. CHANGELOG-UPSTREAM.md holds the
        // inherited upstream history and is deliberately not shipped.
        from(
            "${project.rootDir}/CHANGELOG.md",
            "${project.rootDir}/CHANGELOG-zh.md",
            "${project.rootDir}/CHANGELOG-zh-TW.md",
            "${project.rootDir}/LICENSE"
        )
        into("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    println("Detected available locales: $availableLocales")

    sourceSets {
        getByName("main") {
            assets.srcDirs("${project.layout.buildDirectory.get().asFile}/generated/assets/root")
        }
    }

    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyRootAssets)
    }

    tasks.configureEach {
        if (name.contains("lint", ignoreCase = true)) {
            dependsOn(copyRootAssets)
        }
    }

    defaultConfig {
        minSdk = 16
        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")

        // 库不再有 AGP 只给 application 生成的 APPLICATION_ID / VERSION_* 字段
        // （APPLICATION_ID 本来就不该出现在库里 —— 真实包名一律走 context.packageName，
        //  否则自定义权限名、Shizuku 组件名会指向库的 namespace 而不是宿主 App）。
        // About / 备份这些界面要显示版本，就用库自己的版本号，与宿主保持一致。
        buildConfigField("String", "VERSION_NAME", "\"0.1.1\"")
        buildConfigField("int", "VERSION_CODE", "2")

        // Car++ replaces the phone protocol with ICCOA CarLink (Xiaomi CarWith), so the Android
        // Auto half of the codebase is dead weight at runtime. This flag lets the shared code —
        // chiefly AapService, which Car++ keeps only as the foreground keep-alive — skip the
        // AA-only side effects.
        buildConfigField("boolean", "ICCOA_ONLY", "true")

        setProperty("archivesBaseName", "carpp-sdk")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }

        consumerProguardFiles("proguard-project.txt")
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

    buildTypes {
        getByName("release") {
            // 库本身不裁剪：裁剪留给宿主 App（它会合并这里的 consumer 规则），
            // 这样第三方接入时可以自己决定保留哪些入口。
            isMinifyEnabled = false
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
}

dependencies {
    // ★ SDK 契约层：宿主 App 只依赖它就能面向 PhoneLink 编程，不必知道具体协议。
    api(project(":sdk-api"))
    implementation(project(":contract"))

    // Conscrypt (Flavor specific: 2.6.1 for Playstore 16KB alignment; 2.5.3 for Github minSdk 16)
    "playstoreImplementation"("org.conscrypt:conscrypt-android:2.6.1")
    "githubImplementation"("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku for root / shell access
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}
