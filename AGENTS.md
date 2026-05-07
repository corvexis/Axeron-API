# AGENTS.md — Axeron-API

## Build & Toolchain

- **Gradle 8.13** with Kotlin DSL (`*.gradle.kts`). Run via `./gradlew`.
- **JDK 21** required (`jitpack.yml`, `build.gradle.kts`).
- **Compile SDK 36**, Min SDK 26, Target SDK 36. Kotlin 2.0.21, AGP 8.13.2.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — no per-module repos allowed. All repos are in `settings.gradle.kts`.
- No test framework configured. No CI workflows. Published via **JitPack** only.

## Developer Commands

```bash
./gradlew build                          # Build all modules
./gradlew :<module>:assembleRelease      # Build a single module's release APK/AAR
./gradlew :<module>:publishToMavenLocal  # Publish a published module locally
./gradlew clean                          # Clean all build outputs
```

## Architecture

Android library framework providing root/shell access and plugin management via Binder-based IPC. Client-server model with multiple execution modes (root, shell, user).

### Modules (10 total, see `settings.gradle.kts`)

| Module | Type | Published | Purpose |
|--------|------|-----------|---------|
| `api` | library | yes | Main public API (`Axeron.java` entry point) |
| `aidl` | library | yes | AIDL interfaces + parcelables for IPC |
| `shared` | library | yes | Constants, path helpers, version build config |
| `provider` | library | yes | ContentProvider for binder sharing between processes |
| `axerish` | library | yes | Asset manager — extracts scripts/DEX from assets to `/data/data/<pkg>/files/bin/` |
| `server-shared` | library | no | Server-side service logic, permissions, plugins |
| `rish` | library | no | Terminal emulator with native PTY (C++ via CMake/prefab) |
| `shell` | application | no | Runs as shell (UID 2000), loads server Shell class dynamically |
| `runtime` | application | no | Runs as root, provides interactive shell via `RemoteProcess` |
| `demo-axerish` | application | no | Demo app using Jetpack Compose |

### Key Dependency Directions

```
api → aidl, shared
provider → api, aidl
server-shared → api, aidl, shared, rish
shell → compileOnly server-shared
runtime → aidl, shared, compileOnly server-shared
rish → aidl
axerish → (none)
demo-axerish → axerish
```

`server-shared` and `aidl` share the same Java package (`frb.axeron.server`).

## Critical Quirks

- **AIDL is source of truth for IPC** — `aidl/src/main/aidl/` contains `.aidl` files that generate Java stubs. Edit `.aidl` files, not generated code.
- **DEX extraction during build** — `shell` and `runtime` modules copy their `classes.dex` into `axerish/src/main/assets/scripts/` as `shell_axerish.dex` and `shell_axruntime.dex` respectively. Building shell/runtime modifies axerish assets.
- **Publishing gating** — Only modules with `extra["publishLibrary"] = true` publish. Currently: `api`, `aidl`, `shared`, `provider`, `axerish`. Controlled per-module in their `build.gradle.kts`.
- **Group ID convention** — Direct children of root use `dev.frb.axeron`. Nested use `dev.frb.axeron.<parent>`. Version = `api_version_code` from `gradle.properties`/`manifest.gradle.kts`.
- **Version name** includes git commit count: `{major}.{minor}.{patch}.r{gitRevCount}`. Computed in `build.gradle.kts` from `git rev-list --count HEAD`.
- **Hidden APIs** — Uses Rikka's hidden API compatibility (`dev.rikka.hidden.compat`). `shell` and `runtime` use `rikka.tools.refine` plugin.
- **No README exists** — this file is the primary instruction source.

## Resource Management (Critical)

- **ProcessPoolManager** (`server-shared/.../api/ProcessPoolManager.java`) — Bounded semaphore pool limits concurrent processes. Default max=20, adapts to 10 or 5 under memory pressure. Every `newProcess()` call acquires a slot; `RemoteProcessHolder` releases on `destroy()`.
- **PipeTransferPool** (`server-shared/.../util/PipeTransferPool.java`) — Shared `ThreadPoolExecutor` (core=4, max=32) replaces per-pipe `TransferThread` spawning. All `ParcelFileDescriptorUtil.pipeFrom/pipeTo` calls use this pool.
- **MemoryUtils** (`server-shared/.../util/MemoryUtils.java`) — Heap pressure detection (`LOW`/`MEDIUM`/`HIGH`). Used by `ProcessPoolManager.applyMemoryAdaptation()` at server startup.
- **AxeronNewProcess.clearCache()** — Called automatically on binder death. Manually call if you need to force-clear all cached process references.
- **Axeron health check** — Periodic binder ping every 30s. If binder dies, automatically triggers cleanup and fires `OnBinderDeadListener`.

## Package Naming

Most modules follow `frb.axeron.<scope>`, but exceptions:
- `rish` → `rikka.rish` (forked from upstream Rikka project)
- `demo-axerish` → `dev.frb.demo_axerish`
- `shell`, `runtime`, `axerish` → `frb.axeron` (flat, no sub-scope)
