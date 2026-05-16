package frb.axeron.server.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ProcessPoolManager {

    private static final String TAG = "ProcessPoolManager";

    private static final int DEFAULT_MAX_CONCURRENT = 20;
    private static final long DEFAULT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    private final Semaphore semaphore;
    private final Map<Process, Long> activeProcesses = new ConcurrentHashMap<>();
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    private volatile int maxConcurrent;
    private volatile long defaultTimeoutMs;

    private static volatile ProcessPoolManager instance;

    public static synchronized ProcessPoolManager getInstance() {
        if (instance == null) {
            instance = new ProcessPoolManager(DEFAULT_MAX_CONCURRENT, DEFAULT_TIMEOUT_MS);
        }
        return instance;
    }

    public static synchronized void reset() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
        }
    }

    private ProcessPoolManager(int maxConcurrent, long defaultTimeoutMs) {
        this.maxConcurrent = maxConcurrent;
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public boolean acquireProcess(String label) {
        return acquireProcess(label, defaultTimeoutMs);
    }

    public void acquireProcessBlocking(String label) {
        try {
            semaphore.acquire();
            Log.d(TAG, "Acquired process slot for: " + label + " (" + getActiveCount() + "/" + maxConcurrent + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while acquiring process slot for: " + label);
        }
    }

    public boolean acquireProcess(String label, long timeoutMs) {
        try {
            if (semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.d(TAG, "Acquired process slot for: " + label + " (" + getActiveCount() + "/" + maxConcurrent + ")");
                return true;
            }
            Log.w(TAG, "Failed to acquire process slot for: " + label + " (timeout after " + timeoutMs + "ms)");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while acquiring process slot for: " + label);
            return false;
        }
    }

    public void registerProcess(Process process) {
        activeProcesses.put(process, System.currentTimeMillis());
        scheduleTimeoutCheck(process);
    }

    public void cancelAcquire() {
        semaphore.release();
        Log.d(TAG, "Cancelled acquired slot. Available: " + getAvailableSlots() + "/" + maxConcurrent);
    }

    public void releaseProcess(Process process) {
        if (!activeProcesses.containsKey(process)) {
            Log.w(TAG, "releaseProcess called for untracked process, ignoring");
            return;
        }
        activeProcesses.remove(process);
        semaphore.release();
        Log.d(TAG, "Released process slot. Active: " + getActiveCount() + "/" + maxConcurrent);
    }

    public int getActiveCount() {
        return activeProcesses.size();
    }

    public int getAvailableSlots() {
        return semaphore.availablePermits();
    }

    public void setMaxConcurrent(int max) {
        if (max <= 0) throw new IllegalArgumentException("max must be > 0");
        int old = this.maxConcurrent;
        this.maxConcurrent = max;
        if (max > old) {
            semaphore.release(max - old);
        }
        Log.d(TAG, "Max concurrent changed: " + old + " -> " + max);
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void shutdown() {
        timeoutHandler.removeCallbacksAndMessages(null);
        for (Process process : activeProcesses.keySet()) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }
        activeProcesses.clear();
        semaphore.drainPermits();
        for (int i = 0; i < maxConcurrent; i++) {
            semaphore.release();
        }
    }

    public void cleanupTimedOut() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Process, Long> entry : activeProcesses.entrySet()) {
            if (now - entry.getValue() > defaultTimeoutMs) {
                Log.w(TAG, "Cleaning up timed-out process");
                try {
                    entry.getKey().destroy();
                } catch (Exception ignored) {
                }
                releaseProcess(entry.getKey());
            }
        }
    }

    private void scheduleTimeoutCheck(final Process process) {
        timeoutHandler.postDelayed(() -> {
            if (activeProcesses.containsKey(process)) {
                long elapsed = System.currentTimeMillis() - activeProcesses.get(process);
                if (elapsed > defaultTimeoutMs) {
                    Log.w(TAG, "Process timed out after " + elapsed + "ms");
                    try {
                        process.destroy();
                    } catch (Exception ignored) {
                    }
                    releaseProcess(process);
                }
            }
        }, defaultTimeoutMs);
    }
}
