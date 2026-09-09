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

## 宿主工具

`mquickjs_build.c` 在**宿主机**上运行，把 stdlib 定义编译成可放 ROM 的 C 结构（`*_stdlib.h`）。所有目标平台构建都依赖它的产物，Gradle 里拆成 `buildHostTool` 与各目标构建任务，前者是后者的前置依赖。

## 各目标

| 目标 | 编译 | 绑定 | 产物 |
|---|---|---|---|
| Android | CMake（AGP `externalNativeBuild`），`arm64-v8a` / `armeabi-v7a` / `x86_64` | JNI | AAR 内含 `.so` |
| iosArm64 / iosSimulatorArm64 | Xcode 工具链编出 `.a` | cinterop `.def`，`staticLibraries` 打进 klib | 使用方无需 CocoaPods / SPM |
| macosArm64 | 同上 | 同上 | 调试宿主，随包发布 |

NDK 版本固定在 version catalog 的 `android-ndk`，不用 AGP 默认值，避免 CI 与本机各自下载不同版本。

## 调试宿主

`macosArm64` 是 DEBUG_GC 与 ASan 的运行宿主：上游的 `DEBUG_GC` 开关让每次分配都移动对象，桥接层的野指针会立即暴露。调试版原生库只链接到测试，不进发布产物。

## 字节码

`mqjs -o` 产出的字节码依赖目标 CPU 字长与字节序。iOS 真机、Apple Silicon 模拟器、Android arm64 都是 64 位可共用；`armeabi-v7a` 需用 `-m32` 另出一份。第一版直接分发 JS 源码在运行时编译，字节码预编译放到后续里程碑。
