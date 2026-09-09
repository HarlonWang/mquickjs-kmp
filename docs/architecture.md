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

**句柄表取代 JSValue。** 暴露给 Kotlin 的对象存进句柄表，Kotlin 只拿到整数下标。上游提供两种 GC root 机制：`JS_PushGCRef` / `JS_PopGCRef` 是栈式的，只适合一次调用内的临时引用；`JS_AddGCRef` / `JS_DeleteGCRef` 是链表式、可任意顺序释放，代价是慢。句柄表用后者实现，每个句柄一个 `JSGCRef`，Kotlin 侧 `JsRef` 实现 `AutoCloseable`，并用 `Cleaner` 兜底。

**单一 trampoline 承接宿主函数。** 标准库由 `mquickjs_build.c` 在编译期生成到 ROM，C 函数按 stdlib 定义里的下标引用（`JS_NewCFunctionParams`），不存在运行时注册任意 C 函数的入口。因此 SDK 自带的 stdlib 只声明一个 `__kmpCall(id, args)`，C 侧按 id 分发到 Kotlin 注册的回调，JS 侧用引导脚本把它包装成普通函数。Kotlin/Native 的 `staticCFunction` 不能捕获状态，引擎实例通过 `JS_SetContextOpaque` 挂在上下文上，trampoline 从 opaque 指针取回 `StableRef`。

**值跨界只传原始类型与字符串。** 数字、布尔、字符串直接转；对象与数组经 JSON 字符串过桥。字符串统一以 UTF-8 字节数组过桥，不走 JNI 的 `NewStringUTF`：引擎内部是 WTF-8，JNI 的 modified UTF-8 处理不了未配对代理项。

## 运行时约束

- 上下文单线程。`JsEngine` 内部持有一个单线程 Dispatcher，所有求值方法提供 `suspend` 版本并在其上串行执行。
- 内存由调用方在创建引擎时一次性给出，引擎不再向系统申请。OOM 与 JS 异常统一经 `JS_GetException` 取 message 与 stack，映射为 `JsException`。
- 超时中断使用上游的 `JS_SetInterruptHandler`。
- 字节码不做校验、不保证跨版本兼容，只加载可信来源；字节码缓存 key 必须包含上游 commit 与目标字长。

## 公共 API 边界

core 只暴露四个类型：`JsEngine`、`JsValue`（sealed，原始类型直接持有）、`JsRef`（对象句柄）、`JsException`。类型化桥接（kotlinx.serialization）是独立模块，core 不依赖序列化库。公共 API 由 binary-compatibility-validator 守门，`api/` 目录下的 `.api` 文件入库。
