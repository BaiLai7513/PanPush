pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 用 PREFER_SETTINGS：既尊重本文件的仓库声明，也允许
    // 本机 ~/.gradle/init.d/*.gradle 里的镜像注入（该机器 Maven Central 直连被墙，
    // 依赖 init 脚本里的阿里云镜像兜底）。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PanPush"
include(":app")
