# mquickjs-kmp

> Kotlin Multiplatform bindings for [MicroQuickJS](https://github.com/bellard/mquickjs), the JavaScript engine for embedded systems by Fabrice Bellard. Runs a JS program in as little as 10 kB of RAM, instantiates in microseconds, and never calls `malloc`.

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [中文](./README_ZH.md)

> ⚠️ Work in progress. The native bridge runs on all three targets (evaluate, host functions, exceptions, logging, interrupt), but the API is still moving and nothing is published yet. See [docs/roadmap.md](docs/roadmap.md).

## Why

MQuickJS trades JavaScript coverage for footprint: an ES5-ish strict subset, a compacting GC, a ROM-resident standard library and a fixed memory buffer handed in by the host. That makes it a good fit for rule engines, dynamic configuration expressions and other small, high-frequency scripting on mobile, where QuickJS is heavier than needed. This SDK exposes it to Kotlin Multiplatform with a single Maven coordinate and no native build steps on the consumer side.

## Modules

| Module | Artifact | Status |
| --- | --- | --- |
| `mquickjs-core` | `wang.harlon:mquickjs-core` | M1: bridge working, API unstable |

Planned: `mquickjs-serialization` (typed bridging via kotlinx.serialization) and a Gradle plugin for build-time script checks.

## Platforms

| Target | Binding | Notes |
| --- | --- | --- |
| Android (`minSdk 24`) | JNI | `.so` bundled in the AAR |
| iOS (`iosArm64`, `iosSimulatorArm64`) | cinterop | static library bundled in the klib, no CocoaPods / SPM |
| macOS (`macosArm64`) | cinterop | debug host for `DEBUG_GC` / ASan, published as well |

JVM desktop and Web are out of scope for this phase.

## Install

Not released yet. Every push to `main` publishes `0.1.0-SNAPSHOT` to the Maven Central snapshot repository:

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}
commonMain.dependencies {
    implementation("wang.harlon:mquickjs-core:0.1.0-SNAPSHOT")
}
```

### Developing against a local checkout

Consumers that follow the `local.properties` composite-build convention (see `gradle/composite-substitutions`) can point at this repository instead of Maven:

```properties
# local.properties of the consuming app
mquickjs-kmp.dir=/path/to/mquickjs-kmp
```

The consumer then builds this SDK from source; CI on the consumer side still resolves the Maven version, so bump that after publishing.

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
val rules = engine.evaluate("({limit: 3, check: function (n) { return n <= this.limit; }})", objects = ObjectTransport.REF) as JsRef
rules.use { r ->
    r.set("limit", JsValue.Num(10))
    val check = r.get("check", ObjectTransport.REF) as JsRef
    check.use { it.invoke(thisArg = r, args = listOf(JsValue.Num(7))) } // JsValue.Bool(true)
}
```

Host functions registered with `ObjectTransport.REF` receive refs that live only for the duration of the call; `retain()` keeps one.

### Coroutines: `JsRuntime`

`JsRuntime` serializes every access to one engine on a single-lane dispatcher and maps cancellation and timeouts to engine interrupts.

```kotlin
val runtime = JsRuntime()
runtime.evaluate("for (;;) {}", timeout = 200.milliseconds) // TimeoutCancellationException, engine stays usable
runtime.withEngine { evaluate("1 + 1") }                     // exclusive access, refs usable inside
runtime.shutdown()
```

## Documentation

- [docs/architecture.md](docs/architecture.md): layering, the handle-table design forced by the compacting GC, the host-function trampoline
- [docs/native-build.md](docs/native-build.md): upstream vendoring, host tool, per-target builds
- [docs/js-subset.md](docs/js-subset.md): what the stricter-mode subset forbids and how to handle it on the Kotlin side
- [docs/decisions.md](docs/decisions.md): why things are named and scoped the way they are
- [docs/roadmap.md](docs/roadmap.md): milestones

## Building

- JDK 25 for the Gradle daemon (`gradle/gradle-daemon-jvm.properties`; Gradle downloads it when missing), Xcode, Android SDK with the NDK version pinned in `gradle/libs.versions.toml`, and `cmake` on `PATH`.
- `./gradlew :mquickjs-core:macosArm64Test` is the fastest full check; `testAndroidHostTest` runs the same suite through the real JNI bridge on the host; `connectedAndroidDeviceTest` runs it on a device or emulator.
- CI (`.github/workflows/build.yml`) runs the macOS tests, iOS compilation, Android AAR assembly and the API check on every PR, and publishes a snapshot from `main`.

## Upstream

The engine is vendored under `native/mquickjs` with `git subtree`, pinned to the commit recorded in `native/UPSTREAM`. `MQuickJs.upstreamCommit` exposes that commit at runtime.

## License

MIT. MQuickJS itself is MIT, copyright Fabrice Bellard and Charlie Gordon.
