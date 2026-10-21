import java.io.File
import java.util.Properties

/**
 * Release 签名凭据（RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD）。
 *
 * 只从不进入版本库的位置读取，优先级：环境变量 > 根目录 local.properties（已被 .gitignore 忽略）。
 * 严禁把口令写死在本文件或任何会被 git 跟踪的文件里。
 *
 * 注意：这里刻意不用 rootProject.file(...)，因为本项目开启了
 * org.gradle.unsafe.isolated-projects，跨工程访问 Project API 会直接构建失败。
 */
val localProperties = Properties().apply {
    val localPropertiesFile = File(layout.projectDirectory.asFile.parentFile, "local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProperty(name: String): String? = System.getenv(name) ?: localProperties.getProperty(name)

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp") version "2.3.12"
    id("com.anthonycr.plugins.mezzanine") version "2.5.0"
    id("com.autonomousapps.dependency-analysis") version "3.19.2"
    id("com.squareup.sort-dependencies") version "0.21.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.anthonycr.plugins.mockingbird") version "3.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

android {
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        buildToolsVersion = "37.0.0"
        applicationId = "acr.browser.lightning"
        minSdk = 24
        targetSdk = 37
        versionCode = 102
        versionName = "5.1.0"
        buildConfigField("boolean", "FULL_VERSION", "true")
        vectorDrawables.useSupportLibrary = true
    }

    val isCi = System.getenv("CI") == "true"

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            // test.jks 位于 app/ 目录，已被 .gitignore 的 *.jks 规则排除，不要提交
            storeFile = file(signingProperty("RELEASE_STORE_FILE") ?: "test.jks")
            storePassword = signingProperty("RELEASE_STORE_PASSWORD")
            keyAlias = signingProperty("RELEASE_KEY_ALIAS") ?: "key0"
            keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
            enableV1Signing = true // 兼容旧版本
            enableV2Signing = true // 推荐开启
        }
    }

    buildTypes {
        named("debug") {
            multiDexEnabled = true
            isMinifyEnabled = false
            isShrinkResources = false
            setProguardFiles(listOf("proguard-project.txt"))
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false
        }

        named("release") {
            multiDexEnabled = false
            isMinifyEnabled = !isCi
            isShrinkResources = !isCi
            setProguardFiles(listOf("proguard-project.txt"))
            enableUnitTestCoverage = false
            enableAndroidTestCoverage = false

            // 使用上面的测试打包密钥签名，否则 release 产物是未签名的
            signingConfig = signingConfigs.getByName("release")

            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
                abiFilters.add("armeabi")
                abiFilters.add("mips")
            }
        }
    }

    packaging {
        resources {
            excludes += listOf(".readme")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = true
        disable += "RepeatOnLifecycleWrongUsage"
    }
    namespace = "acr.browser.lightning"

    testOptions {
        unitTests.all {
            it.jvmArgs = listOf(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.runtimeConfiguration.resolutionStrategy.activateDependencyLocking()
    }
}

dependencies {
    val robolectric = "4.17"
    val mezzanineVersion = "2.5.0"
    val daggerVersion = "2.60.1"
    val kotlin = "2.4.20"
    val datastore = "1.2.1"
    val coil = "3.6.3"
    val coroutines = "1.11.0"
    val leakcanary = "2.14"
    val lifecycle = "2.11.0"
    val serialization = "1.11.0"

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-geometry")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.datastore:datastore:$datastore")
    implementation("androidx.datastore:datastore-core:$datastore")
    implementation("androidx.datastore:datastore-preferences:$datastore")
    implementation("androidx.datastore:datastore-preferences-core:$datastore")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-common:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle")
    implementation("androidx.palette:palette:1.0.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.anthonycr.mezzanine:core:$mezzanineVersion")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.dagger:dagger:$daggerVersion")
    implementation("com.guolindev.permissionx:permissionx:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-compose-core:${coil}")
    implementation("io.coil-kt.coil3:coil-core:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
    // KeydroidX 生态按键机 SDK（从 keydroidx-core publishToMavenLocal 发布，AGP 版本与本项目
    // 不同（core=8.5.1 / 本项目=9.4.1），不能用 includeBuild composite build，只能走 mavenLocal）。
    // 更新 core 后需在 core 目录执行：
    //   gradlew.bat :keydroidx-common:publishToMavenLocal :keydroidx-key-core:publishToMavenLocal
    implementation("io.github.cctyl.nokia:keydroidx-key-core:1.0.0")
    implementation("javax.inject:javax.inject:1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serialization")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serialization")
    implementation("org.jsoup:jsoup:1.23.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.leakcanary:leakcanary-android-core:$leakcanary")
    debugImplementation("com.squareup.leakcanary:shark:$leakcanary")

    compileOnly("javax.annotation:jsr250-api:1.0")

    debugRuntimeOnly("com.squareup.leakcanary:leakcanary-android:$leakcanary")

    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:${kotlin}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutines")
    testImplementation("org.robolectric:annotations:$robolectric")
    testImplementation("org.robolectric:robolectric:$robolectric")

    ksp("com.anthonycr.mezzanine:processor:$mezzanineVersion")
    ksp("com.google.dagger:dagger-compiler:$daggerVersion")
}

mezzanine {
    files = files(
        "src/main/html/list.html",
        "src/main/html/bookmarks.html",
        "src/main/html/homepage.html",
        "src/main/js/InvertPage.js",
        "src/main/js/KeyphoneReadingMode.js",
        "src/main/js/KeyphoneScroll.js",
        "src/main/js/TextReflow.js",
        "src/main/js/ThemeColor.js"
    )
}

kotlin {
    jvmToolchain(21)
}
