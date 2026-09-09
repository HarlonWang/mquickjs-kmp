# mquickjs-kmp

> Kotlin Multiplatform bindings for [MicroQuickJS](https://github.com/bellard/mquickjs), the JavaScript engine for embedded systems by Fabrice Bellard. Runs a JS program in as little as 10 kB of RAM, instantiates in microseconds, and never calls `malloc`.

[![Maven Central](https://img.shields.io/maven-central/v/wang.harlon/mquickjs-kmp?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/wang.harlon/mquickjs-kmp)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [中文](./README_ZH.md)

> Published on Maven Central. The API is still settling; see [docs/roadmap.md](docs/roadmap.md) for what is done and what is next.

## Why

MQuickJS trades JavaScript coverage for footprint: an ES5-ish strict subset, a compacting GC, a ROM-resident standard library and a fixed memory buffer handed in by the host. That makes it a good fit for rule engines, dynamic configuration expressions and other small, high-frequency scripting on mobile, where QuickJS is heavier than needed. This SDK exposes it to Kotlin Multiplatform with a single Maven coordinate and no native build steps on the consumer side.

## Platforms

| Target | Binding | Notes |
| --- | --- | --- |
| Android (`minSdk 24`) | JNI | `.so` bundled in the AAR |
| iOS (`iosArm64`, `iosSimulatorArm64`) | cinterop | static library bundled in the klib, no CocoaPods / SPM |
| macOS (`macosArm64`) | cinterop | debug host for `DEBUG_GC` / ASan, published as well |

JVM desktop and Web are out of scope for this phase.

## Install

```kotlin
commonMain.dependencies {
    implementation("wang.harlon:mquickjs-kmp:latest.version")
}
```

## Usage

```kotlin
JsEngine(JsEngineConfig(memoryBytes = 128 * 1024, logger = ::println)).use { engine ->
    engine.registerFunction("discount") { args ->
        val amount = (args[0] as JsValue.Num).value
        JsValue.Num(if (amount > 100) amount * 0.9 else amount)
    }
    engine.evaluate("var total = discount(120);")
    engine.evaluate("total")                      // JsValue.Num(108.0)
    engine.evaluate("({ok: total > 100})")        // JsValue.Json("{\"ok\":true}")
    engine.evaluate("console.log('done', total)") // logger receives "done 108"
}
```

- Primitives cross the boundary as `JsValue.Num` / `Str` / `Bool` / `Null` / `Undefined`; objects and arrays as `JsValue.Json` by default.
- A script that throws, fails to parse, or exhausts its memory raises `JsException` with the engine's message and `stack`.
- Throwing from a host function surfaces in JS as an `Error` with the Kotlin message.
- `engine.interrupt()` may be called from any thread and stops the running script with `InternalError: interrupted`.
- The engine is single-threaded; use `JsRuntime` (below) or serialize access yourself.

### Holding JS objects: `JsRef`

Ask for `ObjectTransport.REF` and objects come back as live handles instead of JSON. A `JsRef` reads and writes properties, indexes arrays, calls functions with a `this` and arguments, and must be closed: the object stays in the engine's fixed memory until then.

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

Host functions registered with `ObjectTransport.REF` receive refs that live only for the duration of the call; `retain()` keeps one. `engine.stats().liveRefs` tells you how many refs are still open, which is how the SDK's own tests prove nothing leaks.

### Precompiled bytecode

`JsBytecode.compile` turns a script into engine bytecode; `JsEngine.loadBytecode` loads it without parsing and keeps it outside the engine's fixed memory. Load it before anything else runs on the engine (one program per engine, so bundle your scripts into one), register host functions, then run.

```kotlin
val bytes = JsBytecode.compile(source, "rules.js")   // do this at build time or once on device, then cache
JsEngine().use { engine ->
    val program = engine.loadBytecode(bytes)          // must come before evaluate / registerFunction
    engine.registerFunction("report") { it[0] }
    program.use { it.run() }
}
```

Bytecode is bound to the engine commit of the SDK that produced it (`MQuickJs.upstreamCommit`) and to a word size (`JsBytecode.wordSize`: 64 everywhere except `armeabi-v7a`); mismatches are rejected with a clear `JsException`. Nothing else about the bytes is validated, so only load what this SDK compiled. The host tool `kmpjsc` (`./gradlew :library:buildHostTools`) does the same from the command line.

### Coroutines: `JsRuntime`

`JsRuntime` serializes every access to one engine on a single-lane dispatcher and maps cancellation and timeouts to engine interrupts.

```kotlin
val runtime = JsRuntime()
try {
    try {
        runtime.evaluate("for (;;) {}", timeout = 200.milliseconds)
    } catch (e: TimeoutCancellationException) {
        // the script was interrupted; the engine stays usable
    }
    runtime.withEngine { evaluate("1 + 1") } // exclusive access, refs usable inside
} finally {
    runtime.shutdown()
}
```

Exclusion comes from an internal mutex, so any dispatcher works; the default is a single lane of `Dispatchers.Default`.

## Documentation

- [docs/architecture.md](docs/architecture.md): layering, the handle-table design forced by the compacting GC, the host-function trampoline
- [docs/native-build.md](docs/native-build.md): upstream vendoring, host tool, per-target builds
- [docs/js-subset.md](docs/js-subset.md): what the stricter-mode subset forbids and how to handle it on the Kotlin side
- [docs/decisions.md](docs/decisions.md): why things are named and scoped the way they are
- [docs/roadmap.md](docs/roadmap.md): milestones

## Building

- JDK 25 for the Gradle daemon (`gradle/gradle-daemon-jvm.properties`; Gradle downloads it when missing), Xcode, Android SDK with the NDK version pinned in `gradle/libs.versions.toml`, and `cmake` on `PATH`.
- `./gradlew :library:macosArm64Test` is the fastest full check; `testAndroidHostTest` runs the same suite through the real JNI bridge on the host; `connectedAndroidDeviceTest` runs it on a device or emulator.
- `./gradlew :library:nativeShimTest` runs the C-level shim tests under `DEBUG_GC` (every allocation moves objects) and AddressSanitizer.
- CI (`.github/workflows/build.yml`) runs the shim tests, macOS tests, Android host tests, iOS compilation, Android AAR assembly and the API check on every PR and push to `main`; `publish.yml` releases to Maven Central when a version tag is pushed.

## Upstream

The engine is vendored under `native/mquickjs` with `git subtree`, pinned to the commit recorded in `native/UPSTREAM`. `MQuickJs.upstreamCommit` exposes that commit at runtime.

## License

MIT. MQuickJS itself is MIT, copyright Fabrice Bellard and Charlie Gordon.
