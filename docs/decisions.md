# 决策记录

只记「为什么这么定」，每条一段。改决策时更新对应条目，不追加叙事。

## 命名：产物 `mquickjs-kmp`，主模块 `library`

三层各管一层：仓库名与主产物同为 `mquickjs-kmp`；Gradle 主模块叫 `library`，扩展模块用自己的名字（将来 `serialization` ↔ `mquickjs-kmp-serialization`），与 kmp-webview 的 `library` / `scanner` 同一惯例，映射在 `gradle/composite-substitutions` 与 `mavenPublishing.coordinates` 显式声明；Kotlin 包名 `wang.harlon.mquickjs`，不带 `kmp`。

产物名里的 `kmp` 不是在描述平台列表（那是 Gradle 模块元数据的事），而是在说「这是 MQuickJS 的 Kotlin 移植，不是引擎本体」。0.1.0 最初以 `mquickjs-core` 发过一次，这个名字读起来像引擎自己的一个模块，随即改名重发；旧坐标无人使用，留作死坐标。`lz4-java`、`sqlite-jdbc`、`quickjs-kt` 都是「库名-绑定语言/平台」这一族。

## 平台范围：本期只做 Android + iOS

JVM 桌面与 Web 不在本期。去掉桌面 JVM 后不需要交叉编译与动态库解压加载，JNI 只有 Android 一个消费者。`macosArm64` 作为调试宿主加入 targets，既然已经在 targets 里，发布时一并发出比专门排除更省事。

## 版本基线：全部取最新稳定

Kotlin 2.4.20 / AGP 9.4.0 / Gradle 9.7.1（2026-09-09 核实）。AGP 9.4 要求 Gradle ≥ 9.6.0、JDK ≥ 17。NDK 锁定本机已装的 27.1.12297006 而非 AGP 默认的 28.2，避免首次构建额外下载。所有坐标只在 `gradle/libs.versions.toml` 声明。

## 上游锁定：git subtree + commit

上游没有 tag，锁 commit。用 subtree 而非 submodule，使用方 clone 后即完整，不需要额外初始化步骤；上游改动只进 `patches/`。`native/UPSTREAM` 是唯一真值，Gradle 读它生成 `BuildInfo`，运行时通过 `MQuickJs.upstreamCommit` 可查。

## 原生分发：使用方零配置

Android 用 AAR 内置 `.so`；Apple 用 cinterop 的 `staticLibraries` 把 `.a` 打进 klib，不发 CocoaPods / SPM。目标是使用方只加一行依赖坐标。

## 本期不引入的依赖

atomicfu、kotlinx-benchmark、Dokka 都到对应里程碑再加（见 roadmap.md），避免第一版就背一堆没用上的依赖。kotlinx-serialization 只进 `serialization` 模块，core 不依赖它。

## 类型化桥接：独立模块，走 JsonElement，宿主函数按参数个数重载

`serialization` 是独立模块而不是 core 的可选依赖：只做「脚本算完给结果」的使用方不该背上序列化运行时与编译器插件，core 的 `JsValue` 本身就是完整的跨界表示。模块只在 `JsValue` 上做扩展，不动 core 的类型：`JsValue.Json` 文本直接 `decodeFromString`，原始值先转 `JsonElement` 再 `decodeFromJsonElement`，编码统一经 `encodeToJsonElement` 后映射——原始类型落到 `Num` / `Str` / `Bool` / `Null` 而不是包成 JSON 文本，与 core「原始类型直传，对象走 JSON」的过桥约定一致，也省掉引擎侧一次 JSON 解析。整数值且落在 `Long` 范围内的 `Num` 转成 Long 形态的 `JsonPrimitive`，因为 `JsonPrimitive(3.0)` 的 content 是 `"3.0"`，kotlinx 解成 `Int` 会失败，而引擎的 `JSON.stringify(3)` 本来就是 `3`；超出 `Long` 范围的整数（如 2^63）与 NaN 保留 Double 形态，`-0.0` 与 `JSON.stringify(-0)` 一样写成 `0`。`undefined` 与 `null` 都解成 `JsonNull`：`undefined` 没有别的合理映射，且缺失的宿主函数参数正是 `undefined`。

类型化的 `registerFunction` 按 lambda 参数个数重载（一到三个），与 core 的 `registerFunction(name) { args -> }` 同名：lambda 参数带显式类型时 SAM 转换不适用、落到扩展，不带类型时成员优先、落到 core，两者互不干扰。不做零参重载：`{ Reply() }` 对 `JsHostFunction` 的 SAM 转换是适用的，成员会先被选中再报返回类型不匹配，错误信息误导。返回 `Unit` 特判为 `undefined`，否则 `UnitSerializer` 会编成 `{}`。

## API 守门：BCV 只覆盖 klib 目标

binary-compatibility-validator 0.18.2 识别不到 AGP `com.android.kotlin.multiplatform.library` 插件的 Android 编译，`apiDump` 只产出 `library.klib.api`（iOS 与 macOS）。Android 侧的 ABI 目前没有守门，公共 API 在 commonMain 单一来源，klib 的 dump 已能覆盖签名变化；等 BCV 支持该插件后再补 JVM dump。

## stdlib 定义：复制上游后修改，而非 patch

上游 `mqjs_stdlib.c` 的全局对象列表没有扩展钩子，唯一的条件编译是示例用的 `CONFIG_CLASS_EXAMPLE`。要加入 `kmp_host` trampoline 只能拥有一份自己的定义文件。选择复制到 `native/stdlib/kmp_stdlib.c` 而不是在 `patches/` 里打补丁，因为 stdlib 列表是声明式的、上游改动频率低、diff 一眼可读；补丁方案需要在构建期对 subtree 目录施加修改，与"上游目录禁止直接修改"冲突。

## 跨界传值：原始类型直传，对象走 JSON

`kmpjs_value` 只有 undefined / null / bool / number / string / object(JSON) / exception 七种。不做通用的 JS 对象句柄化是因为 M1 只需要"脚本算完给结果"的场景，JSON 让 JNI 与 cinterop 两套绑定都只处理字节数组，实现最短。对象句柄归 M2，届时以 `JsValue.Ref` 追加，不改现有类型。

## Android host test 用宿主 JNI 库，而非 JVM target

Android 侧的 Kotlin 桥接是纯 JNI、不碰 Android 框架 API，`System.loadLibrary` 在普通 JVM 上只要 `java.library.path` 里有宿主编译的 `libmquickjs_kmp.dylib` / `.so` 就能加载。因此 `buildNativeHostJni` 用同一份 CMake 在宿主上编一份带 JNI 的动态库，`testAndroidHostTest` 挂上它跑 commonTest，CI 不需要模拟器就覆盖了 JNI 胶水与 `NativeBridge` 回调。

没有为此加 `jvm()` target：那意味着要为五个桌面平台维护原生库、资源解压加载器，并且发布面多出一个不能再删的 variant。设备特有的差异（Bionic、ART 的局部引用上限、armeabi-v7a 的 32 位表）仍靠本地 `connectedAndroidDeviceTest` 兜底。

## ref 的所有权：显式 close，不做 Cleaner 兜底

`JsRef` 只有 `AutoCloseable`，没有 `Cleaner` / finalizer。Android minSdk 24 没有 `java.lang.ref.Cleaner`，Kotlin/Native 没有确定性的析构钩子，两端要各写一套还不保证及时；而泄漏的上界是引擎本身，`JsEngine.close()` 释放整块内存时所有 ref 一并消失。宿主函数收到的 ref 默认只在调用内有效，避免"每次回调都要记得 close"这种最容易漏的场景。

## 对象过桥方式由调用点选择，而非全局配置

`evaluate` / `registerFunction` / `JsRef.get` / `invoke` 都带 `objects: ObjectTransport` 参数，一律默认 JSON，包括已经身处 ref 世界的 `JsRef.get`：默认值不应产生需要关闭的句柄。JSON 零泄漏风险、适合"算完给结果"；REF 适合持有回调函数或大对象。放在调用点而不是引擎级配置，是因为同一个引擎里两种用法常常并存。

## JsRuntime 的互斥来自 Mutex，dispatcher 只决定在哪跑

引擎没有线程亲和（C 侧无线程局部状态，JNI 每次调用取当前 env，K/N 用 StableRef），需要的只是互斥。互斥由 `JsRuntime` 内部的 `Mutex` 保证，不依赖调用方传入的 dispatcher 是否单车道，`shutdown` 也在同一把锁下关闭引擎。默认 dispatcher 是 `Dispatchers.Default` 的单车道：求值是 CPU 工作；宿主函数会阻塞 I/O 时由调用方传自己的 dispatcher。common 里访问不到 `Dispatchers.IO`（在 common 源集是 internal），也是不选它做默认的原因之一。

## 字节码绑定 SDK 版本，不做内容校验

文件头只记 magic、字长与上游 commit。commit 而不是 SDK 版本号，因为字节码格式取决于引擎源码，同一引擎 commit 下的多个 SDK 版本可以互用。内容校验（验证字节码合法性）不做：引擎本身不做，我们在外面也做不到有意义的验证，只能靠「只加载本 SDK 编出来的」这条使用约定。编译能力放进 shim 而不只做命令行工具，是为了让设备端也能编译后缓存，以及让 C 测试与三端 commonTest 都能在进程内往返验证。

## 只发正式版，不发快照

发布只有 tag 触发的正式版；本地联调走消费方的 composite build（`gradle/composite-substitutions`），源码直接接入，不需要中间版本。快照仓库带来的「X-SNAPSHOT 排在 X 之前」「发完正式版还要 bump 快照号」这些约定纯属额外负担。`gradle.properties` 的 `VERSION_NAME=0.0.0-local` 只是本地占位，正式版本号由 `publish.yml` 从 tag 注入。
