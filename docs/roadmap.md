# 路线图

每个里程碑可独立交付，后一步不返工前一步的原生层与 core API。

## M1 打通链路（已完成，除 CI）

- `native/shim` 基于 `kmpjs_value` 的 C API，`native/stdlib` 带 `kmp_host` trampoline 的 stdlib 定义
- `buildHostTool` → `generateStdlib*` → 三端 `CMakeBuild`，Android `.so` 经变体 API 注入 AAR，Apple `.a` 经 cinterop 打进 klib
- `JsEngine`：求值、`registerFunction`、异常映射（message + stack）、`console.log` 日志、`interrupt()`、内存上限
- commonTest 15 个用例在 macOS 与 Android 模拟器通过，iOS 两个目标编译通过
- GitHub Actions：PR / main 构建门禁，main 发 snapshot，tag 发正式版；CI 上的 Android 模拟器设备测试待补

## M2 核心 API

- 句柄表与 `JsRef` 生命周期（`AutoCloseable` + Cleaner），支持持有 JS 对象与回调 JS 函数
- 协程串行化：引擎专属单线程 Dispatcher，`suspend` 求值与超时
- 脚本加载器与字节码预编译入口

## M3 质量与文档

- `macosArm64` 上的 DEBUG_GC + ASan 测试库
- 句柄泄漏测试：循环创建销毁，断言句柄表回到空闲状态
- 子集限制文档补充 Kotlin 侧示范
- benchmark 模块对照 QuickJS 绑定，实例化耗时与常驻内存写进 README
- 1.0 发布

## 之后

- `mquickjs-serialization`：kotlinx.serialization 类型化桥接
- Gradle 插件：构建期 stricter mode 语法检查、按平台预编译字节码
- 桌面 JVM、Web（Wasm 沙箱场景）
