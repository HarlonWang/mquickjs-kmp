# 路线图

每个里程碑可独立交付，后一步不返工前一步的原生层与 core API。

## M1 打通链路（已完成，除 CI）

- `native/shim` 基于 `kmpjs_value` 的 C API，`native/stdlib` 带 `kmp_host` trampoline 的 stdlib 定义
- `buildHostTool` → `generateStdlib*` → 三端 `CMakeBuild`，Android `.so` 经变体 API 注入 AAR，Apple `.a` 经 cinterop 打进 klib
- `JsEngine`：求值、`registerFunction`、异常映射（message + stack）、`console.log` 日志、`interrupt()`、内存上限
- commonTest 15 个用例在 macOS 与 Android 模拟器通过，iOS 两个目标编译通过
- GitHub Actions：PR / main 构建门禁（含宿主 JNI 库跑的 Android host test），tag 发正式版；不发快照，本地联调走 composite build

## M2 核心 API（已完成）

- `JsRef` 句柄表：`ObjectTransport.REF` 下对象以活句柄返回，读写属性、数组下标、带 this 调用函数、`toJson`；宿主函数收到的 ref 调用内有效，`retain` 留住；`AutoCloseable`，无 Cleaner 兜底（引擎 close 时整块内存一起释放）
- `JsRuntime`：单车道 dispatcher 串行化，`withEngine` 独占访问，协程取消与 `timeout` 映射为 `interrupt()`
- 字节码预编译在 M3 完成

## M3 质量与文档（0.1.0 已发布，benchmark 暂缓）

- DEBUG_GC + ASan 的 shim C 测试（`nativeShimTest`，已完成）
- 句柄泄漏测试：`JsEngine.stats()` 暴露存活句柄数，循环创建销毁、transient 参数、回传脚本、泄漏到 OOM 后恢复（已完成）
- 子集限制文档补充 Kotlin 侧示范（已完成）
- benchmark 模块对照 quickjs-wrapper（androidx.benchmark，设备上跑，结果作为文档提交）：暂缓，等有对比诉求再做
- 字节码预编译（已完成）：shim `kmpjs_compile` / `kmpjs_load_bytecode` / `kmpjs_run_program`，Kotlin `JsBytecode.compile` / `JsEngine.loadBytecode` / `JsProgram`，宿主工具 `kmpjsc`，文件头绑定上游 commit 与字长
- 0.1.0 发布（已完成）；1.0 等 benchmark 与更多使用反馈之后

## 之后

- `mquickjs-serialization`：kotlinx.serialization 类型化桥接
- Gradle 插件：构建期 stricter mode 语法检查、按平台预编译字节码
- 桌面 JVM、Web（Wasm 沙箱场景）
