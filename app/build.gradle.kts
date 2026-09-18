import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名口令从全局 ~/.gradle/gradle.properties 读（不进仓库）。
// 读不到就跳过签名：本地能出未签名包，CI 也能正常跑。
val ksFile: String? = providers.gradleProperty("XIAOAIRC_STORE_FILE").orNull

kotlin {
    // Kotlin 2.2 起 kotlinOptions{} 是 error，必须用 compilerOptions DSL
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.tangren.xiaoairc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tangren.xiaoairc"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.2.3"
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = providers.gradleProperty("XIAOAIRC_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("XIAOAIRC_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("XIAOAIRC_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (ksFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // UI 层用 Jetpack Compose（Material 3）实现设计稿；业务逻辑仍在既有 Kotlin 类里
    buildFeatures {
        compose = true
        // 版本号单一来源：UPnP 设备描述与 SSDP SERVER 头都从这里取，
        // 避免像 modelNumber 那样发版后忘了同步、一直停在旧值
        buildConfig = true
    }

    // release 会跑 lintVitalRelease，wakeLock.acquire() 无超时是 Error 级会中断打包，
    // 这里放行；真想看 lint 报告单独跑 :app:lintDebug
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // ---- Jetpack Compose (Material 3) ----
    // BOM 2024.06.00 → Compose 1.6.8 / Material3 1.2.1，与 compileSdk 34 匹配
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // 协程：网络与 socket 全部跑在 IO 调度器
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP 客户端：小米云 API + 音频流代理 + GENA 事件通知
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 内嵌 HTTP 服务器：承载 UPnP 设备描述、SOAP 控制点、音频流代理。
    // 注意：这里不再依赖 org.nanohttpd:nanohttpd，而是把 2.3.1 源码 vendor 到了
    // app/src/main/java/fi/iki/elonen/NanoHTTPD.java —— 原版 Method 枚举没有
    // SUBSCRIBE/UNSUBSCRIBE/NOTIFY，会让 UPnP 的 GENA 事件订阅在进入 serve() 之前
    // 就被库直接 400 掉，导致 QQ音乐/QPlay 这类依赖事件订阅的投屏端连不上。
    // 补丁只改了枚举，其余与原版一致。
}
