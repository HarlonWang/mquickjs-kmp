# mquickjs-kmp

MicroQuickJS 的 KMP 绑定 SDK。开始工作前先读 README.md，再按需读 docs/。

## 结构

- `library/`：核心模块，artifactId `mquickjs-kmp`（主模块叫 library，扩展模块用自己的名字并以 `mquickjs-kmp-` 为产物前缀，同 kmp-webview），包名 `wang.harlon.mquickjs`
- `serialization/`：kotlinx.serialization 类型化桥接，artifactId `mquickjs-kmp-serialization`，包名 `wang.harlon.mquickjs.serialization`，纯 Kotlin、只依赖 library 的公共 API
- `native/mquickjs/`：上游 git subtree，**禁止直接修改**，改动进 `native/patches/`
- `native/stdlib/kmp_stdlib.c`：从上游 `mqjs_stdlib.c` 复制修改而来，subtree pull 后要 diff 同步
- `native/shim/`：唯一的 C API 层，JNI 与 cinterop 都只对接它
- `native/UPSTREAM`：上游 commit 唯一真值，Gradle 读它生成 `BuildInfo.kt`
- `docs/decisions.md`：为什么这么定；改决策时更新条目，不追加叙事

## 约定

- 依赖坐标只在 `gradle/libs.versions.toml` 声明
- `README.md` 与 `README_ZH.md` 必须在同一个 commit 里改完
- 公共 API 改动要跑 `./gradlew apiDump`，各模块 `api/` 目录入库
- 平台绑定只对接 `native/shim`，不直接对接 `mquickjs.h`；Kotlin 侧永远不持有原生 `JSValue`（原因见 docs/architecture.md）

## 构建与测试

- Gradle daemon 用 JDK 25（`gradle/gradle-daemon-jvm.properties`，缺失自动下载）、Xcode、Android SDK（NDK 版本见 version catalog）、PATH 上有 `cmake`、`local.properties` 里 `sdk.dir`
- `gradle/composite-substitutions` 是消费方 composite build 的契约（坐标 → 项目路径），改模块名必须同步
- CI：`.github/workflows/build.yml`（PR 与 main：shim C 测试、macOS 测试、Android host 测试、iOS 编译、Android AAR、apiCheck），`publish.yml`（推 `x.y.z` tag 触发正式发布，版本号从 tag 注入）；不发快照，本地联调靠 `gradle/composite-substitutions`；secrets 命名与 kmp-webview 相同
- 原生链路：`buildHostTool` → `generateStdlib64/32` → `buildNative*`（CMake）→ Android `collectJniLibs` / Apple cinterop，全部由 Gradle 驱动，详见 docs/native-build.md
- macOS 单测（最快的反馈）：`./gradlew macosArm64Test`（两个模块），或 `:library:macosArm64Test`
- Android host test（真实 JNI 路径，不需要模拟器）：`./gradlew testAndroidHostTest`；serialization 模块经 `hostJni` configuration 复用 library 编出的宿主 JNI 库
- shim 的 C 测试（DEBUG_GC + ASan）：`./gradlew :library:nativeShimTest`，改 shim 必跑
- Android 设备测试（需模拟器在线）：`./gradlew :library:connectedAndroidDeviceTest`
- iOS 只编译：`./gradlew :library:compileKotlinIosArm64 :library:compileKotlinIosSimulatorArm64`
- 改 shim 时新增的行为要在 `native/test/shim_test.c` 里加 CHECK
