# Fix: High RAM Consumption & Crash on Command Spam

## Problem Summary

1. **120+ MB RAM at startup** — AxeraConfigManager scans all installed packages at init; heavy object creation
2. **Crashes when spamming commands (e.g., echo)** — Unbounded process creation, FD leaks in `getErrorStream()`, thread explosion from per-pipe TransferThread
3. **Persistent crashes after reopen** — Stale binder references in `AxeronNewProcess.CACHE`, uncleared state on binder death

## Root Causes

| Issue | Location | Cause |
|-------|----------|-------|
| Unbounded process spawning | `AxeronService.kt:newProcess()` | No concurrency limit — every `echo` creates a new Process + 3 PFDs |
| FD leak | `RemoteProcessHolder.java:69-75` | `getErrorStream()` creates new pipe every call, never cached or closed |
| Thread explosion | `ParcelFileDescriptorUtil.java:18,29` | Each pipe spawns `new TransferThread().start()` — no thread pool |
| Stale cache on crash | `AxeronNewProcess.java:32` | `CACHE` retains dead processes; no `clearCache()` API |
| Missing binder death cleanup | `Axeron.java:343-346` | `DEATH_RECIPIENT` doesn't clear `AxeronNewProcess.CACHE` |
| Heavy startup | `AxeronConfigManager.java:103-127` | Scans ALL installed packages with `PackageManager.GET_PERMISSIONS` on init |
| No memory awareness | Everywhere | No runtime memory pressure detection to throttle behavior |

## Implementation Plan

### Step 1: Create `ProcessPoolManager.java` (both repos)

**Path:**
- `/home/corvexis/Axeron-API/server-shared/src/main/java/frb/axeron/server/api/ProcessPoolManager.java`
- `/home/corvexis/Axora/api/server-shared/src/main/java/frb/axeron/server/api/ProcessPoolManager.java`

**Package:** `frb.axeron.server.api`

**Purpose:** Bounded semaphore-based pool limiting concurrent processes (default: 20). Tracks active processes with timestamps for timeout cleanup. Supports adaptive sizing based on memory pressure.

**Key methods:**
- `getInstance()` / `reset()` — singleton lifecycle
- `acquireProcess(label, timeoutMs)` — returns false if pool exhausted (blocks with timeout)
- `registerProcess(process)` — start timeout tracking
- `releaseProcess(process)` — free slot, stop tracking
- `getAvailableSlots()` — for logging/monitoring
- `applyMemoryAdaptation()` — reduce maxConcurrent based on heap pressure
- `shutdown()` / `cleanupTimedOut()` — lifecycle management

### Step 2: Create `PipeTransferPool.java` (both repos)

**Path:**
- `/home/corvexis/Axeron-API/server-shared/src/main/java/frb/axeron/server/util/PipeTransferPool.java`
- `/home/corvexis/Axora/api/server-shared/src/main/java/frb/axeron/server/util/PipeTransferPool.java`

**Package:** `frb.axeron.server.util`

**Purpose:** Shared `ThreadPoolExecutor` (core=4, max=32, queue=128) for all pipe transfer threads instead of `new Thread().start()`.

### Step 3: Create `MemoryUtils.java` (both repos)

**Path:**
- `/home/corvexis/Axeron-API/server-shared/src/main/java/frb/axeron/server/util/MemoryUtils.java`
- `/home/corvexis/Axora/api/server-shared/src/main/java/frb/axeron/server/util/MemoryUtils.java`

**Package:** `frb.axeron.server.util`

**Purpose:** Memory pressure detection utilities.

**Key methods:**
- `getMemoryPressureLevel()` → `LOW` / `MEDIUM` / `HIGH` based on available heap
- `logMemoryState(tag)` — debug logging
- Thresholds: HIGH < 32MB available, MEDIUM < 64MB

### Step 4: Modify `RemoteProcessHolder.java` (Axora server)

**File:** `/home/corvexis/Axora/api/server-shared/src/main/java/frb/axeron/server/api/RemoteProcessHolder.java`

**Changes:**
1. Add `private ParcelFileDescriptor err;` field to cache error stream PFD
2. Constructor: add `ProcessPoolManager.getInstance().acquireProcess("...")` and `.registerProcess(process)` calls
3. `getErrorStream()`: cache the PFD in `err` field instead of creating new pipe each call
4. `destroy()`: close `in`, `out`, `err` PFDs; call `ProcessPoolManager.getInstance().releaseProcess(process)`
5. New `isDestroyed` flag to make `destroy()` idempotent

### Step 5: Modify `ParcelFileDescriptorUtil.java` (both repos)

**Files:**
- `/home/corvexis/Axeron-API/server-shared/src/main/java/frb/axeron/server/util/ParcelFileDescriptorUtil.java`
- `/home/corvexis/Axora/api/server-shared/src/main/java/frb/axeron/server/util/ParcelFileDescriptorUtil.java`

**Changes:**
1. Replace `new TransferThread(...).start()` with `PipeTransferPool.submit(new TransferRunnable(...))`
2. Keep `TransferThread` class but rename inner class to `TransferRunnable` implements `Runnable` (no Thread subclassing, pool manages threads)

### Step 6: Modify `AxeronService.kt` (Axora server)

**File:** `/home/corvexis/Axora/server/src/main/java/frb/axeron/server/AxeronService.kt`

**Changes to `newProcess()` (line ~503):**
```kotlin
override fun newProcess(...): IRemoteProcess {
    enforceCallingPermission("newProcess")
    val cmdStr = cmd?.contentToString() ?: "null"
    val pool = ProcessPoolManager.getInstance()
    if (!pool.acquireProcess(cmdStr)) {
        throw IllegalStateException("Too many concurrent processes (max=${pool.maxConcurrent})")
    }
    val process = Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
    pool.registerProcess(process)
    // ... rest unchanged, RemoteProcessHolder will release on destroy()
}
```

**Changes to `init` block (after `acquire()`):**
```kotlin
ProcessPoolManager.getInstance().applyMemoryAdaptation()
MemoryUtils.logMemoryState("AxeronService")
```

### Step 7: Modify `AxeronConfigManager.java` (Axora server)

**File:** `/home/corvexis/Axora/server/src/main/java/frb/axeron/server/AxeronConfigManager.java`

**Changes to constructor (line ~55):**
- Remove the expensive package scanning loop (lines 103-127) from constructor
- Move it to a deferred lazy initializer triggered on first `find(uid)` call
- Add `@Volatile private boolean initialized = false` flag
- Add `ensureInitialized()` method with double-checked locking:
```java
private void ensureInitialized() {
    if (!initialized) {
        synchronized (this) {
            if (!initialized) {
                scanInstalledPackages(); // extracted from constructor
                initialized = true;
            }
        }
    }
}
```
- Call `ensureInitialized()` in `find(uid)`, `findOrUpdate(uid)`, `update()`, `remove()`

### Step 8: Modify `AxeronNewProcess.java` (Axeron-API)

**File:** `/home/corvexis/Axeron-API/api/src/main/java/frb/axeron/api/AxeronNewProcess.java`

**Changes:**
1. Add public static `clearCache()` method:
```java
public static void clearCache() {
    synchronized (CACHE) {
        for (AxeronNewProcess proc : CACHE) {
            try { proc.remote.destroy(); } catch (Exception ignored) {}
        }
        CACHE.clear();
    }
}
```

### Step 9: Modify `Axeron.java` (Axeron-API)

**File:** `/home/corvexis/Axeron-API/api/src/main/java/frb/axeron/api/Axeron.java`

**Changes:**
1. In `DEATH_RECIPIENT` (line ~343): add `AxeronNewProcess.clearCache()` before existing logic
2. In `onBinderReceived(null, null)` block: add `AxeronNewProcess.clearCache()` after `scheduleBinderDeadListeners()`
3. Add `scheduleHealthCheck()` method — posts a delayed ping every 30s when binder is alive
4. In `onBinderReceived()` (non-null branch): call `scheduleHealthCheck()` to restart monitoring
5. Add `private static volatile Handler healthCheckHandler` and `private static Runnable healthCheckRunnable`

### Step 10: Update `AGENTS.md`

Add new entries to the Critical Quirks section documenting:
- ProcessPoolManager limits and configuration
- PipeTransferPool replaces individual TransferThreads
- MemoryUtils for adaptive behavior
- Lazy config loading behavior
- ClearCache() requirement on binder death

## Files Changed Summary

| File | Repo | Action |
|------|------|--------|
| `server-shared/.../api/ProcessPoolManager.java` | Both | Create |
| `server-shared/.../util/PipeTransferPool.java` | Both | Create |
| `server-shared/.../util/MemoryUtils.java` | Both | Create |
| `server-shared/.../api/RemoteProcessHolder.java` | Axora | Modify |
| `server-shared/.../util/ParcelFileDescriptorUtil.java` | Both | Modify |
| `server/src/.../AxeronService.kt` | Axora | Modify |
| `server/src/.../AxeronConfigManager.java` | Axora | Modify |
| `api/src/.../AxeronNewProcess.java` | Axeron-API | Modify |
| `api/src/.../Axeron.java` | Axeron-API | Modify |
| `AGENTS.md` | Both | Modify |

## Expected Impact

- **RAM at startup**: Reduced by ~40-60% from lazy config loading (eliminates full package scan + PermissionManagerApis calls)
- **Command spam resilience**: Bounded to 20 concurrent processes, max 32 transfer threads, FDs properly cached/closed
- **Post-crash stability**: `clearCache()` on binder death ensures clean reconnect state
- **Memory-aware**: Auto-reduces concurrency under memory pressure
