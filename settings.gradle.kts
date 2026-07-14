pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://jitpack.io") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        // Maven Central 作为 central 仓库的兜底：aliyun central 镜像个别构件（如 leakcanary-android）
        // 返回损坏的空 AAR，需由 Maven Central 提供完好副本。
        mavenCentral()
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "aidev6"
include(":app")
