plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Kotlin 2.x 起 Compose 编译器随 Kotlin 版本走，需显式声明该插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
