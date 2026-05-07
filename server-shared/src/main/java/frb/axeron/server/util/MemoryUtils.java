package frb.axeron.server.util;

import android.os.Debug;

public class MemoryUtils {

    private static final long LOW_MEMORY_THRESHOLD = 64L * 1024 * 1024;
    private static final long VERY_LOW_MEMORY_THRESHOLD = 32L * 1024 * 1024;

    public enum PressureLevel {
        LOW, MEDIUM, HIGH
    }

    public static long getMaxMemory() {
        return Runtime.getRuntime().maxMemory();
    }

    public static long getTotalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    public static long getFreeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    public static long getUsedMemory() {
        return getTotalMemory() - getFreeMemory();
    }

    public static long getAvailableMemory() {
        return getMaxMemory() - getUsedMemory();
    }

    public static PressureLevel getMemoryPressureLevel() {
        long available = getAvailableMemory();
        if (available < VERY_LOW_MEMORY_THRESHOLD) {
            return PressureLevel.HIGH;
        } else if (available < LOW_MEMORY_THRESHOLD) {
            return PressureLevel.MEDIUM;
        }
        return PressureLevel.LOW;
    }

    public static void logMemoryState(String tag) {
        PressureLevel level = getMemoryPressureLevel();
        Logger logger = new Logger(tag);
        logger.i("Memory state: used=%dMB, total=%dMB, max=%dMB, available=%dMB, pressure=%s",
                getUsedMemory() / (1024 * 1024),
                getTotalMemory() / (1024 * 1024),
                getMaxMemory() / (1024 * 1024),
                getAvailableMemory() / (1024 * 1024),
                level);
    }

    public static long getNativeHeapSize() {
        return Debug.getNativeHeapSize();
    }

    public static long getNativeHeapAllocatedSize() {
        return Debug.getNativeHeapAllocatedSize();
    }

    public static long getNativeHeapFreeSize() {
        return Debug.getNativeHeapFreeSize();
    }
}
