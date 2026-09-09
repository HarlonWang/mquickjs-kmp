# mquickjs-kmp

> Kotlin Multiplatform bindings for [MicroQuickJS](https://github.com/bellard/mquickjs), the JavaScript engine for embedded systems by Fabrice Bellard. Runs a JS program in as little as 10 kB of RAM, instantiates in microseconds, and never calls `malloc`.

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-brightgreen)](https://kotlinlang.org/docs/multiplatform.html)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

English | [中文](./README_ZH.md)

> ⚠️ Work in progress. The project is at the scaffolding stage: build setup, architecture docs and the upstream pin are in place; the native bridge is not implemented yet. See [docs/roadmap.md](docs/roadmap.md).

## Why

MQuickJS trades JavaScript coverage for footprint: an ES5-ish strict subset, a compacting GC, a ROM-resident standard library and a fixed memory buffer handed in by the host. That makes it a good fit for rule engines, dynamic configuration expressions and other small, high-frequency scripting on mobile, where QuickJS is heavier than needed. This SDK exposes it to Kotlin Multiplatform with a single Maven coordinate and no native build steps on the consumer side.

## Modules

| Module | Artifact | Status |
| --- | --- | --- |
| `mquickjs-core` | `wang.harlon:mquickjs-core` | Scaffolding |

Planned: `mquickjs-serialization` (typed bridging via kotlinx.serialization) and a Gradle plugin for build-time script checks.

## Platforms

| Target | Binding | Notes |
| --- | --- | --- |
| Android (`minSdk 24`) | JNI | `.so` bundled in the AAR |
| iOS (`iosArm64`, `iosSimulatorArm64`) | cinterop | static library bundled in the klib, no CocoaPods / SPM |
| macOS (`macosArm64`) | cinterop | debug host for `DEBUG_GC` / ASan, published as well |

JVM desktop and Web are out of scope for this phase.

## Install

Not published yet. Once on Maven Central:

```kotlin
commonMain.dependencies {
    implementation("wang.harlon:mquickjs-core:latest.version")
}
```

## Documentation

- [docs/architecture.md](docs/architecture.md): layering, the handle-table design forced by the compacting GC, the host-function trampoline
- [docs/native-build.md](docs/native-build.md): upstream vendoring, host tool, per-target builds
- [docs/js-subset.md](docs/js-subset.md): what the stricter-mode subset forbids and how to handle it on the Kotlin side
- [docs/decisions.md](docs/decisions.md): why things are named and scoped the way they are
- [docs/roadmap.md](docs/roadmap.md): milestones

## Upstream

The engine is vendored under `native/mquickjs` with `git subtree`, pinned to the commit recorded in `native/UPSTREAM`. `MQuickJs.upstreamCommit` exposes that commit at runtime.

## License

MIT. MQuickJS itself is MIT, copyright Fabrice Bellard and Charlie Gordon.
