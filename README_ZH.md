# mquickjs-kmp

> [MicroQuickJS](https://github.com/bellard/mquickjs) 的 Kotlin Multiplatform 绑定。MicroQuickJS 是 Fabrice Bellard 面向嵌入式系统的 JavaScript 引擎，10 kB RAM 即可运行 JS 程序，实例化只需微秒级，且从不调用 `malloc`。

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](./README.md) | 中文

> ⚠️ 开发中。原生桥接已在三端跑通（求值、宿主函数、异常、日志、中断），但 API 仍在变动、尚未发布。见 [docs/roadmap.md](docs/roadmap.md)。

## 为什么

MQuickJS 用 JavaScript 覆盖面换体积：接近 ES5 的严格子集、压缩式 GC、常驻 ROM 的标准库、由宿主一次性给定的固定内存缓冲区。这让它非常适合移动端的规则引擎、动态配置表达式这类小而高频的脚本场景，QuickJS 在这些场景下偏重。本 SDK 把它以一个 Maven 坐标暴露给 Kotlin Multiplatform，使用方不需要任何原生构建步骤。

## 模块

| 模块 | 坐标 | 状态 |
| --- | --- | --- |
| `mquickjs-core` | `wang.harlon:mquickjs-core` | M1：桥接可用，API 未稳定 |

规划中：`mquickjs-serialization`（基于 kotlinx.serialization 的类型化桥接）与构建期脚本检查的 Gradle 插件。

## 平台

| 目标 | 绑定 | 说明 |
| --- | --- | --- |
| Android（`minSdk 24`） | JNI | `.so` 内置于 AAR |
| iOS（`iosArm64`、`iosSimulatorArm64`） | cinterop | 静态库打进 klib，不需要 CocoaPods / SPM |
| macOS（`macosArm64`） | cinterop | `DEBUG_GC` / ASan 的调试宿主，同时随包发布 |

JVM 桌面与 Web 不在本期范围。

## 安装

尚未发正式版。每次推送到 `main` 都会把 `0.1.0-SNAPSHOT` 发布到 Maven Central 快照仓库：

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
commonMain.dependencies {
    implementation("wang.harlon:mquickjs-core:0.1.0-SNAPSHOT")
}
```

### 本地源码联调

遵循 `local.properties` composite build 约定的使用方（映射见 `gradle/composite-substitutions`）可以直接吃本仓源码而不走 Maven：

```properties
# 使用方 App 的 local.properties
mquickjs-kmp.dir=/path/to/mquickjs-kmp
```

此时使用方从源码构建本 SDK；使用方的 CI 仍解析 Maven 版本，发版后记得 bump。

## 用法

```kotlin
JsEngine(JsEngineConfig(memoryBytes = 128 * 1024, logger = ::println)).use { engine ->
    engine.registerFunction("discount") { args ->
        val amount = (args[0] as JsValue.Num).value
        JsValue.Num(if (amount > 100) amount * 0.9 else amount)
    }
    engine.evaluate("var total = discount(120);")
    engine.evaluate("total")                      // JsValue.Num(108.0)
    engine.evaluate("({ok: total > 100})")        // JsValue.Json("{\"ok\":true}")
    engine.evaluate("console.log('done', total)") // logger 收到 "done 108"
}
```

- 原始类型以 `JsValue.Num` / `Str` / `Bool` / `Null` / `Undefined` 过桥，对象与数组默认以 `JsValue.Json` 过桥。
- 脚本抛异常、语法错误或内存耗尽都抛 `JsException`，带引擎的 message 与 `stack`。
- 宿主函数抛出的 Kotlin 异常在 JS 侧表现为带同样 message 的 `Error`。
- `engine.interrupt()` 可从任意线程调用，运行中的脚本以 `InternalError: interrupted` 终止。
- 引擎是单线程的，用下面的 `JsRuntime` 或自行串行化。

### 持有 JS 对象：`JsRef`

指定 `ObjectTransport.REF`，对象以活句柄而非 JSON 返回。`JsRef` 可读写属性、按下标访问数组、带 `this` 与参数调用函数，用完必须 close：对象在此之前一直占着引擎的固定内存。

```kotlin
JsEngine().use { engine ->
    val rules = engine.evaluate("({limit: 3, check: function (n) { return n <= this.limit; }})", objects = ObjectTransport.REF) as JsRef
    rules.use { r ->
        r.set("limit", JsValue.Num(10))
        val check = r.get("check", ObjectTransport.REF) as JsRef
        check.use { it.invoke(thisArg = r, args = listOf(JsValue.Num(7))) } // JsValue.Bool(true)
    }
}
```

以 `ObjectTransport.REF` 注册的宿主函数收到的 ref 只在本次调用内有效，`retain()` 可留住。`engine.stats().liveRefs` 给出仍未关闭的 ref 数，SDK 自己的泄漏测试就靠它断言。

### 字节码预编译

`JsBytecode.compile` 把脚本编成引擎字节码，`JsEngine.loadBytecode` 免解析加载，且字节码常驻在引擎固定内存之外。顺序是：先加载（每个引擎只能加载一个程序，多个脚本请合并成一份），再注册宿主函数，再运行。

```kotlin
val bytes = JsBytecode.compile(source, "rules.js")   // 构建期做，或设备上做一次后缓存
JsEngine().use { engine ->
    val program = engine.loadBytecode(bytes)          // 必须在 evaluate / registerFunction 之前
    engine.registerFunction("report") { it[0] }
    program.use { it.run() }
}
```

字节码绑定产出它的 SDK 所内嵌的引擎 commit（`MQuickJs.upstreamCommit`）与字长（`JsBytecode.wordSize`：除 `armeabi-v7a` 外都是 64），不匹配会以明确的 `JsException` 拒绝。除此之外字节码内容不做校验，只加载本 SDK 编出来的。命令行工具 `kmpjsc`（`./gradlew :mquickjs-core:buildHostTools`）做同样的事。

### 协程：`JsRuntime`

`JsRuntime` 把对同一引擎的所有访问串行到单车道 dispatcher 上，并把协程取消与超时映射为引擎中断。

```kotlin
val runtime = JsRuntime()
try {
    try {
        runtime.evaluate("for (;;) {}", timeout = 200.milliseconds)
    } catch (e: TimeoutCancellationException) {
        // 脚本已被中断，引擎可继续用
    }
    runtime.withEngine { evaluate("1 + 1") } // 独占访问，ref 只能在这里面用
} finally {
    runtime.shutdown()
}
```

互斥来自内部的 Mutex，任何 dispatcher 都可以；默认是 `Dispatchers.Default` 的单车道。

## 文档

- [docs/architecture.md](docs/architecture.md)：分层、压缩式 GC 逼出来的句柄表设计、宿主函数 trampoline
- [docs/native-build.md](docs/native-build.md)：上游引入方式、宿主工具、各目标构建
- [docs/js-subset.md](docs/js-subset.md)：stricter mode 子集禁止了什么、Kotlin 侧怎么处理
- [docs/decisions.md](docs/decisions.md)：命名与范围为什么这么定
- [docs/roadmap.md](docs/roadmap.md)：里程碑

## 构建

- Gradle daemon 要求 JDK 25（`gradle/gradle-daemon-jvm.properties`，缺失时 Gradle 自动下载）、Xcode、装有 `gradle/libs.versions.toml` 所锁定 NDK 版本的 Android SDK、PATH 上的 `cmake`。
- `./gradlew :mquickjs-core:macosArm64Test` 是最快的完整检查；`testAndroidHostTest` 在宿主上经真实 JNI 桥接跑同一套用例；`connectedAndroidDeviceTest` 在设备或模拟器上跑。
- `./gradlew :mquickjs-core:nativeShimTest` 在 `DEBUG_GC`（每次分配都移动对象）加 AddressSanitizer 下跑 C 层 shim 测试。
- CI（`.github/workflows/build.yml`）对每个 PR 跑 shim 测试、macOS 测试、Android host 测试、iOS 编译、Android AAR 组装与 API 校验，并从 `main` 发布快照。

## 上游

引擎以 `git subtree` 引入到 `native/mquickjs`，锁定在 `native/UPSTREAM` 记录的 commit。运行时可通过 `MQuickJs.upstreamCommit` 查到。

## 许可证

MIT。MQuickJS 本身为 MIT，版权归 Fabrice Bellard 与 Charlie Gordon。
