import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 后端地址 / 共享密钥 / 纪念日都不写死在源码里——仓库是公开的，写死就等于泄密。
 * 它们从项目根目录的 keys.properties 读（该文件在 .gitignore 里，不会进版本库）。
 * 文件不存在时用占位值，保证 clone 下来直接能编译通过。
 */
val keys = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun keyOr(name: String, fallback: String): String =
    keys.getProperty(name)?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback

android {
    namespace = "com.example.qlapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.qlapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 见文件开头：值来自 keys.properties，不进版本库
        buildConfigField("String", "BASE_URL", "\"${keyOr("BASE_URL", "https://your-worker.workers.dev")}\"")
        buildConfigField("String", "APP_KEY", "\"${keyOr("APP_KEY", "")}\"")
        buildConfigField("long", "ANNIVERSARY", "${keyOr("ANNIVERSARY", "1735689600000")}L")

        ndk {
            // 必须显式钉住 ABI，别依赖默认。
            // 起因：在 Android Studio 里对 x86_64 模拟器（Pixel_9.avd）Run 过之后，
            // 打包会带上 android.injected.build.abi=x86_64，把 arm 的原生库过滤掉，
            // 出来的包只剩 lib/x86_64 —— 红米 K80 是 arm64-v8a，装它直接报
            // INSTALL_FAILED_NO_MATCHING_ABIS（-113）。显式列出来就不会再被过滤。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Prefs 里的后端地址 / 密钥 / 纪念日都从 BuildConfig 读
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.exifinterface)
}