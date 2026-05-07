package frb.axeron.server.util;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class PipeTransferPool {

    private static final String TAG = "PipeTransferPool";
    private static final String THREAD_NAME_PREFIX = "PipeTransfer-";

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 32;
    private static final long KEEP_ALIVE_SECONDS = 30;
    private static final int QUEUE_CAPACITY = 128;

    private static volatile ExecutorService executor;

    public static synchronized ExecutorService getExecutor() {
        if (executor == null) {
            ThreadFactory threadFactory = new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, THREAD_NAME_PREFIX + count.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            };

            RejectedExecutionHandler fallbackHandler = (r, e) -> {
                Log.w(TAG, "Pool saturated, executing in caller thread");
                try {
                    r.run();
                } catch (Exception ex) {
                    Log.e(TAG, "Fallback execution failed", ex);
                }
            };

            executor = new ThreadPoolExecutor(
                    CORE_POOL_SIZE,
                    MAX_POOL_SIZE,
                    KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                    threadFactory,
                    fallbackHandler
            );
        }
        return executor;
    }

    public static void submit(Runnable task) {
        getExecutor().submit(task);
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    public static int getActiveCount() {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getActiveCount();
        }
        return 0;
    }

    public static int getQueueSize() {
        if (executor instanceof ThreadPoolExecutor) {
            return ((ThreadPoolExecutor) executor).getQueue().size();
        }
        return 0;
    }
}
