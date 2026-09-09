# mquickjs-kmp

MicroQuickJS 的 KMP 绑定 SDK。开始工作前先读 README.md，再按需读 docs/。

## 结构

- `mquickjs-core/`：唯一的库模块，artifactId 同名，包名 `wang.harlon.mquickjs`
- `native/mquickjs/`：上游 git subtree，**禁止直接修改**，改动进 `native/patches/`
- `native/UPSTREAM`：上游 commit 唯一真值，Gradle 读它生成 `BuildInfo.kt`
- `docs/decisions.md`：为什么这么定；改决策时更新条目，不追加叙事

## 约定

- 依赖坐标只在 `gradle/libs.versions.toml` 声明
- `README.md` 与 `README_ZH.md` 必须在同一个 commit 里改完
- 公共 API 改动要跑 `./gradlew apiDump`，`api/` 目录入库
- 平台绑定只对接 `native/shim`，不直接对接 `mquickjs.h`；Kotlin 侧永远不持有原生 `JSValue`（原因见 docs/architecture.md）

## 构建

- JDK 17+，Xcode，Android SDK（NDK 版本见 version catalog）
- `./gradlew :mquickjs-core:assemble`
