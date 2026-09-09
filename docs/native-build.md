# 原生构建

## 目录

```
native/
├── UPSTREAM     上游 commit 锁定（唯一真值，Gradle 读取后注入 BuildInfo）
├── mquickjs/    bellard/mquickjs，git subtree，禁止直接修改（不叫 upstream：macOS 不区分大小写，会与 UPSTREAM 文件撞名）
├── patches/     对上游的补丁，构建时 apply
├── shim/        mquickjs_kmp.c/.h
├── stdlib/      SDK 自带 stdlib 定义（含 trampoline）
└── jni/         JNI 胶水，只服务 Android
```

## 上游同步

上游没有 tag，只能锁 commit。拉取与更新都走 subtree：

```sh
# 首次
git subtree add --prefix native/mquickjs https://github.com/bellard/mquickjs.git <commit> --squash
# 更新
git subtree pull --prefix native/mquickjs https://github.com/bellard/mquickjs.git <commit> --squash
```

更新后同步改 `native/UPSTREAM` 的 `commit=` 与 `date=`。需要改上游代码时一律写进 `patches/`，保证 `subtree pull` 永远能干净合入。

## stdlib 定义与宿主工具

`native/stdlib/kmp_stdlib.c` 是 SDK 自有的 stdlib 定义，从上游 `mqjs_stdlib.c` 派生：去掉 REPL 专用的 `gc` / `load` / `setTimeout` / `clearTimeout`，在 `js_c_function_decl` 里加入 `kmp_host` trampoline。上游没有给全局对象留扩展钩子，只能复制后修改；每次 `subtree pull` 后要 `diff` 上游的 `mqjs_stdlib.c` 并把变更手工同步过来。

`mquickjs_build.c` 与该定义一起在**宿主机**上编译成 `kmp_stdlib` 工具，运行它得到两个头文件：`kmp_stdlib.h`（ROM 表与 `js_stdlib` 定义，被 `shim/mquickjs_kmp.c` 包含）和 `mquickjs_atom.h`（引擎核心 `mquickjs.c` 编译时依赖，atom 编号必须与 stdlib 表一致）。这意味着**每个 stdlib 变体对应一份独立的引擎编译**，不能复用别处的 `mquickjs.o`。

生成物依赖目标字长：`-m32` 产出给 32 位目标用的表。Gradle 里 `buildHostTool` → `generateStdlib64` / `generateStdlib32` → 各目标 `buildNative*`，`armeabi-v7a` 用 32 位表，其余全部 64 位。

## 各目标

| 目标 | 编译 | 绑定 | 产物 |
|---|---|---|---|
| Android | CMake + NDK 工具链，`arm64-v8a` / `armeabi-v7a` / `x86_64` | JNI | AAR 内含 `.so` |
| iosArm64 / iosSimulatorArm64 | Xcode 工具链编出 `.a` | cinterop `.def`，`staticLibraries` 打进 klib | 使用方无需 CocoaPods / SPM |
| macosArm64 | 同上 | 同上 | 调试宿主，随包发布 |

三端都由 `native/CMakeLists.txt` 统一描述，Gradle 的 `CMakeBuild` 任务按目标传不同的 CMake 参数。AGP 9 的 KMP 库插件没有 `externalNativeBuild` DSL，Android 的 `.so` 由 `collectJniLibs` 汇总后经变体 API `sources.jniLibs.addGeneratedSourceDirectory` 注入 AAR。Apple 侧 cinterop 的 `.def` 用 `staticLibraries` 把 `.a` 打进 klib，`-libraryPath` 按目标传入。

NDK 版本固定在 version catalog 的 `android-ndk`，不用 AGP 默认值，避免 CI 与本机各自下载不同版本。`cmake` 取 PATH 上的（brew 或 Android SDK 自带的均可）。

Android host test 走宿主编译的 JNI 库：`buildNativeHostJni` 以 `-DMQJS_HOST_JNI=ON` 在本机编出 `libmquickjs_kmp.dylib`（Linux 为 `.so`），`testAndroidHostTest` 通过 `java.library.path` 加载。JDK 头文件按 `JAVA_HOME` → daemon 的 `java.home` → `/Library/Java/JavaVirtualMachines/*` 顺序找第一个带 `include/jni.h` 的（Android Studio 的 JBR 没有头文件）。设备测试仍走 `connectedAndroidDeviceTest`。

## 调试宿主：DEBUG_GC + ASan 的 shim 测试

`native/test/shim_test.c` 是直接对 C API 的断言测试，由 `buildNativeShimTest` 以 `-DMQJS_SHIM_TEST=ON -DMQJS_DEBUG_GC=ON -DMQJS_ASAN=ON -DCMAKE_BUILD_TYPE=Debug` 在宿主上编译，`nativeShimTest` 运行并挂在 `check` 下、CI 门禁里。上游的 `DEBUG_GC` 让每次分配都触发 GC 并挪动对象（靠缩小一个 dummy block 改变地址，有限次后会打印 warning 停止挪动），shim 里任何拿着过期 `JSValue` 的地方第一次分配就会炸；ASan 抓越界与悬垂。Kotlin 层不持有 `JSValue`，所以 DEBUG_GC 只需覆盖 C 层，不为它单独编 K/N 测试库。`DEBUG_GC` 依赖 `assert`，构建类型必须是 Debug（Release 会定义 `NDEBUG`）。

## 字节码

编译器就是 shim 的 `kmpjs_compile`：宿主工具 `kmpjsc`（`native/tools`，`./gradlew :core:buildHostTools` 编出 `build/native/host-tools/bin/kmpjsc`）和 Kotlin 的 `JsBytecode.compile` 都只是它的包装。输出绑定 `native/UPSTREAM` 的 commit（CMake 读进 `KMPJS_UPSTREAM_COMMIT` 编译期常量）与字长：iOS 真机、Apple Silicon 模拟器、Android arm64 / x86_64 都是 64 位可共用，`armeabi-v7a` 用 `kmpjsc -m32` 另出一份，运行时按 `JsBytecode.wordSize` 选文件。不用上游的 `mqjs`：它链接的是上游 stdlib，且不会写我们的文件头。

使用方在构建期编译的最小做法：

```kotlin
// 使用方工程的 build.gradle.kts；kmpjsc 来自本仓 buildHostTools 的产物
tasks.register<Exec>("compileRules") {
    commandLine("/path/to/kmpjsc", "-o", "src/main/assets/rules.64.bin", "src/main/js/rules.js")
}
```

更新 `native/UPSTREAM` 后所有字节码都要重编，加载会以「built for engine …」拒绝旧文件。
