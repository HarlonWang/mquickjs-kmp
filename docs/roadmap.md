# 路线图

每个里程碑可独立交付，后一步不返工前一步的原生层与 core API。

## M1 打通链路

- `native/shim` 句柄化 C API，`native/stdlib` 带 trampoline 的 stdlib 定义
- 宿主工具任务 `buildHostTool`，Android CMake 接入 AGP，iOS / macOS 编出 `.a` 并经 cinterop 打进 klib
- commonTest 跑通 `eval("1+2")` 与一个宿主函数回调，三端通过
- CI 出 snapshot

## M2 核心 API

- `JsEngine` builder：内存大小、宿主函数表、脚本加载器
- 句柄表与 `JsRef` 生命周期（`AutoCloseable` + Cleaner）
- 异常映射、`JS_SetInterruptHandler` 超时中断
- 协程串行化：引擎专属单线程 Dispatcher，`suspend` 求值

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
