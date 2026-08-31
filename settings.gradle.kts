pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // China-network convenience mirrors (appended; harmless elsewhere):
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "kqprobe"
include(":app")
