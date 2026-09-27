@file:Suppress("UnstableApiUsage")

// 仓库顺序说明（勿随意调整）：
// 官方源在前、国内镜像在后。腾讯云 maven-public / 阿里云 public 存在「有 POM 但没有 jar」的
// 残缺条目，而 Gradle 一旦在某个仓库找到模块元数据，就只会用该仓库取 jar、不会回落到后面的仓库，
// 所以镜像放最前会直接导致解析失败。
// 实测反例：dev.zacsweers.moshix:moshi-sealed-runtime:0.37.0 在腾讯云镜像上只有 POM（jar 404）。
// 镜像放最后仍可在官方源不可达时兜底；若代理长期关闭、官方源非常慢，可把两行 maven(...)
// 上移到各自仓库块的最前面，但需自行承担上述残缺风险。
pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/public/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // KeydroidX 生态 SDK 的本地发布产物（keydroidx-core publishToMavenLocal）
        mavenLocal()
        google()
        mavenCentral()
        // keydroidx-common 传递依赖 com.github.getActivity:XXPermissions:20.0 发布在 JitPack
        maven("https://jitpack.io")
        // 兜底镜像，顺序理由见文件顶部注释
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://maven.aliyun.com/repository/public/")
    }
}

include("app")
