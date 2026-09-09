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

尚未发布。发布到 Maven Central 后：

```kotlin
commonMain.dependencies {
    implementation("wang.harlon:mquickjs-core:latest.version")
}
```

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

- 原始类型以 `JsValue.Num` / `Str` / `Bool` / `Null` / `Undefined` 过桥，对象与数组以 `JsValue.Json` 过桥。
- 脚本抛异常、语法错误或内存耗尽都抛 `JsException`，带引擎的 message 与 `stack`。
- 宿主函数抛出的 Kotlin 异常在 JS 侧表现为带同样 message 的 `Error`。
- `engine.interrupt()` 可从任意线程调用，运行中的脚本以 `InternalError: interrupted` 终止。
- 引擎是单线程的，目前需要调用方自行串行化。

## 文档

- [docs/architecture.md](docs/architecture.md)：分层、压缩式 GC 逼出来的句柄表设计、宿主函数 trampoline
- [docs/native-build.md](docs/native-build.md)：上游引入方式、宿主工具、各目标构建
- [docs/js-subset.md](docs/js-subset.md)：stricter mode 子集禁止了什么、Kotlin 侧怎么处理
- [docs/decisions.md](docs/decisions.md)：命名与范围为什么这么定
- [docs/roadmap.md](docs/roadmap.md)：里程碑

## 上游

引擎以 `git subtree` 引入到 `native/mquickjs`，锁定在 `native/UPSTREAM` 记录的 commit。运行时可通过 `MQuickJs.upstreamCommit` 查到。

## 许可证

MIT。MQuickJS 本身为 MIT，版权归 Fabrice Bellard 与 Charlie Gordon。
