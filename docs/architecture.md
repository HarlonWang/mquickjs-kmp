# 架构

MQuickJS 与 QuickJS 最大的内部差异是**压缩式追踪 GC**：任何一次 JS 分配都可能移动对象，`JSValue` 在 C 侧只能作为跨 API 调用之间的临时值存在。整个绑定层的设计都围绕这一条约束展开，Kotlin 侧永远不持有原生 `JSValue`。

## 分层

```
commonMain      JsEngine / JsValue / JsRef / JsException（expect 声明 + 纯 Kotlin 逻辑）
    │
    ├── native/shim     mquickjs_kmp.c/.h：句柄化 C API，Android 与 Apple 共用同一份
    │
    ├── androidMain     JNI（native/jni）→ shim
    └── iosMain / macosMain   cinterop → shim
```

平台绑定只对接 shim，不直接对接 `mquickjs.h`。理由：JNI 与 cinterop 只需各写一次同样的薄封装；GC 移动对象的复杂性被封在 C 层；shim 接口只用整数句柄与 UTF-8 字节，跨语言最省事。

## shim 的三条设计约束

**值跨界只传原始类型与字符串。** `kmpjs_value` 是唯一的跨界类型：undefined / null / bool / number 直接携带，字符串以 UTF-8 字节加长度传递，对象与数组在 C 侧调用 `JSON.stringify` 后以 JSON 文本传递（函数等不可序列化的对象 JSON 为空）。宿主函数的返回值反向走 `JS_Parse` 的 JSON 模式。字符串以字节数组过桥，Kotlin 侧用自带的 `Wtf8` 编解码：引擎内部是 WTF-8，JNI 的 modified UTF-8 与 Kotlin 标准 UTF-8 编解码都会把未配对代理项改写成替换字符。

**单一 trampoline 承接宿主函数。** 标准库由 `mquickjs_build.c` 在编译期生成到 ROM，C 函数只能按 stdlib 定义里的下标引用，不存在运行时注册任意 C 函数的入口。SDK 的 stdlib 在 `js_c_function_decl` 里只声明一个 `kmp_host`，`registerFunction` 用 `JS_NewCFunctionParams(ctx, kmp_host, id)` 造出带 id 闭包的函数对象挂到全局对象上，调用时 C 侧按 id 分发到 Kotlin。Kotlin/Native 的 `staticCFunction` 不能捕获状态，引擎实例通过 `JS_SetContextOpaque` 挂在上下文上，回调用 `StableRef` 取回。

**句柄表取代 JSValue（M2）。** 需要让 Kotlin 长期持有 JS 对象时，对象存进句柄表，Kotlin 只拿整数下标。上游提供两种 GC root：`JS_PushGCRef` / `JS_PopGCRef` 栈式，只适合一次调用内的临时引用；`JS_AddGCRef` / `JS_DeleteGCRef` 链表式、可任意顺序释放。句柄表用后者。

## 运行时约束

- 上下文单线程。`JsEngine` 不做同步，调用方在单线程使用或自行串行化；`interrupt()` 是唯一可跨线程调用的成员。协程 Dispatcher 封装归 M2。
- 内存由调用方在创建引擎时一次性给出，引擎不再向系统申请。OOM 与 JS 异常统一经 `JS_GetException` 取 message（`toString()` 结果）与 `stack`，映射为 `JsException`。
- 中断走上游的 `JS_SetInterruptHandler`，标志是 C11 `atomic_int`，脚本以 `InternalError: interrupted` 终止，引擎随后可继续使用；中断只作用于正在运行的求值，空闲时调用会被下一次求值清掉。
- 宿主回调（宿主函数、logger）里的 Kotlin 异常一律在回调内截住：Kotlin/Native 异常越过 C 边界会终止进程。宿主函数异常转成 JS `Error`，logger 异常吞掉。
- 引擎的解析器会读到输入末尾之后一个字节，shim 把所有源码与 JSON 拷贝成 NUL 结尾再交给引擎。
- 字节码不做校验、不保证跨版本兼容，只加载可信来源；字节码缓存 key 必须包含上游 commit 与目标字长。

## 公共 API 边界

core 只暴露 `JsEngine`、`JsEngineConfig`、`JsValue`（sealed）、`JsHostFunction`、`JsException`，M2 再加 `JsRef`（对象句柄）。类型化桥接（kotlinx.serialization）是独立模块，core 不依赖序列化库。公共 API 由 binary-compatibility-validator 守门，`api/` 目录下的 `.api` 文件入库。
