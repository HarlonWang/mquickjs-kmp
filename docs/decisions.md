# 决策记录

只记「为什么这么定」，每条一段。改决策时更新对应条目，不追加叙事。

## 模块命名：`mquickjs-core`

Gradle 模块名与 Maven artifactId 相同，都叫 `mquickjs-core`；Kotlin 包名 `wang.harlon.mquickjs`，不带 `core` 与 `kmp`；仓库名 `mquickjs-kmp`，`kmp` 只出现在这一层。

第一版只有一个库模块也带 `-core`，因为 artifactId 改名等于破坏性变更，而路线图里已经有序列化模块与 Gradle 插件；`kotlinx-coroutines-core`、`ktor-client-core`、`koin-core` 都是同样的做法。artifactId 不放 `kmp`，多平台是 Gradle 模块元数据描述的事实。

前缀沿用上游名字 `mquickjs`，好处是搜索直达，风险是名字不归自己。QuickJS 圈子的 Kotlin 绑定沿用 `quickjs-` 前缀没出过问题，作为默认接受。

## 平台范围：本期只做 Android + iOS

JVM 桌面与 Web 不在本期。去掉桌面 JVM 后不需要交叉编译与动态库解压加载，JNI 只有 Android 一个消费者。`macosArm64` 作为调试宿主加入 targets，既然已经在 targets 里，发布时一并发出比专门排除更省事。

## 版本基线：全部取最新稳定

Kotlin 2.4.20 / AGP 9.4.0 / Gradle 9.7.1（2026-09-09 核实）。AGP 9.4 要求 Gradle ≥ 9.6.0、JDK ≥ 17。NDK 锁定本机已装的 27.1.12297006 而非 AGP 默认的 28.2，避免首次构建额外下载。所有坐标只在 `gradle/libs.versions.toml` 声明。

## 上游锁定：git subtree + commit

上游没有 tag，锁 commit。用 subtree 而非 submodule，使用方 clone 后即完整，不需要额外初始化步骤；上游改动只进 `patches/`。`native/UPSTREAM` 是唯一真值，Gradle 读它生成 `BuildInfo`，运行时通过 `MQuickJs.upstreamCommit` 可查。

## 原生分发：使用方零配置

Android 用 AAR 内置 `.so`；Apple 用 cinterop 的 `staticLibraries` 把 `.a` 打进 klib，不发 CocoaPods / SPM。目标是使用方只加一行依赖坐标。

## 本期不引入的依赖

kotlinx-serialization、atomicfu、kotlinx-benchmark、Dokka 都到对应里程碑再加（见 roadmap.md），避免第一版就背一堆没用上的依赖。
