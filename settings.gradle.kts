pluginManagement {
    // Kotlin 2.4 requires R8 9.1.29; override AGP 8.13's bundled R8 only.
    // R8's stable releases are published to this official Maven repository;
    // Google Maven mirrors and Maven Central do not contain this artifact.
    buildscript {
        repositories {
            maven("https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
        // Aliyun/public зеркалирует Maven Central — для kotlin-reflect и т.д.
        maven("https://maven.aliyun.com/repository/public")
        // Aliyun/google зеркалирует Google Maven — для KSP symbol-processing
        maven("https://maven.aliyun.com/repository/google")

        // Gradle Plugin Portal — для Kotlin и KSP плагинов.
        // P.S. Его transitive dependencies разрешаются ТОЛЬКО через
        // репозитории pluginManagement. Aliyun/public перехватывает
        // kotlin-reflect/stdlib до того, как Plugin Portal редиректит
        // на заблокированный repo.maven.apache.org (HTTP 403 в РФ).
        gradlePluginPortal()

        // Прямой Maven Central (если Aliyun чего-то не имеет)
        maven("https://repo1.maven.org/maven2")

        // Остальные зеркала
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
    }
}
plugins {
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Maven Central (прямой URL, не repo.maven.apache.org — тот 403 в РФ)
        maven("https://repo1.maven.org/maven2")

        // Google библиотеки — только через Aliyun зеркало (dl.google.com 403 в РФ)
        maven("https://maven.aliyun.com/repository/google")

        // Aliyun mirror для всего остального
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.huaweicloud.com/repository/maven/")
        maven("https://jitpack.io")
    }
}
rootProject.name = "Impulse-client"
include(":app")
