pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/maven/")
        maven("https://mirrors.aliyun.com/google/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/maven/")
        maven("https://mirrors.aliyun.com/google/")
        google()
        mavenCentral()
        maven("https://jitpack.io")   // BlurView（磨砂玻璃）
    }
}

rootProject.name = "Taktu"
include(":app")
