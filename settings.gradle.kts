pluginManagement {
    repositories {
        // 国内镜像优先（实测比官方源快 10 倍），官方源保留在最后兜底：
        // 镜像对不存在的坐标有时返回 200 + HTML，会导致 POM 解析失败，有官方源兜底更稳。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")   // AGP / androidx / material
        maven("https://maven.aliyun.com/repository/public")   // kotlin / okhttp / nanohttpd
        google()
        mavenCentral()
    }
}

rootProject.name = "XiaoAiCast"
include(":app")
