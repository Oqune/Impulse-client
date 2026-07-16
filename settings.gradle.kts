pluginManagement {
    repositories {
        // Приоритет 1: Зеркала, работающие в РФ
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.huaweicloud.com/repository/maven/")

        // Приоритет 2: Оригинальные репозитории (если зеркала не помогут)
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Для зависимостей проекта используем те же зеркала
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
        maven("https://jitpack.io") // Для библиотек с GitHub

        // Запасные варианты
        google()
        mavenCentral()
    }
}
rootProject.name = "Impulse-client"
include(":app")
